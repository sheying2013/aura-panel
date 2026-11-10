package com.admin.compat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点上报的每服务 {@code paused} 标志进摘要映射（round44）。
 *
 * <p>覆盖的缺口：面板把转发暂停（库 status=0）后，节点上如果还挂着实例在收流量，
 * 四段摘要（端口|handler|链|目标）完全一致，对账判「一致」永远发现不了
 * 「库里暂停、节点仍在跑」。节点这一版在 serviceDigest 里带 {@code paused}，
 * 面板侧必须把它解析成独立维度 {@code <name>@paused}（"1"/"0"）。
 *
 * <p>口径（与 listener/iface/fselector 同一套）：
 * <ol>
 *   <li>{@code paused} 字段存在时 {@code true → "1"}、{@code false → "0"} 都要落键——
 *       "0" 是节点明确说「在跑」，和「老节点没这个字段」不是一回事；</li>
 *   <li>支持与否由哨兵 {@code @paused_supported} 判定：任一服务带该字段，或 {@code features}
 *       数组声明 {@code paused}，两条按 OR 合并；</li>
 *   <li>老节点两条都不满足 → 没有哨兵 → 上层整维度跳过，绝不能凭缺失判「已暂停/已恢复」。</li>
 * </ol>
 */
class ServicePausedDigestTest {

    private static final String NAME = "21_1_0";
    private static final String PAUSED_KEY = NAME + "@paused";
    private static final String SUPPORTED_KEY = "@paused_supported";

    @Test
    @DisplayName("paused=true 落 1，并带出 @paused_supported 哨兵")
    void pausedTrueBecomesOne() throws Exception {
        Map<String, String> digests = digests(services(
                "[{\"name\":\"" + NAME + "\",\"addr\":\"[::]:30111\",\"handler\":\"tcp\","
                        + "\"targets\":[\"backend:6365\"],\"paused\":true}]"), null);
        assertEquals("1", digests.get(PAUSED_KEY), "paused=true 必须落 \"1\"");
        assertEquals("true", digests.get(SUPPORTED_KEY), "字段出现即支持，哨兵必须有");
        // 四段摘要保持 round34 口径逐字不变：暂停维度只加键，不改原键
        assertEquals("30111|tcp|-|backend:6365", digests.get(NAME));
    }

    @Test
    @DisplayName("paused=false 落 0：节点明确说「在跑」也是有效上报")
    void pausedFalseBecomesZero() throws Exception {
        Map<String, String> digests = digests(services(
                "[{\"name\":\"" + NAME + "\",\"addr\":\"[::]:30111\",\"handler\":\"tcp\","
                        + "\"paused\":false}]"), null);
        assertEquals("0", digests.get(PAUSED_KEY), "paused=false 必须落 \"0\"，不能当没报");
        assertEquals("true", digests.get(SUPPORTED_KEY));
    }

    @Test
    @DisplayName("没有 paused 字段但 features 声明 paused：有哨兵、没有 @paused 键")
    void featuresOnlyGivesSentinelWithoutKey() throws Exception {
        Map<String, String> digests = digests(services(
                "[{\"name\":\"" + NAME + "\",\"addr\":\"[::]:30111\",\"handler\":\"tcp\"}]"),
                Set.of("paused"));
        assertEquals("true", digests.get(SUPPORTED_KEY), "features 声明了就必须有哨兵");
        assertFalse(digests.containsKey(PAUSED_KEY), "本轮没报字段就不能凭空造 \"0\"");
    }

    @Test
    @DisplayName("老节点：无 features 无字段 → 整维度跳过（无哨兵）")
    void oldNodeHasNoSentinel() throws Exception {
        Map<String, String> digests = digests(services(
                "[{\"name\":\"" + NAME + "\",\"addr\":\"[::]:30111\",\"handler\":\"tcp\"}]"), null);
        assertNotNull(digests);
        assertFalse(digests.containsKey(SUPPORTED_KEY), "老节点不能有哨兵，否则会凭缺失判漂移");
        assertFalse(digests.containsKey(PAUSED_KEY));
        assertEquals("30111|tcp|-|-", digests.get(NAME));
    }

    @Test
    @DisplayName("features 里只有别的维度时不带暂停哨兵")
    void unrelatedFeaturesDoNotLeakPausedSentinel() throws Exception {
        Map<String, String> digests = digests(services(
                "[{\"name\":\"" + NAME + "\",\"addr\":\"[::]:30111\",\"handler\":\"tcp\"}]"),
                Set.of("listener", "iface", "fselector"));
        assertFalse(digests.containsKey(SUPPORTED_KEY), "features 不含 paused 就不能有哨兵");
        assertEquals("true", digests.get("@iface_supported"));
    }

    @Test
    @DisplayName("多服务混合：@paused 键按服务名分开，互不串台")
    void mixedServicesKeepSeparatePausedKeys() throws Exception {
        Map<String, String> digests = digests(services(
                "[{\"name\":\"21_1_0\",\"addr\":\"[::]:30111\",\"handler\":\"tcp\",\"paused\":true},"
                        + "{\"name\":\"22_1_0\",\"addr\":\"[::]:30112\",\"handler\":\"tcp\",\"paused\":false},"
                        + "{\"name\":\"23_1_0\",\"addr\":\"[::]:30113\",\"handler\":\"tcp\"}]"), null);
        assertEquals("1", digests.get("21_1_0@paused"));
        assertEquals("0", digests.get("22_1_0@paused"));
        assertFalse(digests.containsKey("23_1_0@paused"), "没报字段的服务不能凭空落键");
        assertEquals("true", digests.get(SUPPORTED_KEY));
    }

    @Test
    @DisplayName("paused 与 iface/listener 共存：各占一个键，值互不影响")
    void pausedCoexistsWithOtherDimensions() throws Exception {
        Map<String, String> digests = digests(services(
                "[{\"name\":\"" + NAME + "\",\"addr\":\"[::]:30111\",\"handler\":\"tcp\","
                        + "\"listener\":\"udp|keepAlive=true\",\"iface\":\" eth0 \",\"paused\":true}]"), null);
        assertEquals("1", digests.get(PAUSED_KEY));
        // listener 维度的既有口径：整串小写（真机 gost 落盘键也是小写 keepalive）
        assertEquals("udp|keepalive=true", digests.get(NAME + "@listener"));
        assertEquals("eth0", digests.get(NAME + "@iface"), "网卡名仍只去首尾空格");
        assertEquals("true", digests.get(SUPPORTED_KEY));
        assertEquals("true", digests.get("@listener_supported"));
        assertEquals("true", digests.get("@iface_supported"));
    }

    @Test
    @DisplayName("空/缺 digests 数组仍然返回 null：老节点当没报")
    void emptyReportStaysNull() throws Exception {
        Method toDigestMap = digestMethod();
        assertNull(toDigestMap.invoke(null, new Object[]{null, null}), "缺字段要当没报");
        assertNull(toDigestMap.invoke(null, new JSONArray(), Set.of("paused")), "空数组同样当没报");
    }

    private static JSONArray services(String json) {
        return JSON.parseArray(json);
    }

    private static Method digestMethod() throws Exception {
        Method toDigestMap = Class.forName("com.admin.common.utils.WebSocketServer")
                .getDeclaredMethod("toDigestMap", JSONArray.class, Set.class);
        toDigestMap.setAccessible(true);
        return toDigestMap;
    }

    /** 走生产入口 toDigestMap(array, features)，避免手写期望值掩盖解析口径的 bug。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> digests(JSONArray reported, Set<String> features) throws Exception {
        Map<String, String> result = (Map<String, String>) digestMethod().invoke(null, reported, features);
        assertNotNull(result, "有服务上报就必须给出摘要映射");
        return result;
    }
}
