package com.admin.common.nodehealth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * 调度器回归测试：原实现直接在 Spring 的全局调度线程上逐节点等回包，一轮能占住线程几十秒；
 * 且 {@code fixedDelay} 无法阻止不同触发源叠加。
 *
 * <p>锁死：tick 立即返回、轮次跑在私有工作线程、在途时后续 tick 直接丢弃不排队、
 * 抛异常/关闭后门控能正确释放或停止。
 */
class NodeProbeSchedulerTest {

    @Test
    void tickReturnsImmediatelyAndProbeRunsOnPrivateWorker() throws Exception {
        NodeProbeService service = mock(NodeProbeService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(service.probeIfDue("schedule")).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            started.countDown();
            awaitQuietly(release);
            return Optional.empty();
        });
        NodeProbeScheduler scheduler = new NodeProbeScheduler(service);
        try {
            long begin = System.nanoTime();
            scheduler.scheduledProbe();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

            assertTrue(elapsedMs < 1_000L, "tick 不得等待探测完成，实际 " + elapsedMs + " ms");
            assertTrue(started.await(2L, TimeUnit.SECONDS), "探测应已在后台开始");
            assertTrue(scheduler.probeRunning());
            assertEquals("node-probe-worker", worker.get().getName(), "探测必须跑在私有工作线程");
            assertNotEquals(Thread.currentThread(), worker.get(), "不能占用调用方（Spring 调度）线程");

            release.countDown();
            assertTrue(scheduler.awaitIdle(2L, TimeUnit.SECONDS));
            verify(service, times(1)).probeIfDue("schedule");
        } finally {
            release.countDown();
            scheduler.destroy();
        }
    }

    @Test
    void ticksDuringRunningRoundAreDroppedInsteadOfQueued() throws Exception {
        NodeProbeService service = mock(NodeProbeService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.probeIfDue("schedule")).thenAnswer(invocation -> {
            started.countDown();
            awaitQuietly(release);
            return Optional.empty();
        });
        NodeProbeScheduler scheduler = new NodeProbeScheduler(service);
        try {
            scheduler.scheduledProbe();
            assertTrue(started.await(2L, TimeUnit.SECONDS));

            for (int i = 0; i < 50; i++) {
                long begin = System.nanoTime();
                scheduler.scheduledProbe();
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 200L,
                        "在途轮次期间 tick 必须立即丢弃");
            }

            release.countDown();
            assertTrue(scheduler.awaitIdle(2L, TimeUnit.SECONDS));
            verify(service, times(1)).probeIfDue("schedule");
        } finally {
            release.countDown();
            scheduler.destroy();
        }
    }

    @Test
    void gateIsReleasedAfterProbeThrows() throws Exception {
        NodeProbeService service = mock(NodeProbeService.class);
        when(service.probeIfDue("schedule"))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(Optional.empty());
        NodeProbeScheduler scheduler = new NodeProbeScheduler(service);
        try {
            scheduler.scheduledProbe();
            assertTrue(scheduler.awaitIdle(2L, TimeUnit.SECONDS), "异常后门控必须释放");
            assertFalse(scheduler.probeRunning());

            scheduler.scheduledProbe();
            assertTrue(scheduler.awaitIdle(2L, TimeUnit.SECONDS));
            verify(service, times(2)).probeIfDue("schedule");
        } finally {
            scheduler.destroy();
        }
    }

    @Test
    void destroyInterruptsInFlightRoundAndStopsFurtherTicks() throws Exception {
        NodeProbeService service = mock(NodeProbeService.class);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(service.probeIfDue("schedule")).thenAnswer(invocation -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            return Optional.empty();
        });
        NodeProbeScheduler scheduler = new NodeProbeScheduler(service);
        scheduler.scheduledProbe();
        assertTrue(started.await(2L, TimeUnit.SECONDS));

        long begin = System.nanoTime();
        scheduler.destroy();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertTrue(elapsedMs < 5_000L, "关闭必须打断在途轮次，实际 " + elapsedMs + " ms");
        assertTrue(scheduler.awaitIdle(2L, TimeUnit.SECONDS));

        scheduler.scheduledProbe();
        Thread.sleep(100L);
        assertEquals(1, calls.get(), "关闭后不得再提交新轮次");
    }

    @Test
    void rejectedSubmissionReleasesTheGate() throws Exception {
        NodeProbeService service = mock(NodeProbeService.class);
        NodeProbeScheduler scheduler = new NodeProbeScheduler(service);
        try {
            Field field = NodeProbeScheduler.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((ExecutorService) field.get(scheduler)).shutdown();

            scheduler.scheduledProbe();

            assertFalse(scheduler.probeRunning(), "执行器拒绝任务时必须把门控还回去，否则永远不再探测");
            verifyNoInteractions(service);
        } finally {
            scheduler.destroy();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
