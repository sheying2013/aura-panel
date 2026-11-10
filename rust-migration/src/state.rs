use std::fmt;

use serde::{Deserialize, Serialize};

use crate::capability::Capability;
use crate::ids::{ActionId, ForwardId, NodeId, OperationId};
use crate::plan::{Candidate, Proto, Selector};
use crate::receipt::ReceiptOutcome;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Phase {
    Draft,
    Preparing,
    Provisioning,
    Cutover,
    Cleanup,
    Completed,
    RollbackPending,
    TimeoutUnconfirmed,
    Degraded,
    /// 证据不足时的诚实终态：停在待核查，绝不冒充 COMPLETED。
    ManualReview,
}

impl Phase {
    pub fn allowed_next(self) -> &'static [Phase] {
        use Phase::*;
        match self {
            Draft => &[Preparing],
            Preparing => &[Provisioning, RollbackPending, ManualReview],
            Provisioning => &[Cutover, RollbackPending, ManualReview],
            Cutover => &[Cleanup, RollbackPending, ManualReview],
            Cleanup => &[Completed, RollbackPending, ManualReview],
            RollbackPending => &[Degraded, ManualReview],
            TimeoutUnconfirmed => &[],
            Completed | Degraded | ManualReview => &[],
        }
    }

    pub fn transition_allowed(from: Phase, to: Phase) -> bool {
        from.allowed_next().contains(&to)
    }

    /// 会生成节点动作的进行中阶段。
    pub fn is_active(self) -> bool {
        use Phase::*;
        matches!(self, Preparing | Provisioning | Cutover | Cleanup)
    }

    pub fn is_terminal(self) -> bool {
        use Phase::*;
        matches!(self, Completed | Degraded | ManualReview)
    }

    /// 冻结阶段：结论未收口（TIMEOUT_UNCONFIRMED）或已经终结，不再接受新的节点交互。
    pub fn is_frozen(self) -> bool {
        self == Phase::TimeoutUnconfirmed || self.is_terminal()
    }

    pub fn label(self) -> &'static str {
        use Phase::*;
        match self {
            Draft => "DRAFT",
            Preparing => "PREPARING",
            Provisioning => "PROVISIONING",
            Cutover => "CUTOVER",
            Cleanup => "CLEANUP",
            Completed => "COMPLETED",
            RollbackPending => "ROLLBACK_PENDING",
            TimeoutUnconfirmed => "TIMEOUT_UNCONFIRMED",
            Degraded => "DEGRADED",
            ManualReview => "MANUAL_REVIEW",
        }
    }
}

impl fmt::Display for Phase {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// 节点上一个具名资源（链 / 监听 / 服务）的身份：节点名 + 资源名唯一。
///
/// 身份只由 (node, name) 决定；端口 / 协议 / 类型只是这个身份的属性。GOST 按名字注册资源，
/// 因此同一节点上「同名不同端口」是同一个注册名的冲突，而不是两个可以共存的独立资源。
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct ResourceKey {
    pub node: NodeId,
    pub name: String,
}

impl ResourceKey {
    pub fn new(node: NodeId, name: String) -> Self {
        Self { node, name }
    }
}

impl fmt::Display for ResourceKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}", self.node, self.name)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ResourceKind {
    Chain {
        chain: String,
    },
    Listener {
        listener: String,
        port: u16,
    },
    Service {
        forward: ForwardId,
        port: u16,
        proto: Proto,
    },
}

impl ResourceKind {
    /// 拆除层次：服务(3) 先删，再监听(2)，最后链(1)。
    pub fn layer(&self) -> u8 {
        match self {
            ResourceKind::Service { .. } => 3,
            ResourceKind::Listener { .. } => 2,
            ResourceKind::Chain { .. } => 1,
        }
    }

    pub fn label(&self) -> String {
        match self {
            ResourceKind::Chain { chain } => format!("chain {chain}"),
            ResourceKind::Listener { listener, port } => format!("listener {listener}:{port}"),
            ResourceKind::Service {
                forward,
                port,
                proto,
            } => {
                format!("service {}({}) port {port}", forward.0, proto.as_str())
            }
        }
    }
}

/// 节点回执携带的确认强度。
///
/// - CommandAck：节点只是接受了命令（beta 现状，AddChains 的 isError 甚至被吞掉）。
/// - StateVerified：节点回报了真实状态，只有声明支持 QueryServiceState 的节点能给出。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Evidence {
    CommandAck,
    StateVerified,
}

impl Evidence {
    pub fn is_verified(self) -> bool {
        matches!(self, Evidence::StateVerified)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResourceRecord {
    pub key: ResourceKey,
    pub kind: ResourceKind,
    pub op: OperationId,
    pub provision_confirmed: bool,
    pub quarantined: bool,
    pub released: bool,
    /// 节点回 exists 说明资源已存在，但不证明归属本操作，因此不可补偿删除。
    pub conflicted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ActionKind {
    ReservePort {
        port: u16,
    },
    ProbePort {
        port: u16,
    },
    AddChains {
        chain: String,
        next: Vec<Candidate>,
        hop: u8,
        /// beta 为每个 hop 都带 selector（GostUtil.java:97-103），不是只在首跳带。
        selector: Selector,
    },
    AddListener {
        listener: String,
        port: u16,
        chained: bool,
    },
    AddService {
        forward: ForwardId,
        service: String,
        port: u16,
        proto: Proto,
        chain: String,
    },
    /// beta changeForwardStatus(id, 0, "PauseService")（ForwardServiceImpl:1084 / :1427-1457）。
    /// 服务标识字段与 AddService 完全一致：同一个资源，只是状态。
    PauseService {
        forward: ForwardId,
        service: String,
        port: u16,
        proto: Proto,
        chain: String,
    },
    /// beta changeForwardStatus(id, 1, "ResumeService")。切流阶段永远不生成：
    /// 暂停转发迁移过去仍是暂停，恢复由后续恢复逻辑（含 pause_reason 判定）负责。
    ResumeService {
        forward: ForwardId,
        service: String,
        port: u16,
        proto: Proto,
        chain: String,
    },
    DeleteChains {
        chain: String,
    },
    DeleteListener {
        listener: String,
        port: u16,
    },
    DeleteService {
        forward: ForwardId,
        service: String,
        port: u16,
        proto: Proto,
    },
}

impl ActionKind {
    pub fn required_capability(&self) -> Option<Capability> {
        match self {
            ActionKind::ReservePort { .. } => Some(Capability::AtomicPortReserve),
            ActionKind::ProbePort { .. } => Some(Capability::ProbePort),
            _ => None,
        }
    }

    pub fn resource_kind(&self) -> Option<ResourceKind> {
        match self {
            ActionKind::ReservePort { .. } | ActionKind::ProbePort { .. } => None,
            ActionKind::AddChains { chain, .. } | ActionKind::DeleteChains { chain } => {
                Some(ResourceKind::Chain {
                    chain: chain.clone(),
                })
            }
            ActionKind::AddListener { listener, port, .. }
            | ActionKind::DeleteListener { listener, port } => Some(ResourceKind::Listener {
                listener: listener.clone(),
                port: *port,
            }),
            ActionKind::AddService {
                forward,
                port,
                proto,
                ..
            }
            | ActionKind::PauseService {
                forward,
                port,
                proto,
                ..
            }
            | ActionKind::ResumeService {
                forward,
                port,
                proto,
                ..
            }
            | ActionKind::DeleteService {
                forward,
                port,
                proto,
                ..
            } => Some(ResourceKind::Service {
                forward: *forward,
                port: *port,
                proto: *proto,
            }),
        }
    }

    pub fn is_delete(&self) -> bool {
        matches!(
            self,
            ActionKind::DeleteChains { .. }
                | ActionKind::DeleteListener { .. }
                | ActionKind::DeleteService { .. }
        )
    }

    /// 切流期必须拿到节点确认（verified）的动作：建服务、以及建服务后的暂停/恢复。
    /// 暂停同样要有确认，否则「切流完成」时无法证明服务确实处于暂停态。
    pub fn is_cutover(&self) -> bool {
        matches!(
            self,
            ActionKind::AddService { .. }
                | ActionKind::PauseService { .. }
                | ActionKind::ResumeService { .. }
        )
    }

    pub fn label(&self) -> String {
        match self {
            ActionKind::ReservePort { port } => format!("reserve port {port}"),
            ActionKind::ProbePort { port } => format!("probe port {port}"),
            ActionKind::AddChains {
                chain,
                hop,
                next,
                selector,
            } => {
                let nodes: Vec<String> = next.iter().map(|c| c.node.to_string()).collect();
                format!(
                    "add chain {chain} (hop {hop} -> [{}]) selector {}",
                    nodes.join(", "),
                    selector.to_json()
                )
            }
            ActionKind::AddListener {
                listener,
                port,
                chained,
            } => format!(
                "add listener {listener}:{port}{}",
                if *chained {
                    " (with chain)"
                } else {
                    " (no chain)"
                }
            ),
            ActionKind::AddService {
                service,
                port,
                proto,
                ..
            } => {
                format!("add service {service} port {port} ({})", proto.as_str())
            }
            ActionKind::PauseService {
                service,
                port,
                proto,
                ..
            } => {
                format!("pause service {service} port {port} ({})", proto.as_str())
            }
            ActionKind::ResumeService {
                service,
                port,
                proto,
                ..
            } => {
                format!("resume service {service} port {port} ({})", proto.as_str())
            }
            ActionKind::DeleteChains { chain } => format!("delete chain {chain}"),
            ActionKind::DeleteListener { listener, port } => {
                format!("delete listener {listener}:{port}")
            }
            ActionKind::DeleteService {
                service,
                port,
                proto,
                ..
            } => {
                format!("delete service {service} port {port} ({})", proto.as_str())
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ActionStatus {
    Pending,
    Dispatched {
        attempt: u32,
    },
    Confirmed {
        attempt: u32,
        evidence: Evidence,
    },
    Failed {
        attempt: u32,
        reason: String,
    },
    Unconfirmed {
        attempt: u32,
        note: String,
    },
    /// 节点回 exists：资源存在但不是本操作的，不能算确认，也不能补偿删除。
    Conflict {
        attempt: u32,
        reply: String,
    },
    /// 能力缺失导致的下发前阻断（fail closed）。
    Blocked {
        capability: Capability,
    },
}

impl ActionStatus {
    pub fn is_pending(&self) -> bool {
        matches!(self, ActionStatus::Pending)
    }

    pub fn is_unconfirmed(&self) -> bool {
        matches!(self, ActionStatus::Unconfirmed { .. })
    }

    pub fn is_settled(&self) -> bool {
        matches!(
            self,
            ActionStatus::Confirmed { .. }
                | ActionStatus::Failed { .. }
                | ActionStatus::Conflict { .. }
                | ActionStatus::Blocked { .. }
        )
    }

    pub fn is_confirmed(&self) -> bool {
        matches!(self, ActionStatus::Confirmed { .. })
    }

    pub fn is_verified(&self) -> bool {
        matches!(
            self,
            ActionStatus::Confirmed {
                evidence: Evidence::StateVerified,
                ..
            }
        )
    }

    pub fn attempt(&self) -> u32 {
        match self {
            ActionStatus::Pending => 0,
            ActionStatus::Dispatched { attempt }
            | ActionStatus::Confirmed { attempt, .. }
            | ActionStatus::Failed { attempt, .. }
            | ActionStatus::Unconfirmed { attempt, .. }
            | ActionStatus::Conflict { attempt, .. } => *attempt,
            ActionStatus::Blocked { .. } => 0,
        }
    }

    pub fn label(&self) -> String {
        match self {
            ActionStatus::Pending => "pending".to_string(),
            ActionStatus::Dispatched { attempt } => format!("dispatched(#{attempt})"),
            ActionStatus::Confirmed { attempt, evidence } => match evidence {
                Evidence::StateVerified => format!("confirmed(#{attempt}, verified)"),
                Evidence::CommandAck => format!("confirmed(#{attempt}, command-ack)"),
            },
            ActionStatus::Failed { attempt, reason } => format!("failed(#{attempt}: {reason})"),
            ActionStatus::Unconfirmed { attempt, note } => {
                format!("unconfirmed(#{attempt}: {note})")
            }
            ActionStatus::Conflict { attempt, reply } => format!("conflict(#{attempt}: {reply})"),
            ActionStatus::Blocked { capability } => format!("blocked({})", capability.label()),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Action {
    pub op: OperationId,
    pub id: ActionId,
    pub node: NodeId,
    pub phase: Phase,
    pub kind: ActionKind,
    pub resource: Option<ResourceKey>,
    pub attempt: u32,
    pub status: ActionStatus,
    /// 可执行依赖：这些动作必须 Confirmed 才能下发本动作（阶段屏障之外的动作级依赖）。
    pub depends_on: Vec<ActionId>,
    pub dispatched_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AppliedReceipt {
    pub action: ActionId,
    pub attempt: u32,
    /// 保留完整的回执结论，而不是压成 bool：exists / failure 都"不是成功"，
    /// 压平后同一个 attempt 的相反结论会被误判成重复回执。
    pub outcome: ReceiptOutcome,
    /// 节点自己报的时间，只做审计留痕。跨机器时钟既不参与认证，也不参与因果排序。
    #[serde(default)]
    pub node_time_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CapabilityGap {
    pub node: NodeId,
    pub capability: Capability,
    pub action: ActionId,
    pub phase: Phase,
    pub note: String,
}

/// 旧资源归属的外部证据。必须持久化在快照里，且键/类型必须与旧拓扑推导一致。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LegacyAttestation {
    pub key: ResourceKey,
    pub kind: ResourceKind,
    pub source: String,
    pub recorded_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum LegacyState {
    /// 没有归属证据。
    Unattested,
    /// 有归属证据，但还没清理。
    Attested { source: String },
    /// 已由本操作删除并确认。
    Released,
    /// 与新拓扑同键同属性：属于活动中的新资源，禁止删除。
    SharedWithNewTopology,
    /// 与新拓扑同键但属性不同（例如监听端口变了）：同一个 GOST 注册名被两边抢用，
    /// 新旧资源不可能共存，既不能算共用，也不能按名字删除，只能人工处理。
    NameConflict { new_kind: ResourceKind },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LegacyTargetView {
    pub key: ResourceKey,
    pub kind: ResourceKind,
    pub state: LegacyState,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum BlockReason {
    /// 该资源相关动作结果未决（没有回执）。
    OutcomeUnconfirmed,
    /// 本操作没有创建成功过它（失败 / 未下发 / 已隔离）。
    NotCreated,
    /// 节点回了 exists，归属未证明。
    ConflictUnproven,
    /// 旧拓扑同键但属性不同：同一个注册名被新旧两边抢用，删除会误伤旧资源。
    LegacyNameConflict,
}

impl BlockReason {
    pub fn label(self) -> &'static str {
        match self {
            BlockReason::OutcomeUnconfirmed => "outcome-unconfirmed",
            BlockReason::NotCreated => "not-created",
            BlockReason::ConflictUnproven => "conflict-unproven",
            BlockReason::LegacyNameConflict => "legacy-name-conflict",
        }
    }
}

/// 回滚时"看得见但绝不删"的资源：它是旧拓扑先存在的同名资源，
/// 不属于本操作创建，删了就是拆别人的线路。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PreservedCompensation {
    pub resource: ResourceKey,
    pub kind: ResourceKind,
    pub reason: PreserveReason,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PreserveReason {
    /// 与旧拓扑同键同属性：旧资源本来就在用这个名字，回滚不碰它。
    LegacyShared,
}

impl PreserveReason {
    pub fn label(self) -> &'static str {
        match self {
            PreserveReason::LegacyShared => "legacy-shared",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BlockedCompensation {
    pub resource: ResourceKey,
    pub action: ActionId,
    pub reason: BlockReason,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SettleReport {
    pub pending: Vec<ActionId>,
    pub failed: Vec<ActionId>,
    pub unconfirmed: Vec<ActionId>,
    pub conflicted: Vec<ActionId>,
    pub blocked: Vec<ActionId>,
}

impl SettleReport {
    pub fn is_clean(&self) -> bool {
        self.pending.is_empty()
            && self.failed.is_empty()
            && self.unconfirmed.is_empty()
            && self.conflicted.is_empty()
            && self.blocked.is_empty()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UnknownResults {
    pub actions: Vec<ActionId>,
    /// 结果未知、归属未定的资源（去重后按 key 排序）。
    pub resources: Vec<ResourceKey>,
    /// 其中与旧拓扑同键的部分：名字先于本操作存在，未知不等于"我们建的"。
    pub legacy_overlap: Vec<ResourceKey>,
    pub gaps: Vec<CapabilityGap>,
}
