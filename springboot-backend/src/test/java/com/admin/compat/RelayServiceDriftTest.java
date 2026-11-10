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
 * 中继服务 &lt;tid&gt;_tls 摘要漂移对账的回归测试（round35）。
 *
 * <p>覆盖的原缺口：round33/34 只给入口转发服务（f_u_ut）比配置摘要，中继服务一直只比名字。
 * 节点上的 16_tls 被改成别的端口、或挂错链时，服务名与链名都对得上，流量在链的下一跳处断掉，
 * 面板仍显示正常。
 *
 * <p>口径要求：期望值按 GostUtil.CreateChainService 下发口径算
 * （端口取 chain_tunnel.port、handler 恒为 relay、只有 chainType=2 才挂 chains_&lt;tid&gt;）；
 * 摘要缺失或端口记录缺失时只记录不重建；链刚重建过时由重挂逻辑统一处理，不得重复删建。
 */
class RelayServiceDriftTest {

    private static final long TUNNEL_ID = 16L;
    private static final long IN_NODE = 1L;
    private static final long CHAIN_NODE = 2L;
    private static final long OUT_NODE = 3L;
    private static final int CHAIN_PORT = 30201;
    private static final int OUT_PORT = 30202;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    private List<Forward> activeForwards;
    private String addChainsReply;
    private String deleteReply;
    /** 链节点上 chain_tunnel.protocol 的取值；空白用于验证 listener 维度的脏数据宽容 */
    private String relayProtocol;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        activeForwards = List.of(forward(1));
        addChainsReply = "OK";
        deleteReply = "OK";
        relayProtocol = "tcp";

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

        when(forwardMapper.selectList(any())).thenReturn(activeForwards);
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

        registerNode(IN_NODE);
        registerNode(CHAIN_NODE);
        registerNode(OUT_NODE);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void matchingRelayDigestLeavesServiceUntouched() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                Map.of("16_tls", CHAIN_PORT + "|relay|chains_16|-"),
                Map.of("chains_16", List.of("node_3@10.0.0.3:" + OUT_PORT + "#tcp")));

        assertTrue(notes.isEmpty(), "摘要一致就不该有任何处置，实际：" + notes);
        assertTrue(sentMessages(CHAIN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(CHAIN_NODE)));
    }

    @Test
    void driftedRelayPortRebuildsService() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                Map.of("16_tls", "30298|relay|chains_16|-"),
                Map.of("chains_16", List.of("node_3@10.0.0.3:" + OUT_PORT + "#tcp")));

        assertEquals(List.of("DeleteService", "AddService"), typeNames(sentMessages(CHAIN_NODE)),
                "漂移必须删了再建，实际：" + typeNames(sentMessages(CHAIN_NODE)));
        assertTrue(notes.stream().anyMatch(n -> n.contains("中继服务 16_tls@node2 配置漂移（库 " + CHAIN_PORT
                + "|relay|chains_16|- / 节点 30298|relay|chains_16|-）")), "漂移说明要带两侧摘要，实际：" + notes);
        assertTrue(notes.stream().anyMatch(n -> n.contains("中继服务重建：16_tls@node2")),
                "重建结果要显式上报，实际：" + notes);

        JSONObject rebuilt = sentMessages(CHAIN_NODE).get(1).getJSONArray("data").getJSONObject(0);
        assertEquals("16_tls", rebuilt.getString("name"));
        assertEquals("0.0.0.0:" + CHAIN_PORT, rebuilt.getString("addr"), "重建后的端口必须取库里的 chain_tunnel.port");
        assertEquals("chains_16", rebuilt.getJSONObject("handler").getString("chain"),
                "链节点上的中继服务必须重新挂到 chains_16");
        assertEquals("relay", rebuilt.getJSONObject("handler").getString("type"));
    }

    @Test
    void exitRelayDigestExpectsNoChain() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(OUT_NODE, List.of("16_tls"), List.of(),
                Map.of("16_tls", OUT_PORT + "|relay|chains_16|-"),
                Map.of());

        assertEquals(List.of("DeleteService", "AddService"), typeNames(sentMessages(OUT_NODE)),
                "出口(chainType=3)的期望值里没有链，实际：" + typeNames(sentMessages(OUT_NODE)));
        assertTrue(notes.stream().anyMatch(n -> n.contains("库 " + OUT_PORT + "|relay|-|-")),
                "出口的期望摘要 chain 位必须是 -，实际：" + notes);
        JSONObject rebuilt = sentMessages(OUT_NODE).get(1).getJSONArray("data").getJSONObject(0);
        assertFalse(rebuilt.getJSONObject("handler").containsKey("chain"),
                "出口中继服务本来就不该挂链，重建也不能擅自挂上");
    }

    @Test
    void deleteFailureAbortsRelayRebuild() throws Exception {
        deleteReply = "delete service 16_tls failed: device or resource busy";

        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                Map.of("16_tls", "30298|relay|chains_16|-"),
                Map.of("chains_16", List.of("node_3@10.0.0.3:" + OUT_PORT + "#tcp")));

        assertEquals(List.of("DeleteService"), typeNames(sentMessages(CHAIN_NODE)),
                "删不掉就不得再发 AddService（同名会让整批失败），实际：" + typeNames(sentMessages(CHAIN_NODE)));
        assertTrue(notes.stream().anyMatch(n -> n.contains("漂移重建前删除失败") && n.contains("未重建")),
                "删除失败要上报且说明未重建，实际：" + notes);
    }

    @Test
    void chainRepairedSkipsRelayDriftCheck() throws Exception {
        // 链本身漂移 → 重建链 + 重挂中继服务；此时不得再对同一条服务做第二遍删建
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                Map.of("16_tls", "30298|relay|chains_16|-"),
                Map.of("chains_16", List.of("node_null@172.18.0.6:30300#tcp")));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages(CHAIN_NODE)), "链重建后的重挂只能做一遍，实际：" + typeNames(sentMessages(CHAIN_NODE)));
        assertFalse(notes.stream().anyMatch(n -> n.contains("中继服务重建：")),
                "重挂路径已经处理过这条服务，不该再走摘要重建分支，实际：" + notes);
        assertTrue(notes.stream().anyMatch(n -> n.contains("已重挂中继服务 16_tls@node2")),
                "重挂结果仍要上报，实际：" + notes);
    }

    /**
     * chain_tunnel.protocol 空白（历史脏数据）：库里算不出 listener 期望值，
     * 节点也没报 —— 不能判漂移，否则这条中继服务每轮心跳都被删了重建。
     */
    @Test
    void blankRelayProtocolSkipsListenerComparison() throws Exception {
        relayProtocol = "";
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                Map.of("16_tls", CHAIN_PORT + "|relay|chains_16|-", "@listener_supported", "true"),
                Map.of("chains_16", List.of("node_3@10.0.0.3:" + OUT_PORT + "#")));

        assertTrue(notes.isEmpty(), "库 protocol 空白 + 节点未报 listener，不该处置，实际：" + notes);
        assertTrue(sentMessages(CHAIN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(CHAIN_NODE)));
    }

    /**
     * 链节点（chainType=2）的中继服务本来就不下发 metadata.interface；
     * 节点上残留一份网卡只记录，不删建 —— 否则整条隧道每轮被掐断。
     */
    @Test
    void middleNodeResidueIfaceIsOnlyLoggedNotRebuilt() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                Map.of("16_tls", CHAIN_PORT + "|relay|chains_16|-",
                        "16_tls@iface", "eth9",
                        "@iface_supported", "true"),
                Map.of("chains_16", List.of("node_3@10.0.0.3:" + OUT_PORT + "#tcp")));

        assertTrue(notes.isEmpty(), "chainType=2 的残留网卡不该判漂移，实际：" + notes);
        assertTrue(sentMessages(CHAIN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(CHAIN_NODE)));
    }

    // ---------------------------------------------------------------- 工具

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(21L);
        forward.setName("relay-drift");
        forward.setUserId(1);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(status);
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

    /** 入口(1) → 链(2, inx=1) → 出口(3)，与 publishChains 的分组口径一致。 */
    private List<ChainTunnel> chainRows() {
        return List.of(
                chainRow(1, IN_NODE, null, 1),
                chainRow(2, CHAIN_NODE, CHAIN_PORT, 1),
                chainRow(3, OUT_NODE, OUT_PORT, null));
    }

    private ChainTunnel chainRow(int chainType, long nodeId, Integer port, Integer inx) {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL_ID);
        row.setChainType(chainType);
        row.setNodeId(nodeId);
        row.setPort(port);
        row.setInx(inx);
        row.setProtocol(relayProtocol);
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
                String type = message.getString("type");
                if ("AddChains".equals(type)) {
                    reply.setMsg(addChainsReply);
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
}
