//! 级联删除契约测试：对齐 beta TunnelServiceImpl.deleteTunnel（:1325-1362）、
//! cleanTunnelResources（:1368-1377）、cleanTunnelLimiters（:1386-1393）、
//! CascadeDeletePurger.purgeTunnelDatabaseRecords（:79-89）。
//!
//! 断言的是"析构生命周期"而不只是删了哪些名字：
//! - 限流器（limiter 名 = speed_limit.id）先拆，任何节点上的 limiter 都排在该节点 service/chain 之前；
//! - 每个出现过的 chain_tunnel 节点都拆 `<tid>_tls` 再拆 `chains_<tid>`（service 依赖 chain）；
//! - 入口节点的 `<tid>_tls` 也要拆（TunnelServiceImpl.java:1344-1347 的真机踩坑），暂停的转发照删；
//! - 库行按 purger 事务顺序、隧道行最后，且不被节点动作阻塞（非 strict）。
//!
//! 全部是内存判定，不连数据库、不连 GOST。

use flux_migration::{
    chain_name, forward_service_name, plan_cascade_delete, plan_topology_edit, relay_service_name,
    tunnel_resource_kinds, CascadeDeleteInput, CascadePhase, CascadeStepKind, ChainRole, ChainRow,
    ForwardCascade, ForwardRow, ForwardStatus, NodeId, PublishError, RowDelete, SpeedLimitRow,
    TopologyEdit, TunnelType, UserTunnelRow,
};

const TUNNEL: u64 = 36;

fn node(name: &str) -> NodeId {
    NodeId::new(name)
}

fn nodes(names: &[&str]) -> Vec<NodeId> {
    names.iter().map(|name| node(name)).collect()
}

fn entry_row(id: u64, tunnel: u64, name: &str) -> ChainRow {
    ChainRow::new(id, tunnel, node(name), ChainRole::Entry)
}

fn hop_row(id: u64, tunnel: u64, name: &str, role: ChainRole, port: u16) -> ChainRow {
    ChainRow::new(id, tunnel, node(name), role).with_port(port)
}

fn forward(
    id: u64,
    user_id: u64,
    user_tunnel: Option<u64>,
    status: i32,
    ports: &[(&str, u16)],
) -> ForwardCascade {
    ForwardCascade::new(
        ForwardRow::new(id, user_id, user_tunnel, status),
        ports
            .iter()
            .map(|(name, port)| flux_migration::EntryPort::new(node(name), *port))
            .collect(),
    )
}

/// 基线：入口 entry-a、中继 relay-b:30001、出口 exit-c:30002；
/// 两条转发（501 活跃、502 暂停）都挂在入口；两条限速规则分给两个使用者。
fn baseline() -> CascadeDeleteInput {
    CascadeDeleteInput::new(TUNNEL)
        .with_chain_rows(vec![
            entry_row(11, TUNNEL, "entry-a"),
            hop_row(12, TUNNEL, "relay-b", ChainRole::Relay, 30001),
            hop_row(13, TUNNEL, "exit-c", ChainRole::Exit, 30002),
        ])
        .with_forwards(vec![
            forward(
                501,
                7,
                Some(900),
                ForwardStatus::BETA_ACTIVE,
                &[("entry-a", 20001)],
            ),
            forward(
                502,
                7,
                None,
                ForwardStatus::BETA_PAUSED,
                &[("entry-a", 20002)],
            ),
        ])
        .with_speed_limits(vec![
            SpeedLimitRow::new(77, TUNNEL, "百兆", 100),
            SpeedLimitRow::new(78, TUNNEL, "五十兆", 50),
        ])
        .with_user_tunnels(vec![
            UserTunnelRow::new(900, 7, TUNNEL, Some(77)),
            UserTunnelRow::new(901, 8, TUNNEL, None),
        ])
}

/// 各节点上的预期序列：入口多两层转发服务，中继/出口只有隧道级资源。
fn expected_sequence(name: &str) -> Vec<String> {
    let mut sequence = vec!["limiter 77".to_string(), "limiter 78".to_string()];
    if name == "entry-a" {
        // 逐转发展开，每条转发内部 tcp 再 udp（ForwardServiceImpl.java:1325-1335）。
        for (forward_id, user_tunnel) in [(501, Some(900)), (502, None)] {
            for proto in [flux_migration::Proto::Tcp, flux_migration::Proto::Udp] {
                sequence.push(format!(
                    "service {}",
                    forward_service_name(forward_id, 7, user_tunnel, proto)
                ));
            }
        }
    }
    sequence.push(format!("service {}", relay_service_name(TUNNEL)));
    sequence.push(format!("chain {}", chain_name(TUNNEL)));
    sequence
}

fn plan() -> flux_migration::CascadeDeletePlan {
    plan_cascade_delete(&baseline()).expect("基线输入应当能产出完整析构计划")
}

// ---- 完整生命周期 ---------------------------------------------------------------

#[test]
fn plan_covers_every_owned_resource_once() {
    let plan = plan();

    assert_eq!(plan.tunnel_id, TUNNEL);
    assert!(
        plan.ordering_violations().is_empty(),
        "计划依赖顺序必须自洽"
    );
    // 2 条限速 × 3 个节点 = 6；2 条转发 × 1 个入口 × tcp/udp = 4；
    // 库行 2 forward + 2 forward_port + 2 user_tunnel + 2 speed_limit + 3 chain_tunnel + tunnel = 12；
    // 3 个节点 × (service + chain) = 6。
    assert_eq!(plan.steps.len(), 28, "析构动作数量应与资源量一致");
    assert_eq!(plan.phase_steps(CascadePhase::Limiter).len(), 6);
    assert_eq!(plan.phase_steps(CascadePhase::ForwardService).len(), 4);
    assert_eq!(plan.phase_steps(CascadePhase::DatabaseRow).len(), 12);
    assert_eq!(plan.phase_steps(CascadePhase::RelayService).len(), 3);
    assert_eq!(plan.phase_steps(CascadePhase::Chain).len(), 3);

    assert_eq!(plan.row_ids("forward"), vec![501, 502]);
    assert_eq!(plan.row_ids("forward_port"), vec![501, 502]);
    assert_eq!(plan.row_ids("user_tunnel"), vec![900, 901]);
    assert_eq!(plan.row_ids("speed_limit"), vec![77, 78]);
    assert_eq!(plan.row_ids("chain_tunnel"), vec![11, 12, 13]);
    assert_eq!(plan.row_ids("tunnel"), vec![TUNNEL]);
}

#[test]
fn limiter_is_destroyed_before_any_service_or_chain_on_that_node() {
    let plan = plan();
    let limiter_rank_max = plan
        .phase_steps(CascadePhase::Limiter)
        .iter()
        .map(|(index, _)| *index)
        .max()
        .expect("基线有限速规则");

    for phase in [
        CascadePhase::ForwardService,
        CascadePhase::RelayService,
        CascadePhase::Chain,
    ] {
        for (index, step) in plan.phase_steps(phase) {
            assert!(
                index > limiter_rank_max,
                "{} 步骤 {} 排到了 limiter 之前：限流器必须先拆",
                phase.label(),
                index
            );
            assert!(
                !step.depends_on.is_empty(),
                "节点动作必须显式依赖该节点上的 limiter"
            );
        }
    }

    for (_, step) in plan.phase_steps(CascadePhase::Limiter) {
        assert!(step.depends_on.is_empty(), "limiter 是最早阶段，不该有前置");
    }
}

#[test]
fn every_observed_node_destroys_service_before_chain() {
    let plan = plan();

    for name in ["entry-a", "relay-b", "exit-c"] {
        let sequence = plan.node_sequence(&node(name));
        assert_eq!(
            sequence,
            expected_sequence(name),
            "节点 {name} 的顺序必须是 limiter → service → chain（TunnelServiceImpl.java:1368-1377）"
        );
    }

    // 全局不比较：节点是成块编排的（entry-a 的 chain 早于 relay-b 的 service），
    // 真正的约束是同节点内 service 必须在 chain 之前，且 chain 显式依赖它。
    for (chain_index, chain_step) in plan.phase_steps(CascadePhase::Chain) {
        let node = chain_step.node().expect("chain 步骤必须带节点");
        let service_index = plan
            .node_steps(node)
            .into_iter()
            .find(|(_, step)| matches!(step.kind, CascadeStepKind::RelayService { .. }))
            .map(|(index, _)| index)
            .unwrap_or_else(|| panic!("节点 {node:?} 缺少 service 步骤"));
        assert!(service_index < chain_index, "service 先于 chain 析构");
        assert!(
            chain_step.depends_on.contains(&service_index),
            "chain 必须显式依赖同节点的 service"
        );
    }
}

#[test]
fn entry_node_gets_service_sweep_even_though_it_owns_only_entry_rows() {
    let plan = plan();

    let entry_services = plan
        .node_steps(&node("entry-a"))
        .into_iter()
        .filter_map(|(_, step)| match &step.kind {
            CascadeStepKind::RelayService { service, port, .. } => Some((service.clone(), *port)),
            _ => None,
        })
        .collect::<Vec<_>>();
    assert_eq!(
        entry_services,
        vec![(relay_service_name(TUNNEL), None)],
        "入口行没有端口，但 <tid>_tls 仍要拆（残留会被 loader 重启占端口）"
    );

    let relay_services = plan
        .node_steps(&node("relay-b"))
        .into_iter()
        .filter_map(|(_, step)| match &step.kind {
            CascadeStepKind::RelayService { service, port, .. } => Some((service.clone(), *port)),
            _ => None,
        })
        .collect::<Vec<_>>();
    assert_eq!(
        relay_services,
        vec![(relay_service_name(TUNNEL), Some(30001))]
    );

    let exit_services = plan
        .node_steps(&node("exit-c"))
        .into_iter()
        .filter_map(|(_, step)| match &step.kind {
            CascadeStepKind::RelayService { service, port, .. } => Some((service.clone(), *port)),
            _ => None,
        })
        .collect::<Vec<_>>();
    assert_eq!(
        exit_services,
        vec![(relay_service_name(TUNNEL), Some(30002))]
    );
}

#[test]
fn forward_services_are_deleted_by_name_for_active_and_paused_forwards() {
    let plan = plan();

    let entry_forward_services = plan
        .node_steps(&node("entry-a"))
        .into_iter()
        .filter_map(|(_, step)| match &step.kind {
            CascadeStepKind::ForwardService {
                forward,
                service,
                port,
                ..
            } => Some((*forward, service.clone(), *port)),
            _ => None,
        })
        .collect::<Vec<_>>();

    assert_eq!(
        entry_forward_services,
        vec![
            (
                501,
                forward_service_name(501, 7, Some(900), flux_migration::Proto::Tcp),
                Some(20001)
            ),
            (
                501,
                forward_service_name(501, 7, Some(900), flux_migration::Proto::Udp),
                Some(20001)
            ),
            (
                502,
                forward_service_name(502, 7, None, flux_migration::Proto::Tcp),
                Some(20002)
            ),
            (
                502,
                forward_service_name(502, 7, None, flux_migration::Proto::Udp),
                Some(20002)
            ),
        ],
        "暂停的转发照样按名字删 tcp/udp 服务（ForwardServiceImpl.java:1325-1335 不看 status）"
    );

    assert!(
        plan.notes.iter().any(|note| note.contains("暂停")),
        "暂停转发必须在 notes 里点名"
    );
}

#[test]
fn forward_without_forward_port_still_gets_service_names() {
    let input = CascadeDeleteInput::new(TUNNEL)
        .with_chain_rows(vec![entry_row(11, TUNNEL, "entry-a")])
        .with_forwards(vec![ForwardCascade::new(
            ForwardRow::new(503, 7, None, ForwardStatus::BETA_ACTIVE),
            vec![],
        )]);

    let plan = plan_cascade_delete(&input).expect("缺 forward_port 不该阻断析构");
    let services = plan
        .node_steps(&node("entry-a"))
        .into_iter()
        .filter_map(|(_, step)| match &step.kind {
            CascadeStepKind::ForwardService { service, port, .. } => Some((service.clone(), *port)),
            _ => None,
        })
        .collect::<Vec<_>>();

    assert_eq!(
        services,
        vec![
            (
                forward_service_name(503, 7, None, flux_migration::Proto::Tcp),
                None
            ),
            (
                forward_service_name(503, 7, None, flux_migration::Proto::Udp),
                None
            ),
        ],
        "beta 只按名字 DeleteService，端口留空不猜"
    );
    assert!(plan
        .notes
        .iter()
        .any(|note| note.contains("缺 forward_port 行")));
}

// ---- 库行事务 ---------------------------------------------------------------

#[test]
fn rows_follow_purger_order_and_tunnel_row_goes_last() {
    let plan = plan();

    let tables = plan
        .rows()
        .into_iter()
        .map(|(_, row)| row.table())
        .collect::<Vec<_>>();
    assert_eq!(
        tables,
        vec![
            "forward",
            "forward",
            "forward_port",
            "forward_port",
            "user_tunnel",
            "user_tunnel",
            "speed_limit",
            "speed_limit",
            "chain_tunnel",
            "chain_tunnel",
            "chain_tunnel",
            "tunnel",
        ],
        "顺序必须与 CascadeDeletePurger.java:81-88 一致，隧道行最后"
    );

    for (_, step) in plan.phase_steps(CascadePhase::DatabaseRow) {
        assert!(
            step.depends_on.is_empty(),
            "库行不等节点结果：非 strict 下节点失败只记 leftovers"
        );
    }

    let last_row = plan.rows().last().map(|(_, row)| *row);
    assert_eq!(last_row, Some(&RowDelete::Tunnel { tunnel: TUNNEL }));
}

// ---- 拒绝错误行集 ---------------------------------------------------------------

#[test]
fn foreign_rows_are_rejected_per_table() {
    let speed_limit = CascadeDeleteInput::new(TUNNEL)
        .with_speed_limits(vec![SpeedLimitRow::new(77, 99, "别处", 100)]);
    assert_eq!(
        plan_cascade_delete(&speed_limit),
        Err(PublishError::ForeignRow {
            table: "speed_limit",
            row: 77,
            expected: TUNNEL,
            found: 99,
        })
    );

    let user_tunnel = CascadeDeleteInput::new(TUNNEL)
        .with_user_tunnels(vec![UserTunnelRow::new(900, 7, 99, None)]);
    assert_eq!(
        plan_cascade_delete(&user_tunnel),
        Err(PublishError::ForeignRow {
            table: "user_tunnel",
            row: 900,
            expected: TUNNEL,
            found: 99,
        })
    );

    let chain_row =
        CascadeDeleteInput::new(TUNNEL).with_chain_rows(vec![entry_row(11, 99, "entry-a")]);
    assert_eq!(
        plan_cascade_delete(&chain_row),
        Err(PublishError::ForeignRow {
            table: "chain_tunnel",
            row: 11,
            expected: TUNNEL,
            found: 99,
        })
    );
}

#[test]
fn forward_without_entry_rows_is_rejected() {
    let input = CascadeDeleteInput::new(TUNNEL).with_forwards(vec![forward(
        501,
        7,
        None,
        ForwardStatus::BETA_ACTIVE,
        &[("entry-a", 20001)],
    )]);

    assert_eq!(
        plan_cascade_delete(&input),
        Err(PublishError::MissingEntryNode { forward: 501 })
    );
}

#[test]
fn entry_port_on_non_entry_node_is_rejected() {
    let input = CascadeDeleteInput::new(TUNNEL)
        .with_chain_rows(vec![
            entry_row(11, TUNNEL, "entry-a"),
            hop_row(12, TUNNEL, "relay-b", ChainRole::Relay, 30001),
        ])
        .with_forwards(vec![forward(
            501,
            7,
            None,
            ForwardStatus::BETA_ACTIVE,
            &[("entry-a", 20001), ("relay-b", 20002)],
        )]);

    assert_eq!(
        plan_cascade_delete(&input),
        Err(PublishError::UnboundEntryPort {
            forward: 501,
            node: node("relay-b"),
        })
    );
}

// ---- 确定性与边界 ---------------------------------------------------------------

#[test]
fn plan_is_deterministic_under_shuffled_input() {
    let mut shuffled = baseline();
    shuffled.chain_rows.reverse();
    shuffled.forwards.reverse();
    shuffled.speed_limits.reverse();
    shuffled.user_tunnels.reverse();

    let ordered = plan();
    let shuffled = plan_cascade_delete(&shuffled).expect("乱序输入应当产出同一计划");

    assert_eq!(shuffled.steps, ordered.steps, "析构顺序不能依赖入参顺序");
    assert_eq!(shuffled.notes, ordered.notes);
    assert!(shuffled.ordering_violations().is_empty());
}

#[test]
fn empty_tunnel_only_purges_the_tunnel_row() {
    let plan = plan_cascade_delete(&CascadeDeleteInput::new(TUNNEL)).expect("空隧道也要能删");

    assert_eq!(plan.steps.len(), 1);
    assert_eq!(plan.rows()[0].1, &RowDelete::Tunnel { tunnel: TUNNEL });
    assert!(plan.phase_steps(CascadePhase::Limiter).is_empty());
    assert!(plan.phase_steps(CascadePhase::Chain).is_empty());
    assert!(plan
        .notes
        .iter()
        .any(|note| note.contains("输入即快照") && note.contains("purge")));
}

// ---- 与拓扑编辑共用同一份清理口径 ---------------------------------------------------------------

#[test]
fn topology_edit_cleanup_reuses_the_delete_sequence() {
    let edit = TopologyEdit {
        tunnel_id: TUNNEL,
        old_type: TunnelType::TunnelForward,
        new_type: TunnelType::PortForward,
        old_entries: nodes(&["entry-a"]),
        new_entries: nodes(&["entry-a"]),
        chain_groups: Vec::new(),
        out_node: None,
        forward_count: 0,
    };

    let plan = plan_topology_edit(&edit).expect("2→1 且无转发应当放行");
    assert!(plan.notes.iter().any(|note| note.contains("类型 2 → 1")));

    let cleanup = plan.cleanup_nodes(&edit, &nodes(&["entry-a", "relay-b", "exit-c"]));
    assert_eq!(
        cleanup,
        nodes(&["entry-a", "relay-b", "exit-c"]),
        "2→1 时入口未变的节点也要清（TunnelServiceImpl.java:1065-1074）"
    );

    let [service, chain] = tunnel_resource_kinds(TUNNEL, &node("relay-b"), Some(30001));
    assert_eq!(
        service,
        CascadeStepKind::RelayService {
            node: node("relay-b"),
            service: relay_service_name(TUNNEL),
            port: Some(30001),
        }
    );
    assert_eq!(
        chain,
        CascadeStepKind::Chains {
            node: node("relay-b"),
            chain: chain_name(TUNNEL),
        },
        "删除与编辑共用 service → chain 的清理顺序"
    );
}

#[test]
fn topology_edit_cleanup_still_returns_old_only_entries_when_type_is_unchanged() {
    let mut edit = TopologyEdit {
        tunnel_id: TUNNEL,
        old_type: TunnelType::PortForward,
        new_type: TunnelType::PortForward,
        old_entries: nodes(&["entry-a", "entry-b"]),
        new_entries: nodes(&["entry-b"]),
        chain_groups: Vec::new(),
        out_node: None,
        forward_count: 0,
    };
    edit.new_entries = nodes(&["entry-b"]);

    let plan = plan_topology_edit(&edit).expect("同类型换入口应当放行");
    assert!(!plan.type_changed);
    assert_eq!(plan.old_only_entries, nodes(&["entry-a"]));
    assert_eq!(
        plan.cleanup_nodes(&edit, &nodes(&["entry-a", "entry-b"])),
        nodes(&["entry-a"]),
        "类型不变时只清旧拓扑独有的入口节点"
    );
}
