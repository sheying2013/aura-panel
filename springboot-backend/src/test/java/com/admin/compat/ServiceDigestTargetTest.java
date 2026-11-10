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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 服务摘要对账：监听地址与「全部目标」的回归测试。
 *
 * <p>覆盖两处曾经的盲区：
 * <ol>
 *   <li>摘要只取 remoteAddr 的第一条目标 —— 多目标转发的第 2..N 条被删/被加/被换顺序，
 *       服务名和端口都对得上，永远发现不了，节点上跑的其实是另一套负载均衡；</li>
 *   <li>摘要只取端口、不带监听地址 —— 库里把 tcp_listen_addr 从 0.0.0.0 改成 127.0.0.1，
 *       端口没变，摘要一字不变，节点还在全地址监听也没人纠正。</li>
 * </ol>
 *
 * <p>同时钉住"零抖动"这条硬约束：通配写法（0.0.0.0 / :: / 空）必须与带 host 的写法等价，
 * 老节点（listenAddr 留空或写 0.0.0.0、只报端口式摘要）不得因为格式升级被判漂移。
 */
class ServiceDigestTargetTest {

    private static final long NODE_ID = 211L;
    private static final long FORWARD_ID = 31L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;
    private static final int PORT = 30100;
    private static final String SERVICE_NAME = "31_1_0";
    private static final String T1 = "backend1:6365";
    private static final String T2 = "backend2:6365";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private Node node;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        node = node(NODE_ID);
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

        when(nodeService.getById(any())).thenReturn(node);
        when(forwardMapper.selectById(any())).thenReturn(forward(T1 + "," + T2));
        when(tunnelService.getById(any())).thenReturn(tunnel(1));
        when(userTunnelService.getOne(any())).thenReturn(null);
        when(forwardPortService.list(any())).thenAnswer(invocation -> List.of(forwardPort()));
        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    private Forward forward(String remoteAddr) {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("digest-target-test");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr(remoteAddr);
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
        node.setServerIp("10.0.0.6");
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

    private List<JSONObject> sentMessages(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private List<String> deletedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
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

    /** 节点两侧都在（tcp+udp 名字齐全），把注意力集中在摘要上。 */
    private static List<String> bothProtocolsReported() {
        return List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp");
    }

    private static Map<String, String> digests(String tcpDigest, String udpDigest) {
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put(SERVICE_NAME + "_tcp", tcpDigest);
        digests.put(SERVICE_NAME + "_udp", udpDigest);
        return digests;
    }

    private List<String> resync(Map<String, String> reported) {
        return forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), reported);
    }

    // ---------------------------------------------------------------- 全部目标

    @Test
    void allTargetsMatchingLeavesServiceAlone() throws Exception {
        // 库两条目标、节点上报两条且顺序一致：一个字都不能动
        List<String> notes = resync(digests(
                PORT + "|tcp|-|" + T1 + "," + T2,
                PORT + "|udp|-|" + T1 + "," + T2));

        assertTrue(notes.isEmpty(), "全部目标都一致就不该有任何动作，实际：" + notes);
        assertTrue(deletedServiceNames(NODE_ID).isEmpty(), "一致时不能删服务");
        assertTrue(addedServiceNames(NODE_ID).isEmpty(), "一致时不能重建服务");
    }

    @Test
    void fewerTargetsOnNodeIsDrift() throws Exception {
        // 库里有两条目标，节点上只剩第一条：第二条被删掉了，必须按库重建
        List<String> notes = resync(digests(
                PORT + "|tcp|-|" + T1,
                PORT + "|udp|-|" + T1 + "," + T2));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "漂移要先删整对，否则 AddService 同名整批失败");
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), addedServiceNames(NODE_ID),
                "删完要按库重建整对");
        assertTrue(notes.get(0).contains(PORT + "|tcp|-|" + T1 + "," + T2),
                "说明里要带上库里的两条目标，实际：" + notes);
        assertTrue(notes.get(0).contains(PORT + "|tcp|-|" + T1),
                "说明里要带节点上的一条目标，实际：" + notes);
    }

    @Test
    void extraTargetsOnNodeIsDrift() throws Exception {
        // 库里只有一条目标，节点上多出第二条：多出来的目标也要判漂移
        when(forwardMapper.selectById(any())).thenReturn(forward(T1));

        List<String> notes = resync(digests(
                PORT + "|tcp|-|" + T1 + "," + T2,
                PORT + "|udp|-|" + T1));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID));
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), addedServiceNames(NODE_ID));
        assertTrue(notes.get(0).contains(PORT + "|tcp|-|" + T1 + "," + T2),
                "实际摘要要反映节点上多出来的目标，实际：" + notes);
    }

    @Test
    void targetOrderMatters() throws Exception {
        // 顺序即负载均衡顺序：节点上报的 targets 与库里的 remoteAddr 顺序不同也算漂移
        List<String> notes = resync(digests(
                PORT + "|tcp|-|" + T2 + "," + T1,
                PORT + "|udp|-|" + T1 + "," + T2));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "目标顺序不同要按库重建，实际：" + notes);
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), addedServiceNames(NODE_ID));
    }

    // ---------------------------------------------------------------- 监听地址

    @Test
    void wildcardListenAddrIsEquivalentOnTcp() throws Exception {
        // 库里 tcp/udp_listen_addr=0.0.0.0，节点上报 addr=[::]:30100 —— 同为通配，必须判一致，
        // 否则通配 / IPv6 通配一换写法就是重建风暴
        List<String> notes = resync(toDigests(entries(
                entry(SERVICE_NAME + "_tcp", PORT, T1, T2),
                entry(SERVICE_NAME + "_udp", PORT, T1, T2))));

        assertTrue(notes.isEmpty(), "通配写法等价，不得判漂移，实际：" + notes);
        assertTrue(deletedServiceNames(NODE_ID).isEmpty(), "通配等价时不能删服务");
        assertTrue(addedServiceNames(NODE_ID).isEmpty(), "通配等价时不能重建服务");
    }

    @Test
    void concreteListenAddrOnUdpIsDrift() throws Exception {
        // 库里 udp_listen_addr=127.0.0.1，节点还在 [::]:30100 全地址监听：端口一样、监听地址不一样
        node.setUdpListenAddr("127.0.0.1");

        List<String> notes = resync(digests(
                PORT + "|tcp|-|" + T1 + "," + T2,
                PORT + "|udp|-|" + T1 + "," + T2));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "udp 监听地址漂移要整对删除，实际：" + notes);
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), addedServiceNames(NODE_ID));
        assertTrue(notes.get(0).contains("127.0.0.1:" + PORT + "|udp|"),
                "期望摘要要带库里的 udp 监听地址，实际：" + notes);
        assertTrue(notes.get(0).contains("|udp|-|" + T1),
                "实际摘要只有端口，说明里要看得出差异，实际：" + notes);
    }

    @Test
    void concreteListenAddrOnTcpIsDrift() throws Exception {
        // 库里 tcp_listen_addr=127.0.0.1，节点上报 0.0.0.0:30100：同端口、不同监听地址
        node.setTcpListenAddr("127.0.0.1");

        List<String> notes = resync(digests(
                PORT + "|tcp|-|" + T1 + "," + T2,
                PORT + "|udp|-|" + T1 + "," + T2));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "tcp 监听地址漂移要整对删除，实际：" + notes);
        assertTrue(notes.get(0).contains("127.0.0.1:" + PORT + "|tcp|"),
                "期望摘要要带库里的监听地址，实际：" + notes);
    }

    // ---------------------------------------------------------------- 节点侧归一

    @Test
    void nodeDigestCarriesAllTargetsInReportedOrder() throws Exception {
        // WebSocketServer.toDigestMap 是"节点上报 → 可比摘要"的唯一入口：必须按上报顺序带上全部目标
        Map<String, String> first = toDigests(single(entry(SERVICE_NAME + "_tcp", PORT, T1, T2)));
        assertEquals(PORT + "|tcp|-|" + T1 + "," + T2, first.get(SERVICE_NAME + "_tcp"),
                "两条目标要按上报顺序全部带出");

        Map<String, String> reversed = toDigests(single(entry(SERVICE_NAME + "_tcp", PORT, T2, T1)));
        assertEquals(PORT + "|tcp|-|" + T2 + "," + T1, reversed.get(SERVICE_NAME + "_tcp"),
                "端点不排序：节点报什么顺序就是什么顺序");
    }

    private static JSONArray single(JSONObject entry) {
        return entries(entry);
    }

    private static JSONArray entries(JSONObject... items) {
        JSONArray array = new JSONArray();
        for (JSONObject item : items) {
            array.add(item);
        }
        return array;
    }

    /** 走 WebSocketServer.toDigestMap 拿"节点上报 → 可比摘要"，与生产同一个入口。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> toDigests(JSONArray reported) throws Exception {
        Method toDigestMap = Class.forName("com.admin.common.utils.WebSocketServer")
                .getDeclaredMethod("toDigestMap", JSONArray.class);
        toDigestMap.setAccessible(true);
        return (Map<String, String>) toDigestMap.invoke(null, reported);
    }

    private static JSONObject entry(String name, int port, String... targets) {
        JSONObject entry = new JSONObject();
        entry.put("name", name);
        entry.put("addr", "[::]:" + port);
        // handler 必须跟着服务名的协议走：udp 服务上报 handler=udp，
        // 一律写 TCP 会让"通配等价"用例自己造出一条假漂移
        entry.put("handler", name.endsWith("_udp") ? "UDP" : "TCP");
        JSONArray list = new JSONArray();
        for (String target : targets) {
            list.add(target);
        }
        entry.put("targets", list);
        return entry;
    }

}
