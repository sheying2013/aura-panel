//! 切流生成口径与 beta 对齐（beta::plan_forward）：
//! 只有 status == 1 的转发才在入口建服务，暂停(0)/异常(-1) 的转发只占端口、不产服务，
//! 因此也不会出现「先建服务再补 PauseService」这种 beta 里不存在的顺序。
//!
//! 这些测试只驱动内存状态机，不连接任何 GOST / 数据库。

use flux_migration::{
    ActionId, ActionKind, Candidate, Capability, Engine, EntryPort, ForwardId, ForwardSpec,
    ForwardStatus, MigrationPlan, NodeCapabilities, NodeId, OperationId, Phase, Proto, Receipt,
    ReceiptDecision, Topology,
};

// ---- 辅助 ---------------------------------------------------------------

fn node(name: &str) -> NodeId {
    NodeId::new(name)
}

fn cand(name: &str, port: u16) -> Candidate {
    Candidate::new(node(name), port)
}

/// 一条转发的计划骨架：入口只有 entry-a，中继 relay-b，出口 exit-d。
/// 旧拓扑与新拓扑的节点、端口、服务名完全一致，所以清理由「新旧同键」自然收口。
fn plan_with(statuses: &[(ForwardStatus, u16)]) -> MigrationPlan {
    MigrationPlan {
        op_id: OperationId::new("op-cutover"),
        tunnel_id: "tun7".to_string(),
        old: Topology {
            entries: vec![node("entry-a")],
            hops: vec![],
            exits: vec![cand("exit-d", 30003)],
            strategy: None,
        },
        new: Topology {
            entries: vec![node("entry-a")],
            hops: vec![vec![cand("relay-b", 30001)]],
            exits: vec![cand("exit-d", 30003)],
            strategy: None,
        },
        forwards: statuses
            .iter()
            .enumerate()
            .map(|(index, (status, port))| ForwardSpec {
                forward_id: ForwardId(index as u64 + 1),
                user_id: 7,
                user_tunnel_id: 0,
                protocols: vec![Proto::Tcp, Proto::Udp],
                entry_ports: vec![EntryPort::new(node("entry-a"), *port)],
                old_entry_ports: vec![EntryPort::new(node("entry-a"), *port)],
                status: *status,
                pause_reason: None,
            })
            .collect(),
    }
}

fn engine_for(plan: &MigrationPlan) -> Engine {
    let mut nodes = plan.new.nodes();
    nodes.extend(plan.old.nodes());
    nodes.sort();
    nodes.dedup();
    let caps: Vec<NodeCapabilities> = nodes
        .into_iter()
        .map(|name| {
            NodeCapabilities::new(name, [Capability::AtomicPortReserve, Capability::ProbePort])
                .with(Capability::QueryServiceState)
        })
        .collect();
    Engine::new(plan.clone(), caps).expect("plan should be valid")
}

/// 结算一个动作：切流动作给状态证据，其余只给命令确认。
fn settle(engine: &mut Engine, id: ActionId) {
    let op = engine.plan().op_id.clone();
    let (node_id, attempt, is_cutover) = {
        let action = engine.action(id).expect("action");
        (
            action.node.clone(),
            action.attempt,
            action.kind.is_cutover(),
        )
    };
    let receipt = if is_cutover {
        Receipt::verified(op, id, node_id, attempt)
    } else {
        Receipt::success(op, id, node_id, attempt)
    };
    let decision = engine.apply_receipt(&receipt).expect("receipt accepted");
    assert!(
        matches!(decision, ReceiptDecision::Applied { .. }),
        "{decision:?}"
    );
}

fn drive(engine: &mut Engine, phase: Phase) -> usize {
    let mut count = 0;
    loop {
        let ids = engine.dispatch_all(phase).expect("dispatch wave");
        if ids.is_empty() {
            break;
        }
        for id in ids {
            count += 1;
            settle(engine, id);
        }
    }
    count
}

/// 准备 + 下发两个阶段走完，停在 CUTOVER（此时切流动作已经生成）。
fn cutover_ready(plan: MigrationPlan) -> Engine {
    let mut engine = engine_for(&plan);
    engine.advance_to(Phase::Preparing).expect("preparing");
    drive(&mut engine, Phase::Preparing);
    engine
        .advance_to(Phase::Provisioning)
        .expect("provisioning");
    drive(&mut engine, Phase::Provisioning);
    engine.advance_to(Phase::Cutover).expect("cutover");
    engine
}

/// 切流阶段所有建服务动作的 (服务名, 端口)。
fn cutover_services(engine: &Engine) -> Vec<(String, u16)> {
    engine
        .actions_in(Phase::Cutover)
        .iter()
        .filter_map(|action| match &action.kind {
            ActionKind::AddService { service, port, .. } => Some((service.clone(), *port)),
            _ => None,
        })
        .collect()
}

// ---- 1. 暂停转发：零 AddService -----------------------------------------

#[test]
fn paused_forward_produces_zero_add_service() {
    let engine = cutover_ready(plan_with(&[(ForwardStatus::Paused, 20001)]));
    assert!(
        engine.actions_in(Phase::Cutover).is_empty(),
        "暂停转发不得在切流阶段生成任何动作：{:?}",
        engine.actions_in(Phase::Cutover)
    );
    assert!(cutover_services(&engine).is_empty());
    // beta 从不给暂停转发建服务，也就没有「补暂停」这一说。
    assert!(
        !engine
            .actions()
            .iter()
            .any(|action| matches!(action.kind, ActionKind::PauseService { .. })),
        "不得对不存在的服务发 PauseService"
    );
}

// ---- 2. 暂停转发：端口仍占位 --------------------------------------------

#[test]
fn paused_forward_keeps_entry_port_reserved() {
    let engine = cutover_ready(plan_with(&[
        (ForwardStatus::Paused, 20001),
        (ForwardStatus::Active, 20002),
    ]));

    // Preparing 对全部 forwards 的 entry_port 都预留 + 探测，暂停不释放端口。
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

    // 同一端口不得被任何建服务动作复用，活跃转发的端口照常点出来。
    let ports: Vec<u16> = cutover_services(&engine)
        .into_iter()
        .map(|(_, port)| port)
        .collect();
    assert!(
        !ports.contains(&20001),
        "暂停转发的端口只占位、不建服务：{ports:?}"
    );
    assert!(
        ports.contains(&20002),
        "活跃转发的端口照常建服务：{ports:?}"
    );
}

// ---- 3. 恢复后（status 回到 1）：按 beta 服务名生成 ----------------------

#[test]
fn resumed_forward_generates_beta_service_names() {
    let engine = cutover_ready(plan_with(&[(ForwardStatus::Active, 20001)]));
    let services = cutover_services(&engine);
    assert_eq!(
        services,
        vec![
            ("1_7_0_tcp".to_string(), 20001),
            ("1_7_0_udp".to_string(), 20001)
        ],
        "恢复的转发按 <forward>_<user>_<userTunnel>_<proto> 建服务"
    );
}

// ---- 4. 混合活跃/暂停：只生成活跃转发 -----------------------------------

#[test]
fn mixed_active_and_paused_only_generates_active() {
    let engine = cutover_ready(plan_with(&[
        (ForwardStatus::Paused, 20001),
        (ForwardStatus::Active, 20002),
        (ForwardStatus::Paused, 20003),
    ]));
    let services = cutover_services(&engine);
    assert_eq!(
        services,
        vec![
            ("2_7_0_tcp".to_string(), 20002),
            ("2_7_0_udp".to_string(), 20002)
        ],
        "只有 2 号活跃转发建服务：{services:?}"
    );
    let forwards: Vec<ForwardId> = engine
        .actions_in(Phase::Cutover)
        .iter()
        .filter_map(|action| match &action.kind {
            ActionKind::AddService { forward, .. } => Some(*forward),
            _ => None,
        })
        .collect();
    assert!(
        forwards.iter().all(|forward| *forward == ForwardId(2)),
        "暂停转发的 forward 不得出现在切流动作里：{forwards:?}"
    );
}

// ---- 5. status = -1 同样不激活（fail closed） ---------------------------

#[test]
fn error_status_forward_produces_zero_add_service() {
    let engine = cutover_ready(plan_with(&[(ForwardStatus::Error, 20001)]));
    assert!(
        engine.actions_in(Phase::Cutover).is_empty(),
        "status=-1 没有活跃证据，不得建服务：{:?}",
        engine.actions_in(Phase::Cutover)
    );
}

// ---- 6. 全部暂停的计划仍能收口（切流零动作是真实结论） ------------------

#[test]
fn fully_paused_plan_reaches_completed_without_cutover_actions() {
    let mut engine = cutover_ready(plan_with(&[(ForwardStatus::Paused, 20001)]));
    assert!(engine.actions_in(Phase::Cutover).is_empty());
    engine.advance_to(Phase::Cleanup).expect("cleanup");
    engine
        .advance_to(Phase::Completed)
        .expect("全暂停也要能收口：切流零动作不是缺证据");
    assert_eq!(engine.phase(), Phase::Completed);
}
