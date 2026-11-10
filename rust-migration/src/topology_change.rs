//! 拓扑变更（编辑已存在的隧道）的规划：复刻 beta 在 TunnelServiceImpl.updateTunnelTopologyLocked
//! 里定死的拒绝规则，让迁移原型在写库 / 下发之前先把「这次编辑能不能做」判出来。
//!
//! Java 出处（.worktrees/beta/springboot-backend/src/main/java/com/admin/service/impl/TunnelServiceImpl.java）：
//! - 仍有转发 + 入口数量变化            → :825-829  "多入口迁移无法按序对应，请先删除这些转发"
//! - 入口节点为空                       → :830-832  "入口节点不能为空"
//! - 类型 1 却带 chainNodes / outNodeId → :837      "直连隧道不能配置转发链或出口节点"
//! - 类型 2 却没有 outNodeId            → :838      "出口不能为空"
//! - 类型变更 + 入口变更 + 仍有转发      → :845-848  "请先保存类型变更，确认无误后再迁移入口节点"
//! - 节点重复                           → :864（validateChainRows）/ :902 "节点重复"
//!
//! 校验顺序与 beta 一致：先数量、再空、再链条形状、再类型+入口组合、最后重复。
//! 顺序不是装饰：同时踩两条规则时，返回的必须和 beta 返回的是同一条。
//!
//! 重复口径：严格跟 beta :902 的 `new HashSet<>(newNodeIds)` 口径——把入口、所有跳、出口拍平后
//! 全局去重，跨跳同一节点同样拒绝（早期原型允许跨跳重复，与 beta 不一致，已在本轮收紧）。
//! 同一跳内重复另外单独报 DuplicateHopNode，便于运维直接看出是哪一跳。
//!
//! 纯逻辑：不读库、不下发、不猜默认值，所有判定只依赖调用方喂进来的 TopologyEdit。
//!
//! 与删除的衔接：编辑通过后旧节点上留下的隧道级资源，用的是和 deleteTunnel 完全相同的一套口径
//! ——先删 service `<tid>_tls`、再删 chain `chains_<tid>`（TunnelServiceImpl.java:1368-1377，
//! 两条路径共用 cleanTunnelResources）。节点集合见 TopologyEditPlan::cleanup_nodes，
//! 单节点序列见 crate::beta::tunnel_resource_kinds。

use std::collections::BTreeSet;
use std::fmt;

use serde::{Deserialize, Serialize};

use crate::ids::NodeId;
use crate::plan::TunnelType;

/// 一次「编辑已存在隧道」的提交。字段与 beta TunnelTopologyDto + 库里旧状态一一对应：
/// old_* 来自 chain_tunnel 旧行（chain_type=1，按 id 排序），new_* 来自请求体。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TopologyEdit {
    pub tunnel_id: u64,
    pub old_type: TunnelType,
    pub new_type: TunnelType,
    /// 旧入口节点，顺序即 beta 的 `chain_tunnel.id` 排序（:817-821），迁移按这个顺序一一对应。
    pub old_entries: Vec<NodeId>,
    /// 新入口节点，顺序即请求体里的顺序；有转发时数量必须与旧的一致。
    pub new_entries: Vec<NodeId>,
    /// 转发链分组，外层下标就是跳序（beta chain_tunnel.inx，从 1 开始）；空表示没有链。
    pub chain_groups: Vec<Vec<NodeId>>,
    /// beta outNodeId：类型 2 必填，类型 1 必须为空。
    pub out_node: Option<NodeId>,
    /// 隧道下现存转发条数（beta forwardService.count）。
    pub forward_count: usize,
}

impl TopologyEdit {
    pub fn type_changed(&self) -> bool {
        self.old_type != self.new_type
    }

    /// 与 beta :824 同口径：逐位比较，顺序变了也算变更（入口迁移是按序重绑的）。
    pub fn entries_changed(&self) -> bool {
        self.old_entries != self.new_entries
    }
}

/// 拓扑变更被否的原因。每条对应 beta 里一句 R.err。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TopologyEditError {
    /// 同一 nodeId 在入口列表出现两次（beta :864 / :902）。
    DuplicateEntry(NodeId),
    /// 同一跳内出现两次；`hop` 从 1 开始，就是 beta 的 chain_tunnel.inx / "第 N 组"。
    DuplicateHopNode { hop: usize, node: NodeId },
    /// 跨跳/入口与跳之间重复：beta :902 把整个拓扑拍平后全局去重。
    DuplicateNode(NodeId),
    /// 仍有转发时改入口数量：多入口无法按序对应（beta :825-829）。
    EntryCountChangedWithForwards {
        old: usize,
        new: usize,
        forwards: usize,
    },
    /// 类型变更与入口变更同时提交且仍有转发（beta :845-848）。
    TypeAndEntryChangedWithForwards { forwards: usize },
    /// 类型 1 不允许带 chainNodes / outNodeId（beta :837）。
    DirectWithChainOrOut,
    /// 类型 2 必须有 outNodeId（beta :838）。
    RelayWithoutOut,
    /// 入口节点为空（beta :830-832）。
    EmptyEntries,
}

impl fmt::Display for TopologyEditError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TopologyEditError::DuplicateEntry(node) => write!(
                f,
                "节点重复：入口节点 {node} 出现两次，同一个节点不能当两个入口"
            ),
            TopologyEditError::DuplicateHopNode { hop, node } => write!(
                f,
                "节点重复：第 {hop} 组转发链里节点 {node} 出现两次，同一跳内不能重复"
            ),
            TopologyEditError::DuplicateNode(node) => write!(
                f,
                "节点重复：节点 {node} 在入口/中继/出口中重复出现，整个拓扑内只能出现一次"
            ),
            TopologyEditError::EntryCountChangedWithForwards {
                old,
                new,
                forwards,
            } => write!(
                f,
                "隧道下仍有 {forwards} 条转发，入口节点数量变化（{old} → {new}）：多入口迁移无法按序对应，\
                 请先删除这些转发；入口数量不变可以直接保存，中继/出口可单独改"
            ),
            TopologyEditError::TypeAndEntryChangedWithForwards { forwards } => write!(
                f,
                "隧道类型变更与入口节点变更不能同时进行（仍有 {forwards} 条转发）：\
                 请先保存类型变更，确认无误后再迁移入口节点"
            ),
            TopologyEditError::DirectWithChainOrOut => {
                write!(f, "直连隧道不能配置转发链或出口节点")
            }
            TopologyEditError::RelayWithoutOut => write!(f, "出口不能为空"),
            TopologyEditError::EmptyEntries => write!(f, "入口节点不能为空"),
        }
    }
}

impl std::error::Error for TopologyEditError {}

/// 允许继续保存时给出的变更摘要：调用方据此决定要不要迁移存量转发入口服务。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TopologyEditPlan {
    pub type_changed: bool,
    pub entries_changed: bool,
    /// 旧入口里不再存在的节点（保持旧顺序，去重；用于回滚/清理旧入口节点资源）。
    pub old_only_entries: Vec<NodeId>,
    /// 新入口里新增的节点（保持请求顺序，去重）。
    pub new_only_entries: Vec<NodeId>,
    /// 是否需要重建存量转发的入口服务：类型或入口变了，且还有转发。
    pub needs_forward_rebuild: bool,
    pub notes: Vec<String>,
}

impl TopologyEditPlan {
    /// 需要在删库行之前拆隧道级资源的节点，按 beta 两条规则合并：
    ///
    /// - 旧拓扑独有节点必须趁旧行还在时清（TunnelServiceImpl.java:1076-1079）；
    /// - 类型 2→1 时旧拓扑所有节点都要清，入口集合没变的也在内（:1065-1074）。
    ///
    /// `old_nodes` 是旧 chain_tunnel 行（含中继/出口）的节点快照：删除后再查只剩空集，
    /// 所以必须由调用方在删行前取好，原型不猜。每个节点上的序列见
    /// [`crate::beta::tunnel_resource_kinds`]（service 先于 chain）。
    pub fn cleanup_nodes(&self, edit: &TopologyEdit, old_nodes: &[NodeId]) -> Vec<NodeId> {
        let direct_now =
            edit.old_type == TunnelType::TunnelForward && edit.new_type == TunnelType::PortForward;
        let mut seen: BTreeSet<NodeId> = BTreeSet::new();
        let mut out: Vec<NodeId> = Vec::new();
        if direct_now {
            for node in old_nodes {
                if seen.insert(node.clone()) {
                    out.push(node.clone());
                }
            }
        }
        for node in &self.old_only_entries {
            if seen.insert(node.clone()) {
                out.push(node.clone());
            }
        }
        out
    }
}

/// 按 beta updateTunnelTopologyLocked 的顺序校验并产出变更摘要。
pub fn plan_topology_edit(edit: &TopologyEdit) -> Result<TopologyEditPlan, TopologyEditError> {
    let type_changed = edit.type_changed();
    let entries_changed = edit.entries_changed();

    // :825-829 数量变了就没法按序把每条转发的入口服务搬到新节点上，只能先删转发。
    if edit.forward_count > 0 && entries_changed && edit.old_entries.len() != edit.new_entries.len()
    {
        return Err(TopologyEditError::EntryCountChangedWithForwards {
            old: edit.old_entries.len(),
            new: edit.new_entries.len(),
            forwards: edit.forward_count,
        });
    }

    // :830-832 入口为空时后面所有推导（inIp、入口服务）都无从谈起。
    if edit.new_entries.is_empty() {
        return Err(TopologyEditError::EmptyEntries);
    }

    // :835-838 hasChains / hasOut 只看有没有，不看内容（空组也算法在，交给 plan 层再拦）。
    let has_chains = !edit.chain_groups.is_empty();
    let has_out = edit.out_node.is_some();
    match edit.new_type {
        TunnelType::PortForward => {
            if has_chains || has_out {
                return Err(TopologyEditError::DirectWithChainOrOut);
            }
        }
        TunnelType::TunnelForward => {
            if !has_out {
                return Err(TopologyEditError::RelayWithoutOut);
            }
        }
    }

    // :845-848 类型切换会改变每条转发的 handler 是否挂 chains_<id>；与入口迁移同时做时回滚面交叉，
    // 所以有转发就强制拆成两次保存，保证每次失败都能整体回退。
    if type_changed && entries_changed && edit.forward_count > 0 {
        return Err(TopologyEditError::TypeAndEntryChangedWithForwards {
            forwards: edit.forward_count,
        });
    }

    // :864 validateChainRows（入口行）+ :902 节点重复的入口/同跳部分。
    let mut seen_entries: BTreeSet<&NodeId> = BTreeSet::new();
    for entry in &edit.new_entries {
        if !seen_entries.insert(entry) {
            return Err(TopologyEditError::DuplicateEntry(entry.clone()));
        }
    }
    let mut seen_global: BTreeSet<&NodeId> = BTreeSet::new();
    for entry in &edit.new_entries {
        seen_global.insert(entry);
    }
    for (index, group) in edit.chain_groups.iter().enumerate() {
        let mut seen_hop: BTreeSet<&NodeId> = BTreeSet::new();
        for node in group {
            if !seen_hop.insert(node) {
                return Err(TopologyEditError::DuplicateHopNode {
                    hop: index + 1,
                    node: node.clone(),
                });
            }
            // beta :902：入口 + 所有跳 + 出口拍平后全局去重，跨跳同一节点也拒绝。
            if !seen_global.insert(node) {
                return Err(TopologyEditError::DuplicateNode(node.clone()));
            }
        }
    }
    if let Some(out) = &edit.out_node {
        if !seen_global.insert(out) {
            return Err(TopologyEditError::DuplicateNode(out.clone()));
        }
    }

    // 入口增删：beta :916-921 用 LinkedHashSet 去重后再做差集，顺序因此是稳定的。
    // 原型把范围收到入口（旧入口可能带历史重复行，同样先去重）。
    let new_entry_set: BTreeSet<&NodeId> = edit.new_entries.iter().collect();
    let old_entry_set: BTreeSet<&NodeId> = edit.old_entries.iter().collect();
    let mut old_only_entries = Vec::new();
    let mut old_seen: BTreeSet<&NodeId> = BTreeSet::new();
    for entry in &edit.old_entries {
        if !new_entry_set.contains(entry) && old_seen.insert(entry) {
            old_only_entries.push(entry.clone());
        }
    }
    let mut new_only_entries = Vec::new();
    let mut new_seen: BTreeSet<&NodeId> = BTreeSet::new();
    for entry in &edit.new_entries {
        if !old_entry_set.contains(entry) && new_seen.insert(entry) {
            new_only_entries.push(entry.clone());
        }
    }

    let needs_forward_rebuild = (type_changed || entries_changed) && edit.forward_count > 0;

    let mut notes = Vec::new();
    if type_changed
        && edit.old_type == TunnelType::TunnelForward
        && edit.new_type == TunnelType::PortForward
    {
        // :1065-1074 2→1 之后旧中继链与 <tid>_tls 不再属于这条隧道，入口集合没变的节点也算在
        // oldNodeIds 里，原实现只清 oldOnlyNodes，残留一直占端口。清理必须趁旧行还在时做。
        notes.push(format!(
            "类型 2 → 1：旧拓扑所有节点（含入口未变的）都要拆 {} 与 {}，删除库行之前清（TunnelServiceImpl.java:1065-1074）",
            crate::beta::relay_service_name(edit.tunnel_id),
            crate::beta::chain_name(edit.tunnel_id)
        ));
    }
    if type_changed {
        // :966-968 库改了但节点不重发，就变成面板显示已切换、节点仍按旧类型转发。
        notes.push(format!(
            "隧道 {} 类型切换 {} → {}：存量转发必须按新类型重发（TunnelServiceImpl.java:966-968）",
            edit.tunnel_id,
            beta_type_label(edit.old_type),
            beta_type_label(edit.new_type)
        ));
    }
    if entries_changed {
        // :952-963 新入口的 chains_<id> 由 publishTopology 建好，再按序把每条转发的入口服务搬过去。
        notes.push(format!(
            "入口节点已迁移：{} → {}，转发入口服务已按序重绑（TunnelServiceImpl.java:952-963）",
            java_list(&edit.old_entries),
            java_list(&edit.new_entries)
        ));
    }
    if needs_forward_rebuild {
        notes.push(format!(
            "存量转发 {} 条：入口服务与 chains_<id> 挂载需要重建",
            edit.forward_count
        ));
    } else if edit.forward_count == 0 {
        notes.push("隧道下没有转发：类型/入口可自由变更，无需迁移存量服务".to_string());
    } else {
        notes.push(format!(
            "存量转发 {} 条无需重建：类型与入口均未变化，按热改处理",
            edit.forward_count
        ));
    }

    Ok(TopologyEditPlan {
        type_changed,
        entries_changed,
        old_only_entries,
        new_only_entries,
        needs_forward_rebuild,
        notes,
    })
}

/// beta 的隧道类型中文口径：1 直连 / 2 中继（面板与报错里都是这个说法）。
fn beta_type_label(kind: TunnelType) -> &'static str {
    match kind {
        TunnelType::PortForward => "1 直连",
        TunnelType::TunnelForward => "2 中继",
    }
}

/// Java List 的 toString 口径（[a, b]），让 notes 与 beta 日志逐字对得上。
fn java_list(ids: &[NodeId]) -> String {
    let body: Vec<String> = ids.iter().map(|id| id.to_string()).collect();
    format!("[{}]", body.join(", "))
}
