package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
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
 * 中继链 selector（负载策略）漂移对账的回归测试（round36）。
 *
 * <p>覆盖的原缺口：round34 的链漂移只比下一跳节点列表。节点上的 selector.strategy 从 round 被改成
 * fifo、maxFails / failTimeout 被改时，跳点列表完全一致，对账判「一致」，负载均衡策略静默漂移。
 *
 * <p>口径要求：
 * 1. 节点上报 {@code selector=<strategy>|<maxFails>|<failTimeout>} 时才比策略，期望值按
 *    {@code GostUtil.CreateChains} 的下发口径算（strategy 取下一跳首行、maxFails=1、
 *    failTimeout=600000000000，见 GostUtil.java:97-103）；
 * 2. 老节点不报 selector 时完全退回「只比下一跳」，绝不因此判漂移；
 * 3. 跳点与策略都一致才算一致；跳点不同时文案以跳点为主；
 * 4. 漂移一律走 DeleteChains → AddChains → 重挂引用这条链的服务。
 */
class ChainSelectorDriftTest {

    private static final long TUNNEL_ID = 16L;
    private static final long CHAIN_NODE = 2L;
    private static final int CHAIN_PORT = 30201;
    private static final int OUT_PORT = 30202;

    /** 节点上报的下一跳（出口 node_3），与 chainRows 里出口行一致。 */
    private static final String HOP_OUT = "node_3@10.0.0.3:" + OUT_PORT + "#tcp";
    /** 节点上报的错误跳点（历史 node_null 事故现场的形状）。 */
    private static final String HOP_WRONG = "node_9@10.0.0.9:39999#tcp";
    private static final String SELECTOR_OK = "selector=round|1|600000000000";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    private String chainStrategy;
    private String chainReply;
    private String deleteChainReply;
    private String deleteReply;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        chainStrategy = "round";
        chainReply = "OK";
        deleteChainReply = "OK";
        deleteReply = "OK";

        forwardMapper = mock(ForwardMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));

        when(forwardMapper.selectList(any())).thenReturn(List.of(forward()));
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainRows());
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(nodeService.listByIds(any())).thenAnswer(invocation -> {
            List<Node> loaded = new ArrayList<>();
            for (Long id : (Iterable<Long>) invocation.getArgument(0)) {
                loaded.add(node(id));
            }
            return loaded;
        });
        when(forwardPortService.list(any())).thenReturn(List.of());
        when(tunnelService.getById(any())).thenReturn(tunnel());

        registerNode(CHAIN_NODE);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void matchingSelectorLeavesChainUntouched() throws Exception {
        List<String> notes = resync(List.of(HOP_OUT, SELECTOR_OK));

        assertTrue(notes.isEmpty(), "跳点与 selector 都一致就不该有任何处置，实际：" + notes);
        assertTrue(sentMessages().isEmpty(), "零下发，实际：" + typeNames(sentMessages()));
    }

    @Test
    void strategyDriftRebuildsChainAndRebindsRelayService() throws Exception {
        List<String> notes = resync(List.of(HOP_OUT, "selector=fifo|1|600000000000"));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages()),
                "策略漂移必须删链重建并重挂引用这条链的服务，实际：" + typeNames(sentMessages()));
        assertTrue(notes.stream().anyMatch(n -> n.contains("中继链 chains_16@node2 负载策略漂移"
                        + "（库 selector=round|1|600000000000 / 节点 selector=fifo|1|600000000000）")),
                "漂移说明要带两侧 selector，实际：" + notes);
        assertTrue(notes.stream().anyMatch(n -> n.contains("已按库重建漂移中继链 chains_16@node2")),
                "重建结果要显式上报，实际：" + notes);

        JSONObject rebuild = sentMessages().get(1).getJSONObject("data");
        assertEquals("chains_16", rebuild.getString("name"));
        assertEquals("round", rebuild.getJSONArray("hops").getJSONObject(0).getJSONObject("selector")
                .getString("strategy"), "重建后的策略必须是库里的 round，不是节点上的 fifo");
        assertEquals(1, rebuild.getJSONArray("hops").getJSONObject(0).getJSONObject("selector")
                .getIntValue("maxFails"), "maxFails 下发口径恒为 1");
    }

    @Test
    void missingSelectorKeepsRound34Behaviour() throws Exception {
        // 老节点只报跳点：跳点一致就一致，绝不能凭缺失的 selector 判漂移
        List<String> notes = resync(List.of(HOP_OUT));

        assertTrue(notes.isEmpty(), "老节点不报 selector 时只比跳点，实际：" + notes);
        assertTrue(sentMessages().isEmpty(), "零下发，实际：" + typeNames(sentMessages()));
    }

    @Test
    void missingSelectorStillDetectsHopDrift() throws Exception {
        List<String> notes = resync(List.of(HOP_WRONG));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages()), "跳点漂移仍要重建，实际：" + typeNames(sentMessages()));
        assertTrue(notes.stream().anyMatch(n -> n.contains("中继链 chains_16@node2 配置漂移（库 " + HOP_OUT
                + " / 节点 " + HOP_WRONG + "）")), "跳点漂移文案沿用 round34，实际：" + notes);
        assertFalse(notes.stream().anyMatch(n -> n.contains("负载策略漂移")),
                "节点没报 selector 时不该出现策略漂移文案，实际：" + notes);
    }

    @Test
    void maxFailsDriftRebuildsChain() throws Exception {
        List<String> notes = resync(List.of(HOP_OUT, "selector=round|3|600000000000"));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages()), "maxFails 漂移也要重建，实际：" + typeNames(sentMessages()));
        assertTrue(notes.stream().anyMatch(n -> n.contains("负载策略漂移（库 selector=round|1|600000000000"
                + " / 节点 selector=round|3|600000000000）")), "maxFails 漂移要写进说明，实际：" + notes);
    }

    @Test
    void failTimeoutDriftRebuildsChain() throws Exception {
        List<String> notes = resync(List.of(HOP_OUT, "selector=round|1|3000000000"));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages()), "failTimeout 漂移也要重建，实际：" + typeNames(sentMessages()));
        assertTrue(notes.stream().anyMatch(n -> n.contains("负载策略漂移（库 selector=round|1|600000000000"
                + " / 节点 selector=round|1|3000000000）")), "failTimeout 漂移要写进说明，实际：" + notes);
    }

    @Test
    void hopDriftTakesPrecedenceOverSelectorDrift() throws Exception {
        List<String> notes = resync(List.of(HOP_WRONG, "selector=fifo|1|600000000000"));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages()), "两处都漂移仍只重建一次，实际：" + typeNames(sentMessages()));
        assertTrue(notes.stream().anyMatch(n -> n.contains("中继链 chains_16@node2 配置漂移（库 " + HOP_OUT
                + " / 节点 " + HOP_WRONG + "）")), "跳点漂移为主文案，实际：" + notes);
        assertFalse(notes.stream().anyMatch(n -> n.contains("负载策略漂移")),
                "跳点不同时不再追策略，避免一条链报两个漂移，实际：" + notes);
        assertFalse(String.join("；", notes).contains("selector="),
                "跳点漂移说明只能带跳点，不能把 selector 元素混进节点侧，实际：" + notes);
    }

    @Test
    void blankStrategyExpectsDash() throws Exception {
        chainStrategy = null;
        List<String> notes = resync(List.of(HOP_OUT, "selector=-|1|600000000000"));

        assertTrue(notes.isEmpty(), "库里 strategy 为空时期望值按约定写 -，实际：" + notes);
        assertTrue(sentMessages().isEmpty(), "零下发，实际：" + typeNames(sentMessages()));
    }

    @Test
    void blankStrategyDriftIsReportedWithDash() throws Exception {
        chainStrategy = "  ";
        List<String> notes = resync(List.of(HOP_OUT, "selector=round|1|600000000000"));

        assertTrue(notes.stream().anyMatch(n -> n.contains("负载策略漂移（库 selector=-|1|600000000000"
                + " / 节点 selector=round|1|600000000000）")), "空白 strategy 的期望值必须是 -|1|…，实际：" + notes);
    }

    /** WebSocketServer 解析层：selector 非空白才作为列表末尾的 selector= 元素带出去。 */
    @Test
    void chainDigestMapAppendsSelectorOnlyWhenReported() throws Exception {
        JSONArray array = new JSONArray();
        array.add(chainDigest("chains_16", new String[]{HOP_OUT}, "round|1|600000000000"));
        array.add(chainDigest("chains_17", new String[]{HOP_OUT}, null));
        array.add(chainDigest("chains_18", new String[]{HOP_OUT}, "   "));

        Map<String, List<String>> digests = toChainDigestMap(array);

        assertEquals(List.of(HOP_OUT, SELECTOR_OK), digests.get("chains_16"), "报了 selector 就要追加到末尾");
        assertEquals(List.of(HOP_OUT), digests.get("chains_17"), "整条链没 selector 时字段缺省，不得追加");
        assertEquals(List.of(HOP_OUT), digests.get("chains_18"), "空白 selector 视为没报");
    }

    /** 节点没报 nodes 只报了 selector 时，toStringList 给的是不可变空列表，追加不能炸。 */
    @Test
    void chainDigestMapHandlesSelectorWithoutNodes() throws Exception {
        JSONArray array = new JSONArray();
        array.add(chainDigest("chains_16", null, "round|1|600000000000"));

        Map<String, List<String>> digests = toChainDigestMap(array);

        assertEquals(List.of(SELECTOR_OK), digests.get("chains_16"), "空节点列表也要能追加 selector");
    }

    // ---------------------------------------------------------------- 工具

    private List<String> resync(List<String> reportedChainDigest) {
        return forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                Map.of(), Map.of("chains_16", reportedChainDigest));
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(21L);
        forward.setName("selector-drift");
        forward.setUserId(1);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(2);
        tunnel.setStatus(1);
        return tunnel;
    }

    /** 入口(1) → 链(2, inx=1) → 出口(3)：CHAIN_NODE 的下一跳就是出口行。 */
    private List<ChainTunnel> chainRows() {
        return List.of(
                chainRow(1, 1L, null, 1),
                chainRow(2, CHAIN_NODE, CHAIN_PORT, 1),
                chainRow(3, 3L, OUT_PORT, null));
    }

    private ChainTunnel chainRow(int chainType, long nodeId, Integer port, Integer inx) {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL_ID);
        row.setChainType(chainType);
        row.setNodeId(nodeId);
        row.setPort(port);
        row.setInx(inx);
        row.setProtocol("tcp");
        row.setStrategy(chainStrategy);
        return row;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + nodeId);
        node.setPort("30000-30100");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private static JSONObject chainDigest(String name, String[] nodes, String selector) {
        JSONObject entry = new JSONObject();
        entry.put("name", name);
        if (nodes != null) {
            JSONArray array = new JSONArray();
            array.addAll(List.of(nodes));
            entry.put("nodes", array);
        }
        if (selector != null) {
            entry.put("selector", selector);
        }
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> toChainDigestMap(JSONArray array) throws Exception {
        Method method = WebSocketServer.class.getDeclaredMethod("toChainDigestMap", JSONArray.class);
        method.setAccessible(true);
        return (Map<String, List<String>>) method.invoke(null, array);
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
                String type = message.getString("type");
                if ("AddChains".equals(type)) {
                    reply.setMsg(chainReply);
                } else if ("DeleteChains".equals(type)) {
                    reply.setMsg(deleteChainReply);
                } else if ("DeleteService".equals(type)) {
                    reply.setMsg(deleteReply);
                } else {
                    reply.setMsg("OK");
                }
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private List<JSONObject> sentMessages() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(CHAIN_NODE), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private static List<String> typeNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
            names.add(message.getString("type"));
        }
        return names;
    }
}
