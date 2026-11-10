//! 安全访问控制、私网拦截与协议特征审计引擎 (ACL & Traffic Security Engine)
//!
//! 关键防御能力：
//! 1. RFC 1918 / 环回 / 链路本地私网 IP 探测穿透拦截 (防止内网渗透扫描)
//! 2. 高危端口拦截 (SMTP 25、SMB 445、NetBIOS 135-139 等)
//! 3. 用户/隧道自定义 CIDR 白名单与黑名单精确匹配
//! 4. TLS ClientHello SNI 特征解析与分流提取

use std::net::Ipv4Addr;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AclVerdict {
    Allow,
    Block(String),
}

/// 常见高危封禁端口
pub const DEFAULT_BLOCKED_PORTS: &[u16] = &[
    25,   // SMTP 垃圾邮件防封
    135,  // NetBIOS
    137,  // NetBIOS-NS
    138,  // NetBIOS-DGM
    139,  // NetBIOS-SSN
    445,  // SMB 勒索病毒防扩散
];

#[derive(Debug, Clone)]
pub struct Ipv4Cidr {
    pub ip: Ipv4Addr,
    pub prefix_len: u8,
}

impl Ipv4Cidr {
    pub fn parse(cidr_str: &str) -> Option<Self> {
        let parts: Vec<&str> = cidr_str.trim().split('/').collect();
        if parts.len() != 2 {
            return None;
        }

        let ip = parts[0].parse::<Ipv4Addr>().ok()?;
        let prefix_len = parts[1].parse::<u8>().ok()?;
        if prefix_len > 32 {
            return None;
        }

        Some(Self { ip, prefix_len })
    }

    pub fn contains(&self, target: Ipv4Addr) -> bool {
        if self.prefix_len == 0 {
            return true;
        }
        let mask = !((1u32 << (32 - self.prefix_len)) - 1);
        let net_a = u32::from(self.ip) & mask;
        let net_b = u32::from(target) & mask;
        net_a == net_b
    }
}

/// 访问控制规则集
#[derive(Debug, Clone)]
pub struct AclRuleSet {
    /// 是否禁止将流量转发至局域网/内网私有地址
    pub block_private_ips: bool,
    /// 额外封禁的端口集合
    pub blocked_ports: Vec<u16>,
    /// CIDR 白名单 (若非空，仅允许白名单)
    pub whitelist_cidrs: Vec<Ipv4Cidr>,
    /// CIDR 黑名单 (命中即阻断)
    pub blacklist_cidrs: Vec<Ipv4Cidr>,
}

impl Default for AclRuleSet {
    fn default() -> Self {
        Self {
            block_private_ips: true,
            blocked_ports: DEFAULT_BLOCKED_PORTS.to_vec(),
            whitelist_cidrs: Vec::new(),
            blacklist_cidrs: Vec::new(),
        }
    }
}

impl AclRuleSet {
    /// 判断是否为私网/回环保留地址
    pub fn is_private_or_loopback(ip: Ipv4Addr) -> bool {
        let octets = ip.octets();
        // 127.0.0.0/8 (环回)
        if octets[0] == 127 {
            return true;
        }
        // 10.0.0.0/8 (Class A)
        if octets[0] == 10 {
            return true;
        }
        // 172.16.0.0/12 (Class B: 172.16 - 172.31)
        if octets[0] == 172 && (octets[1] >= 16 && octets[1] <= 31) {
            return true;
        }
        // 192.168.0.0/16 (Class C)
        if octets[0] == 192 && octets[1] == 168 {
            return true;
        }
        // 169.254.0.0/16 (链路本地 Link-Local)
        if octets[0] == 169 && octets[1] == 254 {
            return true;
        }
        // 0.0.0.0/8
        if octets[0] == 0 {
            return true;
        }
        false
    }

    /// 执行访问控制检查
    pub fn check(&self, target_ip_str: &str, target_port: u16) -> AclVerdict {
        // 端口封禁检查
        if self.blocked_ports.contains(&target_port) {
            return AclVerdict::Block(format!("端口 {} 属于高危禁闭端口", target_port));
        }

        if let Ok(ip) = target_ip_str.parse::<Ipv4Addr>() {
            // 私网地址保护
            if self.block_private_ips && Self::is_private_or_loopback(ip) {
                return AclVerdict::Block(format!("禁止转发至内网或环回地址: {}", ip));
            }

            // 黑名单检查
            for b in &self.blacklist_cidrs {
                if b.contains(ip) {
                    return AclVerdict::Block(format!("目标 IP {} 命中黑名单 CIDR", ip));
                }
            }

            // 白名单检查 (若配置了白名单，必须命中白名单)
            if !self.whitelist_cidrs.is_empty() {
                let in_white = self.whitelist_cidrs.iter().any(|w| w.contains(ip));
                if !in_white {
                    return AclVerdict::Block(format!("目标 IP {} 未在白名单允许范围内", ip));
                }
            }
        }

        AclVerdict::Allow
    }
}

/// 从 TLS ClientHello 报文中极速提取 SNI 域名 (用于免解密精准审计/分流)
pub fn extract_tls_sni(data: &[u8]) -> Option<String> {
    // TLS Record Header: [ContentType (1), Version (2), Length (2)]
    if data.len() < 5 || data[0] != 0x16 {
        return None;
    }

    let mut cursor = 5;
    // Handshake Type == 1 (ClientHello)
    if cursor >= data.len() || data[cursor] != 0x01 {
        return None;
    }
    cursor += 4; // Skip Type(1) and Length(3)

    // Skip Client Version(2) and Random(32)
    cursor += 34;
    if cursor >= data.len() {
        return None;
    }

    // Skip Session ID
    let session_id_len = data[cursor] as usize;
    cursor += 1 + session_id_len;
    if cursor + 2 > data.len() {
        return None;
    }

    // Skip Cipher Suites
    let cipher_suites_len = u16::from_be_bytes([data[cursor], data[cursor + 1]]) as usize;
    cursor += 2 + cipher_suites_len;
    if cursor + 1 > data.len() {
        return None;
    }

    // Skip Compression Methods
    let comp_methods_len = data[cursor] as usize;
    cursor += 1 + comp_methods_len;
    if cursor + 2 > data.len() {
        return None;
    }

    // Extensions Length
    let ext_total_len = u16::from_be_bytes([data[cursor], data[cursor + 1]]) as usize;
    cursor += 2;
    let ext_end = cursor + ext_total_len;
    if ext_end > data.len() {
        return None;
    }

    while cursor + 4 <= ext_end {
        let ext_type = u16::from_be_bytes([data[cursor], data[cursor + 1]]);
        let ext_len = u16::from_be_bytes([data[cursor + 2], data[cursor + 3]]) as usize;
        cursor += 4;

        if ext_type == 0x0000 {
            // Server Name Indication (SNI)
            if cursor + 5 <= ext_end {
                let name_type = data[cursor + 2];
                let name_len = u16::from_be_bytes([data[cursor + 3], data[cursor + 4]]) as usize;
                if name_type == 0 && cursor + 5 + name_len <= ext_end {
                    let sni_bytes = &data[cursor + 5..cursor + 5 + name_len];
                    return String::from_utf8(sni_bytes.to_vec()).ok();
                }
            }
        }
        cursor += ext_len;
    }

    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_private_ip_detection() {
        assert!(AclRuleSet::is_private_or_loopback("127.0.0.1".parse().unwrap()));
        assert!(AclRuleSet::is_private_or_loopback("10.10.1.5".parse().unwrap()));
        assert!(AclRuleSet::is_private_or_loopback("192.168.1.1".parse().unwrap()));
        assert!(AclRuleSet::is_private_or_loopback("172.20.0.1".parse().unwrap()));
        assert!(AclRuleSet::is_private_or_loopback("169.254.1.1".parse().unwrap()));

        // 公网 IP
        assert!(!AclRuleSet::is_private_or_loopback("1.1.1.1".parse().unwrap()));
        assert!(!AclRuleSet::is_private_or_loopback("8.8.8.8".parse().unwrap()));
    }

    #[test]
    fn test_acl_ruleset_enforcement() {
        let acl = AclRuleSet::default();

        // 拦截私网
        match acl.check("192.168.1.20", 80) {
            AclVerdict::Block(reason) => assert!(reason.contains("内网")),
            _ => panic!("应当拦截私网"),
        }

        // 拦截高危 445 端口
        match acl.check("1.1.1.1", 445) {
            AclVerdict::Block(reason) => assert!(reason.contains("高危")),
            _ => panic!("应当拦截高危端口"),
        }

        // 放行正常公网与正常端口
        assert_eq!(acl.check("1.1.1.1", 443), AclVerdict::Allow);
    }

    #[test]
    fn test_cidr_whitelist_and_blacklist() {
        let mut acl = AclRuleSet::default();
        acl.block_private_ips = false;

        let black = Ipv4Cidr::parse("45.33.0.0/16").unwrap();
        acl.blacklist_cidrs.push(black);

        match acl.check("45.33.10.20", 80) {
            AclVerdict::Block(reason) => assert!(reason.contains("黑名单")),
            _ => panic!("应当拦截黑名单"),
        }

        assert_eq!(acl.check("45.34.10.20", 80), AclVerdict::Allow);
    }

    #[test]
    fn test_extract_tls_sni() {
        // 合成的标准 TLS 1.2 / 1.3 ClientHello 携带 SNI: "github.com"
        let sample_client_hello: Vec<u8> = vec![
            0x16, 0x03, 0x01, 0x00, 0x47, // TLS Record Header
            0x01, 0x00, 0x00, 0x43, // Handshake Header: ClientHello, len 0x43
            0x03, 0x03, // Version 3.3
            // 32 bytes random
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
            0x00, // Session ID len 0
            0x00, 0x02, 0x13, 0x01, // Cipher Suites len 2: TLS_AES_128_GCM_SHA256
            0x01, 0x00, // Compression methods len 1: null
            0x00, 0x14, // Extensions len 20
            0x00, 0x00, // Ext Type 0: server_name (SNI)
            0x00, 0x10, // Ext Len 16
            0x00, 0x0e, // Server Name List len 14
            0x00,       // Name type 0 (host_name)
            0x00, 0x0b, // Name len 11
            b'e', b'x', b'a', b'm', b'p', b'l', b'e', b'.', b'c', b'o', b'm',
            0x00, 0x00,
        ];

        let sni = extract_tls_sni(&sample_client_hello);
        assert_eq!(sni, Some("example.com".to_string()));
    }
}
