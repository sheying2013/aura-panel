package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
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
 * 连接对账（round12）回归测试。
 *
 * <p>覆盖的原盲区：go-gost 的 reportServiceList() 在 services/chains 双空时直接跳过上报，
 * 于是"整机配置被清空/容器重装"的节点连上后既不会被清扫也不会被补齐——面板显示在线，
 * 端口却没人听，这是最难被发现的一类静默失效。
 *
 * <p>现在的约束：
 * 1. 连上之后一直没自报清单，才触发连接对账；
 * 2. 对账用哨兵清单绕过"双空清单不可信"的守卫，按库把整对服务补回来；
 * 3. 对账只补不删：哨兵清单里没有真实服务名，重挂链前的删除永远不命中；
 * 4. 空清单（用户/解析给的空数组）仍然不做任何推断，避免凭空造资源。
 */
class ConnectReconcileTest {

    private static final long NODE_ID = 301L;
    private static final long FORWARD_ID = 31L;
    private static final int USER_ID = 2;
    private static final int TUNNEL_ID = 3;
    private static final int PORT = 30131;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

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

    @Test
    void reconcileOnlyWhenNodeNeverReportedSinceConnect() {
        long connectAt = 1_000L;
        assertTrue(WebSocketServer.shouldReconcileOnConnect(connectAt, null), "从没上报过必须兜底");
        assertTrue(WebSocketServer.shouldReconcileOnConnect(connectAt, connectAt - 1),
                "上次连接的上报不算这次");
        assertFalse(WebSocketServer.shouldReconcileOnConnect(connectAt, connectAt),
                "本次连上后已经自报过，走上报链路，不要再盲推一遍");
        assertFalse(WebSocketServer.shouldReconcileOnConnect(connectAt, connectAt + 500));
    }

    @Test
    void unknownReportRefillsWholeServicePair() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID,
                List.of("__reconcile_unknown__"), List.of("__reconcile_unknown__"));

        assertEquals(1, notes.size(), "整对缺失应该补一次，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失服务 31_2_0@node301"), "说明要能定位，实际：" + notes);
        assertEquals(List.of("31_2_0_tcp", "31_2_0_udp"), addedServiceNames(NODE_ID));
    }

    @Test
    void reconcileNeverDeletesAnything() throws Exception {
        forwardService.resyncNodeServices(NODE_ID,
                List.of("__reconcile_unknown__"), List.of("__reconcile_unknown__"));

        assertTrue(deleteTypes(NODE_ID).isEmpty(),
                "哨兵清单命不中任何真实服务名，对账路径必须一条删除都不发，实际：" + deleteTypes(NODE_ID));
    }

    @Test
    void emptyReportStillDoesNotInventResources() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, new ArrayList<>(), new ArrayList<>());

        assertTrue(notes.isEmpty(), "空清单不可信，不做推断，实际：" + notes);
        assertTrue(addedServiceNames(NODE_ID).isEmpty(), "不应下发 AddService");
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("connect-reconcile");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(1);
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
        node.setPort("30130-30140");
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

    private List<String> deleteTypes(long nodeId) throws Exception {
        List<String> types = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
            String type = message.getString("type");
            if (type != null && type.startsWith("Delete")) {
                types.add(type);
            }
        }
        return types;
    }
}
