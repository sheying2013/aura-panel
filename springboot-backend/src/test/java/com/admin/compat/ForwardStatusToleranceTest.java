package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.UserTunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.ForwardServiceImpl;
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 暂停/恢复在节点不可达时的一致性（round13）。
 *
 * <p>原缺陷有两条，都在真机复现：
 * 1. 只要一个入口节点掉线，pause/resume 直接整单失败，库里状态不变——用户在最需要停流量的时候
 *    反而关不掉某条转发；
 * 2. 多入口隧道里前半批节点已经真的暂停了、后半批失败，接口报错后库里仍是活跃态，
 *    面板显示正常但流量已经少了一条腿，且没有任何对账去修。
 *
 * <p>现在的约束：库里状态是唯一真相，先落库再逐个节点下发；未执行的节点以列表返回，
 * 前端提示"已记为待执行"，节点重连后由 pauseForwardsExpectingPaused / resyncNodeServices 兜底。
 */
class ForwardStatusToleranceTest {

    private static final long NODE_A = 401L;
    private static final long NODE_B = 402L;
    private static final long FORWARD_ID = 41L;
    private static final int USER_ID = 2;
    private static final int TUNNEL_ID = 5;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        forwardMapper = mock(ForwardMapper.class);
        nodeService = mock(NodeService.class);
        when(forwardMapper.updateById(any())).thenReturn(1);
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", mock(ForwardPortService.class));
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", mock(TunnelService.class));
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", mock(ChainTunnelService.class));
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void offlineIngressNodeStillPersistsPaused() throws Exception {
        Forward forward = forward();

        R result = applyStatus(forward, List.of(ingress(NODE_A)), 0, "PauseService");

        assertEquals(0, result.getCode(), "节点离线不该让暂停整单失败");
        assertEquals(0, forward.getStatus(), "库里必须先落成暂停，节点重连后由对账补暂停");
        verify(forwardMapper).updateById(forward);
        List<String> pending = pendingOf(result);
        assertEquals(1, pending.size(), "要告诉前端哪台节点没执行，实际：" + pending);
        assertTrue(pending.get(0).contains("401"), "要能定位节点，实际：" + pending);
        assertTrue(pending.get(0).contains("不在线"), "要说明原因，实际：" + pending);
    }

    @Test
    void halfExecutedMultiIngressStillConvergesToPaused() throws Exception {
        registerNode(NODE_A);
        Forward forward = forward();

        R result = applyStatus(forward, List.of(ingress(NODE_A), ingress(NODE_B)), 0, "PauseService");

        assertEquals(0, result.getCode());
        assertEquals(0, forward.getStatus(), "前半批已暂停、后半批失败，库里仍要落成暂停，否则半开半闭无人察觉");
        List<String> pending = pendingOf(result);
        assertEquals(1, pending.size(), "只有离线的那台算未执行，实际：" + pending);
        assertTrue(pending.get(0).contains("402"), "实际：" + pending);
        assertEquals(List.of("PauseService"), sentTypes(NODE_A), "在线节点要真的收到暂停指令");
    }

    @Test
    void allIngressOnlineReportsNothingPending() throws Exception {
        registerNode(NODE_A);
        registerNode(NODE_B);
        Forward forward = forward();

        R result = applyStatus(forward, List.of(ingress(NODE_A), ingress(NODE_B)), 0, "PauseService");

        assertEquals(0, result.getCode());
        assertNull(result.getData(), "全部成功时不该带待执行列表");
        assertEquals(0, forward.getStatus());
    }

    @Test
    void offlineIngressNodeStillPersistsResumed() throws Exception {
        Forward forward = forward();
        forward.setStatus(0);

        R result = applyStatus(forward, List.of(ingress(NODE_A)), 1, "ResumeService");

        assertEquals(0, result.getCode());
        assertEquals(1, forward.getStatus(), "节点离线时恢复也要落库，重连后由 resyncNodeServices 补服务");
        assertEquals(1, pendingOf(result).size());
    }

    @Test
    void missingNodeIsReportedInsteadOfAborting() throws Exception {
        // 已有 thenAnswer 桩时不能用 when(...).thenReturn(null)：when() 里的调用会先打到旧桩上并把 null 传进去
        doReturn(null).when(nodeService).getById(any());
        Forward forward = forward();

        R result = applyStatus(forward, List.of(ingress(NODE_A)), 0, "PauseService");

        assertEquals(0, result.getCode());
        assertEquals(0, forward.getStatus());
        List<String> pending = pendingOf(result);
        assertEquals(1, pending.size(), "实际：" + pending);
        assertTrue(pending.get(0).contains("节点不存在"), "实际：" + pending);
    }

    private R applyStatus(Forward forward, List<ChainTunnel> chains, int targetStatus, String method)
            throws Exception {
        Method declared = ForwardServiceImpl.class.getDeclaredMethod("applyStatusChange",
                Forward.class, List.class, UserTunnel.class, int.class, String.class);
        declared.setAccessible(true);
        return (R) declared.invoke(forwardService, forward, chains, null, targetStatus, method);
    }

    private List<String> pendingOf(R result) {
        List<String> pending = new ArrayList<>();
        Object data = result.getData();
        if (data instanceof List<?>) {
            for (Object item : (List<?>) data) {
                pending.add(String.valueOf(item));
            }
        }
        return pending;
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("status-tolerance");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private ChainTunnel ingress(long nodeId) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId((long) TUNNEL_ID);
        chainTunnel.setChainType(1);
        chainTunnel.setNodeId(nodeId);
        return chainTunnel;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.5");
        node.setPort("30130-30140");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            String requestId = message.getString("requestId");
            if (requestId == null) {
                return null;
            }
            CompletableFuture<GostDto> future = pendingRequests.get(requestId);
            if (future != null) {
                GostDto reply = new GostDto();
                reply.setMsg("OK");
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private List<String> sentTypes(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<String> types = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            types.add(JSON.parseObject(message.getPayload()).getString("type"));
        }
        return types;
    }
}
