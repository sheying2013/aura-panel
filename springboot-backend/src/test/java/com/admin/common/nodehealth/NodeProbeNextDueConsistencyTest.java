package com.admin.common.nodehealth;

import com.admin.entity.Node;
import com.admin.service.NodeService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「界面预计下一次探测时间」必须与真实节流点同源。
 *
 * <p>节流判据只有一条：距持久化的 {@code lastAttemptAt} 不足一个间隔就跳过（见
 * {@link NodeProbeService#probeIfDue(String)}）。预占写在探测开始之前
 * （{@link NodeProbeState#reserved(long, String)}），完成时间 = 预占时间 + 单轮耗时。所以
 * {@link NodeProbeService#nextDueAt()} 只能取 {@code lastAttemptAt + intervalMs}：一旦改用完成时间
 * 去推算，界面就会报出一个比真正放行时刻晚「单轮耗时」的时刻——看着还没到点，调度器其实已经开跑，
 * 单轮越慢偏差越大（历史缺陷：单轮 37 分钟即偏差 37 分钟，且每轮都重复这一偏差）。
 *
 * <p>四个用例分别锁：慢轮次下界面时刻 == 放行时刻、中断留下的预占就是解禁时刻、旧账本（无摘要）
 * 仍按尝试时间推算、从未探测过时不编造时间。
 *
 * <p>时间全部由可变假时钟推进，不 sleep、不读真实时间，结果可重复。
 */
class NodeProbeNextDueConsistencyTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;

    /** 单轮耗时 37 分钟：明显大于 0、又远小于一个间隔，任何「按完成时间推算」的偏差都藏不住。 */
    private static final long ROUND_DURATION = 37L * 60L * 1000L;

    @Test
    void displayedNextDueIsExactlyTheThrottlePointEvenWhenTheRoundIsSlow() {
        MutableClock clock = new MutableClock(T0);
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        // 探测本身消耗 37 分钟：用假时钟把「单轮耗时」显式摆出来
        when(transport.probe(1L)).thenAnswer(invocation -> {
            clock.advance(ROUND_DURATION);
            return NodeProbeTransport.Reply.ok(5L, "OK");
        });
        NodeProbeService service = new NodeProbeService(nodeService(node(1L)), transport, id -> true, clock,
                INTERVAL, FRESH, new InMemoryNodeProbeStateStore());

        assertTrue(service.probeIfDue("schedule").isPresent(), "没有账本时第一轮必须跑");
        NodeProbeService.ProbeRound first = service.lastRound();
        assertNotNull(first, "跑完一轮就该有摘要");
        assertEquals(T0, first.startedAt(), "预占（=开始探测）发生在 T0");
        assertEquals(T0 + ROUND_DURATION, first.finishedAt(), "完成时间 = 预占时间 + 单轮耗时");

        long releaseAt = T0 + INTERVAL;
        assertEquals(Long.valueOf(releaseAt), service.nextDueAt(),
                "界面时间必须等于真实放行时刻（上次尝试 + interval），不能被单轮耗时推后");

        clock.set(releaseAt - 1L);
        assertFalse(service.probeIfDue("schedule").isPresent(), "差 1 ms 到节流点，本轮跳过");
        verify(transport, times(1)).probe(1L);

        clock.set(releaseAt);
        assertTrue(service.probeIfDue("schedule").isPresent(), "到点即放行，与界面显示的时刻一致");
        verify(transport, times(2)).probe(1L);

        NodeProbeService.ProbeRound second = service.lastRound();
        assertNotNull(second);
        assertEquals(releaseAt, second.startedAt(), "第二轮预占正好落在上一轮报出的时间点");
        assertEquals(Long.valueOf(T0 + 2 * INTERVAL), service.nextDueAt(),
                "下一轮继续按预占时间推进：慢轮次不会把计划逐轮顶后");
    }

    @Test
    void interruptedRoundReservationIsTheDisplayedNextDue() throws Exception {
        Path dir = newWorkDir("nextdue-interrupted-");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(dir.resolve("node-probe-state.json"));
        MutableClock clock = new MutableClock(T0);
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        AtomicBoolean interruptOnce = new AtomicBoolean(true);
        when(transport.probe(anyLong())).thenAnswer(invocation -> {
            if (interruptOnce.getAndSet(false)) {
                Thread.currentThread().interrupt();
            }
            return NodeProbeTransport.Reply.ok(5L, "OK");
        });
        NodeProbeService service = new NodeProbeService(nodeService(node(1L), node(2L)), transport, id -> true,
                clock, INTERVAL, FRESH, store);

        NodeProbeService.ProbeRound half = runAware(() -> service.probeIfDue("shutdown").orElseThrow());
        assertTrue(half.interrupted(), "线程被打断就该标记为中断");
        assertNull(service.lastRound(), "中断轮不是完整轮次，不得作为上一轮摘要展示");

        long releaseAt = T0 + INTERVAL;
        assertEquals(Long.valueOf(releaseAt), service.nextDueAt(),
                "中断留下的预占就是解禁时刻：界面不能因为「没有完成时间」而报 null 或更早的时间");

        clock.set(releaseAt - 1L);
        assertFalse(service.probeIfDue("schedule").isPresent(), "预占窗口未到点，本轮跳过");
        clock.set(releaseAt);
        assertTrue(service.probeIfDue("schedule").isPresent(), "到点即放行，与界面一致");
    }

    @Test
    void legacyLedgerWithoutRoundReportsTheSameThrottlePoint() throws Exception {
        Path dir = newWorkDir("nextdue-legacy-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"lastAttemptAt\":" + (T0 - INTERVAL)
                + ",\"lastCompletedAt\":" + T0 + ",\"lastTrigger\":\"boot\",\"results\":[]}",
                StandardCharsets.UTF_8);
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        NodeProbeService service = new NodeProbeService(nodeService(node(1L)), transport, id -> true,
                new MutableClock(T0 - 1L), INTERVAL, FRESH, store);

        assertNull(service.lastRound(), "老账本没有摘要就如实返回 null");
        assertEquals(Long.valueOf(T0), service.nextDueAt(), "没有摘要时同样只能推算到「上次尝试 + 间隔」");
        assertFalse(service.probeIfDue("schedule").isPresent(), "差 1 ms 到节流点，沿用老账本的窗口");
        verifyNoInteractions(transport);
    }

    @Test
    void neverProbedReportsNoNextDue() {
        NodeProbeService service = new NodeProbeService(nodeService(node(1L)), mock(NodeProbeTransport.class),
                id -> true, new MutableClock(T0), INTERVAL, FRESH, new InMemoryNodeProbeStateStore());

        assertNull(service.nextDueAt(), "从未探测过就没有「下一轮」，不编造时间");
    }

    private static Node node(Long id) {
        Node node = new Node();
        node.setId(id);
        node.setName("node-" + id);
        node.setStatus(1);
        return node;
    }

    private static NodeService nodeService(Node... nodes) {
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(List.of(nodes));
        return nodeService;
    }

    private static Path newWorkDir(String prefix) throws IOException {
        Path base = Paths.get("target", "nodehealth-nextdue-tmp").toAbsolutePath();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, prefix);
    }

    /** 在工作线程里跑，才能既观察中断标记又不在测试主线程上留标记。 */
    private static NodeProbeService.ProbeRound runAware(Callable<NodeProbeService.ProbeRound> task) throws Exception {
        AtomicReference<NodeProbeService.ProbeRound> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                interruptKept.set(Thread.currentThread().isInterrupted());
            }
        }, "node-probe-nextdue-test");
        worker.start();
        worker.join(10_000L);
        assertFalse(worker.isAlive(), "探测线程不能挂住");
        assertTrue(interruptKept.get(), "中断标记应留在工作线程上，不影响测试主线程");
        if (error.get() != null) {
            throw new AssertionError("探测线程抛异常", error.get());
        }
        return result.get();
    }

    /** 假时钟：单轮耗时靠 {@link #advance(long)} 显式推进，不依赖真实时间。 */
    private static final class MutableClock implements LongSupplier {

        private volatile long now;

        MutableClock(long now) {
            this.now = now;
        }

        void set(long at) {
            this.now = at;
        }

        void advance(long delta) {
            this.now += delta;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }
}
