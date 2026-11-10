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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 入口转发服务 listener 类型 / 出站网卡漂移对账的回归测试（round39）。
 *
 * <p>覆盖的原缺口：round37/38 的入口服务对账只比「端口|handler|链|目标」四段摘要。
 * 面板把 tcp 入口改成 tls、节点上 udp 的 keepAlive 丢了、或节点 metadata.interface 被改成
 * 别的网卡时，四段摘要完全一致，对账判「一致」，流量静默走错协议或从错网卡出去。
 *
 * <p>口径要求：
 * 1. listener 期望值按 {@code GostUtil.createListener} 的下发口径算：协议名小写，
 *    udp 额外带 {@code |keepAlive=true}；tcp/udp 两种协议分别比，不能只比 tcp；
 * 2. iface 期望值按 {@code GostUtil.buildForwardService} 的下发条件算：只有端口转发
 *    （{@code tunnel.type == 1}）且节点配了 interfaceName 时节点上才该有网卡；
 * 3. 两个维度的「节点是否支持」由哨兵键 {@code @listener_supported} / {@code @iface_supported}
 *    判定，老节点不报就整维度跳过——绝不能因为缺字段把在跑的服务删了重建；
 * 4. 漂移一律 DeleteService(tcp+udp) → AddService 整对重建，说明里带 listener= / iface=。
 */
class ServiceListenerIfaceDriftTest {

    private static final long NODE_ID = 201L;
    private static final long FORWARD_ID = 21L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;
    private static final int PORT = 30111;
    private static final String SERVICE_NAME = "21_1_0";
    private static final String REMOTE_ADDR = "backend:6365";

    private static final String TCP = SERVICE_NAME + "_tcp";
    private static final String UDP = SERVICE_NAME + "_udp";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 库里节点配的出站网卡（null/空白 = 节点上不该有 metadata.interface） */
    private String nodeInterfaceName;
    /** 库里的隧道类型：1=端口转发（下 interface），2=隧道转发（网卡挂在链上） */
    private int tunnelType;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        nodeInterfaceName = null;
        tunnelType = 1;

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", mock(ChainTunnelService.class));

        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(forwardMapper.selectById(any())).thenReturn(forward());
        when(tunnelService.getById(any())).thenAnswer(invocation -> tunnel(tunnelType));
        when(forwardPortService.list(any())).thenAnswer(invocation -> List.of(forwardPort()));
        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ---------------------------------------------------------------- 用例

    /** listener 与网卡都和库里一致：零下发。 */
    @Test
    void matchingListenerAndIfaceLeavesServiceAlone() throws Exception {
        nodeInterfaceName = "eth0";
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tcp");
        digests.put(UDP + "@listener", "udp|keepAlive=true");
        digests.put(TCP + "@iface", "eth0");
        digests.put(UDP + "@iface", "eth0");
        digests.put("@listener_supported", "true");
        digests.put("@iface_supported", "true");

        List<String> notes = resync(digests);

        assertTrue(notes.isEmpty(), "两个维度一致就不该有任何处置，实际：" + notes);
        assertNoServiceRebuild();
    }

    /** 库里 tcp，节点上监听成了 tls：四段摘要一模一样，只比摘要永远发现不了。 */
    @Test
    void listenerDriftRebuildsService() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tls");
        digests.put(UDP + "@listener", "udp|keepAlive=true");
        digests.put("@listener_supported", "true");

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "listener 漂移必须整对删了再建，实际：" + typeNames());
        assertEquals(List.of(TCP, UDP), deletedServiceNames(), "删的是 tcp+udp 整对");
        assertTrue(notes.stream().anyMatch(n -> n.contains("配置漂移（库 listener=tcp / 节点 listener=tls）")),
                "漂移说明要带两侧 listener，实际：" + notes);
    }

    /** 老节点不报 listener：即便摘要里混进了错值，没有哨兵也必须完全跳过。 */
    @Test
    void legacyNodeWithoutSentinelSkipsListenerAndIface() throws Exception {
        nodeInterfaceName = "eth0";
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tls");
        digests.put(TCP + "@iface", "eth9");

        List<String> notes = resync(digests);

        assertTrue(notes.isEmpty(), "老节点没有哨兵，两个维度都要跳过，实际：" + notes);
        assertNoServiceRebuild();
    }

    /**
     * 真机节点的真实取值：gost 落盘键是小写 {@code keepalive}，节点摘要也随之全小写，
     * 期望值必须同口径，否则每次上报都判漂移、反复删建（round39 灰度实测踩到）。
     */
    @Test
    void realNodeLowercaseKeepaliveMatchesExpectation() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tcp");
        digests.put(UDP + "@listener", "udp|keepalive=true");
        digests.put("@listener_supported", "true");

        List<String> notes = resync(digests);

        assertTrue(notes.isEmpty(), "小写 keepalive 是节点真实口径，不该判漂移，实际：" + notes);
        assertNoServiceRebuild();
    }

    /** udp 的 keepAlive 丢了：节点报 udp，库期望 udp|keepalive=true。 */
    @Test
    void udpKeepAliveMissingRebuildsService() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tcp");
        digests.put(UDP + "@listener", "udp");
        digests.put("@listener_supported", "true");

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "udp keepAlive 缺失也是 listener 漂移，实际：" + typeNames());
        assertTrue(notes.stream().anyMatch(n -> n.contains(
                        "库 listener=udp|keepalive=true / 节点 listener=udp")),
                "keepAlive 必须出现在期望值里，实际：" + notes);
    }

    /** 库里配了网卡、节点上没有 metadata.interface：流量可能从默认网卡出去。 */
    @Test
    void ifaceExpectedButMissingOnNodeRebuildsService() throws Exception {
        nodeInterfaceName = "eth0";
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tcp");
        digests.put(UDP + "@listener", "udp|keepAlive=true");
        digests.put("@listener_supported", "true");
        digests.put("@iface_supported", "true");

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "iface 缺失必须重建，实际：" + typeNames());
        assertTrue(notes.stream().anyMatch(n -> n.contains("库 iface=eth0 / 节点 iface=-")),
                "节点侧 null 要写 -，实际：" + notes);
    }

    /** 库里清掉了网卡、节点上还挂着旧网卡：同样要纠正。 */
    @Test
    void ifaceOnNodeButNotInLibraryRebuildsService() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tcp");
        digests.put(UDP + "@listener", "udp|keepAlive=true");
        digests.put(TCP + "@iface", "eth9");
        digests.put(UDP + "@iface", "eth9");
        digests.put("@listener_supported", "true");
        digests.put("@iface_supported", "true");

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "库里有节点多余网卡也要纠正，实际：" + typeNames());
        assertTrue(notes.stream().anyMatch(n -> n.contains("库 iface=- / 节点 iface=eth9")),
                "库里没有时期望值写 -，实际：" + notes);
    }

    /**
     * features 能力声明驱动哨兵：节点声明支持 iface，但本轮没有任何一条服务带 iface 字段
     * （节点上服务全都没配网卡），而库里配了 eth0 —— 这正是 round39 QA BLOCK 的场景，
     * 旧哨兵按"本轮是否出现字段"推断时永远判一致，这里必须判漂移并重建。
     */
    @Test
    void featuresSentinelRebuildsWhenLibraryHasIfaceButNodeReportsNoIfaceAtAll() throws Exception {
        nodeInterfaceName = "eth0";
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tcp");
        digests.put(UDP + "@listener", "udp|keepAlive=true");
        digests.put("@listener_supported", "true");
        digests.put("@iface_supported", "true"); // 来自节点 features=["listener","iface","chainIface"]

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "节点声明支持 iface 却一条都没报，等价于网卡全丢，必须按库重建，实际：" + typeNames());
        assertTrue(notes.stream().anyMatch(n -> n.contains("库 iface=eth0 / 节点 iface=-")),
                "缺失的网卡要按 - 报出来，实际：" + notes);
    }

    /**
     * 隧道转发（tunnel.type=2）的入口服务本就不下发 metadata.interface，
     * 节点上残留一份网卡不影响转发，只记录不重建 —— 否则每轮心跳都会掐断业务。
     */
    @Test
    void tunnelTypeTwoResidueIfaceIsOnlyLoggedNotRebuilt() throws Exception {
        tunnelType = 2;
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tcp");
        digests.put(UDP + "@listener", "udp|keepAlive=true");
        digests.put(TCP + "@iface", "eth9");
        digests.put(UDP + "@iface", "eth9");
        digests.put("@listener_supported", "true");
        digests.put("@iface_supported", "true");

        List<String> notes = resync(digests);

        assertTrue(notes.isEmpty(), "type=2 的残留网卡不该判漂移，实际：" + notes);
        assertNoServiceRebuild();
    }

    /** 两个维度同时漂移：只删建一次，文案按固定顺序先报 listener。 */
    @Test
    void bothDimensionsDriftRebuildsOnceAndReportsListenerFirst() throws Exception {
        nodeInterfaceName = "eth0";
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tls");
        digests.put(UDP + "@listener", "udp");
        digests.put(TCP + "@iface", "eth9");
        digests.put(UDP + "@iface", "eth9");
        digests.put("@listener_supported", "true");
        digests.put("@iface_supported", "true");

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "两个维度都漂也只删建一次，实际：" + typeNames());
        assertTrue(notes.stream().anyMatch(n -> n.contains("库 listener=tcp / 节点 listener=tls")),
                "比较顺序固定为 listener 在前，实际：" + notes);
        assertFalse(String.join("；", notes).contains("iface="),
                "listener 已判漂移时文案不再混 iface，实际：" + notes);
    }

    /** 隧道转发（type=2）时入口服务本就不下发 interface，节点报了 iface 算漂移、不报算一致。 */
    @Test
    void tunnelTypeTwoNeverExpectsIface() throws Exception {
        tunnelType = 2;
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@listener", "tcp");
        digests.put(UDP + "@listener", "udp|keepAlive=true");
        digests.put("@listener_supported", "true");
        digests.put("@iface_supported", "true");

        List<String> notes = resync(digests);

        assertTrue(notes.isEmpty(), "隧道转发的入口服务不该有 iface，缺失是一致，实际：" + notes);
        assertNoServiceRebuild();
    }

    // ---------------------------------------------------------------- 工具

    private List<String> resync(Map<String, String> digests) {
        return forwardService.resyncNodeServices(NODE_ID, List.of(TCP, UDP), new ArrayList<>(), digests);
    }

    /** 四段摘要都对得上的底噪：tcp/udp 各一条，端口/链/目标都与库一致（链段随 tunnelType 变）。 */
    private Map<String, String> digestsWithBothProtocols() {
        String chain = tunnelType == 2 ? "chains_" + TUNNEL_ID : "-";
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put(TCP, PORT + "|tcp|" + chain + "|" + REMOTE_ADDR);
        digests.put(UDP, PORT + "|udp|" + chain + "|" + REMOTE_ADDR);
        return digests;
    }

    /** 断言没有发生「删了再建」：这类用例只关心有没有被误判漂移，别的下发（如 ResumeService）不算。 */
    private void assertNoServiceRebuild() throws Exception {
        List<String> types = typeNames();
        assertFalse(types.contains("DeleteService"), "不该删服务，实际：" + types);
        assertFalse(types.contains("AddService"), "不该重建服务，实际：" + types);
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("listener-iface-drift");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr(REMOTE_ADDR);
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private Tunnel tunnel(int type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(type);
        tunnel.setStatus(1);
        return tunnel;
    }

    private ForwardPort forwardPort() {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(FORWARD_ID);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(PORT);
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
        node.setInterfaceName(nodeInterfaceName);
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
        verify(nodeSessions.get(NODE_ID), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private List<String> typeNames() throws Exception {
        List<String> types = new ArrayList<>();
        for (JSONObject message : sentMessages()) {
            types.add(message.getString("type"));
        }
        return types;
    }

    private List<String> deletedServiceNames() throws Exception {
        List<String> names = new ArrayList<>();
        for (JSONObject message : sentMessages()) {
            if (!"DeleteService".equals(message.getString("type"))) {
                continue;
            }
            JSONArray services = message.getJSONObject("data").getJSONArray("services");
            for (int i = 0; i < services.size(); i++) {
                names.add(services.getString(i));
            }
        }
        return names;
    }
}
