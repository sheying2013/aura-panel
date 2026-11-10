use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

use crate::ids::{ForwardId, NodeId, OperationId};
use crate::state::{ResourceKey, ResourceKind};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum Proto {
    Tcp,
    Udp,
}

impl Proto {
    pub fn as_str(self) -> &'static str {
        match self {
            Proto::Tcp => "tcp",
            Proto::Udp => "udp",
        }
    }
}

/// 一跳 chain 的负载均衡选择器，字段名与单位与 beta 完全一致：
/// GostUtil.CreateChains（GostUtil.java:97-103）为每个 hop 写
/// {strategy: chain_tunnel.strategy, maxFails: 1, failTimeout: 600000000000}，
/// failTimeout 是**纳秒**（600 秒），不是 "600s" 字符串（那是服务侧 createForwarder 的口径）。
///
/// strategy 为空时不补默认值：beta 直接把 chain_tunnel.strategy 塞进 fastjson，
/// 未配置时这个键就写不出去；这里同样用 None 表示「没有配置」，序列化时整键省略，
/// 由 gost 自己决定默认策略。原型不臆造 "round" / "fifo" —— "fifo" 是 beta 转发服务
/// （createForwarder）的默认值，与链无关，混用会把两种语义粘在一起。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Selector {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy: Option<String>,
    #[serde(rename = "maxFails")]
    pub max_fails: u32,
    #[serde(rename = "failTimeout")]
    pub fail_timeout: u64,
}

impl Selector {
    /// beta 每条链的 hop 都写死这两个值（GostUtil.java:99-100）。
    pub const MAX_FAILS: u32 = 1;
    pub const FAIL_TIMEOUT_NS: u64 = 600_000_000_000;

    /// 用 chain_tunnel.strategy 构造一跳的 selector；空串与 None 同义（都不构成有效策略）。
    pub fn for_hop(strategy: Option<&str>) -> Self {
        Self {
            strategy: strategy
                .filter(|value| !value.is_empty())
                .map(|value| value.to_string()),
            max_fails: Self::MAX_FAILS,
            fail_timeout: Self::FAIL_TIMEOUT_NS,
        }
    }

    /// 载荷里的 JSON 形态；测试与真实下发共用这一条序列化路径。
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).expect("selector serializes")
    }
}

/// 端口合法区间。beta 库里 port 是 Integer（可为 0/负数/超界），
/// 规划阶段只接受 1..=65535：越界一律产出错误项，不猜默认值、不 panic。
pub const PORT_MIN: u32 = 1;
pub const PORT_MAX: u32 = 65535;

/// beta tunnel.type 的取值（GostUtil.java:210-214，round44 fail-closed）：
/// 1 端口转发 / 2 隧道转发。其余取值（含 NULL）都无法判定直连还是中继，只能拒绝。
pub const TUNNEL_TYPE_PORT_FORWARD: i32 = 1;
pub const TUNNEL_TYPE_TUNNEL_FORWARD: i32 = 2;

fn check_port_with(node: &NodeId, port: i64, scope: &str, kind: &str) -> Result<u16, String> {
    match port {
        port if (PORT_MIN as i64..=PORT_MAX as i64).contains(&port) => Ok(port as u16),
        0 => Err(format!("{scope}: 节点 {node} 的{kind}端口为 0")),
        _ => Err(format!(
            "{scope}: 节点 {node} 的{kind}端口 {port} 超出 {PORT_MIN}..={PORT_MAX}"
        )),
    }
}

/// 监听端口校验：把 beta 的 Integer 端口收窄成 u16，越界即 Err。
pub fn check_port(node: &NodeId, port: i64, scope: &str) -> Result<u16, String> {
    check_port_with(node, port, scope, "监听")
}

/// beta tunnel.type 的类型化口径。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TunnelType {
    PortForward,
    TunnelForward,
}

impl TunnelType {
    /// None 与非 1/2 一律拒绝：按中继兜底会 bind 失败，按直连下发会明文穿透。
    pub fn from_beta(value: Option<i32>) -> Result<Self, String> {
        match value {
            Some(TUNNEL_TYPE_PORT_FORWARD) => Ok(TunnelType::PortForward),
            Some(TUNNEL_TYPE_TUNNEL_FORWARD) => Ok(TunnelType::TunnelForward),
            other => Err(format!(
                "隧道类型不合法（tunnel.type={}），拒绝继续规划：无法判定直连还是中继",
                match other {
                    Some(value) => value.to_string(),
                    None => "NULL".to_string(),
                }
            )),
        }
    }

    pub fn as_beta(self) -> i32 {
        match self {
            TunnelType::PortForward => TUNNEL_TYPE_PORT_FORWARD,
            TunnelType::TunnelForward => TUNNEL_TYPE_TUNNEL_FORWARD,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            TunnelType::PortForward => "port-forward",
            TunnelType::TunnelForward => "tunnel-forward",
        }
    }
}

/// 一跳候选：节点 + 该节点自己的监听端口。
///
/// beta 的端口来自 getNodePort(nodeId)，即每个中继/出口节点各自从节点端口池分配，
/// 不存在「全局 relay_port」。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Candidate {
    pub node: NodeId,
    pub port: u16,
}

impl Candidate {
    pub fn new(node: NodeId, port: u16) -> Self {
        Self { node, port }
    }

    /// 端口规划入口：从 beta 的 Integer 端口收窄，0 / 负数 / 超界一律 Err。
    pub fn from_beta(node: NodeId, port: i64, scope: &str) -> Result<Self, String> {
        Ok(Self {
            node: node.clone(),
            port: check_port(&node, port, scope)?,
        })
    }
}

/// beta 的隧道拓扑：入口只下发 AddChains；中间跳下发 AddChains + AddChainService；
/// 出口只下发 AddChainService。hops 的顺序就是跳序，不得按节点名重排。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Topology {
    pub entries: Vec<NodeId>,
    pub hops: Vec<Vec<Candidate>>,
    pub exits: Vec<Candidate>,
    /// 链的负载模式，取自 beta chain_tunnel.strategy（beta 每跳读该跳首行的值；
    /// 迁移不改写负载模式，因此按隧道级单值建模）。
    ///
    /// 未配置时保留 None / 空串，不在计划阶段编默认值：下发时 selector 里就不写
    /// strategy 这个键（见 Selector）。
    #[serde(default)]
    pub strategy: Option<String>,
}

impl Topology {
    /// 第 index 跳的候选；index == hops.len() 时表示出口。
    pub fn candidates_after(&self, index: usize) -> &[Candidate] {
        if index < self.hops.len() {
            &self.hops[index]
        } else {
            &self.exits
        }
    }

    /// 拓扑里出现的所有节点（入口、逐跳候选、出口），保持拓扑顺序。
    pub fn nodes(&self) -> Vec<NodeId> {
        let mut nodes = self.entries.clone();
        for hop in &self.hops {
            nodes.extend(hop.iter().map(|candidate| candidate.node.clone()));
        }
        nodes.extend(self.exits.iter().map(|candidate| candidate.node.clone()));
        nodes
    }

    fn validate(&self, label: &str) -> Result<(), String> {
        if self.entries.is_empty() {
            return Err(format!("{label}: 没有入口节点"));
        }
        if self.exits.is_empty() {
            return Err(format!("{label}: 没有出口节点"));
        }
        for (index, hop) in self.hops.iter().enumerate() {
            if hop.is_empty() {
                return Err(format!("{label}: 第 {} 跳没有候选节点", index + 1));
            }
        }
        let mut seen = BTreeSet::new();
        for node in self.nodes() {
            if !seen.insert(node.clone()) {
                return Err(format!(
                    "{label}: 节点 {node} 重复出现；beta 建隧道禁止重复 nodeId，链不可折返"
                ));
            }
        }
        for candidate in self.hops.iter().flatten().chain(self.exits.iter()) {
            check_port(&candidate.node, candidate.port as i64, label)?;
        }
        Ok(())
    }
}

/// 一条转发在某入口节点上的监听端口。beta 里 TCP 与 UDP 共用同一个 forwardPort.port，
/// 因此结构上只有一份端口；不同入口节点允许不同端口。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EntryPort {
    pub node: NodeId,
    pub port: u16,
}

impl EntryPort {
    pub fn new(node: NodeId, port: u16) -> Self {
        Self { node, port }
    }

    /// 端口规划入口：beta forward_port.port 是 Integer，越界一律 Err。
    pub fn from_beta(node: NodeId, port: i64, scope: &str) -> Result<Self, String> {
        Ok(Self {
            port: check_port_with(&node, port, scope, "入口")?,
            node,
        })
    }
}

/// beta forward.status 的取值（ForwardServiceImpl.FORWARD_STATUS_*：-1 异常 / 0 暂停 / 1 活跃）。
///
/// 只有 Active 表示「切流后应当继续对外服务」；暂停与异常一律不得被切流激活。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ForwardStatus {
    Error,
    Paused,
    Active,
}

impl ForwardStatus {
    pub const BETA_ERROR: i32 = -1;
    pub const BETA_PAUSED: i32 = 0;
    pub const BETA_ACTIVE: i32 = 1;

    /// beta 库里 forward.status 是 Integer；未知取值返回 None，由调用方决定怎么处理，
    /// 绝不默认成 Active（那正是「暂停被切流激活」的老 bug）。
    pub fn from_beta(status: i32) -> Option<Self> {
        match status {
            Self::BETA_ERROR => Some(ForwardStatus::Error),
            Self::BETA_PAUSED => Some(ForwardStatus::Paused),
            Self::BETA_ACTIVE => Some(ForwardStatus::Active),
            _ => None,
        }
    }

    pub fn as_beta(self) -> i32 {
        match self {
            ForwardStatus::Error => Self::BETA_ERROR,
            ForwardStatus::Paused => Self::BETA_PAUSED,
            ForwardStatus::Active => Self::BETA_ACTIVE,
        }
    }

    pub fn is_active(self) -> bool {
        matches!(self, ForwardStatus::Active)
    }

    pub fn label(self) -> &'static str {
        match self {
            ForwardStatus::Error => "error",
            ForwardStatus::Paused => "paused",
            ForwardStatus::Active => "active",
        }
    }
}

/// 一条业务转发。服务名沿用 beta 的 buildServiceName + GostUtil 后缀：
/// forward_user_userTunnel_tcp / _udp。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForwardSpec {
    pub forward_id: ForwardId,
    pub user_id: u64,
    pub user_tunnel_id: u64,
    pub protocols: Vec<Proto>,
    /// 新拓扑里每个入口节点各自的端口；节点集合必须等于 new.entries。
    pub entry_ports: Vec<EntryPort>,
    /// 旧拓扑里同一转发在入口节点上的端口，只用于新旧对比，不参与下发。
    pub old_entry_ports: Vec<EntryPort>,
    /// beta forward.status：暂停中的转发切流后必须重新暂停（ForwardServiceImpl:1084/1089）。
    pub status: ForwardStatus,
    /// beta forward.pause_reason：'expiry' / 'disabled' 是自动暂停，None 表示手动暂停或未标注。
    ///
    /// 切流阶段不区分原因：一律保持暂停。人工恢复还是按 pause_reason 自动恢复，
    /// 是后续恢复逻辑的事（ForwardServiceImpl:1597-1605 / :1644-1650）。
    pub pause_reason: Option<String>,
}

impl ForwardSpec {
    /// 切流后是否必须立刻暂停：只有 beta 的「活跃」允许迁移后继续对外服务；
    /// 暂停(0) 与异常(-1) 都按「不得激活」处理（fail closed）。
    pub fn needs_pause(&self) -> bool {
        !self.status.is_active()
    }

    /// 该转发是否处于 beta 的暂停态（0）；'-1' 异常态不算暂停，但同样需要 PauseService。
    pub fn is_paused(&self) -> bool {
        matches!(self.status, ForwardStatus::Paused)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MigrationPlan {
    pub op_id: OperationId,
    pub tunnel_id: String,
    pub old: Topology,
    pub new: Topology,
    pub forwards: Vec<ForwardSpec>,
}

impl MigrationPlan {
    pub fn chain_name(&self) -> String {
        format!("chains_{}", self.tunnel_id)
    }

    /// beta 的监听名 tunnelId_tls；名字里的 _tls 只是命名，不代表监听器是 TLS。
    pub fn listener_name(&self) -> String {
        format!("{}_tls", self.tunnel_id)
    }

    pub fn service_name(&self, forward: &ForwardSpec, proto: Proto) -> String {
        format!(
            "{}_{}_{}_{}",
            forward.forward_id.0,
            forward.user_id,
            forward.user_tunnel_id,
            proto.as_str()
        )
    }

    /// 某个拓扑会占据的全部节点资源（链 / 监听 / 服务），按 key 排序仅为报告稳定。
    fn resources_of(&self, topology: &Topology, old: bool) -> Vec<(ResourceKey, ResourceKind)> {
        let chain = self.chain_name();
        let listener = self.listener_name();
        let mut out: Vec<(ResourceKey, ResourceKind)> = Vec::new();
        for entry in &topology.entries {
            out.push((
                ResourceKey::new(entry.clone(), chain.clone()),
                ResourceKind::Chain {
                    chain: chain.clone(),
                },
            ));
        }
        for candidate in topology.hops.iter().flatten() {
            out.push((
                ResourceKey::new(candidate.node.clone(), chain.clone()),
                ResourceKind::Chain {
                    chain: chain.clone(),
                },
            ));
            out.push((
                ResourceKey::new(candidate.node.clone(), listener.clone()),
                ResourceKind::Listener {
                    listener: listener.clone(),
                    port: candidate.port,
                },
            ));
        }
        for candidate in &topology.exits {
            out.push((
                ResourceKey::new(candidate.node.clone(), listener.clone()),
                ResourceKind::Listener {
                    listener: listener.clone(),
                    port: candidate.port,
                },
            ));
        }
        for forward in &self.forwards {
            let ports = if old {
                &forward.old_entry_ports
            } else {
                &forward.entry_ports
            };
            for entry_port in ports {
                for proto in &forward.protocols {
                    let name = self.service_name(forward, *proto);
                    out.push((
                        ResourceKey::new(entry_port.node.clone(), name),
                        ResourceKind::Service {
                            forward: forward.forward_id,
                            port: entry_port.port,
                            proto: *proto,
                        },
                    ));
                }
            }
        }
        out.sort_by(|left, right| left.0.cmp(&right.0));
        out
    }

    pub fn new_resources(&self) -> Vec<(ResourceKey, ResourceKind)> {
        self.resources_of(&self.new, false)
    }

    /// 旧拓扑按同一套命名推导的资源集合。注意这只是「旧拓扑本该有哪些资源」，
    /// 不等于「这些资源确实归本操作所有」；归属必须由外部证据登记（attest_legacy）。
    pub fn legacy_resources(&self) -> Vec<(ResourceKey, ResourceKind)> {
        self.resources_of(&self.old, true)
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.tunnel_id.trim().is_empty() {
            return Err("tunnel_id 为空".to_string());
        }
        self.new.validate("新拓扑")?;
        self.old.validate("旧拓扑")?;

        let new_entries: BTreeSet<&NodeId> = self.new.entries.iter().collect();
        let old_entries: BTreeSet<&NodeId> = self.old.entries.iter().collect();
        let mut forward_ids = BTreeSet::new();
        for forward in &self.forwards {
            if !forward_ids.insert(forward.forward_id) {
                return Err(format!("转发 {} 重复声明", forward.forward_id.0));
            }
            if forward.protocols.is_empty() {
                return Err(format!("转发 {} 没有协议", forward.forward_id.0));
            }
            let mut protos = BTreeSet::new();
            for proto in &forward.protocols {
                if !protos.insert(*proto) {
                    return Err(format!(
                        "转发 {} 重复声明协议 {}",
                        forward.forward_id.0,
                        proto.as_str()
                    ));
                }
            }
            check_entry_ports(forward, &forward.entry_ports, &new_entries, "新拓扑")?;
            check_entry_ports(forward, &forward.old_entry_ports, &old_entries, "旧拓扑")?;
        }
        Ok(())
    }

    /// beta round44 口径的 plan 入口：先按 tunnel.type fail-closed 拦一遍，
    /// 再跑既有拓扑/端口校验。type 为 NULL 或非 1/2 时整条隧道不许继续规划。
    pub fn validate_with_tunnel_type(
        &self,
        tunnel_type: Option<i32>,
    ) -> Result<TunnelType, String> {
        let kind = TunnelType::from_beta(tunnel_type)?;
        self.validate()?;
        Ok(kind)
    }
}

fn check_entry_ports(
    forward: &ForwardSpec,
    ports: &[EntryPort],
    entries: &BTreeSet<&NodeId>,
    label: &str,
) -> Result<(), String> {
    let mut seen: BTreeSet<NodeId> = BTreeSet::new();
    for entry_port in ports {
        if entry_port.port == 0 {
            return Err(format!(
                "转发 {} 在节点 {} 上的入口端口为 0（{label}）",
                forward.forward_id.0, entry_port.node
            ));
        }
        if !entries.contains(&entry_port.node) {
            return Err(format!(
                "转发 {} 的入口端口落在非入口节点 {} 上（{label}）",
                forward.forward_id.0, entry_port.node
            ));
        }
        if !seen.insert(entry_port.node.clone()) {
            return Err(format!(
                "转发 {} 在节点 {} 上重复声明入口端口（{label}）",
                forward.forward_id.0, entry_port.node
            ));
        }
    }
    for entry in entries {
        if !seen.contains(*entry) {
            return Err(format!(
                "转发 {} 缺少入口节点 {} 的端口（{label}）",
                forward.forward_id.0, entry
            ));
        }
    }
    Ok(())
}
