package com.admin.common.nodehealth;

import com.admin.entity.Node;
import com.admin.service.NodeService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 探测节奏回归测试：原缺陷是「固定延时调度 = 进程内计时」，重启一次就把 12 小时清零，
 * 反复重启就变成反复探测。
 *
 * <p>锁死四条：
 * ① 节流依据是落盘账本，重启后仍按上次尝试时间计算；
 * ② 探测中途被杀也占掉这个窗口，不对节点重复打点；
 * ③ 并发触发只跑一轮；
 * ④ 读节点列表失败/写账本失败都不算「测过」，不能悄悄吃掉 12 小时。
 */
class NodeProbeServiceCadenceTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;
    private static final long ONE_HOUR = 60L * 60L * 1000L;

    @Test
    void restartKeepsTheTwelveHourWindow() throws Exception {
        Path dir = newWorkDir("cadence-restart-");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(dir.resolve("node-probe-state.json"));
        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Collections.singletonList(node(1L)));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(5L, "OK"));

        NodeProbeService first = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);
        assertTrue(first.probeIfDue("boot").isPresent(), "首次运行没有账本，应当探测");
        NodeProbeService.ProbeRound persisted = first.lastRound();
        assertNotNull(persisted, "跑完一轮就该有摘要");

        clock.advance(ONE_HOUR);
        NodeProbeService restarted = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);

        assertFalse(restarted.probeIfDue("boot").isPresent(), "重启不得把 12 小时节流清零");
        assertNotNull(restarted.lastRound(), "重启后要恢复账本里那一轮真实的摘要，不能显示成没有跑过");
        assertEquals(persisted, restarted.lastRound(),
                "恢复的必须是落盘的那一轮：起止时间与各状态计数原样，不是重启后新造的");
        assertEquals(Long.valueOf(T0 + INTERVAL), restarted.nextDueAt(), "重启后应沿用账本里的上次尝试时间");
        verify(transport, times(1)).probe(1L);

        clock.advance(INTERVAL);
        assertTrue(restarted.probeIfDue("boot").isPresent(), "真正到点后必须能再测");
        verify(transport, times(2)).probe(1L);
    }

    @Test
    void roundKilledMidwayStillConsumesTheWindow() throws Exception {
        Path dir = newWorkDir("cadence-crash-");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(dir.resolve("node-probe-state.json"));
        store.save(new NodeProbeState(T0, 0L, "schedule"));

        MutableClock clock = new MutableClock(T0 + ONE_HOUR);
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Collections.singletonList(node(1L)));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);

        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);

        assertFalse(service.probeIfDue("schedule").isPresent(),
                "上次是在探测中被杀（只有预占、没有完成时间），这个窗口已经用过了");
        verifyNoInteractions(transport);
    }

    @Test
    void concurrentTriggersRunOnlyOneRound() throws Exception {
        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Collections.singletonList(node(1L)));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenAnswer(invocation -> {
            Thread.sleep(50L);
            return NodeProbeTransport.Reply.ok(5L, "OK");
        });

        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH,
                new InMemoryNodeProbeStateStore());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.probeIfDue("concurrent").isPresent();
                }));
            }
            start.countDown();
            int ran = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10L, TimeUnit.SECONDS)) {
                    ran++;
                }
            }
            assertEquals(1, ran, "并发触发只能有一轮真正探测");
        } finally {
            pool.shutdownNow();
        }
        verify(transport, times(1)).probe(1L);
    }

    @Test
    void nodeListFailureDoesNotConsumeTheWindow() {
        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenThrow(new IllegalStateException("db down"));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH,
                new InMemoryNodeProbeStateStore());

        assertThrows(NodeHealthDataException.class, () -> service.probeIfDue("schedule"));

        // 上一次是 thenThrow，直接 when(mock.list()) 会把异常抛在 when(...) 里，必须用 doReturn
        doReturn(Collections.singletonList(node(1L))).when(nodeService).list();
        doReturn(NodeProbeTransport.Reply.ok(5L, "OK")).when(transport).probe(1L);
        clock.advance(1_000L);

        assertTrue(service.probeIfDue("schedule").isPresent(), "根本没探测过，不该占掉这 12 小时");
    }

    @Test
    void stateWriteFailureAbandonsTheRoundWithoutProbing() {
        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Collections.singletonList(node(1L)));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        NodeProbeStateStore broken = new NodeProbeStateStore() {
            @Override
            public NodeProbeState load() {
                return NodeProbeState.empty();
            }

            @Override
            public void save(NodeProbeState state) {
                throw new NodeProbeStateException("disk full");
            }
        };

        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, broken);

        assertEquals(Optional.empty(), service.probeIfDue("schedule"),
                "记不了账就不能探测：保证不了「12 小时最多一次」时宁可没有新结论");
        verifyNoInteractions(transport);
        assertNull(service.lastRound());
    }

    @Test
    void defaultStateFileSitsNextToTheDatabase() {
        assertEquals(Paths.get("/app/data/node-probe-state.json").toAbsolutePath(),
                NodeHealthConfig.defaultStateFile("/app/data/gost.db"));
    }

    private static Path newWorkDir(String prefix) throws IOException {
        Path base = Paths.get("target", "nodehealth-verify-tmp").toAbsolutePath();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, prefix);
    }

    private static Node node(Long id) {
        Node node = new Node();
        node.setId(id);
        node.setName("node-" + id);
        node.setStatus(1);
        return node;
    }

    private static final class MutableClock implements LongSupplier {

        private volatile long now;

        MutableClock(long now) {
            this.now = now;
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
