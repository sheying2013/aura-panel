package com.admin.ddns;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 域名 → 当前主地址的进程内映射。
 *
 * <p>它是「域名热更新」真正生效的位置：GostUtil 在把 forward.remoteAddr 渲染成
 * forwarder.nodes[].addr 时经过这里，所以已经把域名替换成当前 IP 的转发规则，
 * 在重下发时会得到新 IP，而不是继续把域名交给 GOST 的 resolver 缓存。
 *
 * <p>映射本身不持久化，重启时由 {@link DdnsService#loadRegistry()} 从 ddns_record
 * 恢复；恢复前 GostUtil 仍按域名下发，属于可接受的短窗口（GOST 自己也会解析）。
 *
 * <p><b>装载状态（本类同时是「映射是否可信」的唯一判据）</b>：映射没装载好（启动窗口 /
 * 读库失败）或库里某个域名还没有可用主地址时，节点上报的摘要与按库算出的期望值天然不相等，
 * 拿它判漂移就会把节点上还能用的配置删掉重建。因此这里记录装载状态与「已知但没有主地址」
 * 的域名，供 {@link DdnsTargetRenderer#anyDigestTrustIssue(String)} 在动手前先问一句。
 *
 * <p><b>切换窗口（P0 竞态）</b>：DDNS 在节点确认之前绝不能把新地址写进这里。同时，
 * 只要某域名处在「已把新地址推给节点、但尚未全部确认」的窗口内，节点上的真实目标就不等于
 * 注册表里的旧值 —— 此时任何普通转发编辑/删除/状态变更/对账若按注册表渲染，都会把节点
 * 改回旧地址，属于静默回滚。因此这里维护一个进程级、只允许一个的切换位：
 * 窗口内该域名（含与其他域名共享的同一条 forward）的 {@link #render} 直接抛
 * {@link DdnsTransitionBusyException}，DDNS 自己则用
 * {@link #renderTargets(String, String, String)} 显式携带本窗口要下发的地址绕过注册表。
 */
@Component
public class DdnsAddressRegistry {

    private final ConcurrentHashMap<String, String> byDomain = new ConcurrentHashMap<>();
    /**
     * 库里登记过的域名（含还没有主地址的那些）→ 最近一次写入的序号。
     *
     * <p>它把「没有主地址」和「压根不是 DDNS 域名」分开；序号是给 {@link #markLoaded} 用的：
     * 只有「上次装载时就存在、之后没人再写过」的域名才允许被当作残留清掉，见那里的并发说明。
     */
    private final ConcurrentHashMap<String, Long> known = new ConcurrentHashMap<>();
    /** put/remove 每次取一个单调递增的号，用来判断「这一项在这次装载之后有没有被别的线程动过」。 */
    private final AtomicLong writeSeq = new AtomicLong();
    /** 上一次 {@link #markLoaded} 结束时各域名的写入序号（整体替换的只读快照）。 */
    private final AtomicReference<Map<String, Long>> observed = new AtomicReference<>(Map.of());
    private final AtomicReference<Transition> transition = new AtomicReference<>();
    /** 切换窗口的专属凭证：拿到它才持有该域名的写锁（见 {@link DdnsDomainGate}）。 */
    private final AtomicReference<DdnsDomainGate.Lease> transitionLease = new AtomicReference<>();
    /** 只用来串行化「抢占/释放切换位」这一小段状态变更，绝不在里面做网络下发。 */
    private final Object transitionBook = new Object();
    private volatile Thread transitionOwner;
    private final DdnsDomainGate gate = new DdnsDomainGate();
    private final AtomicReference<LoadState> loadState = new AtomicReference<>(LoadState.NOT_LOADED);
    private volatile String loadError;

    public DdnsAddressRegistry() {
        DdnsTargetRenderer.install(this::render);
        DdnsTargetRenderer.installTransitionProbe(this::isTransitioningHost);
        DdnsTargetRenderer.installDigestTrust(this::digestTrustIssue, this::isDegraded, this::isReady);
        DdnsTargetRenderer.installDispatch(this::beginDispatch);
    }

    /** 正在进行的切换：域名 + 说明（写进拒绝文案，说明为什么不能下发）。 */
    public record Transition(String domain, String note) {
    }

    /**
     * 映射装载状态：{@link #NOT_LOADED} 是进程刚起、bootstrap 还没跑；
     * {@link #FAILED} 是跑过但读库失败；{@link #READY} 才代表「空表之外的一切都查过了」。
     */
    public enum LoadState {
        NOT_LOADED,
        READY,
        FAILED
    }

    /**
     * 抢占全局切换位。同时只允许一个域名处于未确认窗口，跨域名共享同一条 forward 时
     * 才不会出现「A 窗口内 B 按旧值下发」的交叉回滚。
     *
     * <p>除了切换位本身，还会拿到该域名的写锁：真正的互斥来自这把锁，切换位只是文案与
     * 快速失败用的标记。因此它也可能因为「正在有普通下发持有读锁」而失败。
     *
     * @return 抢占成功才返回 true；已有别的切换在跑、或正在进行的下发等不到时返回 false，
     *         调用方必须放弃本次变更
     */
    public boolean beginTransition(String domain, String note) {
        return beginTransitionLease(domain, note, DdnsDomainGate.DEFAULT_TRANSITION_WAIT_MILLIS) != null;
    }

    /**
     * 抢占切换窗口并<b>持有</b>它：返回的凭证在 {@link DdnsDomainGate.Lease#close()} 时释放。
     *
     * <p>调用方必须在拿到窗口<b>之后</b>再规划目标（planTargets）并下发，否则用的还是切换前的
     * 快照；窗口一直持有到落库/回滚结束，普通编辑与自动对账在整段时间内都拿不到读锁。
     *
     * @return null 表示抢不到（已有别的切换，或有普通下发正在飞、等待超时），调用方不得改动任何节点
     */
    public DdnsDomainGate.Lease beginTransitionLease(String domain, String note) {
        return beginTransitionLease(domain, note, DdnsDomainGate.DEFAULT_TRANSITION_WAIT_MILLIS);
    }

    public DdnsDomainGate.Lease beginTransitionLease(String domain, String note, long waitMillis) {
        if (domain == null || domain.isEmpty()) {
            return null;
        }
        String key = lookupHost(domain);
        if (key.isEmpty()) {
            return null;
        }
        synchronized (transitionBook) {
            if (transition.get() != null) {
                return null;
            }
        }
        DdnsDomainGate.Lease lease = gate.exclusive(key, waitMillis);
        if (lease == null) {
            return null;
        }
        synchronized (transitionBook) {
            if (transition.get() != null) {
                lease.close();
                return null;
            }
            synchronized (this) {
                transitionOwner = Thread.currentThread();
                transition.set(new Transition(key, note == null ? "" : note));
                writeSeq.incrementAndGet();
            }
            transitionLease.set(lease);
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                endTransition(key);
            }
        };
    }

    /** 释放切换位（只允许释放自己抢到的那个域名）。释放写锁不会阻塞，不会形成死锁环。 */
    public boolean endTransition(String domain) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        String key = lookupHost(domain);
        synchronized (transitionBook) {
            Transition current = transition.get();
            if (current == null || !current.domain().equals(key)) {
                return false;
            }
            synchronized (this) {
                transition.set(null);
                transitionOwner = null;
                writeSeq.incrementAndGet();
            }
            DdnsDomainGate.Lease lease = transitionLease.getAndSet(null);
            if (lease != null) {
                lease.close();
            }
            return true;
        }
    }

    /**
     * 普通下发的共享凭证：remoteAddr（逗号分隔）覆盖到的全部域名统一加读锁。
     *
     * <p>必须在「检查完切换标记、真正开始删/建/改节点服务之前」拿到它，并持有到该次下发的
     * 删除、重建、补偿全部结束 —— 否则切换可能在中间插进来，把节点改成两套配置。
     *
     * @return null 表示有域名正在切换窗口（或排队超时），调用方必须不动任何节点
     */
    public DdnsDomainGate.Lease beginDispatch(String remoteAddr) {
        return beginDispatch(remoteAddr, DdnsDomainGate.DEFAULT_DISPATCH_WAIT_MILLIS);
    }

    public DdnsDomainGate.Lease beginDispatch(String remoteAddr, long waitMillis) {
        DdnsDomainGate.Lease lease = gate.sharedForTargets(remoteAddr, waitMillis);
        if (lease == null) {
            return null;
        }
        // 写锁拥有者可以在同一线程重入读锁；此时渲染仍会拒绝切换域名，
        // 因此必须在删除或更新节点之前放弃下发，而不是先删后在渲染时失败。
        for (String host : DdnsDomainGate.targetHosts(remoteAddr)) {
            if (isTransitioningHost(host)) {
                lease.close();
                return null;
            }
        }
        return lease;
    }

    public Transition activeTransition() {
        return transition.get();
    }

    public boolean isTransitioning(String domain) {
        Transition current = transition.get();
        return current != null && domain != null && current.domain().equals(lookupHost(domain));
    }

    /** target 的 host 是否正好是正在切换的域名。 */
    public boolean isTransitioningHost(String host) {
        Transition current = transition.get();
        if (current == null || host == null || host.isEmpty()) {
            return false;
        }
        return current.domain().equals(lookupHost(host));
    }

    /** 把 target（host 或 host:port）里的已注册域名替换成当前主地址。窗口内拒绝渲染。 */
    public synchronized String render(String target) {
        if (target == null || target.isEmpty()) {
            return target;
        }
        String host = DdnsAddresses.hostOf(target);
        if (host.isEmpty()) {
            return target;
        }
        String key = lookupHost(host);
        Transition current = transition.get();
        if (current != null && current.domain().equals(key)) {
            throw new DdnsTransitionBusyException(current.domain(), current.note());
        }
        return substitute(target, host, byDomain.get(key));
    }

    /**
     * 用 {@code replacement} 顶替 domain 的登记值渲染 target，其余域名仍按<b>已确认</b>的映射解析。
     *
     * <p>DDNS 下发窗口专用：{@code replacement} 是新主地址（apply）、旧主地址（回滚）或
     * {@code null}（删除回落到域名本身）；它不读、也不改这个域名的登记值。
     */
    public synchronized String renderWith(String target, String domain, String replacement) {
        if (target == null || target.isEmpty()) {
            return target;
        }
        String host = DdnsAddresses.hostOf(target);
        if (host.isEmpty()) {
            return target;
        }
        String key = lookupHost(host);
        if (domain != null && !domain.isEmpty() && key.equals(lookupHost(domain))) {
            return substitute(target, host, replacement);
        }
        String ip = byDomain.get(key);
        if (ip == null) {
            return target;
        }
        return substitute(target, host, ip);
    }

    /** 按 {@link #renderWith} 的口径渲染整条 remoteAddr（逗号分隔，丢弃空项）。 */
    public synchronized List<String> renderTargets(String remoteAddr, String domain, String replacement) {
        List<String> targets = new ArrayList<>();
        if (remoteAddr == null) {
            return targets;
        }
        for (String addr : remoteAddr.split(",")) {
            String trimmed = addr == null ? "" : addr.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            targets.add(renderWith(trimmed, domain, replacement));
        }
        return targets;
    }

    private static String substitute(String target, String host, String ip) {
        if (!DdnsAddresses.isIpLiteral(ip)) {
            return target;
        }
        String rendered = DdnsAddresses.formatHost(ip);
        if (target.equals(host)) {
            return rendered;
        }
        if (target.startsWith(host)) {
            return rendered + target.substring(host.length());
        }
        return target;
    }

    /** 用户可能把域名写成带尾点或大写；查表前统一成规范化域名。 */
    private static String lookupHost(String host) {
        String value = DdnsDomainGate.normalizeHost(host);
        try {
            return DdnsAddresses.normalizeDomain(value);
        } catch (IllegalArgumentException ignored) {
            // IP 或不合法 host 保持原样，后续不会命中合法域名的登记项。
            return value;
        }
    }

    /**
     * 登记一个域名的主地址。域名一律记进 {@link #known}：主地址还不是合法 IP 时
     * 只留「已知未解析」，这样对账才能区分「这条记录还没解析过」和「这个域名不归 DDNS 管」。
     *
     * <p>同一域名重装载时（{@code loadRegistry} 复用同一个注册表），非法主地址必须把上一轮的
     * 旧值一起撤掉：否则旧 IP 会继续参与渲染，而域名同时留在 {@link #known} 与
     * {@code byDomain} 里，{@link #unresolvedDomains()} 与 {@link #isDegraded()} 都不会报警，
     * 对账就把上一轮的旧 IP 当成了本轮的期望值。
     */
    public synchronized void put(String domain, String primaryIp) {
        if (domain == null || domain.isEmpty()) {
            return;
        }
        String key = lookupHost(domain);
        if (key.isEmpty()) {
            return;
        }
        // 先登记序号再写值：清残留的一方看到新序号，就一定能在 byDomain 里看到（或即将看到）新值。
        known.put(key, writeSeq.incrementAndGet());
        if (!DdnsAddresses.isIpLiteral(primaryIp)) {
            byDomain.remove(key);
            return;
        }
        byDomain.put(key, primaryIp.trim().toLowerCase(Locale.ROOT));
    }

    /** 记录被删掉（或回滚前清理）时连「已知」一起撤掉，免得健康的空表被当成有未解析域名。 */
    public synchronized void remove(String domain) {
        if (domain == null || domain.isEmpty()) {
            return;
        }
        String key = lookupHost(domain);
        writeSeq.incrementAndGet();
        byDomain.remove(key);
        known.remove(key);
    }

    /** 查询数据库之前读取版本；切换中不能获取可靠快照。 */
    public synchronized long snapshotVersion() {
        return transition.get() == null || transitionOwner == Thread.currentThread() ? writeSeq.get() : -1L;
    }

    /** 仅当数据库查询期间没有本进程写入或切换时，整体安装已查询结果。 */
    public synchronized boolean applyLoadedSnapshot(long version, Map<String, String> entries) {
        if (version < 0 || (transition.get() != null && transitionOwner != Thread.currentThread())
                || writeSeq.get() != version) {
            return false;
        }
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
        // 保留最近刚写入、还不在上次装载快照中的项；旧残留按原有规则清理。
        markLoaded(entries.keySet());
        // 空快照同样推进版本，防止并行加载互相覆盖。
        writeSeq.incrementAndGet();
        return true;
    }

    public synchronized String get(String domain) {
        return domain == null ? null : byDomain.get(lookupHost(domain));
    }

    public LoadState loadState() {
        return loadState.get();
    }

    /** 映射确实从库里查过一遍（哪怕是空表）。只有它为 true 时摘要才允许参与漂移判定。 */
    public synchronized boolean isReady() {
        return loadState.get() == LoadState.READY;
    }

    /** 装载失败的原因；没有失败时为 null。 */
    public String loadError() {
        return loadError;
    }

    public synchronized int knownSize() {
        return known.size();
    }

    /** 库里登记过、但还没有可用主地址的域名（排序后返回，便于日志与断言稳定）。 */
    public synchronized List<String> unresolvedDomains() {
        List<String> unresolved = new ArrayList<>();
        for (String domain : known.keySet()) {
            if (!byDomain.containsKey(domain)) {
                unresolved.add(domain);
            }
        }
        Collections.sort(unresolved);
        return unresolved;
    }

    /** 只要有任何一个域名查不出主地址，按注册表渲染出的期望摘要就不可信。 */
    public synchronized boolean isDegraded() {
        return loadState.get() != LoadState.READY || !unresolvedDomains().isEmpty();
    }

    /**
     * 装载成功：用这次查库的结果整体替换「已知域名」，空表也是合法结果；上一轮登记过、这一轮库里
     * 已经没有的域名，连 {@code byDomain} 里的旧主地址一起撤掉。
     *
     * <p>不撤的后果是「静默沿用已删除域名的地址」：{@code render} 继续把旧 IP 渲染进节点目标，
     * 而域名已不在「已知」里，{@link #unresolvedDomains()}、{@link #isDegraded()} 与
     * {@link #digestTrustIssue(String)} 都不报警，对账反而把旧 IP 当成可信期望值。
     *
     * <p><b>并发 create/check/delete 交错</b>：查库与这里的清扫之间，别的线程可能已经
     * {@link #put} 了一个「库里刚建好、这次快照没读到」的域名（创建后立刻解析），也可能
     * {@link #remove} 了另一个。只拿 {@code domains} 做 retainAll/删差集会把这个新域名当残留删掉，
     * 结果是节点上是新 IP、注册表退回域名，下一次对账又按域名重写节点。因此这里只清
     * 「上一次 {@code markLoaded} 时就在表里、且那之后没再被写过」的域名：本轮新写入的一律保留，
     * 等下一轮装载时它要么已在库里（自然留下），要么确实已删且期间无人写过（那时才清）。
     * 清理与 put/remove 共用实例锁，检查序号到删除的整个区间不与并发写入交错。
     *
     * @param domains 本次查到的全部域名（含还没有主地址的；主地址本身由 {@link #put} 登记）
     */
    public synchronized void markLoaded(Collection<String> domains) {
        Set<String> fresh = new LinkedHashSet<>();
        if (domains != null) {
            for (String domain : domains) {
                if (domain != null && !domain.trim().isEmpty()) {
                    fresh.add(lookupHost(domain));
                }
            }
        }
        for (String key : new ArrayList<>(known.keySet())) {
            if (!fresh.contains(key) && untouchedSinceLastLoad(key)) {
                evictStale(key);
            }
        }
        observed.set(observedStamps());
        loadError = null;
        loadState.set(LoadState.READY);
    }

    /** 这一项自上次装载以来没人写过：序号还在上次装载记录的快照里。 */
    private boolean untouchedSinceLastLoad(String key) {
        Long loaded = observed.get().get(key);
        return loaded != null && loaded.equals(known.get(key));
    }

    /** 条件删除：值/序号被别人换过就放手，宁可多留一轮，也不删掉并发写入的新登记。 */
    private void evictStale(String key) {
        String ip = byDomain.get(key);
        if (ip != null) {
            byDomain.remove(key, ip);
        }
        Long stamp = known.get(key);
        if (stamp != null) {
            known.remove(key, stamp);
        }
    }

    /** 本轮装载结束时的序号快照；并发写入的项可能落在这里面，那也只是让它在下一轮更早被判为残留。 */
    private Map<String, Long> observedStamps() {
        Map<String, Long> stamps = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : known.entrySet()) {
            stamps.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(stamps);
    }

    /** 装载失败：保留失败原因，摘要对账必须停手（直到重试成功）。 */
    public void markLoadFailed(String message) {
        loadError = message == null || message.trim().isEmpty() ? "未知原因" : message.trim();
        loadState.set(LoadState.FAILED);
    }

    /**
     * 这条目标地址能不能用来判「节点摘要漂移」。
     *
     * @return null 表示可信；非 null 是不可信的原因（写进日志，说明为什么这一轮不做删建）
     */
    public synchronized String digestTrustIssue(String host) {
        if (host == null || host.trim().isEmpty()) {
            return null;
        }
        String key = lookupHost(host);
        if (key.isEmpty() || DdnsAddresses.isIpLiteral(key)) {
            // 字面量 IP 不经过映射，装载状态与它无关
            return null;
        }
        LoadState state = loadState.get();
        if (state == LoadState.FAILED) {
            return "DDNS 映射装载失败（" + loadError + "），无法确定域名 " + key + " 当前应展开成哪个地址";
        }
        if (state != LoadState.READY) {
            return "DDNS 映射尚未装载完成，无法确定域名 " + key + " 当前应展开成哪个地址";
        }
        if (known.containsKey(key) && !byDomain.containsKey(key)) {
            return "DDNS 域名 " + key + " 尚未解析出可用主地址";
        }
        return null;
    }

    public synchronized int size() {
        return byDomain.size();
    }

    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(byDomain);
    }

    public synchronized void clear() {
        writeSeq.incrementAndGet();
        byDomain.clear();
        known.clear();
        observed.set(Map.of());
        loadError = null;
        loadState.set(LoadState.NOT_LOADED);
    }
}
