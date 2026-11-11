package com.admin.compat;

import com.admin.common.nodehealth.NodeHealthViewService;
import com.admin.common.nodehealth.NodeProbeScheduler;
import com.admin.common.nodehealth.NodeProbeService;
import com.admin.common.nodehealth.NodeProbeStateStore;
import com.admin.controller.NodeHealthController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 探测节奏契约测试。
 *
 * <p>锁死「默认 12 小时一次（每天 2 次）」和「探测只挂在调度器上」两件事：
 * 只要有人把间隔调成分钟级、把探测挂到控制器上、或把默认值改小，
 * 这里就会红——因为那意味着仪表板刷新会变成对节点的周期性打点。
 */
class NodeProbeScheduleContractTest {

    /** 用拼接避免源码里出现占位符字面量，读测试的人一眼看出断言的是注解原样文本。 */
    private static final String OPEN = "$" + "{";
    private static final String CLOSE = "}";

    private static final String TICK_KEY = "flux.node-probe.tick-ms";
    private static final String INITIAL_DELAY_KEY = "flux.node-probe.initial-delay-ms";
    private static final String ENABLED_KEY = "flux.node-probe.enabled";

    @Test
    void defaultIntervalIsTwelveHoursTwiceADay() {
        assertEquals(43_200_000L, NodeProbeService.DEFAULT_INTERVAL_MS, "默认 12 小时");
        assertEquals(12L * 60L * 60L * 1000L, NodeProbeService.DEFAULT_INTERVAL_MS);
        assertEquals(24L * 60L * 60L * 1000L, NodeProbeService.DEFAULT_FRESHNESS_MS, "结论保鲜期两轮");
        assertEquals(2, 24 * 60 * 60 * 1000 / NodeProbeService.DEFAULT_INTERVAL_MS, "一天正好两轮");
    }

    @Test
    void scheduledAnnotationIsATickNotTheProbeCadence() throws Exception {
        Method method = NodeProbeScheduler.class.getMethod("scheduledProbe");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertNotNull(scheduled, "探测必须挂在 @Scheduled 上，不能挂在任何 HTTP 请求路径里");
        assertEquals(OPEN + TICK_KEY + ":60000" + CLOSE, scheduled.fixedDelayString(),
                "定时器只做 60 秒 tick；12 小时节流由落盘账本判定，进程重启不会重新计时");
        assertEquals(OPEN + INITIAL_DELAY_KEY + ":60000" + CLOSE, scheduled.initialDelayString());
        assertTrue(scheduled.fixedRateString().isEmpty(), "用 fixedDelay：避免 tick 叠加");
    }

    @Test
    void cadenceGateIsPersistedAndReportsWhetherItRan() throws Exception {
        Method probe = NodeProbeService.class.getMethod("probeIfDue", String.class);
        assertEquals(Optional.class, probe.getReturnType(), "未到点时不探测，必须能区分「跳过」与「跑了」");

        boolean hasStoreConstructor = false;
        for (Constructor<?> constructor : NodeProbeService.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 7 && types[6] == NodeProbeStateStore.class) {
                hasStoreConstructor = true;
            }
        }
        assertTrue(hasStoreConstructor, "生产装配必须能注入落盘账本，否则重启就把 12 小时节流清零");
    }

    @Test
    void schedulerIsOnByDefaultAndCanBeDisabled() {
        ConditionalOnProperty property = NodeProbeScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(property);
        assertEquals(ENABLED_KEY, property.name()[0]);
        assertEquals("true", property.havingValue());
        assertTrue(property.matchIfMissing(), "默认开启，但允许 flux.node-probe.enabled=false 关掉");
    }

    @Test
    void onlyTheSchedulerDrivesProbes() {
        assertFalse(hasScheduledMethod(NodeHealthViewService.class), "只读视图不得自带定时探测");
        assertFalse(hasScheduledMethod(NodeHealthController.class), "控制器不得自带定时探测");
        assertFalse(hasScheduledMethod(NodeProbeService.class), "探测只在调度器驱动下发生，不自己起线程");
    }

    private static boolean hasScheduledMethod(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getAnnotation(Scheduled.class) != null) {
                return true;
            }
        }
        return false;
    }
}
