package com.admin.common.utils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 进程内滑动窗口失败计数器，用于登录/开放接口鉴权的撞库限流。
 *
 * <p>只记最近 {@link #WINDOW_MS} 内的失败时间戳，窗口外的记录在下次访问时被裁掉，
 * 因此不需要任何后台清理线程；key 数量超过 {@link #MAX_KEYS} 时淘汰最早写入的 key，
 * 避免被大量伪造 IP+账号 组合刷爆内存。</p>
 */
public class AuthAttemptLimiter {

    /** 最多同时跟踪的 key 数（IP + 账号 组合） */
    static final int MAX_KEYS = 10000;
    /** 滑动窗口长度：5 分钟 */
    static final long WINDOW_MS = 300000L;
    /** 窗口内允许的最大失败次数，达到后拒绝 */
    static final int MAX_FAILURES = 10;

    private final LongSupplier clock;
    private final Map<String, Deque<Long>> failures;

    public AuthAttemptLimiter() {
        this(System::currentTimeMillis);
    }

    /**
     * 可注入时钟的构造器（包级可见，仅测试用）。
     */
    AuthAttemptLimiter(LongSupplier clock) {
        this.clock = clock;
        this.failures = new LinkedHashMap<String, Deque<Long>>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Long>> eldest) {
                return size() > MAX_KEYS;
            }
        };
    }

    /**
     * 当前是否放行。窗口内失败次数达到 {@link #MAX_FAILURES} 即拒绝。
     */
    public synchronized boolean allow(String key) {
        if (key == null) {
            return true;
        }
        Deque<Long> timestamps = failures.get(key);
        if (timestamps == null) {
            return true;
        }
        prune(timestamps, clock.getAsLong());
        return timestamps.size() < MAX_FAILURES;
    }

    /**
     * 记一次失败。
     */
    public synchronized void recordFailure(String key) {
        if (key == null) {
            return;
        }
        long now = clock.getAsLong();
        Deque<Long> timestamps = failures.get(key);
        if (timestamps == null) {
            timestamps = new ArrayDeque<>();
            failures.put(key, timestamps);
        }
        prune(timestamps, now);
        timestamps.addLast(now);
    }

    /**
     * 成功后清零该 key 的失败记录。
     */
    public synchronized void reset(String key) {
        if (key == null) {
            return;
        }
        failures.remove(key);
    }

    /**
     * 清空全部记录（测试用）。
     */
    public synchronized void clearAll() {
        failures.clear();
    }

    private void prune(Deque<Long> timestamps, long now) {
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= WINDOW_MS) {
            timestamps.pollFirst();
        }
    }
}
