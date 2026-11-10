package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.ForwardServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「节点确认资源全空」上报的对账口径回归（round46 主线 A 的 Java 侧）。
 *
 * <p>覆盖的原缺口：resyncNodeServices 把 services/chains 双空一律当成"这份上报不可信"直接丢弃。
 * 对老节点成立（它干脆不上报），但新节点（round46 起）在配置真的全空时照样上报，
 * 并在 features 里声明 emptyReport，WebSocketServer 因此会写下 {@code @emptyReport_supported} 哨兵。
 *
 * <p>要求：
 * 1. 没有哨兵的双空清单保持老行为，零下发零说明（回归保护）；
 * 2. 只有哨兵、清单双空时按库全量对账，缺什么补什么；
 * 3. 哨兵在但节点行已删，只记说明不动节点；
 * 4. 摘要为 null（老调用方）同样保持老行为；
 * 5. 库内本来就没有任何转发/链时，不得凭空造资源。
 */
class EmptyReportResyncTest {

    private static final long NODE_ID = 201L;
    private static final long FORWARD_ID = 21L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;
    private static final int PORT = 30111;

    /** WebSocketServer#toDigestMap 写出的哨兵键名，逐字对齐生产代码。 */
    private static final String EMPTY_REPORT_SENTINEL = "@emptyReport_supported";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
    private ChainTunnelService chainTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);

        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(forwardMapper.selectById(any())).thenReturn(forward(1));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(userTunnelService.getOne(any())).thenReturn(null);
        when(forwardPortService.list(any())).thenAnswer(invocation -> List.of(forwardPort(PORT)));
        // 本组用例只关心中继转发入口服务那条分支：库里没有任何中继拓扑
        when(chainTunnelService.list(any())).thenReturn(List.of());

        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("老节点没有哨兵：双空清单仍然零下发零说明")
    void oldNodeEmptyReportStaysIgnored() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of());

        assertTrue(notes.isEmpty(), "老节点双空清单不得产生任何处置，实际：" + notes);
        assertTrue(sentMessages(NODE_ID).isEmpty(), "零下发，实际：" + typeNames(sentMessages(NODE_ID)));
        verify(forwardPortService, never()).list(any());
    }

    @Test
    @DisplayName("哨兵 + 双空清单：按库补齐（发出 AddService，note 非空）")
    void emptyReportWithSentinelRebuildsFromDatabase() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests());

        assertEquals(1, notes.size(), "应补一对入口服务并给一条说明，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失服务 21_1_0@node201"), "实际：" + notes);
        assertEquals(List.of("AddService"), typeNames(sentMessages(NODE_ID)),
                "节点上报全空时只能补服务，实际：" + typeNames(sentMessages(NODE_ID)));
        assertEquals(List.of("21_1_0_tcp", "21_1_0_udp"), addedServiceNames(NODE_ID), "tcp/udp 整对补齐");
    }

    @Test
    @DisplayName("哨兵在但节点行已删：只记说明，不动节点")
    void sentinelButNodeMissingIsReported() throws Exception {
        // thenReturn 会先调用既有 answer（argument 为 null）→ NPE，必须用 doReturn 覆盖
        doReturn(null).when(nodeService).getById(any());

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests());

        assertEquals(List.of("节点201 不存在，跳过补齐"), notes, "实际：" + notes);
        assertTrue(sentMessages(NODE_ID).isEmpty(), "节点不存在时零下发");
    }

    @Test
    @DisplayName("摘要为 null（老调用方）：双空清单保持零下发")
    void emptyReportWithoutDigestMapKeepsOldBehavior() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(), null);

        assertTrue(notes.isEmpty(), "没有摘要就没有哨兵，双空清单仍不可信，实际：" + notes);
        assertTrue(sentMessages(NODE_ID).isEmpty(), "零下发，实际：" + typeNames(sentMessages(NODE_ID)));
        verify(forwardPortService, never()).list(any());
    }

    @Test
    @DisplayName("哨兵 + 双空清单 + 库里也没有任何转发：不凭空造资源")
    void emptyReportAgainstEmptyDatabaseMakesNothing() throws Exception {
        when(forwardMapper.selectList(any())).thenReturn(List.of());
        when(forwardPortService.list(any())).thenReturn(List.of());

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests());

        assertTrue(notes.isEmpty(), "库里没有资源时不该有任何处置，实际：" + notes);
        assertTrue(sentMessages(NODE_ID).isEmpty(), "零下发，实际：" + typeNames(sentMessages(NODE_ID)));
    }

    @Test
    @DisplayName("连续两次端口占用导致补齐失败：中断本轮对账，不再逐个转发试")
    void consecutiveBindFailuresAbortTheRound() throws Exception {
        // 空清单但节点实际还在跑（gost 无热加载）：每个补齐都会 bind 冲突。
        // 不能在第三、第四个转发上继续试一遍，更不能把每个都记进熔断器。
        when(forwardPortService.list(any())).thenReturn(List.of(
                forwardPort(101L, 30101), forwardPort(102L, 30102), forwardPort(103L, 30103)));
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> {
            long forwardId = (Long) invocation.getArgument(0);
            return forward(forwardId, 1);
        });
        failNodeCommands(NODE_ID, "create service 101_1_0_tcp failed: listen tcp 0.0.0.0:30101: bind: address already in use");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests());

        assertTrue(notes.stream().anyMatch(note -> note.contains("已中断本轮对账")),
                "连续端口占用必须中断本轮，实际：" + notes);
        long attempted = addedServiceNames(NODE_ID).stream().distinct().count();
        assertTrue(attempted <= 4, "最多试两个转发（各 tcp/udp 一对），实际：" + addedServiceNames(NODE_ID));
    }

    @Test
    @DisplayName("WebSocketServer.toDigestMap：features 声明 emptyReport 时摘要为空也要带哨兵")
    void toDigestMapEmitsSentinelWhenEmptyReportDeclared() throws Exception {
        Method toDigestMap = Class.forName("com.admin.common.utils.WebSocketServer")
                .getDeclaredMethod("toDigestMap", JSONArray.class, Set.class);
        toDigestMap.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> withFeature =
                (Map<String, String>) toDigestMap.invoke(null, new JSONArray(), Set.of("emptyReport"));
        assertEquals("1", withFeature.get(EMPTY_REPORT_SENTINEL),
                "声明 emptyReport 的节点即使摘要列表为空也必须带哨兵");

        assertNull(toDigestMap.invoke(null, new JSONArray(), Set.of()),
                "老节点没有声明、摘要又为空 → 仍是 null（这份清单不可信）");
        assertNull(toDigestMap.invoke(null, new JSONArray(), null),
                "能力清单缺失（老入口）→ 仍是 null");
    }

    // ---------------------------------------------------------------- 工具

    private Map<String, String> emptyReportDigests() {
        Map<String, String> digests = new HashMap<>();
        digests.put(EMPTY_REPORT_SENTINEL, "1");
        return digests;
    }

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("empty-report-resync");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(status);
        return forward;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private ForwardPort forwardPort(int port) {
        return forwardPort(FORWARD_ID, port);
    }

    private ForwardPort forwardPort(long forwardId, int port) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(forwardId);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(port);
        return forwardPort;
    }

    private Forward forward(long forwardId, int status) {
        Forward forward = forward(status);
        forward.setId(forwardId);
        return forward;
    }

    /** 让该节点的所有命令都回同一条失败信息（模拟端口占用）。 */
    private void failNodeCommands(long nodeId, String msg) throws Exception {
        WebSocketSession session = nodeSessions.get(nodeId);
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            String requestId = message.getString("requestId");
            if (requestId == null) {
                return null;
            }
            CompletableFuture<GostDto> future = pendingRequests.get(requestId);
            if (future != null) {
                GostDto reply = new GostDto();
                reply.setMsg(msg);
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
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

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            String requestId = message.getString("requestId");
            if (requestId == null) {
                return null;
            }
            CompletableFuture<GostDto> future = pendingRequests.get(requestId);
            if (future != null) {
                GostDto reply = new GostDto();
                reply.setMsg("OK");
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private List<JSONObject> sentMessages(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private List<String> addedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
            if (!"AddService".equals(message.getString("type"))) {
                continue;
            }
            com.alibaba.fastjson.JSONArray services = message.getJSONArray("data");
            for (int i = 0; i < services.size(); i++) {
                names.add(services.getJSONObject(i).getString("name"));
            }
        }
        return names;
    }

    private static List<String> typeNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
            names.add(message.getString("type"));
        }
        return names;
    }
}
