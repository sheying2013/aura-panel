package com.admin.compat;

import com.admin.common.utils.AuthAttemptLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuthAttemptLimiter} 的窗口语义。
 *
 * <p>可注入时钟的构造器是包级可见的（{@code AuthAttemptLimiter.java} 第二个构造器），
 * 而测试固定放在 {@code com.admin.compat} 包，因此用反射拿它来把时间拨快，
 * 不去改动生产代码的可见性。</p>
 */
class AuthAttemptLimiterTest {

    private static final String KEY = "open_api|10.1.2.3|alice";

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private AuthAttemptLimiter limiter;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<AuthAttemptLimiter> ctor =
                AuthAttemptLimiter.class.getDeclaredConstructor(LongSupplier.class);
        ctor.setAccessible(true);
        limiter = ctor.newInstance((LongSupplier) now::get);
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(KEY);
        }
    }

    @Test
    @DisplayName("无失败记录时放行")
    void allowsWithoutFailures() {
        assertTrue(limiter.allow(KEY));
    }

    @Test
    @DisplayName("窗口内满 10 次失败后拒绝，其他 key 不受影响")
    void blocksAfterMaxFailures() {
        failTimes(10);

        assertFalse(limiter.allow(KEY));
        assertTrue(limiter.allow("open_api|10.1.2.3|bob"));
    }

    @Test
    @DisplayName("窗口滑过后自动放行")
    void allowsAfterWindowSlides() {
        failTimes(10);
        assertFalse(limiter.allow(KEY));

        now.addAndGet(300_001L);

        assertTrue(limiter.allow(KEY));
    }

    @Test
    @DisplayName("只有窗口外的失败被裁掉，窗口内的仍然计数")
    void prunesOnlyExpiredFailures() {
        limiter.recordFailure(KEY);
        now.addAndGet(299_999L);
        failTimes(9);
        assertFalse(limiter.allow(KEY), "10 次失败都还在 5 分钟窗口内");

        now.addAndGet(2L);

        assertTrue(limiter.allow(KEY), "最早那次已过窗口，剩 9 次应放行");
    }

    @Test
    @DisplayName("reset 清空该 key 的失败记录")
    void resetClearsKey() {
        failTimes(10);
        assertFalse(limiter.allow(KEY));

        limiter.reset(KEY);

        assertTrue(limiter.allow(KEY));
    }

    @Test
    @DisplayName("clearAll 清空全部 key")
    void clearAllClearsEverything() {
        failTimes(10);
        limiter.recordFailure("open_api|10.1.2.3|bob");

        limiter.clearAll();

        assertTrue(limiter.allow(KEY));
        assertTrue(limiter.allow("open_api|10.1.2.3|bob"));
    }

    @Test
    @DisplayName("null key 不计数也不抛异常")
    void nullKeyIsIgnored() {
        limiter.recordFailure(null);

        assertTrue(limiter.allow(null));
    }
}
