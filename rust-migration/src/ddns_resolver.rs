//! 动态域名 (DDNS) 目标解析与 IP 漂移自动重载引擎 (DDNS Target Resolver & Hot Reload)
//!
//! 解决家宽/动态出口场景下，目标 IP 发生变动导致中转链路静默断开的行业通病。
//! 实时监控域名 DNS 解析结果，检测 IP 集合漂移差异 (Diff)，生成热重载 (Zero-Downtime Hot Reload) 动作。

use std::collections::{BTreeSet, HashMap};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DdnsResolutionDiff {
    pub domain: String,
    pub is_changed: bool,
    pub old_ips: Vec<String>,
    pub new_ips: Vec<String>,
    pub added_ips: Vec<String>,
    pub removed_ips: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct TrackedDdnsDomain {
    pub domain: String,
    pub current_ips: BTreeSet<String>,
    pub last_resolved_timestamp_ms: u64,
    pub min_check_interval_ms: u64,
    pub associated_forward_ids: Vec<u64>,
}

impl TrackedDdnsDomain {
    pub fn new(domain: impl Into<String>, interval_ms: u64) -> Self {
        Self {
            domain: domain.into(),
            current_ips: BTreeSet::new(),
            last_resolved_timestamp_ms: 0,
            min_check_interval_ms: interval_ms,
            associated_forward_ids: Vec::new(),
        }
    }
}

/// DDNS 跟踪与热重载调度中心
#[derive(Debug, Clone, Default)]
pub struct DdnsTracker {
    pub domains: HashMap<String, TrackedDdnsDomain>,
}

impl DdnsTracker {
    pub fn new() -> Self {
        Self {
            domains: HashMap::new(),
        }
    }

    pub fn register_domain(
        &mut self,
        domain: impl Into<String>,
        forward_id: u64,
        check_interval_ms: u64,
    ) {
        let domain_str = domain.into();
        let entry = self
            .domains
            .entry(domain_str.clone())
            .or_insert_with(|| TrackedDdnsDomain::new(domain_str, check_interval_ms));

        if !entry.associated_forward_ids.contains(&forward_id) {
            entry.associated_forward_ids.push(forward_id);
        }
    }

    /// 评估域名解析的新 IP 列表并产出差异
    pub fn process_dns_update(
        &mut self,
        domain: &str,
        fresh_ips: &[String],
        now_ms: u64,
    ) -> Option<DdnsResolutionDiff> {
        let entry = self.domains.get_mut(domain)?;

        let new_set: BTreeSet<String> = fresh_ips.iter().cloned().collect();
        let old_set = &entry.current_ips;

        let added: Vec<String> = new_set.difference(old_set).cloned().collect();
        let removed: Vec<String> = old_set.difference(&new_set).cloned().collect();

        let is_changed = !added.is_empty() || !removed.is_empty();

        let diff = DdnsResolutionDiff {
            domain: domain.to_string(),
            is_changed,
            old_ips: old_set.iter().cloned().collect(),
            new_ips: fresh_ips.to_vec(),
            added_ips: added,
            removed_ips: removed,
        };

        entry.current_ips = new_set;
        entry.last_resolved_timestamp_ms = now_ms;

        Some(diff)
    }

    /// 查询受 IP 漂移影响需要热重载的转发规则 ID 列表
    pub fn get_affected_forwards(&self, domain: &str) -> Vec<u64> {
        self.domains
            .get(domain)
            .map(|d| d.associated_forward_ids.clone())
            .unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ddns_first_resolution() {
        let mut tracker = DdnsTracker::new();
        tracker.register_domain("home.example.com", 101, 30_000);

        let ips = vec!["1.2.3.4".to_string()];
        let diff = tracker
            .process_dns_update("home.example.com", &ips, 1000)
            .unwrap();

        assert!(diff.is_changed);
        assert_eq!(diff.added_ips, vec!["1.2.3.4".to_string()]);
        assert!(diff.removed_ips.is_empty());
        assert_eq!(tracker.get_affected_forwards("home.example.com"), vec![101]);
    }

    #[test]
    fn test_ddns_ip_drift_detection() {
        let mut tracker = DdnsTracker::new();
        tracker.register_domain("dyn.ddns.net", 202, 10_000);

        // 初始 IP
        tracker.process_dns_update("dyn.ddns.net", &["114.114.114.114".to_string()], 1000);

        // IP 未发生变化
        let diff_same = tracker
            .process_dns_update("dyn.ddns.net", &["114.114.114.114".to_string()], 2000)
            .unwrap();
        assert!(!diff_same.is_changed);
        assert!(diff_same.added_ips.is_empty());
        assert!(diff_same.removed_ips.is_empty());

        // 家宽重拨号，IP 变为 119.29.29.29
        let diff_changed = tracker
            .process_dns_update("dyn.ddns.net", &["119.29.29.29".to_string()], 3000)
            .unwrap();
        assert!(diff_changed.is_changed);
        assert_eq!(diff_changed.added_ips, vec!["119.29.29.29".to_string()]);
        assert_eq!(diff_changed.removed_ips, vec!["114.114.114.114".to_string()]);
    }
}
