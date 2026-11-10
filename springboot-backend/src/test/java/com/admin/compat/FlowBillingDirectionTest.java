package com.admin.compat;

import com.admin.common.utils.FlowBilling;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 计费方向回归：节点上报的 d 是上传、u 是下载（go-gost/x/service/service.go:495 U=outputBytes / D=inputBytes，
 * 真机 200000 字节上行实测 d=200163 / u=211）。本类锁死这一方向，防止再把 u 当上传。
 */
class FlowBillingDirectionTest {

    private static final long UP = 200_163L;
    private static final long DOWN = 211L;

    private static final List<String> results = new ArrayList<>();

    @Test
    void uploadOnlyKeepsUploadDirectionAndDropsDownload() {
        long[] billed = FlowBilling.billed(UP, DOWN, 1, BigDecimal.ONE);
        assertArrayEquals(new long[]{UP, 0L}, billed, "flow=1 应保留上传(d)、把下载(u)归零");
        record("uploadOnlyKeepsUploadDirectionAndDropsDownload", billed);
    }

    @Test
    void ratioScalesUploadWhenDownloadIsDropped() {
        long[] billed = FlowBilling.billed(1_000L, 9_999L, 1, new BigDecimal("2.5"));
        assertArrayEquals(new long[]{2_500L, 0L}, billed);
        record("ratioScalesUploadWhenDownloadIsDropped", billed);
    }

    @Test
    void bothDirectionCountsEachDirectionOnce() {
        long[] billed = FlowBilling.billed(1_000L, 2_000L, 2, BigDecimal.ONE);
        assertArrayEquals(new long[]{1_000L, 2_000L}, billed, "flow=2 双向各计一次，不得乘 2");
        record("bothDirectionCountsEachDirectionOnce", billed);
    }

    @Test
    void illegalDirectionFallsBackToBoth() {
        assertArrayEquals(new long[]{10L, 20L}, FlowBilling.billed(10, 20, 0, BigDecimal.ONE));
        assertArrayEquals(new long[]{10L, 20L}, FlowBilling.billed(10, 20, 7, BigDecimal.ONE));
        record("illegalDirectionFallsBackToBoth", new long[]{10L, 20L});
    }

    @Test
    void negativeAndZeroRatioAreNeutral() {
        assertArrayEquals(new long[]{0L, 0L}, FlowBilling.billed(-5L, -5L, 2, BigDecimal.ONE));
        assertArrayEquals(new long[]{100L, 100L}, FlowBilling.billed(100, 100, 2, BigDecimal.ZERO));
        assertArrayEquals(new long[]{100L, 100L}, FlowBilling.billed(100, 100, 2, null));
        record("negativeAndZeroRatioAreNeutral", new long[]{100L, 100L});
    }

    @Test
    void uploadOnlyOnRealMachineSampleMatchesObservedCounters() {
        // 圣何塞真机：40 次 wget 得到 d=5600 u=10360；新口径下 in_flow 应 +5600、out_flow 应 +0
        long[] billed = FlowBilling.billed(5_600L, 10_360L, 1, BigDecimal.ONE);
        assertArrayEquals(new long[]{5_600L, 0L}, billed);
        assertEquals(5_600L, billed[0] + billed[1], "计费总量应等于上传量");
        record("uploadOnlyOnRealMachineSampleMatchesObservedCounters", billed);
    }

    private static void record(String name, long[] billed) {
        synchronized (results) {
            results.add(name + " -> {" + billed[0] + "," + billed[1] + "}");
        }
    }

    static List<String> results() {
        synchronized (results) {
            return new ArrayList<>(results);
        }
    }
}
