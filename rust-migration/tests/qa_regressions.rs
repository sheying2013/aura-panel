//! QA 静态问题回归：这些用例在修复前的实现上必须失败。
//!
//! 每一条都对应一条被 QA 指出的真实缺陷，断言只使用"外部可观察行为"
//! （动作图、依赖、结算报告、未知结果、回执结论），不去读私有字段，
//! 这样修复方式改变时用例依然有效。
//!
//! 场景全部是纯内存状态机，不连接 GOST / 数据库，也不声称零中断。

use flux_migration::{
    ActionId, ActionKind, ActionStatus, Candidate, Capability, CleanupRejection, Engine,
    EngineError, EntryPort, ForwardId, ForwardSpec, ForwardStatus, LegacyState, MigrationPlan,
    NodeCapabilities, NodeId, OperationId, Phase, Proto, Receipt, ReceiptDecision,
    ReceiptRejection, ResourceKey, ResourceKind, Topology,
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

fn new_engine(plan: MigrationPlan, with_query: bool) -> Engine {
    let caps = caps(&plan, with_query);
    Engine::new(plan, caps).expect("plan should be valid")
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

fn drive(engine: &mut Engine, phase: Phase, verify_cutover: bool) {
    loop {
        let ids = engine.dispatch_all(phase).expect("dispatch wave");
        if ids.is_empty() {
            break;
        }
        for id in ids {
            let is_cutover = engine.action(id).expect("action").kind.is_cutover();
            if verify_cutover && is_cutover {
                verify_state(engine, id);
            } else {
                confirm(engine, id);
            }
        }
    }
}

fn dep_count(engine: &Engine, id: ActionId) -> usize {
    engine.action(id).expect("action").depends_on.len()
}

fn advance_through_preparing(engine: &mut Engine) {
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(engine, Phase::Preparing, false);
}

fn key_of(engine: &Engine, id: ActionId) -> ResourceKey {
    engine
        .action(id)
        .expect("action exists")
        .resource
        .clone()
        .expect("action carries a resource")
}

/// 入口节点整体搬家：旧入口 entry-a 上只有链(1)和服务(3)，中间**没有**监听(2)。
/// 这正是「cleanup_dependencies 只查 layer+1」漏掉依赖的形状。
fn moved_entry_plan() -> MigrationPlan {
    MigrationPlan {
        op_id: OperationId::new("op-qa"),
        tunnel_id: "tun9".to_string(),
        old: Topology {
            entries: vec![node("entry-a")],
            hops: vec![],
            exits: vec![cand("old-x", 30009)],
            strategy: Some("round".to_string()),
        },
        new: Topology {
            entries: vec![node("entry-b")],
            hops: vec![],
            exits: vec![cand("new-x", 30001)],
            strategy: Some("round".to_string()),
        },
        forwards: vec![ForwardSpec {
            forward_id: ForwardId(1),
            user_id: 7,
            user_tunnel_id: 0,
            protocols: vec![Proto::Tcp, Proto::Udp],
            entry_ports: vec![EntryPort::new(node("entry-b"), 20001)],
            old_entry_ports: vec![EntryPort::new(node("entry-a"), 20001)],
            status: ForwardStatus::Active,
            pause_reason: None,
        }],
    }
}

/// 新旧入口相同、只有出口搬家：入口上的链与服务是**旧拓扑先存在**的同键资源。
fn shared_entry_plan() -> MigrationPlan {
    MigrationPlan {
        op_id: OperationId::new("op-qa"),
        tunnel_id: "tun9".to_string(),
        old: Topology {
            entries: vec![node("entry-a")],
            hops: vec![],
            exits: vec![cand("old-x", 30009)],
            strategy: None,
        },
        new: Topology {
            entries: vec![node("entry-a")],
            hops: vec![],
            exits: vec![cand("new-x", 30001)],
            strategy: None,
        },
        forwards: vec![ForwardSpec {
            forward_id: ForwardId(1),
            user_id: 7,
            user_tunnel_id: 0,
            protocols: vec![Proto::Tcp, Proto::Udp],
            entry_ports: vec![EntryPort::new(node("entry-a"), 20001)],
            old_entry_ports: vec![EntryPort::new(node("entry-a"), 20001)],
            status: ForwardStatus::Active,
            pause_reason: None,
        }],
    }
}

fn to_cleanup(engine: &mut Engine) {
    advance_through_preparing(engine);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(engine, Phase::Cutover, true);
    engine.advance_to(Phase::Cleanup).expect("cleanup");
}

fn attest_all(engine: &mut Engine) {
    for target in engine.legacy_targets() {
        if matches!(target.state, LegacyState::Unattested) {
            engine
                .attest_legacy(&target.key, target.kind.clone(), "api:tunnel", 1_000)
                .expect("attest");
        }
    }
}

// ---- QA1: 拆除依赖必须覆盖所有真实引用的更上层资源 -----------------------

#[test]
fn entry_chain_waits_for_services_even_without_a_listener_layer() {
    let mut engine = new_engine(moved_entry_plan(), true);
    to_cleanup(&mut engine);
    attest_all(&mut engine);

    let chain_key = ResourceKey::new(node("entry-a"), "chains_tun9".to_string());
    let chain_action = engine.request_cleanup(&chain_key).expect("chain cleanup");

    // 入口节点没有监听层，但链下面挂着两条服务（tcp/udp）——链必须在它们之后删。
    assert_eq!(
        dep_count(&engine, chain_action),
        2,
        "链删除必须依赖同节点上两条服务删除，而不是「没有紧邻上一层所以无依赖」"
    );
    let mut dep_services: Vec<String> = engine
        .action(chain_action)
        .expect("action")
        .depends_on
        .iter()
        .map(|dep| match &engine.action(*dep).expect("dep action").kind {
            ActionKind::DeleteService { service, .. } => service.clone(),
            other => panic!("链的依赖必须是服务删除，实际 {other:?}"),
        })
        .collect();
    dep_services.sort();
    assert_eq!(dep_services, vec!["1_7_0_tcp", "1_7_0_udp"]);

    assert!(
        matches!(
            engine.dispatch(chain_action),
            Err(EngineError::DependencyUnsettled { .. })
        ),
        "服务还在时链删除不能下发"
    );

    // 先删服务，链才能动；顺序由依赖决定，而不是请求顺序。
    let mut waves = Vec::new();
    loop {
        let ids = engine
            .dispatch_all(Phase::Cleanup)
            .expect("cleanup dispatch wave");
        if ids.is_empty() {
            break;
        }
        waves.push(ids.len());
        for id in ids {
            confirm(&mut engine, id);
        }
    }
    assert_eq!(waves, vec![2, 1], "两条服务先删，链最后删");
}

#[test]
fn cleanup_refuses_to_delete_below_an_unproven_upper_resource() {
    let mut engine = new_engine(moved_entry_plan(), true);
    to_cleanup(&mut engine);

    // 只给链归属证据，两条服务的归属**未知**：删链可能拆掉仍在跑的服务。
    let chain_key = ResourceKey::new(node("entry-a"), "chains_tun9".to_string());
    let chain_kind = engine
        .legacy_targets()
        .into_iter()
        .find(|target| target.key == chain_key)
        .map(|target| target.kind)
        .expect("chain is a legacy target");
    engine
        .attest_legacy(&chain_key, chain_kind, "api:tunnel", 1_000)
        .expect("attest chain");

    let result = engine.request_cleanup(&chain_key);
    assert!(
        result.is_err(),
        "上层资源未证明可删时不能先删底下的链，实际返回 {result:?}"
    );
    assert!(
        engine.actions_in(Phase::Cleanup).is_empty(),
        "被拒绝的请求不能留下半截删除动作"
    );
}

// ---- QA2: 回滚不得删除旧拓扑同键资源 ------------------------------------

#[test]
fn compensation_keeps_legacy_same_name_resources() {
    let mut engine = new_engine(shared_entry_plan(), false);
    advance_through_preparing(&mut engine);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning, false);
    engine.advance_to(Phase::Cutover).expect("cutover");
    drive(&mut engine, Phase::Cutover, false);

    let legacy_keys: Vec<ResourceKey> = vec![
        ResourceKey::new(node("entry-a"), "chains_tun9".to_string()),
        ResourceKey::new(node("entry-a"), "1_7_0_tcp".to_string()),
        ResourceKey::new(node("entry-a"), "1_7_0_udp".to_string()),
    ];
    for key in &legacy_keys {
        assert_eq!(
            engine.legacy_state(key),
            LegacyState::SharedWithNewTopology,
            "{key} 应该被识别为与旧拓扑同名"
        );
    }

    let report = engine.compensate().expect("compensate");
    for action in engine.actions_in(Phase::RollbackPending) {
        let key = action.resource.clone().expect("delete carries resource");
        assert!(
            !legacy_keys.contains(&key),
            "回滚删除了旧拓扑先存在的资源：{key}（{:?}）",
            action.kind
        );
    }
    let pending_keys: Vec<String> = report
        .pending
        .iter()
        .map(|id| key_of(&engine, *id).to_string())
        .collect();
    assert_eq!(
        report.pending.len(),
        1,
        "只剩本操作新建的那个出口监听要删，实际 {pending_keys:?}"
    );
    let mut preserved: Vec<String> = report
        .preserved
        .iter()
        .map(|entry| entry.resource.to_string())
        .collect();
    preserved.sort();
    assert_eq!(
        preserved,
        vec![
            "entry-a:1_7_0_tcp".to_string(),
            "entry-a:1_7_0_udp".to_string(),
            "entry-a:chains_tun9".to_string(),
        ],
        "三个旧拓扑同键资源必须逐个报告为保留，而不是静默跳过"
    );
    assert!(
        report.blocked.is_empty(),
        "同属性共用不该记成阻断：{:?}",
        report.blocked
    );

    for _ in 0..4 {
        let ids = engine
            .dispatch_all(Phase::RollbackPending)
            .expect("rollback wave");
        if ids.is_empty() {
            break;
        }
        for id in ids {
            confirm(&mut engine, id);
        }
    }
    assert!(engine.compensation_status().complete);
    assert_eq!(engine.phase(), Phase::Degraded);
    // 旧资源仍算「还在」，不能被记成已释放。
    for key in &legacy_keys {
        assert_eq!(engine.legacy_state(key), LegacyState::SharedWithNewTopology);
    }
}

// ---- QA3: 首次下发就超时的资源必须有台账 --------------------------------

#[test]
fn timeout_on_first_dispatch_leaves_a_reportable_resource() {
    let mut engine = new_engine(moved_entry_plan(), false);
    advance_through_preparing(&mut engine);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    // 首波动作还没收到任何回执 → 资源台账里本来没有这条记录。
    let ids = engine
        .dispatch_all(Phase::Provisioning)
        .expect("provisioning wave");
    assert!(!ids.is_empty());
    let timed_out = ids[0];
    let key = key_of(&engine, timed_out);
    engine
        .on_timeout(timed_out, "节点无回执")
        .expect("timeout accepted");
    assert_eq!(engine.phase(), Phase::TimeoutUnconfirmed);

    let unknown = engine.unknown_results();
    assert!(
        unknown.resources.contains(&key),
        "首次下发就超时的资源必须在未知结果里被报告，实际 {:?}",
        unknown
            .resources
            .iter()
            .map(|resource| resource.to_string())
            .collect::<Vec<_>>()
    );
    let record = engine
        .resources()
        .iter()
        .find(|record| record.key == key)
        .expect("超时资源必须有台账记录");
    assert!(
        !record.provision_confirmed,
        "没有收到成功回执就不能记成本操作已创建"
    );
    assert!(record.quarantined, "未决状态必须体现在台账里");
}

// ---- QA4: 同名不同属性要在执行前挡住 ------------------------------------

#[test]
fn attested_name_conflict_blocks_before_dispatch() {
    let mut plan = shared_entry_plan();
    // 同一个监听名换端口：注册名不变，只有属性变了。
    plan.new.exits = vec![cand("old-x", 30010)];
    let mut engine = new_engine(plan, false);

    let listener_key = ResourceKey::new(node("old-x"), "tun9_tls".to_string());
    let legacy_kind = engine
        .legacy_targets()
        .into_iter()
        .find(|target| target.key == listener_key)
        .map(|target| target.kind)
        .expect("legacy listener");
    assert!(matches!(
        legacy_kind,
        ResourceKind::Listener { port: 30009, .. }
    ));
    engine
        .attest_legacy(&listener_key, legacy_kind, "api:tunnel", 1_000)
        .expect("attest listener");
    // 有归属证据 + 属性不同 = 已确定的名字争用，执行前就该停。
    assert!(matches!(
        engine.legacy_state(&listener_key),
        LegacyState::NameConflict { .. }
    ));

    advance_through_preparing(&mut engine);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    let new_listener = engine
        .actions()
        .iter()
        .find(|action| {
            action.phase == Phase::Provisioning
                && action.node == node("old-x")
                && matches!(action.kind, ActionKind::AddListener { .. })
        })
        .map(|action| action.id)
        .expect("new listener action");

    let dispatched = engine.dispatch(new_listener);
    let status = &engine.action(new_listener).expect("action").status;
    assert!(
        !matches!(
            status,
            ActionStatus::Dispatched { .. } | ActionStatus::Confirmed { .. }
        ),
        "同名不同属性不该被下发，实际状态 {status:?}"
    );
    assert!(
        dispatched.is_err(),
        "下发必须被阻断并报错，实际返回 {dispatched:?}"
    );
    assert_eq!(
        engine.action(new_listener).expect("action").attempt,
        0,
        "被阻断的动作不能算作已尝试"
    );
}

// ---- QA5: 回执去重必须保留 Exists / Failure 的区别 ----------------------

#[test]
fn same_attempt_conflicting_receipt_is_rejected_not_deduped() {
    let mut engine = new_engine(moved_entry_plan(), false);
    advance_through_preparing(&mut engine);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    let id = {
        let ids = engine
            .dispatch_all(Phase::Provisioning)
            .expect("provisioning wave");
        ids[0]
    };
    let op = engine.plan().op_id.clone();
    let node = node_of(&engine, id);
    let attempt = attempt_of(&engine, id);
    let baseline = engine.applied().len();

    let first = engine
        .apply_receipt(&Receipt::exists(op.clone(), id, node.clone(), attempt))
        .expect("exists accepted");
    assert!(
        matches!(first, ReceiptDecision::Applied { .. }),
        "{first:?}"
    );
    assert!(matches!(
        engine.action(id).expect("action").status,
        ActionStatus::Conflict { .. }
    ));

    // 完全相同的重放：仍是 Duplicate。
    let replay = engine
        .apply_receipt(&Receipt::exists(op.clone(), id, node.clone(), attempt))
        .expect("replay accepted");
    assert!(
        matches!(replay, ReceiptDecision::Duplicate { .. }),
        "{replay:?}"
    );

    // 同一次尝试先说「已存在」、后说「失败」：这是两个相反的结论，不能当重复吞掉。
    let conflicting = engine.apply_receipt(&Receipt::failure(
        op.clone(),
        id,
        node.clone(),
        attempt,
        "boom",
    ));
    assert!(
        matches!(
            conflicting,
            Err(ReceiptRejection::ConflictingOutcome { .. })
        ),
        "同 attempt 的相反结论必须被拒绝，实际 {conflicting:?}"
    );
    assert_eq!(
        engine.applied().len(),
        baseline + 1,
        "只有第一条 exists 进台账，被拒绝的相反结论不能进"
    );
    assert!(matches!(
        engine.action(id).expect("action").status,
        ActionStatus::Conflict { .. }
    ));
}

#[test]
fn dedup_still_rejects_a_second_receipt_after_a_failure() {
    let mut engine = new_engine(moved_entry_plan(), false);
    advance_through_preparing(&mut engine);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");

    let id = {
        let ids = engine
            .dispatch_all(Phase::Provisioning)
            .expect("provisioning wave");
        ids[0]
    };
    let op = engine.plan().op_id.clone();
    let node = node_of(&engine, id);
    let attempt = attempt_of(&engine, id);
    engine
        .apply_receipt(&Receipt::failure(
            op.clone(),
            id,
            node.clone(),
            attempt,
            "boom",
        ))
        .expect("failure accepted");
    let replay = engine
        .apply_receipt(&Receipt::failure(op, id, node, attempt, "boom"))
        .expect("replay accepted");
    assert!(
        matches!(replay, ReceiptDecision::Duplicate { .. }),
        "{replay:?}"
    );
}

fn _assert_cleanup_rejection_is_used(_: &CleanupRejection) {}
