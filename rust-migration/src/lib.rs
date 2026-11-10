//! Flux-Panel 拓扑迁移状态机原型（纯逻辑）。
//!
//! 边界（务必当真）：
//! - 不连接数据库、不连接真实 GOST、不引入任何新协议；
//! - 节点结果只来自调用方喂入的回执，原型不探测任何真实状态；
//! - 因此本原型不保证「零中断」迁移，只保证状态机不会把未知当成功。

pub mod beta;
pub mod capability;
pub mod engine;
pub mod error;
pub mod ids;
pub mod plan;
pub mod receipt;
pub mod state;
pub mod topology_change;

pub use beta::{
    allocated_ports, chain_name, forward_service_base, forward_service_name, hop_element, hop_name,
    plan_cascade_delete, plan_forward, process_server_address, relay_service_name,
    tunnel_resource_kinds, CascadeDeleteInput, CascadeDeletePlan, CascadePhase, CascadeStep,
    CascadeStepKind, ChainRole, ChainRow, ChainSpec, EntryService, ForwardCascade, ForwardPlan,
    ForwardRow, HopNode, NodePlan, PublishError, RelayService, RowDelete, SpeedLimitRow,
    TunnelPublish, UserTunnelRow,
};
pub use capability::{Capability, NodeCapabilities};
pub use engine::{CompensationReport, Engine, ReceiptDecision, SCHEMA_VERSION};
pub use error::{CleanupRejection, EngineError, ReceiptRejection};
pub use ids::{ActionId, ForwardId, NodeId, OperationId};
pub use plan::{
    check_port, Candidate, EntryPort, ForwardSpec, ForwardStatus, MigrationPlan, Proto, Selector,
    Topology, TunnelType, PORT_MAX, PORT_MIN, TUNNEL_TYPE_PORT_FORWARD, TUNNEL_TYPE_TUNNEL_FORWARD,
};
pub use receipt::{Receipt, ReceiptOutcome};
pub use state::{
    Action, ActionKind, ActionStatus, AppliedReceipt, BlockReason, BlockedCompensation,
    CapabilityGap, Evidence, LegacyAttestation, LegacyState, LegacyTargetView, Phase,
    PreserveReason, PreservedCompensation, ResourceKey, ResourceKind, ResourceRecord, SettleReport,
    UnknownResults,
};
pub use topology_change::{plan_topology_edit, TopologyEdit, TopologyEditError, TopologyEditPlan};

pub mod batch_generator;
pub mod failover;
pub mod proxy_protocol;
pub mod quality_radar;
pub mod rule_backup;

pub use batch_generator::{BatchForwardGenerator, BatchPortRangeRequest, BatchValidationError, GeneratedForwardSpec};
pub use failover::{DecisionAction, FailoverDecision, FailoverEngine, FailoverRule, FailoverStrategy, HealthState, TargetMetrics};
pub use proxy_protocol::{ProxyCommand, ProxyHeader, ProxyVersion, TransportProtocol, V2_SIGNATURE};
pub use quality_radar::{QualityGrade, QualityRadar, QualityRadarReport, RadarDimensions};
pub use rule_backup::{BackupArchive, BackupRuleItem};

pub mod acl_engine;
pub mod circuit_breaker;
pub mod ddns_resolver;
pub mod load_balancer;
pub mod traffic_shaper;

pub use acl_engine::{extract_tls_sni, AclRuleSet, AclVerdict, Ipv4Cidr, DEFAULT_BLOCKED_PORTS};
pub use circuit_breaker::{BreakerConfig, BreakerState, CircuitBreaker};
pub use ddns_resolver::{DdnsResolutionDiff, DdnsTracker, TrackedDdnsDomain};
pub use load_balancer::{BackendTarget, BalancingPolicy, ConsistentHashRing, LoadBalancer};
pub use traffic_shaper::{BidirectionalShaper, PeakSchedule, TokenBucket};
