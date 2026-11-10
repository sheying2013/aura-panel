# flux-migration：资源身份 / 清理 DAG / 时钟 / 终态 / 未知超时 —— 独立复核与修复

复核对象：`/Volumes/ssd1t/flux-panel/rust-migration/`（flux-migration 0.1.0，仅依赖 serde/serde_json）。
约束：不接真实节点/DB、不联网、不删改旧生成物（target/ 与既有 TEST-REPORT.md 未动）、不提交；编译产物写入新建 `verify-target.W1/`。
工具链：rustc 1.97.1，`--offline --locked`。

## 1. 对 QA 5 项发现的独立判定

| # | QA 发现 | 判定 | 依据 | 处置 |
|---|---|---|---|---|
| 1 | 资源身份与属性未区分 | 部分成立，但**建议方向错误** | GOST 以「节点 + 名字」注册对象，端口/协议是属性。同名不同端口 = 同名冲突，不能当成两个可共存资源 | 新增 `LegacyState::NameConflict { new_kind }`；**拒绝**"当独立资源"的建议 |
| 2 | 旧资源清理 DAG 有缺口 | 成立 | 原 `request_cleanup` 只对"已存在"的上层动作连边，先请求拆链会得到空依赖 → 链先于监听删除 | DAG 改为按层次递归补齐上层删除动作，依赖真实动作 ID |
| 3 | 节点时钟参与排序 | 成立，且原行为会**丢真实回执** | 原 `apply_receipt` 在 `node_time_ms < dispatched_at_ms` 时返回 `ClockSkew` 拒绝；分布式时钟不可比，不能用于认证/因果排序 | 删除 `ReceiptRejection::ClockSkew`；节点时间只写审计字段，不参与任何判断 |
| 4 | 终态/未收口仍可下发 | 成立 | `dispatch`/`dispatch_all`/`on_timeout` 没有任何阶段门禁 | 加 `Phase::is_frozen()`（TimeoutUnconfirmed 或终态） |
| 5 | 未知结果被当失败删除 | 成立，真正缺陷在 delete 回执 | delete 动作回 `Failure` 时走 `mark_not_created`，把仍在的自有资源标成"从未创建"，归属被抹掉，补偿报告谎报 | delete 失败改走 `mark_delete_failed`：保留归属、只撤隔离 |

## 2. 修复清单

### src/state.rs
- `ResourceKey`（state.rs 身份语义注释）：身份 = (node, name)，端口/协议/类型只是属性；GOST 按名字注册，同名不同端口 = 同名冲突。
- `LegacyState` 增 `NameConflict { new_kind: ResourceKind }`（state.rs:458）。
- `Phase::is_frozen()`（state.rs:55）：`TimeoutUnconfirmed | is_terminal()`。
- `AppliedReceipt` 增 `#[serde(default)] node_time_ms: i64`（state.rs:425）：只做审计，不参与认证/排序。

### src/error.rs
- 删 `ReceiptRejection::ClockSkew`。
- `EngineError::LegacyNameConflict { keys: Vec<ResourceKey> }`（error.rs:63）、`EngineError::PhaseFrozen { phase: Phase }`（error.rs:67），中文 Display（error.rs:148/153）。
- `CleanupRejection::NameConflictInNewTopology(ResourceKey)`（error.rs:264）。

### src/engine.rs
- `SCHEMA_VERSION: u32 = 3`（engine.rs:16）。
- `set_clock`（engine.rs:113）：只约束本机单调时间，注释明确不与节点时钟比较。
- `verify_completion`（engine.rs:243）：先收集 `name_conflicts`，在 `LegacyUnresolved` 之前返回 `LegacyNameConflict`；只有新拓扑存在同名同属性对象才算 `SharedWithNewTopology`。
- `legacy_state`（engine.rs:916）：比较新旧 `ResourceKind` 属性，不同即 `NameConflict`。
- `request_cleanup`（engine.rs:963）+ `ensure_cleanup_action`（engine.rs:982）：`Shared/NameConflict/Released` 分别拒绝；`Failed` 原地重置为 `Pending` 重试同一条；`Unconfirmed` 返回 `OutcomeUnconfirmed`；`Pending/Dispatched` 幂等返回原 ID。
- `cleanup_dependencies`（engine.rs:1024）：递归取「同节点 + 紧邻上一层 + 已 attest」的旧资源删除动作；跨节点依赖被裁掉——链/监听是节点本地对象，一台机清理失败不该卡住整批。
- `dispatch`/`dispatch_all`（engine.rs:527）/`on_timeout`（engine.rs:572）：冻结门禁。单个 `dispatch` 冻结时返回 `PhaseFrozen`；`dispatch_all` 是排空原语，冻结/阶段不符返回 `Ok(vec![])`（否则"dispatch 到空即结束"的循环会被终态卡住）；`on_timeout` 只挡终态（同阶段多条在飞动作各自记录超时是合法事实，不能丢）。
- `apply_receipt`（engine.rs:633）：删除时钟比较；`Failure` 分支按 `is_delete()` 分流为 `mark_delete_failed` / `mark_not_created`。
- `ensure_provisioned`（engine.rs:772）：同 key 不同 kind 不再静默改写属性，改记 `conflicted = true, provision_confirmed = false`。
- `mark_delete_failed`（engine.rs:864）：撤隔离（`quarantined_legacy`/`quarantined`），保留 `provision_confirmed`。
- `compensate`（engine.rs:1056）：先归位已有 `RollbackPending` 动作（`Failed → Pending` 原地重试），只对缺失者新建并按层连边。

### src/main.rs
- demo 的 `legacy_targets` 匹配补 `LegacyState::NameConflict` 分支（打印后 `stop_for_review`）。

## 3. 回归测试（tests/topology_migration.rs，20 → 25 条）

新增：
1. `cleanup_dag_follows_layers_not_request_order`：先请求拆链，链删除必须等监听删除（`DependencyUnsettled`），波次 `[2,1]`，与请求顺序无关；跨节点监听不互相牵连。
2. `same_name_different_port_is_a_conflict_not_a_coexisting_resource`：监听 30009→30010、服务 20002→20012 均判 `NameConflict`；`request_cleanup` 回 `NameConflictInNewTopology`；`advance_to(Completed)` 回 `LegacyNameConflict { keys: [entry-a:2_7_11_tcp, old-exit:tun9_tls] }` 且阶段停在 Cleanup。
3. `unconfirmed_and_terminal_phases_refuse_new_dispatch`：TimeoutUnconfirmed 下 `dispatch` 拒绝、`dispatch_all` 空；同阶段第二条在飞动作超时可记录（`settle_report.unconfirmed == 2`）；ManualReview 下 `dispatch`/`on_timeout` 均拒绝且阶段不被改写。
4. `delete_failure_keeps_ownership_and_stays_retryable`：删除失败 → 报 `failed`、不进 `not_created`、`provision_confirmed` 保持 true、`unknown_results` 为空；`compensate` 复用同一 ID 重试并最终 `Degraded`。
5. `failed_legacy_cleanup_is_retried_in_place`：旧资源删除失败留在 Cleanup（不回滚新拓扑），重试复用同一 ID，成功后 `Released` → `Completed`。

改写：
- `receipt_validation_checks_identity_and_attempt_not_node_clock`：节点时间 500（早于本机 1000）现在接受为 `Applied`，`applied()[0].node_time_ms == 500`；本机时钟回拨仍 `ClockRegression`。
- `happy_path_reaches_completed_with_verified_cutover`：清理改为"先登记全部请求，再按波次下发"，断言 `waves == [2,1]`；终态 `dispatch` → `PhaseFrozen`，`dispatch_all` → 空。
- `rollback_deletes_services_then_listeners_then_chains`：依赖排空语义下仍 `[3,3,3]`。

## 4. 实测

```
CARGO_TARGET_DIR=verify-target.W1 cargo test --offline --locked      → 25 passed; 0 failed（lib/bin/doc 各 0）
cargo clippy --offline --locked --all-targets -- -D warnings         → 0 warning
cargo fmt --check                                                    → clean
CARGO_TARGET_DIR=verify-target.W1 cargo run --offline --locked       → demo 正常，终态 MANUAL_REVIEW（旧监听缺归属证据）
```
修复过程中被新回归抓出的实现问题（已修）：
- `dispatch_all` 一刀切返回 `PhaseFrozen` 会让"dispatch 到空为止"的排空循环在 Degraded 阶段直接失败 → 改为返回空。
- `on_timeout` 一刀切冻结会禁止同阶段第二条在飞动作记录超时（丢失未知结果）→ 只挡终态。
- 清理依赖原先按层全局连边，导致跨节点监听被串进链删除依赖（`dep_count` 期望 1 实际 2）→ 按节点裁剪。

## 5. 残余缺陷（未修，需 PM 决策）

1. **两套 DAG 作用域不一致**：清理按「同节点」裁剪依赖，`compensate`（engine.rs:1056）仍按层次全局连边——不同节点的链会等所有监听删完。方向保守（不会早删），但会串行化；现有测试 `rollback_deletes_services_then_listeners_then_chains` 锁定了 `dep_count == 3`。
2. **快照无迁移**：`SCHEMA_VERSION` 2→3，旧快照会被 `validate_snapshot` 以中文 `Snapshot` 错误拒绝，没有 v2→v3 升级函数。
3. **回滚自身超时无旁路**：回滚删除超时后阶段进入 `TimeoutUnconfirmed`，此时 `compensate` 一律拒绝（`UnconfirmedOutcome`），没有"只隔离该资源、继续其它节点"的通道，必须等迟到回执或人工。
4. **同名冲突只挡不修**：引擎挡住收尾与删除，但不提供改名/迁移动作，只能人工处理。
5. **删除重试无退避/上限**：重试由调用方决定，引擎不封顶尝试次数。
6. **attest_legacy 未挡冲突键**：对 `NameConflict` 键仍可登记归属证据（不改变 `legacy_state` 结论，语义上多余）。
7. **验证边界**：全部为内存状态机验证，未与真实 GOST/数据库联调（按任务约束不接环境）；因此"零中断"仍未被证明。

## 6. 第二轮：QA 静态问题核实与修复（2026-11-07）

先写「在旧实现上必须失败」的回归（`tests/qa_regressions.rs`，7 条），再改 `src/engine.rs`；
5 项 QA 已逐条核实，其中 5 项全部真实存在、已修，另记录 2 项未建模（第 7 节）。

### 6.1 cleanup_dependencies 只查 layer+1（QA1）

复现：入口 chain 是 layer 1、入口 service 是 layer 3，中间**没有 listener（layer 2）**。
旧实现 `kind.layer() == layer + 1` 收集不到 service，`request_cleanup(chain)` 直接把链删了，
而入口服务还在被新拓扑使用；同理，上层若是 `Unattested`，旧实现根本看不到它，于是"跳过未证明可删的上层、删下面的"。

修复（engine.rs:1112-1160）：
- 依赖改为 `kind.layer() > layer && key.node == requested.node` 的**全部**更上层（同节点），按 layer 降序、key 升序稳定排序；
- **第一遍整轮校验**：`Attested`/`Released` 放行；`Unattested`/`SharedWithNewTopology`/`NameConflict` 立即返回
  `CleanupRejection::DependencyNotDeletable { key, blocker, blocker_state }`；
- 第二遍才真正 `ensure_cleanup_action`，所以拒绝时**一条动作都不建**（不留半截 DAG）。

回归：`entry_chain_waits_for_services_even_without_a_listener_layer`（层 2 空缺时链必须等 service）、
`cleanup_refuses_to_delete_below_an_unproven_upper_resource`（上层 unattested → 拒绝，且不产生删除动作）。

### 6.2 compensate 仅凭 provision_confirmed 就删旧同名资源（QA2）

复现：旧 topology 已有 `entry-a:1_7_0_tcp`，新拓扑同一键再 add 一次并拿到 Success 回执，旧实现把它当"本操作创建"生成 `DeleteService`；
回滚时把旧资源一起打掉。

修复（engine.rs:1193 旧同键跳过；engine.rs:1250-1300 preserved / LegacyNameConflict 分支）：
- 候选过滤加 `if self.legacy_kind(&record.key).is_some() { continue; }`：**旧拓扑同键一律不进删除候选**；
- `CompensationReport` 新增 `preserved: Vec<PreservedCompensation>`：同 kind → `PreserveReason::LegacyShared`，
  异 kind → `BlockReason::LegacyNameConflict` 进 `blocked`。逐条报告，不再静默跳过；
- 依赖从「层全局连边」改为「同节点 + 更上层」（`BTreeMap<(NodeId, u8), Vec<ActionId>>`），两套 DAG 作用域终于一致。

回归：`compensation_keeps_legacy_same_name_resources`（回滚后 `pending == 1`、`preserved` 恰为三个旧键、旧资源仍 `SharedWithNewTopology`）。

### 6.3 on_timeout 对首次下发的资源不建台账（QA3）

复现：资源记录是在**收到回执时**才补的；第一次下发就超时 → 台账里没有这条 → `unknown_results()` 漏报，
补偿清单也看不到它（既不是 not_created 也不是 unknown）。

修复（engine.rs:657-683）：`on_timeout` 取 `kind.resource_kind()`；旧拓扑键只进 `quarantined_legacy`（不给归属）；
非旧键已有记录 → 置 `quarantined`；都没有 → 补建 `ResourceRecord { provision_confirmed: false, quarantined: true, .. }`。
`unknown_results()` 的 resources 改 `BTreeSet` 去重，并新增 `legacy_overlap` 单列「新旧同名」的未决键，避免被读成本操作新建。

回归：`timeout_on_first_dispatch_leaves_a_reportable_resource`。

### 6.4 同名不同属性应执行前阻断（QA4）

复现：旧实现只在回执回来（Exists/Conflict）之后才发现同名冲突，等于把注定被拒的命令发到节点上。

修复（engine.rs:599-634 preflight_conflict；dispatch / dispatch_all 调用点）：
- 新增 `preflight_conflict(index)`：本操作台账里同键不同 kind，或旧拓扑同键不同 kind **且已具证** → 命中；
- `dispatch()` 命中即 `EngineError::NameConflictBeforeDispatch`，动作置 `Conflict`（`attempt = 0`, reply = "name-conflict"）；
- `dispatch_all()` 只跳过冲突动作，不整轮失败（不空转：动作已不是 Pending）。

回归：`attested_name_conflict_blocks_before_dispatch`。

### 6.5 回执去重丢了 Exists/Failure 的区别（QA5）

复现：去重键是 `(action, attempt, success)`——`Exists` 与 `Failure` 都是 `success = false`，
于是「先 exists 后 failure」被当重复回执吞掉，第一次的结论被覆盖/掩盖。

修复（engine.rs:742-753 去重比对；engine.rs:828-833 记账）：去重比对 `ReceiptOutcome`（`applied.outcome == receipt.outcome`），
`AppliedReceipt` 记录 `outcome` 而不是 `success`；同 attempt 的相反结论不再进 dedup 通道，
而是落到状态校验分支 → `ReceiptRejection::ConflictingOutcome`，且**被拒绝的回执不进 `applied()` 台账**。

回归：`same_attempt_conflicting_receipt_is_rejected_not_deduped`、`dedup_still_rejects_a_second_receipt_after_a_failure`。

### 6.6 其他 QA 项

- **快照**：`SCHEMA_VERSION` 3 → 4（AppliedReceipt 换成 outcome、`UnknownResults.legacy_overlap`、`CompensationReport.preserved`）。
  `validate_snapshot` 增加 fail-closed 归属校验：`released_legacy`/`quarantined_legacy`/`attestations` 必须指向旧拓扑资源，
  资源台账的键必须属于本计划新旧拓扑，`applied` 必须指向真实动作。
  **旧快照不做升级**：旧实现只是原型（从未作为已上线生产运行），schema 3 里只有布尔 `success`、没有 `preserved`/`legacy_overlap`，
  没有任何可靠证据能把它们还原成新语义，硬升级＝凭空造归属，因此维持 fail-closed 拒绝。

## 7. 本轮未完成 / 未支持（不得声称已支持）

1. **同名链的内容未建模**：`ResourceKind::Chain` 只带链名，`preflight_conflict` 无法比较 `next`/hop，
   「同名链但转发目标变了」在执行前查不出来；这类键仍按 `SharedWithNewTopology` 处理（永不主动删，安全但不阻断）。
   要做需要 plan 侧携带链内容摘要 + 比对逻辑，本轮没做。
2. **快照 v3→v4 无升级函数**（理由见 6.6），也没有任何真实迁移证据可用。
3. **跨节点耦合未建模**：两套 DAG 现在都按「同节点 + 更上层」，若某节点的删除实际要求别的节点先完成，图里看不出来。
4. 5 项修复全部只覆盖**内存状态机**；未与真实 GOST/DB 联调，节点是否真会拒绝同名冲突仍未验证（按任务约束不接环境）。
