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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round46 空清单对账的三处交叉约束（对抗性用例，只读主代码不修改）。
 *
 * <p>被三条互相拉扯的规则同时约束：
 * <ol>
 *   <li>老节点双空清单 = 上报不可信 → 零下发（ForwardServiceImpl.java:2486-2490）；</li>
 *   <li>新节点带 {@code @emptyReport_supported} 哨兵的双空清单 = 配置真空 → 按库全量补齐，
 *       且不得把「库里有、节点报空」误判成「节点不存在」（ForwardServiceImpl.java:2494-2497 只在 node 行缺失时触发）；</li>
 *   <li>补齐时节点可能仍在跑存量服务（gost 无热加载）→ 连续两次 bind 冲突就中断本轮，
 *       但「别的失败」必须把 streak 清零，一次成功也要清零（ForwardServiceImpl.java:2812-2831）。</li>
 * </ol>
 *
 * <p>用例全部经 {@code resyncNodeServices} 的公开入口驱动，断言只落在两处可观测证据上：
 * 下发的 WebSocket 报文（WebSocketServer.send_msg → session.sendMessage）与返回的 notes。
 */
class Round46CrossCheckTest {

    private static final long NODE_ID = 201L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;

    /** WebSocketServer#toDigestMap 写出的哨兵键名，逐字对齐生产代码（ForwardServiceImpl.java:64）。 */
    private static final String EMPTY_REPORT_SENTINEL = "@emptyReport_supported";

    private static final String BIND_IN_USE =
            "create service 101_1_0_tcp failed: listen tcp 0.0.0.0:30101: bind: address already in use";

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
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> forward((Long) invocation.getArgument(0)));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(userTunnelService.getOne(any())).thenReturn(null);
        when(forwardPortService.list(any())).thenAnswer(invocation -> List.of(forwardPort(21L, 30111)));
        // 链资源一律留空：本组只考入口服务这条路径
        when(chainTunnelService.list(any())).thenReturn(List.of());

        registerNode(NODE_ID);
        replyNodeCommands(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("双空清单但摘要里没有 @emptyReport_supported：零下发零 notes（摘要非 null 也不算数）")
    void emptyReportWithoutSentinelStaysIgnored() throws Exception {
        // 摘要非 null、只是没有哨兵键：这是最容易误判的一种形状，必须仍然当「不可信上报」丢掉
        Map<String, String> digests = new HashMap<>();
        digests.put("21_1_0_tcp", "addrs=0.0.0.0:30111");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(), digests, null);

        assertTrue(notes.isEmpty(), "没有哨兵就没有按库补齐的授权，实际：" + notes);
        assertTrue(sentMessages(NODE_ID).isEmpty(), "零下发，实际：" + typeNames(sentMessages(NODE_ID)));
        verify(forwardPortService, never()).list(any());
    }

    @Test
    @DisplayName("哨兵 + 双空清单：按库补齐两条转发，且 notes 不得出现「节点不存在」误报")
    void emptyReportWithSentinelRebuildsAndNeverClaimsNodeMissing() throws Exception {
        when(forwardPortService.list(any())).thenReturn(List.of(
                forwardPort(101L, 30101), forwardPort(102L, 30102)));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests(), null);

        assertEquals(2, notes.size(), "两条缺失转发各一条补齐说明，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失服务 101_1_0@node201"), "实际：" + notes);
        assertTrue(notes.get(1).startsWith("已按库补齐缺失服务 102_1_0@node201"), "实际：" + notes);
        // 节点行在库里、只是清单为空：任何「不存在/节点不存在」文案都是误报
        assertFalse(hasNoteContaining(notes, "不存在"), "空清单不得被误判成节点缺失，实际：" + notes);
        assertEquals(List.of("AddService", "AddService"), typeNames(sentMessages(NODE_ID)),
                "实际：" + typeNames(sentMessages(NODE_ID)));
        assertEquals(List.of("101_1_0_tcp", "101_1_0_udp", "102_1_0_tcp", "102_1_0_udp"),
                addedServiceNames(NODE_ID), "tcp/udp 必须整对补齐");
    }

    @Test
    @DisplayName("连续两次 bind 冲突：中断本轮，第三个转发一次都不下发")
    void twoConsecutiveBindFailuresStopBeforeThirdForward() throws Exception {
        when(forwardPortService.list(any())).thenReturn(List.of(
                forwardPort(101L, 30101), forwardPort(102L, 30102), forwardPort(103L, 30103)));
        replyNodeCommands(NODE_ID, BIND_IN_USE);

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests(), null);

        assertEquals(2, addServiceMessageCount(NODE_ID),
                "第二次 bind 失败后必须 break，第三次不得下发，实际下发：" + addedServiceNames(NODE_ID));
        assertFalse(addedServiceNames(NODE_ID).contains("103_1_0_tcp"), "实际：" + addedServiceNames(NODE_ID));
        assertTrue(hasNoteContaining(notes, "已中断本轮对账"), "必须有中断说明，实际：" + notes);
        assertTrue(hasNoteContaining(notes, "连续2次端口被占用"), "中断文案要报实际 streak，实际：" + notes);
        assertFalse(hasNoteContaining(notes, "连续3次"), "streak 到 2 就该停，实际：" + notes);
    }

    @Test
    @DisplayName("bind 失败后换成别的失败原因：streak 归零，后续转发继续处理")
    void nonBindFailureResetsStreakAndKeepsGoing() throws Exception {
        when(forwardPortService.list(any())).thenReturn(List.of(
                forwardPort(101L, 30101), forwardPort(102L, 30102), forwardPort(103L, 30103)));
        // 第一次 bind 冲突，后面两次是「服务查不到」这类与端口无关的失败
        replyNodeCommands(NODE_ID, BIND_IN_USE, "service 102_1_0 not found", "service 103_1_0 not found");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests(), null);

        assertEquals(3, addServiceMessageCount(NODE_ID),
                "bind 失败后一次非 bind 失败必须清零 streak，三个转发都要试，实际：" + addedServiceNames(NODE_ID));
        assertFalse(hasNoteContaining(notes, "已中断本轮对账"),
                "非端口占用不该触发中断，实际：" + notes);
        assertTrue(hasNoteContaining(notes, "service 103_1_0 not found"), "实际：" + notes);
    }

    @Test
    @DisplayName("一次成功后再两次 bind 才中断：成功必须把 streak 清零（中断文案为连续2次）")
    void successResetsStreakBeforeCountingAgain() throws Exception {
        when(forwardPortService.list(any())).thenReturn(List.of(
                forwardPort(101L, 30101), forwardPort(102L, 30102),
                forwardPort(103L, 30103), forwardPort(104L, 30104)));
        // 101 bind 失败(1) → 102 成功(清零) → 103 bind(1) → 104 bind(2, 中断)
        // 若成功不清零，103 那次就会累计到 2 直接中断，104 根本不会被尝试
        replyNodeCommands(NODE_ID, BIND_IN_USE, "OK", BIND_IN_USE, BIND_IN_USE);

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests(), null);

        assertEquals(4, addServiceMessageCount(NODE_ID),
                "成功清零后必须还要再累计两次才中断，104 应被尝试，实际：" + addedServiceNames(NODE_ID));
        assertTrue(notes.contains("已按库补齐缺失服务 102_1_0@node201（端口30102）"), "实际：" + notes);
        assertTrue(hasNoteContaining(notes, "已中断本轮对账"), "实际：" + notes);
        assertTrue(hasNoteContaining(notes, "连续2次端口被占用"),
                "中断时的 streak 必须是 2（成功已清零），实际：" + notes);
        String last = notes.get(notes.size() - 1);
        assertTrue(last.contains("已中断本轮对账"),
                "中断说明必须是本轮最后一条，实际：" + notes);
    }

    @Test
    @DisplayName("连续两次节点无响应（空回执）：中断本轮，不再逐个转发等超时")
    void twoConsecutiveTimeoutsStopRound() throws Exception {
        when(forwardPortService.list(any())).thenReturn(List.of(
                forwardPort(101L, 30101), forwardPort(102L, 30102), forwardPort(103L, 30103)));
        // 空 msg 是 GostUtil 等回执超时的兜底形状：节点卡死/通道拥堵时每条都要等一次
        replyNodeCommands(NODE_ID, "", "");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests(), null);

        assertEquals(2, addServiceMessageCount(NODE_ID),
                "第二次无响应后必须 break，第三条不得下发，实际：" + addedServiceNames(NODE_ID));
        assertTrue(hasNoteContaining(notes, "连续2次节点无响应"), "实际：" + notes);
        assertTrue(hasNoteContaining(notes, "已中断本轮对账"), "实际：" + notes);
    }

    // ---------------------------------------------------------------- 工具

    private static boolean hasNoteContaining(List<String> notes, String needle) {
        return notes.stream().anyMatch(note -> note != null && note.contains(needle));
    }

    private Map<String, String> emptyReportDigests() {
        Map<String, String> digests = new HashMap<>();
        digests.put(EMPTY_REPORT_SENTINEL, "1");
        return digests;
    }

    private Forward forward(long forwardId) {
        Forward forward = new Forward();
        forward.setId(forwardId);
        forward.setName("round46-cross-" + forwardId);
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(1);
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

    private ForwardPort forwardPort(long forwardId, int port) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(forwardId);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(port);
        return forwardPort;
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
        when(session.getId()).thenReturn("round46-fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        nodeSessions.put(nodeId, session);
    }

    /**
     * 按次序回放节点响应：第 N 条下发命令用第 N 个回执，超出的重复最后一个。
     * 每次调用重装一次 answer，所以 {@code @BeforeEach} 里的默认 "OK" 会被整体替换。
     */
    private void replyNodeCommands(long nodeId, String... replies) throws Exception {
        WebSocketSession session = nodeSessions.get(nodeId);
        // 不传回执时等价于"节点一切正常"，避免空数组造成下标越界
        String[] script = replies.length == 0 ? new String[]{"OK"} : replies;
        AtomicInteger index = new AtomicInteger();
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            String requestId = message.getString("requestId");
            if (requestId == null) {
                return null;
            }
            CompletableFuture<GostDto> future = pendingRequests.get(requestId);
            if (future != null) {
                int at = Math.min(index.getAndIncrement(), script.length - 1);
                GostDto reply = new GostDto();
                reply.setMsg(script[at]);
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
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

    private int addServiceMessageCount(long nodeId) throws Exception {
        int count = 0;
        for (JSONObject message : sentMessages(nodeId)) {
            if ("AddService".equals(message.getString("type"))) {
                count++;
            }
        }
        return count;
    }

    private List<String> addedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
            if (!"AddService".equals(message.getString("type"))) {
                continue;
            }
            JSONArray services = message.getJSONArray("data");
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
