package com.admin.common.nodehealth;

import com.admin.entity.Node;
import com.admin.service.NodeService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 账本读取失败必须 fail-closed：读不出来 ≠ 从未探测，更不能因为读不出来就立刻对全部节点重打一遍。
 *
 * <p>同时锁死三点：启动不因账本损坏失败；账本修好后自动恢复并沿用落盘窗口；恢复重试不能每 tick 都读盘。
 */
class NodeProbeServiceStateRecoveryTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;
    private static final long ONE_HOUR = 60L * 60L * 1000L;

    @Test
    void malformedLedgerFailsClosedThenRecoversAndRespectsStoredWindow() throws Exception {
        Path dir = newWorkDir("recovery-malformed-");
        Path stateFile = dir.resolve("node-probe-state.json");
        Files.writeString(stateFile, "{not-json", StandardCharsets.UTF_8);
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(stateFile);
        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = nodeService();
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(5L, "OK"));

        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);

        assertFalse(service.stateHealth().ok(), "账本畸形时启动必须标记为不健康");
        assertNotNull(service.stateHealth().error(), "摘要要能显示具体原因");
        assertFalse(service.probeIfDue("schedule").isPresent(), "账本不可信时不能探测");
        verifyNoInteractions(transport);

        Files.writeString(stateFile,
                "{\"schemaVersion\":1,\"lastAttemptAt\":" + T0 + ",\"lastCompletedAt\":" + T0
                        + ",\"lastTrigger\":\"boot\"}", StandardCharsets.UTF_8);
        clock.advance(ONE_HOUR);

        assertFalse(service.probeIfDue("schedule").isPresent(), "恢复后要沿用落盘的上次尝试时间");
        assertTrue(service.stateHealth().ok(), "账本恢复可读后状态要回到健康");
        assertNull(service.stateHealth().error());
        assertEquals(Long.valueOf(T0 + INTERVAL), service.nextDueAt());
        verifyNoInteractions(transport);

        clock.advance(INTERVAL);
        assertTrue(service.probeIfDue("schedule").isPresent(), "真正到点后必须恢复探测");
        verify(transport, times(1)).probe(1L);
    }

    @Test
    void recoveryRetryIsThrottledWhileStillUnreadable() {
        MutableClock clock = new MutableClock(T0);
        AtomicInteger loads = new AtomicInteger();
        NodeProbeStateStore broken = new NodeProbeStateStore() {
            @Override
            public NodeProbeState load() {
                loads.incrementAndGet();
                return new NodeProbeState(clock.getAsLong(), 0L, FileNodeProbeStateStore.LOAD_ERROR_PREFIX + "boom");
            }

            @Override
            public void save(NodeProbeState state) {
                throw new NodeProbeStateException("should not save while unreadable");
            }
        };
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        NodeProbeService service = new NodeProbeService(nodeService(), transport, id -> true, clock, INTERVAL, FRESH, broken);
        assertEquals(1, loads.get(), "构造时读一次");

        assertFalse(service.probeIfDue("schedule").isPresent());
        assertEquals(2, loads.get(), "首个 tick 立即重试一次");

        assertFalse(service.probeIfDue("schedule").isPresent());
        assertEquals(2, loads.get(), "恢复失败后不得每个 tick 都读盘");
        assertFalse(service.stateHealth().ok());

        clock.advance(NodeProbeService.STATE_RECOVERY_INTERVAL_MS);
        assertFalse(service.probeIfDue("schedule").isPresent());
        assertEquals(3, loads.get(), "退避到期后才再试");
        assertFalse(service.stateHealth().ok());
    }

    @Test
    void throwingStoreIsFailClosedAndDoesNotBreakStartup() {
        NodeProbeStateStore throwing = new NodeProbeStateStore() {
            @Override
            public NodeProbeState load() {
                throw new IllegalStateException("permission denied");
            }

            @Override
            public void save(NodeProbeState state) {
                throw new NodeProbeStateException("nope");
            }
        };
        NodeProbeTransport transport = mock(NodeProbeTransport.class);

        NodeProbeService service = new NodeProbeService(nodeService(), transport, id -> true,
                new MutableClock(T0), INTERVAL, FRESH, throwing);

        assertFalse(service.stateHealth().ok());
        assertTrue(service.stateHealth().error().contains("permission denied"));
        assertFalse(service.probeIfDue("schedule").isPresent());
        verifyNoInteractions(transport);
    }

    @Test
    void directoryLedgerIsFailClosedButAppStillUsable() throws Exception {
        Path dir = newWorkDir("recovery-directory-");
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        NodeProbeService service = new NodeProbeService(nodeService(), transport, id -> true,
                new MutableClock(T0), INTERVAL, FRESH, new FileNodeProbeStateStore(dir));

        assertFalse(service.stateHealth().ok());
        assertTrue(service.stateHealth().checkedAt() > 0L);
        assertFalse(service.probeIfDue("schedule").isPresent());
        assertNull(service.lastRound());
        verifyNoInteractions(transport);
    }

    private static NodeService nodeService() {
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Collections.singletonList(node(1L)));
        return nodeService;
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
