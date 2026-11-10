package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createForward 失败回滚回归测试。
 *
 * <p>覆盖已修复的两个缺陷：
 * 1. 第一个入口下发就失败（success 为空）时仍然必须返回错误；
 * 2. 多个入口下发成功后才失败时，已成功的入口必须全部尝试清理，而不是只清理第一个。
 *
 * <p>节点下发通道（WebSocketServer 静态会话表）由测试内的假会话接管，
 * 每台节点返回 OK / 失败由本测试指定，不依赖真实 gost 节点。
 */
class ForwardRollbackTest {

    private static final long FORWARD_ID = 1L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    private static final int IN_PORT = 20000;
    private static final long[] NODE_IDS = {101L, 102L, 103L};
    private static final String SERVICE_NAME = FORWARD_ID + "_" + USER_ID + "_0";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 节点下发 AddService 时返回的消息，未设置则为 OK */
    private final Map<Long, String> addServiceResults = new HashMap<>();
    /** 清理（DeleteService）失败的节点 */
    private final Set<Long> cleanupFailureNodes = new HashSet<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        addServiceResults.clear();
        cleanupFailureNodes.clear();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        when(forwardMapper.insert(any(Forward.class))).thenAnswer(invocation -> {
            ((Forward) invocation.getArgument(0)).setId(FORWARD_ID);
            return 1;
        });
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainTunnels());
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));

        for (long nodeId : NODE_IDS) {
            registerNode(nodeId);
        }
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void firstEntryFailureReturnsErrorInsteadOfOk() throws Exception {
        authenticate();
        addServiceResults.put(NODE_IDS[0], "add service rejected");

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode(), "首个入口下发失败时必须返回错误，不能返回成功");
        assertTrue(result.getMsg().contains("add service rejected"), "错误信息应保留下发失败原因，实际：" + result.getMsg());
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
        assertEquals(List.of("AddService"), sentMessageTypes(NODE_IDS[0]));
        assertEquals(List.of(), sentMessageTypes(NODE_IDS[1]), "失败后不应继续下发后续入口");
        assertEquals(List.of(), sentMessageTypes(NODE_IDS[2]), "失败后不应继续下发后续入口");
        verify(nodeSessions.get(NODE_IDS[1]), never()).sendMessage(any());
        verify(nodeSessions.get(NODE_IDS[2]), never()).sendMessage(any());
    }

    @Test
    void allSucceededEntriesAreCleanedWhenLaterEntryFails() throws Exception {
        authenticate();
        addServiceResults.put(NODE_IDS[2], "add service rejected");

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("add service rejected"));
        for (int i = 0; i < NODE_IDS.length - 1; i++) {
            long nodeId = NODE_IDS[i];
            assertEquals(List.of("AddService", "DeleteService"), sentMessageTypes(nodeId),
                    "已成功入口 node" + nodeId + " 必须被清理");
            assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(nodeId));
        }
        assertEquals(List.of("AddService"), sentMessageTypes(NODE_IDS[2]), "失败入口无需清理");
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
    }

    @Test
    void cleanupFailureOfOneEntryDoesNotStopRemainingCleanup() throws Exception {
        authenticate();
        addServiceResults.put(NODE_IDS[2], "add service rejected");
        cleanupFailureNodes.add(NODE_IDS[0]);

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("add service rejected"));
        assertEquals(List.of("AddService", "DeleteService"), sentMessageTypes(NODE_IDS[1]),
                "前一个入口清理失败不能阻止后续入口清理");
        assertTrue(result.getMsg().contains("清理失败"), "应保留清理失败信息，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("node" + NODE_IDS[0]), "应指明清理失败的节点，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("delete failed"), "应保留清理失败原因，实际：" + result.getMsg());
    }

    @Test
    void firstEntryExistsIsConflictAndItsResourceIsKept() throws Exception {
        // 节点对同名服务返回 exists：只是冲突，不是本次创建成功，更不能删掉该节点原有资源
        authenticate();
        addServiceResults.put(NODE_IDS[0], "service " + SERVICE_NAME + "_udp already exists");

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode(), "exists 冲突必须让创建失败");
        assertTrue(result.getMsg().contains("已存在同名服务"), result.getMsg());
        assertTrue(result.getMsg().contains("未作删除处理"), result.getMsg());
        assertEquals(List.of("AddService"), sentMessageTypes(NODE_IDS[0]), "冲突节点的既有资源不得删除");
        assertEquals(List.of(), sentMessageTypes(NODE_IDS[1]), "冲突后不应继续下发后续入口");
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
    }

    @Test
    void middleEntryExistsRollsBackOnlyCreatedEntries() throws Exception {
        // 中间入口 exists：前面确认新建的入口要清理，冲突目标自己一个字节都不能动
        authenticate();
        addServiceResults.put(NODE_IDS[1], "service " + SERVICE_NAME + "_tcp already exists");

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("已存在同名服务"), result.getMsg());
        assertTrue(result.getMsg().contains("node" + NODE_IDS[1]) || result.getMsg().contains("节点" + NODE_IDS[1]),
                result.getMsg());
        assertEquals(List.of("AddService", "DeleteService"), sentMessageTypes(NODE_IDS[0]),
                "已确认新建的入口必须清理");
        assertEquals(List.of("AddService"), sentMessageTypes(NODE_IDS[1]), "冲突节点原有资源必须保留");
        assertEquals(List.of(), sentMessageTypes(NODE_IDS[2]), "失败后不应继续下发后续入口");
        verify(forwardMapper).deleteById(FORWARD_ID);
    }

    @Test
    void timeoutKeepsUnconfirmedNoteAndDeletesNothingOnThatNode() throws Exception {
        // 超时说明节点可能已经执行下发：既不能算创建成功，也不能据未知结果删任何东西
        authenticate();
        addServiceResults.put(NODE_IDS[2], "等待响应超时");

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("等待响应超时"), result.getMsg());
        assertTrue(result.getMsg().contains("未确认"), "必须明确提示结果未确认，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("节点" + NODE_IDS[2]), result.getMsg());
        assertEquals(List.of("AddService", "DeleteService"), sentMessageTypes(NODE_IDS[0]));
        assertEquals(List.of("AddService", "DeleteService"), sentMessageTypes(NODE_IDS[1]));
        assertEquals(List.of("AddService"), sentMessageTypes(NODE_IDS[2]), "结果未确认的节点不得被删除");
    }

    @Test
    void updatePathStillNormalizesExistsWhileCreatePathKeepsIt() throws Exception {
        // 旧更新路径行为不变：UpdateService 的 exists 仍归一成 OK，只有创建路径保留原始响应
        addServiceResults.put(NODE_IDS[0], "service " + SERVICE_NAME + "_tcp already exists");
        Node node = node(NODE_IDS[0]);
        Forward forward = new Forward();
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("10.10.10.10:8080");
        forward.setStrategy("round");
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setPort(IN_PORT);
        Tunnel tunnel = tunnel();

        GostDto created = GostUtil.CreateForwardService(SERVICE_NAME, null, node, forward, forwardPort, tunnel);
        GostDto updated = GostUtil.AddAndUpdateService(SERVICE_NAME, null, node, forward, forwardPort, tunnel, "UpdateService");

        assertTrue(created.getMsg().contains("exists"), "创建路径必须保留原始响应，实际：" + created.getMsg());
        assertEquals("OK", updated.getMsg(), "更新路径的 exists 仍按原样归一成 OK");
        assertEquals(List.of("AddService", "UpdateService"), sentMessageTypes(NODE_IDS[0]));
    }

    // ========== 测试脚手架 ==========

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ForwardDto newForwardDto() {
        ForwardDto forwardDto = new ForwardDto();
        forwardDto.setName("rollback-test");
        forwardDto.setTunnelId(TUNNEL_ID);
        forwardDto.setRemoteAddr("10.10.10.10:8080");
        forwardDto.setStrategy("round");
        forwardDto.setInPort(IN_PORT);
        return forwardDto;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private List<ChainTunnel> chainTunnels() {
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        for (long nodeId : NODE_IDS) {
            ChainTunnel chainTunnel = new ChainTunnel();
            chainTunnel.setTunnelId((long) TUNNEL_ID);
            chainTunnel.setChainType(1);
            chainTunnel.setNodeId(nodeId);
            chainTunnels.add(chainTunnel);
        }
        return chainTunnels;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + (nodeId % 100));
        node.setPort(IN_PORT + "-" + (IN_PORT + 10));
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    /**
     * 注册假节点会话：节点收到下发消息后立刻完成对应的 pendingRequest，绕开真实 WebSocket 往返。
     */
    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                future.complete(nodeReply(nodeId, message.getString("type")));
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private GostDto nodeReply(long nodeId, String type) {
        GostDto gostDto = new GostDto();
        if ("AddService".equals(type)) {
            gostDto.setMsg(addServiceResults.getOrDefault(nodeId, "OK"));
        } else if ("DeleteService".equals(type)) {
            gostDto.setMsg(cleanupFailureNodes.contains(nodeId) ? "delete failed" : "OK");
        } else {
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    private List<String> sentMessageTypes(long nodeId) throws Exception {
        List<String> types = new ArrayList<>();
        for (String payload : sentPayloads(nodeId)) {
            types.add(JSON.parseObject(payload).getString("type"));
        }
        return types;
    }

    private List<String> deletedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (String payload : sentPayloads(nodeId)) {
            JSONObject message = JSON.parseObject(payload);
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

    private List<String> sentPayloads(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<String> payloads = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            payloads.add(message.getPayload());
        }
        return payloads;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
