package com.admin.common.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单条转发的「写窗口」互斥：把编辑下发与自动对账在同一 forwardId 上串起来。
 *
 * <p>它补的窗口：{@code updateForward}（ForwardServiceImpl :1394 → :1437）是「先下发、后落库」——
 * 节点已按新目标生效，库里还是旧快照；同一时刻 {@code resyncForwardServices}（:3208）用
 * {@code this.getById} 读到旧快照，算出的期望摘要与节点上报对不上，就判漂移 → 删服务 → 按旧快照重建。
 * 结果是「库新、节点旧」：面板显示新目标，流量走旧目标，全程没有任何报错。
 *
 * <p><b>键空间</b>：{@code forward:<id>}，一个 forwardId 一把锁。不同转发之间完全不排队 ——
 * 对账逐条遍历，某一条被编辑占住只跳过它自己，同一轮里的其它转发照常处理。
 *
 * <p><b>两侧语义不同</b>：
 * <ul>
 *   <li>编辑侧 {@link #acquire}：等待上限 {@link #EDIT_WAIT_MILLIS}。用户的编辑要能压过一次正在飞的对账；
 *       拿不到才拒（宁可让用户重试，也不要把 Web 请求线程挂在整轮对账上）；</li>
 *   <li>对账侧 {@link #tryAcquire}：零等待。对账是后台自愈，永远可以「下一轮再说」；
 *       它一等就变成反向阻塞编辑，还会把一轮对账拖长。</li>
 * </ul>
 *
 * <p><b>对账侧必须在锁内重读库</b>：本类只提供互斥。{@code getById} 若仍留在取锁之前，
 * 读到的是锁外旧快照，之后拿着锁按旧快照算期望值、删服务、重建，互斥等于没做。
 * 调用点因此把 {@code getById} 放在取锁之后（ForwardServiceImpl :3208 一带）。
 *
 * <p><b>为什么不会死锁</b>：
 * <ul>
 *   <li>只有一把键，不存在「A 持 1 等 2、B 持 2 等 1」的环；</li>
 *   <li>锁序固定为「转发写锁 → DDNS 下发闸门 → 名称锁 / 节点端口锁」：编辑与对账都在取 DDNS 凭证之前
 *       先取本锁；DDNS 闸门、名称锁、端口锁的任何路径都不会反向来取本锁，与既有锁不构成环；</li>
 *   <li>等待有界：编辑 10s、对账 0，没有无界阻塞；</li>
 *   <li>释放幂等：{@link Lease#close()} 可以在 finally 里重复调用。</li>
 * </ul>
 *
 * <p><b>重入是被拒绝的，不是被放行的</b>：同一线程已经持有该 id 时 {@link #acquire} 返回 null。
 * 可重入在这里是危险的 —— 外层（编辑）把旧快照带进锁之后，内层（对账）重入进来照样按旧快照重建，
 * 互斥形同不存在且不会有任何报错。当前调用图里编辑不会触发对账、对账也不会回调编辑；
 * 一旦将来出现这种嵌套，表现是「对账跳过 / 编辑被拒」，可观测，而不是静默失效。
 *
 * <p><b>多进程局限</b>：进程内锁。多实例部署时两个 JVM 各持一份键表、互相看不见，
 * 那需要数据库行锁或分布式锁。本类只保证单实例内编辑与对账不在同一条转发上交错。
 */
public final class ForwardWriteLocks {

    /** 编辑侧等待上限：对账在单条转发上通常是「删 + 建」两次下发，等它走完比让用户重试更划算。 */
    public static final long EDIT_WAIT_MILLIS = 10_000L;

    /**
     * forwardId → 锁。键的数量受转发条数约束，与 {@code NODE_PORT_LOCKS} 同口径，因此不做「用完就摘」：
     * 摘掉的那一刻可能有别的线程正拿旧对象排队，会出现两把锁守同一个键。
     */
    private static final ConcurrentHashMap<Long, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ForwardWriteLocks() {
    }

    /**
     * 取该转发的写窗口凭证。
     *
     * @param waitMillis 等待上限；0 表示只试一次
     * @param forwardId  转发 id；为空或非正数时返回 {@link Lease#NONE}（没有键可守，调用点行为不变）
     * @return 凭证；等待超时、被中断、或同线程重入时返回 null，调用方必须把 null 当成「本次什么都没做」
     */
    public static Lease acquire(long waitMillis, Long forwardId) {
        if (forwardId == null || forwardId <= 0L) {
            return Lease.NONE;
        }
        ReentrantLock lock = LOCKS.computeIfAbsent(forwardId, ignored -> new ReentrantLock());
        // 重入一律拒绝：见类注释「重入是被拒绝的」
        if (lock.isHeldByCurrentThread()) {
            return null;
        }
        boolean locked;
        try {
            locked = lock.tryLock(Math.max(0L, waitMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return locked ? new HeldLease(lock) : null;
    }

    /** 对账侧零等待试取：拿不到就说明这条转发正在被编辑，本轮跳过它。 */
    public static Lease tryAcquire(Long forwardId) {
        return acquire(0L, forwardId);
    }

    /** 一次加锁的凭证；close() 幂等，可以安全地放在 finally 里。 */
    public interface Lease extends AutoCloseable {

        /** 没有键（id 为空）时的空凭证。 */
        Lease NONE = () -> {
        };

        @Override
        void close();
    }

    private static final class HeldLease implements Lease {

        private final ReentrantLock lock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private HeldLease(ReentrantLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                lock.unlock();
            }
        }
    }
}
