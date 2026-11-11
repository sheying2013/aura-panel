package com.admin.common.nodehealth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 节点健康探测的定时入口：定时器只负责「醒来问一句该不该探测」，是否真的探测由
 * {@link NodeProbeService#probeIfDue(String)} 按持久化账本决定，默认 12 小时最多一轮。
 *
 * <p>为什么不用 {@code fixedDelay=12h}：那个延时只活在进程内存里，重启就重新计时，
 * 一天重启十次就会探测十次。所以这里用短 tick（默认 60 秒）唤醒，节流交给落盘的
 * {@link NodeProbeStateStore}——重启不重置。
 *
 * <p>tick 不等于探测频率：绝大多数 tick 在节流判断处直接返回，不产生任何网络动作。
 * 首轮延迟 60 秒，避开启动瞬间的建连/对账高峰。探测只在 {@link NodeProbeService} 里发生，
 * 任何 HTTP 接口（含仪表板刷新）都不会触发它。
 *
 * <p><b>不占 Spring 调度线程</b>：一轮探测要逐个节点等回包（单节点最长 10 秒），不能把全局
 * 调度线程池的线程占用几十秒。{@link #scheduledProbe()} 只做一次原子门控，然后把轮次交给
 * 私有的单线程执行器；上一轮没结束时，后续 tick 直接丢弃——执行器用 {@link SynchronousQueue}
 * 不设队列，不会把 tick 积压成一轮接一轮的重复探测。执行器线程是守护线程，应用关闭时由
 * {@link #destroy()} 打断在途轮次并等待退出。
 *
 * <p>可用 {@code flux.node-probe.enabled=false} 关掉；tick 用 {@code flux.node-probe.tick-ms} 调，
 * 首轮延迟用 {@code flux.node-probe.initial-delay-ms} 调，探测间隔用 {@code flux.node-probe.interval-ms} 调。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "flux.node-probe.enabled", havingValue = "true", matchIfMissing = true)
public class NodeProbeScheduler implements DisposableBean {

    private static final long SHUTDOWN_WAIT_MS = 5_000L;

    private final NodeProbeService nodeProbeService;

    /** 门控：true = 已有一轮在跑或已提交，后续 tick 直接丢弃，不进队列。 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ThreadPoolExecutor executor;

    public NodeProbeScheduler(NodeProbeService nodeProbeService) {
        this.nodeProbeService = nodeProbeService;
        // core=1 / max=1 / SynchronousQueue：不做队列缓冲，配合 running 门控最多只有一个在途轮次
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "node-probe-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelayString = "${flux.node-probe.tick-ms:60000}",
            initialDelayString = "${flux.node-probe.initial-delay-ms:60000}")
    public void scheduledProbe() {
        if (closed.get()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("上一轮节点探测尚未结束，跳过本次 tick");
            return;
        }
        try {
            executor.execute(this::runProbe);
        } catch (RejectedExecutionException e) {
            // 执行器已关闭（或极端竞态）：必须把门控还回去，否则之后再也不会探测
            running.set(false);
            log.warn("节点探测执行器已关闭，跳过本次 tick");
        }
    }

    private void runProbe() {
        try {
            nodeProbeService.probeIfDue("schedule");
        } catch (RuntimeException e) {
            // 定时任务里抛异常会被调度器吞掉并停止后续执行，这里必须自己兜住
            log.warn("节点健康探测轮次异常：{}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** 关闭执行器并打断在途轮次；守护线程即使超时也不会阻止 JVM 退出。 */
    @Override
    public void destroy() {
        closed.set(true);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("节点探测执行器未在 {} ms 内结束，放弃等待（工作线程为守护线程）", SHUTDOWN_WAIT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean probeRunning() {
        return running.get();
    }

    /** 测试用：等待门控释放（在途轮次结束），不参与生产逻辑。 */
    boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (running.get()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return false;
            }
            Thread.sleep(Math.min(10L, TimeUnit.NANOSECONDS.toMillis(remaining) + 1L));
        }
        return true;
    }
}
