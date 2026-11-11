package com.admin.ddns.service;

import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsCheckResult;
import com.admin.ddns.DdnsForwardApplier;
import com.admin.ddns.DdnsResolver;
import com.admin.ddns.DdnsTarget;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.ddns.DdnsTransitionBusyException;
import com.admin.common.utils.GostUtil;
import com.admin.ddns.dto.DdnsCreateRequest;
import com.admin.ddns.dto.DdnsUpdateRequest;
import com.admin.ddns.entity.DdnsRecord;
import com.admin.ddns.mapper.DdnsRecordMapper;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
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
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DDNS 状态机测试：只喂固定 IP 和 mock 下发通道，不解析真实 DNS、不连节点。
 *
 * <p>重点锁死三件事：主地址漂移才下发、下发失败要回滚、失败不能把新值写进库。
 */
class DdnsServiceApplyTest {

    private static final String DOMAIN = "app.example.com";
    private static final long NOW = 1_700_000_000_000L;

    private DdnsRecordMapper mapper;
    private DdnsAddressRegistry registry;
    private DdnsResolver resolver;
    private DdnsForwardApplier applier;
    private ForwardService forwardService;
    private TunnelService tunnelService;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private ForwardPortService forwardPortService;
    private UserTunnelService userTunnelService;
    private UserService userService;
    private DdnsService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DdnsRecordMapper.class);
        registry = new DdnsAddressRegistry();
        resolver = mock(DdnsResolver.class);
        applier = mock(DdnsForwardApplier.class);
        forwardService = mock(ForwardService.class);
        tunnelService = mock(TunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        forwardPortService = mock(ForwardPortService.class);
        userTunnelService = mock(UserTunnelService.class);
        userService = mock(UserService.class);
        service = new DdnsService(mapper, registry, resolver, applier, forwardService,
                tunnelService, chainTunnelService, nodeService, forwardPortService, userTunnelService,
                userService);
        when(mapper.updateById(any(DdnsRecord.class))).thenReturn(1);
        when(userService.getById(anyInt())).thenReturn(user(1));
    }

    @AfterEach
    void tearDown() {
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("主地址漂移：按既有入口口径逐节点 UpdateService，全部成功才落库")
    void appliesDriftToEveryEntryNodeAndPersists() {
        oneForwardWithNodes(1L, 2L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenReturn("OK");

        DdnsRecord record = record("1.1.1.1", "1.1.1.1");
        DdnsCheckResult result = service.apply(record, List.of("2.2.2.2"), NOW);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        assertEquals(1, result.affectedForwards());
        assertEquals("2.2.2.2", record.getPrimaryIp());
        assertEquals("2.2.2.2", registry.get(DOMAIN));

        ArgumentCaptor<DdnsTarget> captor = ArgumentCaptor.forClass(DdnsTarget.class);
        verify(applier, times(2)).update(captor.capture());
        for (DdnsTarget target : captor.getAllValues()) {
            assertEquals("21_1_5", target.serviceName());
            assertEquals(Integer.valueOf(3), target.limiter());
            assertEquals("app.example.com:8080,backup.example.com:9090",
                    target.forward().getRemoteAddr());
        }
        verify(mapper).updateById(record);
    }

    @Test
    @DisplayName("老主地址仍在解析结果里：只更新地址集合，不重下发不断流")
    void keepsServiceWhenPrimaryDoesNotDrift() {
        oneForwardWithNodes(1L);
        when(applier.isOnline(anyLong())).thenReturn(true);

        DdnsRecord record = record("1.1.1.1", "1.1.1.1");
        DdnsCheckResult result = service.apply(record, List.of("1.1.1.1", "2.2.2.2"), NOW);

        assertTrue(result.success(), result.message());
        assertFalse(result.changed());
        assertEquals(0, result.affectedForwards());
        assertEquals("1.1.1.1", record.getPrimaryIp());
        assertEquals("1.1.1.1,2.2.2.2", record.getResolvedIps());
        verify(applier, never()).update(any(DdnsTarget.class));
    }

    @Test
    @DisplayName("第二个节点下发失败：已改节点和失败节点都回滚到旧 IP，库保持旧值")
    void rollsBackAllTouchedNodesWhenOneUpdateFails() {
        oneForwardWithNodes(1L, 2L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class)))
                .thenReturn("OK", "service 21_1_5_tcp not found", "OK", "OK");

        DdnsRecord record = record("1.1.1.1", "1.1.1.1");
        DdnsCheckResult result = service.apply(record, List.of("2.2.2.2"), NOW);

        assertFalse(result.success());
        assertTrue(result.message().contains("已尝试回滚 2 个节点"), result.message());
        assertEquals("1.1.1.1", record.getPrimaryIp());
        assertEquals("1.1.1.1", registry.get(DOMAIN));
        assertTrue(record.getLastError().contains("下发失败"), record.getLastError());
        assertEquals(1, record.getFailCount());

        ArgumentCaptor<DdnsTarget> captor = ArgumentCaptor.forClass(DdnsTarget.class);
        verify(applier, times(4)).update(captor.capture());
        assertEquals(1L, captor.getAllValues().get(0).node().getId());
        assertEquals(2L, captor.getAllValues().get(1).node().getId());
    }

    @Test
    @DisplayName("回滚未确认时只报告尝试，不虚报节点回滚成功")
    void reportsRollbackAttemptsWithoutClaimingSuccess() {
        oneForwardWithNodes(1L, 2L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class)))
                .thenReturn("OK", "timeout", "timeout", "OK");
        DdnsRecord record = record("1.1.1.1", "1.1.1.1");
        DdnsCheckResult result = service.apply(record, List.of("2.2.2.2"), NOW);
        assertFalse(result.success());
        assertTrue(result.message().contains("已尝试回滚 2 个节点"), result.message());
        assertTrue(result.message().contains("回滚未确认：node1"), result.message());
        assertFalse(result.message().contains("已回滚"), result.message());
        assertEquals("1.1.1.1", record.getPrimaryIp());
    }

    @Test
    @DisplayName("任一入口节点离线：不下发任何节点，避免半套配置")
    void refusesToApplyWhenAnyEntryNodeOffline() {
        oneForwardWithNodes(1L, 2L);
        when(applier.isOnline(1L)).thenReturn(true);
        when(applier.isOnline(2L)).thenReturn(false);

        DdnsRecord record = record("1.1.1.1", "1.1.1.1");
        DdnsCheckResult result = service.apply(record, List.of("2.2.2.2"), NOW);

        assertFalse(result.success());
        assertTrue(result.message().contains("入口节点不在线"), result.message());
        assertEquals("1.1.1.1", record.getPrimaryIp());
        assertEquals("1.1.1.1", registry.get(DOMAIN));
        verify(applier, never()).update(any(DdnsTarget.class));
    }

    @Test
    @DisplayName("没有转发规则引用该域名：只记录解析结果，不调用任何节点")
    void persistsResolutionWithoutForwards() {
        when(forwardService.list(any())).thenReturn(List.of());

        DdnsRecord record = record("1.1.1.1", "1.1.1.1");
        DdnsCheckResult result = service.apply(record, List.of("2.2.2.2"), NOW);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        assertEquals(0, result.affectedForwards());
        assertEquals("2.2.2.2", record.getPrimaryIp());
        assertEquals("2.2.2.2", registry.get(DOMAIN));
        verify(applier, never()).update(any(DdnsTarget.class));
    }

    @Test
    @DisplayName("节点已更新但落库失败：回滚节点和注册表，不把新 IP 当成功")
    void rollsBackNodesWhenPersistenceFails() {
        oneForwardWithNodes(1L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenReturn("OK");
        when(mapper.updateById(any(DdnsRecord.class))).thenReturn(0);

        DdnsRecord record = record("1.1.1.1", "1.1.1.1");
        DdnsCheckResult result = service.apply(record, List.of("2.2.2.2"), NOW);

        assertFalse(result.success());
        assertTrue(result.message().contains("落库失败"), result.message());
        assertEquals("1.1.1.1", record.getPrimaryIp());
        assertEquals("1.1.1.1", registry.get(DOMAIN));
        verify(applier, times(2)).update(any(DdnsTarget.class));
    }

    @Test
    @DisplayName("创建时 DNS 解析失败：记录保留并写 lastError，不伪装成功")
    void createKeepsRecordWhenDnsFails() throws Exception {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.insert(any(DdnsRecord.class))).thenAnswer(invocation -> {
            invocation.<DdnsRecord>getArgument(0).setId(9L);
            return 1;
        });
        when(resolver.resolve(anyString())).thenThrow(new RuntimeException("dns down"));

        DdnsCreateRequest request = new DdnsCreateRequest();
        request.setDomain(" App.Example.com. ");
        R response = service.create(request);

        assertEquals(0, response.getCode(), response.getMsg());
        DdnsRecord saved = (DdnsRecord) response.getData();
        assertEquals("app.example.com", saved.getDomain());
        assertTrue(saved.getLastError().contains("DNS 解析失败"), saved.getLastError());
        assertEquals(1, saved.getFailCount());
        assertNull(registry.get(DOMAIN));
        verify(mapper).insert(saved);
        verify(mapper).updateById(saved);
    }

    @Test
    @DisplayName("新增域名尚未解析时摘要保持不可信")
    void createdUnresolvedDomainIsRegisteredImmediately() {
        registry.markLoaded(List.of());
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.insert(any(DdnsRecord.class))).thenAnswer(invocation -> {
            invocation.<DdnsRecord>getArgument(0).setId(9L);
            return 1;
        });
        DdnsCreateRequest request = new DdnsCreateRequest();
        request.setDomain(DOMAIN);
        request.setEnabled(0);
        R response = service.create(request);
        assertEquals(0, response.getCode(), response.getMsg());
        assertTrue(registry.unresolvedDomains().contains(DOMAIN), "新建记录须立即登记为未解析");
        assertTrue(registry.digestTrustIssue(DOMAIN) != null);
    }

    @Test
    @DisplayName("删除时无法完整规划回落：保留记录和注册表，如实报错，不动任何节点")
    void deleteRefusesWhenPlanIsIncomplete() {
        oneForwardWithNodes(1L);
        // 隧道数据缺失 → 规划阶段就确定不了入口服务，此时删记录会让节点永久停在旧 IP
        when(tunnelService.getById(16)).thenReturn(null);
        when(mapper.selectById(7L)).thenReturn(record("1.1.1.1", "1.1.1.1"));
        when(applier.isOnline(anyLong())).thenReturn(true);

        R response = service.delete(7L);

        assertEquals(-1, response.getCode(), response.getMsg());
        assertTrue(response.getMsg().contains("已保留记录"), response.getMsg());
        assertTrue(response.getMsg().contains("隧道不存在"), response.getMsg());
        assertEquals("1.1.1.1", registry.get(DOMAIN));
        verify(mapper, never()).deleteById(anyLong());
        verify(applier, never()).update(any(DdnsTarget.class));
    }

    @Test
    @DisplayName("删除影响 0 行：报错并把渲染映射放回，不假装删掉了")
    void deleteReportsZeroAffectedRowsAndRestoresRegistry() {
        oneForwardWithNodes(1L);
        when(mapper.selectById(7L)).thenReturn(record("1.1.1.1", "1.1.1.1"));
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenReturn("OK");
        when(mapper.deleteById(7L)).thenReturn(0);

        R response = service.delete(7L);

        assertEquals(-1, response.getCode(), response.getMsg());
        String message = response.getMsg();
        assertTrue(message.contains("删除未生效"), message);
        assertTrue(message.contains("已把 1 个已回落的节点改回记录中的 1.1.1.1"), message);
        assertEquals("1.1.1.1", registry.get(DOMAIN), "记录还在，渲染映射也必须还在");
        verify(mapper).deleteById(7L);
    }

    @Test
    @DisplayName("节点离线：保留记录与渲染映射，不下发，返回错误")
    void deleteKeepsRecordWhenEntryNodeOffline() {
        oneForwardWithNodes(1L);
        when(mapper.selectById(7L)).thenReturn(record("1.1.1.1", "1.1.1.1"));
        when(applier.isOnline(1L)).thenReturn(false);
        when(mapper.deleteById(7L)).thenReturn(1);

        R response = service.delete(7L);

        assertEquals(-1, response.getCode(), response.getMsg());
        assertTrue(response.getMsg().contains("入口节点 1 不在线"), response.getMsg());
        assertTrue(response.getMsg().contains("已保留记录"), response.getMsg());
        assertEquals("1.1.1.1", registry.get(DOMAIN));
        verify(mapper, never()).deleteById(anyLong());
        verify(applier, never()).update(any(DdnsTarget.class));
    }

    @Test
    @DisplayName("全部节点确认回落：删除成功文案不带未确认")
    void deleteConfirmsFallbackWhenEveryNodeOnline() {
        oneForwardWithNodes(1L);
        when(mapper.selectById(7L)).thenReturn(record("1.1.1.1", "1.1.1.1"));
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenReturn("OK");
        when(mapper.deleteById(7L)).thenReturn(1);

        R response = service.delete(7L);

        assertEquals(0, response.getCode(), response.getMsg());
        assertEquals("已删除，节点已回落到域名", response.getData());
        assertNull(registry.get(DOMAIN));
        verify(applier).update(any(DdnsTarget.class));
    }

    @Test
    @DisplayName("检查进行中删除同一记录：直接拒绝，否则检查线程会把 IP 再写回节点")
    void deleteIsRejectedWhileCheckInFlight() throws Exception {
        oneForwardWithNodes(1L);
        when(mapper.selectById(7L)).thenAnswer(invocation -> record("1.1.1.1", "1.1.1.1"));
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenReturn("OK");
        when(mapper.deleteById(7L)).thenReturn(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        when(resolver.resolve(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            blocked.await(5, TimeUnit.SECONDS);
            return List.of("2.2.2.2");
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<R> check = pool.submit(() -> service.checkNow(7L));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "检查线程未进入解析阶段");
            R response = service.delete(7L);
            assertEquals(-1, response.getCode(), response.getMsg());
            assertTrue(response.getMsg().contains("正在检查"), response.getMsg());
            verify(mapper, never()).deleteById(anyLong());
            blocked.countDown();
            assertEquals(0, check.get(5, TimeUnit.SECONDS).getCode());
        } finally {
            blocked.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("检查进行中改配置：直接拒绝，避免整实体回写把改动覆盖掉")
    void updateIsRejectedWhileCheckInFlight() throws Exception {
        oneForwardWithNodes(1L);
        when(mapper.selectById(7L)).thenAnswer(invocation -> record("1.1.1.1", "1.1.1.1"));
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenReturn("OK");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        when(resolver.resolve(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            blocked.await(5, TimeUnit.SECONDS);
            return List.of("1.1.1.1");
        });
        DdnsUpdateRequest request = new DdnsUpdateRequest();
        request.setId(7L);
        request.setEnabled(0);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<R> check = pool.submit(() -> service.checkNow(7L));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "检查线程未进入解析阶段");
            R response = service.update(request);
            assertEquals(-1, response.getCode(), response.getMsg());
            assertTrue(response.getMsg().contains("正在检查"), response.getMsg());
            verify(mapper, never()).updateById(any(DdnsRecord.class));
            blocked.countDown();
            assertEquals(0, check.get(5, TimeUnit.SECONDS).getCode());
        } finally {
            blocked.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("管理员属主：即使 user_tunnel 有行，服务名后缀仍是 0 且不带 limiter")
    void adminOwnerKeepsZeroSuffixEvenWhenUserTunnelExists() {
        oneForwardWithNodes(1L);
        when(userService.getById(1)).thenReturn(user(0));
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenReturn("OK");

        DdnsCheckResult result = service.apply(record("1.1.1.1", "1.1.1.1"), List.of("2.2.2.2"), NOW);

        assertTrue(result.success(), result.message());
        ArgumentCaptor<DdnsTarget> captor = ArgumentCaptor.forClass(DdnsTarget.class);
        verify(applier).update(captor.capture());
        assertEquals("21_1_0", captor.getValue().serviceName());
        assertNull(captor.getValue().limiter(), "管理员创建的入口不应被套上限速");
        verify(userTunnelService, never()).getOne(any(), anyBoolean());
    }

    @Test
    @DisplayName("未确认的 DDNS 地址不得暴露给并发规则操作")
    void candidateAddressIsNotPublishedBeforeNodeAcknowledgement() throws Exception {
        oneForwardWithNodes(1L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        when(applier.update(any(DdnsTarget.class))).thenAnswer(invocation -> {
            started.countDown();
            if (!releaseUpdate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return "OK";
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DdnsCheckResult> result = pool.submit(
                    () -> service.apply(record("1.1.1.1", "1.1.1.1"), List.of("2.2.2.2"), NOW));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("1.1.1.1", registry.get(DOMAIN),
                    "节点回执尚未确认时，其他规则不应读取候选地址");
            releaseUpdate.countDown();
            assertTrue(result.get(5, TimeUnit.SECONDS).success());
            assertEquals("2.2.2.2", registry.get(DOMAIN));
        } finally {
            releaseUpdate.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("下发窗口内候选地址只出现在本次下发的目标列表里，注册表保持已确认旧值")
    void stagedTargetsCarryCandidateWhileRegistryStaysCommitted() throws Exception {
        oneForwardWithNodes(1L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<DdnsTarget> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(applier.update(any(DdnsTarget.class))).thenAnswer(invocation -> {
            seen.set(invocation.getArgument(0));
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return "OK";
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DdnsCheckResult> result = pool.submit(
                    () -> service.apply(record("1.1.1.1", "1.1.1.1"), List.of("2.2.2.2"), NOW));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("1.1.1.1", registry.get(DOMAIN), "注册表在节点确认前不得出现候选地址");
            release.countDown();
            assertTrue(result.get(5, TimeUnit.SECONDS).success());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(List.of("2.2.2.2:8080", "backup.example.com:9090"),
                seen.get().renderedTargets(), "本次下发必须显式带候选地址，不能读注册表");
        assertEquals("app.example.com:8080,backup.example.com:9090",
                seen.get().forward().getRemoteAddr(), "原始转发规则不得被改成 IP");
        assertEquals("2.2.2.2", registry.get(DOMAIN), "节点确认后才发布新地址");
    }

    @Test
    @DisplayName("下发窗口内普通转发编辑/对账按注册表渲染该域名会直接失败，而不是拿旧值下发")
    void ordinaryRenderIsRejectedWhilePushWindowIsOpen() throws Exception {
        oneForwardWithNodes(1L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(applier.update(any(DdnsTarget.class))).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return "OK";
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DdnsCheckResult> result = pool.submit(
                    () -> service.apply(record("1.1.1.1", "1.1.1.1"), List.of("2.2.2.2"), NOW));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(DdnsTransitionBusyException.class,
                    () -> DdnsTargetRenderer.render("app.example.com:8080"),
                    "窗口内按注册表渲染该域名必须失败");
            assertThrows(DdnsTransitionBusyException.class,
                    () -> GostUtil.renderForwardTargets("app.example.com:8080,backup.example.com:9090"),
                    "共享同一条转发的普通下发同样必须失败，不能只挡住单个目标");
            release.countDown();
            assertTrue(result.get(5, TimeUnit.SECONDS).success());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals("2.2.2.2", DdnsTargetRenderer.render("app.example.com:8080").split(":")[0],
                "窗口结束后恢复按已确认映射渲染");
    }

    @Test
    @DisplayName("已有别的域名在切换：本次直接放弃，不碰任何节点也不改库")
    void refusesWhenAnotherDomainIsTransitioning() {
        oneForwardWithNodes(1L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        assertTrue(registry.beginTransition("other.example.com", "手工占位"));
        DdnsRecord record = record("1.1.1.1", "1.1.1.1");
        try {
            DdnsCheckResult result = service.apply(record, List.of("2.2.2.2"), NOW);

            assertFalse(result.success());
            assertTrue(result.message().contains("无法开始切换"), result.message());
            assertTrue(result.message().contains("other.example.com"), result.message());
            verify(applier, never()).update(any(DdnsTarget.class));
            assertEquals("1.1.1.1", registry.get(DOMAIN));
            assertEquals("1.1.1.1", record.getPrimaryIp(), "放弃时不得改写库里的主地址");
        } finally {
            registry.endTransition("other.example.com");
        }
    }

    @Test
    @DisplayName("下发失败回滚时下发旧地址，注册表全程没有候选地址，窗口结束后可正常渲染")
    void rollbackStagesCommittedAddressWithoutPublishingCandidate() {
        oneForwardWithNodes(1L, 2L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenReturn("OK", "节点通道断开", "OK", "OK");

        DdnsCheckResult result = service.apply(record("1.1.1.1", "1.1.1.1"), List.of("2.2.2.2"), NOW);

        assertFalse(result.success());
        assertEquals("1.1.1.1", registry.get(DOMAIN), "失败路径绝不能把候选地址留在注册表");
        assertEquals("1.1.1.1:8080", DdnsTargetRenderer.render("app.example.com:8080"),
                "窗口结束后按已确认旧值渲染");
        ArgumentCaptor<DdnsTarget> captor = ArgumentCaptor.forClass(DdnsTarget.class);
        verify(applier, times(4)).update(captor.capture());
        List<DdnsTarget> calls = captor.getAllValues();
        assertEquals(List.of("2.2.2.2:8080", "backup.example.com:9090"), calls.get(0).renderedTargets());
        assertEquals(List.of("2.2.2.2:8080", "backup.example.com:9090"), calls.get(1).renderedTargets());
        assertEquals(List.of("1.1.1.1:8080", "backup.example.com:9090"), calls.get(2).renderedTargets(),
                "回滚必须显式带旧地址，不能靠注册表");
        assertEquals(List.of("1.1.1.1:8080", "backup.example.com:9090"), calls.get(3).renderedTargets());
    }

    @Test
    @DisplayName("删除：节点回落期间注册表仍是旧值，回落后才移除；节点拿到的是域名而非旧 IP")
    void deleteStagesDomainFallbackBeforeRemovingCommittedValue() throws Exception {
        oneForwardWithNodes(1L);
        when(applier.isOnline(anyLong())).thenReturn(true);
        DdnsRecord record = record("1.1.1.1", "1.1.1.1");
        when(mapper.selectById(7L)).thenReturn(record);
        when(mapper.deleteById(7L)).thenReturn(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<DdnsTarget> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(applier.update(any(DdnsTarget.class))).thenAnswer(invocation -> {
            seen.set(invocation.getArgument(0));
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return "OK";
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<R> result = pool.submit(() -> service.delete(7L));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("1.1.1.1", registry.get(DOMAIN),
                    "节点还没回落完，映射不能先移除（否则残留节点永远停在 IP 上）");
            release.countDown();
            R response = result.get(5, TimeUnit.SECONDS);
            assertEquals(0, response.getCode(), response.getMsg());
            assertEquals("已删除，节点已回落到域名", String.valueOf(response.getData()));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(List.of("app.example.com:8080", "backup.example.com:9090"),
                seen.get().renderedTargets(), "删除只能把目标改回域名本身");
        assertNull(registry.get(DOMAIN));
    }

    private void oneForwardWithNodes(Long... nodeIds) {
        registry.put(DOMAIN, "1.1.1.1");
        Tunnel tunnel = new Tunnel();
        tunnel.setId(16L);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        when(tunnelService.getById(16)).thenReturn(tunnel);

        Forward forward = new Forward();
        forward.setId(21L);
        forward.setUserId(1);
        forward.setTunnelId(16);
        forward.setRemoteAddr("app.example.com:8080,backup.example.com:9090");
        forward.setStrategy("round");
        when(forwardService.list(any())).thenReturn(List.of(forward));

        List<ChainTunnel> entries = new java.util.ArrayList<>();
        for (Long nodeId : nodeIds) {
            ChainTunnel entry = new ChainTunnel();
            entry.setId(nodeId + 100);
            entry.setTunnelId(16L);
            entry.setChainType(1);
            entry.setNodeId(nodeId);
            entry.setPort(30200);
            entries.add(entry);

            Node node = new Node();
            node.setId(nodeId);
            node.setName("node-" + nodeId);
            node.setTcpListenAddr("0.0.0.0");
            node.setUdpListenAddr("0.0.0.0");
            node.setInterfaceName("eth0");
            when(nodeService.getById(nodeId)).thenReturn(node);
        }
        when(chainTunnelService.list(any())).thenReturn(entries);

        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(5);
        userTunnel.setUserId(1);
        userTunnel.setTunnelId(16);
        userTunnel.setSpeedId(3);
        when(userTunnelService.getOne(any(), eq(false))).thenReturn(userTunnel);

        ForwardPort port = new ForwardPort();
        port.setForwardId(21L);
        port.setNodeId(nodeIds[0]);
        port.setPort(30200);
        when(forwardPortService.getOne(any(), eq(false))).thenReturn(port);
    }

    private static User user(int roleId) {
        User user = new User();
        user.setId(1L);
        user.setRoleId(roleId);
        return user;
    }

    private static DdnsRecord record(String primaryIp, String resolvedIps) {
        DdnsRecord record = new DdnsRecord();
        record.setId(7L);
        record.setDomain(DOMAIN);
        record.setPrimaryIp(primaryIp);
        record.setResolvedIps(resolvedIps);
        record.setIntervalSeconds(60);
        record.setEnabled(1);
        record.setFailCount(0);
        record.setLastError("");
        return record;
    }
}
