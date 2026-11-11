package com.admin.common.task;

import com.admin.entity.User;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 利用持久化的账号状态重试被写锁占用而未完成的停用暂停；不新建任务表。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "flux.disabled-user-reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class DisabledUserPauseReconciler implements DisposableBean {
    private static final int BATCH_SIZE = 100;
    private final UserService users;
    private final ForwardService forwards;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor executor;
    private long cursor;

    public DisabledUserPauseReconciler(UserService users, ForwardService forwards) {
        this.users = users;
        this.forwards = forwards;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "disabled-user-reconcile");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelayString = "${flux.disabled-user-reconcile.tick-ms:60000}",
            initialDelayString = "${flux.disabled-user-reconcile.initial-delay-ms:60000}")
    public void schedule() {
        if (closed.get() || !running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { reconcileBatch(); }
                catch (RuntimeException e) { log.warn("停用转发补偿轮次失败，稍后重试", e); }
                finally { running.set(false); }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
            if (!closed.get()) log.warn("停用转发补偿提交失败，稍后重试", e);
        }
    }

    /** 仅单线程调用；轮转游标重启归零，数据库用户状态始终为重试依据。 */
    void reconcileBatch() {
        List<User> batch = users.list(new QueryWrapper<User>().eq("status", 0)
                .gt("id", cursor).orderByAsc("id").last("LIMIT " + BATCH_SIZE));
        if (batch == null) throw new IllegalStateException("停用用户查询返回 null");
        for (User user : batch) {
            if (Thread.currentThread().isInterrupted() || closed.get()) return;
            if (user == null || user.getId() == null) continue;
            try {
                List<String> notes = forwards.pauseForDisabledUser(user.getId());
                if (notes != null && !notes.isEmpty()) log.warn("停用用户 {} 的转发补偿待确认：{}", user.getId(), notes);
            } catch (RuntimeException e) {
                log.warn("停用用户 {} 的转发补偿失败，下次扫描重试", user.getId(), e);
            }
            cursor = Math.max(cursor, user.getId());
        }
        if (batch.size() < BATCH_SIZE) cursor = 0;
    }

    @Override
    public void destroy() {
        closed.set(true);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) log.warn("停用转发补偿线程仍在退出");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
