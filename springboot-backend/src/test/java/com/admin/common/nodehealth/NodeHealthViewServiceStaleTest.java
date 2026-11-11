package com.admin.common.nodehealth;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 过期结论（stale）在视图层的一把尺子。
 *
 * <p>锁死三条：
 * ① 任何 {@code stale=true} 的节点，{@code status} 一律 {@code UNKNOWN}，不计入 online/degraded/offline；
 * ② 过期不等于删掉历史：检测时间、上次延迟、detail 原文与 staleNote 照常给出；
 * ③ summary / tunnels / node 三个视图读同一个字段，统计与明细不会各说各话。
 */
@SuppressWarnings("unchecked")
class NodeHealthViewServiceStaleTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;

    private static final long ENTRY_NODE = 11L;
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

    @Test
    void staleOnlineProbeIsCountedUnknownAndKeepsHistory() {
        stubNodes(node(ENTRY_NODE, "hk-entry"));
        connected.add(ENTRY_NODE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(8L, "OK"));
        probeService.probeNode(node(ENTRY_NODE, "hk-entry"));

        Map<String, Object> fresh = viewService.summary();
        assertEquals(1, number(fresh, "online"), "刚测出来的 ONLINE 才是在线");
        assertEquals(0, number(fresh, "staleCount"));

        expire();

        Map<String, Object> summary = viewService.summary();
        assertEquals(0, number(summary, "online"), "过期结论不得再计入在线");
        assertEquals(1, number(summary, "unknown"));
        assertEquals(1, number(summary, "staleCount"));

        Map<String, Object> nodeView = singleNode(summary);
        assertEquals("UNKNOWN", nodeView.get("status"));
        assertEquals(Boolean.TRUE, nodeView.get("stale"));
        assertEquals(Boolean.FALSE, nodeView.get("controlPlaneReachable"));
        assertNull(nodeView.get("latencyMs"), "当前延迟未知，不能拿上次延迟冒充");
        assertEquals(Long.valueOf(8L), nodeView.get("lastLatencyMs"), "上次延迟作为历史保留");
        assertEquals(Long.valueOf(T0), nodeView.get("checkedAt"), "保留上次检测时间");
        assertTrue(String.valueOf(nodeView.get("checkedAtText")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "过期后仍要给出上次检测时间：" + nodeView.get("checkedAtText"));
        assertNotNull(nodeView.get("staleNote"));
        assertTrue(String.valueOf(nodeView.get("detail")).contains("历史结论 ONLINE"),
                "detail 原文必须保留上次结论，供人工查看");
    }

    @Test
    void staleOfflineProbeIsNotCountedOffline() {
        stubNodes(node(EXIT_NODE, "us-exit"));
        when(transport.probe(EXIT_NODE)).thenReturn(NodeProbeTransport.Reply.noReply("控制通道未连接"));
        probeService.probeNode(node(EXIT_NODE, "us-exit"));
        assertEquals(1, number(viewService.summary(), "offline"), "新鲜 OFFLINE 仍是离线");

        expire();

        Map<String, Object> summary = viewService.summary();
        assertEquals(0, number(summary, "offline"), "过期结论既不是在线也不是离线，只能是未知");
        assertEquals(1, number(summary, "unknown"));
        assertEquals(1, number(summary, "staleCount"));
        assertEquals("UNKNOWN", singleNode(summary).get("status"));
        assertTrue(String.valueOf(singleNode(summary).get("detail")).contains("历史结论 OFFLINE"));
    }

    @Test
    void staleDegradedProbeIsNotCountedDegraded() {
        stubNodes(node(ENTRY_NODE, "hk-entry"));
        connected.add(ENTRY_NODE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.noReply("命令无回包"));
        probeService.probeNode(node(ENTRY_NODE, "hk-entry"));
        assertEquals(1, number(viewService.summary(), "degraded"));

        expire();

        Map<String, Object> summary = viewService.summary();
        assertEquals(0, number(summary, "degraded"));
        assertEquals(1, number(summary, "unknown"));
        assertEquals("UNKNOWN", singleNode(summary).get("status"));
    }

    @Test
    void freshProbeKeepsItsOwnStatusWithoutHistoryNoise() {
        stubNodes(node(ENTRY_NODE, "hk-entry"));
        connected.add(ENTRY_NODE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(12L, "OK"));
        probeService.probeNode(node(ENTRY_NODE, "hk-entry"));

        Map<String, Object> nodeView = singleNode(viewService.summary());
        assertEquals("ONLINE", nodeView.get("status"));
        assertEquals(Boolean.FALSE, nodeView.get("stale"));
        assertEquals(Boolean.TRUE, nodeView.get("controlPlaneReachable"));
        assertEquals(Long.valueOf(12L), nodeView.get("latencyMs"));
        assertNull(nodeView.get("lastLatencyMs"));
        assertNull(nodeView.get("staleNote"));
    }

    @Test
    void neverProbedNodeIsUnknownAndNotStale() {
        stubNodes(node(ENTRY_NODE, "hk-entry"));

        Map<String, Object> summary = viewService.summary();
        Map<String, Object> nodeView = singleNode(summary);
        assertEquals("UNKNOWN", nodeView.get("status"));
        assertEquals(Boolean.FALSE, nodeView.get("stale"), "没测过不等于过期");
        assertNull(nodeView.get("checkedAt"));
        assertEquals("尚未检测", nodeView.get("checkedAtText"));
        assertEquals(0, number(summary, "staleCount"));
        assertEquals(1, number(summary, "unknown"));
    }

    @Test
    void tunnelAggregationFollowsTheSameStaleRule() {
        stubTopology();
        connected.add(ENTRY_NODE);
        connected.add(EXIT_NODE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(5L, "OK"));
        when(transport.probe(EXIT_NODE)).thenReturn(NodeProbeTransport.Reply.ok(9L, "OK"));
        probeService.probeNode(node(ENTRY_NODE, "hk-entry"));
        probeService.probeNode(node(EXIT_NODE, "us-exit"));

        expire();
        // 只刷新入口：出口保持过期，形成新鲜/过期混合的一条链
        probeService.probeNode(node(ENTRY_NODE, "hk-entry"));

        Map<String, Object> data = viewService.tunnels();
        List<Map<String, Object>> tunnels = (List<Map<String, Object>>) data.get("tunnels");
        assertEquals(1, tunnels.size());
        Map<String, Object> tunnel = tunnels.get(0);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) tunnel.get("nodes");

        assertEquals("ONLINE", nodes.get(0).get("status"));
        assertEquals("UNKNOWN", nodes.get(1).get("status"), "过期出口按未知展示");
        assertEquals(1, ((Number) tunnel.get("online")).intValue());
        assertEquals(0, ((Number) tunnel.get("offline")).intValue());
        assertEquals(1, ((Number) tunnel.get("unknown")).intValue());
        assertEquals(1, ((Number) tunnel.get("staleCount")).intValue());
        assertEquals("UNKNOWN", tunnel.get("status"), "链上有一个结论不可信，整条不能报在线");
        assertEquals(1, ((Number) data.get("online")).intValue());
        assertEquals(1, ((Number) data.get("unknown")).intValue());
        assertEquals(1, ((Number) data.get("staleCount")).intValue());
    }

    @Test
    void missingNodeDoesNotInheritCachedOnlineStatus() {
        stubTopology();
        connected.add(EXIT_NODE);
        when(transport.probe(EXIT_NODE)).thenReturn(NodeProbeTransport.Reply.ok(9L, "OK"));
        probeService.probeNode(node(EXIT_NODE, "us-exit"));
        when(nodeService.list()).thenReturn(Collections.singletonList(node(ENTRY_NODE, "hk-entry")));

        Map<String, Object> data = viewService.tunnels();
        List<Map<String, Object>> tunnels = (List<Map<String, Object>>) data.get("tunnels");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) tunnels.get(0).get("nodes");
        assertEquals("UNKNOWN", nodes.get(1).get("status"));
        assertEquals(Boolean.FALSE, nodes.get(1).get("controlPlaneReachable"));
        assertNull(nodes.get(1).get("checkedAt"));
    }

    @Test
    void missingIdentityCannotReuseCachedOnlineStatus() {
        Node unnamed = node(ENTRY_NODE, null);
        stubNodes(unnamed);
        connected.add(ENTRY_NODE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(6L, "OK"));
        probeService.probeNode(unnamed);
        Map<String, Object> result = singleNode(viewService.summary());
        assertEquals("UNKNOWN", result.get("status"));
        assertFalse(Boolean.TRUE.equals(result.get("controlPlaneReachable")));
        assertNull(result.get("checkedAt"));
    }

    @Test
    void newlyIdentifiedNodeCannotReuseUnidentifiedCachedResult() {
        Node unnamed = node(ENTRY_NODE, null);
        connected.add(ENTRY_NODE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(6L, "OK"));
        probeService.probeNode(unnamed);
        stubNodes(node(ENTRY_NODE, "replacement"));
        assertEquals("UNKNOWN", singleNode(viewService.summary()).get("status"));
    }

    @Test
    void nodeDetailUsesTheSameStaleRule() {
        stubNodes(node(ENTRY_NODE, "hk-entry"));
        connected.add(ENTRY_NODE);
        when(transport.probe(ENTRY_NODE)).thenReturn(NodeProbeTransport.Reply.ok(7L, "OK"));
        probeService.probeNode(node(ENTRY_NODE, "hk-entry"));
        expire();

        Map<String, Object> nodeView = (Map<String, Object>) viewService.node(ENTRY_NODE).get("node");
        assertEquals("UNKNOWN", nodeView.get("status"));
        assertEquals(Boolean.TRUE, nodeView.get("stale"));
        assertEquals(Long.valueOf(7L), nodeView.get("lastLatencyMs"));
        assertFalse(Boolean.TRUE.equals(nodeView.get("controlPlaneReachable")));
    }

    // ========== 辅助 ==========

    private void expire() {
        clock.advance(FRESH + 1L);
    }

    private void stubNodes(Node... nodes) {
        when(nodeService.list()).thenReturn(new ArrayList<>(Arrays.asList(nodes)));
        when(tunnelService.list()).thenReturn(Collections.emptyList());
        when(chainTunnelService.list()).thenReturn(Collections.emptyList());
    }

    private void stubTopology() {
        when(nodeService.list()).thenReturn(new ArrayList<>(Arrays.asList(
                node(ENTRY_NODE, "hk-entry"), node(EXIT_NODE, "us-exit"))));
        when(tunnelService.list()).thenReturn(Collections.singletonList(tunnel(TUNNEL_ID, "hk→us")));
        when(chainTunnelService.list()).thenReturn(Arrays.asList(
                chainRow(20L, TUNNEL_ID, 3, EXIT_NODE, null),
                chainRow(10L, TUNNEL_ID, 1, ENTRY_NODE, null)));
    }

    private static Map<String, Object> singleNode(Map<String, Object> summary) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) summary.get("nodes");
        assertEquals(1, nodes.size());
        return nodes.get(0);
    }

    private static int number(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).intValue();
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
