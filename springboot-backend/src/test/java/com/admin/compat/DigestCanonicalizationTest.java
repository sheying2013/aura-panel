package com.admin.compat;

import com.admin.common.utils.GostUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * round37 QA 打回项：摘要口径的两处归一化漏洞。
 *
 * <p>① 目标 host 未做大小写归一 —— 库填 {@code Example.COM:8080}、节点回报
 * {@code example.com:8080} 是同一条配置（RFC 4343 域名大小写不敏感），不归一会永久判漂移，
 * 每次心跳都 DeleteService + CreateForwardService，业务长连接被反复掐断。
 *
 * <p>② IPv6 通配只认字面 {@code ::} —— gost 若回报未压缩的 {@code 0:0:0:0:0:0:0:0}
 * 或 {@code ::0}，会与库里的通配写法永久判不等，同样会形成删建风暴。
 */
class DigestCanonicalizationTest {

    @Test
    @DisplayName("目标域名大小写归一：Example.COM 与 example.com 是同一摘要")
    void targetHostIsLowerCased() {
        String upper = GostUtil.normalizeServiceDigestList("30200", "tcp", null,
                List.of("Example.COM:8080"));
        String lower = GostUtil.normalizeServiceDigestList("30200", "tcp", null,
                List.of("example.com:8080"));
        assertEquals(lower, upper);
        assertEquals("30200|tcp|-|example.com:8080", upper);
    }

    @Test
    @DisplayName("多目标里逐个归一小写，顺序与数量语义不变")
    void everyTargetIsNormalized() {
        assertEquals("30200|tcp|-|a.example.com:1,b.example.com:2",
                GostUtil.normalizeServiceDigestList("[::]:30200", "tcp", null,
                        List.of(" A.Example.com:1 ", "B.EXAMPLE.COM:2")));
        assertNotEquals(
                GostUtil.normalizeServiceDigestList("30200", "tcp", null, List.of("a:1,b:2")),
                GostUtil.normalizeServiceDigestList("30200", "tcp", null, List.of("b:2,a:1")));
    }

    @Test
    @DisplayName("IPv6 未压缩通配与 :: 等价：不再误判漂移")
    void uncompressedV6WildcardIsEquivalent() {
        String expected = GostUtil.normalizeServiceDigestList("[::]:30202", "relay", "chains_9", null);
        assertEquals("30202|relay|chains_9|-", expected);
        assertEquals(expected,
                GostUtil.normalizeServiceDigestList("0:0:0:0:0:0:0:0:30202", "relay", "chains_9", null));
        assertEquals(expected,
                GostUtil.normalizeServiceDigestList("[::0]:30202", "relay", "chains_9", null));
    }

    @Test
    @DisplayName("具体 IPv6 地址不被通配折叠：fe80::1 仍要判出来")
    void specificV6StaysSpecific() {
        assertEquals("[fe80::1]:30202|relay|chains_9|-",
                GostUtil.normalizeServiceDigestList("[fe80::1]:30202", "relay", "chains_9", null));
        assertNotEquals(
                GostUtil.normalizeServiceDigestList("[::]:30202", "relay", "chains_9", null),
                GostUtil.normalizeServiceDigestList("[fe80::1]:30202", "relay", "chains_9", null));
    }
}
