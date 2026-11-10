//! beta（SpringBoot 版）的真实发布口径：命名、跳序、端口归属。
//!
//! 这一层只做纯函数推导，不读数据库、不下发节点，作用是让迁移原型的资源名与跳序
//! 与线上 beta 逐字一致，而不是各写一套「差不多」的规则。对齐依据（只读参考）：
//!
//! - ForwardServiceImpl.java:2187-2190 buildServiceName：<forwardId>_<userId>_<userTunnelId>，
//!   userTunnel 缺失时 userTunnelId 写 0；GostUtil.java:204-238 buildForwardService 再拼 _tcp / _udp。
//! - GostUtil.java:66-114 CreateChains：链名 chains_<tunnelId>、hop 名 hop_<tunnelId>、
//!   节点名 node_<nodeId>、地址 <serverIp>:<port>、dialer #<protocol>。
//! - ForwardServiceImpl.java:3430-3443 expectedChainNodes：node_<nodeId>@<serverIp>:<port>#<protocol>。
//! - GostUtil.java:137-166 CreateChainService：中继服务名 <tunnelId>_tls，端口取该行自己的 port，
//!   handler 挂 chains_<tunnelId> 只发生在 chainType == 2；出口(3) 不挂链。
//! - TunnelServiceImpl.java:258-312 publishChains：入口只建链（指向第一跳，无中继时指向出口）、
//!   中继建链 + 服务、出口只建服务。
//! - TunnelServiceImpl.java:92-107 每跳 inx 从 1 递增；:700-723 按 inx 分组升序（null 归 0）。
//!
//! 三条不猜的硬约束：端口只来自行本身的 port（缺就报不可发布）、跳序不依赖输入数组顺序、
//! 暂停转发不生成入口服务但端口记录照样占位。

use std::collections::{BTreeMap, BTreeSet};
use std::fmt;

use serde::{Deserialize, Serialize};

use crate::ids::NodeId;
use crate::plan::{Candidate, EntryPort, ForwardStatus, Proto, Selector, Topology};

/// beta chain_tunnel.chain_type 的取值（TunnelServiceImpl.java:183-212 按位置校验）。
pub const CHAIN_TYPE_ENTRY: i32 = 1;
pub const CHAIN_TYPE_RELAY: i32 = 2;
pub const CHAIN_TYPE_EXIT: i32 = 3;

/// protocol 留空时 beta 按 tls 补全（TunnelServiceImpl.java:240-243），读库同样按此归一。
pub const DEFAULT_CHAIN_PROTOCOL: &str = "tls";

/// 一行 chain_tunnel 在这条隧道里的角色。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ChainRole {
    Entry,
    Relay,
    Exit,
}

impl ChainRole {
    pub fn from_chain_type(chain_type: i32) -> Option<Self> {
        match chain_type {
            CHAIN_TYPE_ENTRY => Some(ChainRole::Entry),
            CHAIN_TYPE_RELAY => Some(ChainRole::Relay),
            CHAIN_TYPE_EXIT => Some(ChainRole::Exit),
            _ => None,
        }
    }

    pub fn chain_type(self) -> i32 {
        match self {
            ChainRole::Entry => CHAIN_TYPE_ENTRY,
            ChainRole::Relay => CHAIN_TYPE_RELAY,
            ChainRole::Exit => CHAIN_TYPE_EXIT,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            ChainRole::Entry => "entry",
            ChainRole::Relay => "relay",
            ChainRole::Exit => "exit",
        }
    }
}

/// beta chain_tunnel 的一行。字段与库表同名同义，端口、协议、inx 都按行各算各的。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChainRow {
    pub id: u64,
    pub tunnel_id: u64,
    pub node_id: NodeId,
    pub chain_type: i32,
    /// 中继组的跳序号（1 起，TunnelServiceImpl.java:94/103/106）；库里可空，null 归 0 组。
    pub inx: Option<i32>,
    /// 该行自己的监听端口。入口行按设计为空：入口端口来自 forward_port，不是 chain_tunnel。
    pub port: Option<u16>,
    pub protocol: Option<String>,
    pub strategy: Option<String>,
}

impl ChainRow {
    pub fn new(id: u64, tunnel_id: u64, node_id: NodeId, role: ChainRole) -> Self {
        Self {
            id,
            tunnel_id,
            node_id,
            chain_type: role.chain_type(),
            inx: None,
            port: None,
            protocol: None,
            strategy: None,
        }
    }

    pub fn with_inx(mut self, inx: i32) -> Self {
        self.inx = Some(inx);
        self
    }

    pub fn with_port(mut self, port: u16) -> Self {
        self.port = Some(port);
        self
    }

    pub fn with_protocol(mut self, protocol: impl Into<String>) -> Self {
        self.protocol = Some(protocol.into());
        self
    }

    pub fn with_strategy(mut self, strategy: impl Into<String>) -> Self {
        self.strategy = Some(strategy.into());
        self
    }

    pub fn role(&self) -> Option<ChainRole> {
        ChainRole::from_chain_type(self.chain_type)
    }

    /// 未配置协议的跳按 beta 的缺省口径当 tls，不自造别的值。
    pub fn protocol(&self) -> &str {
        match self.protocol.as_deref().map(str::trim) {
            Some(protocol) if !protocol.is_empty() => protocol,
            _ => DEFAULT_CHAIN_PROTOCOL,
        }
    }

    /// 分组键：null 的 inx 归 0（TunnelServiceImpl.java:711-716）。
    fn group_key(&self) -> i32 {
        self.inx.unwrap_or(0)
    }
}

/// 不可发布的计划。出现这些错误时调用方必须停下，不得用默认值把端口/地址补齐。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PublishError {
    /// 该行没有端口。beta 只对缺端口行自动分配，读存量时不许猜。
    MissingPort {
        row: u64,
        node: NodeId,
        role: &'static str,
    },
    MissingServerIp {
        row: u64,
        node: NodeId,
    },
    UnknownChainType {
        row: u64,
        chain_type: i32,
    },
    /// 同一批行来自不同隧道：资源名会拼出 chains_<别的 id>，直接拒。
    MixedTunnel {
        expected: u64,
        found: u64,
    },
    NoEntry,
    NoExit,
    /// beta 建隧道禁止同一节点重复出现（TunnelServiceImpl.java:119-121 节点重复）。
    DuplicateNode {
        node: NodeId,
    },
    /// 级联删除时喂进来的行属于别的隧道：删下去会拆掉别人隧道在节点上的资源。
    ForeignRow {
        table: &'static str,
        row: u64,
        expected: u64,
        found: u64,
    },
    /// 有转发要拆，但隧道没有入口行（chain_type=1）：定位不到入口服务所在的节点。
    MissingEntryNode {
        forward: u64,
    },
    /// forward_port 行挂在非入口节点上：行集错配，猜不出该删哪个服务。
    UnboundEntryPort {
        forward: u64,
        node: NodeId,
    },
}

impl fmt::Display for PublishError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PublishError::MissingPort { row, node, role } => write!(
                f,
                "chain_tunnel 行 {row}（节点 {node}，{role}）没有端口，不可发布：迁移不猜端口"
            ),
            PublishError::MissingServerIp { row, node } => {
                write!(
                    f,
                    "chain_tunnel 行 {row}（节点 {node}）缺少 serverIp，算不出跳地址"
                )
            }
            PublishError::UnknownChainType { row, chain_type } => {
                write!(
                    f,
                    "chain_tunnel 行 {row} 的 chain_type={chain_type} 不是 1/2/3"
                )
            }
            PublishError::MixedTunnel { expected, found } => {
                write!(f, "chain_tunnel 行混用了隧道 {expected} 与 {found}")
            }
            PublishError::NoEntry => write!(f, "没有入口行（chain_type=1），不可发布"),
            PublishError::NoExit => write!(f, "没有出口行（chain_type=3），不可发布"),
            PublishError::DuplicateNode { node } => {
                write!(f, "节点 {node} 在一条隧道里重复出现，链会折返")
            }
            PublishError::ForeignRow {
                table,
                row,
                expected,
                found,
            } => write!(
                f,
                "{table} 行 {row} 属于隧道 {found}，与本次要删的隧道 {expected} 不一致：拒绝拆别的隧道的资源"
            ),
            PublishError::MissingEntryNode { forward } => write!(
                f,
                "转发 {forward} 定位不到入口节点（隧道没有 chain_type=1 的 chain_tunnel 行），入口服务拆不掉：迁移不猜节点"
            ),
            PublishError::UnboundEntryPort { forward, node } => write!(
                f,
                "转发 {forward} 的 forward_port 行挂在节点 {node}，但该节点不是入口行（chain_type=1）：行集错配"
            ),
        }
    }
}

impl std::error::Error for PublishError {}

/// chain 里的一跳。element() 就是 expectedChainNodes 的摘要口径。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HopNode {
    pub node: NodeId,
    /// 已按 processServerAddress 处理：IPv6 补方括号，其余原样。
    pub addr: String,
    pub port: u16,
    pub protocol: String,
}

impl HopNode {
    pub fn name(&self) -> String {
        format!("node_{}", self.node)
    }

    /// node_<nodeId>@<serverIp>:<port>#<protocol>（ForwardServiceImpl.java:3440-3442）。
    pub fn element(&self) -> String {
        format!("{}@{}#{}", self.name(), self.addr, self.protocol)
    }
}

/// 一条链的下发体（GostUtil.CreateChains）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChainSpec {
    /// chains_<tunnelId>
    pub name: String,
    /// hop_<tunnelId>
    pub hop_name: String,
    /// 取自下一跳首行的 strategy（GostUtil.java:98），未配置则整键省略。
    pub selector: Selector,
    pub nodes: Vec<HopNode>,
}

impl ChainSpec {
    pub fn elements(&self) -> Vec<String> {
        self.nodes.iter().map(HopNode::element).collect()
    }
}

/// 中继/出口节点上的 <tunnelId>_tls 服务。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RelayService {
    pub name: String,
    pub port: u16,
    pub protocol: String,
    /// 只有中继(2) 挂 chains_<tunnelId>；出口(3) 为 None（GostUtil.java:152-157）。
    pub chain: Option<String>,
}

/// 一个节点在这条隧道上该有的东西：入口只有链，中继有链 + 服务，出口只有服务。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodePlan {
    pub node: NodeId,
    pub role: ChainRole,
    pub chain: Option<ChainSpec>,
    pub relay_service: Option<RelayService>,
}

/// 一条隧道按 beta 口径解析出来的分层结构，plan() 才是逐节点结果。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TunnelPublish {
    pub tunnel_id: u64,
    pub entries: Vec<ChainRow>,
    /// 中继按 inx 升序分组（null→0），组内按行 id 升序。
    pub groups: Vec<Vec<ChainRow>>,
    pub exits: Vec<ChainRow>,
}

impl TunnelPublish {
    /// 从 chain_tunnel 行解析拓扑。输出与输入顺序无关：入口/出口按 id、中继按 (inx, id)。
    pub fn from_rows(rows: &[ChainRow]) -> Result<Self, PublishError> {
        let mut entries: Vec<ChainRow> = Vec::new();
        let mut groups: BTreeMap<i32, Vec<ChainRow>> = BTreeMap::new();
        let mut exits: Vec<ChainRow> = Vec::new();
        let mut tunnel_id: Option<u64> = None;

        for row in rows {
            let role = row.role().ok_or(PublishError::UnknownChainType {
                row: row.id,
                chain_type: row.chain_type,
            })?;
            match tunnel_id {
                None => tunnel_id = Some(row.tunnel_id),
                Some(expected) if expected != row.tunnel_id => {
                    return Err(PublishError::MixedTunnel {
                        expected,
                        found: row.tunnel_id,
                    })
                }
                Some(_) => {}
            }
            match role {
                ChainRole::Entry => entries.push(row.clone()),
                ChainRole::Relay => groups.entry(row.group_key()).or_default().push(row.clone()),
                ChainRole::Exit => exits.push(row.clone()),
            }
        }

        if entries.is_empty() {
            return Err(PublishError::NoEntry);
        }
        if exits.is_empty() {
            return Err(PublishError::NoExit);
        }

        entries.sort_by_key(|row| row.id);
        exits.sort_by_key(|row| row.id);
        let mut grouped: Vec<Vec<ChainRow>> = groups.into_values().collect();
        for group in &mut grouped {
            group.sort_by_key(|row| row.id);
        }

        let mut seen: BTreeSet<&NodeId> = BTreeSet::new();
        for row in entries
            .iter()
            .chain(grouped.iter().flatten())
            .chain(exits.iter())
        {
            if !seen.insert(&row.node_id) {
                return Err(PublishError::DuplicateNode {
                    node: row.node_id.clone(),
                });
            }
        }

        Ok(Self {
            tunnel_id: tunnel_id.expect("entries 非空则 tunnel_id 已确定"),
            entries,
            groups: grouped,
            exits,
        })
    }

    /// 全量行序：入口 → 中继逐跳 → 出口，与 beta 的下发/摘要遍历顺序一致。
    pub fn ordered_rows(&self) -> Vec<&ChainRow> {
        let mut ordered: Vec<&ChainRow> = Vec::new();
        ordered.extend(self.entries.iter());
        for group in &self.groups {
            ordered.extend(group.iter());
        }
        ordered.extend(self.exits.iter());
        ordered
    }

    /// 逐节点计划。servers 是 nodeId → serverIp：跳地址只能从节点表取，不从行里猜。
    pub fn plan(&self, servers: &BTreeMap<NodeId, String>) -> Result<Vec<NodePlan>, PublishError> {
        let chain = chain_name(self.tunnel_id);
        let relay = relay_service_name(self.tunnel_id);
        let mut plans: Vec<NodePlan> = Vec::new();

        // 入口指向第一跳；没有中继组时直接指向出口（TunnelServiceImpl.java:268-277）。
        let entry_next = self
            .groups
            .first()
            .map(Vec::as_slice)
            .unwrap_or(self.exits.as_slice());
        let entry_chain = self.chain_spec(&chain, entry_next, servers)?;
        for row in &self.entries {
            plans.push(NodePlan {
                node: row.node_id.clone(),
                role: ChainRole::Entry,
                chain: Some(entry_chain.clone()),
                relay_service: None,
            });
        }

        for (index, group) in self.groups.iter().enumerate() {
            let next = self
                .groups
                .get(index + 1)
                .map(Vec::as_slice)
                .unwrap_or(self.exits.as_slice());
            let spec = self.chain_spec(&chain, next, servers)?;
            for row in group {
                let port = require_port(row)?;
                plans.push(NodePlan {
                    node: row.node_id.clone(),
                    role: ChainRole::Relay,
                    chain: Some(spec.clone()),
                    relay_service: Some(RelayService {
                        name: relay.clone(),
                        port,
                        protocol: row.protocol().to_string(),
                        chain: Some(chain.clone()),
                    }),
                });
            }
        }

        for row in &self.exits {
            let port = require_port(row)?;
            plans.push(NodePlan {
                node: row.node_id.clone(),
                role: ChainRole::Exit,
                chain: None,
                relay_service: Some(RelayService {
                    name: relay.clone(),
                    port,
                    protocol: row.protocol().to_string(),
                    chain: None,
                }),
            });
        }

        Ok(plans)
    }

    /// 把 beta 行折算成状态机认识的 Topology。Topology 只有隧道级 strategy，
    /// 取入口链那一份（即第一跳首行），与引擎下发入口链的口径一致。
    pub fn to_topology(&self) -> Result<Topology, PublishError> {
        let mut hops: Vec<Vec<Candidate>> = Vec::with_capacity(self.groups.len());
        for group in &self.groups {
            let mut candidates: Vec<Candidate> = Vec::with_capacity(group.len());
            for row in group {
                candidates.push(Candidate::new(row.node_id.clone(), require_port(row)?));
            }
            hops.push(candidates);
        }
        let mut exits: Vec<Candidate> = Vec::with_capacity(self.exits.len());
        for row in &self.exits {
            exits.push(Candidate::new(row.node_id.clone(), require_port(row)?));
        }
        Ok(Topology {
            entries: self.entries.iter().map(|row| row.node_id.clone()).collect(),
            hops,
            exits,
            strategy: self.entry_strategy(),
        })
    }

    fn entry_strategy(&self) -> Option<String> {
        let next = self
            .groups
            .first()
            .map(Vec::as_slice)
            .unwrap_or(self.exits.as_slice());
        next.first()
            .and_then(|row| row.strategy.clone())
            .filter(|strategy| !strategy.trim().is_empty())
    }

    fn chain_spec(
        &self,
        chain: &str,
        next: &[ChainRow],
        servers: &BTreeMap<NodeId, String>,
    ) -> Result<ChainSpec, PublishError> {
        let mut nodes: Vec<HopNode> = Vec::with_capacity(next.len());
        for row in next {
            let port = require_port(row)?;
            let server_ip =
                servers
                    .get(&row.node_id)
                    .ok_or_else(|| PublishError::MissingServerIp {
                        row: row.id,
                        node: row.node_id.clone(),
                    })?;
            nodes.push(HopNode {
                node: row.node_id.clone(),
                addr: process_server_address(server_ip, port),
                port,
                protocol: row.protocol().to_string(),
            });
        }
        Ok(ChainSpec {
            name: chain.to_string(),
            hop_name: hop_name(self.tunnel_id),
            selector: Selector::for_hop(next.first().and_then(|row| row.strategy.as_deref())),
            nodes,
        })
    }
}

fn require_port(row: &ChainRow) -> Result<u16, PublishError> {
    match row.port {
        Some(port) if port > 0 => Ok(port),
        _ => Err(PublishError::MissingPort {
            row: row.id,
            node: row.node_id.clone(),
            role: row.role().map(ChainRole::label).unwrap_or("unknown"),
        }),
    }
}

/// beta forward 的一行。status 是 Integer（-1 异常 / 0 暂停 / 1 活跃）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForwardRow {
    pub forward_id: u64,
    pub user_id: u64,
    /// userTunnel 缺失时为 None，服务名里写 0（ForwardServiceImpl.java:2188）。
    pub user_tunnel_id: Option<u64>,
    pub status: i32,
    pub protocols: Vec<Proto>,
}

impl ForwardRow {
    pub fn new(forward_id: u64, user_id: u64, user_tunnel_id: Option<u64>, status: i32) -> Self {
        Self {
            forward_id,
            user_id,
            user_tunnel_id,
            status,
            protocols: vec![Proto::Tcp, Proto::Udp],
        }
    }

    pub fn with_protocols(mut self, protocols: Vec<Proto>) -> Self {
        self.protocols = protocols;
        self
    }

    pub fn status_kind(&self) -> Option<ForwardStatus> {
        ForwardStatus::from_beta(self.status)
    }

    /// 只有 status == 1 的转发才下发入口服务：暂停态转发不在补齐/重建范围
    /// （ForwardServiceImpl.java:1599-1602 的 round27 注释）。
    pub fn is_active(&self) -> bool {
        self.status == ForwardStatus::BETA_ACTIVE
    }

    pub fn base_name(&self) -> String {
        forward_service_base(self.forward_id, self.user_id, self.user_tunnel_id)
    }

    pub fn service_name(&self, proto: Proto) -> String {
        forward_service_name(self.forward_id, self.user_id, self.user_tunnel_id, proto)
    }
}

/// 一条转发在某个入口节点上的服务（<name>_<proto>，端口来自 forward_port 行）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EntryService {
    pub name: String,
    pub node: NodeId,
    pub port: u16,
    pub proto: Proto,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForwardPlan {
    pub forward_id: u64,
    pub base_name: String,
    pub active: bool,
    /// forward_port 行：暂停的转发也保留，端口仍算已分配。
    pub entry_ports: Vec<EntryPort>,
    /// 暂停的转发为空：不生成入口服务。
    pub services: Vec<EntryService>,
}

/// 按 beta 口径算一条转发的入口服务集合。
pub fn plan_forward(row: &ForwardRow, entry_ports: &[EntryPort]) -> ForwardPlan {
    let mut ports = entry_ports.to_vec();
    ports.sort_by(|left, right| left.node.cmp(&right.node).then(left.port.cmp(&right.port)));

    let mut services: Vec<EntryService> = Vec::new();
    if row.is_active() {
        for entry_port in &ports {
            for proto in &row.protocols {
                services.push(EntryService {
                    name: format!("{}_{}", row.base_name(), proto.as_str()),
                    node: entry_port.node.clone(),
                    port: entry_port.port,
                    proto: *proto,
                });
            }
        }
    }

    ForwardPlan {
        forward_id: row.forward_id,
        base_name: row.base_name(),
        active: row.is_active(),
        entry_ports: ports,
        services,
    }
}

/// 端口台账：所有 forward_port 行都算已分配，暂停的转发不释放端口。
pub fn allocated_ports(plans: &[ForwardPlan]) -> BTreeSet<(NodeId, u16)> {
    let mut out: BTreeSet<(NodeId, u16)> = BTreeSet::new();
    for plan in plans {
        for entry_port in &plan.entry_ports {
            out.insert((entry_port.node.clone(), entry_port.port));
        }
    }
    out
}

/// <forwardId>_<userId>_<userTunnelId>（ForwardServiceImpl.java:2187-2190）。
pub fn forward_service_base(forward_id: u64, user_id: u64, user_tunnel_id: Option<u64>) -> String {
    format!("{forward_id}_{user_id}_{}", user_tunnel_id.unwrap_or(0))
}

/// 入口服务名 = base + _tcp / _udp（GostUtil.java:207）。
pub fn forward_service_name(
    forward_id: u64,
    user_id: u64,
    user_tunnel_id: Option<u64>,
    proto: Proto,
) -> String {
    format!(
        "{}_{}",
        forward_service_base(forward_id, user_id, user_tunnel_id),
        proto.as_str()
    )
}

/// chains_<tunnelId>（GostUtil.java:110）。
pub fn chain_name(tunnel_id: u64) -> String {
    format!("chains_{tunnel_id}")
}

/// hop_<tunnelId>（GostUtil.java:89）。
pub fn hop_name(tunnel_id: u64) -> String {
    format!("hop_{tunnel_id}")
}

/// 中继/出口服务名 <tunnelId>_tls（GostUtil.java:141）。
pub fn relay_service_name(tunnel_id: u64) -> String {
    format!("{tunnel_id}_tls")
}

/// node_<nodeId>@<serverIp>:<port>#<protocol>（ForwardServiceImpl.java:3440-3442）。
pub fn hop_element(node: &NodeId, server_ip: &str, port: u16, protocol: &str) -> String {
    format!(
        "node_{node}@{}#{protocol}",
        process_server_address(server_ip, port)
    )
}

/// 与 GostUtil.processServerAddress 同口径：IPv6 补方括号，其余原样（GostUtil.java:444-470）。
pub fn process_server_address(server_ip: &str, port: u16) -> String {
    let host = server_ip.trim();
    if host.starts_with('[') && host.ends_with(']') {
        return format!("{host}:{port}");
    }
    if is_ipv6(host) {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    }
}

fn is_ipv6(host: &str) -> bool {
    host.matches(':').count() >= 2
}

// ---- 级联删除：deleteTunnel 的析构生命周期 ---------------------------------
//
// Java 出处（.worktrees/beta/springboot-backend/src/main/java/com/admin）：
// - TunnelServiceImpl.java:1325-1362 deleteTunnel：先把 forward / chain_tunnel / speed_limit
//   行集读全（:1330-1333，purge 之后查只会拿到空集），再逐条 deleteForward、一次性事务清库行、
//   最后对每个出现过的节点清 gost 资源（:1344-1347 不再按 chain_type 猜该节点有什么）。
// - ForwardServiceImpl.java:1317-1350 deleteForward：取 chain_type=1 的行，按名字删
//   <fid>_<uid>_<utid>_tcp / _udp（不看 status），非 strict 下节点失败也照样删库行。
// - CascadeDeletePurger.java:79-89 purgeTunnelDatabaseRecords：forward → forward_port →
//   user_tunnel → speed_limit → chain_tunnel → tunnel，隧道行最后，整段一个事务。
// - TunnelServiceImpl.java:1368-1377 cleanTunnelResources：service <tid>_tls 先于 chain chains_<tid>
//   （service 依赖 chain）；:1386-1393 cleanTunnelLimiters：limiter 名 = speed_limit.id。
//
// 与原型的差异只有一处，写在计划 notes 里：beta 每个节点是「service → chain → limiter」，
// 这里按任务要求把限流器统一提到最前（互不依赖，提前拆不会漏 limiter）。

/// beta speed_limit 的一行（entity/SpeedLimit.java）。限速规则归隧道所有，
/// 行没了名字就没人认领，节点上的 limiter 会永久残留。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SpeedLimitRow {
    pub id: u64,
    pub tunnel_id: u64,
    pub name: String,
    /// MB/s；beta 建 limiter 时写成 "$ <speed>MB <speed>MB"（GostUtil.createLimiterData）。
    pub speed: u32,
}

impl SpeedLimitRow {
    pub fn new(id: u64, tunnel_id: u64, name: impl Into<String>, speed: u32) -> Self {
        Self {
            id,
            tunnel_id,
            name: name.into(),
            speed,
        }
    }

    /// beta 的 limiter 名就是规则 id（SpeedLimitServiceImpl.java 建/删都用 id 当 name）。
    pub fn limiter_name(&self) -> String {
        self.id.to_string()
    }
}

/// beta user_tunnel 的一行，只保留级联删除要用的字段。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UserTunnelRow {
    pub id: u64,
    pub user_id: u64,
    pub tunnel_id: u64,
    /// speed_limit.id；库里可空。
    pub speed_id: Option<u64>,
}

impl UserTunnelRow {
    pub fn new(id: u64, user_id: u64, tunnel_id: u64, speed_id: Option<u64>) -> Self {
        Self {
            id,
            user_id,
            tunnel_id,
            speed_id,
        }
    }
}

/// 一条要拆的转发：forward 行 + 它的 forward_port 行。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForwardCascade {
    pub row: ForwardRow,
    pub entry_ports: Vec<EntryPort>,
}

impl ForwardCascade {
    pub fn new(row: ForwardRow, entry_ports: Vec<EntryPort>) -> Self {
        Self { row, entry_ports }
    }

    /// 从已算好的入口计划取端口，避免调用方自己再拼一遍。
    pub fn from_plan(row: ForwardRow, plan: &ForwardPlan) -> Self {
        Self {
            row,
            entry_ports: plan.entry_ports.clone(),
        }
    }
}

/// 一次 deleteTunnel 的输入：全部来自落库前的行快照（TunnelServiceImpl.java:1330-1333）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CascadeDeleteInput {
    pub tunnel_id: u64,
    pub forwards: Vec<ForwardCascade>,
    pub chain_rows: Vec<ChainRow>,
    pub speed_limits: Vec<SpeedLimitRow>,
    pub user_tunnels: Vec<UserTunnelRow>,
}

impl CascadeDeleteInput {
    pub fn new(tunnel_id: u64) -> Self {
        Self {
            tunnel_id,
            forwards: Vec::new(),
            chain_rows: Vec::new(),
            speed_limits: Vec::new(),
            user_tunnels: Vec::new(),
        }
    }

    pub fn with_forwards(mut self, forwards: Vec<ForwardCascade>) -> Self {
        self.forwards = forwards;
        self
    }

    pub fn with_chain_rows(mut self, chain_rows: Vec<ChainRow>) -> Self {
        self.chain_rows = chain_rows;
        self
    }

    pub fn with_speed_limits(mut self, speed_limits: Vec<SpeedLimitRow>) -> Self {
        self.speed_limits = speed_limits;
        self
    }

    pub fn with_user_tunnels(mut self, user_tunnels: Vec<UserTunnelRow>) -> Self {
        self.user_tunnels = user_tunnels;
        self
    }
}

/// 析构阶段。顺序即依赖：限流器 → 转发入口服务 → 库行 → 中继服务 → 链。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum CascadePhase {
    /// 限流器：挂不到别的资源上，先拆不会留下孤儿。
    Limiter = 0,
    /// 转发自带的入口服务 <name>_tcp / _udp，beta 逐条 deleteForward 时拆。
    ForwardService = 1,
    /// 多表库行：beta 一个事务提交，隧道行最后（CascadeDeletePurger.java:79-89）。
    DatabaseRow = 2,
    /// 隧道级服务 <tid>_tls：service 依赖 chain，必须先于链拆（TunnelServiceImpl.java:1368-1377）。
    RelayService = 3,
    /// 隧道级链 chains_<tid>。
    Chain = 4,
}

impl CascadePhase {
    pub fn rank(self) -> u8 {
        self as u8
    }

    pub fn label(self) -> &'static str {
        match self {
            CascadePhase::Limiter => "limiter",
            CascadePhase::ForwardService => "forward-service",
            CascadePhase::DatabaseRow => "db-row",
            CascadePhase::RelayService => "relay-service",
            CascadePhase::Chain => "chain",
        }
    }

    /// 需要下发的节点动作 vs 只在库里做的行删除。
    pub fn is_node_action(self) -> bool {
        !matches!(self, CascadePhase::DatabaseRow)
    }
}

/// 库行删除（CascadeDeletePurger 的口径）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RowDelete {
    ForwardPort { forward: u64 },
    Forward { forward: u64 },
    UserTunnel { user_tunnel: u64 },
    SpeedLimit { speed_limit: u64 },
    ChainTunnel { chain_row: u64 },
    Tunnel { tunnel: u64 },
}

impl RowDelete {
    pub fn table(&self) -> &'static str {
        match self {
            RowDelete::ForwardPort { .. } => "forward_port",
            RowDelete::Forward { .. } => "forward",
            RowDelete::UserTunnel { .. } => "user_tunnel",
            RowDelete::SpeedLimit { .. } => "speed_limit",
            RowDelete::ChainTunnel { .. } => "chain_tunnel",
            RowDelete::Tunnel { .. } => "tunnel",
        }
    }

    pub fn row_id(&self) -> u64 {
        match self {
            RowDelete::ForwardPort { forward } => *forward,
            RowDelete::Forward { forward } => *forward,
            RowDelete::UserTunnel { user_tunnel } => *user_tunnel,
            RowDelete::SpeedLimit { speed_limit } => *speed_limit,
            RowDelete::ChainTunnel { chain_row } => *chain_row,
            RowDelete::Tunnel { tunnel } => *tunnel,
        }
    }
}

/// 一个析构动作。节点动作带 node，库行只带表与主键。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum CascadeStepKind {
    Limiter {
        node: NodeId,
        limiter: String,
        speed_mbps: u32,
    },
    ForwardService {
        node: NodeId,
        forward: u64,
        service: String,
        /// 端口只作观测：beta 删服务只按名字下发（GostUtil.DeleteService），缺 forward_port 不影响拆。
        port: Option<u16>,
    },
    Row(RowDelete),
    RelayService {
        node: NodeId,
        service: String,
        port: Option<u16>,
    },
    Chains {
        node: NodeId,
        chain: String,
    },
}

impl CascadeStepKind {
    pub fn phase(&self) -> CascadePhase {
        match self {
            CascadeStepKind::Limiter { .. } => CascadePhase::Limiter,
            CascadeStepKind::ForwardService { .. } => CascadePhase::ForwardService,
            CascadeStepKind::Row(_) => CascadePhase::DatabaseRow,
            CascadeStepKind::RelayService { .. } => CascadePhase::RelayService,
            CascadeStepKind::Chains { .. } => CascadePhase::Chain,
        }
    }

    pub fn node(&self) -> Option<&NodeId> {
        match self {
            CascadeStepKind::Limiter { node, .. }
            | CascadeStepKind::ForwardService { node, .. }
            | CascadeStepKind::RelayService { node, .. }
            | CascadeStepKind::Chains { node, .. } => Some(node),
            CascadeStepKind::Row(_) => None,
        }
    }

    pub fn label(&self) -> String {
        match self {
            CascadeStepKind::Limiter { limiter, .. } => format!("limiter {limiter}"),
            CascadeStepKind::ForwardService { service, .. } => format!("service {service}"),
            CascadeStepKind::RelayService { service, .. } => format!("service {service}"),
            CascadeStepKind::Chains { chain, .. } => format!("chain {chain}"),
            CascadeStepKind::Row(row) => format!("{}#{}", row.table(), row.row_id()),
        }
    }
}

/// 计划里的一步。depends_on 只指向本计划内更早的下标（同节点、更早阶段）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CascadeStep {
    pub phase: CascadePhase,
    pub depends_on: Vec<usize>,
    pub kind: CascadeStepKind,
}

impl CascadeStep {
    pub fn node(&self) -> Option<&NodeId> {
        self.kind.node()
    }
}

/// 隧道删除的完整析构计划：节点动作与库行在同一条有序序列里，依赖显式化。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CascadeDeletePlan {
    pub tunnel_id: u64,
    pub steps: Vec<CascadeStep>,
    pub notes: Vec<String>,
}

impl CascadeDeletePlan {
    pub fn phase_steps(&self, phase: CascadePhase) -> Vec<(usize, &CascadeStep)> {
        self.steps
            .iter()
            .enumerate()
            .filter(|(_, step)| step.phase == phase)
            .collect()
    }

    pub fn node_steps(&self, node: &NodeId) -> Vec<(usize, &CascadeStep)> {
        self.steps
            .iter()
            .enumerate()
            .filter(|(_, step)| step.node() == Some(node))
            .collect()
    }

    pub fn rows(&self) -> Vec<(usize, &RowDelete)> {
        self.steps
            .iter()
            .enumerate()
            .filter_map(|(index, step)| match &step.kind {
                CascadeStepKind::Row(row) => Some((index, row)),
                _ => None,
            })
            .collect()
    }

    pub fn row_ids(&self, table: &str) -> Vec<u64> {
        self.rows()
            .into_iter()
            .filter(|(_, row)| row.table() == table)
            .map(|(_, row)| row.row_id())
            .collect()
    }

    /// 计划自检：依赖必须指向更早下标，阶段不能倒挂，也不能跨节点串行。
    /// 返回 (本步下标, 违规依赖下标)；空表示拓扑顺序成立。
    pub fn ordering_violations(&self) -> Vec<(usize, usize)> {
        let mut violations = Vec::new();
        for (index, step) in self.steps.iter().enumerate() {
            for dependency in &step.depends_on {
                let earlier = match self.steps.get(*dependency) {
                    Some(earlier) if *dependency < index => earlier,
                    _ => {
                        violations.push((index, *dependency));
                        continue;
                    }
                };
                if earlier.phase.rank() > step.phase.rank() {
                    violations.push((index, *dependency));
                    continue;
                }
                if let (Some(left), Some(right)) = (earlier.node(), step.node()) {
                    if left != right {
                        violations.push((index, *dependency));
                    }
                }
            }
        }
        violations
    }

    /// 同一节点上的析构序列（按计划顺序），供运维对照 leftovers。
    pub fn node_sequence(&self, node: &NodeId) -> Vec<String> {
        self.node_steps(node)
            .into_iter()
            .map(|(_, step)| step.kind.label())
            .collect()
    }
}

/// 一个节点上的隧道级资源序列：service <tid>_tls → chain chains_<tid>
/// （TunnelServiceImpl.java:1368-1377：service 依赖 chain，先删 service）。
/// 删除隧道与拓扑编辑清旧节点共用这一份口径（TunnelServiceImpl.java:1077-1079）。
pub fn tunnel_resource_kinds(
    tunnel_id: u64,
    node: &NodeId,
    port: Option<u16>,
) -> [CascadeStepKind; 2] {
    [
        CascadeStepKind::RelayService {
            node: node.clone(),
            service: relay_service_name(tunnel_id),
            port,
        },
        CascadeStepKind::Chains {
            node: node.clone(),
            chain: chain_name(tunnel_id),
        },
    ]
}

/// 按 beta deleteTunnel 的顺序产出完整析构计划。行集只来自入参快照，不回查库。
pub fn plan_cascade_delete(input: &CascadeDeleteInput) -> Result<CascadeDeletePlan, PublishError> {
    let tunnel_id = input.tunnel_id;

    let mut chain_rows = input.chain_rows.clone();
    chain_rows.sort_by_key(|row| row.id);
    for row in &chain_rows {
        if row.tunnel_id != tunnel_id {
            return Err(PublishError::ForeignRow {
                table: "chain_tunnel",
                row: row.id,
                expected: tunnel_id,
                found: row.tunnel_id,
            });
        }
        if row.role().is_none() {
            return Err(PublishError::UnknownChainType {
                row: row.id,
                chain_type: row.chain_type,
            });
        }
    }

    let mut speed_limits = input.speed_limits.clone();
    speed_limits.sort_by_key(|row| row.id);
    for row in &speed_limits {
        if row.tunnel_id != tunnel_id {
            return Err(PublishError::ForeignRow {
                table: "speed_limit",
                row: row.id,
                expected: tunnel_id,
                found: row.tunnel_id,
            });
        }
    }

    let mut user_tunnels = input.user_tunnels.clone();
    user_tunnels.sort_by_key(|row| row.id);
    for row in &user_tunnels {
        if row.tunnel_id != tunnel_id {
            return Err(PublishError::ForeignRow {
                table: "user_tunnel",
                row: row.id,
                expected: tunnel_id,
                found: row.tunnel_id,
            });
        }
    }

    let mut forwards = input.forwards.clone();
    forwards.sort_by_key(|item| item.row.forward_id);

    // 节点集合：chain_tunnel 出现过就清，不论 chain_type；同节点多行只清一次
    // （TunnelServiceImpl.java:1349-1352 的 LinkedHashSet）。
    let mut node_set: BTreeSet<NodeId> = BTreeSet::new();
    for row in &chain_rows {
        node_set.insert(row.node_id.clone());
    }
    let nodes: Vec<NodeId> = node_set.into_iter().collect();

    // 入口节点：转发服务只挂在 chain_type=1 的节点上（ForwardServiceImpl.java:1317）。
    let mut entry_set: BTreeSet<NodeId> = BTreeSet::new();
    for row in &chain_rows {
        if row.role() == Some(ChainRole::Entry) {
            entry_set.insert(row.node_id.clone());
        }
    }
    let entry_nodes: Vec<NodeId> = entry_set.iter().cloned().collect();

    let mut steps: Vec<CascadeStep> = Vec::new();
    let mut notes: Vec<String> = Vec::new();

    // 1) 限流器：全部排在最前（见模块注释的差异说明）。
    let mut limiter_steps: BTreeMap<NodeId, Vec<usize>> = BTreeMap::new();
    for limit in &speed_limits {
        for node in &nodes {
            let index = steps.len();
            steps.push(CascadeStep {
                phase: CascadePhase::Limiter,
                depends_on: Vec::new(),
                kind: CascadeStepKind::Limiter {
                    node: node.clone(),
                    limiter: limit.limiter_name(),
                    speed_mbps: limit.speed,
                },
            });
            limiter_steps.entry(node.clone()).or_default().push(index);
        }
    }

    // 2) 转发入口服务：每个入口节点 _tcp / _udp 都删，不看 status。
    let mut forward_steps: BTreeMap<NodeId, Vec<usize>> = BTreeMap::new();
    let mut missing_ports: BTreeSet<u64> = BTreeSet::new();
    let mut inactive: Vec<u64> = Vec::new();
    for item in &forwards {
        if entry_nodes.is_empty() {
            return Err(PublishError::MissingEntryNode {
                forward: item.row.forward_id,
            });
        }
        let mut ports: BTreeMap<NodeId, u16> = BTreeMap::new();
        let mut entry_ports = item.entry_ports.clone();
        entry_ports
            .sort_by(|left, right| left.node.cmp(&right.node).then(left.port.cmp(&right.port)));
        for entry_port in &entry_ports {
            if !entry_set.contains(&entry_port.node) {
                return Err(PublishError::UnboundEntryPort {
                    forward: item.row.forward_id,
                    node: entry_port.node.clone(),
                });
            }
            ports
                .entry(entry_port.node.clone())
                .or_insert(entry_port.port);
        }
        if !item.row.is_active() {
            inactive.push(item.row.forward_id);
        }
        for node in &entry_nodes {
            let port = ports.get(node).copied();
            if port.is_none() {
                missing_ports.insert(item.row.forward_id);
            }
            for proto in [Proto::Tcp, Proto::Udp] {
                let index = steps.len();
                steps.push(CascadeStep {
                    phase: CascadePhase::ForwardService,
                    depends_on: limiter_steps.get(node).cloned().unwrap_or_default(),
                    kind: CascadeStepKind::ForwardService {
                        node: node.clone(),
                        forward: item.row.forward_id,
                        service: item.row.service_name(proto),
                        port,
                    },
                });
                forward_steps.entry(node.clone()).or_default().push(index);
            }
        }
    }

    // 3) 库行：CascadeDeletePurger.java:81-88 的语句顺序，隧道行最后。
    let row_step = |row: RowDelete| CascadeStep {
        phase: CascadePhase::DatabaseRow,
        // 不等节点结果：beta 非 strict，节点失败也删库行，只把失败记成 leftovers。
        depends_on: Vec::new(),
        kind: CascadeStepKind::Row(row),
    };
    for item in &forwards {
        steps.push(row_step(RowDelete::Forward {
            forward: item.row.forward_id,
        }));
    }
    for item in &forwards {
        steps.push(row_step(RowDelete::ForwardPort {
            forward: item.row.forward_id,
        }));
    }
    for row in &user_tunnels {
        steps.push(row_step(RowDelete::UserTunnel {
            user_tunnel: row.id,
        }));
    }
    for limit in &speed_limits {
        steps.push(row_step(RowDelete::SpeedLimit {
            speed_limit: limit.id,
        }));
    }
    for row in &chain_rows {
        steps.push(row_step(RowDelete::ChainTunnel { chain_row: row.id }));
    }
    steps.push(row_step(RowDelete::Tunnel { tunnel: tunnel_id }));

    // 4) 隧道级节点资源：每个出现过的节点都试 service → chain。
    for node in &nodes {
        let port = chain_rows
            .iter()
            .filter(|row| &row.node_id == node)
            .filter_map(|row| match row.role() {
                Some(ChainRole::Relay) | Some(ChainRole::Exit) => row.port,
                _ => None,
            })
            .next();
        let [service_kind, chain_kind] = tunnel_resource_kinds(tunnel_id, node, port);
        let mut deps = limiter_steps.get(node).cloned().unwrap_or_default();
        deps.extend(forward_steps.get(node).cloned().unwrap_or_default());
        let service_index = steps.len();
        steps.push(CascadeStep {
            phase: service_kind.phase(),
            depends_on: deps.clone(),
            kind: service_kind,
        });
        deps.push(service_index);
        steps.push(CascadeStep {
            phase: chain_kind.phase(),
            depends_on: deps,
            kind: chain_kind,
        });
    }

    // ---- notes：把与 beta 的差异、以及 beta 注释里的踩坑点逐条带出 ----------
    notes.push(
        "输入即快照：forward / chain_tunnel / speed_limit 行必须在 purge 之前读全（TunnelServiceImpl.java:1330-1333），计划只按入参推导，不回查库"
            .to_string(),
    );
    if !nodes.is_empty() {
        notes.push(format!(
            "节点 {} 个（chain_tunnel 去重）：每个节点都拆 {} 与 {}，不按 chain_type 猜该节点有什么（TunnelServiceImpl.java:1344-1347：入口行残留的 {} 会被 loader 重启拉起继续占端口）",
            nodes.len(),
            relay_service_name(tunnel_id),
            chain_name(tunnel_id),
            relay_service_name(tunnel_id)
        ));
    }
    if !speed_limits.is_empty() {
        notes.push(format!(
            "限速规则 {} 条：{} 个节点上的 limiter（名字 = speed_limit.id）先拆；beta 是每节点 service/chain 之后才拆（TunnelServiceImpl.java:1350-1355），两者互不依赖，提前统一拆不会漏 limiter",
            speed_limits.len(),
            nodes.len()
        ));
    }
    notes.push(
        "库行一个事务提交（CascadeDeletePurger.java:79-89）：forward → forward_port → user_tunnel → speed_limit → chain_tunnel → tunnel，隧道行最后；任一张表抛异常整段回滚，用户可整条重试"
            .to_string(),
    );
    notes.push(
        "库行不等节点结果（ForwardServiceImpl.java:1341-1346 非 strict）：节点失败只作为 leftovers 带出，行照删，避免记录卡死无法重试"
            .to_string(),
    );
    if !inactive.is_empty() {
        let ids: Vec<String> = inactive.iter().map(u64::to_string).collect();
        notes.push(format!(
            "转发 {} 处于暂停/异常态：入口服务照样按名字删（ForwardServiceImpl.java:1325-1335 不看 status）",
            ids.join(", ")
        ));
    }
    if !missing_ports.is_empty() {
        let ids: Vec<String> = missing_ports.iter().map(u64::to_string).collect();
        notes.push(format!(
            "转发 {} 缺 forward_port 行：服务名照删（beta 只按名字下发），端口号留空，不猜",
            ids.join(", ")
        ));
    }
    if chain_rows.is_empty() {
        notes.push("隧道没有 chain_tunnel 行：节点上无隧道级资源要拆，只清库行".to_string());
    }

    let plan = CascadeDeletePlan {
        tunnel_id,
        steps,
        notes,
    };
    // 自检失败说明构造函数本身写错了，直接暴露而不是下发一份顺序可疑的计划。
    debug_assert!(
        plan.ordering_violations().is_empty(),
        "级联删除计划顺序自检失败：{:?}",
        plan.ordering_violations()
    );
    Ok(plan)
}
