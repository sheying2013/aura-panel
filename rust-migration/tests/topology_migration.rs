//! 端到端（纯逻辑）测试：验证状态机与 beta 拓扑语义一致，且未知结果不会冒充成功。
//!
//! 这些测试只驱动内存状态机，不连接任何 GOST / 数据库，也不证明零中断。

use flux_migration::{
    ActionId, ActionKind, ActionStatus, Candidate, Capability, CleanupRejection, Engine,
    EngineError, EntryPort, ForwardId, ForwardSpec, ForwardStatus, LegacyState, MigrationPlan,
    NodeCapabilities, NodeId, OperationId, Phase, Proto, Receipt, ReceiptDecision,
    ReceiptRejection, ResourceKey, ResourceKind, Selector, Topology,
};

// ---- 辅助 ---------------------------------------------------------------

fn node(name: &str) -> NodeId {
    NodeId::new(name)
}

fn cand(name: &str, port: u16) -> Candidate {
    Candidate::new(node(name), port)
}

fn caps(plan: &MigrationPlan, with_query: bool) -> Vec<NodeCapabilities> {
    let mut nodes = plan.new.nodes();
    nodes.extend(plan.old.nodes());
    nodes.sort();
    nodes.dedup();
    nodes
        .into_iter()
        .map(|name| {
            let base =
                NodeCapabilities::new(name, [Capability::AtomicPortReserve, Capability::ProbePort]);
            if with_query {
                base.with(Capability::QueryServiceState)
            } else {
                base
            }
        })
        .collect()
}

fn legacy_caps(plan: &MigrationPlan) -> Vec<NodeCapabilities> {
    let mut nodes = plan.new.nodes();
    nodes.extend(plan.old.nodes());
    nodes.sort();
    nodes.dedup();
    nodes.into_iter().map(NodeCapabilities::legacy).collect()
}

fn new_engine(plan: MigrationPlan, with_query: bool) -> Engine {
    let caps = caps(&plan, with_query);
    Engine::new(plan, caps).expect("plan should be valid")
}

fn base_plan() -> MigrationPlan {
    MigrationPlan {
        op_id: OperationId::new("op-1"),
        tunnel_id: "tun9".to_string(),
        old: Topology {
            entries: vec![node("entry-a")],
            hops: vec![],
            exits: vec![cand("old-exit", 30009)],
            strategy: Some("round".to_string()),
        },
        new: Topology {
            entries: vec![node("entry-a")],
            hops: vec![vec![cand("relay-b", 30001), cand("relay-c", 30002)]],
            exits: vec![cand("exit-d", 30003)],
            strategy: Some("round".to_string()),
        },
        forwards: vec![
            ForwardSpec {
                forward_id: ForwardId(1),
                user_id: 7,
                user_tunnel_id: 0,
                protocols: vec![Proto::Tcp, Proto::Udp],
                entry_ports: vec![EntryPort::new(node("entry-a"), 20001)],
                old_entry_ports: vec![EntryPort::new(node("entry-a"), 20001)],
                status: ForwardStatus::Active,
                pause_reason: None,
            },
            ForwardSpec {
                forward_id: ForwardId(2),
                user_id: 7,
                user_tunnel_id: 11,
                protocols: vec![Proto::Tcp],
                entry_ports: vec![EntryPort::new(node("entry-a"), 20002)],
                old_entry_ports: vec![EntryPort::new(node("entry-a"), 20002)],
                status: ForwardStatus::Active,
                pause_reason: None,
            },
        ],
    }
}

/// 多入口 / 两跳（每跳多候选）/ 多出口；第一跳故意让 zz 排在 aa 前面。
fn multi_plan() -> MigrationPlan {
    MigrationPlan {
        op_id: OperationId::new("op-1"),
        tunnel_id: "tun9".to_string(),
        old: Topology {
            entries: vec![node("entry-a")],
            hops: vec![vec![cand("old-mid", 30041)]],
            exits: vec![cand("old-exit", 30042)],
            strategy: Some("round".to_string()),
        },
        new: Topology {
            entries: vec![node("entry-a"), node("entry-b")],
            hops: vec![
                vec![cand("zz-relay", 30011), cand("aa-relay", 30012)],
                vec![cand("mid-relay", 30021)],
            ],
            exits: vec![cand("exit-x", 30031), cand("exit-y", 30032)],
            strategy: Some("round".to_string()),
        },
        forwards: vec![
            ForwardSpec {
                forward_id: ForwardId(1),
                user_id: 7,
                user_tunnel_id: 0,
                protocols: vec![Proto::Tcp, Proto::Udp],
                entry_ports: vec![
                    EntryPort::new(node("entry-a"), 20001),
                    EntryPort::new(node("entry-b"), 21001),
                ],
                old_entry_ports: vec![EntryPort::new(node("entry-a"), 20001)],
                status: ForwardStatus::Active,
                pause_reason: None,
            },
            ForwardSpec {
                forward_id: ForwardId(2),
                user_id: 7,
                user_tunnel_id: 11,
                protocols: vec![Proto::Tcp],
                entry_ports: vec![
                    EntryPort::new(node("entry-a"), 20002),
                    EntryPort::new(node("entry-b"), 21002),
                ],
                old_entry_ports: vec![EntryPort::new(node("entry-a"), 20002)],
                status: ForwardStatus::Active,
                pause_reason: None,
            },
        ],
    }
}

fn attempt_of(engine: &Engine, id: ActionId) -> u32 {
    engine.action(id).expect("action exists").attempt
}

fn node_of(engine: &Engine, id: ActionId) -> NodeId {
    engine.action(id).expect("action exists").node.clone()
}

fn confirm(engine: &mut Engine, id: ActionId) {
    let op = engine.plan().op_id.clone();
    let (node, attempt) = (node_of(engine, id), attempt_of(engine, id));
    let decision = engine
        .apply_receipt(&Receipt::success(op, id, node, attempt))
        .expect("command-ack receipt accepted");
    assert!(
        matches!(decision, ReceiptDecision::Applied { .. }),
        "{decision:?}"
    );
}

fn verify_state(engine: &mut Engine, id: ActionId) {
    let op = engine.plan().op_id.clone();
    let (node, attempt) = (node_of(engine, id), attempt_of(engine, id));
    let decision = engine
        .apply_receipt(&Receipt::verified(op, id, node, attempt))
        .expect("verified receipt accepted");
    assert!(
        matches!(decision, ReceiptDecision::Applied { .. }),
        "{decision:?}"
    );
}

fn drive(engine: &mut Engine, phase: Phase, verify_cutover: bool) -> usize {
    let mut count = 0;
    loop {
        let ids = engine.dispatch_all(phase).expect("dispatch wave");
        if ids.is_empty() {
            break;
        }
        for id in ids {
            count += 1;
            let is_cutover = engine.action(id).expect("action").kind.is_cutover();
            if verify_cutover && is_cutover {
                verify_state(engine, id);
            } else {
                confirm(engine, id);
            }
        }
    }
    assert!(count > 0, "phase {phase} produced no actions");
    count
}

fn chain_id(engine: &Engine, name: &str) -> ActionId {
    engine
        .actions()
        .iter()
        .find(|action| {
            action.phase == Phase::Provisioning
                && action.node == node(name)
                && matches!(action.kind, ActionKind::AddChains { .. })
        })
        .map(|action| action.id)
        .unwrap_or_else(|| panic!("chain action for {name}"))
}

fn listener_id(engine: &Engine, name: &str) -> ActionId {
    engine
        .actions()
        .iter()
        .find(|action| {
            action.phase == Phase::Provisioning
                && action.node == node(name)
                && matches!(action.kind, ActionKind::AddListener { .. })
        })
        .map(|action| action.id)
        .unwrap_or_else(|| panic!("listener action for {name}"))
}

fn service_id(engine: &Engine, service: &str) -> ActionId {
    engine
        .actions()
        .iter()
        .find(|action| {
            matches!(&action.kind, ActionKind::AddService { service: name, .. } if name == service)
        })
        .map(|action| action.id)
        .unwrap_or_else(|| panic!("service action {service}"))
}

fn chain_next(engine: &Engine, id: ActionId) -> Vec<(String, u16)> {
    match &engine.action(id).expect("action").kind {
        ActionKind::AddChains { next, .. } => next
            .iter()
            .map(|candidate| (candidate.node.to_string(), candidate.port))
            .collect(),
        other => panic!("not a chain action: {other:?}"),
    }
}

fn chain_hop(engine: &Engine, id: ActionId) -> u8 {
    match &engine.action(id).expect("action").kind {
        ActionKind::AddChains { hop, .. } => *hop,
        other => panic!("not a chain action: {other:?}"),
    }
}

fn listener_meta(engine: &Engine, id: ActionId) -> (u16, bool) {
    match &engine.action(id).expect("action").kind {
        ActionKind::AddListener { port, chained, .. } => (*port, *chained),
        other => panic!("not a listener action: {other:?}"),
    }
}

fn service_meta(engine: &Engine, id: ActionId) -> (String, u16, Proto, String) {
    match &engine.action(id).expect("action").kind {
        ActionKind::AddService {
            service,
            port,
            proto,
            chain,
            ..
        } => (service.clone(), *port, *proto, chain.clone()),
        other => panic!("not a service action: {other:?}"),
    }
}

fn dep_count(engine: &Engine, id: ActionId) -> usize {
    engine.action(id).expect("action").depends_on.len()
}

fn legacy_listener_key() -> ResourceKey {
    ResourceKey::new(node("old-exit"), "tun9_tls".to_string())
}

fn legacy_listener_kind() -> ResourceKind {
    ResourceKind::Listener {
        listener: "tun9_tls".to_string(),
        port: 30009,
    }
}

// ---- 1. 输入校验 --------------------------------------------------------

#[test]
fn rejects_folded_back_and_duplicated_nodes() {
    let mut plan = base_plan();
    plan.new.hops = vec![vec![cand("relay-b", 30001)], vec![cand("relay-b", 30002)]];
    let error = Engine::new(plan, Vec::new()).unwrap_err();
    match error {
        EngineError::InvalidPlan(message) => assert!(message.contains("重复"), "{message}"),
        other => panic!("expected InvalidPlan, got {other:?}"),
    }

    let mut plan = base_plan();
    plan.new.hops = vec![vec![cand("entry-a", 30001)]];
    let error = Engine::new(plan, Vec::new()).unwrap_err();
    assert!(
        matches!(error, EngineError::InvalidPlan(ref m) if m.contains("重复")),
        "{error:?}"
    );
}

#[test]
fn rejects_zero_ports_empty_hops_and_bad_forward_ports() {
    let mut plan = base_plan();
    plan.new.exits = vec![cand("exit-d", 0)];
    let error = Engine::new(plan, Vec::new()).unwrap_err();
    assert!(
        matches!(error, EngineError::InvalidPlan(ref m) if m.contains("端口为 0")),
        "{error:?}"
    );

    let mut plan = base_plan();
    plan.new.hops = vec![vec![]];
    let error = Engine::new(plan, Vec::new()).unwrap_err();
    assert!(
        matches!(error, EngineError::InvalidPlan(ref m) if m.contains("没有候选节点")),
        "{error:?}"
    );

    let mut plan = base_plan();
    plan.new.entries.clear();
    let error = Engine::new(plan, Vec::new()).unwrap_err();
    assert!(
        matches!(error, EngineError::InvalidPlan(ref m) if m.contains("没有入口节点")),
        "{error:?}"
    );

    let mut plan = base_plan();
    plan.forwards[0].entry_ports = vec![EntryPort::new(node("entry-a"), 0)];
    let error = Engine::new(plan, Vec::new()).unwrap_err();
    assert!(
        matches!(error, EngineError::InvalidPlan(ref m) if m.contains("入口端口为 0")),
        "{error:?}"
    );

    // 多入口时漏掉一个入口的端口必须报错，不能默认沿用别的入口端口。
    let mut plan = multi_plan();
    plan.forwards[0].entry_ports = vec![EntryPort::new(node("entry-a"), 20001)];
    let error = Engine::new(plan, Vec::new()).unwrap_err();
    assert!(
        matches!(error, EngineError::InvalidPlan(ref m) if m.contains("缺少入口节点")),
        "{error:?}"
    );

    let mut plan = base_plan();
    plan.forwards[1].protocols = vec![Proto::Tcp, Proto::Tcp];
    let error = Engine::new(plan, Vec::new()).unwrap_err();
    assert!(
        matches!(error, EngineError::InvalidPlan(ref m) if m.contains("重复声明协议")),
        "{error:?}"
    );
}

// ---- 2. 拓扑下发语义 ----------------------------------------------------

#[test]
fn entries_and_relays_chain_to_next_hop_while_exits_only_listen() {
    let plan = multi_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    let provisioning: Vec<&flux_migration::Action> = engine.actions_in(Phase::Provisioning);
    let chains = provisioning
        .iter()
        .filter(|a| matches!(a.kind, ActionKind::AddChains { .. }))
        .count();
    let listeners = provisioning
        .iter()
        .filter(|a| matches!(a.kind, ActionKind::AddListener { .. }))
        .count();
    // 入口 2 条链 + 三个中继候选各 1 条链；出口不建链。
    assert_eq!(chains, 5);
    // 三个中继候选 + 两个出口各一个监听；入口不建监听。
    assert_eq!(listeners, 5);

    let entry_a = chain_id(&engine, "entry-a");
    let entry_b = chain_id(&engine, "entry-b");
    let expected_first = vec![
        ("zz-relay".to_string(), 30011u16),
        ("aa-relay".to_string(), 30012),
    ];
    assert_eq!(chain_next(&engine, entry_a), expected_first);
    assert_eq!(chain_next(&engine, entry_b), expected_first);
    assert_eq!(chain_hop(&engine, entry_a), 0);

    let zz = chain_id(&engine, "zz-relay");
    let aa = chain_id(&engine, "aa-relay");
    let mid = chain_id(&engine, "mid-relay");
    assert_eq!(
        chain_next(&engine, zz),
        vec![("mid-relay".to_string(), 30021)]
    );
    assert_eq!(
        chain_next(&engine, aa),
        vec![("mid-relay".to_string(), 30021)]
    );
    assert_eq!(chain_hop(&engine, zz), 1);
    assert_eq!(chain_hop(&engine, mid), 2);
    assert_eq!(
        chain_next(&engine, mid),
        vec![("exit-x".to_string(), 30031), ("exit-y".to_string(), 30032)]
    );

    // 出口只有监听，且监听不带 chain。
    for exit in ["exit-x", "exit-y"] {
        assert!(
            engine
                .actions()
                .iter()
                .all(|a| !(a.node == node(exit) && matches!(a.kind, ActionKind::AddChains { .. }))),
            "{exit} 不应创建链"
        );
        let (_, chained) = listener_meta(&engine, listener_id(&engine, exit));
        assert!(!chained, "{exit} 的监听不应挂链");
    }
    // 中继监听挂链；入口没有监听。
    for relay in ["zz-relay", "aa-relay", "mid-relay"] {
        let (_, chained) = listener_meta(&engine, listener_id(&engine, relay));
        assert!(chained, "{relay} 的监听应挂共享链");
    }
    for entry in ["entry-a", "entry-b"] {
        assert!(
            engine
                .actions()
                .iter()
                .all(|a| !(a.node == node(entry)
                    && matches!(a.kind, ActionKind::AddListener { .. }))),
            "{entry} 不应创建监听"
        );
    }
}

#[test]
fn hop_order_follows_topology_order_not_node_names() {
    let plan = multi_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    // aleph 顺序会排成 aa-relay, zz-relay；这里必须保持拓扑里的 zz-relay 在前。
    let entry_chain = chain_id(&engine, "entry-a");
    let names: Vec<String> = chain_next(&engine, entry_chain)
        .into_iter()
        .map(|(name, _)| name)
        .collect();
    assert_eq!(names, vec!["zz-relay".to_string(), "aa-relay".to_string()]);

    // 同一跳内候选按拓扑顺序生成动作，不按名字重排。
    let zz = chain_id(&engine, "zz-relay");
    let aa = chain_id(&engine, "aa-relay");
    assert!(
        zz.0 < aa.0,
        "zz-relay 应在 aa-relay 之前生成：{zz:?} vs {aa:?}"
    );
}

#[test]
fn each_relay_and_exit_uses_its_own_listener_port() {
    let plan = multi_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    for (name, port) in [
        ("zz-relay", 30011u16),
        ("aa-relay", 30012),
        ("mid-relay", 30021),
        ("exit-x", 30031),
        ("exit-y", 30032),
    ] {
        let (actual, _) = listener_meta(&engine, listener_id(&engine, name));
        assert_eq!(actual, port, "{name} 必须使用自己的能力分配端口");
    }

    // 没有全局 relay_port：两个不同节点使用同一个端口号也是合法的。
    let mut plan = base_plan();
    plan.new.hops = vec![vec![cand("relay-b", 30001), cand("relay-c", 30001)]];
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    assert_eq!(
        listener_meta(&engine, listener_id(&engine, "relay-b")).0,
        30001
    );
    assert_eq!(
        listener_meta(&engine, listener_id(&engine, "relay-c")).0,
        30001
    );
}

#[test]
fn service_names_share_one_chain_and_keep_per_entry_ports() {
    let plan = multi_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");

    let tcp = service_id(&engine, "1_7_0_tcp");
    let udp = service_id(&engine, "1_7_0_udp");
    let (name, port, proto, chain) = service_meta(&engine, tcp);
    assert_eq!(name, "1_7_0_tcp");
    assert_eq!(port, 20001);
    assert_eq!(proto, Proto::Tcp);
    assert_eq!(chain, "chains_tun9");
    let (name, port, proto, chain) = service_meta(&engine, udp);
    assert_eq!(name, "1_7_0_udp");
    assert_eq!(port, 20001, "同一规则 TCP/UDP 共用入口端口");
    assert_eq!(proto, Proto::Udp);
    assert_eq!(chain, "chains_tun9");

    let entry_b_tcp = service_id(&engine, "1_7_0_tcp");
    assert_eq!(node_of(&engine, entry_b_tcp), node("entry-a"));

    // 每条服务只依赖自己入口节点的链。
    let entry_a_chain = chain_id(&engine, "entry-a");
    assert_eq!(
        engine.action(tcp).expect("action").depends_on,
        vec![entry_a_chain]
    );
    assert_eq!(
        engine.action(udp).expect("action").depends_on,
        vec![entry_a_chain]
    );

    // 两个入口、两条 TCP 服务、各自端口不同（同名服务在不同节点上是不同资源）。
    let tcp_services: Vec<(NodeId, u16)> = engine
        .actions()
        .iter()
        .filter_map(|action| match &action.kind {
            ActionKind::AddService {
                service,
                port,
                proto,
                ..
            } if service == "1_7_0_tcp" && *proto == Proto::Tcp => {
                Some((action.node.clone(), *port))
            }
            _ => None,
        })
        .collect();
    assert_eq!(
        tcp_services,
        vec![(node("entry-a"), 20001), (node("entry-b"), 21001)],
        "不同入口允许不同端口"
    );
    assert_eq!(tcp_services.len(), 2);
}

// ---- 3. 能力缺失 fail closed -------------------------------------------

#[test]
fn missing_capabilities_block_instead_of_passing_silently() {
    let plan = base_plan();
    let caps = legacy_caps(&plan);
    let mut engine = Engine::new(plan, caps).expect("valid plan");
    engine.advance_to(Phase::Preparing).expect("preparing");

    assert!(!engine.supports(&node("entry-a"), Capability::ProbePort));
    assert!(!engine.supports(&node("entry-a"), Capability::QueryServiceState));

    let preparing = engine.actions_in(Phase::Preparing);
    assert_eq!(preparing.len(), 4, "两个 (节点,端口) 各预留 + 探测");
    for action in preparing {
        match &action.status {
            ActionStatus::Blocked { capability } => {
                assert!(
                    matches!(
                        capability,
                        Capability::AtomicPortReserve | Capability::ProbePort
                    ),
                    "unexpected capability {capability:?}"
                );
            }
            other => panic!("legacy 节点必须 fail closed，实际 {}", other.label()),
        }
    }
    assert_eq!(engine.gaps().len(), 4);

    let blocked = engine.settle_report(Phase::Preparing);
    assert_eq!(blocked.blocked.len(), 4);
    assert!(blocked.pending.is_empty() && blocked.failed.is_empty());

    match engine.advance_to(Phase::Provisioning) {
        Err(EngineError::CapabilityBlocked { phase, gaps }) => {
            assert_eq!(phase, Phase::Preparing);
            assert_eq!(gaps.len(), 4);
        }
        other => panic!("阶段不得带着能力缺口推进：{other:?}"),
    }
    assert_eq!(engine.phase(), Phase::Preparing);

    let id = preparing_id(&engine);
    assert!(matches!(
        engine.dispatch(id),
        Err(EngineError::IllegalActionState { .. })
    ));
    let op = engine.plan().op_id.clone();
    let rejected = engine
        .apply_receipt(&Receipt::success(op, id, node_of(&engine, id), 0))
        .unwrap_err();
    assert!(
        matches!(rejected, ReceiptRejection::ActionBlocked { .. }),
        "{rejected:?}"
    );
}

fn preparing_id(engine: &Engine) -> ActionId {
    engine.actions_in(Phase::Preparing)[0].id
}

// ---- 4. 真实确认 / 依赖屏障 --------------------------------------------

#[test]
fn verified_evidence_requires_declared_query_capability() {
    let plan = base_plan();
    let mut engine = new_engine(plan, false);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");

    let id = service_id(&engine, "1_7_0_tcp");
    engine.dispatch(id).expect("dispatch");
    let op = engine.plan().op_id.clone();
    let attempt = attempt_of(&engine, id);
    let rejected = engine
        .apply_receipt(&Receipt::verified(
            op.clone(),
            id,
            node_of(&engine, id),
            attempt,
        ))
        .unwrap_err();
    match rejected {
        ReceiptRejection::EvidenceUnproven { capability, .. } => {
            assert_eq!(capability, Capability::QueryServiceState);
        }
        other => panic!("未声明 queryServiceState 的节点不能提供状态证据：{other:?}"),
    }
    assert_eq!(
        attempt_of(&engine, id),
        attempt,
        "拒绝的回执不得改变动作状态"
    );
    assert!(matches!(
        engine.action(id).expect("action").status,
        ActionStatus::Dispatched { .. }
    ));

    confirm(&mut engine, id);
    assert!(engine.action(id).expect("action").status.is_confirmed());
    assert!(
        !engine.action(id).expect("action").status.is_verified(),
        "命令被接受不等于状态被证实"
    );
}

#[test]
fn publishes_in_beta_order_without_reverse_barrier() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    // beta 第一步就是入口链（TunnelServiceImpl.java:268-277），不等待下游监听：
    // createChains 只把下一跳地址写进本节点配置，下游此刻还没起监听是正常的。
    let entry_chain = chain_id(&engine, "entry-a");
    engine
        .dispatch(entry_chain)
        .expect("入口链必须允许先发，不能人为造反向屏障");
    confirm(&mut engine, entry_chain);

    // 中间跳的链同样只写下一跳地址，不依赖下游。
    let b_chain = chain_id(&engine, "relay-b");
    let b_listener = listener_id(&engine, "relay-b");
    engine.dispatch(b_chain).expect("中间跳链不依赖下游监听");

    // 唯一保留的动作级依赖：同一节点先建链再建服务（publishChains 的 createChains 在前）。
    match engine.dispatch(b_listener) {
        Err(EngineError::DependencyUnsettled { action, blocking }) => {
            assert_eq!(action, b_listener);
            assert_eq!(blocking, vec![b_chain], "监听只能被本节点链阻塞");
        }
        other => panic!("链未确认却允许下发同节点监听：{other:?}"),
    }
    confirm(&mut engine, b_chain);
    engine
        .dispatch(b_listener)
        .expect("链确认后同节点监听可下发");
    confirm(&mut engine, b_listener);

    let c_chain = chain_id(&engine, "relay-c");
    let c_listener = listener_id(&engine, "relay-c");
    engine.dispatch(c_chain).expect("第二跳链下发");
    confirm(&mut engine, c_chain);
    engine.dispatch(c_listener).expect("第二跳监听下发");
    confirm(&mut engine, c_listener);

    // 出口监听在 beta 是最后一步发出（:298-304），但语义上不依赖上游确认，原型不加假依赖。
    let exit_listener = listener_id(&engine, "exit-d");
    engine
        .dispatch(exit_listener)
        .expect("出口监听不依赖上游确认");
    confirm(&mut engine, exit_listener);

    // 动作生成顺序逐项对齐 beta：入口链 → 每跳 链/监听 → 出口监听。
    let order: Vec<ActionId> = engine
        .actions_in(Phase::Provisioning)
        .iter()
        .map(|action| action.id)
        .collect();
    assert_eq!(
        order,
        vec![
            entry_chain,
            b_chain,
            b_listener,
            c_chain,
            c_listener,
            exit_listener
        ],
        "生成顺序必须与 publishChains 的入口→中间跳→出口一致"
    );

    for action in engine.actions_in(Phase::Provisioning) {
        assert!(action.status.is_confirmed(), "Provisioning 未全部确认");
    }
    // 中继监听的端口来自各自候选，而不是某个全局 relay 端口。
    assert_eq!(
        listener_meta(&engine, listener_id(&engine, "relay-b")).0,
        30001
    );
    assert_eq!(
        listener_meta(&engine, listener_id(&engine, "relay-c")).0,
        30002
    );
    assert_eq!(
        listener_meta(&engine, listener_id(&engine, "exit-d")).0,
        30003
    );
}

// ---- 5. exists 不等于归属 ----------------------------------------------

#[test]
fn exists_reply_is_a_conflict_not_ownership() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    let exit_listener = listener_id(&engine, "exit-d");
    engine.dispatch(exit_listener).expect("dispatch");
    let op = engine.plan().op_id.clone();
    let attempt = attempt_of(&engine, exit_listener);
    let decision = engine
        .apply_receipt(&Receipt::exists(op, exit_listener, node("exit-d"), attempt))
        .expect("exists is a valid reply");
    assert!(matches!(decision, ReceiptDecision::Applied { .. }));

    let status = engine.action(exit_listener).expect("action").status.clone();
    assert!(
        matches!(status, ActionStatus::Conflict { .. }),
        "{status:?}"
    );

    let record = engine
        .resources()
        .iter()
        .find(|record| record.key.name == "tun9_tls" && record.key.node == node("exit-d"))
        .expect("冲突必须被记录");
    assert!(!record.provision_confirmed);
    assert!(record.conflicted);
    assert_eq!(
        engine.settle_report(Phase::Provisioning).conflicted,
        vec![exit_listener]
    );
    assert_eq!(
        engine.phase(),
        Phase::RollbackPending,
        "冲突必须进入回滚，不得继续推进"
    );

    let report = engine.compensate().expect("compensation after conflict");
    assert!(
        report
            .blocked
            .iter()
            .any(|blocked| blocked.resource.name == "tun9_tls"
                && blocked.resource.node == node("exit-d")),
        "冲突资源不可补偿删除：{report:?}"
    );
    assert!(!report.complete);
    assert!(
        engine
            .actions_in(Phase::RollbackPending)
            .iter()
            .all(
                |action| action.resource.as_ref().map(|key| key.node.clone())
                    != Some(node("exit-d"))
            ),
        "exists 的资源不属于本操作，禁止生成删除动作"
    );
}

// ---- 6. 超时 / 迟到结果 ------------------------------------------------

#[test]
fn two_timeouts_then_late_failure_and_success_still_roll_back() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");

    let first = service_id(&engine, "1_7_0_tcp");
    let second = service_id(&engine, "2_7_11_tcp");
    engine.dispatch(first).expect("dispatch");
    engine.dispatch(second).expect("dispatch");
    engine.on_timeout(first, "no reply").expect("timeout");
    engine.on_timeout(second, "no reply").expect("timeout");

    assert_eq!(engine.phase(), Phase::TimeoutUnconfirmed);
    assert_eq!(engine.resume_phase(), Some(Phase::Cutover));
    assert!(matches!(
        engine.compensate(),
        Err(EngineError::UnconfirmedOutcome { .. })
    ));
    assert!(engine
        .unknown_results()
        .resources
        .contains(&ResourceKey::new(node("entry-a"), "1_7_0_tcp".to_string())));

    // 第一个超时动作迟到成功：仍未决，因为第二个还没有结果。
    let op = engine.plan().op_id.clone();
    let attempt = attempt_of(&engine, first);
    let decision = engine
        .apply_receipt(&Receipt::success(
            op.clone(),
            first,
            node("entry-a"),
            attempt,
        ))
        .expect("late success");
    match decision {
        ReceiptDecision::Applied {
            late_after_timeout,
            phase,
            ..
        } => {
            assert!(late_after_timeout);
            assert_eq!(phase, Phase::TimeoutUnconfirmed);
        }
        other => panic!("{other:?}"),
    }
    assert_eq!(engine.unknown_results().actions, vec![second]);

    // 第二个迟到失败：必须可靠进入回滚。
    let attempt = attempt_of(&engine, second);
    engine
        .apply_receipt(&Receipt::failure(
            op,
            second,
            node("entry-a"),
            attempt,
            "listen denied",
        ))
        .expect("late failure");
    assert_eq!(engine.phase(), Phase::RollbackPending);
    assert!(engine.resume_phase().is_none());

    let report = engine.compensate().expect("compensation");
    assert_eq!(report.phase, Phase::RollbackPending);
    assert_eq!(
        report.pending.len(),
        5,
        "3 监听 + 2 链（入口链与入口服务在旧拓扑里同名，回滚不得删除）"
    );
    let deletes = engine.actions_in(Phase::RollbackPending);
    assert!(
        !deletes
            .iter()
            .any(|action| matches!(&action.kind, ActionKind::DeleteService { .. })),
        "入口服务在旧拓扑里先存在，回滚不能删：{:?}",
        deletes
            .iter()
            .map(|action| action.kind.clone())
            .collect::<Vec<_>>()
    );
    let preserved: Vec<ResourceKey> = report
        .preserved
        .iter()
        .map(|entry| entry.resource.clone())
        .collect();
    for name in ["chains_tun9", "1_7_0_tcp"] {
        let key = ResourceKey::new(node("entry-a"), name.to_string());
        assert!(
            preserved.contains(&key),
            "{key} 必须被报告为保留：{report:?}"
        );
    }
    // entry-a 上的三个旧同名键（链 + 两条服务）一个都不许进删除清单：
    // 没下发过的 1_7_0_udp 连台账都没有，也不等于"可以删"。
    for name in ["chains_tun9", "1_7_0_udp"] {
        let key = ResourceKey::new(node("entry-a"), name.to_string());
        assert!(
            !deletes
                .iter()
                .any(|action| action.resource.as_ref() == Some(&key)),
            "旧拓扑同键资源不能被回滚删除：{key}"
        );
    }
    assert!(
        report
            .not_created
            .contains(&ResourceKey::new(node("entry-a"), "2_7_11_tcp".to_string())),
        "失败的动作不算已创建：{report:?}"
    );
}

#[test]
fn late_success_alone_keeps_the_operation_pending() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");

    let first = service_id(&engine, "1_7_0_tcp");
    let second = service_id(&engine, "1_7_0_udp");
    engine.dispatch(first).expect("dispatch");
    engine.dispatch(second).expect("dispatch");
    engine.on_timeout(first, "no reply").expect("timeout");
    engine.on_timeout(second, "no reply").expect("timeout");

    let op = engine.plan().op_id.clone();
    let attempt = attempt_of(&engine, first);
    engine
        .apply_receipt(&Receipt::success(op, first, node("entry-a"), attempt))
        .expect("late success");

    assert_eq!(
        engine.phase(),
        Phase::TimeoutUnconfirmed,
        "还有未知结果，不得自行恢复"
    );
    assert_eq!(engine.resume_phase(), Some(Phase::Cutover));
    assert_eq!(engine.unknown_results().actions, vec![second]);
    assert!(engine
        .unknown_results()
        .resources
        .contains(&ResourceKey::new(node("entry-a"), "1_7_0_udp".to_string())));
}

// ---- 7. 回滚顺序与补偿范围 --------------------------------------------

#[test]
fn rollback_deletes_services_then_listeners_then_chains() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, false);

    let report = engine.compensate().expect("compensation");
    assert_eq!(
        report.pending.len(),
        5,
        "3 监听 + 2 链：入口链与入口服务在旧拓扑里同名，回滚不得删除"
    );
    assert!(report.started.is_empty());
    let chain_delete = engine
        .actions_in(Phase::RollbackPending)
        .iter()
        .find(|action| matches!(action.kind, ActionKind::DeleteChains { .. }))
        .map(|action| action.id)
        .expect("chain delete");
    assert_eq!(
        dep_count(&engine, chain_delete),
        1,
        "链只等同节点上的监听删完（依赖按节点裁剪，不再全局连边）"
    );

    match engine.dispatch(chain_delete) {
        Err(EngineError::DependencyUnsettled { action, blocking }) => {
            assert_eq!(action, chain_delete);
            assert_eq!(blocking.len(), 1);
        }
        other => panic!("监听未删就下发链删除：{other:?}"),
    }

    // 按层次下发：先监听(2)，再链(1)。
    let mut waves = Vec::new();
    loop {
        let ids = engine
            .dispatch_all(Phase::RollbackPending)
            .expect("rollback wave");
        if ids.is_empty() {
            break;
        }
        waves.push(ids.len());
        for id in ids {
            confirm(&mut engine, id);
        }
    }
    assert_eq!(waves, vec![3, 2]);
    assert!(engine.compensation_status().complete);
    assert_eq!(
        engine.phase(),
        Phase::Degraded,
        "回滚完成是 DEGRADED，不是 COMPLETED"
    );
}

#[test]
fn rollback_only_compensates_confirmed_resources() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");

    let ok = service_id(&engine, "1_7_0_tcp");
    let bad = service_id(&engine, "2_7_11_tcp");
    engine.dispatch(ok).expect("dispatch");
    engine.dispatch(bad).expect("dispatch");
    confirm(&mut engine, ok);
    let op = engine.plan().op_id.clone();
    let attempt = attempt_of(&engine, bad);
    engine
        .apply_receipt(&Receipt::failure(
            op,
            bad,
            node("entry-a"),
            attempt,
            "port busy",
        ))
        .expect("failure receipt");

    assert_eq!(engine.phase(), Phase::RollbackPending);
    let report = engine.compensate().expect("compensation");
    let deleted: Vec<&ResourceKey> = engine
        .actions_in(Phase::RollbackPending)
        .iter()
        .filter_map(|action| action.resource.as_ref())
        .collect();
    // 入口服务在旧拓扑里先存在：即使本操作"确认"了它，也不构成删除授权。
    let confirmed_key = ResourceKey::new(node("entry-a"), "1_7_0_tcp".to_string());
    assert!(
        !deleted.contains(&&confirmed_key),
        "回滚删除了旧拓扑先存在的资源：{deleted:?}"
    );
    assert!(report
        .preserved
        .iter()
        .any(|entry| entry.resource == confirmed_key));
    assert!(!deleted.contains(&&ResourceKey::new(
        node("entry-a"),
        "2_7_11_tcp".to_string()
    )));
    assert!(report
        .not_created
        .contains(&ResourceKey::new(node("entry-a"), "2_7_11_tcp".to_string())));
    assert_eq!(
        report.pending.len(),
        5,
        "3 监听 + 2 链：入口链与入口两条服务在旧拓扑里同名，全部保留"
    );
    assert!(
        !engine
            .actions_in(Phase::RollbackPending)
            .iter()
            .any(|action| matches!(&action.kind, ActionKind::DeleteService { .. })),
        "本计划没有任何可删的服务：入口服务在旧拓扑里先存在"
    );
}

// ---- 8. 清理旧拓扑 -----------------------------------------------------

#[test]
fn cleanup_rejects_new_topology_and_unattested_legacy() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);

    // 非 CLEANUP 阶段不接受清理请求。
    assert!(matches!(
        engine.request_cleanup(&legacy_listener_key()),
        Err(CleanupRejection::WrongPhase(Phase::Draft))
    ));

    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, true);
    engine.advance_to(Phase::Cleanup).expect("cleanup");

    let new_listener = ResourceKey::new(node("relay-b"), "tun9_tls".to_string());
    assert!(matches!(
        engine.request_cleanup(&new_listener),
        Err(CleanupRejection::ActiveInNewTopology(_))
    ));
    let unknown = ResourceKey::new(node("nobody"), "tun9_tls".to_string());
    assert!(matches!(
        engine.request_cleanup(&unknown),
        Err(CleanupRejection::NotLegacyTarget(_))
    ));
    assert!(matches!(
        engine.request_cleanup(&legacy_listener_key()),
        Err(CleanupRejection::LegacyNotAttested(_))
    ));

    // 归属证据的类型必须与旧拓扑一致。
    let wrong = ResourceKind::Chain {
        chain: "chains_tun9".to_string(),
    };
    assert!(matches!(
        engine.attest_legacy(&legacy_listener_key(), wrong, "api:tunnel", 1000),
        Err(EngineError::LegacyKindMismatch { .. })
    ));
    assert_eq!(
        engine.legacy_state(&legacy_listener_key()),
        LegacyState::Unattested
    );

    engine
        .attest_legacy(
            &legacy_listener_key(),
            legacy_listener_kind(),
            "api:tunnel",
            1000,
        )
        .expect("attest");
    assert!(matches!(
        engine.legacy_state(&legacy_listener_key()),
        LegacyState::Attested { .. }
    ));

    let action = engine
        .request_cleanup(&legacy_listener_key())
        .expect("cleanup action");
    assert_eq!(
        action,
        engine
            .request_cleanup(&legacy_listener_key())
            .expect("idempotent")
    );
    engine.dispatch(action).expect("dispatch");
    confirm(&mut engine, action);
    assert_eq!(
        engine.legacy_state(&legacy_listener_key()),
        LegacyState::Released
    );
    assert!(matches!(
        engine.request_cleanup(&legacy_listener_key()),
        Err(CleanupRejection::AlreadyReleased(_))
    ));
}

#[test]
fn legacy_shared_with_new_topology_is_never_cleaned() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, true);
    engine.advance_to(Phase::Cleanup).expect("cleanup");

    // 旧拓扑与新拓扑共用的链 / 服务：只按名字匹配就删除会打掉新拓扑。
    let shared_chain = ResourceKey::new(node("entry-a"), "chains_tun9".to_string());
    assert_eq!(
        engine.legacy_state(&shared_chain),
        LegacyState::SharedWithNewTopology
    );
    assert!(matches!(
        engine.request_cleanup(&shared_chain),
        Err(CleanupRejection::ActiveInNewTopology(_))
    ));
    for service in ["1_7_0_tcp", "1_7_0_udp", "2_7_11_tcp"] {
        let key = ResourceKey::new(node("entry-a"), service.to_string());
        assert_eq!(
            engine.legacy_state(&key),
            LegacyState::SharedWithNewTopology,
            "{service}"
        );
        assert!(matches!(
            engine.request_cleanup(&key),
            Err(CleanupRejection::ActiveInNewTopology(_))
        ));
    }

    engine
        .attest_legacy(
            &legacy_listener_key(),
            legacy_listener_kind(),
            "api:tunnel",
            1000,
        )
        .expect("attest");
    let action = engine
        .request_cleanup(&legacy_listener_key())
        .expect("cleanup");
    engine.dispatch(action).expect("dispatch");
    confirm(&mut engine, action);

    engine.advance_to(Phase::Completed).expect("completed");
    assert_eq!(engine.phase(), Phase::Completed);
    assert!(
        engine
            .resources()
            .iter()
            .filter(|record| record.key.node == node("entry-a"))
            .all(|record| !record.released),
        "新拓扑仍在使用的资源不得被清理"
    );
}

#[test]
fn unproven_legacy_stops_in_manual_review_instead_of_completed() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, true);
    engine.advance_to(Phase::Cleanup).expect("cleanup");

    // 出口节点旧监听没有归属证据：不得按名字直接删，也不得宣布完成。
    match engine.advance_to(Phase::Completed) {
        Err(EngineError::LegacyUnresolved {
            unattested,
            not_cleaned,
        }) => {
            assert_eq!(unattested, vec![legacy_listener_key()]);
            assert!(not_cleaned.is_empty());
        }
        other => panic!("缺少旧资源归属证明时不得完成：{other:?}"),
    }
    assert_eq!(engine.phase(), Phase::Cleanup);
    assert!(engine
        .legacy_targets()
        .iter()
        .any(|target| target.key == legacy_listener_key()
            && matches!(target.state, LegacyState::Unattested)));

    engine.stop_for_review().expect("stop for review");
    assert_eq!(engine.phase(), Phase::ManualReview);
    assert_ne!(engine.phase(), Phase::Completed);
    assert!(matches!(
        engine.advance_to(Phase::Completed),
        Err(EngineError::IllegalTransition { .. })
    ));
}

// ---- 9. 回执时序 ------------------------------------------------------

#[test]
fn receipt_validation_checks_identity_and_attempt_not_node_clock() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.set_clock(1_000).expect("clock");
    engine.advance_to(Phase::Preparing).expect("preparing");

    let pending = engine.actions_in(Phase::Preparing)[0].id;
    let op = engine.plan().op_id.clone();
    // 没下发就来的回执。
    assert!(matches!(
        engine.apply_receipt(&Receipt::success(op.clone(), pending, node("entry-a"), 0)),
        Err(ReceiptRejection::NotDispatched(_))
    ));

    engine.dispatch(pending).expect("dispatch");
    let attempt = attempt_of(&engine, pending);
    assert!(matches!(
        engine.apply_receipt(&Receipt::success(
            OperationId::new("op-other"),
            pending,
            node("entry-a"),
            attempt
        )),
        Err(ReceiptRejection::ForeignOperation { .. })
    ));
    assert!(matches!(
        engine.apply_receipt(&Receipt::success(
            op.clone(),
            ActionId(9999),
            node("entry-a"),
            attempt
        )),
        Err(ReceiptRejection::UnknownAction(_))
    ));
    assert!(matches!(
        engine.apply_receipt(&Receipt::success(
            op.clone(),
            pending,
            node("relay-b"),
            attempt
        )),
        Err(ReceiptRejection::NodeMismatch { .. })
    ));
    assert!(matches!(
        engine.apply_receipt(&Receipt::success(
            op.clone(),
            pending,
            node("entry-a"),
            attempt + 1
        )),
        Err(ReceiptRejection::AttemptMismatch { .. })
    ));
    // 节点时钟比本机下发时间早很多：跨机器时钟不可比，只记录审计，不否决真实回执。
    assert!(matches!(
        engine.apply_receipt(
            &Receipt::success(op.clone(), pending, node("entry-a"), attempt).at(500)
        ),
        Ok(ReceiptDecision::Applied { .. })
    ));
    assert_eq!(engine.applied()[0].node_time_ms, 500);
    // 本机时钟回拨必须被拒绝：只约束本机单调时间戳。
    assert!(matches!(
        engine.set_clock(900),
        Err(EngineError::ClockRegression { .. })
    ));

    // 同一 (动作, 尝试, 结果) 重放是幂等重复，不是新状态。
    assert!(matches!(
        engine.apply_receipt(
            &Receipt::success(op.clone(), pending, node("entry-a"), attempt).at(1_500)
        ),
        Ok(ReceiptDecision::Duplicate { .. })
    ));
    // 已确认的动作不能改判。
    assert!(matches!(
        engine.apply_receipt(&Receipt::failure(
            op,
            pending,
            node("entry-a"),
            attempt,
            "late"
        )),
        Err(ReceiptRejection::ConflictingOutcome { .. })
    ));
}

// ---- 10. 快照 ---------------------------------------------------------

#[test]
fn snapshot_roundtrip_preserves_state_and_rejects_tampering() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.set_clock(2_000).expect("clock");
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    let first_wave = engine.dispatch_all(Phase::Provisioning).expect("wave");
    for id in first_wave {
        confirm(&mut engine, id);
    }

    let snapshot = engine.snapshot();
    let mut restored = Engine::restore(&snapshot).expect("restore");
    assert_eq!(restored.phase(), Phase::Provisioning);
    assert_eq!(restored.clock_ms(), 2_000);
    assert_eq!(restored.actions().len(), engine.actions().len());
    assert_eq!(restored.snapshot(), snapshot);

    // 恢复后可以继续推进到 COMPLETED。
    drive(&mut restored, Phase::Provisioning, false);
    restored.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut restored, Phase::Cutover, true);
    restored.advance_to(Phase::Cleanup).expect("cleanup");
    restored
        .attest_legacy(
            &legacy_listener_key(),
            legacy_listener_kind(),
            "api:tunnel",
            3_000,
        )
        .expect("attest");
    let action = restored
        .request_cleanup(&legacy_listener_key())
        .expect("cleanup");
    restored.dispatch(action).expect("dispatch");
    confirm(&mut restored, action);
    restored.advance_to(Phase::Completed).expect("completed");
    assert_eq!(restored.phase(), Phase::Completed);

    let mut value: serde_json::Value = serde_json::from_str(&snapshot).expect("json");
    value["next_action_id"] = serde_json::json!(1);
    let tampered = serde_json::to_string(&value).expect("json");
    assert!(matches!(
        Engine::restore(&tampered),
        Err(EngineError::Snapshot(_))
    ));

    let mut value: serde_json::Value = serde_json::from_str(&snapshot).expect("json");
    value["schema"] = serde_json::json!(1);
    let tampered = serde_json::to_string(&value).expect("json");
    assert!(matches!(
        Engine::restore(&tampered),
        Err(EngineError::Snapshot(_))
    ));

    let mut value: serde_json::Value = serde_json::from_str(&snapshot).expect("json");
    let actions = value["actions"].as_array_mut().expect("actions");
    actions[0]["depends_on"] = serde_json::json!([9999]);
    let tampered = serde_json::to_string(&value).expect("json");
    assert!(matches!(
        Engine::restore(&tampered),
        Err(EngineError::Snapshot(_))
    ));
}

// ---- 11. 快乐路径 -----------------------------------------------------

#[test]
fn happy_path_reaches_completed_with_verified_cutover() {
    let plan = multi_plan();
    let mut engine = new_engine(plan, true);

    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, true);
    engine.advance_to(Phase::Cleanup).expect("cleanup");

    for target in engine.legacy_targets() {
        if let LegacyState::Unattested = target.state {
            engine
                .attest_legacy(&target.key, target.kind.clone(), "api:tunnel", 5_000)
                .expect("attest legacy");
        }
    }
    let targets: Vec<ResourceKey> = engine
        .legacy_targets()
        .into_iter()
        .filter(|target| matches!(target.state, LegacyState::Attested { .. }))
        .map(|target| target.key)
        .collect();
    assert!(!targets.is_empty(), "旧拓扑应有需要清理的资源");
    // 先按任意顺序登记清理请求，再按 DAG 波次下发：请求顺序不得决定删除顺序。
    for key in targets {
        engine.request_cleanup(&key).expect("cleanup action");
    }
    let mut waves = Vec::new();
    loop {
        let ids = engine.dispatch_all(Phase::Cleanup).expect("cleanup wave");
        if ids.is_empty() {
            break;
        }
        waves.push(ids.len());
        for id in ids {
            confirm(&mut engine, id);
        }
    }
    assert_eq!(waves, vec![2, 1], "监听先删，链最后删");

    engine.advance_to(Phase::Completed).expect("completed");
    assert_eq!(engine.phase(), Phase::Completed);
    assert!(engine.compensate().is_err(), "COMPLETED 之后不得再补偿");
    // 终态不再接受任何新的下发。
    assert!(matches!(
        engine.dispatch(ActionId(1)),
        Err(EngineError::PhaseFrozen { .. })
    ));
    // 批量下发是排空原语：终态下没有任何就绪动作，返回空而不是制造失败。
    assert!(engine
        .dispatch_all(Phase::Cleanup)
        .expect("frozen drain")
        .is_empty());

    for action in engine.actions() {
        assert!(
            !matches!(
                action.status,
                ActionStatus::Failed { .. } | ActionStatus::Conflict { .. }
            ),
            "{} 不应失败：{}",
            action.kind.label(),
            action.status.label()
        );
    }
    // 每条转发的 TCP/UDP 服务都带 userId / userTunnelId 且共用一条隧道级链。
    for forward in &engine.plan().forwards {
        for proto in &forward.protocols {
            let name = engine.plan().service_name(forward, *proto);
            let id = service_id(&engine, &name);
            let (_, _, _, chain) = service_meta(&engine, id);
            assert_eq!(chain, engine.plan().chain_name());
            assert!(name.contains(&format!("{}_{}_", forward.forward_id.0, forward.user_id)));
        }
    }
}

// ---- 21. 旧资源清理 DAG：顺序由层次决定，而不是由请求先后决定 ----------

#[test]
fn cleanup_dag_follows_layers_not_request_order() {
    // multi_plan 的旧拓扑多一层中继，才能验证"链 -> 监听"的真实层次依赖。
    let plan = multi_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, true);
    engine.advance_to(Phase::Cleanup).expect("cleanup");

    for target in engine.legacy_targets() {
        if matches!(target.state, LegacyState::Unattested) {
            engine
                .attest_legacy(&target.key, target.kind.clone(), "api:tunnel", 1_000)
                .expect("attest");
        }
    }

    // 先请求拆链（第 1 层）：此时上一层的监听删除动作还不存在。
    let chain_key = ResourceKey::new(node("old-mid"), "chains_tun9".to_string());
    let chain_action = engine.request_cleanup(&chain_key).expect("chain cleanup");
    assert!(
        matches!(
            engine.dispatch(chain_action),
            Err(EngineError::DependencyUnsettled { .. })
        ),
        "链删除不能抢在监听删除之前下发"
    );
    // 依赖必须指向真实存在的上一层删除动作，而不是空依赖。
    assert_eq!(dep_count(&engine, chain_action), 1, "链删除必须等监听删除");
    let listener_action = engine
        .actions_in(Phase::Cleanup)
        .iter()
        .find(|action| {
            matches!(action.kind, ActionKind::DeleteListener { .. })
                && action.node == node("old-mid")
        })
        .map(|action| action.id)
        .expect("监听删除动作应被自动补齐");
    assert_eq!(
        engine.action(chain_action).expect("action").depends_on,
        vec![listener_action]
    );

    // 另一个监听与链无关，可以随时下发：层次决定顺序，而不是"先到先删"。
    let exit_key = ResourceKey::new(node("old-exit"), "tun9_tls".to_string());
    let exit_action = engine.request_cleanup(&exit_key).expect("exit cleanup");
    assert_eq!(dep_count(&engine, exit_action), 0);

    let mut waves = Vec::new();
    loop {
        let ids = engine.dispatch_all(Phase::Cleanup).expect("cleanup wave");
        if ids.is_empty() {
            break;
        }
        waves.push(ids.len());
        for id in ids {
            confirm(&mut engine, id);
        }
    }
    assert_eq!(waves, vec![2, 1], "两个监听先删，链最后删");
    assert_eq!(engine.actions_in(Phase::Cleanup).len(), 3);
    engine.advance_to(Phase::Completed).expect("completed");
}

// ---- 22. 资源身份 = (node, name)；端口/协议是属性，同名不同端口是冲突 --

#[test]
fn same_name_different_port_is_a_conflict_not_a_coexisting_resource() {
    let mut plan = base_plan();
    // 同名监听换了端口；同名服务也换了端口。
    plan.new.hops = vec![vec![cand("relay-b", 30001)]];
    plan.new.exits = vec![cand("old-exit", 30010)];
    plan.forwards[1].old_entry_ports = vec![EntryPort::new(node("entry-a"), 20012)];

    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, true);
    engine.advance_to(Phase::Cleanup).expect("cleanup");

    // 属性完全一致才算共用；改名之外的情况一律算同名冲突。
    let shared_service = ResourceKey::new(node("entry-a"), "1_7_0_tcp".to_string());
    assert_eq!(
        engine.legacy_state(&shared_service),
        LegacyState::SharedWithNewTopology
    );
    let moved_service = ResourceKey::new(node("entry-a"), "2_7_11_tcp".to_string());
    match engine.legacy_state(&moved_service) {
        LegacyState::NameConflict { new_kind } => assert_eq!(
            new_kind,
            ResourceKind::Service {
                forward: ForwardId(2),
                port: 20002,
                proto: Proto::Tcp,
            }
        ),
        other => panic!("服务同名不同端口必须算冲突，实际 {other:?}"),
    }
    assert!(matches!(
        engine.legacy_state(&legacy_listener_key()),
        LegacyState::NameConflict { .. }
    ));
    // 冲突资源既不能当共用资源留着，也不能直接删。
    assert!(matches!(
        engine.request_cleanup(&moved_service),
        Err(CleanupRejection::NameConflictInNewTopology(_))
    ));
    assert!(matches!(
        engine.request_cleanup(&legacy_listener_key()),
        Err(CleanupRejection::NameConflictInNewTopology(_))
    ));

    match engine.advance_to(Phase::Completed) {
        Err(EngineError::LegacyNameConflict { keys }) => {
            assert_eq!(keys, vec![moved_service, legacy_listener_key()]);
        }
        other => panic!("同名冲突必须挡住收尾，实际 {other:?}"),
    }
    assert_eq!(engine.phase(), Phase::Cleanup, "冲突未收口不得前进");
}

// ---- 23. 结论未收口 / 终态不接受新的下发 --------------------------------

#[test]
fn unconfirmed_and_terminal_phases_refuse_new_dispatch() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");

    let ids = engine.dispatch_all(Phase::Cutover).expect("cutover wave");
    assert_eq!(ids.len(), 3);
    engine
        .on_timeout(ids[0], "网络分区，结果未知")
        .expect("timeout");
    assert_eq!(engine.phase(), Phase::TimeoutUnconfirmed);

    // 结果未知期间：不得再下发新动作。
    assert!(matches!(
        engine.dispatch(ids[1]),
        Err(EngineError::PhaseFrozen { .. })
    ));
    assert!(engine
        .dispatch_all(Phase::Cutover)
        .expect("frozen drain")
        .is_empty());

    // 但同一阶段里其它在飞动作各自超时是事实，必须能记录（否则会丢掉未知结果）。
    engine
        .on_timeout(ids[1], "同一条链上的另一段也超时")
        .expect("second in-flight timeout");
    assert_eq!(engine.phase(), Phase::TimeoutUnconfirmed);
    assert_eq!(engine.settle_report(Phase::Cutover).unconfirmed.len(), 2);

    // 终态：不得退回"结论未收口"，也不得再下发。
    engine.stop_for_review().expect("manual review");
    assert_eq!(engine.phase(), Phase::ManualReview);
    assert!(matches!(
        engine.dispatch(ids[2]),
        Err(EngineError::PhaseFrozen { .. })
    ));
    assert!(matches!(
        engine.on_timeout(ids[2], "人工复核阶段"),
        Err(EngineError::PhaseFrozen { .. })
    ));
    assert_eq!(engine.phase(), Phase::ManualReview, "终态不得被超时改写");
}

// ---- 24. 删除失败不等于「从未创建」：保留归属、可重试、不谎报已清空 ----

#[test]
fn delete_failure_keeps_ownership_and_stays_retryable() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, false);

    let report = engine.compensate().expect("compensation");
    assert_eq!(engine.phase(), Phase::RollbackPending);
    assert!(!report.pending.is_empty());

    // 入口服务全部属于旧拓扑同键资源（回滚不删），所以拿本操作新建的监听做样本。
    let key = ResourceKey::new(node("relay-b"), "tun9_tls".to_string());
    let delete = engine
        .actions_in(Phase::RollbackPending)
        .iter()
        .find(|action| action.resource.as_ref() == Some(&key))
        .map(|action| action.id)
        .expect("listener delete action");
    engine.dispatch(delete).expect("dispatch delete");
    let op = engine.plan().op_id.clone();
    let attempt = attempt_of(&engine, delete);
    let target_node = node_of(&engine, delete);
    engine
        .apply_receipt(&Receipt::failure(
            op,
            delete,
            target_node,
            attempt,
            "rm denied",
        ))
        .expect("failure receipt");

    let status = engine.compensation_status();
    assert!(status.failed.contains(&delete), "失败必须如实上报");
    assert!(
        !status.not_created.contains(&key),
        "删除失败不代表资源从未创建"
    );
    let record = engine
        .resources()
        .iter()
        .find(|record| record.key == key)
        .expect("resource record");
    assert!(record.provision_confirmed, "删除失败不改变归属");
    assert!(!record.quarantined && !record.conflicted && !record.released);
    assert!(
        engine.unknown_results().resources.is_empty(),
        "删除失败是已知失败，不是未知结果"
    );

    // 重试同一条删除，而不是再建一条。
    let retry = engine.compensate().expect("retry");
    assert!(retry.failed.is_empty());
    assert!(retry.pending.contains(&delete));
    assert_eq!(
        engine.action(delete).expect("action").status,
        ActionStatus::Pending
    );
    assert_eq!(
        engine
            .actions_in(Phase::RollbackPending)
            .iter()
            .filter(|action| action.resource.as_ref() == Some(&key))
            .count(),
        1
    );

    drive(&mut engine, Phase::RollbackPending, false);
    assert_eq!(engine.phase(), Phase::Degraded);
}

// ---- 25. 旧资源删除失败：不重复建动作，可原地重试 ----------------------

#[test]
fn failed_legacy_cleanup_is_retried_in_place() {
    let plan = base_plan();
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, true);
    engine.advance_to(Phase::Cleanup).expect("cleanup");

    engine
        .attest_legacy(
            &legacy_listener_key(),
            legacy_listener_kind(),
            "api:tunnel",
            1_000,
        )
        .expect("attest");

    let first = engine
        .request_cleanup(&legacy_listener_key())
        .expect("cleanup");
    engine.dispatch(first).expect("dispatch");
    let op = engine.plan().op_id.clone();
    let attempt = attempt_of(&engine, first);
    let target_node = node_of(&engine, first);
    engine
        .apply_receipt(&Receipt::failure(op, first, target_node, attempt, "busy"))
        .expect("failure receipt");

    assert_eq!(engine.phase(), Phase::Cleanup, "清理失败不回滚新拓扑");
    assert_eq!(engine.actions_in(Phase::Cleanup).len(), 1);

    let again = engine
        .request_cleanup(&legacy_listener_key())
        .expect("retry cleanup");
    assert_eq!(again, first, "重试必须复用同一条删除动作");
    assert_eq!(
        engine.action(first).expect("action").status,
        ActionStatus::Pending
    );
    engine.dispatch(first).expect("dispatch retry");
    confirm(&mut engine, first);
    assert_eq!(
        engine.legacy_state(&legacy_listener_key()),
        LegacyState::Released
    );
    engine.advance_to(Phase::Completed).expect("completed");
}

// ---- 26. P0：暂停中的转发切流后仍是暂停 ----------------------------------

fn paused_plan(status: ForwardStatus, pause_reason: Option<&str>) -> MigrationPlan {
    let mut plan = base_plan();
    plan.forwards[0].status = status;
    plan.forwards[0].pause_reason = pause_reason.map(str::to_string);
    plan
}

fn cutover_ready(plan: MigrationPlan) -> Engine {
    let mut engine = new_engine(plan, true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    engine
}

fn forward_of(kind: &ActionKind) -> Option<ForwardId> {
    match kind {
        ActionKind::AddService { forward, .. }
        | ActionKind::PauseService { forward, .. }
        | ActionKind::ResumeService { forward, .. } => Some(*forward),
        _ => None,
    }
}

/// 切流阶段某条转发的 (建服务, 暂停, 恢复) 动作，按 id 升序。
fn cutover_service_ids(
    engine: &Engine,
    forward: ForwardId,
) -> (Vec<ActionId>, Vec<ActionId>, Vec<ActionId>) {
    let (mut add, mut pause, mut resume) = (Vec::new(), Vec::new(), Vec::new());
    for action in engine.actions_in(Phase::Cutover) {
        if forward_of(&action.kind) != Some(forward) {
            continue;
        }
        match &action.kind {
            ActionKind::AddService { .. } => add.push(action.id),
            ActionKind::PauseService { .. } => pause.push(action.id),
            ActionKind::ResumeService { .. } => resume.push(action.id),
            _ => {}
        }
    }
    (add, pause, resume)
}

fn selector_of(engine: &Engine, id: ActionId) -> Selector {
    match &engine.action(id).expect("action").kind {
        ActionKind::AddChains { selector, .. } => selector.clone(),
        other => panic!("not a chain action: {other:?}"),
    }
}

#[test]
fn manually_paused_forward_generates_no_cutover_service() {
    let engine = cutover_ready(paused_plan(ForwardStatus::Paused, None));
    let (add, pause, resume) = cutover_service_ids(&engine, ForwardId(1));
    assert!(
        add.is_empty(),
        "beta 只补齐 status=1 的转发：手动暂停不得建服务，实际 {add:?}"
    );
    assert!(
        pause.is_empty(),
        "没建过的服务没有可暂停的对象，不得凭空补 PauseService"
    );
    assert!(resume.is_empty(), "切流阶段不得下发 ResumeService");

    // 端口仍然被占用：Preparing 对暂停转发的入口端口照旧预留 + 探测。
    let reserved: Vec<u16> = engine
        .actions_in(Phase::Preparing)
        .iter()
        .filter_map(|action| match action.kind {
            ActionKind::ReservePort { port } => Some(port),
            _ => None,
        })
        .collect();
    assert!(
        reserved.contains(&20001),
        "暂停转发的入口端口仍要预留：{reserved:?}"
    );

    // 同一计划里活跃的 2 号转发照常建服务。
    let (add2, pause2, _) = cutover_service_ids(&engine, ForwardId(2));
    assert_eq!(add2.len(), 1, "活跃转发照常建服务");
    assert!(pause2.is_empty(), "活跃转发不需要暂停");
}

/// 自动暂停（pause_reason = expiry）同样是「不得激活」：切流阶段不区分暂停原因。
#[test]
fn expiry_paused_forward_is_not_activated_at_cutover() {
    let engine = cutover_ready(paused_plan(ForwardStatus::Paused, Some("expiry")));
    let (add, pause, resume) = cutover_service_ids(&engine, ForwardId(1));
    assert!(
        add.is_empty(),
        "expiry 自动暂停的转发也不得在切流阶段被建起来：{add:?}"
    );
    assert!(pause.is_empty(), "切流阶段不凭空补暂停");
    assert!(resume.is_empty(), "切流阶段不得下发 ResumeService");
}

/// status = -1（异常）同样 fail closed：没有「活跃」证据就不许激活。
#[test]
fn error_status_forward_is_not_activated_at_cutover() {
    let engine = cutover_ready(paused_plan(ForwardStatus::Error, Some("disabled")));
    let (add, pause, resume) = cutover_service_ids(&engine, ForwardId(1));
    assert!(
        add.is_empty(),
        "status=-1 同样 fail closed：没有活跃证据就不许建服务"
    );
    assert!(pause.is_empty());
    assert!(resume.is_empty());
}

/// 活跃转发不受影响：切流里既没有 PauseService 也没有 ResumeService。
#[test]
fn active_forward_cutover_has_no_pause_or_resume() {
    let engine = cutover_ready(base_plan());
    for forward in [ForwardId(1), ForwardId(2)] {
        let (add, pause, resume) = cutover_service_ids(&engine, forward);
        assert!(!add.is_empty(), "活跃转发仍要建服务");
        assert!(pause.is_empty(), "活跃转发不需要补暂停");
        assert!(resume.is_empty());
    }
}

/// 建服务仍是切流动作：只有命令确认（没拿到状态证据）就不能算切流完成。
/// 暂停转发在切流里没有任何动作，所以此处只剩活跃转发的那条服务动作。
#[test]
fn cutover_needs_state_evidence_for_every_service() {
    let mut engine = cutover_ready(paused_plan(ForwardStatus::Paused, Some("expiry")));
    let ids: Vec<ActionId> = engine
        .actions_in(Phase::Cutover)
        .iter()
        .map(|action| action.id)
        .collect();
    assert_eq!(
        ids.len(),
        1,
        "只有活跃的 2 号转发建服务：{:?}",
        engine.actions_in(Phase::Cutover)
    );

    // 建服务按状态核验，这里只拿命令确认。
    for id in ids.clone() {
        engine.dispatch(id).expect("dispatch cutover action");
        confirm(&mut engine, id);
    }

    engine.advance_to(Phase::Cleanup).expect("cleanup");
    for target in engine.legacy_targets() {
        if let LegacyState::Unattested = target.state {
            engine
                .attest_legacy(&target.key, target.kind.clone(), "api:tunnel", 5_000)
                .expect("attest legacy");
        }
    }
    let keys: Vec<ResourceKey> = engine
        .legacy_targets()
        .into_iter()
        .filter(|target| matches!(target.state, LegacyState::Attested { .. }))
        .map(|target| target.key)
        .collect();
    assert!(!keys.is_empty(), "旧拓扑应有需要清理的资源");
    for key in keys {
        engine.request_cleanup(&key).expect("cleanup request");
    }
    drive(&mut engine, Phase::Cleanup, false);

    let error = engine.advance_to(Phase::Completed).unwrap_err();
    match error {
        EngineError::CutoverUnverified { actions, .. } => {
            assert_eq!(actions, ids, "缺证据的只能是那条建服务动作");
        }
        other => panic!("建服务未核验必须挡在 COMPLETED 之前：{other:?}"),
    }
}

// ---- 27. P1：链的 selector 必须与 GostUtil.CreateChains 一致 -------------

fn provisioning_chains(engine: &Engine) -> Vec<ActionId> {
    engine
        .actions_in(Phase::Provisioning)
        .iter()
        .filter(|action| matches!(action.kind, ActionKind::AddChains { .. }))
        .map(|action| action.id)
        .collect()
}

#[test]
fn every_chain_hop_carries_beta_selector() {
    let mut engine = new_engine(base_plan(), true);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing, false);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    let chains = provisioning_chains(&engine);
    // 入口 1 条 + 两个中继候选各 1 条。
    assert_eq!(chains.len(), 3);
    for id in chains {
        let selector = selector_of(&engine, id);
        assert_eq!(
            selector.strategy.as_deref(),
            Some("round"),
            "strategy 必须取自 chain_tunnel.strategy"
        );
        assert_eq!(selector.max_fails, 1);
        assert_eq!(
            selector.fail_timeout, 600_000_000_000,
            "failTimeout 单位是纳秒"
        );
        assert_eq!(selector, Selector::for_hop(Some("round")));
        assert_eq!(
            selector.to_json(),
            "{\"strategy\":\"round\",\"maxFails\":1,\"failTimeout\":600000000000}"
        );
    }
}

/// strategy 未配置（None 或空串）不编默认值：payload 里就不出现 strategy 这个键。
#[test]
fn empty_strategy_is_not_replaced_by_an_invented_default() {
    for strategy in [None, Some(String::new())] {
        let mut plan = base_plan();
        plan.new.hops = vec![vec![cand("relay-b", 30001)]];
        plan.new.strategy = strategy;
        let mut engine = new_engine(plan, true);
        engine.advance_to(Phase::Preparing).expect("preparing");
        drive(&mut engine, Phase::Preparing, false);
        engine
            .advance_to(Phase::Provisioning)
            .expect("provisioning");

        let chains = provisioning_chains(&engine);
        assert_eq!(chains.len(), 2, "入口 + 单跳中继");
        for id in chains {
            let selector = selector_of(&engine, id);
            assert_eq!(selector.strategy, None, "空策略不得冒充 round/fifo");
            assert_eq!(selector.max_fails, 1);
            assert_eq!(selector.fail_timeout, 600_000_000_000);
            assert_eq!(
                selector.to_json(),
                "{\"maxFails\":1,\"failTimeout\":600000000000}"
            );
        }
    }

    // 空串与 None 同义。
    assert_eq!(Selector::for_hop(Some("")), Selector::for_hop(None));
}
