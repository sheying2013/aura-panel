package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
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
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流量重置后恢复「配额暂停」的转发（round18）——服务层回归。
 *
 * <p>覆盖的原缺陷：配额超限 → FlowController 把转发暂停并落库（status=0），
 * 但流量重置（/user/reset 或每天 0 点定时任务）只清 in_flow/out_flow，
 * 转发永远停在暂停态：用户额度回来了、业务还是断的，面板也不报任何异常。
 *
 * <p>要求：
 * 1. 重置前确认超额 → 逐条落库 status=1 并下发 ResumeService（与手动恢复同口径）；
 * 2. 入口节点离线不阻断落库，只把节点说明返回给调用方（由重连对账兜底）；
 * 3. 账号/用户隧道过期或停用 → 一条都不恢复（到期暂停不能靠流量重置解除）；
 * 4. 重置前没超额（手动暂停）→ 什么都别做；
 * 5. 只重置了账号、但用户隧道仍超额 → 保持暂停，避免「恢复一次又被下一次上报立刻再停」。
 */
class QuotaResetResumeTest {

    private static final long NODE_ID = 301L;
    private static final long FORWARD_ID = 41L;
    private static final long USER_ID = 7L;
    private static final int TUNNEL_ID = 3;
    private static final int USER_TUNNEL_ID = 12;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
    private UserService userService;
    private ChainTunnelService chainTunnelService;
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
        tunnelService = mock(TunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        userService = mock(UserService.class);
        chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", mock(ForwardPortService.class));
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);

        when(forwardMapper.selectList(any())).thenReturn(List.of(forward(0)));
        when(forwardMapper.updateById(any())).thenReturn(1);
        when(userService.getById(any())).thenReturn(user(1, null, 1L, 0L));
        when(userTunnelService.getOne(any())).thenReturn(userTunnel(1, null, 5L, 0L, 0L));
        when(userTunnelService.getById(any())).thenReturn(userTunnel(1, null, 5L, 5L, 0L));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenReturn(List.of(chainTunnel()));
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void userResetResumesQuotaPausedForward() throws Exception {
        List<String> notes = forwardService.resumeQuotaPausedForUser(USER_ID, true, NOW);

        assertTrue(notes.isEmpty(), "节点在线时不该有未执行说明，实际：" + notes);
        Forward saved = capturedUpdate();
        assertEquals(1, saved.getStatus(), "库里必须改成活跃");
        assertTrue(saved.getUpdatedTime() != null && saved.getUpdatedTime() > 0, "落库时应该刷新 updatedTime");
        assertEquals(List.of("41_7_12_tcp", "41_7_12_udp"), resumedServiceNames(NODE_ID));
    }

    @Test
    void offlineIngressStillPersistsResume() throws Exception {
        nodeSessions.clear();

        List<String> notes = forwardService.resumeQuotaPausedForUser(USER_ID, true, NOW);

        assertEquals(1, notes.size(), "离线节点必须回报未执行，实际：" + notes);
        assertTrue(notes.get(0).contains("节点" + NODE_ID), "说明要能定位节点，实际：" + notes);
        assertEquals(1, capturedUpdate().getStatus(), "节点离线也要落库，重连后由对账补齐");
    }

    @Test
    void expiredUserIsNotResumed() {
        when(userService.getById(any())).thenReturn(user(1, NOW - 1, 1L, 0L));

        List<String> notes = forwardService.resumeQuotaPausedForUser(USER_ID, true, NOW);

        assertTrue(notes.isEmpty(), "到期账号不该被流量重置复活，实际：" + notes);
        verify(forwardMapper, never()).updateById(any());
    }

    @Test
    void manualPauseIsRespectedWhenNotOverQuota() throws Exception {
        List<String> notes = forwardService.resumeQuotaPausedForUser(USER_ID, false, NOW);

        assertTrue(notes.isEmpty());
        verify(forwardMapper, never()).updateById(any());
        assertTrue(resumedServiceNamesQuiet(NODE_ID).isEmpty(), "不该下发 ResumeService");
    }

    @Test
    void userTunnelStillOverQuotaKeepsForwardPaused() {
        // 用户隧道流量已超过自己的额度：只重置账号额度不该把它放开
        when(userTunnelService.getOne(any())).thenReturn(userTunnel(1, null, 1L, GB(), 0L));

        List<String> notes = forwardService.resumeQuotaPausedForUser(USER_ID, true, NOW);

        assertTrue(notes.isEmpty(), "实际：" + notes);
        verify(forwardMapper, never()).updateById(any());
    }

    @Test
    void userTunnelResetResumesWhenUserUnderQuota() {
        List<String> notes = forwardService.resumeQuotaPausedForUserTunnel(USER_TUNNEL_ID, true, NOW);

        assertTrue(notes.isEmpty(), "实际：" + notes);
        assertEquals(1, capturedUpdate().getStatus());
    }

    @Test
    void userTunnelResetKeepsPausedWhenUserStillOverQuota() {
        when(userService.getById(any())).thenReturn(user(1, null, 1L, GB() + 1));

        List<String> notes = forwardService.resumeQuotaPausedForUserTunnel(USER_TUNNEL_ID, true, NOW);

        assertTrue(notes.isEmpty(), "实际：" + notes);
        verify(forwardMapper, never()).updateById(any());
    }

    private static final long NOW = 1_800_000_000_000L;

    private static long GB() {
        return 1024L * 1024L * 1024L;
    }

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId((int) USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setName("quota-resume-test");
        forward.setRemoteAddr("backend:6365");
        forward.setStatus(status);
        return forward;
    }

    private User user(int status, Long expTime, long flowGb, long inFlow) {
        User user = new User();
        user.setId(USER_ID);
        user.setStatus(status);
        user.setExpTime(expTime);
        user.setFlow(flowGb);
        user.setInFlow(inFlow);
        user.setOutFlow(0L);
        return user;
    }

    private UserTunnel userTunnel(int status, Long expTime, long flowGb, long inFlow, long outFlow) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(USER_TUNNEL_ID);
        userTunnel.setUserId((int) USER_ID);
        userTunnel.setTunnelId(TUNNEL_ID);
        userTunnel.setStatus(status);
        userTunnel.setExpTime(expTime);
        userTunnel.setFlow(flowGb);
        userTunnel.setInFlow(inFlow);
        userTunnel.setOutFlow(outFlow);
        return userTunnel;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private ChainTunnel chainTunnel() {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setId(1L);
        chainTunnel.setTunnelId((long) TUNNEL_ID);
        chainTunnel.setNodeId(NODE_ID);
        chainTunnel.setChainType(1);
        return chainTunnel;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.5");
        node.setPort("30100-30120");
        return node;
    }

    private Forward capturedUpdate() {
        ArgumentCaptor<Forward> captor = ArgumentCaptor.forClass(Forward.class);
        verify(forwardMapper, atLeast(1)).updateById(captor.capture());
        return captor.getValue();
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
            JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
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

    private List<JSONObject> sentMessages(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private List<String> resumedServiceNames(long nodeId) throws Exception {
        return resumedServiceNames(sentMessages(nodeId));
    }

    private List<String> resumedServiceNamesQuiet(long nodeId) throws Exception {
        if (!nodeSessions.containsKey(nodeId)) {
            return List.of();
        }
        return resumedServiceNames(sentMessages(nodeId));
    }

    private List<String> resumedServiceNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
            if (!"ResumeService".equals(message.getString("type"))) {
                continue;
            }
            // 报文形状：{"type":"ResumeService","data":{"services":["<name>_tcp","<name>_udp"]}}
            JSONObject payload = message.getJSONObject("data");
            if (payload == null) {
                continue;
            }
            JSONArray data = payload.getJSONArray("services");
            if (data == null) {
                continue;
            }
            for (int i = 0; i < data.size(); i++) {
                names.add(data.getString(i));
            }
        }
        return names;
    }
}
