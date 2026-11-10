package com.admin.common.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流量上报幂等（节点 HTTP 重试导致的重复计入）。
 * <p>
 * 节点侧上报体没有 batch/seq（go-gost/x/service/traffic_reporter.go 的 TrafficReportItem 只有 n/u/d），
 * 因此退化为「时间桶 + 内容」做键：同一节点、同一服务、同一时间桶内、增量数值完全相同的一批上报，
 * 只累加一次。
 * <p>
 * 为什么键里要带内容：节点每 5 秒上报一次增量（go-gost/x/service/global_traffic_manager.go:40 的
 * reportTicker），只按时间桶去重会把相邻两次真实增量吞掉；带上 d/u 后只吞「一字不差的重复报文」，
 * 正是重试产生的形态。
 * <p>
 * 内存有界：只保留最近 {@link #retainWindows} 个时间桶的键，每插入 64 次扫一次过期键；
 * 极端情况（键数超过上限说明被刷）直接清空——宁可少去重，也不让 Map 无界增长。
 */
public final class FlowReportDedup {

    /** 缺省时间桶宽度：2 倍于节点 5 秒上报节拍，覆盖「超时后下一次 tick 重发」的窗口。 */
    public static final long DEFAULT_WINDOW_MS = 10_000L;

    /** 键上限：超过即清空，保证内存有界。 */
    public static final int DEFAULT_MAX_ENTRIES = 200_000;

    private final long windowMs;
    private final int maxEntries;
    private final long retainWindows;
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final AtomicLong inserts = new AtomicLong();

    public FlowReportDedup() {
        this(DEFAULT_WINDOW_MS, 3L, DEFAULT_MAX_ENTRIES);
    }

    public FlowReportDedup(long windowMs, long retainWindows, int maxEntries) {
        if (windowMs <= 0L) {
            throw new IllegalArgumentException("windowMs must be > 0: " + windowMs);
        }
        if (retainWindows <= 0L) {
            throw new IllegalArgumentException("retainWindows must be > 0: " + retainWindows);
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0: " + maxEntries);
        }
        this.windowMs = windowMs;
        this.retainWindows = retainWindows;
        this.maxEntries = maxEntries;
    }

    /**
     * 时间桶序号。时钟回拨到 1970 之前按 0 处理，不产生负桶。
     */
    public static long bucketOf(long epochMillis, long windowMs) {
        if (windowMs <= 0L) {
            throw new IllegalArgumentException("windowMs must be > 0: " + windowMs);
        }
        return Math.max(0L, epochMillis) / windowMs;
    }

    /**
     * 登记一条上报。
     *
     * @return true 表示这条上报在本时间桶内已出现过（重复，调用方必须跳过累加）；
     *         false 表示首次出现，调用方照常累加
     */
    public boolean isDuplicate(String nodeId, String serviceName, long epochMillis, long downloadBytes, long uploadBytes) {
        long bucket = bucketOf(epochMillis, windowMs);
        String key = key(nodeId, serviceName, bucket, downloadBytes, uploadBytes);
        if (seen.putIfAbsent(key, bucket) != null) {
            return true;
        }
        maybePurge(bucket);
        return false;
    }

    public static String key(String nodeId, String serviceName, long bucket, long d, long u) {
        return nodeId + '|' + serviceName + '|' + bucket + '|' + d + '|' + u;
    }

    private void maybePurge(long currentBucket) {
        if ((inserts.incrementAndGet() & 0x3FL) != 0L && seen.size() <= maxEntries) {
            return;
        }
        long oldestKept = currentBucket - retainWindows + 1L;
        seen.entrySet().removeIf(entry -> entry.getValue() < oldestKept);
        if (seen.size() > maxEntries) {
            seen.clear();
        }
    }

    /** 当前键数，仅供测试与观测。 */
    public int size() {
        return seen.size();
    }

    public long windowMs() {
        return windowMs;
    }
}
