//! 规划期校验：端口区间 1..=65535 + beta tunnel.type fail-closed（round44 口径）。
//!
//! beta 侧（GostUtil.java:210-214）在构造转发服务时对 tunnel.type 做 fail-closed：
//! 不是 1/2 就整条拒绝下发，因为按中继兜底会 bind 失败、按直连下发会明文穿透。
//! 迁移原型必须在 plan 阶段复现同一判定，否则迁移后同一份数据两边结论不一致。
//!
//! 这些测试只驱动内存状态机，不连接任何 GOST / 数据库。

use flux_migration::{
    check_port, Candidate, Capability, Engine, EngineError, EntryPort, ForwardId, ForwardSpec,
    ForwardStatus, MigrationPlan, NodeCapabilities, NodeId, OperationId, Proto, Topology,
    TunnelType, PORT_MAX, PORT_MIN, TUNNEL_TYPE_PORT_FORWARD, TUNNEL_TYPE_TUNNEL_FORWARD,
};

// ---- 辅助 ---------------------------------------------------------------

fn node(name: &str) -> NodeId {
    NodeId::new(name)
}

/// 一条最小可发布隧道：入口 entry-a → 中继 relay-b → 出口 exit-d。
fn plan_with(entry_port: u16, relay_port: u16, exit_port: u16) -> MigrationPlan {
    MigrationPlan {
        op_id: OperationId::new("op-plan-validation"),
        tunnel_id: "tun44".to_string(),
        old: Topology {
            entries: vec![node("entry-a")],
            hops: vec![],
            exits: vec![Candidate::new(node("exit-d"), exit_port)],
            strategy: None,
        },
        new: Topology {
            entries: vec![node("entry-a")],
            hops: vec![vec![Candidate::new(node("relay-b"), relay_port)]],
            exits: vec![Candidate::new(node("exit-d"), exit_port)],
            strategy: None,
        },
        forwards: vec![ForwardSpec {
            forward_id: ForwardId(1),
            user_id: 7,
            user_tunnel_id: 0,
            protocols: vec![Proto::Tcp],
            entry_ports: vec![EntryPort::new(node("entry-a"), entry_port)],
            old_entry_ports: vec![EntryPort::new(node("entry-a"), entry_port)],
            status: ForwardStatus::Active,
            pause_reason: None,
        }],
    }
}

fn caps_for(plan: &MigrationPlan) -> Vec<NodeCapabilities> {
    let mut nodes = plan.new.nodes();
    nodes.extend(plan.old.nodes());
    nodes.sort();
    nodes.dedup();
    nodes
        .into_iter()
        .map(|name| {
            NodeCapabilities::new(name, [Capability::AtomicPortReserve, Capability::ProbePort])
                .with(Capability::QueryServiceState)
        })
        .collect()
}

// ---- 端口区间 -----------------------------------------------------------

#[test]
fn rejects_port_above_65535_at_the_plan_entry() {
    let candidate = Candidate::from_beta(node("relay-b"), 70000, "新拓扑");
    match candidate {
        Err(reason) => assert!(reason.contains("70000"), "{reason}"),
        Ok(_) => panic!("70000 不该被当成合法端口"),
    }
    assert!(matches!(
        EntryPort::from_beta(node("entry-a"), 70000, "新拓扑"),
        Err(reason) if reason.contains("70000") && reason.contains("入口")
    ));
    assert!(matches!(
        check_port(&node("exit-d"), 65536, "新拓扑"),
        Err(reason) if reason.contains("65536")
    ));
}

#[test]
fn rejects_zero_and_negative_ports() {
    assert!(matches!(
        check_port(&node("relay-b"), 0, "新拓扑"),
        Err(reason) if reason.contains("端口为 0")
    ));
    assert!(matches!(
        EntryPort::from_beta(node("entry-a"), 0, "新拓扑"),
        Err(reason) if reason.contains("入口端口为 0")
    ));
    assert!(matches!(
        check_port(&node("exit-d"), -1, "新拓扑"),
        Err(reason) if reason.contains("-1")
    ));
}

#[test]
fn accepts_ports_1_and_65535_as_boundaries() {
    assert_eq!(PORT_MIN, 1);
    assert_eq!(PORT_MAX, 65535);
    assert_eq!(check_port(&node("relay-b"), 1, "新拓扑"), Ok(1));
    assert_eq!(check_port(&node("relay-b"), 65535, "新拓扑"), Ok(65535));
    assert_eq!(check_port(&node("relay-b"), 2, "新拓扑"), Ok(2));
    assert_eq!(check_port(&node("relay-b"), 65534, "新拓扑"), Ok(65534));

    // 边界端口要能一路走到拓扑校验与引擎构造，不被区间判断误杀。
    let plan = plan_with(1, 65535, 1);
    assert_eq!(plan.validate(), Ok(()));
    let caps = caps_for(&plan);
    let engine =
        Engine::new_with_tunnel_type(plan, caps, Some(1)).expect("边界端口 + type=1 应当通过");
    assert_eq!(engine.tunnel_type(), Some(TunnelType::PortForward));
}

#[test]
fn zero_ports_are_rejected_by_the_topology_validator() {
    let mut plan = plan_with(1, 1, 1);
    plan.new.exits[0].port = 0;
    match Engine::new_with_tunnel_type(plan, vec![], Some(1)) {
        Err(EngineError::InvalidPlan(reason)) => {
            assert!(reason.contains("监听端口为 0"), "{reason}")
        }
        other => panic!("expected InvalidPlan, got {other:?}"),
    }
}

// ---- 隧道类型 -----------------------------------------------------------

#[test]
fn rejects_tunnel_type_none() {
    assert!(matches!(
        TunnelType::from_beta(None),
        Err(reason) if reason.contains("NULL")
    ));
    let plan = plan_with(1, 1, 1);
    assert!(matches!(
        plan.validate_with_tunnel_type(None),
        Err(reason) if reason.contains("tunnel.type=NULL")
    ));
    match Engine::new_with_tunnel_type(plan, vec![], None) {
        Err(EngineError::InvalidPlan(reason)) => {
            assert!(reason.contains("隧道类型不合法"), "{reason}")
        }
        other => panic!("expected InvalidPlan, got {other:?}"),
    }
}

#[test]
fn rejects_tunnel_type_outside_1_and_2() {
    assert!(matches!(
        TunnelType::from_beta(Some(9)),
        Err(reason) if reason.contains("tunnel.type=9")
    ));
    assert!(TunnelType::from_beta(Some(3)).is_err());
    assert!(TunnelType::from_beta(Some(0)).is_err());
    let plan = plan_with(1, 1, 1);
    match Engine::new_with_tunnel_type(plan, vec![], Some(9)) {
        Err(EngineError::InvalidPlan(reason)) => {
            assert!(reason.contains("tunnel.type=9"), "{reason}")
        }
        other => panic!("expected InvalidPlan, got {other:?}"),
    }
}

#[test]
fn accepts_tunnel_type_1_and_2() {
    assert_eq!(TunnelType::from_beta(Some(1)), Ok(TunnelType::PortForward));
    assert_eq!(
        TunnelType::from_beta(Some(2)),
        Ok(TunnelType::TunnelForward)
    );
    assert_eq!(TunnelType::PortForward.as_beta(), TUNNEL_TYPE_PORT_FORWARD);
    assert_eq!(
        TunnelType::TunnelForward.as_beta(),
        TUNNEL_TYPE_TUNNEL_FORWARD
    );

    let plan = plan_with(1, 30001, 30003);
    let caps = caps_for(&plan);
    let port_forward =
        Engine::new_with_tunnel_type(plan.clone(), caps.clone(), Some(1)).expect("type=1 应通过");
    assert_eq!(port_forward.tunnel_type(), Some(TunnelType::PortForward));
    assert_eq!(port_forward.phase(), flux_migration::Phase::Draft);
    assert!(port_forward.actions().is_empty());

    // type 证据必须跟着快照走，回放后不能被悄悄降级成 None。
    let snapshot = port_forward.snapshot();
    let restored = Engine::restore(&snapshot).expect("snapshot 应可回放");
    assert_eq!(restored.tunnel_type(), Some(TunnelType::PortForward));

    let tunnel_forward = Engine::new_with_tunnel_type(plan, caps, Some(2)).expect("type=2 应通过");
    assert_eq!(
        tunnel_forward.tunnel_type(),
        Some(TunnelType::TunnelForward)
    );
}

// ---- 组合：类型合法但端口越界仍然拒绝 -----------------------------------

#[test]
fn illegal_type_and_illegal_port_both_block_planning() {
    let plan = plan_with(1, 1, 1);
    assert!(matches!(
        Engine::new_with_tunnel_type(plan.clone(), vec![], Some(7)),
        Err(EngineError::InvalidPlan(_))
    ));
    // 类型合法时，越界端口仍要在拓扑入口被拦下。
    let mut bad_port = plan.clone();
    bad_port.forwards[0].entry_ports[0].port = 0;
    match Engine::new_with_tunnel_type(bad_port, vec![], Some(2)) {
        Err(EngineError::InvalidPlan(reason)) => {
            assert!(reason.contains("入口端口为 0"), "{reason}")
        }
        other => panic!("expected InvalidPlan, got {other:?}"),
    }
}
