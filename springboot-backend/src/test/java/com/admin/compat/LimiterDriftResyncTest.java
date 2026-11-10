package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.SpeedLimit;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.SpeedLimitServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 节点限流器「速率漂移」对账的回归测试。
 *
 * <p>round30/round31 都留了同一个缺口：限流器只在创建时下发一次，之后没有任何校验。
 * resyncNodeLimiters 只判"库里有没有"，节点上同名限流器的速率被改成别的值（手工改 gost.json、
 * 旧版本按错值下发）它照样返回成功 —— 面板显示 500KB/s，节点实际跑 1MB/s，全绿且永远发现不了。
 *
 * <p>这里锁死上报驱动的对账口径：节点 ServiceListReport 里的 limiters 是唯一比对依据。
 * 缺 → AddLimiters 补齐；名称在但 limits 不一致 → UpdateLimiters 纠正；一致 → 一条命令都不发；
 * 上报空清单 → 整段跳过（与孤儿清扫同一口径）；老节点不报该字段 → 退回原"按库补齐"路径。
 */
class LimiterDriftResyncTest {

    private static final long NODE = 2L;
    private static final long OTHER_NODE = 3L;
    private static final long TUNNEL = 16L;

    /** 库里 8Mbit/s → "$ 1000000.0MB 1000000.0MB"；4Mbit/s → "$ 500000.0MB 500000.0MB"。 */
    private static final String ONE_GBPS = "$ 1000000.0MB 1000000.0MB";
    private static final String HALF_GBPS = "$ 500000.0MB 500000.0MB";

    private WebSocketServer server;
    private SpeedLimitMapper speedLimitMapper;
    private ChainTunnelService chainTunnelService;
    private ForwardService forwardService;
    private TunnelService tunnelService;

    private List<SpeedLimit> limits;
    private String addReply;
    private String updateReply;
    private final Map<Long, String> addReplies = new HashMap<>();

    private final Map<Long, List<String>> payloads = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();
        sweepingNodes().clear();
        payloads.clear();

        limits = new ArrayList<>(List.of(limit(1L, 8000000)));
        addReply = "OK";
        updateReply = "OK";
        addReplies.clear();

        speedLimitMapper = mock(SpeedLimitMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        forwardService = mock(ForwardService.class);
        tunnelService = mock(TunnelService.class);

        SpeedLimitServiceImpl speedLimitService = new SpeedLimitServiceImpl();
        ReflectionTestUtils.setField(speedLimitService, "baseMapper", speedLimitMapper);
        ReflectionTestUtils.setField(speedLimitService, "chainTunnelService", chainTunnelService);
        // 孤儿回收要查 user_tunnel 引用：未注入时是 fail-closed 跳过（见 SpeedLimitServiceImpl.reapOrphanLimiters）
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        when(userTunnelService.list(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(speedLimitService, "userTunnelService", userTunnelService);

        when(speedLimitMapper.selectList(any())).thenAnswer(invocation -> limits);
        when(chainTunnelService.list(any())).thenReturn(List.of(chainRow(NODE)));
        when(forwardService.resyncNodeServices(any(), any(), any())).thenReturn(Collections.emptyList());

        server = new WebSocketServer();
        ReflectionTestUtils.setField(server, "forwardService", forwardService);
        ReflectionTestUtils.setField(server, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(server, "speedLimitService", speedLimitService);
    }

    @AfterEach
    void tearDown() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();
        sweepingNodes().clear();
        payloads.clear();
    }

    // ========== 1) 节点没有该限流器 → 补齐 ==========

    @Test
    void missingLimiterIsAddedFromReport() throws Exception {
        registerNode(NODE);

        // 节点只报了别的限流器：库里的 1 号在节点上不存在
        server.handleTextMessage(nodeSessions().get(NODE),
                new TextMessage(report(limiter("99", ONE_GBPS))));

        List<JSONObject> messages = awaitMessages(NODE, 2);
        assertEquals(2, messages.size(), "补齐一条 + 回收孤儿 99 一条，实际：" + typeNames(messages));
        assertEquals("AddLimiters", messages.get(0).getString("type"));
        assertEquals("DeleteLimiters", messages.get(1).getString("type"),
                "99 是孤儿（库里只有 1），同一轮就该回收，不能被补齐动作整体推迟");
        assertEquals("99", messages.get(1).getJSONObject("data").getString("limiter"));
        JSONObject data = messages.get(0).getJSONObject("data");
        assertEquals("1", data.getString("name"), "限流器名就是 speed_limit.id");
        assertEquals(ONE_GBPS, data.getJSONArray("limits").getString(0),
                "期望值必须由库里的 speed 换算：8Mbit/s → 1000000.0MB");
    }

    // ========== 2) 名称在但速率不一致 → 纠正 ==========

    @Test
    void driftedLimiterIsCorrected() throws Exception {
        registerNode(NODE);
        limits = new ArrayList<>(List.of(limit(1L, 4000000)));

        server.handleTextMessage(nodeSessions().get(NODE),
                new TextMessage(report(limiter("1", ONE_GBPS))));

        List<JSONObject> messages = awaitMessages(NODE, 1);
        assertEquals(1, messages.size(), "只该纠正一次，实际：" + typeNames(messages));
        assertEquals("UpdateLimiters", messages.get(0).getString("type"),
                "名称已存在但速率不符：不能只 AddLimiters，必须 UpdateLimiters");
        JSONObject data = messages.get(0).getJSONObject("data");
        assertEquals("1", data.getString("limiter"));
        assertEquals(HALF_GBPS, data.getJSONObject("data").getJSONArray("limits").getString(0),
                "纠正值必须是库里的 4Mbit/s → 500000.0MB，而不是节点上报的旧值");
    }

    // ========== 3) 一致 → 一条命令都不发 ==========

    @Test
    void matchingLimiterIsNotTouched() throws Exception {
        registerNode(NODE);

        server.handleTextMessage(nodeSessions().get(NODE),
                new TextMessage(report(limiter("1", ONE_GBPS))));

        Thread.sleep(500);
        assertTrue(messages(NODE).isEmpty(), "速率一致就不是漂移，不许发命令：" + typeNames(messages(NODE)));
    }

    // ========== 4) 上报空清单 → 跳过 ==========

    @Test
    void emptyReportedLimitersAreSkipped() throws Exception {
        registerNode(NODE);

        server.handleTextMessage(nodeSessions().get(NODE), new TextMessage(report()));

        Thread.sleep(500);
        assertTrue(messages(NODE).isEmpty(),
                "节点报了个空 limiters，这份清单不可信，不许据此补发限流器：" + typeNames(messages(NODE)));
    }

    // ========== 5) 单节点失败不影响其它节点 ==========

    @Test
    void failingNodeDoesNotBlockOtherNodes() throws Exception {
        // 节点 2 的限流器下发被拒（权限/配置写不进去），节点 3 正常
        registerNode(NODE);
        addReplies.put(NODE, "create limiter 1 failed: permission denied");
        registerNode(OTHER_NODE);

        server.handleTextMessage(nodeSessions().get(NODE), new TextMessage(report(limiter("99", ONE_GBPS))));
        server.handleTextMessage(nodeSessions().get(OTHER_NODE), new TextMessage(report(limiter("99", ONE_GBPS))));

        List<JSONObject> otherMessages = awaitMessages(OTHER_NODE, 2);
        assertEquals(2, otherMessages.size(), "另一个节点的补齐 + 孤儿回收照做，实际：" + typeNames(otherMessages));
        assertEquals("AddLimiters", otherMessages.get(0).getString("type"));
        assertEquals("DeleteLimiters", otherMessages.get(1).getString("type"));

        awaitSweepsDone();
        assertEquals(2, messages(NODE).size(),
                "失败节点也把命令发出去了（补齐被拒 + 孤儿回收），不该静默重试或补发：" + typeNames(messages(NODE)));
    }

    // ========== 6) 同一次上报里同一个 limiter 只处理一次 ==========

    @Test
    void duplicateReportedEntriesAreHandledOnce() throws Exception {
        registerNode(NODE);

        // 同名两条：第一条是旧值（漂移），第二条是正确值。只认第一条 → 恰好一次纠正
        server.handleTextMessage(nodeSessions().get(NODE),
                new TextMessage(report(limiter("1", HALF_GBPS), limiter("1", ONE_GBPS))));

        List<JSONObject> messages = awaitMessages(NODE, 1);
        Thread.sleep(300);
        assertEquals(1, messages(NODE).size(), "同一个限流器一次上报只处理一次，实际：" + typeNames(messages(NODE)));
        assertEquals("UpdateLimiters", messages.get(0).getString("type"));
    }

    // ========== 7) 老节点不报 limiters 字段 → 维持原按库补齐 ==========

    @Test
    void missingLimitersFieldKeepsLegacyResync() throws Exception {
        registerNode(NODE);
        limits = new ArrayList<>(List.of(limit(1L, 8000000)));

        JSONObject payload = JSON.parseObject(report());
        payload.getJSONObject("data").remove("limiters");
        server.handleTextMessage(nodeSessions().get(NODE), new TextMessage(payload.toJSONString()));

        List<JSONObject> messages = awaitMessages(NODE, 1);
        assertEquals(1, messages.size(), "老节点没这个字段，必须退回原来的按库补齐，实际：" + typeNames(messages));
        assertEquals("AddLimiters", messages.get(0).getString("type"));
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

    private String limiter(String name, String... limits) {
        JSONObject entry = new JSONObject();
        entry.put("name", name);
        JSONArray array = new JSONArray();
        array.addAll(Arrays.asList(limits));
        entry.put("limits", array);
        return entry.toJSONString();
    }

    /** 拼一份 ServiceListReport；limiters 为空表示"报了空清单"，与字段缺失是两种不同情形。 */
    private String report(String... limiters) {
        JSONArray services = new JSONArray();
        // 用非白名单名字：孤儿清扫只认 <id>_tls / <fid>_<uid>_<utid>_(tcp|udp) / chains_<id> / hop_<id>
        services.add("node_" + NODE);
        JSONArray limiterArray = new JSONArray();
        for (String entry : limiters) {
            limiterArray.add(JSON.parseObject(entry));
        }
        JSONObject data = new JSONObject();
        data.put("services", services);
        data.put("chains", new JSONArray());
        data.put("limiters", limiterArray);
        JSONObject report = new JSONObject();
        report.put("type", "ServiceListReport");
        report.put("success", true);
        report.put("message", "OK");
        report.put("data", data);
        return report.toJSONString();
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
            payloads.computeIfAbsent(nodeId, key -> Collections.synchronizedList(new ArrayList<>())).add(payload);
            CompletableFuture<GostDto> future = pendingRequests().get(message.getString("requestId"));
            if (future != null) {
                GostDto reply = new GostDto();
                if ("UpdateLimiters".equals(message.getString("type"))) {
                    reply.setMsg(updateReply);
                } else {
                    reply.setMsg(addReplies.getOrDefault(nodeId, addReply));
                }
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions().put(nodeId, session);
    }

    private List<JSONObject> messages(long nodeId) {
        List<JSONObject> found = new ArrayList<>();
        List<String> raw = payloads.get(nodeId);
        if (raw == null) {
            return found;
        }
        synchronized (raw) {
            for (String payload : raw) {
                found.add(JSON.parseObject(payload));
            }
        }
        return found;
    }

    private List<JSONObject> awaitMessages(long nodeId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (messages(nodeId).size() >= expected) {
                break;
            }
            Thread.sleep(10);
        }
        Thread.sleep(150);
        return messages(nodeId);
    }

    private void awaitSweepsDone() throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (sweepingNodes().isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private static String typeNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
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

    @SuppressWarnings("unchecked")
    private static Set<Long> sweepingNodes() throws Exception {
        return (Set<Long>) staticField("sweepingNodes");
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
