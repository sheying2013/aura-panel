package com.admin.common.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用户 / 隧道资源生命周期的进程内互斥：把「创建转发」与「删除隧道 / 删除用户」的
 * 校验 → 落库 → 节点下发 → 级联清理整段串起来。
 *
 * <p>它补的是删除侧最后一次漂移检查之后的那段窗口：{@code deleteTunnel} / {@code deleteUser}
 * 会按 {@code tunnel_id} / {@code user_id} 把转发行直接 purge 掉，而 {@code createForward}
 * 在「查无重名 → insert → 逐入口节点下发服务」之间没有任何互斥。删除侧读完最后一份快照后，
 * 新建请求照样能把行写进去、把服务建到节点上；随后 purge 把行删掉，节点上就留下一条
 * 库里再也查不到的服务（NodeOrphanSweeper 之外无人认领）。
 *
 * <p><b>键空间</b>：{@code user:<id>} 与 {@code tunnel:<id>}。删除隧道只取隧道键，删除用户只取
 * 用户键，创建转发两种键都要（它既受父隧道的清理约束，也受属主账号的清理约束）。
 *
 * <p><b>为什么不会死锁</b>：
 * <ul>
 *   <li>所有调用点都把「本请求需要的全部键」一次性交给 {@link #acquire}，由本类按字面序去重排序
 *       后逐个加锁，释放逆序 —— 不存在 A 拿着小编号键等大编号键、B 反过来的环；</li>
 *   <li>没有无界等待：每个键都是 {@code tryLock(deadline)}，等不到就整体放弃并返回 null，
 *       调用方直接把本次操作拒掉（一个字节都不改）；</li>
 *   <li>它是三个入口最外层的锁（生命周期 → DDNS 下发闸门 → 节点端口锁），而 DDNS 闸门、
 *       端口锁与同名锁的任何路径都不会反向来取本锁，所以与既有锁不构成环；</li>
 *   <li>重入安全：同一线程重复 {@link #acquire} 同一个键只是持有计数加一，{@link Lease#close()}
 *       按本凭证自己的加锁次数释放。</li>
 * </ul>
 *
 * <p><b>多进程局限</b>：这是进程内锁。多实例部署时两个 JVM 各自持有一份键表，互相看不见 ——
 * 那需要数据库行锁或分布式锁。本类只保证单实例内的顺序，跨进程仍可能出现同样的孤儿窗口；
 * 删除侧的漂移检查（{@code forwardSnapshotDrift}）是唯一还能在跨进程场景下兜住一道的东西，
 * 但它挡不住「检查之后、purge 之前」新插入的行。
 */
public final class ResourceLifecycleLocks {

    /** 删除侧等待上限：删除必须能压过正在飞的一次创建（创建最长约等于入口节点数 × 单次下发 10s）。 */
    public static final long DELETE_WAIT_MILLIS = 30_000L;

    /** 创建侧等待上限：宁可让这次创建失败重试，也不要把请求线程挂在一整条级联删除上。 */
    public static final long CREATE_WAIT_MILLIS = 10_000L;

    /**
     * 键 → 锁。键的数量受用户数 + 隧道数约束（与 ForwardServiceImpl 的节点端口锁同口径），
     * 因此不做「用完就摘」：摘掉的那一刻可能有别的线程正拿旧对象排队，会出现两把锁守同一个键。
     */
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ResourceLifecycleLocks() {
    }

    /** 用户键；id 为空时返回 null（{@link #acquire} 会跳过 null 键）。 */
    public static String userKey(Number userId) {
        return userId == null ? null : "user:" + userId.longValue();
    }

    /** 用户隧道权限键，与账号、隧道和转发键保持独立。 */
    public static String userTunnelKey(Number userTunnelId) {
        return userTunnelId == null ? null : "user-tunnel:" + userTunnelId.longValue();
    }

    /** 隧道键；id 为空时返回 null（{@link #acquire} 会跳过 null 键）。 */
    public static String tunnelKey(Number tunnelId) {
        return tunnelId == null ? null : "tunnel:" + tunnelId.longValue();
    }

    /**
     * 按全局顺序取齐全部键。拿不到（等待超时/被中断）就释放本次已经拿到的部分并返回 null，
     * 调用方必须把 null 当成「本次什么都没做」。
     */
    public static Lease acquire(long waitMillis, String... keys) {
        List<String> ordered = orderedKeys(keys);
        if (ordered.isEmpty()) {
            return Lease.NONE;
        }
        List<ReentrantLock> acquired = new ArrayList<>(ordered.size());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
        for (String key : ordered) {
            ReentrantLock lock = LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
            long remaining = deadline - System.nanoTime();
            try {
                if (!lock.tryLock(Math.max(0L, remaining), TimeUnit.NANOSECONDS)) {
                    release(acquired);
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                release(acquired);
                return null;
            }
            acquired.add(lock);
        }
        return new HeldLease(acquired);
    }

    /** 去重 + 升序：所有调用点共用这一份顺序，加锁就不可能成环。 */
    private static List<String> orderedKeys(String... keys) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (keys != null) {
            for (String key : keys) {
                if (key != null && !key.isEmpty()) {
                    unique.add(key);
                }
            }
        }
        List<String> ordered = new ArrayList<>(unique);
        ordered.sort(String::compareTo);
        return ordered;
    }

    private static void release(List<ReentrantLock> acquired) {
        for (int i = acquired.size() - 1; i >= 0; i--) {
            acquired.get(i).unlock();
        }
    }

    /** 一次加锁的凭证；close() 幂等，可以安全地放在 finally 里。 */
    public interface Lease extends AutoCloseable {

        /** 没有任何键（例如 id 为空）时的空凭证。 */
        Lease NONE = () -> {
        };

        @Override
        void close();
    }

    private static final class HeldLease implements Lease {

        private final List<ReentrantLock> held;
        private final AtomicBoolean closed = new AtomicBoolean();

        private HeldLease(List<ReentrantLock> held) {
            this.held = held;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(held);
            }
        }
    }
}
