//! 高性能流量整形与令牌桶速率控制引擎 (Traffic Shaping & Token Bucket Engine)
//!
//! 提供纳秒/微秒级令牌生成、上行/下行双向精准限速、突发流量缓冲 (Burst Allowance)
//! 以及峰谷时段动态倍率调度。

#[derive(Debug, Clone, PartialEq)]
pub struct PeakSchedule {
    /// 峰时段起始小时 (0-23)
    pub peak_start_hour: u8,
    /// 峰时段结束小时 (0-23)
    pub peak_end_hour: u8,
    /// 峰时段速率倍率 (例如 0.8 表示高峰限速到 80%)
    pub peak_multiplier: f64,
    /// 谷时段速率倍率 (例如 1.5 表示夜间闲时提速到 150%)
    pub off_peak_multiplier: f64,
}

impl Default for PeakSchedule {
    fn default() -> Self {
        Self {
            peak_start_hour: 20,
            peak_end_hour: 23,
            peak_multiplier: 1.0,
            off_peak_multiplier: 1.0,
        }
    }
}

impl PeakSchedule {
    pub fn get_multiplier(&self, current_hour: u8) -> f64 {
        if self.peak_start_hour <= self.peak_end_hour {
            if current_hour >= self.peak_start_hour && current_hour < self.peak_end_hour {
                self.peak_multiplier
            } else {
                self.off_peak_multiplier
            }
        } else {
            // 跨天情况 (如 22:00 到次日 02:00)
            if current_hour >= self.peak_start_hour || current_hour < self.peak_end_hour {
                self.peak_multiplier
            } else {
                self.off_peak_multiplier
            }
        }
    }
}

/// 令牌桶限速器
#[derive(Debug, Clone)]
pub struct TokenBucket {
    /// 基础速率 (字节/秒)
    pub base_rate_bytes_per_sec: u64,
    /// 最大突发容量 (字节)
    pub burst_capacity_bytes: u64,
    /// 当前桶内可用令牌 (字节)
    pub current_tokens: f64,
    /// 上次填充时间戳 (毫秒)
    pub last_fill_timestamp_ms: u64,
    /// 峰谷调度规则
    pub schedule: Option<PeakSchedule>,
}

impl TokenBucket {
    pub fn new(rate_bytes_per_sec: u64, burst_bytes: u64, now_ms: u64) -> Self {
        Self {
            base_rate_bytes_per_sec: rate_bytes_per_sec,
            burst_capacity_bytes: burst_bytes,
            current_tokens: burst_bytes as f64,
            last_fill_timestamp_ms: now_ms,
            schedule: None,
        }
    }

    pub fn with_schedule(mut self, schedule: PeakSchedule) -> Self {
        self.schedule = Some(schedule);
        self
    }

    /// 根据经过时间和当前小时刷新桶内令牌
    pub fn replenish(&mut self, now_ms: u64, current_hour: u8) {
        if now_ms <= self.last_fill_timestamp_ms {
            return;
        }

        let elapsed_ms = now_ms - self.last_fill_timestamp_ms;
        let multiplier = self
            .schedule
            .as_ref()
            .map(|s| s.get_multiplier(current_hour))
            .unwrap_or(1.0);

        let effective_rate = (self.base_rate_bytes_per_sec as f64) * multiplier;
        let generated_tokens = (effective_rate * elapsed_ms as f64) / 1000.0;

        self.current_tokens = (self.current_tokens + generated_tokens)
            .min(self.burst_capacity_bytes as f64);
        self.last_fill_timestamp_ms = now_ms;
    }

    /// 尝试消耗指定字节的令牌
    pub fn try_consume(&mut self, bytes: u64, now_ms: u64, current_hour: u8) -> bool {
        self.replenish(now_ms, current_hour);

        if self.current_tokens >= bytes as f64 {
            self.current_tokens -= bytes as f64;
            true
        } else {
            false
        }
    }

    /// 计算需要等待多久才有足够令牌 (毫秒)
    pub fn wait_time_ms(&mut self, bytes: u64, now_ms: u64, current_hour: u8) -> u64 {
        self.replenish(now_ms, current_hour);

        if self.current_tokens >= bytes as f64 {
            return 0;
        }

        let deficit = (bytes as f64) - self.current_tokens;
        let multiplier = self
            .schedule
            .as_ref()
            .map(|s| s.get_multiplier(current_hour))
            .unwrap_or(1.0);

        let effective_rate = (self.base_rate_bytes_per_sec as f64) * multiplier;
        if effective_rate <= 0.0 {
            return u64::MAX;
        }

        ((deficit * 1000.0) / effective_rate).ceil() as u64
    }
}

/// 双向流量整形管理器 (Bidirectional Traffic Shaper)
#[derive(Debug, Clone)]
pub struct BidirectionalShaper {
    pub ingress_bucket: TokenBucket,
    pub egress_bucket: TokenBucket,
    pub total_rx_bytes: u64,
    pub total_tx_bytes: u64,
    pub dropped_rx_packets: u64,
    pub dropped_tx_packets: u64,
}

impl BidirectionalShaper {
    pub fn new(rx_rate: u64, rx_burst: u64, tx_rate: u64, tx_burst: u64, now_ms: u64) -> Self {
        Self {
            ingress_bucket: TokenBucket::new(rx_rate, rx_burst, now_ms),
            egress_bucket: TokenBucket::new(tx_rate, tx_burst, now_ms),
            total_rx_bytes: 0,
            total_tx_bytes: 0,
            dropped_rx_packets: 0,
            dropped_tx_packets: 0,
        }
    }

    pub fn check_ingress(&mut self, bytes: u64, now_ms: u64, hour: u8) -> bool {
        if self.ingress_bucket.try_consume(bytes, now_ms, hour) {
            self.total_rx_bytes += bytes;
            true
        } else {
            self.dropped_rx_packets += 1;
            false
        }
    }

    pub fn check_egress(&mut self, bytes: u64, now_ms: u64, hour: u8) -> bool {
        if self.egress_bucket.try_consume(bytes, now_ms, hour) {
            self.total_tx_bytes += bytes;
            true
        } else {
            self.dropped_tx_packets += 1;
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_token_bucket_replenish_and_consume() {
        // 速率 1000 字节/秒，突发 2000 字节
        let mut bucket = TokenBucket::new(1000, 2000, 0);

        // 初始装满 2000
        assert!(bucket.try_consume(1500, 0, 12));
        assert_eq!(bucket.current_tokens as u64, 500);

        // 再请求 800，当前只有 500，应当拒绝
        assert!(!bucket.try_consume(800, 0, 12));

        // 经过 500ms，按 1000B/s 增加 500 令牌，当前应为 1000
        assert!(bucket.try_consume(800, 500, 12));
        assert_eq!(bucket.current_tokens as u64, 200);
    }

    #[test]
    fn test_peak_schedule_multiplier() {
        let schedule = PeakSchedule {
            peak_start_hour: 20,
            peak_end_hour: 23,
            peak_multiplier: 0.5,
            off_peak_multiplier: 2.0,
        };

        // 21 点处于高峰，0.5 倍
        assert_eq!(schedule.get_multiplier(21), 0.5);
        // 14 点处于平时，2.0 倍
        assert_eq!(schedule.get_multiplier(14), 2.0);

        let mut bucket = TokenBucket::new(1000, 5000, 0).with_schedule(schedule);
        bucket.current_tokens = 0.0;

        // 在 21 点（高峰期 0.5 倍）经过 1000ms，生成 500 令牌
        bucket.replenish(1000, 21);
        assert_eq!(bucket.current_tokens as u64, 500);

        // 在 14 点（闲时 2.0 倍）经过 1000ms (到 2000ms)，生成 2000 令牌，总计 2500
        bucket.replenish(2000, 14);
        assert_eq!(bucket.current_tokens as u64, 2500);
    }

    #[test]
    fn test_bidirectional_shaper() {
        let mut shaper = BidirectionalShaper::new(10000, 10000, 5000, 5000, 0);

        // Ingress 1000B
        assert!(shaper.check_ingress(1000, 0, 10));
        assert_eq!(shaper.total_rx_bytes, 1000);
        assert_eq!(shaper.dropped_rx_packets, 0);

        // Egress 4000B
        assert!(shaper.check_egress(4000, 0, 10));
        assert_eq!(shaper.total_tx_bytes, 4000);

        // Egress 再超限 2000B (只剩 1000B)
        assert!(!shaper.check_egress(2000, 0, 10));
        assert_eq!(shaper.dropped_tx_packets, 1);
    }
}
