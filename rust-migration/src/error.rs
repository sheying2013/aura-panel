use std::fmt;

use serde::{Deserialize, Serialize};

use crate::capability::Capability;
use crate::ids::{ActionId, NodeId, OperationId};
use crate::plan::Proto;
use crate::state::{CapabilityGap, LegacyState, Phase, ResourceKey, ResourceKind};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EngineError {
    InvalidPlan(String),
    IllegalTransition {
        from: Phase,
        to: Phase,
    },
    PhaseNotSettled {
        phase: Phase,
        blocking: Vec<ActionId>,
    },
    PhaseHasFailures {
        phase: Phase,
        actions: Vec<ActionId>,
    },
    PhaseHasConflicts {
        phase: Phase,
        actions: Vec<ActionId>,
    },
    CapabilityBlocked {
        phase: Phase,
        gaps: Vec<CapabilityGap>,
    },
    UnconfirmedOutcome {
        action: Option<ActionId>,
    },
    DependencyUnsettled {
        action: ActionId,
        blocking: Vec<ActionId>,
    },
    UnknownAction(ActionId),
    IllegalActionState {
        action: ActionId,
        status: String,
    },
    /// 切换动作只有命令 ACK、没有真实状态确认，禁止收尾为 COMPLETED。
    CutoverUnverified {
        verified: usize,
        total: usize,
        actions: Vec<ActionId>,
    },
    /// 旧拓扑资源缺少归属证据或尚未清理，禁止 COMPLETED。
    LegacyUnresolved {
        unattested: Vec<ResourceKey>,
        not_cleaned: Vec<ResourceKey>,
    },
    UnknownLegacyTarget(ResourceKey),
    LegacyKindMismatch {
        key: ResourceKey,
        expected: ResourceKind,
        received: ResourceKind,
    },
    /// 同一节点同名资源的属性与新拓扑不一致：名称冲突，禁止 COMPLETED。
    LegacyNameConflict {
        keys: Vec<ResourceKey>,
    },
    /// 执行前就确定的名字争用：同一个 (节点, 名字) 已经带着别的属性在使用。
    /// 下发它等于让节点按老属性重注册，所以不下发、直接停。
    NameConflictBeforeDispatch {
        action: ActionId,
        key: ResourceKey,
        existing: ResourceKind,
        requested: ResourceKind,
    },
    /// 阶段已冻结（结果未决 / 终态）：不再接受下发或超时登记。
    PhaseFrozen {
        phase: Phase,
    },
    CompensationUnavailable(Phase),
    ClockRegression {
        current: i64,
        received: i64,
    },
    Snapshot(String),
}

impl fmt::Display for EngineError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EngineError::InvalidPlan(reason) => write!(f, "输入计划非法: {reason}"),
            EngineError::IllegalTransition { from, to } => {
                write!(f, "非法阶段流转 {from} -> {to}")
            }
            EngineError::PhaseNotSettled { phase, blocking } => write!(
                f,
                "阶段 {phase} 仍有 {} 个动作未下发（{:?}）",
                blocking.len(),
                blocking
            ),
            EngineError::PhaseHasFailures { phase, actions } => {
                write!(f, "阶段 {phase} 有失败动作 {:?}", actions)
            }
            EngineError::PhaseHasConflicts { phase, actions } => write!(
                f,
                "阶段 {phase} 有 exists 冲突动作 {:?}（exists 不证明归属）",
                actions
            ),
            EngineError::CapabilityBlocked { phase, gaps } => {
                let detail: Vec<String> = gaps
                    .iter()
                    .map(|gap| format!("{}@{}={}", gap.node, gap.capability.label(), gap.action.0))
                    .collect();
                write!(f, "阶段 {phase} 存在能力缺失阻断: {}", detail.join(", "))
            }
            EngineError::UnconfirmedOutcome { action } => match action {
                Some(action) => write!(f, "动作 {} 结果未决，不能推进或补偿", action.0),
                None => write!(f, "存在未决动作结果，不能推进或补偿"),
            },
            EngineError::DependencyUnsettled { action, blocking } => {
                write!(f, "动作 {} 的依赖未确认 {:?}", action.0, blocking)
            }
            EngineError::UnknownAction(action) => write!(f, "未知动作 {}", action.0),
            EngineError::IllegalActionState { action, status } => {
                write!(f, "动作 {} 当前状态 {status} 不允许该操作", action.0)
            }
            EngineError::CutoverUnverified {
                verified,
                total,
                actions,
            } => write!(
                f,
                "切换只有 {verified}/{total} 个动作拿到真实状态确认，未确认: {:?}",
                actions
            ),
            EngineError::LegacyUnresolved {
                unattested,
                not_cleaned,
            } => write!(
                f,
                "旧拓扑资源未了结：缺归属证据 {}，已证实未清理 {}",
                format_keys(unattested),
                format_keys(not_cleaned)
            ),
            EngineError::UnknownLegacyTarget(key) => {
                write!(f, "{key} 不在旧拓扑推导出的资源集合里")
            }
            EngineError::LegacyKindMismatch {
                key,
                expected,
                received,
            } => write!(
                f,
                "{key} 归属证据类型不符：期望 {}，收到 {}",
                expected.label(),
                received.label()
            ),
            EngineError::LegacyNameConflict { keys } => write!(
                f,
                "旧资源与新拓扑同名但属性不同（GOST 同一注册名冲突，不能共存也不能按名字删）：{}",
                format_keys(keys)
            ),
            EngineError::NameConflictBeforeDispatch {
                action,
                key,
                existing,
                requested,
            } => write!(
                f,
                "动作 {} 要注册 {key} 为 {}，但该名字已带着 {} 在用（同名不同属性），执行前阻断",
                action.0,
                requested.label(),
                existing.label()
            ),
            EngineError::PhaseFrozen { phase } => write!(
                f,
                "阶段 {phase} 已冻结：结果未决或已终结，不再接受新的下发或超时登记"
            ),
            EngineError::CompensationUnavailable(phase) => {
                write!(f, "阶段 {phase} 不允许补偿")
            }
            EngineError::ClockRegression { current, received } => {
                write!(f, "时钟回拨：当前 {current}，收到 {received}")
            }
            EngineError::Snapshot(reason) => write!(f, "快照非法: {reason}"),
        }
    }
}

fn legacy_state_label(state: &LegacyState) -> &'static str {
    match state {
        LegacyState::Unattested => "unattested",
        LegacyState::Attested { .. } => "attested",
        LegacyState::Released => "released",
        LegacyState::SharedWithNewTopology => "shared-with-new-topology",
        LegacyState::NameConflict { .. } => "name-conflict",
    }
}

fn format_keys(keys: &[ResourceKey]) -> String {
    let items: Vec<String> = keys.iter().map(|key| key.to_string()).collect();
    format!("[{}]", items.join(", "))
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReceiptRejection {
    ForeignOperation {
        expected: OperationId,
        received: OperationId,
    },
    UnknownAction(ActionId),
    NodeMismatch {
        action: ActionId,
        expected: NodeId,
        received: NodeId,
    },
    AttemptMismatch {
        action: ActionId,
        current: u32,
        received: u32,
    },
    /// 节点声称自己没声明过的能力（例如没有 queryServiceState 却回 StateVerified）。
    EvidenceUnproven {
        action: ActionId,
        node: NodeId,
        capability: Capability,
    },
    NotDispatched(ActionId),
    ActionBlocked {
        action: ActionId,
        capability: Capability,
    },
    ConflictingOutcome {
        action: ActionId,
        attempt: u32,
    },
}

impl fmt::Display for ReceiptRejection {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ReceiptRejection::ForeignOperation { expected, received } => {
                write!(f, "回执属于别的操作 {received}（本操作 {expected}）")
            }
            ReceiptRejection::UnknownAction(action) => write!(f, "未知动作 {}", action.0),
            ReceiptRejection::NodeMismatch {
                action,
                expected,
                received,
            } => write!(
                f,
                "动作 {} 属于节点 {expected}，回执来自 {received}",
                action.0
            ),
            ReceiptRejection::AttemptMismatch {
                action,
                current,
                received,
            } => write!(
                f,
                "动作 {} 当前 attempt {current}，回执 attempt {received}",
                action.0
            ),
            ReceiptRejection::EvidenceUnproven {
                action,
                node,
                capability,
            } => write!(
                f,
                "动作 {} 的节点 {node} 未声明能力 {}，不接受该证据",
                action.0,
                capability.label()
            ),
            ReceiptRejection::NotDispatched(action) => {
                write!(f, "动作 {} 从未下发，忽略回执", action.0)
            }
            ReceiptRejection::ActionBlocked { action, capability } => write!(
                f,
                "动作 {} 因缺少能力 {} 被阻断，不接受回执",
                action.0,
                capability.label()
            ),
            ReceiptRejection::ConflictingOutcome { action, attempt } => {
                write!(f, "动作 {} 的 attempt {attempt} 已有相反结论", action.0)
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CleanupRejection {
    WrongPhase(Phase),
    /// 该键属于新拓扑的活动资源：清理阶段绝不能删它。
    ActiveInNewTopology(ResourceKey),
    /// 该键与新拓扑同名但属性不同：同一个注册名的冲突，删旧的会打断新资源。
    NameConflictInNewTopology(ResourceKey),
    NotLegacyTarget(ResourceKey),
    AlreadyReleased(ResourceKey),
    OutcomeUnconfirmed {
        key: ResourceKey,
        action: ActionId,
    },
    LegacyNotAttested(ResourceKey),
    /// 更上层的旧资源还没证明可删（缺证据 / 仍被新拓扑使用 / 同名冲突）：
    /// 先删底层会把仍在用的上层拆掉，只能整条链路一起等。
    DependencyNotDeletable {
        key: ResourceKey,
        blocker: ResourceKey,
        blocker_state: LegacyState,
    },
}

impl fmt::Display for CleanupRejection {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CleanupRejection::WrongPhase(phase) => {
                write!(f, "只有 CLEANUP 阶段可以清理旧拓扑，当前 {phase}")
            }
            CleanupRejection::ActiveInNewTopology(key) => {
                write!(f, "{key} 是新拓扑的活动资源，禁止删除")
            }
            CleanupRejection::NameConflictInNewTopology(key) => write!(
                f,
                "{key} 与新拓扑同名但属性不同（GOST 名称冲突），先人工处理再清理"
            ),
            CleanupRejection::NotLegacyTarget(key) => {
                write!(f, "{key} 不在旧拓扑推导出的资源集合里")
            }
            CleanupRejection::AlreadyReleased(key) => write!(f, "{key} 已清理"),
            CleanupRejection::OutcomeUnconfirmed { key, action } => {
                write!(f, "{key} 的删除动作 {} 结果未决", action.0)
            }
            CleanupRejection::LegacyNotAttested(key) => {
                write!(f, "{key} 缺少持久化的归属证据")
            }
            CleanupRejection::DependencyNotDeletable {
                key,
                blocker,
                blocker_state,
            } => write!(
                f,
                "{key} 的上层资源 {blocker} 状态为 {}，未证明可删，禁止先删 {key}",
                legacy_state_label(blocker_state)
            ),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProtoLabel(pub Proto);

impl fmt::Display for ProtoLabel {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0.as_str())
    }
}
