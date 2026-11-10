package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.TunnelDto;
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
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创建隧道时显式指定的端口必须被采用，不能静默换成自动分配的下一个口。
 *
 * <p>真机缺陷：chainNodes 传 [{nodeId:2,chainType:2,protocol:tls,port:30202}]，落库成 30203，
 * 接口还回 code=0 —— 调用方以为端口按自己指定的落，实际监听在别处。
 *
 * <p>这里锁死：显式端口可用则采用；被本隧道/其它隧道/端口转发占用或不在节点允许范围时明确报错且不写库；
 * 不传端口仍走原来的自动分配，既有调用方结果不变。
 */
class TunnelCreateExplicitPortTest {

    private static final long TUNNEL_ID = 7L;
    private static final String NODE_PORT_RANGE = "30201-30210";

    private TunnelServiceImpl service;
    private TunnelMapper tunnelMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private final Map<Long, Node> nodes = new LinkedHashMap<>();
    private List<ChainTunnel> occupiedChainTunnels = new ArrayList<>();
    private List<ForwardPort> occupiedForwardPorts = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        nodes.clear();
        occupiedChainTunnels = new ArrayList<>();
        occupiedForwardPorts = new ArrayList<>();

        tunnelMapper = mock(TunnelMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);

        service = new TunnelServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", tunnelMapper);
        ReflectionTestUtils.setField(service, "nodeService", nodeService);
        ReflectionTestUtils.setField(service, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(service, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(service, "forwardService", mock(ForwardService.class));
        ReflectionTestUtils.setField(service, "userTunnelService", mock(UserTunnelService.class));

        when(nodeService.getById(any())).thenAnswer(invocation -> nodes.get((Long) invocation.getArgument(0)));
        when(nodeService.list(any())).thenAnswer(invocation -> new ArrayList<>(nodes.values()));
        when(chainTunnelService.list(any())).thenAnswer(invocation -> new ArrayList<>(occupiedChainTunnels));
        when(chainTunnelService.saveBatch(any())).thenReturn(true);
        when(chainTunnelService.remove(any())).thenReturn(true);
        when(forwardPortService.list(any())).thenAnswer(invocation -> new ArrayList<>(occupiedForwardPorts));
        when(tunnelMapper.selectCount(any())).thenReturn(0);
        when(tunnelMapper.insert(any(Tunnel.class))).thenAnswer(invocation -> {
            ((Tunnel) invocation.getArgument(0)).setId(TUNNEL_ID);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void explicitPortOnChainNodeIsUsed() throws Exception {
        useNodes(1L, 2L, 3L);
        registerNodes(1L, 2L, 3L);
        ChainTunnel chainNode = chainTunnel(3L, 2, 30202);

        R result = service.createTunnel(dto(List.of(chainTunnel(1L, 1, null)), List.of(List.of(chainNode)), List.of(chainTunnel(2L, 3, null))));

        assertEquals(0, result.getCode(), result.getMsg());
        List<ChainTunnel> written = writtenChainTunnels();
        assertEquals(30202, portOf(written, 3L).intValue(), "显式 30202 必须落到库里");
        assertEquals(30201, portOf(written, 2L).intValue(), "没传端口的出口照旧自动分配");
    }

    @Test
    void explicitPortOnOutNodeIsUsed() throws Exception {
        useNodes(1L, 2L, 3L);
        registerNodes(1L, 2L, 3L);

        R result = service.createTunnel(dto(
                List.of(chainTunnel(1L, 1, null)),
                List.of(List.of(chainTunnel(3L, 2, null))),
                List.of(chainTunnel(2L, 3, 30209))));

        assertEquals(0, result.getCode(), result.getMsg());
        List<ChainTunnel> written = writtenChainTunnels();
        assertEquals(30209, portOf(written, 2L).intValue(), "出口节点的显式端口同样要采用");
        assertEquals(30201, portOf(written, 3L).intValue(), "没传端口的链节点照旧自动分配");
    }

    @Test
    void conflictingExplicitPortIsRejectedWithoutWriting() throws Exception {
        useNodes(1L, 2L, 3L);
        occupiedChainTunnels.add(occupiedChainTunnel(3L, 30202));

        R result = service.createTunnel(dto(
                List.of(chainTunnel(1L, 1, null)),
                List.of(List.of(chainTunnel(3L, 2, 30202))),
                List.of(chainTunnel(2L, 3, null))));

        assertEquals(-1, result.getCode(), "端口被占用必须报错，不能静默改口");
        assertTrue(result.getMsg().contains("30202"), result.getMsg());
        assertTrue(result.getMsg().contains("占用"), result.getMsg());
        verify(tunnelMapper, never()).insert(any(Tunnel.class));
        verify(chainTunnelService, never()).saveBatch(any());
    }

    @Test
    void explicitPortTakenByForwardIsRejected() throws Exception {
        useNodes(1L, 2L, 3L);
        occupiedForwardPorts.add(forwardPort(3L, 30202));

        R result = service.createTunnel(dto(
                List.of(chainTunnel(1L, 1, null)),
                List.of(List.of(chainTunnel(3L, 2, 30202))),
                List.of(chainTunnel(2L, 3, null))));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("30202"), result.getMsg());
        assertTrue(result.getMsg().contains("占用"), result.getMsg());
        verify(tunnelMapper, never()).insert(any(Tunnel.class));
    }

    @Test
    void explicitPortOutsideNodeRangeIsRejected() throws Exception {
        useNodes(1L, 2L, 3L);

        R result = service.createTunnel(dto(
                List.of(chainTunnel(1L, 1, null)),
                List.of(List.of(chainTunnel(3L, 2, 39999))),
                List.of(chainTunnel(2L, 3, null))));

        assertEquals(-1, result.getCode(), "节点没开这个端口必须报错");
        assertTrue(result.getMsg().contains("39999"), result.getMsg());
        assertTrue(result.getMsg().contains(NODE_PORT_RANGE), result.getMsg());
        verify(tunnelMapper, never()).insert(any(Tunnel.class));
        verify(chainTunnelService, never()).saveBatch(any());
    }

    @Test
    void absentPortsKeepAutoAssignment() throws Exception {
        useNodes(1L, 2L, 3L);
        registerNodes(1L, 2L, 3L);

        R result = service.createTunnel(dto(
                List.of(chainTunnel(1L, 1, null)),
                List.of(List.of(chainTunnel(3L, 2, null))),
                List.of(chainTunnel(2L, 3, null))));

        assertEquals(0, result.getCode(), result.getMsg());
        List<ChainTunnel> written = writtenChainTunnels();
        assertEquals(30201, portOf(written, 3L).intValue(), "自动分配行为不能变");
        assertEquals(30201, portOf(written, 2L).intValue(), "自动分配行为不能变");
    }

    @Test
    void nullEntranceNodesIsRejectedInsteadOfNpe() {
        TunnelDto tunnelDto = dto(
                List.of(chainTunnel(1L, 1, null)),
                List.of(List.of(chainTunnel(3L, 2, null))),
                List.of(chainTunnel(2L, 3, null)));
        tunnelDto.setInNodeId(null);

        R result = assertDoesNotThrow(() -> service.createTunnel(tunnelDto));

        assertEquals(-1, result.getCode());
        assertEquals("入口节点不能为空", result.getMsg());
        verify(tunnelMapper, never()).insert(any(Tunnel.class));
    }

    // ========== 夹具 ==========

    @SuppressWarnings("unchecked")
    private List<ChainTunnel> writtenChainTunnels() {
        ArgumentCaptor<Collection<ChainTunnel>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(chainTunnelService).saveBatch(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    private static Integer portOf(List<ChainTunnel> rows, long nodeId) {
        return rows.stream()
                .filter(row -> row.getNodeId() == nodeId)
                .map(ChainTunnel::getPort)
                .findFirst()
                .orElseThrow(() -> new AssertionError("节点" + nodeId + " 没有落库任何端口"));
    }

    private void useNodes(Long... nodeIds) {
        for (Long nodeId : nodeIds) {
            nodes.put(nodeId, node(nodeId));
        }
    }

    private void registerNodes(Long... nodeIds) throws Exception {
        for (Long nodeId : nodeIds) {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.isOpen()).thenReturn(true);
            when(session.getId()).thenReturn("fake-node-" + nodeId);
            when(session.getAttributes()).thenReturn(new HashMap<>());
            doAnswer(invocation -> {
                JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
                CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
                if (future != null) {
                    GostDto gostDto = new GostDto();
                    gostDto.setMsg("OK");
                    future.complete(gostDto);
                }
                return null;
            }).when(session).sendMessage(any());
            nodeSessions.put(nodeId, session);
        }
    }

    private static Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + nodeId);
        node.setPort(NODE_PORT_RANGE);
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setStatus(1);
        return node;
    }

    private static ChainTunnel chainTunnel(long nodeId, int chainType, Integer port) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setNodeId(nodeId);
        chainTunnel.setChainType(chainType);
        chainTunnel.setPort(port);
        chainTunnel.setProtocol("tls");
        chainTunnel.setStrategy("round");
        return chainTunnel;
    }

    private static ChainTunnel occupiedChainTunnel(long nodeId, int port) {
        ChainTunnel chainTunnel = chainTunnel(nodeId, 2, port);
        chainTunnel.setTunnelId(99L);
        return chainTunnel;
    }

    private static ForwardPort forwardPort(long nodeId, int port) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setNodeId(nodeId);
        forwardPort.setPort(port);
        forwardPort.setForwardId(88L);
        return forwardPort;
    }

    private static TunnelDto dto(List<ChainTunnel> inNodes, List<List<ChainTunnel>> chainNodes, List<ChainTunnel> outNodes) {
        TunnelDto tunnelDto = new TunnelDto();
        tunnelDto.setName("tunnel-" + TUNNEL_ID);
        tunnelDto.setType(2);
        tunnelDto.setFlow(1);
        tunnelDto.setTrafficRatio(BigDecimal.ONE);
        tunnelDto.setInNodeId(new ArrayList<>(inNodes));
        tunnelDto.setChainNodes(new ArrayList<>(chainNodes));
        tunnelDto.setOutNodeId(new ArrayList<>(outNodes));
        return tunnelDto;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
