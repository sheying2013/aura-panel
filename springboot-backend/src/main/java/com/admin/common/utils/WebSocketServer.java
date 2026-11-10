package com.admin.common.utils;


import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.entity.ForwardPort;
import com.admin.service.ForwardPortService;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.stream.Collectors;


@Slf4j
public class WebSocketServer extends TextWebSocketHandler {

    @Resource
    NodeService nodeService;

    @Resource
    ForwardService forwardService;

    @Resource
    ForwardPortService forwardPortService;

    @Resource
    ChainTunnelService chainTunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    TunnelService tunnelService;

    @Resource
    SpeedLimitService speedLimitService;

    // 存储所有活跃的 WebSocket 连接（
    private static final CopyOnWriteArraySet<WebSocketSession> activeSessions = new CopyOnWriteArraySet<>();
    
    // 存储节点ID和对应的WebSocket session映射
    private static final ConcurrentHashMap<Long, WebSocketSession> nodeSessions = new ConcurrentHashMap<>();
    
    // 为每个session提供锁对象，防止并发发送消息
    private static final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    
    // 存储等待响应的请求，key为requestId，value为CompletableFuture
    private static final ConcurrentHashMap<String, CompletableFuture<GostDto>> pendingRequests = new ConcurrentHashMap<>();
    
    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> cryptoCache = new ConcurrentHashMap<>();

    // 正在清扫孤儿的节点：同一节点串行，避免重连风暴把同一批孤儿重复删
    private static final Set<Long> sweepingNodes = ConcurrentHashMap.newKeySet();

    // 正在做"连接对账"的节点：与孤儿清扫分开，两条路只补/只删，不会互相打架
    private static final Set<Long> reconcilingNodes = ConcurrentHashMap.newKeySet();

    // 节点最近一次自报资源清单的时间：用来判断它这次连上后到底报没报
    private static final ConcurrentHashMap<Long, Long> lastReportAt = new ConcurrentHashMap<>();

    // 节点连上后等这么久还没有 ServiceListReport，才认为它不上报（空配置节点压根不发）
    private static final long CONNECT_RECONCILE_DELAY_MS = 20_000L;

    // 节点最近一次发来任意消息的时间（节点侧 2 秒一次心跳），用来识别「TCP 还在、对端已死」的半死连接
    private static final ConcurrentHashMap<Long, Long> nodeLastSeen = new ConcurrentHashMap<>();

    // 超过这么久没收到任何消息就判定离线：节点心跳 2 秒一次，30 秒足够覆盖网络抖动又不会误杀
    private static final long NODE_STALE_MS = 30_000L;

    // 连接对账用的哨兵名：只为绕开"双空清单不可信"的守卫，节点上不存在这个资源
    private static final List<String> RECONCILE_UNKNOWN = Collections.singletonList("__reconcile_unknown__");

    // 连接纪元：每次握手 +1。sessionId 在测试/重连里会复用，纪元不会，
    // 用它判定"同一轮连接"才不会把上一轮的补发当成这一轮的。
    private static final AtomicLong connectEpoch = new AtomicLong();

    // 节点当前连接的纪元
    private static final ConcurrentHashMap<Long, Long> nodeConnectEpoch = new ConcurrentHashMap<>();

    // 旧暂停补发路径最近一次实际执行的纪元：同一轮连接内绝不重复补发（round45）
    private static final ConcurrentHashMap<Long, Long> pauseReconciledEpoch = new ConcurrentHashMap<>();

    // 最近一次上报里带 @paused_supported 哨兵的节点：暂停维度交给 ForwardServiceImpl 对账，旧路径让位。
    // 能力不会随重连退化，所以缓存跨轮次保留，直到它报出无哨兵的上报。
    private static final Set<Long> pausedDimensionNodes = ConcurrentHashMap.newKeySet();

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() { return encrypted; }
        public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    }

    //接受客户端消息
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            if (StringUtils.isNoneBlank(message.getPayload())) {
                
                String id = session.getAttributes().get("id").toString();
                String type = session.getAttributes().get("type").toString();
                String nodeSecret = (String) session.getAttributes().get("nodeSecret");
                // 任何一条入站消息都算节点活着：心跳、命令响应、资源清单都会走这里
                touchNode(id);

                // 尝试解密消息
                String decryptedPayload = decryptMessageIfNeeded(message.getPayload(), nodeSecret);

                if (decryptedPayload.contains("memory_usage")){
                    // 先发送确认消息
                    sendToUser(session, "{\"type\":\"call\"}", nodeSecret);
                }else if (decryptedPayload.contains("requestId")) {
                    log.info("收到消息: {}", decryptedPayload);
                    // 处理命令响应消息
                    try {
                        JSONObject responseJson = JSONObject.parseObject(decryptedPayload);
                        String requestId = responseJson.getString("requestId");
                        String responseMessage = responseJson.getString("message");
                        String responseType = responseJson.getString("type");
                        JSONObject responseData = responseJson.getJSONObject("data");
                        
                        if (requestId != null) {
                            CompletableFuture<GostDto> future = pendingRequests.remove(requestId);

                            if (future != null) {
                                GostDto result = new GostDto();
                                
                                // 根据响应类型处理不同的数据
                                if ("PingResponse".equals(responseType) && responseData != null) {
                                    // 特殊处理ping响应，将完整的响应数据返回
                                    result.setMsg(responseMessage != null ? responseMessage : "OK");
                                    result.setData(responseData); // 保存ping详细结果
                                } else {
                                    // 其他类型的响应
                                    result.setMsg(responseMessage != null ? responseMessage : "无响应消息");
                                    if (responseData != null) {
                                        result.setData(responseData);
                                    }
                                }
                                
                                future.complete(result);
                            }
                        }
                    } catch (Exception e) {
                        log.info("处理响应消息失败: {}", e.getMessage(), e);
                    }
                } else if (decryptedPayload.contains("ServiceListReport")) {
                    // 节点上线自报资源清单：只挑孤儿删，解析 + 下发全部丢后台，绝不在 WS 线程上做
                    handleServiceListReport(session, decryptedPayload);
                } else {
                    log.info("收到消息: {}", decryptedPayload);
                }

                // 如果是节点类型，转发消息给其他会话
                if (Objects.equals(type, "1")) {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("id", id);
                    jsonObject.put("type", "info");
                    jsonObject.put("data", decryptedPayload);
                    String broadcastMessage = jsonObject.toJSONString();
                    
                    // 异步处理广播消息，避免阻塞当前线程
                    for (WebSocketSession targetSession : activeSessions) {
                        if (targetSession != null && targetSession.isOpen() && !targetSession.equals(session)) {
                            sendToUser(targetSession, broadcastMessage, null);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.info("处理WebSocket消息时发生异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 尝试解密消息（如果需要）
     */
    private String decryptMessageIfNeeded(String payload, String nodeSecret) {
        if (payload == null || payload.trim().isEmpty()) {
            return payload;
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(payload, EncryptedMessage.class);
            
            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(nodeSecret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return payload;
                }
                
                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.info("WebSocket消息未加密或解密失败，使用原始数据: {}", e.getMessage());
        }
        
        return payload;
    }

    /**
     * 加密消息（如果可能）
     */
    private static String encryptMessageIfPossible(String message, String nodeSecret) {
        if (message == null || nodeSecret == null) {
            return message;
        }

        try {
            AESCrypto crypto = getOrCreateCrypto(nodeSecret);
            if (crypto != null) {
                String encryptedData = crypto.encrypt(message);
                
                // 创建加密消息包装器
                JSONObject encryptedMessage = new JSONObject();
                encryptedMessage.put("encrypted", true);
                encryptedMessage.put("data", encryptedData);
                encryptedMessage.put("timestamp", System.currentTimeMillis());
                
                return encryptedMessage.toJSONString();
            }
        } catch (Exception e) {
            log.info("⚠️ WebSocket消息加密失败，发送原始数据: {}", e.getMessage());
        }

        return message;
    }

    /**
     * 获取或创建加密器实例
     */
    private static AESCrypto getOrCreateCrypto(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        return cryptoCache.computeIfAbsent(secret, AESCrypto::create);
    }

    // 建立连接
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            String id = session.getAttributes().get("id").toString();
            String type = session.getAttributes().get("type").toString();
            
            if (!Objects.equals(type, "1")) {
                // 网页管理员连接
                activeSessions.add(session);
                log.info("管理员连接建立，sessionId: {}", session.getId());
            } else {
                // 客户端节点连接
                Long nodeId = Long.valueOf(id);
                String version = (String) session.getAttributes().get("nodeVersion");
                String http = (String) session.getAttributes().get("http");
                String tls = (String) session.getAttributes().get("tls");
                String socks = (String) session.getAttributes().get("socks");
                
                log.info("节点 {} 尝试连接，开始处理连接逻辑", nodeId);
                log.info("屏蔽协议数据:http->{}, tls->{}, socks->{}", http, tls, socks);
                
                // 检查是否已有该节点的连接，如果有则记录日志但直接覆盖
                WebSocketSession existingSession = nodeSessions.get(nodeId);
                if (existingSession != null && existingSession.isOpen()) {
                    log.info("节点 {} 已有连接存在: {}，新连接将覆盖旧连接", nodeId, existingSession.getId());
                    // 清理旧连接的锁对象
                    sessionLocks.remove(existingSession.getId());
                }
                
                // 直接覆盖会话映射（不主动关闭旧连接，让它自然断开）
                nodeSessions.put(nodeId, session);
                
                // 如果有旧连接，在覆盖映射后主动关闭它
                if (existingSession != null && existingSession.isOpen()) {
                    try {
                        log.info("主动关闭节点 {} 的旧连接: {}", nodeId, existingSession.getId());
                        existingSession.close();
                    } catch (Exception e) {
                        log.info("关闭节点 {} 旧连接失败: {}", nodeId, e.getMessage());
                    }
                }
                
                // 更新节点状态为在线
                Node node = nodeService.getById(nodeId);
                if (node != null) {
                    // 更新状态和版本信息
                    node.setStatus(1);
                    if (version != null) {
                        node.setVersion(version);
                    }
                    if (http != null) {
                        node.setHttp(Integer.parseInt(http));
                    }
                    if (tls != null) {
                        node.setTls(Integer.parseInt(tls));
                    }
                    if (socks != null) {
                        node.setSocks(Integer.parseInt(socks));
                    }

                    boolean updateResult = nodeService.updateById(node);
                    
                    if (updateResult) {
                        log.info("节点 {} 连接建立成功，状态更新为在线，版本: {}", nodeId, version);
                        
                        // 广播节点上线状态给所有管理员
                        JSONObject res = new JSONObject();
                        res.put("id", id);
                        res.put("type", "status");
                        res.put("data", 1);
                        broadcastMessage(res.toJSONString());
                    } else {
                        log.info("节点 {} 状态更新失败", nodeId);
                    }

                    // 节点重启可能把已暂停的服务复活：建连后按库里的期望态补发暂停，握手线程不等结果
                    reconcilePausedForwardsAsync(nodeId, beginConnectEpoch(nodeId));
                    // 活跃服务/中继链/限流器只在创建时下发过一次：节点配置被清空后没有任何自愈路径，
                    // 而空配置节点又不上报清单（go-gost reportServiceList 双空直接 return），
                    // 所以这里按时间兜底，连上后一直没上报就主动对一次账。
                    scheduleConnectReconcile(nodeId);
                } else {
                    log.info("节点 {} 不存在，无法更新状态", nodeId);
                    // 移除无效的会话
                    nodeSessions.remove(nodeId);
                    forgetNode(nodeId);
                }
            }

        } catch (Exception e) {
            log.info("建立连接时发生异常: {}", e.getMessage(), e);
            // 异常情况下，确保清理会话
            try {
                String id = session.getAttributes().get("id").toString();
                String type = session.getAttributes().get("type").toString();
                if (Objects.equals(type, "1")) {
                    Long nodeId = Long.valueOf(id);
                    nodeSessions.remove(nodeId);
                    forgetNode(nodeId);
                    log.info("由于异常，移除节点 {} 的会话", nodeId);
                }
            } catch (Exception cleanupException) {
                log.info("清理异常会话时出错: {}", cleanupException.getMessage());
            }
        }
    }

    /**
     * 节点重连后的期望态对账：节点侧重启/重载可能把已暂停的服务又跑起来，而面板没有任何机会察觉。
     * send_msg 最长要等节点 10s，握手线程不能陪着等，所以整段丢给公共池。
     */
    private void reconcilePausedForwardsAsync(Long nodeId, long epoch) {
        // 建连时还没收到上报，能力未知：认得哨兵（最近一次上报带过）就直接让位给 ForwardServiceImpl，
        // 否则按老规矩兜底 —— 空配置节点压根不上报，没人兜底就永远补不回来。
        if (!claimPauseRound(nodeId, epoch, null)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                pauseForwardsExpectingPaused(nodeId);
            } catch (Exception e) {
                log.info("节点 {} 重连对账异常: {}", nodeId, e.getMessage(), e);
            }
        });
    }

    /**
     * 连接对账兜底：节点连上 20 秒还没自报资源清单，就按库里的期望态幂等补一遍。
     *
     * <p>为什么需要这条路：go-gost 的 reportServiceList() 在 services/chains 双空时直接
     * "跳过上报"（避免空清单被后端当成真相误删），于是整机配置被清空/容器重装这类
     * 最需要自愈的场景永远不会触发清扫与补齐。有清单的节点走 ServiceListReport 那条路，
     * 这里只处理"报不出来"的节点。
     *
     * <p>只补不删：哨兵清单让 resync 认为"节点上什么都没有"，AddService/AddChains 遇到
     * 同名资源返回 exists（GostUtil 归一成 OK），不会重复 bind；而删除动作（重挂链前的
     * DeleteService）只删哨兵清单里命中的名字，命中不了，所以这条路径不会删任何资源。
     */
    private void scheduleConnectReconcile(Long nodeId) {
        final long connectedAt = System.currentTimeMillis();
        CompletableFuture.delayedExecutor(CONNECT_RECONCILE_DELAY_MS, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    try {
                        if (!shouldReconcileOnConnect(connectedAt, lastReportAt.get(nodeId))) {
                            return;
                        }
                        WebSocketSession session = nodeSessions.get(nodeId);
                        if (session == null || !session.isOpen()) {
                            return; // 已经离线，推过去的命令只会超时
                        }
                        reconcileNodeState(nodeId);
                    } catch (Exception e) {
                        log.warn("节点 {} 连接对账调度异常: {}", nodeId, e.toString());
                    }
                });
    }

    /**
     * 连接之后没收到过清单才需要对账。
     *
     * @param connectedAt         本次连接建立时间
     * @param lastReportAtMillis  最近一次成功上报时间，null 表示这个进程从没见过它上报
     */
    public static boolean shouldReconcileOnConnect(long connectedAt, Long lastReportAtMillis) {
        return lastReportAtMillis == null || lastReportAtMillis < connectedAt;
    }

    /**
     * 按库里期望态补服务 + 中继链 + 限流器。任何异常都吞成日志：这是一条兜底路径，
     * 不能因为某个节点数据坏掉就影响 WS 线程或其他节点的对账。
     */
    private void reconcileNodeState(Long nodeId) {
        if (!reconcilingNodes.add(nodeId)) {
            log.info("节点 {} 已有连接对账在进行，跳过本次", nodeId);
            return;
        }
        try {
            log.warn("节点 {} 连接后未上报资源清单，按库对账补一遍", nodeId);
            List<String> repaired = forwardService.resyncNodeServices(nodeId, RECONCILE_UNKNOWN, RECONCILE_UNKNOWN);
            if (!repaired.isEmpty()) {
                log.warn("节点 {} 连接对账补齐：{}", nodeId, String.join("；", repaired));
            }
            List<String> limiterNotes = speedLimitService.resyncNodeLimiters(nodeId);
            if (!limiterNotes.isEmpty()) {
                log.warn("节点 {} 连接对账限流器：{}", nodeId, String.join("；", limiterNotes));
            }
        } catch (Exception e) {
            log.warn("节点 {} 连接对账异常: {}", nodeId, e.toString());
        } finally {
            reconcilingNodes.remove(nodeId);
        }
    }

    /**
     * 对"以该节点为入口、库里 status=0"的转发逐个补发 PauseService。
     * 只负责把期望态发出去：失败只记日志，不改库、不影响连接建立（节点侧重复 pause 是空操作）。
     * 服务名与 ForwardServiceImpl#buildServiceName 保持一致：forwardId_userId_userTunnelId。
     */
    private void pauseForwardsExpectingPaused(Long nodeId) {
        List<ChainTunnel> ingress = chainTunnelService.list(new QueryWrapper<ChainTunnel>()
                .eq("node_id", nodeId)
                .eq("chain_type", 1));
        if (ingress.isEmpty()) {
            return;
        }

        Set<Integer> tunnelIds = ingress.stream()
                .map(chainTunnel -> chainTunnel.getTunnelId().intValue())
                .collect(Collectors.toSet());

        List<Forward> paused = forwardService.list(new QueryWrapper<Forward>()
                .in("tunnel_id", tunnelIds)
                .eq("status", 0));
        for (Forward forward : paused) {
            UserTunnel userTunnel = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                    .eq("user_id", forward.getUserId())
                    .eq("tunnel_id", forward.getTunnelId()));
            String serviceName = forward.getId() + "_" + forward.getUserId() + "_"
                    + (userTunnel != null ? userTunnel.getId() : 0);
            GostDto gostDto = GostUtil.PauseAndResumeService(nodeId, serviceName, "PauseService");
            String pauseMsg = gostDto == null || gostDto.getMsg() == null ? "节点无响应" : gostDto.getMsg();
            if (GostUtil.notFound(pauseMsg)) {
                // 服务本就不在节点上（配置被清空或已被改名），没有需要暂停的东西，
                // 恢复时由重建路径按库补建，这里不该刷失败日志。
                log.debug("节点 {} 重连对账：暂停服务 {} 在节点上不存在，跳过补发", nodeId, serviceName);
            } else if (!Objects.equals("OK", pauseMsg)) {
                log.info("节点 {} 重连对账：补发暂停 {} 失败: {}", nodeId, serviceName, pauseMsg);
            }
        }
    }

    /** 报表侧调度：send_msg 最长等节点 10 秒，不能占着 WS 线程。 */
    private void pauseForwardsExpectingPausedAsync(Long nodeId) {
        CompletableFuture.runAsync(() -> {
            try {
                pauseForwardsExpectingPaused(nodeId);
            } catch (Exception e) {
                log.info("节点 {} 报表对账异常: {}", nodeId, e.getMessage(), e);
            }
        });
    }

    /**
     * 判定「这一轮连接上旧暂停补发路径还要不要跑」，并登记本轮已处理。
     *
     * <p>两条路径的边界：上报里带 {@link #PAUSED_SUPPORTED_KEY} 哨兵时，节点自报的 {@code <name>@paused}
     * 已经足够让 ForwardServiceImpl#enforcePausedService 把仍在跑的服务停掉，旧路径必须让位；
     * 老节点没有这个维度，只能由旧路径按库补发。判断放在同步段里做，
     * 免得建连线程与报表线程各发一轮（幂等，但白多一轮 WS 消息与日志）。
     *
     * @param digests 本轮摘要，null 表示还没收到上报（建连路径，能力未知）
     * @return true 表示本轮由旧路径补发，调用方负责真正下发
     */
    private static synchronized boolean claimPauseRound(Long nodeId, long epoch, Map<String, String> digests) {
        if (digests != null) {
            if (digests.containsKey(PAUSED_SUPPORTED_KEY)) {
                pausedDimensionNodes.add(nodeId);
            } else {
                // 报了但没有哨兵 = 老版本节点：撤掉缓存，旧路径继续兜底
                pausedDimensionNodes.remove(nodeId);
            }
        }
        if (pausedDimensionNodes.contains(nodeId)) {
            log.debug("节点 {} 支持 paused 维度，跳过旧暂停补发路径", nodeId);
            return false;
        }
        Long reconciled = pauseReconciledEpoch.get(nodeId);
        if (reconciled != null && reconciled == epoch) {
            return false;
        }
        pauseReconciledEpoch.put(nodeId, epoch);
        return true;
    }

    /** 握手时开新一轮纪元。 */
    private static long beginConnectEpoch(Long nodeId) {
        long epoch = connectEpoch.incrementAndGet();
        nodeConnectEpoch.put(nodeId, epoch);
        return epoch;
    }

    /** 取该节点当前连接的纪元；没走过握手（老会话、直接灌报表）就现开一个。 */
    private static long currentConnectEpoch(Long nodeId) {
        Long epoch = nodeConnectEpoch.get(nodeId);
        return epoch != null ? epoch : beginConnectEpoch(nodeId);
    }

    /**
     * 解析节点自报的资源清单（{"type":"ServiceListReport","success":true,"data":{"services":[...],"chains":[...]}}）
     * 并交给后台清扫孤儿。解析失败只记 WARN，不向 WS 线程抛异常。
     */
    private void handleServiceListReport(WebSocketSession session, String payload) {
        Long nodeId;
        List<String> services;
        List<String> chains;
        Map<String, List<String>> limiters;
        Map<String, String> digests;
        Map<String, List<String>> chainDigests;
        Set<String> features;
        try {
            nodeId = Long.valueOf(String.valueOf(session.getAttributes().get("id")));
            JSONObject report = JSON.parseObject(payload);
            if (report == null || !"ServiceListReport".equals(report.getString("type"))) {
                return;
            }
            // 节点自己说这次没数清楚，就别拿它当证据删东西
            if (Boolean.FALSE.equals(report.getBoolean("success"))) {
                log.warn("节点 {} 上报资源清单标记失败，跳过孤儿清扫", nodeId);
                return;
            }
            JSONObject data = report.getJSONObject("data");
            services = toStringList(data == null ? null : data.getJSONArray("services"));
            chains = toStringList(data == null ? null : data.getJSONArray("chains"));
            limiters = toLimiterMap(data == null ? null : data.getJSONArray("limiters"));
            // 能力清单是「节点声明支持哪些摘要维度」，不能从本轮有没有出现某字段去推断：
            // 节点上所有服务都没配网卡时，字段本来就不会出现，推断出来的哨兵永远是假，
            // 「库里配了 interface、节点丢了」会变成永久静默漏判（round39 QA BLOCK）。
            features = toStringSet(data == null ? null : data.getJSONArray("features"));
            digests = toDigestMap(data == null ? null : data.getJSONArray("digests"), features);
            chainDigests = toChainDigestMap(data == null ? null : data.getJSONArray("chainDigests"), features);
        } catch (Exception e) {
            log.warn("解析 ServiceListReport 失败: {}", e.toString());
            return;
        }
        // 记下"它这次连上确实报了"：连接对账靠这个时间戳决定要不要兜底
        lastReportAt.put(nodeId, System.currentTimeMillis());
        // 旧路径的兜底判断（round45）：本轮摘要带 @paused_supported 哨兵时，暂停维度由
        // ForwardServiceImpl#enforcePausedService 按 <name>@paused 对账，旧路径必须让位，
        // 否则同一轮对同一批服务各发一次 PauseService（幂等，但多一轮 WS 消息、日志噪音，
        // 熔断计数还分两个命名空间各记一次）。老节点没有哨兵，继续由旧路径兜底。
        if (claimPauseRound(nodeId, currentConnectEpoch(nodeId), digests)) {
            pauseForwardsExpectingPausedAsync(nodeId);
        }
        sweepOrphansAsync(nodeId, services, chains, limiters, digests, chainDigests);
    }

    private static List<String> toStringList(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>(array.size());
        for (Object item : array) {
            if (item != null) {
                values.add(item.toString());
            }
        }
        return values;
    }

    /**
     * 解析节点自报的限流器清单（name → limits）。
     *
     * <p>字段缺失返回 null——老节点没这个字段，调用方沿用"按库补齐"的原行为；
     * 空数组返回空 Map——这次清单不可信，与孤儿清扫同一口径，整段跳过不补发。
     * 同名只认第一个：同一次上报里同一个限流器绝不重复处理。
     */
    private static Map<String, List<String>> toLimiterMap(JSONArray array) {
        if (array == null) {
            return null;
        }
        Map<String, List<String>> limiters = new LinkedHashMap<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (!(item instanceof JSONObject)) {
                continue;
            }
            JSONObject entry = (JSONObject) item;
            String name = entry.getString("name");
            if (StringUtils.isBlank(name) || limiters.containsKey(name)) {
                continue;
            }
            limiters.put(name, toStringList(entry.getJSONArray("limits")));
        }
        return limiters;
    }

    /**
     * 摘要映射里的哨兵键：表示节点这一版二进制支持该维度。
     *
     * <p>判据有两条，按 OR 合并：① 节点在 {@code features} 里显式声明（round39 起）；
     * ② 本轮至少一条摘要真的带了这个字段（老协议兼容）。只靠 ② 会漏判——节点上所有服务
     * 都没配网卡时字段本就不出现，推断出来的哨兵永远是假，「库里配了 interface、节点丢了」
     * 永远发现不了（round39 QA BLOCK）。老节点两条都不满足 → 整维度跳过。
     */
    private static final String LISTENER_SUPPORTED_KEY = "@listener_supported";
    private static final String IFACE_SUPPORTED_KEY = "@iface_supported";
    /** 转发策略档位（forwarder.selector）维度的哨兵：本轮出现过 selector 或 features 声明 fselector。 */
    private static final String FSELECTOR_SUPPORTED_KEY = "@fselector_supported";
    /**
     * 暂停标志（metadata.paused）维度的哨兵：本轮任一服务报了 {@code paused} 字段，或 features 声明 paused。
     *
     * <p>与 listener/iface 同一口径：老节点两条都不满足 → 没有哨兵 → 上层整维度跳过。
     * 绝不能用「没报 paused」推出「没暂停」——那会把库里暂停中、节点上仍在收流量的服务判成「一致」。
     */
    private static final String PAUSED_SUPPORTED_KEY = "@paused_supported";
    /**
     * 「配置真的全空也会上报」维度的哨兵：节点在 features 里声明 emptyReport（round46 起）。
     *
     * <p>老节点在 services/chains 双空时压根不上报，收到空清单只能当成脏数据丢弃；
     * 新节点全空时照样上报，摘要列表本身就是空的，哨兵只能靠 features 声明带出去。
     */
    private static final String EMPTY_REPORT_SUPPORTED_KEY = "@emptyReport_supported";

    /** 链摘要映射里的哨兵键：节点声明支持 hop.interface 维度（值为单元素列表）。 */
    private static final String CHAIN_IFACE_SUPPORTED_KEY = "@chain_iface_supported";

    /** 链摘要列表里 {@code iface=<hop0>;<hop1>} 元素的前缀，与 {@code selector=} 同风格。 */
    private static final String CHAIN_IFACE_PREFIX = "iface=";

    /**
     * 解析节点自报的服务配置摘要（name → 规范化摘要串）。
     *
     * <p>缺失或为空一律返回 null：老节点没这个字段，摘要为空说明这次上报没带上，
     * 两种都只能退回「只比服务名」的原行为，绝不能凭空的摘要去判漂移、删人家在跑的服务。
     * 同名只认第一个（与限流器同一口径）。
     */
    /** 无能力声明的老入口：两个新维度按「本轮是否出现字段」推断（round39 之前的调用方与测试）。 */
    private static Map<String, String> toDigestMap(JSONArray array) {
        return toDigestMap(array, null);
    }

    private static Map<String, String> toDigestMap(JSONArray array, Set<String> features) {
        // 节点声明 emptyReport 表示「配置全空也照常上报」。这种节点在真的全空时摘要列表本来就是空的，
        // 哨兵只能靠 features 带出去；否则上层会把这份合法上报当脏数据丢掉（round46）。
        boolean emptyReportSupported = features != null && features.contains("emptyReport");
        if (array == null || array.isEmpty()) {
            if (!emptyReportSupported) {
                return null;
            }
            Map<String, String> sentinelOnly = new LinkedHashMap<>(2);
            sentinelOnly.put(EMPTY_REPORT_SUPPORTED_KEY, "1");
            return sentinelOnly;
        }
        Map<String, String> digests = new LinkedHashMap<>(array.size());
        boolean listenerReported = false;
        boolean ifaceReported = false;
        boolean fselectorReported = false;
        boolean pausedReported = false;
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (!(item instanceof JSONObject)) {
                continue;
            }
            JSONObject entry = (JSONObject) item;
            String name = entry.getString("name");
            if (StringUtils.isBlank(name) || digests.containsKey(name)) {
                continue;
            }
            // 节点报的是全部 forwarder 目标（go-gost websocket_reporter.go 按 forwarder.nodes 顺序全量上报），
            // 摘要按同一顺序全量比对：少一条/多一条/换顺序都是另一套负载均衡行为
            List<String> targets = toStringList(entry.getJSONArray("targets"));
            digests.put(name, GostUtil.normalizeServiceDigestList(entry.getString("addr"),
                    entry.getString("handler"), entry.getString("chain"), targets));
            // 转发策略单独占一个 key（name@strategy），不进四段摘要串：
            // 老节点不报这个字段，读不到就跳过该维度；新节点的四段摘要与旧版逐字一致，不会全员惊群。
            String strategy = entry.getString("strategy");
            if (StringUtils.isNotBlank(strategy)) {
                digests.put(name + "@strategy", strategy.trim().toLowerCase(java.util.Locale.ROOT));
            }
            // 监听器类型（name@listener）：<type>，udp 且 keepAlive=true 时节点报 "udp|keepAlive=true"
            String listener = entry.getString("listener");
            if (StringUtils.isNotBlank(listener)) {
                digests.put(name + "@listener", listener.trim().toLowerCase(java.util.Locale.ROOT));
                listenerReported = true;
            }
            // 转发策略档位（name@fselector）：<strategy>|<maxFails>|<failTimeoutNanos>，例 fifo|1|600000000000。
            // 节点只给配了 forwarder 的服务报这个字段，读不到就跳过该维度。
            String selector = entry.getString("selector");
            if (StringUtils.isNotBlank(selector)) {
                digests.put(name + "@fselector", selector.trim().toLowerCase(java.util.Locale.ROOT));
                fselectorReported = true;
            }
            // 出站网卡（name@iface）：metadata.interface 原样，只去首尾空格（网卡名大小写敏感）
            String iface = entry.getString("iface");
            if (StringUtils.isNotBlank(iface)) {
                digests.put(name + "@iface", iface.trim());
                ifaceReported = true;
            }
            // 暂停标志（name@paused）：<1|0>。true 与 false 都要落键——"0" 是节点明确说
            // 「这个服务在跑」，与「老节点没这个字段」必须是两回事，后者靠哨兵整维度跳过。
            Boolean paused = entry.getBoolean("paused");
            if (paused != null) {
                digests.put(name + "@paused", Boolean.TRUE.equals(paused) ? "1" : "0");
                pausedReported = true;
            }
        }
        // 哨兵键：能力声明 OR 本轮真的报了字段。老节点两条都不满足 → 没有哨兵 →
        // 对账侧完全跳过该维度，绝不能凭缺失判漂移去删服务。
        if (listenerReported || (features != null && features.contains("listener"))) {
            digests.put(LISTENER_SUPPORTED_KEY, "true");
        }
        if (ifaceReported || (features != null && features.contains("iface"))) {
            digests.put(IFACE_SUPPORTED_KEY, "true");
        }
        // selector 只在服务配了 forwarder 时才出现，单靠「本轮有没有字段」会漏判
        // 「库里配了策略、节点上整条 forwarder 丢了」；features 声明与字段出现按 OR 合并。
        if (fselectorReported || (features != null && features.contains("fselector"))) {
            digests.put(FSELECTOR_SUPPORTED_KEY, "true");
        }
        // 暂停标志只在有服务暂停/被显式标记时才出现，单靠字段出现会漏判
        // 「库里暂停、节点上整条 metadata.paused 丢了」；features 声明与字段出现按 OR 合并。
        if (pausedReported || (features != null && features.contains("paused"))) {
            digests.put(PAUSED_SUPPORTED_KEY, "true");
        }
        if (emptyReportSupported) {
            digests.put(EMPTY_REPORT_SUPPORTED_KEY, "1");
        }
        return digests.isEmpty() ? null : digests;
    }

    /**
     * 解析节点自报的链摘要（链名 → 下一跳节点列表，形如 {@code node_2@1.2.3.4:30202#tls}）。
     *
     * <p>缺失或为空同样返回 null：老节点没这个字段，只能退回「只比链名」的原行为；
     * 凭一个空列表去判漂移会把每条链都删了重建。同名只认第一个。
     *
     * <p>节点还报了 {@code selector}（{@code <strategy>|<maxFails>|<failTimeout>}）时，
     * 在列表末尾追加一个 {@code selector=<值>} 元素带出去——跳点与负载策略放在同一个列表里，
     * 调用方只认前缀，老节点不报就不追加，序列长度与 round34 完全一致。
     *
     * <p>节点报了 {@code ifaces}（{@code <hop0>;<hop1>}，空 hop 写 {@code -}）时，同样在末尾追加
     * 一个 {@code iface=<值>} 元素。整条链都没有 interface 的节点不上报该字段，元素就不出现。
     */
    /** 无能力声明的老入口，同 {@link #toDigestMap(JSONArray)}。 */
    private static Map<String, List<String>> toChainDigestMap(JSONArray array) {
        return toChainDigestMap(array, null);
    }

    private static Map<String, List<String>> toChainDigestMap(JSONArray array, Set<String> features) {
        if (array == null || array.isEmpty()) {
            return null;
        }
        Map<String, List<String>> digests = new LinkedHashMap<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (!(item instanceof JSONObject)) {
                continue;
            }
            JSONObject entry = (JSONObject) item;
            String name = entry.getString("name");
            if (StringUtils.isBlank(name) || digests.containsKey(name)) {
                continue;
            }
            List<String> nodes = toStringList(entry.getJSONArray("nodes"));
            String selector = entry.getString("selector");
            // 各 hop 的出站网卡（"<hop0>;<hop1>"，空 hop 写 "-"）：整条链都没有 interface 时节点不上报
            String ifaces = entry.getString("ifaces");
            if (StringUtils.isNotBlank(selector) || StringUtils.isNotBlank(ifaces)) {
                // toStringList 无节点时给的是不可变空列表，必须复制一份再追加；
                // 两种扩展元素都只在节点确实报了才追加，序列长度与 round34/36 完全一致
                List<String> withExtras = new ArrayList<>(nodes.size() + 2);
                withExtras.addAll(nodes);
                if (StringUtils.isNotBlank(selector)) {
                    withExtras.add("selector=" + selector.trim());
                }
                if (StringUtils.isNotBlank(ifaces)) {
                    withExtras.add(CHAIN_IFACE_PREFIX + ifaces.trim());
                }
                nodes = withExtras;
            }
            digests.put(name, nodes);
        }
        // 哨兵：节点声明支持 hop.interface 维度。这条链本次没报 ifaces 只代表「所有 hop 都没配网卡」，
        // 不代表节点不支持——没有哨兵，库里有网卡而节点丢了就会永久静默漏判。
        if (features != null && features.contains("chainIface")) {
            digests.put(CHAIN_IFACE_SUPPORTED_KEY, new ArrayList<>(Collections.singletonList("1")));
        }
        return digests.isEmpty() ? null : digests;
    }

    /** 节点上报的能力清单；缺失（老节点）返回空集合，调用方据此整维度跳过。 */
    private static Set<String> toStringSet(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<>(array.size());
        for (Object item : array) {
            if (item == null) {
                continue;
            }
            String text = item.toString().trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return values;
    }

    /**
     * 孤儿清扫是库查询 + 逐条下发（每次等节点 10s），不能占用 WS 消息线程。
     * 同一节点同一时刻只跑一轮，重复上报直接丢弃（下一次上报还会重新扫）。
     */
    private void sweepOrphansAsync(Long nodeId, List<String> services, List<String> chains,
                                   Map<String, List<String>> reportedLimiters,
                                   Map<String, String> reportedDigests,
                                   Map<String, List<String>> reportedChainDigests) {
        CompletableFuture.runAsync(() -> {
            if (!sweepingNodes.add(nodeId)) {
                log.info("节点 {} 已有孤儿清扫在进行，跳过本次上报", nodeId);
                return;
            }
            try {
                NodeOrphanSweeper sweeper = new NodeOrphanSweeper(
                        (nId, fId) -> isForwardActiveOnNode(nId, fId),
                        (nId, tId) -> isTunnelServiceNeededOnNode(nId, tId),
                        (nId, tId) -> isTunnelChainNeededOnNode(nId, tId),
                        (id, names) -> "OK".equals(GostUtil.DeleteService(id, names).getMsg()),
                        (id, name) -> "OK".equals(GostUtil.DeleteChains(id, name).getMsg()));
                NodeOrphanSweeper.SweepResult result = sweeper.sweep(nodeId, services, chains);
                log.info("节点 {} 孤儿清扫：checked={} deleted={} failed={} 已删={}",
                        nodeId, result.getChecked(), result.getDeleted(), result.getFailed(), result.getDeletedNames());
                if (result.getFailed() > 0) {
                    log.warn("节点 {} 孤儿清扫有 {} 项失败（失败的已跳过，不影响其余条目）", nodeId, result.getFailed());
                }
                // 清扫只管"节点有、库里没有"；反向的"库里有、节点没有"要靠补齐，
                // 否则节点丢了 gost.json 后活跃转发静默失效，面板还显示正常。
                // chains 必须一起传：中继链缺失时入口/链/出口三段的监听都指向不存在的链。
                try {
                    List<String> repaired = forwardService.resyncNodeServices(
                            nodeId, services, chains, reportedDigests, reportedChainDigests);
                    if (!repaired.isEmpty()) {
                        log.warn("节点 {} 服务补齐：{}", nodeId, String.join("；", repaired));
                    }
                } catch (Exception e) {
                    log.warn("节点 {} 服务补齐异常: {}", nodeId, e.toString());
                }
                // 限流器同样只在创建时下发过一次：节点丢配置后 limiters 整段消失，
                // 服务里的 "limiter":"<id>" 引用还在，gost 查不到限流器就既不报错也不限速。
                // 节点报了 limiters 就以自报值为准逐条比对速率（缺失补齐、不一致纠正、一致不动），
                // 没报（老节点）退回"按库补齐"；报了空清单说明这次不可信，整段跳过（与孤儿清扫同一口径）。
                try {
                    if (reportedLimiters == null) {
                        List<String> limiterNotes = speedLimitService.resyncNodeLimiters(nodeId);
                        if (!limiterNotes.isEmpty()) {
                            log.warn("节点 {} 限流器补齐：{}", nodeId, String.join("；", limiterNotes));
                        }
                    } else {
                        for (String note : speedLimitService.reconcileReportedLimiters(nodeId, reportedLimiters)) {
                            log.warn("{}", note);
                        }
                    }
                } catch (Exception e) {
                    log.warn("节点 {} 限流器对账异常: {}", nodeId, e.toString());
                }
            } catch (Exception e) {
                log.warn("节点 {} 孤儿清扫异常: {}", nodeId, e.toString());
            } finally {
                sweepingNodes.remove(nodeId);
            }
        });
    }

    /**
     * 判定该转发服务是否应当运行在该节点上。
     * 必须满足：转发记录在库且未删除；并且在 forward_port 表中明确分配给该节点。
     * 若未注入 forwardPortService（轻量或部分单元测试环境），降级为全量库存在检查。
     */
    public boolean isForwardActiveOnNode(Long nodeId, Long forwardId) {
        try {
            if (forwardId == null || nodeId == null) return false;
            if (forwardService == null) return false;
            Forward forward = forwardService.getById(forwardId);
            if (forward == null) return false;
            if (forwardPortService == null) {
                return true;
            }
            return forwardPortService.count(new QueryWrapper<ForwardPort>()
                    .eq("forward_id", forwardId).eq("node_id", nodeId)) > 0;
        } catch (Exception e) {
            log.warn("节点孤儿清扫：查转发 {} 归属节点 {} 失败，保守保留: {}", forwardId, nodeId, e.toString());
            return true;
        }
    }

    /**
     * 判定该节点是否应当运行隧道的 &lt;tunnelId&gt;_tls 监听服务。
     * 必须满足：隧道存在且为中继隧道（type != 1），且该节点在 chain_tunnel 中担任中继（chain_type=2）或出口（chain_type=3）。
     * 入口节点（chain_type=1）严禁存在 _tls 监听。
     * 若未注入 chainTunnelService，降级为 tunnelNeedsChain。
     */
    public boolean isTunnelServiceNeededOnNode(Long nodeId, Long tunnelId) {
        try {
            if (tunnelId == null || nodeId == null) return false;
            if (tunnelService == null) return false;
            Tunnel tunnel = tunnelService.getById(tunnelId);
            if (tunnel == null) return false;
            if (tunnel.getType() != null && tunnel.getType() == 1) {
                return false; // 直连隧道不需要任何中继服务
            }
            if (chainTunnelService == null) {
                return tunnelNeedsChain(tunnelId);
            }
            return chainTunnelService.count(new QueryWrapper<ChainTunnel>()
                    .eq("tunnel_id", tunnelId).eq("node_id", nodeId).in("chain_type", 2, 3)) > 0;
        } catch (Exception e) {
            log.warn("节点孤儿清扫：查隧道 {} 监听服务归属节点 {} 失败，保守保留: {}", tunnelId, nodeId, e.toString());
            return true;
        }
    }

    /**
     * 判定该节点是否应当包含隧道的 chains_&lt;tunnelId&gt; / hop_&lt;tunnelId&gt; 链路配置。
     * 必须满足：隧道存在且为中继隧道（type != 1），且该节点在 chain_tunnel 中担任入口（chain_type=1）或中继（chain_type=2）。
     * 出口节点（chain_type=3）严禁存在 chains_&lt;tunnelId&gt;。
     * 若未注入 chainTunnelService，降级为 tunnelNeedsChain。
     */
    public boolean isTunnelChainNeededOnNode(Long nodeId, Long tunnelId) {
        try {
            if (tunnelId == null || nodeId == null) return false;
            if (tunnelService == null) return false;
            Tunnel tunnel = tunnelService.getById(tunnelId);
            if (tunnel == null) return false;
            if (tunnel.getType() != null && tunnel.getType() == 1) {
                return false; // 直连隧道不需要中继链路
            }
            if (chainTunnelService == null) {
                return tunnelNeedsChain(tunnelId);
            }
            return chainTunnelService.count(new QueryWrapper<ChainTunnel>()
                    .eq("tunnel_id", tunnelId).eq("node_id", nodeId).in("chain_type", 1, 2)) > 0;
        } catch (Exception e) {
            log.warn("节点孤儿清扫：查隧道 {} 链路归属节点 {} 失败，保守保留: {}", tunnelId, nodeId, e.toString());
            return true;
        }
    }

    /**
     * 节点上的 &lt;tunnelId&gt;_tls / chains_&lt;tunnelId&gt; / hop_&lt;tunnelId&gt; 是否还应当存在。
     *
     * <p>只有"隧道转发"（type=2）才需要这两类配套资源；直连（type=1）走单跳，节点上本不该有它们。
     * 隧道类型从 2 改成 1 后行还在，光看"行是否存在"会把节点上的残留链路判成活着并永久保留 ——
     * 端口一直被占着（bind: address already in use），面板再也选不到。
     *
     * <p>类型为空（历史行没填 type）时无法判断这条隧道该不该有中继资源，一律按"需要"处理并打 WARN：
     * 误删在用的 chains_&lt;id&gt; / &lt;id&gt;_tls 会直接把线上链路打断，多留一条残留最多占个名字。
     * 只有明确 type=1（直连）才判定为残留。
     *
     * <p>查库异常一律返回 true（保留）：宁可少删一条，也不能因为一次查询抖动把在用的链路删掉。
     */
    private boolean tunnelNeedsChain(long tunnelId) {
        try {
            Tunnel tunnel = tunnelService.getById(tunnelId);
            if (tunnel == null) {
                return true;
            }
            Integer type = tunnel.getType();
            if (type == null) {
                log.warn("节点孤儿清扫：隧道 {} 类型为空，无法判断是否需要中继资源，保守保留", tunnelId);
                return true;
            }
            return type != 1;
        } catch (Exception e) {
            log.warn("节点孤儿清扫：查隧道 {} 类型失败，按需要保留处理: {}", tunnelId, e.toString());
            return true;
        }
    }

    // 连接关闭后
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        try {
            String id = session.getAttributes().get("id").toString();
            String type = session.getAttributes().get("type").toString();
            String sessionId = session.getId();
            
            log.info("连接关闭，ID: {}, 类型: {}, 状态: {}", id, type, status);
            
            if (!Objects.equals(type, "1")) {
                // 管理员连接关闭
                boolean removed = activeSessions.remove(session);
                log.info("管理员连接关闭，sessionId: {}, 移除结果: {}", sessionId, removed);
            } else {
                // 客户端节点连接关闭
                Long nodeId = Long.valueOf(id);
                
                // 验证当前会话是否还是活跃会话（关键：这里会自动过滤掉被覆盖的旧连接）
                WebSocketSession currentSession = nodeSessions.get(nodeId);
                if (currentSession == null || !currentSession.equals(session)) {
                    log.info("节点 {} 连接关闭，但已有新连接或会话不匹配，跳过状态更新", nodeId);
                    sessionLocks.remove(sessionId);
                    return;
                }
                
                log.info("节点 {} 当前活跃连接关闭，开始验证并更新状态", nodeId);
                
                    nodeSessions.remove(nodeId);
                    forgetNode(nodeId);
                    
                    // 更新节点状态为离线
                    Node node = nodeService.getById(nodeId);
                    if (node != null) {
                        node.setStatus(0);
                        boolean updateResult = nodeService.updateById(node);
                        
                        if (updateResult) {
                            log.info("节点 {} 状态更新为离线成功", nodeId);
                            
                            JSONObject res = new JSONObject();
                            res.put("id", id);
                            res.put("type", "status");
                            res.put("data", 0);
                            broadcastMessage(res.toJSONString());
                        } else {
                            log.info("节点 {} 状态更新为离线失败", nodeId);
                        }
                    } else {
                        log.info("节点 {} 不存在，无法更新离线状态", nodeId);
                    }
            }
            
            // 清理session锁对象
            sessionLocks.remove(sessionId);

        } catch (Exception e) {
            log.info("关闭连接时发生异常: {}", e.getMessage(), e);
        }
    }

    // 点对点发送消息
    @SneakyThrows
    public static void sendToUser(WebSocketSession socketSession, String message) {
        sendToUser(socketSession, message, null);
    }

    // 点对点发送消息（支持加密）
    @SneakyThrows
    public static void sendToUser(WebSocketSession socketSession, String message, String nodeSecret) {
        if (socketSession != null && socketSession.isOpen()) {
            String sessionId = socketSession.getId();
            Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
            
            synchronized (lock) {
                try {
                    if (socketSession.isOpen()) {
                        // 如果是节点连接且有密钥，尝试加密消息
                        String finalMessage = message;
                        if (nodeSecret != null && !nodeSecret.isEmpty()) {
                            String type = (String) socketSession.getAttributes().get("type");
                            if ("1".equals(type)) { // 节点连接
                                finalMessage = encryptMessageIfPossible(message, nodeSecret);
                            }
                        }
                        socketSession.sendMessage(new TextMessage(finalMessage));
                    }
                } catch (Exception e) {
                    log.info("发送WebSocket消息失败 [sessionId={}]: {}", sessionId, e.getMessage());
                    cleanupSession(socketSession);
                }
            }
        } else {
            cleanupSession(socketSession);
        }
    }
    
    /**
     * 清理失效的session，自动识别是节点session还是管理员session
     */
    private static void cleanupSession(WebSocketSession session) {
        if (session == null) return;
        
        String sessionId = session.getId();
        
        // 清理session锁
        sessionLocks.remove(sessionId);
        
        boolean removedFromAdmin = activeSessions.remove(session);
        
        if (!removedFromAdmin) {
            nodeSessions.entrySet().removeIf(entry -> {
                if (entry.getValue() == session) {
                    return true;
                }
                return false;
            });
        }
    }

    // 广播消息
    public static void broadcastMessage(String message) {
        for (WebSocketSession session : activeSessions) {
            sendToUser(session, message);
        }
    }



    /**
     * 主动向节点索取一次资源清单（call/info_request）。
     *
     * <p>节点收到后会把 gost.json 里的 services/chains 重新上报，随后由 NodeOrphanSweeper
     * 回收"库里已经没有对应记录"的残留资源。删除失败、强制删除之后都靠它把节点拉回来对账，
     * 否则孤儿监听要等下一次节点重连才会被清掉。不带 requestId，也不等待响应。
     */
    public static boolean requestServiceList(Long nodeId) {
        if (nodeId == null) {
            return false;
        }
        WebSocketSession nodeSession = nodeSessions.get(nodeId);
        if (nodeSession == null || !nodeSession.isOpen()) {
            return false;
        }
        JSONObject payload = new JSONObject();
        payload.put("type", "call");
        payload.put("callType", "info_request");
        payload.put("data", new JSONObject());
        String nodeSecret = (String) nodeSession.getAttributes().get("nodeSecret");
        try {
            sendToUser(nodeSession, payload.toJSONString(), nodeSecret);
            return true;
        } catch (Exception e) {
            log.info("向节点 {} 索取资源清单失败: {}", nodeId, e.getMessage());
            return false;
        }
    }

    /**
     * 节点当前是否可下发命令。
     *
     * <p>多节点批量变更（限速器增删改、隧道拓扑等）必须在下发前用它做一次整体预检：
     * 只要有一个节点不在线就整单拒绝，避免"前几个节点改了、后面的失败"这种部分生效。
     * 真正的下发仍然交给 send_msg，未在线节点会在那里再拦一次。
     */
    public static boolean isNodeOnline(Long nodeId) {
        if (nodeId == null) {
            return false;
        }
        WebSocketSession nodeSession = nodeSessions.get(nodeId);
        if (nodeSession == null || !nodeSession.isOpen()) {
            return false;
        }
        // 半死连接：网络黑洞/容器 pause 时 TCP 不会立刻 FIN，isOpen() 仍为 true。
        // 只有收到过消息的节点才用静默时长判定；无记录（测试直接塞会话、刚连上还没发第一条心跳）时回落到 isOpen。
        Long seen = nodeLastSeen.get(nodeId);
        if (seen != null && System.currentTimeMillis() - seen > NODE_STALE_MS) {
            return false;
        }
        return true;
    }

    /** 记录节点最近一次活动时间；id 非法或为空时忽略。 */
    static void touchNode(String nodeIdText) {
        if (nodeIdText == null || nodeIdText.isBlank()) {
            return;
        }
        try {
            nodeLastSeen.put(Long.parseLong(nodeIdText.trim()), System.currentTimeMillis());
        } catch (NumberFormatException ignored) {
            // id 不是数字说明不是节点会话（例如前端调试连接），不做活动记录
        }
    }

    /** 节点静默时长（毫秒），无记录返回 -1：排障时用来解释「明明连着为什么判离线」。 */
    public static long nodeSilenceMs(Long nodeId) {
        Long seen = nodeId == null ? null : nodeLastSeen.get(nodeId);
        return seen == null ? -1L : System.currentTimeMillis() - seen;
    }

    /** 会话关闭时清掉活动记录，避免同 id 重新连上前被旧时间戳误判。 */
    static void forgetNode(Long nodeId) {
        if (nodeId != null) {
            nodeLastSeen.remove(nodeId);
            // 连接没了，本轮纪元作废；pausedDimensionNodes 是能力缓存，跨重连保留
            nodeConnectEpoch.remove(nodeId);
            pauseReconciledEpoch.remove(nodeId);
        }
    }

    public static GostDto send_msg(Long node_id, Object msg, String type) {
        WebSocketSession nodeSession = nodeSessions.get(node_id);

        if (nodeSession == null) {
            log.info("发送消息失败：节点 {} 不在线或会话不存在", node_id);
            GostDto result = new GostDto();
            result.setMsg("节点不在线");
            return result;
        }

        if (!nodeSession.isOpen()) {
            log.info("发送消息失败：节点 {} 连接已断开，清理会话", node_id);
            nodeSessions.remove(node_id);
            forgetNode(node_id);
            sessionLocks.remove(nodeSession.getId());
            GostDto result = new GostDto();
            result.setMsg("节点连接已断开");
            return result;
        }

        // 生成唯一的请求ID
        String requestId = UUID.randomUUID().toString();
        
        // 创建CompletableFuture用于等待响应
        CompletableFuture<GostDto> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        
        // 获取节点密钥用于加密
        String nodeSecret = (String) nodeSession.getAttributes().get("nodeSecret");

        try {
            JSONObject data = new JSONObject();
            data.put("type", type);
            data.put("data", msg);
            data.put("requestId", requestId);
            sendToUser(nodeSession, data.toJSONString(), nodeSecret);
            GostDto result = future.get(10, TimeUnit.SECONDS);
            
            log.info("成功发送消息到节点 {} 并收到响应: {}", node_id, result.getMsg());
            return result;
            
        } catch (Exception e) {
            // 清理请求和映射关系
            pendingRequests.remove(requestId);

            GostDto result = new GostDto();
            if (e instanceof java.util.concurrent.TimeoutException) {
                result.setMsg("等待响应超时");
                log.info("节点 {} 响应超时，可能存在连接问题", node_id);
            } else {
                result.setMsg("发送消息失败: " + e.getMessage());
                log.info("发送消息到节点 {} 失败: {}", node_id, e.getMessage(), e);
            }
            return result;
        }
    }

    
}
