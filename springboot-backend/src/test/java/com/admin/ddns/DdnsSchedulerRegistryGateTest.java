package com.admin.ddns;

import com.admin.ddns.service.DdnsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「渲染映射没装载成功就整轮跳过」的回归测试（全离线：DdnsService 是 mock，不碰 DNS、不碰节点、不碰库）。
 *
 * <p>原缺陷：{@code runRound} 调完 {@code ensureRegistryLoaded()} 就丢掉返回值，无论映射是否就绪
 * 都继续 {@code checkDueRecords()}。映射未就绪时 {@code DdnsService.apply} 的 {@code stage}
 * 只对本域名显式给新地址，同一条 {@code remoteAddr} 上的其它域名仍按注册表渲染 —— 注册表是空的
 * 就渲染成域名本身，节点上本来能用的 IP 被改回域名；等映射恢复后摘要对账再判漂移，删整对重建。
 * 也就是说「一次读库失败」会换来一次节点级删建。
 *
 * <p>本类锁死三件事：
 * ① 未就绪的一轮不调 {@code checkDueRecords}（不下发、不落库、不改节点）；
 * ② 跳过不能把门控卡死 —— 下一轮必须继续重试，恢复就绪后正常检查；
 * ③ 就绪走原路径，行为不变（修复不能顺手把检查关掉）。
 */
class DdnsSchedulerRegistryGateTest {

    private DdnsService service;
    private DdnsScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.destroy();
        }
    }

    @Test
    @DisplayName("映射未就绪：本轮整轮跳过，一次 DNS 解析/节点下发都不跑")
    void unreadyRegistrySkipsTheRoundInsteadOfDispatching() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenAnswer(invocation -> {
            probes.incrementAndGet();
            return false;
        });
        scheduler = new DdnsScheduler(service);

        scheduler.tick();
        assertTrue(scheduler.awaitIdle(5, TimeUnit.SECONDS), "跳过的一轮也必须释放门控");

        assertEquals(1, probes.get(), "每一轮都要试着补一次映射");
        verify(service, never()).checkDueRecords();
        assertFalse(scheduler.tickRunning());
    }

    @Test
    @DisplayName("跳过之后继续重试：映射恢复即就绪，下一轮正常检查且只检查一次")
    void retriesEveryRoundAndRunsOnceTheRegistryRecovers() throws Exception {
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenReturn(false, true);
        when(service.checkDueRecords()).thenReturn("checked=1, changed=0, failed=0");
        scheduler = new DdnsScheduler(service);

        scheduler.tick();
        assertTrue(scheduler.awaitIdle(5, TimeUnit.SECONDS));
        verify(service, never()).checkDueRecords();

        scheduler.tick();
        assertTrue(scheduler.awaitIdle(5, TimeUnit.SECONDS));

        verify(service, times(2)).ensureRegistryLoaded();
        verify(service, times(1)).checkDueRecords();
    }

    @Test
    @DisplayName("已就绪：行为与修复前一致，照常检查并把轮次交给工作线程")
    void readyRegistryStillRunsTheRound() throws Exception {
        AtomicReference<String> worker = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenReturn(true);
        when(service.checkDueRecords()).thenAnswer(invocation -> {
            worker.set(Thread.currentThread().getName());
            entered.countDown();
            return "checked=1, changed=0, failed=0";
        });
        scheduler = new DdnsScheduler(service);

        scheduler.tick();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "就绪时必须真的检查");
        assertTrue(scheduler.awaitIdle(5, TimeUnit.SECONDS));

        assertEquals("ddns-scheduler-worker", worker.get(), "检查仍跑在私有工作线程上");
        assertNotEquals(Thread.currentThread().getName(), worker.get());
        verify(service, times(1)).checkDueRecords();
    }

    @Test
    @DisplayName("就绪判定期间门控保持占用：判定没回来之前重复 tick 不重入")
    void probeRunsUnderTheGateSoRepeatedTicksDoNotOverlap() throws Exception {
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        AtomicInteger probes = new AtomicInteger();
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenAnswer(invocation -> {
            probes.incrementAndGet();
            probeEntered.countDown();
            releaseProbe.await(5, TimeUnit.SECONDS);
            return false; // 判定期间库还是读不出来：这一轮必须跳过
        });
        scheduler = new DdnsScheduler(service);

        scheduler.tick();
        assertTrue(probeEntered.await(5, TimeUnit.SECONDS), "判定应在工作线程上开始");
        assertTrue(scheduler.tickRunning());

        for (int i = 0; i < 10; i++) {
            scheduler.tick();
        }
        assertEquals(1, probes.get(), "判定未返回时不得并发再判一次");

        releaseProbe.countDown();
        assertTrue(scheduler.awaitIdle(5, TimeUnit.SECONDS));
        verify(service, never()).checkDueRecords();
    }

    @Test
    @DisplayName("关闭中判定被打断：不检查、不记为完成，门控仍释放")
    void destroyInterruptsAnUnreadyProbeAndStillReleasesTheGate() throws Exception {
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenAnswer(invocation -> {
            probeEntered.countDown();
            try {
                neverReleased.await(30, TimeUnit.SECONDS);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        scheduler = new DdnsScheduler(service);

        scheduler.tick();
        assertTrue(probeEntered.await(5, TimeUnit.SECONDS), "判定应已开始");

        scheduler.destroy();

        assertFalse(scheduler.tickRunning(), "被打断的判定也必须释放门控");
        verify(service, never()).checkDueRecords();

        scheduler.tick();
        verify(service, times(1)).ensureRegistryLoaded();
    }
}
