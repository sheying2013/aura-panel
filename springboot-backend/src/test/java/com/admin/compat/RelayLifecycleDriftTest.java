package com.admin.compat;

import com.admin.common.dto.GostDto;
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
 * 中继服务 &lt;tid&gt;_tls 生命周期回归（round36 补测）。
 *
 * <p>被测路径：{@code ForwardServiceImpl.resyncNodeServices}
 * → {@code resyncChainResources}（ForwardServiceImpl.java:2511）
 * → {@code reconcileChainServiceDigest}（ForwardServiceImpl.java:2718）。
 *
 * <p>这组用例只盯「一个节点上多条隧道的 relay 服务互不串台」「出口节点不挂链」
 * 「节点侧改名不能被当成漂移删掉别人的东西」三件事，外加 listener.type 漂移的已知缺口。
 */
class RelayLifecycleDriftTest {

    private static final long TUNNEL_A = 9L;
    private static final long TUNNEL_B = 16L;
    private static final long CHAIN_NODE = 2L;
    private static final long OUT_NODE = 3L;
    private static final int A_CHAIN_PORT = 30201;
    private static final int A_OUT_PORT = 30202;
    private static final int B_CHAIN_PORT = 30301;
    private static final int B_OUT_PORT = 30302;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    private List<ChainTunnel> chainRows;
    private List<Forward> activeForwards;
    private String deleteReply;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        chainRows = new ArrayList<>();
        activeForwards = new ArrayList<>();
        deleteReply = "OK";
        singleTunnelTopology(); // 默认只铺 tunnel 9：链节点 node2 + 出口 node3

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

        when(forwardMapper.selectList(any())).thenAnswer(invocation -> activeForwards);
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainRows);
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(nodeService.listByIds(any())).thenAnswer(invocation -> {
            List<Node> loaded = new ArrayList<>();
            for (Long id : (Iterable<Long>) invocation.getArgument(0)) {
                loaded.add(node(id));
            }
            return loaded;
        });
        when(forwardPortService.list(any())).thenReturn(List.of());
        when(tunnelService.getById(any())).thenAnswer(invocation -> tunnel());

        registerNode(CHAIN_NODE);
        registerNode(OUT_NODE);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ---------------------------------------------------------------- 用例

    /**
     * 场景 1：同一节点上挂着两条链的中继服务（9_tls / 16_tls），只有 9_tls 摘要漂移。
     *
     * <p>逐隧道的对账必须是「这条隧道只碰自己的服务」：16_tls 在节点上完全正常，
     * 任何一次 DeleteService/AddService 落到它头上，都会把另一条链的流量打断。
     */
    @Test
    void onlyDriftedTunnelRelayIsRebuiltOnSharedChainNode() throws Exception {
        twoTunnelTopology();

        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE,
                List.of("9_tls", "16_tls"), List.of("chains_9", "chains_16"),
                Map.of("9_tls", "30999|relay|chains_9|-",
                        "16_tls", B_CHAIN_PORT + "|relay|chains_16|-"));

        List<JSONObject> sent = sentMessages(CHAIN_NODE);
        assertEquals(List.of("DeleteService", "AddService"), typeNames(sent),
                "两条链同节点时，只允许对漂移的那一条删+建，实际下发：" + typeNames(sent));
        assertEquals(List.of("9_tls"), deletedServiceNames(sent.get(0)),
                "删除名单里只能有 9_tls，16_tls 不许被删，实际：" + deletedServiceNames(sent.get(0)));

        JSONObject rebuilt = rebuiltService(sent.get(1));
        assertEquals("9_tls", rebuilt.getString("name"), "重建的必须是被判漂移的 9_tls");
        assertEquals("0.0.0.0:" + A_CHAIN_PORT, rebuilt.getString("addr"), "重建端口必须取 chain_tunnel.port");
        assertEquals("chains_9", rebuilt.getJSONObject("handler").getString("chain"), "链节点上的中继服务要挂回 chains_9");
        assertTrue(notes.stream().noneMatch(n -> n.contains("16_tls")),
                "16_tls 摘要一致，不该出现在任何处置说明里，实际：" + notes);
        assertTrue(notes.stream().anyMatch(n -> n.contains("中继服务重建：9_tls@node" + CHAIN_NODE)),
                "漂移重建要显式上报，实际：" + notes);
    }

    /**
     * 场景 2：出口节点（chain_type=3）的 relay 服务漂移。
     *
     * <p>出口中继不挂链，期望摘要是 {@code <port>|relay|-|-}；重建下发的 handler 里不能凭空多出
     * chain 字段，否则流量会被引到出口上根本不存在的 chains_&lt;tid&gt;。
     */
    @Test
    void exitNodeRelayRebuildCarriesNoChainInHandler() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(OUT_NODE,
                List.of("9_tls"), List.of(),
                Map.of("9_tls", A_OUT_PORT + "|relay|chains_9|-"));

        List<JSONObject> sent = sentMessages(OUT_NODE);
        assertEquals(List.of("DeleteService", "AddService"), typeNames(sent),
                "出口节点摘要漂移同样要删+建，实际：" + typeNames(sent));
        assertTrue(notes.stream().anyMatch(n -> n.contains("库 " + A_OUT_PORT + "|relay|-|-")),
                "出口的期望摘要 chain 位必须是 -，实际：" + notes);

        JSONObject rebuilt = rebuiltService(sent.get(1));
        assertEquals("0.0.0.0:" + A_OUT_PORT, rebuilt.getString("addr"), "重建端口必须取出口行的 chain_tunnel.port");
        assertEquals("relay", rebuilt.getJSONObject("handler").getString("type"));
        assertFalse(rebuilt.getJSONObject("handler").containsKey("chain"),
                "出口(chainType=3)的中继服务不该挂链，重建也不能擅自补上 chain");
    }

    /**
     * 场景 3：节点把 9_tls 改名成 9_relay（库里仍是 9_tls）。
     *
     * <p>名字对不上只能走「补齐缺失」分支：按库补一条 9_tls 回去，
     * 但绝不能因为「清单里有前缀一样的东西」就删 9_relay —— 那是别人的服务。
     */
    @Test
    void renamedRelayOnNodeIsFilledWithoutDeletingForeignName() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE,
                List.of("9_relay"), List.of("chains_9"),
                Map.of("9_relay", A_CHAIN_PORT + "|relay|chains_9|-"));

        List<JSONObject> sent = sentMessages(CHAIN_NODE);
        assertEquals(List.of("AddService"), typeNames(sent),
                "库里 9_tls 缺失只能补建，不许对节点上的 9_relay 发任何删除，实际：" + typeNames(sent));
        assertTrue(notes.stream().noneMatch(n -> n.contains("9_relay") && n.contains("删除")),
                "改名不是漂移，不得删除节点上已有的 9_relay，实际：" + notes);

        JSONObject added = rebuiltService(sent.get(0));
        assertEquals("9_tls", added.getString("name"), "补建的服务名必须按库里的 9_tls，而不是节点上的 9_relay");
        assertEquals("0.0.0.0:" + A_CHAIN_PORT, added.getString("addr"), "补建端口必须取 chain_tunnel.port");
        assertEquals("chains_9", added.getJSONObject("handler").getString("chain"), "链节点上的中继服务要挂 chains_9");
        assertTrue(notes.stream().anyMatch(n -> n.contains("已按库补齐缺失中继服务 9_tls@node" + CHAIN_NODE)),
                "补建结果要上报，实际：" + notes);
    }

    /**
     * 场景 4：节点把 9_tls 的 listener.type 从 tls 改成 tcp。
     *
     * <p>已知缺口：摘要口径是 {@code port|handler|chain|target}
     * （GostUtil.normalizeServiceDigest，GostUtil.java:251），不含 listener.type。
     * 节点把 listener 换成 tcp 后上报上来的摘要与库里期望值逐字相同，
     * <b>本用例断言当前不会触发重建</b>。等摘要补上 listener 字段后，
     * 这里应当改成期望 DeleteService + AddService。
     */
    @Test
    void listenerTypeChangeIsInvisibleToCurrentDigest() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE,
                List.of("9_tls"), List.of("chains_9"),
                Map.of("9_tls", A_CHAIN_PORT + "|relay|chains_9|-"));

        List<JSONObject> sent = sentMessages(CHAIN_NODE);
        assertTrue(sent.isEmpty(),
                "摘要不含 listener.type，listener 从 tls 改成 tcp 目前不触发重建，实际下发：" + typeNames(sent));
        assertTrue(notes.isEmpty(), "无漂移判定就不该有任何处置说明，实际：" + notes);
    }

    // ---------------------------------------------------------------- 工具

    private void singleTunnelTopology() {
        chainRows.add(chainRow(TUNNEL_A, 2, CHAIN_NODE, A_CHAIN_PORT, 1));
        chainRows.add(chainRow(TUNNEL_A, 3, OUT_NODE, A_OUT_PORT, null));
        activeForwards.add(forward(TUNNEL_A));
    }

    private void twoTunnelTopology() {
        singleTunnelTopology();
        chainRows.add(chainRow(TUNNEL_B, 2, CHAIN_NODE, B_CHAIN_PORT, 1));
        chainRows.add(chainRow(TUNNEL_B, 3, OUT_NODE, B_OUT_PORT, null));
        activeForwards.add(forward(TUNNEL_B));
    }

    private Forward forward(long tunnelId) {
        Forward forward = new Forward();
        forward.setId(100L + tunnelId);
        forward.setName("relay-lifecycle-" + tunnelId);
        forward.setUserId(1);
        forward.setTunnelId((int) tunnelId);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_A);
        tunnel.setName("tunnel");
        tunnel.setType(2);
        tunnel.setStatus(1);
        return tunnel;
    }

    private ChainTunnel chainRow(long tunnelId, int chainType, long nodeId, Integer port, Integer inx) {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(tunnelId);
        row.setChainType(chainType);
        row.setNodeId(nodeId);
        row.setPort(port);
        row.setInx(inx);
        row.setProtocol("tcp");
        row.setStrategy("round");
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
                if ("DeleteService".equals(message.getString("type"))) {
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

    private List<JSONObject> sentMessages(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
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

    /** DeleteService 的 data 是 {services:[names]}，取成列表便于断言"只删了谁"。 */
    private static List<String> deletedServiceNames(JSONObject message) {
        List<String> names = new ArrayList<>();
        JSONArray array = message.getJSONObject("data").getJSONArray("services");
        for (int i = 0; i < array.size(); i++) {
            names.add(array.getString(i));
        }
        return names;
    }

    /** AddService 的 data 就是 services 数组本身。 */
    private static JSONObject rebuiltService(JSONObject message) {
        return message.getJSONArray("data").getJSONObject(0);
    }
}
