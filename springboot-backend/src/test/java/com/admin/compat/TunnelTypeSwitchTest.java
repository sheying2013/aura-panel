package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.TunnelTopologyDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
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
import java.util.HashMap;
import java.util.HashSet;
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
 * 隧道类型切换（直连 type=1 / 中继 type=2）的本地夹具锁定。
 *
 * <p>真实缺陷（圣何塞隔离环境读码 + 基线复现）：updateTunnelTopologyLocked 只在 type==2 时
 * publishTopology，且只有 entriesChanged 才 migrateEntryNodes；因此 2→1 既不清理旧中继链/中继服务，
 * 也不重建存量转发（handler 仍挂 chains_&lt;id&gt;），1→2 建好链但入口服务仍直连绕过新链。
 * 两条路径都不会自愈：孤儿清扫只看 tunnel 行是否存在，2→1 之后 chains_/&lt;tid&gt;_tls 永久占端口。
 *
 * <p>这里锁死：① 2→1 必须按新类型重建存量转发入口服务，并清掉共享入口节点上的链资源；
 * ② 1→2 必须先 publish 新拓扑、再按 type=2 重建；③ 重建失败必须按旧类型回滚转发并恢复旧拓扑，
 * 不动数据库；④ 类型与入口同时变直接拒绝；⑤ 无转发时只做清理不重建。
 */
class TunnelTypeSwitchTest {

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

        when(nodeService.getById(any())).thenAnswer(invocation -> nodes.get((Long) invocation.getArgument(0)));
        when(chainTunnelService.list(any())).thenAnswer(invocation -> new ArrayList<>(oldRows));
        when(chainTunnelService.saveBatch(anyCollection())).thenReturn(true);
        when(chainTunnelService.removeByIds(anyCollection())).thenReturn(true);
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardService.count(any())).thenReturn(0);
        when(forwardService.rebuildTunnelForwardServices(any(), anyList())).thenReturn(null);
        when(tunnelMapper.selectById(any())).thenReturn(tunnel(2));
        when(tunnelMapper.updateById(any())).thenReturn(1);
    }

    @Test
    void typeSwitchToDirectRebuildsEntryServicesAndCleansChainResourcesOnSharedNodes() {
        oldRows.add(row(101L, 1L, 1, null, null, null));
        oldRows.add(row(102L, 2L, 2, 30201, 1, "tls"));
        oldRows.add(row(103L, 3L, 3, 30202, null, "tls"));
        when(forwardService.count(any())).thenReturn(1);

        // 2→1：入口节点不变（node1），中继/出口被撤掉；node1 同时属于旧新拓扑，必须单独清链资源
        R result = service.updateTunnelTopology(dto(1, List.of(row(null, 1L, 1, null, null, null)),
                List.of(), List.of()));

        assertEquals(0, result.getCode(), result.getMsg());
        ArgumentCaptor<Tunnel> dispatched = ArgumentCaptor.forClass(Tunnel.class);
        verify(forwardService).rebuildTunnelForwardServices(dispatched.capture(), anyList());
        assertEquals(1, dispatched.getValue().getType().intValue(),
                "重建时必须按新类型下发，否则入口服务仍挂 chains_9");
        List<String> notes = (List<String>) result.getData();
        assertTrue(notes != null && notes.stream().anyMatch(n -> n.contains("隧道类型已切换")),
                "类型切换结果必须回给调用方：" + notes);
        assertEquals(List.of(
                "deleteService:1", "deleteChains:1",
                "deleteService:2", "deleteChains:2",
                "deleteService:3", "deleteChains:3"), service.ops,
                "2→1 必须把共享入口与旧独占节点上的链资源全部清掉：" + service.ops);

        ArgumentCaptor<Collection<ChainTunnel>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(chainTunnelService).saveBatch(saved.capture());
        assertEquals(1, saved.getValue().size(), "直连隧道只允许入口行");
        ArgumentCaptor<Tunnel> patch = ArgumentCaptor.forClass(Tunnel.class);
        verify(tunnelMapper).updateById(patch.capture());
        assertEquals(1, patch.getValue().getType().intValue());
    }

    @Test
    void typeSwitchToRelayPublishesChainsThenRebuildsEntryServices() {
        oldRows.add(row(101L, 1L, 1, null, null, null));
        when(tunnelMapper.selectById(any())).thenReturn(tunnel(1));
        when(forwardService.count(any())).thenReturn(2);
        when(forwardService.rebuildTunnelForwardServices(any(), anyList())).thenAnswer(invocation -> {
            service.ops.add("rebuild:" + ((Tunnel) invocation.getArgument(0)).getType());
            return null;
        });

        // 1→2：入口仍是 node1，新增中继 node2（自动端口）与出口 node5
        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 1L, 1, null, null, null)),
                List.of(List.of(row(null, 2L, 2, null, null, null))),
                List.of(row(null, 5L, 3, null, null, null))));

        assertEquals(0, result.getCode(), result.getMsg());
        assertTrue(service.ops.contains("createChains:2:9"), service.ops.toString());
        assertTrue(service.ops.contains("createService:5:30500:9"), service.ops.toString());
        int lastCreate = -1;
        for (int i = 0; i < service.ops.size(); i++) {
            if (service.ops.get(i).startsWith("create")) lastCreate = i;
        }
        int rebuild = service.ops.indexOf("rebuild:2");
        assertTrue(rebuild > lastCreate,
                "必须先建好新链再重建入口服务，否则 handler.chain 指向不存在的链：" + service.ops);
        ArgumentCaptor<Tunnel> patch = ArgumentCaptor.forClass(Tunnel.class);
        verify(tunnelMapper).updateById(patch.capture());
        assertEquals(2, patch.getValue().getType().intValue());
        verify(chainTunnelService).saveBatch(anyCollection());
    }

    @Test
    void rebuildFailureRollsBackToOldTypeAndRestoresOldTopology() {
        oldRows.add(row(101L, 1L, 1, null, null, null));
        when(tunnelMapper.selectById(any())).thenReturn(tunnel(1));
        when(forwardService.count(any())).thenReturn(1);
        when(forwardService.rebuildTunnelForwardServices(any(), anyList())).thenAnswer(invocation -> {
            Tunnel target = invocation.getArgument(0);
            service.ops.add("rebuild:" + target.getType());
            return target.getType() == 2 ? "create service 1_1_0_tcp failed: boom" : null;
        });

        R result = service.updateTunnelTopology(dto(2, List.of(row(null, 1L, 1, null, null, null)),
                List.of(List.of(row(null, 2L, 2, null, null, null))),
                List.of(row(null, 5L, 3, null, null, null))));

        assertEquals(-1, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("隧道类型变更失败"), result.getMsg());
        assertTrue(result.getMsg().contains("已按旧类型恢复转发入口服务"), result.getMsg());
        assertEquals(List.of("rebuild:2", "rebuild:1"),
                service.ops.stream().filter(op -> op.startsWith("rebuild")).toList(),
                "必须先按新类型重建、失败后按旧类型重建回来：" + service.ops);
        assertTrue(service.ops.contains("deleteChains:1"),
                "旧拓扑是直连，回退时共享入口节点上的链条残留必须清掉：" + service.ops);
        verify(chainTunnelService, never()).removeByIds(anyCollection());
        verify(chainTunnelService, never()).saveBatch(anyCollection());
        verify(tunnelMapper, never()).updateById(any());
    }

    @Test
    void typeAndEntryChangeTogetherAreRejected() {
        oldRows.add(row(101L, 1L, 1, null, null, null));
        oldRows.add(row(102L, 2L, 2, 30201, 1, "tls"));
        oldRows.add(row(103L, 3L, 3, 30202, null, "tls"));
        when(forwardService.count(any())).thenReturn(1);

        R result = service.updateTunnelTopology(dto(1, List.of(row(null, 4L, 1, null, null, null)),
                List.of(), List.of()));

        assertEquals(-1, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("不能同时进行"), result.getMsg());
        assertTrue(service.ops.isEmpty(), "拒绝时不能对节点做任何动作：" + service.ops);
        verify(forwardService, never()).rebuildTunnelForwardServices(any(), anyList());
        verify(forwardService, never()).migrateEntryNodes(any(Tunnel.class), anyList(), anyList(), anyMap());
        verify(chainTunnelService, never()).saveBatch(anyCollection());
    }

    @Test
    void noForwardSkipsRebuildButStillCleansOldChainResources() {
        oldRows.add(row(101L, 1L, 1, null, null, null));
        oldRows.add(row(102L, 2L, 2, 30201, 1, "tls"));
        oldRows.add(row(103L, 3L, 3, 30202, null, "tls"));
        when(forwardService.count(any())).thenReturn(0);

        R result = service.updateTunnelTopology(dto(1, List.of(row(null, 1L, 1, null, null, null)),
                List.of(), List.of()));

        assertEquals(0, result.getCode(), result.getMsg());
        verify(forwardService, never()).rebuildTunnelForwardServices(any(), anyList());
        assertTrue(service.ops.contains("deleteChains:1"), "没有转发也要清共享入口的链资源：" + service.ops);
        assertFalse(service.ops.stream().anyMatch(op -> op.startsWith("create")),
                "直连类型不能下发任何链资源：" + service.ops);
    }

    // ---------- 夹具 ----------

    private static final class RecordingTunnelService extends TunnelServiceImpl {
        private final List<String> ops = new ArrayList<>();

        @Override
        protected GostDto createChains(Long nodeId, List<ChainTunnel> chainTunnels, Map<Long, Node> nodes, Tunnel tunnel) {
            ops.add("createChains:" + nodeId + ":" + chainTunnels.get(0).getTunnelId());
            return ok();
        }

        @Override
        protected GostDto createChainService(Long nodeId, ChainTunnel chainTunnel, Map<Long, Node> nodes, Tunnel tunnel) {
            ops.add("createService:" + nodeId + ":" + chainTunnel.getPort() + ":" + chainTunnel.getTunnelId());
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
    }

    private static Tunnel tunnel(int type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("sj-type-switch-fixture");
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
