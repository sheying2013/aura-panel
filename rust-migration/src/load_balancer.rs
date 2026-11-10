//! 高级负载均衡与会话粘滞调度引擎 (Advanced Load Balancing & Session Sticky Engine)
//!
//! 包含：
//! 1. 平滑加权轮询 (Smooth Weighted Round-Robin, Nginx SWRR 算法)
//! 2. 最小连接数优先 (Least Connections)
//! 3. 一致性哈希环 (Consistent Hashing with Virtual Nodes) 保证 IP/会话粘滞

use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq)]
pub struct BackendTarget {
    pub id: String,
    pub address: String,
    pub weight: u32,
    pub current_weight: i32,
    pub effective_weight: i32,
    pub active_connections: u32,
    pub healthy: bool,
}

impl BackendTarget {
    pub fn new(id: impl Into<String>, address: impl Into<String>, weight: u32) -> Self {
        Self {
            id: id.into(),
            address: address.into(),
            weight,
            current_weight: 0,
            effective_weight: weight as i32,
            active_connections: 0,
            healthy: true,
        }
    }
}

/// 负载均衡策略
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BalancingPolicy {
    /// 平滑加权轮询
    SmoothWeightedRoundRobin,
    /// 最小连接数
    LeastConnections,
    /// 一致性哈希 (会话粘滞)
    ConsistentHash,
}

/// 一致性哈希环
#[derive(Debug, Clone)]
pub struct ConsistentHashRing {
    /// 虚拟节点数
    pub virtual_nodes: u32,
    /// 环：hash_value -> target_id
    pub ring: BTreeMap<u32, String>,
}

impl ConsistentHashRing {
    pub fn new(virtual_nodes: u32) -> Self {
        Self {
            virtual_nodes,
            ring: BTreeMap::new(),
        }
    }

    /// FNV-1a 32-bit 哈希实现 (零外部依赖，极速)
    pub fn hash(key: &str) -> u32 {
        let mut h: u32 = 0x811c9dc5;
        for b in key.as_bytes() {
            h ^= *b as u32;
            h = h.wrapping_mul(0x01000193);
        }
        h
    }

    pub fn add_target(&mut self, target_id: &str) {
        for v in 0..self.virtual_nodes {
            let vkey = format!("{}-vnode-{}", target_id, v);
            let h = Self::hash(&vkey);
            self.ring.insert(h, target_id.to_string());
        }
    }

    pub fn remove_target(&mut self, target_id: &str) {
        for v in 0..self.virtual_nodes {
            let vkey = format!("{}-vnode-{}", target_id, v);
            let h = Self::hash(&vkey);
            self.ring.remove(&h);
        }
    }

    pub fn get_target(&self, key: &str) -> Option<&str> {
        if self.ring.is_empty() {
            return None;
        }

        let h = Self::hash(key);
        // 在环中寻找第一个 >= h 的节点；若到达尾部则环回取第一个
        if let Some((_, target_id)) = self.ring.range(h..).next() {
            Some(target_id.as_str())
        } else {
            self.ring.values().next().map(|s| s.as_str())
        }
    }
}

/// 综合调度器
#[derive(Debug, Clone)]
pub struct LoadBalancer {
    pub policy: BalancingPolicy,
    pub targets: Vec<BackendTarget>,
    pub hash_ring: ConsistentHashRing,
}

impl LoadBalancer {
    pub fn new(policy: BalancingPolicy) -> Self {
        Self {
            policy,
            targets: Vec::new(),
            hash_ring: ConsistentHashRing::new(64),
        }
    }

    pub fn add_target(&mut self, target: BackendTarget) {
        self.hash_ring.add_target(&target.id);
        self.targets.push(target);
    }

    pub fn set_target_health(&mut self, target_id: &str, healthy: bool) {
        if let Some(t) = self.targets.iter_mut().find(|t| t.id == target_id) {
            t.healthy = healthy;
        }
    }

    pub fn increment_connection(&mut self, target_id: &str) {
        if let Some(t) = self.targets.iter_mut().find(|t| t.id == target_id) {
            t.active_connections += 1;
        }
    }

    pub fn decrement_connection(&mut self, target_id: &str) {
        if let Some(t) = self.targets.iter_mut().find(|t| t.id == target_id) {
            if t.active_connections > 0 {
                t.active_connections -= 1;
            }
        }
    }

    /// 执行调度选择后端
    pub fn select_target(&mut self, session_key: Option<&str>) -> Option<BackendTarget> {
        let healthy_indices: Vec<usize> = self
            .targets
            .iter()
            .enumerate()
            .filter(|(_, t)| t.healthy && t.weight > 0)
            .map(|(i, _)| i)
            .collect();

        if healthy_indices.is_empty() {
            return None;
        }

        match self.policy {
            BalancingPolicy::SmoothWeightedRoundRobin => {
                let total_weight: i32 = healthy_indices
                    .iter()
                    .map(|&i| self.targets[i].effective_weight)
                    .sum();

                if total_weight <= 0 {
                    return Some(self.targets[healthy_indices[0]].clone());
                }

                let mut best_index = healthy_indices[0];
                let mut max_current = i32::MIN;

                for &idx in &healthy_indices {
                    let target = &mut self.targets[idx];
                    target.current_weight += target.effective_weight;
                    if target.current_weight > max_current {
                        max_current = target.current_weight;
                        best_index = idx;
                    }
                }

                self.targets[best_index].current_weight -= total_weight;
                Some(self.targets[best_index].clone())
            }

            BalancingPolicy::LeastConnections => {
                let best_index = *healthy_indices
                    .iter()
                    .min_by_key(|&&i| self.targets[i].active_connections)
                    .unwrap();

                Some(self.targets[best_index].clone())
            }

            BalancingPolicy::ConsistentHash => {
                let key = session_key.unwrap_or("default-session");
                if let Some(target_id) = self.hash_ring.get_target(key) {
                    if let Some(t) = self.targets.iter().find(|t| t.id == target_id && t.healthy) {
                        return Some(t.clone());
                    }
                }
                // 若所选目标不健康，降级选择第一个健康的
                Some(self.targets[healthy_indices[0]].clone())
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_smooth_weighted_round_robin() {
        let mut lb = LoadBalancer::new(BalancingPolicy::SmoothWeightedRoundRobin);
        // A(5), B(1), C(1) 总计权重 7
        lb.add_target(BackendTarget::new("A", "10.0.0.1:80", 5));
        lb.add_target(BackendTarget::new("B", "10.0.0.2:80", 1));
        lb.add_target(BackendTarget::new("C", "10.0.0.3:80", 1));

        let mut counts = std::collections::HashMap::new();
        // 调度 7 次，A 应当选中 5 次，B 1 次，C 1 次，且平滑分散
        for _ in 0..7 {
            let t = lb.select_target(None).expect("target should be selected");
            *counts.entry(t.id).or_insert(0) += 1;
        }

        assert_eq!(counts.get("A"), Some(&5));
        assert_eq!(counts.get("B"), Some(&1));
        assert_eq!(counts.get("C"), Some(&1));
    }

    #[test]
    fn test_least_connections_balancing() {
        let mut lb = LoadBalancer::new(BalancingPolicy::LeastConnections);
        lb.add_target(BackendTarget::new("A", "10.0.0.1:80", 1));
        lb.add_target(BackendTarget::new("B", "10.0.0.2:80", 1));

        // 初始都是 0 连接，任选一个
        let t1 = lb.select_target(None).unwrap();
        lb.increment_connection(&t1.id);

        // 现在被选中的连接数增加，下一次必须选另一个连接数为 0 的
        let t2 = lb.select_target(None).unwrap();
        assert_ne!(t1.id, t2.id);
        lb.increment_connection(&t2.id);

        // 连接数均为 1，释放 A 的连接
        lb.decrement_connection("A");
        // 下一次必然选 A
        let t3 = lb.select_target(None).unwrap();
        assert_eq!(t3.id, "A");
    }

    #[test]
    fn test_consistent_hashing_session_stickiness() {
        let mut lb = LoadBalancer::new(BalancingPolicy::ConsistentHash);
        lb.add_target(BackendTarget::new("Node1", "10.0.0.1:80", 1));
        lb.add_target(BackendTarget::new("Node2", "10.0.0.2:80", 1));
        lb.add_target(BackendTarget::new("Node3", "10.0.0.3:80", 1));

        let client_ip = "192.168.1.100";
        // 相同 Client IP 必须 100% 映射到相同节点
        let selected1 = lb.select_target(Some(client_ip)).unwrap();
        for _ in 0..10 {
            let selected2 = lb.select_target(Some(client_ip)).unwrap();
            assert_eq!(selected1.id, selected2.id);
        }
    }
}
