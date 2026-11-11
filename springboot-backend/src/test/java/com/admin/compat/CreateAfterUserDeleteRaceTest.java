package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.ResourceLifecycleLocks;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createForward 与 deleteUser 的锁交错审计：等锁期间属主行消失，持锁后还会不会重新确认属主？
 *
 * <p><b>被测顺序</b>（{@code ForwardServiceImpl#createForward}）：先在锁外 {@code getCurrentUserInfo()}
 * 从 JWT 取 userId/roleId，再 {@link ResourceLifecycleLocks#acquire} 取「属主用户键 + 隧道键」，
 * 进锁后才调 {@code checkUserPermissions}。删除侧（{@code UserServiceImpl#deleteUser}）只取属主用户键。
 * 因此只要创建侧等锁，删除侧就一定跑在「读 JWT」和「进锁」之间 —— 这正是必须持锁重查 user 行的窗口。
 *
 * <p><b>审计结论</b>：
 * <ul>
 *   <li>非管理员分支是安全的：{@code checkUserPermissions} 持锁后 {@code userService.getById(userId)}，
 *       行已消失就返回「用户不存在」，一条 forward 都不会落库（{@link #nonAdminCreateIsRejectedWhenOwnerRowVanishes}，绿）。</li>
 *   <li>管理员分支也必须持锁后重读用户行：修复前会根据 JWT 管理员角色提前返回，
 *       修复后属主行缺失应拒绝创建（{@link #adminCreateAfterOwnerRowVanishedMustBeRejected}）。</li>
 *   <li>{@link #adminPathReReadsOwnerRow} 确认正常管理员创建仍成功，且读取了用户行。</li>
 * </ul>
 *
 * <p><b>为什么是确定性交错而不是碰运气</b>：删除侧由测试线程显式持有 {@code user:<id>} 生命周期锁，
 * 并在此之前把用户行从假库里摘掉；创建线程必须阻塞在 {@code tryLock} 上，测试先断言"它确实还没完成、
 * 也还没落库"，再放锁。没有 sleep 竞速，没有真实库/真实节点/真实网络。
 */
class CreateAfterUserDeleteRaceTest {

    private static final int USER_ID = 7;
    private static final String USER_NAME = "race-user";
    private static final long TUNNEL_ID = 3L;
    private static final long NODE_ID = 101L;
    private static final long NEW_FORWARD_ID = 500L;
    private static final int IN_PORT = 20005;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private UserService userService;
    private UserTunnelService userTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 假 user 表：删除侧从这里把行摘掉，createForward 的重查就读到 null。 */
    private final Map<Integer, User> users = new ConcurrentHashMap<>();
    /** 假 forward 表：只记本次测试真正落库的行。 */
    private final List<Forward> inserted = new CopyOnWriteArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        users.clear();
        inserted.clear();
        users.put(USER_ID, user());

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

        when(forwardMapper.insert(any(Forward.class))).thenAnswer(invocation -> {
            Forward forward = invocation.getArgument(0);
            forward.setId(NEW_FORWARD_ID);
            inserted.add(forward);
            return 1;
        });
        when(forwardMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<Forward>());
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> entryChainTunnels());
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        when(nodeService.getById(any())).thenAnswer(invocation -> node());
        when(userService.getById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return id == null ? null : users.get(((Number) id).intValue());
        });
        when(userTunnelService.getOne(any())).thenAnswer(invocation -> userTunnel());
        registerNode();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
        users.clear();
        inserted.clear();
    }

    // ========== 对照：非管理员等锁期间被删，持锁后必须拒绝 ==========

    @Test
    @Timeout(20)
    void nonAdminCreateIsRejectedWhenOwnerRowVanishes() throws Exception {
        R result = interleavedCreate(1);

        assertEquals(-1, result.getCode(),
                "属主行在等锁期间被删除后，必须拒绝创建，实际：" + result.getMsg() + "；落库行：" + describeInserted());
        assertEquals("用户不存在", result.getMsg(), "拒绝理由应是持锁后重查发现属主行不存在");
        assertEquals(List.of(), inserted, "一条 forward 都不该落库，实际：" + describeInserted());
        verify(userService).getById(USER_ID);
    }

    // ========== 缺陷：管理员短路跳过了同一道重查（红） ==========

    @Test
    @Timeout(20)
    void adminCreateAfterOwnerRowVanishedMustBeRejected() throws Exception {
        R result = interleavedCreate(0);

        assertEquals(-1, result.getCode(),
                "管理员角色同样必须在持锁后确认属主行存在；实际返回成功并在库里留下无属主转发："
                        + describeInserted());
        assertEquals(List.of(), inserted, "属主行已不存在时不得落库，实际：" + describeInserted());
        verify(userService).getById(USER_ID);
    }

    // ========== 管理员路径也必须重查 user 表 ==========

    @Test
    @Timeout(20)
    void adminPathReReadsOwnerRow() {
        authenticate(0);

        R result = forwardService.createForward(newForwardDto());

        assertEquals(0, result.getCode(), "管理员正常创建应成功，实际：" + result.getMsg());
        assertEquals(1, inserted.size(), "管理员创建必须落库");
        verify(userService).getById(USER_ID);
    }

    // ========== 确定性交错脚手架 ==========

    /**
     * 让「删除侧」先持有属主用户的生命周期锁并从假库摘掉用户行，再让 createForward 去抢锁：
     * 创建线程必须停在 {@code tryLock} 上，放锁后才继续 —— 复现真实 deleteUser 与 createForward 的交错。
     */
    private R interleavedCreate(int roleId) throws Exception {
        CountDownLatch deleteHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        AtomicReference<Boolean> deleteAcquiredLock = new AtomicReference<>(Boolean.FALSE);
        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();

        Thread deleter = new Thread(() -> {
            ResourceLifecycleLocks.Lease lease = null;
            try {
                lease = ResourceLifecycleLocks.acquire(ResourceLifecycleLocks.DELETE_WAIT_MILLIS,
                        ResourceLifecycleLocks.userKey(USER_ID));
                deleteAcquiredLock.set(lease != null);
                // 真实 deleteUser 在锁内 purge 掉 forward 行并 removeById(userId)，这里只表达结果：行不存在了
                users.remove(USER_ID);
                deleteHoldsLock.countDown();
                releaseDelete.await(10, TimeUnit.SECONDS);
            } catch (Throwable t) {
                deleteFailure.set(t);
            } finally {
                if (lease != null) {
                    lease.close();
                }
                deleteHoldsLock.countDown();
            }
        }, "fake-delete-user-" + USER_ID);
        deleter.start();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(deleteHoldsLock.await(5, TimeUnit.SECONDS), "假删除侧没能持有属主用户生命周期锁");
            assertTrue(deleteAcquiredLock.get(), "假删除侧必须真正拿到锁，否则交错不成立");
            assertEquals(null, deleteFailure.get(), "假删除侧异常：" + deleteFailure.get());
            assertFalse(users.containsKey(USER_ID), "交错前提：属主行此时已不存在");

            Future<R> create = pool.submit(() -> {
                authenticate(roleId);
                return forwardService.createForward(newForwardDto());
            });

            assertBlockedOnLifecycleLock(create);

            releaseDelete.countDown();
            R result = create.get(10, TimeUnit.SECONDS);
            deleter.join(5_000L);
            assertFalse(deleter.isAlive(), "假删除侧必须结束");
            return result;
        } finally {
            releaseDelete.countDown();
            pool.shutdownNow();
        }
    }

    /** 只要 createForward 没被锁挡住，全内存替身会在毫秒级跑完 —— 因此"仍存活且未落库"就是等锁的证据。 */
    private void assertBlockedOnLifecycleLock(Future<R> create) throws InterruptedException {
        AtomicBoolean doneEarly = new AtomicBoolean(false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
        while (System.nanoTime() < deadline) {
            if (create.isDone()) {
                doneEarly.set(true);
                break;
            }
            Thread.sleep(20L);
        }
        assertFalse(doneEarly.get(), "createForward 必须阻塞在 ResourceLifecycleLocks 上，而不是直接跑完");
        assertFalse(create.isCancelled(), "创建线程被取消");
        assertEquals(List.of(), inserted, "等锁期间不得落库，实际：" + describeInserted());
    }

    // ========== 测试脚手架 ==========

    private String describeInserted() {
        if (inserted.isEmpty()) {
            return "(无)";
        }
        return inserted.stream()
                .map(f -> "forward{id=" + f.getId() + ",user_id=" + f.getUserId() + ",user_name=" + f.getUserName()
                        + ",tunnel_id=" + f.getTunnelId() + ",name=" + f.getName() + "}")
                .collect(Collectors.joining(", "));
    }

    private void authenticate(int roleId) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":" + roleId + ",\"name\":\"" + USER_NAME + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ForwardDto newForwardDto() {
        ForwardDto dto = new ForwardDto();
        dto.setName("post-delete-race");
        dto.setTunnelId((int) TUNNEL_ID);
        dto.setRemoteAddr("10.10.10.10:8080");
        dto.setStrategy("round");
        dto.setInPort(IN_PORT);
        return dto;
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

    private User user() {
        User user = new User();
        user.setId((long) USER_ID);
        user.setUser(USER_NAME);
        user.setNum(10);
        user.setFlow(1000L);
        user.setExpTime(null);
        user.setRoleId(1);
        return user;
    }

    private UserTunnel userTunnel() {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(11);
        userTunnel.setUserId(USER_ID);
        userTunnel.setTunnelId((int) TUNNEL_ID);
        userTunnel.setStatus(1);
        userTunnel.setNum(10);
        userTunnel.setFlow(1000L);
        return userTunnel;
    }

    /** 假节点会话：收到下发立刻完成 pendingRequest（返回 OK），绕开真实 WebSocket 往返。 */
    private void registerNode() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + NODE_ID);
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
        nodeSessions.put(NODE_ID, session);
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        assertNotNull(value, "WebSocketServer." + name + " 必须存在");
        return value;
    }
}
