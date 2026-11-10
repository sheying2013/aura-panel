package com.admin.compat;

import com.admin.common.utils.IpUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link IpUtils} 的抗伪造回归。
 *
 * <p>旧实现无条件取 X-Forwarded-For 最左一段（{@code IpUtils.java:32-38}），客户端自填
 * 一个 XFF 即可伪造限流 key 与日志 IP；现在只有 remoteAddr 落在可信代理网段时才参考
 * XFF，并从右往左跳过可信代理。{@code resolve} 是包级可见的，测试用反射调用，不改生产
 * 可见性。</p>
 */
class IpAddressTrustTest {

    private static String resolve(String remoteAddr, String xff) {
        try {
            Method method = IpUtils.class.getDeclaredMethod("resolve", String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, remoteAddr, xff);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("直连客户端自填 XFF 被忽略")
    void directClientForgedXffIgnored() {
        assertEquals("203.0.113.7", resolve("203.0.113.7", "1.1.1.1"));
        assertEquals("203.0.113.7", resolve("203.0.113.7", "1.1.1.1, 8.8.8.8"));
    }

    @Test
    @DisplayName("单代理场景取 XFF 中的真实客户端")
    void singleProxyUsesClientFromXff() {
        assertEquals("198.51.100.9", resolve("10.0.0.5", "198.51.100.9"));
    }

    @Test
    @DisplayName("多级可信代理从右往左跳过")
    void skipsTrustedProxyHopsFromRight() {
        assertEquals("198.51.100.9",
                resolve("10.0.0.5", "198.51.100.9, 172.16.3.4, 192.168.1.1"));
        assertEquals("198.51.100.9",
                resolve("10.0.0.5", "198.51.100.9, 192.168.1.1"));
        assertEquals("198.51.100.9",
                resolve("0:0:0:0:0:0:0:1", "198.51.100.9, ::1"));
    }

    @Test
    @DisplayName("攻击者放在最左的伪造 IP 不会被采纳")
    void forgedLeftmostEntryIgnored() {
        assertEquals("198.51.100.9", resolve("10.0.0.5", "1.2.3.4, 198.51.100.9"));
        assertEquals("198.51.100.9", resolve("10.0.0.5", "1.2.3.4,1.2.3.5, 198.51.100.9"));
    }

    @Test
    @DisplayName("整条链都是可信代理时回退 remoteAddr")
    void allHopsTrustedFallsBackToRemote() {
        assertEquals("10.0.0.5", resolve("10.0.0.5", "10.1.1.1, 172.20.0.9, 127.0.0.1"));
        assertEquals("192.168.3.4", resolve("192.168.3.4", "192.168.3.4"));
    }

    @Test
    @DisplayName("XFF 为 unknown 或空时回退 remoteAddr")
    void unknownOrEmptyXffFallsBackToRemote() {
        assertEquals("10.0.0.5", resolve("10.0.0.5", "unknown"));
        assertEquals("10.0.0.5", resolve("10.0.0.5", " Unknown "));
        assertEquals("10.0.0.5", resolve("10.0.0.5", ""));
        assertEquals("10.0.0.5", resolve("10.0.0.5", null));
    }

    @Test
    @DisplayName("IPv6、方括号与 zone 被规范化")
    void ipv6BracketsAndZoneNormalized() {
        assertEquals("2001:db8::1", resolve("::1", "[2001:db8::1]"));
        assertEquals("2001:db8::2", resolve("::1", "2001:db8::2%eth0"));
        assertEquals("2001:db8::3", resolve("0:0:0:0:0:0:0:1", "2001:db8::3"));
        assertEquals("10.0.0.5", resolve("10.0.0.5", "[::1]"));
        assertEquals("::1", resolve("[::1]", null));
    }

    @Test
    @DisplayName("null remoteAddr 不抛异常")
    void nullRemoteAddrIsSafe() {
        assertNotNull(resolve(null, "1.2.3.4"));
        assertEquals("", resolve(null, "1.2.3.4"));
        assertEquals("", resolve(null, null));
    }

    @Test
    @DisplayName("无效条目被跳过，继续向左找")
    void invalidEntriesSkipped() {
        assertEquals("198.51.100.9", resolve("10.0.0.5", "not-an-ip, 198.51.100.9"));
        assertEquals("2001:db8::9", resolve("10.0.0.5", "999.1.1.1, 2001:db8::9"));
        assertEquals("198.51.100.9", resolve("10.0.0.5", " , 198.51.100.9 , "));
    }

    @Test
    @DisplayName("空白被裁剪")
    void whitespaceTrimmed() {
        assertEquals("198.51.100.9", resolve("10.0.0.5", "  198.51.100.9  "));
        assertEquals("10.0.0.5", resolve("  10.0.0.5  ", null));
    }

    @Test
    @DisplayName("127.0.0.1 不再被替换成本机网卡 IP")
    void loopbackNotRewrittenToNicAddress() {
        assertEquals("127.0.0.1", resolve("127.0.0.1", null));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertEquals("127.0.0.1", IpUtils.getIpAddr(request));
    }

    @Test
    @DisplayName("getIpAddr 对直连请求忽略伪造头")
    void getIpAddrIgnoresForgedHeaderOnDirectConnection() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("x-forwarded-for", "1.1.1.1");

        assertEquals("203.0.113.7", IpUtils.getIpAddr(request));
    }

    @Test
    @DisplayName("getIpAddr 在可信代理后取真实客户端")
    void getIpAddrUsesRealClientBehindTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("x-forwarded-for", "1.2.3.4, 198.51.100.9");

        assertEquals("198.51.100.9", IpUtils.getIpAddr(request));
    }

    @Test
    @DisplayName("Proxy-Client-IP 不再被采信（多数反代不覆盖，等于可伪造入口）")
    void proxyClientIpHeaderIsNoLongerTrusted() {
        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("10.0.0.5");
        trusted.addHeader("Proxy-Client-IP", "198.51.100.77");

        assertEquals("10.0.0.5", IpUtils.getIpAddr(trusted));

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("203.0.113.7");
        direct.addHeader("Proxy-Client-IP", "198.51.100.77");

        assertEquals("203.0.113.7", IpUtils.getIpAddr(direct));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 折叠回点分 IPv4")
    void ipv4MappedIpv6IsUnmapped() {
        assertEquals("127.0.0.1", resolve("::ffff:127.0.0.1", null));
        assertEquals("198.51.100.9", resolve("::ffff:10.0.0.5", "198.51.100.9"));
        assertEquals("203.0.113.7", resolve("::ffff:203.0.113.7", "1.1.1.1"));
    }

    @Test
    @DisplayName("带端口的转发条目剥端口后仍可用")
    void forwardedEntryWithPortIsStripped() {
        assertEquals("198.51.100.9", resolve("10.0.0.5", "198.51.100.9:5678"));
        assertEquals("1.2.3.4", resolve("1.2.3.4:5678", "9.9.9.9"));
        assertEquals("2001:db8::1", resolve("::1", "[2001:db8::1]:443"));
    }

    @Test
    @DisplayName("getIpAddr(null) 返回空串")
    void getIpAddrNullRequestIsSafe() {
        assertEquals("", IpUtils.getIpAddr(null));
    }

    @Test
    @DisplayName("自定义可信代理CIDR匹配公网反代")
    void customTrustedProxyCidrMatches() {
        try {
            IpUtils.resetCustomTrustedProxies();
            // 默认情况下 103.21.244.15 不是私网，会被视为不可信直连
            assertEquals("103.21.244.15", resolve("103.21.244.15", "198.51.100.9"));

            // 注册可信 CIDR 网段（如 CDN 或公网反代）
            IpUtils.addCustomTrustedProxies("103.21.244.0/22, 198.51.100.5/32, 2001:db8:abcd::/48");

            // 落在 103.21.244.0/22 内的公网反代被信任，成功提取真实客户端
            assertEquals("198.51.100.9", resolve("103.21.244.15", "198.51.100.9"));
            assertEquals("198.51.100.9", resolve("198.51.100.5", "198.51.100.9"));

            // 落在 2001:db8:abcd::/48 内的 IPv6 反代被信任
            assertEquals("198.51.100.9", resolve("2001:db8:abcd:0012::1", "198.51.100.9"));

            // 网段外的公网 IP 依然不可信
            assertEquals("103.22.0.1", resolve("103.22.0.1", "198.51.100.9"));
        } finally {
            IpUtils.resetCustomTrustedProxies();
        }
    }
}
