use serde::{Deserialize, Serialize};

use crate::ids::{ActionId, NodeId, OperationId};
use crate::state::Evidence;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ReceiptOutcome {
    Success,
    /// 节点回 exists：资源已存在，但不是本操作创建的。
    Exists,
    Failure {
        reason: String,
    },
}

impl ReceiptOutcome {
    /// 稳定的短标签，用于错误消息与回执台账；exists 与 failure 必须能区分开。
    pub fn label(&self) -> String {
        match self {
            ReceiptOutcome::Success => "success".to_string(),
            ReceiptOutcome::Exists => "exists".to_string(),
            ReceiptOutcome::Failure { reason } => format!("failure({reason})"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Receipt {
    pub op_id: OperationId,
    pub action: ActionId,
    pub node: NodeId,
    pub attempt: u32,
    /// 节点侧时间；0 表示未提供（不参与时序校验）。
    pub node_time_ms: i64,
    pub evidence: Evidence,
    pub outcome: ReceiptOutcome,
}

impl Receipt {
    pub fn new(
        op_id: OperationId,
        action: ActionId,
        node: NodeId,
        attempt: u32,
        evidence: Evidence,
        outcome: ReceiptOutcome,
    ) -> Self {
        Self {
            op_id,
            action,
            node,
            attempt,
            node_time_ms: 0,
            evidence,
            outcome,
        }
    }

    /// 命令被接受（beta 现状强度）。
    pub fn success(op_id: OperationId, action: ActionId, node: NodeId, attempt: u32) -> Self {
        Self::new(
            op_id,
            action,
            node,
            attempt,
            Evidence::CommandAck,
            ReceiptOutcome::Success,
        )
    }

    /// 节点回报了真实状态；只有声明支持 QueryServiceState 的节点能给出。
    pub fn verified(op_id: OperationId, action: ActionId, node: NodeId, attempt: u32) -> Self {
        Self::new(
            op_id,
            action,
            node,
            attempt,
            Evidence::StateVerified,
            ReceiptOutcome::Success,
        )
    }

    pub fn exists(op_id: OperationId, action: ActionId, node: NodeId, attempt: u32) -> Self {
        Self::new(
            op_id,
            action,
            node,
            attempt,
            Evidence::CommandAck,
            ReceiptOutcome::Exists,
        )
    }

    pub fn failure(
        op_id: OperationId,
        action: ActionId,
        node: NodeId,
        attempt: u32,
        reason: impl Into<String>,
    ) -> Self {
        Self::new(
            op_id,
            action,
            node,
            attempt,
            Evidence::CommandAck,
            ReceiptOutcome::Failure {
                reason: reason.into(),
            },
        )
    }

    /// 打上节点侧时间戳：只记录事实用于审计，不参与认证与因果排序。
    /// 分布式节点时钟不可比，早于本机下发时间不算异常。
    pub fn at(mut self, node_time_ms: i64) -> Self {
        self.node_time_ms = node_time_ms;
        self
    }

    pub fn is_success(&self) -> bool {
        matches!(self.outcome, ReceiptOutcome::Success)
    }

    pub fn is_exists(&self) -> bool {
        matches!(self.outcome, ReceiptOutcome::Exists)
    }
}
