//! 多级自愈熔断器与级联雪崩防御引擎 (Circuit Breaker & Cascade Defense Engine)
//!
//! 经典三态有限状态机：
//! - Closed (正常放行): 监控调用失败率与连续失败数；
//! - Open (熔断阻断): 拒绝请求快速失败，启动冷却时间窗口；
//! - Half-Open (半开探活): 放行指定配额的试探请求，连续成功则自愈闭合，任一失败立即回退熔断。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BreakerState {
    Closed,
    Open,
    HalfOpen,
}

#[derive(Debug, Clone)]
pub struct BreakerConfig {
    /// 触发熔断的连续失败次数阈值
    pub failure_threshold: u32,
    /// 熔断冷却持续时间 (毫秒)，超时后进入半开
    pub open_cooldown_ms: u64,
    /// 半开探活需要连续成功的次数 (达标后闭合)
    pub half_open_success_threshold: u32,
}

impl Default for BreakerConfig {
    fn default() -> Self {
        Self {
            failure_threshold: 5,
            open_cooldown_ms: 10_000,
            half_open_success_threshold: 3,
        }
    }
}

#[derive(Debug, Clone)]
pub struct CircuitBreaker {
    pub name: String,
    pub config: BreakerConfig,
    pub state: BreakerState,
    pub consecutive_failures: u32,
    pub consecutive_successes: u32,
    pub last_state_change_ms: u64,
    pub total_trips: u32,
}

impl CircuitBreaker {
    pub fn new(name: impl Into<String>, config: BreakerConfig, now_ms: u64) -> Self {
        Self {
            name: name.into(),
            config,
            state: BreakerState::Closed,
            consecutive_failures: 0,
            consecutive_successes: 0,
            last_state_change_ms: now_ms,
            total_trips: 0,
        }
    }

    /// 获取当前生效状态 (自动处理 Open -> HalfOpen 冷却超时)
    pub fn current_state(&mut self, now_ms: u64) -> BreakerState {
        if self.state == BreakerState::Open {
            if now_ms >= self.last_state_change_ms + self.config.open_cooldown_ms {
                self.state = BreakerState::HalfOpen;
                self.consecutive_successes = 0;
                self.last_state_change_ms = now_ms;
            }
        }
        self.state
    }

    /// 判断当前是否允许请求通过
    pub fn can_execute(&mut self, now_ms: u64) -> bool {
        match self.current_state(now_ms) {
            BreakerState::Closed => true,
            BreakerState::HalfOpen => true,
            BreakerState::Open => false,
        }
    }

    /// 记录调用成功事件
    pub fn record_success(&mut self, now_ms: u64) {
        match self.current_state(now_ms) {
            BreakerState::Closed => {
                self.consecutive_failures = 0;
            }
            BreakerState::HalfOpen => {
                self.consecutive_successes += 1;
                if self.consecutive_successes >= self.config.half_open_success_threshold {
                    // 自愈恢复闭合
                    self.state = BreakerState::Closed;
                    self.consecutive_failures = 0;
                    self.consecutive_successes = 0;
                    self.last_state_change_ms = now_ms;
                }
            }
            BreakerState::Open => {}
        }
    }

    /// 记录调用失败事件
    pub fn record_failure(&mut self, now_ms: u64) {
        match self.current_state(now_ms) {
            BreakerState::Closed => {
                self.consecutive_failures += 1;
                if self.consecutive_failures >= self.config.failure_threshold {
                    // 触发熔断
                    self.state = BreakerState::Open;
                    self.total_trips += 1;
                    self.last_state_change_ms = now_ms;
                }
            }
            BreakerState::HalfOpen => {
                // 半开阶段一旦失败，立即重新打回熔断
                self.state = BreakerState::Open;
                self.total_trips += 1;
                self.consecutive_successes = 0;
                self.last_state_change_ms = now_ms;
            }
            BreakerState::Open => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_circuit_breaker_trip_and_cooldown() {
        let config = BreakerConfig {
            failure_threshold: 3,
            open_cooldown_ms: 5000,
            half_open_success_threshold: 2,
        };
        let mut cb = CircuitBreaker::new("target-node-1", config, 0);

        // 初始 Closed
        assert_eq!(cb.current_state(0), BreakerState::Closed);
        assert!(cb.can_execute(0));

        // 记录 2 次失败，仍然 Closed
        cb.record_failure(100);
        cb.record_failure(200);
        assert_eq!(cb.current_state(300), BreakerState::Closed);

        // 第 3 次失败，触发熔断 Open
        cb.record_failure(400);
        assert_eq!(cb.current_state(400), BreakerState::Open);
        assert!(!cb.can_execute(400));
        assert_eq!(cb.total_trips, 1);

        // 冷却期内 (比如 3000ms)，保持 Open
        assert_eq!(cb.current_state(3400), BreakerState::Open);
        assert!(!cb.can_execute(3400));

        // 冷却期满 (超过 5000ms，例如 6000ms)，转入 HalfOpen
        assert_eq!(cb.current_state(6000), BreakerState::HalfOpen);
        assert!(cb.can_execute(6000));
    }

    #[test]
    fn test_half_open_recovery_to_closed() {
        let config = BreakerConfig {
            failure_threshold: 2,
            open_cooldown_ms: 1000,
            half_open_success_threshold: 2,
        };
        let mut cb = CircuitBreaker::new("target-node-2", config, 0);

        // 触发熔断
        cb.record_failure(10);
        cb.record_failure(20);
        assert_eq!(cb.current_state(30), BreakerState::Open);

        // 超时进入半开
        assert_eq!(cb.current_state(1100), BreakerState::HalfOpen);

        // 第 1 次成功
        cb.record_success(1200);
        assert_eq!(cb.current_state(1200), BreakerState::HalfOpen);

        // 第 2 次成功，达标，闭合恢复！
        cb.record_success(1300);
        assert_eq!(cb.current_state(1300), BreakerState::Closed);
        assert!(cb.can_execute(1300));
    }

    #[test]
    fn test_half_open_failure_re_trip() {
        let config = BreakerConfig {
            failure_threshold: 2,
            open_cooldown_ms: 1000,
            half_open_success_threshold: 2,
        };
        let mut cb = CircuitBreaker::new("target-node-3", config, 0);

        // 触发熔断
        cb.record_failure(10);
        cb.record_failure(20);

        // 冷却转入半开
        assert_eq!(cb.current_state(1100), BreakerState::HalfOpen);

        // 半开期间遇到失败，立即打回 Open！
        cb.record_failure(1200);
        assert_eq!(cb.current_state(1200), BreakerState::Open);
        assert!(!cb.can_execute(1200));
        assert_eq!(cb.total_trips, 2);
    }
}
