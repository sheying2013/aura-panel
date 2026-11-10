//! 纯逻辑演示：把一份迁移计划按阶段推进到底，打印每步动作与旧拓扑判定。
//!
//! 演示里的节点回执全部由本进程伪造，只用来展示状态机行为；
//! 它不连接 GOST、不连接数据库，也不证明迁移零中断。

use flux_migration::{
    Candidate, Capability, Engine, EntryPort, ForwardId, ForwardSpec, ForwardStatus, LegacyState,
    MigrationPlan, NodeCapabilities, NodeId, OperationId, Phase, Proto, Receipt, Topology,
};

fn node(name: &str) -> NodeId {
    NodeId::new(name)
}

fn plan() -> MigrationPlan {
    MigrationPlan {
        op_id: OperationId::new("demo-op"),
        tunnel_id: "tun9".to_string(),
        old: Topology {
            entries: vec![node("entry-a")],
            hops: vec![],
            exits: vec![Candidate::new(node("old-exit"), 30009)],
            strategy: None,
        },
        new: Topology {
            entries: vec![node("entry-a")],
            hops: vec![
                vec![
                    Candidate::new(node("relay-b"), 30001),
                    Candidate::new(node("relay-c"), 30002),
                ],
                vec![Candidate::new(node("relay-d"), 30004)],
            ],
            exits: vec![Candidate::new(node("exit-e"), 30003)],
            strategy: Some("round".to_string()),
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

fn capabilities(plan: &MigrationPlan) -> Vec<NodeCapabilities> {
    let mut nodes = plan.new.nodes();
    nodes.extend(plan.old.nodes());
    nodes.sort();
    nodes.dedup();
    nodes
        .into_iter()
        .map(|name| {
            // 这里声明能力，只是为了演示状态机能接受真实状态回执；
            // 旧 GOST 没有已证实的 ProbePort / queryServiceState，所以默认不声明。
            NodeCapabilities::new(
                name,
                [
                    Capability::AtomicPortReserve,
                    Capability::ProbePort,
                    Capability::QueryServiceState,
                ],
            )
        })
        .collect()
}

fn main() {
    let plan = plan();
    println!(
        "op={} tunnel={} chain={} listener={}",
        plan.op_id,
        plan.tunnel_id,
        plan.chain_name(),
        plan.listener_name()
    );
    for forward in &plan.forwards {
        for proto in &forward.protocols {
            println!(
                "  service {:<12} proto={}",
                plan.service_name(forward, *proto),
                proto.as_str()
            );
        }
    }

    let caps = capabilities(&plan);
    let mut engine = Engine::new(plan, caps).expect("plan valid");

    for phase in [Phase::Preparing, Phase::Provisioning, Phase::Cutover] {
        engine.advance_to(phase).expect("advance");
        run_phase(&mut engine, phase);
    }

    engine.advance_to(Phase::Cleanup).expect("cleanup");
    for target in engine.legacy_targets() {
        match target.state {
            // 新拓扑仍在用的资源不清理。
            LegacyState::SharedWithNewTopology => {
                println!("legacy {} 与新拓扑共用，保留", target.key);
            }
            LegacyState::Attested { .. } => {
                let action = engine.request_cleanup(&target.key).expect("cleanup");
                engine.dispatch(action).expect("dispatch");
                confirm(&mut engine, action, true);
                println!("legacy {} 已清理", target.key);
            }
            // 同名不同端口不是独立资源，而是同一个 GOST 注册名的冲突：不能当共用，也不能按名字删。
            LegacyState::NameConflict { .. } => {
                println!(
                    "legacy {} 与新拓扑同名但属性不同，名称冲突，停在待核查",
                    target.key
                );
                engine.stop_for_review().expect("review");
                break;
            }
            LegacyState::Unattested => {
                // 没有归属证据就停在待核查，绝不按名字删。
                println!("legacy {} 缺少归属证据，停在待核查", target.key);
                engine.stop_for_review().expect("review");
                break;
            }
            LegacyState::Released => {}
        }
    }

    if engine.phase() == Phase::Cleanup {
        match engine.advance_to(Phase::Completed) {
            Ok(()) => println!("phase={}", engine.phase()),
            Err(error) => {
                println!("无法完成：{error}");
                engine.stop_for_review().expect("review");
                println!("phase={}", engine.phase());
            }
        }
    } else {
        println!("phase={}", engine.phase());
    }

    println!("\nactions:");
    for action in engine.actions() {
        println!(
            "  #{:<3} {:<16} {:<10} {:<44} {}",
            action.id.0,
            action.phase.label(),
            action.node.to_string(),
            action.kind.label(),
            action.status.label()
        );
    }
    println!("\ncapability gaps: {}", engine.gaps().len());
}

/// 逐波下发并回执，保证「下游先就绪」。
fn run_phase(engine: &mut Engine, phase: Phase) {
    loop {
        let ids = engine.dispatch_all(phase).expect("dispatch wave");
        if ids.is_empty() {
            break;
        }
        for id in ids {
            confirm(engine, id, true);
        }
    }
    assert!(engine.settle_report(phase).is_clean(), "{phase} 未结算干净");
}

fn confirm(engine: &mut Engine, id: flux_migration::ActionId, verified: bool) {
    let action = engine.action(id).expect("action");
    let node = action.node.clone();
    let attempt = action.attempt;
    let op = engine.plan().op_id.clone();
    let receipt = if verified && action.kind.is_cutover() {
        Receipt::verified(op, id, node, attempt)
    } else {
        Receipt::success(op, id, node, attempt)
    };
    engine.apply_receipt(&receipt).expect("receipt accepted");
}
