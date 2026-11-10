//! 拓扑变更规则测试：对齐 beta TunnelServiceImpl.updateTunnelTopologyLocked（round44 之后的口径）。
//!
//! 每条用例只改 `TopologyEdit` 的一个维度，断言"beta 会拒/会放"以及放行时给出的迁移摘要，
//! 包括 needs_forward_rebuild（要不要重建存量转发入口服务）与 old_only_entries（要清理的旧入口）。
//!
//! 全部是内存判定，不连数据库、不连 GOST。

use flux_migration::{plan_topology_edit, NodeId, TopologyEdit, TopologyEditError, TunnelType};

fn node(name: &str) -> NodeId {
    NodeId::new(name)
}

fn nodes(names: &[&str]) -> Vec<NodeId> {
    names.iter().map(|name| node(name)).collect()
}

/// 基线：类型不变、入口只有 entry-a、没有链也没有出口、隧道下没有转发。
fn edit(kind: TunnelType, entries: &[&str]) -> TopologyEdit {
    TopologyEdit {
        tunnel_id: 36,
        old_type: kind,
        new_type: kind,
        old_entries: nodes(entries),
        new_entries: nodes(entries),
        chain_groups: Vec::new(),
        out_node: None,
        forward_count: 0,
    }
}

/// 类型 2 的最小合法形状：入口 + 逐跳分组 + 出口。
fn relay(entries: &[&str], hops: Vec<Vec<&str>>, out: &str) -> TopologyEdit {
    let mut edit = edit(TunnelType::TunnelForward, entries);
    edit.chain_groups = hops.into_iter().map(|hop| nodes(&hop)).collect();
    edit.out_node = Some(node(out));
    edit
}

// ---- 重复节点（beta :864 validateChainRows / :902 节点重复）------------------------

#[test]
fn duplicate_entry_is_rejected() {
    let mut edit = edit(TunnelType::PortForward, &["entry-a"]);
    edit.new_entries = nodes(&["entry-a", "entry-a"]);

    assert_eq!(
        plan_topology_edit(&edit),
        Err(TopologyEditError::DuplicateEntry(node("entry-a")))
    );
    assert!(TopologyEditError::DuplicateEntry(node("entry-a"))
        .to_string()
        .contains("节点重复"));
}

#[test]
fn duplicate_node_inside_one_hop_is_rejected() {
    let first_hop = relay(&["entry-a"], vec![vec!["relay-b", "relay-b"]], "exit-d");
    assert_eq!(
        plan_topology_edit(&first_hop),
        Err(TopologyEditError::DuplicateHopNode {
            hop: 1,
            node: node("relay-b"),
        })
    );

    // hop 编号从 1 开始，跟 beta 的 chain_tunnel.inx / "第 N 组" 一致
    let second_hop = relay(
        &["entry-a"],
        vec![vec!["relay-b"], vec!["relay-c", "relay-c"]],
        "exit-d",
    );
    assert_eq!(
        plan_topology_edit(&second_hop),
        Err(TopologyEditError::DuplicateHopNode {
            hop: 2,
            node: node("relay-c"),
        })
    );
}

#[test]
fn duplicate_node_across_hops_is_rejected() {
    // beta :902 用 new HashSet<>(newNodeIds) 把入口 + 所有跳 + 出口拍平后判重，
    // 跨跳复用同一节点同样被拒；原型早期版本比 beta 宽松，本用例钉住收紧后的口径。
    let edit = relay(
        &["entry-a"],
        vec![vec!["relay-b"], vec!["relay-b"]],
        "exit-d",
    );
    assert_eq!(
        plan_topology_edit(&edit),
        Err(TopologyEditError::DuplicateNode(node("relay-b")))
    );
}

#[test]
fn entry_reused_as_hop_is_rejected() {
    let edit = relay(
        &["entry-a"],
        vec![vec!["entry-a"]],
        "exit-d",
    );
    assert_eq!(
        plan_topology_edit(&edit),
        Err(TopologyEditError::DuplicateNode(node("entry-a")))
    );
}

#[test]
fn out_reused_as_hop_is_rejected() {
    let mut edit = relay(&["entry-a"], vec![vec!["relay-b"]], "relay-b");
    edit.forward_count = 0;
    assert_eq!(
        plan_topology_edit(&edit),
        Err(TopologyEditError::DuplicateNode(node("relay-b")))
    );
}

// ---- 有转发时的入口数量 / 类型组合（beta :825-829、:845-848）-------------------------

#[test]
fn entry_count_change_with_forwards_is_rejected() {
    let mut edit = edit(TunnelType::PortForward, &["entry-a"]);
    edit.new_entries = nodes(&["entry-a", "entry-b"]);
    edit.forward_count = 2;

    let error = plan_topology_edit(&edit).expect_err("有转发时入口数量变化必须拒绝");
    assert_eq!(
        error,
        TopologyEditError::EntryCountChangedWithForwards {
            old: 1,
            new: 2,
            forwards: 2,
        }
    );
    assert!(error.to_string().contains("多入口迁移无法按序对应"));
}

#[test]
fn same_count_entry_swap_needs_forward_rebuild() {
    let mut edit = edit(TunnelType::PortForward, &["entry-a"]);
    edit.new_entries = nodes(&["entry-b"]);
    edit.forward_count = 3;

    let plan = plan_topology_edit(&edit).expect("同数量换入口应按序重绑");
    assert!(plan.entries_changed);
    assert!(!plan.type_changed);
    assert!(plan.needs_forward_rebuild);
    assert_eq!(plan.old_only_entries, nodes(&["entry-a"]));
    assert_eq!(plan.new_only_entries, nodes(&["entry-b"]));
    assert_eq!(plan.notes.len(), 2);
    assert!(plan
        .notes
        .iter()
        .any(|note| note.contains("入口节点已迁移")));
}

#[test]
fn type_and_entry_change_with_forwards_is_rejected() {
    let mut edit = relay(&["entry-a"], vec![vec!["relay-b"]], "exit-d");
    edit.old_type = TunnelType::PortForward;
    edit.new_entries = nodes(&["entry-b"]);
    edit.forward_count = 1;

    let error = plan_topology_edit(&edit).expect_err("类型+入口同时改且仍有转发必须拒绝");
    assert_eq!(
        error,
        TopologyEditError::TypeAndEntryChangedWithForwards { forwards: 1 }
    );
    assert!(error.to_string().contains("请先保存类型变更"));
}

#[test]
fn type_and_entry_change_without_forwards_is_allowed() {
    let mut edit = relay(&["entry-a"], vec![vec!["relay-b"]], "exit-d");
    edit.old_type = TunnelType::PortForward;
    edit.new_entries = nodes(&["entry-b"]);

    let plan = plan_topology_edit(&edit).expect("没有转发时应允许一次改完");
    assert!(plan.type_changed);
    assert!(plan.entries_changed);
    assert!(!plan.needs_forward_rebuild);
    assert_eq!(plan.old_only_entries, nodes(&["entry-a"]));
    assert_eq!(plan.new_only_entries, nodes(&["entry-b"]));
}

// ---- 类型与链条形状（beta :837、:838、:830-832）-------------------------------------

#[test]
fn direct_type_with_chain_or_out_is_rejected() {
    let mut with_chain = edit(TunnelType::PortForward, &["entry-a"]);
    with_chain.chain_groups = vec![nodes(&["relay-b"])];
    assert_eq!(
        plan_topology_edit(&with_chain),
        Err(TopologyEditError::DirectWithChainOrOut)
    );

    let mut with_out = edit(TunnelType::PortForward, &["entry-a"]);
    with_out.out_node = Some(node("exit-d"));
    assert_eq!(
        plan_topology_edit(&with_out),
        Err(TopologyEditError::DirectWithChainOrOut)
    );

    assert_eq!(
        TopologyEditError::DirectWithChainOrOut.to_string(),
        "直连隧道不能配置转发链或出口节点"
    );
}

#[test]
fn relay_without_out_is_rejected() {
    let mut edit = edit(TunnelType::PortForward, &["entry-a"]);
    edit.new_type = TunnelType::TunnelForward;

    assert_eq!(
        plan_topology_edit(&edit),
        Err(TopologyEditError::RelayWithoutOut)
    );
    assert_eq!(
        TopologyEditError::RelayWithoutOut.to_string(),
        "出口不能为空"
    );
}

#[test]
fn empty_entries_is_rejected() {
    let mut edit = edit(TunnelType::PortForward, &["entry-a"]);
    edit.new_entries = Vec::new();

    assert_eq!(
        plan_topology_edit(&edit),
        Err(TopologyEditError::EmptyEntries)
    );
    assert_eq!(
        TopologyEditError::EmptyEntries.to_string(),
        "入口节点不能为空"
    );
}

// ---- 2 → 1 的类型切换：入口增删清单 -------------------------------------------------

#[test]
fn relay_to_direct_without_forwards_lists_old_only_entries() {
    let mut edit = relay(&["entry-a", "entry-b"], vec![vec!["relay-b"]], "exit-d");
    edit.new_type = TunnelType::PortForward;
    edit.new_entries = nodes(&["entry-a"]);
    edit.chain_groups = Vec::new();
    edit.out_node = None;

    let plan = plan_topology_edit(&edit).expect("2→1 且没有转发时应放行");
    assert!(plan.type_changed);
    assert!(plan.entries_changed);
    assert_eq!(plan.old_only_entries, nodes(&["entry-b"]));
    assert!(plan.new_only_entries.is_empty());
    assert!(!plan.needs_forward_rebuild);
    assert!(plan.notes.iter().any(|note| note.contains("类型切换")));
}
