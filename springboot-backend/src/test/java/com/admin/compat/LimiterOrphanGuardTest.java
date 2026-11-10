package com.admin.compat;

import com.admin.common.dto.GostDto;
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
import org.junit.jupiter.api.DisplayName;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 孤儿限流器回收的安全阀（round46 主线 C，对抗性用例，只读主代码不修改）。
 *
 * <p>回收本身是「删节点侧资源」，误删的代价是不可逆的（servce 直接退化成不限速），
 * 所以本组用例专攻四条安全阀：引用未清不删（SpeedLimitServiceImpl.java:526-536）、
 * speed_id 为 null 不算引用（:510）、引用查询失败整段放弃（:515-518）、
 * 非纯数字名字一律不碰（:563-577）、单轮上限 20 条（:541、:557-559）。
 */
class LimiterOrphanGuardTest {

    private static final long NODE = 2L;
    private static final long TUNNEL = 16L;

    /** 8Mbit/s → "$ 1000000.0MB 1000000.0MB"，与节点上报的 limits 逐字相等即视为一致。 */
    private static final String ONE_GBPS = "$ 1000000.0MB 1000000.0MB";

    private SpeedLimitMapper speedLimitMapper;
    private ChainTunnelService chainTunnelService;
    private UserTunnelService userTunnelService;
    private SpeedLimitServiceImpl speedLimitService;

    /** user_tunnel.speed_id 引用（select("speed_id") 查询的返回）。 */
    private List<UserTunnel> referencedTunnels;
    private boolean refQueryFails;

    /** 隧道内限速规则（in("tunnel_id", …) 查询的返回）与 speed_limit 全表 id 投影（select("id")）。 */
    private List<SpeedLimit> tunnelLimits;
    private List<SpeedLimit> allLimits;
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
            return allIdsQuery ? allLimits : tunnelLimits;
        });

        registerNode(NODE);
    }

    @AfterEach
    void tearDown() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();
        messages.clear();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("speed_id 指向孤儿：该条不删并说明仍被引用，同批其它孤儿照删")
    void orphanReferencedByUserTunnelIsNotDeleted() {
        // 42 仍被 user_tunnel.speed_id 引用、43 无人引用：安全阀必须逐条判断，不能一有引用就整批放过
        referencedTunnels = new ArrayList<>(List.of(reference(42L)));
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "42", ONE_GBPS,
                "43", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertEquals(List.of("43"), deletedLimiterIds(),
                "只有无人引用的 43 该删，实际：" + typeNames() + " / " + deletedLimiterIds());
        assertEquals(2, notes.size(), "一条引用说明 + 一条清理说明，实际：" + notes);
        String referenceNote = noteContaining(notes, "仍被 user_tunnel 引用");
        assertTrue(referenceNote != null, "必须说明为什么不删，实际：" + notes);
        assertTrue(referenceNote.contains("42"), "说明要带被引用的 id：" + notes);
        assertTrue(referenceNote.contains("跳过删除"), "说明要写明跳过：" + notes);
        assertTrue(noteContaining(notes, "清理孤儿限流器 id=43") != null, "43 要被清理：" + notes);
    }

    @Test
    @DisplayName("speed_id 为 null 的 user_tunnel 行不构成引用：孤儿照删")
    void nullSpeedIdReferenceDoesNotProtectOrphan() {
        referencedTunnels = new ArrayList<>(List.of(reference(null), reference(7L)));
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "42", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertEquals(List.of("42"), deletedLimiterIds(),
                "speed_id 为 null 的行不能把孤儿当成被引用，实际：" + typeNames());
        assertEquals(1, notes.size(), "实际：" + notes);
        assertTrue(notes.get(0).contains("清理孤儿限流器 id=42"), "实际：" + notes);
    }

    @Test
    @DisplayName("userTunnelService 查询抛异常：整段放弃，零删除，只留一条跳过说明")
    void referenceQueryExceptionAbortsWholeReap() {
        refQueryFails = true;
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "42", ONE_GBPS,
                "43", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertTrue(messages.isEmpty(), "查不到引用关系时一条都不许删，实际：" + typeNames());
        assertEquals(1, notes.size(), "实际：" + notes);
        assertTrue(notes.get(0).contains("查询限流器引用失败"), "说明要报出引用查询失败：" + notes);
        assertTrue(notes.get(0).contains("跳过孤儿限流器回收"), "说明要写明跳过：" + notes);
        assertTrue(notes.get(0).contains("[SQLITE_BUSY]"), "说明要带上原始错误：" + notes);
    }

    @Test
    @DisplayName("21 个孤儿：只删前 20 条（按 id 升序），剩下的写进「下轮再处理」")
    void orphanReapIsCappedAtTwentyPerRound() {
        Map<String, List<String>> reported = new LinkedHashMap<>();
        reported.put("1", List.of(ONE_GBPS));
        // 倒序塞入 300..320 共 21 条孤儿，验证的是「按 id 数值升序取前 20」而不是插入顺序
        for (int id = 320; id >= 300; id--) {
            reported.put(String.valueOf(id), List.of(ONE_GBPS));
        }

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        List<String> expected = new ArrayList<>();
        for (int id = 300; id < 320; id++) {
            expected.add(String.valueOf(id));
        }
        assertEquals(expected, deletedLimiterIds(), "单轮只许删 id 最小的 20 条");
        assertEquals(21, notes.size(), "20 条清理说明 + 1 条剩余说明，实际：" + notes);
        String last = notes.get(notes.size() - 1);
        assertTrue(last.contains("下轮再处理"), "剩余必须留到下轮：" + last);
        assertTrue(last.contains("其余 1 条"), "剩余条数要写明：" + last);
    }

    @Test
    @DisplayName("非数字/非正整数的 limiter 名（custom、1e3、-1、0）一律不删")
    void nonNumericLimiterNamesAreNeverTouched() {
        Map<String, List<String>> reported = reported(
                "1", ONE_GBPS,
                "custom", ONE_GBPS,
                "1e3", ONE_GBPS,
                "-1", ONE_GBPS,
                "0", ONE_GBPS,
                "  ", ONE_GBPS,
                "9999999999999999999", ONE_GBPS);

        List<String> notes = speedLimitService.reconcileReportedLimiters(NODE, reported);

        assertTrue(messages.isEmpty(), "非本面板命名口径的 limiter 一条都不许删，实际：" + typeNames());
        assertTrue(notes.isEmpty(), "没有可判定的孤儿就不该有说明，实际：" + notes);
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

    private UserTunnel reference(Long speedId) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setSpeedId(speedId == null ? null : (int) (long) speedId);
        return userTunnel;
    }

    /** name1, limits1, name2, limits2, … 形式的节点上报 limiters 映射。 */
    private Map<String, List<String>> reported(String... pairs) {
        Map<String, List<String>> reported = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            reported.put(pairs[i], List.of(pairs[i + 1]));
        }
        return reported;
    }

    private static String noteContaining(List<String> notes, String needle) {
        return notes.stream().filter(note -> note != null && note.contains(needle)).findFirst().orElse(null);
    }

    /** 实际下发过的 DeleteLimiters 的目标 id，按发送顺序。 */
    private List<String> deletedLimiterIds() {
        List<String> ids = new ArrayList<>();
        List<JSONObject> snapshot;
        synchronized (messages) {
            snapshot = new ArrayList<>(messages);
        }
        for (JSONObject message : snapshot) {
            if (!"DeleteLimiters".equals(message.getString("type"))) {
                continue;
            }
            ids.add(message.getJSONObject("data").getString("limiter"));
        }
        return ids;
    }

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

    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", String.valueOf(nodeId));
        attributes.put("type", "1");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("round46-limiter-node-" + nodeId);
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
