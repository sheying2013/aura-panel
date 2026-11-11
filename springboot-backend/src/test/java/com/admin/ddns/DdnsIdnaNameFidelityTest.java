package com.admin.ddns;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.IDN;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁死「JDK 的 IDNA2003（{@link IDN}）与浏览器 UTS46 不一致」这一类的静默改名。
 *
 * <p>期望值取自 UTS46 nontransitional（python-idna 3.18：
 * {@code idna.encode(name, uts46=True, transitional=False)}，JDK 21.0.11）。凡是两边给出
 * 不同 DNS 名称的输入，{@link DdnsAddresses#normalizeDomain(String)} 必须抛错；两边一致的
 * 正常 Unicode、显式 punycode 和全角/大小写/尾点收敛必须继续可用。
 */
class DdnsIdnaNameFidelityTest {

    @AfterEach
    void resetRenderer() {
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("IDNA2003 会换成另一个 DNS 名称的输入一律拒绝，不静默改写")
    void rejectsInputsThatIdna2003WouldRename() {
        // JDK 给 fass.de / ss.de；浏览器给 xn--fa-hia.de / xn--zca.de。
        assertRejected("faß.de", "fass.de");
        assertRejected("ß.de", "ss.de");
        assertRejected("FAß.DE", "fass.DE");
        assertRejected("faß.de.", "fass.de");
        // ẞ 在 nameprep 的 Unicode 3.2 表里未分配：JDK 编成 xn--kkg.de，浏览器折叠成 ß 得 xn--zca.de。
        assertRejected("ẞ.de", null);
        // 最终 sigma 是 UTS46 的 deviation：JDK 折叠成 σ（xn--fa-wbc.gr），浏览器保留 ς（xn--fa-tbc.gr）。
        assertRejected("faς.gr", "xn--fa-wbc.gr");
        // 零宽连接符被 IDNA2003 直接删掉：a?b.de 变成 ab.de；UTS46 对这种上下文直接报错。
        assertRejected("a\u200Db.de", "ab.de");
        // 软连字符同样被删；即使 UTS46 也删，丢弃用户输入的字符也不该被当成同一个名字接受。
        assertRejected("a\u00ADb.de", "ab.de");
        assertRejected("a\uFEFFb.de", "ab.de");
        assertRejected("a\u200Bb.de", "ab.de");
    }

    @Test
    @DisplayName("两套标准一致的正常 Unicode 域名继续可用，且结果与 UTS46 相同")
    void acceptsNamesBothStacksAgreeOn() {
        assertEquals("xn--bcher-kva.example", DdnsAddresses.normalizeDomain("Bücher.example"));
        assertEquals("xn--bcher-kva.example", DdnsAddresses.normalizeDomain("BÜCHER.EXAMPLE"));
        assertEquals("xn--fsqu00a.xn--0zwm56d", DdnsAddresses.normalizeDomain("例子.测试"));
        assertEquals("xn--hxajbheg2az3al.xn--jxalpdlp",
                DdnsAddresses.normalizeDomain("παράδειγμα.δοκιμή"));
        // 全角兼容字符：两边都 NFKC 成 ASCII。
        assertEquals("example.com", DdnsAddresses.normalizeDomain("ｅｘample.com"));
        // 西里尔 а 的同形域名不是「改写」而是用户真的输入了另一个名字，照原样编码。
        assertEquals("xn--exmple-4nf.com", DdnsAddresses.normalizeDomain("exаmple.com"));
        assertEquals("3com.example", DdnsAddresses.normalizeDomain("3com.example"));
    }

    @Test
    @DisplayName("显式 punycode 原样通过，不会被反解再折叠成别的名字")
    void acceptsExplicitPunycode() {
        assertEquals("xn--fsqu00a.xn--0zwm56d", DdnsAddresses.normalizeDomain("xn--fsqu00a.xn--0zwm56d"));
        assertEquals("xn--bcher-kva.example", DdnsAddresses.normalizeDomain("xn--bcher-kva.example"));
        assertEquals("xn--bcher-kva.example", DdnsAddresses.normalizeDomain("XN--BCHER-KVA.example"));
        assertEquals("xn--fsqu00a.xn--0zwm56d", DdnsAddresses.normalizeDomain("xn--fsqu00a.xn--0zwm56d."));
        // 这是 faß.de 的 UTS46 形式，本身是合法 ACE，必须继续支持。
        assertEquals("xn--fa-hia.de", DdnsAddresses.normalizeDomain("xn--fa-hia.de"));
    }

    @Test
    @DisplayName("转换结果幂等：ASCII 结果再规范化、以及回解码后再规范化都等于它自己")
    void conversionIsIdempotent() {
        for (String input : new String[]{"Bücher.example", "例子.测试", "xn--fa-hia.de",
                "ｅｘample.com", "παράδειγμα.δοκιμή", "app.example.com"}) {
            String ascii = DdnsAddresses.normalizeDomain(input);
            assertEquals(ascii, DdnsAddresses.normalizeDomain(ascii), input);
            assertEquals(ascii, DdnsAddresses.normalizeDomain(IDN.toUnicode(ascii)), input);
        }
    }

    @Test
    @DisplayName("大小写与末尾点仍然收敛，且不因新校验失效")
    void foldsCaseAndTrailingDot() {
        assertEquals("app.example.com", DdnsAddresses.normalizeDomain("  App.Example.COM.  "));
        assertEquals("xn--bcher-kva.example", DdnsAddresses.normalizeDomain("BÜCHER.EXAMPLE."));
        assertEquals("xn--fsqu00a.xn--0zwm56d",
                DdnsAddresses.normalizeDomain("例子.测试."));
    }

    @Test
    @DisplayName("注册表和 target 匹配都不会把 faß.de 当成 fass.de")
    void registryAndTargetMatchDoNotCollapseRename() {
        assertFalse(DdnsAddresses.targetUsesDomain("fass.de:443", "faß.de"));
        assertFalse(DdnsAddresses.targetUsesDomain("faß.de:443", "fass.de"));
        assertTrue(DdnsAddresses.targetUsesDomain("xn--fa-hia.de:443", "xn--fa-hia.de"));

        DdnsAddressRegistry registry = new DdnsAddressRegistry();
        registry.put("fass.de", "2.2.2.2");
        // 用户写的是 faß.de，host 不能被换名字后当成 fass.de 去下发 2.2.2.2。
        assertEquals("faß.de:443", registry.render("faß.de:443"));
        assertEquals("2.2.2.2:443", registry.render("fass.de:443"));
        registry.clear();
    }

    @Test
    @DisplayName("拒绝信息里给出 JDK 想改成的那个域名，便于用户判断")
    void errorMessageShowsTheRenamedDomain() {
        IllegalArgumentException renamed = assertThrows(IllegalArgumentException.class,
                () -> DdnsAddresses.normalizeDomain("faß.de"));
        assertTrue(renamed.getMessage().contains("fass.de"), renamed.getMessage());
        assertTrue(renamed.getMessage().contains("IDNA2003"), renamed.getMessage());

        IllegalArgumentException unassigned = assertThrows(IllegalArgumentException.class,
                () -> DdnsAddresses.normalizeDomain("ẞ.de"));
        assertEquals("域名格式不合法：ẞ.de", unassigned.getMessage());
    }

    private static void assertRejected(String input, String renamedTo) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DdnsAddresses.normalizeDomain(input), input);
        if (renamedTo != null) {
            assertTrue(error.getMessage().contains(renamedTo),
                    input + " 的报错应提到 " + renamedTo + "，实际：" + error.getMessage());
        }
    }
}
