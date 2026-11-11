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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同一注册表实例上「库里整条域名被删掉」的重装载回归（全 mock，不解析 DNS、不连节点）。
 *
 * <p>静态发现的缺口：{@link DdnsAddressRegistry#markLoaded(java.util.Collection)} 只
 * {@code knownDomains.clear()} 并重建「已知域名」，完全不动 {@code byDomain}；而
 * {@code DdnsService.loadRegistry()} 每次都用<b>同一个</b>注册表实例，只对库里还在的行调
 * {@code put}。于是「上一轮有域名、这一轮读出空表」时：
 * <ul>
 *   <li>{@code byDomain} 还留着上一轮的 IP，{@code render}/{@code DdnsTargetRenderer.render}
 *       继续把旧 IP 渲染进节点目标（静默使用已删除域名的地址）；</li>
 *   <li>域名已不在 {@code knownDomains} 里，{@code unresolvedDomains()} 为空、
 *       {@code isDegraded()} 为 false、{@code digestTrustIssue} 返回 null —— 摘要在
 *       {@code READY} 状态下被当成可信期望值，没有任何报警。</li>
 * </ul>
 *
 * <p>同理，删掉 B 只留 A 时 B 的旧 IP 也会残留；而「删空后再新增同一域名」靠 {@code put}
 * 覆盖可以自行恢复，作为对照绿例保留。
 */
class DdnsRegistryRemovedDomainReloadTest {

    private static final String DOMAIN_A = "a.home.example.com";
    private static final String IP_A = "198.51.100.7";
    private static final String IP_A_FRESH = "203.0.113.9";
    private static final String DOMAIN_B = "b.home.example.com";
    private static final String IP_B = "192.0.2.44";
    private static final int PORT = 8443;

    private DdnsAddressRegistry registry;
    private DdnsService service;
    /** 本轮 loadRegistry 看到的 ddns_record 全表。 */
    private List<DdnsRecord> table;

    @BeforeEach
    void setUp() {
        registry = new DdnsAddressRegistry();
        DdnsRecordMapper mapper = mock(DdnsRecordMapper.class);
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
    @DisplayName("库从一条合法地址变成空表：旧 IP 必须消失，渲染回域名，快照不残留，状态仍是 READY")
    void reloadFromAddressToEmptyTableDropsStaleRendering() {
        table = List.of(record(DOMAIN_A, IP_A));
        assertTrue(service.loadRegistry(), "首轮装载应成功");
        assertEquals(IP_A + ":" + PORT, DdnsTargetRenderer.render(target(DOMAIN_A)),
                "对照：首轮有主地址时必须渲染成 IP");

        table = List.of();
        assertTrue(service.loadRegistry(), "空表也是成功的装载");

        assertEquals(target(DOMAIN_A), DdnsTargetRenderer.render(target(DOMAIN_A)),
                "库里已无该域名，渲染不得沿用上一轮的旧 IP（实际="
                        + DdnsTargetRenderer.render(target(DOMAIN_A)) + "）");
        assertEquals(target(DOMAIN_A), registry.render(target(DOMAIN_A)), "注册表 render 同样不得保留旧 IP");
        assertNull(registry.get(DOMAIN_A), "byDomain 里不得残留已删除域名的旧主地址");
        assertTrue(registry.snapshot().isEmpty(), "快照里不得残留旧主地址：" + registry.snapshot());
        assertEquals(0, registry.size(), "byDomain 计数必须归零");
        assertEquals(DdnsAddressRegistry.LoadState.READY, registry.loadState(), "空表装载成功后状态必须是 READY");
        assertEquals(List.of(), registry.unresolvedDomains(), "空表没有任何未解析域名");
        assertNull(DdnsTargetRenderer.anyDigestTrustIssue(target(DOMAIN_A)),
                "该域名已不是 DDNS 域名，摘要信任必须回到正常（不报警，按字面量处理）");
    }

    @Test
    @DisplayName("删掉域名 B 只留 A：A 继续渲染自己的 IP，B 的旧 IP 不得残留")
    void removedDomainDropsStaleRenderingWhileSurvivorKeepsAddress() {
        table = List.of(record(DOMAIN_A, IP_A), record(DOMAIN_B, IP_B));
        assertTrue(service.loadRegistry(), "首轮装载应成功");
        assertEquals(IP_B + ":" + PORT, DdnsTargetRenderer.render(target(DOMAIN_B)), "对照：B 首轮渲染成 IP");

        table = List.of(record(DOMAIN_A, IP_A));
        assertTrue(service.loadRegistry(), "删掉 B 后重装载应成功");

        assertEquals(IP_A + ":" + PORT, DdnsTargetRenderer.render(target(DOMAIN_A)), "留下来的 A 必须继续渲染自己的 IP");
        assertEquals(target(DOMAIN_B), DdnsTargetRenderer.render(target(DOMAIN_B)),
                "B 已从库里删掉，渲染不得继续使用它的旧 IP（实际="
                        + DdnsTargetRenderer.render(target(DOMAIN_B)) + "）");
        assertNull(registry.get(DOMAIN_B), "byDomain 里不得残留 B 的旧主地址");
        assertEquals(Map.of(DOMAIN_A, IP_A), registry.snapshot(), "快照只应剩 A：" + registry.snapshot());
        assertEquals(DdnsAddressRegistry.LoadState.READY, registry.loadState(), "重装载成功后状态必须是 READY");
        assertEquals(List.of(), registry.unresolvedDomains(), "A 有主地址、B 已不是 DDNS 域名，没有未解析项");
        assertNull(DdnsTargetRenderer.anyDigestTrustIssue(target(DOMAIN_A)), "A 的摘要必须可信");
    }

    @Test
    @DisplayName("删空后重新新增同一域名：必须渲染新地址，不能带出空表期间的残留")
    void reAddedDomainAfterEmptyReloadRendersFreshAddress() {
        table = List.of(record(DOMAIN_A, IP_A));
        assertTrue(service.loadRegistry(), "首轮装载应成功");

        table = List.of();
        assertTrue(service.loadRegistry(), "空表装载应成功");

        table = List.of(record(DOMAIN_A, IP_A_FRESH));
        assertTrue(service.loadRegistry(), "重新新增后重装载应成功");

        assertEquals(IP_A_FRESH, registry.get(DOMAIN_A), "重新新增后必须登记新主地址");
        assertEquals(IP_A_FRESH + ":" + PORT, DdnsTargetRenderer.render(target(DOMAIN_A)), "渲染必须用新地址");
        assertEquals(Map.of(DOMAIN_A, IP_A_FRESH), registry.snapshot(), "快照只应有新地址：" + registry.snapshot());
        assertEquals(DdnsAddressRegistry.LoadState.READY, registry.loadState(), "装载成功后状态必须是 READY");
        assertEquals(List.of(), registry.unresolvedDomains(), "新地址可用，没有未解析项");
        assertNull(DdnsTargetRenderer.anyDigestTrustIssue(target(DOMAIN_A)), "有新地址时摘要必须可信");
    }

    @Test
    @DisplayName("对照：显式 remove 掉同一域名后，上面那组期望全部成立（期望值本身不自相矛盾）")
    void explicitRemoveReachesTheSameTrustedState() {
        table = List.of(record(DOMAIN_A, IP_A));
        assertTrue(service.loadRegistry(), "首轮装载应成功");

        registry.remove(DOMAIN_A);

        assertEquals(target(DOMAIN_A), registry.render(target(DOMAIN_A)), "撤掉登记后渲染必须回到域名");
        assertTrue(registry.snapshot().isEmpty(), "快照必须为空：" + registry.snapshot());
        assertEquals(0, registry.size(), "byDomain 计数必须归零");
        assertEquals(DdnsAddressRegistry.LoadState.READY, registry.loadState(), "remove 不改装载状态");
        assertEquals(List.of(), registry.unresolvedDomains(), "没有未解析项");
        assertNull(DdnsTargetRenderer.anyDigestTrustIssue(target(DOMAIN_A)), "已不是 DDNS 域名，摘要信任正常");
    }

    private static String target(String domain) {
        return domain + ":" + PORT;
    }

    private static DdnsRecord record(String domain, String primaryIp) {
        DdnsRecord record = new DdnsRecord();
        record.setDomain(domain);
        record.setPrimaryIp(primaryIp);
        record.setResolvedIps(primaryIp);
        record.setIntervalSeconds(60);
        record.setEnabled(1);
        record.setFailCount(0);
        record.setLastError("");
        return record;
    }
}
