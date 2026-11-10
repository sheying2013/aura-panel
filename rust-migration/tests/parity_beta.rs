//! beta 对齐测试：命名、跳序、端口归属逐条对齐
//! ForwardServiceImpl / GostUtil / TunnelServiceImpl 的真实口径。
//!
//! 纯内存推导，不连数据库、不连节点；每个用例只断言 beta 代码里能读到的规则。

use std::collections::BTreeMap;

use flux_migration::beta::{
    allocated_ports, chain_name, forward_service_base, forward_service_name, hop_element, hop_name,
    plan_forward, process_server_address, relay_service_name, ChainRole, ChainRow, ForwardRow,
    NodePlan, PublishError, TunnelPublish,
};
use flux_migration::{EntryPort, NodeId, Proto};

// ---- 辅助 ---------------------------------------------------------------

fn node(name: &str) -> NodeId {
    NodeId::new(name)
}

fn row(id: u64, tunnel: u64, node_name: &str, role: ChainRole) -> ChainRow {
    ChainRow::new(id, tunnel, node(node_name), role)
}

fn servers(pairs: &[(&str, &str)]) -> BTreeMap<NodeId, String> {
    pairs
        .iter()
        .map(|(name, ip)| (node(name), (*ip).to_string()))
        .collect()
}

const THREE_HOP: &[(&str, &str)] = &[
    ("entry-a", "10.0.0.1"),
    ("relay-a", "10.0.0.2"),
    ("relay-b", "10.0.0.3"),
    ("exit-a", "10.0.0.4"),
];

fn three_hop_rows() -> Vec<ChainRow> {
    vec![
        row(1, 7, "entry-a", ChainRole::Entry),
        row(10, 7, "relay-a", ChainRole::Relay)
            .with_inx(1)
            .with_port(20001),
        row(11, 7, "relay-b", ChainRole::Relay)
            .with_inx(2)
            .with_port(20002),
        row(12, 7, "exit-a", ChainRole::Exit).with_port(20003),
    ]
}

fn plans_of(rows: &[ChainRow], pairs: &[(&str, &str)]) -> Vec<NodePlan> {
    TunnelPublish::from_rows(rows)
        .expect("行应能解析成拓扑")
        .plan(&servers(pairs))
        .expect("拓扑应可发布")
}

fn node_names(plans: &[NodePlan]) -> Vec<String> {
    plans.iter().map(|plan| plan.node.0.clone()).collect()
}

// ---- 1. 三跳链的顺序与每跳独立端口 --------------------------------------

#[test]
fn three_hop_chain_orders_entry_relay_exit_with_per_hop_ports() {
    let plans = plans_of(&three_hop_rows(), THREE_HOP);
    assert_eq!(
        node_names(&plans),
        vec!["entry-a", "relay-a", "relay-b", "exit-a"]
    );

    let entry = &plans[0];
    assert_eq!(entry.role, ChainRole::Entry);
    let entry_chain = entry.chain.as_ref().expect("入口必须建链");
    assert_eq!(entry_chain.name, "chains_7");
    assert_eq!(entry_chain.hop_name, "hop_7");
    assert_eq!(
        entry_chain.elements(),
        vec!["node_relay-a@10.0.0.2:20001#tls"],
        "入口链只指向第一跳"
    );

    let relay_a = &plans[1];
    assert_eq!(
        relay_a.chain.as_ref().unwrap().elements(),
        vec!["node_relay-b@10.0.0.3:20002#tls"]
    );
    let service_a = relay_a.relay_service.as_ref().unwrap();
    assert_eq!(service_a.name, "7_tls");
    assert_eq!(service_a.port, 20001, "中继端口来自本行 port");
    assert_eq!(service_a.chain.as_deref(), Some("chains_7"));

    let relay_b = &plans[2];
    assert_eq!(
        relay_b.chain.as_ref().unwrap().elements(),
        vec!["node_exit-a@10.0.0.4:20003#tls"]
    );
    assert_eq!(relay_b.relay_service.as_ref().unwrap().port, 20002);

    let exit = &plans[3];
    assert_eq!(exit.relay_service.as_ref().unwrap().port, 20003);
    let ports: Vec<u16> = plans
        .iter()
        .filter_map(|plan| plan.relay_service.as_ref().map(|service| service.port))
        .collect();
    assert_eq!(ports, vec![20001, 20002, 20003], "每跳端口各自独立");
}

// ---- 2. 同 inx 多节点按行 id 稳定 ---------------------------------------

#[test]
fn same_inx_nodes_are_ordered_by_row_id() {
    let pairs: &[(&str, &str)] = &[
        ("entry-a", "10.0.0.1"),
        ("relay-b", "10.0.0.2"),
        ("relay-c", "10.0.0.3"),
        ("exit-a", "10.0.0.4"),
    ];
    let rows = vec![
        row(1, 9, "entry-a", ChainRole::Entry),
        row(30, 9, "relay-c", ChainRole::Relay)
            .with_inx(1)
            .with_port(26003),
        row(20, 9, "relay-b", ChainRole::Relay)
            .with_inx(1)
            .with_port(26002),
        row(40, 9, "exit-a", ChainRole::Exit).with_port(26004),
    ];
    let plans = plans_of(&rows, pairs);
    assert_eq!(
        node_names(&plans),
        vec!["entry-a", "relay-b", "relay-c", "exit-a"]
    );
    assert_eq!(
        plans[0].chain.as_ref().unwrap().elements(),
        vec![
            "node_relay-b@10.0.0.2:26002#tls",
            "node_relay-c@10.0.0.3:26003#tls"
        ],
        "同 inx 按行 id 升序，不看输入顺序"
    );
    assert_eq!(
        plans[1].chain.as_ref().unwrap().elements(),
        plans[2].chain.as_ref().unwrap().elements()
    );
}

// ---- 3. 输入乱序结果不变 ------------------------------------------------

#[test]
fn shuffled_input_produces_identical_plan() {
    let rows = three_hop_rows();
    let shuffled = vec![
        rows[3].clone(),
        rows[1].clone(),
        rows[0].clone(),
        rows[2].clone(),
    ];
    assert_eq!(
        plans_of(&rows, THREE_HOP),
        plans_of(&shuffled, THREE_HOP),
        "逐节点计划必须与输入顺序无关"
    );

    let ordered_ids = |input: &[ChainRow]| -> Vec<u64> {
        TunnelPublish::from_rows(input)
            .expect("行应能解析")
            .ordered_rows()
            .iter()
            .map(|row| row.id)
            .collect()
    };
    assert_eq!(ordered_ids(&rows), vec![1, 10, 11, 12]);
    assert_eq!(ordered_ids(&shuffled), vec![1, 10, 11, 12]);
}

// ---- 4. 缺端口不可发布（不许猜） ---------------------------------------

#[test]
fn missing_hop_port_is_unpublishable() {
    let pairs: &[(&str, &str)] = &[
        ("entry-a", "10.0.0.1"),
        ("relay-a", "10.0.0.2"),
        ("exit-a", "10.0.0.3"),
    ];
    let no_relay_port = vec![
        row(1, 3, "entry-a", ChainRole::Entry),
        row(10, 3, "relay-a", ChainRole::Relay).with_inx(1),
        row(12, 3, "exit-a", ChainRole::Exit).with_port(24000),
    ];
    let publish = TunnelPublish::from_rows(&no_relay_port).expect("解析阶段不看端口");
    assert_eq!(
        publish.plan(&servers(pairs)).unwrap_err(),
        PublishError::MissingPort {
            row: 10,
            node: node("relay-a"),
            role: "relay",
        }
    );
    assert!(
        publish.to_topology().is_err(),
        "缺端口也不能折算成 Topology 蒙过去"
    );

    let no_exit_port = vec![
        row(1, 3, "entry-a", ChainRole::Entry),
        row(10, 3, "relay-a", ChainRole::Relay)
            .with_inx(1)
            .with_port(24001),
        row(12, 3, "exit-a", ChainRole::Exit),
    ];
    assert!(matches!(
        TunnelPublish::from_rows(&no_exit_port)
            .unwrap()
            .plan(&servers(pairs))
            .unwrap_err(),
        PublishError::MissingPort { role: "exit", .. }
    ));
}

// ---- 5. 暂停转发跳过但端口仍占位 ---------------------------------------

#[test]
fn paused_forward_skips_services_but_keeps_its_ports() {
    let ports = vec![
        EntryPort::new(node("entry-a"), 25000),
        EntryPort::new(node("entry-b"), 25001),
    ];
    let paused = plan_forward(&ForwardRow::new(9, 5, Some(42), 0), &ports);
    assert!(!paused.active);
    assert!(paused.services.is_empty(), "暂停转发不生成入口服务");
    assert_eq!(paused.base_name, "9_5_42");

    let ledger = allocated_ports(std::slice::from_ref(&paused));
    assert_eq!(ledger.len(), 2);
    assert!(ledger.contains(&(node("entry-a"), 25000)));
    assert!(ledger.contains(&(node("entry-b"), 25001)));

    let active = plan_forward(&ForwardRow::new(9, 5, Some(42), 1), &ports);
    assert_eq!(active.services.len(), 4, "2 个入口 x 2 个协议");
    assert!(active
        .services
        .iter()
        .any(|service| service.name == "9_5_42_udp" && service.port == 25001));

    let broken = plan_forward(&ForwardRow::new(9, 5, None, -1), &ports);
    assert!(broken.services.is_empty(), "异常态(-1) 同样不得下发");
}

// ---- 6. user_tunnel 缺失写 0 / 有值写 42 --------------------------------

#[test]
fn service_name_uses_zero_when_user_tunnel_missing() {
    assert_eq!(forward_service_base(12, 3, None), "12_3_0");
    assert_eq!(forward_service_name(12, 3, None, Proto::Tcp), "12_3_0_tcp");
    assert_eq!(forward_service_name(12, 3, None, Proto::Udp), "12_3_0_udp");
    assert_eq!(
        forward_service_name(12, 3, Some(42), Proto::Tcp),
        "12_3_42_tcp"
    );
    assert_eq!(
        forward_service_name(12, 3, Some(42), Proto::Udp),
        "12_3_42_udp"
    );
    assert_eq!(
        ForwardRow::new(12, 3, None, 1).service_name(Proto::Udp),
        "12_3_0_udp"
    );
}

// ---- 7. 出口节点没有 chains --------------------------------------------

#[test]
fn exit_node_has_relay_service_without_chain() {
    let plans = plans_of(&three_hop_rows(), THREE_HOP);
    let exit = plans.last().unwrap();
    assert_eq!(exit.role, ChainRole::Exit);
    assert!(exit.chain.is_none(), "出口节点不创建 chains");
    let service = exit.relay_service.as_ref().expect("出口必须有 <tid>_tls");
    assert_eq!(service.name, relay_service_name(7));
    assert_eq!(service.name, "7_tls");
    assert!(service.chain.is_none(), "出口的 handler 不挂链");
}

// ---- 8. chain service 只出现在中继/出口节点 -----------------------------

#[test]
fn chain_service_only_on_relay_and_exit_nodes() {
    let plans = plans_of(&three_hop_rows(), THREE_HOP);
    for plan in &plans {
        let expected = plan.role != ChainRole::Entry;
        assert_eq!(
            plan.relay_service.is_some(),
            expected,
            "节点 {} 的 <tid>_tls 归属错误",
            plan.node
        );
        if plan.role == ChainRole::Entry {
            assert!(plan.chain.is_some(), "入口只建链");
            assert!(plan.relay_service.is_none(), "入口不建 <tid>_tls");
        }
    }
}

// ---- 9. 无中继时入口链直接指向出口 -------------------------------------

#[test]
fn entry_chain_points_at_exit_when_no_relay_group() {
    let pairs: &[(&str, &str)] = &[("entry-a", "10.0.0.1"), ("exit-a", "10.0.0.9")];
    let rows = vec![
        row(1, 4, "entry-a", ChainRole::Entry),
        row(12, 4, "exit-a", ChainRole::Exit).with_port(28000),
    ];
    let plans = plans_of(&rows, pairs);
    assert_eq!(
        plans[0].chain.as_ref().unwrap().elements(),
        vec!["node_exit-a@10.0.0.9:28000#tls"]
    );
    assert!(plans[1].chain.is_none());
    assert_eq!(plans[1].relay_service.as_ref().unwrap().port, 28000);
}

// ---- 10. 折算 Topology 保留逐行端口；selector 取下一跳首行 --------------

#[test]
fn topology_keeps_per_row_ports_and_selector_from_next_hop() {
    let mut rows = three_hop_rows();
    rows[1].strategy = Some("round".to_string());
    rows[2].strategy = Some("fifo".to_string());
    let publish = TunnelPublish::from_rows(&rows).expect("行应能解析");

    let topology = publish.to_topology().expect("端口齐全应可折算");
    assert_eq!(topology.entries, vec![node("entry-a")]);
    assert_eq!(topology.hops.len(), 2);
    assert_eq!(topology.hops[0][0].port, 20001);
    assert_eq!(topology.hops[1][0].port, 20002);
    assert_eq!(topology.exits[0].port, 20003);
    assert_eq!(topology.strategy.as_deref(), Some("round"));

    let plans = publish.plan(&servers(THREE_HOP)).expect("应可发布");
    let entry_chain = plans[0].chain.as_ref().unwrap();
    assert_eq!(entry_chain.selector.strategy.as_deref(), Some("round"));
    let relay_a_chain = plans[1].chain.as_ref().unwrap();
    assert_eq!(
        relay_a_chain.selector.strategy.as_deref(),
        Some("fifo"),
        "中继链的 strategy 取下一跳首行，不是隧道级单值"
    );
    assert_eq!(relay_a_chain.selector.max_fails, 1);
    assert_eq!(relay_a_chain.selector.fail_timeout, 600_000_000_000);
}

// ---- 11. 命名与地址渲染的固定口径 --------------------------------------

#[test]
fn names_and_addresses_match_beta_rendering() {
    assert_eq!(chain_name(7), "chains_7");
    assert_eq!(hop_name(7), "hop_7");
    assert_eq!(relay_service_name(7), "7_tls");
    assert_eq!(
        hop_element(&node("relay-a"), "10.0.0.2", 20001, "tls"),
        "node_relay-a@10.0.0.2:20001#tls"
    );
    assert_eq!(
        hop_element(&node("relay-a"), "2001:db8::1", 20001, "tls"),
        "node_relay-a@[2001:db8::1]:20001#tls",
        "IPv6 必须补方括号"
    );
    assert_eq!(
        hop_element(&node("relay-a"), "[2001:db8::1]", 20001, "wss"),
        "node_relay-a@[2001:db8::1]:20001#wss"
    );
    assert_eq!(process_server_address("10.0.0.2", 20001), "10.0.0.2:20001");
}

// ---- 12. 非法行直接拒，不静默降级 --------------------------------------

#[test]
fn unknown_chain_type_and_mixed_tunnel_are_rejected() {
    let mut broken = row(1, 7, "entry-a", ChainRole::Entry);
    broken.chain_type = 4;
    assert_eq!(
        TunnelPublish::from_rows(&[broken]).unwrap_err(),
        PublishError::UnknownChainType {
            row: 1,
            chain_type: 4,
        }
    );

    let mixed = vec![
        row(1, 7, "entry-a", ChainRole::Entry),
        row(12, 8, "exit-a", ChainRole::Exit).with_port(29000),
    ];
    assert!(matches!(
        TunnelPublish::from_rows(&mixed).unwrap_err(),
        PublishError::MixedTunnel { .. }
    ));
}
