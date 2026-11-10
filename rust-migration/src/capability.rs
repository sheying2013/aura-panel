use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

use crate::ids::NodeId;

/// 控制面必须显式声明的能力。
///
/// 旧 GOST 只证明了「命令被接受」，没有已证实的 ProbePort 或 queryServiceState(requestId)
/// 实现，也没有持久去重。因此 legacy() 返回空集合：任何依赖这些能力的动作都必须
/// fail closed（阻断），不得默默算通过。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum Capability {
    AtomicPortReserve,
    ProbePort,
    QueryServiceState,
}

impl Capability {
    pub fn label(self) -> &'static str {
        match self {
            Capability::AtomicPortReserve => "atomic-port-reserve",
            Capability::ProbePort => "probe-port",
            Capability::QueryServiceState => "query-service-state",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodeCapabilities {
    pub node: NodeId,
    pub supported: BTreeSet<Capability>,
}

impl NodeCapabilities {
    pub fn new(node: NodeId, supported: impl IntoIterator<Item = Capability>) -> Self {
        Self {
            node,
            supported: supported.into_iter().collect(),
        }
    }

    /// 旧 GOST 节点：能力集合为空，所有需要能力的动作都会被阻断。
    pub fn legacy(node: NodeId) -> Self {
        Self {
            node,
            supported: BTreeSet::new(),
        }
    }

    pub fn with(mut self, capability: Capability) -> Self {
        self.supported.insert(capability);
        self
    }

    pub fn supports(&self, capability: Capability) -> bool {
        self.supported.contains(&capability)
    }
}
