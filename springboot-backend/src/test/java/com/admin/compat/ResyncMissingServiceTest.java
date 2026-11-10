package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * resyncNodeServices 回归测试。
 *
 * <p>覆盖的原缺陷：节点丢了 gost.json（容器重装、磁盘清理、手工删服务）后，
 * 只有 paused 会补发 PauseService，活跃转发没有任何自愈路径——
 * 库里还在、面板显示正常，端口却没人听，用户只能等到发现业务不通。
 *
 * <p>现在的要求（宁可不补，也不能补错）：
 * 1. tcp/udp 整对缺失才下发 AddService，并按库里的端口/目标重建；
 * 2. 只剩单协议（半残）时只补缺失的那一个协议——tcp/udp 端口空间独立，不会顶掉已有监听；
 *    活跃转发遇到前缀相同但整名不同（user_tunnel 段变了）时删旧名、按库建新名；
 * 3. 暂停（status=0）的转发绝不建服务（round41 契约：暂停的转发本就不该在节点监听），
 *    只清理遗留的旧名条目，恢复时由 rebuildMissingForwardService 按库重建；
 * 4. 设备不在线/节点记录缺失只记说明，不抛异常影响 WS 线程。
 */
class ResyncMissingServiceTest {

    private static final long NODE_ID = 201L;
    private static final long FORWARD_ID = 21L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;
    private static final int PORT = 30111;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 让 AddService 返回失败（模拟端口已被别的进程占）
     */
    private boolean addServiceFails;
    /** 让 DeleteService 返回失败（模拟节点拒绝删除旧名条目） */
    private boolean deleteServiceFails;
    /** 让 PauseService 返回失败（模拟节点拒绝暂停、仍在放量） */
    private boolean pauseServiceFails;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        addServiceFails = false;
        deleteServiceFails = false;
        pauseServiceFails = false;

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
        when(forwardMapper.selectById(any())).thenReturn(forward(1));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(userTunnelService.getOne(any())).thenReturn(null);
        when(forwardPortService.list(any())).thenAnswer(invocation -> List.of(forwardPort(PORT)));
        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("resync-test");
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

    private ForwardPort forwardPort(int port) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(FORWARD_ID);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(port);
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
                if (addServiceFails && "AddService".equals(message.getString("type"))) {
                    reply.setMsg("listen tcp 0.0.0.0:" + PORT + ": bind: address already in use");
                } else if (deleteServiceFails && "DeleteService".equals(message.getString("type"))) {
                    reply.setMsg("service busy");
                } else if (pauseServiceFails && "PauseService".equals(message.getString("type"))) {
                    reply.setMsg("service 21_1_0_tcp changed while pausing");
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
            // AddService 的 data 直接就是服务数组，不是 {services:[...]} 包装
            com.alibaba.fastjson.JSONArray services = message.getJSONArray("data");
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
            com.alibaba.fastjson.JSONArray services = message.getJSONObject("data").getJSONArray("services");
            for (int i = 0; i < services.size(); i++) {
                names.add(services.getString(i));
            }
        }
        return names;
    }

    private List<String> pausedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
            if (!"PauseService".equals(message.getString("type"))) {
                continue;
            }
            com.alibaba.fastjson.JSONArray services = message.getJSONObject("data").getJSONArray("services");
            for (int i = 0; i < services.size(); i++) {
                names.add(services.getString(i));
            }
        }
        return names;
    }

    /** 服务摘要里只有「暂停标志」这一维：其余维度不参与本组用例。 */
    private Map<String, String> pausedDigests(String tcp, String udp) {
        Map<String, String> digests = new HashMap<>();
        digests.put("@paused_supported", "true");
        digests.put("21_1_0_tcp@paused", tcp);
        digests.put("21_1_0_udp@paused", udp);
        return digests;
    }

    @Test
    void pausedForwardStillRunningIsPausedAgain() throws Exception {
        // 暂停是节点侧软状态：有人改回 gost.json、老二进制没持久化 paused、补暂停失败，
        // 都会让入口服务重新收流量，而库里/面板仍是「已暂停」——超额放量 + 计费黑洞。
        when(forwardMapper.selectById(any())).thenReturn(forward(0));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID,
                List.of("21_1_0_tcp", "21_1_0_udp"), List.of(), pausedDigests("0", "1"));

        assertEquals(1, notes.size(), "应一条说明，实际：" + notes);
        assertTrue(notes.get(0).contains("已补暂停仍在运行的转发服务 21_1_0_tcp@node201"), "实际：" + notes);
        assertEquals(List.of("21_1_0_tcp"), pausedServiceNames(NODE_ID),
             "只暂停真在跑的那一个协议：缺的协议名混进批次会让节点整批失败");
        assertTrue(addedServiceNames(NODE_ID).isEmpty(), "暂停态绝不建服务");
    }

    @Test
    void absentPausedKeyWithSentinelMeansRunning() throws Exception {
        // 节点侧 Paused 是 omitempty：真在跑的服务压根不带该字段。
        // 有哨兵 + 没键 = 在运行，按「没上报」跳过就会让暂停转发一直放量。
        when(forwardMapper.selectById(any())).thenReturn(forward(0));
        Map<String, String> digests = new HashMap<>();
        digests.put("@paused_supported", "true");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID,
                List.of("21_1_0_tcp", "21_1_0_udp"), List.of(), digests);

        assertEquals(1, notes.size(), "应一条说明，实际：" + notes);
        assertTrue(notes.get(0).contains("已补暂停仍在运行的转发服务 21_1_0_tcp,21_1_0_udp@node201"), "实际：" + notes);
        assertEquals(List.of("21_1_0_tcp", "21_1_0_udp"), pausedServiceNames(NODE_ID), "两个协议都要停");
    }

    @Test
    void pausedForwardAlreadyPausedIsLeftAlone() throws Exception {
        when(forwardMapper.selectById(any())).thenReturn(forward(0));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID,
                List.of("21_1_0_tcp", "21_1_0_udp"), List.of(), pausedDigests("1", "1"));

        assertTrue(notes.isEmpty(), "都已暂停就不该有任何动作，实际：" + notes);
        assertTrue(pausedServiceNames(NODE_ID).isEmpty(), "零下发");
    }

    @Test
    void oldNodeWithoutPausedDigestIsNotJudged() throws Exception {
        // 老节点不报 paused：没有哨兵就必须整维度跳过，凭「没报」判运行态会误停服务
        when(forwardMapper.selectById(any())).thenReturn(forward(0));
        Map<String, String> digests = new HashMap<>();
        digests.put("21_1_0_tcp", "30111|tcp|-|backend:6365");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID,
                List.of("21_1_0_tcp", "21_1_0_udp"), List.of(), digests);

        assertTrue(notes.isEmpty(), "老节点不做暂停推断，实际：" + notes);
        assertTrue(pausedServiceNames(NODE_ID).isEmpty(), "零下发");
    }

    @Test
    void pausedEnforceFailureIsReported() throws Exception {
        when(forwardMapper.selectById(any())).thenReturn(forward(0));
        pauseServiceFails = true;

        List<String> notes = forwardService.resyncNodeServices(NODE_ID,
                List.of("21_1_0_tcp", "21_1_0_udp"), List.of(), pausedDigests("0", "0"));

        assertEquals(1, notes.size(), "应一条说明，实际：" + notes);
        assertTrue(notes.get(0).contains("补暂停失败"), "要在面板上暴露失败，实际：" + notes);
        assertTrue(notes.get(0).contains("可能仍在放量"), "要说清后果，实际：" + notes);
    }

    @Test
    void missingPairIsRecreatedFromDatabase() throws Exception {
        // 节点只报了别人的服务：我们的 21_1_0 整对不见了，才允许补
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("99_9_9_tcp", "99_9_9_udp"));

        assertEquals(1, notes.size(), "整对缺失应该补一次，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失服务 21_1_0@node201"), "说明要能定位到服务与节点，实际：" + notes);
        assertEquals(List.of("21_1_0_tcp", "21_1_0_udp"), addedServiceNames(NODE_ID));
    }

    @Test
    void existingPairIsLeftAlone() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("21_1_0_tcp", "21_1_0_udp"));

        assertTrue(notes.isEmpty(), "服务都在就不该有任何动作，实际：" + notes);
        assertTrue(addedServiceNames(NODE_ID).isEmpty(), "不应下发 AddService");
    }

    @Test
    void duplicateForwardPortRowsAreHandledOnce() throws Exception {
        // 同一转发在同一节点有多条 forward_port 行（历史脏数据/迁移残留）：按 forward_port 行遍历时
        // 会对同一服务名重复 cleanupPausedVariant / enforcePausedService / 补齐，
        // 产生重复 WS 消息与重复 note。按 forwardId 去重后只按第一条处置一次。
        when(forwardPortService.list(any())).thenReturn(List.of(forwardPort(30100), forwardPort(30101)));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("99_9_9_tcp", "99_9_9_udp"));

        assertEquals(1, notes.size(), "同一转发的重复端口行只能处置一次，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失服务 21_1_0@node201"), "实际：" + notes);
        assertEquals(1, notes.stream().filter(n -> n.contains("21_1_0")).count(),
                "服务名在 notes 里只能出现一次，实际：" + notes);
        assertEquals(List.of("21_1_0_tcp", "21_1_0_udp"), addedServiceNames(NODE_ID), "AddService 只能发一批");
    }

    @Test
    void halfBrokenPairIsRepairedWithMissingProtocolOnly() throws Exception {
        // 只剩 tcp：udp 那一半丢了，只补 _udp，不能把整对重发（重发会整批失败）
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("21_1_0_tcp"));

        assertEquals(1, notes.size(), "应只补一次，实际：" + notes);
        assertTrue(notes.get(0).contains("已按库补齐缺失协议 21_1_0_udp@node201"), "说明要精确到缺失协议，实际：" + notes);
        assertEquals(List.of("21_1_0_udp"), addedServiceNames(NODE_ID), "只补缺失协议，不能重发已有协议");
    }

    @Test
    void halfBrokenPairRepairsMissingTcpWhenOnlyUdpLeft() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("21_1_0_udp"));

        assertEquals(1, notes.size(), "应只补一次，实际：" + notes);
        assertTrue(notes.get(0).contains("已按库补齐缺失协议 21_1_0_tcp@node201"), "说明要精确到缺失协议，实际：" + notes);
        assertEquals(List.of("21_1_0_tcp"), addedServiceNames(NODE_ID));
    }

    @Test
    void nameVariantFromChangedUserTunnelIsRebuiltUnderExpectedName() throws Exception {
        // user_tunnel 行被删/重建 → 名字从 21_1_0 变成 21_1_5。旧实现只上报不修，
        // 面板显示正常、节点上跑旧名；现在必须先删旧名再按库建新名，且只删前缀匹配的条目。
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("21_1_5_tcp", "21_1_5_udp"));

        assertEquals(1, notes.size(), "应一条说明，实际：" + notes);
        assertTrue(notes.get(0).contains("已按库重建服务名变体"), "要说明是变体重建，实际：" + notes);
        assertTrue(notes.get(0).contains("21_1_5_tcp"), "要列出旧名，实际：" + notes);
        assertTrue(notes.get(0).contains("21_1_0@node201"), "要落到期望服务名，实际：" + notes);
        assertEquals(List.of("21_1_5_tcp", "21_1_5_udp"), deletedServiceNames(NODE_ID), "只删旧变体");
        assertEquals(List.of("21_1_0_tcp", "21_1_0_udp"), addedServiceNames(NODE_ID), "按库建新名");
    }

    @Test
    void pausedForwardVariantIsDeletedNotRebuilt() throws Exception {
        // 暂停转发 + user_tunnel 改名：节点上留着 21_1_5 的条目（可能还带 paused 元数据，
        // 也可能是运行态），主路径本来直接 continue，旧名永远没人清 —— 现在只清旧名，
        // 绝不在暂停态建服务（round41 契约），恢复时由 round27 按库重建。
        when(forwardMapper.selectById(any())).thenReturn(forward(0));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("21_1_5_tcp", "21_1_5_udp"));

        assertEquals(1, notes.size(), "应一条说明，实际：" + notes);
        assertTrue(notes.get(0).contains("已清理暂停转发的旧名服务"), "要说明清的是暂停转发的旧名，实际：" + notes);
        assertTrue(notes.get(0).contains("恢复时将按库重建"), "要说明恢复路径会重建，实际：" + notes);
        assertEquals(List.of("21_1_5_tcp", "21_1_5_udp"), deletedServiceNames(NODE_ID), "只删旧变体");
        assertTrue(addedServiceNames(NODE_ID).isEmpty(), "暂停转发绝不建服务，实际：" + addedServiceNames(NODE_ID));
    }

    @Test
    void pausedVariantCleanupFailureIsReported() throws Exception {
        when(forwardMapper.selectById(any())).thenReturn(forward(0));
        deleteServiceFails = true;

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("21_1_5_tcp"));

        assertEquals(1, notes.size(), "应一条说明，实际：" + notes);
        assertTrue(notes.get(0).contains("清理失败"), "删除失败必须显式上报，实际：" + notes);
        assertTrue(notes.get(0).contains("旧名条目仍在"), "要说清残留状态，实际：" + notes);
        assertTrue(addedServiceNames(NODE_ID).isEmpty(), "清理失败也不能退化成建服务");
    }

    @Test
    void pausedForwardIsNotResurrected() throws Exception {
        when(forwardMapper.selectById(any())).thenReturn(forward(0));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, new ArrayList<>());

        assertTrue(notes.isEmpty(), "暂停的转发本来就不该有服务，实际：" + notes);
        assertTrue(addedServiceNames(NODE_ID).isEmpty());
    }

    @Test
    void emptyReportIsIgnoredBecauseNodeOnlyReportsNonEmptyLists() throws Exception {
        // 节点侧 reportServiceList 遇到空清单压根不发；收到空清单说明上游解析有问题，
        // 当成“服务全丢”去补会在节点真的空的时候无事可做、在解析出错时凭空补服务。
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, new ArrayList<>());

        assertTrue(notes.isEmpty(), "空清单不做任何推断，实际：" + notes);
        assertTrue(addedServiceNames(NODE_ID).isEmpty());
    }

    @Test
    void addFailureIsReportedWithoutThrowing() throws Exception {
        addServiceFails = true;

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("99_9_9_tcp"));

        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("失败"), "要把节点返回的失败原因带出来，实际：" + notes);
        assertTrue(notes.get(0).contains("address already in use"), "保留原始错误，实际：" + notes);
    }
}
