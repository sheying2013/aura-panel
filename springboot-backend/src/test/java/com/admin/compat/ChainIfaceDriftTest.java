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
 * 中继链出站网卡（hop.interface）漂移对账的回归测试（round39）。
 *
 * <p>覆盖的原缺口：round34/36 的链漂移只比下一跳节点列表与 selector。节点上的
 * {@code hop.interface} 被改成别的网卡、或库里清掉网卡后节点还挂着旧网卡时，
 * 跳点与策略完全一致，对账判「一致」，流量静默从错网卡出去。
 *
 * <p>口径要求：
 * 1. 节点把 {@code hop.interface} 压成 {@code iface=<hop0>;<hop1>}（空 hop 写 "-"）追加在摘要列表末尾，
 *    对账时必须把它从跳点列表里挑出来，绝不能当成多出来的跳点；
 * 2. 期望值按 {@code GostUtil.CreateChains} 的下发口径算：一条链只有一个 hop，
 *    hop.interface 取接收这条链的节点的 interfaceName，空写 "-"；
 * 3. 老节点不报 {@code iface=} 元素时整维度跳过，绝不因此删链重建；
 * 4. 漂移一律 DeleteChains → AddChains（→ 重挂引用这条链的中继服务）。
 */
class ChainIfaceDriftTest {

    private static final long TUNNEL_ID = 16L;
    private static final long CHAIN_NODE = 2L;
    private static final int CHAIN_PORT = 30201;
    private static final int OUT_PORT = 30202;

    /** 节点上报的下一跳（出口 node_3），与 chainRows 里出口行一致。 */
    private static final String HOP_OUT = "node_3@10.0.0.3:" + OUT_PORT + "#tcp";
    private static final String HOP_WRONG = "node_9@10.0.0.9:39999#tcp";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 库里承载这条链的节点（CHAIN_NODE）配的出站网卡 */
    private String chainNodeInterfaceName;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        chainNodeInterfaceName = "eth0";

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

    /** 网卡一致：零下发。同时证明 iface= 元素没有被当成多出来的跳点。 */
    @Test
    void matchingIfaceLeavesChainUntouched() throws Exception {
        List<String> notes = resync(List.of(HOP_OUT, "iface=eth0"));

        assertTrue(notes.isEmpty(), "跳点与网卡都一致就不该有任何处置，实际：" + notes);
        assertTrue(sentMessages().isEmpty(), "零下发，实际：" + typeNames(sentMessages()));
    }

    /** 节点上 hop.interface 被改成 eth9：跳点完全一致，只比跳点永远发现不了。 */
    @Test
    void ifaceDriftRebuildsChain() throws Exception {
        List<String> notes = resync(List.of(HOP_OUT, "iface=eth9"));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages()),
                "网卡漂移必须删链重建并重挂引用这条链的服务，实际：" + typeNames(sentMessages()));
        assertTrue(notes.stream().anyMatch(n -> n.contains(
                        "中继链 chains_16@node2 出站网卡漂移（库 iface=eth0 / 节点 iface=eth9）")),
                "漂移说明要带两侧 iface，实际：" + notes);

        JSONObject rebuild = sentMessages().get(1).getJSONObject("data");
        assertEquals("chains_16", rebuild.getString("name"));
        assertEquals("eth0", rebuild.getJSONArray("hops").getJSONObject(0).getString("interface"),
                "重建后的 hop.interface 必须是库里的 eth0，不是节点上的 eth9");
    }

    /** 老节点只报跳点：没有 iface= 元素就必须完全跳过，绝不能凭缺字段删链。 */
    @Test
    void legacyNodeWithoutIfaceElementSkipsCheck() throws Exception {
        List<String> notes = resync(List.of(HOP_OUT));

        assertTrue(notes.isEmpty(), "老节点不报 iface= 时只比跳点，实际：" + notes);
        assertTrue(sentMessages().isEmpty(), "老节点不得被删建，实际：" + typeNames(sentMessages()));
    }

    /** 库里没配网卡、节点上也没有：节点按约定报 "-"，算一致。 */
    @Test
    void dashIfaceMatchesWhenLibraryHasNoInterface() throws Exception {
        chainNodeInterfaceName = null;
        List<String> notes = resync(List.of(HOP_OUT, "iface=-"));

        assertTrue(notes.isEmpty(), "库里没网卡时节点报 - 就是一致，实际：" + notes);
        assertTrue(sentMessages().isEmpty(), "零下发，实际：" + typeNames(sentMessages()));
    }

    /** 库里清掉了网卡、节点上还挂着旧网卡：必须纠正。 */
    @Test
    void strayIfaceWhenLibraryHasNoneRebuildsChain() throws Exception {
        chainNodeInterfaceName = "  ";
        List<String> notes = resync(List.of(HOP_OUT, "iface=eth9"));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages()), "库里无网卡节点有网卡也要重建，实际：" + typeNames(sentMessages()));
        assertTrue(notes.stream().anyMatch(n -> n.contains("库 iface=- / 节点 iface=eth9")),
                "库里没有时期望值写 -，实际：" + notes);
    }

    /** 跳点与网卡同时漂移：只重建一次，文案以跳点为主（避免一条链报两个问题）。 */
    @Test
    void hopDriftTakesPrecedenceOverIfaceDrift() throws Exception {
        List<String> notes = resync(List.of(HOP_WRONG, "iface=eth9"));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages()), "两处都漂也只重建一次，实际：" + typeNames(sentMessages()));
        assertTrue(notes.stream().anyMatch(n -> n.contains("中继链 chains_16@node2 配置漂移（库 " + HOP_OUT
                + " / 节点 " + HOP_WRONG + "）")), "跳点漂移为主文案，实际：" + notes);
        assertFalse(String.join("；", notes).contains("iface="),
                "跳点不同时不再追网卡，实际：" + notes);
    }

    // ---------------------------------------------------------------- 工具

    private List<String> resync(List<String> reportedChainDigest) {
        return forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                Map.of(), Map.of("chains_16", reportedChainDigest));
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(21L);
        forward.setName("iface-drift");
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
        if (nodeId == CHAIN_NODE) {
            node.setInterfaceName(chainNodeInterfaceName);
        }
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
            JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
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
