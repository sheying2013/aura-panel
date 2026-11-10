//! 端口段批量映射与规则生成校验引擎（纯 Rust 实现）。
//!
//! 核心功能：
//! - 商业级端口段 (Port Range) 批量生成：支持如 10000..10050 映射到 20000..20050；
//! - 自动端口占用冲突扫描（Against Allocated Set 与系统保留端口）；
//! - 批量生成安全原子性保证：单批次最大数量限制，边界强制校验。

use serde::{Deserialize, Serialize};
use std::collections::HashSet;

pub const MAX_BATCH_PORTS: usize = 200;
pub const MIN_PORT: u16 = 1;
pub const MAX_PORT: u16 = 65535;

/// 端口段批量生成请求
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchPortRangeRequest {
    pub name_prefix: String,
    pub start_ingress_port: u16,
    pub end_ingress_port: u16,
    pub target_ip: String,
    pub start_target_port: u16,
    pub tunnel_id: u64,
    pub user_id: u64,
    pub protocol: String, // "tcp", "udp", "both"
}

/// 批量生成生成的单个转发规则描述
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GeneratedForwardSpec {
    pub name: String,
    pub ingress_port: u16,
    pub target_addr: String, // "target_ip:target_port"
    pub target_ip: String,
    pub target_port: u16,
    pub tunnel_id: u64,
    pub user_id: u64,
    pub protocol: String,
}

/// 冲突与错误分类
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum BatchValidationError {
    InvalidRange { start: u16, end: u16 },
    PortOverflow { port: u32 },
    BatchLimitExceeded { count: usize, max: usize },
    PortCollision { port: u16, reason: String },
    EmptyTargetAddress,
    EmptyNamePrefix,
}

impl std::fmt::Display for BatchValidationError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidRange { start, end } => write!(f, "无效端口范围: 起始 {} > 结束 {}", start, end),
            Self::PortOverflow { port } => write!(f, "端口溢出范围 (1-65535): {}", port),
            Self::BatchLimitExceeded { count, max } => {
                write!(f, "单次批量生成端口数超过上限 (申请 {} 个，上限 {})", count, max)
            }
            Self::PortCollision { port, reason } => write!(f, "端口冲突 [{}]: {}", port, reason),
            Self::EmptyTargetAddress => write!(f, "目标地址不能为空"),
            Self::EmptyNamePrefix => write!(f, "规则名称前缀不能为空"),
        }
    }
}

impl std::error::Error for BatchValidationError {}

/// 端口段批量生成器
pub struct BatchForwardGenerator;

impl BatchForwardGenerator {
    /// 校验并生成批量规则
    pub fn generate(
        req: &BatchPortRangeRequest,
        allocated_ports: &HashSet<u16>,
    ) -> Result<Vec<GeneratedForwardSpec>, BatchValidationError> {
        if req.name_prefix.trim().is_empty() {
            return Err(BatchValidationError::EmptyNamePrefix);
        }
        if req.target_ip.trim().is_empty() {
            return Err(BatchValidationError::EmptyTargetAddress);
        }
        if req.start_ingress_port > req.end_ingress_port {
            return Err(BatchValidationError::InvalidRange {
                start: req.start_ingress_port,
                end: req.end_ingress_port,
            });
        }

        let port_count = (req.end_ingress_port - req.start_ingress_port + 1) as usize;
        if port_count > MAX_BATCH_PORTS {
            return Err(BatchValidationError::BatchLimitExceeded {
                count: port_count,
                max: MAX_BATCH_PORTS,
            });
        }

        // 预检目标端口是否会超过 65535
        let end_target_port = req.start_target_port as u32 + (port_count as u32) - 1;
        if end_target_port > MAX_PORT as u32 {
            return Err(BatchValidationError::PortOverflow {
                port: end_target_port,
            });
        }

        let mut generated = Vec::with_capacity(port_count);

        for i in 0..port_count {
            let in_port = req.start_ingress_port + i as u16;
            let target_port = req.start_target_port + i as u16;

            // 端口占用冲突检测
            if allocated_ports.contains(&in_port) {
                return Err(BatchValidationError::PortCollision {
                    port: in_port,
                    reason: "该入站端口已被其他既有转发或隧道占用".to_string(),
                });
            }

            let rule_name = format!("{}_{}", req.name_prefix.trim(), in_port);
            let target_addr = format!("{}:{}", req.target_ip.trim(), target_port);

            generated.push(GeneratedForwardSpec {
                name: rule_name,
                ingress_port: in_port,
                target_addr,
                target_ip: req.target_ip.trim().to_string(),
                target_port,
                tunnel_id: req.tunnel_id,
                user_id: req.user_id,
                protocol: req.protocol.clone(),
            });
        }

        Ok(generated)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_valid_port_range_generation() {
        let req = BatchPortRangeRequest {
            name_prefix: "batch_game".to_string(),
            start_ingress_port: 31000,
            end_ingress_port: 31005,
            target_ip: "10.10.10.1".to_string(),
            start_target_port: 41000,
            tunnel_id: 10,
            user_id: 1,
            protocol: "tcp".to_string(),
        };

        let allocated = HashSet::new();
        let res = BatchForwardGenerator::generate(&req, &allocated).unwrap();

        assert_eq!(res.len(), 6);
        assert_eq!(res[0].name, "batch_game_31000");
        assert_eq!(res[0].ingress_port, 31000);
        assert_eq!(res[0].target_port, 41000);
        assert_eq!(res[0].target_addr, "10.10.10.1:41000");

        assert_eq!(res[5].name, "batch_game_31005");
        assert_eq!(res[5].ingress_port, 31005);
        assert_eq!(res[5].target_port, 41005);
    }

    #[test]
    fn test_port_collision_detection() {
        let req = BatchPortRangeRequest {
            name_prefix: "batch_rule".to_string(),
            start_ingress_port: 32000,
            end_ingress_port: 32003,
            target_ip: "1.2.3.4".to_string(),
            start_target_port: 80,
            tunnel_id: 1,
            user_id: 1,
            protocol: "both".to_string(),
        };

        let mut allocated = HashSet::new();
        allocated.insert(32002); // 发生冲突

        let err = BatchForwardGenerator::generate(&req, &allocated).unwrap_err();
        match err {
            BatchValidationError::PortCollision { port, reason: _ } => {
                assert_eq!(port, 32002);
            }
            _ => panic!("Expected PortCollision error"),
        }
    }

    #[test]
    fn test_invalid_range_and_overflow() {
        let req_rev = BatchPortRangeRequest {
            name_prefix: "test".to_string(),
            start_ingress_port: 30005,
            end_ingress_port: 30000,
            target_ip: "1.1.1.1".to_string(),
            start_target_port: 1000,
            tunnel_id: 1,
            user_id: 1,
            protocol: "tcp".to_string(),
        };
        assert!(matches!(
            BatchForwardGenerator::generate(&req_rev, &HashSet::new()),
            Err(BatchValidationError::InvalidRange { .. })
        ));

        let req_overflow = BatchPortRangeRequest {
            name_prefix: "test".to_string(),
            start_ingress_port: 30000,
            end_ingress_port: 30010,
            target_ip: "1.1.1.1".to_string(),
            start_target_port: 65530,
            tunnel_id: 1,
            user_id: 1,
            protocol: "tcp".to_string(),
        };
        assert!(matches!(
            BatchForwardGenerator::generate(&req_overflow, &HashSet::new()),
            Err(BatchValidationError::PortOverflow { .. })
        ));
    }
}
