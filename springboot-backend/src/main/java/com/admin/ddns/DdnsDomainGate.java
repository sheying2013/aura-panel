package com.admin.ddns;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 域名级下发闸门：把「DDNS 切换窗口（规划 → 逐个节点确认 → 落库）」与「所有普通下发」真正互斥。
 *
 * <p>只用「是否正在切换」的布尔标志是不够的：普通编辑在检查标志之后、真正把请求发到节点之前
 * 仍可能被执行线程切换打断，此时切换已经开始推新地址，普通下发按旧快照渲染就又把节点改回旧值
 * （静默回滚）；反过来，切换刚放开标志、普通编辑才开始删服务，也会和切换的下发交叉。
 *
 * <p>因此这里对每个域名维护一把公平的读写锁：
 * <ul>
 *   <li><b>写锁（独占）= 切换窗口</b>：从规划目标开始，到落库/回滚结束为止，全程持有；</li>
 *   <li><b>读锁（共享）= 普通下发</b>：编辑、删除、状态变更后的重建、隧道类型重建、自动对账、
 *       节点重挂、以及失败补偿的实际下发，都必须先拿到读锁再动手。</li>
 * </ul>
 * 普通下发之间仍然并发（它们彼此由既有的按节点端口锁/同名锁串行），只有与切换互斥。
 *
 * <p><b>为什么不会死锁</b>：所有加锁都是 {@code tryLock(timeout)}，没有任何无界等待；
 * 多域名时按规范化域名的字面序逐个加锁，释放逆序，加锁顺序全局一致；切换线程自身从不反向
 * 再去等普通下发持有的其它资源（它只等本域名的写锁）。因此最坏情况只是「等不到 → 放弃本次
 * 操作并给出提示」，不会把请求线程永久挂住，也不会在长网络等待期间形成环。
 *
 * <p><b>多进程局限</b>：这是进程内锁。多实例部署（两个 JVM 同时操作同一批节点）或与其它
 * 服务共享节点时，它不能提供跨进程互斥 —— 那需要数据库行锁/分布式锁。本类只保证单实例内的
 * 下发顺序，跨进程仍可能出现交错，运维上应避免多实例同时执行 DDNS 与转发变更。
 */
public final class DdnsDomainGate {

    /** 普通下发等待上限：切换窗口在位时尽快失败，不让请求线程排队。 */
    public static final long DEFAULT_DISPATCH_WAIT_MILLIS = 2000L;
    /** 切换窗口等待上限：等正在飞的下发收尾；等不到就放弃本次切换（不无限期挂住调度线程）。 */
    public static final long DEFAULT_TRANSITION_WAIT_MILLIS = 5000L;

    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    /** 一次加锁的凭证；close() 幂等，可以安全地在 finally 里反复调用。 */
    public interface Lease extends AutoCloseable {

        /** 不做任何事、永远可关闭的凭证（非 DDNS 目标 / 没有安装闸门时使用）。 */
        Lease NONE = () -> {
        };

        @Override
        void close();
    }

    /** 独占（写）：DDNS 切换窗口。拿不到返回 null，调用方必须放弃本次变更。 */
    public Lease exclusive(String host, long waitMillis) {
        String key = normalizeHost(host);
        if (key.isEmpty() || DdnsAddresses.isIpLiteral(key)) {
            return Lease.NONE;
        }
        ReentrantReadWriteLock lock = lockFor(key);
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        try {
            if (!writeLock.tryLock(Math.max(0L, waitMillis), TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return new HeldLease(writeLock::unlock);
    }

    /** 共享（读）：普通下发。任一域名拿不到就整体放弃，返回 null。 */
    public Lease shared(Collection<String> hosts, long waitMillis) {
        List<String> keys = sortedKeys(hosts);
        if (keys.isEmpty()) {
            return Lease.NONE;
        }
        List<ReentrantReadWriteLock> acquired = new ArrayList<>(keys.size());
        long waitNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
        long deadline = System.nanoTime() + waitNanos;
        for (String key : keys) {
            ReentrantReadWriteLock lock = lockFor(key);
            long remaining = deadline - System.nanoTime();
            try {
                // waitMillis=0 是「只试一次」，不是「立刻放弃」：remaining 在进入循环时就已经 <= 0，
                // 直接按那句短路径判断会让零等待的下发永远拿不到锁。
                boolean locked = waitNanos <= 0L
                        ? lock.readLock().tryLock()
                        : lock.readLock().tryLock(Math.max(0L, remaining), TimeUnit.NANOSECONDS);
                if (!locked) {
                    releaseAll(acquired);
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                releaseAll(acquired);
                return null;
            }
            acquired.add(lock);
        }
        return new HeldLease(() -> releaseAll(acquired));
    }

    /** remoteAddr（逗号分隔）覆盖到的全部域名加共享锁。 */
    public Lease sharedForTargets(String remoteAddr, long waitMillis) {
        return shared(targetHosts(remoteAddr), waitMillis);
    }

    /** 把 remoteAddr 拆成规范化域名：跳过空项与字面量 IP（它们不经过注册表，无需互斥）。 */
    public static List<String> targetHosts(String remoteAddr) {
        List<String> hosts = new ArrayList<>();
        if (remoteAddr == null) {
            return hosts;
        }
        for (String addr : remoteAddr.split(",")) {
            String trimmed = addr == null ? "" : addr.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String host = DdnsAddresses.hostOf(trimmed);
            if (host.isEmpty()) {
                continue;
            }
            String key = normalizeHost(host);
            if (key.isEmpty() || DdnsAddresses.isIpLiteral(key)) {
                continue;
            }
            hosts.add(key);
        }
        return hosts;
    }

    /** 与 DDNS 注册表共享 IDNA 规范化，避免 Unicode 别名绕过域名锁。 */
    public static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim();
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        value = value.toLowerCase(Locale.ROOT);
        try {
            return DdnsAddresses.normalizeDomain(value);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private static List<String> sortedKeys(Collection<String> hosts) {
        TreeSet<String> keys = new TreeSet<>();
        if (hosts != null) {
            for (String host : hosts) {
                String key = normalizeHost(host);
                if (key.isEmpty() || DdnsAddresses.isIpLiteral(key)) {
                    continue;
                }
                keys.add(key);
            }
        }
        return new ArrayList<>(keys);
    }

    private ReentrantReadWriteLock lockFor(String key) {
        return locks.computeIfAbsent(key, ignored -> new ReentrantReadWriteLock(true));
    }

    private static void releaseAll(List<ReentrantReadWriteLock> acquired) {
        for (int i = acquired.size() - 1; i >= 0; i--) {
            acquired.get(i).readLock().unlock();
        }
    }

    private static final class HeldLease implements Lease {

        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private HeldLease(Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }
}
