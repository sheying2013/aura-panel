package com.admin.compat;

import com.admin.common.dto.TunnelTopologyDto;
import com.admin.common.lang.R;
import com.admin.mapper.TunnelMapper;
import com.admin.service.impl.TunnelServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 隧道拓扑变更的并发互斥：同一隧道同时只允许一个变更在跑，抢不到的请求必须直接拒绝，
 * 而不是并发地删/建同一批 chains 和入口服务。
 */
class TunnelTopologyLockTest {

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Long, ReentrantLock> locks() throws Exception {
        Field f = TunnelServiceImpl.class.getDeclaredField("TOPOLOGY_LOCKS");
        f.setAccessible(true);
        return (ConcurrentHashMap<Long, ReentrantLock>) f.get(null);
    }

    private static TunnelServiceImpl serviceReturningMissingTunnel() {
        TunnelServiceImpl service = new TunnelServiceImpl();
        TunnelMapper mapper = mock(TunnelMapper.class);
        when(mapper.selectById(any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }

    private static TunnelTopologyDto dto(long id) {
        TunnelTopologyDto dto = new TunnelTopologyDto();
        dto.setId(id);
        return dto;
    }

    @Test
    void concurrentTopologyUpdateOnSameTunnelIsRejected() throws Exception {
        long tunnelId = 900001L;
        // ReentrantLock 可重入：必须在另一个线程持锁，才能模拟"别的请求正在改这条隧道"
        ReentrantLock held = new ReentrantLock();
        java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            held.lock();
            acquired.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                held.unlock();
            }
        });
        holder.setDaemon(true);
        holder.start();
        assertTrue(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try {
            locks().put(tunnelId, held);
            TunnelServiceImpl service = serviceReturningMissingTunnel();
            R result = service.updateTunnelTopology(dto(tunnelId));
            assertNotEquals(0, result.getCode(), "并发变更必须被拒绝而不是继续执行");
            assertTrue(result.getMsg().contains("正在处理拓扑变更"), "拒绝原因要说明是并发：" + result.getMsg());
        } finally {
            release.countDown();
            holder.join(3000);
            locks().remove(tunnelId);
        }
    }

    @Test
    void differentTunnelsDoNotBlockEachOther() throws Exception {
        long busyId = 900002L;
        long freeId = 900003L;
        ReentrantLock held = new ReentrantLock();
        held.lock();
        try {
            locks().put(busyId, held);
            TunnelServiceImpl service = serviceReturningMissingTunnel();
            R result = service.updateTunnelTopology(dto(freeId));
            assertEquals(-1, result.getCode());
            assertTrue(result.getMsg().contains("隧道不存在"), "另一条隧道不应被无关的锁挡住：" + result.getMsg());
        } finally {
            held.unlock();
            locks().remove(busyId);
        }
    }

    @Test
    void lockIsReleasedAndCleanedUpAfterNormalFinish() throws Exception {
        long tunnelId = 900004L;
        TunnelServiceImpl service = serviceReturningMissingTunnel();
        R result = service.updateTunnelTopology(dto(tunnelId));
        assertTrue(result.getMsg().contains("隧道不存在"));
        ReentrantLock lock = locks().get(tunnelId);
        if (lock != null) {
            assertFalse(lock.isLocked(), "正常流程结束后锁必须释放");
        }
        // 释放后再来一次仍能进入业务逻辑（说明没有被永久占住）
        R again = service.updateTunnelTopology(dto(tunnelId));
        assertTrue(again.getMsg().contains("隧道不存在"));
    }
}
