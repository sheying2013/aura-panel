package com.admin.compat;

import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.User;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * updateForward 的「先下发、后落库」安全边界。
 *
 * <p>真机缺陷（基线 761db18）：节点离线时接口返回 {"code":-1,"msg":"节点不在线"}，
 * 但 DB 的 remoteAddr 已经改成新值，节点仍按旧配置转发，重连也拉不回来 —— 永久静默漂移。
 *
 * <p>这里锁死三件事：节点下发失败时库里一个字段都不许变；中途失败的已下发节点要改回原配置；
 * 只有全部节点接受新配置之后才允许写库。数据库与节点之间没有事务，只保证"尽力补偿 + 如实报告"。
 */
class ForwardUpdateSafetyTest {

    private static final long FORWARD_ID = 1L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    private static final long FORWARD_PORT_ID = 11L;
    private static final int OLD_PORT = 20005;
    private static final int AVAILABLE_PORT = 20000;
    private static final long[] NODE_IDS = {101L, 102L};
    private static final String OLD_ADDR = "10.1.1.1:8080";
    private static final String NEW_ADDR = "10.2.2.2:9090";
    private static final String OLD_NAME = "old-name";
    private static final String NEW_NAME = "new-name";
    private static final String OLD_STRATEGY = "fifo";
    private static final String NEW_STRATEGY = "round";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private final Map<Long, String> updateServiceResults = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        updateServiceResults.clear();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        // checkUserPermissions 持锁后先重读账号行：管理员替身必须能从库里读到一条 role_id=0 的行，否则按账号不存在拒绝。
        UserService stubbedUserService = mock(UserService.class);
        User stubbedAdminRow = new User();
        stubbedAdminRow.setId((long) USER_ID);
        stubbedAdminRow.setRoleId(0);
        when(stubbedUserService.getById(any())).thenReturn(stubbedAdminRow);
        ReflectionTestUtils.setField(forwardService, "userService", stubbedUserService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);

        when(forwardMapper.selectById(any())).thenReturn(storedForward());
        when(forwardMapper.updateById(any(Forward.class))).thenReturn(1);
        when(forwardMapper.selectCount(any())).thenReturn(0);
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainTunnels());
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.getOne(any())).thenAnswer(invocation -> storedPort());
        when(forwardPortService.updateById(any(ForwardPort.class))).thenReturn(true);
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(userTunnelService.getOne(any())).thenReturn(userTunnel());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ========== 反向用例：下发失败时库必须保持原值 ==========

    @Test
    void nodeOfflineLeavesDatabaseUntouched() throws Exception {
        authenticateAdmin();

        R result = forwardService.updateForward(updateDto());

        assertEquals(-1, result.getCode(), "节点不在线时必须返回错误");
        assertEquals("节点不在线", result.getMsg(), "错误信息要如实透出节点侧原因");
        verify(forwardMapper, never()).updateById(any(Forward.class));
        verify(forwardPortService, never()).updateById(any(ForwardPort.class));
    }

    @Test
    void laterNodeFailureRestoresAlreadyDispatchedNodes() throws Exception {
        authenticateAdmin();
        registerNode(NODE_IDS[0], "OK");

        R result = forwardService.updateForward(updateDto());

        assertEquals(-1, result.getCode());
        assertEquals("节点不在线", result.getMsg());
        List<String> payloads = sentPayloads(NODE_IDS[0]);
        assertEquals(2, payloads.size(), "先下发了新配置，失败后必须再发一次原配置");
        assertEquals(NEW_ADDR, forwardedAddr(payloads.get(0)), "第一条是本次的新配置");
        assertEquals(OLD_ADDR, forwardedAddr(payloads.get(1)), "失败后必须把已下发节点改回原配置");
        verify(forwardMapper, never()).updateById(any(Forward.class));
        verify(forwardPortService, never()).updateById(any(ForwardPort.class));
    }

    @Test
    void missingPortRecordFailsBeforeAnyDispatch() throws Exception {
        authenticateAdmin();
        registerNode(NODE_IDS[0], "OK");
        registerNode(NODE_IDS[1], "OK");
        when(forwardPortService.getOne(any())).thenReturn(null);

        R result = forwardService.updateForward(updateDto());

        assertEquals(-1, result.getCode());
        assertEquals("部分节点不存在1", result.getMsg());
        assertEquals(List.of(), sentPayloads(NODE_IDS[0]), "校验没通过前不许下发");
        assertEquals(List.of(), sentPayloads(NODE_IDS[1]), "校验没通过前不许下发");
        verify(forwardMapper, never()).updateById(any(Forward.class));
    }

    @Test
    void databaseWriteFailureRestoresNodesAndFails() throws Exception {
        authenticateAdmin();
        registerNode(NODE_IDS[0], "OK");
        registerNode(NODE_IDS[1], "OK");
        when(forwardMapper.updateById(any(Forward.class))).thenReturn(0);

        R result = forwardService.updateForward(updateDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("转发记录保存失败"), result.getMsg());
        assertTrue(result.getMsg().contains("已把节点改回原配置"), result.getMsg());
        assertEquals(OLD_ADDR, forwardedAddr(sentPayloads(NODE_IDS[0]).get(1)), "写库失败后节点要退回原配置");
        verify(forwardPortService, never()).updateById(any(ForwardPort.class));
    }

    @Test
    void portWriteFailureRevertsForwardRowAndNodes() throws Exception {
        authenticateAdmin();
        registerNode(NODE_IDS[0], "OK");
        registerNode(NODE_IDS[1], "OK");
        when(forwardPortService.updateById(any(ForwardPort.class))).thenReturn(false);

        R result = forwardService.updateForward(updateDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("端口记录保存失败"), result.getMsg());
        assertTrue(result.getMsg().contains("转发记录和节点已退回原配置"), result.getMsg());

        ArgumentCaptor<Forward> captor = ArgumentCaptor.forClass(Forward.class);
        verify(forwardMapper, times(2)).updateById(captor.capture());
        assertEquals(NEW_ADDR, captor.getAllValues().get(0).getRemoteAddr(), "先写的是新配置");
        assertEquals(OLD_ADDR, captor.getAllValues().get(1).getRemoteAddr(), "端口没写成要把转发记录退回原值");
        assertEquals(OLD_NAME, captor.getAllValues().get(1).getName());
        assertEquals(OLD_STRATEGY, captor.getAllValues().get(1).getStrategy());
        assertEquals(OLD_ADDR, forwardedAddr(sentPayloads(NODE_IDS[0]).get(1)), "节点也要退回原配置");
    }

    // ========== 正向用例：全部节点接受后才落库 ==========

    @Test
    void databaseIsWrittenOnlyAfterEveryNodeAccepts() throws Exception {
        authenticateAdmin();
        registerNode(NODE_IDS[0], "OK");
        registerNode(NODE_IDS[1], "OK");

        R result = forwardService.updateForward(updateDto());

        assertEquals(0, result.getCode(), result.getMsg());

        ArgumentCaptor<Forward> forwardCaptor = ArgumentCaptor.forClass(Forward.class);
        verify(forwardMapper).updateById(forwardCaptor.capture());
        Forward written = forwardCaptor.getValue();
        assertEquals(FORWARD_ID, written.getId());
        assertEquals(NEW_ADDR, written.getRemoteAddr());
        assertEquals(NEW_NAME, written.getName());
        assertEquals(NEW_STRATEGY, written.getStrategy());
        assertEquals(1, written.getStatus());

        ArgumentCaptor<ForwardPort> portCaptor = ArgumentCaptor.forClass(ForwardPort.class);
        verify(forwardPortService, times(NODE_IDS.length)).updateById(portCaptor.capture());
        for (ForwardPort port : portCaptor.getAllValues()) {
            assertEquals(AVAILABLE_PORT, port.getPort(), "端口重算结果要一起落库");
        }

        List<String> payloads = sentPayloads(NODE_IDS[0]);
        assertEquals(1, payloads.size());
        assertEquals(NEW_ADDR, forwardedAddr(payloads.get(0)), "节点收到的必须是新地址");
        assertEquals(List.of("UpdateService"), sentMessageTypes(NODE_IDS[0]));
    }

    // ========== 测试脚手架 ==========

    private void authenticateAdmin() {
        authenticate(0, "admin");
    }

    private void authenticate(int roleId, String name) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":" + roleId + ",\"name\":\"" + name + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ForwardUpdateDto updateDto() {
        ForwardUpdateDto dto = new ForwardUpdateDto();
        dto.setId(FORWARD_ID);
        dto.setUserId(USER_ID);
        dto.setName(NEW_NAME);
        dto.setRemoteAddr(NEW_ADDR);
        dto.setStrategy(NEW_STRATEGY);
        dto.setInPort(null);
        return dto;
    }

    private Forward storedForward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(USER_ID);
        forward.setUserName("user");
        forward.setName(OLD_NAME);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr(OLD_ADDR);
        forward.setStrategy(OLD_STRATEGY);
        forward.setStatus(1);
        forward.setCreatedTime(1000L);
        forward.setUpdatedTime(2000L);
        return forward;
    }

    private ForwardPort storedPort() {
        ForwardPort port = new ForwardPort();
        port.setId(FORWARD_PORT_ID);
        port.setForwardId(FORWARD_ID);
        port.setNodeId(NODE_IDS[0]);
        port.setPort(OLD_PORT);
        return port;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private UserTunnel userTunnel() {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(7);
        userTunnel.setUserId(USER_ID);
        userTunnel.setTunnelId(TUNNEL_ID);
        userTunnel.setStatus(1);
        userTunnel.setNum(10);
        userTunnel.setFlow(100L);
        return userTunnel;
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
        node.setPort(AVAILABLE_PORT + "-" + (AVAILABLE_PORT + 10));
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private void registerNode(long nodeId, String updateResult) throws Exception {
        updateServiceResults.put(nodeId, updateResult);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto gostDto = new GostDto();
                gostDto.setMsg(updateServiceResults.getOrDefault(nodeId, "OK"));
                future.complete(gostDto);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private String forwardedAddr(String payload) {
        JSONArray services = JSON.parseObject(payload).getJSONArray("data");
        return services.getJSONObject(0).getJSONObject("forwarder").getJSONArray("nodes")
                .getJSONObject(0).getString("addr");
    }

    private List<String> sentMessageTypes(long nodeId) throws Exception {
        List<String> types = new ArrayList<>();
        for (String payload : sentPayloads(nodeId)) {
            types.add(JSON.parseObject(payload).getString("type"));
        }
        return types;
    }

    private List<String> sentPayloads(long nodeId) throws Exception {
        WebSocketSession session = nodeSessions.get(nodeId);
        if (session == null) {
            return List.of();
        }
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(0)).sendMessage(captor.capture());
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
