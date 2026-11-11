package com.admin.common.nodehealth;

import com.admin.entity.ChainTunnel;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.impl.ForwardServiceImpl;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表板只读视图：把隧道拓扑与「上次探测结论」拼成前端能直接渲染的结构。
 *
 * <p>四条硬约束：
 * ① 只读——本类任何方法都不会调用探测通道，刷新页面不会产生任何对节点的请求；
 * ② 行序沿用 {@link ForwardServiceImpl#sortChainRows}（入口 → 转发链 hop → 出口），与隧道列表口径一致；
 * ③ 未探测过的节点状态是 UNKNOWN 且 {@code controlPlaneReachable=false}，绝不因为库里有记录就报在线；
 * ④ 数据源读失败一律抛 {@link NodeHealthDataException}，不在这里吞成空列表——把「查不出来」渲染成
 * 「没有节点/没有隧道」，等于用正常态掩盖故障。
 *
 * <p>每个节点行同时给出三个维度的信息，避免再次混用：
 * {@code connection} = 当前控制通道在不在（实时内存态）；
 * {@code status} = 上次探测的真实结论（可能已过期，见 {@code stale}）；
 * {@code dbOnline} = 库里那条「上次 WS 建连」的历史标记，仅供对照，不是存活依据。
 */
public class NodeHealthViewService {

    static final int ROLE_ENTRY = 1;
    static final int ROLE_RELAY = 2;
    static final int ROLE_EXIT = 3;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NodeService nodeService;
    private final TunnelService tunnelService;
    private final ChainTunnelService chainTunnelService;
    private final NodeProbeService probeService;

    public NodeHealthViewService(NodeService nodeService,
                                 TunnelService tunnelService,
                                 ChainTunnelService chainTunnelService,
                                 NodeProbeService probeService) {
        this.nodeService = nodeService;
        this.tunnelService = tunnelService;
        this.chainTunnelService = chainTunnelService;
        this.probeService = probeService;
    }

    /** 全局摘要：节点计数 + 上次轮次 + 探测节奏，给仪表板顶部卡片用。 */
    public Map<String, Object> summary() {
        List<Node> nodes = requireNodes();
        int online = 0;
        int degraded = 0;
        int offline = 0;
        int unknown = 0;
        int staleCount = 0;
        int total = 0;
        List<Map<String, Object>> nodeViews = new ArrayList<>();
        for (Node node : nodes) {
            if (node == null || node.getId() == null) {
                continue;
            }
            total++;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("nodeId", node.getId());
            putNodeStatus(view, node.getId(), node);
            nodeViews.add(view);
            if (Boolean.TRUE.equals(view.get("stale"))) {
                staleCount++;
            }
            NodeProbeStatus status = NodeProbeStatus.fromCode((String) view.get("status"));
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", probeService.now());
        putProbeScope(data);
        data.put("intervalMs", probeService.intervalMs());
        data.put("freshnessMs", probeService.freshnessMs());
        data.put("nextDueAt", probeService.nextDueAt());
        data.put("lastRound", roundView(probeService.lastRound()));
        data.put("stateHealthy", probeService.stateHealth().ok());
        data.put("total", total);
        data.put("nodes", nodeViews);
        data.put("online", online);
        data.put("degraded", degraded);
        data.put("offline", offline);
        data.put("unknown", unknown);
        data.put("staleCount", staleCount);
        data.put("statusLegend", statusLegend());
        return data;
    }

    /** 按隧道展示：每条隧道下按角色/跳序列出节点名称与上次检测结论。 */
    public Map<String, Object> tunnels() {
        Map<Long, Node> nodes = nodeIndex(requireNodes());
        Map<Long, List<ChainTunnel>> rowsByTunnel = chainRowsByTunnel();
        List<Tunnel> tunnels = new ArrayList<>();
        for (Tunnel tunnel : requireTunnels()) {
            if (tunnel != null) {
                tunnels.add(tunnel);
            }
        }
        tunnels.sort(Comparator.comparing(Tunnel::getId,
                Comparator.nullsLast(Comparator.<Long>naturalOrder())));

        List<Map<String, Object>> tunnelViews = new ArrayList<>();
        int online = 0;
        int degraded = 0;
        int offline = 0;
        int unknown = 0;
        int staleCount = 0;
        for (Tunnel tunnel : tunnels) {
            List<ChainTunnel> rows = rowsByTunnel.get(tunnel.getId());
            if (rows == null) {
                rows = Collections.emptyList();
            }
            List<Map<String, Object>> nodeViews = new ArrayList<>();
            int tunnelOnline = 0;
            int tunnelDegraded = 0;
            int tunnelOffline = 0;
            int tunnelUnknown = 0;
            int tunnelStale = 0;
            for (ChainTunnel chainRow : rows) {
                Map<String, Object> nodeView = chainNodeView(chainRow, nodes);
                nodeViews.add(nodeView);
                if (Boolean.TRUE.equals(nodeView.get("stale"))) {
                    tunnelStale++;
                }
                NodeProbeStatus status = NodeProbeStatus.fromCode((String) nodeView.get("status"));
                if (status == NodeProbeStatus.ONLINE) {
                    tunnelOnline++;
                } else if (status == NodeProbeStatus.DEGRADED) {
                    tunnelDegraded++;
                } else if (status == NodeProbeStatus.OFFLINE) {
                    tunnelOffline++;
                } else {
                    tunnelUnknown++;
                }
            }
            online += tunnelOnline;
            degraded += tunnelDegraded;
            offline += tunnelOffline;
            unknown += tunnelUnknown;
            staleCount += tunnelStale;

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("tunnelId", tunnel.getId());
            view.put("tunnelName", tunnel.getName());
            view.put("type", tunnel.getType());
            view.put("inIp", tunnel.getInIp());
            view.put("nodeCount", nodeViews.size());
            view.put("status", worstStatus(nodeViews));
            view.put("online", tunnelOnline);
            view.put("degraded", tunnelDegraded);
            view.put("offline", tunnelOffline);
            view.put("unknown", tunnelUnknown);
            view.put("staleCount", tunnelStale);
            view.put("nodes", nodeViews);
            tunnelViews.add(view);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", probeService.now());
        putProbeScope(data);
        data.put("intervalMs", probeService.intervalMs());
        data.put("freshnessMs", probeService.freshnessMs());
        data.put("nextDueAt", probeService.nextDueAt());
        data.put("lastRound", roundView(probeService.lastRound()));
        data.put("tunnelCount", tunnelViews.size());
        data.put("online", online);
        data.put("degraded", degraded);
        data.put("offline", offline);
        data.put("unknown", unknown);
        data.put("staleCount", staleCount);
        data.put("statusLegend", statusLegend());
        data.put("tunnels", tunnelViews);
        return data;
    }

    /**
     * 单节点详情：节点状态 + 它出现在哪些隧道/角色里。
     *
     * @return 节点不存在时返回 null，由控制器给业务错误码
     */
    public Map<String, Object> node(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        Map<Long, Node> nodes = nodeIndex(requireNodes());
        Node node = nodes.get(nodeId);
        if (node == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("nodeId", nodeId);
        putNodeStatus(snapshot, nodeId, node);

        List<Map<String, Object>> usedBy = new ArrayList<>();
        Map<Long, List<ChainTunnel>> rowsByTunnel = chainRowsByTunnel();
        Map<Long, String> tunnelNames = tunnelNames(requireTunnels());
        for (Map.Entry<Long, List<ChainTunnel>> entry : rowsByTunnel.entrySet()) {
            String tunnelName = tunnelNames.get(entry.getKey());
            for (ChainTunnel row : entry.getValue()) {
                if (row == null || !nodeId.equals(row.getNodeId())) {
                    continue;
                }
                Map<String, Object> usage = new LinkedHashMap<>();
                usage.put("tunnelId", entry.getKey());
                usage.put("tunnelName", tunnelName);
                usage.put("role", roleCode(row.getChainType()));
                usage.put("roleLabel", roleLabel(row.getChainType()));
                usage.put("hopIndex", row.getInx());
                usage.put("port", row.getPort());
                usage.put("protocol", row.getProtocol());
                usedBy.add(usage);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", probeService.now());
        putProbeScope(data);
        data.put("intervalMs", probeService.intervalMs());
        data.put("freshnessMs", probeService.freshnessMs());
        data.put("statusLegend", statusLegend());
        data.put("node", snapshot);
        data.put("tunnels", usedBy);
        return data;
    }

    // ========== 内部拼装 ==========

    private Map<String, Object> chainNodeView(ChainTunnel row, Map<Long, Node> nodes) {
        Long nodeId = row == null ? null : row.getNodeId();
        Node node = nodeId == null ? null : nodes.get(nodeId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rowId", row == null ? null : row.getId());
        view.put("role", roleCode(row == null ? null : row.getChainType()));
        view.put("roleLabel", roleLabel(row == null ? null : row.getChainType()));
        view.put("hopIndex", row == null ? null : row.getInx());
        view.put("port", row == null ? null : row.getPort());
        view.put("protocol", row == null ? null : row.getProtocol());
        view.put("strategy", row == null ? null : row.getStrategy());
        view.put("nodeId", nodeId);
        putNodeStatus(view, nodeId, node);
        return view;
    }

    /**
     * 三个维度的状态字段统一在这里写，避免各处口径漂移。
     *
     * <p>过期结论只作历史：{@code stale=true} 时 {@code status} 一律按 {@code UNKNOWN} 输出，
     * {@code online} 计数、隧道汇总、单节点详情都读同一个字段，不会出现「列表显示在线、
     * 计数却算未知」的两套口径。历史结论本身不丢弃——检测时间、上次延迟与 detail 原文照常给出。
     */
    private void putNodeStatus(Map<String, Object> view, Long nodeId, Node node) {
        // 带身份取结论：节点记录被重建（ID 被复用）时旧结论必须作废，不能让新机器顶着一个 ONLINE。
        // 节点记录已经不存在时同理——账本里留着的 ONLINE 属于一条已消失的记录，没有任何东西能证明
        // 它还是「这个」节点，按未知展示，绝不拿缓存顶着在线。
        String identity = NodeProbeService.identityOf(node);
        NodeProbeResult result = node != null && identity != null
                ? probeService.effective(nodeId, identity)
                : nodeId == null
                        ? NodeProbeResult.never()
                        : new NodeProbeResult(NodeProbeStatus.UNKNOWN, 0L, -1L,
                                "节点记录或身份信息缺失，历史探测结论无法归属，按未知展示");
        boolean connected = node != null && probeService.isConnected(nodeId);
        boolean stale = probeService.isStale(result);
        NodeProbeStatus status = stale ? NodeProbeStatus.UNKNOWN : result.status();
        boolean hasLatency = result.latencyMs() >= 0L;
        view.put("nodeName", nodeName(nodeId, node));
        view.put("serverIp", node == null ? null : node.getServerIp());
        view.put("dbOnline", node != null && Integer.valueOf(1).equals(node.getStatus()));
        view.put("connection", connected ? "CONNECTED" : "DISCONNECTED");
        view.put("connectionLabel", connected ? "控制通道已连接" : "控制通道未连接");
        view.put("status", status.code());
        view.put("statusLabel", status.label());
        view.put("controlPlaneReachable", status == NodeProbeStatus.ONLINE);
        view.put("latencyMs", !stale && hasLatency ? Long.valueOf(result.latencyMs()) : null);
        view.put("lastLatencyMs", stale && hasLatency ? Long.valueOf(result.latencyMs()) : null);
        view.put("checkedAt", result.probed() ? Long.valueOf(result.checkedAt()) : null);
        view.put("checkedAtText", result.probed() ? formatTime(result.checkedAt()) : "尚未检测");
        view.put("stale", stale);
        view.put("staleNote", stale ? "上次检测结论已过期，仅作历史参考，不计入在线" : null);
        view.put("detail", result.detail());
    }

    private String nodeName(Long nodeId, Node node) {
        if (node != null && node.getName() != null && !node.getName().isBlank()) {
            return node.getName();
        }
        if (nodeId == null) {
            return "未配置节点";
        }
        return "节点#" + nodeId + "（节点记录不存在）";
    }

    private String worstStatus(List<Map<String, Object>> nodeViews) {
        NodeProbeStatus worst = null;
        for (Map<String, Object> nodeView : nodeViews) {
            worst = NodeProbeStatus.worst(worst, NodeProbeStatus.fromCode((String) nodeView.get("status")));
        }
        return worst == null ? NodeProbeStatus.UNKNOWN.code() : worst.code();
    }

    private Map<String, Object> roundView(NodeProbeService.ProbeRound round) {
        if (round == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("trigger", round.trigger());
        view.put("startedAt", round.startedAt());
        view.put("finishedAt", round.finishedAt());
        view.put("finishedAtText", formatTime(round.finishedAt()));
        view.put("total", round.total());
        view.put("online", round.online());
        view.put("degraded", round.degraded());
        view.put("offline", round.offline());
        view.put("unknown", round.unknown());
        return view;
    }

    private Map<String, Object> statusLegend() {
        Map<String, Object> legend = new LinkedHashMap<>();
        for (NodeProbeStatus status : NodeProbeStatus.values()) {
            legend.put(status.code(), status.label());
        }
        return legend;
    }

    /** 三个视图共用的口径字段：探测范围、人话说明、上次探测时间。 */
    private void putProbeScope(Map<String, Object> data) {
        data.put("probeScope", NodeProbeService.PROBE_SCOPE);
        data.put("probeNotice", NodeProbeService.PROBE_NOTICE);
        data.put("lastProbeAt", probeService.lastProbeAt());
    }

    // ========== 数据来源（失败即抛，绝不吞成空列表） ==========

    private List<Node> requireNodes() {
        if (nodeService == null) {
            throw new NodeHealthDataException("节点健康视图读取节点列表失败：NodeService 未装配");
        }
        List<Node> nodes;
        try {
            nodes = nodeService.list();
        } catch (RuntimeException e) {
            throw new NodeHealthDataException("节点健康视图读取节点列表失败：" + e.getMessage(), e);
        }
        if (nodes == null) {
            throw new NodeHealthDataException("节点健康视图读取节点列表失败：返回 null");
        }
        return nodes;
    }

    private List<Tunnel> requireTunnels() {
        if (tunnelService == null) {
            throw new NodeHealthDataException("节点健康视图读取隧道列表失败：TunnelService 未装配");
        }
        List<Tunnel> tunnels;
        try {
            tunnels = tunnelService.list();
        } catch (RuntimeException e) {
            throw new NodeHealthDataException("节点健康视图读取隧道列表失败：" + e.getMessage(), e);
        }
        if (tunnels == null) {
            throw new NodeHealthDataException("节点健康视图读取隧道列表失败：返回 null");
        }
        return tunnels;
    }

    private Map<Long, Node> nodeIndex(List<Node> nodes) {
        Map<Long, Node> index = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (node != null && node.getId() != null) {
                index.put(node.getId(), node);
            }
        }
        return index;
    }

    /** 一次查全部链路行并按隧道分组，组内沿用既有排序口径（入口 → hop → 出口）。 */
    private Map<Long, List<ChainTunnel>> chainRowsByTunnel() {
        if (chainTunnelService == null) {
            throw new NodeHealthDataException("节点健康视图读取链路行失败：ChainTunnelService 未装配");
        }
        List<ChainTunnel> all;
        try {
            all = chainTunnelService.list();
        } catch (RuntimeException e) {
            throw new NodeHealthDataException("节点健康视图读取链路行失败：" + e.getMessage(), e);
        }
        if (all == null) {
            throw new NodeHealthDataException("节点健康视图读取链路行失败：返回 null");
        }
        Map<Long, List<ChainTunnel>> grouped = new LinkedHashMap<>();
        for (ChainTunnel row : all) {
            if (row == null || row.getTunnelId() == null) {
                continue;
            }
            List<ChainTunnel> bucket = grouped.get(row.getTunnelId());
            if (bucket == null) {
                bucket = new ArrayList<>();
                grouped.put(row.getTunnelId(), bucket);
            }
            bucket.add(row);
        }
        for (Map.Entry<Long, List<ChainTunnel>> entry : grouped.entrySet()) {
            List<ChainTunnel> sorted = ForwardServiceImpl.sortChainRows(entry.getValue());
            entry.setValue(sorted == null ? new ArrayList<>() : sorted);
        }
        return grouped;
    }

    /** 隧道名索引：查一次给整个节点详情用，避免每行都重新查一遍隧道表。 */
    private Map<Long, String> tunnelNames(List<Tunnel> tunnels) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Tunnel tunnel : tunnels) {
            if (tunnel != null && tunnel.getId() != null) {
                names.put(tunnel.getId(), tunnel.getName());
            }
        }
        return names;
    }

    private static int roleCode(Integer chainType) {
        if (chainType == null) {
            return 0;
        }
        return chainType;
    }

    private static String roleLabel(Integer chainType) {
        if (chainType == null) {
            return "未知角色";
        }
        switch (chainType) {
            case ROLE_ENTRY:
                return "入口";
            case ROLE_RELAY:
                return "转发链";
            case ROLE_EXIT:
                return "出口";
            default:
                return "未知角色(" + chainType + ")";
        }
    }

    private static String formatTime(long epochMillis) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}