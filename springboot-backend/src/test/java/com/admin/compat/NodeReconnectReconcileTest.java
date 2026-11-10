package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.UserTunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.UserTunnelService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点重连后的期望态对账（基线 761db18 缺陷：afterConnectionEstablished 只更新在线状态）。
 *
 * <p>节点重启可能把已暂停的服务又跑起来，面板没有任何机会察觉 —— 于是面板显示暂停、实际在转发。
 * 修法是建连成功后异步把"以该节点为入口、库里 status=0"的转发逐个补发 PauseService；
 * 这里锁死：暂停的补发、运行的不补发、只查本节点入口、补发失败不影响连接建立。
 */
class NodeReconnectReconcileTest {

    private static final long NODE_ID = 101L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    private static final long FORWARD_ID = 1L;
    private static final int USER_TUNNEL_ID = 7;
    private static final String EXPECTED_SERVICE = FORWARD_ID + "_" + USER_ID + "_" + USER_TUNNEL_ID;

    private WebSocketServer server;
    private NodeService nodeService;
    private ForwardService forwardService;
    private ChainTunnelService chainTunnelService;
    private UserTunnelService userTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private final List<String> payloads = Collections.synchronizedList(new ArrayList<>());
    private final List<Object> forwardWrappers = new ArrayList<>();
    private final List<Object> chainWrappers = new ArrayList<>();
    private List<Forward> forwardsForQuery = new ArrayList<>();
    private WebSocketSession session;
    private volatile String pauseResult;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        payloads.clear();
        forwardWrappers.clear();
        chainWrappers.clear();
        forwardsForQuery = new ArrayList<>();
        pauseResult = "OK";

        nodeService = mock(NodeService.class);
        forwardService = mock(ForwardService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        userTunnelService = mock(UserTunnelService.class);

        server = new WebSocketServer();
        ReflectionTestUtils.setField(server, "nodeService", nodeService);
        inject("forwardService", forwardService);
        inject("chainTunnelService", chainTunnelService);
        inject("userTunnelService", userTunnelService);

        when(nodeService.getById(any())).thenReturn(node());
        when(nodeService.updateById(any(Node.class))).thenReturn(true);
        when(chainTunnelService.list(any())).thenAnswer(invocation -> {
            chainWrappers.add(invocation.getArgument(0));
            return ingressChainTunnels();
        });
        when(forwardService.list(any())).thenAnswer(invocation -> {
            forwardWrappers.add(invocation.getArgument(0));
            return forwardsForQuery;
        });
        when(userTunnelService.getOne(any())).thenReturn(userTunnel());

        session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", String.valueOf(NODE_ID));
        attributes.put("type", "1");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + NODE_ID);
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            payloads.add(payload);
            JSONObject message = JSON.parseObject(payload);
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto gostDto = new GostDto();
                gostDto.setMsg(pauseResult);
                future.complete(gostDto);
            }
            return null;
        }).when(session).sendMessage(any());
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
        payloads.clear();
    }

    @Test
    void pausedForwardIsRepausedOnReconnect() throws Exception {
        forwardsForQuery = List.of(forward(0));

        server.afterConnectionEstablished(session);

        List<JSONObject> pauses = awaitPauseRequests();
        assertEquals(1, pauses.size(), "暂停中的转发必须在建连后被补发一次 PauseService");
        JSONObject pause = pauses.get(0);
        assertEquals("PauseService", pause.getString("type"));
        assertEquals(List.of(EXPECTED_SERVICE + "_tcp", EXPECTED_SERVICE + "_udp"),
                pause.getJSONObject("data").getJSONArray("services").toJavaList(String.class),
                "服务名必须和 ForwardServiceImpl#buildServiceName 一致，节点侧才认得出这条服务");
    }

    @Test
    void runningForwardIsNotPausedOnReconnect() throws Exception {
        forwardsForQuery = new ArrayList<>();

        server.afterConnectionEstablished(session);
        waitForQuiet();

        assertTrue(pauseRequests().isEmpty(), "运行中的转发不许被暂停");
        assertEquals(1, forwardWrappers.size(), "建连后必须查一次期望态");
        QueryWrapper<?> wrapper = (QueryWrapper<?>) forwardWrappers.get(0);
        assertTrue(wrapper.getSqlSegment().contains("status"), wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(0),
                "只挑 status=0（暂停）的转发：" + wrapper.getParamNameValuePairs());
    }

    @Test
    void onlyIngressChainTunnelsOfTheConnectedNodeAreQueried() throws Exception {
        // 用 doAnswer 重新定桩：when(mock.list(any())) 本身会以既有 answer 执行一次，多记一条捕获
        doAnswer(invocation -> {
            chainWrappers.add(invocation.getArgument(0));
            return new ArrayList<>();
        }).when(chainTunnelService).list(any());

        server.afterConnectionEstablished(session);
        waitForQuiet();

        assertEquals(1, chainWrappers.size());
        QueryWrapper<?> wrapper = (QueryWrapper<?>) chainWrappers.get(0);
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("node_id"), sql);
        assertTrue(sql.contains("chain_type"), sql);
        Collection<Object> values = wrapper.getParamNameValuePairs().values();
        assertTrue(values.contains(NODE_ID), "只看当前重连的这个节点：" + values);
        assertTrue(values.contains(1), "只看入口链路（chain_type=1）：" + values);
        verify(forwardService, never()).list(any());
    }

    @Test
    void pauseFailureKeepsConnectionEstablished() throws Exception {
        forwardsForQuery = List.of(forward(0));
        pauseResult = "节点不在线";

        server.afterConnectionEstablished(session);
        waitForQuiet();

        assertSame(session, nodeSessions.get(NODE_ID), "补发暂停失败不许影响连接登记");
        verify(nodeService).updateById(any(Node.class));
        assertFalse(payloads.isEmpty(), "确实尝试下发过");
    }

    // ========== 测试脚手架 ==========

    /**
     * 基线 WebSocketServer 没有对账依赖：注入失败就等于"根本没有对账逻辑"，
     * 让后面的行为断言去红，而不是在脚手架阶段就抛异常。
     */
    private void inject(String field, Object bean) {
        try {
            ReflectionTestUtils.setField(server, field, bean);
        } catch (IllegalArgumentException ignored) {
            // 基线无此字段，交给断言判定
        }
    }

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("10.1.1.1:8080");
        forward.setStatus(status);
        return forward;
    }

    private List<ChainTunnel> ingressChainTunnels() {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId((long) TUNNEL_ID);
        chainTunnel.setChainType(1);
        chainTunnel.setNodeId(NODE_ID);
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel);
        return chainTunnels;
    }

    private UserTunnel userTunnel() {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(USER_TUNNEL_ID);
        userTunnel.setUserId(USER_ID);
        userTunnel.setTunnelId(TUNNEL_ID);
        userTunnel.setStatus(1);
        return userTunnel;
    }

    private Node node() {
        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("node");
        node.setServerIp("10.0.0.1");
        node.setPort("20000-20010");
        return node;
    }

    private List<JSONObject> pauseRequests() {
        List<JSONObject> pauses = new ArrayList<>();
        synchronized (payloads) {
            for (String payload : payloads) {
                JSONObject message = JSON.parseObject(payload);
                if ("PauseService".equals(message.getString("type"))) {
                    pauses.add(message);
                }
            }
        }
        return pauses;
    }

    private List<JSONObject> awaitPauseRequests() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (!pauseRequests().isEmpty()) {
                break;
            }
            Thread.sleep(10);
        }
        Thread.sleep(100);
        return pauseRequests();
    }

    /** 对账是异步的：给后台线程留出足够时间把（不该发的）消息发出来，再断言"没发"。 */
    private void waitForQuiet() throws InterruptedException {
        Thread.sleep(300);
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
