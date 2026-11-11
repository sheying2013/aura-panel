package com.admin.common.nodehealth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落盘账本回归测试：节流的可信度完全取决于这个文件，所以它自己的失败方式必须固定。
 *
 * <p>锁死四条：
 * ① 存取往返不丢字段，写完不留临时文件；
 * ② 文件不存在 = 从没探测过（不是「刚刚探测过」，否则新装机器会白等 12 小时）；
 * ③ 文件损坏时用修改时间保守节流，绝不退化成「随便测」；
 * ④ 写不进去必须抛 {@link NodeProbeStateException}，让调用方放弃本轮。
 */
class FileNodeProbeStateStoreTest {

    @Test
    void saveThenLoadKeepsEveryField() throws Exception {
        Path dir = newWorkDir("store-roundtrip-");
        Path file = dir.resolve("node-probe-state.json");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);

        store.save(new NodeProbeState(111L, 222L, "schedule"));

        NodeProbeState loaded = new FileNodeProbeStateStore(file).load();
        assertEquals(111L, loaded.lastAttemptAt());
        assertEquals(222L, loaded.lastCompletedAt());
        assertEquals("schedule", loaded.lastTrigger());
        assertTrue(loaded.hasAttempt());
        assertFalse(Files.exists(dir.resolve("node-probe-state.json.tmp")), "临时文件必须被原子改名吃掉，不能留在目录里");
        assertEquals(file.toAbsolutePath(), store.file());
    }

    @Test
    void missingFileMeansNeverProbed() throws Exception {
        Path dir = newWorkDir("store-missing-");

        NodeProbeState loaded = new FileNodeProbeStateStore(dir.resolve("node-probe-state.json")).load();

        assertEquals(0L, loaded.lastAttemptAt());
        assertFalse(loaded.hasAttempt(), "新机器没有账本 = 没探测过，必须允许立刻测一轮");
    }

    @Test
    void corruptedFileFallsBackToFileModificationTime() throws Exception {
        Path dir = newWorkDir("store-corrupt-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        long mtime = 1_690_000_000_000L;
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));

        NodeProbeState loaded = new FileNodeProbeStateStore(file).load();

        assertEquals(mtime, loaded.lastAttemptAt(), "读不出来就按文件时间保守节流，不能当成没测过");
        assertTrue(loaded.hasAttempt());
        assertEquals(0L, loaded.lastCompletedAt());
        assertTrue(FileNodeProbeStateStore.isLoadError(loaded), "损坏的账本必须被标记为不可信，调用方要 fail-closed");
        assertTrue(FileNodeProbeStateStore.loadErrorMessage(loaded).contains("node-probe-state.json"));
    }

    @Test
    void emptyJsonObjectIsUnreadableInsteadOfNeverProbed() throws Exception {
        Path dir = newWorkDir("store-empty-object-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);

        NodeProbeState loaded = new FileNodeProbeStateStore(file).load();

        assertTrue(FileNodeProbeStateStore.isLoadError(loaded), "空对象字段全缺，不能假装「从未探测」");
        assertTrue(loaded.lastAttemptAt() > 0L, "不可读时的时间戳绝不能用 0（0 等于从未探测）");
        assertTrue(FileNodeProbeStateStore.loadErrorMessage(loaded).contains("lastAttemptAt"));
    }

    @Test
    void missingFieldsAreUnreadable() throws Exception {
        Path dir = newWorkDir("store-missing-field-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"lastAttemptAt\":123}", StandardCharsets.UTF_8);

        NodeProbeState loaded = new FileNodeProbeStateStore(file).load();

        assertTrue(FileNodeProbeStateStore.isLoadError(loaded), "缺 lastCompletedAt 也要 fail-closed");
        assertTrue(FileNodeProbeStateStore.loadErrorMessage(loaded).contains("lastCompletedAt"));
    }

    @Test
    void nonNumericAndNegativeFieldsAreUnreadable() throws Exception {
        Path dir = newWorkDir("store-bad-types-");
        Path stringFile = dir.resolve("string.json");
        Files.writeString(stringFile,
                "{\"lastAttemptAt\":\"123\",\"lastCompletedAt\":0}", StandardCharsets.UTF_8);
        assertTrue(FileNodeProbeStateStore.isLoadError(new FileNodeProbeStateStore(stringFile).load()),
                "字符串数字不能被读成 0 或被静默转换");

        Path negativeFile = dir.resolve("negative.json");
        Files.writeString(negativeFile,
                "{\"lastAttemptAt\":-1,\"lastCompletedAt\":0}", StandardCharsets.UTF_8);
        assertTrue(FileNodeProbeStateStore.isLoadError(new FileNodeProbeStateStore(negativeFile).load()),
                "负时间戳不可信");
    }

    @Test
    void unsupportedSchemaVersionIsUnreadable() throws Exception {
        Path dir = newWorkDir("store-schema-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(file,
                "{\"schemaVersion\":2,\"lastAttemptAt\":1,\"lastCompletedAt\":2}", StandardCharsets.UTF_8);

        NodeProbeState loaded = new FileNodeProbeStateStore(file).load();

        assertTrue(FileNodeProbeStateStore.isLoadError(loaded), "未知版本的字段语义不可信，必须 fail-closed");
        assertTrue(FileNodeProbeStateStore.loadErrorMessage(loaded).contains("schemaVersion"));
    }

    @Test
    void directoryInPlaceOfFileIsUnreadableButStartupSafe() throws Exception {
        Path dir = newWorkDir("store-directory-");

        NodeProbeState loaded = new FileNodeProbeStateStore(dir).load();

        assertTrue(FileNodeProbeStateStore.isLoadError(loaded), "路径是目录时不是「文件不存在」，必须 fail-closed");
        assertTrue(loaded.lastAttemptAt() > 0L);
    }

    @Test
    void unreadableFileIsUnreadableNotMissing() throws Exception {
        Path dir = newWorkDir("store-denied-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(file, "{\"lastAttemptAt\":1,\"lastCompletedAt\":2}", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(file), "以 root 运行时权限位不生效，跳过该用例");

        long before = System.currentTimeMillis();
        NodeProbeState loaded = new FileNodeProbeStateStore(file).load();

        assertTrue(FileNodeProbeStateStore.isLoadError(loaded), "无权限不是「文件不存在」，不能当成从未探测");
        assertTrue(loaded.lastAttemptAt() > 0L);
        assertTrue(Math.abs(System.currentTimeMillis() - loaded.lastAttemptAt()) < 60_000L,
                "mtime 也读不到时要用当前时间兜底，不能返回 0");
        assertTrue(loaded.lastAttemptAt() >= before - 60_000L);
    }

    @Test
    void validZeroStateIsNotAReadFailure() throws Exception {
        Path dir = newWorkDir("store-valid-zero-");
        Path file = dir.resolve("node-probe-state.json");
        Files.writeString(file,
                "{\"schemaVersion\":1,\"lastAttemptAt\":0,\"lastCompletedAt\":0}", StandardCharsets.UTF_8);

        NodeProbeState loaded = new FileNodeProbeStateStore(file).load();

        assertFalse(FileNodeProbeStateStore.isLoadError(loaded), "字段齐全的 0 是合法状态，不能被误判为损坏");
        assertFalse(loaded.hasAttempt());
    }

    @Test
    void unwritableTargetThrowsInsteadOfPretendingToSave() throws Exception {
        Path dir = newWorkDir("store-unwritable-");
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(blocker.resolve("node-probe-state.json"));

        assertThrows(NodeProbeStateException.class, () -> store.save(new NodeProbeState(1L, 2L, "schedule")));
    }

    @Test
    void nullStateIsRejected() throws Exception {
        Path dir = newWorkDir("store-null-");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(dir.resolve("node-probe-state.json"));

        assertThrows(NodeProbeStateException.class, () -> store.save(null));
        assertThrows(IllegalArgumentException.class, () -> new FileNodeProbeStateStore(null));
    }

    @Test
    void concurrentSavesLeaveAReadableFile() throws Exception {
        Path dir = newWorkDir("store-concurrent-");
        Path file = dir.resolve("node-probe-state.json");
        FileNodeProbeStateStore store = new FileNodeProbeStateStore(file);

        int threads = 4;
        int rounds = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        store.save(new NodeProbeState(1000L + i, 2000L + i, "t" + id));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20L, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        NodeProbeState loaded = new FileNodeProbeStateStore(file).load();
        assertTrue(loaded.lastAttemptAt() >= 1000L, "并发写之后文件仍要是完整 JSON，而不是半截内容");
        assertTrue(loaded.lastCompletedAt() >= 2000L);
        assertTrue(loaded.lastTrigger() != null && loaded.lastTrigger().startsWith("t"));
    }

    private static Path newWorkDir(String prefix) throws IOException {
        Path base = Paths.get("target", "nodehealth-verify-tmp").toAbsolutePath();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, prefix);
    }
}
