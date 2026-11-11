package com.admin.ddns;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域名闸门的本地语义锁定：切换窗口（写锁）与普通下发（读锁）互斥、不同域名互不牵连、
 * 全部按超时抢锁（不会因长网络等待把线程永远挂住）、多域名按字面序加锁（两个方向不会死锁）。
 *
 * <p>互斥断言一律跨线程：{@link java.util.concurrent.locks.ReentrantReadWriteLock} 的读锁在同一线程里
 * 可以越过自己持有的写锁（重入），同线程测不出「切换挡住普通下发」。
 */
class DdnsDomainGateTest {

    private final DdnsDomainGate gate = new DdnsDomainGate();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        DdnsTargetRenderer.reset();
    }

    @Test
    void exclusiveBlocksDispatchOnSameHost() throws Exception {
        try (ThreadLease window = new ThreadLease(() -> gate.exclusive("app.example.com", 0)).await()) {
            assertNull(gate.shared(List.of("app.example.com"), 100), "切换窗口内同一域名不得有普通下发");
            assertNull(gate.sharedForTargets("APP.example.com.:8080", 100), "大小写与尾点不能绕过同一把锁");
        }

        DdnsDomainGate.Lease dispatch = gate.sharedForTargets("app.example.com:8080", 100);
        assertNotNull(dispatch, "窗口关闭后必须能下发");
        dispatch.close();
    }

    @Test
    void otherHostsAreNotBlockedByAWindow() throws Exception {
        try (ThreadLease window = new ThreadLease(() -> gate.exclusive("app.example.com", 0)).await()) {
            DdnsDomainGate.Lease other = gate.shared(List.of("other.example.com"), 100);
            assertNotNull(other, "别的域名不该被这条切换牵连");
            other.close();
        }
    }

    @Test
    void exclusiveTimesOutInsteadOfHanging() throws Exception {
        try (ThreadLease dispatch = new ThreadLease(() -> gate.sharedForTargets("app.example.com:8080", 0)).await()) {
            long start = System.nanoTime();
            DdnsDomainGate.Lease window = gate.exclusive("app.example.com", 120);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertNull(window, "有待完成的下发时切换必须抢不到，而不是排队等下去");
            assertTrue(elapsedMillis < 3000, "必须按超时返回，不能挂住：" + elapsedMillis + "ms");
        }
    }

    @Test
    void zeroWaitMeansTryOnce() {
        // waitMillis=0 是「只试一次」：空闲时必须拿得到，不能被「剩余时间 <= 0」的短路径直接放弃
        DdnsDomainGate.Lease dispatch = gate.sharedForTargets("app.example.com:8080", 0);
        assertNotNull(dispatch, "零等待也必须先试一次");
        dispatch.close();
        DdnsDomainGate.Lease window = gate.exclusive("app.example.com", 0);
        assertNotNull(window);
        window.close();
    }

    @Test
    void leaseCloseIsIdempotent() {
        DdnsDomainGate.Lease window = gate.exclusive("app.example.com", 0);
        assertNotNull(window);
        window.close();
        window.close();
        DdnsDomainGate.Lease again = gate.exclusive("app.example.com", 200);
        assertNotNull(again, "重复 close 不能把锁搞坏");
        again.close();
        assertSame(DdnsDomainGate.Lease.NONE, gate.sharedForTargets("10.0.0.1:8080", 0),
                "IP 直填的目标不需要凭证");
    }

    @Test
    void emptyAndIpTargetsNeedNoLease() {
        assertTrue(DdnsDomainGate.targetHosts(null).isEmpty());
        assertTrue(DdnsDomainGate.targetHosts("  ").isEmpty());
        assertTrue(DdnsDomainGate.targetHosts("10.0.0.1:8080").isEmpty(), "IP 字面量没有 DDNS 语义");
        assertSame(DdnsDomainGate.Lease.NONE, gate.sharedForTargets("10.0.0.1:8080", 0));
        assertEquals(List.of("a.example.com", "b.example.com"),
                DdnsDomainGate.targetHosts(" A.Example.com.:8080 , b.example.com:9090 "));
    }

    @Test
    void twoDispatchOrdersDoNotDeadlock() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<Boolean> first = pool.submit(() -> acquireBoth(List.of("b.example.com", "a.example.com"), barrier));
        Future<Boolean> second = pool.submit(() -> acquireBoth(List.of("a.example.com", "b.example.com"), barrier));
        assertTrue(first.get(5, TimeUnit.SECONDS), "两个方向的多域名下发都必须拿到凭证");
        assertTrue(second.get(5, TimeUnit.SECONDS));
    }

    @Test
    void transitionCannotStartWhileDispatchInFlight() throws Exception {
        DdnsAddressRegistry registry = new DdnsAddressRegistry();
        registry.put("app.example.com", "1.1.1.1");
        try (ThreadLease dispatch = new ThreadLease(() -> registry.beginDispatch("app.example.com:8080", 0)).await()) {
            assertNull(registry.beginTransitionLease("app.example.com", "切换", 120),
                    "普通下发没结束时切换不得开始（否则它的旧规划会被覆盖）");
            assertNull(registry.activeTransition(), "抢不到窗口时不能留下半开的切换位");
        }

        DdnsDomainGate.Lease window = registry.beginTransitionLease("app.example.com", "切换", 500);
        assertNotNull(window, "下发结束后切换必须能开始");
        assertNotNull(registry.activeTransition());
        // 窗口由主线程持有，读锁对同一线程可重入，必须换线程才测得出互斥
        DdnsDomainGate.Lease blocked = pool.submit(() -> registry.beginDispatch("app.example.com:8080", 0))
                .get(5, TimeUnit.SECONDS);
        assertNull(blocked, "切换窗口内普通下发必须被挡");
        window.close();
        assertNull(registry.activeTransition());
        assertFalse(registry.endTransition("other.example.com"), "别的域名不能释放这个窗口");
    }

    private boolean acquireBoth(List<String> hosts, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        DdnsDomainGate.Lease lease = gate.shared(hosts, 2000);
        if (lease == null) {
            return false;
        }
        try {
            Thread.sleep(20); // 让另一线程确实在等同一组域名
            return true;
        } finally {
            lease.close();
        }
    }

    /** 由独立线程持有凭证：写锁的归属线程必须是释放它的那个线程，同线程重入也测不出互斥。 */
    private static final class ThreadLease implements AutoCloseable {

        private final Thread thread;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile DdnsDomainGate.Lease lease;

        ThreadLease(Supplier<DdnsDomainGate.Lease> acquire) {
            thread = new Thread(() -> {
                lease = acquire.get();
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (lease != null) {
                    lease.close();
                }
            }, "ddns-gate-holder");
            thread.setDaemon(true);
            thread.start();
        }

        ThreadLease await() throws InterruptedException {
            assertTrue(held.await(5, TimeUnit.SECONDS), "凭证线程应已启动");
            assertNotNull(lease, "测试前置：凭证应能拿到");
            return this;
        }

        @Override
        public void close() {
            release.countDown();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
