//! 多目标主动健康探活与故障转移决策引擎（纯逻辑与高性能状态机）。
//!
//! 核心设计：
//! - 支持 ActiveStandby（主备优先级）、LowestLatency（最低延迟优选）与 WeightedHealth（健康加权）；
//! - 引入 EWMA (指数加权移动平均) 与 Jitter 抖动计算，消除偶发网络毛刺；
//! - 采用滞后回差 (Hysteresis) 机制，必须连续满足 failure_threshold 或 recovery_threshold 才切换，严防频繁抖动震荡 (Flapping)；
//! - 当全部目标均宕机时，触发 AllDownAlert 并降级保留主备 fallback，提供可观测性审计。

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};

/// 目标节点标识，格式为 "IP:Port" 或 "Domain:Port"
pub type TargetId = String;

/// 节点健康状态
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum HealthState {
    /// 完全健康（延迟低、连续探测成功）
    Healthy,
    /// 亚健康/性能劣化（存在丢包或抖动，但仍可达）
    Degraded,
    /// 节点完全宕机或不可达
    Down,
    /// 刚加入或初始化探测中
    Probing,
}

/// 故障转移策略
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FailoverStrategy {
    /// 主备模式：优先路由到 Primary；当 Primary 失败数达到阈值切换至 Standby；Primary 连续恢复后优雅切回
    ActiveStandby,
    /// 最低延迟优选：在所有 Healthy/Degraded 目标中选择 EWMA 延迟最低者；包含滞后差容忍度防止震荡
    LowestLatency,
    /// 健康度加权：根据 (100 - 丢包率) * (1000 / (RTT + 10)) 综合健康度打分
    WeightedHealth,
}

/// 单个目标的网络指标监控集
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TargetMetrics {
    pub target: TargetId,
    pub state: HealthState,
    pub consecutive_failures: u32,
    pub consecutive_successes: u32,
    pub total_probes: u64,
    pub failed_probes: u64,
    pub ewma_rtt_ms: f64,
    pub jitter_ms: f64,
    pub packet_loss_percent: f64,
    pub last_probe_timestamp: u64,
    pub last_error: Option<String>,
    #[serde(skip)]
    pub rtt_window: VecDeque<u64>,
}

impl TargetMetrics {
    pub fn new(target: TargetId) -> Self {
        Self {
            target,
            state: HealthState::Probing,
            consecutive_failures: 0,
            consecutive_successes: 0,
            total_probes: 0,
            failed_probes: 0,
            ewma_rtt_ms: 0.0,
            jitter_ms: 0.0,
            packet_loss_percent: 0.0,
            last_probe_timestamp: 0,
            last_error: None,
            rtt_window: VecDeque::with_capacity(30),
        }
    }

    /// 记录一次单次探测样本
    pub fn record_sample(&mut self, success: bool, rtt_ms: Option<u64>, timestamp: u64, err: Option<String>) {
        self.total_probes += 1;
        self.last_probe_timestamp = timestamp;

        if success {
            self.consecutive_successes += 1;
            self.consecutive_failures = 0;
            self.last_error = None;

            if let Some(rtt) = rtt_ms {
                if self.rtt_window.len() >= 30 {
                    self.rtt_window.pop_front();
                }
                self.rtt_window.push_back(rtt);

                // 更新 EWMA：alpha = 0.2
                if self.ewma_rtt_ms <= 0.0 {
                    self.ewma_rtt_ms = rtt as f64;
                } else {
                    let diff = (rtt as f64 - self.ewma_rtt_ms).abs();
                    self.jitter_ms = 0.8 * self.jitter_ms + 0.2 * diff;
                    self.ewma_rtt_ms = 0.8 * self.ewma_rtt_ms + 0.2 * (rtt as f64);
                }
            }
        } else {
            self.failed_probes += 1;
            self.consecutive_failures += 1;
            self.consecutive_successes = 0;
            self.last_error = err;
        }

        // 计算滑动窗口/总体丢包率
        if self.total_probes > 0 {
            self.packet_loss_percent = (self.failed_probes as f64 / self.total_probes as f64) * 100.0;
        }
    }

    /// 综合健康打分 (0.0 ~ 100.0)
    pub fn compute_composite_score(&self) -> f64 {
        if self.state == HealthState::Down || self.consecutive_failures >= 3 {
            return 0.0;
        }
        let loss_penalty = (100.0 - self.packet_loss_percent).max(0.0);
        let rtt_factor = (500.0 / (self.ewma_rtt_ms + 10.0)).min(1.0);
        let jitter_penalty = (50.0 / (self.jitter_ms + 5.0)).min(1.0);
        (loss_penalty * 0.5 + rtt_factor * 30.0 + jitter_penalty * 20.0).clamp(0.0, 100.0)
    }
}

/// 故障转移规则定义
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FailoverRule {
    pub rule_id: String,
    pub name: String,
    pub primary_target: TargetId,
    pub standby_targets: Vec<TargetId>,
    pub strategy: FailoverStrategy,
    pub failure_threshold: u32,
    pub recovery_threshold: u32,
    pub latency_tolerance_ms: u64,
    pub current_active: TargetId,
    pub active_switches_count: u64,
}

impl FailoverRule {
    pub fn new(
        rule_id: String,
        name: String,
        primary_target: TargetId,
        standby_targets: Vec<TargetId>,
        strategy: FailoverStrategy,
    ) -> Self {
        let current_active = primary_target.clone();
        Self {
            rule_id,
            name,
            primary_target,
            standby_targets,
            strategy,
            failure_threshold: 3,
            recovery_threshold: 5,
            latency_tolerance_ms: 15, // 超过 15ms 差异才切换，防止网络微小抖动频繁变动
            current_active,
            active_switches_count: 0,
        }
    }

    pub fn all_targets(&self) -> Vec<TargetId> {
        let mut list = vec![self.primary_target.clone()];
        for s in &self.standby_targets {
            if !list.contains(s) {
                list.push(s.clone());
            }
        }
        list
    }
}

/// 决策动作类型
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum DecisionAction {
    /// 维持当前活动目标不变
    MaintainCurrent,
    /// 发生故障转移/故障回切
    SwitchTarget {
        from: TargetId,
        to: TargetId,
        reason: String,
    },
    /// 所有目标均不可用报警
    AllTargetsDownAlert {
        fallback_target: TargetId,
        message: String,
    },
}

/// 故障转移评估决策结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FailoverDecision {
    pub rule_id: String,
    pub action: DecisionAction,
    pub active_target: TargetId,
    pub target_states: HashMap<TargetId, HealthState>,
    pub timestamp: u64,
}

/// 故障转移引擎中控
#[derive(Debug, Default)]
pub struct FailoverEngine {
    rules: HashMap<String, FailoverRule>,
    metrics: HashMap<TargetId, TargetMetrics>,
}

impl FailoverEngine {
    pub fn new() -> Self {
        Self {
            rules: HashMap::new(),
            metrics: HashMap::new(),
        }
    }

    /// 注册或更新故障转移规则
    pub fn register_rule(&mut self, rule: FailoverRule) {
        for target in rule.all_targets() {
            self.metrics
                .entry(target.clone())
                .or_insert_with(|| TargetMetrics::new(target));
        }
        self.rules.insert(rule.rule_id.clone(), rule);
    }

    /// 录入探测结果并自动更新指标与状态
    pub fn record_probe(
        &mut self,
        target: &str,
        success: bool,
        rtt_ms: Option<u64>,
        timestamp: u64,
        err: Option<String>,
    ) {
        let metrics = self
            .metrics
            .entry(target.to_string())
            .or_insert_with(|| TargetMetrics::new(target.to_string()));

        metrics.record_sample(success, rtt_ms, timestamp, err);

        // 状态机演化：以连续成功/失败计数器为主，辅以滑动窗口指标
        if metrics.consecutive_failures >= 3 {
            metrics.state = HealthState::Down;
        } else if metrics.consecutive_failures > 0 {
            metrics.state = HealthState::Degraded;
        } else if metrics.consecutive_successes >= 5 {
            // 连续成功达到 5 次，无论历史丢包率，标记为恢复健康
            if metrics.ewma_rtt_ms > 400.0 {
                metrics.state = HealthState::Degraded;
            } else {
                metrics.state = HealthState::Healthy;
            }
        } else if metrics.consecutive_successes >= 2 {
            metrics.state = HealthState::Degraded;
        }
    }

    /// 针对特定规则进行健康度与故障切换决策评估
    pub fn evaluate_rule(&mut self, rule_id: &str, timestamp: u64) -> Option<FailoverDecision> {
        let rule = self.rules.get_mut(rule_id)?;

        let mut target_states = HashMap::new();
        for t in rule.all_targets() {
            let state = self
                .metrics
                .get(&t)
                .map(|m| m.state)
                .unwrap_or(HealthState::Probing);
            target_states.insert(t, state);
        }

        let primary_metric = self.metrics.get(&rule.primary_target).cloned();
        let current_metric = self.metrics.get(&rule.current_active).cloned();

        let decision = match rule.strategy {
            FailoverStrategy::ActiveStandby => {
                let primary_down = primary_metric
                    .as_ref()
                    .map(|m| m.state == HealthState::Down || m.consecutive_failures >= rule.failure_threshold)
                    .unwrap_or(false);

                let primary_healthy = primary_metric
                    .as_ref()
                    .map(|m| m.state == HealthState::Healthy && m.consecutive_successes >= rule.recovery_threshold)
                    .unwrap_or(false);

                if rule.current_active == rule.primary_target {
                    if primary_down {
                        // 寻找第一个健康的备用目标
                        let fallback = rule.standby_targets.iter().find(|s| {
                            self.metrics
                                .get(*s)
                                .map(|m| m.state != HealthState::Down)
                                .unwrap_or(false)
                        });

                        if let Some(target) = fallback {
                            let old = rule.current_active.clone();
                            rule.current_active = target.clone();
                            rule.active_switches_count += 1;
                            DecisionAction::SwitchTarget {
                                from: old,
                                to: target.clone(),
                                reason: format!("主目标断线或连续失败 {} 次，自动切换至备用节点", rule.failure_threshold),
                            }
                        } else {
                            DecisionAction::AllTargetsDownAlert {
                                fallback_target: rule.primary_target.clone(),
                                message: "警告：主目标与所有备用节点均不可达！".to_string(),
                            }
                        }
                    } else {
                        DecisionAction::MaintainCurrent
                    }
                } else {
                    // 当前处于备用目标上，检查主目标是否已充分恢复
                    if primary_healthy {
                        let old = rule.current_active.clone();
                        rule.current_active = rule.primary_target.clone();
                        rule.active_switches_count += 1;
                        DecisionAction::SwitchTarget {
                            from: old,
                            to: rule.primary_target.clone(),
                            reason: format!(
                                "主目标已连续恢复成功 {} 次，健康度达标，自动优雅回切 (Failback)",
                                rule.recovery_threshold
                            ),
                        }
                    } else {
                        // 主目标未恢复，检查当前备用是否也挂了
                        let current_down = current_metric
                            .as_ref()
                            .map(|m| m.state == HealthState::Down)
                            .unwrap_or(false);
                        if current_down {
                            // 尝试换下一个备用
                            let next_fallback = rule.standby_targets.iter().find(|s| {
                                *s != &rule.current_active
                                    && self
                                        .metrics
                                        .get(*s)
                                        .map(|m| m.state != HealthState::Down)
                                        .unwrap_or(false)
                            });

                            if let Some(target) = next_fallback {
                                let old = rule.current_active.clone();
                                rule.current_active = target.clone();
                                rule.active_switches_count += 1;
                                DecisionAction::SwitchTarget {
                                    from: old,
                                    to: target.clone(),
                                    reason: "备用节点宕机，顺位切换至次级备用节点".to_string(),
                                }
                            } else {
                                DecisionAction::AllTargetsDownAlert {
                                    fallback_target: rule.primary_target.clone(),
                                    message: "当前备用节点失效且无可替换备用节点！".to_string(),
                                }
                            }
                        } else {
                            DecisionAction::MaintainCurrent
                        }
                    }
                }
            }

            FailoverStrategy::LowestLatency => {
                // 找出可用目标中 EWMA 延迟最低的目标
                let mut candidates: Vec<(TargetId, f64)> = rule
                    .all_targets()
                    .into_iter()
                    .filter_map(|t| {
                        let m = self.metrics.get(&t)?;
                        if m.state != HealthState::Down && m.ewma_rtt_ms > 0.0 {
                            Some((t, m.ewma_rtt_ms))
                        } else {
                            None
                        }
                    })
                    .collect();

                candidates.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal));

                if let Some((best_target, best_rtt)) = candidates.first() {
                    let current_rtt = self
                        .metrics
                        .get(&rule.current_active)
                        .map(|m| m.ewma_rtt_ms)
                        .unwrap_or(9999.0);

                    // 滞后差：只有当新目标的延迟比当前显著低 (当前 - 新 >= tolerance) 时才切换
                    if best_target != &rule.current_active
                        && (current_rtt - best_rtt) >= (rule.latency_tolerance_ms as f64)
                    {
                        let old = rule.current_active.clone();
                        rule.current_active = best_target.clone();
                        rule.active_switches_count += 1;
                        DecisionAction::SwitchTarget {
                            from: old,
                            to: best_target.clone(),
                            reason: format!(
                                "发现更优延迟链路 (当前 {:.1}ms -> 新节点 {:.1}ms, 差距超出阈值 {}ms)",
                                current_rtt, best_rtt, rule.latency_tolerance_ms
                            ),
                        }
                    } else {
                        DecisionAction::MaintainCurrent
                    }
                } else {
                    DecisionAction::AllTargetsDownAlert {
                        fallback_target: rule.primary_target.clone(),
                        message: "所有可用目标的延迟数据不可达或离线！".to_string(),
                    }
                }
            }

            FailoverStrategy::WeightedHealth => {
                // 加权健康优选
                let mut scored: Vec<(TargetId, f64)> = rule
                    .all_targets()
                    .into_iter()
                    .map(|t| {
                        let score = self
                            .metrics
                            .get(&t)
                            .map(|m| m.compute_composite_score())
                            .unwrap_or(0.0);
                        (t, score)
                    })
                    .collect();

                scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));

                if let Some((best_target, best_score)) = scored.first() {
                    if *best_score <= 0.0 {
                        DecisionAction::AllTargetsDownAlert {
                            fallback_target: rule.primary_target.clone(),
                            message: "所有目标健康度评分归零！".to_string(),
                        }
                    } else if best_target != &rule.current_active {
                        let old = rule.current_active.clone();
                        rule.current_active = best_target.clone();
                        rule.active_switches_count += 1;
                        DecisionAction::SwitchTarget {
                            from: old,
                            to: best_target.clone(),
                            reason: format!("加权综合健康度重新评估，选择最高评分目标 ({:.1}分)", best_score),
                        }
                    } else {
                        DecisionAction::MaintainCurrent
                    }
                } else {
                    DecisionAction::MaintainCurrent
                }
            }
        };

        Some(FailoverDecision {
            rule_id: rule_id.to_string(),
            action: decision,
            active_target: rule.current_active.clone(),
            target_states,
            timestamp,
        })
    }

    pub fn get_metrics(&self, target: &str) -> Option<&TargetMetrics> {
        self.metrics.get(target)
    }

    pub fn get_rule(&self, rule_id: &str) -> Option<&FailoverRule> {
        self.rules.get(rule_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_active_standby_failover_and_failback() {
        let mut engine = FailoverEngine::new();
        let rule = FailoverRule::new(
            "rule-1".to_string(),
            "BGP-HK-VIP".to_string(),
            "1.1.1.1:443".to_string(),
            vec!["2.2.2.2:443".to_string(), "3.3.3.3:443".to_string()],
            FailoverStrategy::ActiveStandby,
        );
        engine.register_rule(rule);

        // 初始状态，维持主目标
        let d0 = engine.evaluate_rule("rule-1", 1000).unwrap();
        assert_eq!(d0.action, DecisionAction::MaintainCurrent);
        assert_eq!(d0.active_target, "1.1.1.1:443");

        // 主目标探测 3 次失败
        engine.record_probe("1.1.1.1:443", false, None, 1001, Some("Timeout".to_string()));
        engine.record_probe("1.1.1.1:443", false, None, 1002, Some("Timeout".to_string()));
        engine.record_probe("1.1.1.1:443", false, None, 1003, Some("Timeout".to_string()));

        // 备用目标处于存活状态
        engine.record_probe("2.2.2.2:443", true, Some(45), 1003, None);
        engine.record_probe("2.2.2.2:443", true, Some(46), 1004, None);
        engine.record_probe("2.2.2.2:443", true, Some(45), 1005, None);

        // 触发故障转移至 2.2.2.2
        let d1 = engine.evaluate_rule("rule-1", 1005).unwrap();
        match d1.action {
            DecisionAction::SwitchTarget { from, to, reason: _ } => {
                assert_eq!(from, "1.1.1.1:443");
                assert_eq!(to, "2.2.2.2:443");
            }
            _ => panic!("Expected SwitchTarget action"),
        }
        assert_eq!(d1.active_target, "2.2.2.2:443");

        // 主目标开始恢复，但未达到 recovery_threshold (5 次)
        for i in 0..4 {
            engine.record_probe("1.1.1.1:443", true, Some(30), 2000 + i, None);
            let d_mid = engine.evaluate_rule("rule-1", 2000 + i).unwrap();
            assert_eq!(d_mid.action, DecisionAction::MaintainCurrent);
            assert_eq!(d_mid.active_target, "2.2.2.2:443");
        }

        // 第 5 次恢复成功，满足 recovery_threshold，触发优雅切回 (Failback)
        engine.record_probe("1.1.1.1:443", true, Some(28), 2005, None);
        let d_back = engine.evaluate_rule("rule-1", 2005).unwrap();
        match d_back.action {
            DecisionAction::SwitchTarget { from, to, reason: _ } => {
                assert_eq!(from, "2.2.2.2:443");
                assert_eq!(to, "1.1.1.1:443");
            }
            _ => panic!("Expected SwitchTarget action back to primary"),
        }
        assert_eq!(d_back.active_target, "1.1.1.1:443");
    }

    #[test]
    fn test_lowest_latency_switching_with_hysteresis() {
        let mut engine = FailoverEngine::new();
        let mut rule = FailoverRule::new(
            "rule-latency".to_string(),
            "Anycast-Lowest-Latency".to_string(),
            "10.0.0.1:80".to_string(),
            vec!["10.0.0.2:80".to_string()],
            FailoverStrategy::LowestLatency,
        );
        rule.latency_tolerance_ms = 20;
        engine.register_rule(rule);

        // 目标1 延迟 100ms
        for i in 0..5 {
            engine.record_probe("10.0.0.1:80", true, Some(100), 1000 + i, None);
        }

        // 目标2 延迟 90ms（差距 10ms < 20ms 容忍度，不发生切换）
        for i in 0..5 {
            engine.record_probe("10.0.0.2:80", true, Some(90), 1000 + i, None);
        }

        let d1 = engine.evaluate_rule("rule-latency", 2000).unwrap();
        assert_eq!(d1.action, DecisionAction::MaintainCurrent);
        assert_eq!(d1.active_target, "10.0.0.1:80");

        // 目标2 优化至 50ms（差距 50ms > 20ms，触发切换）
        for i in 0..5 {
            engine.record_probe("10.0.0.2:80", true, Some(50), 3000 + i, None);
        }

        let d2 = engine.evaluate_rule("rule-latency", 3005).unwrap();
        match d2.action {
            DecisionAction::SwitchTarget { from, to, reason: _ } => {
                assert_eq!(from, "10.0.0.1:80");
                assert_eq!(to, "10.0.0.2:80");
            }
            _ => panic!("Expected SwitchTarget due to lower latency"),
        }
        assert_eq!(d2.active_target, "10.0.0.2:80");
    }

    #[test]
    fn test_all_targets_down_alert() {
        let mut engine = FailoverEngine::new();
        let rule = FailoverRule::new(
            "rule-disaster".to_string(),
            "All-Down-Test".to_string(),
            "1.1.1.1:80".to_string(),
            vec!["2.2.2.2:80".to_string()],
            FailoverStrategy::ActiveStandby,
        );
        engine.register_rule(rule);

        // 全部连续失败 3 次
        for i in 0..3 {
            engine.record_probe("1.1.1.1:80", false, None, 1000 + i, Some("Refused".to_string()));
            engine.record_probe("2.2.2.2:80", false, None, 1000 + i, Some("Refused".to_string()));
        }

        let d = engine.evaluate_rule("rule-disaster", 2000).unwrap();
        match d.action {
            DecisionAction::AllTargetsDownAlert { fallback_target, message: _ } => {
                assert_eq!(fallback_target, "1.1.1.1:80");
            }
            _ => panic!("Expected AllTargetsDownAlert"),
        }
    }
}
