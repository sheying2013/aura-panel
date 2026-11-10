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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创建隧道的结构校验：缺 chainType 不能拖到 saveBatch 才炸成 message 为 null 的异常。
 *
 * <p>真机缺陷（圣何塞隔离环境 2026-11-07）：chainNodes 里的行没带 chainType，saveBatch 直接抛异常，
 * 前端只看到 "隧道节点记录保存异常：null，未下发任何节点"，无法定位是哪个字段缺了。
 *
 * <p>这里锁死：缺 chainType 按位置补全（入口=1、链=2、出口=3）并正常下发；显式传了矛盾值、
 * 缺 nodeId、端口非法、协议非法都在写库前返回带位置/字段名的 R.err；saveBatch 抛无 message
 * 异常时错误文案里必须出现实体类名，不能再是 null。
 */
class TunnelCreateValidationTest {

    private static final long TUNNEL_ID = 7L;
    private static final String NODE_PORT_RANGE = "30201-30210";

    private TunnelServiceImpl service;
    private TunnelMapper tunnelMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private final Map<Long, Node> nodes = new LinkedHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        nodes.clear();

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
        when(chainTunnelService.list(any())).thenAnswer(invocation -> new ArrayList<ChainTunnel>());
        when(chainTunnelService.saveBatch(any())).thenReturn(true);
        when(chainTunnelService.remove(any())).thenReturn(true);
        when(forwardPortService.list(any())).thenAnswer(invocation -> new ArrayList<ForwardPort>());
        when(tunnelMapper.selectCount(any())).thenReturn(0);
        when(tunnelMapper.insert(any(Tunnel.class))).thenAnswer(invocation -> {
            ((Tunnel) invocation.getArgument(0)).setId(TUNNEL_ID);
            return 1;
        });
        when(tunnelMapper.deleteById(any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void missingChainTypeIsFilledByPositionAndPublished() throws Exception {
        useNodes(1L, 2L, 3L);
        registerNodes(1L, 2L, 3L);
        ChainTunnel entrance = row(1L, null);
        ChainTunnel chain = row(3L, null);
        ChainTunnel out = row(2L, null);

        R result = service.createTunnel(dto(List.of(entrance), List.of(List.of(chain)), List.of(out)));

        assertEquals(0, result.getCode(), result.getMsg());
        List<ChainTunnel> written = writtenRows();
        assertEquals(1, chainTypeOf(written, 1L).intValue(), "入口行缺省 chainType 必须补成 1");
        assertEquals(2, chainTypeOf(written, 3L).intValue(), "链行缺省 chainType 必须补成 2");
        assertEquals(3, chainTypeOf(written, 2L).intValue(), "出口行缺省 chainType 必须补成 3");
        assertTrue(written.stream().allMatch(r -> "tls".equals(r.getProtocol())), "缺省 protocol 应补成 tls");
    }

    @Test
    void chainNodeWithContradictingChainTypeIsRejected() throws Exception {
        useNodes(1L, 2L, 3L);
        ChainTunnel chain = row(3L, 1);

        R result = service.createTunnel(dto(
                List.of(row(1L, 1)),
                List.of(List.of(chain)),
                List.of(row(2L, 3))));

        assertEquals(-1, result.getCode(), "链节点传 chainType=1 必须报矛盾，不能静默当入口处理");
        assertTrue(result.getMsg().contains("chainType=1"), result.getMsg());
        assertTrue(result.getMsg().contains("矛盾"), result.getMsg());
        assertTrue(result.getMsg().contains("第 1 组第 1 个转发链节点"), result.getMsg());
        verify(tunnelMapper, never()).insert(any(Tunnel.class));
        verify(chainTunnelService, never()).saveBatch(any());
    }

    @Test
    void chainNodeWithoutNodeIdIsRejectedWithGroupAndIndex() throws Exception {
        useNodes(1L, 2L, 3L);
        ChainTunnel missingNodeId = row(null, 2);

        R result = service.createTunnel(dto(
                List.of(row(1L, 1)),
                List.of(List.of(row(3L, 2)), List.of(missingNodeId)),
                List.of(row(2L, 3))));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("第 2 组第 1 个转发链节点"), result.getMsg());
        assertTrue(result.getMsg().contains("nodeId"), result.getMsg());
        verify(tunnelMapper, never()).insert(any(Tunnel.class));
        verify(chainTunnelService, never()).saveBatch(any());
    }

    @Test
    void invalidPortIsRejectedBeforeAnyWrite() throws Exception {
        useNodes(1L, 2L, 3L);
        ChainTunnel chain = row(3L, 2);
        chain.setPort(70000);

        R result = service.createTunnel(dto(
                List.of(row(1L, 1)),
                List.of(List.of(chain)),
                List.of(row(2L, 3))));

        assertEquals(-1, result.getCode(), "端口 70000 必须被拒，不能留给 gost 报错");
        assertTrue(result.getMsg().contains("70000"), result.getMsg());
        assertTrue(result.getMsg().contains("1-65535"), result.getMsg());
        assertTrue(result.getMsg().contains("第 1 组第 1 个转发链节点"), result.getMsg());
        verify(tunnelMapper, never()).insert(any(Tunnel.class));
        verify(chainTunnelService, never()).saveBatch(any());
    }

    @Test
    void zeroPortIsRejected() throws Exception {
        useNodes(1L, 2L, 3L);
        ChainTunnel out = row(2L, 3);
        out.setPort(0);

        R result = service.createTunnel(dto(
                List.of(row(1L, 1)),
                List.of(List.of(row(3L, 2))),
                List.of(out)));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("第 1 个出口节点"), result.getMsg());
        assertTrue(result.getMsg().contains("1-65535"), result.getMsg());
    }

    @Test
    void missingProtocolDefaultsToTls() throws Exception {
        useNodes(1L, 2L, 3L);
        registerNodes(1L, 2L, 3L);
        ChainTunnel chain = row(3L, 2);
        chain.setProtocol(null);
        ChainTunnel out = row(2L, 3);
        out.setProtocol("  ");

        R result = service.createTunnel(dto(List.of(row(1L, 1)), List.of(List.of(chain)), List.of(out)));

        assertEquals(0, result.getCode(), result.getMsg());
        List<ChainTunnel> written = writtenRows();
        assertEquals("tls", protocolOf(written, 3L), "链行 protocol 缺省必须补 tls");
        assertEquals("tls", protocolOf(written, 2L), "出口行 protocol 空白也必须补 tls");
    }

    @Test
    void unsupportedProtocolIsRejected() throws Exception {
        useNodes(1L, 2L, 3L);
        ChainTunnel chain = row(3L, 2);
        chain.setProtocol("http");

        R result = service.createTunnel(dto(
                List.of(row(1L, 1)),
                List.of(List.of(chain)),
                List.of(row(2L, 3))));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("protocol=http"), result.getMsg());
        assertTrue(result.getMsg().contains("不支持"), result.getMsg());
        assertTrue(result.getMsg().contains("tls/wss/tcp"), result.getMsg());
        verify(chainTunnelService, never()).saveBatch(any());
    }

    @Test
    void saveBatchWithNullMessageReturnsReadableReason() throws Exception {
        useNodes(1L, 2L, 3L);
        registerNodes(1L, 2L, 3L);
        when(chainTunnelService.saveBatch(any())).thenThrow(new RuntimeException());

        R result = service.createTunnel(dto(
                List.of(row(1L, 1)),
                List.of(List.of(row(3L, 2))),
                List.of(row(2L, 3))));

        assertEquals(-1, result.getCode());
        String msg = result.getMsg();
        assertTrue(msg.contains("隧道节点记录保存异常"), msg);
        assertTrue(msg.contains("ChainTunnel"), "无 message 的异常必须点出实体类名：" + msg);
        assertFalse(msg.contains("null"), "不能把 null 甩给前端：" + msg);
        assertTrue(msg.contains("未下发任何节点"), msg);
    }

    // ========== 夹具 ==========

    @SuppressWarnings("unchecked")
    private List<ChainTunnel> writtenRows() {
        ArgumentCaptor<Collection<ChainTunnel>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(chainTunnelService).saveBatch(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    private static Integer chainTypeOf(List<ChainTunnel> rows, long nodeId) {
        return rows.stream()
                .filter(row -> row.getNodeId() == nodeId)
                .map(ChainTunnel::getChainType)
                .findFirst()
                .orElseThrow(() -> new AssertionError("节点" + nodeId + " 没有落库任何 chainType"));
    }

    private static String protocolOf(List<ChainTunnel> rows, long nodeId) {
        return rows.stream()
                .filter(row -> row.getNodeId() == nodeId)
                .map(ChainTunnel::getProtocol)
                .findFirst()
                .orElse(null);
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

    /** 只造调用方真正会传的字段：chainType 允许为 null，模拟真机上缺字段的那一行。 */
    private static ChainTunnel row(Long nodeId, Integer chainType) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setNodeId(nodeId);
        chainTunnel.setChainType(chainType);
        chainTunnel.setProtocol("tls");
        chainTunnel.setStrategy("round");
        return chainTunnel;
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
