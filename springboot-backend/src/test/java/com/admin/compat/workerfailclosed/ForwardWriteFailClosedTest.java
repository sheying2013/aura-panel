package com.admin.compat.workerfailclosed;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.ddns.DdnsDomainGate;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
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
import org.junit.jupiter.api.Timeout;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写路径的账号存在性必须 fail-closed：JWT 里的 userId 只是快照，账号被删除后
 * validateForwardExists 不能把“查不到用户”当成普通用户，也不能让 JWT 与转发的
 * userId 同时为 null 时靠 Objects.equals 放行。
 *
 * <p>同一组异常在暂停/恢复上还有第二个入口：changeForwardStatus 原来按 JWT 的
 * role_id 选分支，令牌仍写着管理员、库内已降权时会跳过流量与隧道权限校验。
 */
class ForwardWriteFailClosedTest {

    private static final int JWT_USER_ID = 7;
    private static final long TUNNEL_ID = 3L;
    private static final long NODE_ID = 101L;
    private static final long FORWARD_ID = 500L;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private UserService userService;
    private UserTunnelService userTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    private final Map<Integer, User> users = new ConcurrentHashMap<>();
    private final List<String> sentPayloads = new CopyOnWriteArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        sentPayloads.clear();
        users.clear();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        userService = mock(UserService.class);
        userTunnelService = mock(UserTunnelService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);

        when(forwardMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<Forward>());
        when(forwardMapper.selectCount(any())).thenReturn(0);
        when(forwardMapper.updateById(any(Forward.class))).thenReturn(1);
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> entryChainTunnels());
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.getOne(any())).thenAnswer(invocation -> forwardPort());
        when(forwardPortService.remove(any())).thenReturn(true);
        when(nodeService.getById(any())).thenAnswer(invocation -> node());
        // 账号被删除后 getById 返回 null；这是本组用例的核心输入，不做任何兜底。
        when(userService.getById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return id == null ? null : users.get(((Number) id).intValue());
        });
        when(userTunnelService.getOne(any())).thenAnswer(invocation -> null);
        registerNode();
    }

    @AfterEach
    void tearDown() {
        DdnsTargetRenderer.reset();
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
        sentPayloads.clear();
        users.clear();
    }

    // ========== forceDelete：账号缺失但转发属主就是 JWT 里的 userId ==========

    @Test
    @Timeout(20)
    void forceDeleteRejectsMissingAccountEvenWhenForwardBelongsToSameUserId() {
        givenForwardOwnedBy(JWT_USER_ID);
        authenticate(JWT_USER_ID, 1);

        R result = forwardService.forceDeleteForward(FORWARD_ID);

        assertEquals(-1, result.getCode(),
                "账号已不存在时不得把同 ID 当成属主放行，实际：" + result.getMsg());
        assertEquals("端口转发不存在", result.getMsg());
        verify(forwardMapper, never()).deleteById(any());
        verify(forwardPortService, never()).remove(any());
        assertTrue(sentPayloads.isEmpty(), "拒绝路径不得向节点下发内容，实际：" + sentPayloads);
    }

    @Test
    @Timeout(20)
    void forceDeleteRejectsMissingAccountEvenWhenJwtClaimsAdmin() {
        givenForwardOwnedBy(JWT_USER_ID);
        authenticate(JWT_USER_ID, 0);

        R result = forwardService.forceDeleteForward(FORWARD_ID);

        assertEquals(-1, result.getCode(),
                "库内查不到账号时，JWT 管理员声明不能当作管理员放行，实际：" + result.getMsg());
        assertEquals("端口转发不存在", result.getMsg());
        verify(forwardMapper, never()).deleteById(any());
        verify(forwardPortService, never()).remove(any());
    }

    // ========== forceDelete：JWT 与转发 userId 同时为 null ==========

    @Test
    @Timeout(20)
    void forceDeleteRejectsWhenJwtUserIdAndForwardUserIdAreBothNull() {
        givenForwardOwnedBy(null);
        // payload 里没有 sub，getUserIdFromToken 返回 null；转发 user_id 也为 null，
        // 旧实现 Objects.equals(null, null) 为 true，直接当成属主放行。
        authenticatePayload("{\"role_id\":1,\"name\":\"ghost\"}");

        R result = forwardService.forceDeleteForward(FORWARD_ID);

        assertEquals(-1, result.getCode(), "双 null 不得通过 Objects.equals 当成属主，实际：" + result.getMsg());
        assertEquals("端口转发不存在", result.getMsg());
        verify(forwardMapper, never()).deleteById(any());
        verify(forwardPortService, never()).remove(any());
        assertTrue(sentPayloads.isEmpty(), "拒绝路径不得向节点下发内容，实际：" + sentPayloads);
    }

    // ========== pause：账号缺失时不得继续下发暂停 ==========

    @Test
    @Timeout(20)
    void pauseForwardRejectsMissingAccount() {
        givenForwardOwnedBy(JWT_USER_ID);
        authenticate(JWT_USER_ID, 1);

        R result = forwardService.pauseForward(FORWARD_ID);

        assertEquals(-1, result.getCode(), "账号已不存在时不得暂停转发，实际：" + result.getMsg());
        assertEquals("用户不存在", result.getMsg());
        verify(forwardMapper, never()).updateById(any(Forward.class));
        assertTrue(sentPayloads.isEmpty(), "拒绝路径不得向节点下发内容，实际：" + sentPayloads);
    }

    // ========== resume：库内已降权但 JWT 仍写管理员 ==========

    @Test
    @Timeout(20)
    void resumeForwardUsesDatabaseRoleNotJwtAdminClaim() {
        users.put(JWT_USER_ID, user(JWT_USER_ID, 1));
        givenForwardOwnedBy(JWT_USER_ID);
        authenticate(JWT_USER_ID, 0);

        R result = forwardService.resumeForward(FORWARD_ID);

        assertEquals(-1, result.getCode(),
                "JWT 声称管理员、库内是普通用户时，不得跳过隧道权限校验，实际：" + result.getMsg());
        assertEquals("你没有该隧道权限", result.getMsg());
        verify(forwardMapper, never()).updateById(any(Forward.class));
        assertTrue(sentPayloads.isEmpty(), "拒绝路径不得向节点下发内容，实际：" + sentPayloads);
    }

    // ========== 脚手架 ==========

    private void givenForwardOwnedBy(Integer userId) {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(userId);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setName("owner-forward");
        forward.setRemoteAddr("10.9.9.9:9000");
        forward.setStrategy("round");
        forward.setStatus(1);
        when(forwardMapper.selectById(FORWARD_ID)).thenReturn(forward);
    }

    private void authenticate(int userId, int jwtRoleId) {
        authenticatePayload("{\"sub\":" + userId + ",\"role_id\":" + jwtRoleId
                + ",\"name\":\"fail-closed-user\"}");
    }

    private void authenticatePayload(String json) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private List<ChainTunnel> entryChainTunnels() {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId(TUNNEL_ID);
        chainTunnel.setChainType(1);
        chainTunnel.setNodeId(NODE_ID);
        chainTunnel.setPort(null);
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel);
        return chainTunnels;
    }

    private Node node() {
        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("node-" + NODE_ID);
        node.setServerIp("10.0.0.1");
        node.setPort("20005-20015");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setStatus(1);
        return node;
    }

    private User user(int id, int roleId) {
        User user = new User();
        user.setId((long) id);
        user.setUser("user-" + id);
        user.setNum(10);
        user.setFlow(1000L);
        user.setInFlow(0L);
        user.setOutFlow(0L);
        user.setExpTime(null);
        user.setRoleId(roleId);
        user.setStatus(1);
        return user;
    }

    private ForwardPort forwardPort() {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(FORWARD_ID);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(20005);
        return forwardPort;
    }

    private void registerNode() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + NODE_ID);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            sentPayloads.add(payload);
            JSONObject message = JSON.parseObject(payload);
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

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        assertTrue(value != null, "WebSocketServer." + name + " 必须存在");
        return value;
    }
}
