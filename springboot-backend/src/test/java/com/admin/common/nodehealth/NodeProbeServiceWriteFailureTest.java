package com.admin.common.nodehealth;

import com.admin.entity.Node;
import com.admin.service.NodeService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 账本「写不进去」必须被界面看见，而且恢复不能只凭一次读成功。
 *
 * <p>原缺陷：{@code save(reserved)} 抛异常只写一行日志，{@code stateHealth} 仍然是 healthy，
 * 摘要里一片正常；而恢复只做一次 {@code load()} 就宣布健康——磁盘满、只读挂载恰恰是
 * 「读得出来、写不进去」的形态，于是每个 tick 都在「假装健康 → 预占写失败 → 放弃探测」之间循环。
 *
 * <p>这里锁死三条：① 预占写失败 → 状态不健康且带原因；② 只读成功不恢复，必须写验证也过；
 * ③ 完成时间写失败也算不健康，而已落盘的预占仍然吃掉这个窗口（不得因为写失败就重复打点）。
 */
class NodeProbeServiceWriteFailureTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;
    private static final long ONE_HOUR = 60L * 60L * 1000L;

    @Test
    void reservationWriteFailureIsVisibleInsteadOfSilentlyHealthy() throws Exception {
        Path stateFile = newWorkDir("write-fail-reserve-").resolve("node-probe-state.json");
        FlakyStore store = new FlakyStore(new FileNodeProbeStateStore(stateFile));
        store.failEverySave();
        NodeProbeTransport transport = mock(NodeProbeTransport.class);

        NodeProbeService service = new NodeProbeService(nodeService(1L), transport, id -> true,
                new MutableClock(T0), INTERVAL, FRESH, store);

        assertTrue(service.probeIfDue("schedule").isEmpty(), "记不了账就不能探测");
        verifyNoInteractions(transport);
        assertNull(service.lastRound());
        assertNull(service.lastProbeAt(), "预占没落盘，就不存在「上次探测时间」");

        NodeProbeService.StateHealth health = service.stateHealth();
        assertFalse(health.ok(), "写盘失败必须体现在健康态上，否则界面无法提示持久化异常");
        assertEquals(NodeProbeService.StateFailureKind.WRITE, health.kind(), "要能区分「写不进去」和「读不出来」");
        assertTrue(health.error().contains("disk full"), "原因要能直接展示：" + health.error());
        assertFalse(Files.exists(stateFile), "写失败时不该留下半截账本");
    }

    @Test
    void readSuccessAloneDoesNotRestoreWritability() {
        MutableClock clock = new MutableClock(T0);
        FlakyStore store = new FlakyStore(new InMemoryNodeProbeStateStore());
        store.failEverySave();
        NodeProbeTransport transport = mock(NodeProbeTransport.class);

        NodeProbeService service = new NodeProbeService(nodeService(1L), transport, id -> true,
                clock, INTERVAL, FRESH, store);

        assertTrue(service.probeIfDue("schedule").isEmpty());
        assertFalse(service.stateHealth().ok());

        clock.advance(ONE_HOUR);
        int loadsBefore = store.loadCalls.get();
        assertTrue(service.probeIfDue("schedule").isEmpty(), "写不进去时恢复不能成立");
        assertTrue(store.loadCalls.get() > loadsBefore, "恢复必须先读出来");
        assertTrue(store.saveCalls.get() > 0, "读完必须再写一次验证可写性");
        verifyNoInteractions(transport);
        assertFalse(service.stateHealth().ok(), "只凭 read 成功不能谎称可写");
        assertEquals(NodeProbeService.StateFailureKind.WRITE, service.stateHealth().kind());
        assertNull(service.lastRound(), "恢复不成立时不得探测");

        // 真的修好了：写验证通过才回到健康，并且因为没有落盘记录，本轮就是到期轮次
        store.healSaves();
        clock.advance(NodeProbeService.STATE_RECOVERY_INTERVAL_MS);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(7L, "OK"));

        assertTrue(service.probeIfDue("schedule").isPresent(), "读写都通过后要恢复探测");
        assertTrue(service.stateHealth().ok(), "恢复后状态必须回到健康");
        assertNull(service.stateHealth().error());
        verify(transport, times(1)).probe(1L);
    }

    @Test
    void completionWriteFailureIsVisibleAndKeepsThePersistedReservation() throws Exception {
        Path stateFile = newWorkDir("write-fail-complete-").resolve("node-probe-state.json");
        FlakyStore store = new FlakyStore(new FileNodeProbeStateStore(stateFile));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(9L, "OK"));

        MutableClock clock = new MutableClock(T0);
        NodeProbeService service = new NodeProbeService(nodeService(1L), transport, id -> true,
                clock, INTERVAL, FRESH, store);

        store.failFromSave(2); // 第 1 次 = 预占，写成功；第 2 次 = 完成时间，写失败
        assertTrue(service.probeIfDue("schedule").isPresent(), "预占成功就该真的探测");
        verify(transport, times(1)).probe(1L);

        assertFalse(service.stateHealth().ok(), "完成时间写失败同样要暴露，跨重启节流已经不可信");
        assertEquals(NodeProbeService.StateFailureKind.WRITE, service.stateHealth().kind());
        assertTrue(service.stateHealth().error().contains("disk full"));

        NodeProbeState persisted = store.load();
        assertEquals(T0, persisted.lastAttemptAt(), "预占必须留在盘上，否则重启后会重复打点");
        assertEquals(0L, persisted.lastCompletedAt(), "没写成功就不能假装这一轮完成了");

        // 修好之后：恢复只做读+写验证，本进程内仍按已落盘的预占节流，不重复探测
        store.healSaves();
        clock.advance(1_000L);
        assertTrue(service.probeIfDue("schedule").isEmpty(), "退避没到期之前不再去戳账本");
        assertFalse(service.stateHealth().ok(), "还没验证过可写，不能提前宣布健康");

        clock.advance(NodeProbeService.STATE_RECOVERY_INTERVAL_MS);
        assertTrue(service.probeIfDue("schedule").isEmpty(), "窗口已经被预占吃掉了，不能重复打点");
        assertTrue(service.stateHealth().ok(), "写恢复可用后健康态要回到正常");
        verify(transport, times(1)).probe(1L);

        clock.advance(INTERVAL);
        assertTrue(service.probeIfDue("schedule").isPresent(), "真正到点后必须继续探测");
        verify(transport, times(2)).probe(1L);
    }

    private static NodeService nodeService(Long... ids) {
        NodeService nodeService = mock(NodeService.class);
        List<Node> nodes = java.util.Arrays.stream(ids).map(NodeProbeServiceWriteFailureTest::node).toList();
        when(nodeService.list()).thenReturn(nodes);
        return nodeService;
    }

    private static Node node(Long id) {
        Node node = new Node();
        node.setId(id);
        node.setName("node-" + id);
        node.setStatus(1);
        return node;
    }

    private static Path newWorkDir(String prefix) throws IOException {
        Path base = Paths.get("target", "nodehealth-guard-tmp").toAbsolutePath();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, prefix);
    }

    /** 能单独让「第 N 次之后的写」失败的账本，模拟磁盘满/只读挂载：读正常、写抛错。 */
    private static final class FlakyStore implements NodeProbeStateStore {

        private final NodeProbeStateStore delegate;
        private final AtomicInteger loadCalls = new AtomicInteger();
        private final AtomicInteger saveCalls = new AtomicInteger();
        private volatile int failFromSave = Integer.MAX_VALUE;

        FlakyStore(NodeProbeStateStore delegate) {
            this.delegate = delegate;
        }

        void failEverySave() {
            this.failFromSave = 1;
        }

        void failFromSave(int oneBasedCall) {
            this.failFromSave = oneBasedCall;
        }

        void healSaves() {
            this.failFromSave = Integer.MAX_VALUE;
        }

        @Override
        public NodeProbeState load() {
            loadCalls.incrementAndGet();
            return delegate.load();
        }

        @Override
        public void save(NodeProbeState state) {
            int call = saveCalls.incrementAndGet();
            if (call >= failFromSave) {
                throw new NodeProbeStateException("disk full");
            }
            delegate.save(state);
        }
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
