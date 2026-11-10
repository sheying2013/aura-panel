package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点服务「配置漂移」对账回归测试。
 *
 * <p>覆盖的原缺陷（台账 1075 行）：对账只看服务名在不在清单里，不看内容——
 * 库里改过端口/remoteAddr、或节点侧配置被改过，名字依然对得上，永远不纠正，
 * 面板显示正常，实际却转发到旧目标。
 *
 * <p>现在的要求：
 * 1. 摘要一致 → 一动不动（连 AddService 都不发）；
 * 2. 端口/目标/链任一漂移 → 先 DeleteService 再 CreateForwardService 重建整对；
 * 3. 摘要缺失（老节点）→ 保持原有"只比名字"的行为；
 * 4. 重建失败只记说明，不抛异常影响 WS 线程。
 */
class ServiceDriftResyncTest {

    private static final long NODE_ID = 201L;
    private static final long FORWARD_ID = 21L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;
    private static final int PORT = 30111;
    private static final String SERVICE_NAME = "21_1_0";
    private static final String REMOTE_ADDR = "backend:6365";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 让重建用的 AddService 返回失败（模拟端口被占/链缺失） */
    private boolean addServiceFails;

    /** 库里节点配置的 tcp 监听地址，用例可覆盖（默认通配，与老数据一致） */
    private String tcpListenAddr = "0.0.0.0";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        addServiceFails = false;

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
        when(forwardMapper.selectById(any())).thenReturn(forward());
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

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("drift-test");
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
        node.setTcpListenAddr(tcpListenAddr);
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
                if (addServiceFails && "AddService".equals(message.getString("type"))) {
                    reply.setMsg("listen tcp 0.0.0.0:" + PORT + ": bind: address already in use");
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

    private List<String> messageTypes(long nodeId) throws Exception {
        List<String> types = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
            types.add(message.getString("type"));
        }
        return types;
    }

    private List<String> addedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
            if (!"AddService".equals(message.getString("type"))) {
                continue;
            }
            // AddService 的 data 直接就是服务数组，不是 {services:[...]} 包装
            JSONArray services = message.getJSONArray("data");
            for (int i = 0; i < services.size(); i++) {
                names.add(services.getJSONObject(i).getString("name"));
            }
        }
        return names;
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

    /** 节点两侧都在（tcp+udp 名字齐全），便于把注意力集中在摘要上。 */
    private static List<String> bothProtocolsReported() {
        return List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp");
    }

    private static Map<String, String> digests(String digest) {
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put(SERVICE_NAME + "_tcp", digest);
        return digests;
    }

    @Test
    void digestFormatIsSharedBetweenBothEnds() {
        // 两端同一口径：port|handler|chain|target，addr 只取数字端口，空值写 "-"
        assertEquals("30200|tcp|-|backend:6365",
                GostUtil.normalizeServiceDigest("[::]:30200", "TCP", null, "backend:6365"));
        assertEquals("30200|relay|chains_16|-",
                GostUtil.normalizeServiceDigest("0.0.0.0:30200", "relay", "chains_16", ""));
    }

    @Test
    void nodeReportDigestsAreNormalizedBeforeComparison() throws Exception {
        // WebSocketServer.toDigestMap 是"节点上报 → 可比摘要"的唯一入口：
        // 字段缺失/空数组都必须变成 null（否则会拿空摘要去判漂移、删人家在跑的服务）。
        Method toDigestMap = Class.forName("com.admin.common.utils.WebSocketServer")
                .getDeclaredMethod("toDigestMap", JSONArray.class);
        toDigestMap.setAccessible(true);
        assertNull(toDigestMap.invoke(null, new Object[]{null}), "老节点没有 digests 字段要当没报");
        assertNull(toDigestMap.invoke(null, new JSONArray()), "空数组同样当没报");

        JSONArray reported = new JSONArray();
        JSONObject entry = new JSONObject();
        entry.put("name", SERVICE_NAME + "_tcp");
        entry.put("addr", "[::]:" + PORT);
        entry.put("handler", "TCP");
        JSONArray targets = new JSONArray();
        targets.add("backend:6365");
        targets.add("backup:6365");
        entry.put("targets", targets);
        reported.add(entry);

        @SuppressWarnings("unchecked")
        Map<String, String> digests = (Map<String, String>) toDigestMap.invoke(null, reported);
        assertEquals(PORT + "|tcp|-|backend:6365,backup:6365", digests.get(SERVICE_NAME + "_tcp"),
                "port 只取数字端口、handler 小写、无链写 -、target 按顺序全量保留（round37 起不再只取第一条）");
    }

    @Test
    void matchingDigestLeavesServiceAlone() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests(PORT + "|tcp|-|" + REMOTE_ADDR));

        assertTrue(notes.isEmpty(), "摘要一致就不该有任何动作，实际：" + notes);
        assertTrue(deletedServiceNames(NODE_ID).isEmpty(), "摘要一致不能删服务");
        assertTrue(addedServiceNames(NODE_ID).isEmpty(), "摘要一致不能重建服务");
    }

    @Test
    void forwarderStrategyDriftRebuildsService() throws Exception {
        // round38：面板把策略从 fifo 改成 round，节点上还是 fifo。端口和目标都没变，
        // 只比四段摘要永远发现不了，客户端按旧策略打到一个已经下线的目标上也不会切走。
        Map<String, String> digests = digests(PORT + "|tcp|-|" + REMOTE_ADDR);
        digests.put(SERVICE_NAME + "_tcp@strategy", "fifo");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests);

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "策略漂移必须整对删了重建，否则 AddService 同名整批失败");
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), addedServiceNames(NODE_ID));
        assertEquals(1, notes.size(), "只该有一条重建说明，实际：" + notes);
        assertTrue(notes.get(0).contains("配置漂移"), "说明里要写明是配置漂移，实际：" + notes);
        assertTrue(notes.get(0).contains("strategy=round"), "要带库里的期望策略，实际：" + notes);
        assertTrue(notes.get(0).contains("strategy=fifo"), "要带节点上的实际策略，实际：" + notes);
    }

    @Test
    void matchingStrategyLeavesServiceAlone() throws Exception {
        Map<String, String> digests = digests(PORT + "|tcp|-|" + REMOTE_ADDR);
        digests.put(SERVICE_NAME + "_tcp@strategy", "round");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests);

        assertTrue(notes.isEmpty(), "策略一致不该有任何动作，实际：" + notes);
        assertTrue(deletedServiceNames(NODE_ID).isEmpty(), "策略一致不能删服务");
    }

    @Test
    void oldNodeWithoutStrategyIsNotJudged() throws Exception {
        // 未升级节点不报 strategy 键。绝不能当成"策略是空串"去比 —— 否则升级后端那一刻
        // 存量节点全部被判漂移，一轮心跳删建几百条服务。
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests(PORT + "|tcp|-|" + REMOTE_ADDR));

        assertTrue(notes.isEmpty(), "节点没报策略就必须跳过该维度，实际：" + notes);
        assertTrue(deletedServiceNames(NODE_ID).isEmpty(), "老节点不能被误判漂移");
    }

    @Test
    void portDriftRebuildsService() throws Exception {
        // 库里端口是 30111，节点上却在听 30112
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests("30112|tcp|-|" + REMOTE_ADDR));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "漂移必须先把整对删掉，否则 AddService 同名整批失败");
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), addedServiceNames(NODE_ID),
                "删完要按库重建整对");
        assertEquals(1, notes.size(), "只该有一条重建说明，实际：" + notes);
        assertTrue(notes.get(0).contains("配置漂移"), "说明里要写明是配置漂移，实际：" + notes);
        assertTrue(notes.get(0).contains("30111|tcp|-|" + REMOTE_ADDR), "要带上库里的期望摘要，实际：" + notes);
        assertTrue(notes.get(0).contains("30112|tcp|-|" + REMOTE_ADDR), "要带上节点上的实际摘要，实际：" + notes);
    }

    @Test
    void udpDriftRebuildsServiceEvenWhenTcpMatches() throws Exception {
        // round33 遗留：只比 _tcp 摘要时，节点上 udp 侧端口/目标被改过永远发现不了
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put(SERVICE_NAME + "_tcp", PORT + "|tcp|-|" + REMOTE_ADDR);
        digests.put(SERVICE_NAME + "_udp", "30112|udp|-|" + REMOTE_ADDR);

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests);

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "udp 漂移也要整对删，否则 AddService 同名整批失败");
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), addedServiceNames(NODE_ID),
                "删完按库重建整对");
        assertEquals(1, notes.size(), "只该有一条重建说明，实际：" + notes);
        assertTrue(notes.get(0).contains(SERVICE_NAME + "_udp 配置漂移"), "说明要点名是 udp 侧漂移，实际：" + notes);
        assertTrue(notes.get(0).contains("30112|udp|-|" + REMOTE_ADDR), "要带上节点上的实际 udp 摘要，实际：" + notes);
    }

    @Test
    void remoteAddrDriftRebuildsService() throws Exception {
        // 库里 remoteAddr 改成 backend2:6365 了，节点还在往旧目标转发
        Forward moved = forward();
        moved.setRemoteAddr("backend2:6365");
        when(forwardMapper.selectById(any())).thenReturn(moved);

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests(PORT + "|tcp|-|" + REMOTE_ADDR));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID));
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), addedServiceNames(NODE_ID));
        assertTrue(notes.get(0).contains("backend2:6365"), "期望摘要要反映库里的新目标，实际：" + notes);
        assertTrue(notes.get(0).contains(REMOTE_ADDR), "实际摘要要反映节点上的旧目标，实际：" + notes);
    }

    @Test
    void chainDriftRebuildsService() throws Exception {
        // 隧道型转发：库里的服务必须挂 chains_1，节点上却没挂链（直连）
        when(tunnelService.getById(any())).thenReturn(tunnel(2));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests(PORT + "|tcp|-|" + REMOTE_ADDR));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID));
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), addedServiceNames(NODE_ID));
        assertTrue(notes.get(0).contains("30111|tcp|chains_" + TUNNEL_ID), "期望摘要要带链，实际：" + notes);
    }

    @Test
    void missingDigestsKeepLegacyBehaviour() throws Exception {
        // 老节点没上报摘要：只按名字对账，一个字节都不能动
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported());

        assertTrue(notes.isEmpty(), "老节点上不该有动作，实际：" + notes);
        assertTrue(deletedServiceNames(NODE_ID).isEmpty(), "没有摘要依据不能删服务");
        assertTrue(addedServiceNames(NODE_ID).isEmpty());
    }

    @Test
    void digestForUnknownServiceIsIgnored() throws Exception {
        // 摘要里有别的服务、没有我们这台的：说明这次没报它，不能凭空判漂移
        Map<String, String> onlyOthers = new LinkedHashMap<>();
        onlyOthers.put("99_9_9_tcp", "30200|tcp|-|other:1");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), onlyOthers);

        assertTrue(notes.isEmpty(), "摘要里没有这条服务就不该动，实际：" + notes);
        assertTrue(deletedServiceNames(NODE_ID).isEmpty());
    }

    @Test
    void rebuildFailureIsOnlyReported() throws Exception {
        addServiceFails = true;

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests("30112|tcp|-|" + REMOTE_ADDR));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "漂移服务要先删掉");
        assertEquals(1, notes.size(), "失败只记一条说明，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已删除漂移服务 " + SERVICE_NAME), "说明要写清已删待重建，实际：" + notes);
        assertTrue(notes.get(0).contains("但重建失败"), "要写明重建失败，实际：" + notes);
        assertTrue(notes.get(0).contains("address already in use"), "保留原始错误，实际：" + notes);
        assertTrue(!messageTypes(NODE_ID).contains("ResumeService"),
                "重建失败后不能再走恢复逻辑，实际消息：" + messageTypes(NODE_ID));
    }

    @Test
    void multiTargetListIsComparedAsWhole() throws Exception {
        // round37：库里只有一个目标，节点上有两个 —— 多出来的那条会把一半流量导去别处
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests(PORT + "|tcp|-|" + REMOTE_ADDR + ",backup:6365"));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "目标列表不一致必须整对重建");
        assertEquals(1, notes.size(), "只该有一条重建说明，实际：" + notes);
        assertTrue(notes.get(0).contains("|" + REMOTE_ADDR + " "), "期望摘要只带库里的单目标，实际：" + notes);
        assertTrue(notes.get(0).contains(REMOTE_ADDR + ",backup:6365"), "实际摘要要反映节点上的双目标，实际：" + notes);
    }

    @Test
    void multiTargetOrderDriftRebuildsService() throws Exception {
        // 库里是 backend:6365,backup:6365，节点上顺序反了：round/round 策略下主备语义就变了
        Forward twoTargets = forward();
        twoTargets.setRemoteAddr("backend:6365,backup:6365");
        when(forwardMapper.selectById(any())).thenReturn(twoTargets);

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests(PORT + "|tcp|-|backup:6365,backend:6365"));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "顺序不同也是不同配置");
        assertTrue(notes.get(0).contains("|backend:6365,backup:6365"), "期望摘要按库里的顺序，实际：" + notes);
    }

    @Test
    void listenAddrDriftRebuildsService() throws Exception {
        // round37：库里把节点 tcp 监听地址从 0.0.0.0 收到 127.0.0.1，端口没变
        tcpListenAddr = "127.0.0.1";

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests(PORT + "|tcp|-|" + REMOTE_ADDR));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "监听地址变了必须重建，否则新地址永远不生效");
        assertTrue(notes.get(0).contains("127.0.0.1:" + PORT + "|tcp|"), "期望摘要要带库里的监听地址，实际：" + notes);
    }

    @Test
    void wildcardListenAddrsAreTreatedAsEquivalent() throws Exception {
        // 库里写 0.0.0.0、节点上是 [::]：都是"全部地址"，不能因此反复重建。
        // 等价发生在 toDigestMap 归一化这一步（节点原始 addr 是 "[::]:30111"），所以这里直接比归一化结果。
        Method toDigestMap = Class.forName("com.admin.common.utils.WebSocketServer")
                .getDeclaredMethod("toDigestMap", JSONArray.class);
        toDigestMap.setAccessible(true);
        assertEquals(entryDigest(toDigestMap, "[::]:" + PORT), entryDigest(toDigestMap, "0.0.0.0:" + PORT),
                "0.0.0.0 与 [::] 都是通配，归一到同一个摘要");
        assertEquals(PORT + "|tcp|-|" + REMOTE_ADDR, entryDigest(toDigestMap, "[::]:" + PORT));

        // 归一化后的摘要 == 按库算出的期望摘要 → 节点已在跑正确配置，一次都不该动
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), digests(entryDigest(toDigestMap, "[::]:" + PORT)));
        assertTrue(notes.isEmpty(), "通配写法等价，不该有动作，实际：" + notes);
        assertTrue(deletedServiceNames(NODE_ID).isEmpty());
    }

    /** 用生产入口 toDigestMap 把一条节点上报归一化，避免手写摘要掩盖归一口径的 bug。 */
    @SuppressWarnings("unchecked")
    private static String entryDigest(Method toDigestMap, String addr) throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("name", SERVICE_NAME + "_tcp");
        entry.put("addr", addr);
        entry.put("handler", "TCP");
        JSONArray targets = new JSONArray();
        targets.add(REMOTE_ADDR);
        entry.put("targets", targets);
        JSONArray reported = new JSONArray();
        reported.add(entry);
        Map<String, String> result = (Map<String, String>) toDigestMap.invoke(null, reported);
        return result.get(SERVICE_NAME + "_tcp");
    }
}
