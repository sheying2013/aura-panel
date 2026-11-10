package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.TunnelTopologyDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.TunnelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 隧道拓扑变更（入口/转发链/出口）在本地夹具上的行为锁定。
 *
 * <p>真实缺陷（圣何塞隔离环境读码确认）：TunnelServiceImpl.updateTunnel 只写 name/flow/trafficRatio/inIp，
 * 拓扑字段完全不处理，面板上“改拓扑”等于没改；而入口迁移会牵动 forward_port 绑定，不能只重建监听。
 *
 * <p>这里锁死：① 有转发时直接拒绝，不做任何节点动作；② 节点离线/重复/直连带链都拒绝；
 * ③ 成功路径先按新拓扑重建、再清旧独占节点、最后换库，且端口由节点段自动分配并写进入库的新行；
 * ④ 下发失败时必须把旧拓扑重新拉起来并且不动数据库；⑤ 自动端口撞“address already in use”时换口。
 */
class TunnelTopologyTest {

    private static final long TUNNEL_ID = 9L;

    private RecordingTunnelService service;
    private TunnelMapper tunnelMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private ForwardService forwardService;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final List<ChainTunnel> oldRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tunnelMapper = mock(TunnelMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        forwardService = mock(ForwardService.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);

        service = new RecordingTunnelService();
        ReflectionTestUtils.setField(service, "baseMapper", tunnelMapper);
        ReflectionTestUtils.setField(service, "nodeService", nodeService);
        ReflectionTestUtils.setField(service, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(service, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(service, "forwardService", forwardService);
        ReflectionTestUtils.setField(service, "userTunnelService", mock(UserTunnelService.class));

        nodes.clear();
        oldRows.clear();
        nodes.put(1L, node(1L, "node-a", "10.0.0.1", "30100-30120", 1));
        nodes.put(2L, node(2L, "node-b", "10.0.0.2", "30200-30220", 1));
        nodes.put(3L, node(3L, "node-c", "10.0.0.3", "30300-30320", 1));
        nodes.put(4L, node(4L, "node-d", "10.0.0.4", "30400-30410", 1));
        nodes.put(5L, node(5L, "node-e", "10.0.0.5", "30500-30510", 1));
        oldRows.add(row(101L, 1L, 1, null, null, null));
        oldRows.add(row(102L, 2L, 2, 30201, 1, "tls"));
        oldRows.add(row(103L, 3L, 3, 30202, null, "tls"));

        when(nodeService.getById(any())).thenAnswer(invocation -> nodes.get((Long) invocation.getArgument(0)));
        when(chainTunnelService.list(any())).thenAnswer(invocation -> new ArrayList<>(oldRows));
        when(chainTunnelService.saveBatch(anyCollection())).thenReturn(true);
        when(chainTunnelService.removeByIds(anyCollection())).thenReturn(true);
        when(forwardPortService.list(any())).thenAnswer(invocation -> new ArrayList<ForwardPort>());
        when(forwardService.count(any())).thenReturn(0);
        when(tunnelMapper.selectById(any())).thenReturn(tunnel(2));
        when(tunnelMapper.updateById(any())).thenReturn(1);
    }

    @Test
    void entryMigrationIsHandedToForwardServiceWhenEntryCountUnchanged() {
        when(forwardService.count(any())).thenReturn(2);
        when(forwardService.migrateEntryNodes(any(Tunnel.class), anyList(), anyList(), anyMap())).thenReturn(null);

        // 旧入口 node1 → 新入口 node4，数量一致：拓扑先重建，再按序把 2 条转发的入口服务搬过去
        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 4L, 1, null, null, null)),
                List.of(List.of(row(null, 2L, 2, null, null, null))),
                List.of(row(null, 5L, 3, null, null, null))));

        assertEquals(0, result.getCode(), result.getMsg());
        ArgumentCaptor<List<ChainTunnel>> oldEntries = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ChainTunnel>> newEntries = ArgumentCaptor.forClass(List.class);
        verify(forwardService).migrateEntryNodes(any(Tunnel.class), oldEntries.capture(), newEntries.capture(), anyMap());
        assertEquals(List.of(1L), oldEntries.getValue().stream().map(ChainTunnel::getNodeId).toList());
        assertEquals(List.of(4L), newEntries.getValue().stream().map(ChainTunnel::getNodeId).toList());
        List<String> notes = (List<String>) result.getData();
        assertTrue(notes != null && notes.stream().anyMatch(note -> note.contains("入口节点已迁移")),
                "迁移结果必须回给调用方，实际：" + notes);
        verify(chainTunnelService).removeByIds(anyCollection());
        verify(chainTunnelService).saveBatch(anyCollection());
    }

    @Test
    void entryCountChangeIsRejectedWhileForwardsStillExist() {
        when(forwardService.count(any())).thenReturn(2);

        // 单入口变双入口无法一一对应：只允许删转发后再改，不能猜哪条转发该落到哪个入口
        R result = service.updateTunnelTopology(dto(2,
                List.of(row(null, 4L, 1, null, null, null), row(null, 5L, 1, null, null, null)),
                List.of(List.of(row(null, 2L, 2, null, null, null))),
                List.of(row(null, 3L, 3, null, null, null))));

        assertEquals(-1, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("入口节点数量变化"), result.getMsg());
        assertTrue(service.ops.isEmpty(), "拒绝时不能对节点做任何动作：" + service.ops);
        verify(forwardService, never()).migrateEntryNodes(any(Tunnel.class), anyList(), anyList(), anyMap());
        verify(chainTunnelService, never()).removeByIds(anyCollection());
        verify(chainTunnelService, never()).saveBatch(anyCollection());
    }

    @Test
    void relayAndOutCanChangeWhileForwardsStillExist() {
        when(forwardService.count(any())).thenReturn(2);

        // 入口仍是 node1，只把中继换成 node4、出口换成 node5：入口服务与 forward_port 不受影响，可以热改
        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 1L, 1, null, null, null)),
                List.of(List.of(row(null, 4L, 2, null, null, null))),
                List.of(row(null, 5L, 3, null, null, null))));

        assertEquals(0, result.getCode(), result.getMsg());
        assertTrue(service.ops.contains("createChains:1:9"), service.ops.toString());
        assertTrue(service.ops.contains("createService:5:30500:9"), service.ops.toString());
        verify(chainTunnelService).removeByIds(anyCollection());
        verify(chainTunnelService).saveBatch(anyCollection());
        // 旧中继/出口节点被清掉；入口 node1 只重建 chains_9、不删任何转发服务（publishTopology 只删 <tid>_tls）
        assertTrue(service.ops.contains("deleteService:2"), service.ops.toString());
        assertTrue(service.ops.contains("deleteService:3"), service.ops.toString());
    }

    @Test
    void offlineNodeRejectsTopologyChange() {
        nodes.put(4L, node(4L, "node-d", "10.0.0.4", "30400-30410", 0));

        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 1L, 1, null, null, null)),
                List.of(List.of(row(null, 4L, 2, null, null, null))),
                List.of(row(null, 5L, 3, null, null, null))));

        assertEquals(-1, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("部分节点不在线"), result.getMsg());
        assertTrue(result.getMsg().contains("node-d"), result.getMsg());
        assertTrue(service.ops.isEmpty(), "拒绝时不能对节点做任何动作：" + service.ops);
    }

    @Test
    void duplicateNodeAcrossRolesIsRejected() {
        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 1L, 1, null, null, null)),
                List.of(List.of(row(null, 1L, 2, null, null, null))),
                List.of(row(null, 5L, 3, null, null, null))));

        assertEquals(-1, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("节点重复"), result.getMsg());
        assertTrue(service.ops.isEmpty(), "拒绝时不能对节点做任何动作：" + service.ops);
    }

    @Test
    void directTunnelWithChainIsRejected() {
        R result = service.updateTunnelTopology(dto(1, List.of(row(null, 1L, 1, null, null, null)),
                List.of(List.of(row(null, 4L, 2, null, null, null))),
                Collections.emptyList()));

        assertEquals(-1, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("直连隧道不能配置转发链或出口节点"), result.getMsg());
        assertTrue(service.ops.isEmpty(), "拒绝时不能对节点做任何动作：" + service.ops);
    }

    @Test
    void tunnelTunnelWithoutOutIsRejected() {
        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 1L, 1, null, null, null)),
                Collections.emptyList(), Collections.emptyList()));

        assertEquals(-1, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("出口不能为空"), result.getMsg());
        assertTrue(service.ops.isEmpty(), "拒绝时不能对节点做任何动作：" + service.ops);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulChangeRebuildsNodesThenSwapsRowsAndCleansOldOnlyNodes() {
        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 1L, 1, null, null, null)),
                List.of(List.of(row(null, 4L, 2, null, null, null))),
                List.of(row(null, 5L, 3, null, null, null))));

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(List.of(
                "deleteChains:1", "deleteService:1",
                "deleteChains:4", "deleteService:4",
                "deleteChains:5", "deleteService:5",
                "createChains:1:9",
                "createChains:4:9", "createService:4:30400:9",
                "createService:5:30500:9",
                "deleteService:2", "deleteChains:2",
                "deleteService:3", "deleteChains:3"
        ), service.ops, "必须先清理拓扑内节点旧资源、重建新链路，最后清掉旧独占节点");
        assertTrue(service.ops.stream().filter(op -> op.startsWith("create"))
                        .allMatch(op -> op.endsWith(":9")),
                "下发时行上必须已挂 tunnelId，否则会发出 chains_null/null_tls：" + service.ops);

        ArgumentCaptor<Collection<Long>> removed = ArgumentCaptor.forClass(Collection.class);
        verify(chainTunnelService).removeByIds(removed.capture());
        assertEquals(Set.of(101L, 102L, 103L), new HashSet<>(removed.getValue()));

        ArgumentCaptor<Collection<ChainTunnel>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(chainTunnelService).saveBatch(saved.capture());
        List<ChainTunnel> written = new ArrayList<>(saved.getValue());
        assertEquals(3, written.size());
        assertTrue(written.stream().allMatch(r -> Long.valueOf(TUNNEL_ID).equals(r.getTunnelId())),
                "新行必须全部挂到本隧道");
        ChainTunnel chain = written.stream().filter(r -> r.getChainType() == 2).findFirst().orElseThrow();
        assertEquals(4L, chain.getNodeId());
        assertEquals(30400, chain.getPort().intValue(), "自动端口必须写进最终入库的行");
        assertEquals(1, chain.getInx().intValue());
        assertEquals("tls", chain.getProtocol());
        ChainTunnel out = written.stream().filter(r -> r.getChainType() == 3).findFirst().orElseThrow();
        assertEquals(5L, out.getNodeId());
        assertEquals(30500, out.getPort().intValue());
        verify(chainTunnelService, never()).update(any());

        ArgumentCaptor<Tunnel> patch = ArgumentCaptor.forClass(Tunnel.class);
        verify(tunnelMapper).updateById(patch.capture());
        assertEquals(2, patch.getValue().getType().intValue());
        assertEquals("10.0.0.1", patch.getValue().getInIp(), "入口地址必须按新入口节点重算");
    }

    @Test
    void publishFailureRestoresOldTopologyWithoutTouchingDatabase() {
        service.failServiceOnceOn.add(4L);

        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 1L, 1, null, null, null)),
                List.of(List.of(row(null, 4L, 2, null, null, null))),
                List.of(row(null, 5L, 3, null, null, null))));

        assertEquals(-1, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("拓扑变更失败"), result.getMsg());
        assertTrue(result.getMsg().contains("节点4"), result.getMsg());
        assertTrue(service.ops.contains("createService:2:30201:9"), "必须按旧拓扑重建中继：" + service.ops);
        assertTrue(service.ops.contains("createService:3:30202:9"), "必须按旧拓扑重建出口：" + service.ops);
        assertTrue(service.ops.contains("deleteService:5") && service.ops.contains("deleteChains:5"),
                "新拓扑独占节点要清掉残留：" + service.ops);
        verify(chainTunnelService, never()).removeByIds(anyCollection());
        verify(chainTunnelService, never()).saveBatch(anyCollection());
        verify(tunnelMapper, never()).updateById(any());
        verify(chainTunnelService, never()).update(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void automaticPortRetriesNextCandidateWhenNodeReportsBindFailure() {
        service.bindFailures.put(4L, 1);

        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 1L, 1, null, null, null)),
                List.of(List.of(row(null, 4L, 2, null, null, null))),
                List.of(row(null, 5L, 3, null, null, null))));

        assertEquals(0, result.getCode(), result.getMsg());
        assertTrue(service.ops.contains("createService:4:30400:9"), service.ops.toString());
        assertTrue(service.ops.contains("createService:4:30401:9"), "撞 bind 后必须换口重试：" + service.ops);
        // 换口后上游 chains 必须按最终端口重发
        assertEquals(2, service.ops.stream().filter(op -> op.equals("createChains:1:9")).count());
        assertEquals(2, service.ops.stream().filter(op -> op.equals("createChains:4:9")).count());

        ArgumentCaptor<Collection<ChainTunnel>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(chainTunnelService).saveBatch(saved.capture());
        ChainTunnel chain = new ArrayList<>(saved.getValue()).stream()
                .filter(r -> r.getChainType() == 2).findFirst().orElseThrow();
        assertEquals(30401, chain.getPort().intValue(), "入库行必须是换口后的最终端口");
        verify(chainTunnelService, never()).update(any());
    }

    // ---------- 夹具 ----------

    private static final class RecordingTunnelService extends TunnelServiceImpl {
        private final List<String> ops = new ArrayList<>();
        private final Set<Long> failServiceOnceOn = new HashSet<>();
        private final Map<Long, Integer> bindFailures = new HashMap<>();

        @Override
        protected GostDto createChains(Long nodeId, List<ChainTunnel> chainTunnels, Map<Long, Node> nodes, Tunnel tunnel) {
            ops.add("createChains:" + nodeId + ":" + chainTunnels.get(0).getTunnelId());
            return ok();
        }

        @Override
        protected GostDto createChainService(Long nodeId, ChainTunnel chainTunnel, Map<Long, Node> nodes, Tunnel tunnel) {
            ops.add("createService:" + nodeId + ":" + chainTunnel.getPort() + ":" + chainTunnel.getTunnelId());
            Integer remaining = bindFailures.get(nodeId);
            if (remaining != null && remaining > 0) {
                bindFailures.put(nodeId, remaining - 1);
                return err("listen tcp 0.0.0.0:" + chainTunnel.getPort() + ": bind: address already in use");
            }
            if (failServiceOnceOn.remove(nodeId)) {
                return err("create service boom");
            }
            return ok();
        }

        @Override
        protected GostDto deleteChains(Long nodeId, String name) {
            ops.add("deleteChains:" + nodeId);
            return ok();
        }

        @Override
        protected GostDto deleteService(Long nodeId, String name) {
            ops.add("deleteService:" + nodeId);
            return ok();
        }

        private static GostDto ok() {
            GostDto dto = new GostDto();
            dto.setMsg("OK");
            return dto;
        }

        private static GostDto err(String msg) {
            GostDto dto = new GostDto();
            dto.setMsg(msg);
            return dto;
        }
    }

    private static Tunnel tunnel(int type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("sj-topology-fixture");
        tunnel.setType(type);
        tunnel.setFlow(1);
        tunnel.setTrafficRatio(new java.math.BigDecimal("1"));
        tunnel.setInIp("10.0.0.1");
        return tunnel;
    }

    private static Node node(Long id, String name, String serverIp, String port, int status) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setServerIp(serverIp);
        node.setPort(port);
        node.setStatus(status);
        return node;
    }

    private static ChainTunnel row(Long id, Long nodeId, int chainType, Integer port, Integer inx, String protocol) {
        ChainTunnel row = new ChainTunnel();
        row.setId(id);
        row.setTunnelId(TUNNEL_ID);
        row.setNodeId(nodeId);
        row.setChainType(chainType);
        row.setPort(port);
        row.setInx(inx);
        row.setProtocol(protocol);
        return row;
    }

    private static TunnelTopologyDto dto(int type, List<ChainTunnel> in, List<List<ChainTunnel>> chains,
                                         List<ChainTunnel> out) {
        TunnelTopologyDto dto = new TunnelTopologyDto();
        dto.setId(TUNNEL_ID);
        dto.setType(type);
        dto.setInNodeId(new ArrayList<>(in));
        dto.setChainNodes(new ArrayList<>(chains));
        dto.setOutNodeId(new ArrayList<>(out));
        return dto;
    }
}
