# 测试与审核报告 — flux-migration 拓扑迁移状态机

- 环境：macOS / rustc 1.97.1 (8bab26f4f 2026-07-14) / cargo 1.97.1 (c980f4866 2026-06-30)，均来自 Homebrew
- 命令（全部离线、锁定依赖，未联网、未安装任何东西）：
  - `cargo fmt --check` → 干净（本次新增代码已 `cargo fmt` 落盘）
  - `cargo clippy --offline --locked --all-targets` → 0 warning / 0 error
  - `cargo test --offline --locked` → **20 passed / 0 failed**（lib 0 + bin 0 + 集成 20 + doc 0）
  - `cargo run --offline --locked` → demo 跑通两跳多候选拓扑，停在 `MANUAL_REVIEW`（旧监听缺归属证据，符合 fail-closed 设计）
- 依赖仍只有 `serde 1.0.229` + `serde_json 1.0.154`（`--locked` 未变）
- **本文所有结论只来自纯逻辑状态机自测**：没有连接数据库、没有连接 GOST、没有发起任何网络请求；
  `Receipt` 全部由测试注入，因此「节点真实行为」「真实超时分布」「真实无中断」都**没有被验证**，也不能据此声称。
- 测试生成物保留：`target/`（含 `target/debug/deps/topology_migration-*`）未删除，可复跑。

## 1. 本轮审核发现并修复的阻断问题

对照 `docs/local-migration-contract.md` 与 `origin/beta` 源码（`TunnelServiceImpl` / `GostUtil` / `ForwardServiceImpl`）静态阅读，
旧原型（14 测试）存在 5 类架构级偏差，已全部重写修正：

| # | 旧原型的错误 | 现在的事实 | 证据（测试） |
| --- | --- | --- | --- |
| 1 | 单一全局 `relay_port`；`relay_nodes()` 对节点名 sort+dedup（丢跳序/候选/多入口）；对所有 transit+exit 都 `AddChains`+`AddListener`；服务名 `<tunnel>-<forward>-<proto>` | `Topology{entries,hops:Vec<Vec<Candidate>>,exits}`；入口+每跳中继各自 `AddChains{next=下一跳候选}`，出口只 `AddListener{chained:false}`；每节点自带端口；服务名 `<forward>_<user>_<userTunnel>_<proto>` | `entries_and_relays_chain_to_next_hop_while_exits_only_listen`、`hop_order_follows_topology_order_not_node_names`、`each_relay_and_exit_uses_its_own_listener_port`、`service_names_share_one_chain_and_keep_per_entry_ports` |
| 2 | 缺能力时 `ActionStatus::Skipped` 且阶段照常推进（假通过）；无能力也能给 `verified` | 缺能力 → `Blocked` + `CapabilityGap`，推进得 `CapabilityBlocked`；`verified` 需 `QueryServiceState`，否则 `EvidenceUnproven`；`NodeCapabilities::legacy()` 为空集（不假称旧 GOST 有 ProbePort/queryServiceState） | `missing_capabilities_block_instead_of_passing_silently`、`verified_evidence_requires_declared_query_capability` |
| 3 | 动作只有数组顺序，无依赖屏障；补偿无顺序 | `Action.depends_on` + `dispatch` 的 `DependencyUnsettled`；PROVISIONING 反向铺开（出口→跳→入口），CUTOVER 依赖入口链；补偿按 `Service(3)→Listener(2)→Chain(1)` 且只依赖紧邻上一层 | `dependency_barriers_gate_downstream_before_entry_chain`、`rollback_deletes_services_then_listeners_then_chains`、`rollback_only_compensates_confirmed_resources` |
| 4 | 迟到失败不进回滚（`TimeoutUnconfirmed` 不算 active）；`exists` 被当成功；旧拓扑按名字删除；无「待核查」态 | `triggers_rollback` 覆盖 `Preparing/Provisioning/Cutover` + 超时解析后仍回滚；`Exists` → `Conflict`（非归属、禁删、阻断补偿）；`attest_legacy` + `LegacyState` 四态；无可证明归属则 `MANUAL_REVIEW`，`verify_completion` 拒绝假 COMPLETED | `two_timeouts_then_late_failure_and_success_still_roll_back`、`late_success_alone_keeps_the_operation_pending`、`exists_reply_is_a_conflict_not_ownership`、`cleanup_rejects_new_topology_and_unattested_legacy`、`legacy_shared_with_new_topology_is_never_cleaned`、`unproven_legacy_stops_in_manual_review_instead_of_completed` |
| 5 | 无输入校验；重复节点/端口 0/空跳/空入口都能构造 | `MigrationPlan::validate()`：入口/出口非空、每跳非空、全隧道节点唯一（beta 禁重复 ⇒ 不可折返）、端口非 0、forward id 唯一、协议非空不重复、每个 forward 的入口端口节点集合必须等于拓扑 entries | `rejects_folded_back_and_duplicated_nodes`、`rejects_zero_ports_empty_hops_and_bad_forward_ports` |

另外修掉两处**引擎真实缺陷**（不是测试写错）：
1. `mark_not_created` 只更新已有记录 → 从未确认过的资源在补偿报告里凭空消失；现补一条 `provision_confirmed=false` 记录（含与旧拓扑同名的键，仍由归属证据决定删不删）。
2. 一进入 `RollbackPending` 就因 `complete==true` 立刻翻 `DEGRADED`，导致之后 `compensate()` 被判 `CompensationUnavailable`；现只有真正跑过一轮补偿（`compensation_started`）才允许收口。

## 2. 覆盖矩阵（tests/topology_migration.rs，1193 行 / 20 个测试）

| # | 行 | 测试 | 断言要点 |
| --- | --- | --- | --- |
| 1 | 279 | `rejects_folded_back_and_duplicated_nodes` | 同一节点出现在两跳 / 入口与出口重复 → `InvalidPlan`（beta 禁重复 nodeId，不可折返） |
| 2 | 298 | `rejects_zero_ports_empty_hops_and_bad_forward_ports` | 端口 0、空跳、空入口/出口、forward 缺入口端口、协议重复 → 全部拒绝 |
| 3 | 352 | `entries_and_relays_chain_to_next_hop_while_exits_only_listen` | 入口 `AddChains.next` = 第一跳候选；中继 `next` = 下一跳；`hop` 序递增；出口 `chained=false` 且没有 `AddChains` |
| 4 | 433 | `hop_order_follows_topology_order_not_node_names` | 跳序按列表（`zz-relay` 在 `aa-relay` 前），不因节点名排序 |
| 5 | 460 | `each_relay_and_exit_uses_its_own_listener_port` | 3 个下游节点各自端口（30001/30002/30003）；不同节点可复用同一端口号；无全局 `relay_port` |
| 6 | 500 | `service_names_share_one_chain_and_keep_per_entry_ports` | 服务名 `1_7_0_tcp`/`1_7_0_udp`/`2_7_11_tcp`；同规则两协议同端口；不同入口（entry-a 20001 / entry-b 21001）端口不同；同一隧道共享 `chains_tun9` |
| 7 | 565 | `missing_capabilities_block_instead_of_passing_silently` | legacy 节点 → `Blocked` + gap；`dispatch` 拒绝 `CapabilityBlocked`；`advance_to` 被 `CapabilityBlocked` 挡住（fail closed，不是跳过） |
| 8 | 627 | `verified_evidence_requires_declared_query_capability` | 未声明 `QueryServiceState` 的 `Receipt::verified` → `EvidenceUnproven`，状态不变 |
| 9 | 675 | `dependency_barriers_gate_downstream_before_entry_chain` | 入口链在下一跳监听 confirmed 前 `DependencyUnsettled{blocking=2}`；`dispatch_all` 波次 `[1,2,2,1]`（出口→跳内链→跳内监听→入口链） |
| 10 | 740 | `exists_reply_is_a_conflict_not_ownership` | `exists` → `Conflict`、进 `RollbackPending`、`Blocker{ConflictUnproven}`、不为该资源生成删除动作 |
| 11 | 806 | `two_timeouts_then_late_failure_and_success_still_roll_back` | 两动作超时 → 未决；一迟到成功（回 `TimeoutUnconfirmed` 保持未决）+ 一迟到失败 → 可靠进回滚；补偿 7 条待删、迟到失败那条只进 `not_created` |
| 12 | 901 | `late_success_alone_keeps_the_operation_pending` | 只有一个迟到成功、另一个仍无音讯 → 仍 `TimeoutUnconfirmed`，不提前放行 |
| 13 | 941 | `rollback_deletes_services_then_listeners_then_chains` | 链删除依赖 3 个监听；波次 `[3,3,3]`；结束 `DEGRADED`（不是 COMPLETED） |
| 14 | 1000 | `rollback_only_compensates_confirmed_resources` | 未确认失败的服务不在删除清单；`pending.len()==7`；`not_created` 含失败服务 |
| 15 | 1060 | `cleanup_rejects_new_topology_and_unattested_legacy` | `Cleanup` 阶段：新拓扑键 `ActiveInNewTopology`、未知键 `NotLegacyTarget`、无证 `LegacyNotAttested`、类型不符 `LegacyKindMismatch`、已释放 `AlreadyReleased` |
| 16 | 1143 | `legacy_shared_with_new_topology_is_never_cleaned` | 与新拓扑同名/同键的旧资源 `SharedWithNewTopology`，`request_cleanup` 拒绝；清理完仍 `COMPLETED` 且新拓扑资源未 `released` |
| 17 | 1206 | `unproven_legacy_stops_in_manual_review_instead_of_completed` | 旧资源无归属证据 → `advance_to(Completed)` 报 `LegacyUnresolved`；`stop_for_review()` → `MANUAL_REVIEW` |
| 18 | 1249 | `receipt_validation_checks_identity_attempt_and_clock_order` | 别 op/未知动作/节点不符/attempt 不符/未下发/已终结/重复/时钟早于下发/未声明能力的 verified → 各自拒绝值，且状态不变 |
| 19 | 1340 | `snapshot_roundtrip_preserves_state_and_rejects_tampering` | 往返保阶段/attempts/台账；篡改 `next_action_id`、schema、`depends_on` 指向不存在 id → 恢复被拒 |
| 20 | 1411 | `happy_path_reaches_completed_with_verified_cutover` | 全流程到 `COMPLETED`；CUTOVER 必须 verified；终态后补偿 `CompensationUnavailable` |

## 3. demo 证据（`cargo run --offline --locked` 输出摘录）

```
op=demo-op tunnel=tun9 chain=chains_tun9 listener=tun9_tls
  service 1_7_0_tcp    proto=tcp
  service 1_7_0_udp    proto=udp
legacy entry-a:1_7_0_tcp 与新拓扑共用，保留
legacy entry-a:chains_tun9 与新拓扑共用，保留
legacy old-exit:tun9_tls 缺少归属证据，停在待核查
phase=MANUAL_REVIEW
  #3   PROVISIONING     exit-e     add listener tun9_tls:30003 (no chain)
  #4   PROVISIONING     relay-d    add chain chains_tun9 (hop 2 -> [exit-e])
  #6   PROVISIONING     relay-b    add chain chains_tun9 (hop 1 -> [relay-d])
  #10  PROVISIONING     entry-a    add chain chains_tun9 (hop 0 -> [relay-b, relay-c])
  #11  CUTOVER          entry-a    add service 1_7_0_tcp port 20001 (tcp) verified
capability gaps: 0
```

可见：出口只 `add listener (no chain)`；中继 `add chain` + 监听；入口 `hop 0 -> [relay-b, relay-c]`（多候选原样交给节点）；
各节点端口不同（30003/30004/30001/30002）；DEMO 因旧监听无归属证据停在 `MANUAL_REVIEW`，没有假 COMPLETED。

## 4. 本轮自测中发现并已修正的问题（诚实记录）

- 6 条断言在首次全量跑时失败，其中 **1 条是引擎缺陷**（`mark_not_created` 丢记录）、**1 条是状态机缺陷**（提前翻 `DEGRADED`），
  其余 4 条是测试自身写错（把 pending 当 started、把入口链依赖数写成 3 而实际是 2、Cutover 动作尚未生成就去查、用 attempt=1 查未下发状态）。
  逐条修复后 20/20 通过，未通过删除/放宽断言的方式掩盖问题。

## 5. 未解决 / 未验证（不得据此声称的能力）

1. **真实执行器缺失**：不连 GOST / 不连 DB，节点成功率、超时率、乱序概率全部未知。
2. **无零中断保证**：先下游再入口只是顺序约束；入口切换瞬间的断流窗口未被消除，也未被测量。
3. **原子端口预留不存在**：`ReservePort` 仅是台账占位，没有「预留即独占」的节点能力。
4. **状态确认能力**：旧 GOST 未证实 `ProbePort` / `queryServiceState`，因此 legacy 节点无法提供 verified 证据。
5. **归属证明是入参**：`attest_legacy(source)` 不校验来源真实性，真实实现必须从持久记录推导。
6. **候选选路不在编排层**：与 beta 一致，只把候选与 strategy/maxFails 交给节点，无健康探测/主动切换。
7. **共享引用清单**：`SharedWithNewTopology` 目前只覆盖「键与新拓扑相同」；多条旧隧道共用同一 relay 监听
  这类跨操作共享，需要数据库侧引用计数，本原型未建模（只是不删它也算保守）。
8. beta 的已知静态缺陷（`AddChains` 结果被空 `isError` 吞、`ForwardServiceImpl` 清理循环内 `return`、
  暂停传同一服务名、端口分配非原子）属 **Java 侧待修**，本原型只保证自己不在编排层重复这些错误，未改 Java 代码。

## 6. 第二轮回归（2026-11-07）：QA 静态问题 → 先写失败用例再修

新增 `tests/qa_regressions.rs`（7 条），先确认它们在**未修复的实现上失败**，再改 `src/engine.rs`。

### 6.1 全量结果

```
CARGO_TARGET_DIR=/Volumes/ssd1t/flux-panel/compat-beta-tests/qa-fix.20261107T0955/target \
  cargo test --offline
→ tests/qa_regressions.rs       7 passed; 0 failed
→ tests/topology_migration.rs  25 passed; 0 failed
→ lib/bin/doc                   0 passed; 0 failed
cargo fmt --check              → clean
cargo clippy --offline --all-targets → 3 warning（全是 result_large_err：CleanupRejection/EngineError 变体偏大，既有风格，未改）
cargo run --offline            → demo 正常，终态 MANUAL_REVIEW（旧监听缺归属证据，无假 COMPLETED）
```

### 6.2 变异验证（证明回归真的盯着修复点）

在 `compat-beta-tests/qa-fix.20261107T0955/mutants/` 里复制本 crate，逐条把修复**反向**改回旧行为，再跑 `qa_regressions`：

| 变异 | 反向改动 | 失败用例 |
| --- | --- | --- |
| m1 | 清理依赖 `kind.layer() > layer` → `== layer + 1` | `entry_chain_waits_for_services_even_without_a_listener_layer`、`cleanup_refuses_to_delete_below_an_unproven_upper_resource` |
| m2 | 去掉 `compensate` 里「旧拓扑同键跳过」 | `compensation_keeps_legacy_same_name_resources` |
| m3 | `on_timeout` 不再为首次下发的资源补建台账 | `timeout_on_first_dispatch_leaves_a_reportable_resource` |
| m4 | `preflight_conflict` 直接返回 `None` | `attested_name_conflict_blocks_before_dispatch` |
| m5 | 去重改回「只比 success」 | `same_attempt_conflicting_receipt_is_rejected_not_deduped` |

即：每条回归都恰好被它对应的修复点钳住，没有一条是"随便都过"的摆设。

### 6.3 处理旧测试的 4 条断言

修复改变了 `base_plan` 的回滚语义（入口链/入口服务属于旧拓扑同键资源，不再删），4 条旧断言随之调整，
**都是收窄而不是放宽**：`pending` 7→5 / 9→5、`dep_count` 3→1（按节点裁剪）、`waves` `[3,3,3]`→`[3,2]`、
`delete_failure_keeps_ownership_and_stays_retryable` 改用本操作新建的 relay-b 监听做样本（原样本已不可删）。
另外补了 `report.preserved` 的逐条断言，避免"静默跳过"被当成"什么都没发生"。

详细说明见 `REVIEW-FIX.worker-coder.md` 第 6、7 节。产物与日志在
`/Volumes/ssd1t/flux-panel/compat-beta-tests/qa-fix.20261107T0955/`。
