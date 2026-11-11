package com.admin.compat;

import com.admin.common.nodehealth.NodeHealthDataException;
import com.admin.common.nodehealth.NodeHealthViewService;
import com.admin.common.nodehealth.NodeProbeService;
import com.admin.common.nodehealth.NodeProbeStateStore;
import com.admin.common.nodehealth.NodeProbeTransport;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仪表板只读视图回归测试。
 *
 * <p>覆盖的原缺陷：前端拿节点列表里那条 status 当在线显示，页面一刷新就「全绿」，与真实连通性无关。
 * 这里锁死：
 * ① 隧道行按 入口 → 转发链(hop) → 出口 排列，并带上节点名称；
 * ② 刷新视图（summary / tunnels / node）一次探测都不发；
 * ③ 未探测 = UNKNOWN 且 controlPlaneReachable=false，即使库里 dbOnline=true；
 * ④ 结论过期降为 UNKNOWN 但保留「上次检测」时间；
 * ⑤ 隧道汇总取最坏状态，一条链上有一个掉线就不报整条在线。
 */
@SuppressWarnings("unchecked")
class NodeHealthViewServiceTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;

    private static final long ENTRY_NODE = 11L;
    private static final long RELAY_ONE = 14L;
    private static final long RELAY_TWO = 15L;
    private static final long EXIT_NODE = 13L;
    private static final long TUNNEL_ID = 1L;

    private NodeService nodeService;
    private TunnelService tunnelService;
    private ChainTunnelService chainTunnelService;
    private NodeProbeTransport transport;
    private NodeProbeService probeService;
    private NodeHealthViewService viewService;
    private MutableClock clock;
    private Set<Long> connected;

    @BeforeEach
    void setUp() {
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        transport = mock(NodeProbeTransport.class);
        clock = new MutableClock(T0);
        connected = new HashSet<>();
        probeService = new NodeProbeService(nodeService, transport, id -> connected.contains(id), clock, INTERVAL, FRESH);
        viewService = new NodeHealthViewService(nodeService, tunnelService, chainTunnelService, probeService);
    }

    // ========== 1) 行序与节点名 ==========

    @Test
    void tunnelRowsAreOrderedEntryRelayExitWithNodeNames() {
        stubTopology();

        Map<String, Object> data = viewService.tunnels();
        List<Map<String, Object>> tunnels = (List<Map<String, Object>>) data.get("tunnels");

        assertEquals(1, tunnels.size());
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) tunnels.get(0).get("nodes");
        assertEquals(Arrays.asList(ENTRY_NODE, RELAY_ONE, RELAY_TWO, EXIT_NODE), nodeIds(nodes),
                "入口 → 转发链(inx) → 出口，不能跟着查询行序走");
        assertEquals(Arrays.asList("入口", "转发链", "转发链", "出口"), labels(nodes, "roleLabel"));
        assertEquals(Arrays.asList("hk-entry", "jp-relay-a", "jp-relay-b", "us-exit"), labels(nodes, "nodeName"),
                "按隧道展示必须带节点名称，而不是只剩 nodeId");
        assertEquals(Integer.valueOf(1), nodes.get(1).get("hopIndex"));
        assertEquals(Integer.valueOf(2), nodes.get(2).get("hopIndex"));
    }

    @Test
    void summaryReturnsNamedNodesAndCountsFromSameSnapshot() {
        stubTopology();
        connected.add(ENTRY_NODE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(8L, "OK"));
        probeService.probeIfDue("test").orElseThrow();
        Map<String, Object> summary = viewService.summary();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) summary.get("nodes");
        assertNotNull(nodes);
        assertEquals(nodes.size(), summary.get("total"));
        assertEquals(nodes.stream().filter(n -> "ONLINE".equals(n.get("status"))).count(),
                ((Number) summary.get("online")).longValue());
        assertTrue(nodes.stream().anyMatch(n -> ENTRY_NODE == ((Number) n.get("nodeId")).longValue()
                && "hk-entry".equals(n.get("nodeName")) && "ONLINE".equals(n.get("status"))));
    }

    @Test
    void summaryReportsUnhealthyStateWithoutExposingStoreError() {
        stubTopology();
        NodeProbeStateStore store = mock(NodeProbeStateStore.class);
        when(store.load()).thenThrow(new IllegalStateException("internal-path-secret"));
        NodeProbeService failedProbe = new NodeProbeService(nodeService, transport,
                id -> connected.contains(id), clock, INTERVAL, FRESH, store);
        NodeHealthViewService failedView = new NodeHealthViewService(nodeService, tunnelService,
                chainTunnelService, failedProbe);
        Map<String, Object> summary = failedView.summary();
        assertEquals(Boolean.FALSE, summary.get("stateHealthy"));
        assertTrue(!summary.toString().contains("internal-path-secret"));
        verify(transport, never()).probe(any());
    }

    // ========== 2) 刷新不触发探测 ==========

    @Test
    void dashboardRefreshNeverTriggersProbe() {
        stubTopology();

        viewService.summary();
        viewService.tunnels();
        viewService.node(ENTRY_NODE);

        verify(transport, never()).probe(any());
    }

    // ========== 3) 未探测 = UNKNOWN，即使库里写着在线 ==========

    @Test
    void unprobedNodeIsUnknownEvenWhenDbSaysOnline() {
        stubTopology();

        List<Map<String, Object>> nodes = nodesOf(viewService.tunnels());
        Map<String, Object> entry = nodes.get(0);

        assertEquals("UNKNOWN", entry.get("status"), "没有探测结论就是未知，绝不能因为库里有记录就报在线");
        assertEquals(Boolean.FALSE, entry.get("controlPlaneReachable"), "字段名不许暗示业务可达");
        assertNull(entry.get("checkedAt"), "没测过就没有检测时间，不能编一个");
        assertEquals("尚未检测", entry.get("checkedAtText"));
        assertEquals(Boolean.TRUE, entry.get("dbOnline"), "库里那条历史标记仍然要能看见，用来对照它不可信");
        assertEquals("DISCONNECTED", entry.get("connection"));
    }

    // ========== 4) 过期结论降为 UNKNOWN 且保留上次检测 ==========

    @Test
    void staleProbeBecomesUnknownButKeepsLastCheckedAt() {
        stubTopology();
        connected.add(ENTRY_NODE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(12L, "OK"));
        probeService.probeIfDue("test").orElseThrow();
        assertEquals("ONLINE", nodesOf(viewService.tunnels()).get(0).get("status"));

        clock.advance(FRESH + 1L);

        Map<String, Object> entry = nodesOf(viewService.tunnels()).get(0);
        assertEquals("UNKNOWN", entry.get("status"), "两天前的结论不能继续当在线");
        assertEquals(Boolean.FALSE, entry.get("controlPlaneReachable"));
        assertEquals(Long.valueOf(T0), entry.get("checkedAt"), "上次检测时间必须保留，界面要显示「上次检测」");
        assertNotNull(entry.get("checkedAtText"));
        assertEquals(Boolean.TRUE, entry.get("stale"));
    }

    // ========== 5) 隧道汇总取最坏状态 ==========

    @Test
    void tunnelStatusFollowsWorstNodeSeverity() {
        stubTopology();
        connected.add(ENTRY_NODE);
        connected.add(RELAY_ONE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(8L, "OK"));
        when(transport.probe(RELAY_ONE)).thenReturn(NodeProbeTransport.Reply.ok(9L, "OK"));
        probeService.probeIfDue("test").orElseThrow();

        Map<String, Object> tunnel = ((List<Map<String, Object>>) viewService.tunnels().get("tunnels")).get(0);

        assertEquals("OFFLINE", tunnel.get("status"), "链上还有节点掉线，整条隧道就不能报在线");
        assertEquals(2, tunnel.get("online"));
        assertEquals(2, tunnel.get("offline"));
        assertEquals(4, tunnel.get("nodeCount"));
    }

    // ========== 6) 节点记录被删了也不藏起来 ==========

    @Test
    void missingNodeRecordStillShowsPlaceholderName() {
        when(nodeService.list()).thenReturn(Collections.singletonList(node(ENTRY_NODE, "hk-entry")));
        when(tunnelService.list()).thenReturn(Collections.singletonList(tunnel(TUNNEL_ID, "hk→us")));
        when(chainTunnelService.list()).thenReturn(Arrays.asList(
                chainRow(10L, TUNNEL_ID, 1, ENTRY_NODE, null),
                chainRow(30L, TUNNEL_ID, 3, 99L, null)));

        Map<String, Object> missing = nodesOf(viewService.tunnels()).get(1);

        assertEquals(Long.valueOf(99L), missing.get("nodeId"));
        assertTrue(String.valueOf(missing.get("nodeName")).contains("99"), "节点记录没了也要指出是哪个 id，不能静默丢弃");
        assertEquals("UNKNOWN", missing.get("status"));
    }

    // ========== 7) 全局摘要计数 ==========

    @Test
    void summaryCountsEffectiveStatuses() {
        when(nodeService.list()).thenReturn(Arrays.asList(
                node(ENTRY_NODE, "hk-entry"), node(RELAY_ONE, "jp-relay-a"), node(EXIT_NODE, "us-exit")));
        when(tunnelService.list()).thenReturn(Collections.emptyList());
        when(chainTunnelService.list()).thenReturn(Collections.emptyList());
        connected.add(ENTRY_NODE);
        connected.add(RELAY_ONE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(8L, "OK"));
        when(transport.probe(RELAY_ONE)).thenReturn(NodeProbeTransport.Reply.ok(9L, "OK"));
        probeService.probeIfDue("test").orElseThrow();

        Map<String, Object> summary = viewService.summary();

        assertEquals(3, summary.get("total"));
        assertEquals(2, summary.get("online"));
        assertEquals(1, summary.get("offline"));
        assertEquals(0, summary.get("degraded"));
        assertEquals(0, summary.get("unknown"));
        assertEquals(INTERVAL, summary.get("intervalMs"));
        assertNotNull(summary.get("lastRound"));
    }

    // ========== 8) 单节点详情列出它在隧道里的位置 ==========

    @Test
    void nodeEndpointListsTunnelUsage() {
        stubTopology();

        Map<String, Object> data = viewService.node(RELAY_ONE);

        assertNotNull(data);
        Map<String, Object> node = (Map<String, Object>) data.get("node");
        assertEquals("jp-relay-a", node.get("nodeName"));
        assertEquals("UNKNOWN", node.get("status"));
        List<Map<String, Object>> usages = (List<Map<String, Object>>) data.get("tunnels");
        assertEquals(1, usages.size());
        assertEquals(Long.valueOf(TUNNEL_ID), usages.get(0).get("tunnelId"));
        assertEquals("转发链", usages.get(0).get("roleLabel"));
        assertEquals(Integer.valueOf(1), usages.get(0).get("hopIndex"));
    }

    @Test
    void nodeEndpointRejectsUnknownNode() {
        when(nodeService.list()).thenReturn(Collections.emptyList());

        assertNull(viewService.node(404L), "节点不存在要返回 null，由控制器给业务错误");
    }

    // ========== 9) 数据源失败必须显式上抛，不能伪装成「没有节点/没有隧道」 ==========

    @Test
    void nodeListFailureIsReportedNotRenderedAsEmpty() {
        when(nodeService.list()).thenThrow(new IllegalStateException("db down"));

        NodeHealthDataException summaryFailure = assertThrows(NodeHealthDataException.class,
                () -> viewService.summary());
        assertTrue(summaryFailure.getMessage().contains("db down"), "失败原因要带出来");

        assertThrows(NodeHealthDataException.class, () -> viewService.tunnels());
        assertThrows(NodeHealthDataException.class, () -> viewService.node(RELAY_ONE));
    }

    @Test
    void tunnelListFailureIsReportedNotRenderedAsEmpty() {
        stubTopology();
        when(tunnelService.list()).thenThrow(new IllegalStateException("tunnel table gone"));

        NodeHealthDataException failure = assertThrows(NodeHealthDataException.class,
                () -> viewService.tunnels());
        assertTrue(failure.getMessage().contains("tunnel table gone"));
    }

    @Test
    void chainRowFailureIsReportedNotRenderedAsEmpty() {
        stubTopology();
        when(chainTunnelService.list()).thenThrow(new IllegalStateException("chain table gone"));

        NodeHealthDataException failure = assertThrows(NodeHealthDataException.class,
                () -> viewService.tunnels());
        assertTrue(failure.getMessage().contains("chain table gone"));
    }

    // ========== 10) 视图必须写清「测的是控制通道，不是业务可达」 ==========

    @Test
    void viewsDeclareProbeScopeAndNotice() {
        stubTopology();
        when(transport.probe(any())).thenReturn(NodeProbeTransport.Reply.ok(9L, "OK"));
        probeService.probeIfDue("test").orElseThrow();

        Map<String, Object> summary = viewService.summary();
        assertEquals(NodeProbeService.PROBE_SCOPE, summary.get("probeScope"));
        assertTrue(String.valueOf(summary.get("probeNotice")).contains("不代表该节点承载的业务流量"));
        assertEquals(Long.valueOf(T0), summary.get("lastProbeAt"), "视图要能说出上次探测时间");

        Map<String, Object> tunnels = viewService.tunnels();
        assertEquals(NodeProbeService.PROBE_SCOPE, tunnels.get("probeScope"));
        assertEquals(Long.valueOf(T0), tunnels.get("lastProbeAt"));
    }

    // ========== 脚手架 ==========

    private void stubTopology() {
        when(nodeService.list()).thenReturn(Arrays.asList(
                node(ENTRY_NODE, "hk-entry"),
                node(RELAY_ONE, "jp-relay-a"),
                node(RELAY_TWO, "jp-relay-b"),
                node(EXIT_NODE, "us-exit")));
        when(tunnelService.list()).thenReturn(Collections.singletonList(tunnel(TUNNEL_ID, "hk→us")));
        when(chainTunnelService.list()).thenReturn(Arrays.asList(
                chainRow(30L, TUNNEL_ID, 3, EXIT_NODE, null),
                chainRow(20L, TUNNEL_ID, 2, RELAY_TWO, 2),
                chainRow(10L, TUNNEL_ID, 1, ENTRY_NODE, null),
                chainRow(5L, TUNNEL_ID, 2, RELAY_ONE, 1)));
    }

    private static List<Map<String, Object>> nodesOf(Map<String, Object> tunnelsData) {
        List<Map<String, Object>> tunnels = (List<Map<String, Object>>) tunnelsData.get("tunnels");
        return (List<Map<String, Object>>) tunnels.get(0).get("nodes");
    }

    private static List<Long> nodeIds(List<Map<String, Object>> nodes) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            ids.add((Long) node.get("nodeId"));
        }
        return ids;
    }

    private static List<Object> labels(List<Map<String, Object>> nodes, String key) {
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            values.add(node.get(key));
        }
        return values;
    }

    private static Node node(Long id, String name) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setStatus(1);
        return node;
    }

    private static Tunnel tunnel(Long id, String name) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(id);
        tunnel.setName(name);
        tunnel.setType(1);
        return tunnel;
    }

    private static ChainTunnel chainRow(Long id, Long tunnelId, Integer chainType, Long nodeId, Integer inx) {
        ChainTunnel row = new ChainTunnel();
        row.setId(id);
        row.setTunnelId(tunnelId);
        row.setChainType(chainType);
        row.setNodeId(nodeId);
        row.setInx(inx);
        row.setPort(20001);
        row.setProtocol("tls");
        return row;
    }

    private static final class MutableClock implements LongSupplier {

        private long now;

        MutableClock(long now) {
            this.now = now;
        }

        void advance(long delta) {
            this.now += delta;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }
}
