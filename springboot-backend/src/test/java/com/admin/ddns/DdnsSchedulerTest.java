package com.admin.ddns;

import com.admin.ddns.service.DdnsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DDNS 调度线程模型回归测试（全离线：DdnsService 全部是 mock，不碰 DNS、不碰节点）。
 *
 * <p>覆盖的原问题：{@code @Scheduled tick} 里直接跑整轮 DNS 解析与节点下发，慢一轮就把
 * Spring 共享调度线程占住几十秒。这里锁死四件事：
 * ① 慢活不在调度线程上跑，tick 立即返回；
 * ② 上一轮未结束时重复 tick 只丢弃，不排队、不重入；
 * ③ 轮次异常 / 提交被拒时门控必须释放，否则之后再也不检查；
 * ④ 关闭时打断在途轮次、不把中断轮次记成完成，且销毁不会无限等待。
 */
class DdnsSchedulerTest {

    private DdnsService service;
    private DdnsScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.destroy();
        }
    }

    @Test
    void tickHandsTheRoundToItsOwnWorkerAndReturnsImmediately() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> worker = new AtomicReference<>();
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenReturn(true);
        when(service.checkDueRecords()).thenAnswer(invocation -> {
            worker.set(Thread.currentThread().getName());
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "checked=1, changed=0, failed=0";
        });
        scheduler = new DdnsScheduler(service);

        long start = System.nanoTime();
        scheduler.tick();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 300, "tick 必须立即返回，慢 DNS / 慢节点不能占 Spring 共享调度线程");
        assertTrue(entered.await(5, TimeUnit.SECONDS), "轮次应已被提交到执行器");
        assertEquals("ddns-scheduler-worker", worker.get(), "轮次必须跑在私有工作线程上");
        assertNotEquals(Thread.currentThread().getName(), worker.get());
        assertTrue(scheduler.tickRunning(), "在途轮次期间门控应保持占用");

        release.countDown();
        assertTrue(scheduler.awaitIdle(5, TimeUnit.SECONDS));
        assertFalse(scheduler.tickRunning(), "轮次结束后门控必须释放");
        verify(service, times(1)).checkDueRecords();
    }

    @Test
    void repeatedTicksNeitherOverlapNorQueue() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenReturn(true);
        when(service.checkDueRecords()).thenAnswer(invocation -> {
            calls.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return "checked=1, changed=0, failed=0";
        });
        scheduler = new DdnsScheduler(service);

        scheduler.tick();
        waitUntil(() -> calls.get() == 1, 5_000);
        for (int i = 0; i < 30; i++) {
            scheduler.tick();
        }
        assertEquals(1, calls.get(), "上一轮没结束时，重复 tick 必须被丢弃而不是排队执行");

        release.countDown();
        assertTrue(scheduler.awaitIdle(5, TimeUnit.SECONDS));
        scheduler.tick();
        waitUntil(() -> calls.get() == 2, 5_000);
        assertEquals(2, calls.get(), "上一轮结束后应能正常开始下一轮");
        verify(service, times(2)).checkDueRecords();
    }

    @Test
    void failingRoundReleasesTheGate() throws Exception {
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenReturn(true);
        when(service.checkDueRecords())
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn("checked=1, changed=0, failed=0");
        scheduler = new DdnsScheduler(service);

        scheduler.tick();
        assertTrue(scheduler.awaitIdle(5, TimeUnit.SECONDS), "异常轮次结束后门控必须释放");
        assertFalse(scheduler.tickRunning());

        scheduler.tick();
        assertTrue(scheduler.awaitIdle(5, TimeUnit.SECONDS));
        verify(service, times(2)).checkDueRecords();
    }

    @Test
    void rejectedSubmissionReleasesTheGateInsteadOfStickingClosed() throws Exception {
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenReturn(true);
        when(service.checkDueRecords()).thenReturn("checked=1, changed=0, failed=0");
        scheduler = new DdnsScheduler(service);
        scheduler.destroy();

        // 只把 closed 标志复位，执行器保持已关闭：模拟「登记关闭标志之前执行器先关掉」的竞态
        Field closed = DdnsScheduler.class.getDeclaredField("closed");
        closed.setAccessible(true);
        ((AtomicBoolean) closed.get(scheduler)).set(false);

        scheduler.tick();

        assertFalse(scheduler.tickRunning(), "提交被拒时必须把门控还回去，否则之后再也不检查");
        verifyNoInteractions(service);
    }

    @Test
    void destroyInterruptsInFlightRoundAndDoesNotReportItAsDone() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenReturn(true);
        when(service.checkDueRecords()).thenAnswer(invocation -> {
            entered.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return "checked=9, changed=9, failed=0";
        });
        scheduler = new DdnsScheduler(service);
        scheduler.tick();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "轮次应已开始");

        long start = System.nanoTime();
        scheduler.destroy();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(interrupted.get(), "关闭必须打断在途轮次，不能等它跑完再当成完成");
        assertTrue(elapsedMs < DdnsScheduler.SHUTDOWN_WAIT_MS,
                "被中断的轮次应立即退出，destroy 不该等满整轮 DNS 超时");
        assertFalse(scheduler.tickRunning(), "中断轮次的 finally 仍须释放门控");
        verify(service, times(1)).checkDueRecords();
    }

    @Test
    void destroyGivesUpInsteadOfHangingOnAStubbornRound() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenReturn(true);
        when(service.checkDueRecords()).thenAnswer(invocation -> {
            entered.countDown();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while (System.nanoTime() < until) {
                Thread.onSpinWait(); // 忽略中断，模拟卡在不可中断的 IO/计算里
            }
            return "checked=1, changed=0, failed=0";
        });
        scheduler = new DdnsScheduler(service);
        scheduler.tick();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "轮次应已开始");

        long start = System.nanoTime();
        scheduler.destroy();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < DdnsScheduler.SHUTDOWN_WAIT_MS, "销毁必须有上限，不能被顽固轮次挂死");
    }

    @Test
    void tickAfterDestroyIsANoOp() {
        service = mock(DdnsService.class);
        when(service.ensureRegistryLoaded()).thenReturn(true);
        scheduler = new DdnsScheduler(service);
        scheduler.destroy();

        scheduler.tick();
        scheduler.tick();

        assertFalse(scheduler.tickRunning());
        verifyNoInteractions(service);
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("等待超时：" + timeoutMs + " ms");
            }
            Thread.sleep(5L);
        }
    }
}
