package com.admin.ddns;

import com.admin.ddns.service.DdnsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DDNS 定时入口：tick 只做一次原子门控，把整轮检查交给私有单线程执行器。
 *
 * <p>为什么不在调度线程里直接跑：一轮检查要逐条记录等 DNS 解析、再逐节点重下发转发，
 * 慢起来能把 Spring 共享调度线程占住几十秒，连带拖住其他定时任务。tick 因此只做
 * 「占门 → 提交」，慢活全在 {@code ddns-scheduler-worker} 上跑。
 *
 * <p>为什么不叠轮次：执行器 core=max=1、队列用 {@link SynchronousQueue}（不做缓冲），
 * 再配 {@link #running} 门控——上一轮没结束时后续 tick 直接丢弃，既不排队也不重入。
 *
 * <p>关闭语义：{@link #destroy()} 先置 closed、再 {@code shutdownNow()} 打断在途轮次并等待退出
 * （上限 {@value #SHUTDOWN_WAIT_MS} ms，工作线程是守护线程）。被打断的轮次只记「中止」，
 * 不打印本轮 summary——没跑完的轮次不报成已完成。
 */
@Slf4j
@Component
public class DdnsScheduler implements DisposableBean {

    static final long SHUTDOWN_WAIT_MS = 5_000L;

    private final DdnsService ddnsService;

    /** 门控：true = 已有一轮在跑或已提交，后续 tick 直接丢弃，不进队列。 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ThreadPoolExecutor executor;

    public DdnsScheduler(DdnsService ddnsService) {
        this.ddnsService = ddnsService;
        // core=1 / max=1 / SynchronousQueue：不做队列缓冲，配合 running 门控最多只有一个在途轮次
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "ddns-scheduler-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelayString = "${ddns.check-interval-ms:15000}",
            initialDelayString = "${ddns.initial-delay-ms:30000}")
    public void tick() {
        if (closed.get()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("上一轮 DDNS 检查尚未结束，跳过本次 tick");
            return;
        }
        try {
            executor.execute(this::runRound);
        } catch (RejectedExecutionException e) {
            // 执行器已关闭（或极端竞态）：门控必须还回去，否则之后再也不会检查
            running.set(false);
            log.warn("DDNS 执行器已关闭，跳过本次 tick");
        }
    }

    private void runRound() {
        try {
            // 启动窗口或读库失败时，这里负责把渲染映射补齐：不然节点摘要对账会一直按降级口径跑。
            // 补不齐就必须整轮跳过：apply 的 stage 只给本域名显式指定新地址，同一条 remoteAddr 上的
            // 其它域名仍按注册表渲染，未就绪时会被写回域名本身（节点上还能用的 IP 被改掉，
            // 恢复后对账再判漂移删建）。门控在 finally 里释放，下一轮继续重试。
            if (!ddnsService.ensureRegistryLoaded()) {
                log.warn("DDNS 渲染映射尚未就绪，跳过本轮检查，下一轮继续重试");
                return;
            }
            String summary = ddnsService.checkDueRecords();
            if (closed.get()) {
                log.info("DDNS 轮次在关闭中被打断，本轮结果不记为完成");
                return;
            }
            if (summary != null && !summary.contains("checked=0")) {
                log.info("DDNS 本轮检查：{}", summary);
            }
        } catch (RuntimeException e) {
            if (closed.get()) {
                log.info("DDNS 轮次在关闭中被打断：{}", e.getMessage());
            } else {
                log.error("DDNS 调度轮次异常", e);
            }
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
                log.warn("DDNS 执行器未在 {} ms 内结束，放弃等待（工作线程为守护线程）", SHUTDOWN_WAIT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean tickRunning() {
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
