package com.admin.service.impl;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.DriftCircuitBreaker;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.MapParamUtils;
import com.admin.common.utils.PauseReason;
import com.admin.common.utils.QuotaResume;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.ForwardMapper;
import com.admin.service.*;
import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * <p>
 * 端口转发服务实现类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class ForwardServiceImpl extends ServiceImpl<ForwardMapper, Forward> implements ForwardService {

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    /**
     * 节点上报摘要里的哨兵键，与 WebSocketServer.toDigestMap 写入的键名逐字一致：
     * 本轮任意一条服务带了 listener / iface 才有该键。没有哨兵 = 老节点，整维度跳过。
     */
    private static final String LISTENER_SUPPORTED_KEY = "@listener_supported";
    private static final String IFACE_SUPPORTED_KEY = "@iface_supported";
    /** 转发策略档位（forwarder.selector）维度的哨兵，见 {@link WebSocketServer} 的 toDigestMap。 */
    private static final String FSELECTOR_SUPPORTED_KEY = "@fselector_supported";
    /** 暂停标志（metadata.paused）维度的哨兵：老节点不报，整维度跳过，不能凭缺失判运行态。 */
    private static final String PAUSED_SUPPORTED_KEY = "@paused_supported";
    /**
     * 「配置真的全空也会上报」的哨兵，与 WebSocketServer#toDigestMap 写入的键名逐字一致。
     *
     * <p>老节点在 services/chains 双空时压根不上报，收到双空清单只能当脏数据丢弃；
     * 新节点（round46 起）在 features 里声明 emptyReport，双空上报是合法证据，必须按库对账。
     */
    private static final String EMPTY_REPORT_SUPPORTED_KEY = "@emptyReport_supported";

    /** selector 摘要里与 strategy 无关的两段固定值：maxFails、failTimeout(ns)，与 GostUtil 下发口径一致。 */
    private static final String FSELECTOR_FIXED_TAIL = "1|600000000000";
    /** 节点声明支持中继链网卡摘要（features 含 chainIface）；有它才比，避免老节点满屏误判。 */
    private static final String CHAIN_IFACE_SUPPORTED_KEY = "@chain_iface_supported";

    // 自动分口撞上节点上的僵尸监听时，最多试这么多个候选端口（含首次分配的那个），与 TunnelServiceImpl 同口径
    private static final int AUTO_PORT_ATTEMPTS = 3;

    /**
     * 单轮对账的修复预算上限：一次 resyncForwardServices 最多下发这么多次修复动作。
     *
     * <p>缺的这道闸门由 round46 的 emptyReport 打开：节点配置被清空、库里又有大量活跃转发时，
     * 旧实现会在同一轮里连着下发几十上百个 AddService（真机 10 个起就明显挤压 WS 通道与
     * gost 配置写入），而每个下发都要等节点回包，一轮能把上报线程拖到分钟级。
     *
     * <p>只统计真正产生下发的动作（整对补齐、单协议补齐、变体重建、摘要漂移重建、暂停纠正），
     * 「已经一致」的分支不占额度。额度只在单次调用内计数、不落库：节点 5 分钟后的下一次
     * info_request 会继续处理本轮剩下的条目，多轮之后收敛。
     */
    private static final int MAX_REPAIRS_PER_ROUND = 30;

    /**
     * 中继资源（chains_&lt;tid&gt; 与 &lt;tid&gt;_tls）的单轮修复预算，与转发入口服务分开计数。
     *
     * <p>为什么不共用 {@link #MAX_REPAIRS_PER_ROUND}：链必须先于服务就绪（入口服务的 handler 引用
     * chains_&lt;tid&gt;），共用一份额度时谁先跑谁把 30 吃光，另一侧整轮动不了 —— 空配置节点恢复时
     * 表现为「要么只有链没有服务、要么只有服务没有链」，两种都业务不通。链资源按隧道计数，
     * 数量通常远小于转发数，10 条足够一轮推进（QA round47 WARN-3）。
     */
    private static final int MAX_CHAIN_REPAIRS_PER_ROUND = 10;

    /**
     * 转发对账的轮转游标：nodeId → 下一轮的起始下标。
     *
     * <p>预算耗尽即 break，若每轮都从第 0 条开始，排在前面的一批持久坏项（目标地址非法、
     * 端口被非 gost 进程永久占用等非连续失败）会吃掉全部 30 个额度，第 31 条之后的正常转发
     * 永远轮不到（QA round47 WARN-4）。游标按本轮实际消耗推进，下一轮从断点继续。
     * 只在内存里，进程重启从头开始，不落库。
     */
    private final Map<Long, Integer> resyncCursors = new ConcurrentHashMap<>();

    /**
     * 同名转发的串行化锁：check-then-insert 之间不做互斥时，两个并发创建会各自查到"没有重名"再各插一条
     * （round26 真机 5 并发创建同名 r27-race-1，库里出现 5 条同名转发）。
     * <p>
     * 只对同一 (userId, tunnelId, name) 串行，其他转发照常并发；锁只包住"查重 + 写库"这一段，
     * 节点下发仍在线程内继续持有锁之外执行（不把秒级的 WS 往返塞进临界区）。
     * <p>
     * 进程内锁，多实例部署时仍可能重复，属已知残留（与 TunnelServiceImpl 的 TOPOLOGY_LOCKS 同口径）。
     */
    private static final ConcurrentHashMap<String, ReentrantLock> FORWARD_NAME_LOCKS = new ConcurrentHashMap<>();

    /**
     * 入口节点端口分配的串行化锁：getNodePort 只读库（"这段端口没人用"），到 forward_port 落库之间没有互斥时，
     * 两个并发创建针对同一个入口节点会各自读到同一批空闲端口，把同一个 (node_id, port) 写进两条转发。
     * 库里没有唯一约束时两条都算成功，节点上只有一条真的监听得起来，另一条面板正常却永远连不通（round52）。
     * <p>
     * 锁按 nodeId 分桶，不同入口节点之间照常并发；一次创建涉及多个入口节点时按 nodeId 升序取锁，
     * 保证两个请求不会各自拿着一把再去抢对方那一把。键的数量受节点总数约束，不需要像
     * {@link #FORWARD_NAME_LOCKS} 那样用完就摘。
     * <p>
     * 进程内锁，多实例部署时仍可能撞——那一层由数据库唯一索引 uk_forward_port_node_port 与
     * {@link #saveForwardPort} 的冲突捕获兜底。
     */
    private static final ConcurrentHashMap<Long, ReentrantLock> NODE_PORT_LOCKS = new ConcurrentHashMap<>();

    /**
     * 配置漂移「删除+重建」熔断器：同一条资源连续漂移 {@link DriftCircuitBreaker#DEFAULT_THRESHOLD} 次
     * （或重建一直失败、节点侧口径永远对不上）就冷却一段时间，期间只记说明不再动节点。
     *
     * <p>本类是 Spring 单例 bean，一个实例跨心跳累计即可。这里特意用实例字段而不是共享静态实例：
     * 生产只有一个 bean，语义完全等价；而 compat 测试在同一个 JVM 里会用同一个 key 反复造漂移
     * （ServiceDriftResyncTest 一个类就有 8 次同 key 漂移），静态实例会跨用例累计，把既有用例打成熔断。
     */
    /**
     * 每个 service 实例一份熔断状态（生产只有一个 bean）；登记后管理端 {@code POST /api/v1/node/breaker}
     * 能看到它。刻意不用全局单例：单例会把上一个用例/上一个租户的计数带进来。
     */
    private DriftCircuitBreaker driftBreaker = DriftCircuitBreaker.registered(
            DriftCircuitBreaker.DEFAULT_THRESHOLD, DriftCircuitBreaker.DEFAULT_COOLDOWN_MS);

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserService userService;

    @Resource
    NodeService nodeService;

    @Resource
    ChainTunnelService chainTunnelService;

    @Resource
    ForwardPortService forwardPortService;

    @Override
    public R getAllForwards() {
        UserInfo currentUser = getCurrentUserInfo();
        List<ForwardWithTunnelDto> forwardList;
        if (currentUser.getRoleId() != 0) {
            forwardList = baseMapper.selectForwardsWithTunnelByUserId(currentUser.getUserId());
        } else {
            forwardList = baseMapper.selectAllForwardsWithTunnel();
        }

        // 填充入口IP和端口信息
        for (ForwardWithTunnelDto forward : forwardList) {
            // 获取隧道信息
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null) continue;

            // 获取该转发的所有ForwardPort记录
            List<ForwardPort> forwardPorts = forwardPortService.list(
                    new QueryWrapper<ForwardPort>().eq("forward_id", forward.getId())
            );

            if (forwardPorts.isEmpty()) continue;

            // 判断是否使用隧道的inIp
            boolean useTunnelInIp = tunnel.getInIp() != null && !tunnel.getInIp().trim().isEmpty();

            Set<String> ipPortSet = new LinkedHashSet<>();

            if (useTunnelInIp) {
                // 使用隧道的inIp（求笛卡尔积）
                List<String> ipList = new ArrayList<>();
                List<Integer> portList = new ArrayList<>();
                
                String[] tunnelInIps = tunnel.getInIp().split(",");
                for (String ip : tunnelInIps) {
                    if (ip != null && !ip.trim().isEmpty()) {
                        ipList.add(ip.trim());
                    }
                }
                
                // 收集所有端口
                for (ForwardPort forwardPort : forwardPorts) {
                    if (forwardPort.getPort() != null) {
                        portList.add(forwardPort.getPort());
                    }
                }
                
                // 去重
                List<String> uniqueIps = ipList.stream().distinct().toList();
                List<Integer> uniquePorts = portList.stream().distinct().toList();
                
                // 组合 IP:Port（笛卡尔积）
                for (String ip : uniqueIps) {
                    for (Integer port : uniquePorts) {
                        ipPortSet.add(ip + ":" + port);
                    }
                }
                
                // inPort设置为第一个端口（用于向后兼容）
                if (!uniquePorts.isEmpty()) {
                    forward.setInPort(uniquePorts.getFirst());
                }
            } else {
                // 使用节点的serverIp（一对一，不求笛卡尔积）
                for (ForwardPort forwardPort : forwardPorts) {
                    Node node = nodeService.getById(forwardPort.getNodeId());
                    if (node != null && node.getServerIp() != null && forwardPort.getPort() != null) {
                        ipPortSet.add(node.getServerIp() + ":" + forwardPort.getPort());
                    }
                }
                
                // inPort设置为第一个端口（用于向后兼容）
                if (!forwardPorts.isEmpty() && forwardPorts.getFirst().getPort() != null) {
                    forward.setInPort(forwardPorts.getFirst().getPort());
                }
            }

            // 设置入口IP
            if (!ipPortSet.isEmpty()) {
                forward.setInIp(String.join(",", ipPortSet));
            }
        }

        return R.ok(forwardList);
    }

    @Override
    public R createForward(ForwardDto forwardDto) {
        UserInfo currentUser = getCurrentUserInfo();

        Tunnel tunnel = validateTunnel(forwardDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        if (tunnel.getStatus() != 1) {
            return R.err("隧道已禁用，无法创建转发");
        }

        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }
        String duplicateName = duplicateForwardName(currentUser.getUserId(), tunnel.getId(), forwardDto.getName(), null);
        if (duplicateName != null) {
            return R.err(duplicateName);
        }
        Forward forward = new Forward();
        BeanUtils.copyProperties(forwardDto, forward);
        forward.setName(trimName(forwardDto.getName()));
        forward.setStatus(1);
        forward.setUserId(currentUser.getUserId());
        forward.setUserName(currentUser.getUserName());
        forward.setCreatedTime(System.currentTimeMillis());
        forward.setUpdatedTime(System.currentTimeMillis());
        List<JSONObject> success = new ArrayList<>();
        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));
        if (chainTunnels.isEmpty()) {
            // 没有入口节点就没有可写库的端口，先落库再下发只会留下一条永远连不通的转发记录
            return R.err("隧道没有入口节点，无法创建转发");
        }
        // 端口分配是「查空闲 → 写库」两步：先按 nodeId 升序把入口节点的分配锁全部拿到手，
        // 再查端口、再落库，中间任何一步都不放开，避免两个并发创建分到同一个 (node_id, port)。
        // 锁要一直持有到最后一个 forward_port 写入完成——循环里每写一行就马上下发节点服务，
        // 撤回与端口回写都依赖这行已经在库里，因此节点下发也在同一段临界区内（单次创建、多入口节点的量级）。
        List<ReentrantLock> portLocks = lockNodePorts(chainTunnels.stream().map(ChainTunnel::getNodeId).toList());
        try {
            chainTunnels = get_port(chainTunnels, forwardDto.getInPort(), 0L);
            // 上面的查重是快速失败；真正防并发重名靠这把锁里的二次查重：两个并发请求可能同时通过第一次查重
            String nameKey = forwardNameKey(currentUser.getUserId(), tunnel.getId(), forwardDto.getName());
            ReentrantLock nameLock = FORWARD_NAME_LOCKS.computeIfAbsent(nameKey, k -> new ReentrantLock());
            String forwardWriteError;
            nameLock.lock();
            try {
                String racedDuplicate = duplicateForwardName(currentUser.getUserId(), tunnel.getId(), forwardDto.getName(), null);
                if (racedDuplicate != null) {
                    return R.err(racedDuplicate);
                }
                forwardWriteError = dbWriteError(() -> this.save(forward), "转发记录");
            } finally {
                nameLock.unlock();
                if (!nameLock.hasQueuedThreads()) {
                    FORWARD_NAME_LOCKS.remove(nameKey, nameLock);
                }
            }
            if (forwardWriteError != null) {
                return R.err(forwardWriteError + "，未下发任何节点");
            }

            // 已确认落库的端口记录条数：清理时 0 行删除只有在"写过"的前提下才说明没删掉
            int savedPorts = 0;
            // 只有没指定 inPort 的请求才是自动分配端口：自动分到的端口节点上可能被僵尸监听占着，才允许换口
            boolean autoPort = forwardDto.getInPort() == null;
            for (ChainTunnel chainTunnel : chainTunnels) {

                ForwardPort forwardPort = new ForwardPort();
                forwardPort.setForwardId(forward.getId());
                forwardPort.setNodeId(chainTunnel.getNodeId());
                forwardPort.setPort(chainTunnel.getPort());
                String portWriteError = saveForwardPort(forwardPort, chainTunnel.getNodeId());
                if (portWriteError != null) {
                    // 端口记录没落库就不能再下发：已下发成功的入口先撤回，清理结果如实拼进错误信息
                    String rollbackError = rollbackCreateForward(forward.getId(), success, savedPorts, null);
                    return R.err(rollbackError == null
                            ? portWriteError + "，已撤回本次已下发成功的节点服务"
                            : portWriteError + "；" + rollbackError);
                }
                savedPorts++;
                String serviceName = buildServiceName(forward.getId(), forward.getUserId(), permissionResult.getUserTunnel());
                Integer limiter = permissionResult.getLimiter();

                Node node = nodeService.getById(chainTunnel.getNodeId());
                if (node == null) {
                    String rollbackError = rollbackCreateForward(forward.getId(), success, savedPorts, null);
                    return R.err(rollbackError == null ? "部分节点不存在" : "部分节点不存在；" + rollbackError);
                }
                GostDto gostDto;
                try {
                    gostDto = createForwardServiceWithPortRetry(forwardPort, node, forward, tunnel, serviceName, limiter, autoPort);
                } catch (RuntimeException e) {
                    // 构建服务本身抛错（例如 tunnel.type 被 Fail-Closed 拦下）也属于下发失败。
                    // 不接住就会把已落库的 forward/forward_port 留在库里：面板显示一条正常转发，
                    // 节点上什么都没有，而且这条脏行会一直占着自动端口（round44 真机 type=9 实测）。
                    String rollbackError = rollbackCreateForward(forward.getId(), success, savedPorts, null);
                    String failure = "节点" + node.getId() + " 下发失败：" + e.getMessage();
                    return R.err(rollbackError == null ? failure : failure + "；" + rollbackError);
                }
                if (Objects.equals(gostDto.getMsg(), "OK")) {
                    JSONObject data = new JSONObject();
                    data.put("node_id", node.getId());
                    data.put("name", serviceName);
                    success.add(data);
                } else {
                    String failure = describeCreateServiceFailure(node, gostDto.getMsg());
                    String unconfirmedNote = isResultUnconfirmed(gostDto.getMsg())
                            ? "节点" + node.getId() + " 下发结果未确认（可能已生效），未作删除处理，需人工确认"
                            : null;
                    String rollbackError = rollbackCreateForward(forward.getId(), success, savedPorts, unconfirmedNote);
                    return R.err(rollbackError == null ? failure : failure + "；" + rollbackError);
                }

            }
            return R.ok();
        } finally {
            unlockNodePorts(portLocks);
        }
    }

    /**
     * 数据库写入结果必须显式判断：返回 false 或抛异常都不代表已落库，
     * 带着没落库的数据继续下发节点会留下"节点有服务、库里没记录"的孤儿。
     * 返回 null 表示写入成功，否则返回可直接拼进错误信息的描述。
     */
    /**
     * 同一用户在同一隧道下的转发名称必须唯一：重名会让面板出现两行一模一样的目标，
     * 诊断/日志只能靠 id 区分，运维按名称排查时容易看错对象。
     * <p>
     * 用 list 而不是 count：count 与配额校验共用同一条统计口径，混进来会让"配额查询是否排除自身"
     * 这类断言语义变得含混；这里只需要"有没有一行"。
     * <p>
     * 只做应用层校验（建表没有唯一索引），并发创建仍可能各插一条，属已知残留。
     */
    private String duplicateForwardName(Integer userId, Long tunnelId, String name, Long excludeForwardId) {
        String candidate = trimName(name);
        if (userId == null || tunnelId == null || candidate == null) {
            return null;
        }
        QueryWrapper<Forward> query = new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId)
                .eq("name", candidate);
        if (excludeForwardId != null) {
            query.ne("id", excludeForwardId);
        }
        List<Forward> sameName;
        try {
            sameName = this.list(query);
        } catch (RuntimeException e) {
            // 查询失败不能变成"名字随便建"，也不能平白拦住正常创建：记日志后放行，由写入侧兜底
            log.warn("同名转发校验查询异常，按无重名继续", e);
            return null;
        }
        return sameName == null || sameName.isEmpty()
                ? null
                : "同一隧道下已存在同名转发「" + candidate + "」，请换一个名称";
    }

    /** 同名锁的键：只约束"同一用户在同一隧道下"的名字，不同用户/不同隧道之间互不影响。 */
    private static String forwardNameKey(Integer userId, Long tunnelId, String name) {
        String candidate = trimName(name);
        return userId + "|" + tunnelId + "|" + (candidate == null ? "" : candidate);
    }

    private static String trimName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String dbWriteError(BooleanSupplier write, String what) {
        try {
            return write.getAsBoolean() ? null : what + "保存失败";
        } catch (RuntimeException e) {
            log.warn("{}写入异常，后续不做任何节点下发", what, e);
            return what + "保存异常：" + e.getMessage();
        }
    }

    /**
     * 按 nodeId 升序取一批入口节点的端口分配锁。升序是关键：两个创建请求的入口节点集合只要重叠，
     * 一个请求持有小号节点锁去等大号节点锁、另一个持有大号去等小号，就会互等死锁；
     * 全序获取让它们只能排队，不会成环。返回顺序即加锁顺序，交给 {@link #unlockNodePorts} 逆序释放。
     */
    private static List<ReentrantLock> lockNodePorts(List<Long> nodeIds) {
        List<Long> ordered = new ArrayList<>();
        for (Long nodeId : nodeIds) {
            if (nodeId != null && !ordered.contains(nodeId)) {
                ordered.add(nodeId);
            }
        }
        ordered.sort(Long::compareTo);
        List<ReentrantLock> locks = new ArrayList<>(ordered.size());
        for (Long nodeId : ordered) {
            ReentrantLock lock = NODE_PORT_LOCKS.computeIfAbsent(nodeId, k -> new ReentrantLock());
            lock.lock();
            locks.add(lock);
        }
        return locks;
    }

    private static void unlockNodePorts(List<ReentrantLock> locks) {
        for (int i = locks.size() - 1; i >= 0; i--) {
            locks.get(i).unlock();
        }
    }

    /**
     * 写入一条入口节点端口记录。返回 null 表示已落库，否则返回可直接拼进错误信息的描述。
     * <p>
     * 节点端口锁只挡得住本进程，多实例部署或锁没覆盖到的路径仍可能把同一个 (node_id, port) 写两次，
     * 那一层由数据库唯一索引 uk_forward_port_node_port 拦下：拦下来的冲突必须翻译成"重试"，
     * 而不是把 SQLite 的 constraint failed 原文丢给用户。
     */
    private String saveForwardPort(ForwardPort forwardPort, Long nodeId) {
        try {
            return forwardPortService.save(forwardPort) ? null : "节点" + nodeId + " 端口记录保存失败";
        } catch (RuntimeException e) {
            if (isUniqueConflict(e)) {
                log.warn("端口记录并发冲突：nodeId={}, port={}, forwardId={}", nodeId, forwardPort.getPort(),
                        forwardPort.getForwardId(), e);
                return "节点" + nodeId + " 端口" + forwardPort.getPort()
                        + "刚被其它转发占用（并发分配冲突），请重试";
            }
            log.warn("节点{}端口记录写入异常，后续不做任何节点下发", nodeId, e);
            return "节点" + nodeId + " 端口记录保存异常：" + e.getMessage();
        }
    }

    /**
     * 是否唯一键冲突。SQLite 的 UNIQUE 冲突在 Spring 侧不一定是 DuplicateKeyException
     * （驱动抛 SQLITE_CONSTRAINT_UNIQUE，会归到 UncategorizedSQLException），
     * 与 UserTunnelServiceImpl#isUniqueConflict 保持同一判定口径。
     */
    private static boolean isUniqueConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            // 只认 UNIQUE 类错误：NOTNULL / FOREIGNKEY 同属 SQLITE_CONSTRAINT_*，
            // 硬说成"端口刚被占用"会把真实故障藏起来。
            if (lower.contains("unique constraint failed")
                    || lower.contains("constraint_unique")
                    || lower.contains("duplicate entry")
                    || lower.contains("duplicate key")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建转发失败后的清理：删除已落库的转发及端口记录，并逐个尝试删除已下发成功的 gost 服务。
     * 单个入口清理失败不会中断后续清理，失败信息会汇总返回（null 表示全部清理成功）。
     * 只删除本次确认创建成功的服务；exists 冲突与结果未知（超时/发送异常）的目标一律不删。
     * 这是尽力补偿，不是分布式原子回滚，残留服务需人工确认。
     *
     * @param savedPorts 本次已确认落库的端口记录条数，用于区分"该删没删"和"本来就没有"
     */
    private String rollbackCreateForward(Long forwardId, List<JSONObject> createdServices, int savedPorts,
                                        String unconfirmedNote) {
        List<String> failures = new ArrayList<>();
        List<String> unconfirmed = new ArrayList<>();
        // 删除返回 false 说明"没删掉"这件事没有任何证据，不能当作已清理；但转发记录此前已确认写入，所以这里必须报
        try {
            if (!this.removeById(forwardId)) {
                unconfirmed.add("转发记录 id=" + forwardId + " 删除返回 false，未确认已清理");
            }
        } catch (RuntimeException e) {
            log.warn("创建转发失败后清理转发记录异常：forwardId={}", forwardId, e);
            failures.add("转发记录 id=" + forwardId + " 清理失败(" + e.getMessage() + ")");
        }
        try {
            if (!forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", forwardId))) {
                // 影响 0 行：写过端口记录说明该删的没删掉；一条都没写成说明端口表本来就干净，属预期
                if (savedPorts > 0) {
                    unconfirmed.add("转发端口记录删除返回 false，未确认已清理");
                } else {
                    log.info("转发端口记录删除影响 0 行，本次未确认写入过端口记录，属预期：forwardId={}", forwardId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("创建转发失败后清理端口记录异常：forwardId={}", forwardId, e);
            failures.add("转发端口记录清理失败(" + e.getMessage() + ")");
        }
        for (JSONObject createdService : createdServices) {
            String serviceName = createdService.getString("name");
            Long nodeId = createdService.getLong("node_id");
            JSONArray services = new JSONArray();
            services.add(serviceName + "_tcp");
            services.add(serviceName + "_udp");
            GostDto deleteDto = GostUtil.DeleteService(nodeId, services);
            if (!Objects.equals(deleteDto.getMsg(), "OK")) {
                log.warn("创建转发失败后清理服务失败，转发已删除，服务可能残留：forwardId={}, nodeId={}, name={}, msg={}",
                        forwardId, nodeId, serviceName, deleteDto.getMsg());
                failures.add(serviceName + "@node" + nodeId + "(" + deleteDto.getMsg() + ")");
            }
        }
        List<String> notes = new ArrayList<>();
        if (!failures.isEmpty()) {
            notes.add("以下清理失败，可能残留，需人工确认：" + String.join("; ", failures));
        }
        notes.addAll(unconfirmed);
        if (unconfirmedNote != null) {
            notes.add(unconfirmedNote);
        }
        return notes.isEmpty() ? null : String.join("；", notes);
    }

    /** exists 只说明同名服务早已存在（配置是否一致未知）：既不算本次创建成功，也不删该节点的既有资源。 */
    private static String describeCreateServiceFailure(Node node, String msg) {
        if (msg != null && msg.contains("exists")) {
            return "节点" + node.getId() + " 已存在同名服务，配置是否与本次一致未确认，未作删除处理：" + msg;
        }
        return msg;
    }

    /** 超时/发送异常说明节点可能已执行下发，结果未知：不能当作未创建，也不能据未知结果删除任何服务。 */
    private static boolean isResultUnconfirmed(String msg) {
        return msg != null && (msg.contains("等待响应超时") || msg.startsWith("发送消息失败"));
    }

    // ========== 自动端口换口重试（口径与 TunnelServiceImpl.publishChainService 一致） ==========

    /**
     * 只有"端口已被占用"才换口：节点把端口冲突报成 listen tcp ... bind: address already in use。
     * 不能把任意 "bind:" 都当端口冲突——"bind: cannot assign requested address"（监听地址在本机不存在）
     * 换个端口一样失败，真机 2026-11-08 实测会把一次失败放大成 3 个端口各建一次，掩盖真错因。
     * 判定口径与 TunnelServiceImpl.isBindFailure 保持一致。
     */
    private static boolean isBindFailure(String msg) {
        return msg != null && (msg.contains("address already in use") || msg.contains("EADDRINUSE"));
    }

    /** 连续端口占用失败计数：成功一次立即清零，非端口占用的失败不累加（别把别的错误算成风暴）。 */
    private static int countBindFailure(int streak, String msg) {
        return isBindFailure(msg) ? streak + 1 : 0;
    }

    /**
     * 连续「节点无响应」计数：gostDto 为 null、msg 为 null 或空白都算节点没回话
     * （GostUtil 在等待回执超时时正是拿 null 兜底）。有回话就清零，含节点报错——
     * 报错说明通道是通的，不该和卡死混为一谈。
     */
    private static int countTimeoutStreak(int streak, GostDto gostDto) {
        boolean unresponsive = gostDto == null || gostDto.getMsg() == null || gostDto.getMsg().isBlank();
        return unresponsive ? streak + 1 : 0;
    }

    /**
     * 成功判定不能写死 equals("OK")：换口成功时 msg 会带「；端口尝试：…」尾部，
     * 按字面比较会把已经起来的服务判成失败，接着回滚删掉——正是"换口成功反而建不出转发"。
     */
    private static boolean isOkMsg(String msg) {
        return msg != null && (msg.equals("OK") || msg.startsWith("OK；"));
    }

    /** 显式指定的端口不换口：端口被占是配置问题，换掉只会让面板记录的端口和节点实际监听悄悄不一致。 */
    private static GostDto explicitPortFailure(Integer port, GostDto gostDto) {
        GostDto failed = new GostDto();
        failed.setMsg("端口" + port + " 已被节点上其它进程占用（显式指定端口，不换口重试）：" + gostDto.getMsg());
        return failed;
    }

    /** 每一次尝试都要随失败信息回给前端：前端只展示 msg，换口过程必须可见。 */
    private static GostDto withPortAttempts(GostDto gostDto, List<String> portAttempts, Collection<Integer> tried) {
        if (portAttempts.isEmpty()) {
            return gostDto;
        }
        GostDto result = new GostDto();
        result.setMsg(gostDto.getMsg() + "；端口尝试：" + String.join("；", portAttempts)
                + "；已试端口：" + tried.stream().map(String::valueOf).collect(Collectors.joining(",")));
        return result;
    }

    /**
     * 换口候选：节点端口段内、库里没人用、本次又没试过的端口，小的优先。
     * 与 TunnelServiceImpl.candidatePorts 同口径——只按库判断不探测节点，节点上的僵尸监听正是要靠下发重试兜住的。
     */
    private List<Integer> nextPortCandidates(Long nodeId, Long forwardId, Set<Integer> unavailable) {
        List<Integer> candidates = new ArrayList<>();
        try {
            for (Integer port : getNodePort(nodeId, forwardId)) {
                if (!unavailable.contains(port)) {
                    candidates.add(port);
                }
            }
        } catch (RuntimeException e) {
            log.warn("自动端口换口候选查询失败：节点{} 转发{} {}", nodeId, forwardId, e.getMessage());
        }
        return candidates;
    }

    /** 删掉 name_tcp / name_udp 两份服务，原样返回节点响应（GostUtil.DeleteService 已把 not found 归一成 OK）。 */
    private static GostDto deleteForwardServices(Long nodeId, String serviceName) {
        JSONArray services = new JSONArray();
        services.add(serviceName + "_tcp");
        services.add(serviceName + "_udp");
        return GostUtil.DeleteService(nodeId, services);
    }

    /**
     * 下发一条入口节点的服务。自动分配的端口只保证"库里没人用"，节点上有没有僵尸监听只有下发时才知道：
     * 节点回 bind: address already in use 时换下一个候选端口重试（最多 AUTO_PORT_ATTEMPTS 个候选，含首次）。
     * 换口前先删掉同名服务残留并要求节点确认，删不掉就停止换口——同名资源状态未知时再下发一次的结果同样无法确认。
     *
     * @param autoPort 只有调用方没指定 inPort 的请求才允许换口
     * @return 最终下发结果；成功时 forwardPort 里是最终生效的端口
     */
    private GostDto createForwardServiceWithPortRetry(ForwardPort forwardPort, Node node, Forward forward, Tunnel tunnel,
                                                      String serviceName, Integer limiter, boolean autoPort) {
        GostDto gostDto = GostUtil.CreateForwardService(serviceName, limiter, node, forward, forwardPort, tunnel);
        if (!isBindFailure(gostDto.getMsg())) {
            return gostDto;
        }
        if (!autoPort) {
            return explicitPortFailure(forwardPort.getPort(), gostDto);
        }

        List<String> portAttempts = new ArrayList<>();
        Set<Integer> tried = new LinkedHashSet<>();
        tried.add(forwardPort.getPort());
        while (isBindFailure(gostDto.getMsg()) && tried.size() < AUTO_PORT_ATTEMPTS) {
            Integer failedPort = forwardPort.getPort();
            String reason = gostDto.getMsg();
            List<Integer> candidates = nextPortCandidates(node.getId(), forward.getId(), tried);
            if (candidates.isEmpty()) {
                portAttempts.add("端口" + failedPort + " 失败(" + reason + ")，节点已无其它可用端口");
                log.warn("自动端口重试中止：节点{} 已无其它候选端口，forwardId={}", node.getId(), forward.getId());
                break;
            }
            GostDto cleanup = deleteForwardServices(node.getId(), serviceName);
            if (!Objects.equals(cleanup.getMsg(), "OK")) {
                // 同名 service 状态未知，再下发一次无法判断成败
                portAttempts.add("端口" + failedPort + " 失败(" + reason + ")，换口前清理同名 service 未确认(" + cleanup.getMsg() + ")");
                log.warn("自动端口重试中止：节点{} service {} 清理未确认({})，forwardId={}",
                        node.getId(), serviceName, cleanup.getMsg(), forward.getId());
                return withPortAttempts(gostDto, portAttempts, tried);
            }
            Integer candidate = candidates.get(0);
            log.warn("自动端口被节点占用，换口重试：节点{} forwardId={} 端口{} 失败原因={} 改用端口{}",
                    node.getId(), forward.getId(), failedPort, reason, candidate);
            portAttempts.add("端口" + failedPort + " 失败(" + reason + ")，改用端口" + candidate);
            forwardPort.setPort(candidate);
            tried.add(candidate);
            gostDto = GostUtil.CreateForwardService(serviceName, limiter, node, forward, forwardPort, tunnel);
        }

        if (!Objects.equals(gostDto.getMsg(), "OK")) {
            // 候选耗尽：最后一次尝试可能在节点上留了同名 service，清掉再返回失败
            GostDto cleanup = deleteForwardServices(node.getId(), serviceName);
            portAttempts.add(Objects.equals(cleanup.getMsg(), "OK")
                    ? "已试 " + tried.size() + " 个候选端口，最后一次残留 service 已清理"
                    : "已试 " + tried.size() + " 个候选端口，最后一次残留 service 清理未确认(" + cleanup.getMsg() + ")");
            return withPortAttempts(gostDto, portAttempts, tried);
        }

        if (tried.size() == 1) {
            return gostDto;
        }

        // 节点已按新端口起来、库里还是旧端口：先回写；回写不成就删掉刚下发的服务。
        // 留着它比删掉更糟——节点在监听一个库里没有的端口，正是这次要修的僵尸态。
        String writeBackError = dbWriteError(() -> forwardPortService.updateById(forwardPort),
                "节点" + node.getId() + " 端口记录回写");
        if (writeBackError == null) {
            return gostDto;
        }
        GostDto cleanup = deleteForwardServices(node.getId(), serviceName);
        portAttempts.add("端口" + forwardPort.getPort() + " 换口成功但端口记录回写失败：" + writeBackError);
        log.warn("换口后回写端口记录失败：节点{} forwardId={} {}", node.getId(), forward.getId(), writeBackError);
        GostDto failed = new GostDto();
        failed.setMsg(writeBackError + (Objects.equals(cleanup.getMsg(), "OK")
                ? "，已回滚删除刚下发的 service " + serviceName
                : "，回滚删除 service " + serviceName + " 未确认(" + cleanup.getMsg() + ")"));
        return withPortAttempts(failed, portAttempts, tried);
    }

    /**
     * 编辑转发时下发单个入口节点。自动分配到的端口撞上节点上的僵尸监听时换口重试：
     * 每次换口前先删掉同名服务并要求确认，删过之后节点上已经没有这条服务，
     * 所以重试只能按 AddService 重建（UpdateService 会直接回 service not found）。
     * 最终端口只写进 update.targetPort，落库仍由 updateForward 统一收尾，避免两处写库各写一半。
     */
    private GostDto updateForwardServiceWithPortRetry(NodeServiceUpdate update, Forward updatedForward,
                                                      Forward originalForward, Tunnel tunnel, String serviceName,
                                                      Integer limiter, boolean autoPort, List<String> notes) {
        GostDto gostDto = GostUtil.AddAndUpdateService(serviceName, limiter, update.node, updatedForward,
                update.copyWithTargetPort(), tunnel, "UpdateService");
        if (!isBindFailure(gostDto.getMsg())) {
            return gostDto;
        }
        if (!autoPort) {
            return explicitPortFailure(update.targetPort, gostDto);
        }

        List<String> portAttempts = new ArrayList<>();
        Set<Integer> tried = new LinkedHashSet<>();
        tried.add(update.targetPort);
        // 自己的原端口也不能当候选：换口就是为了离开这个冲突端口
        Set<Integer> unavailable = new LinkedHashSet<>(tried);
        unavailable.add(update.storedPort.getPort());
        boolean released = false;
        while (isBindFailure(gostDto.getMsg()) && tried.size() < AUTO_PORT_ATTEMPTS) {
            Integer failedPort = update.targetPort;
            String reason = gostDto.getMsg();
            List<Integer> candidates = nextPortCandidates(update.node.getId(), originalForward.getId(), unavailable);
            if (candidates.isEmpty()) {
                portAttempts.add("端口" + failedPort + " 失败(" + reason + ")，节点已无其它可用端口");
                break;
            }
            GostDto cleanup = deleteForwardServices(update.node.getId(), serviceName);
            if (!Objects.equals(cleanup.getMsg(), "OK")) {
                portAttempts.add("端口" + failedPort + " 失败(" + reason + ")，换口前清理同名 service 未确认(" + cleanup.getMsg() + ")");
                log.warn("自动端口重试中止：节点{} service {} 清理未确认({})，forwardId={}",
                        update.node.getId(), serviceName, cleanup.getMsg(), originalForward.getId());
                break;
            }
            released = true;
            Integer candidate = candidates.get(0);
            portAttempts.add("端口" + failedPort + " 失败(" + reason + ")，改用端口" + candidate);
            log.warn("自动端口被节点占用，换口重试：节点{} forwardId={} 端口{} 失败原因={} 改用端口{}",
                    update.node.getId(), originalForward.getId(), failedPort, reason, candidate);
            update.usePort(candidate);
            tried.add(candidate);
            unavailable.add(candidate);
            gostDto = GostUtil.CreateForwardService(serviceName, limiter, update.node, updatedForward,
                    update.copyWithTargetPort(), tunnel);
        }

        if (Objects.equals(gostDto.getMsg(), "OK")) {
            return gostDto;
        }
        if (released) {
            // 旧服务是自己删掉的：失败路径上的 UpdateService 补偿会报 not found，
            // 这里先按库里的原端口把原配置建回来，节点才不会因为一次失败的换口彻底丢掉这条转发
            GostDto restore = GostUtil.CreateForwardService(serviceName, limiter, update.node, originalForward,
                    update.storedPort, tunnel);
            notes.add(Objects.equals(restore.getMsg(), "OK")
                    ? "节点" + update.node.getId() + " 已按库内原端口" + update.storedPort.getPort() + " 重建原服务"
                    : "节点" + update.node.getId() + " 按原端口" + update.storedPort.getPort()
                    + " 重建原服务失败(" + restore.getMsg() + ")，需节点上报后按库补齐");
            if (Objects.equals(restore.getMsg(), "OK")) {
                // 已回到库里的原状态，后续补偿不该再对这台节点重发 UpdateService
                update.usePort(update.storedPort.getPort());
            }
        }
        return withPortAttempts(gostDto, portAttempts, tried);
    }

    /**
     * 把已经按新配置下发过的节点改回库里的原配置：只重发 UpdateService，不做删除重建。
     * 返回 null 表示全部改回成功，否则返回可直接拼进错误信息的描述。
     */
    private String restoreNodeServices(List<NodeServiceUpdate> applied, Forward originalForward,
                                       String serviceName, Integer limiter, Tunnel tunnel) {
        List<String> failures = new ArrayList<>();
        for (NodeServiceUpdate update : applied) {
            GostDto gostDto = GostUtil.AddAndUpdateService(serviceName, limiter, update.node, originalForward,
                    update.storedPort, tunnel, "UpdateService");
            if (!Objects.equals(gostDto.getMsg(), "OK")) {
                failures.add("node" + update.node.getId() + "(" + gostDto.getMsg() + ")");
            }
        }
        return failures.isEmpty() ? null
                : "已下发节点改回原配置失败，可能残留新配置，需人工确认：" + String.join("; ", failures);
    }

    /**
     * 隧道入口热迁移：隧道改了入口节点但下面还有转发时，按入口顺序把每条转发的入口服务与
     * forward_port 行搬到新节点。
     * <p>
     * 顺序有意固定为「新入口先建 → 旧入口再删 → 最后写库」：任何一步失败都还能回到原状。
     * 先删旧再建新会在新节点下发失败时把业务整条打断；先写库再下发改的是库里指向、节点还在旧节点监听，
     * 正是 updateForward 之前的静默漂移缺陷。多入口隧道按 index 一一对应，数量不同直接拒绝。
     *
     * @return null 表示全部迁移成功；否则返回失败原因，并已尝试回滚本次已迁移的转发
     */
    @Override
    public String migrateEntryNodes(Long tunnelId, List<ChainTunnel> oldEntries, List<ChainTunnel> newEntries,
                                    Map<Long, Node> nodes) {
        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel == null) {
            return "隧道" + tunnelId + "不存在，无法迁移入口服务";
        }
        return migrateEntryNodes(tunnel, oldEntries, newEntries, nodes);
    }

    /**
     * 入口迁移主流程。{@code dispatchTunnel} 只用于「按哪种类型下发」；回滚一律用库里持久化的旧类型，
     * 否则类型 2→1 与入口迁移同一请求里发生时，回滚会把旧入口服务建成新类型的形态。
     */
    @Override
    public String migrateEntryNodes(Tunnel dispatchTunnel, List<ChainTunnel> oldEntries, List<ChainTunnel> newEntries,
                                    Map<Long, Node> nodes) {
        if (oldEntries == null || newEntries == null || oldEntries.isEmpty() || newEntries.isEmpty()) {
            return null;
        }
        if (oldEntries.size() != newEntries.size()) {
            return "入口节点数量不一致（旧 " + oldEntries.size() + "，新 " + newEntries.size() + "），无法按序迁移转发";
        }
        if (dispatchTunnel == null || dispatchTunnel.getId() == null) {
            return "隧道不存在，无法迁移入口服务";
        }
        List<Forward> forwards = this.list(new QueryWrapper<Forward>().eq("tunnel_id", dispatchTunnel.getId()));
        if (forwards == null || forwards.isEmpty()) {
            return null;
        }
        Tunnel rollbackTunnel = tunnelService.getById(dispatchTunnel.getId());
        if (rollbackTunnel == null) {
            rollbackTunnel = dispatchTunnel;
        }

        List<EntryMigration> migrated = new ArrayList<>();
        for (Forward forward : forwards) {
            String error = migrateForwardEntries(dispatchTunnel, rollbackTunnel, forward, oldEntries, newEntries,
                    nodes, migrated);
            if (error != null) {
                String rollback = rollbackEntryMigrations(migrated);
                return rollback == null ? error : error + "；已迁移转发回滚未完成：" + rollback;
            }
        }
        return null;
    }

    private String migrateForwardEntries(Tunnel tunnel, Tunnel rollbackTunnel, Forward forward, List<ChainTunnel> oldEntries,
                                         List<ChainTunnel> newEntries, Map<Long, Node> nodes,
                                         List<EntryMigration> migrated) {
        List<ForwardPort> rows;
        try {
            rows = forwardPortService.list(new QueryWrapper<ForwardPort>().eq("forward_id", forward.getId()));
        } catch (RuntimeException e) {
            return "转发" + forward.getId() + "端口记录查询失败：" + e.getMessage();
        }
        Map<Long, ForwardPort> byNode = new HashMap<>();
        if (rows != null) {
            for (ForwardPort row : rows) {
                byNode.put(row.getNodeId(), row);
            }
        }

        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();
        boolean active = forward.getStatus() != null && forward.getStatus() == 1;

        for (int i = 0; i < oldEntries.size(); i++) {
            Long oldNodeId = oldEntries.get(i).getNodeId();
            Long newNodeId = newEntries.get(i).getNodeId();
            if (Objects.equals(oldNodeId, newNodeId)) {
                continue;
            }
            Node newNode = nodes.get(newNodeId);
            if (newNode == null) {
                return "新入口节点" + newNodeId + "不存在，转发" + forward.getId() + "未迁移";
            }
            ForwardPort row = byNode.get(oldNodeId);
            if (row == null) {
                return "转发" + forward.getId() + "在入口节点" + oldNodeId + "缺少端口记录，未迁移";
            }
            Integer oldPort = row.getPort();
            Integer targetPort = pickEntryPort(newNode, oldPort, forward.getId());
            if (targetPort == null) {
                return "新入口节点" + newNodeId + "端口段已无可用端口，转发" + forward.getId() + "未迁移";
            }

            ForwardPort target = new ForwardPort();
            target.setId(row.getId());
            target.setForwardId(row.getForwardId());
            target.setNodeId(newNodeId);
            target.setPort(targetPort);

            if (active) {
                GostDto created = createEntryServiceWithPortRetry(newNode, forward, tunnel, serviceName, limiter, target);
                if (!isOkMsg(created.getMsg())) {
                    return "转发" + forward.getId() + "在新入口节点" + newNodeId + "下发失败：" + created.getMsg();
                }
                if (!Objects.equals(created.getMsg(), "OK")) {
                    // 换过口时 msg 是「OK；端口尝试：…」：成功仍成立，但过程要留痕，否则事后查不到端口为什么变了
                    log.info("入口迁移换口成功：转发{} 新入口节点{} 最终端口{} 明细：{}",
                            forward.getId(), newNodeId, target.getPort(), created.getMsg());
                }
                GostDto oldCleanup = deleteForwardServices(oldNodeId, serviceName);
                if (!Objects.equals(oldCleanup.getMsg(), "OK")) {
                    GostDto rollbackNew = deleteForwardServices(newNodeId, serviceName);
                    return "转发" + forward.getId() + "新入口已下发，但旧入口节点" + oldNodeId + "服务清理未确认("
                            + oldCleanup.getMsg() + ")，已撤回新入口" + (Objects.equals(rollbackNew.getMsg(), "OK")
                            ? "" : "（撤回未确认：" + rollbackNew.getMsg() + "）");
                }
                String writeError = dbWriteError(() -> forwardPortService.updateById(target),
                        "转发" + forward.getId() + "入口端口记录迁移");
                if (writeError != null) {
                    deleteForwardServices(newNodeId, serviceName);
                    Node oldNode = nodeService.getById(oldNodeId);
                    GostDto restored = oldNode == null ? null
                            : GostUtil.CreateForwardService(serviceName, limiter, oldNode, forward, row, rollbackTunnel);
                    return writeError + (restored != null && Objects.equals(restored.getMsg(), "OK")
                            ? "，已在新入口撤销并按原端口" + oldPort + "恢复旧入口服务"
                            : "，新入口已撤销，旧入口重建失败(" + (restored == null ? "旧节点不存在" : restored.getMsg())
                            + ")，需节点上报后按库补齐");
                }
                migrated.add(new EntryMigration(row, oldNodeId, newNodeId, serviceName, limiter, rollbackTunnel, forward, true));
            } else {
                // 暂停的转发本来就不在节点上监听；旧节点只可能残留暂停前的配置条目，尽力清掉即可
                GostDto oldCleanup = deleteForwardServices(oldNodeId, serviceName);
                String writeError = dbWriteError(() -> forwardPortService.updateById(target),
                        "转发" + forward.getId() + "入口端口记录迁移");
                if (writeError != null) {
                    return writeError + (Objects.equals(oldCleanup.getMsg(), "OK")
                            ? "，暂停转发在旧入口的服务已清理，库记录未变"
                            : "，库记录未变，旧入口暂停态服务清理未确认(" + oldCleanup.getMsg() + ")");
                }
                migrated.add(new EntryMigration(row, oldNodeId, newNodeId, serviceName, limiter, rollbackTunnel, forward, false));
                if (!Objects.equals(oldCleanup.getMsg(), "OK")) {
                    log.warn("暂停转发{} 旧入口节点{} 服务清理未确认：{}", forward.getId(), oldNodeId, oldCleanup.getMsg());
                }
            }
            byNode.remove(oldNodeId);
            byNode.put(newNodeId, target);
        }
        return null;
    }

    @Override
    public String rebuildTunnelForwardServices(Tunnel tunnel, List<Long> entryNodeIds) {
        if (tunnel == null || tunnel.getId() == null) {
            return "隧道不存在，无法重建转发入口服务";
        }
        List<Long> targets = new ArrayList<>();
        if (entryNodeIds != null) {
            for (Long nodeId : entryNodeIds) {
                if (nodeId != null && !targets.contains(nodeId)) {
                    targets.add(nodeId);
                }
            }
        }
        if (targets.isEmpty()) {
            return "入口节点清单为空，未重建转发入口服务";
        }
        List<Forward> forwards;
        try {
            forwards = this.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnel.getId()));
        } catch (RuntimeException e) {
            return "转发清单查询失败：" + e.getMessage();
        }
        if (forwards == null || forwards.isEmpty()) {
            return null;
        }
        List<String> failures = new ArrayList<>();
        int rebuilt = 0;
        int paused = 0;
        for (Forward forward : forwards) {
            if (forward.getStatus() == null || forward.getStatus() != 1) {
                // 暂停中的转发本就不该在节点上监听：类型切换不能把它复活，也不能因为它让整次变更失败
                paused++;
                continue;
            }
            UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
            String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
            Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();
            List<ForwardPort> rows;
            try {
                rows = forwardPortService.list(new QueryWrapper<ForwardPort>().eq("forward_id", forward.getId()));
            } catch (RuntimeException e) {
                failures.add("转发" + forward.getId() + "端口记录查询失败：" + e.getMessage());
                continue;
            }
            Map<Long, ForwardPort> byNode = new HashMap<>();
            if (rows != null) {
                for (ForwardPort row : rows) {
                    byNode.put(row.getNodeId(), row);
                }
            }
            for (Long nodeId : targets) {
                ForwardPort stored = byNode.get(nodeId);
                if (stored == null || stored.getPort() == null) {
                    failures.add("转发" + forward.getId() + "在入口节点" + nodeId + "缺少端口记录");
                    continue;
                }
                Node node = nodeService.getById(nodeId);
                if (node == null) {
                    failures.add("入口节点" + nodeId + "不存在");
                    continue;
                }
                String error = rebuildEntryService(tunnel, node, forward, stored, serviceName, limiter);
                if (error != null) {
                    failures.add(error);
                } else {
                    rebuilt++;
                }
            }
        }
        if (!failures.isEmpty()) {
            return String.join("；", failures) + "（本次已重建 " + rebuilt + " 个入口服务，跳过暂停转发 " + paused + " 条）";
        }
        log.info("隧道{} 类型切换后重建转发入口服务：重建={} 跳过暂停转发={}", tunnel.getId(), rebuilt, paused);
        return null;
    }

    /**
     * 单个入口服务的类型切换重建：先删同名 tcp/udp（旧类型形态），再按 {@code tunnel.type} 重发。
     * 端口沿用库里的 forward_port；节点报 bind 冲突时走既有换口重试，并把最终端口回写，避免库与节点再次漂移。
     */
    protected String rebuildEntryService(Tunnel tunnel, Node node, Forward forward, ForwardPort stored,
                                       String serviceName, Integer limiter) {
        GostDto cleanup = deleteForwardServices(node.getId(), serviceName);
        if (!Objects.equals(cleanup.getMsg(), "OK")) {
            return "转发" + forward.getId() + "节点" + node.getId() + "清理旧服务未确认(" + cleanup.getMsg() + ")";
        }
        ForwardPort target = new ForwardPort();
        target.setId(stored.getId());
        target.setForwardId(stored.getForwardId());
        target.setNodeId(stored.getNodeId());
        target.setPort(stored.getPort());
        GostDto created = createEntryServiceWithPortRetry(node, forward, tunnel, serviceName, limiter, target);
        if (!isOkMsg(created.getMsg())) {
            return "转发" + forward.getId() + "节点" + node.getId() + "按新类型下发失败：" + created.getMsg();
        }
        if (!Objects.equals(target.getPort(), stored.getPort())) {
            String writeError = dbWriteError(() -> forwardPortService.updateById(target),
                    "转发" + forward.getId() + "入口端口记录更新");
            if (writeError != null) {
                deleteForwardServices(node.getId(), serviceName);
                return writeError;
            }
            log.warn("类型切换重建换口：隧道{} 转发{} 节点{} 端口{} → {}", tunnel.getId(), forward.getId(),
                    node.getId(), stored.getPort(), target.getPort());
        }
        return null;
    }

    /**
     * 入口迁移的端口选择：优先沿用原端口（新节点端口段内、且库里没有别的转发/隧道链占用），
     * 否则取新节点端口段里第一个空闲口。只按库判断，节点上的僵尸监听由下发重试兜住。
     */
    private Integer pickEntryPort(Node node, Integer preferred, Long forwardId) {
        List<Integer> available = getNodePort(node.getId(), forwardId);
        if (preferred != null && available.contains(preferred)) {
            return preferred;
        }
        return available.isEmpty() ? null : available.get(0);
    }

    /** 迁移时在新入口下发服务；沿用 create 路径的换口重试口径，但端口只写进临时 target，不在这里落库。 */
    private GostDto createEntryServiceWithPortRetry(Node node, Forward forward, Tunnel tunnel, String serviceName,
                                                    Integer limiter, ForwardPort target) {
        GostDto gostDto = GostUtil.CreateForwardService(serviceName, limiter, node, forward, target, tunnel);
        List<String> attempts = new ArrayList<>();
        Set<Integer> tried = new LinkedHashSet<>();
        tried.add(target.getPort());
        while (isBindFailure(gostDto.getMsg()) && tried.size() < AUTO_PORT_ATTEMPTS) {
            Integer failedPort = target.getPort();
            String reason = gostDto.getMsg();
            List<Integer> candidates = nextPortCandidates(node.getId(), forward.getId(), tried);
            if (candidates.isEmpty()) {
                attempts.add("端口" + failedPort + " 失败(" + reason + ")，节点已无其它可用端口");
                break;
            }
            GostDto cleanup = deleteForwardServices(node.getId(), serviceName);
            if (!Objects.equals(cleanup.getMsg(), "OK")) {
                attempts.add("端口" + failedPort + " 失败(" + reason + ")，换口前清理同名 service 未确认(" + cleanup.getMsg() + ")");
                return withPortAttempts(gostDto, attempts, tried);
            }
            Integer candidate = candidates.get(0);
            log.warn("入口迁移端口被节点占用，换口重试：节点{} forwardId={} 端口{} 失败原因={} 改用端口{}",
                    node.getId(), forward.getId(), failedPort, reason, candidate);
            attempts.add("端口" + failedPort + " 失败(" + reason + ")，改用端口" + candidate);
            target.setPort(candidate);
            tried.add(candidate);
            gostDto = GostUtil.CreateForwardService(serviceName, limiter, node, forward, target, tunnel);
        }
        if (!Objects.equals(gostDto.getMsg(), "OK") && tried.size() > 1) {
            GostDto cleanup = deleteForwardServices(node.getId(), serviceName);
            attempts.add(Objects.equals(cleanup.getMsg(), "OK")
                    ? "已试 " + tried.size() + " 个候选端口，最后一次残留 service 已清理"
                    : "已试 " + tried.size() + " 个候选端口，最后一次残留 service 清理未确认(" + cleanup.getMsg() + ")");
        }
        return attempts.isEmpty() ? gostDto : withPortAttempts(gostDto, attempts, tried);
    }

    /** 逆序回滚本次已完成的入口迁移：先撤新入口服务，再按旧端口重建旧入口，最后把端口行改回旧节点。 */
    private String rollbackEntryMigrations(List<EntryMigration> migrated) {
        List<String> problems = new ArrayList<>();
        for (int i = migrated.size() - 1; i >= 0; i--) {
            EntryMigration migration = migrated.get(i);
            if (migration.active) {
                GostDto removed = deleteForwardServices(migration.newNodeId, migration.serviceName);
                if (!Objects.equals(removed.getMsg(), "OK")) {
                    problems.add("新入口节点" + migration.newNodeId + "撤销服务失败(" + removed.getMsg() + ")");
                }
                Node oldNode = nodeService.getById(migration.oldNodeId);
                if (oldNode == null) {
                    problems.add("旧入口节点" + migration.oldNodeId + "不存在，无法恢复服务");
                } else {
                    GostDto restored = GostUtil.CreateForwardService(migration.serviceName, migration.limiter,
                            oldNode, migration.forward, migration.row, migration.tunnel);
                    if (!Objects.equals(restored.getMsg(), "OK")) {
                        problems.add("旧入口节点" + migration.oldNodeId + "恢复服务失败(" + restored.getMsg() + ")");
                    }
                }
            }
            String writeError = dbWriteError(() -> forwardPortService.updateById(migration.row),
                    "转发" + migration.forward.getId() + "入口端口记录回滚");
            if (writeError != null) {
                problems.add(writeError);
            }
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    /** 一次入口迁移的现场，回滚要用：row 是迁移前那一行（node/port 仍是旧值）。 */
    private static final class EntryMigration {
        private final ForwardPort row;
        private final Long oldNodeId;
        private final Long newNodeId;
        private final String serviceName;
        private final Integer limiter;
        private final Tunnel tunnel;
        private final Forward forward;
        private final boolean active;

        private EntryMigration(ForwardPort row, Long oldNodeId, Long newNodeId, String serviceName,
                               Integer limiter, Tunnel tunnel, Forward forward, boolean active) {
            this.row = row;
            this.oldNodeId = oldNodeId;
            this.newNodeId = newNodeId;
            this.serviceName = serviceName;
            this.limiter = limiter;
            this.tunnel = tunnel;
            this.forward = forward;
            this.active = active;
        }
    }

    @Override
    public R updateForward(ForwardUpdateDto forwardUpdateDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();


        // 2. 检查转发是否存在
        Forward existForward = validateForwardExists(forwardUpdateDto.getId(), currentUser);
        if (existForward == null) {
            return R.err("转发不存在");
        }


        Tunnel tunnel = validateTunnel(existForward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 配额判断必须排除当前转发自身：num 上限限制的是"能存多少条"，不是"能不能改这一条"
        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, existForward.getId());
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }

        String duplicateName = duplicateForwardName(existForward.getUserId(), tunnel.getId(),
                forwardUpdateDto.getName(), existForward.getId());
        if (duplicateName != null) {
            return R.err(duplicateName);
        }

        UserTunnel userTunnel;
        if (currentUser.getRoleId() != 0) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员用户也需要获取UserTunnel（如果存在的话），用于构建正确的服务名称
            // 通过forward记录获取原始的用户ID
            userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
        }

        // 新配置先放在独立对象里：节点下发失败时 existForward 必须还是库里的原值（错误信息和后续补偿都要用原值）
        Forward updatedForward = new Forward();
        BeanUtils.copyProperties(existForward, updatedForward);
        updatedForward.setRemoteAddr(forwardUpdateDto.getRemoteAddr());
        updatedForward.setName(trimName(forwardUpdateDto.getName()));
        updatedForward.setStrategy(forwardUpdateDto.getStrategy());
        // 编辑内容不改变运行态：暂停中的转发改完目标地址仍是暂停（节点侧没被恢复，改回 1 会变成"面板说运行、实际不转发"）
        updatedForward.setStatus(existForward.getStatus());

        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));
        if (chainTunnels == null || chainTunnels.isEmpty()) {
            return R.err("隧道没有入口节点，无法更新转发");
        }

        // 自己占用的应该不算
        chainTunnels = get_port(chainTunnels, forwardUpdateDto.getInPort(), existForward.getId());

        String serviceName = buildServiceName(existForward.getId(), existForward.getUserId(), userTunnel);
        Integer limiter = permissionResult.getLimiter();

        // 先把每个节点的目标状态算齐、该报的错全报完，再开始下发：中途失败不会留下改了一半的库
        List<NodeServiceUpdate> updates = new ArrayList<>();
        for (ChainTunnel chainTunnel : chainTunnels) {
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) {
                return R.err("部分节点不存在");
            }
            ForwardPort forwardPort = forwardPortService.getOne(new QueryWrapper<ForwardPort>().eq("forward_id", existForward.getId()).eq("node_id", node.getId()));
            if (forwardPort == null) {
                return R.err("部分节点不存在1");
            }
            updates.add(new NodeServiceUpdate(node, forwardPort, chainTunnel.getPort()));
        }

        // 先下发后落库：节点没按新配置生效就写库，会留下"库里新配置、节点旧配置"的永久静默漂移
        List<NodeServiceUpdate> applied = new ArrayList<>();
        // 只有没指定 inPort 的请求才是自动分配端口，才允许换口重试
        boolean autoPort = forwardUpdateDto.getInPort() == null;
        for (NodeServiceUpdate update : updates) {
            List<String> notes = new ArrayList<>();
            GostDto gostDto;
            try {
                gostDto = updateForwardServiceWithPortRetry(update, updatedForward, existForward, tunnel,
                        serviceName, limiter, autoPort, notes);
            } catch (RuntimeException e) {
                // 构建服务抛错（tunnel.type Fail-Closed 等）同样要按"下发失败"处理：
                // 已应用的节点恢复原配置，库保持原值，不能让异常直接冒到全局处理器。
                String compensation = restoreNodeServices(applied, existForward, serviceName, limiter, tunnel);
                notes.add("节点" + update.node.getId() + " 下发失败：" + e.getMessage());
                if (compensation != null) {
                    notes.add(compensation);
                }
                return R.err(String.join("；", notes));
            }
            if (!Objects.equals(gostDto.getMsg(), "OK")) {
                if (isResultUnconfirmed(gostDto.getMsg())) {
                    notes.add("节点" + update.node.getId() + " 下发结果未确认（可能已生效），数据库保持原值，需人工确认");
                }
                String compensation = restoreNodeServices(applied, existForward, serviceName, limiter, tunnel);
                if (compensation != null) {
                    notes.add(compensation);
                }
                return R.err(notes.isEmpty() ? gostDto.getMsg() : gostDto.getMsg() + "；" + String.join("；", notes));
            }
            applied.add(update);
        }

        // 节点全部按新配置生效，现在才允许改库；写库失败要把已下发的节点改回原配置再说。
        // 改名同样要串行化：两个转发同时改成同一个名字时，各自的快速查重都会放行，必须在锁内再查一次。
        String nameKey = forwardNameKey(existForward.getUserId(), tunnel.getId(), updatedForward.getName());
        ReentrantLock nameLock = FORWARD_NAME_LOCKS.computeIfAbsent(nameKey, k -> new ReentrantLock());
        String forwardWriteError;
        nameLock.lock();
        try {
            String racedDuplicate = duplicateForwardName(existForward.getUserId(), tunnel.getId(),
                    updatedForward.getName(), existForward.getId());
            if (racedDuplicate != null) {
                // 节点已经按新配置生效，但名字被并发请求抢先占用：节点退回原配置，库保持原值
                String compensation = restoreNodeServices(applied, existForward, serviceName, limiter, tunnel);
                return R.err(compensation == null ? racedDuplicate : racedDuplicate + "；" + compensation);
            }
            forwardWriteError = dbWriteError(() -> this.updateById(updatedForward), "转发记录");
        } finally {
            nameLock.unlock();
            if (!nameLock.hasQueuedThreads()) {
                FORWARD_NAME_LOCKS.remove(nameKey, nameLock);
            }
        }
        if (forwardWriteError != null) {
            String compensation = restoreNodeServices(applied, existForward, serviceName, limiter, tunnel);
            return R.err(compensation == null
                    ? forwardWriteError + "，已把节点改回原配置"
                    : forwardWriteError + "；" + compensation);
        }

        for (NodeServiceUpdate update : updates) {
            String portWriteError = dbWriteError(() -> forwardPortService.updateById(update.copyWithTargetPort()),
                    "节点" + update.node.getId() + " 端口记录");
            if (portWriteError != null) {
                // 转发记录已按新配置写过、端口没写成，同样是漂移：库和节点一起退回原配置
                String revertError = dbWriteError(() -> this.updateById(existForward), "转发记录回滚");
                String compensation = restoreNodeServices(updates, existForward, serviceName, limiter, tunnel);
                List<String> notes = new ArrayList<>();
                if (revertError != null) {
                    notes.add(revertError + "，数据库可能停留在新配置");
                }
                if (compensation != null) {
                    notes.add(compensation);
                }
                return R.err(notes.isEmpty()
                        ? portWriteError + "，转发记录和节点已退回原配置"
                        : portWriteError + "；" + String.join("；", notes));
            }
        }

        return R.ok();
    }

    @Override
    public R deleteForward(Long id) {
        return deleteForward(id, false);
    }

    @Override
    public R deleteForward(Long id, boolean strict) {

        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();


        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }


        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 删除自己的转发不该被 num 配额拦住：配额管的是"能存多少条"，删一条是在往下减
        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null, true);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != 0) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员用户也需要获取UserTunnel（如果存在的话），用于构建正确的服务名称
            // 通过forward记录获取原始的用户ID
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));
        // 旧实现把 DeleteService 的返回值直接丢掉：节点上没删掉也照样回「操作成功」，
        // 库里记录没了、节点还在监听，端口被永久占住（只能等节点重连才可能被孤儿清扫发现）。
        // 现在把每个入口节点的真实结果收起来：库照删（避免记录卡死无法重试），
        // 失败的节点立刻要一份资源清单，让 NodeOrphanSweeper 把残留服务回收掉，
        // 并把明细回给前端提示用户。
        List<String> unfinished = new ArrayList<>();
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        for (ChainTunnel chainTunnel : chainTunnels) {
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) {
                unfinished.add("节点" + chainTunnel.getNodeId() + " 不存在，无法下发清理");
                continue;
            }

            JSONArray services = new JSONArray();
            services.add(serviceName + "_tcp");
            services.add(serviceName + "_udp");
            GostDto gostDto = GostUtil.DeleteService(node.getId(), services);
            if (!Objects.equals(gostDto.getMsg(), "OK")) {
                unfinished.add("节点" + node.getId() + "：" + gostDto.getMsg());
                WebSocketServer.requestServiceList(node.getId());
            }
        }
        if (strict && !unfinished.isEmpty()) {
            // 严格模式：节点侧没删干净就不动库，让调用方（撤销权限）能安全中止并重试。
            return R.err("节点侧服务未删除：" + String.join("；", unfinished) + "，库里记录已保留");
        }
        forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", id));
        this.removeById(id);
        if (!unfinished.isEmpty()) {
            return R.ok(unfinished);
        }
        return R.ok();
    }

    @Override
    public R pauseForward(Long id) {
        return changeForwardStatus(id, 0, "PauseService");
    }

    @Override
    public R resumeForward(Long id) {
        return changeForwardStatus(id, 1, "ResumeService");
    }

    @Override
    public R forceDeleteForward(Long id) {
        UserInfo currentUser = getCurrentUserInfo();
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }
        forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", id));
        this.removeById(id);
        // 强制删除只保证库里记录消失，节点上的服务可能还在跑：立刻索取一次资源清单，
        // 由 NodeOrphanSweeper 按白名单回收，否则强制删完端口仍被占着、面板反复 bind 失败。
        for (ChainTunnel chainTunnel : chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().eq("tunnel_id", forward.getTunnelId()).eq("chain_type", 1))) {
            if (nodeService.getById(chainTunnel.getNodeId()) != null) {
                WebSocketServer.requestServiceList(chainTunnel.getNodeId());
            }
        }
        return R.ok();
    }

    @Override
    public R diagnoseForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限访问
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 获取隧道的ChainTunnel信息
        List<ChainTunnel> chainTunnels = chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId())
        );

        if (chainTunnels.isEmpty()) {
            return R.err("隧道配置不完整");
        }

        // 分类节点
        List<ChainTunnel> inNodes = chainTunnels.stream()
                .filter(ct -> Integer.valueOf(1).equals(ct.getChainType()))
                .toList();

        Map<Integer, List<ChainTunnel>> chainNodesMap = chainTunnels.stream()
                .filter(ct -> Integer.valueOf(2).equals(ct.getChainType()))
                .collect(Collectors.groupingBy(
                        ct -> ct.getInx() != null ? ct.getInx() : 0,
                        Collectors.toList()
                ));

        List<List<ChainTunnel>> chainNodesList = chainNodesMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();

        List<ChainTunnel> outNodes = chainTunnels.stream()
                .filter(ct -> Integer.valueOf(3).equals(ct.getChainType()))
                .toList();

        List<DiagnosisResult> results = new ArrayList<>();
        String[] remoteAddresses = forward.getRemoteAddr() != null ? forward.getRemoteAddr().split(",") : new String[0];

        // 根据隧道类型执行不同的诊断策略
        if (Integer.valueOf(1).equals(tunnel.getType())) {
            // 端口转发：入口节点直接TCP ping目标地址
            for (ChainTunnel inNode : inNodes) {
                Node node = nodeService.getById(inNode.getNodeId());
                if (node != null) {
                    for (String remoteAddress : remoteAddresses) {
                        String targetIp = extractIpFromAddress(remoteAddress);
                        int targetPort = extractPortFromAddress(remoteAddress);
                        if (targetIp != null && targetPort != -1) {
                            DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                    node, targetIp, targetPort,
                                    "入口(" + node.getName() + ")->目标(" + remoteAddress + ")"
                            );
                            result.setFromChainType(1);
                            results.add(result);
                        }
                    }
                }
            }
        } else if (Integer.valueOf(2).equals(tunnel.getType())) {
            // 隧道转发：测试完整链路
            // 1. 入口->第一跳（或出口）
            for (ChainTunnel inNode : inNodes) {
                Node fromNode = nodeService.getById(inNode.getNodeId());

                if (fromNode != null) {
                    if (!chainNodesList.isEmpty()) {
                        for (ChainTunnel firstChainNode : chainNodesList.getFirst()) {
                            Node toNode = nodeService.getById(firstChainNode.getNodeId());
                            if (toNode != null) {
                                DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                        fromNode, toNode.getServerIp(), firstChainNode.getPort(),
                                        "入口(" + fromNode.getName() + ")->第1跳(" + toNode.getName() + ")"
                                );
                                result.setFromChainType(1);
                                result.setToChainType(2);
                                result.setToInx(firstChainNode.getInx());
                                results.add(result);
                            }
                        }
                    } else if (!outNodes.isEmpty()) {
                        for (ChainTunnel outNode : outNodes) {
                            Node toNode = nodeService.getById(outNode.getNodeId());
                            if (toNode != null) {
                                DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                        fromNode, toNode.getServerIp(), outNode.getPort(),
                                        "入口(" + fromNode.getName() + ")->出口(" + toNode.getName() + ")"
                                );
                                result.setFromChainType(1);
                                result.setToChainType(3);
                                results.add(result);
                            }
                        }
                    }
                }
            }

            // 2. 链路测试
            for (int i = 0; i < chainNodesList.size(); i++) {
                List<ChainTunnel> currentHop = chainNodesList.get(i);

                for (ChainTunnel currentNode : currentHop) {
                    Node fromNode = nodeService.getById(currentNode.getNodeId());

                    if (fromNode != null) {
                        if (i + 1 < chainNodesList.size()) {
                            for (ChainTunnel nextNode : chainNodesList.get(i + 1)) {
                                Node toNode = nodeService.getById(nextNode.getNodeId());
                                if (toNode != null) {
                                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                            fromNode, toNode.getServerIp(), nextNode.getPort(),
                                            "第" + (i + 1) + "跳(" + fromNode.getName() + ")->第" + (i + 2) + "跳(" + toNode.getName() + ")"
                                    );
                                    result.setFromChainType(2);
                                    result.setFromInx(currentNode.getInx());
                                    result.setToChainType(2);
                                    result.setToInx(nextNode.getInx());
                                    results.add(result);
                                }
                            }
                        } else if (!outNodes.isEmpty()) {
                            for (ChainTunnel outNode : outNodes) {
                                Node toNode = nodeService.getById(outNode.getNodeId());
                                if (toNode != null) {
                                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                            fromNode, toNode.getServerIp(), outNode.getPort(),
                                            "第" + (i + 1) + "跳(" + fromNode.getName() + ")->出口(" + toNode.getName() + ")"
                                    );
                                    result.setFromChainType(2);
                                    result.setFromInx(currentNode.getInx());
                                    result.setToChainType(3);
                                    results.add(result);
                                }
                            }
                        }
                    }
                }
            }

            // 3. 出口->目标地址
            for (ChainTunnel outNode : outNodes) {
                Node node = nodeService.getById(outNode.getNodeId());
                if (node != null) {
                    for (String remoteAddress : remoteAddresses) {
                        String targetIp = extractIpFromAddress(remoteAddress);
                        int targetPort = extractPortFromAddress(remoteAddress);
                        if (targetIp != null && targetPort != -1) {
                            DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                    node, targetIp, targetPort,
                                    "出口(" + node.getName() + ")->目标(" + remoteAddress + ")"
                            );
                            result.setFromChainType(3);
                            results.add(result);
                        }
                    }
                }
            }
        }

        // 构建诊断报告
        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("forwardId", id);
        diagnosisReport.put("forwardName", forward.getName());
        // type 为 NULL 的历史行按直连语义标注，避免拆箱 NPE 让整个诊断接口 500
        diagnosisReport.put("tunnelType", Integer.valueOf(2).equals(tunnel.getType()) ? "隧道转发" : "端口转发");
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    @Override
    @Transactional
    public R updateForwardOrder(Map<String, Object> params) {
        // 1. 先验证参数：结构非法时不必读用户上下文，直接给可读原因
        if (params == null || !params.containsKey("forwards")) {
            return R.err("缺少forwards参数");
        }

        Object rawForwards = params.get("forwards");
        if (!(rawForwards instanceof List<?> forwardsList)) {
            return R.err("forwards参数格式不正确，必须为数组");
        }
        if (forwardsList.isEmpty()) {
            return R.err("forwards参数不能为空");
        }

        // 3. 逐条解析 id/inx：缺字段或非数字直接给可读原因，不让 NPE 冒到全局异常处理
        List<Forward> forwardsToUpdate = new ArrayList<>();
        List<Long> forwardIds = new ArrayList<>();
        for (int i = 0; i < forwardsList.size(); i++) {
            Object item = forwardsList.get(i);
            if (!(item instanceof Map<?, ?> forwardDataRaw)) {
                return R.err("第 " + (i + 1) + " 条排序数据格式不正确，必须为对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> forwardData = (Map<String, Object>) forwardDataRaw;
            Long id = MapParamUtils.toLong(forwardData, "id");
            if (id == null) {
                return R.err("第 " + (i + 1) + " 条排序数据缺少有效的 id：" + MapParamUtils.error(forwardData, "id"));
            }
            Integer inx = MapParamUtils.toInteger(forwardData, "inx");
            if (inx == null) {
                return R.err("第 " + (i + 1) + " 条排序数据缺少有效的 inx：" + MapParamUtils.error(forwardData, "inx"));
            }
            Forward forward = new Forward();
            forward.setId(id);
            forward.setInx(inx);
            forwardsToUpdate.add(forward);
            forwardIds.add(id);
        }

        // 4. 获取当前用户并按权限校验（只能更新自己的转发）
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != 0) {
            // 检查所有转发是否属于当前用户
            QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
            queryWrapper.in("id", forwardIds);
            queryWrapper.eq("user_id", currentUser.getUserId());

            long count = this.count(queryWrapper);
            if (count != forwardIds.size()) {
                return R.err("只能更新自己的转发排序");
            }
        }

        // 5. 执行批量更新
        this.updateBatchById(forwardsToUpdate);
        return R.ok();


    }


    private R changeForwardStatus(Long id, int targetStatus, String gostMethod) {
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != 0) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        UserTunnel userTunnel = null;
        if (targetStatus == 1) {
            if (tunnel.getStatus() != 1) {
                return R.err("隧道已禁用，无法恢复服务");
            }
            if (currentUser.getRoleId() != 0) {
                R flowCheckResult = checkUserFlowLimits(currentUser.getUserId(), tunnel);
                if (flowCheckResult.getCode() != 0) {
                    return flowCheckResult;
                }
                userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
                if (userTunnel == null) {
                    return R.err("你没有该隧道权限");
                }
                if (userTunnel.getStatus() != 1) {
                    return R.err("隧道被禁用");
                }
            }
        }
        if (currentUser.getRoleId() != 0 && userTunnel == null) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        }

        if (userTunnel == null) {
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));

        return applyStatusChange(forward, chainTunnels, userTunnel, targetStatus, gostMethod);
    }

    /**
     * 把期望状态下发到入口节点并落库。
     *
     * <p>语义按「库里是唯一真相」定义：节点离线或下发失败都不阻断落库，失败项以列表返回给前端提示，
     * 由节点重连后的对账兜底——暂停走 WebSocketServer#pauseForwardsExpectingPaused，
     * 恢复走 resyncNodeServices 的「库里有、节点没有」补齐。
     *
     * <p>为什么不在失败时回滚/报错：一条隧道的多个入口节点里只要有一台掉线，旧实现就整单失败，
     * 而失败前已经暂停掉的节点不会恢复，库里仍是活跃态，面板显示正常但流量已经少了一半；
     * 同一场景下删除转发却是允许的（库里删掉、节点重连后由清扫回收），暂停/恢复没有理由更严格。
     *
     * @return 全部节点执行成功返回 R.ok()；有未执行项返回 R.ok(List<String>)，库里状态已生效
     */
    private R applyStatusChange(Forward forward, List<ChainTunnel> chainTunnels, UserTunnel userTunnel,
                                int targetStatus, String gostMethod) {
        return applyStatusChange(forward, chainTunnels, userTunnel, targetStatus, gostMethod,
                targetStatus == 1 ? "" : PauseReason.MANUAL);
    }

    /**
     * 同 {@link #applyStatusChange(Forward, List, UserTunnel, int, String)}，但显式指定暂停原因。
     *
     * <p>round21：停用用户时需要写 pause_reason=disabled，否则续期/启用后的自动恢复
     * （resumeExpiryDisabledPausedForUser 只认 expiry/disabled）无法区分「被停用停掉」和「手动停掉」。
     */
    private R applyStatusChange(Forward forward, List<ChainTunnel> chainTunnels, UserTunnel userTunnel,
                                int targetStatus, String gostMethod, String pauseReason) {
        List<String> unfinished = new ArrayList<>();
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        for (ChainTunnel chainTunnel : chainTunnels) {
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) {
                unfinished.add("节点" + chainTunnel.getNodeId() + "：节点不存在，已记为待执行");
                continue;
            }
            GostDto gostDto = GostUtil.PauseAndResumeService(node.getId(), serviceName, gostMethod);
            if (!Objects.equals("OK", gostDto.getMsg())) {
                if (targetStatus == 1 && isMissingService(gostDto.getMsg())) {
                    // round27：节点上这条服务已经不在了（节点重装/配置被清空，而暂停态的转发不在补齐范围），
                    // 只发 ResumeService 只会一直回 not found，库里却已经恢复成活跃——流量永远起不来。
                    // 这里按库里的端口把服务重建出来，重建失败再把原因带回前端。
                    GostDto rebuilt = rebuildMissingForwardService(forward, userTunnel, node, serviceName);
                    if (Objects.equals("OK", rebuilt.getMsg())) {
                        log.warn("恢复转发 {}：节点{} 上服务 {} 已丢失，已按库重建", forward.getId(), node.getId(), serviceName);
                        continue;
                    }
                    gostDto = rebuilt;
                }
                unfinished.add("节点" + node.getId() + "：" + gostDto.getMsg());
            }
        }
        forward.setStatus(targetStatus);
        forward.setUpdatedTime(System.currentTimeMillis());
        // round19：暂停原因与状态一起落库。恢复时写空串而不是 null——
        // MyBatis-Plus 的 updateById 默认忽略 null 字段，写 null 清不掉旧原因；
        // 空串表示「无暂停原因」，读取方（QuotaResume#isUnlabeled）与存量 null 同样按未标注处理。
        forward.setPauseReason(targetStatus == 1 ? "" : pauseReason);
        this.updateById(forward);
        if (!unfinished.isEmpty()) {
            log.warn("转发 {} 期望状态 {} 已落库，{} 个入口节点未执行：{}",
                    forward.getId(), targetStatus, unfinished.size(), String.join("；", unfinished));
            return R.ok(unfinished);
        }
        return R.ok();
    }

    /**
     * 恢复时节点上服务已不存在，按库里的端口重建（round27）。
     *
     * <p>为什么不能靠 resyncNodeServices：那条补齐路径只处理「节点上报了清单」的时刻，
     * 且跳过 status!=1 的转发；转发处于暂停态时节点被重装，服务就永远补不回来，
     * 用户点恢复只得到 not found，库里却已经变成活跃态。
     *
     * <p>不换口：这个端口是暂停前一直在用的，若真被别的进程占用，重建会失败并把原因带回前端。
     */
    private GostDto rebuildMissingForwardService(Forward forward, UserTunnel userTunnel, Node node, String serviceName) {
        GostDto failed = new GostDto();
        ForwardPort forwardPort = forwardPortService.getOne(new QueryWrapper<ForwardPort>()
                .eq("forward_id", forward.getId()).eq("node_id", node.getId()), false);
        if (forwardPort == null) {
            failed.setMsg("service " + serviceName + " 不存在，且库中没有节点" + node.getId() + " 的端口记录，无法重建");
            return failed;
        }
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) {
            failed.setMsg("service " + serviceName + " 不存在，且所属隧道已删除，无法重建");
            return failed;
        }
        Integer limiter = userTunnel != null ? userTunnel.getSpeedId() : null;
        return GostUtil.CreateForwardService(serviceName, limiter, node, forward, forwardPort, tunnel);
    }

    /** 节点对「服务不存在」的几种回执都带 not found，用它判断是否值得重建。 */
    private static boolean isMissingService(String msg) {
        return msg != null && msg.toLowerCase().contains("not found");
    }

/**
     * 流量重置后恢复「因配额暂停」的转发（round18）。
     *
     * <p>为什么需要：配额超限时 FlowController 会把转发暂停并落库（status=0），但库里没有暂停原因，
     * 于是流量重置只清零 in_flow/out_flow，转发永远停在暂停态——用户额度回来了、业务还是断的。
     * 这里用「重置前是否超额」推断原因（调用方在清零前算），只放行真正因配额停掉的转发；
     * 手动暂停的转发在账号未超额时不会被碰，到期/停用的账号则整段跳过。
     *
     * @param userWasOverQuota 重置前用户是否超额
     */
    @Override
    public List<String> resumeQuotaPausedForUser(Long userId, boolean userWasOverQuota, long now) {
        List<String> notes = new ArrayList<>();
        if (userId == null || !userWasOverQuota) {
            return notes;
        }
        User user = userService.getById(userId);
        if (user == null) {
            return notes;
        }
        List<Forward> paused = this.list(new QueryWrapper<Forward>().eq("user_id", userId).eq("status", 0));
        List<Forward> toResume = new ArrayList<>();
        for (Forward forward : paused) {
            UserTunnel userTunnel = getUserTunnel(forward.getUserId(), forward.getTunnelId());
            String deny = QuotaResume.denyReason(forward, user, userTunnel, true, now);
            if (deny != null) {
                log.info("流量重置后不恢复转发 {}：{}", forward.getId(), deny);
                continue;
            }
            if (userTunnel != null && QuotaResume.userTunnelOverQuota(userTunnel.getFlow(),
                    userTunnel.getInFlow(), userTunnel.getOutFlow())) {
                // 只重置了账号额度、隧道额度仍超：恢复了也会被下一次上报立刻再停，保持暂停等隧道重置
                log.info("流量重置后保持暂停转发 {}：所属用户隧道 {} 配额仍超额", forward.getId(), userTunnel.getId());
                continue;
            }
            toResume.add(forward);
        }
        notes.addAll(resumeForwards(toResume));
        if (!toResume.isEmpty()) {
            log.info("用户 {} 流量重置后按配额暂停记录恢复 {} 条转发", userId, toResume.size());
        }
        return notes;
    }

    /**
     * 流量重置后恢复「因配额暂停」的转发（用户隧道维度，round18）。
     */
    @Override
    public List<String> resumeQuotaPausedForUserTunnel(Integer userTunnelId, boolean userTunnelWasOverQuota, long now) {
        List<String> notes = new ArrayList<>();
        if (userTunnelId == null || !userTunnelWasOverQuota) {
            return notes;
        }
        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel == null) {
            return notes;
        }
        User user = userService.getById(userTunnel.getUserId());
        if (user != null && QuotaResume.userOverQuota(user.getFlow(), user.getInFlow(), user.getOutFlow())) {
            log.info("用户隧道 {} 流量重置后保持暂停：账号 {} 配额仍超额", userTunnelId, userTunnel.getUserId());
            return notes;
        }
        List<Forward> paused = this.list(new QueryWrapper<Forward>()
                .eq("user_id", userTunnel.getUserId())
                .eq("tunnel_id", userTunnel.getTunnelId())
                .eq("status", 0));
        List<Forward> toResume = new ArrayList<>();
        for (Forward forward : paused) {
            String deny = QuotaResume.denyReason(forward, user, userTunnel, true, now);
            if (deny == null) {
                toResume.add(forward);
            } else {
                log.info("流量重置后不恢复转发 {}：{}", forward.getId(), deny);
            }
        }
        notes.addAll(resumeForwards(toResume));
        if (!toResume.isEmpty()) {
            log.info("用户隧道 {} 流量重置后按配额暂停记录恢复 {} 条转发", userTunnelId, toResume.size());
        }
        return notes;
    }

    @Override
    public List<String> resumeExpiryDisabledPausedForUser(Long userId) {
        List<String> notes = new ArrayList<>();
        if (userId == null) {
            return notes;
        }
        User user = userService.getById(userId);
        if (user == null) {
            return notes;
        }
        long now = System.currentTimeMillis();
        if (!QuotaResume.usable(user.getStatus(), user.getExpTime(), now)) {
            return notes;
        }
        List<Forward> paused = this.list(new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("status", 0)
                .in("pause_reason", PauseReason.EXPIRY, PauseReason.DISABLED));
        List<Forward> toResume = new ArrayList<>();
        for (Forward forward : paused) {
            // 双保险：SQL 已按 pause_reason 过滤，这里再判一次，避免以后有人放宽查询条件
            // 把 manual 也带进来（手动暂停绝不能被续期动作放开）。
            if (!PauseReason.EXPIRY.equals(forward.getPauseReason())
                    && !PauseReason.DISABLED.equals(forward.getPauseReason())) {
                continue;
            }
            UserTunnel userTunnel = getUserTunnel(forward.getUserId(), forward.getTunnelId());
            if (userTunnel != null && !QuotaResume.usable(userTunnel.getStatus(), userTunnel.getExpTime(), now)) {
                log.info("用户 {} 已续期/启用，但用户隧道 {} 仍不可用，保持暂停转发 {}",
                        userId, userTunnel.getId(), forward.getId());
                continue;
            }
            toResume.add(forward);
        }
        notes.addAll(resumeForwards(toResume));
        if (!toResume.isEmpty()) {
            log.info("用户 {} 续期/启用后恢复 {} 条到期或停用暂停的转发", userId, toResume.size());
        }
        return notes;
    }

    @Override
    public List<String> resumeExpiryDisabledPausedForUserTunnel(Integer userTunnelId) {
        List<String> notes = new ArrayList<>();
        if (userTunnelId == null) {
            return notes;
        }
        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel == null) {
            return notes;
        }
        long now = System.currentTimeMillis();
        if (!QuotaResume.usable(userTunnel.getStatus(), userTunnel.getExpTime(), now)) {
            return notes;
        }
        User user = userService.getById(userTunnel.getUserId());
        if (user == null || !QuotaResume.usable(user.getStatus(), user.getExpTime(), now)) {
            log.info("用户隧道 {} 已续期/启用，但账号 {} 仍不可用，不恢复", userTunnelId, userTunnel.getUserId());
            return notes;
        }
        List<Forward> paused = this.list(new QueryWrapper<Forward>()
                .eq("user_id", userTunnel.getUserId())
                .eq("tunnel_id", userTunnel.getTunnelId())
                .eq("status", 0)
                .in("pause_reason", PauseReason.EXPIRY, PauseReason.DISABLED));
        List<Forward> toResume = new ArrayList<>();
        for (Forward forward : paused) {
            if (PauseReason.EXPIRY.equals(forward.getPauseReason())
                    || PauseReason.DISABLED.equals(forward.getPauseReason())) {
                toResume.add(forward);
            }
        }
        notes.addAll(resumeForwards(toResume));
        if (!toResume.isEmpty()) {
            log.info("用户隧道 {} 续期/启用后恢复 {} 条到期或停用暂停的转发", userTunnelId, toResume.size());
        }
        return notes;
    }

    /**
     * 逐条把暂停态转发恢复成活跃态并下发 ResumeService。
     * 语义与 {@link #applyStatusChange} 一致：先落库，节点失败只记说明。
     */
    private List<String> resumeForwards(List<Forward> forwards) {
        List<String> notes = new ArrayList<>();
        for (Forward forward : forwards) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null) {
                notes.add("转发" + forward.getId() + "：隧道不存在，未恢复");
                continue;
            }
            if (tunnel.getStatus() != 1) {
                notes.add("转发" + forward.getId() + "：隧道已禁用，未恢复");
                continue;
            }
            UserTunnel userTunnel = getUserTunnel(forward.getUserId(), forward.getTunnelId());
            List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>()
                    .eq("tunnel_id", forward.getTunnelId()).eq("chain_type", 1));
            if (chainTunnels.isEmpty()) {
                notes.add("转发" + forward.getId() + "：没有入口节点，未恢复");
                continue;
            }
            R r = applyStatusChange(forward, chainTunnels, userTunnel, 1, "ResumeService");
            if (r.getData() instanceof List) {
                for (Object item : (List<?>) r.getData()) {
                    notes.add(String.valueOf(item));
                }
            }
        }
        return notes;
    }

    private String extractIpFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1) {
                return address.substring(1, closeBracket);
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0) {
            return address.substring(0, lastColon);
        }

        // 如果没有端口，直接返回地址
        return address;
    }

    private int extractPortFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return -1;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1 && closeBracket + 1 < address.length() && address.charAt(closeBracket + 1) == ':') {
                String portStr = address.substring(closeBracket + 2);
                try {
                    return Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < address.length()) {
            String portStr = address.substring(lastColon + 1);
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // 如果没有端口，返回-1表示无法解析
        return -1;
    }

    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 构建TCP ping请求数据
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 4);
            tcpPingData.put("timeout", 5000); // 5秒超时

            // 发送TCP ping命令到节点
            GostDto gostResult = WebSocketServer.send_msg(node.getId(), tcpPingData, "TcpPing");

            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setTimestamp(System.currentTimeMillis());

            if (gostResult != null && "OK".equals(gostResult.getMsg())) {
                // 尝试解析TCP ping响应数据
                try {
                    if (gostResult.getData() != null) {
                        JSONObject tcpPingResponse = (JSONObject) gostResult.getData();
                        boolean success = tcpPingResponse.getBooleanValue("success");

                        result.setSuccess(success);
                        if (success) {
                            result.setMessage("TCP连接成功");
                            result.setAverageTime(tcpPingResponse.getDoubleValue("averageTime"));
                            result.setPacketLoss(tcpPingResponse.getDoubleValue("packetLoss"));
                        } else {
                            result.setMessage(tcpPingResponse.getString("errorMessage"));
                            result.setAverageTime(-1.0);
                            result.setPacketLoss(100.0);
                        }
                    } else {
                        // 没有详细数据，使用默认值
                        result.setSuccess(true);
                        result.setMessage("TCP连接成功");
                        result.setAverageTime(0.0);
                        result.setPacketLoss(0.0);
                    }
                } catch (Exception e) {
                    // 解析响应数据失败，但TCP ping命令本身成功了
                    result.setSuccess(true);
                    result.setMessage("TCP连接成功，但无法解析详细数据");
                    result.setAverageTime(0.0);
                    result.setPacketLoss(0.0);
                }
            } else {
                result.setSuccess(false);
                result.setMessage(gostResult != null ? gostResult.getMsg() : "节点无响应");
                result.setAverageTime(-1.0);
                result.setPacketLoss(100.0);
            }

            return result;
        } catch (Exception e) {
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setSuccess(false);
            result.setMessage("诊断执行异常: " + e.getMessage());
            result.setTimestamp(System.currentTimeMillis());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    private DiagnosisResult performTcpPingDiagnosisWithConnectionCheck(Node node, String targetIp, Integer port, String description) {
        DiagnosisResult result = new DiagnosisResult();
        result.setNodeId(node != null ? node.getId() : null);
        result.setNodeName(node != null ? node.getName() : null);
        result.setTargetIp(targetIp);
        result.setTargetPort(port != null ? port : 0);
        result.setDescription(description);
        result.setTimestamp(System.currentTimeMillis());

        if (node == null) {
            result.setSuccess(false);
            result.setMessage("节点不存在");
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
        if (targetIp == null || targetIp.trim().isEmpty()) {
            result.setSuccess(false);
            result.setMessage("目标IP未配置或无效");
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
        if (port == null || port <= 0 || port > 65535) {
            result.setSuccess(false);
            result.setMessage("目标端口未配置或无效");
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }

        try {
            return performTcpPingDiagnosis(node, targetIp, port, description);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("连接检查异常: " + e.getMessage());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    private UserInfo getCurrentUserInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        String userName = JwtUtil.getNameFromToken();
        return new UserInfo(userId, roleId, userName);
    }

    private Tunnel validateTunnel(Integer tunnelId) {
        return tunnelService.getById(tunnelId);
    }

    private Forward validateForwardExists(Long forwardId, UserInfo currentUser) {
        Forward forward = this.getById(forwardId);
        if (forward == null) {
            return null;
        }

        // 普通用户只能操作自己的转发
        if (!currentUser.isAdmin() &&
                !Objects.equals(currentUser.getUserId(), forward.getUserId())) {
            return null;
        }

        return forward;
    }

    private UserPermissionResult checkUserPermissions(UserInfo currentUser, Tunnel tunnel, Long excludeForwardId) {
        return checkUserPermissions(currentUser, tunnel, excludeForwardId, false);
    }

    /**
     * @param skipQuota 删除已有转发时不检查数量配额：满配额的用户必须还能删自己的转发
     */
    private UserPermissionResult checkUserPermissions(UserInfo currentUser, Tunnel tunnel, Long excludeForwardId, boolean skipQuota) {
        if (currentUser.isAdmin()) {
            return UserPermissionResult.success(null, null);
        }

        // 获取用户信息
        User userInfo = userService.getById(currentUser.getUserId());
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("当前账号已到期");
        }

        // 检查用户隧道权限
        UserTunnel userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
        if (userTunnel == null) {
            return UserPermissionResult.error("你没有该隧道权限");
        }

        if (userTunnel.getStatus() != 1) {
            return UserPermissionResult.error("隧道被禁用");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("该隧道权限已到期");
        }

        // 流量限制检查
        if (userInfo.getFlow() <= 0) {
            return UserPermissionResult.error("用户总流量已用完");
        }
        if (userTunnel.getFlow() <= 0) {
            return UserPermissionResult.error("该隧道流量已用完");
        }

        // 转发数量限制检查
        if (!skipQuota) {
            R quotaCheckResult = checkForwardQuota(currentUser.getUserId(), tunnel.getId().intValue(), userTunnel, userInfo, excludeForwardId);
            if (quotaCheckResult.getCode() != 0) {
                return UserPermissionResult.error(quotaCheckResult.getMsg());
            }
        }

        return UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
    }

    private R checkForwardQuota(Integer userId, Integer tunnelId, UserTunnel userTunnel, User userInfo, Long excludeForwardId) {
        // 检查用户总转发数量限制（编辑时排除当前转发自身，否则 num=1 的用户改不了自己唯一那条）
        QueryWrapper<Forward> userQuery = new QueryWrapper<Forward>().eq("user_id", userId);
        if (excludeForwardId != null) {
            userQuery.ne("id", excludeForwardId);
        }

        long userForwardCount = this.count(userQuery);
        if (userForwardCount >= userInfo.getNum()) {
            return R.err("用户总转发数量已达上限，当前限制：" + userInfo.getNum() + "个");
        }

        // 检查用户在该隧道的转发数量限制
        QueryWrapper<Forward> tunnelQuery = new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId);

        if (excludeForwardId != null) {
            tunnelQuery.ne("id", excludeForwardId);
        }

        long tunnelForwardCount = this.count(tunnelQuery);
        if (tunnelForwardCount >= userTunnel.getNum()) {
            return R.err("该隧道转发数量已达上限，当前限制：" + userTunnel.getNum() + "个");
        }

        return R.ok();
    }

    private R checkUserFlowLimits(Integer userId, Tunnel tunnel) {
        User userInfo = userService.getById(userId);
        if (userInfo == null) {
            return R.err("用户不存在");
        }
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return R.err("当前账号已到期");
        }

        UserTunnel userTunnel = getUserTunnel(userId, tunnel.getId().intValue());
        if (userTunnel == null) {
            return R.err("你没有该隧道权限");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return R.err("该隧道权限已到期，无法恢复服务");
        }

        // 检查用户总流量限制（QuotaResume安全处理 null 与 <=0 不限量语义）
        if (QuotaResume.userOverQuota(userInfo.getFlow(), userInfo.getInFlow(), userInfo.getOutFlow())) {
            return R.err("用户总流量已用完，无法恢复服务");
        }

        // 检查隧道流量限制
        if (QuotaResume.userTunnelOverQuota(userTunnel.getFlow(), userTunnel.getInFlow(), userTunnel.getOutFlow())) {
            return R.err("该隧道流量已用完，无法恢复服务");
        }

        return R.ok();
    }

    /**
     * 停用用户时立即暂停其名下活跃转发（round21）。
     *
     * <p>原实现只有恢复侧：updateUser 把 status 改成 0 后，没有任何路径主动停流量，
     * 要等下一次节点流量上报由 FlowController 检查 status 才暂停；节点离线或无流量时，
     * 已停用的账号仍在转发。这里与到期暂停同口径：先落库 status=0 + pause_reason=disabled，
     * 再逐入口下发 PauseService，失败只记说明，由节点重连的 pauseForwardsExpectingPaused 兜底。
     */
    @Override
    public List<String> pauseForDisabledUser(Long userId) {
        List<String> notes = new ArrayList<>();
        if (userId == null) {
            return notes;
        }
        List<Forward> active = this.list(new QueryWrapper<Forward>().eq("user_id", userId).eq("status", 1));
        for (Forward forward : active) {
            try {
                UserTunnel userTunnel = getUserTunnel(forward.getUserId(), forward.getTunnelId());
                List<ChainTunnel> chainTunnels = chainTunnelService.list(
                        new QueryWrapper<ChainTunnel>().eq("tunnel_id", forward.getTunnelId()).eq("chain_type", 1));
                R r = applyStatusChange(forward, chainTunnels, userTunnel, 0, "PauseService", PauseReason.DISABLED);
                if (r.getData() instanceof List) {
                    for (Object item : (List<?>) r.getData()) {
                        notes.add(String.valueOf(item));
                    }
                }
            } catch (Exception e) {
                log.warn("停用用户 {} 时暂停转发 {} 失败", userId, forward.getId(), e);
                notes.add("转发" + forward.getId() + "：" + e.getMessage());
            }
        }
        return notes;
    }

    private UserTunnel getUserTunnel(Integer userId, Integer tunnelId) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId));
    }

    private String buildServiceName(Long forwardId, Integer userId, UserTunnel userTunnel) {
        int userTunnelId = (userTunnel != null) ? userTunnel.getId() : 0;
        return forwardId + "_" + userId + "_" + userTunnelId;
    }


    public List<ChainTunnel> get_port(List<ChainTunnel> chainTunnelList, Integer in_port, Long forward_id) {
        if (chainTunnelList == null || chainTunnelList.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<Integer>> list = new ArrayList<>();

        // 获取每个节点的端口列表
        for (ChainTunnel tunnel : chainTunnelList) {
            List<Integer> nodePort = getNodePort(tunnel.getNodeId(), forward_id);
            if (nodePort.isEmpty()) {
                throw new RuntimeException("暂无可用端口");
            }
            list.add(nodePort);
        }

        // ========== 如果指定了 in_port，优先检查公有 ==========
        if (in_port != null) {
            for (List<Integer> ports : list) {
                if (!ports.contains(in_port)) {
                    throw new RuntimeException("指定端口 " + in_port + " 不可用（并非所有节点都有此端口）");
                }
            }

            // 所有节点都有该端口 设置回 ChainTunnel
            for (ChainTunnel tunnel : chainTunnelList) {
                tunnel.setPort(in_port);
            }
            return chainTunnelList;
        }

        // ========== 未指定 in_port 查找最小的共同端口 ==========
        if (list.isEmpty()) {
            return chainTunnelList;
        }
        Set<Integer> intersection = new HashSet<>(list.getFirst());
        for (int i = 1; i < list.size(); i++) {
            intersection.retainAll(list.get(i));
        }

        if (!intersection.isEmpty()) {
            // 找最小端口
            Integer commonMin = intersection.stream().min(Integer::compareTo).orElseThrow();

            // 设置到所有节点
            for (ChainTunnel tunnel : chainTunnelList) {
                tunnel.setPort(commonMin);
            }

            return chainTunnelList;
        }

        // ========== 没有共同端口取各自第一个可用端口 ==========
        for (int i = 0; i < chainTunnelList.size(); i++) {
            List<Integer> ports = list.get(i);
            Integer first = ports.getFirst();
            chainTunnelList.get(i).setPort(first);
        }

        return chainTunnelList;
    }

    public List<Integer> getNodePort(Long nodeId, Long forward_id) {

        Node node = nodeService.getById(nodeId);
        if (node == null) {
            throw new RuntimeException("节点不存在");
        }

        // 1. 查询隧道转发链占用的端口
        List<ChainTunnel> chainTunnels = chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().eq("node_id", nodeId)
        );
        Set<Integer> usedPorts = new HashSet<>();
        List<String> dirtyChainPorts = new ArrayList<>();
        for (ChainTunnel chainTunnel : chainTunnels) {
            Integer chainPort = chainTunnel.getPort();
            if (chainPort == null) {
                continue;
            }
            // 越界脏值（0 / 70000）不是节点上真在监听的端口，不能占住端口段：
            // 否则脏行一天不修，这段端口就一天不再分配，而且跳点摘要永远对不上（见 expectedChainNodes）。
            // 只记日志不动库：自动删行会把排障线索一起删掉，清脏数据要人工确认。
            if (!isValidPort(chainPort)) {
                dirtyChainPorts.add("tunnelId=" + chainTunnel.getTunnelId() + ",port=" + chainPort);
                continue;
            }
            usedPorts.add(chainPort);
        }
        if (!dirtyChainPorts.isEmpty()) {
            log.warn("节点[{}] 中继链存在非法端口，已忽略其占用（不改库，待人工清理）：{}", nodeId, dirtyChainPorts);
        }

        List<ForwardPort> list = forwardPortService.list(new QueryWrapper<ForwardPort>().eq("node_id", nodeId).ne("forward_id", forward_id));
        Set<Integer> forwardUsedPorts = new HashSet<>();
        List<String> dirtyForwardPorts = new ArrayList<>();
        if (list != null) {
            for (ForwardPort forwardPort : list) {
                if (forwardPort == null) {
                    continue;
                }
                Integer port = forwardPort.getPort();
                if (!isValidPort(port)) {
                    dirtyForwardPorts.add("forwardId=" + forwardPort.getForwardId() + ",port=" + port);
                    continue;
                }
                forwardUsedPorts.add(port);
            }
        }
        if (!dirtyForwardPorts.isEmpty()) {
            log.warn("节点[{}] 转发端口记录存在非法端口，已忽略其占用（不改库，待人工清理）：{}", nodeId, dirtyForwardPorts);
        }
        usedPorts.addAll(forwardUsedPorts);

        List<Integer> parsedPorts = TunnelServiceImpl.parsePorts(node.getPort());
        return parsedPorts.stream()
                .filter(p -> !usedPorts.contains(p))
                .toList();
    }

    /** 端口合法区间 1..65535：0、负数、&gt;65535 都是脏值，既不能占用端口段，也算不出跳点摘要。 */
    private static boolean isValidPort(Integer port) {
        return port != null && port >= 1 && port <= 65535;
    }



    // ========== 内部数据类 ==========

    /** 单个入口节点的更新计划：库里的原端口记录（补偿要用原端口）+ 本次要应用的目标端口。 */
    private static final class NodeServiceUpdate {
        private final Node node;
        private final ForwardPort storedPort;
        // 换口重试会改它，下发和落库都必须用最终值，不能一个用一个不写
        private Integer targetPort;

        private NodeServiceUpdate(Node node, ForwardPort storedPort, Integer targetPort) {
            this.node = node;
            this.storedPort = storedPort;
            this.targetPort = targetPort;
        }

        private void usePort(Integer port) {
            this.targetPort = port;
        }

        /** 下发和落库都用副本：storedPort 必须保持库里的原值，补偿时要按原端口重发。 */
        private ForwardPort copyWithTargetPort() {
            ForwardPort forwardPort = new ForwardPort();
            BeanUtils.copyProperties(storedPort, forwardPort);
            forwardPort.setPort(targetPort);
            return forwardPort;
        }
    }

    @Data
    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;
        private final String userName;

        public UserInfo(Integer userId, Integer roleId, String userName) {
            this.userId = userId;
            this.roleId = roleId != null ? roleId : -1;
            this.userName = userName;
        }

        public boolean isAdmin() {
            return Integer.valueOf(0).equals(this.roleId);
        }
    }

    @Data
    private static class UserPermissionResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer limiter;
        private final UserTunnel userTunnel;

        private UserPermissionResult(boolean hasError, String errorMessage, Integer limiter, UserTunnel userTunnel) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.limiter = limiter;
            this.userTunnel = userTunnel;
        }

        public static UserPermissionResult success(Integer limiter, UserTunnel userTunnel) {
            return new UserPermissionResult(false, null, limiter, userTunnel);
        }

        public static UserPermissionResult error(String errorMessage) {
            return new UserPermissionResult(true, errorMessage, null, null);
        }
    }

    @Data
    public static class DiagnosisResult {
        private Long nodeId;
        private String nodeName;
        private String targetIp;
        private Integer targetPort;
        private String description;
        private boolean success;
        private String message;
        private double averageTime;
        private double packetLoss;
        private long timestamp;

        // 链路类型相关字段
        private Integer fromChainType; // 1: 入口, 2: 链, 3: 出口
        private Integer fromInx;
        private Integer toChainType;
        private Integer toInx;
    }

    /**
     * 节点重连后按数据库补齐缺失的入口服务（兼容旧调用方：没有链清单）。
     */
    @Override
    public List<String> resyncNodeServices(Long nodeId, List<String> reportedServices) {
        return resyncNodeServices(nodeId, reportedServices, Collections.emptyList());
    }

    /**
     * 节点重连后按数据库补齐缺失的入口服务与中继链（chains_* / <tunnelId>_tls）。
     *
     * <p>背景：只有 paused 状态会补发 PauseService，活跃转发在节点上丢了 gost.json
     * （容器重装、磁盘被清、手工删服务）后不会自愈——面板显示正常，端口却没人听。
     * 这里用节点自报的 ServiceListReport 当依据，只补"节点清单里确实没有"的资源：
     * 节点侧 AddService 是先全量 IsRegistered 再注册，同名重发会让整批失败甚至抢同一个端口，
     * 所以已经在清单里的一律不动，半残的入口服务也只上报不自动修复。
     *
     * <p>中继链按 TunnelServiceImpl.publishChains 的口径判断：入口节点（chainType=1）只需要
     * chains_<tid>，链节点（2）既需要 chains_<tid>（指向下一跳）也需要 <tid>_tls，
     * 出口节点（3）只需要 <tid>_tls。同一隧道必须先补 chains 再补 service——service 的 handler
     * 直接引用 chains_<tid>，链没立起来时补监听只会得到一个连不通的入口。
     *
     * <p>只补"仍被活跃转发引用"的隧道（forward.tunnel_id 且 status=1 至少一条），
     * 否则用户已经放弃的隧道会被这条自愈悄悄复活。
     *
     * <p>必须是只读判断 + 幂等下发，异常一律吞成说明文本，不影响 WS 线程与孤儿清扫。
     *
     * @param nodeId           上报清单的节点
     * @param reportedServices 节点自报的服务名列表
     * @param reportedChains   节点自报的链名列表
     * @return 处置说明，空表示无需处理
     */
    @Override
    public List<String> resyncNodeServices(Long nodeId, List<String> reportedServices, List<String> reportedChains) {
        return resyncNodeServices(nodeId, reportedServices, reportedChains, null);
    }

    /**
     * 在上面三参版本之上，额外按节点上报的配置摘要纠正「名字对得上、配置却不一样」的漂移。
     *
     * <p>摘要为 null（老节点没报）时与三参版本完全等价；摘要里没有某个服务名，
     * 说明节点这次没报它，同样不动。
     */
    @Override
    public List<String> resyncNodeServices(Long nodeId, List<String> reportedServices, List<String> reportedChains,
                                           Map<String, String> reportedDigests) {
        return resyncNodeServices(nodeId, reportedServices, reportedChains, reportedDigests, null);
    }

    /**
     * 五参版本：链摘要也参与对账。链摘要为 null（老节点没报）时与四参版本完全等价。
     */
    @Override
    public List<String> resyncNodeServices(Long nodeId, List<String> reportedServices, List<String> reportedChains,
                                           Map<String, String> reportedDigests,
                                           Map<String, List<String>> reportedChainDigests) {
        List<String> notes = new ArrayList<>();
        if (nodeId == null) {
            return notes;
        }
        List<String> services = reportedServices == null ? Collections.emptyList() : reportedServices;
        List<String> chains = reportedChains == null ? Collections.emptyList() : reportedChains;
        // 老节点侧只有 services/chains 都空时才压根不上报（见 go-gost reportServiceList），
        // 双空清单说明这份上报本身不可信：不做任何推断，免得凭空造出资源。
        // 新节点（round46 起）配置真的全空时照样上报，并在 features 里声明 emptyReport，
        // WebSocketServer 会把 @emptyReport_supported 哨兵写进摘要——带哨兵才按库全量对账。
        boolean emptyAllowed = reportedDigests != null
                && reportedDigests.containsKey(EMPTY_REPORT_SUPPORTED_KEY);
        if (services.isEmpty() && chains.isEmpty() && !emptyAllowed) {
            return notes;
        }
        if (services.isEmpty() && chains.isEmpty()) {
            log.info("节点 {} 上报资源全空且声明 emptyReport，按库全量对账", nodeId);
        }
        Node node = nodeService.getById(nodeId);
        if (node == null) {
            notes.add("节点" + nodeId + " 不存在，跳过补齐");
            return notes;
        }
        // 先链后服务：隧道型转发的入口服务 handler 引用 chains_<tid>，链先立起来才有意义
        try {
            resyncChainResources(nodeId, node, services, chains, reportedDigests, reportedChainDigests, notes);
        } catch (RuntimeException e) {
            notes.add("节点" + nodeId + " 中继链补齐异常：" + e.getMessage());
        }
        resyncForwardServices(nodeId, node, services, notes, reportedDigests);
        return notes;
    }

    /**
     * 按节点自报的服务清单补齐转发入口服务（f_u_ut 这一对 tcp/udp）。
     *
     * <p>节点侧 AddService 是先全量 IsRegistered 再注册，同名残留会让整批失败，
     * 所以半残（只剩一个协议）不自动修复，只上报交人工处理。
     */
    /**
     * 对"清单里有、但可能已被暂停"的入口服务补发一次 ResumeService。
     *
     * <p>gost 的暂停是软状态：pauseServices 释放注册名并在服务配置里打
     * Metadata["paused"]=true，节点重启后条目还在、清单照样上报，后端的"缺失才补"规则
     * 因此看不见它。resumeServices 对没暂停的服务只做 skipped 并返回成功，所以这条路径
     * 对健康服务无副作用，幂等。
     *
     * @return 非 OK 时把节点返回的原因写进 notes；成功不写，避免每次上报都刷日志
     */
    /**
     * 暂停转发的旧名服务清理。
     *
     * <p>暂停（status=0）转发的服务条目仍留在节点配置里（Metadata["paused"]=true，自报清单照样带上），
     * 但 resync 主路径遇到 status!=1 直接 continue。user_tunnel 记录被删/重建导致名字从
     * `f_u_5` 变成 `f_u_0` 时，节点上留下的是旧名条目：恢复路径按新名重建看不到它，
     * 清扫只认"节点有、库里没有"的孤儿，于是旧名条目与旧监听永久驻留，面板毫无异常。
     *
     * <p>只删不建：暂停转发本就不该在节点上占监听（round41 契约）。删掉旧名后，用户点恢复时
     * round27 的 rebuildMissingForwardService 会按库重建期望名服务。在这里"先建后停"会把一个
     * 真实监听短暂暴露给本该停流的转发（暂停多半因为超额/欠费/停用），得不偿失。
     *
     * @return false 表示本轮修复预算已耗尽，调用方必须立刻停止本轮后续修复动作
     */
    private boolean cleanupPausedVariant(Long nodeId, Forward forward, ForwardPort forwardPort,
                                        Set<String> present, List<String> notes, RepairBudget budget) {
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) {
            return true; // 隧道已删：交给删除/清扫流程，这里不推断
        }
        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        String prefix = forward.getId() + "_" + forward.getUserId() + "_";
        List<String> stale = new ArrayList<>();
        for (String presentName : present) {
            if (presentName != null && presentName.startsWith(prefix)
                    && !presentName.equals(serviceName + "_tcp") && !presentName.equals(serviceName + "_udp")) {
                stale.add(presentName);
            }
        }
        if (stale.isEmpty()) {
            return true;
        }
        Collections.sort(stale);
        // 与活跃路径的 key 隔离：暂停态清理失败不能把活跃业务一起熔断 15 分钟
        String key = "paused_variant|" + nodeId + "|" + serviceName;
        if (!breakerAllows(key, nodeId, "清理暂停转发旧名服务 " + String.join(",", stale), notes)) {
            return true;
        }
        if (!budget.tryConsume()) {
            return false;
        }
        com.alibaba.fastjson.JSONArray stalePayload = new com.alibaba.fastjson.JSONArray();
        stalePayload.addAll(stale);
        GostDto removed;
        try {
            removed = GostUtil.DeleteService(nodeId, stalePayload);
        } catch (RuntimeException e) {
            breakerResult(key, false, null, e.getMessage());
            notes.add("暂停转发旧名服务清理异常（" + String.join(",", stale) + "@node" + nodeId + "）："
                    + e.getMessage() + "，旧名条目仍在");
            return true;
        }
        String removedMsg = removed == null || removed.getMsg() == null ? "节点无响应" : removed.getMsg();
        if (!"OK".equals(removedMsg)) {
            breakerResult(key, false, null, removedMsg);
            notes.add("暂停转发旧名服务清理失败（" + String.join(",", stale) + "@node" + nodeId + "）："
                    + removedMsg + "，旧名条目仍在");
            return true;
        }
        breakerResult(key, true, null, null);
        budget.markRepaired();
        notes.add("已清理暂停转发的旧名服务 " + String.join(",", stale) + "@node" + nodeId
                + "（端口" + forwardPort.getPort() + "，恢复时将按库重建）");
        return true;
    }

    /**
     * 暂停态放量纠正：库里 status=0 的转发，节点上同名服务却仍在运行。
     *
     * <p>暂停在节点侧是软状态（Metadata["paused"]=true + 释放注册名），以下几种情况都会让它
     * 悄悄回到运行态而面板毫无察觉：有人手工改回 gost.json、老版本二进制没持久化 paused、
     * 重建后补暂停失败、容器整盘恢复。后果是入口端口重新收流量，而计费/配额仍按暂停处理 ——
     * 既超额放量又是计费黑洞。
     *
     * <p>唯一可信来源是节点自报摘要里的 {@code <name>@paused}（0=在跑）。老节点不报这个字段，
     * 没有 {@code @paused_supported} 哨兵就整维度跳过：绝不能凭「没报」推断「没暂停」。
     *
     * @return false 表示本轮修复预算已耗尽，调用方必须立刻停止本轮后续修复动作
     */
    private boolean enforcePausedService(Long nodeId, Forward forward, Set<String> present,
                                        Map<String, String> digests, List<String> notes, RepairBudget budget) {
        if (digests == null || !digests.containsKey(PAUSED_SUPPORTED_KEY)) {
            return true;
        }
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) {
            return true; // 隧道已删：交给删除/清扫流程，这里不推断
        }
        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        List<String> running = new ArrayList<>();
        for (String protocol : new String[]{"_tcp", "_udp"}) {
            String protocolName = serviceName + protocol;
            if (!present.contains(protocolName)) {
                continue;
            }
            // 节点侧 Paused 字段带 omitempty：只有真暂停才出现 "paused":true。
            // 因此「有哨兵 + 没有该键」== 在运行，不能当成「没上报、无法判断」；
            // 节点明确报 false 时 WebSocketServer 也会落成 "0"，两种写法都按运行处理。
            if (!"1".equals(digests.get(protocolName + "@paused"))) {
                running.add(protocolName);
            }
        }
        if (running.isEmpty()) {
            return true;
        }
        // 与活跃漂移、旧名清理各自独立计数：暂停态纠正失败不该把在跑的业务一起熔断
        String key = "paused_enforce|" + nodeId + "|" + serviceName;
        if (!breakerAllows(key, nodeId, "补暂停服务 " + String.join(",", running), notes)) {
            return true;
        }
        if (!budget.tryConsume()) {
            return false;
        }
        boolean ok = false;
        String msg;
        try {
            // 只发实际在跑的那几个名字：节点 pauseServices 逐个校验，缺一个就整批失败
            GostDto gostDto = GostUtil.PauseAndResumeServices(nodeId, running, "PauseService");
            msg = gostDto == null || gostDto.getMsg() == null ? "节点无响应" : gostDto.getMsg();
            ok = "OK".equals(msg) || GostUtil.notFound(msg);
        } catch (RuntimeException e) {
            msg = e.getMessage();
        }
        breakerResult(key, ok, null, ok ? null : msg);
        if (ok) {
            budget.markRepaired();
            notes.add("已补暂停仍在运行的转发服务 " + String.join(",", running) + "@node" + nodeId
                    + "（库内为暂停态，节点此前仍在放量）");
        } else {
            notes.add("暂停转发服务补暂停失败（" + String.join(",", running) + "@node" + nodeId + "）：" + msg
                    + "，该转发可能仍在放量");
        }
        return true;
    }

    private void resumeReportedServices(Long nodeId, String serviceName, List<String> notes) {
        GostDto gostDto;
        try {
            gostDto = GostUtil.PauseAndResumeService(nodeId, serviceName, "ResumeService");
        } catch (RuntimeException e) {
            notes.add("服务 " + serviceName + "@node" + nodeId + " 恢复异常：" + e.getMessage());
            return;
        }
        if (gostDto == null || !"OK".equals(gostDto.getMsg())) {
            notes.add("服务 " + serviceName + "@node" + nodeId + " 恢复失败："
                    + (gostDto == null ? "节点无响应" : gostDto.getMsg()));
        }
    }

    /**
     * 单轮修复预算：一次 resyncForwardServices 里最多下发 {@link #MAX_REPAIRS_PER_ROUND} 个真实动作。
     *
     * <p>为什么需要：round46 让资源全空的节点也上报（{@code @emptyReport_supported}），这条哨兵路径
     * 能一次看到全部活跃 forward。节点配置被清空、库里又有大量活跃 forward 时，一轮就会连续下发
     * 几十上百个 AddService（真机 10 个、无速率控制），把节点 WS 通道与 gost 配置写入打满。
     *
     * <p>只统计真正下发动作：整对补齐、单协议补齐、变体重建（删旧名+建新名算一次）、暂停纠正
     * （清理旧名 / 补暂停）。「已经一致」不占预算，否则一致的服务越多、越修不动真正缺的。
     *
     * <p>预算只在单次调用内计数、不落库：下一轮 info_request（5 分钟）从零开始，剩余条目自然续上。
     */
    private static final class RepairBudget {
        private int used;
        private int repaired;
        private boolean denied;

        /** 申请一次下发额度；false 表示本轮已耗尽，调用方必须立刻停止本轮后续修复动作。 */
        boolean tryConsume() {
            if (used >= MAX_REPAIRS_PER_ROUND) {
                denied = true;
                return false;
            }
            used++;
            return true;
        }

        /** 节点回 OK 才算修好一条：失败的下发同样占额度，避免一轮反复砸同一个坏转发。 */
        void markRepaired() {
            repaired++;
        }

        int repairedCount() {
            return repaired;
        }

        /** 本轮已用额度：游标推进 Δ 用它，保证下一轮从断点继续。 */
        int used() {
            return used;
        }

        /** 本轮是否出现过「额度不够」。 */
        boolean blocked() {
            return denied;
        }
    }

    /**
     * 预算耗尽的收尾提示：N = 本轮真正修好的条数，M = 本轮还剩多少条 forward 没轮到
     * （当前这条 + 后面尚未进入循环的不同 forwardId，按 ports 顺序去重后计数）。
     */
    private void addBudgetExhaustedNote(List<ForwardPort> ports, int currentIndex,
                                        Set<Long> handledForwards, RepairBudget budget, List<String> notes) {
        Set<Long> pending = new LinkedHashSet<>();
        for (int i = Math.max(currentIndex, 0); i < ports.size(); i++) {
            pending.add(ports.get(i).getForwardId());
        }
        pending.removeAll(handledForwards);
        // 当前这条已进 handledForwards（重复端口行去重），但本次并没能修完，仍要算进剩余
        if (currentIndex >= 0 && currentIndex < ports.size()) {
            pending.add(ports.get(currentIndex).getForwardId());
        }
        notes.add("本轮已修复 " + budget.repairedCount() + " 条，其余 " + pending.size()
                + " 条下轮继续（单轮上限 " + MAX_REPAIRS_PER_ROUND + "）");
    }

    private void resyncForwardServices(Long nodeId, Node node, List<String> reportedServices, List<String> notes,
                                       Map<String, String> reportedDigests) {
        Set<String> present = new HashSet<>(reportedServices);
        List<ForwardPort> ports;
        try {
            ports = forwardPortService.list(new QueryWrapper<ForwardPort>().eq("node_id", nodeId));
        } catch (RuntimeException e) {
            notes.add("节点" + nodeId + " 端口记录查询失败：" + e.getMessage());
            return;
        }
        if (ports == null) {
            return;
        }
        // 轮转起点：上一轮预算耗尽的位置继续，别让固定头部的坏项每轮吃满额度（QA round47 WARN-4）
        int start = Math.floorMod(resyncCursors.getOrDefault(nodeId, 0), Math.max(1, ports.size()));
        List<ForwardPort> ordered = rotateFrom(ports, start);
        // 同一转发在同一节点可能有多条 forward_port 行（历史脏数据/迁移残留）：按转发去重，
        // 否则会对同一服务名重复 cleanupPausedVariant / enforcePausedService / 补齐，产生重复下发与重复 note
        Set<Long> handledForwards = new HashSet<>();
        // 空清单对账时节点可能仍在跑存量服务（gost 无配置热加载）：每个转发都会 bind 失败。
        // 连续两次端口占用就停手，别把整套转发各试一遍、把熔断器一次性刷满（QA round46 P1-2）。
        int bindFailureStreak = 0;
        // 节点整体卡死/通道拥堵时每次下发都会等超时（msg 为空）：逐个转发各等一次会把一轮对账拖到几十分钟，
        // 与端口占用一样需要连续两次就停手。
        int timeoutStreak = 0;
        // 单轮修复预算：只在本轮生效，下一轮 info_request 自动续上（见 RepairBudget）
        RepairBudget budget = new RepairBudget();
        for (int portIndex = 0; portIndex < ordered.size(); portIndex++) {
            ForwardPort forwardPort = ordered.get(portIndex);
            try {
                Forward forward = this.getById(forwardPort.getForwardId());
                if (forward == null) {
                    continue; // 悬空端口行归删除/清扫流程处理，这里不推断
                }
                if (!handledForwards.add(forward.getId())) {
                    continue; // 重复端口行只跳过，不打 warn：脏数据不该每轮刷日志
                }
                // status: 1 活跃、0 暂停；暂停的转发不应有在跑的服务，但可能残留旧名条目
                if (forward.getStatus() != null && forward.getStatus() != 1) {
                    // 暂停纠正与整对补齐共用同一预算：额度不够就立刻停手，不继续逐个判断
                    if (!cleanupPausedVariant(nodeId, forward, forwardPort, present, notes, budget)
                            || !enforcePausedService(nodeId, forward, present, reportedDigests, notes, budget)) {
                        addBudgetExhaustedNote(ordered, portIndex, handledForwards, budget, notes);
                        break;
                    }
                    continue;
                }
                Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
                if (tunnel == null) {
                    notes.add("转发" + forward.getId() + " 的隧道" + forward.getTunnelId() + " 不存在，未补齐");
                    continue;
                }
                UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
                String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
                boolean hasTcp = present.contains(serviceName + "_tcp");
                boolean hasUdp = present.contains(serviceName + "_udp");
                if (hasTcp && hasUdp) {
                    // 名字在清单里也不等于配置对：库里改过端口/remoteAddr、或节点侧被改过时，
                    // 服务名依然对得上，只比名字的规则永远发现不了，面板显示正常、实际转发到旧目标。
                    // 摘要不一致就删掉重发；返回 true 表示已按漂移处置，不要再走恢复逻辑。
                    if (reconcileServiceDigest(nodeId, node, serviceName,
                            userTunnel == null ? null : userTunnel.getSpeedId(), forward, forwardPort,
                            tunnel, reportedDigests, notes, budget)) {
                        if (budget.blocked()) {
                            addBudgetExhaustedNote(ordered, portIndex, handledForwards, budget, notes);
                            break;
                        }
                        continue;
                    }
                    // 名字在清单里不等于服务在跑：gost 的 pause 只释放注册名并把
                    // Metadata["paused"]=true 写进配置，进程重启后配置条目仍在，节点自报
                    // 清单照样带上它，于是"库里有、节点没有"这条补齐规则看不到这个软状态。
                    // ResumeService 对运行中的服务是空操作（resumeServices 里 skipped），
                    // 只有 paused 的才会真正重建，所以这里可以无条件补一发。
                    resumeReportedServices(nodeId, serviceName, notes);
                    continue;
                }
                Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();
                // 半残：名字对得上、但只剩一个协议。tcp 与 udp 端口空间独立，
                // 只补缺失的那一半不会与已有监听冲突，也不需要重建整个服务。
                if (hasTcp != hasUdp) {
                    String missing = hasTcp ? "udp" : "tcp";
                    String repairKey = "service|" + nodeId + "|" + serviceName;
                    if (!breakerAllows(repairKey, nodeId, "补齐缺失协议 " + serviceName + "_" + missing, notes)) {
                        continue;
                    }
                    if (!budget.tryConsume()) {
                        addBudgetExhaustedNote(ordered, portIndex, handledForwards, budget, notes);
                        break;
                    }
                    GostDto repair;
                    try {
                        repair = GostUtil.CreateSingleForwardService(serviceName, limiter, node, forward, forwardPort, tunnel, missing);
                    } catch (RuntimeException e) {
                        breakerResult(repairKey, false, null, e.getMessage());
                        notes.add("补齐缺失协议 " + serviceName + "_" + missing + "@node" + nodeId + " 异常：" + e.getMessage());
                        continue;
                    }
                    boolean repaired = repair != null && "OK".equals(repair.getMsg());
                    breakerResult(repairKey, repaired, null, repaired ? null : (repair == null ? "节点无响应" : repair.getMsg()));
                    if (repaired) {
                        bindFailureStreak = 0;
                        budget.markRepaired();
                        notes.add("已按库补齐缺失协议 " + serviceName + "_" + missing + "@node" + nodeId
                                + "（端口" + forwardPort.getPort() + "）");
                    } else {
                        String repairMsg = repair == null ? "节点无响应" : repair.getMsg();
                        notes.add("补齐缺失协议 " + serviceName + "_" + missing + "@node" + nodeId + " 失败：" + repairMsg);
                        bindFailureStreak = countBindFailure(bindFailureStreak, repairMsg);
                        timeoutStreak = countTimeoutStreak(timeoutStreak, repair);
                        if (bindFailureStreak >= 2) {
                            notes.add("连续" + bindFailureStreak + "次端口被占用导致补齐失败，已中断本轮对账"
                                    + "（节点上可能仍有服务在监听，空清单疑似不实）：" + repairMsg);
                            break;
                        }
                        if (timeoutStreak >= 2) {
                            notes.add("连续" + timeoutStreak + "次节点无响应，已中断本轮对账"
                                    + "（节点可能卡死或通道拥堵）：" + repairMsg);
                            break;
                        }
                    }
                    continue;
                }
                // 前缀相同但整个名字对不上：user_tunnel 行被删/重建导致名字从 f_u_5 变成 f_u_0。
                // 旧实现只上报不修复 —— 面板永久显示正常，节点上跑的却是旧名服务，业务随时会断在改名那一刻。
                // 旧变体与期望服务同属一个 forward（前缀含 forwardId_userId），端口与目标都取自同一行库记录，
                // 因此可以安全地「删旧名 → 按库建新名」；只动前缀匹配的条目，不碰同节点上别人的服务。
                String prefix = forward.getId() + "_" + forward.getUserId() + "_";
                List<String> stale = new ArrayList<>();
                for (String presentName : present) {
                    if (presentName != null && presentName.startsWith(prefix)
                            && !presentName.equals(serviceName + "_tcp") && !presentName.equals(serviceName + "_udp")) {
                        stale.add(presentName);
                    }
                }
                if (!stale.isEmpty()) {
                    Collections.sort(stale);
                    String variantKey = "service|" + nodeId + "|" + serviceName;
                    if (!breakerAllows(variantKey, nodeId, "重建服务名变体 " + String.join(",", stale), notes)) {
                        continue;
                    }
                    // 删旧名 + 建新名是同一次「变体重建」，只占一个额度
                    if (!budget.tryConsume()) {
                        addBudgetExhaustedNote(ordered, portIndex, handledForwards, budget, notes);
                        break;
                    }
                    com.alibaba.fastjson.JSONArray stalePayload = new com.alibaba.fastjson.JSONArray();
                    stalePayload.addAll(stale);
                    GostDto removed;
                    try {
                        removed = GostUtil.DeleteService(nodeId, stalePayload);
                    } catch (RuntimeException e) {
                        breakerResult(variantKey, false, null, e.getMessage());
                        notes.add("服务名变体清理异常（" + String.join(",", stale) + " → " + serviceName + "@node" + nodeId
                                + "）：" + e.getMessage() + "，未重建");
                        continue;
                    }
                    String removedMsg = removed == null || removed.getMsg() == null ? "节点无响应" : removed.getMsg();
                    if (!"OK".equals(removedMsg)) {
                        breakerResult(variantKey, false, null, removedMsg);
                        notes.add("服务名变体清理失败（" + String.join(",", stale) + " → " + serviceName + "@node" + nodeId
                                + "）：" + removedMsg + "，未重建");
                        continue;
                    }
                    GostDto rebuilt;
                    try {
                        rebuilt = GostUtil.CreateForwardService(serviceName, limiter, node, forward, forwardPort, tunnel);
                    } catch (RuntimeException e) {
                        breakerResult(variantKey, false, null, e.getMessage());
                        notes.add("服务名变体重建异常（" + serviceName + "@node" + nodeId + "）：" + e.getMessage());
                        continue;
                    }
                    String rebuiltMsg = rebuilt == null || rebuilt.getMsg() == null ? "节点无响应" : rebuilt.getMsg();
                    boolean rebuiltOk = "OK".equals(rebuiltMsg);
                    breakerResult(variantKey, rebuiltOk, null, rebuiltOk ? null : rebuiltMsg);
                    if (rebuiltOk) {
                        budget.markRepaired();
                        notes.add("已按库重建服务名变体 " + String.join(",", stale) + " → " + serviceName + "@node" + nodeId
                                + "（端口" + forwardPort.getPort() + "）");
                    } else {
                        notes.add("服务名变体重建失败（" + serviceName + "@node" + nodeId + "）：" + rebuiltMsg);
                    }
                    continue;
                }
                String missingKey = "service|" + nodeId + "|" + serviceName;
                if (!breakerAllows(missingKey, nodeId, "补齐缺失服务 " + serviceName, notes)) {
                    continue;
                }
                if (!budget.tryConsume()) {
                    addBudgetExhaustedNote(ordered, portIndex, handledForwards, budget, notes);
                    break;
                }
                GostDto gostDto = GostUtil.CreateForwardService(serviceName, limiter, node, forward, forwardPort, tunnel);
                String missingMsg = gostDto == null || gostDto.getMsg() == null ? "节点无响应" : gostDto.getMsg();
                boolean missingOk = "OK".equals(missingMsg);
                breakerResult(missingKey, missingOk, null, missingOk ? null : missingMsg);
                if (missingOk) {
                    bindFailureStreak = 0;
                    budget.markRepaired();
                    notes.add("已按库补齐缺失服务 " + serviceName + "@node" + nodeId + "（端口" + forwardPort.getPort() + "）");
                } else {
                    notes.add("补齐服务 " + serviceName + "@node" + nodeId + " 失败：" + missingMsg);
                    bindFailureStreak = countBindFailure(bindFailureStreak, missingMsg);
                    timeoutStreak = countTimeoutStreak(timeoutStreak, gostDto);
                    if (bindFailureStreak >= 2) {
                        notes.add("连续" + bindFailureStreak + "次端口被占用导致补齐失败，已中断本轮对账"
                                + "（节点上可能仍有服务在监听，空清单疑似不实）：" + missingMsg);
                        break;
                    }
                    if (timeoutStreak >= 2) {
                        notes.add("连续" + timeoutStreak + "次节点无响应，已中断本轮对账"
                                + "（节点可能卡死或通道拥堵）：" + missingMsg);
                        break;
                    }
                }
            } catch (RuntimeException e) {
                notes.add("补齐转发" + forwardPort.getForwardId() + "@node" + nodeId + " 异常：" + e.getMessage());
            }
        }
        // 游标推进「本轮真正用掉的额度」：耗满 30 时下一轮正好从第 31 条开始，
        // 一路轮转下去，任何一条都不会被固定头部永久挡住（QA round47 WARN-4）。
        if (!ordered.isEmpty()) {
            resyncCursors.put(nodeId, (start + Math.max(1, budget.used())) % ordered.size());
        }
    }

    /** 中继资源预算耗尽的收尾提示（去重，避免每条隧道各写一行）。 */
    private static void addChainBudgetNote(List<String> notes) {
        String note = "本轮中继资源修复已达上限 " + MAX_CHAIN_REPAIRS_PER_ROUND + "，其余隧道下轮继续";
        if (!notes.contains(note)) {
            notes.add(note);
        }
    }

    /** 从指定下标开始环形重排：只改遍历顺序，不动 DB 行序与去重语义。 */
    private static List<ForwardPort> rotateFrom(List<ForwardPort> rows, int start) {
        if (start <= 0 || rows.size() <= 1) {
            return rows;
        }
        List<ForwardPort> rotated = new ArrayList<>(rows.size());
        rotated.addAll(rows.subList(start, rows.size()));
        rotated.addAll(rows.subList(0, start));
        return rotated;
    }

    /**
     * 统一的下发前闸门：同一条资源（入口服务 / 中继服务 / 中继链）连续失败达到阈值后，
     * 冷却期内不再对节点做任何创建/删除，只把状态写进 notes 与 WARN。
     *
     * <p>补的缺口（QA 审计 P0-1）：熔断此前只管「摘要漂移 → 删除重建」这一条路径，
     * 而「服务被删后补齐失败」「半残单协议补齐失败」「中继链/中继服务缺失补齐失败」
     * 「链补齐后的级联重挂失败」全都绕过熔断。真机实测：把节点监听地址改成节点上不存在的
     * 192.0.2.10 后，每次节点重连都触发一次失败创建，抖动 4 次就重试 4 次，永不停。
     *
     * @param key   资源标识；与摘要漂移共用同一把 key，跨路径累计失败次数
     * @param what  日志与提示里的人类可读名称
     * @return true 允许本轮下发
     */
    private boolean breakerAllows(String key, Long nodeId, String what, List<String> notes) {
        if (driftBreaker.allowAttempt(key)) {
            return true;
        }
        int drifts = driftBreaker.consecutiveDrifts(key);
        long remainSeconds = (driftBreaker.remainingCooldownMs(key) + 999) / 1000;
        log.warn("[CIRCUIT_BREAKER_TRIPPED] 节点 {} {} 下发已熔断（连续 {} 次，剩余 {}s，key={}）",
                nodeId, what, drifts, remainSeconds, key);
        notes.add("节点" + nodeId + " " + what + " 下发已熔断（连续" + drifts + "次，剩余" + remainSeconds
                + "s），本轮未动节点");
        return false;
    }

    /** 一次下发的结果回填熔断器：成功清计数，失败累计并留存摘要。 */
    private void breakerResult(String key, boolean ok, String expected, String actual) {
        if (ok) {
            driftBreaker.recordMatch(key);
        } else {
            driftBreaker.recordDrift(key, expected, actual);
        }
    }

    /**
     * 名字对上了、配置却可能不对：按仓库里期望的配置算出摘要，与节点上报的摘要逐字比对，
     * 不一致就"删除后重发"整对 tcp/udp（AddService 对同名残留是整批失败，只能先删）。
     *
     * @param reportedDigests 节点上报的摘要；null 表示老节点没报，保持现状不动
     * @return true 表示检测到漂移并已处置（成功重建或失败仅记录），调用方不要再走恢复逻辑
     */
    private boolean reconcileServiceDigest(Long nodeId, Node node, String serviceName, Integer limiter,
                                           Forward forward, ForwardPort forwardPort, Tunnel tunnel,
                                           Map<String, String> reportedDigests, List<String> notes,
                                           RepairBudget budget) {
        if (reportedDigests == null) {
            return false;
        }
        // tcp 与 udp 各比一次：端口一致但 udp 侧监听地址/目标被改过时只看 tcp 会漏
        String driftProtocol = null;
        String expectedDigest = null;
        String actualDigest = null;
        // 转发策略（forwarder.selector.strategy）单独比：面板把 round 改成随机、节点上还是 round 时，
        // 端口与目标都没变，只比四段摘要永远发现不了。老节点不上报 @strategy 键 → 跳过本维度。
        // 节点上报的条目名带协议后缀（21_1_0_tcp），策略键因此是 21_1_0_tcp@strategy；
        // 只查 serviceName+"@strategy" 会永远取到 null，策略维度等于没接上（round38 回归用例抓到的）。
        String actualStrategy = reportedDigests.get(serviceName + "_tcp@strategy");
        if (actualStrategy == null) {
            actualStrategy = reportedDigests.get(serviceName + "_udp@strategy");
        }
        if (actualStrategy != null) {
            String expectedStrategy = expectedForwardStrategy(forward);
            if (!expectedStrategy.equals(actualStrategy)) {
                driftProtocol = "forwarder";
                expectedDigest = "strategy=" + expectedStrategy;
                actualDigest = "strategy=" + actualStrategy;
            }
        }
        // 转发策略档位（forwarder.selector.maxFails / failTimeout）：strategy 相同、这两个值被改过时，
        // 上面的 strategy 维度看不出来，四段摘要更是永远一致。哨兵不存在（老节点）→ 整维度跳过。
        // 只查 serviceName+"@fselector" 会永远取到 null（上报条目名带协议后缀），必须按协议后缀取键。
        if (driftProtocol == null && reportedDigests.containsKey(FSELECTOR_SUPPORTED_KEY)) {
            String expected = expectedForwardSelector(forward);
            for (String protocol : new String[]{"tcp", "udp"}) {
                if (!reportedDigests.containsKey(serviceName + "_" + protocol)) {
                    continue; // 节点上没这条协议的入口服务，不当成漂移（半残/缺失由别的分支补）
                }
                String actual = lowerOrNull(reportedDigests.get(serviceName + "_" + protocol + "@fselector"));
                if (!expected.equals(actual)) {
                    driftProtocol = "fselector";
                    expectedDigest = "selector=" + expected;
                    actualDigest = "selector=" + orDash(actual);
                    break;
                }
            }
        }
        // 监听器类型（listener.type）：端口与目标都没变、四段摘要也一致时，面板把 tcp 改成 tls、
        // 或节点上 udp 的 keepAlive 丢了，只比摘要永远发现不了。哨兵不存在（老节点）→ 整维度跳过。
        if (driftProtocol == null && reportedDigests.containsKey(LISTENER_SUPPORTED_KEY)) {
            for (String protocol : new String[]{"tcp", "udp"}) {
                if (!reportedDigests.containsKey(serviceName + "_" + protocol)) {
                    continue; // 节点上没这条协议的入口服务，不当成漂移（半残/缺失由别的分支补）
                }
                String expected = expectedListenerDigest(protocol);
                String actual = lowerOrNull(reportedDigests.get(serviceName + "_" + protocol + "@listener"));
                if (!Objects.equals(expected, actual)) {
                    driftProtocol = "listener";
                    expectedDigest = "listener=" + expected;
                    actualDigest = "listener=" + orDash(actual);
                    break;
                }
            }
        }
        // 出站网卡（metadata.interface）：只在端口转发（tunnel.type=1）且节点配了网卡时才该有。
        // 库里有、节点上没有，或节点上挂着库里已清掉的网卡，都是同一类漂移，必须整对删建才会生效。
        if (driftProtocol == null && reportedDigests.containsKey(IFACE_SUPPORTED_KEY)) {
            String expected = expectedServiceInterface(node, tunnel);
            for (String protocol : new String[]{"tcp", "udp"}) {
                if (!reportedDigests.containsKey(serviceName + "_" + protocol)) {
                    continue; // 同上：该协议的服务不存在时谈不到网卡漂移
                }
                String actual = reportedDigests.get(serviceName + "_" + protocol + "@iface");
                if (expected == null && actual != null && tunnel.getType() != null && tunnel.getType() != 1) {
                    // 隧道转发（type=2）的入口服务本就不下发 metadata.interface；节点上残留这份网卡不影响转发，
                    // 按漂移删建只会每轮掐断业务，故只记录不处置。
                    log.info("节点 {} 服务 {} 挂着库中未配置的出站网卡 {}（tunnel.type={}），仅记录不重建",
                            nodeId, serviceName + "_" + protocol, actual, tunnel.getType());
                    continue;
                }
                if (!Objects.equals(expected, actual)) {
                    driftProtocol = "iface";
                    expectedDigest = "iface=" + orDash(expected);
                    actualDigest = "iface=" + orDash(actual);
                    break;
                }
            }
        }
        if (driftProtocol == null) {
            for (String protocol : new String[]{"tcp", "udp"}) {
                String actual = reportedDigests.get(serviceName + "_" + protocol);
                if (actual == null) {
                    continue; // 该协议这次没报（或本就不存在），跳过
                }
                String expected = expectedServiceDigest(node, forward, forwardPort, tunnel, protocol);
                if (!expected.equals(actual)) {
                    driftProtocol = protocol;
                    expectedDigest = expected;
                    actualDigest = actual;
                    break;
                }
            }
        }
        String driftKey = "service|" + nodeId + "|" + serviceName;
        if (driftProtocol == null) {
            // 两侧摘要对上了：清掉这条资源的漂移计数，条目随 recordMatch 移除，不会堆积
            driftBreaker.recordMatch(driftKey);
            return false;
        }
        if (!driftBreaker.allowAttempt(driftKey)) {
            // 同样的摘要已经连续删建过好几轮，说明存在对不上的口径而不是节点上的一次性偏差：
            // 继续删只会反复掐断业务长连接，这里只记录，等冷却到期再放行一次探测
            int drifts = driftBreaker.consecutiveDrifts(driftKey);
            long remainSeconds = (driftBreaker.remainingCooldownMs(driftKey) + 999) / 1000;
            log.warn("节点 {} {} 配置漂移重建已熔断（连续 {} 次，剩余 {}s）：库 {} / 节点 {}",
                    nodeId, serviceName, drifts, remainSeconds, expectedDigest, actualDigest);
            notes.add("节点" + nodeId + " 服务" + serviceName + " 漂移重建已熔断（连续" + drifts
                    + "次，剩余" + remainSeconds + "s），配置未自动修复：库 " + expectedDigest
                    + " / 节点 " + actualDigest);
            return true; // 已处置：调用方不要再走 ResumeService
        }
        // 真正删建前先占用单轮预算：额度不够就本轮不动节点，交下一轮（调用方据 blocked() 收尾）
        if (!budget.tryConsume()) {
            log.info("节点 {} 服务 {} 配置漂移，单轮修复预算已用完，延后到下一轮", nodeId, serviceName);
            return true;
        }
        // 真正删建前先计数：删除失败/重建失败时计数留在原地，冷却同样生效
        driftBreaker.recordDrift(driftKey, expectedDigest, actualDigest);
        JSONArray names = new JSONArray();
        names.add(serviceName + "_tcp");
        names.add(serviceName + "_udp");
        GostDto deleted;
        try {
            deleted = GostUtil.DeleteService(nodeId, names);
        } catch (RuntimeException e) {
            log.warn("节点 {} 服务 {} 配置漂移，删除异常：{}", nodeId, serviceName, e.getMessage());
            notes.add("节点" + nodeId + " 服务" + serviceName + " 配置漂移，删除异常：" + e.getMessage());
            return true;
        }
        if (deleted == null || !"OK".equals(deleted.getMsg())) {
            String reason = deleted == null ? "节点无响应" : deleted.getMsg();
            log.warn("节点 {} 服务 {} 配置漂移，删除失败：{}", nodeId, serviceName, reason);
            notes.add("节点" + nodeId + " 服务" + serviceName + " 配置漂移，删除失败：" + reason);
            return true;
        }
        GostDto rebuilt;
        try {
            rebuilt = GostUtil.CreateForwardService(serviceName, limiter, node, forward, forwardPort, tunnel);
        } catch (RuntimeException e) {
            log.warn("已删除漂移服务 {}@node{} 但重建失败：{}", serviceName, nodeId, e.getMessage());
            notes.add("已删除漂移服务 " + serviceName + "@node" + nodeId + " 但重建失败：" + e.getMessage());
            return true;
        }
        if (rebuilt != null && "OK".equals(rebuilt.getMsg())) {
            budget.markRepaired();
            log.warn("节点 {} 服务重建：{}_{} 配置漂移（库 {} / 节点 {}）",
                    nodeId, serviceName, driftProtocol, expectedDigest, actualDigest);
            notes.add("节点" + nodeId + " 服务重建：" + serviceName + "_" + driftProtocol
                    + " 配置漂移（库 " + expectedDigest + " / 节点 " + actualDigest + "）");
        } else {
            String reason = rebuilt == null ? "节点无响应" : rebuilt.getMsg();
            log.warn("已删除漂移服务 {}@node{} 但重建失败：{}", serviceName, nodeId, reason);
            notes.add("已删除漂移服务 " + serviceName + "@node" + nodeId + " 但重建失败：" + reason);
        }
        return true;
    }

    /**
     * 按库算这条服务在节点上应该有的摘要，口径与 GostUtil.buildForwardService 下发的一致：
     * 监听地址取该协议的 tcpListenAddr/udpListenAddr + forwardPort、handler 取协议名、
     * 隧道型转发挂 chains_&lt;tid&gt;、目标取 remoteAddr 全部（按逗号顺序，与下发同一顺序）。
     *
     * <p>监听地址必须带上：库里把 0.0.0.0 改成 127.0.0.1（或反之）时端口不变，
     * 只看端口会永远判一致；通配写法（空/0.0.0.0/::/*）在 GostUtil.digestListen 里归一成"只留端口"，
     * 所以老数据不会因为写 0.0.0.0 还是 [::] 而抖动。
     *
     * <p>目标是全部而不是第一条：createForwarder 按逗号拆分下发全部节点，节点也全量上报，
     * 只比第一条会让"删掉第二个目标"这种漂移静默存活。
     */
    /**
     * 期望的 forwarder 策略，与 {@link GostUtil#buildForwardService} 下发口径一致：
     * 库里为空（历史数据 / 新建时没填）时 GostUtil.createForwarder 会兜底成 {@code fifo}，
     * 节点上报时已转小写，这里同样归一，避免 DB 里写的 "FIFO" 被误判成漂移。
     */
    private static String expectedForwardStrategy(Forward forward) {
        String strategy = forward == null ? null : forward.getStrategy();
        if (strategy == null || strategy.trim().isEmpty()) {
            return "fifo";
        }
        return strategy.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 期望的 forwarder.selector 完整摘要 {@code <strategy>|<maxFails>|<failTimeoutNanos>}，
     * 与 {@link GostUtil#createForwarder} 的下发口径逐字一致：strategy 空白兜底 fifo（小写），
     * maxFails 恒 1、failTimeout 恒 600s = 600000000000ns。节点侧同样转小写后上报。
     */
    private static String expectedForwardSelector(Forward forward) {
        return expectedForwardStrategy(forward) + "|" + FSELECTOR_FIXED_TAIL;
    }

    /**
     * 期望的 listener 摘要，与 {@link GostUtil#createListener} 的下发口径一致：
     * 入口服务的 listener.type 就是协议名，udp 额外带 {@code metadata.keepAlive=true}
     * （节点侧压成 {@code udp|keepalive=true}，见 go-gost websocket_reporter.go:1076-1078）。
     *
     * <p>保持全小写：节点侧摘要统一 {@code toLowerCase}（WebSocketServer.toDigestMap），
     * 而 gost 落盘键本身就是小写 {@code keepalive}，写死驼峰会每轮判漂移、反复删建（真机已踩）。
     */
    private static String expectedListenerDigest(String protocol) {
        String type = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
        if ("udp".equals(type)) {
            return "udp|keepalive=true";
        }
        return type;
    }

    /**
     * 期望的出站网卡（入口服务 metadata.interface），与 {@link GostUtil#buildForwardService} 同一条件：
     * 只有端口转发（tunnel.type=1）才下发 interface，隧道转发时网卡挂在链的 hop 上。
     *
     * @return null 表示这条服务在节点上本就不该有 iface
     */
    private static String expectedServiceInterface(Node node, Tunnel tunnel) {
        if (node == null || tunnel == null || !Integer.valueOf(1).equals(tunnel.getType())) {
            return null;
        }
        String iface = node.getInterfaceName();
        return iface == null || iface.trim().isEmpty() ? null : iface.trim();
    }

    private static String lowerOrNull(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 漂移说明里 null 一律写 "-"（与链摘要的节点侧口径一致）。 */
    private static String orDash(String value) {
        return value == null ? "-" : value;
    }

    private static String expectedServiceDigest(Node node, Forward forward, ForwardPort forwardPort, Tunnel tunnel,
                                                String protocol) {
        String chain = null;
        if (tunnel != null && Integer.valueOf(2).equals(tunnel.getType())) {
            chain = "chains_" + forward.getTunnelId();
        }
        String listenAddr = String.valueOf(forwardPort.getPort());
        if (node != null) {
            String host = "udp".equals(protocol) ? node.getUdpListenAddr() : node.getTcpListenAddr();
            listenAddr = GostUtil.canonicalListenAddr(host) + ":" + forwardPort.getPort();
        }
        List<String> targets = new ArrayList<>();
        String remoteAddr = forward.getRemoteAddr();
        if (remoteAddr != null) {
            for (String addr : remoteAddr.split(",")) {
                if (addr != null && !addr.trim().isEmpty()) {
                    targets.add(addr.trim());
                }
            }
        }
        return GostUtil.normalizeServiceDigestList(listenAddr, protocol, chain, targets);
    }

    /** 本节点在一条隧道里要维持的中继资源：chains 的下一跳、以及 <tid>_tls 对应的 chain_tunnel 行。 */
    private static final class NodeChainPlan {
        private List<ChainTunnel> next;
        private ChainTunnel serviceRow;
        private String conflict;
    }

    /**
     * 补齐本节点缺失的中继链与中继服务。
     *
     * <p>一次上报里只查三批数据（活跃转发、chain_tunnel、节点）：逐条隧道查库会把
     * WS 上报线程拖成 N 次往返，节点上报频率一高就是成倍的库压力。
     */
    private void resyncChainResources(Long nodeId, Node node, List<String> reportedServices,
                                      List<String> reportedChains, Map<String, String> reportedDigests,
                                      Map<String, List<String>> reportedChainDigests, List<String> notes) {
        Set<String> presentChains = new HashSet<>(reportedChains);
        Set<String> presentServices = new HashSet<>(reportedServices);

        List<Forward> actives;
        try {
            actives = this.list(new QueryWrapper<Forward>().eq("status", 1));
        } catch (RuntimeException e) {
            notes.add("节点" + nodeId + " 活跃转发查询失败，跳过中继链补齐：" + e.getMessage());
            return;
        }
        // 链资源的归属判定不能只钉在「活跃转发」上：一条隧道的转发全部暂停时，节点上的
        // chains_<tid> 与 <tid>_tls 依然占着端口（暂停只下掉入口服务 f_u_ut），而恢复路径
        // （rebuildMissingForwardService）只重建入口服务、不补链。节点重装 / 清盘后这条隧道
        // 一恢复就会引用不存在的链：gost 的 chainGroup 解析不到链时静默直连 target 裸奔。
        // 所以候选集取「仍被任一转发引用（不限状态）」，再用 tunnel 行过滤掉已删隧道 ——
        // 已删隧道补出来的中继资源是死配置，交给 NodeOrphanSweeper 清理。
        List<Forward> referencing;
        try {
            referencing = this.list(new QueryWrapper<Forward>().select("tunnel_id"));
        } catch (RuntimeException e) {
            notes.add("节点" + nodeId + " 转发隧道引用查询失败，跳过中继链补齐：" + e.getMessage());
            return;
        }
        // forward.tunnel_id 是 Integer，chain_tunnel.tunnel_id 是 Long：统一成 Long 才能和拓扑行对上
        Set<Long> candidateTunnelIds = referencing == null ? new HashSet<>() : referencing.stream()
                .map(Forward::getTunnelId)
                .filter(Objects::nonNull)
                .map(Integer::longValue)
                .collect(Collectors.toCollection(HashSet::new));
        if (candidateTunnelIds.isEmpty()) {
            return; // 没有任何转发引用隧道，补出来的中继资源只会是死配置
        }

        List<ChainTunnel> rows;
        try {
            rows = chainTunnelService.list(new QueryWrapper<ChainTunnel>().in("tunnel_id", candidateTunnelIds));
        } catch (RuntimeException e) {
            notes.add("节点" + nodeId + " 隧道拓扑查询失败，跳过中继链补齐：" + e.getMessage());
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 行序必须由 (chain_type, inx, id) 定：这里是 in(tunnel_id) 批量查，发布路径是 eq(tunnel_id)
        // 单条查，两次查询的行序本来就不保证一致，组内顺序一变，摘要每轮判漂移、链被反复删建
        rows = sortChainRows(rows);

        Map<Long, Node> nodes;
        try {
            nodes = loadChainNodes(nodeId, node, rows);
        } catch (RuntimeException e) {
            notes.add("节点" + nodeId + " 中继节点信息查询失败，跳过中继链补齐：" + e.getMessage());
            return;
        }

        Map<Long, List<ChainTunnel>> byTunnel = rows.stream()
                .filter(row -> row.getTunnelId() != null)
                .collect(Collectors.groupingBy(ChainTunnel::getTunnelId, LinkedHashMap::new, Collectors.toList()));

        // 中继服务 <tid>_tls 同样监听本地端口：空清单时存量监听起来后逐个补都会 bind 冲突，
        // 与入口服务同一口径，连续两次就中断本轮（QA round46 P1）。
        int relayBindStreak = 0;
        // 中继资源独立预算：与入口服务的 30 条分开计数，链先于服务、互不饿死（QA round47 WARN-3）
        int chainRepairs = 0;
        for (Map.Entry<Long, List<ChainTunnel>> entry : byTunnel.entrySet()) {
            Long tunnelId = entry.getKey();
            String chainName = "chains_" + tunnelId;
            String serviceName = tunnelId + "_tls";
            // 单条隧道出错只记描述，不打断其余隧道的补齐
            try {
                // 转发行还在、隧道行已被删：补出来的 chains_<tid>/<tid>_tls 是死配置，
                // 交给 NodeOrphanSweeper 统一回收，不在这里复活。
                Tunnel tunnel = tunnelService.getById(tunnelId);
                if (tunnel == null) {
                    continue;
                }
                // 直连隧道没有中继链：<tid>_tls 在这类隧道上既不该存在，补出来只会白占端口，
                // 并与 NodeOrphanSweeper（type!=2 判孤儿删除）形成心跳级删建拉锯（round44 QA 裁决）。
                if (!Integer.valueOf(2).equals(tunnel.getType())) {
                    continue;
                }
                NodeChainPlan plan = planChainForNode(nodeId, entry.getValue());
                if (plan == null) {
                    continue; // 这条隧道不经过本节点
                }
                if (plan.conflict != null) {
                    notes.add("隧道" + tunnelId + "@node" + nodeId + " " + plan.conflict + "，未自动修复");
                    continue;
                }
                boolean chainRepaired = false;
                if (plan.next != null && !plan.next.isEmpty() && !presentChains.contains(chainName)) {
                    String missingChainKey = "chain|" + nodeId + "|" + chainName;
                    if (!breakerAllows(missingChainKey, nodeId, "补齐中继链 " + chainName, notes)) {
                        continue;
                    }
                    if (chainRepairs >= MAX_CHAIN_REPAIRS_PER_ROUND) {
                        addChainBudgetNote(notes);
                        break;
                    }
                    chainRepairs++;
                    boolean chainOk = repairChain(nodeId, tunnelId, plan.next, nodes, notes, "补齐缺失", tunnel);
                    breakerResult(missingChainKey, chainOk, null, null);
                    if (!chainOk) {
                        continue; // 链没接通，先别给这条链挂监听
                    }
                    chainRepaired = true;
                } else if (plan.next != null && !plan.next.isEmpty()) {
                    // 链在、名字对，但下一跳被改过：只比名字永远发现不了，流量会打到不存在的跳点
                    // 期望值单独算一次：算不出来（库内跳点端口非法／节点信息缺失）时，
                    // chainDrift 返回的 null 既不是「一致」也不是「漂移」，不能拿它清熔断计数
                    List<String> expectedChain = expectedChainNodes(plan.next, nodes);
                    String drift = chainDrift(nodeId, plan.next, nodes, expectedChain, chainName, reportedChainDigests);
                    String driftKey = "chain|" + nodeId + "|" + chainName;
                    if (drift == null) {
                        // 只有节点这一轮确实报了这条链的摘要、且判为一致，才清计数：
                        // 「没报摘要」不是一致（chainDrift 对缺失一律返回 null），不能拿来清熔断
                        if (reportedChainDigests != null && reportedChainDigests.containsKey(chainName)) {
                            if (expectedChain == null) {
                                // 脏库行造成的黑洞：既修不了也不该被当作一致（round45 QA P1-4）
                                log.warn("节点 {} 中继链 {} 期望值不可算，跳过漂移判定且不清熔断计数（需人工修库）",
                                        nodeId, chainName);
                                notes.add("中继链 " + chainName + "@node" + nodeId
                                        + " 库内跳点数据不全或端口非法，跳过漂移判定（需人工修库），未清熔断计数");
                            } else {
                                driftBreaker.recordMatch(driftKey);
                            }
                        }
                    } else if (!driftBreaker.allowAttempt(driftKey)) {
                        int drifts = driftBreaker.consecutiveDrifts(driftKey);
                        long remainSeconds = (driftBreaker.remainingCooldownMs(driftKey) + 999) / 1000;
                        log.warn("节点 {} {} 配置漂移重建已熔断（连续 {} 次，剩余 {}s）：{}",
                                nodeId, chainName, drifts, remainSeconds, drift);
                        notes.add("中继链 " + chainName + "@node" + nodeId + " 漂移重建已熔断（连续" + drifts
                                + "次，剩余" + remainSeconds + "s），未自动修复：" + drift);
                        continue; // 本条链本轮不动，其余隧道照常处理
                    } else {
                        if (chainRepairs >= MAX_CHAIN_REPAIRS_PER_ROUND) {
                            addChainBudgetNote(notes);
                            break;
                        }
                        chainRepairs++;
                        // 期望串随漂移内容变化：换了一处跳点/策略就该从 1 重新计数，同一处反复失败才累计
                        driftBreaker.recordDrift(driftKey, drift, drift);
                        notes.add(drift);
                        GostDto del = GostUtil.DeleteChains(nodeId, chainName);
                        String delMsg = del == null || del.getMsg() == null ? "节点无响应" : del.getMsg();
                        if (!"OK".equals(delMsg) && !delMsg.contains("not found")) {
                            notes.add("中继链 " + chainName + "@node" + nodeId + " 漂移重建前删除失败：" + delMsg + "，未重建");
                            continue;
                        }
                        if (!repairChain(nodeId, tunnelId, plan.next, nodes, notes, "重建漂移", tunnel)) {
                            continue; // 链没接通，先别给这条链挂监听
                        }
                        chainRepaired = true;
                    }
                }
                ChainTunnel serviceRow = plan.serviceRow;
                // 链是本次才补上的：节点上早已存在的服务是按「chain 查不到」的配方注册的，
                // gost 在服务 Init 阶段就把 chain 解析成 nil 并让 handler 直连 target，
                // 只补链不会回溯修改已注册的服务，必须删掉重发，流量才会真正走进中继链。
                if (chainRepaired) {
                    if (chainRepairs >= MAX_CHAIN_REPAIRS_PER_ROUND) {
                        addChainBudgetNote(notes);
                        break;
                    }
                    chainRepairs++;
                    rebindServicesForChain(nodeId, node, tunnelId, serviceRow, presentServices, actives, nodes, notes);
                }
                if (!chainRepaired && serviceRow != null && presentServices.contains(serviceName)) {
                    // 只有真的判出漂移并下发才算额度：一致的中继服务每轮都过一遍，不能把预算吃空
                    if (chainRepairs >= MAX_CHAIN_REPAIRS_PER_ROUND) {
                        addChainBudgetNote(notes);
                        break;
                    }
                    // 中继服务 <tid>_tls 的配置漂移：名字在清单里不等于它监听的端口/挂的链没被改过。
                    // relay handler 没有 forwarder，摘要里 target 恒为 "-"，只能靠端口与 chain 判。
                    if (reconcileChainServiceDigest(nodeId, serviceRow, nodes, tunnel, reportedDigests, notes)) {
                        chainRepairs++;
                    }
                    continue; // 名字已在，后面的「补缺失」分支本来就无事可做
                }
                if (serviceRow == null || presentServices.contains(serviceName)) {
                    continue; // 清单里已有就不重发：同名重发会 bind 冲突，整批 AddService 都会失败
                }
                if (serviceRow.getPort() == null) {
                    notes.add("中继服务 " + serviceName + "@node" + nodeId + " 缺少端口记录，未自动修复");
                    continue;
                }
                if (!nodes.containsKey(serviceRow.getNodeId())) {
                    notes.add("中继服务 " + serviceName + "@node" + nodeId + " 补齐失败：节点数据缺失");
                    continue;
                }
                String missingRelayKey = "relay|" + nodeId + "|" + serviceName;
                if (!breakerAllows(missingRelayKey, nodeId, "补齐中继服务 " + serviceName, notes)) {
                    continue;
                }
                if (chainRepairs >= MAX_CHAIN_REPAIRS_PER_ROUND) {
                    addChainBudgetNote(notes);
                    break;
                }
                chainRepairs++;
                GostDto gostDto = GostUtil.CreateChainService(nodeId, serviceRow, nodes, tunnel);
                String msg = gostDto == null || gostDto.getMsg() == null ? "节点无响应" : gostDto.getMsg();
                breakerResult(missingRelayKey, "OK".equals(msg), null, "OK".equals(msg) ? null : msg);
                if ("OK".equals(msg)) {
                    notes.add("已按库补齐缺失中继服务 " + serviceName + "@node" + nodeId + "（端口" + serviceRow.getPort() + "）");
                } else if (msg.contains("exists")) {
                    // exists 只说明同名资源在节点上，配置是否与库里一致无法校验：既不覆盖也不删除
                    notes.add("节点" + nodeId + " 已有 " + serviceName + " 但配置未知，未自动修复");
                } else {
                    notes.add("中继服务 " + serviceName + "@node" + nodeId + " 补齐失败：" + msg);
                    relayBindStreak = countBindFailure(relayBindStreak, msg);
                    if (relayBindStreak >= 2) {
                        notes.add("连续" + relayBindStreak + "次中继服务端口被占用，已中断本轮中继补齐"
                                + "（节点上可能仍有中继服务在监听，空清单疑似不实）：" + msg);
                        break;
                    }
                }
            } catch (RuntimeException e) {
                notes.add("隧道" + tunnelId + "@node" + nodeId + " 中继补齐异常：" + e.getMessage());
            }
        }
    }

    /**
     * 链刚补上时，重建本节点上引用这条链的既有服务。
     *
     * <p>gost 的 chain 只在服务 Init 时解析一次：
     * go-gost/x/config/parsing/service/parse.go:471-497 的 chainGroup() 在注册表里查不到
     * 对应链时直接 return null，:324 又原样把 null 交给 chain.ChainRouterOption()，
     * 于是转发 handler 走 Router.Dial 直连 target，既不报错也不打日志。
     * 因此「补链」本身不会改变已注册服务的走向，必须删掉重发：
     * 入口服务用 CreateForwardService，链节点上的 &lt;tid&gt;_tls 用 CreateChainService。
     *
     * <p>删除失败一律不重发（同名 exists 会让整批 AddService 失败），只记说明交人工。
     */
    private void rebindServicesForChain(Long nodeId, Node node, Long tunnelId, ChainTunnel serviceRow,
                                        Set<String> presentServices, List<Forward> actives,
                                        Map<Long, Node> nodes, List<String> notes) {
        // 链刚补上就重挂：节点若仍占着端口（空清单不实/存量监听起来），每个服务都会 bind 冲突，
        // 连续两次就停手，别把整条隧道下的转发挨个试一遍（QA round46 P1）。
        int rebindBindStreak = 0;
        for (Forward forward : actives) {
            if (forward.getTunnelId() == null || forward.getTunnelId().longValue() != tunnelId.longValue()) {
                continue;
            }
            try {
                ForwardPort forwardPort = forwardPortService.getOne(new QueryWrapper<ForwardPort>()
                        .eq("forward_id", forward.getId()).eq("node_id", nodeId).last("limit 1"));
                if (forwardPort == null || forwardPort.getPort() == null) {
                    continue; // 这条转发不在本节点入口
                }
                UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnelId.intValue());
                String name = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
                if (!presentServices.contains(name + "_tcp") && !presentServices.contains(name + "_udp")) {
                    continue; // 服务本身也缺，交给 resyncForwardServices 补
                }
                Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
                if (tunnel == null) {
                    notes.add("重挂链服务 " + name + "@node" + nodeId + " 失败：隧道" + tunnelId + " 不存在");
                    continue;
                }
                String rebindKey = "service|" + nodeId + "|" + name;
                if (!breakerAllows(rebindKey, nodeId, "重挂入口服务 " + name, notes)) {
                    continue;
                }
                if (!deleteServices(nodeId, name, presentServices, notes)) {
                    breakerResult(rebindKey, false, null, "删除失败，未重发");
                    continue;
                }
                Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();
                GostDto dto = GostUtil.CreateForwardService(name, limiter, node, forward, forwardPort, tunnel);
                String msg = dto == null || dto.getMsg() == null ? "节点无响应" : dto.getMsg();
                breakerResult(rebindKey, "OK".equals(msg), null, "OK".equals(msg) ? null : msg);
                if ("OK".equals(msg)) {
                    notes.add("中继链补齐后已重挂入口服务 " + name + "@node" + nodeId + "（端口" + forwardPort.getPort() + "）");
                } else {
                    presentServices.remove(name + "_tcp");
                    presentServices.remove(name + "_udp");
                    notes.add("中继链补齐后重挂入口服务 " + name + "@node" + nodeId + " 失败：" + msg);
                    rebindBindStreak = countBindFailure(rebindBindStreak, msg);
                    if (rebindBindStreak >= 2) {
                        notes.add("连续" + rebindBindStreak + "次重挂端口被占用，已中断本轮重挂"
                                + "（节点上可能仍有服务在监听，空清单疑似不实）：" + msg);
                        return;
                    }
                }
            } catch (RuntimeException e) {
                notes.add("中继链补齐后重挂转发" + forward.getId() + "@node" + nodeId + " 异常：" + e.getMessage());
            }
        }
        // 链节点上的中继服务 <tid>_tls 也把链写进了 handler，必须一并重挂
        if (serviceRow != null && Objects.equals(serviceRow.getChainType(), 2)
                && presentServices.contains(tunnelId + "_tls")) {
            String reRebindKey = "relay|" + nodeId + "|" + tunnelId + "_tls";
            if (!breakerAllows(reRebindKey, nodeId, "重挂中继服务 " + tunnelId + "_tls", notes)) {
                return;
            }
            JSONArray names = new JSONArray();
            names.add(tunnelId + "_tls");
            GostDto del = GostUtil.DeleteService(nodeId, names);
            String delMsg = del == null || del.getMsg() == null ? "节点无响应" : del.getMsg();
            if (!"OK".equals(delMsg)) {
                breakerResult(reRebindKey, false, null, delMsg);
                notes.add("重挂中继服务 " + tunnelId + "_tls@node" + nodeId + " 前删除失败：" + delMsg + "，未重发");
                return;
            }
            Tunnel relayTunnel = tunnelService.getById(tunnelId);
            if (relayTunnel == null) {
                notes.add("重挂中继服务 " + tunnelId + "_tls@node" + nodeId + " 跳过：隧道" + tunnelId + " 不存在");
                return;
            }
            GostDto dto = GostUtil.CreateChainService(nodeId, serviceRow, nodes, relayTunnel);
            String msg = dto == null || dto.getMsg() == null ? "节点无响应" : dto.getMsg();
            breakerResult(reRebindKey, "OK".equals(msg), null, "OK".equals(msg) ? null : msg);
            if ("OK".equals(msg)) {
                notes.add("中继链补齐后已重挂中继服务 " + tunnelId + "_tls@node" + nodeId + "（端口" + serviceRow.getPort() + "）");
            } else {
                presentServices.remove(tunnelId + "_tls");
                notes.add("中继链补齐后重挂中继服务 " + tunnelId + "_tls@node" + nodeId + " 失败：" + msg);
            }
        }
    }

    /**
     * 中继服务 &lt;tid&gt;_tls 的配置漂移对账（round35）。
     *
     * <p>此前只有入口转发服务比摘要，中继服务只比名字：节点上的 &lt;tid&gt;_tls 被改成别的端口、
     * 或挂错链时，链名和服务名都对得上，流量却在链的下一跳处断掉，面板显示一切正常。
     * 期望值按 {@link GostUtil#CreateChainService} 的下发口径算：端口取 chain_tunnel.port、
     * handler 恒为 relay、只有链节点（chainType=2）才挂 chains_&lt;tid&gt;，出口(3)与入口(1)不挂链。
     *
     * <p>宁可不动也不能乱删：摘要缺失（老节点 / 本次没报）、端口记录缺失，一律只记录不重建。
     */
    private boolean reconcileChainServiceDigest(Long nodeId, ChainTunnel serviceRow, Map<Long, Node> nodes,
                                                Tunnel tunnel,
                                                Map<String, String> reportedDigests, List<String> notes) {
        if (reportedDigests == null || serviceRow == null || serviceRow.getTunnelId() == null) {
            return false;
        }
        String serviceName = serviceRow.getTunnelId() + "_tls";
        // 同一条中继服务在节点上的三段摘要：整段配置（端口/handler/chain）、listener 类型、出站网卡
        String actual = reportedDigests.get(serviceName);
        String actualListener = lowerOrNull(reportedDigests.get(serviceName + "@listener"));
        String actualIface = reportedDigests.get(serviceName + "@iface");
        if (actual == null && actualListener == null && actualIface == null) {
            return false; // 本次上报没带这条服务，维持原行为
        }
        if (serviceRow.getPort() == null) {
            notes.add("中继服务 " + serviceName + "@node" + nodeId + " 缺少端口记录，未自动修复");
            return false;
        }
        String chain = Objects.equals(serviceRow.getChainType(), 2) ? "chains_" + serviceRow.getTunnelId() : null;
        // 监听地址取承载这条中继服务的节点（与 GostUtil.CreateChainService 下发 addr 同一取法）；
        // 期望值只写端口而节点报 addr 时，节点一旦配了具体监听地址就会每轮都判漂移、反复重建
        Long holderId = serviceRow.getNodeId() == null ? nodeId : serviceRow.getNodeId();
        Node holder = nodes.get(holderId);
        String listenAddr = GostUtil.canonicalListenAddr(holder == null ? null : holder.getTcpListenAddr())
                + ":" + serviceRow.getPort();
        String expected = GostUtil.normalizeServiceDigestList(listenAddr, "relay", chain, null);
        // 中继服务同型熔断：key 带 relay 前缀，与入口服务、与链各自独立计数
        String driftKey = "relay|" + nodeId + "|" + serviceName;
        // 三个维度共用同一套「删 + 建」处置，逐项比对、只留第一处不一致：整段配置 → listener → iface。
        // 删建粒度是整条服务，一个维度的差异足以把三段一起重下发，故不需要累计所有维度。
        String driftExpected = null;
        String driftActual = null;
        if (!expected.equals(actual)) {
            driftExpected = expected;
            driftActual = actual;
        }
        // listener 类型：中继服务的 listener.type 就是 chainTunnel.protocol，改协议不会动端口与 chain，
        // 只比整段摘要永远发现不了。哨兵不存在（老节点）→ 整维度跳过。
        if (driftExpected == null && reportedDigests.containsKey(LISTENER_SUPPORTED_KEY)) {
            String expectedListener = expectedRelayListener(serviceRow);
            if ("-".equals(expectedListener) && actualListener == null) {
                // chain_tunnel.protocol 空白的历史脏数据：库里算不出 listener，节点也没报，别判漂移
                log.debug("中继服务 {}@node{} 的 listener 期望值缺失（protocol 空白），跳过 listener 比对", serviceName, nodeId);
            } else if (!Objects.equals(expectedListener, actualListener)) {
                driftExpected = "listener=" + expectedListener;
                driftActual = "listener=" + orDash(actualListener);
            }
        }
        // 出站网卡：只在出口节点（chainType=3）才会下发 metadata.interface，
        // 与 GostUtil.CreateChainService 取的是「收到这条服务的节点」的 interfaceName 同一口径。
        if (driftExpected == null && reportedDigests.containsKey(IFACE_SUPPORTED_KEY)) {
            String expectedIface = expectedRelayInterface(serviceRow, nodes.get(nodeId));
            if (expectedIface == null && actualIface != null) {
                // 中间节点/入口节点的中继服务本不该带 metadata.interface，残留只记录（删建会掐断整条隧道）
                log.info("中继服务 {}@node{} 挂着库中未配置的出站网卡 {}（chainType={}），仅记录不重建",
                        serviceName, nodeId, actualIface, serviceRow.getChainType());
            } else if (!Objects.equals(expectedIface, actualIface)) {
                driftExpected = "iface=" + orDash(expectedIface);
                driftActual = "iface=" + orDash(actualIface);
            }
        }
        if (driftExpected == null) {
            driftBreaker.recordMatch(driftKey);
            return false;
        }
        if (!driftBreaker.allowAttempt(driftKey)) {
            int drifts = driftBreaker.consecutiveDrifts(driftKey);
            long remainSeconds = (driftBreaker.remainingCooldownMs(driftKey) + 999) / 1000;
            log.warn("节点 {} {} 配置漂移重建已熔断（连续 {} 次，剩余 {}s）：库 {} / 节点 {}",
                    nodeId, serviceName, drifts, remainSeconds, driftExpected, driftActual);
            notes.add("中继服务 " + serviceName + "@node" + nodeId + " 漂移重建已熔断（连续" + drifts
                    + "次，剩余" + remainSeconds + "s），配置未自动修复：库 " + driftExpected + " / 节点 " + driftActual);
            return false;
        }
        driftBreaker.recordDrift(driftKey, driftExpected, driftActual);
        notes.add("中继服务 " + serviceName + "@node" + nodeId + " 配置漂移（库 " + driftExpected + " / 节点 " + driftActual + "）");
        JSONArray names = new JSONArray();
        names.add(serviceName);
        GostDto deleted = GostUtil.DeleteService(nodeId, names);
        String delMsg = deleted == null || deleted.getMsg() == null ? "节点无响应" : deleted.getMsg();
        if (!"OK".equals(delMsg)) {
            notes.add("中继服务 " + serviceName + "@node" + nodeId + " 漂移重建前删除失败：" + delMsg + "，未重建");
            return true; // 已经动过节点（删除下发），额度照算
        }
        if (serviceRow.getNodeId() == null || !nodes.containsKey(serviceRow.getNodeId())) {
            notes.add("中继服务 " + serviceName + "@node" + nodeId + " 重建失败：节点数据缺失");
            return true;
        }
        GostDto rebuilt = GostUtil.CreateChainService(nodeId, serviceRow, nodes, tunnel);
        String msg = rebuilt == null || rebuilt.getMsg() == null ? "节点无响应" : rebuilt.getMsg();
        if ("OK".equals(msg)) {
            notes.add("中继服务重建：" + serviceName + "@node" + nodeId
                    + " 配置漂移（库 " + driftExpected + " / 节点 " + driftActual + "）");
        } else {
            notes.add("已删除漂移中继服务 " + serviceName + "@node" + nodeId + " 但重建失败：" + msg);
        }
        return true;
    }

    /**
     * 期望的中继服务 listener 类型，与 {@link GostUtil#CreateChainService} 下发口径一致：
     * listener.type 原样取 chainTunnel.protocol。节点侧压成小写比较，空协议写 "-"（节点不会空，纯防御）。
     */
    private static String expectedRelayListener(ChainTunnel serviceRow) {
        String protocol = serviceRow.getProtocol();
        if (protocol == null || protocol.trim().isEmpty()) {
            return "-";
        }
        return protocol.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 期望的中继服务出站网卡，与 {@link GostUtil#CreateChainService} 同一条件：只有出口节点
     * （chainType=3）才带 metadata.interface，取的是收到这条服务的节点。
     *
     * @return null 表示这条中继服务在节点上本就不该有 iface
     */
    private static String expectedRelayInterface(ChainTunnel serviceRow, Node node) {
        if (node == null || !Objects.equals(3, serviceRow.getChainType())) {
            return null;
        }
        String iface = node.getInterfaceName();
        return iface == null || iface.trim().isEmpty() ? null : iface.trim();
    }

    /** 删除 f_u_ut 的 tcp/udp 两份服务，只删清单里确实存在的名字。 */
    private boolean deleteServices(Long nodeId, String serviceName, Set<String> presentServices, List<String> notes) {
        JSONArray names = new JSONArray();
        if (presentServices.contains(serviceName + "_tcp")) {
            names.add(serviceName + "_tcp");
        }
        if (presentServices.contains(serviceName + "_udp")) {
            names.add(serviceName + "_udp");
        }
        if (names.isEmpty()) {
            return false;
        }
        GostDto dto = GostUtil.DeleteService(nodeId, names);
        String msg = dto == null || dto.getMsg() == null ? "节点无响应" : dto.getMsg();
        if ("OK".equals(msg)) {
            return true;
        }
        notes.add("重挂链服务前删除 " + serviceName + "@node" + nodeId + " 失败：" + msg + "，未重发（避免同名 exists）");
        return false;
    }

    /**
     * 下发一条中继链（chains_&lt;tid&gt;，next 为下一跳节点集合）。
     *
     * @return true 表示链已就绪（本次补成功，或调用方已确认清单里有）
     */
    private boolean repairChain(Long nodeId, Long tunnelId, List<ChainTunnel> next,
                                Map<Long, Node> nodes, List<String> notes, String action, Tunnel tunnel) {
        String chainName = "chains_" + tunnelId;
        for (ChainTunnel hop : next) {
            if (hop.getNodeId() == null || !nodes.containsKey(hop.getNodeId())) {
                notes.add("中继链 " + chainName + "@node" + nodeId + " 补齐失败：下一跳节点数据缺失");
                return false;
            }
        }
        GostDto gostDto = GostUtil.CreateChains(nodeId, next, nodes, tunnel);
        String msg = gostDto == null || gostDto.getMsg() == null ? "节点无响应" : gostDto.getMsg();
        if ("OK".equals(msg)) {
            notes.add("已按库" + action + "中继链 " + chainName + "@node" + nodeId + "（下一跳 " + next.size() + " 个节点）");
            return true;
        }
        if (msg.contains("exists")) {
            notes.add("节点" + nodeId + " 已有 " + chainName + " 但配置未知，未自动修复");
            return false;
        }
        notes.add("中继链 " + chainName + "@node" + nodeId + " 补齐失败：" + msg);
        return false;
    }

    /** 节点上报的 selector 在链摘要列表里的前缀：{@code selector=<strategy>|<maxFails>|<failTimeout>}。 */
    private static final String CHAIN_SELECTOR_PREFIX = "selector=";

    /** 节点上报的出站网卡在链摘要列表里的前缀：{@code iface=<hop0>;<hop1>}（空 hop 写 "-"）。 */
    private static final String CHAIN_IFACE_PREFIX = "iface=";

    /**
     * 判链漂移：链名对得上时再比一次「下一跳是谁」，节点报了 selector 时连负载策略一起比。
     *
     * <p>跳点先比：跳点不同只报跳点漂移，不去追策略，避免一条链上同时有两个问题时报出歧义说明。
     * selector 只在节点确实上报了才比——老节点不报，必须完全退回「只比下一跳」，
     * 绝不能因为缺字段把每条链都判成漂移、删了重建。
     *
     * @return 非 null 表示需要重建，返回值是要写进说明的漂移描述
     */
    private static String chainDrift(Long nodeId, List<ChainTunnel> next, Map<Long, Node> nodes,
                                     List<String> expected, String chainName,
                                     Map<String, List<String>> reportedChainDigests) {
        if (reportedChainDigests == null) {
            return null; // 老节点没报链摘要，只能维持「只比链名」
        }
        List<String> actual = reportedChainDigests.get(chainName);
        if (actual == null) {
            return null; // 这次上报没带这条链的摘要，不动
        }
        if (expected == null) {
            return null; // 库里的节点数据不全，算不出期望值，交人工
        }
        List<String> actualNodes = new ArrayList<>(actual.size());
        String actualSelector = null;
        String actualIfaces = null;
        for (String item : actual) {
            if (item == null) {
                continue;
            }
            if (item.startsWith(CHAIN_SELECTOR_PREFIX)) {
                actualSelector = item.substring(CHAIN_SELECTOR_PREFIX.length()).trim();
            } else if (item.startsWith(CHAIN_IFACE_PREFIX)) {
                // iface= 是整条链的网卡摘要，不是跳点；必须挑出来，否则会被当成多出来的跳点判漂移
                actualIfaces = item.substring(CHAIN_IFACE_PREFIX.length()).trim();
            } else {
                actualNodes.add(item);
            }
        }
        if (!expected.equals(actualNodes)) {
            return "中继链 " + chainName + "@node" + nodeId + " 配置漂移（库 "
                    + String.join(",", expected) + " / 节点 " + String.join(",", actualNodes) + "）";
        }
        // 出站网卡：老节点不报 iface= 就整维度跳过，绝不因此判漂移删链。
        boolean chainIfaceSupported = reportedChainDigests.containsKey(CHAIN_IFACE_SUPPORTED_KEY);
        if (chainIfaceSupported || actualIfaces != null) {
            String expectedIfaces = expectedChainIfaces(nodeId, nodes);
            if (expectedIfaces == null) {
                return null; // 承载节点不在本次上报的节点表里，算不出期望值，交人工
            }
            // 节点声明支持该维度却完全没报 iface=，等价于「这条链上没配网卡」，按 "-" 比；
            // 否则「库配了网卡、节点整个丢了」永远发现不了（round39 QA BLOCK 的另一半）。
            String actualCmp = actualIfaces == null ? "-" : actualIfaces;
            if (!expectedIfaces.equals(actualCmp)) {
                return "中继链 " + chainName + "@node" + nodeId + " 出站网卡漂移（库 iface=" + expectedIfaces
                        + " / 节点 iface=" + actualCmp + "）";
            }
        }
        if (actualSelector == null) {
            return null; // 老节点不报 selector：跳点一致就算一致
        }
        String expectedSelector = expectedChainSelector(next);
        if (expectedSelector.equals(actualSelector)) {
            return null;
        }
        return "中继链 " + chainName + "@node" + nodeId + " 负载策略漂移（库 selector="
                + expectedSelector + " / 节点 selector=" + actualSelector + "）";
    }

    /**
     * 按 {@link GostUtil#CreateChains} 的下发口径算一条链的出站网卡摘要。
     *
     * <p>口径要点（与节点侧 parseChainDigests 同构，go-gost websocket_reporter.go:1172-1198）：
     * 节点按 <b>gost hop</b> 收集 {@code hop.interface}，空写 "-"、";" 连接。而 CreateChains
     * 把整条链的全部跳点塞进<b>同一个</b> hop 的 nodes（GostUtil.java:88-107），hop.interface
     * 取的是接收这条链的节点（GostUtil.java:92-94），所以节点侧这条链永远只有一段。
     * 期望值必须按 hop 口径算（而不是按 chain_tunnel 行数算），否则多跳链每轮都会判漂移、反复删链重建。
     *
     * @param carrierNodeId 接收这条链的节点 id（与 CreateChainService/AddChains 的 node_id 同一取值）
     * @return null 表示节点数据缺失，算不出期望值
     */
    private static String expectedChainIfaces(Long carrierNodeId, Map<Long, Node> nodes) {
        Node carrier = carrierNodeId == null ? null : nodes.get(carrierNodeId);
        if (carrier == null) {
            return null;
        }
        String iface = carrier.getInterfaceName();
        return iface == null || iface.trim().isEmpty() ? "-" : iface.trim();
    }

    /**
     * 按 {@link GostUtil#CreateChains} 的下发口径算一条链的 selector 摘要：
     * {@code <strategy>|1|600000000000}（maxFails=1、failTimeout=600 秒按纳秒写，GostUtil.java:97-103）。
     *
     * <p>strategy 直接取下一跳首行的值，与 CreateChains 的 {@code chainTunnels.getFirst().getStrategy()}
     * 同一来源；库里为空时节点侧按约定上报 "-"，这里也写 "-" 才对得上。
     */
    private static String expectedChainSelector(List<ChainTunnel> next) {
        String strategy = next.getFirst().getStrategy();
        if (strategy == null || strategy.trim().isEmpty()) {
            return "-|1|600000000000";
        }
        // 节点侧 chainSelectorSegment 会 TrimSpace(strategy)（websocket_reporter.go:1177-1191），
        // 这里必须同口径去空格：否则库里 strategy 带前后空格时永远判漂移，每次上报都删链重建。
        return strategy.trim() + "|1|600000000000";
    }

    /**
     * 按 {@link GostUtil#CreateChains} 的下发口径算一条链的下一跳摘要：
     * {@code node_<nodeId>@<serverIp:port>#<protocol>}，顺序与 chain_tunnel 的行序一致。
     *
     * <p>跳点端口是脏值（null / 0 / 70000）时同样返回 null：硬算出来的摘要永远对不上节点上的真实监听，
     * 每轮都会判「配置漂移」→ 删链重建，只能靠漂移熔断器止住。跳过判定、打一条 warn 交人工改库才是对的。
     *
     * @return null 表示有跳点的节点信息缺失或端口非法，算不出完整期望值（调用方跳过本轮漂移判定）
     */
    private static List<String> expectedChainNodes(List<ChainTunnel> next, Map<Long, Node> nodes) {
        List<String> expected = new ArrayList<>(next.size());
        for (ChainTunnel hop : next) {
            if (hop.getNodeId() == null) {
                return null;
            }
            Node hopNode = nodes.get(hop.getNodeId());
            if (hopNode == null || hopNode.getServerIp() == null) {
                return null;
            }
            if (!isValidPort(hop.getPort())) {
                log.warn("中继链跳点端口非法，跳过漂移判定：tunnelId={} nodeId={} port={}",
                        hop.getTunnelId(), hop.getNodeId(), hop.getPort());
                return null;
            }
            expected.add("node_" + hop.getNodeId() + "@"
                    + GostUtil.processServerAddress(hopNode.getServerIp() + ":" + hop.getPort())
                    + "#" + hop.getProtocol());
        }
        return expected;
    }

    /**
     * 按发布口径（TunnelServiceImpl.publishChains）判断本节点在这条隧道里该有什么。
     *
     * <p>入口（1）只下发 chains_&lt;tid&gt;，next 指向第一跳，没有中间跳时直接指向出口；
     * 链节点（2）第 i 组：chains_&lt;tid&gt; 指向第 i+1 组或出口，另有 &lt;tid&gt;_tls；
     * 出口（3）只有 &lt;tid&gt;_tls。inx 为 null 按 0 归组，与 getAllTunnels 一致。
     *
     * @return null 表示本节点不在这条隧道上
     */
    private NodeChainPlan planChainForNode(Long nodeId, List<ChainTunnel> rows) {
        List<ChainTunnel> inNodes = new ArrayList<>();
        List<ChainTunnel> outNodes = new ArrayList<>();
        Map<Integer, List<ChainTunnel>> chainGroups = new TreeMap<>();
        for (ChainTunnel row : rows) {
            Integer type = row.getChainType();
            if (type == null) {
                continue;
            }
            if (type == 1) {
                inNodes.add(row);
            } else if (type == 2) {
                chainGroups.computeIfAbsent(row.getInx() == null ? 0 : row.getInx(), key -> new ArrayList<>()).add(row);
            } else if (type == 3) {
                outNodes.add(row);
            }
        }
        List<List<ChainTunnel>> chainNodes = new ArrayList<>(chainGroups.values());

        boolean isIn = firstRowOf(inNodes, nodeId) != null;
        Integer groupIndex = null;
        for (int i = 0; i < chainNodes.size(); i++) {
            if (firstRowOf(chainNodes.get(i), nodeId) != null) {
                groupIndex = i;
                break;
            }
        }
        ChainTunnel outRow = firstRowOf(outNodes, nodeId);

        int roles = (isIn ? 1 : 0) + (groupIndex != null ? 1 : 0) + (outRow != null ? 1 : 0);
        if (roles == 0) {
            return null;
        }
        NodeChainPlan plan = new NodeChainPlan();
        if (roles > 1) {
            // 同一节点被配成多个中继角色时，发布口径本身有歧义，
            // 按任一角色补都可能拼出一条错链，交给人工确认
            plan.conflict = "同时被配置为多个中继角色";
            return plan;
        }
        if (outRow != null) {
            plan.serviceRow = outRow;
            return plan;
        }
        if (groupIndex != null) {
            plan.next = groupIndex + 1 < chainNodes.size() ? chainNodes.get(groupIndex + 1) : outNodes;
            plan.serviceRow = firstRowOf(chainNodes.get(groupIndex), nodeId);
            return plan;
        }
        plan.next = chainNodes.isEmpty() ? outNodes : chainNodes.get(0);
        return plan;
    }

    /**
     * 中继行的确定顺序：(chain_type, inx, id)。
     *
     * <p>planChainForNode 只按 inx 分 TreeMap 组，组内顺序完全取决于查询返回的行序；而发布路径按
     * {@code eq(tunnel_id)} 单条查、对账路径按 {@code in(tunnel_id)} 批量查，两者行序不保证一致，
     * 组内顺序一抖，「第 i 组下一跳」就换人，摘要每轮判漂移，于是反复删建链。
     *
     * <p>排序口径：chain_type 升序（null 排最后，视为 9）→ inx 升序（null 视为 0）→ id 升序
     * （null 视为最大）。入口(1) 恒在最前、出口(3) 恒在最后，中间按 inx 分组后组内按 id 稳定。
     * 返回新列表，不改入参，重复调用结果不变（幂等）。
     */
    public static List<ChainTunnel> sortChainRows(List<ChainTunnel> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<ChainTunnel> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparingInt((ChainTunnel row) -> row.getChainType() == null ? 9 : row.getChainType())
                .thenComparingInt(row -> row.getInx() == null ? 0 : row.getInx())
                .thenComparingLong(row -> row.getId() == null ? Long.MAX_VALUE : row.getId()));
        return sorted;
    }

    private static ChainTunnel firstRowOf(List<ChainTunnel> rows, Long nodeId) {
        for (ChainTunnel row : rows) {
            if (row != null && nodeId.equals(row.getNodeId())) {
                return row;
            }
        }
        return null;
    }

    /** 一次性把链上所有节点捞出来：CreateChains/CreateChainService 要按 nodeId 取对端地址。 */
    private Map<Long, Node> loadChainNodes(Long nodeId, Node node, List<ChainTunnel> rows) {
        Set<Long> ids = new HashSet<>();
        for (ChainTunnel row : rows) {
            if (row.getNodeId() != null) {
                ids.add(row.getNodeId());
            }
        }
        ids.add(nodeId);
        Map<Long, Node> nodes = new HashMap<>();
        List<Node> loaded = nodeService.listByIds(ids);
        if (loaded != null) {
            for (Node item : loaded) {
                if (item != null && item.getId() != null) {
                    nodes.put(item.getId(), item);
                }
            }
        }
        nodes.put(nodeId, node); // 上报节点自己一定有，接口名等字段要取得到
        return nodes;
    }

}
