package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.TunnelDto;
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
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 隧道节点重复的回归测试：同一 nodeId 在入口列表里出现两次会写两条
 * (tunnel_id, node_id, chain_type=1) 行，建转发时同一节点被写两条 forward_port、
 * 同名服务下发两次（round46 真机已验证多行会被重复处理）。
 *
 * <p>创建与拓扑编辑共用 TunnelServiceImpl.validateChainRows：重复必须在写库/下发之前报出来，
 * 因此每个拒绝用例都验证 chainTunnelService 的 saveBatch/update/removeByIds 与节点下发动作一次都没发生。
 *
 * <p>round48 W3：入口/中继同跳/出口/跨位置四处细分文案与 createTunnel、updateTunnelTopologyLocked
 * 两处拍平兜底现在都由 TunnelServiceImpl.duplicateMessage(position, unitHint, nodeId) 产生。
 * 兜底在结构上不可达（validateChainRows 已覆盖 node_ids 的全部重复形态），
 * 所以兜底的文案与"取第一个重复节点"的判定用反射直接验证，不伪造 API 入口。
 */
class EntryNodeDedupTest {

    private static final long TUNNEL_ID = 9L;

    private final Map<Long, Node> nodes = new LinkedHashMap<>();
    private final List<ChainTunnel> oldRows = new ArrayList<>();
    private RecordingTunnelService service;
    private TunnelMapper tunnelMapper;
    private ChainTunnelService chainTunnelService;

    @BeforeEach
    void setUp() {
        tunnelMapper = mock(TunnelMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        NodeService nodeService = mock(NodeService.class);
        ForwardService forwardService = mock(ForwardService.class);
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
        nodes.put(1L, node(1L, "node-a", "10.0.0.1", "30100-30120"));
        nodes.put(2L, node(2L, "node-b", "10.0.0.2", "30200-30220"));
        nodes.put(3L, node(3L, "node-c", "10.0.0.3", "30300-30320"));
        nodes.put(4L, node(4L, "node-d", "10.0.0.4", "30400-30410"));
        nodes.put(5L, node(5L, "node-e", "10.0.0.5", "30500-30510"));
        oldRows.add(row(101L, 1L, 1, null, null, null));
        oldRows.add(row(102L, 2L, 2, 30201, 1, "tls"));
        oldRows.add(row(103L, 3L, 3, 30202, null, "tls"));

        when(nodeService.getById(any())).thenAnswer(invocation -> nodes.get((Long) invocation.getArgument(0)));
        when(nodeService.list(any())).thenAnswer(invocation -> new ArrayList<>(nodes.values()));
        when(chainTunnelService.list(any())).thenAnswer(invocation -> new ArrayList<ChainTunnel>());
        when(chainTunnelService.saveBatch(anyCollection())).thenReturn(true);
        when(chainTunnelService.removeByIds(anyCollection())).thenReturn(true);
        when(forwardPortService.list(any())).thenReturn(new ArrayList<ForwardPort>());
        when(forwardService.count(any())).thenReturn(0);
        when(tunnelMapper.selectById(any())).thenReturn(tunnel(2));
        when(tunnelMapper.updateById(any())).thenReturn(1);
    }

    // ---------- 创建 ----------

    @Test
    void createRejectsDuplicateEntryNode() {
        // 入口 [1,1]：会写两条 (tunnel_id=9, node_id=1, chain_type=1)，同一入口服务下发两次
        R result = service.createTunnel(tunnelDto(
                List.of(entry(1L), entry(1L)),
                List.of(List.of(relay(2L))),
                List.of(exit(3L))));

        assertNotEquals(0, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("入口节点重复"), result.getMsg());
        assertTrue(result.getMsg().contains("节点1"), result.getMsg());
        assertNoWriteOrDispatch();
    }

    @Test
    void createRejectsDuplicateRelayWithinSameHop() {
        // 同一跳 [2,2]：两条 (tunnel_id=9, node_id=2, chain_type=2, inx=1) 行
        R result = service.createTunnel(tunnelDto(
                List.of(entry(1L)),
                List.of(List.of(relay(2L), relay(2L))),
                List.of(exit(3L))));

        assertNotEquals(0, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("中继第1跳节点重复"), result.getMsg());
        assertTrue(result.getMsg().contains("节点2"), result.getMsg());
        assertNoWriteOrDispatch();
    }

    @Test
    void duplicateNodeAcrossDifferentHopsIsRejectedByChainValidation() throws Exception {
        // 跨跳重复（hop1=[2]、hop2=[2]）已下沉到结构校验层拒绝：
        // 同一节点跨跳会让 chains_<tid>（GostUtil.java:125）与 <tid>_tls（:176）同名，
        // 第二次下发撞 already exists，整批回滚（QA round47 WARN-2）
        Object validated = ReflectionTestUtils.invokeMethod(service, "validateChainRows", tunnelDto(
                List.of(entry(1L)),
                List.of(List.of(relay(2L)), List.of(relay(2L))),
                List.of(exit(3L))));
        assertNotNull(validated, "跨跳的同一节点必须在校验层就被拒");
        R chainResult = (R) validated;
        assertNotEquals(0, chainResult.getCode(), chainResult.getMsg());
        assertEquals(invokeDuplicateTemplate(null, null, 2L), chainResult.getMsg(), chainResult.getMsg());

        R result = service.createTunnel(tunnelDto(
                List.of(entry(1L)),
                List.of(List.of(relay(2L)), List.of(relay(2L))),
                List.of(exit(3L))));
        assertNotEquals(0, result.getCode(), result.getMsg());
        assertEquals(invokeDuplicateTemplate(null, null, 2L), result.getMsg(), result.getMsg());
        assertNoWriteOrDispatch();
    }

    @Test
    void entryNodeReusedAsHopIsRejectedByChainValidation() throws Exception {
        // 入口同时当第一跳：chains_<tid> 会下到入口节点两次（同一节点两行 chain_type=1/2）
        Object validated = ReflectionTestUtils.invokeMethod(service, "validateChainRows", tunnelDto(
                List.of(entry(1L)),
                List.of(List.of(relay(1L))),
                List.of(exit(3L))));
        assertNotNull(validated, "入口复用为跳点必须被拒");
        R chainResult = (R) validated;
        assertEquals(invokeDuplicateTemplate(null, null, 1L), chainResult.getMsg(), chainResult.getMsg());

        R result = service.createTunnel(tunnelDto(
                List.of(entry(1L)),
                List.of(List.of(relay(1L))),
                List.of(exit(3L))));
        assertNotEquals(0, result.getCode(), result.getMsg());
        assertNoWriteOrDispatch();
    }

    @Test
    void createRejectsMissingNodeId() {
        ChainTunnel noNodeId = new ChainTunnel();
        noNodeId.setChainType(1);

        R entryResult = service.createTunnel(tunnelDto(
                List.of(noNodeId),
                List.of(List.of(relay(2L))),
                List.of(exit(3L))));

        assertNotEquals(0, entryResult.getCode(), entryResult.getMsg());
        assertTrue(entryResult.getMsg().contains("缺少 nodeId"), entryResult.getMsg());
        assertTrue(entryResult.getMsg().contains("第 1 个入口节点"), entryResult.getMsg());

        ChainTunnel noRelayNodeId = new ChainTunnel();
        noRelayNodeId.setChainType(2);
        noRelayNodeId.setProtocol("tls");

        R relayResult = service.createTunnel(tunnelDto(
                List.of(entry(1L)),
                List.of(List.of(noRelayNodeId)),
                List.of(exit(3L))));

        assertNotEquals(0, relayResult.getCode(), relayResult.getMsg());
        assertTrue(relayResult.getMsg().contains("缺少 nodeId"), relayResult.getMsg());
        assertTrue(relayResult.getMsg().contains("第 1 组第 1 个转发链节点"), relayResult.getMsg());
        assertNoWriteOrDispatch();
    }

    @Test
    void createRejectsNonPositiveNodeId() {
        R result = service.createTunnel(tunnelDto(
                List.of(entry(0L)),
                List.of(List.of(relay(2L))),
                List.of(exit(3L))));

        assertNotEquals(0, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("nodeId=0"), result.getMsg());
        assertTrue(result.getMsg().contains("第 1 个入口节点"), result.getMsg());
        assertNoWriteOrDispatch();
    }

    // ---------- 拓扑编辑 ----------

    @Test
    void topologyChangeRejectsDuplicateEntryNode() {
        R result = service.updateTunnelTopology(topology(
                List.of(entry(1L), entry(1L)),
                List.of(List.of(relay(2L))),
                List.of(exit(3L))));

        assertNotEquals(0, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("入口节点重复"), result.getMsg());
        assertTrue(result.getMsg().contains("节点1"), result.getMsg());
        assertNoWriteOrDispatch();
        verify(tunnelMapper, never()).updateById(any());
    }

    @Test
    void topologyChangeRejectsDuplicateRelayWithinSameHop() {
        R result = service.updateTunnelTopology(topology(
                List.of(entry(1L)),
                List.of(List.of(relay(4L), relay(4L))),
                List.of(exit(3L))));

        assertNotEquals(0, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("中继第1跳节点重复"), result.getMsg());
        assertTrue(result.getMsg().contains("节点4"), result.getMsg());
        assertNoWriteOrDispatch();
        verify(tunnelMapper, never()).updateById(any());
    }

    @Test
    void topologyChangeRejectsDuplicateOutNode() {
        R result = service.updateTunnelTopology(topology(
                List.of(entry(1L)),
                List.of(List.of(relay(2L))),
                List.of(exit(3L), exit(3L))));

        assertNotEquals(0, result.getCode(), result.getMsg());
        assertTrue(result.getMsg().contains("出口节点重复"), result.getMsg());
        assertTrue(result.getMsg().contains("节点3"), result.getMsg());
        assertNoWriteOrDispatch();
        verify(tunnelMapper, never()).updateById(any());
    }

    // ---------- 拍平兜底（createTunnel / updateTunnelTopologyLocked）----------
    // 这两处兜底不可达：validateChainRows（createTunnel 第 73 行、updateTunnelTopologyLocked 第 905 行各调一次）
    // 已经把入口重复、同一跳重复、出口重复、跨位置重复全部拦下，拍平列表里不可能带着重复节点走到兜底。
    // 所以兜底的文案与判定用反射直接验证，不去伪造一条根本走不到的 API 路径。

    @Test
    void flatFallbackNamesFirstDuplicateNode() throws Exception {
        assertEquals("节点重复：节点2 在入口/中继/出口中重复出现（整个拓扑内只能出现一次）",
                invokeFirstDuplicate(List.of(1L, 2L, 2L, 2L)));
        // 报的是第一个重复出现的节点，不是最后一个：入口 4 → 中继 1 → 出口 4
        assertEquals("节点重复：节点4 在入口/中继/出口中重复出现（整个拓扑内只能出现一次）",
                invokeFirstDuplicate(List.of(4L, 1L, 4L)));
    }

    @Test
    void flatFallbackStaysSilentWithoutDuplicate() throws Exception {
        assertNull(invokeFirstDuplicate(List.of()), "空拓扑不该被兜底误杀");
        assertNull(invokeFirstDuplicate(List.of(1L, 2L, 3L)), "没有重复时兜底必须放行");
    }

    @Test
    void flatFallbackAndSplitChecksShareOneTemplate() throws Exception {
        // 兜底（position == null）与细分校验同源：同一 nodeId 出来的文案必须逐字相同
        assertEquals(invokeDuplicateTemplate(null, null, 7L), invokeFirstDuplicate(List.of(7L, 8L, 7L)));
        assertEquals("入口节点重复：节点5（同一入口只能出现一次）",
                invokeDuplicateTemplate("入口节点", "入口", 5L));
        assertEquals("中继第2跳节点重复：节点5（同一跳只能出现一次）",
                invokeDuplicateTemplate("中继第2跳节点", "跳", 5L));
        assertEquals("出口节点重复：节点5（同一出口只能出现一次）",
                invokeDuplicateTemplate("出口节点", "出口", 5L));
    }

    @Test
    void liveCreateMessagesComeFromTheSameTemplate() throws Exception {
        R crossRole = service.createTunnel(tunnelDto(
                List.of(entry(1L)),
                List.of(List.of(relay(1L))),
                List.of(exit(3L))));
        assertEquals(invokeDuplicateTemplate(null, null, 1L), crossRole.getMsg(), crossRole.getMsg());

        R duplicateEntry = service.createTunnel(tunnelDto(
                List.of(entry(4L), entry(4L)),
                List.of(List.of(relay(2L))),
                List.of(exit(3L))));
        assertEquals(invokeDuplicateTemplate("入口节点", "入口", 4L), duplicateEntry.getMsg(), duplicateEntry.getMsg());

        R duplicateOut = service.createTunnel(tunnelDto(
                List.of(entry(1L)),
                List.of(List.of(relay(2L))),
                List.of(exit(5L), exit(5L))));
        assertEquals(invokeDuplicateTemplate("出口节点", "出口", 5L), duplicateOut.getMsg(), duplicateOut.getMsg());

        assertNoWriteOrDispatch();
    }

    @Test
    void everyDuplicateShapeIsRejectedBeforeTheFlatFallback() {
        // 可达性论证：node_ids 的每种重复形态都先在结构校验层被拒，拍平兜底没有入口可走
        List<TunnelDto> duplicateShapes = List.of(
                tunnelDto(List.of(entry(1L), entry(1L)), List.of(List.of(relay(2L))), List.of(exit(3L))),
                tunnelDto(List.of(entry(1L)), List.of(List.of(relay(2L), relay(2L))), List.of(exit(3L))),
                tunnelDto(List.of(entry(1L)), List.of(List.of(relay(2L))), List.of(exit(3L), exit(3L))),
                tunnelDto(List.of(entry(1L)), List.of(List.of(relay(1L))), List.of(exit(3L))),
                tunnelDto(List.of(entry(1L)), List.of(List.of(relay(2L))), List.of(exit(2L))));
        for (TunnelDto shape : duplicateShapes) {
            Object validated = ReflectionTestUtils.invokeMethod(service, "validateChainRows", shape);
            assertTrue(validated instanceof R, "重复形态必须在结构校验层就被拒");
            assertNotEquals(0, ((R) validated).getCode(), ((R) validated).getMsg());
        }
        // 反证：不重复的拓扑能过结构校验，兜底判重也不会误报
        Object clean = ReflectionTestUtils.invokeMethod(service, "validateChainRows",
                tunnelDto(List.of(entry(1L)), List.of(List.of(relay(2L))), List.of(exit(3L))));
        assertNull(clean, "不重复的拓扑不该被结构校验拦下");
    }

    // ---------- 断言 ----------

    private void assertNoWriteOrDispatch() {
        verify(chainTunnelService, never()).saveBatch(anyCollection());
        verify(chainTunnelService, never()).update(any());
        verify(chainTunnelService, never()).removeByIds(anyCollection());
        assertTrue(service.ops.isEmpty(), "拒绝时不能对节点做任何动作：" + service.ops);
        assertTrue(service.savedTunnelIds.isEmpty(), "拒绝时不能写隧道记录：" + service.savedTunnelIds);
    }

    // ---------- 夹具 ----------

    /**
     * 反射调用拍平兜底的判重入口：它拿不到具体 nodeId 之外的上下文，返回 null 表示没有重复。
     */
    private static String invokeFirstDuplicate(List<Long> nodeIds) throws Exception {
        return invokeStatic("firstDuplicateMessage", new Class<?>[]{List.class}, nodeIds);
    }

    /**
     * 反射调用文案模板：细分校验与拍平兜底共用这一个方法，用它当唯一基准做逐字比对。
     */
    private static String invokeDuplicateTemplate(String position, String unitHint, Long nodeId) throws Exception {
        return invokeStatic("duplicateMessage", new Class<?>[]{String.class, String.class, Long.class},
                position, unitHint, nodeId);
    }

    private static String invokeStatic(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = TunnelServiceImpl.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return (String) method.invoke(null, args);
    }

    /**
     * 替身：GostUtil 是静态方法，离线环境没有 mockito-inline，改为覆盖下发出口来记录节点动作。
     */
    static class RecordingTunnelService extends TunnelServiceImpl {

        final List<String> ops = new ArrayList<>();
        final List<Long> savedTunnelIds = new ArrayList<>();

        @Override
        public int count(Wrapper<Tunnel> queryWrapper) {
            return 0;
        }

        @Override
        public boolean save(Tunnel entity) {
            entity.setId(TUNNEL_ID);
            savedTunnelIds.add(TUNNEL_ID);
            return true;
        }

        @Override
        protected GostDto createChains(Long nodeId, List<ChainTunnel> chainTunnels, Map<Long, Node> nodes, Tunnel tunnel) {
            ops.add("createChains:" + nodeId);
            return ok();
        }

        @Override
        protected GostDto createChainService(Long nodeId, ChainTunnel chainTunnel, Map<Long, Node> nodes, Tunnel tunnel) {
            ops.add("createService:" + nodeId);
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
        tunnel.setName("dedup-fixture");
        tunnel.setType(type);
        tunnel.setFlow(1);
        tunnel.setTrafficRatio(BigDecimal.ONE);
        tunnel.setInIp("10.0.0.1");
        return tunnel;
    }

    private static Node node(Long id, String name, String serverIp, String port) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setServerIp(serverIp);
        node.setPort(port);
        node.setTcpListenAddr("0.0.0.0");
        node.setStatus(1);
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

    private static ChainTunnel entry(Long nodeId) {
        return row(null, nodeId, 1, null, null, null);
    }

    private static ChainTunnel relay(Long nodeId) {
        return row(null, nodeId, 2, null, null, "tls");
    }

    private static ChainTunnel exit(Long nodeId) {
        return row(null, nodeId, 3, null, null, "tls");
    }

    private static TunnelDto tunnelDto(List<ChainTunnel> in, List<List<ChainTunnel>> chains, List<ChainTunnel> out) {
        TunnelDto dto = new TunnelDto();
        dto.setName("dedup-fixture");
        dto.setType(2);
        dto.setFlow(1);
        dto.setTrafficRatio(BigDecimal.ONE);
        dto.setInNodeId(new ArrayList<>(in));
        dto.setChainNodes(chains.stream().map(ArrayList::new).collect(Collectors.toList()));
        dto.setOutNodeId(new ArrayList<>(out));
        return dto;
    }

    private static TunnelTopologyDto topology(List<ChainTunnel> in, List<List<ChainTunnel>> chains, List<ChainTunnel> out) {
        TunnelTopologyDto dto = new TunnelTopologyDto();
        dto.setId(TUNNEL_ID);
        dto.setType(2);
        dto.setInNodeId(new ArrayList<>(in));
        dto.setChainNodes(chains.stream().map(ArrayList::new).collect(Collectors.toList()));
        dto.setOutNodeId(new ArrayList<>(out));
        return dto;
    }
}
