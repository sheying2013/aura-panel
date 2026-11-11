package com.admin.common.nodehealth;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 关停语义：{@code shutdownNow()} 打断探测线程后，探测必须「停」而不是「继续逐个空等」，也不能把
 * 被打断的这一轮记成完成。
 *
 * <p>三道证据：① {@code send_msg} 被中断时恢复中断标记（否则中断被吞掉，上层完全不知道要停）；
 * ② 中断后不再探测后面的节点，被打断的那次等待记 UNKNOWN 而不是诬陷节点降级；
 * ③ 这一轮不写 {@code lastCompletedAt}，但已落盘的预占保留——不重复打点，也不假装成功。
 */
class NodeProbeInterruptTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;
    private static final long ONE_HOUR = 60L * 60L * 1000L;
    private static final long NODE_ID = 424242L;

    @Test
    void sendMsgRestoresInterruptAndDropsThePendingRequest() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("interrupt-node-" + NODE_ID);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        Map<Long, WebSocketSession> nodeSessions = nodeSessions();
        Map<String, CompletableFuture<GostDto>> pending = pendingRequests();
        List<String> before = new ArrayList<>(pending.keySet());
        nodeSessions.put(NODE_ID, session);

        AtomicReference<GostDto> result = new AtomicReference<>();
        AtomicBoolean interruptFlagKept = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("port", 0);
                result.set(WebSocketServer.send_msg(NODE_ID, payload, "TcpPing"));
            } finally {
                interruptFlagKept.set(Thread.currentThread().isInterrupted());
            }
        }, "send-msg-interrupt-test");

        try {
            worker.start();
            assertTrue(awaitPending(pending, before, 5_000L), "send_msg 必须先挂上等待，才能验证中断路径");
            worker.interrupt();
            worker.join(5_000L);

            assertFalse(worker.isAlive(), "被中断的 send_msg 必须马上返回，不能硬等 10 秒超时");
            assertTrue(interruptFlagKept.get(), "中断标记必须恢复，调用方才知道这一轮要停");
            assertNotNull(result.get());
            assertTrue(result.get().getMsg().contains("中断"),
                    "要说清是被中断，而不是「节点没回包」：" + result.get().getMsg());
            assertEquals(new java.util.HashSet<>(before), new java.util.HashSet<>(pending.keySet()),
                    "等待表不能残留 requestId");
        } finally {
            nodeSessions.remove(NODE_ID);
            worker.interrupt();
        }
    }

    @Test
    void interruptStopsProbingFurtherNodesAndDoesNotMarkTheRoundComplete() throws Exception {
        Path stateFile = newWorkDir("interrupt-stop-").resolve("node-probe-state.json");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(stateFile);
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        AtomicInteger probes = new AtomicInteger();
        when(transport.probe(anyLong())).thenAnswer(invocation -> {
            probes.incrementAndGet();
            // shutdownNow 的效果：等待回包时被中断，send_msg 恢复标记后把结果抛回上层
            Thread.currentThread().interrupt();
            return NodeProbeTransport.Reply.noReply("发送消息失败: 等待响应被中断");
        });

        NodeProbeService service = new NodeProbeService(nodeService(1L, 2L, 3L), transport, id -> true,
                new MutableClock(T0), INTERVAL, FRESH, store);

        AtomicBoolean interruptFlagKept = new AtomicBoolean();
        NodeProbeService.ProbeRound round = runAware(() -> service.probeIfDue("shutdown").orElse(null),
                interruptFlagKept);

        assertNotNull(round, "被打断的一轮也要留下摘要，界面才能说清「只测了一部分」");
        assertTrue(round.interrupted(), "摘要必须标明这一轮被中断，不能算完整成功");
        assertEquals(1, round.total(), "中断后不得继续探测后面的节点");
        assertEquals(1, probes.get(), "只允许探测被打断的那一个节点");
        assertTrue(interruptFlagKept.get(), "中断标记必须保持，调度层才知道要停");

        assertEquals(NodeProbeStatus.UNKNOWN, service.effective(1L).status(),
                "被打断的等待是我方关停造成的，不能记成节点降级：" + service.effective(1L).detail());
        assertFalse(service.hasResult(2L), "后面的节点根本没探测，不能凭空给结论");
        assertFalse(service.hasResult(3L));

        NodeProbeState persisted = store.load();
        assertEquals(T0, persisted.lastAttemptAt(), "预占必须保留，否则重启/下一轮会重复打点");
        assertEquals(0L, persisted.lastCompletedAt(), "被打断不能写成完成，否则等于谎称整轮成功");
        assertTrue(service.stateHealth().ok(), "写盘本身是成功的，健康态不该被中断污染");

        assertTrue(service.probeIfDue("schedule").isEmpty(), "预占窗口仍然有效，本轮不允许再来一次");
        verify(transport, times(1)).probe(anyLong());
    }

    @Test
    void interruptBeforeTheFirstNodeProbesNothing() throws Exception {
        InMemoryNodeProbeStateStore store = new InMemoryNodeProbeStateStore();
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        NodeProbeService service = new NodeProbeService(nodeService(1L, 2L), transport, id -> true,
                new MutableClock(T0), INTERVAL, FRESH, store);

        AtomicBoolean interruptFlagKept = new AtomicBoolean();
        NodeProbeService.ProbeRound round = runAware(() -> {
            Thread.currentThread().interrupt();
            return service.probeIfDue("shutdown").orElse(null);
        }, interruptFlagKept);

        assertNotNull(round);
        assertTrue(round.interrupted());
        assertEquals(0, round.total(), "一开始就被打断，一个节点都不该探测");
        verifyNoInteractions(transport);
        assertEquals(0L, store.load().lastCompletedAt(), "被打断的一轮不算完成");
        assertEquals(T0, store.load().lastAttemptAt(), "但预占仍然算数");
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
        }, "node-probe-interrupt-test");
        worker.start();
        worker.join(10_000L);
        assertFalse(worker.isAlive(), "探测线程不能挂住");
        if (error.get() != null) {
            throw new AssertionError("探测线程抛异常", error.get());
        }
        return result.get();
    }

    private static boolean awaitPending(Map<String, CompletableFuture<GostDto>> pending,
                                        List<String> before, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (pending.size() != before.size()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return false;
    }

    private static NodeService nodeService(Long... ids) {
        NodeService nodeService = mock(NodeService.class);
        List<Node> nodes = java.util.Arrays.stream(ids).map(NodeProbeInterruptTest::node).toList();
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

    @SuppressWarnings("unchecked")
    private static Map<Long, WebSocketSession> nodeSessions() throws Exception {
        Field field = WebSocketServer.class.getDeclaredField("nodeSessions");
        field.setAccessible(true);
        return (Map<Long, WebSocketSession>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CompletableFuture<GostDto>> pendingRequests() throws Exception {
        Field field = WebSocketServer.class.getDeclaredField("pendingRequests");
        field.setAccessible(true);
        return (Map<String, CompletableFuture<GostDto>>) field.get(null);
    }

    private static Path newWorkDir(String prefix) throws IOException {
        Path base = Paths.get("target", "nodehealth-interrupt-tmp").toAbsolutePath();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, prefix);
    }

    private static final class MutableClock implements LongSupplier {

        private volatile long now;

        MutableClock(long now) {
            this.now = now;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }
}
