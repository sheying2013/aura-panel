//! PROXY Protocol v1 与 v2 协议解析与封装引擎（纯 Rust 实现）。
//!
//! 在四层端口转发与多跳链式中继中，用于透明传输客户端源 IP 与源端口（Real Client IP）。
//! 支持标准：
//! - PROXY Protocol v1（ASCII 文本协议，如 PROXY TCP4 1.2.3.4 5.6.7.8 1234 80\r\n）
//! - PROXY Protocol v2（二进制协议，以 12 字节魔数开头，支持 IPv4/IPv6 与 TCP/UDP，支持 TLV 扩展）

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

/// PROXY 协议版本
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProxyVersion {
    V1,
    V2,
}

/// 传输层协议
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportProtocol {
    Stream,   // TCP
    Datagram, // UDP
}

/// PROXY 协议报文头结构体
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProxyHeader {
    pub version: ProxyVersion,
    pub command: ProxyCommand,
    pub transport: TransportProtocol,
    pub src_addr: SocketAddr,
    pub dst_addr: SocketAddr,
}

/// PROXY v2 命令
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProxyCommand {
    Local, // 健康检查或本地连接，不携带代理地址
    Proxy, // 代理传输，携带客户端和目标端真实地址
}

/// PROXY v2 签名魔数（12 字节）
pub const V2_SIGNATURE: [u8; 12] = [
    0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A,
];

impl ProxyHeader {
    /// 创建一个新的标准 PROXY 转发头
    pub fn new(
        version: ProxyVersion,
        transport: TransportProtocol,
        src_addr: SocketAddr,
        dst_addr: SocketAddr,
    ) -> Self {
        Self {
            version,
            command: ProxyCommand::Proxy,
            transport,
            src_addr,
            dst_addr,
        }
    }

    /// 编码为字节流
    pub fn encode(&self) -> Vec<u8> {
        match self.version {
            ProxyVersion::V1 => self.encode_v1(),
            ProxyVersion::V2 => self.encode_v2(),
        }
    }

    /// 编码为 v1 文本格式
    fn encode_v1(&self) -> Vec<u8> {
        match self.command {
            ProxyCommand::Local => b"PROXY UNKNOWN\r\n".to_vec(),
            ProxyCommand::Proxy => {
                let proto = match (self.src_addr, self.dst_addr) {
                    (SocketAddr::V4(_), SocketAddr::V4(_)) => "TCP4",
                    (SocketAddr::V6(_), SocketAddr::V6(_)) => "TCP6",
                    _ => "UNKNOWN",
                };
                if proto == "UNKNOWN" {
                    return b"PROXY UNKNOWN\r\n".to_vec();
                }
                let line = format!(
                    "PROXY {} {} {} {} {}\r\n",
                    proto,
                    self.src_addr.ip(),
                    self.dst_addr.ip(),
                    self.src_addr.port(),
                    self.dst_addr.port()
                );
                line.into_bytes()
            }
        }
    }

    /// 编码为 v2 二进制格式
    fn encode_v2(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(64);
        buf.extend_from_slice(&V2_SIGNATURE);

        let ver_cmd = match self.command {
            ProxyCommand::Local => 0x20,
            ProxyCommand::Proxy => 0x21,
        };
        buf.push(ver_cmd);

        match (self.src_addr, self.dst_addr) {
            (SocketAddr::V4(src4), SocketAddr::V4(dst4)) => {
                let fam_proto = match self.transport {
                    TransportProtocol::Stream => 0x11,   // AF_INET + STREAM
                    TransportProtocol::Datagram => 0x12, // AF_INET + DGRAM
                };
                buf.push(fam_proto);
                let addr_len: u16 = 12; // 4 + 4 + 2 + 2
                buf.extend_from_slice(&addr_len.to_be_bytes());
                buf.extend_from_slice(&src4.ip().octets());
                buf.extend_from_slice(&dst4.ip().octets());
                buf.extend_from_slice(&src4.port().to_be_bytes());
                buf.extend_from_slice(&dst4.port().to_be_bytes());
            }
            (SocketAddr::V6(src6), SocketAddr::V6(dst6)) => {
                let fam_proto = match self.transport {
                    TransportProtocol::Stream => 0x21,   // AF_INET6 + STREAM
                    TransportProtocol::Datagram => 0x22, // AF_INET6 + DGRAM
                };
                buf.push(fam_proto);
                let addr_len: u16 = 36; // 16 + 16 + 2 + 2
                buf.extend_from_slice(&addr_len.to_be_bytes());
                buf.extend_from_slice(&src6.ip().octets());
                buf.extend_from_slice(&dst6.ip().octets());
                buf.extend_from_slice(&src6.port().to_be_bytes());
                buf.extend_from_slice(&dst6.port().to_be_bytes());
            }
            _ => {
                // 不匹配时作为 LOCAL 或 UNSPEC
                buf.push(0x00);
                buf.extend_from_slice(&0u16.to_be_bytes());
            }
        }

        buf
    }

    /// 解析二进制流中的 PROXY 协议头（自动识别 v1 与 v2）
    pub fn parse(input: &[u8]) -> Result<(Self, usize), String> {
        if input.is_empty() {
            return Err("输入缓冲区为空".to_string());
        }

        if input.starts_with(&V2_SIGNATURE) {
            Self::parse_v2(input)
        } else if input.starts_with(b"PROXY ") {
            Self::parse_v1(input)
        } else {
            Err("未知协议头格式".to_string())
        }
    }

    fn parse_v1(input: &[u8]) -> Result<(Self, usize), String> {
        let crlf = input
            .windows(2)
            .position(|w| w == b"\r\n")
            .ok_or_else(|| "未找到完整的 \r\n 换行符".to_string())?;

        let line = std::str::from_utf8(&input[..crlf])
            .map_err(|e| format!("v1 协议文本不是有效 UTF-8: {}", e))?;
        let consumed = crlf + 2;

        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.is_empty() || parts[0] != "PROXY" {
            return Err("非法 v1 PROXY 前缀".to_string());
        }

        if parts.len() == 2 && parts[1] == "UNKNOWN" {
            let dummy = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            return Ok((
                Self {
                    version: ProxyVersion::V1,
                    command: ProxyCommand::Local,
                    transport: TransportProtocol::Stream,
                    src_addr: dummy,
                    dst_addr: dummy,
                },
                consumed,
            ));
        }

        if parts.len() < 6 {
            return Err(format!("v1 协议字段不足: {}", line));
        }

        let proto = parts[1];
        let src_ip: IpAddr = parts[2]
            .parse()
            .map_err(|e| format!("解析源 IP 失败: {}", e))?;
        let dst_ip: IpAddr = parts[3]
            .parse()
            .map_err(|e| format!("解析目的 IP 失败: {}", e))?;
        let src_port: u16 = parts[4]
            .parse()
            .map_err(|e| format!("解析源端口失败: {}", e))?;
        let dst_port: u16 = parts[5]
            .parse()
            .map_err(|e| format!("解析目的端口失败: {}", e))?;

        let (src_addr, dst_addr) = match proto {
            "TCP4" => {
                if let (IpAddr::V4(s), IpAddr::V4(d)) = (src_ip, dst_ip) {
                    (SocketAddr::new(IpAddr::V4(s), src_port), SocketAddr::new(IpAddr::V4(d), dst_port))
                } else {
                    return Err("TCP4 声明但 IP 不是 IPv4".to_string());
                }
            }
            "TCP6" => {
                if let (IpAddr::V6(s), IpAddr::V6(d)) = (src_ip, dst_ip) {
                    (SocketAddr::new(IpAddr::V6(s), src_port), SocketAddr::new(IpAddr::V6(d), dst_port))
                } else {
                    return Err("TCP6 声明但 IP 不是 IPv6".to_string());
                }
            }
            _ => return Err(format!("未知的 v1 协议族类型: {}", proto)),
        };

        Ok((
            Self {
                version: ProxyVersion::V1,
                command: ProxyCommand::Proxy,
                transport: TransportProtocol::Stream,
                src_addr,
                dst_addr,
            },
            consumed,
        ))
    }

    fn parse_v2(input: &[u8]) -> Result<(Self, usize), String> {
        if input.len() < 16 {
            return Err("v2 头部字节长度不足 16 字节".to_string());
        }

        let ver_cmd = input[12];
        if (ver_cmd & 0xF0) != 0x20 {
            return Err(format!("不支持的 PROXY v2 版本号: {:#x}", ver_cmd >> 4));
        }
        let command = match ver_cmd & 0x0F {
            0x00 => ProxyCommand::Local,
            0x01 => ProxyCommand::Proxy,
            cmd => return Err(format!("未知的 v2 命令代码: {:#x}", cmd)),
        };

        let fam_proto = input[13];
        let transport = match fam_proto & 0x0F {
            0x01 => TransportProtocol::Stream,
            0x02 => TransportProtocol::Datagram,
            _ => TransportProtocol::Stream,
        };

        let addr_len = u16::from_be_bytes([input[14], input[15]]) as usize;
        let total_len = 16 + addr_len;
        if input.len() < total_len {
            return Err(format!(
                "v2 载荷不完整: 需要 {} 字节，实际可用 {} 字节",
                total_len,
                input.len()
            ));
        }

        let payload = &input[16..total_len];
        let dummy = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);

        if command == ProxyCommand::Local || (fam_proto >> 4) == 0 {
            return Ok((
                Self {
                    version: ProxyVersion::V2,
                    command,
                    transport,
                    src_addr: dummy,
                    dst_addr: dummy,
                },
                total_len,
            ));
        }

        let (src_addr, dst_addr) = match fam_proto >> 4 {
            0x01 => {
                // IPv4
                if payload.len() < 12 {
                    return Err("IPv4 报文长度不足 12 字节".to_string());
                }
                let src_ip = Ipv4Addr::new(payload[0], payload[1], payload[2], payload[3]);
                let dst_ip = Ipv4Addr::new(payload[4], payload[5], payload[6], payload[7]);
                let src_port = u16::from_be_bytes([payload[8], payload[9]]);
                let dst_port = u16::from_be_bytes([payload[10], payload[11]]);
                (
                    SocketAddr::new(IpAddr::V4(src_ip), src_port),
                    SocketAddr::new(IpAddr::V4(dst_ip), dst_port),
                )
            }
            0x02 => {
                // IPv6
                if payload.len() < 36 {
                    return Err("IPv6 报文长度不足 36 字节".to_string());
                }
                let mut src_octets = [0u8; 16];
                let mut dst_octets = [0u8; 16];
                src_octets.copy_from_slice(&payload[0..16]);
                dst_octets.copy_from_slice(&payload[16..32]);
                let src_ip = Ipv6Addr::from(src_octets);
                let dst_ip = Ipv6Addr::from(dst_octets);
                let src_port = u16::from_be_bytes([payload[32], payload[33]]);
                let dst_port = u16::from_be_bytes([payload[34], payload[35]]);
                (
                    SocketAddr::new(IpAddr::V6(src_ip), src_port),
                    SocketAddr::new(IpAddr::V6(dst_ip), dst_port),
                )
            }
            _ => (dummy, dummy),
        };

        Ok((
            Self {
                version: ProxyVersion::V2,
                command,
                transport,
                src_addr,
                dst_addr,
            },
            total_len,
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_v1_encode_and_parse_ipv4() {
        let src: SocketAddr = "192.168.1.100:54321".parse().unwrap();
        let dst: SocketAddr = "10.0.0.1:8080".parse().unwrap();
        let header = ProxyHeader::new(ProxyVersion::V1, TransportProtocol::Stream, src, dst);

        let encoded = header.encode();
        assert_eq!(
            encoded,
            b"PROXY TCP4 192.168.1.100 10.0.0.1 54321 8080\r\n".to_vec()
        );

        let (parsed, consumed) = ProxyHeader::parse(&encoded).unwrap();
        assert_eq!(consumed, encoded.len());
        assert_eq!(parsed.version, ProxyVersion::V1);
        assert_eq!(parsed.command, ProxyCommand::Proxy);
        assert_eq!(parsed.src_addr, src);
        assert_eq!(parsed.dst_addr, dst);
    }

    #[test]
    fn test_v1_encode_and_parse_ipv6() {
        let src: SocketAddr = "[2001:db8::1]:12345".parse().unwrap();
        let dst: SocketAddr = "[2001:db8::2]:443".parse().unwrap();
        let header = ProxyHeader::new(ProxyVersion::V1, TransportProtocol::Stream, src, dst);

        let encoded = header.encode();
        assert_eq!(
            encoded,
            b"PROXY TCP6 2001:db8::1 2001:db8::2 12345 443\r\n".to_vec()
        );

        let (parsed, consumed) = ProxyHeader::parse(&encoded).unwrap();
        assert_eq!(consumed, encoded.len());
        assert_eq!(parsed.version, ProxyVersion::V1);
        assert_eq!(parsed.src_addr, src);
        assert_eq!(parsed.dst_addr, dst);
    }

    #[test]
    fn test_v2_encode_and_parse_ipv4_tcp() {
        let src: SocketAddr = "1.2.3.4:1111".parse().unwrap();
        let dst: SocketAddr = "5.6.7.8:2222".parse().unwrap();
        let header = ProxyHeader::new(ProxyVersion::V2, TransportProtocol::Stream, src, dst);

        let encoded = header.encode();
        assert_eq!(&encoded[..12], &V2_SIGNATURE);
        assert_eq!(encoded[12], 0x21); // v2 + PROXY
        assert_eq!(encoded[13], 0x11); // IPv4 + STREAM

        let (parsed, consumed) = ProxyHeader::parse(&encoded).unwrap();
        assert_eq!(consumed, encoded.len());
        assert_eq!(parsed.version, ProxyVersion::V2);
        assert_eq!(parsed.transport, TransportProtocol::Stream);
        assert_eq!(parsed.src_addr, src);
        assert_eq!(parsed.dst_addr, dst);
    }

    #[test]
    fn test_v2_encode_and_parse_ipv6_udp() {
        let src: SocketAddr = "[fe80::1]:8888".parse().unwrap();
        let dst: SocketAddr = "[fe80::2]:9999".parse().unwrap();
        let header = ProxyHeader::new(ProxyVersion::V2, TransportProtocol::Datagram, src, dst);

        let encoded = header.encode();
        assert_eq!(&encoded[..12], &V2_SIGNATURE);
        assert_eq!(encoded[12], 0x21); // v2 + PROXY
        assert_eq!(encoded[13], 0x22); // IPv6 + DGRAM

        let (parsed, consumed) = ProxyHeader::parse(&encoded).unwrap();
        assert_eq!(consumed, encoded.len());
        assert_eq!(parsed.version, ProxyVersion::V2);
        assert_eq!(parsed.transport, TransportProtocol::Datagram);
        assert_eq!(parsed.src_addr, src);
        assert_eq!(parsed.dst_addr, dst);
    }

    #[test]
    fn test_v1_unknown_handling() {
        let raw = b"PROXY UNKNOWN\r\n";
        let (parsed, consumed) = ProxyHeader::parse(raw).unwrap();
        assert_eq!(consumed, raw.len());
        assert_eq!(parsed.command, ProxyCommand::Local);
    }
}
