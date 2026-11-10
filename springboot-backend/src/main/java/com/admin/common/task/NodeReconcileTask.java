package com.admin.common.task;

import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/**
 * 定期向在线节点索取一次资源清单（下行 call/info_request），让节点主动上报，
 * 从而在"没有任何管理操作、节点也没重连"的情况下也能跑一轮对账。
 *
 * <p>补的缺口：orphan 清扫与"库里有、节点没有 / 配置漂移"的补齐原本只在节点上报时触发，
 * 而上报只在建连、backend 主动索取、以及删除/强制删除之后的 requestServiceList 里发生。
 * 于是三种情况会一直静默：① 重建失败（删掉了、建失败）后节点不再重连；② 有人在节点上直接改
 * gost.json 但不重启（下次上报要等到不知什么时候）；③ 节点容器被原地替换但 WS 没断。
 *
 * <p>成本：一轮只发 N 条 WS 消息（N=在线节点数），真正的清扫/补齐在节点上报后异步跑，
 * 且 sweepOrphansAsync 对同一节点做了并发去重，不会叠加。
 *
 * <p>可用 flux.reconcile.enabled=false 关掉；间隔用 flux.reconcile.interval-ms 调（默认 5 分钟）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "flux.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class NodeReconcileTask {

    private final NodeService nodeService;

    /** 下发钩子，默认走 WS；测试里替换成计数器，避免依赖真实连接。 */
    private LongFunction<Boolean> requester = WebSocketServer::requestServiceList;

    /** 在线判定钩子，默认走 WS 会话表；同上。 */
    private LongPredicate onlineCheck = WebSocketServer::isNodeOnline;

    @Autowired
    public NodeReconcileTask(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    /** 仅供测试替换下发钩子。 */
    public void setRequester(LongFunction<Boolean> requester) {
        this.requester = requester;
    }

    /** 仅供测试替换在线判定钩子。 */
    public void setOnlineCheck(LongPredicate onlineCheck) {
        this.onlineCheck = onlineCheck;
    }

    @Scheduled(fixedDelayString = "${flux.reconcile.interval-ms:300000}",
            initialDelayString = "${flux.reconcile.initial-delay-ms:60000}")
    public void reconcile() {
        try {
            reconcileOnce();
        } catch (RuntimeException e) {
            // 定时任务里抛异常会被调度器吞掉并停止后续执行，这里必须自己兜住
            log.warn("节点对账轮询异常：{}", e.getMessage());
        }
    }

    /**
     * 跑一轮：给每个在线节点发一次 info_request。
     *
     * @return 成功发出的节点数
     */
    public int reconcileOnce() {
        List<Node> nodes;
        try {
            nodes = nodeService.list();
        } catch (RuntimeException e) {
            log.warn("节点对账轮询查询节点失败：{}", e.getMessage());
            return 0;
        }
        if (nodes == null || nodes.isEmpty()) {
            return 0;
        }
        List<Long> offline = new ArrayList<>();
        int sent = 0;
        for (Node node : nodes) {
            if (node == null || node.getId() == null) {
                continue;
            }
            Long nodeId = node.getId();
            boolean online;
            try {
                online = onlineCheck.test(nodeId);
            } catch (RuntimeException e) {
                log.warn("节点 {} 在线判定异常，跳过本轮：{}", nodeId, e.getMessage());
                continue;
            }
            if (!online) {
                offline.add(nodeId);
                continue;
            }
            try {
                if (Boolean.TRUE.equals(requester.apply(nodeId))) {
                    sent++;
                }
            } catch (RuntimeException e) {
                // 单个节点发不出去不影响其余节点
                log.warn("向节点 {} 索取资源清单异常：{}", nodeId, e.getMessage());
            }
        }
        if (sent > 0) {
            log.info("节点对账轮询：已向 {} 个在线节点索取资源清单（离线 {} 个：{}）", sent, offline.size(), offline);
        } else {
            log.debug("节点对账轮询：无在线节点（离线 {} 个：{}）", offline.size(), offline);
        }
        return sent;
    }
}
