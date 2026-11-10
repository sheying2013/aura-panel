package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.entity.ChainTunnel;
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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * resyncNodeServices 中继链补齐的回归测试。
 *
 * <p>覆盖的原缺口：round9 只补转发入口服务（f_u_ut），中继链缺失仍归"运维确认"。
 * 节点丢了 gost.json 后 chains_&lt;tid&gt; 与 &lt;tid&gt;_tls 都没了，
 * 面板显示隧道正常，实际整条链路没有任何一个监听，只能等用户报障。
 *
 * <p>要求（宁可不补，也不能补错）：
 * 1. 只补仍被活跃转发引用（forward.status=1）的隧道，废弃隧道不复活；
 * 2. 判据只有节点自报清单：清单里有的绝不重发（重发会 bind 冲突）；
 * 3. 同一隧道先 chains 后 service，chains 没成就跳过 service；
 * 4. exists 只上报"配置未知"，既不覆盖也不删除；
 * 5. 空清单不做任何推断，异常不外抛。
 */
class ResyncChainTest {

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
    private TunnelService tunnelService;
    private ForwardPortService forwardPortService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 库里"活跃转发"（status=1）的查询结果；空列表等价于没有活跃转发。 */
    private List<Forward> activeForwards;
    /** 库里"全部转发"（不限状态）的查询结果，用于候选隧道集；空列表等价于没有任何转发引用隧道。 */
    private List<Forward> allForwards;
    /** 记录 selectList 的 wrapper 是否带上了 status 过滤，防止在内存里补过滤。 */
    private boolean sawStatusFilter;
    private String chainReply;
    private String serviceReply;
    private String deleteReply;
    /** 非 null 时覆盖默认的 chainRows()，用于构造脏库行（例如越界端口）。 */
    private List<ChainTunnel> chainRowsData;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        activeForwards = List.of(forward(1));
        allForwards = List.of(forward(1));
        sawStatusFilter = false;
        chainReply = "OK";
        serviceReply = "OK";
        deleteReply = "OK";
        chainRowsData = null;

        forwardMapper = mock(ForwardMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        forwardPortService = mock(ForwardPortService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));

        when(forwardMapper.selectList(any())).thenAnswer(invocation -> {
            Object wrapper = invocation.getArgument(0);
            // QueryWrapper.toString() 只有类名与哈希（已实测），要看条件必须取 getSqlSegment()
            String segment = wrapper instanceof QueryWrapper<?> qw ? qw.getSqlSegment() : null;
            boolean statusFiltered = segment != null && segment.contains("status");
            sawStatusFilter = sawStatusFilter || statusFiltered;
            // 候选隧道集走的是不带 status 的 select tunnel_id，两种查询要返回不同结果
            return statusFiltered ? activeForwards : allForwards;
        });
        when(chainTunnelService.list(any())).thenAnswer(invocation ->
                chainRowsData != null ? chainRowsData : chainRows());
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(nodeService.listByIds(any())).thenReturn(List.of(node(IN_NODE), node(CHAIN_NODE), node(OUT_NODE)));
        // 这些用例只关心中继资源：节点上没有转发端口行，转发入口服务那条分支自然什么都不做
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
    void entryNodeMissingChainGetsOneAddChainsPointingToFirstHop() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("other_tcp"), List.of());

        assertEquals(1, notes.size(), "入口节点只该补 1 条中继链，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失中继链 chains_16@node1"), "实际：" + notes);
        assertTrue(notes.get(0).contains("下一跳 1 个节点"), "实际：" + notes);

        List<JSONObject> messages = sentMessages(IN_NODE);
        assertEquals(1, messages.size(), "只该下发一次 AddChains，实际：" + typeNames(messages));
        assertEquals("AddChains", messages.get(0).getString("type"));
        JSONObject data = messages.get(0).getJSONObject("data");
        assertEquals("chains_16", data.getString("name"));
        JSONArray hops = data.getJSONArray("hops");
        assertEquals(1, hops.size());
        assertEquals("hop_16", hops.getJSONObject(0).getString("name"));
        JSONArray nodes = hops.getJSONObject(0).getJSONArray("nodes");
        assertEquals(1, nodes.size(), "入口的 next 必须是第一跳（链节点 2）");
        assertEquals("node_2", nodes.getJSONObject(0).getString("name"));
        assertEquals("10.0.0.2:" + CHAIN_PORT, nodes.getJSONObject(0).getString("addr"));
        assertTrue(addedServiceNames(IN_NODE).isEmpty(), "入口节点没有 _tls 服务");
    }

    @Test
    void chainNodeRepairsChainsBeforeService() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("99_9_9_tcp"), List.of());

        assertEquals(2, notes.size(), "链节点该补链+服务，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失中继链 chains_16@node2"), "实际：" + notes);
        assertTrue(notes.get(1).startsWith("已按库补齐缺失中继服务 16_tls@node2（端口" + CHAIN_PORT + "）"), "实际：" + notes);

        WebSocketSession session = nodeSessions.get(CHAIN_NODE);
        InOrder inOrder = inOrder(session);
        inOrder.verify(session).sendMessage(argThat(message -> "AddChains".equals(typeOf(message))));
        inOrder.verify(session).sendMessage(argThat(message -> "AddService".equals(typeOf(message))));

        JSONObject service = firstServicePayload(CHAIN_NODE);
        assertEquals("16_tls", service.getString("name"));
        assertEquals("0.0.0.0:" + CHAIN_PORT, service.getString("addr"));
        assertEquals("chains_16", service.getJSONObject("handler").getString("chain"),
                "链节点服务必须挂在 chains_16 上，否则流量回到原点");
    }

    @Test
    void exitNodeRepairsOnlyService() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(OUT_NODE, List.of("99_9_9_tcp"), List.of());

        assertEquals(1, notes.size(), "出口只补服务，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失中继服务 16_tls@node3（端口" + OUT_PORT + "）"), "实际：" + notes);
        assertEquals(List.of("16_tls"), addedServiceNames(OUT_NODE));
        for (JSONObject message : sentMessages(OUT_NODE)) {
            assertTrue(!"AddChains".equals(message.getString("type")), "出口节点不该收到 AddChains");
        }
        assertEquals("relay", firstServicePayload(OUT_NODE).getJSONObject("handler").getString("type"));
        assertTrue(firstServicePayload(OUT_NODE).getJSONObject("handler").getString("chain") == null,
                "出口的 relay handler 不引用链");
    }

    @Test
    void reportedResourcesAreLeftAlone() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"));

        assertTrue(notes.isEmpty(), "清单里都在就不该有任何处置，实际：" + notes);
        assertTrue(sentMessages(CHAIN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(CHAIN_NODE)));
    }

    @Test
    @DisplayName("库内跳点端口是脏值时跳过漂移判定，且不清熔断计数（round45 QA P1-4）")
    void dirtyChainPortSkipsDriftJudgementWithoutClearingBreaker() throws Exception {
        // 70000 越界：expectedChainNodes 算不出期望值，chainDrift 只能返回 null。
        // 这个 null 既不是「一致」也不是「漂移」，绝不能拿去 recordMatch 清计数——
        // 否则脏库行永远不修、永远不告警，还会把已经积累的熔断计数清零（无限重试）。
        chainRowsData = List.of(
                chainRow(1, IN_NODE, null, 1),
                chainRow(2, CHAIN_NODE, CHAIN_PORT, 1),
                chainRow(3, OUT_NODE, 70000, null));
        Map<String, List<String>> chainDigests = Map.of("chains_16", List.of("node_3@10.0.0.3:30202#tcp"));

        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of("chains_16"),
                null, chainDigests);

        assertEquals(1, notes.size(), "只应给出一条跳过说明，实际：" + notes);
        assertTrue(notes.get(0).contains("库内跳点数据不全或端口非法"), "实际：" + notes);
        assertTrue(notes.get(0).contains("跳过漂移判定"), "实际：" + notes);
        assertTrue(notes.get(0).contains("未清熔断计数"), "实际：" + notes);
        assertTrue(sentMessages(CHAIN_NODE).isEmpty(),
                "不得下发删建，实际：" + typeNames(sentMessages(CHAIN_NODE)));
    }

    @Test
    void tunnelWithNoForwardAtAllIsNotResurrected() throws Exception {
        activeForwards = List.of();
        allForwards = List.of();

        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("99_9_9_tcp"), List.of());

        assertTrue(notes.isEmpty(), "没有任何转发引用的隧道不补齐，实际：" + notes);
        assertTrue(sentMessages(CHAIN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(CHAIN_NODE)));
        assertTrue(sawStatusFilter, "活跃转发必须在 SQL 里按 status 过滤，不能把全部隧道拉进内存再筛");
    }

    @Test
    void pausedOnlyTunnelStillGetsChainAndRelayService() throws Exception {
        // 一条隧道的转发全部暂停时，节点上的 chains_16/16_tls 依然在占端口（暂停只下掉入口
        // 服务 f_u_ut）。若按「活跃转发」筛，节点重装后这里就永远不补，恢复转发时入口服务
        // 会挂到不存在的链上，handler 静默直连 target —— 中继形同虚设。
        activeForwards = List.of();
        allForwards = List.of(forward(0));

        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("99_9_9_tcp"), List.of());

        assertEquals(2, notes.size(), "暂停隧道仍要补链+中继服务，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失中继链 chains_16@node2"), "实际：" + notes);
        assertTrue(notes.get(1).startsWith("已按库补齐缺失中继服务 16_tls@node2（端口" + CHAIN_PORT + "）"), "实际：" + notes);
    }

    @Test
    void pausedOnlyTunnelDoesNotGetEntryServiceBack() throws Exception {
        // 补链不等于放量：入口服务 f_u_ut 必须仍按 status=1 判，暂停的转发绝不能因此复活
        activeForwards = List.of();
        allForwards = List.of(forward(0));
        when(forwardPortService.getOne(any())).thenReturn(forwardPort(30100, IN_NODE));
        when(forwardPortService.list(any())).thenReturn(List.of(forwardPort(30100, IN_NODE)));

        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("21_1_0_tcp", "21_1_0_udp"), List.of());

        assertTrue(addedServiceNames(IN_NODE).isEmpty(), "暂停转发不得重建入口服务，实际：" + addedServiceNames(IN_NODE));
        assertTrue(notes.stream().noneMatch(n -> n.contains("已按库补齐缺失服务 21_1_0")), "实际：" + notes);
    }

    @Test
    void referencedTunnelMissingFromTunnelTableIsSkipped() throws Exception {
        // 转发行还在、隧道行已被删：补出来的 chains_16 是死配置，交给孤儿清扫统一回收
        when(tunnelService.getById(any())).thenReturn(null);

        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("99_9_9_tcp"), List.of());

        assertTrue(notes.isEmpty(), "隧道行已删不补齐，实际：" + notes);
        assertTrue(sentMessages(CHAIN_NODE).isEmpty(), "零下发，实际：" + typeNames(sentMessages(CHAIN_NODE)));
    }

    @Test
    void existingChainIsReportedAndServiceIsSkipped() throws Exception {
        chainReply = "chains_16: object already exists";

        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("99_9_9_tcp"), List.of());

        assertEquals(1, notes.size(), "exists 只上报一条，实际：" + notes);
        assertTrue(notes.get(0).contains("已有 chains_16 但配置未知，未自动修复"), "实际：" + notes);

        List<JSONObject> messages = sentMessages(CHAIN_NODE);
        assertEquals(1, messages.size(), "链状态未知时不该再挂监听，更不能删东西，实际：" + typeNames(messages));
        assertEquals("AddChains", messages.get(0).getString("type"));
    }

    @Test
    void emptyReportMakesNoInference() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of(), List.of());

        assertTrue(notes.isEmpty(), "双空清单不做推断，实际：" + notes);
        assertTrue(sentMessages(CHAIN_NODE).isEmpty());
        verify(chainTunnelService, never()).list(any());
    }

    // ------------------------------------------------- 链补齐后必须重挂既有服务

    @Test
    void repairedChainRebindsExistingEntryService() throws Exception {
        // 节点上入口服务还在，但链丢了：服务注册时 chain 被解析成 nil，handler 会直连 target，
        // 只补链不重挂等于没修，必须走 DeleteService → AddService 让 handler 重新挂上 chains_16
        when(forwardPortService.getOne(any())).thenReturn(forwardPort(30100, IN_NODE));

        List<String> notes = forwardService.resyncNodeServices(IN_NODE,
                List.of("21_1_0_tcp", "21_1_0_udp"), List.of());

        List<JSONObject> messages = sentMessages(IN_NODE);
        assertEquals(List.of("AddChains", "DeleteService", "AddService"), typeNames(messages),
                "实际下发的消息顺序：" + typeNames(messages));
        JSONArray deleted = messages.get(1).getJSONObject("data").getJSONArray("services");
        assertEquals(List.of("21_1_0_tcp", "21_1_0_udp"), deleted.toJavaList(String.class),
                "只删清单里真实存在的两份，实际：" + deleted);
        JSONObject rebind = messages.get(2).getJSONArray("data").getJSONObject(0);
        assertEquals("21_1_0_tcp", rebind.getString("name"));
        assertEquals("chains_16", rebind.getJSONObject("handler").getString("chain"),
                "重挂后的服务必须重新挂到 chains_16");
        assertTrue(notes.stream().anyMatch(n -> n.contains("已重挂入口服务 21_1_0@node1")),
                "重挂结果要显式上报，实际：" + notes);
    }

    @Test
    void repairedChainRebindsRelayServiceOnChainNode() throws Exception {
        // 链节点上的 <tid>_tls 也把 chain 写进了 handler，链丢了同样会直连下一跳
        List<String> notes = forwardService.resyncNodeServices(CHAIN_NODE, List.of("16_tls"), List.of());

        List<JSONObject> messages = sentMessages(CHAIN_NODE);
        assertEquals(List.of("AddChains", "DeleteService", "AddService"), typeNames(messages),
                "实际下发的消息顺序：" + typeNames(messages));
        assertEquals("16_tls", messages.get(1).getJSONObject("data").getJSONArray("services").getString(0));
        JSONObject relay = messages.get(2).getJSONArray("data").getJSONObject(0);
        assertEquals("16_tls", relay.getString("name"));
        assertEquals("relay", relay.getJSONObject("handler").getString("type"));
        assertEquals("chains_16", relay.getJSONObject("handler").getString("chain"));
        assertTrue(notes.stream().anyMatch(n -> n.contains("已重挂中继服务 16_tls@node2")),
                "重挂结果要显式上报，实际：" + notes);
    }

    @Test
    void presentChainDoesNotTouchExistingServices() throws Exception {
        // 链本来就在清单里：服务是被正确注册的，绝不能为了"保险"重启一遍监听
        when(forwardPortService.getOne(any())).thenReturn(forwardPort(30100, IN_NODE));

        List<String> notes = forwardService.resyncNodeServices(IN_NODE,
                List.of("21_1_0_tcp", "21_1_0_udp"), List.of("chains_16"));

        assertTrue(typeNames(sentMessages(IN_NODE)).isEmpty(),
                "链已存在时零下发，实际：" + typeNames(sentMessages(IN_NODE)));
        assertTrue(notes.isEmpty(), "链已存在时不该有说明，实际：" + notes);
    }

    @Test
    void deleteFailureKeepsServiceWithoutReAdd() throws Exception {
        // 删不掉还硬发 AddService 只会撞 exists 让整批失败，服务反而更残：先保留原样交人工
        when(forwardPortService.getOne(any())).thenReturn(forwardPort(30100, IN_NODE));
        deleteReply = "delete service 21_1_0_tcp failed: device or resource busy";

        List<String> notes = forwardService.resyncNodeServices(IN_NODE,
                List.of("21_1_0_tcp", "21_1_0_udp"), List.of());

        assertEquals(List.of("AddChains", "DeleteService"), typeNames(sentMessages(IN_NODE)),
                "删除失败后不得重发，实际：" + typeNames(sentMessages(IN_NODE)));
        assertTrue(notes.stream().anyMatch(n -> n.contains("未重发")), "要上报未重发，实际：" + notes);
    }

    @Test
    @DisplayName("直连隧道（type=1）即使残留 chain_tunnel 行也不补中继链与 <tid>_tls")
    void directTunnelDoesNotGetChainResources() throws Exception {
        Tunnel direct = tunnel();
        direct.setType(1);
        when(tunnelService.getById(any())).thenReturn(direct);

        List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of("other_tcp"), List.of());

        assertTrue(notes.isEmpty(), "直连隧道不该产生任何中继补齐，实际：" + notes);
        assertTrue(sentMessages(IN_NODE).isEmpty(),
                "直连隧道不该下发 AddChains/AddService，实际：" + typeNames(sentMessages(IN_NODE)));
    }

    // ---------------------------------------------------------------- 工具

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(21L);
        forward.setName("chain-resync");
        forward.setUserId(1);
        forward.setTunnelId((int) TUNNEL_ID); // Forward.tunnelId 是 Integer，ChainTunnel 那边是 Long
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
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + nodeId);
        node.setPort("30000-30100");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private ForwardPort forwardPort(int port) {
        return forwardPort(port, CHAIN_NODE);
    }

    private ForwardPort forwardPort(int port, long nodeId) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(21L);
        forwardPort.setNodeId(nodeId);
        forwardPort.setPort(port);
        return forwardPort;
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
                } else if ("AddService".equals(type)) {
                    reply.setMsg(serviceReply);
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

    private JSONObject firstServicePayload(long nodeId) throws Exception {
        for (JSONObject message : sentMessages(nodeId)) {
            if ("AddService".equals(message.getString("type"))) {
                return message.getJSONArray("data").getJSONObject(0);
            }
        }
        throw new AssertionError("节点 " + nodeId + " 没有收到 AddService");
    }

    private static List<String> typeNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
            names.add(message.getString("type"));
        }
        return names;
    }

    private static String typeOf(WebSocketMessage<?> message) {
        return JSON.parseObject(((TextMessage) message).getPayload()).getString("type");
    }
}
