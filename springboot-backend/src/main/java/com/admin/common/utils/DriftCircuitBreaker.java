package com.admin.common.utils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置漂移「删除 + 重建」失败的熔断器。
 *
 * <p>动机：对账靠「按库算出的摘要」与「节点上报的摘要」逐字比对判漂移。两侧口径只要有一处
 * 对不上（节点侧不可控字段、老节点残留、重建后节点又换了写法、或者库里的监听地址在节点上根本
 * 不存在），每轮心跳都会判定漂移 → 删服务 → 重建失败，业务长连接被反复掐断且永不自愈。
 * 熔断器把「同一条资源连续失败」的次数记住，连续 {@code threshold} 次就锁死 {@code cooldownMs}，
 * 期间只记说明、不再动节点。
 *
 * <p>语义（2026-11-08 按 QA 审计修正）：
 * <ul>
 *   <li>{@link #allowAttempt(String)}：未熔断 → true；冷却中 → false；</li>
 *   <li><b>半开只放行一次探测</b>：冷却到期时只把冷却窗口顺延一个周期并返回 true，<b>不清零计数</b>。
 *       同一轮里连续多次询问只有第一次能拿到 true；探测仍然失败则计数继续累加、重新冷却，
 *       不会出现「到期即清零、每轮巡检都重新放开」的假熔断（旧实现与 NodeReconcileTask 的
 *       5 分钟周期同频，等于完全没熔断）；</li>
 *   <li>成功（{@link #recordMatch(String)}）才清条目，条目不会因为冷却到期而消失；</li>
 *   <li>容量上限 {@link #DEFAULT_MAX_STATES}：超限先淘汰「未熔断且长期没动」的条目，再按
 *       lastTouched 从旧到新淘汰，避免「只漂移不成功」的 key（转发被删）无限堆积；</li>
 *   <li>{@link #snapshot()} 暴露给管理端，运维能看到哪条资源被熔断、连续几次、还剩多久、
 *       库与节点的摘要分别是什么；</li>
 *   <li>管理端手动放行（{@link #release(String)} / {@link #releaseByNode(Long)} / {@link #releaseAll()}）：
 *       删条目即视为「从没失败过」，把节点侧写法规整后下一次巡检立刻重试，不必干等冷却到期。</li>
 * </ul>
 *
 * <p>线程安全：心跳回调是 Netty 线程池并发进来的，同一 key 的读改写全部在 state 对象上
 * synchronized（{@link ConcurrentHashMap} 只负责定位条目），计数不丢，也不会两个线程同时判定
 * 「可以重建」。
 */
public class DriftCircuitBreaker {

    /**
     * 生产默认阈值：同一条资源连续失败 2 次熔断。
     *
     * <p>不能用 3：节点重连会各触发一次补齐（真机实测每次重启 1 次），阈值 3 意味着前两次
     * 重建已经是无意义的抖动重试，第三次才熔断。
     */
    public static final int DEFAULT_THRESHOLD = 2;

    /**
     * 生产默认冷却：15 分钟。
     *
     * <p>必须显著大于 NodeReconcileTask 的巡检周期（{@code flux.reconcile.interval-ms} 默认
     * 300000ms = 5 分钟），否则每轮巡检时冷却恰好到期，熔断形同虚设。15 分钟 = 3 个巡检周期。
     */
    public static final long DEFAULT_COOLDOWN_MS = 900_000L;

    /** 条目上限：正常环境一条资源一个 key，4096 足够覆盖上千转发；超限按最旧淘汰。 */
    public static final int DEFAULT_MAX_STATES = 4096;

    /** 未熔断条目的闲置保留时间：到期后由 prune 回收。 */
    public static final long DEFAULT_STATE_TTL_MS = 6 * 60 * 60 * 1000L;

    private static final DriftCircuitBreaker SHARED =
            new DriftCircuitBreaker(DEFAULT_THRESHOLD, DEFAULT_COOLDOWN_MS,
                    DEFAULT_MAX_STATES, DEFAULT_STATE_TTL_MS);

    /**
     * 生产实例登记表。
     *
     * <p>为什么不用单例：{@code ForwardServiceImpl} 的每个实例（测试里几百个）都持有自己的熔断状态，
     * 共用单例会让上一个用例的计数污染下一个用例。生产只有一个 service bean，登记后管理端
     * 仍能看到唯一那份状态；测试里各实例互不干扰。
     */
    private static final List<WeakReference<DriftCircuitBreaker>> REGISTERED = new ArrayList<>();

    private final int threshold;
    private final long cooldownMs;
    private final int maxStates;
    private final long stateTtlMs;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public DriftCircuitBreaker(int threshold, long cooldownMs) {
        this(threshold, cooldownMs, DEFAULT_MAX_STATES, DEFAULT_STATE_TTL_MS);
    }

    public DriftCircuitBreaker(int threshold, long cooldownMs, int maxStates, long stateTtlMs) {
        this.threshold = Math.max(1, threshold);
        this.cooldownMs = Math.max(0L, cooldownMs);
        this.maxStates = Math.max(1, maxStates);
        this.stateTtlMs = Math.max(0L, stateTtlMs);
    }

    /** 生产共享实例（阈值 2 / 冷却 15 分钟）。仅用于展示默认值与兜底快照。 */
    public static DriftCircuitBreaker shared() {
        return SHARED;
    }

    /** 新建一个熔断器并登记，管理端 {@link #snapshotAll()} 能读到它的状态。 */
    public static DriftCircuitBreaker registered(int threshold, long cooldownMs) {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(threshold, cooldownMs);
        synchronized (REGISTERED) {
            REGISTERED.removeIf(ref -> ref.get() == null);
            REGISTERED.add(new WeakReference<>(breaker));
        }
        return breaker;
    }

    /** 存活实例数（管理端展示与测试断言用）。 */
    public static int liveInstances() {
        return liveBreakers().size();
    }

    /** 所有存活实例的合并快照；生产只有一个 service 实例，因此就是那一份状态。 */
    public static Map<String, Map<String, Object>> snapshotAll() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (DriftCircuitBreaker breaker : liveBreakers()) {
            out.putAll(breaker.snapshot());
        }
        return out;
    }

    /** 登记表里当前的存活实例，顺带清掉已被 GC 的弱引用。 */
    private static List<DriftCircuitBreaker> liveBreakers() {
        List<DriftCircuitBreaker> live = new ArrayList<>();
        synchronized (REGISTERED) {
            REGISTERED.removeIf(ref -> ref.get() == null);
            for (WeakReference<DriftCircuitBreaker> ref : REGISTERED) {
                DriftCircuitBreaker breaker = ref.get();
                if (breaker != null) {
                    live.add(breaker);
                }
            }
        }
        return live;
    }

    /**
     * 管理端手动放行（跨实例）：对登记表里每个存活实例执行 {@link #release(String)}，返回累计删除条目数。
     *
     * <p>为什么需要这一层：控制器手上没有 {@code ForwardServiceImpl} 里的熔断器实例（它是
     * {@link #registered} 出来的私有字段），只能靠登记表找到它。生产只有一个 service bean，
     * 累计值就是那一个实例的删除数；测试里各实例的 key 互不重叠，累计同样等于目标实例的删除数。
     */
    public static int releaseEverywhere(String key) {
        int released = 0;
        for (DriftCircuitBreaker breaker : liveBreakers()) {
            if (breaker.release(key)) {
                released++;
            }
        }
        return released;
    }

    /** 管理端手动放行（跨实例）：返回各存活实例 {@link #releaseByNode(Long)} 的删除总数。 */
    public static int releaseByNodeEverywhere(Long nodeId) {
        int released = 0;
        for (DriftCircuitBreaker breaker : liveBreakers()) {
            released += breaker.releaseByNode(nodeId);
        }
        return released;
    }

    /** 管理端手动放行（跨实例）：返回各存活实例 {@link #releaseAll()} 的删除总数。 */
    public static int releaseAllEverywhere() {
        int released = 0;
        for (DriftCircuitBreaker breaker : liveBreakers()) {
            released += breaker.releaseAll();
        }
        return released;
    }

    /**
     * 冷却中 → false；未熔断、或冷却已过（放行一次探测）→ true。
     *
     * <p>半开态只放行一次：到期时把 openedAt 顺延到当前时刻，计数保持原值。探测成功由
     * {@link #recordMatch(String)} 清条目；探测失败由 {@link #recordDrift} 继续累加并重新冷却。
     */
    public boolean allowAttempt(String key) {
        State state = state(key);
        if (state == null) {
            return true;
        }
        synchronized (state) {
            long now = System.currentTimeMillis();
            state.lastTouched = now;
            if (state.openedAt == 0L) {
                return true;
            }
            if (now - state.openedAt < cooldownMs) {
                return false;
            }
            // 半开：顺延冷却窗口并放行这一次探测；不清零计数（清零会让熔断在下个巡检周期失效）
            state.openedAt = now;
            return true;
        }
    }

    /** 记一次失败，返回当前连续次数；达到阈值即熔断，并刷新冷却起点（重建失败时也要真正冷却）。 */
    public int recordDrift(String key) {
        return recordDrift(key, null, null);
    }

    /**
     * 记一次失败，并留存期望/实际摘要供 {@link #snapshot()} 展示。
     *
     * @param expected 库里的期望摘要；未知传 null
     * @param actual   节点上报的摘要；未知传 null
     */
    public int recordDrift(String key, String expected, String actual) {
        if (key == null) {
            return 0;
        }
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.openedAt != 0L && now - state.openedAt >= cooldownMs) {
                state.count = 0;
                state.openedAt = 0L;
            }
            // 期望摘要变了 = 运维改的是另一处配置，不是同一条漂移反复失败：
            // 上一条的重建结果还没等到下一次上报就被新漂移覆盖时，不能把两次独立修复累计成熔断
            // （2026-11-08 圣何塞真机抓到：先修 strategy 再修 maxFails，两次都重建成功，第 3 次
            //  failTimeout 漂移却被判「连续 2 次」直接熔断 15 分钟）。
            if (expected != null && state.expected != null && !state.expected.equals(expected)) {
                state.count = 0;
                state.openedAt = 0L;
            }
            state.count++;
            state.lastTouched = now;
            if (expected != null) {
                state.expected = expected;
            }
            if (actual != null) {
                state.actual = actual;
            }
            if (state.count >= threshold) {
                state.openedAt = now;
            }
            int count = state.count;
            pruneIfNeeded();
            return count;
        }
    }

    /** 摘要一致 / 下发成功：原子移除条目，长期不失败的 key 不会堆积。 */
    public void recordMatch(String key) {
        if (key == null) {
            return;
        }
        // computeIfPresent 返回 null 即删除，删除与并发读之间不会留下「已移除但仍被改写」的悬挂状态
        states.computeIfPresent(key, (k, v) -> null);
    }

    /** 是否处于熔断冷却中（冷却到期即视为未熔断）。 */
    public boolean isOpen(String key) {
        State state = state(key);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.openedAt != 0L && System.currentTimeMillis() - state.openedAt < cooldownMs;
        }
    }

    /** 冷却到期的绝对时间戳（epoch ms）；未熔断返回 0。 */
    public long openUntil(String key) {
        State state = state(key);
        if (state == null) {
            return 0L;
        }
        synchronized (state) {
            return state.openedAt == 0L ? 0L : state.openedAt + cooldownMs;
        }
    }

    /** 当前连续失败次数；没失败过返回 0。熔断命中时要拿它写「连续 N 次」。 */
    public int consecutiveDrifts(String key) {
        State state = state(key);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.count;
        }
    }

    /** 冷却剩余毫秒；未熔断或已到期返回 0。 */
    public long remainingCooldownMs(String key) {
        long until = openUntil(key);
        if (until == 0L) {
            return 0L;
        }
        return Math.max(0L, until - System.currentTimeMillis());
    }

    /** 当前条目数（测试与容量观察用）。 */
    public int size() {
        return states.size();
    }

    /** 清空全部状态（测试用；生产不要调）。 */
    public void reset() {
        states.clear();
    }

    /**
     * 管理端手动放行：删除该 key 的熔断状态，返回删除前是否存在过。
     *
     * <p>用途（round38 遗留 3）：熔断是纯内存态，运维把节点侧写法规整之后没有任何手段让某条资源
     * 立刻重试，只能干等 {@code cooldownMs}（默认 15 分钟 = 3 个巡检周期）。删条目等于「从没失败过」：
     * 计数归 0、冷却消失，下一次 {@link #allowAttempt(String)} 立即放行，同一实例的
     * {@link #snapshot()} 也立刻看不到它。
     */
    public boolean release(String key) {
        if (key == null) {
            return false;
        }
        return states.remove(key) != null;
    }

    /**
     * 管理端手动放行：删除该节点名下所有资源条目，返回删除数量。
     *
     * <p>多机场景下运维往往只想重试一台机器。key 结构固定为 {@code <kind>|<nodeId>|<name>}，
     * 因此只按第二段判定节点：用 {@code split("\\|", 3)} 切开，{@code name} 里即便含 {@code |}
     * 也只落在第三段，不影响节点判定。
     */
    public int releaseByNode(Long nodeId) {
        if (nodeId == null) {
            return 0;
        }
        String wanted = String.valueOf(nodeId);
        int released = 0;
        for (Map.Entry<String, State> entry : states.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 3);
            if (parts.length < 2 || !wanted.equals(parts[1])) {
                continue;
            }
            // 带 value 的 remove：并发下别的线程刚把条目删掉又重建时，不误删那份新计数
            if (states.remove(entry.getKey(), entry.getValue())) {
                released++;
            }
        }
        return released;
    }

    /** 管理端手动放行：清空全部条目，返回删除数量（同实例的 {@link #snapshot()} 立刻为空）。 */
    public int releaseAll() {
        int released = states.size();
        states.clear();
        return released;
    }

    /** 阈值（展示用）。 */
    public int threshold() {
        return threshold;
    }

    /** 冷却毫秒（展示用）。 */
    public long cooldownMs() {
        return cooldownMs;
    }

    /**
     * 管理端可见的熔断快照，按 key 字典序。
     *
     * <p>每项字段：{@code count}（连续失败次数）、{@code open}（当前是否在冷却）、
     * {@code openUntil}（冷却到期 epoch ms）、{@code remainingMs}、{@code expected}/{@code actual}
     * （最近一次记录的摘要，可能为 null）、{@code lastTouched}。
     */
    public Map<String, Map<String, Object>> snapshot() {
        List<Map.Entry<String, State>> entries = new ArrayList<>(states.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        long now = System.currentTimeMillis();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, State> entry : entries) {
            State state = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            synchronized (state) {
                boolean open = state.openedAt != 0L && now - state.openedAt < cooldownMs;
                row.put("count", state.count);
                row.put("open", open);
                row.put("openUntil", state.openedAt == 0L ? 0L : state.openedAt + cooldownMs);
                row.put("remainingMs", open ? Math.max(0L, state.openedAt + cooldownMs - now) : 0L);
                row.put("expected", state.expected);
                row.put("actual", state.actual);
                row.put("lastTouched", state.lastTouched);
            }
            out.put(entry.getKey(), row);
        }
        return out;
    }

    private void pruneIfNeeded() {
        if (states.size() <= maxStates) {
            return;
        }
        long now = System.currentTimeMillis();
        // 先回收「没熔断且长期没动」的条目
        states.entrySet().removeIf(e -> {
            State state = e.getValue();
            synchronized (state) {
                return state.openedAt == 0L && now - state.lastTouched > stateTtlMs;
            }
        });
        if (states.size() <= maxStates) {
            return;
        }
        // 仍超限：按 lastTouched 从旧到新淘汰到 3/4，熔断中的条目同样参与（其摘要已被记录不影响观测）
        List<Map.Entry<String, State>> entries = new ArrayList<>(states.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().lastTouched));
        int target = Math.max(1, maxStates * 3 / 4);
        for (Map.Entry<String, State> entry : entries) {
            if (states.size() <= target) {
                break;
            }
            states.remove(entry.getKey(), entry.getValue());
        }
    }

    private State state(String key) {
        return key == null ? null : states.get(key);
    }

    /** 一条资源的失败状态；所有字段的读改写都在实例锁内完成。 */
    private static final class State {
        private int count;
        private long openedAt;
        private long lastTouched;
        private String expected;
        private String actual;
    }
}
