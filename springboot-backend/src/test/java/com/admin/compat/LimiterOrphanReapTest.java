package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.SpeedLimit;
import com.admin.entity.UserTunnel;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.SpeedLimitServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 孤儿限流器回收的回归测试（round46 主线 C）。
 *
 * <p>缺口：limiter 只在创建限速规则时下发一次，deleteSpeedLimit 也只在删除成功路径里删节点侧；
 * 库里那行 speed_limit 若因回滚/人工清表/迁移漏行消失，节点上的 limiter 永远残留。
 * reconcileReportedLimiters 原来只处理「库里有、节点缺失/不一致」，对「节点有、库里没有」零动作。
 *
 * <p>这里锁死回收口径：只有纯数字、能解析成正整数、且不在 speed_limit 全表 id 里的名字才算孤儿；
 * 非数字名字（别的系统/人工建的 limiter）不碰；查库失败必须整段放弃（空集合会把限流器全删）；
 * 单轮最多 20 条、按 id 升序；删除报 not found 按成功清理记账。
 */
class LimiterOrphanReapTest {

    private static final long NODE = 2L;
    private static final long TUNNEL = 16L;

    /** 8Mbit/s → "$ 1000000.0MB 1000000.0MB"；4Mbit/s → "$ 500000.0MB 500000.0MB"。 */
    private static final String ONE_GBPS = "$ 1000000.0MB 1000000.0MB";
    private static final String HALF_GBPS = "$ 500000.0MB 500000.0MB";

    private SpeedLimitMapper speedLimitMapper;
    private ChainTunnelService chainTunnelService;
    private UserTunnelService userTunnelService;
    private SpeedLimitServiceImpl speedLimitService;

    /** user_tunnel.speed_id 引用（select("speed_id") 查询的返回）。 */
    private List<UserTunnel> referencedTunnels;
    private boolean refQueryFails;

    /** 隧道内限速规则（in("tunnel_id", …) 查询的返回）。 */
    private List<SpeedLimit> tunnelLimits;
    /** speed_limit 全表 id 投影（select("id") 查询的返回）。 */
    private List<SpeedLimit> allLimits;
    private boolean allQueryFails;
    private boolean allQueryReturnsNull;
    private String deleteReply;

    private final List<JSONObject> messages = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();
        messages.clear();

        tunnelLimits = new ArrayList<>(List.of(limit(1L, 8000000)));
        allLimits = new ArrayList<>(List.of(limit(1L, 8000000)));
        allQueryFails = false;
        allQueryReturnsNull = false;
        deleteReply = "OK";
        referencedTunnels = new ArrayList<>();
        refQueryFails = false;

        speedLimitMapper = mock(SpeedLimitMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);

        userTunnelService = mock(UserTunnelService.class);
        when(userTunnelService.list(any())).thenAnswer(invocation -> {
            if (refQueryFails) {
                throw new RuntimeException("[SQLITE_BUSY] database is locked");
            }
            return referencedTunnels;
        });

        speedLimitService = new SpeedLimitServiceImpl();
        ReflectionTestUtils.setField(speedLimitService, "baseMapper", speedLimitMapper);
        ReflectionTestUtils.setField(speedLimitService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(speedLimitService, "userTunnelService", userTunnelService);

        when(chainTunnelService.list(any())).thenReturn(List.of(chainRow(NODE)));
        when(speedLimitMapper.selectList(any())).thenAnswer(invocation -> {
            QueryWrapper<?> wrapper = invocation.getArgument(0);
            // select("id") 是孤儿回收的全表 id 查询；不带 select 的才是按隧道查限速规则
            boolean allIdsQuery = wrapper != null && wrapper.getSqlSelect() != null;
            if (!allIdsQuery) {
                return tunnelLimits;
            }
            if (allQueryFails) {
                throw new RuntimeException("[SQLITE_BUSY] database is locked");
            }
            return allQueryReturnsNull ? null : allLimits;
        });

        registerNode(NODE);
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void tearDown() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();
        messages.clear();
    }

    // ========== 1) 纯数字孤儿被删 ==========

    @Test
    void numericOrphanLimiterIsReaped() throws Exception {
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "42", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertEquals(1, messages.size(), "只有 42 号是孤儿，实际：" + typeNames());
        assertEquals("DeleteLimiters", messages.get(0).getString("type"));
        assertEquals("42", messages.get(0).getJSONObject("data").getString("limiter"),
                "删除请求必须带被回收的 limiter 名（就是旧 id）");
        assertEquals(1, notes.size(), "一条孤儿一条说明，实际：" + notes);
        assertTrue(notes.get(0).contains("孤儿限流器"), "说明文案要能定位到孤儿回收：" + notes);
        assertTrue(notes.get(0).contains("42"), "说明文案要带 id：" + notes);
        assertTrue(notes.get(0).contains("库内已无该限速规则"), "说明要讲清为什么删：" + notes);
    }

    // ========== 1a) 不挂链资源的纯转发节点：隧道集合为空也必须回收孤儿 ==========

    @Test
    void pureForwardNodeStillReapsOrphan() {
        // 纯转发节点在 chain_tunnel 里没有行：旧实现在这里直接 return，孤儿限流器永远清不掉
        when(chainTunnelService.list(any())).thenReturn(List.of());
        Map<String, List<String>> reported = reported("42", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertEquals(1, messages.size(), "纯转发节点也要回收孤儿，实际：" + typeNames());
        assertEquals("DeleteLimiters", messages.get(0).getString("type"));
        assertEquals("42", messages.get(0).getJSONObject("data").getString("limiter"));
        assertTrue(notes.stream().anyMatch(note -> note.contains("清理孤儿限流器 id=42")), "实际：" + notes);
    }

    // ========== 1c) 删除失败不占额度：超过单轮上限的孤儿仍会被逐个尝试 ==========

    @Test
    void failingDeletesDoNotConsumeReapQuota() {
        // 21 条孤儿、条目全部删除失败：旧实现按「前 20 条」切窗口，第 21 条永远轮不到
        // 库里只剩 1 号规则 → 300..320 全部是孤儿
        allLimits = new ArrayList<>(List.of(limit(1L, 8000000)));
        tunnelLimits = new ArrayList<>();
        deleteReply = "boom";
        Map<String, List<String>> reported = new java.util.LinkedHashMap<>();
        for (long id = 300L; id < 321L; id++) {
            reported.put(String.valueOf(id), List.of(ONE_GBPS));
        }

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertEquals(21, messages.size(), "失败也要把 21 条都试一遍（尝试上限 40），实际：" + typeNames());
        assertTrue(notes.stream().noneMatch(note -> note.contains("下轮再处理")),
                "没有未尝试的条目就不该报下轮再处理：" + notes);
        assertEquals(21, notes.stream().filter(note -> note.contains("失败")).count(), "每条都要留失败记录：" + notes);
    }

    // ========== 1b) 仍被 user_tunnel.speed_id 引用的孤儿不删 ==========

    @Test
    void orphanLimiterStillReferencedIsNotDeleted() {
        referencedTunnels = new ArrayList<>(List.of(reference(42L)));
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "42", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertTrue(messages.isEmpty(),
                "仍被 user_tunnel 引用的限流器删掉会让服务退化成不限速，必须不删，实际：" + typeNames());
        assertEquals(1, notes.size(), "实际：" + notes);
        assertTrue(notes.get(0).contains("仍被 user_tunnel 引用"), "说明要讲清引用关系：" + notes);
        assertTrue(notes.get(0).contains("跳过删除"), "说明要写明跳过：" + notes);
        assertTrue(notes.get(0).contains("42"), "说明要带 id：" + notes);
    }

    @Test
    void referenceQueryFailureSkipsOrphanReap() {
        refQueryFails = true;
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "42", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertTrue(messages.isEmpty(),
                "查不到引用关系时不许猜，一律先不删，实际：" + typeNames());
        assertEquals(1, notes.size(), "实际：" + notes);
        assertTrue(notes.get(0).contains("查询限流器引用失败"), "实际：" + notes);
        assertTrue(notes.get(0).contains("跳过孤儿限流器回收"), "实际：" + notes);
    }

    // ========== 2) 非数字/非法数字名字一律不碰 ==========

    @Test
    void nonNumericLimiterNamesAreIgnored() {
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "custom-limiter", ONE_GBPS,
                "vip_2", ONE_GBPS,
                "0", ONE_GBPS,
                "1234567890123456789", ONE_GBPS,
                "   ", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertTrue(messages.isEmpty(), "非本面板命名/非正整数的 limiter 不许删，实际：" + typeNames());
        assertTrue(notes.isEmpty(), "没有孤儿就不该有说明，实际：" + notes);
    }

    // ========== 3) 库内存在的 id 不删，仍走原有补齐/纠正 ==========

    @Test
    void knownLimiterIsNotReapedAndStillCorrected() {
        tunnelLimits = new ArrayList<>(List.of(limit(1L, 4000000)));
        allLimits = new ArrayList<>(List.of(limit(1L, 4000000), limit(2L, 8000000)));
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "2", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertEquals(1, messages.size(), "只该有一条纠正，实际：" + typeNames());
        assertEquals("UpdateLimiters", messages.get(0).getString("type"),
                "库内存在的 id 走原有纠正分支，不许被当成孤儿删掉");
        assertEquals("1", messages.get(0).getJSONObject("data").getString("limiter"));
        assertEquals(1, notes.size(), "实际：" + notes);
        assertTrue(notes.get(0).contains("纠正"), "原有纠正说明要保留：" + notes);
        assertFalse(notes.get(0).contains("孤儿"), "库内存在的 id 不是孤儿：" + notes);
    }

    // ========== 4) 查库异常 → 一条说明、零删除 ==========

    @Test
    void dbFailureSkipsOrphanReap() {
        allQueryFails = true;
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "42", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertTrue(messages.isEmpty(),
                "查库失败时不许当库是空的去删节点限流器，实际：" + typeNames());
        assertEquals(1, notes.size(), "只留一条跳过说明，实际：" + notes);
        assertTrue(notes.get(0).contains("查询限速规则失败"), "说明要报出查询失败：" + notes);
        assertTrue(notes.get(0).contains("跳过孤儿限流器回收"), "说明要写明跳过：" + notes);
    }

    @Test
    void nullAllIdsQueryResultSkipsOrphanReap() {
        allQueryReturnsNull = true;
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "42", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertTrue(messages.isEmpty(), "查库无结果同样不许删，实际：" + typeNames());
        assertEquals(1, notes.size(), "实际：" + notes);
        assertTrue(notes.get(0).contains("跳过孤儿限流器回收"), "实际：" + notes);
    }

    // ========== 5) not found 当成已清理 ==========

    @Test
    void notFoundReplyCountsAsCleanedUp() {
        deleteReply = "1 error(s) occurred: limiter not found";
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "42", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertEquals(1, messages.size(), "实际：" + typeNames());
        assertEquals("DeleteLimiters", messages.get(0).getString("type"));
        assertEquals(1, notes.size(), "实际：" + notes);
        assertTrue(notes.get(0).contains("清理孤儿限流器"), "not found 说明目标已不存在，应记成功：" + notes);
        assertFalse(notes.get(0).contains("失败"), "not found 不是失败：" + notes);
    }

    // ========== 6) 升序 + 单轮上限 20 ==========

    @Test
    void orphansAreSortedAndCappedAtTwenty() {
        Map<String, List<String>> reported = new LinkedHashMap<>();
        reported.put("1", List.of(ONE_GBPS));
        // 故意倒序塞进去：200..224 共 25 条孤儿
        for (int id = 224; id >= 200; id--) {
            reported.put(String.valueOf(id), List.of(ONE_GBPS));
        }

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertEquals(20, messages.size(), "单轮最多 20 条，实际：" + typeNames());
        List<String> deleted = new ArrayList<>();
        for (JSONObject message : messages) {
            assertEquals("DeleteLimiters", message.getString("type"));
            deleted.add(message.getJSONObject("data").getString("limiter"));
        }
        List<String> expected = new ArrayList<>();
        for (int id = 200; id < 220; id++) {
            expected.add(String.valueOf(id));
        }
        assertEquals(expected, deleted, "必须按 id 数值升序处理前 20 条");
        assertEquals(21, notes.size(), "20 条清理说明 + 1 条剩余说明，实际：" + notes);
        String last = notes.get(notes.size() - 1);
        assertTrue(last.contains("其余 5 条下轮再处理"), "剩余条数要写明：" + last);
    }

    // ========== 7) 补齐与孤儿回收同一轮并存（不能互相推迟） ==========

    @Test
    void repairRoundStillReapsOrphan() {
        tunnelLimits = new ArrayList<>(List.of(limit(1L, 8000000)));
        allLimits = new ArrayList<>(List.of(limit(1L, 8000000)));
        // 节点报的清单里没有 1 号 → 本轮会 AddLimiters 补齐，同时 42 是孤儿。
        // 旧实现只要本轮动过任何 limiter 就把孤儿整体推迟，节点长期漂移时孤儿永远回收不到。
        Map<String, List<String>> reported = reported("42", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertEquals(2, messages.size(), "补齐 + 回收各一条，实际：" + typeNames());
        assertEquals("AddLimiters", messages.get(0).getString("type"));
        assertEquals("DeleteLimiters", messages.get(1).getString("type"));
        assertEquals("42", messages.get(1).getJSONObject("data").getString("limiter"));
        assertTrue(notes.stream().anyMatch(note -> note.contains("清理孤儿限流器 id=42")),
                "孤儿必须在同一轮被回收：" + notes);
    }

    // ---------------------------------------------------------------- 脚手架

    private SpeedLimit limit(long id, int speedBits) {
        SpeedLimit limit = new SpeedLimit();
        limit.setId(id);
        limit.setName("speed-" + id);
        limit.setSpeed(speedBits);
        limit.setTunnelId(TUNNEL);
        limit.setStatus(1);
        return limit;
    }

    private ChainTunnel chainRow(long nodeId) {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL);
        row.setChainType(1);
        row.setNodeId(nodeId);
        row.setProtocol("tcp");
        row.setStrategy("round");
        return row;
    }

    /** name1, limits1, name2, limits2, … 形式的节点上报 limiters 映射。 */
    private UserTunnel reference(long speedId) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setSpeedId((int) speedId);
        return userTunnel;
    }

    private Map<String, List<String>> reported(String... pairs) {
        Map<String, List<String>> reported = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            reported.put(pairs[i], List.of(pairs[i + 1]));
        }
        return reported;
    }

    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", String.valueOf(nodeId));
        attributes.put("type", "1");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            messages.add(message);
            CompletableFuture<GostDto> future = pendingRequests().get(message.getString("requestId"));
            if (future != null) {
                GostDto reply = new GostDto();
                reply.setMsg("DeleteLimiters".equals(message.getString("type")) ? deleteReply : "OK");
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions().put(nodeId, session);
    }

    /** 已发出的命令类型列表，断言失败时打出来看是哪条命令多余/缺失。 */
    private String typeNames() {
        List<String> names = new ArrayList<>();
        List<JSONObject> snapshot;
        synchronized (messages) {
            snapshot = new ArrayList<>(messages);
        }
        for (JSONObject message : snapshot) {
            names.add(message.getString("type"));
        }
        return names.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, WebSocketSession> nodeSessions() throws Exception {
        return (Map<Long, WebSocketSession>) staticField("nodeSessions");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CompletableFuture<GostDto>> pendingRequests() throws Exception {
        return (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
