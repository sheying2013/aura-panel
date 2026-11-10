package com.admin.common.utils;

import javax.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析。
 *
 * <p>只有 remoteAddr 落在可信代理网段时才参考转发头，且从 X-Forwarded-For 右往左跳过
 * 可信代理、取第一个不可信地址；直连请求自带的转发头一律忽略，避免客户端自填 XFF
 * 伪造限流 key 与日志 IP。</p>
 */
public class IpUtils {

    private static final String UNKNOWN = "unknown";

    /**
     * 只认 X-Forwarded-For：nginx 的 $proxy_add_x_forwarded_for 会把真实对端追加在最右，
     * 客户端自带的同名头只能落在左侧、被右向左扫描忽略。Proxy-Client-IP / WL-Proxy-Client-IP
     * 在多数反代下不会被覆盖，等于给客户端留了一个可伪造的入口，故不再采信。
     */
    private static final String[] FORWARDED_HEADERS = {"x-forwarded-for"};

    public static String getIpAddr(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        try {
            return resolve(request.getRemoteAddr(), firstForwardedHeader(request));
        } catch (Exception e) {
            return "";
        }
    }

    /** 取第一个非空且非 unknown 的转发头，全部缺失时返回 null。 */
    private static String firstForwardedHeader(HttpServletRequest request) {
        for (String header : FORWARDED_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.trim().isEmpty() && !UNKNOWN.equalsIgnoreCase(value.trim())) {
                return value;
            }
        }
        return null;
    }

    /**
     * 解析客户端 IP。
     *
     * @param remoteAddr 直连对端地址；不可信时直接作为结果，XFF 不参与
     * @param xff        转发链（逗号分隔，最右为最近一跳）
     * @return 客户端 IP；全部不可判定时回退 remoteAddr
     */
    static String resolve(String remoteAddr, String xff) {
        String remote = normalize(remoteAddr);
        if (!isTrusted(remote)) {
            return remote;
        }
        if (xff != null) {
            String[] hops = xff.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = normalize(hops[i]);
                if (!isIp(hop)) {
                    continue;
                }
                if (!isTrusted(hop)) {
                    return hop;
                }
            }
        }
        return remote;
    }

    /** 去空白、去方括号、去端口、去 IPv6 zone；非法形式返回空串。 */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) {
                return "";
            }
            value = value.substring(1, end);
        } else {
            value = stripPort(value);
        }
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        value = unmapIpv4(value);
        return value.trim();
    }

    /** 裸 IPv4 带端口（1.2.3.4:5678）时剥掉端口；IPv6 多冒号形式原样返回。 */
    private static String stripPort(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || value.indexOf(':', colon + 1) >= 0) {
            return value;
        }
        String host = value.substring(0, colon);
        String port = value.substring(colon + 1);
        if (port.isEmpty() || port.length() > 5 || parseIpv4(host) < 0) {
            return value;
        }
        for (int i = 0; i < port.length(); i++) {
            char c = port.charAt(i);
            if (c < '0' || c > '9') {
                return value;
            }
        }
        return host;
    }

    /** IPv4-mapped IPv6（::ffff:127.0.0.1）折叠回点分 IPv4，否则双栈 socket 下的代理会被当成直连。 */
    private static String unmapIpv4(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String prefix = "::ffff:";
        if (!lower.startsWith(prefix)) {
            return value;
        }
        String rest = value.substring(prefix.length());
        return parseIpv4(rest) >= 0 ? rest : value;
    }

    /** 自定义可信代理匹配器集合（支持 IPv4/IPv6 单 IP 与 CIDR 网段）。 */
    private static final java.util.List<CidrMatcher> CUSTOM_TRUSTED_PROXIES = new java.util.concurrent.CopyOnWriteArrayList<>();

    static {
        loadCustomProxiesFromEnvironment();
    }

    /** 从环境变量 FLUX_TRUSTED_PROXIES 或系统属性 flux.trusted-proxies 加载自定义可信代理网段。 */
    public static void loadCustomProxiesFromEnvironment() {
        String env = System.getenv("FLUX_TRUSTED_PROXIES");
        if (env == null || env.trim().isEmpty()) {
            env = System.getProperty("flux.trusted-proxies");
        }
        if (env != null && !env.trim().isEmpty()) {
            addCustomTrustedProxies(env);
        }
    }

    /**
     * 添加自定义可信代理网段（支持逗号或分号分隔，例如 "103.21.244.0/22, 198.51.100.5/32, 2001:db8::/32"）。
     */
    public static void addCustomTrustedProxies(String cidrList) {
        if (cidrList == null || cidrList.trim().isEmpty()) {
            return;
        }
        for (String item : cidrList.split("[,;\\s]+")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                CidrMatcher matcher = CidrMatcher.parse(trimmed);
                if (matcher != null) {
                    CUSTOM_TRUSTED_PROXIES.add(matcher);
                }
            }
        }
    }

    /** 清空自定义可信代理网段（主要用于测试隔离）。 */
    public static void resetCustomTrustedProxies() {
        CUSTOM_TRUSTED_PROXIES.clear();
    }

    /** 默认可信代理网段：127/8、::1、10/8、172.16/12、192.168/16，以及用户配置的自定义可信网段。 */
    static boolean isTrusted(String ip) {
        long v4 = parseIpv4(ip);
        if (v4 >= 0) {
            boolean defaultMatch = (v4 & 0xFF000000L) == 0x7F000000L
                    || (v4 & 0xFF000000L) == 0x0A000000L
                    || (v4 & 0xFFF00000L) == 0xAC100000L
                    || (v4 & 0xFFFF0000L) == 0xC0A80000L;
            if (defaultMatch) {
                return true;
            }
        } else {
            byte[] v6 = parseIpv6(ip);
            if (v6 != null) {
                boolean isLoopback = true;
                for (int i = 0; i < 15; i++) {
                    if (v6[i] != 0) {
                        isLoopback = false;
                        break;
                    }
                }
                if (isLoopback && v6[15] == 1) {
                    return true;
                }
            }
        }
        for (CidrMatcher custom : CUSTOM_TRUSTED_PROXIES) {
            if (custom.matches(ip)) {
                return true;
            }
        }
        return false;
    }

    abstract static class CidrMatcher {
        abstract boolean matches(String ip);

        static CidrMatcher parse(String cidr) {
            if (cidr == null || cidr.trim().isEmpty()) {
                return null;
            }
            cidr = cidr.trim();
            int slash = cidr.indexOf('/');
            String ipPart = slash < 0 ? cidr : cidr.substring(0, slash);
            String prefixPart = slash < 0 ? null : cidr.substring(slash + 1);

            long v4 = parseIpv4(ipPart);
            if (v4 >= 0) {
                int prefix = 32;
                if (prefixPart != null) {
                    try {
                        prefix = Integer.parseInt(prefixPart);
                        if (prefix < 0 || prefix > 32) return null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                long mask = prefix == 0 ? 0L : ((0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL);
                return new Ipv4CidrMatcher(v4 & mask, mask);
            }

            byte[] v6 = parseIpv6(ipPart);
            if (v6 != null) {
                int prefix = 128;
                if (prefixPart != null) {
                    try {
                        prefix = Integer.parseInt(prefixPart);
                        if (prefix < 0 || prefix > 128) return null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return new Ipv6CidrMatcher(v6, prefix);
            }
            return null;
        }
    }

    static class Ipv4CidrMatcher extends CidrMatcher {
        private final long network;
        private final long mask;

        Ipv4CidrMatcher(long network, long mask) {
            this.network = network;
            this.mask = mask;
        }

        @Override
        boolean matches(String ip) {
            long v4 = parseIpv4(ip);
            if (v4 < 0) return false;
            return (v4 & mask) == network;
        }
    }

    static class Ipv6CidrMatcher extends CidrMatcher {
        private final byte[] network;
        private final int prefixLength;

        Ipv6CidrMatcher(byte[] ipBytes, int prefixLength) {
            this.prefixLength = prefixLength;
            this.network = new byte[16];
            for (int i = 0; i < 16; i++) {
                int bitsInByte = Math.min(8, Math.max(0, prefixLength - i * 8));
                int maskByte = bitsInByte == 0 ? 0 : (0xFF << (8 - bitsInByte)) & 0xFF;
                this.network[i] = (byte) (ipBytes[i] & maskByte);
            }
        }

        @Override
        boolean matches(String ip) {
            byte[] v6 = parseIpv6(ip);
            if (v6 == null) return false;
            for (int i = 0; i < 16; i++) {
                int bitsInByte = Math.min(8, Math.max(0, prefixLength - i * 8));
                if (bitsInByte == 0) break;
                int maskByte = (0xFF << (8 - bitsInByte)) & 0xFF;
                if ((v6[i] & maskByte) != (network[i] & 0xFF)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean isIp(String ip) {
        return parseIpv4(ip) >= 0 || parseIpv6(ip) != null;
    }

    /** 严格点分十进制解析，非法返回 -1。 */
    private static long parseIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return -1;
        }
        long value = 0L;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return -1;
            }
            int octet = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return -1;
                }
                octet = octet * 10 + (c - '0');
            }
            if (octet > 255) {
                return -1;
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    /** IPv6 解析（支持 :: 压缩），非法返回 null。 */
    private static byte[] parseIpv6(String ip) {
        if (ip.isEmpty() || ip.indexOf(':') < 0) {
            return null;
        }
        int compress = ip.indexOf("::");
        if (compress >= 0 && ip.indexOf("::", compress + 2) >= 0) {
            return null;
        }
        int[] head = ipv6Groups(compress < 0 ? ip : ip.substring(0, compress));
        int[] tail = compress < 0 ? new int[0] : ipv6Groups(ip.substring(compress + 2));
        if (head == null || tail == null) {
            return null;
        }
        if (compress < 0) {
            if (head.length != 8) {
                return null;
            }
        } else if (head.length + tail.length > 7) {
            return null;
        }
        byte[] bytes = new byte[16];
        int pos = 0;
        for (int group : head) {
            bytes[pos++] = (byte) (group >> 8);
            bytes[pos++] = (byte) group;
        }
        pos += (8 - head.length - tail.length) * 2;
        for (int group : tail) {
            bytes[pos++] = (byte) (group >> 8);
            bytes[pos++] = (byte) group;
        }
        return bytes;
    }

    private static int[] ipv6Groups(String part) {
        if (part.isEmpty()) {
            return new int[0];
        }
        String[] tokens = part.split(":", -1);
        int[] groups = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty() || token.length() > 4) {
                return null;
            }
            int group = 0;
            for (int j = 0; j < token.length(); j++) {
                int digit = Character.digit(token.charAt(j), 16);
                if (digit < 0) {
                    return null;
                }
                group = (group << 4) | digit;
            }
            groups[i] = group;
        }
        return groups;
    }
}
