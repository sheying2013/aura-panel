package com.admin.common.nodehealth;

import com.admin.service.ChainTunnelService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.common.utils.WebSocketServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 节点健康探测的装配。
 *
 * <p>探测节奏只在这里读配置：{@code flux.node-probe.interval-ms} 默认 43200000（12 小时，每天 2 次），
 * {@code flux.node-probe.freshness-ms} 默认 86400000（两轮没测过就只承认「未知」），
 * {@code flux.node-probe.state-file} 指定节流账本文件（默认与数据库同目录的 node-probe-state.json）。
 * 节流账本必须落盘：只放内存的话，重启就等于把「12 小时最多一次」清零。
 * 单测直接 new {@link NodeProbeService}，不依赖 Spring 容器。
 */
@Configuration
public class NodeHealthConfig {

    @Bean
    public NodeProbeTransport nodeProbeTransport() {
        return new WebSocketNodeProbeTransport();
    }

    @Bean
    public NodeProbeStateStore nodeProbeStateStore(
            @Value("${flux.node-probe.state-file:}") String stateFile,
            @Value("${DB_PATH:/app/data/gost.db}") String dbPath) {
        if (stateFile != null && !stateFile.isBlank()) {
            return new FileNodeProbeStateStore(Paths.get(stateFile.trim()));
        }
        return new FileNodeProbeStateStore(defaultStateFile(dbPath));
    }

    /**
     * 默认落在数据库同目录：容器里 /app/data 一般是持久卷，账本和 gost.db 一起活过重启。
     * 数据库路径取不到时退回进程工作目录——节流照常生效，只是换了个位置。
     */
    static Path defaultStateFile(String dbPath) {
        try {
            String raw = dbPath == null || dbPath.isBlank() ? "gost.db" : dbPath.trim();
            Path parent = Paths.get(raw).toAbsolutePath().getParent();
            return (parent == null ? Paths.get(".") : parent).resolve("node-probe-state.json");
        } catch (RuntimeException e) {
            return Paths.get("node-probe-state.json").toAbsolutePath();
        }
    }

    @Bean
    public NodeProbeService nodeProbeService(NodeService nodeService,
                                             NodeProbeTransport nodeProbeTransport,
                                             NodeProbeStateStore nodeProbeStateStore,
                                             @Value("${flux.node-probe.interval-ms:43200000}") long intervalMs,
                                             @Value("${flux.node-probe.freshness-ms:86400000}") long freshnessMs) {
        return new NodeProbeService(nodeService, nodeProbeTransport, WebSocketServer::isNodeOnline,
                System::currentTimeMillis, intervalMs, freshnessMs, nodeProbeStateStore);
    }

    @Bean
    public NodeHealthViewService nodeHealthViewService(NodeService nodeService,
                                                       TunnelService tunnelService,
                                                       ChainTunnelService chainTunnelService,
                                                       NodeProbeService nodeProbeService) {
        return new NodeHealthViewService(nodeService, tunnelService, chainTunnelService, nodeProbeService);
    }
}
