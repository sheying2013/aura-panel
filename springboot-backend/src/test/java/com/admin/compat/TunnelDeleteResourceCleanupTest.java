package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.CascadeDeletePurger;
import com.admin.service.impl.TunnelServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除隧道的级联清理：节点上实际可能存在的资源都要清，不能按 chainType 猜。
 *
 * <p>真机缺陷（圣何塞，tunnel 5）：两行 ChainTunnel 都是 chainType=1，旧实现对入口行只发 DeleteChains，
 * 节点上的 5_tls 服务留在 gost.json 里，docker restart 后被 loader 重新拉起继续占 30201，删除等于没删。
 *
 * <p>这里锁死：每个出现过的节点都收到 DeleteService 与 DeleteChains（service 先于 chain）；
 * 节点回 not found 视为已清理；节点离线这类真失败不吞，随删除结果带出；没有 chainTunnel 行时不抛异常。
 */
class TunnelDeleteResourceCleanupTest {

    private static final long TUNNEL_ID = 5L;
    private static final long FORWARD_ID = 77L;

    private TunnelServiceImpl service;
    private TunnelMapper tunnelMapper;
    private ChainTunnelService chainTunnelService;
    private ForwardService forwardService;
    private UserTunnelService userTunnelService;
    private SpeedLimitService speedLimitService;
    private ForwardPortService forwardPortService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        tunnelMapper = mock(TunnelMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        forwardService = mock(ForwardService.class);
        userTunnelService = mock(UserTunnelService.class);
        speedLimitService = mock(SpeedLimitService.class);
        forwardPortService = mock(ForwardPortService.class);

        service = new TunnelServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", tunnelMapper);
        ReflectionTestUtils.setField(service, "nodeService", mock(NodeService.class));
        ReflectionTestUtils.setField(service, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(service, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(service, "forwardService", forwardService);
        ReflectionTestUtils.setField(service, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(service, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(service, "cascadeDeletePurger", purger());

        when(tunnelMapper.selectById(any())).thenReturn(tunnel());
        when(tunnelMapper.deleteById(any())).thenReturn(1);
        when(forwardService.list(any())).thenReturn(new ArrayList<>());
        when(userTunnelService.remove(any())).thenReturn(true);
        when(chainTunnelService.remove(any())).thenReturn(true);
        when(speedLimitService.list(any())).thenReturn(new ArrayList<>());
        when(speedLimitService.remove(any())).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
    }

    /** 多表删除的事务边界在 CascadeDeletePurger 里，测试里直接共享同一批 mock 服务。 */
    private CascadeDeletePurger purger() {
        CascadeDeletePurger purger = new CascadeDeletePurger();
        ReflectionTestUtils.setField(purger, "forwardService", forwardService);
        ReflectionTestUtils.setField(purger, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(purger, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(purger, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(purger, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(purger, "statisticsFlowService", mock(com.admin.service.StatisticsFlowService.class));
        ReflectionTestUtils.setField(purger, "tunnelService", service);
        ReflectionTestUtils.setField(purger, "userService", mock(UserService.class));
        return purger;
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void allRowsAreEntranceTypeStillCleanServiceOnEveryNode() throws Exception {
        // 真机形态：两行都是 chainType=1，旧实现只 DeleteChains，5_tls 服务残留
        chainRows(chainTunnel(1L, 1, 30101), chainTunnel(2L, 1, 30102));
        registerNode(1L);
        registerNode(2L);

        R result = service.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        assertNull(result.getData(), "全部节点都确认清理时不该报残留");
        List<String> expected = List.of(
                "DeleteService:" + TUNNEL_ID + "_tls",
                "DeleteChains:chains_" + TUNNEL_ID
        );
        assertEquals(expected, ops(1L), "节点1 必须同时清 service 和 chain，且 service 先删");
        assertEquals(expected, ops(2L), "节点2 必须同时清 service 和 chain，且 service 先删");
        verify(tunnelMapper).deleteById(TUNNEL_ID);
        verify(chainTunnelService).remove(any());
    }

    @Test
    void standardEntranceChainOutTunnelCleansEveryNode() throws Exception {
        chainRows(chainTunnel(1L, 1, 30101), chainTunnel(2L, 2, 30102), chainTunnel(3L, 3, 30103));
        registerNode(1L);
        registerNode(2L);
        registerNode(3L);

        R result = service.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        List<String> expected = List.of(
                "DeleteService:" + TUNNEL_ID + "_tls",
                "DeleteChains:chains_" + TUNNEL_ID
        );
        for (long nodeId : new long[]{1L, 2L, 3L}) {
            assertEquals(expected, ops(nodeId), "节点" + nodeId + " 的资源没清干净");
        }
    }

    @Test
    void duplicateRowsOnSameNodeAreCleanedOnce() throws Exception {
        // 同一节点出现多行（脏数据）只下发一轮，多删一次不存在的东西没有意义
        chainRows(chainTunnel(2L, 1, 30101), chainTunnel(2L, 1, 30101));
        registerNode(2L);

        R result = service.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(2, ops(2L).size(), "同一节点重复行不该重复下发：" + ops(2L));
    }

    @Test
    void notFoundReplyCountsAsCleaned() throws Exception {
        // 节点上本来就没有这个资源：GostUtil 已把 not found 归一成 OK，不该当成残留
        chainRows(chainTunnel(2L, 2, 30102));
        Map<String, String> replies = new HashMap<>();
        replies.put("DeleteService", "1 error(s) occurred: " + TUNNEL_ID + "_tls not found");
        replies.put("DeleteChains", "1 error(s) occurred: chains_" + TUNNEL_ID + " not found");
        registerNode(2L, replies);

        R result = service.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        assertNull(result.getData(), "not found 属于已清理，不该报残留");
        assertEquals(List.of("DeleteService:" + TUNNEL_ID + "_tls", "DeleteChains:chains_" + TUNNEL_ID), ops(2L));
    }

    @Test
    void offlineNodeIsReportedInsteadOfSwallowed() throws Exception {
        // 节点离线＝资源是否还在未知：删除照样落库，但必须把残留如实带出来
        chainRows(chainTunnel(1L, 1, 30101), chainTunnel(2L, 1, 30102));
        registerNode(1L);

        R result = service.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        String reported = String.valueOf(result.getData());
        assertTrue(reported.contains("节点2"), reported);
        assertTrue(reported.contains("节点不在线"), reported);
        verify(tunnelMapper).deleteById(TUNNEL_ID);
        verify(chainTunnelService).remove(any());
    }

    @Test
    void noChainRowsStillDeletesRecords() {
        chainRows();

        R result = assertDoesNotThrow(() -> service.deleteTunnel(TUNNEL_ID));

        assertEquals(0, result.getCode(), result.getMsg());
        assertNull(result.getData());
        verify(tunnelMapper).deleteById(TUNNEL_ID);
        verify(chainTunnelService).remove(any());
        verify(userTunnelService).remove(any());
        assertTrue(nodeSessions.isEmpty(), "没有节点行就不该往节点下发任何东西");
    }

    @Test
    void forwardIsCleanedBeforeNodeResources() throws Exception {
        chainRows(chainTunnel(2L, 2, 30102));
        registerNode(2L);
        List<Forward> forwards = new ArrayList<>();
        forwards.add(forward());
        when(forwardService.list(any())).thenReturn(forwards);

        service.deleteTunnel(TUNNEL_ID);

        // 行删除顺序：先把转发/子表清掉，隧道行最后删（父行留到最后才敢丢）
        InOrder inOrder = inOrder(forwardService, chainTunnelService, tunnelMapper);
        inOrder.verify(forwardService).deleteForward(any());
        inOrder.verify(chainTunnelService).remove(any());
        inOrder.verify(tunnelMapper).deleteById(TUNNEL_ID);
    }

    // ========== 夹具 ==========

    private void chainRows(ChainTunnel... rows) {
        List<ChainTunnel> chainTunnels = new ArrayList<>(List.of(rows));
        when(chainTunnelService.list(any())).thenReturn(chainTunnels);
    }

    private void registerNode(long nodeId) throws Exception {
        registerNode(nodeId, new HashMap<>());
    }

    private void registerNode(long nodeId, Map<String, String> repliesByType) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto gostDto = new GostDto();
                gostDto.setMsg(repliesByType.getOrDefault(message.getString("type"), "OK"));
                future.complete(gostDto);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private List<String> ops(long nodeId) throws Exception {
        WebSocketSession session = nodeSessions.get(nodeId);
        if (session == null) {
            return List.of();
        }
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(0)).sendMessage(captor.capture());
        List<String> ops = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            JSONObject json = JSON.parseObject(message.getPayload());
            String type = json.getString("type");
            if ("DeleteService".equals(type)) {
                // DeleteService 的 data 是 {"services":["<id>_tls"]}，不是裸数组
                ops.add(type + ":" + json.getJSONObject("data").getJSONArray("services").getString(0));
            } else if ("DeleteChains".equals(type)) {
                ops.add(type + ":" + json.getJSONObject("data").getString("chain"));
            } else {
                ops.add(type + ":unexpected");
            }
        }
        return ops;
    }

    private static ChainTunnel chainTunnel(long nodeId, int chainType, int port) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId(TUNNEL_ID);
        chainTunnel.setNodeId(nodeId);
        chainTunnel.setChainType(chainType);
        chainTunnel.setPort(port);
        chainTunnel.setProtocol("tls");
        chainTunnel.setStrategy("round");
        return chainTunnel;
    }

    private static Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setName("forward-" + FORWARD_ID);
        return forward;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("tunnel-" + TUNNEL_ID);
        tunnel.setType(2);
        tunnel.setStatus(1);
        return tunnel;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
