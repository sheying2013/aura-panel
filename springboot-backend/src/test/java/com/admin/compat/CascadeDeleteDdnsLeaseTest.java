package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsDomainGate;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.SpeedLimitService;
import com.admin.service.StatisticsFlowService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.CascadeDeletePurger;
import com.admin.service.impl.TunnelServiceImpl;
import com.admin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DDNS 切换窗口与 deleteTunnel / deleteUser 的互斥回归测试。
 *
 * <p>覆盖的原缺陷：deleteForward 在 DDNS 锁争用时只回错误，deleteTunnel / deleteUser 不看返回值，
 * 照样 purge 掉库里的 forward 行 —— 切换刚写进节点的新地址从此没有任何记录能对上，变成永久残留。
 *
 * <p>固定住的不变量：
 * <ol>
 *   <li>级联删除先把全部目标域名的共享凭证拿全，再动第一条转发；拿不到就一行都不删；</li>
 *   <li>凭证一直持有到级联事务返回，期间该域名的切换拿不到写锁；</li>
 *   <li>只锁自己用到的域名，不变成全局锁；</li>
 *   <li>快照之后新增转发 / 改到锁外域名 → 整体放弃，且凭证正常释放；</li>
 *   <li>DDNS 目标转发删除失败 → 不 purge；非 DDNS 转发失败仍是原有语义（照旧级联清理）。</li>
 * </ol>
 */
class CascadeDeleteDdnsLeaseTest {

    private static final long TUNNEL_ID = 16L;
    private static final long USER_ID = 7L;
    private static final long FORWARD_ID_1 = 21L;
    private static final long FORWARD_ID_2 = 22L;
    private static final long FORWARD_ID_3 = 23L;

    private static final String DOMAIN_A = "alpha.example.com";
    private static final String DOMAIN_B = "beta.example.com";
    private static final String DOMAIN_C = "gamma.example.com";
    private static final String REMOTE_A = DOMAIN_A + ":8080";
    private static final String REMOTE_B = DOMAIN_B + ":9090";
    private static final String REMOTE_C = DOMAIN_C + ":7070";
    /** 纯 IP 目标：不是 DDNS 域名，不参与加锁。 */
    private static final String REMOTE_IP = "1.1.1.1:8080";

    private final ExecutorService workers = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cascade-delete");
        thread.setDaemon(true);
        return thread;
    });

    private DdnsAddressRegistry registry;
    private ForwardService forwardService;
    private ForwardPortService forwardPortService;
    private UserTunnelService userTunnelService;
    private SpeedLimitService speedLimitService;
    private ChainTunnelService chainTunnelService;
    private StatisticsFlowService statisticsFlowService;
    private CascadeDeletePurger purger;
    private TestTunnelService tunnelService;
    private TestUserService userService;

    @BeforeEach
    void setUp() {
        // 真实闸门：构造器会把 render/dispatch 探针装到静态门面上，测试里必须用它自己的实例
        registry = new DdnsAddressRegistry();
        registry.put(DOMAIN_A, "10.0.0.1");
        registry.put(DOMAIN_B, "10.0.0.2");
        registry.put(DOMAIN_C, "10.0.0.3");

        forwardService = mock(ForwardService.class);
        forwardPortService = mock(ForwardPortService.class);
        userTunnelService = mock(UserTunnelService.class);
        speedLimitService = mock(SpeedLimitService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        statisticsFlowService = mock(StatisticsFlowService.class);
        purger = mock(CascadeDeletePurger.class);

        when(chainTunnelService.list(any())).thenReturn(List.of());
        when(speedLimitService.list(any())).thenReturn(List.of());
        when(forwardService.deleteForward(anyLong())).thenReturn(R.ok());

        tunnelService = new TestTunnelService();
        ReflectionTestUtils.setField(tunnelService, "forwardService", forwardService);
        ReflectionTestUtils.setField(tunnelService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(tunnelService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(tunnelService, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(tunnelService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(tunnelService, "cascadeDeletePurger", purger);

        userService = new TestUserService();
        ReflectionTestUtils.setField(userService, "forwardService", forwardService);
        ReflectionTestUtils.setField(userService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(userService, "statisticsFlowService", statisticsFlowService);
        ReflectionTestUtils.setField(userService, "cascadeDeletePurger", purger);
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
        DdnsTargetRenderer.reset();
    }

    // ========== 持锁窗口 ==========

    @Test
    void tunnelDeleteHoldsEveryTargetDomainUntilCascadeReturns() throws Exception {
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_A + "," + REMOTE_B),
                forward(FORWARD_ID_2, REMOTE_A)));
        CountDownLatch insideCascade = new CountDownLatch(1);
        CountDownLatch releaseCascade = new CountDownLatch(1);
        when(forwardService.deleteForward(anyLong())).thenAnswer(invocation -> {
            insideCascade.countDown();
            assertTrue(releaseCascade.await(10, TimeUnit.SECONDS), "探测方没有放行");
            return R.ok();
        });
        tunnelService.tunnel = tunnel();

        Future<R> pending = workers.submit(() -> tunnelService.deleteTunnel(TUNNEL_ID));
        assertTrue(insideCascade.await(10, TimeUnit.SECONDS), "删除没有进到级联下发");

        // 级联还没返回：这两个目标域名的写锁必须拿不到，说明共享凭证还攥在删除方手里
        assertNull(registry.beginTransitionLease(DOMAIN_A, "竞争", 100),
                "A 域名在级联期间必须被锁住");
        assertNull(registry.beginTransitionLease(DOMAIN_B, "竞争", 100),
                "同一行里的第二个目标域名也必须被锁住");
        // 不是全局锁：这次删除没用到的域名照样能切换
        DdnsDomainGate.Lease unrelated = registry.beginTransitionLease(DOMAIN_C, "无关切换", 100);
        assertNotNull(unrelated, "C 域名不该被这次删除锁住");
        unrelated.close();

        releaseCascade.countDown();
        R result = pending.get(10, TimeUnit.SECONDS);

        assertEquals(0, result.getCode(), result.getMsg());
        verify(purger).purgeTunnelDatabaseRecords(eq(TUNNEL_ID), eq(List.of(FORWARD_ID_1, FORWARD_ID_2)));

        DdnsDomainGate.Lease after = registry.beginTransitionLease(DOMAIN_A, "释放检查", 500);
        assertNotNull(after, "级联事务返回后 A 域名必须已经释放");
        after.close();
    }

    @Test
    void userDeleteHoldsEveryTargetDomainUntilCascadeReturns() throws Exception {
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_A), forward(FORWARD_ID_2, REMOTE_B)));
        CountDownLatch insideCascade = new CountDownLatch(1);
        CountDownLatch releaseCascade = new CountDownLatch(1);
        when(forwardService.deleteForward(anyLong())).thenAnswer(invocation -> {
            insideCascade.countDown();
            assertTrue(releaseCascade.await(10, TimeUnit.SECONDS), "探测方没有放行");
            return R.ok();
        });
        userService.user = user();

        Future<R> pending = workers.submit(() -> userService.deleteUser(USER_ID));
        assertTrue(insideCascade.await(10, TimeUnit.SECONDS), "删除没有进到级联下发");

        assertNull(registry.beginTransitionLease(DOMAIN_A, "竞争", 100), "A 域名在级联期间必须被锁住");
        assertNull(registry.beginTransitionLease(DOMAIN_B, "竞争", 100), "B 域名在级联期间必须被锁住");

        releaseCascade.countDown();
        R result = pending.get(10, TimeUnit.SECONDS);

        assertEquals(0, result.getCode(), result.getMsg());
        verify(purger).purgeUserDatabaseRecords(eq(USER_ID), eq(List.of(FORWARD_ID_1, FORWARD_ID_2)));

        DdnsDomainGate.Lease after = registry.beginTransitionLease(DOMAIN_B, "释放检查", 500);
        assertNotNull(after, "级联事务返回后 B 域名必须已经释放");
        after.close();
    }

    // ========== 锁争用：拒绝且零删除 ==========

    @Test
    void tunnelDeleteIsRejectedWithoutTouchingDbWhileTransitionRuns() {
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_A)));
        tunnelService.tunnel = tunnel();
        DdnsDomainGate.Lease switching = registry.beginTransitionLease(DOMAIN_A, "切换中", 500);
        assertNotNull(switching, "测试前提：切换窗口应先拿到");
        try {
            R result = tunnelService.deleteTunnel(TUNNEL_ID);

            assertEquals(-1, result.getCode(), "切换窗口内必须拒绝删除");
            assertTrue(result.getMsg().contains(DOMAIN_A), result.getMsg());
            verify(forwardService, never()).deleteForward(anyLong());
            verify(purger, never()).purgeTunnelDatabaseRecords(anyLong(), anyList());
        } finally {
            switching.close();
        }
    }

    @Test
    void userDeleteIsRejectedWithoutTouchingDbWhileTransitionRuns() {
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_B)));
        userService.user = user();
        DdnsDomainGate.Lease switching = registry.beginTransitionLease(DOMAIN_B, "切换中", 500);
        assertNotNull(switching, "测试前提：切换窗口应先拿到");
        try {
            R result = userService.deleteUser(USER_ID);

            assertEquals(-1, result.getCode(), "切换窗口内必须拒绝删除");
            assertTrue(result.getMsg().contains(DOMAIN_B), result.getMsg());
            verify(forwardService, never()).deleteForward(anyLong());
            verify(purger, never()).purgeUserDatabaseRecords(anyLong(), anyList());
        } finally {
            switching.close();
        }
    }

    // ========== 快照竞态 ==========

    @Test
    void forwardAppearingAfterSnapshotAbortsTheWholeDelete() {
        // 第一次读是快照，之后每次重读都多出一行：那条转发落在没加过锁的域名的锁外
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_A)),
                List.of(forward(FORWARD_ID_1, REMOTE_A), forward(FORWARD_ID_3, REMOTE_C)));
        tunnelService.tunnel = tunnel();

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(-1, result.getCode(), "快照之后新增转发必须整体放弃");
        assertTrue(result.getMsg().contains("转发" + FORWARD_ID_3), result.getMsg());
        verify(forwardService, never()).deleteForward(anyLong());
        verify(purger, never()).purgeTunnelDatabaseRecords(anyLong(), anyList());

        DdnsDomainGate.Lease after = registry.beginTransitionLease(DOMAIN_A, "释放检查", 500);
        assertNotNull(after, "放弃之后凭证必须已经释放");
        after.close();
    }

    @Test
    void forwardRetargetedToUnlockedDomainAbortsTheWholeDelete() {
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_A)),
                List.of(forward(FORWARD_ID_1, REMOTE_C)));
        tunnelService.tunnel = tunnel();

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(-1, result.getCode(), "改到锁外域名必须整体放弃");
        assertTrue(result.getMsg().contains(DOMAIN_C), result.getMsg());
        verify(forwardService, never()).deleteForward(anyLong());
        verify(purger, never()).purgeTunnelDatabaseRecords(anyLong(), anyList());
    }

    @Test
    void userForwardAppearingAfterSnapshotAbortsTheWholeDelete() {
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_A)),
                List.of(forward(FORWARD_ID_1, REMOTE_A), forward(FORWARD_ID_3, REMOTE_C)));
        userService.user = user();

        R result = userService.deleteUser(USER_ID);

        assertEquals(-1, result.getCode(), "快照之后新增转发必须整体放弃");
        assertTrue(result.getMsg().contains("转发" + FORWARD_ID_3), result.getMsg());
        verify(forwardService, never()).deleteForward(anyLong());
        verify(purger, never()).purgeUserDatabaseRecords(anyLong(), anyList());
    }

    // ========== 转发删除失败的两套语义 ==========

    @Test
    void ddnsForwardDeleteFailureAbortsCascadeInsteadOfPurging() {
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_A), forward(FORWARD_ID_2, REMOTE_B)));
        tunnelService.tunnel = tunnel();
        when(forwardService.deleteForward(FORWARD_ID_2)).thenReturn(R.err("转发目标正在切换"));

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(-1, result.getCode(), "DDNS 目标转发没删掉就不能删库");
        assertTrue(result.getMsg().contains("转发" + FORWARD_ID_2), result.getMsg());
        verify(purger, never()).purgeTunnelDatabaseRecords(anyLong(), anyList());

        // 中止路径同样要放锁，否则用户重试时永远撞在「正在切换」上
        DdnsDomainGate.Lease after = registry.beginTransitionLease(DOMAIN_A, "释放检查", 500);
        assertNotNull(after, "中止之后凭证必须已经释放");
        after.close();
    }

    @Test
    void userDdnsForwardDeleteFailureAbortsCascadeInsteadOfPurging() {
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_A)));
        userService.user = user();
        when(forwardService.deleteForward(FORWARD_ID_1)).thenReturn(R.err("目标域名正在 DDNS 切换"));

        R result = userService.deleteUser(USER_ID);

        assertEquals(-1, result.getCode(), "DDNS 目标转发没删掉就不能删库");
        verify(purger, never()).purgeUserDatabaseRecords(anyLong(), anyList());
    }

    @Test
    void nonDdnsForwardFailureKeepsLegacyCascade() {
        // 纯 IP 目标：删除失败沿用原语义，端口行孤儿比节点残留更难恢复，照旧级联清理
        stubForwardReads(List.of(forward(FORWARD_ID_1, REMOTE_IP)));
        tunnelService.tunnel = tunnel();
        when(forwardService.deleteForward(FORWARD_ID_1)).thenReturn(R.err("节点侧服务未删除"));

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        verify(purger).purgeTunnelDatabaseRecords(eq(TUNNEL_ID), eq(List.of(FORWARD_ID_1)));
    }

    // ========== 控制组：没有 DDNS 目标时行为不变 ==========

    @Test
    void deleteWithoutDdnsTargetsBehavesAsBeforeAndTakesNoLock() {
        Forward plain = forward(FORWARD_ID_1, REMOTE_IP);
        Forward noTarget = forward(FORWARD_ID_2, null);
        stubForwardReads(List.of(plain, noTarget));
        tunnelService.tunnel = tunnel();

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        verify(forwardService).deleteForward(FORWARD_ID_1);
        verify(forwardService).deleteForward(FORWARD_ID_2);
        verify(purger).purgeTunnelDatabaseRecords(eq(TUNNEL_ID), eq(List.of(FORWARD_ID_1, FORWARD_ID_2)));

        DdnsDomainGate.Lease anyDomain = registry.beginTransitionLease(DOMAIN_A, "无锁检查", 100);
        assertNotNull(anyDomain, "没有 DDNS 目标就不该占用任何域名凭证");
        anyDomain.close();
    }

    // ========== 测试脚手架 ==========

    @SafeVarargs
    private final void stubForwardReads(List<Forward>... snapshots) {
        int[] call = {0};
        when(forwardService.list(any())).thenAnswer(invocation -> {
            List<Forward> rows = snapshots[Math.min(call[0], snapshots.length - 1)];
            call[0]++;
            return rows;
        });
    }

    private static Forward forward(long id, String remoteAddr) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId((int) USER_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setRemoteAddr(remoteAddr);
        return forward;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("ddns-lease-test");
        tunnel.setStatus(1);
        return tunnel;
    }

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setUser("ddns-lease-test");
        user.setRoleId(1);
        return user;
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
    }

    private class TestUserService extends UserServiceImpl {

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
