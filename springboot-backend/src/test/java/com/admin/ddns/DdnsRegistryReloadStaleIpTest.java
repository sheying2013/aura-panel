package com.admin.ddns;

import com.admin.ddns.entity.DdnsRecord;
import com.admin.ddns.mapper.DdnsRecordMapper;
import com.admin.ddns.service.DdnsService;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同一域名「先有主地址、再从库里读到空/非法主地址」时的重装载回归（全 mock，不解析 DNS、不连节点）。
 *
 * <p>{@code DdnsService.loadRegistry()} 每次都用<b>同一个</b>注册表实例调 {@link DdnsAddressRegistry#put}，
 * 再 {@code markLoaded}。而 {@code put} 在主地址不是合法 IP 时直接 return：既不写新值，也不撤掉
 * byDomain 里上一轮的旧值。于是第二轮装载后：
 * <ul>
 *   <li>{@code render}/{@code DdnsTargetRenderer.render} 继续把旧 IP 渲染进节点目标（静默回滚）；</li>
 *   <li>域名同时出现在 knownDomains 与 byDomain 里，{@code unresolvedDomains()} 为空，
 *       {@code isDegraded()} 与摘要闸门都不报警（对账把旧 IP 当成可信期望值）。</li>
 * </ul>
 *
 * <p>装载失败后重试（{@code ensureRegistryLoaded}）与重复 bootstrap 都会走到这条路径，所以旧值必须被撤掉。
 */
class DdnsRegistryReloadStaleIpTest {

    private static final String DOMAIN = "home.example.com";
    private static final String STALE_IP = "198.51.100.7";
    private static final String FRESH_IP = "203.0.113.9";
    private static final int PORT = 8443;
    private static final String DOMAIN_TARGET = DOMAIN + ":" + PORT;
    private static final String STALE_TARGET = STALE_IP + ":" + PORT;

    private DdnsAddressRegistry registry;
    private DdnsService service;
    private DdnsRecordMapper mapper;
    /** 本轮 loadRegistry 看到的 ddns_record 全表。 */
    private List<DdnsRecord> table;

    @BeforeEach
    void setUp() {
        registry = new DdnsAddressRegistry();
        mapper = mock(DdnsRecordMapper.class);
        when(mapper.selectList(any())).thenAnswer(invocation -> table);
        service = new DdnsService(mapper, registry, mock(DdnsResolver.class),
                mock(DdnsForwardApplier.class), mock(ForwardService.class), mock(TunnelService.class),
                mock(ChainTunnelService.class), mock(NodeService.class), mock(ForwardPortService.class),
                mock(UserTunnelService.class), mock(UserService.class));
    }

    @AfterEach
    void tearDown() {
        // 注册表构造器把渲染/摘要探针装在进程级静态接缝上，不还原会泄漏给同 JVM 的其他测试类。
        DdnsTargetRenderer.reset();
        registry.clear();
    }

    @Test
    @DisplayName("重装载撞到空主地址：旧 IP 必须消失，渲染回域名，且降级报警")
    void reloadWithBlankPrimaryIpDropsStaleRendering() {
        table = List.of(record(FRESH_IP));
        assertTrue(service.loadRegistry(), "首轮装载应成功");
        assertEquals(FRESH_IP, registry.get(DOMAIN), "首轮应把主地址装进注册表");

        table = List.of(record(""));
        assertTrue(service.loadRegistry(), "库里主地址为空也是成功的装载");

        assertEquals(DOMAIN_TARGET, DdnsTargetRenderer.render(DOMAIN_TARGET),
                "库里已没有可用主地址，渲染不得退回上一轮的旧 IP（实际=" + DdnsTargetRenderer.render(DOMAIN_TARGET) + "）");
        assertEquals(DOMAIN_TARGET, registry.render(DOMAIN_TARGET),
                "注册表 render 同样不得保留旧 IP（实际=" + registry.render(DOMAIN_TARGET) + "）");
        assertNull(registry.get(DOMAIN), "byDomain 里不得残留旧主地址");
        assertTrue(registry.snapshot().isEmpty(), "快照里不得残留旧主地址：" + registry.snapshot());
        assertEquals(List.of(DOMAIN), registry.unresolvedDomains(), "该域名应被记为「已知但无可用主地址」");
        assertTrue(registry.isDegraded(), "有域名没主地址就必须降级报警，否则对账拿旧 IP 当期望值");
        assertTrue(DdnsTargetRenderer.digestTrustDegraded(), "摘要闸门必须认为不可信");
        assertNotNull(DdnsTargetRenderer.anyDigestTrustIssue(DOMAIN_TARGET), "该目标必须被判为不可用于摘要漂移");
    }

    @Test
    @DisplayName("重装载撞到非法主地址：旧 IP 同样必须消失，不得继续渲染")
    void reloadWithInvalidPrimaryIpDropsStaleRendering() {
        table = List.of(record(FRESH_IP));
        assertTrue(service.loadRegistry(), "首轮装载应成功");
        assertEquals(FRESH_IP, registry.get(DOMAIN), "首轮应把主地址装进注册表");

        table = List.of(record("not-an-ip"));
        assertTrue(service.loadRegistry(), "主地址非法不影响装载本身成功");

        assertEquals(DOMAIN_TARGET, DdnsTargetRenderer.render(DOMAIN_TARGET),
                "非法主地址不得让上一轮的旧 IP 继续生效（实际=" + DdnsTargetRenderer.render(DOMAIN_TARGET) + "）");
        assertNull(registry.get(DOMAIN), "byDomain 里不得残留旧主地址");
        assertEquals(List.of(DOMAIN), registry.unresolvedDomains(), "该域名应被记为「已知但无可用主地址」");
        assertTrue(registry.isDegraded(), "有域名没主地址就必须降级报警");
        assertNotNull(DdnsTargetRenderer.anyDigestTrustIssue(DOMAIN_TARGET), "该目标必须被判为不可用于摘要漂移");
    }

    @Test
    @DisplayName("put 直接覆盖非法主地址：旧值必须被撤掉，而不是留在 byDomain 里")
    void putWithInvalidIpDropsPreviousValue() {
        registry.put(DOMAIN, FRESH_IP);
        registry.markLoaded(List.of(DOMAIN));
        assertFalse(registry.isDegraded(), "对照：有可用主地址时不降级");

        registry.put(DOMAIN, "");

        assertNull(registry.get(DOMAIN), "put 非法主地址后不得留下上一轮的值");
        assertEquals(DOMAIN_TARGET, registry.render(DOMAIN_TARGET), "渲染不得继续使用旧 IP");
        assertTrue(registry.isDegraded(), "已知无主地址必须降级");
    }

    @Test
    void loadSnapshotMustNotOverwriteConcurrentNewValue() {
        registry.put(DOMAIN, STALE_IP);
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            registry.put(DOMAIN, FRESH_IP);
            return List.of(record(STALE_IP));
        });
        service.loadRegistry();
        assertEquals(FRESH_IP, registry.get(DOMAIN), "旧数据库快照不得覆盖已确认的新地址");
    }

    @Test
    void loadSnapshotMustNotRestoreConcurrentDeletion() {
        registry.put(DOMAIN, STALE_IP);
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            registry.remove(DOMAIN);
            return List.of(record(STALE_IP));
        });
        service.loadRegistry();
        assertNull(registry.get(DOMAIN), "旧数据库快照不得复活已删除的域名");
    }

    @Test
    void concurrentThreadChangingRegistryInvalidatesOldDatabaseSnapshot() throws Exception {
        registry.put(DOMAIN, STALE_IP);
        var readStarted = new java.util.concurrent.CountDownLatch(1);
        var allowReturn = new java.util.concurrent.CountDownLatch(1);
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            readStarted.countDown();
            if (!allowReturn.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out");
            }
            return List.of(record(STALE_IP));
        });
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var load = pool.submit(() -> service.loadRegistry());
            assertTrue(readStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            registry.put(DOMAIN, FRESH_IP);
            allowReturn.countDown();
            assertFalse(load.get(5, java.util.concurrent.TimeUnit.SECONDS), "发生竞争时应放弃本轮加载");
            assertEquals(FRESH_IP, registry.get(DOMAIN));
        } finally {
            allowReturn.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static DdnsRecord record(String primaryIp) {
        DdnsRecord record = new DdnsRecord();
        record.setDomain(DOMAIN);
        record.setPrimaryIp(primaryIp);
        record.setResolvedIps(primaryIp);
        record.setIntervalSeconds(60);
        record.setEnabled(1);
        record.setFailCount(0);
        record.setLastError("");
        return record;
    }
}
