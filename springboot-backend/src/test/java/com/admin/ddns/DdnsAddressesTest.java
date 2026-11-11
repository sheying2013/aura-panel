package com.admin.ddns;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DDNS 纯字符串规则；不碰 DNS、不碰数据库。 */
class DdnsAddressesTest {

    @AfterEach
    void resetRenderer() {
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("域名规范化：大小写、尾点和 IDN 都收敛成小写 ASCII")
    void normalizesDomain() {
        assertEquals("app.example.com", DdnsAddresses.normalizeDomain("  App.Example.COM.  "));
        assertEquals("xn--bcher-kva.example", DdnsAddresses.normalizeDomain("Bücher.example"));
    }

    @Test
    @DisplayName("IDNA2003 会换名字的输入直接拒绝：faß.de 不等于 fass.de")
    void rejectsDomainThatIdna2003WouldRename() {
        // JDK 的 IDN.toASCII("faß.de") 是 fass.de，浏览器（UTS46）是 xn--fa-hia.de。
        // 两者不是同一个域名，静默采用前者等于把用户写的名字悄悄换成别人的站点。
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DdnsAddresses.normalizeDomain("faß.de"));
        assertTrue(error.getMessage().contains("fass.de"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DdnsAddresses.normalizeDomain("ß.de"));
        assertFalse(DdnsAddresses.targetUsesDomain("fass.de:443", "faß.de"));
    }

    @Test
    @DisplayName("域名校验拒绝 IP、协议、端口、路径和非法标签")
    void rejectsNonDomainInput() {
        assertThrows(IllegalArgumentException.class, () -> DdnsAddresses.normalizeDomain("1.2.3.4"));
        assertThrows(IllegalArgumentException.class, () -> DdnsAddresses.normalizeDomain("https://app.example.com"));
        assertThrows(IllegalArgumentException.class, () -> DdnsAddresses.normalizeDomain("app.example.com:8080"));
        assertThrows(IllegalArgumentException.class, () -> DdnsAddresses.normalizeDomain("app.example.com/x"));
        assertThrows(IllegalArgumentException.class, () -> DdnsAddresses.normalizeDomain("bad_label.example.com"));
        assertThrows(IllegalArgumentException.class, () -> DdnsAddresses.normalizeDomain(" "));
    }

    @Test
    @DisplayName("target host 提取支持域名、IPv4、[IPv6] 和端口")
    void extractsHost() {
        assertEquals("app.example.com", DdnsAddresses.hostOf("app.example.com:8080"));
        assertEquals("app.example.com", DdnsAddresses.hostOf("app.example.com"));
        assertEquals("1.2.3.4", DdnsAddresses.hostOf("1.2.3.4:80"));
        assertEquals("2001:db8::1", DdnsAddresses.hostOf("[2001:db8::1]:443"));
        assertEquals("2001:db8::1", DdnsAddresses.hostOf("2001:db8::1"));
    }

    @Test
    @DisplayName("逗号列表按 host 命中域名，端口和大小写不影响")
    void matchesDomainInTargetList() {
        assertTrue(DdnsAddresses.targetUsesDomain("a:1, APP.EXAMPLE.COM:8080 ", "app.example.com"));
        assertFalse(DdnsAddresses.targetUsesDomain("app.example.com.evil:80", "app.example.com"));
        assertFalse(DdnsAddresses.targetUsesDomain("1.2.3.4:80", "app.example.com"));
        assertTrue(DdnsAddresses.targetUsesDomain("例子.测试.:443", "xn--fsqu00a.xn--0zwm56d"));
        assertTrue(DdnsAddresses.targetUsesDomain("xn--fsqu00a.xn--0zwm56d:443", "例子.测试"));
        assertFalse(DdnsAddresses.targetUsesDomain("bad_label.example:80", "bad_label.example"));
    }

    @Test
    @DisplayName("IP 清洗：去 zone、去重、IPv4 优先且稳定排序")
    void normalizesIps() {
        List<String> ips = DdnsAddresses.normalizeIps(List.of(
                "2001:db8::1", "2.2.2.2%en0", "1.1.1.1", "2.2.2.2", "not-an-ip", " "));
        assertEquals(List.of("1.1.1.1", "2.2.2.2", "2001:db8::1"), ips);
    }

    @Test
    @DisplayName("仅接受合法 IPv6 字面量，过滤畸形地址")
    void rejectsMalformedIpv6() {
        assertTrue(DdnsAddresses.isIpLiteral("2001:db8::1"));
        assertTrue(DdnsAddresses.isIpLiteral("::1"));
        assertTrue(DdnsAddresses.isIpLiteral("::ffff:c000:201"));
        assertTrue(DdnsAddresses.isIpLiteral("::ffff:192.0.2.1"));
        assertFalse(DdnsAddresses.isIpLiteral("::ffff:192.0.2.999"));
        assertFalse(DdnsAddresses.isIpLiteral("1:2"));
        assertFalse(DdnsAddresses.isIpLiteral("1:2:3:4:5:6:7:8:9"));
        assertFalse(DdnsAddresses.isIpLiteral("1::2::3"));
        assertEquals(List.of("2001:db8::1"),
                DdnsAddresses.normalizeIps(List.of("1:2", "1::2::3", "2001:db8::1")));
    }

    @Test
    @DisplayName("主地址选择：老地址还在就不动，避免无意义断流")
    void keepsCurrentPrimaryWhenStillPresent() {
        assertEquals("1.1.1.1", DdnsAddresses.choosePrimary("1.1.1.1", List.of("2.2.2.2", "1.1.1.1")));
        assertEquals("2.2.2.2", DdnsAddresses.choosePrimary("1.1.1.1", List.of("2.2.2.2")));
        assertEquals("2.2.2.2", DdnsAddresses.choosePrimary(null, List.of("2.2.2.2")));
        assertNull(DdnsAddresses.choosePrimary("1.1.1.1", List.of()));
    }

    @Test
    @DisplayName("IPv6 目标必须加方括号，避免和端口拼出歧义地址")
    void formatsIpv6Host() {
        assertEquals("[2001:db8::1]", DdnsAddresses.formatHost("2001:db8::1"));
        assertEquals("2.2.2.2", DdnsAddresses.formatHost("2.2.2.2"));
        assertEquals("[2001:db8::1]", DdnsAddresses.formatHost("[2001:db8::1]"));
    }

    @Test
    @DisplayName("注册表只替换已登记域名的 host，端口和未知目标原样保留")
    void registryRendersOnlyRegisteredHost() {
        DdnsAddressRegistry registry = new DdnsAddressRegistry();
        registry.put("app.example.com", "2.2.2.2");

        assertEquals("2.2.2.2:8080", registry.render("app.example.com:8080"));
        assertEquals("2.2.2.2", registry.render("APP.EXAMPLE.COM"));
        assertEquals("other.example.com:80", registry.render("other.example.com:80"));
        assertEquals("1.1.1.1:80", registry.render("1.1.1.1:80"));
        assertEquals("2.2.2.2:8080", registry.render("App.Example.com.:8080"));

        registry.put("xn--fsqu00a.xn--0zwm56d", "2.2.2.2");
        assertEquals("2.2.2.2:443", registry.render("例子.测试.:443"));
        assertEquals("[::1]:443", registry.renderWith("例子.测试.:443", "xn--fsqu00a.xn--0zwm56d", "::1"));
        assertTrue(registry.beginTransition("例子.测试", "test"));
        assertThrows(DdnsTransitionBusyException.class, () -> registry.render("xn--fsqu00a.xn--0zwm56d:443"));
        assertTrue(registry.endTransition("xn--fsqu00a.xn--0zwm56d"));

        registry.put("v6.example.com", "2001:db8::1");
        assertEquals("[2001:db8::1]:8080", registry.render("v6.example.com:8080"));

        registry.remove("app.example.com");
        assertEquals("app.example.com:8080", registry.render("app.example.com:8080"));
        registry.clear();
    }
}
