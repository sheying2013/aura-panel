# flux-migration — 拓扑迁移状态机（纯逻辑原型）

对一个 **beta 版隧道/转发拓扑变更** 做「准备 → 建链 → 切换 → 清理」的编排逻辑，
把「什么时候可以推进、什么时候必须停下、出错时按什么顺序撤销、哪些旧资源允许删」的规则钉死在代码里。

## 0. 边界（先读，避免误用）

| 项目 | 本原型的状态 |
| --- | --- |
| 数据库 / 迁移记录表 | **不连接**。阶段、动作台账、资源归属、已消费回执全在内存，靠 `snapshot()` / `restore()` 落 JSON |
| 真实 GOST / 节点 API | **不连接**。不发起任何网络请求，节点结果只能由调用方构造 `Receipt` 注入 |
| 零中断保证 | **不保证**。先下游后入口的顺序只降低入口中断窗口，是否真正无感取决于节点行为与执行器 |
| 协议扩展 | **不新增**。`ProbePort`、`queryServiceState(requestId)`、持久化去重、原子端口预留都属于「协议扩展 + 能力协商」范畴；旧 GOST 未证实这些能力，所以 `NodeCapabilities::legacy()` 是空集合，相关动作直接 `Blocked` |
| ss / netstat 快照 | 不作为所有权证据。端口只有在节点回执（命令确认）后算「本操作创建」，只有状态查询能力存在时才算「已验证」 |
| 写入范围 | 仅本目录。未改 Java/Go/前端，未切分支、未提交、未联网 |

## 1. 与 beta 拓扑语义的对齐

从 `origin/beta` 的 `TunnelServiceImpl` / `GostUtil` / `ForwardServiceImpl` 静态阅读得到的事实，
本原型逐条建模（不依赖运行 beta）：

1. **每个入口节点、每个中继节点各自 `AddChains`，指向「下一跳候选」**；
   链的 hop 里放的是下一跳的**所有候选**，由节点侧 strategy/maxFails 选择，编排层不做选择。
   `ActionKind::AddChains { chain, next: Vec<Candidate>, hop, selector }` 的 `next` 就是这个候选集合，`hop` 是该跳序号（入口为 0）；
   `selector` 照抄 `GostUtil.CreateChains`（GostUtil.java:97-103）：`{ strategy: <chain_tunnel.strategy>, maxFails: 1, failTimeout: 600000000000 }`，
   `failTimeout` 单位是纳秒。`strategy` 未配置时保留 `None`（空串同义）并在载荷里整键省略——原型不臆造 `round` / `fifo`。
2. **出口只监听，不建链**。`AddListener { chained: false }`；中继是 `AddListener { chained: true }`（监听挂在 chains_<tunnel> 上）。
   入口节点不建监听（入口只建链 + 后续建服务）。
3. **多入口 / 多个有序中间跳 / 每跳多个候选 / 多出口**：`Topology { entries: Vec<NodeId>, hops: Vec<Vec<Candidate>>, exits: Vec<Candidate>, strategy: Option<String> }`（`strategy` = beta `chain_tunnel.strategy`，隧道级单值）。
   `hops` 是 `Vec<Vec<..>>`（外层顺序 = 跳序，内层 = 该跳候选），**跳序只由用列表顺序决定，从不按节点名排序**。
   `candidates_after(index)` 在 `index == hops.len()` 时返回出口候选，用于「最后一跳指向出口」。
4. **每个中继/出口节点有各自的监听端口**：端口写在 `Candidate.port`。不存在全局 `relay_port`，
   两个不同节点用同一端口号是合法的，同一节点两个资源用同一端口由 `validate()` 拒绝（端口 0 同样拒绝）。
5. **beta 禁止重复 nodeId**（同隧道内节点唯一，因此不能折返再经过同一节点）：
   `validate()` 对「入口 ∪ 所有跳候选 ∪ 出口」做唯一性检查，重复即 `InvalidPlan`。
   注意这是**比 beta 更严的下界**：beta 只对真实使用到的节点去重，本原型把候选也算进去，宁可拒绝。
6. **服务命名**：`<forwardId>_<userId>_<userTunnelId>_<proto>`（beta: buildServiceName + GostUtil 拼 proto）。
   `ForwardSpec { forward_id, user_id, user_tunnel_id, protocols, entry_ports, old_entry_ports, status, pause_reason }`；
   `status` 是 beta `forward.status`（1 活跃 / 0 暂停 / -1 异常），`pause_reason` 是 beta `forward.pause_reason`（`expiry` / `disabled` 为自动暂停，`None` 为手动暂停或未标注）；
   同一转发的 tcp/udp **共享同一入口端口**（由 `entry_ports` 里那条端口决定，`AddService` 两个协议各一条动作、端口相同），
   不同入口节点可以给同一转发不同端口（`entry_ports` 是 (节点, 端口) 列表，不是单个端口）。
7. **链名 `chains_<tunnelId>`、监听名 `<tunnelId>_tls`**，同一隧道一条链名（各节点各建一份）；
   `_tls` 不代表 TLS——`AddChainService` 的 listener.type 取的是隧道 protocol，名字是历史包袱。
8. **旧拓扑**同样用 `Topology` 表达（`MigrationPlan.old`），**但不按名字匹配就删**，见第 6 节。

## 2. 状态机

```
DRAFT -> PREPARING -> PROVISIONING -> CUTOVER -> CLEANUP -> COMPLETED
           |             |             |           |
           +-------------+-------------+-----------+--> ROLLBACK_PENDING -> DEGRADED
                              |                             |
                              +--> TIMEOUT_UNCONFIRMED ------+
任意非终态 --(证据不足/无法证明归属)--> MANUAL_REVIEW（终态，绝不冒充 COMPLETED）
```

- `Phase::allowed_next()` 是唯一真值表；`advance_to()` 不满足即 `IllegalTransition`。
- 推进前必须**结算当前阶段**，顺序固定：`UnconfirmedOutcome` → `PhaseHasConflicts` → `PhaseHasFailures`
  → `CapabilityBlocked` → `PhaseNotSettled`（越靠前越"重"，不会因为先看到 pending 就掩盖失败）。
- `RollbackPending` / `ManualReview` 是「放弃当前阶段」的入口，允许存在未结算动作。
- `Completed` 额外要求：CUTOVER 的每条服务动作都有 **verified 证据**（`Evidence::StateVerified`，不是命令 ack），
  且旧拓扑每条资源都被证明「已释放」或「与新拓扑共用」，否则 `CutoverUnverified` / `LegacyUnresolved`。
- `TimeoutUnconfirmed` 没有后继，只能被迟到回执解析回原阶段或进回滚。
- `Completed` / `Degraded` / `ManualReview` 是终态；`CompensationUnavailable` 拒绝在终态补偿。

## 3. 动作、依赖与阶段屏障

```rust
struct Action { op, id, node, phase, kind, resource: Option<ResourceKey>,
                attempt, status, depends_on: Vec<ActionId>, dispatched_at_ms }
```

- `depends_on` 是**可执行依赖**，不是数组顺序：`dispatch()` 先做 `unsettled_dependencies()`，
  只要依赖里有 `Pending` / `Dispatched` / `Unconfirmed` / `Failed` / `Blocked` / `Conflict` 就
  `DependencyUnsettled { action, blocking }`；`dispatch_all()` 一次只发一波（依赖全 settled 的那些）。
- 生成时的屏障（PROVISIONING 反向铺开）：
  出口 `AddListener`（无依赖） → 最后一跳每个候选 `AddChains`（依赖**下一层所有出口监听**）
  → 同节点 `AddListener`（依赖**自己的 AddChains**） → 逐跳往前 → 最后入口 `AddChains`（依赖**第一跳全部监听**）。
  CUTOVER 的 `AddService` 依赖**本入口节点的 AddChains**；
  `status != 1` 的转发在建服务之后追加 `PauseService`（依赖**自己的 `AddService`**），切流阶段永不生成 `ResumeService`。
- 因此「先下游准备、再入口切换」不是靠调用顺序自觉，而是靠依赖图强制：入口链在最后一跳监听 confirmed 之前发不出去。
- 下发 `dispatch(id)` 让 `attempt + 1` 并记 `dispatched_at_ms = clock_ms()`；
  对已下发/未确认的动作再次 `dispatch` 只返回当前 attempt（幂等），不产生第二次真实下发。
下发受阶段门禁：`TimeoutUnconfirmed` 与三个终态（`Completed` / `Degraded` / `ManualReview`）下 `dispatch()` → `PhaseFrozen`，
`dispatch_all()` 是排空原语，冻结或阶段不符时返回空集（不会把「dispatch 到空为止」的循环卡成错误）。

## 4. 能力：缺失即阻断（fail closed）

```rust
enum Capability { AtomicPortReserve, ProbePort, QueryServiceState }
struct NodeCapabilities { node, supported: BTreeSet<Capability> }   // legacy(node) = 空集
```

- `ReservePort` 需要 `AtomicPortReserve`，`ProbePort` 需要 `ProbePort`；生成时缺能力 →
  动作 `ActionStatus::Blocked { capability }` + 一条 `CapabilityGap`，`phase` 原地不动，
  想推进只会拿到 `CapabilityBlocked`。**没有"跳过预检继续走"这条路**。
- `Evidence::StateVerified` 的 `Receipt::verified(..)` 要求该节点声明 `QueryServiceState`，
  否则 `ReceiptRejection::EvidenceUnproven`（旧 GOST 没有 `queryServiceState`，不能假装"确认过状态"）。
- `gaps()` / `unknown_results().gaps` 把缺口原样交给上层，由人决定是升级节点还是改方案。

## 5. 回执校验（顺序固定，任一失败都不改状态）

| # | 条件 | 拒绝值 |
| --- | --- | --- |
| 1 | `receipt.op_id == plan.op_id` | `ForeignOperation` |
| 2 | 动作存在 | `UnknownAction` |
| 3 | `receipt.node == action.node` | `NodeMismatch` |
| 4 | `receipt.attempt == action.attempt` | `AttemptMismatch` |
| 5 | `(action, attempt, outcome)` 未消费过 | `Duplicate`（幂等返回） |
| 6 | verified 证据有对应能力 | `EvidenceUnproven` |
| 7 | 状态是 `Pending` / `Blocked` / 已终结 | `NotDispatched` / `ActionBlocked` / `ConflictingOutcome` |

`node_time_ms` **不参与校验**：各节点时钟不可比，拿它和 `dispatched_at_ms` 排序会丢掉真实回执，
它只被抄进 `AppliedReceipt` 供审计。回执身份只由 op / action / node / attempt 决定。

去重比的是**回执结论**（`ReceiptOutcome`，Success/Exists/Failure），不是布尔 `success`：
`Exists` 与 `Failure` 都「不是成功」，压成布尔就会把同一次尝试里**先 exists 后 failure** 这种自相矛盾的结果
当成重复吞掉。同 attempt 的相反结论 → `ConflictingOutcome`，且**被拒绝的回执不进 `applied()` 台账**，
现场保留第一次的结论（`(action, attempt, outcome)` 三元组仍只有一条）。

三种结果语义：`Success` → `Confirmed` + 资源 `provision_confirmed`（删除类则 `released`）；
`Exists` → `Conflict`（**exists 只证明资源存在，不证明归属本操作**，此类资源既不算创建成功也不许删）；
`Failure` → `Failed`：非删除类资源记「未创建」（若此前从未确认过，也会补一条 `provision_confirmed = false` 的记录，让报告诚实）；
**删除类资源保留归属**（`mark_delete_failed`：只撤隔离，不动 `provision_confirmed`），失败的删除可原地重试，
不会把仍在的自有资源谎报成「从未创建」。

## 6. 超时：未知不是失败

`on_timeout(id, note)` 只接受 `Dispatched`：动作 → `Unconfirmed`，阶段 → `TimeoutUnconfirmed`，
原阶段存 `resume_phase`，资源记 `quarantined`（不删、不释放、不标失败）。此时
`advance_to()` 全失败、`request_cleanup()` → `WrongPhase`、`compensate()` → `UnconfirmedOutcome`、`dispatch()` → `PhaseFrozen`。
同阶段多条在飞动作各自超时都会被记录（第一条超时不会掐掉其余未知结果），只有终态才拒绝 `on_timeout()`。

**首次下发就超时**的动作原本没有任何台账记录（记录是在收到回执时才补的），所以 `on_timeout()` 会按动作的
`ResourceKind` 补建一条 `provision_confirmed = false, quarantined = true` 的记录——否则 `unknown_results()`
会漏报「可能已经建出来、只是没回执」的资源。旧拓扑同键的动作只进 `quarantined_legacy`，不建记录、不给出归属；
`unknown_results().legacy_overlap` 单列这些「新旧同名」的未决键，避免被读成「本操作新建的未知资源」。

迟到回执按 attempt 匹配后被接受：`Success` 解析回 `resume_phase`；`Failure` 转 `RollbackPending`。
**多个超时共存**：只要还有未确认动作，`TimeoutUnconfirmed` 就一直保持未决（`late_success_alone_keeps_the_operation_pending`），
一个迟到成功 + 另一个迟到失败会可靠进入回滚，并把成功那条计为「已创建可补偿」、失败那条计为「未创建」。

## 7. 补偿（回滚）：按层、按证据

`compensate()` 只为本操作 **已确认创建、未隔离、未释放、未冲突** 的资源生成 `Delete*`，
并且 **旧拓扑里先存在的同键资源一律不进删除候选**（`legacy_kind().is_some()` 直接跳过）——
「本次操作也 add 过一次同名对象」不等于「这个对象是本操作的」，`provision_confirmed` 只说明命令被确认，不构成删除授权。顺序由 `ResourceKind::layer()` 决定：

```
Service(3) -> Listener(2) -> Chain(1)
```

即**先删入口 service，再删中继/出口 listener，最后删 chain**（正好是 beta 删除的反向），
且每条删除动作 `depends_on` = **同节点上所有更上层（layer 更大）**的删除动作，不做全局连边：
链只等同节点上的监听删完，去删 relay-b 的链不需要等 exit-d 的监听。
这样既不会早删，也不会把互不相关的节点串行化。

以 `base_plan`（入口 entry-a、中继 relay-b/relay-c、出口 exit-d）为例，入口的链与两条服务是旧拓扑同键资源、
不参与回滚，所以待删只剩 3 个监听 + 2 条链，`dispatch_all` 的波次是 `[3, 2]`。

`CompensationReport { phase, started, pending, failed, blocked, preserved, not_created, complete }`：

- `started` = 已确认的删除动作；`pending` = 还没结算；`failed` = 删除失败（保持 `RollbackPending` 可重试）；
- `blocked` = `OutcomeUnconfirmed`（结果未知）/ `ConflictUnproven`（exists 未归属）/ `NotCreated` / `LegacyNameConflict`；
- `preserved` = 旧拓扑本来就有、回滚**故意不删**的资源（`reason: LegacyShared`），逐条列出而不是静默跳过；
- `not_created` = 从未创建成功的资源，不需要删；
- `complete = pending 空 && failed 空 && blocked 空`；只有**真正跑过一轮补偿**（`compensation_started`）且 complete 才转 `DEGRADED`——
  刚进回滚、还没生成删除动作时不会提前收口。

补偿期间`Deleted` 的资源若与旧拓扑同名，只记 `released`，不改旧拓扑归属（旧拓扑删不删由第 8 节证据决定）。

## 8. 旧拓扑清理：只有证据能授权删除

```rust
enum LegacyState { Unattested, Attested { source }, Released, SharedWithNewTopology, NameConflict { new_kind } }
```

- 资源身份 = `(node, name)`，端口 / 协议 / 类型只是**属性**：GOST 按名字注册对象，同名不同端口不是「两个资源」而是**同名冲突**。
- `legacy_state(&key)`：同一 (node, name) 出现在新拓扑里且属性一致 → `SharedWithNewTopology`（**永远不清理**，按名字匹配就删会打掉正在跑的新拓扑）；
  属性不同（端口/协议/类型）→ `NameConflict { new_kind }`：既不能当共用保留，也不能删，只能人工改名或迁移；
  这类同名冲突在**下发前**就挡：`dispatch()` 先做 preflight（本操作台账里同键不同 kind，或旧拓扑同键不同 kind
  且已具证），命中就返回 `EngineError::NameConflictBeforeDispatch` 并把动作置 `Conflict`，
  不把注定被节点拒绝的请求发出去；`dispatch_all()` 遇到它继续发其余动作，不整轮失败；
  在 `attestations` 里 → `Attested`；在 `released_legacy` 里 → `Released`；否则 `Unattested`。
- `attest_legacy(&key, kind, source, recorded_at_ms)` 是「持久化的归属证明」入口：`source` 必须能说明这条旧资源
  凭哪份持久记录（迁移记录/旧隧道行/共享引用清单）归本次迁移处理；类型不符 → `LegacyKindMismatch`。
- `request_cleanup(&key)` 门槛：阶段 `Cleanup`、属于旧拓扑、已具证、未被共享引用占用、结果确定未决；
  新拓扑资源 → `ActiveInNewTopology`；同名冲突 → `NameConflictInNewTopology(key)`；旧拓扑里没有的键 → `NotLegacyTarget`；
  无证 → `LegacyNotAttested(key)`；未确认 → `OutcomeUnconfirmed`；已释放 → `AlreadyReleased`。
  重复请求复用同一条删除动作；上一轮 `Failed` 的删除会被重置为 `Pending` 原地重试（不新建第二条）。
  依赖不是「按请求顺序」而是按层补齐，而且补的是**所有真实引用的更上层**，不是只查 `layer + 1`：
  入口链是 layer 1、入口 service 是 layer 3，两者之间常常**没有 listener（layer 2）**——只查 `layer + 1` 会让
  service 删完就放链出去。现在 `cleanup_dependencies()` 收集同节点上 `layer > 自身` 的全部旧资源，并且
  **先整轮校验再建动作**：任一上层是 `Unattested` / `SharedWithNewTopology` / `NameConflict`，
  就返回 `DependencyNotDeletable { blocker, blocker_state }`，一条删除动作都不建（不留半截 DAG），
  更不能因为「上层未具证」就跳过它直接删下面。
- `verify_completion()` 要求旧拓扑每条资源都是 `Released` 或 `SharedWithNewTopology`；
  存在 `NameConflict` → 先返回 `LegacyNameConflict { keys }`（同名冲突不会被当成共用而放行）；
  存在 `Unattested`/`Attested` 未清 → `LegacyUnresolved`，正确做法是 `stop_for_review()` 停在 `MANUAL_REVIEW`
  （`unproven_legacy_stops_in_manual_review_instead_of_completed` 就是这条），**不产生假 COMPLETED**。

## 9. 快照 / 恢复

`snapshot() -> String`（JSON，含 `schema = SCHEMA_VERSION = 4`）与 `Engine::restore(&str)`。恢复时校验：
schema 匹配、`next_action_id` 大于所有动作 id、`op_id` 一致、`depends_on` 指向的动作存在且 id 更小（禁自依赖）、
`TimeoutUnconfirmed` 必须带 `resume_phase` 且确有未确认动作、资源键唯一，任一不满足 → `EngineError::Snapshot` / `IllegalTransition`。

schema 4 还额外做 **fail-closed 归属校验**：`released_legacy` / `quarantined_legacy` / `attestations` 的键必须是旧拓扑资源，
资源台账的键必须属于本计划的新旧拓扑，`applied` 台账必须指向真实存在的动作——
即「拼出来的 / 换了计划 / 被改过的快照」一律拒绝，而不是带着假归属继续跑。
重启后重放同一回执只会得到 `Duplicate`。

## 10. 关键 API

```rust
Engine::new(plan: MigrationPlan, capabilities: Vec<NodeCapabilities>) -> Result<Engine, EngineError>
Engine::restore(json: &str) -> Result<Engine, EngineError>

plan() / phase() / resume_phase() / clock_ms() / set_clock(now_ms)
actions() / action(id) / actions_in(phase) / resources() / applied() / gaps() / attestations()
supports(node, capability) / settle_report(phase) / unknown_results() / compensation_status()
legacy_state(&key) / legacy_targets() / snapshot()

advance_to(next) / stop_for_review()
dispatch(id) -> attempt / dispatch_all(phase) -> ids
on_timeout(id, note) / apply_receipt(&Receipt) -> ReceiptDecision
compensate() -> CompensationReport
attest_legacy(&key, kind, source, ts) / request_cleanup(&key) -> ActionId
```

## 11. 运行与验证

```
cargo fmt --check
cargo clippy --offline --locked --all-targets
cargo test  --offline --locked      # 25 个原集成测试 + 7 个 QA 回归（tests/qa_regressions.rs）
cargo run   --offline --locked      # demo：两跳多候选，停在 MANUAL_REVIEW（旧监听无归属证据）
```

demo 会打印每个动作的阶段/节点/结果、能力缺口，以及旧拓扑三条「共用保留」+ 一条「缺证据 → 待核查」。详细结果见 `TEST-REPORT.md`。

## 12. 尚未解决（诚实清单）

1. **没有任何真实执行器**：`Receipt` 由调用方伪造/注入，节点行为、超时、乱序的统计特性都未验证。
2. **原子端口预留不存在**：`ReservePort` 只是台账占位；没有「预留成功即不会被别人占用」的保证。
3. **状态确认能力缺失**：旧 GOST 没有 `queryServiceState`，`verified` 证据目前只能来自具备该能力的新节点或被显式声明支持的节点。
4. **清理归属**：`attest_legacy` 的 `source` 是字符串，本原型不校验来源真实性；真实实现必须由数据库中的旧记录/共享引用推导。
5. **候选选择不在编排层**：本原型只把候选集合交给节点（strategy/maxFails），不做健康探测与选路，语义与 beta 一致。
6. **端口复用检查是局部规则**：只拒绝「同一节点两个资源同端口」，不做跨节点/跨隧道冲突检测（那属于节点侧或数据库侧职责）。
7. **两套依赖图都按「同节点 + 更上层」裁剪**：现在一致，但跨节点的真实耦合（比如出口监听依赖上游中继）没有建模，
   若某个节点上的删除确实要求别的节点先完成，这套图看不出来，只能靠编排层保证。
8. **快照无迁移**：`SCHEMA_VERSION` 2→3→4，旧快照被 `Snapshot` 拒绝，没有升级函数。
   **这是刻意的 fail-closed**：旧实现只是原型（从未作为已上线生产跑过），没有可靠的迁移证据把 schema 3 的
   `applied`（只有布尔 `success`）还原成 `outcome`，也没有 `preserved` / `legacy_overlap` 的历史值，
   硬升级等于凭空造归属；宁可让旧快照恢复失败，也不猜。
9. **回滚自身超时无旁路**：回滚删除超时后在 `TimeoutUnconfirmed` 期间 `compensate()` 一律拒绝，只能等迟到回执或人工。
10. **同名冲突只挡不修**：`NameConflict` 只阻断收尾与删除，引擎不提供改名/迁移动作。
11. **删除重试无退避/上限**：重试由调用方决定，引擎不封顶尝试次数。
12. **同名链的内容未建模**：`ResourceKind::Chain` 只带链名，不带 `next` / hop / 端口，
    所以「同名链但转发目标已变」在执行前**查不出来**——preflight 只挡 kind 真不同的同名资源。
    `ResourceKind::Chain` 仍按 `SharedWithNewTopology` 处理（永不主动删），不会误删，但也不会阻断。
    要真做，得先让 plan 里的链携带内容摘要，再在 `preflight_conflict` 里比对——本轮没做。
