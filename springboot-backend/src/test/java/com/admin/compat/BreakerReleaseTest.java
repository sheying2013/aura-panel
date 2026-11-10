package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.common.utils.DriftCircuitBreaker;
import com.admin.controller.NodeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 漂移熔断「管理端手动放行」回归测试（round38 遗留 3）。
 *
 * <p>缺口：熔断只有内存态 + 自动冷却（默认 15 分钟 = 3 个巡检周期）。运维把节点侧写法规整之后，
 * 没有任何手段让某条资源立刻重试，只能干等冷却到期；冷却期内 {@code allowAttempt} 一直 false，
 * 面板上那条资源就一直是「熔断中」。
 *
 * <p>要求：
 * 1. {@code release(key)} 只删这一个 key，返回「是否存在过」，重复调用返回 false；
 * 2. {@code releaseByNode(nodeId)} 只删该节点名下的条目，别的节点同名资源与同名前缀节点都不受影响，
 *    key 第三段（资源名）里含 {@code |} 也不影响节点判定；
 * 3. {@code releaseAll()} 清空并返回删除数量；
 * 4. 放行后 {@code allowAttempt} 立即 true、连续计数从 0 重新开始（不是接着旧计数加）；
 * 5. {@code snapshot()} 里对应条目立刻消失；
 * 6. {@code POST /api/v1/node/breaker/release} 的三选一与优先级 key &gt; nodeId &gt; all。
 *
 * <p>全部是纯单测：不启 Spring 上下文，控制器直接 new（该接口不依赖注入的 service）。
 */
class BreakerReleaseTest {

    private static final String SERVICE_KEY = "service|7|41_1_0";

    // ---------------------------------------------------------------- 熔断器本体

    @Test
    @DisplayName("release 命中返回 true、未命中返回 false，且只动这一个 key")
    void releaseHitsOnlyTheGivenKey() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 60_000L);
        breaker.recordDrift(SERVICE_KEY);
        breaker.recordDrift("service|7|41_1_1");

        assertTrue(breaker.release(SERVICE_KEY), "条目存在过，必须返回 true");
        assertEquals(1, breaker.size(), "只删掉一个，另一个要留下");
        assertFalse(breaker.release(SERVICE_KEY), "已经删过，再删必须返回 false");
        assertFalse(breaker.release(null), "null key 不能抛异常，按未命中处理");
        assertEquals(0, breaker.consecutiveDrifts(SERVICE_KEY), "被删的 key 计数归 0");
        assertEquals(1, breaker.consecutiveDrifts("service|7|41_1_1"), "另一个 key 的计数不能被牵连");
    }

    @Test
    @DisplayName("releaseByNode 只删该节点，别节点同名资源 / 同前缀节点都不动")
    void releaseByNodeOnlyDropsThatNode() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 60_000L);
        breaker.recordDrift("service|7|41_1_0");
        breaker.recordDrift("relay|7|9_tls");
        breaker.recordDrift("chain|7|chains_9");
        breaker.recordDrift("service|7|name|with|pipes");   // 资源名里含 |，仍属节点 7
        breaker.recordDrift("service|8|41_1_0");            // 别的节点上的同名资源
        breaker.recordDrift("service|77|41_1_0");           // 前缀相同的节点 77
        breaker.recordDrift("service|17|41_1_0");           // 尾号相同的节点 17

        assertEquals(4, breaker.releaseByNode(7L), "节点 7 名下正好 4 条（含资源名带 | 的那条）");
        assertEquals(3, breaker.size(), "节点 8 / 77 / 17 的条目必须原样留下");
        assertTrue(breaker.snapshot().containsKey("service|8|41_1_0"), "别节点上的同名资源不能动");
        assertTrue(breaker.snapshot().containsKey("service|77|41_1_0"), "前缀相同的节点 77 不能动");
        assertTrue(breaker.snapshot().containsKey("service|17|41_1_0"), "尾号相同的节点 17 不能动");
        assertEquals(0, breaker.releaseByNode(7L), "再放行一次没有可删的，返回 0");
        assertEquals(0, breaker.releaseByNode(null), "null nodeId 返回 0");
        assertEquals(1, breaker.releaseByNode(77L), "节点 77 只命中自己那一条");
    }

    @Test
    @DisplayName("releaseByNode 不能让别的节点的资源提前解熔断")
    void releaseByNodeKeepsOtherNodesFrozen() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 60_000L);
        breaker.recordDrift("service|7|41_1_0");
        breaker.recordDrift("service|7|41_1_0");
        breaker.recordDrift("service|8|41_1_0");
        breaker.recordDrift("service|8|41_1_0");

        assertTrue(breaker.isOpen("service|8|41_1_0"), "节点 8 先熔断");
        assertEquals(1, breaker.releaseByNode(7L));
        assertTrue(breaker.isOpen("service|8|41_1_0"), "放行节点 7 不能解开节点 8 的熔断");
        assertFalse(breaker.allowAttempt("service|8|41_1_0"), "节点 8 仍在冷却，不放行");
    }

    @Test
    @DisplayName("releaseAll 清空全部条目并返回删除数量")
    void releaseAllClearsEverything() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 60_000L);
        breaker.recordDrift("service|7|41_1_0");
        breaker.recordDrift("relay|8|9_tls");
        breaker.recordDrift("chain|9|chains_9");

        assertEquals(3, breaker.releaseAll(), "返回删掉的条目数");
        assertEquals(0, breaker.size(), "条目表必须清空");
        assertTrue(breaker.snapshot().isEmpty(), "快照立刻为空");
        assertEquals(0, breaker.releaseAll(), "已经是空的，再清返回 0");
        assertTrue(breaker.allowAttempt("service|7|41_1_0"), "清空后所有 key 都当没失败过");
    }

    @Test
    @DisplayName("release 后 allowAttempt 立刻放行，连续计数从 0 重新开始")
    void releaseMakesKeyRetryableFromScratch() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 600_000L);
        breaker.recordDrift(SERVICE_KEY);
        breaker.recordDrift(SERVICE_KEY);
        assertTrue(breaker.isOpen(SERVICE_KEY), "连续 2 次漂移必须熔断");
        assertTrue(breaker.remainingCooldownMs(SERVICE_KEY) > 0L, "冷却中");
        assertFalse(breaker.allowAttempt(SERVICE_KEY), "冷却中不放行");

        assertTrue(breaker.release(SERVICE_KEY), "手动放行");
        assertEquals(0, breaker.consecutiveDrifts(SERVICE_KEY), "计数归 0");
        assertEquals(0L, breaker.openUntil(SERVICE_KEY), "没有到期时间了");
        assertEquals(0L, breaker.remainingCooldownMs(SERVICE_KEY), "剩余冷却归 0");
        assertFalse(breaker.isOpen(SERVICE_KEY), "不再是熔断态");
        assertTrue(breaker.allowAttempt(SERVICE_KEY), "放行后立刻能重试");

        assertEquals(1, breaker.recordDrift(SERVICE_KEY), "重新计数是 1，不是接着旧的 2 往上加");
        assertTrue(breaker.allowAttempt(SERVICE_KEY), "1 次没到阈值，仍放行");
    }

    @Test
    @DisplayName("snapshot 里放行过的条目立刻消失，未放行的条目字段不变")
    void snapshotDropsReleasedEntriesImmediately() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 60_000L);
        breaker.recordDrift(SERVICE_KEY, "30111|tcp|-|backend:6365", "30112|tcp|-|backend:6365");
        breaker.recordDrift(SERVICE_KEY, "30111|tcp|-|backend:6365", "30112|tcp|-|backend:6365");
        breaker.recordDrift("relay|7|9_tls", "expected", "actual");
        assertEquals(2, breaker.snapshot().size());

        breaker.release(SERVICE_KEY);
        Map<String, Map<String, Object>> snapshot = breaker.snapshot();
        assertFalse(snapshot.containsKey(SERVICE_KEY), "放行过的 key 必须从快照里消失");
        assertEquals("expected", snapshot.get("relay|7|9_tls").get("expected"), "别的条目原样保留");

        breaker.releaseByNode(7L);
        assertTrue(breaker.snapshot().isEmpty(), "节点 7 全放行后快照为空");
    }

    @Test
    @DisplayName("跨实例放行能触到 registered 出来的生产实例")
    void releaseEverywhereReachesRegisteredInstance() {
        DriftCircuitBreaker registered = DriftCircuitBreaker.registered(2, 60_000L);
        registered.recordDrift("service|9301|breaker_release_a");
        registered.recordDrift("service|9302|breaker_release_b");

        assertEquals(1, DriftCircuitBreaker.releaseEverywhere("service|9301|breaker_release_a"),
                "key 只在一个实例里存在过，计数为 1");
        assertFalse(DriftCircuitBreaker.snapshotAll().containsKey("service|9301|breaker_release_a"),
                "登记表的合并快照里也要立刻消失");
        assertTrue(DriftCircuitBreaker.snapshotAll().containsKey("service|9302|breaker_release_b"),
                "别的 key 不受影响");
        assertEquals(1, DriftCircuitBreaker.releaseByNodeEverywhere(9302L));
        assertEquals(0, registered.size(), "两条都放行完毕");
    }

    // ---------------------------------------------------------------- 管理端接口

    @Test
    @DisplayName("POST /breaker/release: key 模式")
    void endpointReleasesByKey() {
        DriftCircuitBreaker registered = DriftCircuitBreaker.registered(2, 60_000L);
        registered.recordDrift("service|9401|endpoint_key");
        registered.recordDrift("service|9401|endpoint_key");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", "service|9401|endpoint_key");
        R r = new NodeController().releaseBreaker(params);

        assertEquals(0, r.getCode(), "成功时 code 必须是 0，msg：" + r.getMsg());
        Map<String, Object> data = data(r);
        assertEquals(1, data.get("released"));
        assertEquals("key", data.get("mode"));
        assertFalse(registered.isOpen("service|9401|endpoint_key"), "接口调用后必须真的解熔断");
    }

    @Test
    @DisplayName("POST /breaker/release: nodeId 模式")
    void endpointReleasesByNode() {
        DriftCircuitBreaker registered = DriftCircuitBreaker.registered(2, 60_000L);
        registered.recordDrift("service|9402|endpoint_node_a");
        registered.recordDrift("relay|9402|endpoint_node_b");
        registered.recordDrift("service|9403|endpoint_node_c");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeId", 9402);
        R r = new NodeController().releaseBreaker(params);

        assertEquals(0, r.getCode(), "msg：" + r.getMsg());
        Map<String, Object> data = data(r);
        assertEquals(2, data.get("released"), "节点 9402 名下两条");
        assertEquals("node", data.get("mode"));
        assertTrue(registered.snapshot().containsKey("service|9403|endpoint_node_c"), "节点 9403 不能被牵连");
    }

    @Test
    @DisplayName("POST /breaker/release: all 模式清空")
    void endpointReleasesAll() {
        DriftCircuitBreaker registered = DriftCircuitBreaker.registered(2, 60_000L);
        registered.recordDrift("service|9404|endpoint_all_a");
        registered.recordDrift("relay|9405|endpoint_all_b");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("all", Boolean.TRUE);
        R r = new NodeController().releaseBreaker(params);

        assertEquals(0, r.getCode(), "msg：" + r.getMsg());
        Map<String, Object> data = data(r);
        assertTrue(((Integer) data.get("released")) >= 2, "至少删掉本实例这 2 条，实际：" + data.get("released"));
        assertEquals("all", data.get("mode"));
        assertEquals(0, registered.size(), "本实例必须被清空");
    }

    @Test
    @DisplayName("POST /breaker/release: 优先级 key > nodeId > all")
    void endpointPrefersKeyThenNodeIdOverAll() {
        DriftCircuitBreaker registered = DriftCircuitBreaker.registered(2, 60_000L);
        registered.recordDrift("service|9406|endpoint_prio_a");
        registered.recordDrift("service|9406|endpoint_prio_b");

        Map<String, Object> keyParams = new LinkedHashMap<>();
        keyParams.put("key", "service|9406|endpoint_prio_a");
        keyParams.put("nodeId", 9406);
        keyParams.put("all", Boolean.TRUE);
        R byKey = new NodeController().releaseBreaker(keyParams);
        assertEquals("key", data(byKey).get("mode"), "带 key 时必须是 key 模式");
        assertEquals(1, data(byKey).get("released"));
        assertTrue(registered.snapshot().containsKey("service|9406|endpoint_prio_b"),
                "all=true 不能因为顺带传了 key 就把全部清掉");

        Map<String, Object> nodeParams = new LinkedHashMap<>();
        nodeParams.put("nodeId", 9406);
        nodeParams.put("all", Boolean.TRUE);
        R byNode = new NodeController().releaseBreaker(nodeParams);
        assertEquals("node", data(byNode).get("mode"), "带 nodeId 时 all=true 不生效");
        assertEquals(1, data(byNode).get("released"));
        assertEquals(0, registered.size());
    }

    @Test
    @DisplayName("POST /breaker/release: 三样都没给要报错，非法 nodeId 不能退化成 all")
    void endpointRejectsEmptyAndBadParams() {
        NodeController controller = new NodeController();

        R empty = controller.releaseBreaker(new LinkedHashMap<>());
        assertEquals(-1, empty.getCode(), "什么都没给必须报错");
        assertEquals("请指定 key、nodeId 或 all", empty.getMsg());

        R nullBody = controller.releaseBreaker(null);
        assertEquals("请指定 key、nodeId 或 all", nullBody.getMsg(), "空 body 走同一条文案");

        R blankKey = controller.releaseBreaker(new LinkedHashMap<>(Map.of("key", "   ", "all", Boolean.FALSE)));
        assertEquals(-1, blankKey.getCode(), "空白 key 视为没给，all=false 也等于没给");

        DriftCircuitBreaker registered = DriftCircuitBreaker.registered(2, 60_000L);
        registered.recordDrift("service|9407|endpoint_bad_node");
        Map<String, Object> badNode = new LinkedHashMap<>();
        badNode.put("nodeId", "abc");
        badNode.put("all", Boolean.TRUE);
        R r = controller.releaseBreaker(badNode);
        assertEquals(-2, r.getCode(), "非法 nodeId 必须报参数错误，不能落到 all 把状态清空");
        assertEquals("nodeId 格式不正确", r.getMsg());
        assertTrue(registered.snapshot().containsKey("service|9407|endpoint_bad_node"),
                "报错时一条都不能删");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(R r) {
        assertNotNull(r.getData(), "成功响应必须带 data");
        return (Map<String, Object>) r.getData();
    }
}
