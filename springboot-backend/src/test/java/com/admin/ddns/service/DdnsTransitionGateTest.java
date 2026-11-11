package com.admin.ddns.service;

import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsCheckResult;
import com.admin.ddns.DdnsDomainGate;
import com.admin.ddns.DdnsForwardApplier;
import com.admin.ddns.DdnsResolver;
import com.admin.ddns.DdnsTarget;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.ddns.entity.DdnsRecord;
import com.admin.ddns.mapper.DdnsRecordMapper;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.doAnswer;

/**
 * DDNS 切换窗口与普通下发互斥的确定性回归（全 mock，不碰真实 DNS / 节点）。
 *
 * <p>锁定三件事：
 * 1. 普通编辑还给节点下发时，切换抢不到窗口 → 一次推送都不发、库不动（旧快照覆盖不了别人）；
 * 2. 切换窗口内删除记录被拒、不删库、不删节点服务（删前被切换挡住）；
 * 3. 切换窗口覆盖「规划 → 逐个下发 → 落库」，期间的删除请求被拒。
 */
class DdnsTransitionGateTest {

    private static final String DOMAIN = "app.example.com";
    private static final long RECORD_ID = 7L;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final List<String> pushes = new ArrayList<>();
    private DdnsAddressRegistry registry;
    private DdnsRecordMapper mapper;
    private DdnsForwardApplier applier;
    private DdnsService service;

    @BeforeEach
    void setUp() {
        registry = new DdnsAddressRegistry();
        registry.put(DOMAIN, "1.1.1.1");
        mapper = mock(DdnsRecordMapper.class);
        applier = mock(DdnsForwardApplier.class);

        ForwardService forwardService = mock(ForwardService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);
        NodeService nodeService = mock(NodeService.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);
        UserService userService = mock(UserService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);

        Forward forward = new Forward();
        forward.setId(21L);
        forward.setUserId(1);
        forward.setTunnelId(16);
        forward.setStatus(1);
        forward.setRemoteAddr(DOMAIN + ":8080");
        when(forwardService.list(any())).thenReturn(new ArrayList<>(List.of(forward)));

        Tunnel tunnel = new Tunnel();
        tunnel.setId(16L);
        tunnel.setType(1);
        when(tunnelService.getById(any())).thenReturn(tunnel);

        ChainTunnel entry = new ChainTunnel();
        entry.setTunnelId(16L);
        entry.setChainType(1);
        entry.setNodeId(1L);
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>(List.of(entry)));

        Node node = new Node();
        node.setId(1L);
        node.setName("node-1");
        when(nodeService.getById(any())).thenReturn(node);

        ForwardPort port = new ForwardPort();
        port.setId(31L);
        port.setForwardId(21L);
        port.setNodeId(1L);
        port.setPort(30200);
        when(forwardPortService.getOne(any(), anyBoolean())).thenReturn(port);

        User owner = new User();
        owner.setId(1L);
        owner.setRoleId(0);
        when(userService.getById(any())).thenReturn(owner);

        when(applier.isOnline(anyLong())).thenReturn(true);
        when(mapper.updateById(any())).thenReturn(1);
        when(mapper.deleteById(any())).thenReturn(1);
        doAnswer(invocation -> {
            pushes.add(((DdnsTarget) invocation.getArgument(0)).serviceName());
            return "OK";
        }).when(applier).update(any());

        service = new DdnsService(mapper, registry, mock(DdnsResolver.class), applier, forwardService, tunnelService,
                chainTunnelService, nodeService, forwardPortService, userTunnelService, userService);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        DdnsTargetRenderer.reset();
    }

    @Test
    void normalEditInFlightBlocksSwitchPlanAndPushesNothing() {
        // 模拟「普通编辑已经取到窗口、正在往节点写」：切换只能在它结束后才能规划
        try (Holder edit = hold(() -> registry.beginDispatch(DOMAIN + ":8080", 0))) {
            DdnsCheckResult result = service.apply(record(), List.of("2.2.2.2"), 1_700_000_000_000L);

            assertFalse(result.success(), "有别的下发在写节点时切换不得开始");
            assertTrue(result.message().contains("其它下发"), result.message());
            assertTrue(pushes.isEmpty(), "抢不到窗口就一个节点都不能改：" + pushes);
            assertEquals("1.1.1.1", registry.get(DOMAIN), "注册表必须还是旧地址");
            // fail() 会回写 lastError，但主地址不能被旧快照改掉
            org.mockito.ArgumentCaptor<DdnsRecord> saved = org.mockito.ArgumentCaptor.forClass(DdnsRecord.class);
            verify(mapper, atLeast(0)).updateById(saved.capture());
            for (DdnsRecord written : saved.getAllValues()) {
                assertEquals("1.1.1.1", written.getPrimaryIp(), "抢不到窗口时库里的主地址不能变");
            }
        }

        DdnsCheckResult retried = service.apply(record(), List.of("2.2.2.2"), 1_700_000_000_001L);
        assertTrue(retried.success(), retried.message());
        assertEquals(List.of("21_1_0"), pushes, "窗口放开后重试才允许下发");
        assertEquals("2.2.2.2", registry.get(DOMAIN));
    }

    @Test
    void deleteIsRejectedWhileTransitionWindowIsOpen() {
        when(mapper.selectById(RECORD_ID)).thenReturn(record());

        try (Holder window = hold(() -> registry.beginTransitionLease(DOMAIN, "切换中", 0))) {
            R result = service.delete(RECORD_ID);

            assertEquals(-1, result.getCode(), "切换窗口内删除必须被拒");
            assertTrue(result.getMsg().contains("无法删除"), result.getMsg());
            verify(mapper, never()).deleteById(any());
            assertTrue(pushes.isEmpty());
        }
    }

    /** 凭证必须由别的线程持有：读锁在同一线程里可以越过自己持有的写锁，同线程测不出互斥。 */
    private Holder hold(java.util.function.Supplier<DdnsDomainGate.Lease> acquire) {
        return new Holder(acquire);
    }

    private static final class Holder implements AutoCloseable {

        private final Thread thread;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile DdnsDomainGate.Lease lease;

        Holder(java.util.function.Supplier<DdnsDomainGate.Lease> acquire) {
            thread = new Thread(() -> {
                lease = acquire.get();
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (lease != null) {
                    lease.close();
                }
            }, "ddns-holder");
            thread.setDaemon(true);
            thread.start();
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS), "凭证线程应已启动");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            assertNotNull(lease, "测试前置：凭证应能拿到");
        }

        @Override
        public void close() {
            release.countDown();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void deleteDuringInFlightSwitchIsRejectedAndSwitchStillCommits() throws Exception {
        CountDownLatch pushing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            pushes.add(((DdnsTarget) invocation.getArgument(0)).serviceName());
            pushing.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "OK";
        }).when(applier).update(any());
        when(mapper.selectById(RECORD_ID)).thenReturn(record());

        Future<DdnsCheckResult> switching = pool.submit(() -> service.apply(record(), List.of("2.2.2.2"),
                1_700_000_000_000L));
        assertTrue(pushing.await(5, TimeUnit.SECONDS), "切换应当已经开始下发");

        R deleteResult = service.delete(RECORD_ID);
        assertEquals(-1, deleteResult.getCode(), "切换正在给节点写地址时删除不得插入：" + deleteResult.getMsg());
        verify(mapper, never()).deleteById(any());

        release.countDown();
        DdnsCheckResult committed = switching.get(5, TimeUnit.SECONDS);
        assertTrue(committed.success(), committed.message());
        assertEquals("2.2.2.2", registry.get(DOMAIN), "切换完成后注册表才切到新地址");
        assertEquals(List.of("21_1_0"), pushes, "只应有一次下发，且不能被删除/回滚插队");
    }

    private static DdnsRecord record() {
        DdnsRecord record = new DdnsRecord();
        record.setId(RECORD_ID);
        record.setDomain(DOMAIN);
        record.setPrimaryIp("1.1.1.1");
        record.setResolvedIps("1.1.1.1");
        record.setEnabled(1);
        return record;
    }
}
