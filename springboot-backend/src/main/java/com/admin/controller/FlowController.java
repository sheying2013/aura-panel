package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.FlowBilling;
import com.admin.common.utils.FlowReportDedup;
import com.admin.common.utils.FlowReportDedupStore;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.PauseReason;
import com.admin.common.utils.QuotaResume;
import com.admin.entity.*;
import com.admin.service.ChainTunnelService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计
 * <p>
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * <p>
 * 并发安全解决方案：
 * 1. 使用UpdateWrapper进行数据库层面的原子更新操作，避免读取-修改-写入的竞态条件
 * 2. 使用synchronized锁确保同一用户/隧道的流量更新串行执行
 * 3. 这样可以避免相同用户相同隧道不同转发同时上报时流量统计丢失的问题
 */
@RestController
@RequestMapping("/flow")
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final String DEFAULT_USER_TUNNEL_ID = "0";
    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    /** 节点密钥请求头（新协议）；旧节点仍可走 query 参数，但会被反代访问日志记下来。 */
    private static final String NODE_SECRET_HEADER = "X-Node-Secret";
    private static final String LEGACY_SECRET_HEADER = "X-Secret";
    private static final AtomicBoolean QUERY_SECRET_WARNED = new AtomicBoolean(false);

    // 用于同步相同用户和隧道的流量更新操作
    private static final ConcurrentHashMap<String, Object> USER_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> TUNNEL_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> FORWARD_LOCKS = new ConcurrentHashMap<>();

    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    // 节点上报幂等：节点 HTTP 重试会把同一批增量重发，同一时间桶内同样内容只累加一次。
    // round21：优先走库表（跨实例/跨重启）；store 未注入时（单元测试直接 new）退回进程内实现。
    private static final FlowReportDedup FLOW_DEDUP = new FlowReportDedup();

    @Resource
    FlowReportDedupStore flowReportDedupStore;

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    @Resource
    @Lazy
    ChainTunnelService chainTunnelService;

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }

    @PostMapping("/config")
    @LogAnnotation
    public String config(@RequestBody String rawData, String secret, HttpServletRequest request) {
        String nodeSecret = resolveSecret(request, secret);
        Node node = getNodeBySecret(nodeSecret);
        if (node == null) return SUCCESS_RESPONSE;

        try {
            // 尝试解密数据
            String decryptedData = decryptIfNeeded(rawData, nodeSecret);

            // 解析为GostConfigDto
            GostConfigDto gostConfigDto = JSON.parseObject(decryptedData, GostConfigDto.class);
            checkGostConfigAsync.cleanNodeConfigs(node.getId().toString(), gostConfigDto);

            log.info("🔓 节点 {} 配置数据接收成功{}", node.getId(), isEncryptedMessage(rawData) ? "（已解密）" : "");

        } catch (Exception e) {
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getMessage());
        }

        return SUCCESS_RESPONSE;
    }

    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }

    /**
     * 处理流量数据上报
     *
     * @param rawData 原始数据（可能是加密的）
     * @param secret  节点密钥
     * @return 处理结果
     */
    @RequestMapping("/upload")
    @LogAnnotation
    public String uploadFlowData(@RequestBody String rawData, String secret, HttpServletRequest request) {
        // 1. 验证节点权限
        String nodeSecret = resolveSecret(request, secret);
        Node node = getNodeBySecret(nodeSecret);
        if (node == null) {
            return SUCCESS_RESPONSE;
        }

        // 2. 尝试解密数据
        String decryptedData = decryptIfNeeded(rawData, nodeSecret);

        // 3. 解析为FlowDto列表
        JSONArray flowDataList = JSONObject.parseArray(decryptedData);
        log.info("节点上报流量数据{}", flowDataList);
        // 同一请求内所有条目共用一个上报时刻，重试报文才会落在同一个时间桶里
        long reportTime = System.currentTimeMillis();
        for (int i = 0; i < flowDataList.size(); i++) {
            String jsonObject = flowDataList.getJSONObject(i).toJSONString();
            FlowDto flowDto = JSONObject.parseObject(jsonObject, FlowDto.class);
            if (Objects.equals(flowDto.getN(), "web_api")) {
                continue;
            }
            // 4. 幂等：同一节点 + 同一服务 + 同一时间桶 + 相同增量，只累加一次
            boolean duplicate = (flowReportDedupStore != null)
                    ? flowReportDedupStore.isDuplicate(String.valueOf(node.getId()), flowDto.getN(), reportTime,
                        nullToZero(flowDto.getD()), nullToZero(flowDto.getU()))
                    : FLOW_DEDUP.isDuplicate(String.valueOf(node.getId()), flowDto.getN(), reportTime,
                        nullToZero(flowDto.getD()), nullToZero(flowDto.getU()));
            if (duplicate) {
                log.debug("丢弃重复流量上报: node={} service={} d={} u={}",
                        node.getId(), flowDto.getN(), flowDto.getD(), flowDto.getU());
                continue;
            }
            processFlowData(flowDto);
        }
        return SUCCESS_RESPONSE;

    }

    /**
     * 兼容无请求上下文的内部调用（单测直调）：等价于只带 query 参数的旧行为。
     */
    public String uploadFlowData(String rawData, String secret) {
        return uploadFlowData(rawData, secret, null);
    }

    /**
     * 检测消息是否为加密格式
     */
    private boolean isEncryptedMessage(String data) {
        try {
            JSONObject json = JSON.parseObject(data);
            return json.getBooleanValue("encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据需要解密数据
     */
    private String decryptIfNeeded(String rawData, String secret) {
        if (rawData == null || rawData.trim().isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(rawData, EncryptedMessage.class);

            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(secret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return rawData;
                }

                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.info("数据未加密或解密失败，使用原始数据: {}", e.getMessage());
        }

        return rawData;
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    /**
     * 处理流量数据的核心逻辑
     */
    private void processFlowData(FlowDto flowDataList) {
        String[] serviceIds = parseServiceName(flowDataList.getN());
        if (serviceIds == null) {
            // round49：服务名是节点上报的不可信输入，格式不对直接丢弃这一条，
            // 不能让 split 越界把整批上报打成 500（其余条目本来是好的）。
            log.warn("丢弃服务名非法的流量上报：node 服务名={}", flowDataList.getN());
            return;
        }
        String forwardId = serviceIds[0];
        String userId = serviceIds[1];
        String userTunnelId = serviceIds[2];

        Forward forward = forwardService.getById(forwardId);
        if (forward != null){
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null) {
                applyBilling(flowDataList, tunnel);
            }
        }

        // 先更新所有流量统计 - 确保流量数据的一致性
        updateForwardFlow(forwardId, flowDataList);
        updateUserFlow(userId, flowDataList);
        updateUserTunnelFlow(userTunnelId, flowDataList);

        // 7. 检查和服务暂停操作
        if (!Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) { // 非管理员的转发需要检测流量限制
            checkUserRelatedLimits(userId);
            checkUserTunnelRelatedLimits(userTunnelId, userId);
        }

    }

    /**
     * 把节点上报的原始字节换算成计费字节，并写回上报对象。
     * <p>
     * 口径只在这里定义一次（见 {@link FlowBilling}）：倍率只取 trafficRatio（0/负数/null 按 1），
     * Tunnel.flow 只决定方向（1 单向=仅上传，2 双向=上传+下载），绝不作为倍数相乘。
     * 写回后的 d/u 就是扣配额、写 statistics_flow、前端展示三方共用的计费量。
     */
    private void applyBilling(FlowDto report, Tunnel tunnel) {
        int flow = tunnel.getFlow();
        BigDecimal ratio = tunnel.getTrafficRatio();
        if (!FlowBilling.isLegalDirection(flow)) {
            log.warn("隧道 {} 的流量计算类型 {} 非法（应为 1 单向 / 2 双向），按双向全额计量",
                    tunnel.getId(), flow);
        }
        if (!FlowBilling.isLegalRatio(ratio)) {
            log.warn("隧道 {} 的流量倍率 {} 非法，按 {} 处理", tunnel.getId(), ratio,
                    FlowBilling.normalizeRatio(ratio));
        }
        long[] billed = FlowBilling.billed(nullToZero(report.getD()), nullToZero(report.getU()), flow, ratio);
        report.setD(billed[0]);
        report.setU(billed[1]);
    }

    private void checkUserRelatedLimits(String userId) {

        // 重新查询用户以获取最新的流量数据
        User updatedUser = userService.getById(userId);
        if (updatedUser == null) return;

        // 检查用户总流量限制（QuotaResume 安全处理 null 与 <=0 不限量语义）
        if (QuotaResume.userOverQuota(updatedUser.getFlow(), updatedUser.getInFlow(), updatedUser.getOutFlow())) {
            pauseAllUserServices(userId, PauseReason.QUOTA);
            return;
        }

        // 检查用户到期时间
        if (updatedUser.getExpTime() != null && updatedUser.getExpTime() <= new Date().getTime()) {
            pauseAllUserServices(userId, PauseReason.EXPIRY);
            return;
        }

        // 检查用户状态
        if (updatedUser.getStatus() != 1) {
            pauseAllUserServices(userId, PauseReason.DISABLED);
        }
    }

    /**
     * 暂停某用户名下全部转发，并记录暂停原因（round19）。
     */
    public void pauseAllUserServices(String userId, String reason) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userId));
        pauseService(forwardList, reason);
    }

    /** 兼容旧调用：未指定原因时按配额暂停处理（流量上报路径的默认语义）。 */
    public void pauseAllUserServices(String userId) {
        pauseAllUserServices(userId, PauseReason.QUOTA);
    }

    public void checkUserTunnelRelatedLimits(String userTunnelId, String userId) {

        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel == null) return;
        // 检查隧道流量限制（QuotaResume 安全处理 null 与 <=0 不限量语义）
        if (QuotaResume.userTunnelOverQuota(userTunnel.getFlow(), userTunnel.getInFlow(), userTunnel.getOutFlow())) {
            pauseSpecificForward(userTunnel.getTunnelId(), userId, PauseReason.QUOTA);
            return;
        }

        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            pauseSpecificForward(userTunnel.getTunnelId(), userId, PauseReason.EXPIRY);
            return;
        }

        if (userTunnel.getStatus() != 1) {
            pauseSpecificForward(userTunnel.getTunnelId(), userId, PauseReason.DISABLED);
        }


    }

    private void pauseSpecificForward(Integer tunnelId, String userId, String reason) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId).eq("user_id", userId));
        pauseService(forwardList, reason);
    }

    /**
     * 暂停转发对应的 gost 服务。
     * <p>
     * 每条转发的服务名必须由转发自身的 forwardId/userId/userTunnelId 解析，不能复用上报方的服务名，
     * 否则批量暂停时会去停别的转发（或根本不存在的）服务。
     * <p>
     * 状态以数据库为准：即使有入口节点没确认（离线/超时），也先把 status=0 落库。
     * 原因是配额已经超了，若因为某个入口离线就不落库，其余在线入口会一直放行超额流量，配额形同虚设；
     * 未确认的节点由 WebSocketServer.pauseForwardsExpectingPaused 在其重连时补发 PauseService（与 round14 的
     * ForwardServiceImpl.applyStatusChange 同一口径）。
     */
    public void pauseService(List<Forward> forwardList) {
        pauseService(forwardList, PauseReason.QUOTA);
    }

    /**
     * 暂停转发并落库暂停原因（round19）。
     *
     * @param reason {@link PauseReason} 之一：quota / expiry / disabled；传 null 表示不标注
     */
    public void pauseService(List<Forward> forwardList, String reason) {
        for (Forward forward : forwardList) {
            String name = resolveServiceName(forward);
            if (name == null) {
                log.warn("转发 {} 无法确定对应的用户隧道，跳过暂停", forward.getId());
                continue;
            }

            List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", forward.getTunnelId()).eq("chain_type", 1));
            if (chainTunnels.isEmpty()) {
                log.warn("转发 {} 没有入口节点，跳过暂停", forward.getId());
                continue;
            }

            // 先落库再下发（round19）：顺序反了的话，中途异常会留下「节点已停、库里 status=1」，
            // 而 ForwardServiceImpl.resyncForwardServices 对 status=1 的服务会补发 ResumeService，等于把用户重新放开。
            forward.setStatus(0);
            forward.setPauseReason(reason);
            forward.setUpdatedTime(System.currentTimeMillis());
            forwardService.updateById(forward);

            List<String> notes = new ArrayList<>();
            for (ChainTunnel chainTunnel : chainTunnels) {
                GostDto gostDto = pauseNodeService(chainTunnel.getNodeId(), name);
                if (gostDto == null || !Objects.equals(gostDto.getMsg(), "OK")) {
                    notes.add("节点" + chainTunnel.getNodeId() + "：" + (gostDto == null ? "无响应" : gostDto.getMsg()));
                }
            }
            if (!notes.isEmpty()) {
                log.warn("转发 {} 已记为{}（{}），{} 个入口节点未确认：{}，等待节点重连后补偿",
                        forward.getId(), PauseReason.label(reason), reason, notes.size(), String.join("；", notes));
            }
        }
    }

    /**
     * 停节点上的服务，抽出来便于测试替换 gost 调用。
     */
    protected GostDto pauseNodeService(Long nodeId, String name) {
        return GostUtil.PauseAndResumeService(nodeId, name, "PauseService");
    }

    /**
     * 按 buildServiceName 规则解析转发自身的服务名：{forwardId}_{userId}_{userTunnelId}。
     * 普通用户取该用户在该隧道上的 UserTunnel 主键；管理员创建时固定为 0。
     *
     * @return 服务名，无法确定对应的用户隧道时返回 null
     */
    private String resolveServiceName(Forward forward) {
        User user = userService.getById(forward.getUserId());
        if (user == null) return null;
        if (Objects.equals(user.getRoleId(), 0)) {
            return forward.getId() + "_" + forward.getUserId() + "_0";
        }
        UserTunnel userTunnel = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", forward.getUserId())
                .eq("tunnel_id", forward.getTunnelId()));
        if (userTunnel == null) return null;
        int userTunnelId = userTunnel.getId();
        return forward.getId() + "_" + forward.getUserId() + "_" + userTunnelId;
    }

    private void updateForwardFlow(String forwardId, FlowDto flowStats) {
        // 对相同转发的流量更新进行同步，避免并发覆盖
        synchronized (getForwardLock(forwardId)) {
            UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", forwardId);
            updateWrapper.setSql("in_flow = in_flow + " + nonNegativeDelta(flowStats.getD()));
            updateWrapper.setSql("out_flow = out_flow + " + nonNegativeDelta(flowStats.getU()));

            forwardService.update(null, updateWrapper);
        }
    }

    private void updateUserFlow(String userId, FlowDto flowStats) {
        // 对相同用户的流量更新进行同步，避免并发覆盖
        synchronized (getUserLock(userId)) {
            UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userId);

            updateWrapper.setSql("in_flow = in_flow + " + nonNegativeDelta(flowStats.getD()));
            updateWrapper.setSql("out_flow = out_flow + " + nonNegativeDelta(flowStats.getU()));

            userService.update(null, updateWrapper);
        }
    }

    private void updateUserTunnelFlow(String userTunnelId, FlowDto flowStats) {
        if (Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            return; // 默认隧道不需要更新，返回成功
        }

        // 对相同用户隧道的流量更新进行同步，避免并发覆盖
        synchronized (getTunnelLock(userTunnelId)) {
            UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userTunnelId);
            updateWrapper.setSql("in_flow = in_flow + " + nonNegativeDelta(flowStats.getD()));
            updateWrapper.setSql("out_flow = out_flow + " + nonNegativeDelta(flowStats.getU()));
            userTunnelService.update(null, updateWrapper);
        }
    }

    private Object getUserLock(String userId) {
        return USER_LOCKS.computeIfAbsent(userId, k -> new Object());
    }

    private Object getTunnelLock(String userTunnelId) {
        return TUNNEL_LOCKS.computeIfAbsent(userTunnelId, k -> new Object());
    }

    private Object getForwardLock(String forwardId) {
        return FORWARD_LOCKS.computeIfAbsent(forwardId, k -> new Object());
    }

    /**
     * 解析节点密钥：请求头优先，query 参数只作为旧节点的兼容回退。
     * <p>
     * query 里的密钥会被 Nginx/反向代理的 access_log 原样记录下来，等于把节点身份写进日志，
     * 所以命中回退时提示一次（不打印密钥本身）。
     */
    private String resolveSecret(HttpServletRequest request, String querySecret) {
        if (request != null) {
            String headerSecret = trimToNull(request.getHeader(NODE_SECRET_HEADER));
            if (headerSecret == null) {
                headerSecret = trimToNull(request.getHeader(LEGACY_SECRET_HEADER));
            }
            if (headerSecret != null) {
                return headerSecret;
            }
        }

        String fallback = trimToNull(querySecret);
        if (fallback != null && QUERY_SECRET_WARNED.compareAndSet(false, true)) {
            log.warn("节点密钥经 URL query 传输，建议改用 {} 请求头", NODE_SECRET_HEADER);
        }
        return fallback;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Node getNodeBySecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        return nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 解析服务名 {forwardId}_{userId}_{userTunnelId}。
     * <p>
     * round49：节点上报的 n 字段不可信，必须严格三段纯数字；否则 split 越界会抛
     * ArrayIndexOutOfBoundsException，一条坏数据就能把整批流量上报打成 500。
     *
     * @return 三段服务名，格式不合法返回 null
     */
    private String[] parseServiceName(String serviceName) {
        if (serviceName == null) {
            return null;
        }
        String[] parts = serviceName.split("_", -1);
        // 真机上报的服务名带协议后缀（43_1_0_tcp / 43_1_0_udp），只认三段会把全部真实上报丢掉——
        // 首轮真机验证就是这么发现计费被整体清零的。协议段只做白名单校验，不参与取数。
        if (parts.length == 4) {
            String protocol = parts[3];
            if (!"tcp".equals(protocol) && !"udp".equals(protocol)) {
                return null;
            }
            String[] numeric = new String[]{parts[0], parts[1], parts[2]};
            return allNumericSegments(numeric) ? numeric : null;
        }
        if (parts.length != 3) {
            return null;
        }
        return allNumericSegments(parts) ? parts : null;
    }

    /** 服务名三段必须都是 1..18 位纯数字。 */
    private static boolean allNumericSegments(String[] parts) {
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 18) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 上报增量只能是有限的正数：负数会把流量减回去（等于洗掉计费），
     * 超大值会溢出成负数，同样能绕过配额。
     */
    private static long nonNegativeDelta(Long value) {
        long delta = nullToZero(value);
        if (delta <= 0) {
            return 0L;
        }
        // 单条上报超过 1PB 只可能是伪造或解析错乱；再大就会把 SQLite 的 64 位整数加溢出成负数
        return Math.min(delta, 1L << 50);
    }

}
