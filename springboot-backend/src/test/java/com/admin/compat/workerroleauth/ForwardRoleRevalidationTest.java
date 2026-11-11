package com.admin.compat.workerroleauth;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.ResourceLifecycleLocks;
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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * update / delete / forceDelete 以及 createForward 锁内的角色复核：授权必须取数据库当下角色，不能沿用 JWT 快照。
 *
 * <p>漏洞窗口：{@code validateForwardExists} 在 DDNS 下发凭证之外执行，拿到凭证后还要经过
 * {@code checkUserPermissions} 的一系列库查询。如果这期间账号被降权，而 update/delete 仍用
 * JWT 里的 {@code role_id == 0} 选分支，就会按管理员身份改/删别人的转发。
 *
 * <p>这里的交错是确定性的：{@link DdnsTargetRenderer#installDispatch} 换成一个会阻塞的探针，
 * 请求线程先完成 {@code validateForwardExists}，再停在下发窗口入口；测试线程此时修改假 user 表的
 * 角色，然后放行。没有任何 sleep 竞速。
 */
class ForwardRoleRevalidationTest {

    private static final int JWT_USER_ID = 7;
    private static final int OWNER_USER_ID = 8;
    private static final String JWT_USER_NAME = "role-revalidation-user";
    private static final long TUNNEL_ID = 3L;
    private static final long NODE_ID = 101L;
    private static final long FORWARD_ID = 500L;
    private static final int IN_PORT = 20005;
    private static final String OLD_REMOTE_ADDR = "10.9.9.9:9000";
    private static final String NEW_REMOTE_ADDR = "10.10.10.10:8080";

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
        users.put(JWT_USER_ID, user(JWT_USER_ID, 0));
        users.put(OWNER_USER_ID, user(OWNER_USER_ID, 1));

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

        when(forwardMapper.selectById(FORWARD_ID)).thenReturn(forward());
        when(forwardMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<Forward>());
        when(forwardMapper.selectCount(any())).thenReturn(0);
        when(forwardMapper.updateById(any(Forward.class))).thenReturn(1);
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> entryChainTunnels());
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.getOne(any())).thenAnswer(invocation -> forwardPort());
        when(forwardPortService.updateById(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        when(nodeService.getById(any())).thenAnswer(invocation -> node());
        when(userService.getById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return id == null ? null : users.get(((Number) id).intValue());
        });
        when(userTunnelService.getOne(any())).thenAnswer(invocation -> {
            QueryWrapper<UserTunnel> wrapper = invocation.getArgument(0);
            // 先让 MyBatis-Plus 生成 SQL 片段，参数才会填进 paramNameValuePairs
            wrapper.getSqlSegment();
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            if (params.containsValue(OWNER_USER_ID)) {
                return userTunnel(22, OWNER_USER_ID);
            }
            if (params.containsValue(JWT_USER_ID)) {
                return userTunnel(11, JWT_USER_ID);
            }
            return null;
        });
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

    // ========== 红：等 DDNS 窗口期间被降权，仍按 JWT 管理员改别人的转发 ==========

    @Test
    @Timeout(20)
    void demotedAdminUpdateOthersForwardMustBeRejectedAtDispatchWindow() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        installBlockingDispatch(entered, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<R> update = pool.submit(() -> {
                authenticate(0);
                return forwardService.updateForward(updateDto());
            });

            assertTrue(entered.await(5, TimeUnit.SECONDS),
                    "updateForward 必须停在 DDNS 下发窗口，才能制造降权交错");
            users.put(JWT_USER_ID, user(JWT_USER_ID, 1));
            release.countDown();

            R result = update.get(10, TimeUnit.SECONDS);
            assertEquals(-1, result.getCode(),
                    "JWT 仍是管理员但库内已降权时，不得更新别人的转发，实际返回成功");
            assertEquals("转发不存在", result.getMsg());
            verify(forwardMapper, never()).updateById(any(Forward.class));
            verify(forwardPortService, never()).updateById(any(ForwardPort.class));
            assertTrue(sentPayloads.isEmpty(), "拒绝路径不得向节点下发任何内容，实际：" + sentPayloads);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void demotedAdminDeleteOthersForwardMustBeRejectedAtDispatchWindow() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        installBlockingDispatch(entered, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<R> delete = pool.submit(() -> {
                authenticate(0);
                return forwardService.deleteForward(FORWARD_ID);
            });

            assertTrue(entered.await(5, TimeUnit.SECONDS),
                    "deleteForward 必须停在 DDNS 下发窗口，才能制造降权交错");
            users.put(JWT_USER_ID, user(JWT_USER_ID, 1));
            release.countDown();

            R result = delete.get(10, TimeUnit.SECONDS);
            assertEquals(-1, result.getCode(),
                    "JWT 仍是管理员但库内已降权时，不得删除别人的转发，实际返回成功");
            assertEquals("转发不存在", result.getMsg());
            verify(forwardMapper, never()).deleteById(any());
            verify(forwardPortService, never()).remove(any());
            assertTrue(sentPayloads.isEmpty(), "拒绝路径不得向节点下发任何内容，实际：" + sentPayloads);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ========== 红：JWT 管理员 + 库内已降权，无窗口也应拒绝 ==========

    @Test
    @Timeout(20)
    void staleAdminTokenCannotUpdateOthersForwardWhenDbAlreadyDemoted() {
        users.put(JWT_USER_ID, user(JWT_USER_ID, 1));
        authenticate(0);

        R result = forwardService.updateForward(updateDto());

        assertEquals(-1, result.getCode(), "库内角色已是普通用户，JWT 管理员不得改别人的转发");
        assertEquals("转发不存在", result.getMsg());
        verify(forwardMapper, never()).updateById(any(Forward.class));
        verify(forwardPortService, never()).updateById(any(ForwardPort.class));
    }

    // ========== 绿：库内升权后，管理员路径仍按属主命名，服务名不变 ==========

    @Test
    @Timeout(20)
    void promotedAdminUpdatesOthersForwardAndKeepsOwnerServiceName() {
        users.put(JWT_USER_ID, user(JWT_USER_ID, 0));
        authenticate(1);

        R result = forwardService.updateForward(updateDto());

        assertEquals(0, result.getCode(), "库内已是管理员，应允许更新他人转发，实际：" + result.getMsg());
        verify(forwardMapper).updateById(any(Forward.class));
        verify(forwardPortService).updateById(any(ForwardPort.class));
        assertTrue(payloadContains("500_8_22"),
                "管理员代改时服务名必须继续用属主 userTunnel(22)，实际下发：" + sentPayloads);
    }

    @Test
    @Timeout(20)
    void promotedAdminDeletesOthersForwardAndCascadesPortRow() {
        users.put(JWT_USER_ID, user(JWT_USER_ID, 0));
        authenticate(1);

        R result = forwardService.deleteForward(FORWARD_ID);

        assertEquals(0, result.getCode(), "库内已是管理员，应允许删除他人转发，实际：" + result.getMsg());
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
        assertTrue(payloadContains("500_8_22_tcp") && payloadContains("500_8_22_udp"),
                "删除下发必须同时包含属主命名 tcp/udp 服务，实际下发：" + sentPayloads);
    }

    // ========== 红：库查询失败不能被当成管理员而删库成功 ==========

    @Test
    @Timeout(20)
    void dbLookupFailureBlocksForceDeleteInsteadOfDeleting() {
        authenticate(0);
        when(userService.getById(any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class,
                () -> forwardService.forceDeleteForward(FORWARD_ID),
                "角色校验读库失败必须向上抛，不能继续删库");
        verify(forwardMapper, never()).deleteById(any());
        verify(forwardPortService, never()).remove(any());
    }

    @Test
    @Timeout(20)
    void demotedAdminForceDeleteOthersForwardMustBeRejectedAtDispatchWindow() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        installBlockingDispatch(entered, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<R> forceDelete = pool.submit(() -> {
                authenticate(0);
                return forwardService.forceDeleteForward(FORWARD_ID);
            });

            assertTrue(entered.await(5, TimeUnit.SECONDS),
                    "forceDeleteForward 必须停在 DDNS 下发窗口，才能制造降权交错");
            users.put(JWT_USER_ID, user(JWT_USER_ID, 1));
            release.countDown();

            R result = forceDelete.get(10, TimeUnit.SECONDS);
            assertEquals(-1, result.getCode(),
                    "JWT 仍是管理员但库内已降权时，不得强制删除别人的转发，实际返回成功");
            assertEquals("端口转发不存在", result.getMsg());
            verify(forwardMapper, never()).deleteById(any());
            verify(forwardPortService, never()).remove(any());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ========== 防护：等生命周期锁期间被降权，createForwardLocked 不得沿用 JWT 管理员 ==========

    @Test
    @Timeout(20)
    void demotedAdminCreateDuringLifecycleWaitMustNotUseStaleAdminRole() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, ReentrantLock> locks = (Map<String, ReentrantLock>) lifecycleLocks();
        SignalingLock tunnelLock = new SignalingLock();
        locks.put(ResourceLifecycleLocks.tunnelKey(TUNNEL_ID), tunnelLock);
        ResourceLifecycleLocks.Lease held = ResourceLifecycleLocks.acquire(1_000,
                ResourceLifecycleLocks.tunnelKey(TUNNEL_ID),
                ResourceLifecycleLocks.userKey(JWT_USER_ID));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 降权后用户 7 对该隧道没有授权：一旦仍按 JWT 管理员短路就会建成功
            doReturn(null).when(userTunnelService).getOne(any());

            Future<R> create = pool.submit(() -> {
                authenticate(0);
                return forwardService.createForward(createDto());
            });

            assertTrue(tunnelLock.waiting.await(5, TimeUnit.SECONDS),
                    "createForward 必须停在生命周期锁上，才能制造降权交错");
            users.put(JWT_USER_ID, user(JWT_USER_ID, 1));
            held.close();

            R result = create.get(10, TimeUnit.SECONDS);
            assertEquals(-1, result.getCode(),
                    "JWT 仍是管理员但库内已降权时，不得绕过隧道授权创建转发，实际返回成功");
            assertEquals("你没有该隧道权限", result.getMsg());
            verify(forwardMapper, never()).insert(any(Forward.class));
            assertTrue(sentPayloads.isEmpty(), "拒绝路径不得向节点下发任何内容，实际：" + sentPayloads);
        } finally {
            held.close();
            pool.shutdownNow();
            locks.remove(ResourceLifecycleLocks.tunnelKey(TUNNEL_ID));
        }
    }

    // ========== 脚手架 ==========

    private void installBlockingDispatch(CountDownLatch entered, CountDownLatch release) {
        DdnsTargetRenderer.installDispatch(remoteAddr -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DdnsDomainGate.Lease.NONE;
        });
    }

    private boolean payloadContains(String token) {
        return sentPayloads.stream().anyMatch(payload -> payload.contains(token));
    }

    private void authenticate(int jwtRoleId) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + JWT_USER_ID + ",\"role_id\":" + jwtRoleId
                        + ",\"name\":\"" + JWT_USER_NAME + "\"}").getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ForwardDto createDto() {
        ForwardDto dto = new ForwardDto();
        dto.setName("new-forward");
        dto.setTunnelId((int) TUNNEL_ID);
        dto.setRemoteAddr(NEW_REMOTE_ADDR);
        dto.setStrategy("round");
        dto.setInPort(IN_PORT);
        return dto;
    }

    private ForwardUpdateDto updateDto() {
        ForwardUpdateDto dto = new ForwardUpdateDto();
        dto.setId(FORWARD_ID);
        dto.setUserId(OWNER_USER_ID);
        dto.setName("owner-forward");
        dto.setRemoteAddr(NEW_REMOTE_ADDR);
        dto.setStrategy("round");
        dto.setInPort(IN_PORT);
        return dto;
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(OWNER_USER_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setName("owner-forward");
        forward.setRemoteAddr(OLD_REMOTE_ADDR);
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
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
        // 入口节点自身未绑定端口，指定 inPort 才会被 get_port 判为可用
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
        node.setPort(IN_PORT + "-" + (IN_PORT + 10));
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

    private UserTunnel userTunnel(int id, int userId) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(id);
        userTunnel.setUserId(userId);
        userTunnel.setTunnelId((int) TUNNEL_ID);
        userTunnel.setStatus(1);
        userTunnel.setNum(10);
        userTunnel.setFlow(1000L);
        userTunnel.setInFlow(0L);
        userTunnel.setOutFlow(0L);
        userTunnel.setExpTime(null);
        return userTunnel;
    }

    private ForwardPort forwardPort() {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(FORWARD_ID);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(IN_PORT);
        return forwardPort;
    }

    /** 假节点会话：收到下发立刻完成 pendingRequest（返回 OK），并记录 payload 供断言。 */
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

    private static Object lifecycleLocks() throws Exception {
        Field field = ResourceLifecycleLocks.class.getDeclaredField("LOCKS");
        field.setAccessible(true);
        return field.get(null);
    }

    /** 只在下发线程被别的线程挡住时亮灯；测试线程自己取锁不会触发。 */
    private static final class SignalingLock extends ReentrantLock {
        private final CountDownLatch waiting = new CountDownLatch(1);

        @Override
        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            if (isLocked() && !isHeldByCurrentThread()) {
                waiting.countDown();
            }
            return super.tryLock(timeout, unit);
        }
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        assertTrue(value != null, "WebSocketServer." + name + " 必须存在");
        return value;
    }
}
