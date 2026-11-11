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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编辑转发不得改动运行态（基线 761db18 缺陷：updateForward 里硬编码 setStatus(1)）。
 *
 * <p>暂停（status=0）的转发只在面板库里标了 0，节点侧对应服务已经是 paused 状态；
 * 用户改目标地址时若把库里的 status 写成 1，面板显示"运行中"、节点侧却仍然 paused ——
 * 面板与节点永久漂移，配额也按运行态误判。这里锁死：编辑保留原状态，且不下发 ResumeService。
 */
class ForwardPauseStateTest {

    private static final long FORWARD_ID = 1L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    private static final long FORWARD_PORT_ID = 11L;
    private static final int OLD_PORT = 20005;
    private static final int AVAILABLE_PORT = 20000;
    private static final long NODE_ID = 101L;
    private static final String OLD_ADDR = "10.1.1.1:8080";
    private static final String NEW_ADDR = "10.2.2.2:9090";
    private static final String OLD_NAME = "old-name";
    private static final String NEW_NAME = "new-name";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private int storedStatus;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        NodeService nodeService = mock(NodeService.class);
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

        when(forwardMapper.selectById(any())).thenAnswer(invocation -> storedForward());
        when(forwardMapper.updateById(any(Forward.class))).thenAnswer(invocation -> {
            // 模拟真实 updateById：把落库对象写回存储，后面读到的就是库里的实际状态
            storedStatus = ((Forward) invocation.getArgument(0)).getStatus();
            return 1;
        });
        when(forwardMapper.selectCount(any())).thenReturn(0);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainTunnels());
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.getOne(any())).thenAnswer(invocation -> storedPort());
        when(forwardPortService.updateById(any(ForwardPort.class))).thenReturn(true);
        when(nodeService.getById(any())).thenReturn(node());
        when(userTunnelService.getOne(any())).thenReturn(userTunnel());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void pausedForwardStaysPausedAfterEdit() throws Exception {
        authenticateAdmin();
        storedStatus = 0;
        registerNode();

        R result = forwardService.updateForward(updateDto());

        assertEquals(0, result.getCode(), result.getMsg());
        ArgumentCaptor<Forward> captor = ArgumentCaptor.forClass(Forward.class);
        verify(forwardMapper).updateById(captor.capture());
        Forward written = captor.getValue();
        assertEquals(NEW_ADDR, written.getRemoteAddr(), "编辑内容要落库");
        assertEquals(0, written.getStatus(), "暂停中的转发改完目标地址仍然是暂停，不能被改回运行态");
        assertEquals(0, storedStatus, "库里读回来的也必须还是 0");
    }

    @Test
    void runningForwardStaysRunningAfterEdit() throws Exception {
        authenticateAdmin();
        storedStatus = 1;
        registerNode();

        R result = forwardService.updateForward(updateDto());

        assertEquals(0, result.getCode(), result.getMsg());
        ArgumentCaptor<Forward> captor = ArgumentCaptor.forClass(Forward.class);
        verify(forwardMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getStatus(), "运行态编辑后保持运行态（回归）");
    }

    @Test
    void pausedForwardEditDoesNotResumeServiceOnNode() throws Exception {
        authenticateAdmin();
        storedStatus = 0;
        registerNode();

        R result = forwardService.updateForward(updateDto());

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(List.of("UpdateService"), sentMessageTypes(),
                "编辑只改配置（UpdateService），不许顺手把暂停的服务恢复起来");
    }

    // ========== 测试脚手架 ==========

    private void authenticateAdmin() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
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
        dto.setStrategy("fifo");
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
        forward.setStrategy("fifo");
        forward.setStatus(storedStatus);
        forward.setCreatedTime(1000L);
        forward.setUpdatedTime(2000L);
        return forward;
    }

    private ForwardPort storedPort() {
        ForwardPort port = new ForwardPort();
        port.setId(FORWARD_PORT_ID);
        port.setForwardId(FORWARD_ID);
        port.setNodeId(NODE_ID);
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
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId((long) TUNNEL_ID);
        chainTunnel.setChainType(1);
        chainTunnel.setNodeId(NODE_ID);
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel);
        return chainTunnels;
    }

    private Node node() {
        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("node");
        node.setServerIp("10.0.0.1");
        node.setPort(AVAILABLE_PORT + "-" + (AVAILABLE_PORT + 10));
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private void registerNode() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + NODE_ID);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto gostDto = new GostDto();
                gostDto.setMsg("OK");
                future.complete(gostDto);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(NODE_ID, session);
    }

    private List<String> sentMessageTypes() throws Exception {
        WebSocketSession session = nodeSessions.get(NODE_ID);
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(0)).sendMessage(captor.capture());
        List<String> types = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            types.add(JSON.parseObject(message.getPayload()).getString("type"));
        }
        return types;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
