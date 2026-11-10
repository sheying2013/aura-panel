package com.admin.common.task;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.entity.*;
import com.admin.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 节点配置上报后的巡检。
 *
 * <p>本类只做「数据库里查不到同名记录」的告警，不向节点下发任何删除命令：
 * 上报内容来自节点自身，缺库既可能是创建失败留下的残留，也可能是同名冲突资源，
 * 还可能是下发超时未落库的资源。缺库本身不构成「该资源由本系统创建」的所有权证据，
 * 因此不能据此回收。能确认所有权的删除只发生在创建失败的回滚路径上
 * （TunnelServiceImpl / ForwardServiceImpl），不在本类里。
 */
@Slf4j
@Service
public class CheckGostConfigAsync {

    private static final String WEB_API_SERVICE = "web_api";

    @Resource
    private NodeService nodeService;

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    @Lazy
    private SpeedLimitService speedLimitService;

    @Resource
    TunnelService tunnelService;



    /**
     * 巡检节点上报的配置项：缺库项只告警，不执行远端删除。
     */
    @Async
    public void cleanNodeConfigs(String node_id, GostConfigDto gostConfig) {
        Node node = nodeService.getById(node_id);
        if (node == null) {
            return;
        }
        if (gostConfig == null) {
            log.warn("配置巡检：节点 {} 上报的配置为空，跳过本轮巡检；不做任何远端操作", node.getId());
            return;
        }
        warnOrphanedServices(gostConfig.getServices(), node);
        warnOrphanedChains(gostConfig.getChains(), node);
        warnOrphanedLimiters(gostConfig.getLimiters(), node);
    }

    /**
     * 巡检服务：{forward_id}_tls 由隧道下发，{forward_id}_{user_id}_{port}_{tcp|udp} 由端口转发下发。
     */
    private void warnOrphanedServices(List<ConfigItem> configItems, Node node) {
        if (configItems == null) return;
        for (ConfigItem service : configItems) {
            safeExecute(() -> {

                if (!Objects.equals(service.getName(), WEB_API_SERVICE)) {
                    List<String> serviceIds = parseServiceName(service.getName());
                    if (serviceIds.isEmpty()) {
                        logUnparsable("服务", service.getName(), node);
                        return;
                    }
                    String suffix = serviceIds.getLast();

                    if (Objects.equals(suffix, "tls")) {
                        if (serviceIds.size() != 2) {
                            logUnparsable("服务", service.getName(), node);
                            return;
                        }
                        if (tunnelService.getById(serviceIds.getFirst()) == null) {
                            logMissing("服务", service.getName(), "隧道", serviceIds.getFirst(), node);
                        }
                        return;
                    }

                    if (Objects.equals(suffix, "tcp") || Objects.equals(suffix, "udp")) {
                        if (serviceIds.size() != 4) {
                            logUnparsable("服务", service.getName(), node);
                            return;
                        }
                        if (forwardService.getById(serviceIds.getFirst()) == null) {
                            logMissing("服务", service.getName(), "端口转发", serviceIds.getFirst(), node);
                        }
                    }

                }
            }, "巡检服务 " + service.getName());
        }

    }

    /**
     * 巡检链：chains_{tunnel_id}
     */
    private void warnOrphanedChains(List<ConfigItem> configItems, Node node) {
        if (configItems == null) return;
        for (ConfigItem chain : configItems) {
            safeExecute(() -> {
                List<String> chainIds = parseServiceName(chain.getName());
                if (chainIds.size() != 2 || !Objects.equals(chainIds.getFirst(), "chains")) {
                    logUnparsable("链", chain.getName(), node);
                    return;
                }
                String tunnelId = chainIds.getLast();
                if (tunnelService.getById(tunnelId) == null) {
                    logMissing("链", chain.getName(), "隧道", tunnelId, node);
                }
            }, "巡检链 " + chain.getName());
        }
    }

    /**
     * 巡检限流器：名称即 SpeedLimit 主键
     */
    private void warnOrphanedLimiters(List<ConfigItem> configItems, Node node) {
        if (configItems == null) return;


        for (ConfigItem limiter : configItems) {
            safeExecute(() -> {
                Long limiterId = parseLimiterId(limiter.getName());
                if (limiterId == null) {
                    logUnparsable("限流器", limiter.getName(), node);
                    return;
                }
                if (speedLimitService.getById(limiterId) == null) {
                    logMissing("限流器", limiter.getName(), "限速记录", limiterId.toString(), node);
                }
            }, "巡检限流器 " + limiter.getName());
        }
    }

    /**
     * 缺库只是线索，不是所有权证据：这里只记录告警，不下发删除，也不声称资源已经回收。
     */
    private void logMissing(String kind, String name, String recordKind, String recordId, Node node) {
        log.warn("配置巡检：{} {} 在数据库中无对应{} {}（节点: {}），仅告警，未做远端删除；"
                        + "同名冲突资源或下发超时未落库的资源需人工确认",
                kind, name, recordKind, recordId, node.getId());
    }

    private void logUnparsable(String kind, String name, Node node) {
        log.warn("配置巡检：无法解析的{}名称 {}（节点: {}），仅告警", kind, name, node.getId());
    }

    /**
     * 安全执行操作，捕获异常
     */
    private void safeExecute(Runnable operation, String operationDesc) {
        try {
            operation.run();
        } catch (Exception e) {
            log.warn("配置巡检执行失败: {}", operationDesc, e);
        }
    }


    /**
     * 解析服务名称
     */
    private List<String> parseServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return List.of();
        }
        String[] split = serviceName.split("_");
        return new ArrayList<>(Arrays.asList(split));
    }

    /**
     * 限流器名称即主键，解析失败说明上报内容不可信
     */
    private Long parseLimiterId(String limiterName) {
        try {
            return Long.parseLong(limiterName);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
