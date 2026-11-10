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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * resyncNodeServices 中继链「摘要漂移」对账的回归测试（round34）。
 *
 * <p>覆盖的原缺口：round33 的服务漂移对账只比服务名，链只比 chains_&lt;tid&gt; 在不在。
 * 实测圣何塞 node2 上的 chains_9 下一跳是 node_null@172.18.0.6:30300（历史 GostUtil 用 inx
 * 拼节点名的 bug 留下的），链名对得上、流量却打到不存在的跳点，面板一切正常。
 *
 * <p>要求（宁可不重建，也不能乱删）：
 * 1. 只有节点上报了这条链的摘要、且与库算出的期望值不等时才重建；
 * 2. 摘要缺失（老节点 / 本次没带）或库里节点数据不全，一律维持「只比链名」的老行为；
 * 3. 重建前必须先删除，删除失败就停手，不能叠加同名链；
 * 4. 重建成功后本节点引用这条链的服务必须重挂，否则 handler 里的 chain 还是旧的。
 */
class ChainDriftResyncTest {

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
    private ForwardPortService forwardPortService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    private List<Forward> activeForwards;
    private String chainReply;
    private String deleteChainReply;
    private String deleteReply;
    private final Map<Long, Node> nodeOverrides = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        nodeOverrides.clear();

        activeForwards = List.of(forward(1));
        chainReply = "OK";
        deleteChainReply = "OK";
        deleteReply = "OK";

        forwardMapper = mock(ForwardMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        forwardPortService = mock(ForwardPortService.class);

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
    void matchingDigestLeavesChainUntouched() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("other_tcp"), List.of("chains_16"),
                Map.of(), Map.of("chains_16", List.of("node_2@10.0.0.2:" + CHAIN_PORT + "#tcp")));

        assertTrue(notes.isEmpty(), "摘要一致就不该有任何处置，实际：" + notes);
        assertTrue(sentMessages(IN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(IN_NODE)));
    }

    @Test
    void driftedDigestRebuildsChain() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("other_tcp"), List.of("chains_16"),
                Map.of(), Map.of("chains_16", List.of("node_9@10.0.0.9:39999#tcp")));

        assertEquals(List.of("DeleteChains", "AddChains"), typeNames(sentMessages(IN_NODE)),
                "漂移必须删了再建，实际：" + typeNames(sentMessages(IN_NODE)));
        assertTrue(notes.stream().anyMatch(n -> n.contains("中继链 chains_16@node1 配置漂移（库 node_2@10.0.0.2:"
                + CHAIN_PORT + "#tcp / 节点 node_9@10.0.0.9:39999#tcp）")), "漂移说明要带两侧摘要，实际：" + notes);
        assertTrue(notes.stream().anyMatch(n -> n.contains("已按库重建漂移中继链 chains_16@node1")),
                "重建结果要显式上报，实际：" + notes);

        JSONObject rebuild = sentMessages(IN_NODE).get(1).getJSONObject("data");
        assertEquals("chains_16", rebuild.getString("name"));
        assertEquals("node_2", rebuild.getJSONArray("hops").getJSONObject(0).getJSONArray("nodes")
                .getJSONObject(0).getString("name"), "重建后的下一跳必须是库里的节点，不是节点上的旧值");
    }

    @Test
    void deleteFailureAbortsRebuild() throws Exception {
        deleteChainReply = "delete chain chains_16 failed: device or resource busy";

        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("other_tcp"), List.of("chains_16"),
                Map.of(), Map.of("chains_16", List.of("node_9@10.0.0.9:39999#tcp")));

        assertEquals(List.of("DeleteChains"), typeNames(sentMessages(IN_NODE)),
                "删不掉就不得再发 AddChains，实际：" + typeNames(sentMessages(IN_NODE)));
        assertTrue(notes.stream().anyMatch(n -> n.contains("漂移重建前删除失败") && n.contains("未重建")),
                "删除失败要上报且说明未重建，实际：" + notes);
    }

    @Test
    void rebuildFailureIsReportedWithoutRebinding() throws Exception {
        chainReply = "add chain chains_16 failed: invalid hop";

        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("other_tcp"), List.of("chains_16"),
                Map.of(), Map.of("chains_16", List.of("node_9@10.0.0.9:39999#tcp")));

        assertEquals(List.of("DeleteChains", "AddChains"), typeNames(sentMessages(IN_NODE)),
                "链没建成时不得重挂服务，实际：" + typeNames(sentMessages(IN_NODE)));
        assertTrue(notes.stream().anyMatch(n -> n.contains("中继链 chains_16@node1 补齐失败：add chain chains_16 failed")),
                "重建失败要上报原始原因，实际：" + notes);
    }

    @Test
    void missingDigestKeepsNameOnlyBehaviour() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("other_tcp"), List.of("chains_16"));

        assertTrue(notes.isEmpty(), "老节点不报摘要时维持只比链名，实际：" + notes);
        assertTrue(sentMessages(IN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(IN_NODE)));
    }

    @Test
    void digestWithoutThisChainIsIgnored() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("other_tcp"), List.of("chains_16"),
                Map.of(), Map.of("chains_9", List.of("node_9@10.0.0.9:39999#tcp")));

        assertTrue(notes.isEmpty(), "本次上报没带这条链的摘要就不动，实际：" + notes);
        assertTrue(sentMessages(IN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(IN_NODE)));
    }

    @Test
    void ipv6HopDigestIsComparedWithBrackets() throws Exception {
        Node ipv6 = node(CHAIN_NODE);
        ipv6.setServerIp("2001:db8::1");
        nodeOverrides.put(CHAIN_NODE, ipv6);

        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("other_tcp"), List.of("chains_16"),
                Map.of(), Map.of("chains_16", List.of("node_2@[2001:db8::1]:" + CHAIN_PORT + "#tcp")));

        assertTrue(notes.isEmpty(), "IPv6 跳点必须按 processServerAddress 的口径加方括号比较，实际：" + notes);
        assertTrue(sentMessages(IN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(IN_NODE)));
    }

    @Test
    void relayServiceIsReboundAfterDrift() throws Exception {
        // 真机现场：node2 的 chains_9 下一跳是 node_null@172.18.0.6:30300，库里应是 node_3@10.0.0.3:30202
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                Map.of(), Map.of("chains_16", List.of("node_null@172.18.0.6:30300#tcp")));

        assertEquals(List.of("DeleteChains", "AddChains", "DeleteService", "AddService"),
                typeNames(sentMessages(CHAIN_NODE)), "实际：" + typeNames(sentMessages(CHAIN_NODE)));
        JSONArray deleted = sentMessages(CHAIN_NODE).get(2).getJSONObject("data").getJSONArray("services");
        assertEquals(List.of("16_tls"), deleted.toJavaList(String.class), "只删清单里真实存在的中继服务");
        JSONObject relay = sentMessages(CHAIN_NODE).get(3).getJSONArray("data").getJSONObject(0);
        assertEquals("16_tls", relay.getString("name"));
        assertEquals("chains_16", relay.getJSONObject("handler").getString("chain"),
                "重挂后的中继服务必须重新挂到重建后的 chains_16");
        assertTrue(notes.stream().anyMatch(n -> n.contains("已重挂中继服务 16_tls@node2")),
                "重挂结果要显式上报，实际：" + notes);
    }

    // ---------------------------------------------------------------- 工具

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(21L);
        forward.setName("chain-drift");
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
        row.setProtocol("tcp");
        row.setStrategy("round");
        return row;
    }

    private Node node(long nodeId) {
        Node overridden = nodeOverrides.get(nodeId);
        if (overridden != null) {
            return overridden;
        }
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
