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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * resyncForwardServices 单轮修复预算回归（round47）。
 *
 * <p>覆盖的原缺口：round46 让节点在配置真的全空时也上报（features 含 emptyReport，后端据此写
 * {@code @emptyReport_supported} 哨兵）。这条路径不再受「清单双空=不可信」保护，节点配置被清空、
 * 库里又有大量活跃 forward 时，一轮会连续下发几十上百个 AddService（真机 10 个、无速率控制），
 * 把节点 WS 通道与 gost 配置写入打满。
 *
 * <p>要求：
 * 1. 单轮最多下发 {@code MAX_REPAIRS_PER_ROUND}=30 个真实动作，超出即停手；
 * 2. 「已经一致」不占预算，否则一致的服务越多越修不动真正缺的；
 * 3. 预算耗尽时 notes 追加「本轮已修复 N 条，其余 M 条下轮继续（单轮上限 30）」，M 按剩余 forward 数真实计数；
 * 4. 预算不落库：下一次 resync（5 分钟后 info_request）从零开始，把剩下的条目续完；
 * 5. 暂停纠正（清理旧名 / 补暂停）与整对补齐共用同一份预算。
 */
class ResyncBudgetTest {

    private static final long NODE_ID = 201L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;

    /** 生产代码里的单轮上限，逐字对齐 ForwardServiceImpl.MAX_REPAIRS_PER_ROUND。 */
    private static final int BUDGET = 30;

    /** WebSocketServer#toDigestMap 写出的哨兵键名。 */
    private static final String EMPTY_REPORT_SENTINEL = "@emptyReport_supported";
    /** 节点自报摘要里的暂停能力哨兵。 */
    private static final String PAUSED_SUPPORTED = "@paused_supported";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
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

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", mock(ChainTunnelService.class));

        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> forward((Long) invocation.getArgument(0), 1));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(userTunnelService.getOne(any())).thenReturn(null);
        when(forwardPortService.list(any())).thenAnswer(invocation -> ports(BUDGET));
        // 本组用例只关心中继转发入口服务那条分支：库里没有任何中继拓扑
        ChainTunnelService chainTunnelService = (ChainTunnelService) ReflectionTestUtils.getField(
                forwardService, "chainTunnelService");
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
    @DisplayName("40 条缺失服务：一轮只下发 30 次，notes 说明剩余条目下轮继续")
    void fortyMissingServicesAreCappedAtThirty() throws Exception {
        when(forwardPortService.list(any())).thenReturn(ports(40));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests());

        assertEquals(BUDGET, addServiceCount(NODE_ID),
                "单轮最多下发 " + BUDGET + " 个真实动作（40 条里只补 30 条），实际：" + addServiceCount(NODE_ID));
        assertTrue(notes.stream().anyMatch(note -> note.contains("单轮上限 " + BUDGET)),
                "预算耗尽必须在 notes 里说明上限，实际：" + notes);
        assertTrue(notes.stream().anyMatch(note -> note.contains("本轮已修复 " + BUDGET + " 条，其余 10 条下轮继续")),
                "M 要按本轮剩余待处理 forward 数真实计数，实际：" + notes);
        assertEquals(BUDGET + 1, notes.size(), "30 条补齐说明 + 1 条预算说明，实际：" + notes);
    }

    @Test
    @DisplayName("第二次 resync 继续下发剩余 10 条：预算只在单次调用内计数，不落库")
    void secondRoundContinuesRemainingForwards() throws Exception {
        when(forwardPortService.list(any())).thenReturn(ports(40));

        List<String> firstRound = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests());
        assertTrue(firstRound.stream().anyMatch(note -> note.contains("单轮上限 " + BUDGET)),
                "第一轮应被预算截断，实际：" + firstRound);
        assertEquals(BUDGET, addServiceCount(NODE_ID), "第一轮只补 30 条");

        // 5 分钟后的下一轮：节点已把上一轮补好的 30 对服务报上来，只剩第 31-40 条要补
        List<String> firstRoundPresent = pairNames(1, BUDGET);
        List<String> secondRound = forwardService.resyncNodeServices(NODE_ID, firstRoundPresent, List.of());

        assertEquals(BUDGET + 10, addServiceCount(NODE_ID),
                "第二轮必须把剩余 10 条补完（预算已重置），实际：" + addServiceCount(NODE_ID));
        assertEquals(10, secondRound.size(), "第二轮只应有 10 条补齐说明，实际：" + secondRound);
        assertFalse(secondRound.stream().anyMatch(note -> note.contains("单轮上限")),
                "剩余条数没超上限就不该出现预算提示，实际：" + secondRound);
        assertTrue(secondRound.stream().allMatch(note -> note.startsWith("已按库补齐缺失服务")),
                "已一致的 30 条不占预算也不产生说明，实际：" + secondRound);
    }

    @Test
    @DisplayName("全部一致：零下发、notes 为空（一致的服务不占预算也不产生说明）")
    void allConsistentMakesNoDispatch() throws Exception {
        when(forwardPortService.list(any())).thenReturn(ports(40));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, pairNames(1, 40), List.of());

        assertTrue(notes.isEmpty(), "服务全在就不该有任何处置，实际：" + notes);
        assertEquals(0, addServiceCount(NODE_ID), "零下发，实际：" + addServiceCount(NODE_ID));
    }

    @Test
    @DisplayName("恰好 30 条缺失：用满预算但不下「下轮继续」提示")
    void exactlyBudgetNeedsNoContinuationNote() throws Exception {
        when(forwardPortService.list(any())).thenReturn(ports(BUDGET));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of(), List.of(),
                emptyReportDigests());

        assertEquals(BUDGET, addServiceCount(NODE_ID), "恰好 30 条应全部补齐，实际：" + addServiceCount(NODE_ID));
        assertEquals(BUDGET, notes.size(), "30 条补齐说明，且不该有预算提示，实际：" + notes);
        assertFalse(notes.stream().anyMatch(note -> note.contains("下轮继续")),
                "恰好用满上限时没有剩余条目，不该提示下轮继续，实际：" + notes);
        assertFalse(notes.stream().anyMatch(note -> note.contains("单轮上限")), "实际：" + notes);
    }

    @Test
    @DisplayName("暂停纠正与整对补齐共用同一份预算")
    void pausedCorrectionSharesTheSameBudget() throws Exception {
        // 前 20 条是暂停转发（节点上仍在放量 → 各占一次 PauseService 下发），后 20 条是整对缺失
        when(forwardPortService.list(any())).thenReturn(ports(40));
        // setUp 里已经有 thenAnswer：再用 when(...) 重打桩会先以 null 参数调用既有 answer 直接 NPE，
        // 必须走 doAnswer 覆盖（同 EmptyReportResyncTest 对 getById 的处理）
        doAnswer(invocation -> {
            long forwardId = (Long) invocation.getArgument(0);
            return forward(forwardId, forwardId <= 20 ? 0 : 1);
        }).when(forwardMapper).selectById(any());

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, pairNames(1, 20), List.of(),
                pausedDigests());

        assertEquals(20, pausedServiceCount(NODE_ID), "20 条暂停纠正各占一个额度，实际：" + pausedServiceCount(NODE_ID));
        assertEquals(10, addServiceCount(NODE_ID),
                "剩 10 个额度只能补 10 条整对（不是 20 条），实际：" + addServiceCount(NODE_ID));
        assertTrue(notes.stream().anyMatch(note -> note.contains("单轮上限 " + BUDGET)), "实际：" + notes);
        assertTrue(notes.stream().anyMatch(note -> note.contains("本轮已修复 " + BUDGET + " 条，其余 10 条下轮继续")),
                "暂停纠正占掉的额度要算进 N，剩余 forward 数算进 M，实际：" + notes);
    }

    // ---------------------------------------------------------------- 工具

    private Map<String, String> emptyReportDigests() {
        Map<String, String> digests = new HashMap<>();
        digests.put(EMPTY_REPORT_SENTINEL, "1");
        return digests;
    }

    /** 只带暂停能力哨兵：节点没报 paused 键 == 服务在跑（与生产口径一致）。 */
    private Map<String, String> pausedDigests() {
        Map<String, String> digests = new HashMap<>();
        digests.put(PAUSED_SUPPORTED, "true");
        return digests;
    }

    private Forward forward(long forwardId, int status) {
        Forward forward = new Forward();
        forward.setId(forwardId);
        forward.setName("budget-" + forwardId);
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

    private List<ForwardPort> ports(int count) {
        List<ForwardPort> ports = new ArrayList<>();
        for (long forwardId = 1; forwardId <= count; forwardId++) {
            ForwardPort forwardPort = new ForwardPort();
            forwardPort.setForwardId(forwardId);
            forwardPort.setNodeId(NODE_ID);
            forwardPort.setPort(30100 + (int) forwardId);
            ports.add(forwardPort);
        }
        return ports;
    }

    /** serviceName = forwardId_userId_userTunnelId（userTunnel 缺失时尾段为 0）。 */
    private List<String> pairNames(int fromForwardId, int toForwardId) {
        List<String> names = new ArrayList<>();
        for (long forwardId = fromForwardId; forwardId <= toForwardId; forwardId++) {
            names.add(forwardId + "_" + USER_ID + "_0_tcp");
            names.add(forwardId + "_" + USER_ID + "_0_udp");
        }
        return names;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.5");
        node.setPort("30100-30160");
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

    /** 一轮真实下发 = 一条 AddService（整对 tcp/udp 在同一个 data 数组里）。 */
    private int addServiceCount(long nodeId) throws Exception {
        int count = 0;
        for (JSONObject message : sentMessages(nodeId)) {
            if ("AddService".equals(message.getString("type"))) {
                count++;
            }
        }
        return count;
    }

    private int pausedServiceCount(long nodeId) throws Exception {
        int count = 0;
        for (JSONObject message : sentMessages(nodeId)) {
            if ("PauseService".equals(message.getString("type"))) {
                count++;
            }
        }
        return count;
    }
}
