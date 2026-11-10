package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.ForwardServiceImpl;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 暂停下发双路径去重（round45）回归测试。
 *
 * <p>原缺陷：节点重连与资源上报两条路径各自无条件补发一遍 PauseService，
 * 同一批服务一轮里被下发两次（幂等，但多一轮 WS 消息与日志噪音，熔断计数还分两个命名空间各记一次）。
 * 而且 round44 引入 {@code <name>@paused} 摘要后，ForwardServiceImpl#enforcePausedService
 * 已经能按节点自报状态精确纠正，旧路径纯属重复劳动。
 *
 * <p>现在的约束：
 * 1. 上报里带 {@code @paused_supported} 哨兵（新节点）时，旧路径让位；
 * 2. 老节点没有该维度，旧路径继续按库兜底，不能一起关掉；
 * 3. 同一次连接（同一 epoch）里旧路径只跑一轮，建连与报表不再各跑一次；
 * 4. 能力是缓存跨重连的：建连时还没收到上报（digests=null）也要按上次声明的能力判定。
 *
 * <p>两层覆盖：前 5 例打 {@code claimPauseRound} 这个判定点（边界最密），
 * 后 6 例走完整 WS 报文链（{@code handleTextMessage} → 旧路径 / 清扫 → 新路径），用真实
 * {@link ForwardServiceImpl} 验证两条路径各自到底发没发。
 */
class PausedPathDedupTest {

    /** claimPauseRound 白盒用例的节点号（与端到端用例的节点号分开，避免静态缓存串台）。 */
    private static final long CLAIM_NODE_ID = 907L;
    private static final String SENTINEL = "@paused_supported";

    /** 端到端用例的节点号：不与既有 compat 用例（1/5/7/8/101/201/202/211/301/501）冲突。 */
    private static final long NODE_ID = 601L;
    private static final long FORWARD_ID = 43L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;
    /** 与 ForwardServiceImpl#buildServiceName 一致：forwardId_userId_userTunnelId（userTunnel 缺失按 0）。 */
    private static final String SERVICE = FORWARD_ID + "_" + USER_ID + "_0";
    private static final String TCP = SERVICE + "_tcp";
    private static final String UDP = SERVICE + "_udp";
    private static final int TCP_PORT = 30111;
    private static final int UDP_PORT = 30112;

    private Method claimPauseRound;
    private Set<Long> pausedDimensionNodes;
    private Map<Long, Long> pauseReconciledEpoch;

    private WebSocketServer server;
    private ForwardService forwardService;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private UserTunnelService userTunnelService;
    private TunnelService tunnelService;
    private SpeedLimitService speedLimitService;
    private ForwardPortService forwardPortService;
    private ForwardMapper forwardMapper;

    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private WebSocketSession session;
    private final List<String> payloads = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        payloads.clear();
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        clearStaticState();

        claimPauseRound = WebSocketServer.class.getDeclaredMethod(
                "claimPauseRound", Long.class, long.class, Map.class);
        claimPauseRound.setAccessible(true);
        pausedDimensionNodes = (Set<Long>) staticField("pausedDimensionNodes");
        pauseReconciledEpoch = (Map<Long, Long>) staticField("pauseReconciledEpoch");

        nodeService = mock(NodeService.class);
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(nodeService.updateById(any(Node.class))).thenReturn(true);

        forwardService = mock(ForwardService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        tunnelService = mock(TunnelService.class);
        speedLimitService = mock(SpeedLimitService.class);
        when(forwardService.list(any())).thenReturn(List.of(forward(0)));
        when(forwardService.getById(any())).thenReturn(forward(0));
        when(chainTunnelService.list(any())).thenReturn(List.of(ingressChain()));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(speedLimitService.resyncNodeLimiters(any())).thenReturn(Collections.emptyList());
        when(speedLimitService.reconcileReportedLimiters(any(), any())).thenReturn(Collections.emptyList());

        server = new WebSocketServer();
        ReflectionTestUtils.setField(server, "nodeService", nodeService);
        ReflectionTestUtils.setField(server, "forwardService", forwardService);
        ReflectionTestUtils.setField(server, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(server, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(server, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(server, "speedLimitService", speedLimitService);

        session = nodeSession(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
        payloads.clear();
        clearStaticState();
    }

    // ==================== 一、判定点白盒（claimPauseRound） ====================

    @Test
    void nodeWithPausedDigestSkipsLegacyPath() throws Exception {
        assertFalse(claim(10L, digests(SENTINEL, "true")),
                "新节点自报 paused 维度时旧补发路径必须让位");
    }

    @Test
    void legacyNodeWithoutSentinelStillRunsLegacyPath() throws Exception {
        assertTrue(claim(10L, digests("21_1_0_tcp", "30200|tcp|-|backend:6365")),
                "老节点没有 paused 维度，旧路径仍需按库兜底");
    }

    @Test
    void sameEpochIsProcessedOnlyOnce() throws Exception {
        Map<String, String> report = digests("21_1_0_tcp", "30200|tcp|-|backend:6365");
        assertTrue(claim(11L, report), "本轮第一次应当补发");
        assertFalse(claim(11L, report), "同一次连接里报表路径不该再补发一遍");
        assertTrue(claim(12L, report), "重连开了新纪元，要重新对账一次");
    }

    @Test
    void cachedCapabilitySurvivesConnectBeforeAnyReport() throws Exception {
        assertFalse(claim(10L, digests(SENTINEL, "true")), "先由上报声明能力");
        assertFalse(claim(10L, null), "建连时还没收到上报，也要按已声明能力跳过旧路径");
    }

    @Test
    void sentinelDisappearingRestoresLegacyPath() throws Exception {
        assertFalse(claim(10L, digests(SENTINEL, "true")), "新节点先声明能力");
        assertTrue(claim(11L, digests("21_1_0_tcp", "30200|tcp|-|backend:6365")),
                "节点回滚成老版本后旧路径要恢复兜底，不能永久失效");
    }

    // ==================== 二、端到端：报文 → 两条路径实际下发 ====================

    @Test
    void sentinelNodeSkipsLegacyPausePathOnReportAndReconnect() throws Exception {
        send(report(List.of(TCP, UDP), List.of("paused"), digestsJson(true, true)));
        settle();

        assertTrue(pauses().isEmpty(), "节点支持 paused 维度时旧路径必须让位，实际发了：" + pauses());
        // chain_tunnel 只有旧路径会查：一次都没查，说明它压根没跑
        verify(chainTunnelService, never()).list(any());
        // 清单已经交给清扫 → resyncNodeServices（新路径）
        verify(forwardService, timeout(3000)).resyncNodeServices(eq(NODE_ID), any(), any(), any(), any());

        // 重连：能力缓存跨轮保留，旧路径仍然不许补（这正是升级后每轮重连都会踩的那条重复）
        WebSocketSession reconnected = nodeSession(NODE_ID);
        server.afterConnectionEstablished(reconnected);
        settle();
        assertTrue(pauses().isEmpty(), "重连后旧路径仍然要跳过，实际发了：" + pauses());
        verify(chainTunnelService, never()).list(any());
    }

    @Test
    void legacyNodeStillRepausesByOldPath() {
        send(report(List.of(TCP, UDP), List.of(), digestsJson(null, null)));
        awaitPauses(1);

        List<JSONObject> found = pauses();
        assertEquals(1, found.size(), "老节点没有 paused 维度，旧路径必须补一次：" + found);
        assertEquals(List.of(TCP, UDP), services(found.get(0)),
                "旧路径按库整对下发 tcp+udp（新路径只发在跑的那一个名字，可据此区分）");
        verify(chainTunnelService, atLeastOnce()).list(any());
    }

    @Test
    void legacyNodeRepausesOncePerReportRound() {
        send(report(List.of(TCP, UDP), List.of(), digestsJson(null, null)));
        send(report(List.of(TCP, UDP), List.of(), digestsJson(null, null)));

        awaitPauses(1);
        settle();
        assertEquals(1, pauses().size(), "同一轮连接里两份上报只该补一次：" + pauses());
    }

    @Test
    void sentinelNodeWithPausedServicesSendsNothing() {
        useRealPauseEnforcement();

        send(report(List.of(TCP, UDP), List.of("paused"), digestsJson(true, true)));

        // forwardPortService 只有真实 ForwardServiceImpl#resyncForwardServices 会用：等它跑完再断言
        verify(forwardPortService, timeout(5000)).list(any());
        settle();
        assertTrue(pauses().isEmpty(), "节点已报 @paused=1，两条路径都不该再发 PauseService：" + pauses());
        verify(chainTunnelService, never()).list(any());
    }

    @Test
    void sentinelNodeRunningServiceIsPausedOnceByNewPath() {
        useRealPauseEnforcement();

        // 节点只报 tcp 在跑（udp 不在 present 里），摘要不带 paused 字段 == 在运行
        send(report(List.of(TCP), List.of("paused"), digestsJson(null, null)));
        awaitPauses(1);
        settle();

        List<JSONObject> found = pauses();
        assertEquals(1, found.size(), "只该有一条 PauseService（旧路径已让位）：" + found);
        assertEquals(List.of(TCP), services(found.get(0)),
                "只发在运行的那一个协议名 ⇒ 来自新路径（旧路径会整对发 tcp+udp）：" + found.get(0));
        verify(chainTunnelService, never()).list(any());
    }

    @Test
    void legacyReportAfterSentinelRoundFallsBackOnce() {
        send(report(List.of(TCP, UDP), List.of("paused"), digestsJson(true, true)));
        settle();
        assertTrue(pauses().isEmpty(), "带哨兵那轮旧路径不该发：" + pauses());

        send(report(List.of(TCP, UDP), List.of(), digestsJson(null, null)));
        awaitPauses(1);
        settle();

        List<JSONObject> found = pauses();
        assertEquals(1, found.size(), "哨兵消失后旧路径要重新兜底，且只补一次：" + found);
        assertEquals(List.of(TCP, UDP), services(found.get(0)));
    }

    // ==================== 三、辅助 ====================

    private boolean claim(long epoch, Map<String, String> digests) throws Exception {
        Object result;
        try {
            result = claimPauseRound.invoke(null, CLAIM_NODE_ID, epoch, digests);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
        return (Boolean) result;
    }

    private static Map<String, String> digests(String key, String value) {
        Map<String, String> digests = new HashMap<>();
        digests.put(key, value);
        return digests;
    }

    /** pausedForTcp/pausedForUdp：null = 不落 paused 字段（在跑），Boolean = 节点显式上报该字段。 */
    private String digestsJson(Boolean pausedForTcp, Boolean pausedForUdp) {
        return "[" + digestEntry(TCP, TCP_PORT, pausedForTcp) + ","
                + digestEntry(UDP, UDP_PORT, pausedForUdp) + "]";
    }

    private String digestEntry(String name, int port, Boolean paused) {
        return "{\"name\":\"" + name + "\",\"addr\":\"[::]:" + port + "\",\"handler\":\"tcp\","
                + "\"targets\":[\"backend:6365\"]"
                + (paused == null ? "" : ",\"paused\":" + paused)
                + "}";
    }

    private String report(List<String> serviceNames, List<String> features, String digestsJson) {
        JSONObject data = new JSONObject();
        data.put("services", new ArrayList<>(serviceNames));
        data.put("chains", new ArrayList<String>());
        data.put("features", new ArrayList<>(features));
        data.put("digests", JSONArray.parseArray(digestsJson));
        JSONObject report = new JSONObject();
        report.put("type", "ServiceListReport");
        report.put("success", true);
        report.put("message", "OK");
        report.put("data", data);
        return report.toJSONString();
    }

    private void send(String payload) {
        server.handleTextMessage(session, new TextMessage(payload));
    }

    private List<JSONObject> pauses() {
        List<JSONObject> found = new ArrayList<>();
        synchronized (payloads) {
            for (String payload : payloads) {
                JSONObject message = JSON.parseObject(payload);
                if ("PauseService".equals(message.getString("type"))) {
                    found.add(message);
                }
            }
        }
        return found;
    }

    private List<String> services(JSONObject message) {
        JSONObject data = message.getJSONObject("data");
        if (data == null || data.getJSONArray("services") == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (Object item : data.getJSONArray("services")) {
            names.add(String.valueOf(item));
        }
        return names;
    }

    /** 等异步补发落地（send_msg 最长等节点 10 秒，异步链路不能只靠固定 sleep）。 */
    private void awaitPauses(int min) {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (pauses().size() >= min) {
                return;
            }
            settle();
        }
    }

    /** 给异步补发留出时间，用于「必须一条都没有」的断言。 */
    private void settle() {
        try {
            Thread.sleep(300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void useRealPauseEnforcement() {
        forwardPortService = mock(ForwardPortService.class);
        forwardMapper = mock(ForwardMapper.class);
        // 一个 forward 在同一个节点上只有一条端口行；给两行会让 enforcePausedService 对同一服务跑两遍
        when(forwardPortService.list(any())).thenReturn(List.of(forwardPort(TCP_PORT)));
        when(forwardMapper.selectById(any())).thenReturn(forward(0));

        ForwardServiceImpl impl = new ForwardServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(impl, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(impl, "nodeService", nodeService);
        ReflectionTestUtils.setField(impl, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(impl, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(impl, "userService", mock(UserService.class));
        // 单独一个实例：旧路径的 chainTunnelService 要保持干净，never() 断言才有意义
        ReflectionTestUtils.setField(impl, "chainTunnelService", mock(ChainTunnelService.class));

        forwardService = impl;
        ReflectionTestUtils.setField(server, "forwardService", impl);
    }

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("paused-dedup");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(status);
        return forward;
    }

    private ForwardPort forwardPort(int port) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(FORWARD_ID);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(port);
        return forwardPort;
    }

    private ChainTunnel ingressChain() {
        ChainTunnel chain = new ChainTunnel();
        chain.setId(1L);
        chain.setNodeId(NODE_ID);
        chain.setTunnelId((long) TUNNEL_ID);
        chain.setChainType(1);
        return chain;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.5");
        node.setPort("30100-30120");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private WebSocketSession nodeSession(long nodeId) throws Exception {
        WebSocketSession mockSession = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", String.valueOf(nodeId));
        attributes.put("type", "1");
        when(mockSession.getAttributes()).thenReturn(attributes);
        when(mockSession.isOpen()).thenReturn(true);
        when(mockSession.getId()).thenReturn("fake-node-" + nodeId);
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            payloads.add(payload);
            JSONObject message = JSON.parseObject(payload);
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto gostDto = new GostDto();
                gostDto.setMsg("OK");
                future.complete(gostDto);
            }
            return null;
        }).when(mockSession).sendMessage(any());
        nodeSessions.put(nodeId, mockSession);
        return mockSession;
    }

    private static void clearStaticState() {
        try {
            ((Map<Long, Long>) staticField("nodeConnectEpoch")).clear();
            ((Map<Long, Long>) staticField("pauseReconciledEpoch")).clear();
            ((Set<Long>) staticField("pausedDimensionNodes")).clear();
            ((Set<Long>) staticField("sweepingNodes")).clear();
            ((Set<Long>) staticField("reconcilingNodes")).clear();
            ((Map<Long, Long>) staticField("lastReportAt")).clear();
            ((Map<Long, Long>) staticField("nodeLastSeen")).clear();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object staticField(String name) throws Exception {
        Field field = WebSocketServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
