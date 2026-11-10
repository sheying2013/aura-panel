use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

use crate::beta::{plan_forward, ForwardRow};
use crate::capability::{Capability, NodeCapabilities};
use crate::error::{CleanupRejection, EngineError, ReceiptRejection};
use crate::ids::{ActionId, NodeId};
use crate::plan::{Candidate, ForwardSpec, MigrationPlan, Selector, TunnelType};
use crate::receipt::{Receipt, ReceiptOutcome};
use crate::state::{
    Action, ActionKind, ActionStatus, AppliedReceipt, BlockReason, BlockedCompensation,
    CapabilityGap, LegacyAttestation, LegacyState, LegacyTargetView, Phase, PreserveReason,
    PreservedCompensation, ResourceKey, ResourceKind, ResourceRecord, SettleReport, UnknownResults,
};

/// 快照 schema 4：AppliedReceipt 保留回执结论（不再是压平的 bool success），
/// UnknownResults 增加 legacy_overlap，CompensationReport 增加 preserved。
/// 旧快照把 exists 与 failure 压成同一个 success=false，重放时无法区分相反结论，
/// 也没有 preserved 位记录「为旧拓扑保留」的资源，因此不能按 3 读入。
pub const SCHEMA_VERSION: u32 = 4;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReceiptDecision {
    Applied {
        action: ActionId,
        late_after_timeout: bool,
        previous_phase: Phase,
        phase: Phase,
    },
    Duplicate {
        action: ActionId,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CompensationReport {
    pub phase: Phase,
    pub started: Vec<ActionId>,
    pub pending: Vec<ActionId>,
    pub failed: Vec<ActionId>,
    pub blocked: Vec<BlockedCompensation>,
    /// 旧拓扑里本来就有、回滚时故意不删的资源（同键同属性 = 仍在新拓扑使用）。
    pub preserved: Vec<PreservedCompensation>,
    pub not_created: Vec<ResourceKey>,
    pub complete: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Engine {
    schema: u32,
    plan: MigrationPlan,
    /// beta tunnel.type 的规划期证据。None = 没有 type 证据（老入口），
    /// 只可能来自 Engine::new；带 type 的入口一定先过 fail-closed 校验。
    #[serde(default)]
    tunnel_type: Option<TunnelType>,
    capabilities: BTreeMap<NodeId, BTreeSet<Capability>>,
    phase: Phase,
    resume_phase: Option<Phase>,
    rollback_requested: bool,
    /// 是否已真正跑过一轮补偿。DEGRADED 只能由补偿收口产生，
    /// 不允许「刚进入回滚、还没生成删除动作」就冒充回滚完成。
    #[serde(default)]
    compensation_started: bool,
    clock_ms: i64,
    actions: Vec<Action>,
    resources: Vec<ResourceRecord>,
    applied: Vec<AppliedReceipt>,
    gaps: Vec<CapabilityGap>,
    attestations: Vec<LegacyAttestation>,
    released_legacy: Vec<ResourceKey>,
    quarantined_legacy: Vec<ResourceKey>,
    next_action_id: u64,
}

impl Engine {
    pub fn new(
        plan: MigrationPlan,
        capabilities: Vec<NodeCapabilities>,
    ) -> Result<Self, EngineError> {
        plan.validate().map_err(EngineError::InvalidPlan)?;
        Ok(Self::assemble(plan, capabilities, None))
    }

    /// beta 对齐的构造入口：先按 tunnel.type（1/2）fail-closed 拦住类型，
    /// 再跑拓扑/端口校验。type 为 NULL 或非 1/2 时这条隧道不许继续 plan。
    pub fn new_with_tunnel_type(
        plan: MigrationPlan,
        capabilities: Vec<NodeCapabilities>,
        tunnel_type: Option<i32>,
    ) -> Result<Self, EngineError> {
        let kind = plan
            .validate_with_tunnel_type(tunnel_type)
            .map_err(EngineError::InvalidPlan)?;
        Ok(Self::assemble(plan, capabilities, Some(kind)))
    }

    fn assemble(
        plan: MigrationPlan,
        capabilities: Vec<NodeCapabilities>,
        tunnel_type: Option<TunnelType>,
    ) -> Self {
        let mut map: BTreeMap<NodeId, BTreeSet<Capability>> = BTreeMap::new();
        for entry in capabilities {
            map.insert(entry.node, entry.supported);
        }
        Self {
            schema: SCHEMA_VERSION,
            plan,
            tunnel_type,
            capabilities: map,
            phase: Phase::Draft,
            resume_phase: None,
            rollback_requested: false,
            compensation_started: false,
            clock_ms: 0,
            actions: Vec::new(),
            resources: Vec::new(),
            applied: Vec::new(),
            gaps: Vec::new(),
            attestations: Vec::new(),
            released_legacy: Vec::new(),
            quarantined_legacy: Vec::new(),
            next_action_id: 1,
        }
    }

    pub fn plan(&self) -> &MigrationPlan {
        &self.plan
    }

    /// 规划期认定的 beta tunnel.type；None 表示没带 type 证据（老入口）。
    pub fn tunnel_type(&self) -> Option<TunnelType> {
        self.tunnel_type
    }

    pub fn phase(&self) -> Phase {
        self.phase
    }

    pub fn resume_phase(&self) -> Option<Phase> {
        self.resume_phase
    }

    pub fn clock_ms(&self) -> i64 {
        self.clock_ms
    }

    /// 本机单调时钟：回拨直接拒绝。它只描述本机时间戳，
    /// 绝不用来和节点时钟比较做因果排序（跨机器时钟不可比）。
    pub fn set_clock(&mut self, now_ms: i64) -> Result<(), EngineError> {
        if now_ms < self.clock_ms {
            return Err(EngineError::ClockRegression {
                current: self.clock_ms,
                received: now_ms,
            });
        }
        self.clock_ms = now_ms;
        Ok(())
    }

    pub fn actions(&self) -> &[Action] {
        &self.actions
    }

    pub fn action(&self, id: ActionId) -> Option<&Action> {
        self.actions.iter().find(|action| action.id == id)
    }

    pub fn actions_in(&self, phase: Phase) -> Vec<&Action> {
        self.actions
            .iter()
            .filter(|action| action.phase == phase)
            .collect()
    }

    pub fn resources(&self) -> &[ResourceRecord] {
        &self.resources
    }

    pub fn applied(&self) -> &[AppliedReceipt] {
        &self.applied
    }

    pub fn gaps(&self) -> &[CapabilityGap] {
        &self.gaps
    }

    pub fn attestations(&self) -> &[LegacyAttestation] {
        &self.attestations
    }

    pub fn supports(&self, node: &NodeId, capability: Capability) -> bool {
        self.capabilities
            .get(node)
            .map(|set| set.contains(&capability))
            .unwrap_or(false)
    }

    fn triggers_rollback(phase: Phase) -> bool {
        matches!(
            phase,
            Phase::Preparing | Phase::Provisioning | Phase::Cutover
        )
    }

    // ---- 阶段推进 -------------------------------------------------------

    pub fn advance_to(&mut self, next: Phase) -> Result<(), EngineError> {
        let from = self.phase;
        if !Phase::transition_allowed(from, next) {
            return Err(EngineError::IllegalTransition { from, to: next });
        }
        match next {
            Phase::RollbackPending => {
                self.rollback_requested = true;
                self.phase = next;
                return Ok(());
            }
            Phase::ManualReview => {
                self.phase = next;
                return Ok(());
            }
            _ => {}
        }

        let report = self.settle_report(from);
        if let Some(action) = report.unconfirmed.first() {
            return Err(EngineError::UnconfirmedOutcome {
                action: Some(*action),
            });
        }
        if !report.conflicted.is_empty() {
            return Err(EngineError::PhaseHasConflicts {
                phase: from,
                actions: report.conflicted,
            });
        }
        if !report.failed.is_empty() {
            return Err(EngineError::PhaseHasFailures {
                phase: from,
                actions: report.failed,
            });
        }
        if !report.blocked.is_empty() {
            let gaps: Vec<CapabilityGap> = self
                .gaps
                .iter()
                .filter(|gap| report.blocked.contains(&gap.action))
                .cloned()
                .collect();
            return Err(EngineError::CapabilityBlocked { phase: from, gaps });
        }
        if !report.pending.is_empty() {
            return Err(EngineError::PhaseNotSettled {
                phase: from,
                blocking: report.pending,
            });
        }
        if next == Phase::Completed {
            self.verify_completion()?;
        }
        self.phase = next;
        self.generate_actions_for(next);
        Ok(())
    }

    /// 证据不足时停在待核查，绝不冒充 COMPLETED。
    pub fn stop_for_review(&mut self) -> Result<(), EngineError> {
        let from = self.phase;
        if from.is_terminal() {
            return Err(EngineError::IllegalTransition {
                from,
                to: Phase::ManualReview,
            });
        }
        self.phase = Phase::ManualReview;
        Ok(())
    }

    fn verify_completion(&self) -> Result<(), EngineError> {
        let cutover: Vec<&Action> = self
            .actions
            .iter()
            .filter(|a| a.kind.is_cutover())
            .collect();
        let verified = cutover.iter().filter(|a| a.status.is_verified()).count();
        let unverified: Vec<ActionId> = cutover
            .iter()
            .filter(|a| !a.status.is_verified())
            .map(|a| a.id)
            .collect();
        // 完整性按 beta::plan_forward 算出的服务集合衡量：暂停/异常转发不进这个集合，
        // 所以「全部转发都暂停」时切流阶段本来就没有动作 —— 那是真实结论，不是缺证据
        // （旧实现用 cutover.is_empty()，会把这种合法情形永远挡在 COMPLETED 之前）。
        // 只要还有活跃转发，建服务动作就不得少于这个集合。
        let expected: usize = self
            .plan
            .forwards
            .iter()
            .map(|forward| {
                plan_forward(&beta_row(forward), &forward.entry_ports)
                    .services
                    .len()
            })
            .sum();
        if !unverified.is_empty() || cutover.len() < expected {
            return Err(EngineError::CutoverUnverified {
                verified,
                total: cutover.len(),
                actions: unverified,
            });
        }

        let mut unattested: Vec<ResourceKey> = Vec::new();
        let mut not_cleaned: Vec<ResourceKey> = Vec::new();
        let mut name_conflicts: Vec<ResourceKey> = Vec::new();
        for (key, _kind) in self.plan.legacy_resources() {
            match self.legacy_state(&key) {
                LegacyState::SharedWithNewTopology | LegacyState::Released => {}
                LegacyState::Attested { .. } => not_cleaned.push(key),
                LegacyState::Unattested => unattested.push(key),
                LegacyState::NameConflict { .. } => name_conflicts.push(key),
            }
        }
        if !name_conflicts.is_empty() {
            return Err(EngineError::LegacyNameConflict {
                keys: name_conflicts,
            });
        }
        if !unattested.is_empty() || !not_cleaned.is_empty() {
            return Err(EngineError::LegacyUnresolved {
                unattested,
                not_cleaned,
            });
        }
        Ok(())
    }

    // ---- 动作生成 -------------------------------------------------------

    fn generate_actions_for(&mut self, phase: Phase) {
        if self.actions.iter().any(|action| action.phase == phase) {
            return;
        }
        match phase {
            Phase::Preparing => self.generate_preparing(),
            Phase::Provisioning => self.generate_provisioning(),
            Phase::Cutover => self.generate_cutover(),
            _ => {}
        }
    }

    /// 预检：每个 (入口节点, 端口) 各一次原子预留 + 真实探测。
    /// 两个协议共用同一入口端口，所以这里按端口去重，不按协议。
    fn generate_preparing(&mut self) {
        let mut targets: Vec<(NodeId, u16)> = Vec::new();
        for forward in &self.plan.forwards {
            for entry_port in &forward.entry_ports {
                targets.push((entry_port.node.clone(), entry_port.port));
            }
        }
        targets.sort();
        targets.dedup();
        for (node, port) in targets {
            self.push_action(
                node.clone(),
                Phase::Preparing,
                ActionKind::ReservePort { port },
                None,
                Vec::new(),
            );
            self.push_action(
                node,
                Phase::Preparing,
                ActionKind::ProbePort { port },
                None,
                Vec::new(),
            );
        }
    }

    /// 下发顺序与 beta 的 publishChains 一致（TunnelServiceImpl.java:268-304）：
    /// 入口链 → 中间跳（每候选先链后监听）→ 出口监听。
    ///
    /// <p>不能像旧实现那样「反向拓扑屏障」：beta 的 createChains 只是把下一跳地址写进本节点
    /// 配置，不要求下游监听已经存在；真机验证过入口链先发、下游随后建立即可。原型在这里
    /// 强行等待下游确认，会与真实下发顺序、失败回滚边界全部对不上。
    ///
    /// <p>保留的唯一动作级依赖是 beta 同一节点内的「先建链再建服务」
    /// （publishChains 里 createChains 在前、publishChainService 在后）：监听/中继服务引用
    /// 本节点链名，链没确认就下发会让节点拿不到 chain。
    fn generate_provisioning(&mut self) {
        let chain = self.plan.chain_name();
        let listener = self.plan.listener_name();
        // beta 每条链的每个 hop 都带同一个 selector（GostUtil.java:97-103）。
        let selector = Selector::for_hop(self.plan.new.strategy.as_deref());
        // 拓扑先拷贝出来，避免遍历时同时可变借用 self。
        let exits = self.plan.new.exits.clone();
        let hops = self.plan.new.hops.clone();
        let entries = self.plan.new.entries.clone();

        // 1) 入口：只建链，指向第一跳候选（没有中间跳时直接指向出口候选）。
        //    beta 第一步就是入口链（TunnelServiceImpl.java:268-277），无下游前置依赖。
        let first_next: Vec<Candidate> = if hops.is_empty() {
            exits.clone()
        } else {
            hops[0].clone()
        };
        for entry in &entries {
            let key = ResourceKey::new(entry.clone(), chain.clone());
            self.push_action(
                entry.clone(),
                Phase::Provisioning,
                ActionKind::AddChains {
                    chain: chain.clone(),
                    next: first_next.clone(),
                    hop: 0,
                    selector: selector.clone(),
                },
                Some(key),
                Vec::new(),
            );
        }

        // 2) 中间跳：按正向顺序，每跳每个候选各自 链 + 监听（beta :279-296 的嵌套循环）。
        for (index, layer) in hops.iter().enumerate() {
            let next: Vec<Candidate> = if index + 1 < hops.len() {
                hops[index + 1].clone()
            } else {
                exits.clone()
            };
            for candidate in layer {
                let chain_key = ResourceKey::new(candidate.node.clone(), chain.clone());
                let chain_id = self.push_action(
                    candidate.node.clone(),
                    Phase::Provisioning,
                    ActionKind::AddChains {
                        chain: chain.clone(),
                        next: next.clone(),
                        hop: (index + 1) as u8,
                        selector: selector.clone(),
                    },
                    Some(chain_key),
                    Vec::new(),
                );
                let listener_key = ResourceKey::new(candidate.node.clone(), listener.clone());
                self.push_action(
                    candidate.node.clone(),
                    Phase::Provisioning,
                    ActionKind::AddListener {
                        listener: listener.clone(),
                        port: candidate.port,
                        chained: true,
                    },
                    Some(listener_key),
                    vec![chain_id],
                );
            }
        }

        // 3) 出口：只建监听，不建链（beta :298-304 的最后一步）。
        for candidate in &exits {
            let key = ResourceKey::new(candidate.node.clone(), listener.clone());
            self.push_action(
                candidate.node.clone(),
                Phase::Provisioning,
                ActionKind::AddListener {
                    listener: listener.clone(),
                    port: candidate.port,
                    chained: false,
                },
                Some(key),
                Vec::new(),
            );
        }
    }

    /// 切换：只在入口节点上按 forward x 协议建服务，依赖该入口的链已确认。
    ///
    /// 服务集合完全交给 beta::plan_forward：beta 的补齐/重建只处理 status == 1 的转发
    /// （ForwardServiceImpl.java:1597-1602）。暂停(0) / 异常(-1) 的转发在切流里既不建服务，
    /// 也不补 PauseService —— 那些服务 beta 从来没建过，对不存在的服务发暂停就是凭空造状态。
    ///
    /// 端口照旧被占着：generate_preparing 对全部 forwards 的 entry_ports 都预留 + 探测，
    /// beta 的 forward_port 行也不会因为 status != 1 而删除。
    fn generate_cutover(&mut self) {
        let chain = self.plan.chain_name();
        // 入口链动作按节点索引：服务必须挂在本节点链已确认之后。
        let chain_ids: BTreeMap<NodeId, ActionId> = self
            .actions
            .iter()
            .filter(|action| {
                action.phase == Phase::Provisioning
                    && matches!(action.kind, ActionKind::AddChains { .. })
            })
            .map(|action| (action.node.clone(), action.id))
            .collect();

        for forward in self.plan.forwards.clone() {
            let forward_plan = plan_forward(&beta_row(&forward), &forward.entry_ports);
            for service in &forward_plan.services {
                let depends_on: Vec<ActionId> =
                    chain_ids.get(&service.node).copied().into_iter().collect();
                self.push_action(
                    service.node.clone(),
                    Phase::Cutover,
                    ActionKind::AddService {
                        forward: forward.forward_id,
                        service: service.name.clone(),
                        port: service.port,
                        proto: service.proto,
                        chain: chain.clone(),
                    },
                    Some(ResourceKey::new(service.node.clone(), service.name.clone())),
                    depends_on,
                );
            }
        }
    }

    fn push_action(
        &mut self,
        node: NodeId,
        phase: Phase,
        kind: ActionKind,
        resource: Option<ResourceKey>,
        depends_on: Vec<ActionId>,
    ) -> ActionId {
        let id = ActionId(self.next_action_id);
        self.next_action_id += 1;
        let mut status = ActionStatus::Pending;
        if let Some(capability) = kind.required_capability() {
            if !self.supports(&node, capability) {
                status = ActionStatus::Blocked { capability };
                self.gaps.push(CapabilityGap {
                    node: node.clone(),
                    capability,
                    action: id,
                    phase,
                    note: "节点未声明该能力；按 fail closed 阻断，不当作通过".to_string(),
                });
            }
        }
        self.actions.push(Action {
            op: self.plan.op_id.clone(),
            id,
            node,
            phase,
            kind,
            resource,
            attempt: 0,
            status,
            depends_on,
            dispatched_at_ms: 0,
        });
        id
    }

    // ---- 下发 -----------------------------------------------------------

    pub fn dispatch(&mut self, id: ActionId) -> Result<u32, EngineError> {
        if self.phase.is_frozen() {
            return Err(EngineError::PhaseFrozen { phase: self.phase });
        }
        let index = self
            .actions
            .iter()
            .position(|action| action.id == id)
            .ok_or(EngineError::UnknownAction(id))?;
        match self.actions[index].status.clone() {
            ActionStatus::Pending => {
                if let Some((key, existing, requested)) = self.preflight_conflict(index) {
                    // 同名不同属性 = 抢同一个 GOST 注册名。执行前就地阻断：既不把动作
                    // 下发出去（否则节点会按名字覆盖旧对象），也不当成"未决"——
                    // 这是已知冲突，交给结算/补偿按冲突处理。
                    self.mark_conflict(&key, requested.clone());
                    self.actions[index].status = ActionStatus::Conflict {
                        attempt: 0,
                        reply: "name-conflict".to_string(),
                    };
                    return Err(EngineError::NameConflictBeforeDispatch {
                        action: id,
                        key,
                        existing,
                        requested,
                    });
                }
                let blocking = self.unsettled_dependencies(index);
                if !blocking.is_empty() {
                    return Err(EngineError::DependencyUnsettled {
                        action: id,
                        blocking,
                    });
                }
                let attempt = self.actions[index].attempt + 1;
                self.actions[index].attempt = attempt;
                self.actions[index].status = ActionStatus::Dispatched { attempt };
                self.actions[index].dispatched_at_ms = self.clock_ms;
                Ok(attempt)
            }
            ActionStatus::Dispatched { attempt } => Ok(attempt),
            other => Err(EngineError::IllegalActionState {
                action: id,
                status: other.label(),
            }),
        }
    }

    /// 按依赖波次下发该阶段所有就绪动作；被依赖未确认的动作留在原地。
    ///
    /// 批量下发是"排空"原语：冻结阶段或非当前阶段不存在可就绪动作，返回空向量。
    /// 单条 dispatch 才代表操作者的明确意图，冻结时直接拒绝。
    pub fn dispatch_all(&mut self, phase: Phase) -> Result<Vec<ActionId>, EngineError> {
        if self.phase.is_frozen() || phase != self.phase {
            return Ok(Vec::new());
        }
        let mut dispatched = Vec::new();
        loop {
            let ready: Vec<ActionId> = self
                .actions
                .iter()
                .filter(|action| action.phase == phase && action.status.is_pending())
                .filter(|action| self.dependencies_confirmed(action))
                .map(|action| action.id)
                .collect();
            if ready.is_empty() {
                break;
            }
            for id in ready {
                match self.dispatch(id) {
                    Ok(_) => dispatched.push(id),
                    // 同名冲突已被 dispatch 就地置为 Conflict，不再是 pending，
                    // 所以不会让这个循环空转；同波次其它动作照常下发。
                    Err(EngineError::NameConflictBeforeDispatch { .. }) => {}
                    Err(err) => return Err(err),
                }
            }
        }
        Ok(dispatched)
    }

    fn unsettled_dependencies(&self, index: usize) -> Vec<ActionId> {
        let deps = self.actions[index].depends_on.clone();
        deps.into_iter()
            .filter(|dep| {
                !self
                    .actions
                    .iter()
                    .any(|action| action.id == *dep && action.status.is_confirmed())
            })
            .collect()
    }

    /// 执行前的同名争用判定：同一个 (节点, 名字) 在 GOST 里只能有一种属性。
    ///
    /// 只有拿到证据才算争用，不在计划阶段臆测：
    /// - 本操作台账里这个名字已按别的属性登记过（含上次回执留下的记录）；
    /// - 旧拓扑同键但属性不同，且该旧资源有归属证据 —— 没有归属证据的旧名字只是
    ///   "可能还在用"，此时按计划继续下发，让真实回执说明情况（保持既有契约）。
    ///
    /// 链的 kind 只带名字，不携带 next/hop，所以同名链的内容变化在这里无从比较：
    /// 这类情况保持 SharedWithNewTopology（保守：永不删），不作为冲突阻断。
    fn preflight_conflict(
        &self,
        index: usize,
    ) -> Option<(ResourceKey, ResourceKind, ResourceKind)> {
        let action = &self.actions[index];
        if action.kind.is_delete() {
            return None;
        }
        let key = action.resource.clone()?;
        let requested = action.kind.resource_kind()?;
        if let Some(record) = self.resources.iter().find(|record| record.key == key) {
            if record.kind != requested {
                return Some((key, record.kind.clone(), requested));
            }
        }
        let legacy_kind = self
            .plan
            .legacy_resources()
            .into_iter()
            .find(|(candidate, _)| candidate == &key)
            .map(|(_, kind)| kind)?;
        if legacy_kind != requested && self.attestation(&key).is_some() {
            return Some((key, legacy_kind, requested));
        }
        None
    }

    fn dependencies_confirmed(&self, action: &Action) -> bool {
        action.depends_on.iter().all(|dep| {
            self.actions
                .iter()
                .any(|other| other.id == *dep && other.status.is_confirmed())
        })
    }

    /// 超时：动作未决 + 资源隔离，阶段进入 TIMEOUT_UNCONFIRMED 并记住原阶段。
    pub fn on_timeout(&mut self, id: ActionId, note: impl Into<String>) -> Result<(), EngineError> {
        // 已经终结的阶段不得因为一条超时通知退回"结论未收口"。
        // 同一阶段内多条在飞动作各自记录超时是合法的，所以这里只挡终态。
        if self.phase.is_terminal() {
            return Err(EngineError::PhaseFrozen { phase: self.phase });
        }
        let index = self
            .actions
            .iter()
            .position(|action| action.id == id)
            .ok_or(EngineError::UnknownAction(id))?;
        let note = note.into();
        let attempt = match &self.actions[index].status {
            ActionStatus::Dispatched { attempt } => *attempt,
            other => {
                return Err(EngineError::IllegalActionState {
                    action: id,
                    status: other.label(),
                })
            }
        };
        let resource = self.actions[index].resource.clone();
        let resource_kind = self.actions[index].kind.resource_kind();
        self.actions[index].status = ActionStatus::Unconfirmed { attempt, note };
        if let Some(key) = resource {
            if self.is_legacy_target(&key) {
                // 旧拓扑同名键：只记"结果未知"，绝不给本操作记归属——
                // 归属由旧拓扑证据决定，不能因为一次成功回执就把旧资源划过来。
                if !self.quarantined_legacy.contains(&key) {
                    self.quarantined_legacy.push(key);
                }
            } else if let Some(record) = self.resources.iter_mut().find(|record| record.key == key)
            {
                record.quarantined = true;
            } else if let Some(kind) = resource_kind {
                // 首次下发就超时：从来没有回执，台账里根本没这条。必须补一条
                // 「未确认」记录，否则补偿清单既看不到它、也无法如实报告它未决。
                // provision_confirmed=false 是硬规矩：没有回执就没有归属证据。
                self.resources.push(ResourceRecord {
                    key: key.clone(),
                    kind,
                    op: self.plan.op_id.clone(),
                    provision_confirmed: false,
                    quarantined: true,
                    released: false,
                    conflicted: false,
                });
            }
        }
        if self.phase != Phase::TimeoutUnconfirmed {
            self.resume_phase = Some(self.phase);
            self.phase = Phase::TimeoutUnconfirmed;
        }
        Ok(())
    }

    fn resolve_timeout(&mut self) {
        if self
            .actions
            .iter()
            .any(|action| action.status.is_unconfirmed())
        {
            return;
        }
        if self.phase != Phase::TimeoutUnconfirmed {
            return;
        }
        if self.rollback_requested {
            self.resume_phase = None;
            self.phase = Phase::RollbackPending;
        } else if let Some(phase) = self.resume_phase.take() {
            self.phase = phase;
        }
    }

    // ---- 回执 -----------------------------------------------------------

    pub fn apply_receipt(
        &mut self,
        receipt: &Receipt,
    ) -> Result<ReceiptDecision, ReceiptRejection> {
        if receipt.op_id != self.plan.op_id {
            return Err(ReceiptRejection::ForeignOperation {
                expected: self.plan.op_id.clone(),
                received: receipt.op_id.clone(),
            });
        }
        let index = self
            .actions
            .iter()
            .position(|action| action.id == receipt.action)
            .ok_or(ReceiptRejection::UnknownAction(receipt.action))?;
        if self.actions[index].node != receipt.node {
            return Err(ReceiptRejection::NodeMismatch {
                action: receipt.action,
                expected: self.actions[index].node.clone(),
                received: receipt.node.clone(),
            });
        }
        let attempt = self.actions[index].attempt;
        if receipt.attempt != attempt {
            return Err(ReceiptRejection::AttemptMismatch {
                action: receipt.action,
                current: attempt,
                received: receipt.attempt,
            });
        }
        let success = receipt.is_success();
        // 判重必须按完整结论比：exists 与 failure 都"不是成功"，压成 bool 会把
        // 同一个 attempt 的相反结论当成重复回执悄悄吞掉。
        if self.applied.iter().any(|applied| {
            applied.action == receipt.action
                && applied.attempt == receipt.attempt
                && applied.outcome == receipt.outcome
        }) {
            return Ok(ReceiptDecision::Duplicate {
                action: receipt.action,
            });
        }
        // 节点时钟不参与认证，也不参与因果排序：身份/attempt 已经由上面四项校验确定，
        // 跨机器时钟的偏差只记进 applied 审计，不能用来否定一条真实回执。
        let node = self.actions[index].node.clone();
        if receipt.evidence.is_verified() && !self.supports(&node, Capability::QueryServiceState) {
            return Err(ReceiptRejection::EvidenceUnproven {
                action: receipt.action,
                node,
                capability: Capability::QueryServiceState,
            });
        }
        match &self.actions[index].status {
            ActionStatus::Pending => return Err(ReceiptRejection::NotDispatched(receipt.action)),
            ActionStatus::Blocked { capability } => {
                return Err(ReceiptRejection::ActionBlocked {
                    action: receipt.action,
                    capability: *capability,
                })
            }
            ActionStatus::Confirmed { .. }
            | ActionStatus::Failed { .. }
            | ActionStatus::Conflict { .. } => {
                return Err(ReceiptRejection::ConflictingOutcome {
                    action: receipt.action,
                    attempt,
                })
            }
            ActionStatus::Dispatched { .. } | ActionStatus::Unconfirmed { .. } => {}
        }

        let late_after_timeout = self.actions[index].status.is_unconfirmed();
        let previous_phase = self.phase;
        let action_phase = self.actions[index].phase;
        let kind = self.actions[index].kind.clone();
        let resource = self.actions[index].resource.clone();

        match &receipt.outcome {
            ReceiptOutcome::Success => {
                self.actions[index].status = ActionStatus::Confirmed {
                    attempt,
                    evidence: receipt.evidence,
                };
                if let (Some(key), Some(resource_kind)) = (resource.clone(), kind.resource_kind()) {
                    if kind.is_delete() {
                        self.mark_released(&key, resource_kind);
                    } else {
                        self.ensure_provisioned(&key, resource_kind);
                    }
                }
            }
            ReceiptOutcome::Exists => {
                self.actions[index].status = ActionStatus::Conflict {
                    attempt,
                    reply: "exists".to_string(),
                };
                if let (Some(key), Some(resource_kind)) = (resource, kind.resource_kind()) {
                    self.mark_conflict(&key, resource_kind);
                }
            }
            ReceiptOutcome::Failure { reason } => {
                self.actions[index].status = ActionStatus::Failed {
                    attempt,
                    reason: reason.clone(),
                };
                if let Some(key) = resource.clone() {
                    if kind.is_delete() {
                        // 删除失败 = 资源还在、归属仍成立：不能记成「未创建」，否则补偿会漏删；
                        // 已知失败也不再算「结果未知」，隔离标记要撤掉。
                        self.mark_delete_failed(&key);
                    } else if let Some(resource_kind) = kind.resource_kind() {
                        self.mark_not_created(&key, resource_kind);
                    }
                }
            }
        }
        self.applied.push(AppliedReceipt {
            action: receipt.action,
            attempt,
            outcome: receipt.outcome.clone(),
            node_time_ms: receipt.node_time_ms,
        });

        if !success && Self::triggers_rollback(action_phase) {
            self.rollback_requested = true;
            if Self::triggers_rollback(self.phase) {
                self.phase = Phase::RollbackPending;
            }
        }
        if late_after_timeout {
            self.resolve_timeout();
        }
        self.refresh_rollback();
        Ok(ReceiptDecision::Applied {
            action: receipt.action,
            late_after_timeout,
            previous_phase,
            phase: self.phase,
        })
    }

    fn ensure_provisioned(&mut self, key: &ResourceKey, kind: ResourceKind) {
        if let Some(record) = self.resources.iter_mut().find(|record| &record.key == key) {
            if record.kind != kind {
                // 同名不同属性：这是 GOST 注册名冲突，不是「同一个资源换了端口」，
                // 不能悄悄改写属性把冲突吞掉。
                record.provision_confirmed = false;
                record.quarantined = false;
                record.conflicted = true;
                return;
            }
            record.provision_confirmed = true;
            record.quarantined = false;
            record.conflicted = false;
            return;
        }
        self.resources.push(ResourceRecord {
            key: key.clone(),
            kind,
            op: self.plan.op_id.clone(),
            provision_confirmed: true,
            quarantined: false,
            released: false,
            conflicted: false,
        });
    }

    fn mark_conflict(&mut self, key: &ResourceKey, kind: ResourceKind) {
        if let Some(record) = self.resources.iter_mut().find(|record| &record.key == key) {
            record.provision_confirmed = false;
            record.conflicted = true;
            record.quarantined = false;
            return;
        }
        self.resources.push(ResourceRecord {
            key: key.clone(),
            kind,
            op: self.plan.op_id.clone(),
            provision_confirmed: false,
            quarantined: false,
            released: false,
            conflicted: true,
        });
    }

    fn mark_not_created(&mut self, key: &ResourceKey, kind: ResourceKind) {
        if let Some(record) = self.resources.iter_mut().find(|record| &record.key == key) {
            record.provision_confirmed = false;
            record.quarantined = false;
            return;
        }
        // 从未确认过的资源也要留痕：节点明确回了失败，就如实记成「未创建」，
        // 这样补偿清单能报告它、且不会被误删（含与旧拓扑同名的键：那是旧资源仍在，
        // 由旧拓扑归属证据决定是否清理，绝不因本操作失败就删）。
        self.resources.push(ResourceRecord {
            key: key.clone(),
            kind,
            op: self.plan.op_id.clone(),
            provision_confirmed: false,
            quarantined: false,
            released: false,
            conflicted: false,
        });
    }

    fn mark_released(&mut self, key: &ResourceKey, kind: ResourceKind) {
        if self.is_legacy_target(key) {
            if !self.released_legacy.contains(key) {
                self.released_legacy.push(key.clone());
            }
            self.quarantined_legacy.retain(|other| other != key);
            return;
        }
        if let Some(record) = self.resources.iter_mut().find(|record| &record.key == key) {
            record.kind = kind;
            record.provision_confirmed = true;
            record.quarantined = false;
            record.released = true;
            return;
        }
        self.resources.push(ResourceRecord {
            key: key.clone(),
            kind,
            op: self.plan.op_id.clone(),
            provision_confirmed: true,
            quarantined: false,
            released: true,
            conflicted: false,
        });
    }

    /// 删除失败：资源仍然存在且仍归本操作。只解除「结果未知」的隔离，
    /// 绝不清掉归属标记，否则补偿会把它当成从未创建而漏删。
    fn mark_delete_failed(&mut self, key: &ResourceKey) {
        self.quarantined_legacy.retain(|other| other != key);
        if let Some(record) = self.resources.iter_mut().find(|record| &record.key == key) {
            record.quarantined = false;
        }
    }

    fn is_legacy_target(&self, key: &ResourceKey) -> bool {
        self.plan
            .legacy_resources()
            .iter()
            .any(|(candidate, _)| candidate == key)
    }

    fn legacy_released(&self, key: &ResourceKey) -> bool {
        self.released_legacy.contains(key)
    }

    fn attestation(&self, key: &ResourceKey) -> Option<&LegacyAttestation> {
        self.attestations.iter().find(|entry| &entry.key == key)
    }

    pub fn attest_legacy(
        &mut self,
        key: &ResourceKey,
        kind: ResourceKind,
        source: impl Into<String>,
        recorded_at_ms: i64,
    ) -> Result<(), EngineError> {
        let expected = self
            .plan
            .legacy_resources()
            .into_iter()
            .find(|(candidate, _)| candidate == key)
            .map(|(_, kind)| kind)
            .ok_or_else(|| EngineError::UnknownLegacyTarget(key.clone()))?;
        if expected != kind {
            return Err(EngineError::LegacyKindMismatch {
                key: key.clone(),
                expected,
                received: kind,
            });
        }
        self.attestations.push(LegacyAttestation {
            key: key.clone(),
            kind,
            source: source.into(),
            recorded_at_ms,
        });
        Ok(())
    }

    pub fn legacy_state(&self, key: &ResourceKey) -> LegacyState {
        if let Some(new_kind) = self
            .plan
            .new_resources()
            .into_iter()
            .find(|(candidate, _)| candidate == key)
            .map(|(_, kind)| kind)
        {
            // 身份是 (节点, 名字)，端口/协议只是属性。新拓扑占了同一个名字但属性不同，
            // 说明新旧两边抢的是同一个 GOST 注册名：既不能算共用，也不能按名字删。
            let attribute_conflict = self
                .plan
                .legacy_resources()
                .into_iter()
                .find(|(candidate, _)| candidate == key)
                .map(|(_, legacy_kind)| legacy_kind != new_kind)
                .unwrap_or(false);
            return if attribute_conflict {
                LegacyState::NameConflict { new_kind }
            } else {
                LegacyState::SharedWithNewTopology
            };
        }
        if self.legacy_released(key) {
            return LegacyState::Released;
        }
        match self.attestation(key) {
            Some(entry) => LegacyState::Attested {
                source: entry.source.clone(),
            },
            None => LegacyState::Unattested,
        }
    }

    pub fn legacy_targets(&self) -> Vec<LegacyTargetView> {
        self.plan
            .legacy_resources()
            .into_iter()
            .map(|(key, kind)| {
                let state = self.legacy_state(&key);
                LegacyTargetView { key, kind, state }
            })
            .collect()
    }

    // ---- 清理旧拓扑 -----------------------------------------------------

    pub fn request_cleanup(&mut self, key: &ResourceKey) -> Result<ActionId, CleanupRejection> {
        if self.phase != Phase::Cleanup {
            return Err(CleanupRejection::WrongPhase(self.phase));
        }
        match self.legacy_state(key) {
            LegacyState::SharedWithNewTopology => {
                return Err(CleanupRejection::ActiveInNewTopology(key.clone()))
            }
            LegacyState::NameConflict { .. } => {
                return Err(CleanupRejection::NameConflictInNewTopology(key.clone()))
            }
            LegacyState::Released => return Err(CleanupRejection::AlreadyReleased(key.clone())),
            LegacyState::Unattested | LegacyState::Attested { .. } => {}
        }
        self.ensure_cleanup_action(key)
    }

    /// 删除 DAG 由资源层次决定，和请求顺序无关：低层永远等紧邻上一层删完，
    /// 所以先请求拆链也不会抢在监听/服务之前下发。
    fn ensure_cleanup_action(&mut self, key: &ResourceKey) -> Result<ActionId, CleanupRejection> {
        let kind = self
            .legacy_kind(key)
            .ok_or_else(|| CleanupRejection::NotLegacyTarget(key.clone()))?;
        if self.legacy_released(key) {
            return Err(CleanupRejection::AlreadyReleased(key.clone()));
        }
        if self.attestation(key).is_none() {
            return Err(CleanupRejection::LegacyNotAttested(key.clone()));
        }
        if let Some(index) = self.actions.iter().position(|action| {
            action.phase == Phase::Cleanup && action.resource.as_ref() == Some(key)
        }) {
            let id = self.actions[index].id;
            match &self.actions[index].status {
                ActionStatus::Unconfirmed { .. } => {
                    return Err(CleanupRejection::OutcomeUnconfirmed {
                        key: key.clone(),
                        action: id,
                    })
                }
                // 节点明确回过失败：重试同一条动作，不另建一条把失败记录掩盖掉。
                ActionStatus::Failed { .. } => self.actions[index].status = ActionStatus::Pending,
                _ => {}
            }
            return Ok(id);
        }
        let depends_on = self.cleanup_dependencies(key, kind.layer())?;
        Ok(self.push_action(
            key.node.clone(),
            Phase::Cleanup,
            delete_kind(&kind, key),
            Some(key.clone()),
            depends_on,
        ))
    }

    /// 同节点上所有更高层次旧资源的删除动作；只收可以证明归属、且确实要删的旧资源。
    ///
    /// 不能只等紧邻上一层：入口链(1) 和入口服务(3) 之间经常没有监听(2)（服务直连
    /// 入口链），按 layer+1 找依赖会得到"无依赖"，于是在服务还引用着链的时候就先把
    /// 链拆了。
    ///
    /// 上层资源只要没被证明可删（未 attest、仍在新拓扑使用、同名冲突、归属未定），
    /// 就不能跳过它去删下层：跳过等于宣称"它不影响下层"，而我们没有这个证据。
    /// 先整轮校验再建动作，报错时不会留下半截删除 DAG。
    ///
    /// 依赖仍按节点裁剪：链和监听都是节点本地对象（监听只引用本机的同名链），
    /// 跨节点删除互不影响，一台机器的清理失败不该把别的节点一起卡住。
    fn cleanup_dependencies(
        &mut self,
        requested: &ResourceKey,
        layer: u8,
    ) -> Result<Vec<ActionId>, CleanupRejection> {
        let mut upper: Vec<(ResourceKey, ResourceKind)> = self
            .plan
            .legacy_resources()
            .into_iter()
            .filter(|(key, kind)| kind.layer() > layer && key.node == requested.node)
            .collect();
        upper.sort_by(|left, right| {
            right
                .1
                .layer()
                .cmp(&left.1.layer())
                .then_with(|| left.0.cmp(&right.0))
        });
        for (key, _) in &upper {
            match self.legacy_state(key) {
                LegacyState::Attested { .. } | LegacyState::Released => {}
                blocker_state => {
                    return Err(CleanupRejection::DependencyNotDeletable {
                        key: requested.clone(),
                        blocker: key.clone(),
                        blocker_state,
                    })
                }
            }
        }
        let mut ids = Vec::new();
        for (key, _) in upper {
            if self.legacy_released(&key) {
                continue;
            }
            ids.push(self.ensure_cleanup_action(&key)?);
        }
        Ok(ids)
    }

    fn legacy_kind(&self, key: &ResourceKey) -> Option<ResourceKind> {
        self.plan
            .legacy_resources()
            .into_iter()
            .find(|(candidate, _)| candidate == key)
            .map(|(_, kind)| kind)
    }

    // ---- 补偿 -----------------------------------------------------------

    pub fn compensate(&mut self) -> Result<CompensationReport, EngineError> {
        match self.phase {
            Phase::TimeoutUnconfirmed => {
                return Err(EngineError::UnconfirmedOutcome {
                    action: self.first_unconfirmed(),
                })
            }
            Phase::Completed | Phase::Degraded | Phase::ManualReview => {
                return Err(EngineError::CompensationUnavailable(self.phase))
            }
            _ => {}
        }
        self.rollback_requested = true;
        self.compensation_started = true;
        self.phase = Phase::RollbackPending;

        let mut candidates: Vec<(ResourceKey, ResourceKind)> = Vec::new();
        for record in &self.resources {
            if record.op != self.plan.op_id {
                continue;
            }
            if record.released
                || record.conflicted
                || record.quarantined
                || !record.provision_confirmed
            {
                continue;
            }
            // 旧拓扑里本来就有这个键：本操作"成功"只说明名字能注册/能复用，
            // 不构成删除旧资源的授权。回滚只撤本操作新建的东西，旧资源要么保留
            // （同属性 = 仍在新拓扑使用），要么按同名争用阻断上报（compensation_status）。
            if self.legacy_kind(&record.key).is_some() {
                continue;
            }
            candidates.push((record.key.clone(), record.kind.clone()));
        }
        candidates.sort_by(|left, right| {
            right
                .1
                .layer()
                .cmp(&left.1.layer())
                .then_with(|| left.0.cmp(&right.0))
        });

        // 已有删除动作（含上次失败重试的）按层归位；只有真正缺失的才新建。
        // 重试复用同一条动作，避免旧失败记录把阶段永久卡死。
        let mut by_level: BTreeMap<(NodeId, u8), Vec<ActionId>> = BTreeMap::new();
        let mut missing: Vec<(ResourceKey, ResourceKind)> = Vec::new();
        for (key, kind) in candidates {
            match self.actions.iter().position(|action| {
                action.phase == Phase::RollbackPending && action.resource.as_ref() == Some(&key)
            }) {
                Some(index) => {
                    if matches!(self.actions[index].status, ActionStatus::Failed { .. }) {
                        self.actions[index].status = ActionStatus::Pending;
                    }
                    by_level
                        .entry((key.node.clone(), kind.layer()))
                        .or_default()
                        .push(self.actions[index].id);
                }
                None => missing.push((key, kind)),
            }
        }
        for (key, kind) in missing {
            let layer = kind.layer();
            // 依赖看同一节点上的所有更高层次删除动作，而不是全局同层：
            // 链/监听是节点本地对象，只查 layer+1 会漏掉"没有中间层"的直连引用，
            // 全局连边又会让别的节点的清理把这里卡住。
            let depends_on: Vec<ActionId> = by_level
                .iter()
                .filter(|((node, other), _)| node == &key.node && *other > layer)
                .flat_map(|(_, ids)| ids.iter().copied())
                .collect();
            let action_kind = delete_kind(&kind, &key);
            let id = self.push_action(
                key.node.clone(),
                Phase::RollbackPending,
                action_kind,
                Some(key.clone()),
                depends_on,
            );
            by_level.entry((key.node, layer)).or_default().push(id);
        }
        self.refresh_rollback();
        Ok(self.compensation_status())
    }

    pub fn compensation_status(&self) -> CompensationReport {
        let rollback: Vec<&Action> = self
            .actions
            .iter()
            .filter(|action| action.phase == Phase::RollbackPending)
            .collect();
        let mut started = Vec::new();
        let mut pending = Vec::new();
        let mut failed = Vec::new();
        for action in &rollback {
            match &action.status {
                ActionStatus::Confirmed { .. } => started.push(action.id),
                ActionStatus::Failed { .. } => failed.push(action.id),
                ActionStatus::Pending
                | ActionStatus::Dispatched { .. }
                | ActionStatus::Unconfirmed { .. } => pending.push(action.id),
                ActionStatus::Conflict { .. } | ActionStatus::Blocked { .. } => {}
            }
        }

        let mut blocked: Vec<BlockedCompensation> = Vec::new();
        let mut preserved: Vec<PreservedCompensation> = Vec::new();
        let mut not_created: Vec<ResourceKey> = Vec::new();
        for record in &self.resources {
            if record.op != self.plan.op_id || record.released {
                continue;
            }
            let action = rollback
                .iter()
                .find(|action| action.resource.as_ref() == Some(&record.key))
                .map(|action| action.id)
                .unwrap_or(ActionId(0));
            // 旧拓扑里本来就有这个键：回滚从不删它。同属性记"保留"，
            // 异属性记"同名争用"——两者都需要报告，绝不能静默当成已删除。
            if record.provision_confirmed {
                if let Some(legacy_kind) = self.legacy_kind(&record.key) {
                    if legacy_kind == record.kind {
                        preserved.push(PreservedCompensation {
                            resource: record.key.clone(),
                            kind: record.kind.clone(),
                            reason: PreserveReason::LegacyShared,
                        });
                    } else {
                        blocked.push(BlockedCompensation {
                            resource: record.key.clone(),
                            action,
                            reason: BlockReason::LegacyNameConflict,
                        });
                    }
                    continue;
                }
            }
            if record.provision_confirmed && !record.quarantined && !record.conflicted {
                continue;
            }
            if record.conflicted {
                blocked.push(BlockedCompensation {
                    resource: record.key.clone(),
                    action,
                    reason: BlockReason::ConflictUnproven,
                });
            } else if record.quarantined {
                blocked.push(BlockedCompensation {
                    resource: record.key.clone(),
                    action,
                    reason: BlockReason::OutcomeUnconfirmed,
                });
            } else {
                not_created.push(record.key.clone());
            }
        }

        preserved.sort_by(|left, right| left.resource.cmp(&right.resource));
        let complete = pending.is_empty() && failed.is_empty() && blocked.is_empty();
        CompensationReport {
            phase: self.phase,
            started,
            pending,
            failed,
            blocked,
            preserved,
            not_created,
            complete,
        }
    }

    fn refresh_rollback(&mut self) {
        if self.phase != Phase::RollbackPending {
            return;
        }
        if self.compensation_started && self.compensation_status().complete {
            self.phase = Phase::Degraded;
        }
    }

    fn first_unconfirmed(&self) -> Option<ActionId> {
        self.actions
            .iter()
            .find(|action| action.status.is_unconfirmed())
            .map(|action| action.id)
    }

    // ---- 结算 / 未知 ----------------------------------------------------

    pub fn settle_report(&self, phase: Phase) -> SettleReport {
        let mut report = SettleReport {
            pending: Vec::new(),
            failed: Vec::new(),
            unconfirmed: Vec::new(),
            conflicted: Vec::new(),
            blocked: Vec::new(),
        };
        for action in self.actions.iter().filter(|action| action.phase == phase) {
            match &action.status {
                ActionStatus::Pending | ActionStatus::Dispatched { .. } => {
                    report.pending.push(action.id)
                }
                ActionStatus::Failed { .. } => report.failed.push(action.id),
                ActionStatus::Unconfirmed { .. } => report.unconfirmed.push(action.id),
                ActionStatus::Conflict { .. } => report.conflicted.push(action.id),
                ActionStatus::Blocked { .. } => report.blocked.push(action.id),
                ActionStatus::Confirmed { .. } => {}
            }
        }
        report
    }

    pub fn unknown_results(&self) -> UnknownResults {
        let mut resources: BTreeSet<ResourceKey> = self
            .resources
            .iter()
            .filter(|record| record.quarantined && !record.released)
            .map(|record| record.key.clone())
            .collect();
        let new_keys: BTreeSet<ResourceKey> = self
            .plan
            .new_resources()
            .into_iter()
            .map(|(key, _)| key)
            .collect();
        let mut legacy_overlap: Vec<ResourceKey> = Vec::new();
        for key in &self.quarantined_legacy {
            if !self.legacy_released(key) {
                resources.insert(key.clone());
            }
            // 同名跨新旧拓扑：这次未决的结果既可能是旧资源仍在，也可能是本操作
            // 新注册的，归属未定。单列出来，不能被读成"本操作新建的未知资源"。
            if new_keys.contains(key) {
                legacy_overlap.push(key.clone());
            }
        }
        legacy_overlap.sort();
        UnknownResults {
            actions: self
                .actions
                .iter()
                .filter(|action| action.status.is_unconfirmed())
                .map(|action| action.id)
                .collect(),
            resources: resources.into_iter().collect(),
            legacy_overlap,
            gaps: self.gaps.clone(),
        }
    }

    // ---- 快照 -----------------------------------------------------------

    pub fn snapshot(&self) -> String {
        serde_json::to_string(self).expect("engine snapshot serialises")
    }

    pub fn restore(snapshot: &str) -> Result<Self, EngineError> {
        let engine: Engine =
            serde_json::from_str(snapshot).map_err(|err| EngineError::Snapshot(err.to_string()))?;
        engine.validate_snapshot()?;
        Ok(engine)
    }

    fn validate_snapshot(&self) -> Result<(), EngineError> {
        if self.schema != SCHEMA_VERSION {
            return Err(EngineError::Snapshot(format!(
                "快照 schema {} 与当前 {} 不符",
                self.schema, SCHEMA_VERSION
            )));
        }
        let max_id = self
            .actions
            .iter()
            .map(|action| action.id.0)
            .max()
            .unwrap_or(0);
        if self.next_action_id <= max_id {
            return Err(EngineError::Snapshot(
                "next_action_id 未超过已有动作 id，可能被篡改".to_string(),
            ));
        }
        let ids: BTreeSet<u64> = self.actions.iter().map(|action| action.id.0).collect();
        for action in &self.actions {
            if action.op != self.plan.op_id {
                return Err(EngineError::Snapshot(format!(
                    "动作 {} 属于别的操作 {}",
                    action.id.0, action.op
                )));
            }
            for dep in &action.depends_on {
                if *dep == action.id {
                    return Err(EngineError::Snapshot(format!(
                        "动作 {} 依赖自身",
                        action.id.0
                    )));
                }
                if !ids.contains(&dep.0) {
                    return Err(EngineError::Snapshot(format!(
                        "动作 {} 依赖不存在的动作 {}",
                        action.id.0, dep.0
                    )));
                }
                if dep.0 >= action.id.0 {
                    return Err(EngineError::Snapshot(format!(
                        "动作 {} 依赖了更晚生成的动作 {}",
                        action.id.0, dep.0
                    )));
                }
            }
        }
        if self.phase == Phase::TimeoutUnconfirmed {
            if self.resume_phase.is_none() {
                return Err(EngineError::Snapshot(
                    "TIMEOUT_UNCONFIRMED 缺少 resume_phase".to_string(),
                ));
            }
            if !self
                .actions
                .iter()
                .any(|action| action.status.is_unconfirmed())
            {
                return Err(EngineError::Snapshot(
                    "TIMEOUT_UNCONFIRMED 却没有未决动作".to_string(),
                ));
            }
        }
        // 归属证据只能挂在旧拓扑上：别的键的 attestation 是无意义的授权。
        let legacy_keys: BTreeSet<ResourceKey> = self
            .plan
            .legacy_resources()
            .into_iter()
            .map(|(key, _)| key)
            .collect();
        let new_keys: BTreeSet<ResourceKey> = self
            .plan
            .new_resources()
            .into_iter()
            .map(|(key, _)| key)
            .collect();
        for key in &self.released_legacy {
            if !legacy_keys.contains(key) {
                return Err(EngineError::Snapshot(format!(
                    "已释放的旧资源 {key} 不在旧拓扑里"
                )));
            }
        }
        for key in &self.quarantined_legacy {
            if !legacy_keys.contains(key) {
                return Err(EngineError::Snapshot(format!(
                    "被隔离的旧资源 {key} 不在旧拓扑里"
                )));
            }
        }
        for entry in &self.attestations {
            if !legacy_keys.contains(&entry.key) {
                return Err(EngineError::Snapshot(format!(
                    "归属证据 {} 指向的不是旧拓扑资源",
                    entry.key
                )));
            }
        }
        let mut keys: BTreeSet<&ResourceKey> = BTreeSet::new();
        for record in &self.resources {
            if !keys.insert(&record.key) {
                return Err(EngineError::Snapshot(format!(
                    "资源记录重复 {}",
                    record.key
                )));
            }
            // 台账只能记本计划里的资源：外来键意味着快照被换过计划或人为拼过。
            if !new_keys.contains(&record.key) && !legacy_keys.contains(&record.key) {
                return Err(EngineError::Snapshot(format!(
                    "资源记录 {} 不在本计划的新旧拓扑里",
                    record.key
                )));
            }
        }
        for applied in &self.applied {
            if !ids.contains(&applied.action.0) {
                return Err(EngineError::Snapshot(format!(
                    "回执台账指向不存在的动作 {}",
                    applied.action.0
                )));
            }
        }
        Ok(())
    }
}

/// ForwardSpec → beta 的 ForwardRow：cutover 的服务集合一律由 beta::plan_forward 算，
/// 引擎里不再维护第二套「服务名 × 端口 × 协议」的口径。
///
/// user_tunnel_id 在计划和 beta 库里都可能缺省，beta 生成服务名时用 unwrap_or(0)
/// （beta.rs forward_service_base），所以 0 归一成 None，避免同一事实两种表示。
fn beta_row(forward: &ForwardSpec) -> ForwardRow {
    ForwardRow::new(
        forward.forward_id.0,
        forward.user_id,
        Some(forward.user_tunnel_id).filter(|id| *id != 0),
        forward.status.as_beta(),
    )
    .with_protocols(forward.protocols.clone())
}

fn delete_kind(kind: &ResourceKind, key: &ResourceKey) -> ActionKind {
    match kind {
        ResourceKind::Chain { chain } => ActionKind::DeleteChains {
            chain: chain.clone(),
        },
        ResourceKind::Listener { listener, port } => ActionKind::DeleteListener {
            listener: listener.clone(),
            port: *port,
        },
        ResourceKind::Service {
            forward,
            port,
            proto,
        } => ActionKind::DeleteService {
            forward: *forward,
            service: key.name.clone(),
            port: *port,
            proto: *proto,
        },
    }
}
