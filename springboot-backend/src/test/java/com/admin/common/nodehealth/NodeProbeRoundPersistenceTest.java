package com.admin.common.nodehealth;

import com.admin.entity.Node;
import com.admin.service.ChainTunnelService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 上一轮摘要（{@link NodeProbeService.ProbeRound}）的持久化与恢复。
 *
 * <p>缺陷现场：真实 Spring + GOST 环境里重启后 {@code summary.lastRound} 变成 null，
 * 而逐节点结论与检测时间都还在——账本只存了「什么时候跑的」和「每台节点什么结论」，
 * 没存那一轮的聚合计数，重启后自然拼不出摘要。
 *
 * <p>这里锁死四条边界：
 * ① 摘要与节流时间、结论集同一次原子写落盘，重启后原样恢复（不是重启后新造一轮）；
 * ② 计数只能来自账本，绝不能从当前节点表或 results 反推——节点增删后重算等于编历史；
 * ③ 老账本没有这个字段、字段块坏掉，都只丢摘要，不把账本判成不可读；
 * ④ 中断的轮次、完成时间写盘失败，都不产生「已完成」的假摘要；恢复旧账本不得盖掉内存里更新的一轮。
 */
class NodeProbeRoundPersistenceTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;
    private static final long ONE_HOUR = 60L * 60L * 1000L;

    // ========== 1) 完整轮次：随账本落盘，重启后原样恢复 ==========

    @Test
    void completedRoundIsPersistedAndRestoredVerbatim() throws Exception {
        Path dir = newWorkDir("round-verbatim-");
        Path file = dir.resolve("node-probe-state.json");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);
        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = nodeService(node(1L, "a", 11L), node(2L, "b", 22L), node(3L, "c", 33L));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(5L, "OK"));
        when(transport.probe(2L)).thenReturn(NodeProbeTransport.Reply.noReply("等待响应超时"));
        when(transport.probe(3L)).thenReturn(NodeProbeTransport.Reply.ok(5L, "OK"));

        NodeProbeService service = new NodeProbeService(nodeService, transport,
                id -> id != 3L, clock, INTERVAL, FRESH, store);
        NodeProbeService.ProbeRound round = service.probeIfDue("schedule").orElseThrow();

        assertEquals(3, round.total());
        assertEquals(1, round.online());
        assertEquals(1, round.degraded());
        assertEquals(1, round.offline());
        assertEquals(0, round.unknown());
        assertEquals(round, store.load().lastRound(), "摘要必须落盘，字段一个不少");

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\":1"), "不能为了新字段升版本号：老程序还要能读");
        assertTrue(json.contains("\"lastRound\""), "摘要写在同一份账本里");

        clock.advance(ONE_HOUR);
        NodeProbeService restarted = new NodeProbeService(nodeService, transport,
                id -> id != 3L, clock, INTERVAL, FRESH, store);

        assertNotNull(restarted.lastRound(), "重启后摘要不能变回 null");
        assertEquals(round, restarted.lastRound(), "恢复的必须是落盘的那一轮：触发源、起止时间、各状态计数原样");
        assertFalse(restarted.probeIfDue("schedule").isPresent(), "恢复摘要不影响节流");
        verify(transport, times(2)).probe(anyLong());
    }

    // ========== 2) 老账本：没有 lastRound 字段，读成「没有摘要」但仍按它节流 ==========

    @Test
    @SuppressWarnings("unchecked")
    void summaryExposesRestoredRoundAfterRestart() throws Exception {
        Path dir = newWorkDir("round-summary-");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(dir.resolve("node-probe-state.json"));
        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = nodeService(node(1L, "a", 11L), node(2L, "b", 22L));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(anyLong())).thenReturn(NodeProbeTransport.Reply.ok(9L, "OK"));
        NodeProbeService.ProbeRound round = new NodeProbeService(nodeService, transport, id -> true, clock,
                INTERVAL, FRESH, store).probeIfDue("schedule").orElseThrow();

        clock.advance(ONE_HOUR);
        NodeProbeService restarted = new NodeProbeService(nodeService, mock(NodeProbeTransport.class), id -> false,
                clock, INTERVAL, FRESH, store);
        NodeHealthViewService view = new NodeHealthViewService(nodeService, mock(TunnelService.class),
                mock(ChainTunnelService.class), restarted);

        Map<String, Object> lastRound = (Map<String, Object>) view.summary().get("lastRound");
        assertNotNull(lastRound, "缺陷现场：重启后 summary.lastRound 不该是 null");
        assertEquals(round.trigger(), lastRound.get("trigger"));
        assertEquals(round.startedAt(), lastRound.get("startedAt"));
        assertEquals(round.finishedAt(), lastRound.get("finishedAt"));
        assertEquals(round.total(), lastRound.get("total"));
        assertEquals(round.online(), lastRound.get("online"));
        assertNotNull(lastRound.get("finishedAtText"), "摘要还要给出可读时间");
    }

    @Test
    void legacyLedgerWithoutRoundStillThrottles() throws Exception {
        Path dir = newWorkDir("round-legacy-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"lastAttemptAt\":" + T0
                + ",\"lastCompletedAt\":" + (T0 - 1_000L) + ",\"lastTrigger\":\"boot\"}", StandardCharsets.UTF_8);
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);

        NodeProbeState loaded = store.load();
        assertFalse(FileNodeProbeStateStore.isLoadError(loaded), "缺新字段不等于账本损坏");
        assertNull(loaded.lastRound(), "老账本没有摘要就如实返回 null，不能补造");

        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        NodeProbeService service = new NodeProbeService(nodeService(node(1L, "a", 11L)), transport,
                id -> true, new MutableClock(T0), INTERVAL, FRESH, store);

        assertNull(service.lastRound());
        assertTrue(service.stateHealth().ok(), "老账本照常可用");
        assertFalse(service.probeIfDue("schedule").isPresent(), "仍沿用老账本的窗口");
        verifyNoInteractions(transport);
        assertEquals(Long.valueOf(T0 + INTERVAL), service.nextDueAt(), "下一轮仍是上次尝试 + 间隔");
    }

    // ========== 3) 摘要块坏掉：只丢摘要，账本照常可读 ==========

    @Test
    void malformedRoundBlockIsDroppedButLedgerStaysUsable() throws Exception {
        String[] brokenRounds = {
                "\"oops\"",
                "{}",
                "{\"trigger\":\"\",\"startedAt\":1,\"finishedAt\":2,\"total\":0,\"online\":0,"
                        + "\"degraded\":0,\"offline\":0,\"unknown\":0,\"interrupted\":false}",
                roundJson("schedule", T0 - 10L, T0, 3, 2, 0, 0, 0, false),
                roundJson("schedule", T0 - 10L, T0, 3, 1, 1, 1, 0, true),
                roundJson("schedule", T0, T0 - 10L, 1, 1, 0, 0, 0, false),
                roundJson("schedule", T0 - 10L, T0, -1, -1, 0, 0, 0, false),
                "{\"trigger\":\"schedule\",\"startedAt\":" + (T0 - 10L) + ",\"finishedAt\":" + T0
                        + ",\"total\":3.5,\"online\":1,\"degraded\":1,\"offline\":1.5,\"unknown\":0,"
                        + "\"interrupted\":false}",
        };
        for (String broken : brokenRounds) {
            Path dir = newWorkDir("round-broken-");
            Path file = dir.resolve("node-probe-state.json");
            Files.writeString(file, "{\"schemaVersion\":1,\"lastAttemptAt\":" + (T0 - INTERVAL)
                    + ",\"lastCompletedAt\":" + T0 + ",\"lastTrigger\":\"schedule\",\"results\":[],"
                    + "\"lastRound\":" + broken + "}", StandardCharsets.UTF_8);
            FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);

            NodeProbeState loaded = store.load();
            assertFalse(FileNodeProbeStateStore.isLoadError(loaded),
                    "坏掉的摘要只能丢摘要，不能把整份账本判成不可读：" + broken);
            assertNull(loaded.lastRound(), "坏数据不得变成历史：" + broken);

            NodeProbeService service = new NodeProbeService(nodeService(node(1L, "a", 11L)),
                    mock(NodeProbeTransport.class), id -> true, new MutableClock(T0), INTERVAL, FRESH, store);
            assertNull(service.lastRound(), "坏摘要不恢复：" + broken);
            assertTrue(service.stateHealth().ok(), "账本仍健康：" + broken);
        }
    }

    // ========== 4) 摘要必须和账本描述的那一轮对得上 ==========

    @Test
    void roundMustMatchLedgerCompletionTime() throws Exception {
        assertRoundNotRestored(T0, roundJson("schedule", T0 - 10L, T0 - 1L, 1, 1, 0, 0, 0, false),
                "完成时间与 lastCompletedAt 不一致的摘要不属于这一轮");
    }

    @Test
    void roundFromTheFutureIsRejected() throws Exception {
        long future = T0 + ONE_HOUR + NodeProbeService.CLOCK_SKEW_MS;
        assertRoundNotRestored(future, roundJson("schedule", T0, future, 1, 1, 0, 0, 0, false),
                "时间戳跑到未来的摘要是坏数据，不能当作上一轮");
    }

    @Test
    void inMemoryRoundMustBeACompleteRound() {
        // 非文件账本（如 InMemoryNodeProbeStateStore）绕过了解析校验，服务层仍要挡住坏轮次
        InMemoryNodeProbeStateStore store = new InMemoryNodeProbeStateStore();
        store.save(new NodeProbeState(T0 - INTERVAL, T0, "schedule", List.of(),
                new NodeProbeService.ProbeRound("schedule", T0 - 10L, T0, 2, 2, 0, 0, 0, true)));

        NodeProbeService interrupted = new NodeProbeService(nodeService(node(1L, "a", 11L)),
                mock(NodeProbeTransport.class), id -> true, new MutableClock(T0), INTERVAL, FRESH, store);
        assertNull(interrupted.lastRound(), "中断的轮次不能以「已完成」的样子恢复");

        store.save(new NodeProbeState(T0 - INTERVAL, T0, "schedule", List.of(),
                new NodeProbeService.ProbeRound("schedule", T0 - 10L, T0, 3, 2, 0, 0, 0, false)));
        NodeProbeService mismatched = new NodeProbeService(nodeService(node(1L, "a", 11L)),
                mock(NodeProbeTransport.class), id -> true, new MutableClock(T0), INTERVAL, FRESH, store);
        assertNull(mismatched.lastRound(), "各状态计数之和必须等于总数，否则不是一轮真实的聚合");
    }

    // ========== 5) 计数只能来自账本，不能按当前节点表/结论集重算 ==========

    @Test
    void restoredCountsAreNotRecomputedFromCurrentNodes() throws Exception {
        Path dir = newWorkDir("round-no-recompute-");
        Path file = dir.resolve("node-probe-state.json");
        // 账本里那一轮共 3 台：1 正常、1 降级、1 未连接；结论集只剩 node 2 一条（其余已被裁剪）
        String results = "[{\"nodeId\":2,\"nodeIdentity\":\"createdTime=22\",\"status\":\"DEGRADED\","
                + "\"checkedAt\":" + T0 + ",\"latencyMs\":-1,\"detail\":\"等待响应超时\"}]";
        Files.writeString(file, "{\"schemaVersion\":1,\"lastAttemptAt\":" + (T0 - INTERVAL)
                + ",\"lastCompletedAt\":" + T0 + ",\"lastTrigger\":\"schedule\",\"results\":" + results
                + ",\"lastRound\":" + roundJson("schedule", T0 - 500L, T0, 3, 1, 1, 1, 0, false) + "}",
                StandardCharsets.UTF_8);
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);

        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        // 现在只剩 1 台节点：若从当前节点表重算，total 会变成 1，那就是编历史
        NodeProbeService service = new NodeProbeService(nodeService(node(1L, "a", 11L)), transport,
                id -> true, new MutableClock(T0), INTERVAL, FRESH, store);

        NodeProbeService.ProbeRound restored = service.lastRound();
        assertNotNull(restored, "账本里有完整摘要就该恢复");
        assertEquals(3, restored.total(), "总数取自账本，不取自当前节点表");
        assertEquals(1, restored.online());
        assertEquals(1, restored.degraded());
        assertEquals(1, restored.offline());
        assertEquals(0, restored.unknown());
        assertEquals(T0, restored.finishedAt());
        assertEquals(T0 - 500L, restored.startedAt());
        assertFalse(restored.interrupted());
        verifyNoInteractions(transport);
    }

    // ========== 6) 中断的轮次：不落摘要，也不覆盖上一轮 ==========

    @Test
    void interruptedRoundNeverBecomesThePersistedSummary() throws Exception {
        Path dir = newWorkDir("round-interrupted-");
        Path file = dir.resolve("node-probe-state.json");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);
        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = nodeService(node(1L, "a", 11L));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return NodeProbeTransport.Reply.ok(5L, "OK");
        });

        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);
        NodeProbeService.ProbeRound half = runAware(() -> service.probeIfDue("boot").orElseThrow());

        assertTrue(half.interrupted(), "线程被打断就该标记为中断");
        assertNull(service.lastRound(), "中断轮不是完整轮次，不得作为上一轮摘要展示");
        NodeProbeState onDisk = store.load();
        assertEquals(0L, onDisk.lastCompletedAt(), "中断不写完成时间");
        assertNull(onDisk.lastRound(), "半轮不以「已完成」的样子落盘");

        NodeProbeService restarted = new NodeProbeService(nodeService, transport, id -> true,
                new MutableClock(T0 + ONE_HOUR), INTERVAL, FRESH, store);
        assertNull(restarted.lastRound(), "重启后不能把半轮当成上一轮");
        assertFalse(restarted.probeIfDue("boot").isPresent(), "预占仍在，窗口照旧被占用");
    }

    @Test
    void interruptedRoundKeepsPreviousRoundSummary() throws Exception {
        Path dir = newWorkDir("round-interrupted-keeps-");
        Path file = dir.resolve("node-probe-state.json");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);
        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = nodeService(node(1L, "a", 11L));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(5L, "OK"));

        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);
        NodeProbeService.ProbeRound first = service.probeIfDue("boot").orElseThrow();

        clock.advance(INTERVAL);
        when(transport.probe(1L)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return NodeProbeTransport.Reply.ok(5L, "OK");
        });
        NodeProbeService.ProbeRound half = runAware(() -> service.probeIfDue("schedule").orElseThrow());
        assertTrue(half.interrupted());
        assertEquals(first, service.lastRound(), "内存中的上一轮摘要也必须保持完整轮次");

        NodeProbeState onDisk = store.load();
        assertEquals(first, onDisk.lastRound(), "中断的那一轮不覆盖上一轮摘要");
        assertEquals(T0, onDisk.lastCompletedAt(), "完成时间还是上一轮的");
        assertEquals(T0 + INTERVAL, onDisk.lastAttemptAt(), "预占已经落盘");

        NodeProbeService restarted = new NodeProbeService(nodeService, transport, id -> true,
                new MutableClock(T0 + INTERVAL + 1L), INTERVAL, FRESH, store);
        assertEquals(first, restarted.lastRound(), "重启后显示的是真正跑完的上一轮，不是被中断的那一轮");
        assertEquals(Long.valueOf(T0 + 2 * INTERVAL), restarted.nextDueAt(),
                "预占时间比完成时间晚时，下一轮按预占时间算，不能显示一个已经过去的时刻");
        assertFalse(restarted.probeIfDue("boot").isPresent(), "仍被预占的窗口挡住");
    }

    // ========== 7) 完成时间写盘失败：内存有真轮次，盘上没有，重启不得编造 ==========

    @Test
    void completionWriteFailureNeverFabricatesRoundAfterRestart() throws Exception {
        Path dir = newWorkDir("round-write-fail-");
        Path file = dir.resolve("node-probe-state.json");
        FileNodeProbeStateStore real = new FileNodeProbeStateStore(file);
        FailingStore store = new FailingStore(real);
        store.failFromSave(2); // 第 1 次是预占（成功），第 2 次是完成（失败）

        MutableClock clock = new MutableClock(T0);
        NodeService nodeService = nodeService(node(1L, "a", 11L));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(5L, "OK"));

        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);
        NodeProbeService.ProbeRound round = service.probeIfDue("boot").orElseThrow();

        assertFalse(service.stateHealth().ok(), "完成时间写不进去必须报不健康");
        assertEquals(round, service.lastRound(), "内存里保留真实跑完的那一轮");

        NodeProbeState onDisk = real.load();
        assertEquals(0L, onDisk.lastCompletedAt());
        assertNull(onDisk.lastRound(), "写失败时盘上没有这一轮的摘要");

        NodeProbeService restarted = new NodeProbeService(nodeService, transport, id -> true,
                new MutableClock(T0 + ONE_HOUR), INTERVAL, FRESH, real);
        assertNull(restarted.lastRound(), "盘上没有摘要就不能凭空造一轮");
        assertFalse(restarted.probeIfDue("boot").isPresent(), "预占仍在，重启不得清零窗口");
    }

    // ========== 8) 恢复旧账本不得盖掉内存里更新的一轮 ==========

    @Test
    void recoveryDoesNotDowngradeNewerInMemoryRound() throws Exception {
        Path dir = newWorkDir("round-recover-");
        Path file = dir.resolve("node-probe-state.json");
        FileNodeProbeStateStore real = new FileNodeProbeStateStore(file);
        NodeProbeService.ProbeRound oldRound = new NodeProbeService.ProbeRound(
                "boot", T0 - 1_000L, T0, 2, 1, 0, 1, 0, false);
        NodeProbeState oldLedger = new NodeProbeState(T0 - 3 * INTERVAL, T0, "boot", List.of(), oldRound);
        real.save(oldLedger);
        StaleViewStore store = new StaleViewStore(real, oldLedger);

        MutableClock clock = new MutableClock(T0 + 3 * INTERVAL);
        NodeService nodeService = nodeService(node(1L, "a", 11L));
        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        when(transport.probe(1L)).thenReturn(NodeProbeTransport.Reply.ok(4L, "OK"));

        NodeProbeService service = new NodeProbeService(nodeService, transport, id -> true, clock, INTERVAL, FRESH, store);
        assertEquals(oldRound, service.lastRound(), "启动时先恢复盘上的旧一轮");

        NodeProbeService.ProbeRound fresh = service.probeIfDue("schedule").orElseThrow();
        assertFalse(fresh.interrupted());
        assertEquals(fresh, service.lastRound());
        assertTrue(fresh.finishedAt() > oldRound.finishedAt());

        // 预占写失败 → 账本不健康，但内存里仍是刚跑完的那一轮
        store.failSave(true);
        clock.advance(INTERVAL);
        assertFalse(service.probeIfDue("schedule").isPresent(), "写不进账本就不探测");
        assertFalse(service.stateHealth().ok());
        assertEquals(fresh, service.lastRound(), "写失败不能让内存里的真实摘要消失");

        // 恢复时读到的是更旧的盘上视图：不能把内存里更新的摘要降级
        store.failSave(false);
        when(nodeService.list()).thenThrow(new IllegalStateException("db down"));
        clock.advance(NodeProbeService.STATE_RECOVERY_INTERVAL_MS);
        assertThrows(NodeHealthDataException.class, () -> service.probeIfDue("schedule"));
        assertTrue(service.stateHealth().ok(), "账本可读可写后健康态要恢复");
        assertEquals(fresh, service.lastRound(), "恢复旧账本不得盖掉内存里更新的一轮");
    }

    // ========== 脚手架 ==========

    private static void assertRoundNotRestored(long lastCompletedAt, String lastRoundJson,
                                               String message) throws Exception {
        Path dir = newWorkDir("round-mismatch-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"lastAttemptAt\":" + (T0 - INTERVAL)
                + ",\"lastCompletedAt\":" + lastCompletedAt + ",\"lastTrigger\":\"schedule\","
                + "\"results\":[],\"lastRound\":" + lastRoundJson + "}", StandardCharsets.UTF_8);
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);

        NodeProbeState loaded = store.load();
        assertFalse(FileNodeProbeStateStore.isLoadError(loaded), "对不上不等于账本损坏");
        assertNotNull(loaded.lastRound(), "结构完整的摘要要能读出来，判断交给服务层");

        NodeProbeTransport transport = mock(NodeProbeTransport.class);
        NodeProbeService service = new NodeProbeService(nodeService(node(1L, "a", 11L)), transport,
                id -> true, new MutableClock(T0), INTERVAL, FRESH, store);
        assertNull(service.lastRound(), message);
        verifyNoInteractions(transport);
    }

    private static String roundJson(String trigger, long startedAt, long finishedAt, int total,
                                    int online, int degraded, int offline, int unknown, boolean interrupted) {
        return "{\"trigger\":\"" + trigger + "\",\"startedAt\":" + startedAt
                + ",\"finishedAt\":" + finishedAt + ",\"total\":" + total
                + ",\"online\":" + online + ",\"degraded\":" + degraded
                + ",\"offline\":" + offline + ",\"unknown\":" + unknown
                + ",\"interrupted\":" + interrupted + "}";
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
        Path base = Paths.get("target", "nodehealth-round-tmp").toAbsolutePath();
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
        }, "node-probe-round-test");
        worker.start();
        worker.join(10_000L);
        assertFalse(worker.isAlive(), "探测线程不能挂住");
        assertTrue(interruptKept.get(), "中断标记应留在工作线程上，不影响测试主线程");
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

    /** 让「第 N 次起的写」失败的账本：读正常、写抛错，模拟磁盘满/只读挂载。 */
    private static final class FailingStore implements NodeProbeStateStore {

        private final NodeProbeStateStore delegate;
        private final AtomicInteger saveCalls = new AtomicInteger();
        private volatile int failFromSave = Integer.MAX_VALUE;

        FailingStore(NodeProbeStateStore delegate) {
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
            if (saveCalls.incrementAndGet() >= failFromSave) {
                throw new NodeProbeStateException("disk full");
            }
            delegate.save(state);
        }
    }

    /** 读走一份固定不变的「盘上视图」，写落到真实文件：模拟账本被外部回滚成更旧的一份。 */
    private static final class StaleViewStore implements NodeProbeStateStore {

        private final NodeProbeStateStore delegate;
        private final NodeProbeState view;
        private volatile boolean failSave;

        StaleViewStore(NodeProbeStateStore delegate, NodeProbeState view) {
            this.delegate = delegate;
            this.view = view;
        }

        void failSave(boolean fail) {
            this.failSave = fail;
        }

        @Override
        public NodeProbeState load() {
            return view;
        }

        @Override
        public void save(NodeProbeState state) {
            if (failSave) {
                throw new NodeProbeStateException("read-only mount");
            }
            delegate.save(state);
        }
    }
}
