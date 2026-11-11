package com.admin.compat.round52;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重连补暂停的服务名口径回归（round52）。
 *
 * <p>原盲区：{@code WebSocketServer.pauseForwardsExpectingPaused} 无论属主是谁，都按
 * {@code user_tunnel} 行 id 拼 {@code forwardId_userId_userTunnelId}。但管理员创建转发时走的是
 * {@code ForwardServiceImpl#checkUserPermissions} 的管理员直通分支，拿到的 userTunnel 恒为 null，
 * 节点上的服务名尾缀固定是 0；管理员在同一隧道下往往也有一行 user_tunnel，于是重连补发永远
 * 打在一个不存在的名字上（not found 被当成"本来就没有"跳过），暂停态在节点重启后静默丢失。
 *
 * <p>现在的约束：
 * 1. 普通用户仍按 user_tunnel 行 id 拼名；
 * 2. 属主是管理员时先按 0 拼名（创建口径），命中即止；
 * 3. 主口径 not found 时用另一套尾缀再试一次（角色在建转发后变过）；
 * 4. 用户行查不到或查询抛异常时两套名字都试，宁可多一条 WS 消息也不漏补。
 */
class AdminPauseReconnectNamingTest {

    private static final long NODE_ID = 952L;
    private static final long FORWARD_ID = 31L;
    private static final int OWNER_ID = 2;
    private static final int TUNNEL_ID = 3;
    private static final int USER_TUNNEL_ID = 77;

    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private final Deque<String> nodeReplies = new ArrayDeque<>();

    private WebSocketServer server;
    private ForwardService forwardService;
    private ChainTunnelService chainTunnelService;
    private UserTunnelService userTunnelService;
    private UserService userService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        nodeReplies.clear();

        forwardService = mock(ForwardService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        userService = mock(UserService.class);

        server = new WebSocketServer();
        ReflectionTestUtils.setField(server, "forwardService", forwardService);
        ReflectionTestUtils.setField(server, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(server, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(server, "userService", userService);

        when(chainTunnelService.list(any())).thenReturn(List.of(ingress()));
        when(forwardService.list(any())).thenReturn(List.of(pausedForward()));
        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void ordinaryUserStillUsesUserTunnelSuffix() throws Exception {
        when(userService.getById(any())).thenReturn(user(1));
        when(userTunnelService.getOne(any())).thenReturn(userTunnel(USER_TUNNEL_ID));

        pause();

        assertEquals(List.of("31_2_77"), pauseBaseNames(), "普通用户必须沿用 user_tunnel 行 id");
    }

    @Test
    void adminOwnerUsesZeroSuffixEvenWithUserTunnelRow() throws Exception {
        when(userService.getById(any())).thenReturn(user(0));
        when(userTunnelService.getOne(any())).thenReturn(userTunnel(USER_TUNNEL_ID));

        pause();

        assertEquals(List.of("31_2_0"), pauseBaseNames(),
                "管理员创建时 userTunnel 为 null，节点上的名字尾缀是 0；按 77 拼名就是打空");
    }

    @Test
    void adminOwnerFallsBackToUserTunnelNameWhenZeroIsMissing() throws Exception {
        when(userService.getById(any())).thenReturn(user(0));
        when(userTunnelService.getOne(any())).thenReturn(userTunnel(USER_TUNNEL_ID));
        nodeReplies.add("service 31_2_0_udp not found");
        nodeReplies.add("OK");

        pause();

        assertEquals(List.of("31_2_0", "31_2_77"), pauseBaseNames(),
                "0 号名字不在节点上时说明这条转发是降权前按普通用户口径建的，必须再试 user_tunnel 名");
    }

    @Test
    void missingUserRowTriesBothSuffixes() throws Exception {
        when(userService.getById(any())).thenReturn(null);
        when(userTunnelService.getOne(any())).thenReturn(userTunnel(USER_TUNNEL_ID));
        nodeReplies.add("not found");
        nodeReplies.add("OK");

        pause();

        assertEquals(List.of("31_2_77", "31_2_0"), pauseBaseNames(),
                "查不到属主就不知道当年按哪套口径建的服务，两套都要试");
    }

    @Test
    void lookupFailureStaysConservative() throws Exception {
        when(userService.getById(any())).thenThrow(new RuntimeException("db down"));
        when(userTunnelService.getOne(any())).thenReturn(userTunnel(USER_TUNNEL_ID));
        nodeReplies.add("not found");
        nodeReplies.add("OK");

        pause();

        assertEquals(List.of("31_2_77", "31_2_0"), pauseBaseNames(),
                "查库失败不能猜单一名字，否则正好漏掉要补的那条");
    }

    @Test
    void ordinaryUserWithoutUserTunnelRowStillUsesZero() throws Exception {
        when(userService.getById(any())).thenReturn(user(1));
        when(userTunnelService.getOne(any())).thenReturn(null);

        pause();

        assertEquals(List.of("31_2_0"), pauseBaseNames(), "没有权限行时创建口径同样是 0，且不该重复下发同一个名字");
    }

    @Test
    void nodeAnswerThatIsNotNotFoundStopsAfterFirstCandidate() throws Exception {
        when(userService.getById(any())).thenReturn(user(0));
        when(userTunnelService.getOne(any())).thenReturn(userTunnel(USER_TUNNEL_ID));
        nodeReplies.add("等待响应超时");

        pause();

        assertEquals(List.of("31_2_0"), pauseBaseNames(),
                "超时/失败代表名字已命中，不能拿另一套名字再发一遍");
    }

    private void pause() {
        ReflectionTestUtils.invokeMethod(server, "pauseForwardsExpectingPaused", NODE_ID);
    }

    /** 每次 PauseService 的基名（去掉 _tcp/_udp），按发送顺序。 */
    private List<String> pauseBaseNames() throws Exception {
        List<String> bases = new ArrayList<>();
        for (JSONObject message : sentMessages()) {
            if (!"PauseService".equals(message.getString("type"))) {
                continue;
            }
            JSONArray services = message.getJSONObject("data").getJSONArray("services");
            for (int i = 0; i < services.size(); i++) {
                String name = services.getString(i);
                String base = name.endsWith("_tcp") || name.endsWith("_udp")
                        ? name.substring(0, name.length() - 4) : name;
                if (!bases.contains(base)) {
                    bases.add(base);
                }
            }
        }
        return bases;
    }

    private List<JSONObject> sentMessages() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(NODE_ID), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
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
                reply.setMsg(nodeReplies.isEmpty() ? "OK" : nodeReplies.poll());
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private ChainTunnel ingress() {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setId(1L);
        chainTunnel.setTunnelId((long) TUNNEL_ID);
        chainTunnel.setChainType(1);
        chainTunnel.setNodeId(NODE_ID);
        chainTunnel.setPort(30001);
        return chainTunnel;
    }

    private Forward pausedForward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(OWNER_ID);
        forward.setUserName("owner");
        forward.setTunnelId(TUNNEL_ID);
        forward.setStatus(0);
        return forward;
    }

    private User user(int roleId) {
        User user = new User();
        user.setId((long) OWNER_ID);
        user.setRoleId(roleId);
        user.setStatus(0);
        return user;
    }

    private UserTunnel userTunnel(int id) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(id);
        userTunnel.setUserId(OWNER_ID);
        userTunnel.setTunnelId(TUNNEL_ID);
        userTunnel.setStatus(1);
        return userTunnel;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
