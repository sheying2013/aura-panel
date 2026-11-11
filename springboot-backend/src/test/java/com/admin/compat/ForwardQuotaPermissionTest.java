package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
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
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 满配额用户必须还能编辑 / 删除自己那条转发。
 *
 * <p>真机缺陷（基线 761db18）：num=1 的用户已经有 1 条转发时，update 和 delete 都返回
 * 「用户总转发数量已达上限，当前限制：1个」，只能管理员代删。根因是 count 查询没排除自身。
 *
 * <p>这里用"假数据库行数 - 自身"来模拟真实 count：wrapper 里出现 id &lt;&gt; 才减掉自身那一行，
 * 因此如果实现回退回"不排除自身"，用例会立刻变红。
 */
class ForwardQuotaPermissionTest {

    private static final long FORWARD_ID = 1L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    private static final long FORWARD_PORT_ID = 11L;
    private static final long NODE_ID = 101L;
    private static final int OLD_PORT = 20005;
    private static final int AVAILABLE_PORT = 20000;
    private static final String OLD_ADDR = "10.1.1.1:8080";
    private static final String NEW_ADDR = "10.2.2.2:9090";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserService userService;
    private UserTunnelService userTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 假数据库里的行数（含当前转发自身那一行）。 */
    private int userForwardRows;
    private int tunnelForwardRows;
    /** 被 stub 到的 count 查询 SQL，用来断言"是否排除了自身"。 */
    private final List<String> countSqlSegments = new ArrayList<>();
    /** userService.getById 返回的用户，null 表示按默认值构造。 */
    private User currentUserEntity;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        countSqlSegments.clear();
        userForwardRows = 1;
        tunnelForwardRows = 1;
        currentUserEntity = null;

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        userService = mock(UserService.class);
        userTunnelService = mock(UserTunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);

        when(forwardMapper.selectById(any())).thenReturn(storedForward());
        when(forwardMapper.insert(any(Forward.class))).thenReturn(1);
        when(forwardMapper.updateById(any(Forward.class))).thenReturn(1);
        when(forwardMapper.deleteById(any())).thenAnswer(invocation -> 1);
        when(forwardMapper.selectCount(any())).thenAnswer(this::fakeCount);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainTunnels());
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.getOne(any())).thenAnswer(invocation -> storedPort());
        when(forwardPortService.updateById(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(userService.getById(any())).thenAnswer(invocation -> currentUserEntity != null ? currentUserEntity : user());
        when(userTunnelService.getOne(any())).thenAnswer(invocation -> userTunnel());
        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ========== 正向：满配额用户能改 / 能删自己的转发 ==========

    @Test
    void quotaFullUserCanEditOwnForward() {
        authenticate(1, "user");
        currentUserEntity = user(1);

        R result = forwardService.updateForward(updateDto());

        assertEquals(0, result.getCode(), "num=1 的用户必须能编辑自己唯一那条转发：" + result.getMsg());
        assertEquals(2, countSqlSegments.size(), "用户级与隧道级各执行一次 count");
        assertTrue(countSqlSegments.get(0).contains("user_id"), countSqlSegments.get(0));
        assertTrue(countSqlSegments.get(0).contains("<>"), "用户总配额查询必须排除当前转发自身：" + countSqlSegments.get(0));
        assertTrue(countSqlSegments.get(1).contains("tunnel_id"), countSqlSegments.get(1));
        assertTrue(countSqlSegments.get(1).contains("<>"), "隧道配额查询也要排除自身：" + countSqlSegments.get(1));
        verify(forwardMapper).updateById(any(Forward.class));
    }

    @Test
    void quotaFullUserCanDeleteOwnForwardWithoutQuotaCheck() {
        authenticate(1, "user");
        currentUserEntity = user(1);

        R result = forwardService.deleteForward(FORWARD_ID);

        assertEquals(0, result.getCode(), "删除自己的转发不该被 num 配额拦住：" + result.getMsg());
        assertEquals(List.of(), countSqlSegments, "delete 路径根本不该做数量配额查询");
        verify(forwardMapper).deleteById(FORWARD_ID);
    }

    // ========== 反向：配额语义不能被放松 ==========

    @Test
    void createIsStillBlockedWhenQuotaFull() {
        authenticate(1, "user");
        currentUserEntity = user(1);

        R result = forwardService.createForward(createDto());

        assertEquals(-1, result.getCode(), "create 必须保持原有配额拦截");
        assertEquals("用户总转发数量已达上限，当前限制：1个", result.getMsg());
        assertFalse(countSqlSegments.get(0).contains("<>"), "create 的配额查询不排除任何行：" + countSqlSegments.get(0));
        verify(forwardMapper, never()).insert(any(Forward.class));
    }

    @Test
    void updateIsStillBlockedWhenOtherForwardsFillQuota() {
        authenticate(1, "user");
        currentUserEntity = user(1);
        // 用户 num=1，但库里还有 2 条别的转发（排除自身后仍超限）
        userForwardRows = 3;

        R result = forwardService.updateForward(updateDto());

        assertEquals(-1, result.getCode());
        assertEquals("用户总转发数量已达上限，当前限制：1个", result.getMsg());
        verify(forwardMapper, never()).updateById(any(Forward.class));
    }

    @Test
    void tunnelQuotaStillBlocksUpdateWhenOthersFillIt() {
        authenticate(1, "user");
        currentUserEntity = user(10);
        tunnelForwardRows = 12;

        R result = forwardService.updateForward(updateDto());

        assertEquals(-1, result.getCode());
        assertEquals("该隧道转发数量已达上限，当前限制：10个", result.getMsg(), result.getMsg());
        verify(forwardMapper, never()).updateById(any(Forward.class));
    }

    // ========== 管理员路径 ==========

    @Test
    void adminSkipsQuotaButStillReReadsAccountRow() {
        authenticate(0, "admin");
        User admin = user();
        admin.setRoleId(0);
        currentUserEntity = admin;

        R result = forwardService.updateForward(updateDto());

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(List.of(), countSqlSegments, "管理员不做配额查询");
        verify(userService, atLeastOnce()).getById(any());
        verify(forwardMapper).updateById(any(Forward.class));
    }

    @Test
    void deleteStillHonoursAccountExpiry() {
        authenticate(1, "user");
        User expired = user(10);
        expired.setExpTime(System.currentTimeMillis() - 60_000L);
        currentUserEntity = expired;

        R result = forwardService.deleteForward(FORWARD_ID);

        assertEquals(-1, result.getCode());
        assertEquals("当前账号已到期", result.getMsg(), "删除只跳过数量配额，到期/流量限制仍要拦");
        verify(forwardMapper, never()).deleteById(any());
        assertEquals(List.of(), countSqlSegments);
    }

    // ========== 测试脚手架 ==========

    private Object fakeCount(org.mockito.invocation.InvocationOnMock invocation) {
        Wrapper<Forward> wrapper = invocation.getArgument(0);
        String sql = wrapper.getSqlSegment();
        countSqlSegments.add(sql);
        boolean excludesSelf = sql.contains("<>");
        if (sql.contains("tunnel_id")) {
            return Math.max(0, tunnelForwardRows - (excludesSelf ? 1 : 0));
        }
        return Math.max(0, userForwardRows - (excludesSelf ? 1 : 0));
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
        dto.setName("new-name");
        dto.setRemoteAddr(NEW_ADDR);
        dto.setStrategy("round");
        return dto;
    }

    private ForwardDto createDto() {
        ForwardDto dto = new ForwardDto();
        dto.setName("new-forward");
        dto.setTunnelId(TUNNEL_ID);
        dto.setRemoteAddr(NEW_ADDR);
        dto.setStrategy("round");
        return dto;
    }

    private Forward storedForward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(USER_ID);
        forward.setUserName("user");
        forward.setName("old-name");
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr(OLD_ADDR);
        forward.setStrategy("fifo");
        forward.setStatus(1);
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

    private User user() {
        return user(1);
    }

    private User user(int num) {
        User user = new User();
        user.setId((long) USER_ID);
        user.setUser("user");
        user.setNum(num);
        user.setFlow(1000L);
        user.setExpTime(null);
        return user;
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

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.1");
        node.setPort(AVAILABLE_PORT + "-" + (AVAILABLE_PORT + 10));
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

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
                GostDto gostDto = new GostDto();
                gostDto.setMsg("OK");
                future.complete(gostDto);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
