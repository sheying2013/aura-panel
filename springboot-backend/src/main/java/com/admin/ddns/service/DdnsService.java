package com.admin.ddns.service;

import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsAddresses;
import com.admin.ddns.DdnsCheckResult;
import com.admin.ddns.DdnsDomainGate;
import com.admin.ddns.DdnsForwardApplier;
import com.admin.ddns.DdnsResolver;
import com.admin.ddns.DdnsTarget;
import com.admin.ddns.dto.DdnsCreateRequest;
import com.admin.ddns.dto.DdnsUpdateRequest;
import com.admin.ddns.entity.DdnsRecord;
import com.admin.ddns.mapper.DdnsRecordMapper;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DDNS 的持久化状态机：解析 → 判断主地址是否漂移 → 按既有转发口径重下发 → 成功才落库。
 *
 * <p>为什么主地址漂移才重下发：GOST 的 forwarder.nodes 是目标地址列表，面板只选一个
 * 当前地址写进去。多 A 记录里只是新增/移除备用地址时，现有服务仍然可用；此时重下发
 * 会先关闭再启动监听，平白断流。只有真正要换 IP 才值得付这个代价。
 *
 * <p>失败语义：任一节点下发失败，就把已经改过的节点按旧地址回滚，数据库保持旧值。
 * 回滚也失败时保留 lastError，节点会在下一次服务上报时按库里的旧地址自我修正；
 * 这里不会把「失败」写成成功。
 */
@Slf4j
@Service
public class DdnsService {

    private static final int DEFAULT_INTERVAL_SECONDS = 60;
    private static final int MAX_ERROR_LENGTH = 500;

    private final DdnsRecordMapper ddnsRecordMapper;
    private final DdnsAddressRegistry registry;
    private final DdnsResolver resolver;
    private final DdnsForwardApplier applier;
    private final ForwardService forwardService;
    private final TunnelService tunnelService;
    private final ChainTunnelService chainTunnelService;
    private final NodeService nodeService;
    private final ForwardPortService forwardPortService;
    private final UserTunnelService userTunnelService;
    private final UserService userService;

    private final Set<Long> checking = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean schedulerRunning = new AtomicBoolean(false);

    public DdnsService(DdnsRecordMapper ddnsRecordMapper,
                       DdnsAddressRegistry registry,
                       DdnsResolver resolver,
                       DdnsForwardApplier applier,
                       ForwardService forwardService,
                       TunnelService tunnelService,
                       ChainTunnelService chainTunnelService,
                       NodeService nodeService,
                       ForwardPortService forwardPortService,
                       UserTunnelService userTunnelService,
                       UserService userService) {
        this.ddnsRecordMapper = ddnsRecordMapper;
        this.registry = registry;
        this.resolver = resolver;
        this.applier = applier;
        this.forwardService = forwardService;
        this.tunnelService = tunnelService;
        this.chainTunnelService = chainTunnelService;
        this.nodeService = nodeService;
        this.forwardPortService = forwardPortService;
        this.userTunnelService = userTunnelService;
        this.userService = userService;
    }

    public List<DdnsRecord> list() {
        return ddnsRecordMapper.selectList(new QueryWrapper<DdnsRecord>().orderByAsc("id"));
    }

    public R create(DdnsCreateRequest request) {
        String domain;
        int interval;
        int enabled;
        try {
            domain = DdnsAddresses.normalizeDomain(request.getDomain());
            interval = interval(request.getIntervalSeconds());
            enabled = enabled(request.getEnabled());
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        if (!ddnsRecordMapper.selectList(new QueryWrapper<DdnsRecord>().eq("domain", domain)).isEmpty()) {
            return R.err("域名已存在：" + domain);
        }

        long now = System.currentTimeMillis();
        DdnsRecord record = new DdnsRecord();
        record.setDomain(domain);
        record.setResolvedIps("");
        record.setPrimaryIp("");
        record.setIntervalSeconds(interval);
        record.setEnabled(enabled);
        record.setLastCheckTime(0L);
        record.setLastChangeTime(0L);
        record.setLastError("");
        record.setFailCount(0);
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        try {
            if (ddnsRecordMapper.insert(record) <= 0) {
                return R.err("DDNS 记录创建失败");
            }
        } catch (DuplicateKeyException e) {
            return R.err("域名已存在：" + domain);
        }
        // 即使暂停检查或首次解析失败，也要登记未解析状态，禁止对账把它当成普通域名。
        registry.put(domain, record.getPrimaryIp());

        if (enabled == 1 && acquire(record.getId())) {
            // 创建即解析一次：失败不影响记录存在，但会把原因写进 lastError，
            // 调度器下一轮会继续重试，不会把“没解析成功”伪装成“已生效”。
            // 抢不到闸门说明调度器已经在查这条记录，重复检查没有意义。
            try {
                checkInternal(record);
            } finally {
                release(record.getId());
            }
        }
        return R.ok(record);
    }

    public R update(DdnsUpdateRequest request) {
        if (request.getId() == null) {
            return R.err("DDNS 记录不存在");
        }
        // 与检查/删除共用同一把闸门：检查线程最后会用整实体 updateById 落库，
        // 不互斥时它手里那份旧实体就会把这里刚改的 interval/enabled 覆盖回去，
        // 表现为「改了没生效」，而下一次检查又按旧配置跑。
        if (!acquire(request.getId())) {
            return R.err("该域名正在检查，请稍后再试");
        }
        try {
            return doUpdate(request);
        } finally {
            release(request.getId());
        }
    }

    private R doUpdate(DdnsUpdateRequest request) {
        DdnsRecord record = ddnsRecordMapper.selectById(request.getId());
        if (record == null) {
            return R.err("DDNS 记录不存在");
        }
        if (request.getDomain() != null && !request.getDomain().isBlank()) {
            String requested;
            try {
                requested = DdnsAddresses.normalizeDomain(request.getDomain());
            } catch (IllegalArgumentException e) {
                return R.err(e.getMessage());
            }
            if (!requested.equals(record.getDomain())) {
                return R.err("域名创建后不可修改；需要换域名请删除后重建");
            }
        }
        try {
            if (request.getIntervalSeconds() != null) {
                record.setIntervalSeconds(interval(request.getIntervalSeconds()));
            }
            if (request.getEnabled() != null) {
                record.setEnabled(enabled(request.getEnabled()));
            }
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        record.setUpdatedTime(System.currentTimeMillis());
        if (ddnsRecordMapper.updateById(record) <= 0) {
            return R.err("DDNS 记录更新失败");
        }
        if (Integer.valueOf(1).equals(record.getEnabled())
                && (record.getPrimaryIp() == null || record.getPrimaryIp().isBlank())) {
            checkInternal(record);
        }
        return R.ok(record);
    }

    /**
     * 删除记录并先把节点服务回落到域名，只有全部入口节点确认回落才真正删库。
     *
     * <p>「删除」在语义上是「节点不再需要这个域名」。可是记录与注册表映射是节点上旧 IP 的
     * 唯一对账依据：只要有一个入口节点没确认回落（离线、下发失败、下发异常/超时），
     * 删掉记录就等于把「节点钉在旧 IP」变成不可恢复状态 —— 库里没有记录、注册表里没有域名，
     * 下一次服务上报无库可对账，也没有第二次回落机会。所以任一节点未确认就整批保留：
     * 记录与渲染映射原样不动，返回值点名未确认的节点，剩下的交给下次服务上报按库对账。
     *
     * <p>规划不完整（转发/隧道/端口数据缺失）同样失败保留：不知道哪些入口服务写过这个域名时，
     * 删掉记录会让那些节点永久停在旧 IP 上，也没法事后补回落。
     *
     * <p>只有所有入口节点都确认回落，才摘映射、删记录；删除落库没生效时再把映射按库还原。
     */
    public R delete(Long id) {
        if (id == null) {
            return R.err("DDNS 记录不存在");
        }
        if (!acquire(id)) {
            return R.err("该域名正在检查，请稍后再试");
        }
        try {
            DdnsRecord record = ddnsRecordMapper.selectById(id);
            if (record == null) {
                return R.err("DDNS 记录不存在");
            }
            String domain = record.getDomain();
            // 先独占窗口再规划：规划用的快照必须就是切换期间的那一份。反过来（先规划后抢窗口）
            // 会让普通编辑在两步之间改完节点并落库，切换随后仍按旧快照下发，把新配置覆盖回旧值。
            DdnsDomainGate.Lease window = registry.beginTransitionLease(domain, "删除记录，节点回落到域名");
            if (window == null) {
                DdnsAddressRegistry.Transition active = registry.activeTransition();
                if (active != null) {
                    return R.err("无法删除：域名 " + active.domain() + " 正在 DDNS 切换（" + active.note()
                            + "），已保留记录、未改动任何节点");
                }
                return R.err("无法删除：域名 " + domain + " 正有其它下发（编辑/删除/对账）在写节点，"
                        + "等待超时，已保留记录、未改动任何节点；请稍后重试");
            }
            try {
                List<DdnsTarget> targets;
                try {
                    targets = planTargets(domain);
                } catch (RuntimeException e) {
                    log.warn("DDNS 删除 {} 前无法完整规划节点回落：{}", domain, messageOf(e));
                    return R.err("无法确定哪些入口节点还写着 " + domain + " 的 IP（" + messageOf(e)
                            + "），已保留记录、未改动任何节点；请先修复相关转发/隧道数据再删除");
                }
                String renderRisk = siblingRenderRisk(targets, domain);
                if (renderRisk != null) {
                    log.warn("DDNS 删除 {} 前发现同转发兄弟域名的渲染映射不可用：{}", domain, renderRisk);
                    return R.err("无法删除：" + renderRisk + "，已保留记录");
                }
                // 删除的前置条件是「每个入口节点都能确认回落」。先全量确认在线再动节点，与 apply()
                // 同一口径：只要有一个节点不在线，就整批放弃，一个节点都不碰。
                //
                // 不能「先删记录再统计失败数」：记录一没，注册表里也没有这个域名，节点上残留的旧 IP
                // 就再没有任何持久状态可对账，也没有第二次回落机会。
                List<DdnsTarget> staged = stage(targets, domain, null);
                for (DdnsTarget target : staged) {
                    if (isNodeOnline(target.node().getId())) {
                        continue;
                    }
                    return R.err("无法删除：入口节点 " + target.node().getId()
                            + " 不在线，无法确认它已把 " + domain + " 回落成域名；"
                            + "已保留记录与渲染映射、未改动任何节点，待节点上线后重试");
                }
                List<DdnsTarget> applied = new ArrayList<>();
                for (DdnsTarget target : staged) {
                    String response = safeUpdate(target);
                    if (!isOk(response)) {
                        // 已经确认回落的节点改回记录里的地址：记录还在，库的状态就是节点该有的状态。
                        // 改不回去的照实说，交给下次服务上报按库对账，不假装成功。
                        String rollbackError = rollbackConfirmed(domain, record, applied);
                        restoreRegistry(record);
                        return R.err("无法删除：节点 " + target.node().getId() + " 未能确认把 " + domain
                                + " 回落成域名（" + response + "）；"
                                + (staged.size() - applied.size()) + " 个入口节点状态未确认，"
                                + "已保留记录与渲染映射；"
                                + fallbackState(record, applied.size(), rollbackError)
                                + "，待下次服务上报按库对账");
                    }
                    applied.add(target);
                }
                registry.remove(domain);
                int deleted;
                try {
                    deleted = ddnsRecordMapper.deleteById(id);
                } catch (RuntimeException e) {
                    log.warn("DDNS 记录 {} 删除异常：{}", domain, messageOf(e));
                    deleted = 0;
                }
                if (deleted <= 0) {
                    restoreRegistry(record);
                    String rollbackError = rollbackConfirmed(domain, record, applied);
                    return R.err("DDNS 记录删除未生效（受影响 " + deleted + " 行），记录仍在；"
                            + (targets.isEmpty()
                                    ? "没有活跃转发引用该域名，未改动任何节点"
                                    : fallbackState(record, applied.size(), rollbackError)));
                }
                if (targets.isEmpty()) {
                    return R.ok("已删除，没有活跃转发引用该域名");
                }
                return R.ok("已删除，节点已回落到域名");
            } finally {
                window.close();
            }
        } finally {
            release(id);
        }
    }

    /** 手动检查；失败返回错误而不是把旧值当成功。 */
    public R checkNow(Long id) {
        DdnsRecord record = ddnsRecordMapper.selectById(id);
        if (record == null) {
            return R.err("DDNS 记录不存在");
        }
        if (!acquire(id)) {
            return R.err("该域名正在检查，请稍后再试");
        }
        try {
            // 闸门内重读：selectById 与 acquire 之间 update/delete 可能已经完整跑完，
            // 拿闸门外读到的旧实体下发会「并发复活」——记录删了节点照样被推新 IP，
            // 整实体回写还会把 update 刚改的 enabled/interval 覆盖回去。
            DdnsRecord current = ddnsRecordMapper.selectById(id);
            if (current == null) {
                return R.err("DDNS 记录不存在");
            }
            DdnsCheckResult result = checkInternal(current);
            if (!result.success()) {
                return R.err(result.message());
            }
            return R.ok(result);
        } finally {
            release(id);
        }
    }

    /** 调度入口：只跑到期且启用的记录，返回简述供日志使用。 */
    public String checkDueRecords() {
        if (!schedulerRunning.compareAndSet(false, true)) {
            return "上一轮 DDNS 检查尚未结束，跳过";
        }
        try {
            long now = System.currentTimeMillis();
            List<DdnsRecord> records = ddnsRecordMapper.selectList(
                    new QueryWrapper<DdnsRecord>().eq("enabled", 1));
            int checked = 0;
            int changed = 0;
            int failed = 0;
            for (DdnsRecord record : records) {
                if (!isDue(record, now) || !checking.add(record.getId())) {
                    continue;
                }
                try {
                    // 列表是加闸门之前取的快照：抢到闸门后按库里的当前行重新确认，
                    // 否则会继续检查已删除/已停用的记录，把节点和记录一起写回旧状态。
                    DdnsRecord current = ddnsRecordMapper.selectById(record.getId());
                    if (current == null || !Integer.valueOf(1).equals(current.getEnabled())) {
                        continue;
                    }
                    checked++;
                    DdnsCheckResult result = checkInternal(current);
                    if (result.changed()) {
                        changed++;
                    }
                    if (!result.success()) {
                        failed++;
                        log.warn("DDNS {} 检查失败：{}", record.getDomain(), result.message());
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.error("DDNS {} 检查异常", record.getDomain(), e);
                } finally {
                    checking.remove(record.getId());
                }
            }
            return "checked=" + checked + ", changed=" + changed + ", failed=" + failed;
        } finally {
            schedulerRunning.set(false);
        }
    }

    /**
     * 同一条记录的 update/delete/check 共用一把闸门。
     *
     * <p>不互斥的后果是「并发复活」：删除正在把节点回落成域名时，另一条检查线程还拿着
     * 新解析到的 IP 重新下发，记录删掉后节点就永久停在这个 IP 上；update 也一样会被
     * 检查线程手里的旧实体覆盖回旧配置。这里用非阻塞抢锁：抢不到就直接告诉调用方
     * 正在检查，比排队更容易理解，也不会在锁上堆积请求。
     */
    private boolean acquire(Long id) {
        return id == null || checking.add(id);
    }

    private void release(Long id) {
        if (id != null) {
            checking.remove(id);
        }
    }

    /**
     * 删除没生效时把渲染映射放回与库一致的状态：记录还在，节点就该按记录的 IP 渲染。
     *
     * <p>主地址为空也必须 {@code put}：{@code knownDomains} 是「这个域名归 DDNS 管、只是还没解析出
     * 地址」的唯一载体，漏登记会让未解析域名从摘要闸门里消失，按摘要删建的通道被重新打开。
     */
    private void restoreRegistry(DdnsRecord record) {
        registry.put(record.getDomain(), record.getPrimaryIp());
    }

    /**
     * 删除中止时说明节点与库（记录仍在）的真实关系：能确定的说确定，不能确定的点明待对账。
     *
     * <p>记录还在，节点就该按记录里的地址提供服务 —— 主地址是 IP 时，已回落的节点被改回该 IP；
     * 主地址为空时「回落成域名」本身与库一致，不需要再下发。
     */
    private static String fallbackState(DdnsRecord record, int confirmed, String rollbackError) {
        if (confirmed == 0) {
            return "没有节点确认回落";
        }
        String primaryIp = record.getPrimaryIp();
        if (!DdnsAddresses.isIpLiteral(primaryIp)) {
            return confirmed + " 个节点已回落到域名（记录没有主地址，与库一致）";
        }
        return rollbackError.isEmpty()
                ? "已把 " + confirmed + " 个已回落的节点改回记录中的 " + primaryIp + "，与库一致"
                : confirmed + " 个已回落节点的地址状态未确认" + rollbackError;
    }

    /**
     * 启动时把已落库的主地址装回渲染表；表还没建好时只告警，不阻断启动。
     *
     * <p>返回值是「这次查库成功了吗」，而不是「装了多少条」：空表也是成功，这一点必须能被
     * 调用方区分开 —— 装载成功才会把注册表标成就绪，节点摘要也才允许参与漂移判定。
     *
     * @return 查库并装载成功返回 true；读库失败返回 false（注册表停在 FAILED，摘要对账停手）
     */
    public boolean loadRegistry() {
        try {
            long snapshotVersion = registry.snapshotVersion();
            if (snapshotVersion < 0) {
                log.info("DDNS 正在切换，暂缓重载映射");
                return false;
            }
            List<DdnsRecord> records = ddnsRecordMapper.selectList(null);
            if (records == null) {
                records = List.of();
            }
            Map<String, String> entries = new HashMap<>();
            int loaded = 0;
            for (DdnsRecord record : records) {
                String domain = record == null ? null : record.getDomain();
                if (domain == null || domain.trim().isEmpty()) {
                    continue;
                }
                entries.put(domain, record.getPrimaryIp());
                if (DdnsAddresses.isIpLiteral(record.getPrimaryIp())) {
                    loaded++;
                }
            }
            if (!registry.applyLoadedSnapshot(snapshotVersion, entries)) {
                log.info("DDNS 映射在查库期间发生变化，放弃旧快照并等待重试");
                return false;
            }
            log.info("DDNS 已恢复 {} 条域名到 IP 的渲染映射（登记 {} 条，未解析 {} 条）",
                    loaded, entries.size(), registry.unresolvedDomains().size());
            return true;
        } catch (RuntimeException e) {
            registry.markLoadFailed(e.getMessage());
            log.warn("DDNS 渲染映射恢复失败，节点摘要对账暂停（只补缺失、不做删建），稍后自动重试：{}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * 确保渲染映射已经装好；没装好就再试一次。
     *
     * <p>给调度轮次用的重试入口：启动窗口内先连上来的节点不会等 bootstrap，
     * 一次读库失败也不该让进程永久停在「按域名下发」的降级态。
     */
    public boolean ensureRegistryLoaded() {
        if (registry.isReady()) {
            return true;
        }
        return loadRegistry();
    }

    DdnsCheckResult checkInternal(DdnsRecord record) {
        long now = System.currentTimeMillis();
        List<String> raw;
        try {
            raw = resolver.resolve(record.getDomain());
        } catch (Exception e) {
            return fail(record, now, "DNS 解析失败：" + messageOf(e));
        }
        List<String> fresh = DdnsAddresses.normalizeIps(raw);
        if (fresh.isEmpty()) {
            return fail(record, now, "DNS 未返回可用 A/AAAA 地址");
        }
        return apply(record, fresh, now);
    }

    /** 核心状态迁移；包级可见，单测直接喂固定地址，不碰真实 DNS。 */
    DdnsCheckResult apply(DdnsRecord record, List<String> fresh, long now) {
        String domain = record.getDomain();
        String oldPrimary = record.getPrimaryIp() == null || record.getPrimaryIp().isBlank()
                ? null : record.getPrimaryIp();
        String newPrimary = DdnsAddresses.choosePrimary(oldPrimary, fresh);
        Set<String> oldIps = DdnsAddresses.splitCsv(record.getResolvedIps());
        boolean setChanged = !oldIps.equals(new LinkedHashSet<>(fresh));

        if (oldPrimary != null && oldPrimary.equalsIgnoreCase(newPrimary)) {
            record.setResolvedIps(DdnsAddresses.join(fresh));
            record.setPrimaryIp(newPrimary);
            record.setLastCheckTime(now);
            record.setLastError("");
            record.setFailCount(0);
            if (setChanged) {
                record.setLastChangeTime(now);
            }
            record.setUpdatedTime(now);
            if (ddnsRecordMapper.updateById(record) <= 0) {
                return DdnsCheckResult.fail("DDNS 记录落库失败", record);
            }
            registry.put(domain, newPrimary);
            return DdnsCheckResult.ok(false, 0, setChanged ? "地址集合已更新，主地址未漂移" : "无变化", record);
        }

        // P0：节点确认之前绝不能把新地址写进注册表。整个下发窗口独占该域名的写锁：
        // 期间任何普通转发编辑/删除/状态变更/对账/节点重挂都拿不到读锁，共享同一条 forward 的
        // 其它域名也一并被挡住，避免「节点上已是新值、注册表还是旧值」时被对账改回旧地址。
        //
        // 先抢窗口再规划：planTargets 的快照必须与随后下发的一致，否则普通编辑可以在
        // 「规划完、抢窗口前」改完节点并落库，切换随后按旧快照把节点改了回去。
        DdnsDomainGate.Lease window = registry.beginTransitionLease(domain,
                "主地址 " + (oldPrimary == null ? "-" : oldPrimary) + " → " + newPrimary + " 下发中");
        if (window == null) {
            DdnsAddressRegistry.Transition active = registry.activeTransition();
            if (active != null) {
                return fail(record, now, "无法开始切换：域名 " + active.domain()
                        + " 正在 DDNS 切换（" + active.note() + "），本次未改动任何节点");
            }
            return fail(record, now, "无法开始切换：域名 " + domain
                    + " 正有其它下发（编辑/删除/对账）在写节点，等待超时；本次未改动任何节点，请稍后重试");
        }
        try {
            List<DdnsTarget> targets;
            try {
                targets = planTargets(domain);
            } catch (RuntimeException e) {
                return fail(record, now, "转发规则规划失败：" + messageOf(e));
            }
            int affectedForwards = (int) targets.stream()
                    .map(target -> target.forward().getId()).distinct().count();

            if (targets.isEmpty()) {
                return persistChange(record, domain, fresh, oldPrimary, newPrimary, now, 0, List.of(), setChanged);
            }
            String renderRisk = siblingRenderRisk(targets, domain);
            if (renderRisk != null) {
                return fail(record, now, renderRisk);
            }
            for (DdnsTarget target : targets) {
                if (!applier.isOnline(target.node().getId())) {
                    return fail(record, now, "入口节点不在线：node " + target.node().getId()
                            + "，为避免半套配置，未做任何修改");
                }
            }

            List<DdnsTarget> applied = new ArrayList<>();
            for (DdnsTarget target : stage(targets, domain, newPrimary)) {
                String response = safeUpdate(target);
                if (!isOk(response)) {
                    List<DdnsTarget> touched = new ArrayList<>(applied);
                    touched.add(target);
                    String rollbackError = rollback(domain, oldPrimary, touched);
                    return fail(record, now, "节点 " + target.node().getId() + " 下发失败（" + response + "）"
                            + "，已尝试回滚 " + touched.size() + " 个节点" + rollbackError);
                }
                applied.add(target);
            }

            return persistChange(record, domain, fresh, oldPrimary, newPrimary, now,
                    affectedForwards, applied, setChanged);
        } finally {
            window.close();
        }
    }

    /**
     * 把本次窗口要下发的目标列表显式渲染好：该域名用 {@code replacement}（新地址 / 旧地址 /
     * null 表示回落到域名本身），其余域名仍按已确认映射解析。全程不读该域名的登记值。
     */
    private List<DdnsTarget> stage(List<DdnsTarget> targets, String domain, String replacement) {
        List<DdnsTarget> staged = new ArrayList<>(targets.size());
        for (DdnsTarget target : targets) {
            staged.add(new DdnsTarget(target.serviceName(), target.limiter(), target.node(),
                    target.forward(), target.forwardPort(), target.tunnel(),
                    registry.renderTargets(target.forward().getRemoteAddr(), domain, replacement)));
        }
        return staged;
    }

    private DdnsCheckResult persistChange(DdnsRecord record, String domain, List<String> fresh,
                                          String oldPrimary, String newPrimary, long now,
                                          int affectedForwards, List<DdnsTarget> applied,
                                          boolean setChanged) {
        String previousIps = record.getResolvedIps();
        String previousPrimary = record.getPrimaryIp();
        Long previousChangeTime = record.getLastChangeTime();
        record.setResolvedIps(DdnsAddresses.join(fresh));
        record.setPrimaryIp(newPrimary);
        record.setLastCheckTime(now);
        record.setLastChangeTime(now);
        record.setLastError("");
        record.setFailCount(0);
        record.setUpdatedTime(now);
        try {
            if (ddnsRecordMapper.updateById(record) <= 0) {
                throw new IllegalStateException("updateById 返回 0");
            }
        } catch (RuntimeException e) {
            record.setResolvedIps(previousIps);
            record.setPrimaryIp(previousPrimary);
            record.setLastChangeTime(previousChangeTime);
            String rollbackError = rollback(domain, oldPrimary, applied);
            return fail(record, now, "DDNS 记录落库失败（" + messageOf(e) + "），已尝试回滚 " + applied.size() + " 个节点" + rollbackError);
        }
        registry.put(domain, newPrimary);
        return DdnsCheckResult.ok(true, affectedForwards,
                "主地址 " + (oldPrimary == null ? "-" : oldPrimary) + " → " + newPrimary
                        + (setChanged ? "，地址集合已更新" : ""), record);
    }

    /**
     * 回滚已下发过的节点。目标列表同样显式渲染（旧地址；没有旧地址就回落成域名），
     * 不通过注册表 —— 窗口内它还没有新值，也没什么可「恢复」的，写它只会制造假状态。
     *
     * <p>回滚本身失败时返回「回滚未确认」，调用方必须如实返回：此时节点上到底是新值、
     * 旧值还是半套配置无法确定，只能靠下次对账收敛。
     */
    private String rollback(String domain, String oldPrimary, List<DdnsTarget> targets) {
        List<String> failures = new ArrayList<>();
        for (DdnsTarget target : stage(targets, domain, oldPrimary)) {
            String response = safeUpdate(target);
            if (!isOk(response)) {
                failures.add("node" + target.node().getId() + "(" + response + ")");
            }
        }
        return failures.isEmpty() ? "" : "；回滚未确认：" + String.join("，", failures);
    }

    /**
     * 删除前确认节点在线。查询本身抛异常（节点侧接口异常、超时）一律按「未确认」处理：
     * 这时候既不能当它在线照发，也不能当成功照删。
     */
    private boolean isNodeOnline(Long nodeId) {
        try {
            return applier.isOnline(nodeId);
        } catch (RuntimeException e) {
            log.warn("DDNS 删除前查询节点 {} 在线状态异常：{}", nodeId, messageOf(e));
            return false;
        }
    }

    /**
     * 把已经确认回落的节点改回记录里的地址。记录没有可用地址时不下发：
     * 那些节点停在域名上，正是保留记录（主地址为空）该有的渲染结果。
     */
    private String rollbackConfirmed(String domain, DdnsRecord record, List<DdnsTarget> applied) {
        if (applied.isEmpty() || !DdnsAddresses.isIpLiteral(record.getPrimaryIp())) {
            return "";
        }
        return rollback(domain, record.getPrimaryIp(), applied);
    }

    private String safeUpdate(DdnsTarget target) {
        try {
            String response = applier.update(target);
            return response == null ? "节点无响应" : response;
        } catch (RuntimeException e) {
            return "下发异常：" + messageOf(e);
        }
    }

    /**
     * 同一条转发上还写着别的域名时，本次下发会顺带重写它们的渲染结果：{@code stage} 只给本次
     * 切换的域名显式指定地址，兄弟域名一律按注册表解析，解析不到就原样保留成域名本身。
     *
     * <p>注册表没就绪（启动窗口内读库失败、后台重试未成功）时库里明明存着兄弟域名的主地址，
     * 节点上却会被写回域名 —— 于是「手点一次检查」就能把节点上还能用的 IP 换掉。这里在动
     * 节点之前确认映射，不可用就整批放弃，让记录、注册表和节点都停在原样。
     *
     * <p>没有兄弟域名时零开销直接放行（单域名转发不受影响）；检查就绪时
     * {@link #ensureRegistryLoaded()} 短路返回，也不会反复读库。
     *
     * @return null 表示可以继续下发；否则是放弃本次下发的原因
     */
    private String siblingRenderRisk(List<DdnsTarget> targets, String domain) {
        String self = "";
        try {
            self = DdnsAddresses.normalizeDomain(domain);
        } catch (IllegalArgumentException ignored) {
            // 域名本身不合法时无法区分「自己」和兄弟域名，按最坏情况处理
        }
        Set<String> siblings = new LinkedHashSet<>();
        for (DdnsTarget target : targets) {
            for (String entry : DdnsAddresses.splitCsv(target.forward().getRemoteAddr())) {
                String host = DdnsAddresses.hostOf(entry);
                if (host.isEmpty() || DdnsAddresses.isIpLiteral(host)) {
                    continue;
                }
                String normalized;
                try {
                    normalized = DdnsAddresses.normalizeDomain(host);
                } catch (IllegalArgumentException e) {
                    // 畸形 host 不会被映射替换，也就不存在「被写回域名」的风险
                    continue;
                }
                if (!normalized.equals(self)) {
                    siblings.add(normalized);
                }
            }
        }
        if (siblings.isEmpty() || ensureRegistryLoaded()) {
            return null;
        }
        String cause = registry.loadError() == null ? "原因未知" : registry.loadError();
        return "渲染映射未就绪（" + cause + "）：同一条转发上还有 " + String.join("、", siblings)
                + "，缺它们的已确认地址时继续下发会把它们从 IP 改回域名，"
                + "已放弃本次下发、未改动任何节点；映射恢复就绪后可直接重试";
    }

    /** 按 ForwardServiceImpl 的入口口径，找出所有引用该域名的活跃转发及其入口服务。 */
    private List<DdnsTarget> planTargets(String domain) {
        List<DdnsTarget> targets = new ArrayList<>();
        Map<Integer, Boolean> adminOwners = new HashMap<>();
        List<Forward> forwards = forwardService.list(new QueryWrapper<Forward>().eq("status", 1));
        for (Forward forward : forwards) {
            if (!DdnsAddresses.targetUsesDomain(forward.getRemoteAddr(), domain)) {
                continue;
            }
            if (forward.getUserId() == null) {
                throw new IllegalStateException("转发 " + forward.getId() + " 缺少 user_id，无法确定入口服务名");
            }
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null) {
                throw new IllegalStateException("转发 " + forward.getId() + " 的隧道不存在");
            }
            List<ChainTunnel> entries = chainTunnelService.list(new QueryWrapper<ChainTunnel>()
                    .eq("tunnel_id", tunnel.getId())
                    .eq("chain_type", 1));
            if (entries == null || entries.isEmpty()) {
                throw new IllegalStateException("转发 " + forward.getId() + " 的隧道没有入口节点");
            }
            // 服务名与限速必须和 ForwardServiceImpl 创建时逐字一致，否则会打到不存在的服务上
            // （GOST 那边没有同名服务，UpdateService 只会新增一个空壳），或者给不该限速的
            // 入口凭空加上 limiter。创建口径见 ForwardServiceImpl.buildServiceName：
            // 管理员走「不查 user_tunnel、不给 limiter」的分支，后缀固定为 0，
            // 即使 user_tunnel 表里恰好有这一行也不能用。
            UserTunnel userTunnel = isAdminOwner(forward.getUserId(), adminOwners)
                    ? null
                    : userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                            .eq("user_id", forward.getUserId())
                            .eq("tunnel_id", tunnel.getId()), false);
            Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();
            String serviceName = forward.getId() + "_" + forward.getUserId() + "_"
                    + (userTunnel == null ? 0 : userTunnel.getId());
            for (ChainTunnel entry : entries) {
                Node node = nodeService.getById(entry.getNodeId());
                if (node == null) {
                    throw new IllegalStateException("转发 " + forward.getId() + " 的入口节点不存在");
                }
                ForwardPort port = forwardPortService.getOne(new QueryWrapper<ForwardPort>()
                        .eq("forward_id", forward.getId())
                        .eq("node_id", entry.getNodeId()), false);
                if (port == null) {
                    throw new IllegalStateException("转发 " + forward.getId() + " 在节点 "
                            + entry.getNodeId() + " 上没有分配端口");
                }
                targets.add(new DdnsTarget(serviceName, limiter, node, forward, port, tunnel));
            }
        }
        return targets;
    }

    /**
     * 这条转发的属主是不是管理员（roleId=0）。
     *
     * <p>只用于还原创建时的服务名口径；同一次规划内按 userId 缓存，避免同一属主的多条
     * 转发重复查库。查不到用户时按「非管理员」处理：那是数据异常，后续名称对不上会
     * 在下发失败时暴露出来，比默认套用管理员后缀更安全。
     */
    private boolean isAdminOwner(Integer userId, Map<Integer, Boolean> cache) {
        return cache.computeIfAbsent(userId, id -> {
            User owner = userService.getById(id);
            return owner != null && Integer.valueOf(0).equals(owner.getRoleId());
        });
    }

    private DdnsCheckResult fail(DdnsRecord record, long now, String message) {
        String error = truncate(message);
        record.setLastCheckTime(now);
        record.setLastError(error);
        record.setFailCount((record.getFailCount() == null ? 0 : record.getFailCount()) + 1);
        record.setUpdatedTime(now);
        try {
            ddnsRecordMapper.updateById(record);
        } catch (RuntimeException e) {
            log.error("DDNS {} 失败状态落库失败", record.getDomain(), e);
        }
        return DdnsCheckResult.fail(error, record);
    }

    private static boolean isDue(DdnsRecord record, long now) {
        long interval = (record.getIntervalSeconds() == null
                ? DEFAULT_INTERVAL_SECONDS : record.getIntervalSeconds()) * 1000L;
        Long last = record.getLastCheckTime();
        return last == null || last <= 0 || now - last >= interval;
    }

    private static boolean isOk(String response) {
        return response != null && (response.equals("OK") || response.startsWith("OK；"));
    }

    private static int interval(Integer value) {
        int interval = value == null ? DEFAULT_INTERVAL_SECONDS : value;
        if (interval < 10 || interval > 3600) {
            throw new IllegalArgumentException("检查间隔必须在 10 到 3600 秒之间");
        }
        return interval;
    }

    private static int enabled(Integer value) {
        int enabled = value == null ? 1 : value;
        if (enabled != 0 && enabled != 1) {
            throw new IllegalArgumentException("enabled 只能是 0 或 1");
        }
        return enabled;
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        String value = message.trim();
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
