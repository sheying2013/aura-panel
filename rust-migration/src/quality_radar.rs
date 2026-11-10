//! 链路网络质量雷达与综合评分引擎（纯 Rust 实现）。
//!
//! 核心设计：
//! - 多维度雷达评估：延迟评分 (Latency)、抖动评分 (Jitter)、丢包率评分 (PacketLoss)、可用性评分 (Availability)；
//! - 自动等级划分：S级 (电竞/实时语音)、A级 (4K流媒体/极速网页)、B级 (常规网页)、C级 (性能劣化)、D级 (不稳定/告警)；
//! - 产出雷达五维数据，直供前端雷达图 (Radar Chart) 与性能徽章渲染。

use serde::{Deserialize, Serialize};

/// 质量等级
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum QualityGrade {
    S, // 95 ~ 100 分 (电竞/超低时延)
    A, // 85 ~ 94 分 (4K流媒体/优质中转)
    B, // 70 ~ 84 分 (常规可用)
    C, // 50 ~ 69 分 (轻度劣化)
    D, // 0 ~ 49 分 (严重告警/不稳定)
}

impl QualityGrade {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::S => "S",
            Self::A => "A",
            Self::B => "B",
            Self::C => "C",
            Self::D => "D",
        }
    }
}

/// 四维雷达分项评分 (各 0.0 ~ 100.0)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RadarDimensions {
    pub latency_score: f64,
    pub jitter_score: f64,
    pub packet_loss_score: f64,
    pub availability_score: f64,
}

/// 质量雷达综合评估报告
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityRadarReport {
    pub entity_name: String,
    pub composite_score: f64,
    pub grade: QualityGrade,
    pub dimensions: RadarDimensions,
    pub suggested_use_case: String,
}

pub struct QualityRadar;

impl QualityRadar {
    /// 评估单条链路或节点的综合指标
    pub fn evaluate(
        entity_name: &str,
        ewma_rtt_ms: f64,
        jitter_ms: f64,
        packet_loss_percent: f64,
        success_rate_percent: f64,
    ) -> QualityRadarReport {
        // 1. 延迟评分：<= 25ms 满分，>= 300ms 降至 20 分
        let latency_score = if ewma_rtt_ms <= 25.0 {
            100.0
        } else if ewma_rtt_ms >= 400.0 {
            10.0
        } else {
            100.0 - ((ewma_rtt_ms - 25.0) / 375.0) * 90.0
        };

        // 2. 抖动评分：<= 2ms 满分，>= 50ms 降至 20 分
        let jitter_score = if jitter_ms <= 2.0 {
            100.0
        } else if jitter_ms >= 60.0 {
            15.0
        } else {
            100.0 - ((jitter_ms - 2.0) / 58.0) * 85.0
        };

        // 3. 丢包评分：0% 满分，>= 10% 降至 0 分
        let packet_loss_score = if packet_loss_percent <= 0.0 {
            100.0
        } else if packet_loss_percent >= 10.0 {
            0.0
        } else {
            100.0 - (packet_loss_percent / 10.0) * 100.0
        };

        // 4. 可用性评分：直接参考近端成功率
        let availability_score = success_rate_percent.clamp(0.0, 100.0);

        // 5. 综合加权得分：可用性 (35%) + 丢包 (30%) + 延迟 (25%) + 抖动 (10%)
        let composite_score = (availability_score * 0.35
            + packet_loss_score * 0.30
            + latency_score * 0.25
            + jitter_score * 0.10)
            .clamp(0.0, 100.0);

        let (grade, use_case) = if composite_score >= 95.0 {
            (QualityGrade::S, "极速电竞 / 实时语音 RTC 极品链路".to_string())
        } else if composite_score >= 85.0 {
            (QualityGrade::A, "4K 流媒体 / 极速下载与大吞吐中转".to_string())
        } else if composite_score >= 70.0 {
            (QualityGrade::B, "日常网页浏览与办公中转".to_string())
        } else if composite_score >= 50.0 {
            (QualityGrade::C, "网络轻度劣化，建议排查上游丢包".to_string())
        } else {
            (QualityGrade::D, "链路极不稳定或严重断流，触发熔断排查".to_string())
        };

        QualityRadarReport {
            entity_name: entity_name.to_string(),
            composite_score,
            grade,
            dimensions: RadarDimensions {
                latency_score,
                jitter_score,
                packet_loss_score,
                availability_score,
            },
            suggested_use_case: use_case,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_perfect_gaming_link() {
        let report = QualityRadar::evaluate("HK-VIP-Direct", 18.5, 1.2, 0.0, 100.0);
        assert_eq!(report.grade, QualityGrade::S);
        assert!(report.composite_score >= 95.0);
        assert!(report.suggested_use_case.contains("电竞"));
    }

    #[test]
    fn test_degraded_link() {
        let report = QualityRadar::evaluate("US-Cross-Ocean", 280.0, 35.0, 4.5, 96.0);
        assert!(report.composite_score < 80.0);
        assert!(report.grade == QualityGrade::B || report.grade == QualityGrade::C);
    }

    #[test]
    fn test_unstable_link() {
        let report = QualityRadar::evaluate("Down-Node", 500.0, 80.0, 25.0, 60.0);
        assert_eq!(report.grade, QualityGrade::D);
        assert!(report.composite_score < 50.0);
    }
}
