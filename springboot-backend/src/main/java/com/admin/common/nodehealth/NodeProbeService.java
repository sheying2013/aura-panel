package com.admin.common.nodehealth;

import com.admin.entity.Node;
import com.admin.service.NodeService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * 节点健康探测：把「库里的在线标记」换成「最近一次真实往返的结论」。
 *
 * <p>病根：{@code Node.status} 只在 WS 建连/断连时被改写（{@code WebSocketServer} 的
 * {@code afterConnectionEstablished} / {@code afterConnectionClosed}），网络黑洞、容器 pause、
 * 进程被 kill -9 都不会触发 afterConnectionClosed，于是库里一直写着 1，前端一直显示在线。
 *
 * <p>本类只做两件事：① 由 {@link NodeProbeScheduler} 驱动、按「12 小时最多一轮」的持久化账本做真实往返，
 * 并把结论连同节流时间一起落盘（{@link NodeProbeState#results()}），重启后先按落盘结论回答，再等下一轮到点；
 * ② 对外提供只读的 {@link #effective(Long)}，让展示层问「上次检测的真实结论是什么」。
 * 探测绝不挂在 Web 请求上——页面刷新、列表轮询、仪表板渲染都不触发任何探测。
 *
 * <p>探测范围只有 <b>agent 控制通道</b>（{@link #PROBE_SCOPE}）：命令能否被节点收到并回包。它不是业务可达性，
 * 代理端口能不能转发流量、上游域名解不解析，本类都不下结论。
 *
 * <p>判定：无会话/心跳静默 → OFFLINE；有会话但命令无回包 → DEGRADED；拿到回包 → ONLINE；
 * 探测异常、从未探测、结论超过 {@code freshnessMs} → UNKNOWN。UNKNOWN 永远不算在线。
 *
 * <p>账本读/写失败一律 fail-closed：不清空节流记录、不把「读不出来」当成「从未探测」、也不在
 * 「写不进去」时继续探测（预占落不了盘，跨重启的节流保证已经失效）。失败原因和类别放在
 * {@link #stateHealth()} 里供摘要展示；恢复的判据是<b>读得到且写得到</b>——只读一次成功不能证明可写
 * （磁盘满、只读挂载正是「先读成功、后写失败」的形态），见 {@link #probeIfDue(String)}。
 *
 * <p>中断语义：探测工作线程被 {@code shutdownNow()} 打断时，本类停止继续往后逐节点探测、不把这一轮
 * 记成「已完成」，只保留已经落盘的预占（下次仍按一个间隔节流）。中断标记由调用链负责恢复，
 * 本类只读它、不清它。
 *
 * <p><b>多 JVM 限制</b>：{@code roundLock}、结论缓存和恢复状态都是进程内的。多个 JVM 共用同一账本文件时，
 * 只有文件里的 {@code lastAttemptAt} 能做「尽最大努力」的节流，两个进程可能同时读到同一个时间并各跑一轮；
 * 本类不提供跨进程互斥，也不承诺多实例部署下「12 小时最多一次」仍然严格成立。
 */
@Slf4j
public class NodeProbeService {

    /** 默认 12 小时一轮 = 每天 2 次；跨重启也按这个间隔节流，见 {@link NodeProbeStateStore}。 */
    public static final long DEFAULT_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    /** 默认结论保鲜期 24 小时 = 两轮；超过两轮没测过就只承认「未知」。 */
    public static final long DEFAULT_FRESHNESS_MS = 24L * 60L * 60L * 1000L;

    /** 探测覆盖范围：只有 agent 控制通道，不含业务数据面。 */
    public static final String PROBE_SCOPE = "CONTROL_PLANE";

    /**
     * 给接口和界面用的一句话口径，避免把「控制通道通」读成「节点业务可达」。
     * 旧版 agent 收到任何命令都会重写 gost.json（内容不变），本地修复未部署，所以这里不宣称零副作用。
     */
    public static final String PROBE_NOTICE =
            "只验证节点 agent 控制通道能否收到命令并回包（TcpPing 的 port=0 走探活分支，不拨号、不测业务端口），"
                    + "不代表该节点承载的业务流量（数据面）连通。旧版 agent 每收到一次命令都会重写 gost.json（内容不变），"
                    + "本地修复尚未部署，因此不能声称该探测对所有版本零副作用。";

    /** 账本报错的最短重复间隔：磁盘坏了不该每秒刷一行日志。 */
    private static final long STATE_ERROR_LOG_INTERVAL_MS = 10L * 60L * 1000L;

    /** 账本不可读后的重试间隔：太密会反复读盘刷日志，太稀会让修复后迟迟不恢复。 */
    static final long STATE_RECOVERY_INTERVAL_MS = 10L * 60L * 1000L;

    /** 结论时间戳允许超前多少：超出这个量说明文件被改过或时钟乱了，按不可信丢弃。 */
    static final long CLOCK_SKEW_MS = 5L * 60L * 1000L;

    private final NodeService nodeService;
    private final NodeProbeTransport transport;
    private final LongPredicate connectionCheck;
    private final LongSupplier clock;
    private final long intervalMs;
    private final long freshnessMs;
    private final NodeProbeStateStore stateStore;

    /**
     * 节点 → 最近一次探测结论（含节点身份）。随账本一起落盘、启动时回填：
     * 只持久化轮次时间的话，每次重启后到下一轮之间（最长一个间隔）所有节点都只能报未知。
     */
    private final Map<Long, NodeProbeEntry> results = new ConcurrentHashMap<>();

    /** 同一时刻只允许一轮：定时 tick 与手动触发撞车时，重复探测就是重复打点。 */
    private final ReentrantLock roundLock = new ReentrantLock();

    private volatile ProbeRound lastRound;
    private volatile NodeProbeState state;
    private volatile long stateErrorLoggedAt;

    /** 账本健康状态：读/写失败时 ok=false，error 和 kind 可直接进摘要。 */
    private volatile StateHealth stateHealth;
    private volatile long nextStateRecoveryAt;

    /** 单测/临时装配用：节流账本只在内存里，进程重启即清零。 */
    public NodeProbeService(NodeService nodeService,
                            NodeProbeTransport transport,
                            LongPredicate connectionCheck,
                            LongSupplier clock,
                            long intervalMs,
                            long freshnessMs) {
        this(nodeService, transport, connectionCheck, clock, intervalMs, freshnessMs,
                new InMemoryNodeProbeStateStore());
    }

    public NodeProbeService(NodeService nodeService,
                            NodeProbeTransport transport,
                            LongPredicate connectionCheck,
                            LongSupplier clock,
                            long intervalMs,
                            long freshnessMs,
                            NodeProbeStateStore stateStore) {
        this.nodeService = nodeService;
        this.transport = transport;
        this.connectionCheck = connectionCheck;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.intervalMs = intervalMs > 0L ? intervalMs : DEFAULT_INTERVAL_MS;
        this.freshnessMs = freshnessMs;
        this.stateStore = stateStore == null ? new InMemoryNodeProbeStateStore() : stateStore;
        this.state = loadState();
    }

    /**
     * 启动时读账本。读取失败只降级为 fail-closed，不向上抛——应用必须能正常启动；
     * 但绝不能返回 {@link NodeProbeState#empty()}，那会让下一次 tick 把「读不出来」当成「从没测过」。
     */
    private NodeProbeState loadState() {
        long now = clock.getAsLong();
        try {
            NodeProbeState loaded = stateStore.load();
            if (FileNodeProbeStateStore.isLoadError(loaded)) {
                String error = FileNodeProbeStateStore.loadErrorMessage(loaded);
                markStateUnhealthyOnStartup(now, StateFailureKind.READ, error);
                log.error("节点探测账本不可读，本次启动按 fail-closed 处理：{}", error);
                return NodeProbeState.empty();
            }
            stateHealth = StateHealth.healthy(now);
            NodeProbeState usable = loaded == null ? NodeProbeState.empty() : loaded;
            hydrateResults(usable, now);
            hydrateRound(usable, now);
            return usable;
        } catch (RuntimeException e) {
            // 账本契约要求 load 不抛；真抛了也不能拖垮启动，但必须 fail-closed
            String error = describe(e);
            markStateUnhealthyOnStartup(now, StateFailureKind.READ, error);
            log.error("节点探测账本不可读，本次启动按 fail-closed 处理：{}", error);
            return NodeProbeState.empty();
        }
    }

    /**
     * 账本出问题只改两件事：健康态（界面能看见）+ 恢复时间（本轮不探测）。
     * 日志留给调用方，因为「预占写失败」和「完成时间写失败」要说清各自的后果。
     */
    private void markStateUnhealthy(long now, StateFailureKind kind, String error) {
        stateHealth = StateHealth.failed(now, kind, error);
        // 失败后按 STATE_RECOVERY_INTERVAL_MS 退避：账本坏了就每个 tick 都去戳，只会把日志刷满
        nextStateRecoveryAt = now + STATE_RECOVERY_INTERVAL_MS;
    }

    /**
     * 启动时账本读不出来：允许第一个 tick 立刻复核一次（可能只是启动瞬间被别的写者占着），
     * 之后按 {@link #STATE_RECOVERY_INTERVAL_MS} 退避。
     */
    private void markStateUnhealthyOnStartup(long now, StateFailureKind kind, String error) {
        markStateUnhealthy(now, kind, error);
        nextStateRecoveryAt = now;
    }

    /**
     * 账本出问题时尝试恢复，判据是两步都过：
     * ① load 成功且不是 load-error 占位——读不到就还是 fail-closed，读失败绝不当成「从未探测」；
     * ② 用「内容不变的 save」做一次原子写验证——读得出来不等于写得进去，只凭 read 成功就宣布健康
     * 会把磁盘满/只读挂载掩盖成「一切正常」，下一轮预占写失败又悄悄回到不探测。
     *
     * <p>写验证失败不毁现场：{@link FileNodeProbeStateStore#save} 走同目录临时文件 + ATOMIC_MOVE，
     * 写不进去时原文件内容仍是它原来的样子。任一步失败就保持不健康、本轮不探测，按
     * {@link #STATE_RECOVERY_INTERVAL_MS} 退避重试。
     */
    private boolean recoverState(long now) {
        if (now < nextStateRecoveryAt) {
            log.debug("节点探测账本仍不可用（{}），{} ms 后重试", stateHealth.kind(), nextStateRecoveryAt - now);
            return false;
        }
        nextStateRecoveryAt = now + STATE_RECOVERY_INTERVAL_MS;
        NodeProbeState loaded;
        try {
            loaded = stateStore.load();
        } catch (RuntimeException e) {
            String error = describe(e);
            markStateUnhealthy(now, StateFailureKind.READ, error);
            log.warn("节点探测账本仍然不可读，本轮放弃探测：{}", error);
            return false;
        }
        if (FileNodeProbeStateStore.isLoadError(loaded)) {
            String error = FileNodeProbeStateStore.loadErrorMessage(loaded);
            markStateUnhealthy(now, StateFailureKind.READ, error);
            log.warn("节点探测账本仍然不可读，本轮放弃探测：{}", error);
            return false;
        }
        NodeProbeState recovered = loaded == null ? NodeProbeState.empty() : loaded;
        try {
            // 写回同一份内容：只证明「能写」，不改节流时间
            stateStore.save(recovered);
        } catch (RuntimeException e) {
            String error = describe(e);
            markStateUnhealthy(now, StateFailureKind.WRITE, error);
            log.warn("节点探测账本可读但不可写，本轮放弃探测：{}", error);
            return false;
        }
        state = recovered;
        hydrateResults(recovered, now);
        hydrateRound(recovered, now);
        stateHealth = StateHealth.healthy(now);
        log.info("节点探测账本已恢复（可读且可写），继续按落盘记录节流");
        return true;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    /** 当前时钟（毫秒），供展示层统一时间口径。 */
    public long now() {
        return clock.getAsLong();
    }

    public long intervalMs() {
        return intervalMs;
    }

    /** {@code <= 0} 表示结论永不过期（仅测试/特殊部署）。 */
    public long freshnessMs() {
        return freshnessMs;
    }

    /** 上一次完整轮次的摘要：本进程跑完的，或启动时从账本恢复的真实上一轮；两者都没有时为 null。 */
    public ProbeRound lastRound() {
        return lastRound;
    }

    /**
     * 预计下一次轮次时间：上次尝试时间加一个间隔，与 {@link #probeIfDue(String)} 的节流判据同源。
     *
     * <p>节流只认持久化的 {@code lastAttemptAt}（预占发生在探测开始之前），真正放行时刻就是
     * 「上次尝试 + 间隔」。拿上一轮完成时间（= 尝试 + 单轮耗时）去推，界面会显示一个比调度器晚
     * 「单轮耗时」的时刻：看着还没到点，其实早该跑了，单轮越慢偏差越大。
     *
     * <p>中断/写盘失败留下的预占（尝试时间晚于完成时间）同样按尝试时间算，正好是真正解禁的时刻；
     * 刚重启还没跑过时用账本里的上次尝试时间，免得重启把间隔读成「马上要测」。
     * 从未尝试过时返回 null——不编造时间。
     */
    public Long nextDueAt() {
        NodeProbeState snapshot = state;
        return snapshot.hasAttempt() ? Long.valueOf(snapshot.lastAttemptAt() + intervalMs) : null;
    }

    /** 账本健康状态；读/写失败时 ok=false，error 与 kind 是可直接展示的原因和类别。 */
    public StateHealth stateHealth() {
        return stateHealth;
    }

    /** 上次真正开始探测的时间（含重启前的记录）；从未探测过为 null。 */
    public Long lastProbeAt() {
        ProbeRound round = lastRound;
        if (round != null) {
            return Long.valueOf(round.startedAt());
        }
        NodeProbeState snapshot = state;
        return snapshot.hasAttempt() ? Long.valueOf(snapshot.lastAttemptAt()) : null;
    }

    /**
     * 到点才跑一轮：距上次尝试不足 {@link #intervalMs} 时直接跳过。
     *
     * <p>节流依据是持久化的 {@code lastAttemptAt}，不是内存时间戳——进程重启、容器重建都不会把
     * 「12 小时最多一次」清零。整个方法串行执行，并发触发只有一个能真正探测。
     *
     * <p>顺序是刻意的：先读节点列表（读不出来就显式抛出，什么都不记账），再写盘预占本次尝试，
     * 最后才探测。中途被杀最坏是「白等一个间隔」，不会对节点重复打点。
     *
     * @param trigger 触发来源，写进轮次摘要（schedule / manual / test）
     * @return 真正跑了轮次时返回摘要；未到点或无法记账时返回空
     */
    public Optional<ProbeRound> probeIfDue(String trigger) {
        roundLock.lock();
        try {
            long now = clock.getAsLong();
            if (!stateHealth.ok() && !recoverState(now)) {
                // 账本不可信时不探测：保证不了「上一轮是什么时候」就不能对节点重复打点
                return Optional.empty();
            }
            NodeProbeState snapshot = state;
            if (snapshot.hasAttempt() && now - snapshot.lastAttemptAt() < intervalMs) {
                log.debug("距上次探测 {} ms，未到 {} ms，本轮跳过（{}）",
                        now - snapshot.lastAttemptAt(), intervalMs, trigger);
                return Optional.empty();
            }

            List<Node> nodes = requireNodes();
            // 开一轮之前先丢掉「节点已不存在 / ID 被复用」的旧结论：账本因此有界，
            // 新建的节点也不会继承上一个同 ID 节点的在线结论
            pruneResults(nodes);

            NodeProbeState reserved = snapshot.reserved(now, trigger);
            try {
                stateStore.save(reserved);
            } catch (RuntimeException e) {
                // 记不了账就不探测：保证不了「12 小时最多一次」时，宁可这一轮没有新结论。
                // 同时必须让健康态变成不健康——否则界面只看到「一切正常」，持久化坏了没人知道。
                markStateUnhealthy(now, StateFailureKind.WRITE, describe(e));
                logStateFailure("节点探测账本写入失败，本轮放弃探测", e);
                return Optional.empty();
            }
            state = reserved;

            ProbeRound round = runRound(trigger, nodes);
            if (round.interrupted()) {
                // 中断不是「本轮完成」：不写 lastCompletedAt，落盘的预占保留（下次仍按一个间隔节流）；
                // 已探测的结论留在内存里够展示，剩下的节点等下一轮，不在关停过程中逐个打完。
                // 半轮的结论也不写盘：账本里那份「最近一轮」必须是完整跑完的，否则重启后会把半轮当整轮展示。
                log.warn("节点探测轮次被中断（{}）：已探测 {}/{} 个，剩余节点本轮不再探测，本轮不计完成",
                        trigger, round.total(), nodes.size());
                return Optional.of(round);
            }
            NodeProbeState completed = reserved.completed(round.finishedAt(), trigger)
                    .withResults(snapshotResults())
                    .withRound(round);
            state = completed;
            try {
                stateStore.save(completed);
            } catch (RuntimeException e) {
                // 本轮已经探过；内存态能挡住本进程内的重复探测，跨重启的那一份保证这轮丢了，必须报错
                markStateUnhealthy(clock.getAsLong(), StateFailureKind.WRITE, describe(e));
                logStateFailure("节点探测账本写入完成时间失败，跨重启节流可能失效", e);
            }
            return Optional.of(round);
        } finally {
            roundLock.unlock();
        }
    }

    private ProbeRound runRound(String trigger, List<Node> nodes) {
        long startedAt = clock.getAsLong();
        int total = 0;
        int online = 0;
        int degraded = 0;
        int offline = 0;
        int unknown = 0;
        boolean interrupted = false;
        for (Node node : nodes) {
            if (Thread.currentThread().isInterrupted()) {
                // 被 shutdownNow 打断后不再往后逐节点探测：剩下的节点等下一轮，别在关停过程中逐个空等 10 秒
                interrupted = true;
                break;
            }
            if (node == null || node.getId() == null) {
                continue;
            }
            total++;
            NodeProbeStatus status = probeNode(node).status();
            if (status == NodeProbeStatus.ONLINE) {
                online++;
            } else if (status == NodeProbeStatus.DEGRADED) {
                degraded++;
            } else if (status == NodeProbeStatus.OFFLINE) {
                offline++;
            } else {
                unknown++;
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            // 中断落在最后一个节点之后：这一轮同样不算完整
            interrupted = true;
        }
        ProbeRound round = new ProbeRound(trigger, startedAt, clock.getAsLong(),
                total, online, degraded, offline, unknown, interrupted);
        if (!interrupted) {
            lastRound = round;
        }
        if (interrupted) {
            log.warn("节点探测轮次被中断（{}）：已探测 {} 个，控制通道正常 {}，降级 {}，未连接 {}，未知 {}",
                    trigger, total, online, degraded, offline, unknown);
        } else {
            log.info("节点探测轮次完成（{}）：共 {} 个，控制通道正常 {}，降级 {}，未连接 {}，未知 {}",
                    round.trigger(), total, online, degraded, offline, unknown);
        }
        return round;
    }

    /**
     * 探测单个节点并写入缓存。控制通道不在时直接判 OFFLINE、不发命令（省掉 10 秒无谓等待）。
     */
    public NodeProbeResult probeNode(Node node) {
        if (node == null || node.getId() == null) {
            return NodeProbeResult.never();
        }
        Long nodeId = node.getId();
        long now = clock.getAsLong();

        boolean connected;
        try {
            connected = connectionCheck != null && connectionCheck.test(nodeId);
        } catch (RuntimeException e) {
            return store(node, new NodeProbeResult(NodeProbeStatus.UNKNOWN, now, -1L,
                    "读取控制通道状态异常，结论未知: " + e.getMessage()));
        }
        if (!connected) {
            return store(node, new NodeProbeResult(NodeProbeStatus.OFFLINE, now, -1L,
                    "控制通道未连接（无会话或心跳静默超过 30 秒）"));
        }

        try {
            NodeProbeTransport.Reply reply = transport == null ? null : transport.probe(nodeId);
            if (Thread.currentThread().isInterrupted()) {
                // 中断发生在等回包时：拿到的「无回包」是我方关停造成的，记成降级会诬陷节点
                return store(node, new NodeProbeResult(NodeProbeStatus.UNKNOWN, now, -1L,
                        "探测被中断，结论未知"));
            }
            if (reply == null) {
                return store(node, new NodeProbeResult(NodeProbeStatus.DEGRADED, now, -1L,
                        "控制通道在，但探测通道没有返回结果"));
            }
            if (reply.responded()) {
                return store(node, new NodeProbeResult(NodeProbeStatus.ONLINE, now,
                        reply.latencyMs(), reply.detail()));
            }
            return store(node, new NodeProbeResult(NodeProbeStatus.DEGRADED, now,
                    reply.latencyMs(), "控制通道在，但探测命令无回包：" + reply.detail()));
        } catch (RuntimeException e) {
            return store(node, new NodeProbeResult(NodeProbeStatus.UNKNOWN, now, -1L,
                    "探测异常，结论未知: " + e.getMessage()));
        }
    }

    /**
     * 展示用结论：从未探测 → UNKNOWN(未检测)；结论过期 → UNKNOWN + 保留上次检测时间。
     * 只读，不会发起任何探测。
     */
    public NodeProbeResult effective(Long nodeId) {
        return effective(nodeId, null);
    }

    /**
     * 带身份校验的展示用结论：节点记录被删后重建（ID 被复用）时，旧结论一律作废，
     * 否则新机器会顶着上一台机器的 ONLINE 出现在面板上。
     *
     * @param identity 调用方手上这条节点记录的身份指纹，见 {@link #identityOf(Node)}；null 表示无从判定
     */
    public NodeProbeResult effective(Long nodeId, String identity) {
        NodeProbeEntry entry = nodeId == null ? null : results.get(nodeId);
        if (entry == null) {
            return NodeProbeResult.never();
        }
        if (entry.identityConflicts(identity)) {
            return new NodeProbeResult(NodeProbeStatus.UNKNOWN, 0L, -1L,
                    "节点记录已重建或 ID 被复用，旧结论作废，等下一轮探测");
        }
        NodeProbeResult raw = entry.result();
        if (!raw.probed() || !isStale(raw)) {
            return raw;
        }
        return raw.asStale();
    }

    /** 结论是否已过期（从未探测的结果不算过期，它是「没测过」）。 */
    public boolean isStale(NodeProbeResult result) {
        if (result == null || !result.probed() || freshnessMs <= 0L) {
            return false;
        }
        return clock.getAsLong() - result.checkedAt() > freshnessMs;
    }

    /** 控制通道当前是否在（读 WS 会话表的内存态，不是探测，也不改任何结论）。 */
    public boolean isConnected(Long nodeId) {
        if (nodeId == null || connectionCheck == null) {
            return false;
        }
        try {
            return connectionCheck.test(nodeId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 是否已有探测结论（含过期结论）。 */
    public boolean hasResult(Long nodeId) {
        return nodeId != null && results.containsKey(nodeId);
    }

    private NodeProbeResult store(Node node, NodeProbeResult result) {
        if (node == null || node.getId() == null) {
            return result;
        }
        results.put(node.getId(), new NodeProbeEntry(node.getId(), identityOf(node), result));
        return result;
    }

    /**
     * 节点身份使用创建时间，缺失时退回非空节点名；两者均非唯一标识，仍可能发生碰撞。
     * 均缺失时返回 null，展示层按未知处理。
     */
    static String identityOf(Node node) {
        if (node == null) {
            return null;
        }
        Long createdTime = node.getCreatedTime();
        if (createdTime != null) {
            return "createdTime=" + createdTime;
        }
        String name = node.getName();
        return name == null || name.isBlank() ? null : "name=" + name.trim();
    }

    /**
     * 用账本里的结论集回填内存缓存。只接受「真能被测出来」的形态：没有检测时间、时间戳跑到未来、
     * 声称在线却没有往返耗时——这些都不可能是本进程记录下来的，宁可回到 UNKNOWN。
     */
    private void hydrateResults(NodeProbeState snapshot, long now) {
        if (snapshot == null) {
            return;
        }
        for (NodeProbeEntry entry : snapshot.results()) {
            if (!isTrustworthy(entry, now)) {
                continue;
            }
            // 内存里的结论比文件新：恢复账本时不能用旧快照盖掉本轮刚测出来的
            results.putIfAbsent(entry.nodeId(), entry);
        }
    }

    /**
     * 用账本里的上一轮摘要回填 {@link #lastRound}，让重启后的摘要还能显示「上一轮跑了什么」。
     *
     * <p>只接受与本账本描述的那一轮对得上的一条：{@code finishedAt} 必须等于 {@code lastCompletedAt}、
     * trigger 必须等于当次记录的 trigger、且不在未来。对不上说明这块摘要不属于这一轮（手改过的文件、
     * 两次写拼接、时钟回拨），宁可没有摘要也不把别的轮次按到这一轮头上。
     *
     * <p>也不覆盖内存里更新的摘要：完成时间写盘失败后恢复账本时，盘上还是上一轮，而本进程这一轮
     * 确实跑完了——界面该看到的是真实跑完的那一轮。
     */
    private void hydrateRound(NodeProbeState snapshot, long now) {
        ProbeRound persisted = snapshot == null ? null : snapshot.lastRound();
        if (persisted == null) {
            return;
        }
        if (!matchesPersistedRound(snapshot, persisted, now)) {
            log.warn("节点探测账本里的上一轮摘要与账本不符，按无摘要处理（{} @ {}）",
                    persisted.trigger(), persisted.finishedAt());
            return;
        }
        ProbeRound current = lastRound;
        if (current != null && current.finishedAt() >= persisted.finishedAt()) {
            return;
        }
        lastRound = persisted;
    }

    private static boolean matchesPersistedRound(NodeProbeState snapshot, ProbeRound round, long now) {
        if (round.interrupted() || round.trigger() == null) {
            return false;
        }
        // 只认「完成时间」这条硬链接：lastTrigger 是每次预占都会改写的尝试来源，
        // 上一次尝试被中断时它与上一轮的 trigger 本来就不同，据此拒绝会丢掉真实的上一轮。
        if (round.finishedAt() <= 0L || round.finishedAt() != snapshot.lastCompletedAt()) {
            return false;
        }
        if (round.startedAt() <= 0L || round.startedAt() > round.finishedAt()) {
            return false;
        }
        if (round.finishedAt() > now + CLOCK_SKEW_MS) {
            return false;
        }
        int total = round.total();
        int online = round.online();
        int degraded = round.degraded();
        int offline = round.offline();
        int unknown = round.unknown();
        if (total < 0 || online < 0 || degraded < 0 || offline < 0 || unknown < 0) {
            return false;
        }
        return (long) online + degraded + offline + unknown == total;
    }

    private static boolean isTrustworthy(NodeProbeEntry entry, long now) {
        if (entry == null || entry.nodeId() == null || entry.nodeId() <= 0L) {
            return false;
        }
        NodeProbeResult result = entry.result();
        if (result == null || !result.probed()) {
            return false;
        }
        if (result.checkedAt() > now + CLOCK_SKEW_MS) {
            return false;
        }
        return result.status() != NodeProbeStatus.ONLINE || result.latencyMs() >= 0L;
    }

    /** 丢掉已下线节点与身份不符的结论；只动内存，随本轮完成一起落盘。 */
    private void pruneResults(List<Node> nodes) {
        Map<Long, String> identities = new HashMap<>();
        for (Node node : nodes) {
            if (node == null || node.getId() == null) {
                continue;
            }
            identities.put(node.getId(), identityOf(node));
        }
        results.entrySet().removeIf(entry -> {
            if (!identities.containsKey(entry.getKey())) {
                return true;
            }
            return entry.getValue().identityConflicts(identities.get(entry.getKey()));
        });
    }

    /** 落盘用的稳定快照：按节点 ID 排序，同一份结论集写出来的字节不随遍历顺序变化。 */
    private List<NodeProbeEntry> snapshotResults() {
        List<NodeProbeEntry> snapshot = new ArrayList<>(results.values());
        snapshot.sort(Comparator.comparing(NodeProbeEntry::nodeId));
        return snapshot;
    }

    /**
     * 读节点列表。读不出来必须显式失败：吞成空列表会让本轮得出 total=0 的「正常」结论，
     * 界面上就是「没有节点要检测」——故障被伪装成了成功。
     */
    private List<Node> requireNodes() {
        if (nodeService == null) {
            throw new NodeHealthDataException("节点健康探测读取节点列表失败：NodeService 未装配");
        }
        List<Node> nodes;
        try {
            nodes = nodeService.list();
        } catch (RuntimeException e) {
            throw new NodeHealthDataException("节点健康探测读取节点列表失败：" + e.getMessage(), e);
        }
        if (nodes == null) {
            throw new NodeHealthDataException("节点健康探测读取节点列表失败：返回 null");
        }
        return nodes;
    }

    private void logStateFailure(String message, RuntimeException e) {
        long now = clock.getAsLong();
        if (now - stateErrorLoggedAt >= STATE_ERROR_LOG_INTERVAL_MS) {
            stateErrorLoggedAt = now;
            log.error("{}：{}（账本 {}）", message, e.getMessage(), stateStore.getClass().getSimpleName());
        } else {
            log.debug("{}：{}", message, e.getMessage());
        }
    }

    /**
     * 一轮探测的摘要，给仪表板回答「上次什么时候跑的、结果如何」。
     * {@code interrupted=true} 表示这一轮被中断打断，只覆盖了 {@code total} 个节点中的一部分，不是完整轮次。
     */
    public record ProbeRound(String trigger, long startedAt, long finishedAt,
                             int total, int online, int degraded, int offline, int unknown,
                             boolean interrupted) {
    }

    /** 账本失败的类别：读不出来 / 写不进去，恢复判据和界面文案都不一样。 */
    public enum StateFailureKind {
        /** 读不出账本：读失败不能当「从未探测」，也不能覆盖现场。 */
        READ,
        /** 写不进账本：预占或完成时间落不了盘，跨重启的节流保证已失效，本轮不探测。 */
        WRITE
    }

    /**
     * 节流账本的健康状态：{@code ok=false} 表示这次运行读不出或写不进落盘账本，探测已 fail-closed；
     * {@code error} 是给摘要/界面直接展示的原因，{@code kind} 说明是哪一类故障；
     * 恢复判据是「读得到且写得到」，两者都过才回到 {@code ok=true}。
     */
    public record StateHealth(boolean ok, long checkedAt, String error, StateFailureKind kind) {

        static StateHealth healthy(long at) {
            return new StateHealth(true, at, null, null);
        }

        static StateHealth failed(long at, StateFailureKind kind, String error) {
            String message = error == null || error.isBlank()
                    ? (kind == StateFailureKind.WRITE ? "节点探测账本不可写" : "节点探测账本不可读")
                    : error;
            return new StateHealth(false, at, message, kind);
        }
    }
}
