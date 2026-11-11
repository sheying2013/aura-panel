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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 结论持久化回归测试。
 *
 * <p>原缺陷：账本只记轮次时间、逐节点结论只在内存里，于是<b>每次重启</b>之后到下一轮之间（最长一个间隔），
 * 面板上所有节点都只能报「未知」——一天两次巡检，一次重启就等于丢掉半天的可见性。
 *
 * <p>这里锁死五条，都是「宁可未知也不能谎称在线」的边界：
 * ① 完整跑完的一轮，结论与节流时间同一次原子写落盘，重启后原样复用（检测时间不改写）；
 * ② 旧账本（没有 results 字段）仍然只按节流时间生效，结论为空 → 未知，且不被判成账本损坏；
 * ③ 结论集坏掉、单条记录形态不可能（在线却没有往返耗时、时间戳在未来、陌生状态码）一律丢弃 → 未知；
 * ④ 节点被删后重建、ID 被复用时不继承上一个节点的在线结论；
 * ⑤ 半轮（被中断）与「完成时间没写进盘」的轮次都不落结论，重启后只能报未知。
 */
class NodeProbeResultPersistenceTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;
    private static final long ONE_HOUR = 60L * 60L * 1000L;

    @Test
    void resultsSurviveRestartSoNodesStopReportingUnknown() throws Exception {
        Path file = newWorkDir("results-restart-").resolve("node-probe-state.json");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);
        Node online = node(1L, "hk-entry", 111L);
        Node offline = node(2L, "us-exit", 222L);
        NodeService nodeService = nodeService(online, offline);
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(12L, "OK"));
        MutableClock clock = new MutableClock(T0);

        NodeProbeService first = new NodeProbeService(nodeService, transport, id -> id == 1L, clock, INTERVAL, FRESH, store);
        assertTrue(first.probeIfDue("schedule").isPresent(), "首次运行没有账本，应当探测");

        clock.advance(ONE_HOUR);
        NodeProbeService restarted = new NodeProbeService(nodeService, transport, id -> id == 1L, clock, INTERVAL, FRESH, store);

        NodeProbeResult onlineResult = restarted.effective(1L, NodeProbeService.identityOf(online));
        assertEquals(NodeProbeStatus.ONLINE, onlineResult.status(), "重启后必须沿用落盘的在线结论");
        assertEquals(T0, onlineResult.checkedAt(), "检测时间要跟着结论一起恢复，不能改写成重启时间");
        assertEquals(12L, onlineResult.latencyMs());
        assertTrue(onlineResult.controlPlaneReachable());
        assertEquals(NodeProbeStatus.OFFLINE, restarted.effective(2L, NodeProbeService.identityOf(offline)).status(),
                "离线结论同样要跨重启保留");

        assertTrue(restarted.probeIfDue("schedule").isEmpty(), "重启不得把 12 小时节流清零");
    }

    @Test
    void legacyLedgerWithoutResultsStillThrottles() throws Exception {
        Path file = newWorkDir("results-legacy-").resolve("node-probe-state.json");
        Files.writeString(file, """
                {"schemaVersion":1,"lastAttemptAt":%d,"lastCompletedAt":%d,"lastTrigger":"schedule"}
                """.formatted(T0, T0 - 1000L), StandardCharsets.UTF_8);
        MutableClock clock = new MutableClock(T0 + ONE_HOUR);
        NodeProbeTransport transport = mock(NodeProbeTransport.class);

        NodeProbeService service = new NodeProbeService(nodeService(node(1L, "hk-entry", 111L)), transport,
                id -> true, clock, INTERVAL, FRESH, new FileNodeProbeStateStore(file));

        assertTrue(service.stateHealth().ok(), "旧格式没有 results 字段，不该被判成账本损坏");
        assertTrue(service.probeIfDue("schedule").isEmpty(), "旧账本的节流时间仍然有效");
        assertEquals(NodeProbeStatus.UNKNOWN, service.effective(1L).status());
        assertFalse(service.hasResult(1L), "没有结论就是没有结论，不能凭空造一条");
        verifyNoInteractions(transport);
    }

    @Test
    void corruptResultsBlockIsIgnoredInsteadOfFailingClosed() throws Exception {
        Path file = newWorkDir("results-corrupt-").resolve("node-probe-state.json");
        Files.writeString(file, """
                {"schemaVersion":1,"lastAttemptAt":%d,"lastCompletedAt":%d,"lastTrigger":"schedule","results":"oops"}
                """.formatted(T0, T0), StandardCharsets.UTF_8);
        MutableClock clock = new MutableClock(T0 + ONE_HOUR);

        NodeProbeService service = new NodeProbeService(nodeService(node(1L, "hk-entry", 111L)),
                mock(NodeProbeTransport.class), id -> true, clock, INTERVAL, FRESH, new FileNodeProbeStateStore(file));

        assertTrue(service.stateHealth().ok(),
                "坏掉的只是结论集，节流记录仍然可用；把整份账本判成不可读会让巡检白停一个间隔");
        assertTrue(service.probeIfDue("schedule").isEmpty(), "节流时间仍然有效");
        assertEquals(NodeProbeStatus.UNKNOWN, service.effective(1L).status());
    }

    @Test
    void entriesThatCouldNotHaveBeenMeasuredAreDropped() throws Exception {
        Path file = newWorkDir("results-forged-").resolve("node-probe-state.json");
        long future = T0 + ONE_HOUR;
        Files.writeString(file, """
                {"schemaVersion":1,"lastAttemptAt":%d,"lastCompletedAt":%d,"lastTrigger":"schedule","results":[
                  {"nodeId":7,"nodeIdentity":"createdTime=1","status":"ONLINE","checkedAt":%d,"latencyMs":-1,"detail":"online-without-rtt"},
                  {"nodeId":8,"nodeIdentity":"createdTime=2","status":"ONLINE","checkedAt":%d,"latencyMs":5,"detail":"from-the-future"},
                  {"nodeId":9,"nodeIdentity":"createdTime=3","status":"BOGUS","checkedAt":%d,"latencyMs":5,"detail":"unknown-code"},
                  {"nodeId":10,"nodeIdentity":"createdTime=4","status":"ONLINE","checkedAt":%d,"latencyMs":5,"detail":"fine"}
                ]}
                """.formatted(T0, T0, T0, future, T0, T0), StandardCharsets.UTF_8);
        MutableClock clock = new MutableClock(T0);

        NodeProbeService service = new NodeProbeService(nodeService(node(10L, "ok-node", 444L)),
                mock(NodeProbeTransport.class), id -> true, clock, INTERVAL, FRESH, new FileNodeProbeStateStore(file));

        assertEquals(NodeProbeStatus.UNKNOWN, service.effective(7L).status(), "声称在线却拿不出往返耗时，不能认");
        assertFalse(service.hasResult(7L));
        assertEquals(NodeProbeStatus.UNKNOWN, service.effective(8L).status(), "检测时间跑到未来的结论不可信");
        assertFalse(service.hasResult(8L));
        assertFalse(service.hasResult(9L), "陌生状态码要丢弃，不能兜成 UNKNOWN 之后又当成结论");
        assertEquals(NodeProbeStatus.ONLINE, service.effective(10L).status(), "正常形态的结论必须保留");
    }

    @Test
    void reusedNodeIdDoesNotInheritThePreviousOnline() throws Exception {
        Path file = newWorkDir("results-reuse-").resolve("node-probe-state.json");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);
        NodeService nodeService = nodeService(node(5L, "edge-a", 111L));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(5L)).thenReturn(NodeProbeTransport.Reply.ok(8L, "OK"));
        MutableClock clock = new MutableClock(T0);
        NodeProbeService first = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);
        assertTrue(first.probeIfDue("schedule").isPresent());

        // 删掉旧记录、再建一台新机器：node 表的自增 ID 会回到同一个值
        Node newRecord = node(5L, "edge-a", 999L);
        when(nodeService.list()).thenReturn(List.of(newRecord));
        clock.advance(ONE_HOUR);
        NodeProbeService restarted = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);

        NodeProbeResult asNewRecord = restarted.effective(5L, NodeProbeService.identityOf(newRecord));
        assertEquals(NodeProbeStatus.UNKNOWN, asNewRecord.status(), "ID 被复用后不能继承上一台机器的在线结论");
        assertTrue(asNewRecord.detail().contains("作废"), "要能说清为什么是未知：" + asNewRecord.detail());
        assertFalse(asNewRecord.probed(), "作废的结论不该带检测时间，否则会被当成刚测过");

        clock.advance(INTERVAL);
        assertTrue(restarted.probeIfDue("schedule").isPresent(), "到点后必须照常探测");
        NodeProbeResult fresh = restarted.effective(5L, NodeProbeService.identityOf(newRecord));
        assertEquals(NodeProbeStatus.ONLINE, fresh.status(), "新机器自己的结论要能被展示");
        assertEquals(clock.getAsLong(), fresh.checkedAt(), "要用本轮新测出来的结论覆盖旧记录");
    }

    @Test
    void halfRoundIsNotPersistedAsACompleteOne() throws Exception {
        Path file = newWorkDir("results-interrupt-").resolve("node-probe-state.json");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);
        NodeService nodeService = nodeService(node(1L, "hk-entry", 111L), node(2L, "us-exit", 222L));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(anyLong())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return NodeProbeTransport.Reply.noReply("等待响应被中断");
        });
        MutableClock clock = new MutableClock(T0);
        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);

        AtomicBoolean interruptFlagKept = new AtomicBoolean();
        NodeProbeService.ProbeRound round = runAware(() -> service.probeIfDue("shutdown").orElse(null), interruptFlagKept);

        assertNotNull(round);
        assertTrue(round.interrupted());
        assertEquals(1, round.total(), "中断后不得继续探测后面的节点");
        assertTrue(interruptFlagKept.get());

        NodeProbeService restarted = new NodeProbeService(nodeService, mock(NodeProbeTransport.class), id -> true,
                clock, INTERVAL, FRESH, new FileNodeProbeStateStore(file));
        assertEquals(NodeProbeStatus.UNKNOWN, restarted.effective(1L).status(), "半轮的结论不能落盘，重启后只能报未知");
        assertFalse(restarted.hasResult(1L), "没跑完的轮次不能留下结论");
        assertEquals(T0, store.load().lastAttemptAt(), "预占仍然吃掉了这个窗口");
        assertEquals(0L, store.load().lastCompletedAt(), "被打断不能写成完成");
    }

    @Test
    void resultsAreNotPersistedWhenTheCompletionWriteFails() throws Exception {
        Path file = newWorkDir("results-writefail-").resolve("node-probe-state.json");
        FlakyStore store = new FlakyStore(new FileNodeProbeStateStore(file));
        NodeService nodeService = nodeService(node(1L, "hk-entry", 111L));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(4L, "OK"));
        MutableClock clock = new MutableClock(T0);
        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);

        store.failFromSave(2); // 第 1 次 = 预占，第 2 次 = 完成时间（与结论集同一次写）
        assertTrue(service.probeIfDue("schedule").isPresent());
        assertFalse(service.stateHealth().ok(), "完成时间写失败必须暴露出来");

        NodeProbeService restarted = new NodeProbeService(nodeService, mock(NodeProbeTransport.class), id -> true,
                clock, INTERVAL, FRESH, new FileNodeProbeStateStore(file));
        assertEquals(NodeProbeStatus.UNKNOWN, restarted.effective(1L).status(), "没写进盘的结论，重启后不能凭空出现");
        assertTrue(restarted.probeIfDue("schedule").isEmpty(), "预占仍在盘上，窗口不得被重复使用");
    }

    @Test
    void leftoverTempFileDoesNotDisturbTheLedger() throws Exception {
        Path dir = newWorkDir("results-tmpfile-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(dir.resolve("node-probe-state.json.123456.tmp"), "half-written", StandardCharsets.UTF_8);
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);
        Node online = node(3L, "sg-relay", 333L);
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(3L)).thenReturn(NodeProbeTransport.Reply.ok(6L, "OK"));
        MutableClock clock = new MutableClock(T0);

        NodeProbeService first = new NodeProbeService(nodeService(online), transport, id -> true, clock, INTERVAL, FRESH, store);
        assertTrue(first.probeIfDue("schedule").isPresent());

        clock.advance(ONE_HOUR);
        NodeProbeService restarted = new NodeProbeService(nodeService(online), transport, id -> true, clock, INTERVAL, FRESH, store);
        assertTrue(restarted.stateHealth().ok(), "残留的临时文件不该影响正式账本");
        assertEquals(NodeProbeStatus.ONLINE, restarted.effective(3L, NodeProbeService.identityOf(online)).status());
    }

    private static NodeService nodeService(Node... nodes) {
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(List.of(nodes));
        return nodeService;
    }

    private static Node node(Long id, String name, Long createdTime) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setCreatedTime(createdTime);
        node.setStatus(1);
        return node;
    }

    private static Path newWorkDir(String prefix) throws IOException {
        Path base = Paths.get("target", "nodehealth-results-tmp").toAbsolutePath();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, prefix);
    }

    /** 在工作线程里跑，才能既观察中断标记又不在测试主线程上留标记。 */
    private static NodeProbeService.ProbeRound runAware(Callable<NodeProbeService.ProbeRound> task,
                                                        AtomicBoolean interruptFlagKept) throws Exception {
        AtomicReference<NodeProbeService.ProbeRound> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                interruptFlagKept.set(Thread.currentThread().isInterrupted());
            }
        }, "node-probe-results-test");
        worker.start();
        worker.join(10_000L);
        assertFalse(worker.isAlive(), "探测线程不能挂住");
        if (error.get() != null) {
            throw new AssertionError("探测线程抛异常", error.get());
        }
        return result.get();
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

    /** 让「第 N 次之后的写」失败的账本，模拟磁盘满/只读挂载：读正常、写抛错。 */
    private static final class FlakyStore implements NodeProbeStateStore {

        private final NodeProbeStateStore delegate;
        private final AtomicInteger saveCalls = new AtomicInteger();
        private volatile int failFromSave = Integer.MAX_VALUE;

        FlakyStore(NodeProbeStateStore delegate) {
            this.delegate = delegate;
        }

        void failFromSave(int oneBasedCall) {
            this.failFromSave = oneBasedCall;
        }

        @Override
        public NodeProbeState load() {
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
}
