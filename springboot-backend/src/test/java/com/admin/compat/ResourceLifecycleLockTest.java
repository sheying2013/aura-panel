package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.ResourceLifecycleLocks;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.StatisticsFlowService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.CascadeDeletePurger;
import com.admin.service.impl.ForwardServiceImpl;
import com.admin.service.impl.TunnelServiceImpl;
import com.admin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户 / 隧道资源生命周期互斥的回归测试。
 *
 * <p>覆盖的缺陷：deleteTunnel / deleteUser 的第二道漂移检查（forwardSnapshotDrift）只看库行，
 * 挡不住「检查通过之后、purge 之前」才插入的转发 —— 那条转发的行被 purge 删掉，而 createForward
 * 这时可能刚把服务建到节点上，库里从此再没有记录能与那条服务对应。
 *
 * <p>断言分两层：
 * 1. 锁本体：重叠键互斥、反序传参不成环、等不到就整体放弃（不留半把锁）、重入与幂等释放；
 * 2. 入口：删除侧持锁期间 createForward 一个字节都不写；deleteTunnel 的转发快照在拿到锁之后才读，
 *    因此等锁期间新插入的行同样会被 purge 覆盖；deleteTunnel 与 deleteUser 键空间不相交，互不阻塞。
 */
class ResourceLifecycleLockTest {

    private static final int USER_ID = 3;
    private static final long TUNNEL_ID = 7L;
    private static final long OTHER_USER_ID = 4L;

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    // ========== 锁本体 ==========

    @Test
    void overlappingKeysAreMutuallyExclusive() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        ResourceLifecycleLocks.Lease holder = ResourceLifecycleLocks.acquire(1000,
                ResourceLifecycleLocks.userKey(USER_ID), ResourceLifecycleLocks.tunnelKey(TUNNEL_ID));
        assertNotNull(holder);
        try {
            assertFalse(pool.submit(() -> acquireAndClose(150, ResourceLifecycleLocks.tunnelKey(TUNNEL_ID)))
                    .get(5, TimeUnit.SECONDS), "隧道键被占用时不得拿到锁");
        } finally {
            holder.close();
        }
        assertTrue(pool.submit(() -> acquireAndClose(1000, ResourceLifecycleLocks.tunnelKey(TUNNEL_ID)))
                .get(5, TimeUnit.SECONDS), "释放后必须能拿到");
    }

    @Test
    void reversedKeyOrderIsSerializedWithoutDeadlock() throws Exception {
        pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final boolean reversed = i == 0;
            futures.add(pool.submit(() -> {
                start.await();
                int timeouts = 0;
                for (int round = 0; round < 200; round++) {
                    // 两个线程故意以相反顺序传键：加锁顺序必须由 ResourceLifecycleLocks 统一决定，
                    // 否则这就是经典的 ABBA 死锁
                    String userKey = ResourceLifecycleLocks.userKey(USER_ID);
                    String tunnelKey = ResourceLifecycleLocks.tunnelKey(TUNNEL_ID);
                    boolean locked = reversed
                            ? acquireAndClose(5000, tunnelKey, userKey)
                            : acquireAndClose(5000, userKey, tunnelKey);
                    if (!locked) {
                        timeouts++;
                    }
                }
                return timeouts;
            }));
        }
        start.countDown();
        assertEquals(0, futures.get(0).get(30, TimeUnit.SECONDS), "反序传参不得因成环而拿不到锁");
        assertEquals(0, futures.get(1).get(30, TimeUnit.SECONDS), "反序传参不得因成环而拿不到锁");
    }

    @Test
    void failedAcquireReleasesEveryKeyItAlreadyHolds() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        // 排在前面的隧道键能拿到、排在后面的用户键拿不到：
        // 失败的这次必须回滚已经拿到的隧道键，否则这条隧道此后所有创建/删除都会被一把没人持有的锁挡住
        ResourceLifecycleLocks.Lease holder = ResourceLifecycleLocks.acquire(1000,
                ResourceLifecycleLocks.userKey(USER_ID));
        assertNotNull(holder);
        try {
            assertFalse(pool.submit(() -> acquireAndClose(100,
                            ResourceLifecycleLocks.tunnelKey(TUNNEL_ID), ResourceLifecycleLocks.userKey(USER_ID)))
                    .get(5, TimeUnit.SECONDS), "用户键被占用时不得拿到锁");
        } finally {
            holder.close();
        }
        assertTrue(acquireAndClose(1000, ResourceLifecycleLocks.tunnelKey(TUNNEL_ID)),
                "失败的加锁必须回滚已持有的键，不能留半把锁");
    }

    @Test
    void leaseIsReentrantAndCloseIsIdempotent() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        ResourceLifecycleLocks.Lease outer = ResourceLifecycleLocks.acquire(1000,
                ResourceLifecycleLocks.userKey(USER_ID));
        ResourceLifecycleLocks.Lease inner = ResourceLifecycleLocks.acquire(1000,
                ResourceLifecycleLocks.userKey(USER_ID));
        assertNotNull(outer);
        assertNotNull(inner);

        outer.close();
        outer.close(); // 幂等：第二次 close 不能多放一次
        assertFalse(pool.submit(() -> acquireAndClose(100, ResourceLifecycleLocks.userKey(USER_ID)))
                .get(5, TimeUnit.SECONDS), "重入两次只 close 一次时锁仍被持有");

        inner.close();
        assertTrue(pool.submit(() -> acquireAndClose(1000, ResourceLifecycleLocks.userKey(USER_ID)))
                .get(5, TimeUnit.SECONDS), "两份凭证都释放后必须能再拿到");
    }

    // ========== 入口：createForward 与删除侧互斥 ==========

    @Test
    void createForwardWritesNothingWhileDeleteHoldsEitherLifecycleKey() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        ForwardMapper forwardMapper = mock(ForwardMapper.class);
        NodeService nodeService = mock(NodeService.class);
        ForwardServiceImpl service = newForwardService(forwardMapper, nodeService);

        // 隧道侧：deleteTunnel 持隧道键
        assertCreateForwardBlockedUntilRelease(service, forwardMapper, nodeService,
                ResourceLifecycleLocks.tunnelKey(TUNNEL_ID));
        // 用户侧：deleteUser 持属主键，创建方两种键都要，一样得等
        assertCreateForwardBlockedUntilRelease(service, forwardMapper, nodeService,
                ResourceLifecycleLocks.userKey(USER_ID));
    }

    /** 删除侧位于临界区内时，并发创建必须停在锁上，一个字节都不写。 */
    private void assertCreateForwardBlockedUntilRelease(ForwardServiceImpl service,
                                                        ForwardMapper forwardMapper,
                                                        NodeService nodeService,
                                                        String heldKey) throws Exception {
        ResourceLifecycleLocks.Lease holder = ResourceLifecycleLocks.acquire(1000, heldKey);
        assertNotNull(holder, "测试自身要能拿到 " + heldKey);
        Future<R> pending = pool.submit(() -> {
            // RequestContextHolder 默认线程隔离：JWT 必须在真正执行 createForward 的线程上挂
            authenticate();
            return service.createForward(newForwardDto());
        });
        try {
            Thread.sleep(200);
            assertFalse(pending.isDone(), "删除侧持锁期间 createForward 不允许返回（" + heldKey + "）");
            verify(forwardMapper, never()).insert(any(Forward.class));
            verify(nodeService, never()).getById(any());
        } finally {
            holder.close();
        }

        // 删除已经把隧道清掉：创建方拿到锁后必须重读，而不是沿用等锁前的结论
        R result = pending.get(15, TimeUnit.SECONDS);
        assertEquals(-1, result.getCode(), "隧道已删时必须失败：" + result.getMsg());
        assertEquals("隧道不存在", result.getMsg());
        verify(forwardMapper, never()).insert(any(Forward.class));
        verify(nodeService, never()).getById(any());
    }

    @Test
    void deleteUserIsNotSerializedWithTunnelScopedDelete() throws Exception {
        ForwardService forwardService = mock(ForwardService.class);
        when(forwardService.list(any())).thenReturn(Collections.emptyList());
        CascadeDeletePurger purger = mock(CascadeDeletePurger.class);

        TestUserService userService = new TestUserService();
        ReflectionTestUtils.setField(userService, "forwardService", forwardService);
        ReflectionTestUtils.setField(userService, "userTunnelService", mock(UserTunnelService.class));
        ReflectionTestUtils.setField(userService, "statisticsFlowService", mock(StatisticsFlowService.class));
        ReflectionTestUtils.setField(userService, "cascadeDeletePurger", purger);
        userService.user = user(1);

        // 另一个线程正按隧道键删隧道：两个入口的键空间不相交，不能互相挡住
        ResourceLifecycleLocks.Lease tunnelHolder = ResourceLifecycleLocks.acquire(1000,
                ResourceLifecycleLocks.tunnelKey(TUNNEL_ID));
        try {
            R result = userService.deleteUser(OTHER_USER_ID);
            assertEquals(0, result.getCode(), result.getMsg());
            verify(purger).purgeUserDatabaseRecords(OTHER_USER_ID, Collections.emptyList());
        } finally {
            tunnelHolder.close();
        }
    }

    // ========== 入口：deleteTunnel 的快照在锁内读取 ==========

    @Test
    void deleteTunnelReadsForwardSnapshotInsideTheLockSoLateRowsArePurged() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        ForwardService forwardService = mock(ForwardService.class);
        List<Forward> rows = new ArrayList<>();
        when(forwardService.list(any())).thenAnswer(invocation -> new ArrayList<>(rows));
        when(forwardService.deleteForward(anyLong())).thenReturn(R.ok());
        CascadeDeletePurger purger = mock(CascadeDeletePurger.class);

        TestTunnelService tunnelService = new TestTunnelService();
        ReflectionTestUtils.setField(tunnelService, "forwardService", forwardService);
        ReflectionTestUtils.setField(tunnelService, "chainTunnelService", emptyChainTunnels());
        ReflectionTestUtils.setField(tunnelService, "speedLimitService", emptySpeedLimits());
        ReflectionTestUtils.setField(tunnelService, "cascadeDeletePurger", purger);
        tunnelService.tunnel = tunnel();

        ResourceLifecycleLocks.Lease holder = ResourceLifecycleLocks.acquire(1000,
                ResourceLifecycleLocks.tunnelKey(TUNNEL_ID));
        assertNotNull(holder);
        Future<R> pending = pool.submit(() -> tunnelService.deleteTunnel(TUNNEL_ID));
        try {
            Thread.sleep(200);
            // 关键点：锁还在别人手里时，删除不得先读快照 —— 否则读出来的就是一份注定过期的行集
            assertFalse(pending.isDone(), "持锁期间 deleteTunnel 不允许返回");
            verify(forwardService, never()).list(any());
            verify(purger, never()).purgeTunnelDatabaseRecords(anyLong(), anyList());

            // 等锁期间 createForward 插进来一条：它不在任何旧快照里，必须被这次删除覆盖
            rows.add(forward(900L));
        } finally {
            holder.close();
        }

        R result = pending.get(15, TimeUnit.SECONDS);
        assertEquals(0, result.getCode(), result.getMsg());
        verify(forwardService).deleteForward(900L);
        verify(purger).purgeTunnelDatabaseRecords(TUNNEL_ID, Collections.singletonList(900L));
    }

    // ========== 脚手架 ==========

    /** 拿到就立刻释放：锁必须由持锁线程自己解锁，测试里不能跨线程 close。 */
    private static boolean acquireAndClose(long waitMillis, String... keys) {
        ResourceLifecycleLocks.Lease lease = ResourceLifecycleLocks.acquire(waitMillis, keys);
        if (lease == null) {
            return false;
        }
        lease.close();
        return true;
    }

    private ForwardServiceImpl newForwardService(ForwardMapper forwardMapper, NodeService nodeService) {
        ForwardServiceImpl service = new ForwardServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", forwardMapper);
        // 隧道已被删：创建方拿到锁后重读只能得到 null
        TunnelService tunnelService = mock(TunnelService.class);
        when(tunnelService.getById(any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(service, "chainTunnelService", emptyChainTunnels());
        ReflectionTestUtils.setField(service, "nodeService", nodeService);
        ReflectionTestUtils.setField(service, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(service, "userTunnelService", mock(UserTunnelService.class));
        return service;
    }

    private static ChainTunnelService emptyChainTunnels() {
        ChainTunnelService service = mock(ChainTunnelService.class);
        when(service.list(any())).thenReturn(Collections.emptyList());
        return service;
    }

    private static SpeedLimitService emptySpeedLimits() {
        SpeedLimitService service = mock(SpeedLimitService.class);
        when(service.list(any())).thenReturn(Collections.emptyList());
        return service;
    }

    /** 只带 sub + role_id=0（管理员）：权限判断在读用户之前就返回，创建流程不必关心配额。 */
    private static void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static ForwardDto newForwardDto() {
        ForwardDto dto = new ForwardDto();
        dto.setName("lifecycle-lock-test");
        dto.setTunnelId((int) TUNNEL_ID);
        dto.setRemoteAddr("10.10.10.10:8080");
        dto.setStrategy("round");
        dto.setInPort(20000);
        return dto;
    }

    private static Forward forward(long id) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId(USER_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        return forward;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("lifecycle-lock-test");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private static User user(int roleId) {
        User user = new User();
        user.setId(OTHER_USER_ID);
        user.setUser("lifecycle-lock-test");
        user.setRoleId(roleId);
        return user;
    }

    private static GostDto reply() {
        GostDto dto = new GostDto();
        dto.setMsg("OK");
        return dto;
    }

    private class TestTunnelService extends TunnelServiceImpl {

        Tunnel tunnel;

        @Override
        public Tunnel getById(Serializable id) {
            return tunnel;
        }

        @Override
        public boolean removeById(Serializable id) {
            return true;
        }

        @Override
        protected GostDto deleteService(Long nodeId, String name) {
            return reply();
        }

        @Override
        protected GostDto deleteChains(Long nodeId, String name) {
            return reply();
        }

        @Override
        protected GostDto deleteLimiters(Long nodeId, Long limiterId) {
            return reply();
        }
    }

    private static class TestUserService extends UserServiceImpl {

        User user;

        @Override
        public User getById(Serializable id) {
            return user;
        }

        @Override
        public boolean removeById(Serializable id) {
            return true;
        }
    }
}
