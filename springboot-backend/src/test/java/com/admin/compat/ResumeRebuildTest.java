package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 恢复时节点上已经没有这条服务（round27）。
 *
 * <p>真机复现（159.54.178.84，forward 3 / node2 / 30200）：
 * 暂停中的转发在节点重装后不会进补齐——resyncNodeServices 只补 status=1 的服务；
 * 用户点恢复，节点回 "service 3_1_0_tcp configuration not found"，
 * 接口 code=0、库里 status=1，但节点上没有任何监听，30200 一直是关的，
 * 而且节点不会再发清单，没有人会来修它。
 *
 * <p>契约：ResumeService 回 not found 时按库里的 forward_port 端口重建；
 * 重建成功算执行成功，重建失败把原因带回前端（库里仍然是活跃态，靠节点重连兜底）。
 */
class ResumeRebuildTest {

    private static final long NODE_ID = 501L;
    private static final long FORWARD_ID = 51L;
    private static final int USER_ID = 2;
    private static final long TUNNEL_ID = 6L;
    private static final int PORT = 31100;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** ResumeService 的回执内容，null 表示 OK。 */
    private String resumeReply;
    /** AddService 的回执内容，null 表示 OK。 */
    private String addReply;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        forwardMapper = mock(ForwardMapper.class);
        when(forwardMapper.updateById(any())).thenReturn(1);

        NodeService nodeService = mock(NodeService.class);
        when(nodeService.getById(any())).thenAnswer(invocation -> node());

        forwardPortService = mock(ForwardPortService.class);
        when(forwardPortService.getOne(any(), anyBoolean())).thenReturn(forwardPort());

        TunnelService tunnelService = mock(TunnelService.class);
        when(tunnelService.getById(any())).thenReturn(tunnel());

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", mock(ChainTunnelService.class));

        registerNode();
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void resumeRebuildsWhenNodeLostService() throws Exception {
        resumeReply = "service " + serviceName() + "_tcp configuration not found";
        Forward forward = forward();

        R result = applyStatus(forward, 1, "ResumeService");

        assertEquals(0, result.getCode());
        assertNull(pendingOf(result), "重建成功不该再报未执行，实际：" + pendingOf(result));
        assertEquals(1, forward.getStatus(), "库里要恢复成活跃");
        verify(forwardPortService).getOne(any(), anyBoolean());
        JSONArray added = sentServicesOfType("AddService");
        assertEquals(2, added.size(), "tcp/udp 都要重建，实际：" + added);
        assertEquals("0.0.0.0:" + PORT, added.getJSONObject(0).getString("addr"), "必须沿用库里的端口");
    }

    @Test
    void rebuildFailureIsReportedBack() throws Exception {
        resumeReply = "service " + serviceName() + "_tcp configuration not found";
        addReply = "listen tcp 0.0.0.0:" + PORT + ": bind: address already in use";
        Forward forward = forward();

        R result = applyStatus(forward, 1, "ResumeService");

        List<String> pending = pendingOf(result);
        assertNotNull(pending, "重建失败必须告诉前端");
        assertEquals(1, pending.size(), "实际：" + pending);
        assertTrue(pending.get(0).contains("bind"), "要带节点原始错误，实际：" + pending);
        assertEquals(1, forward.getStatus(), "库里仍是活跃态，靠节点重连兜底");
        verify(forwardMapper).updateById(forward);
    }

    @Test
    void pauseDoesNotRebuildAnything() throws Exception {
        addReply = "service " + serviceName() + "_tcp configuration not found";
        Forward forward = forward();

        R result = applyStatus(forward, 0, "PauseService");

        assertEquals(0, result.getCode());
        assertEquals(0, forward.getStatus());
        assertTrue(sentTypes().contains("PauseService"), "实际：" + sentTypes());
        assertEquals(0, sentServicesOfType("AddService").size(), "暂停路径不能凭空建服务");
        assertTrue(pendingOf(result).get(0).contains("not found"), "暂停回执要如实带出");
    }

    @Test
    void healthyResumeDoesNotRebuild() throws Exception {
        Forward forward = forward();

        R result = applyStatus(forward, 1, "ResumeService");

        assertEquals(0, result.getCode());
        assertNull(pendingOf(result));
        assertEquals(0, sentServicesOfType("AddService").size(), "服务还在就不该重复下发");
        verify(forwardPortService, never()).getOne(any(), anyBoolean());
    }

    // ---------- 夹具 ----------

    private R applyStatus(Forward forward, int targetStatus, String method) throws Exception {
        Method m = ForwardServiceImpl.class.getDeclaredMethod("applyStatusChange", Forward.class, List.class,
                UserTunnel.class, int.class, String.class);
        m.setAccessible(true);
        return (R) m.invoke(forwardService, forward, List.of(ingress()), null, targetStatus, method);
    }

    @SuppressWarnings("unchecked")
    private List<String> pendingOf(R result) {
        Object data = result.getData();
        return data == null ? null : (List<String>) data;
    }

    private String serviceName() {
        return FORWARD_ID + "_" + USER_ID + "_0";
    }

    private void registerNode() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + NODE_ID);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
            String requestId = message.getString("requestId");
            if (requestId == null) {
                return null;
            }
            CompletableFuture<GostDto> future = pendingRequests.get(requestId);
            if (future == null) {
                return null;
            }
            String type = message.getString("type");
            String reply = "ResumeService".equals(type) ? resumeReply : addReply;
            GostDto dto = new GostDto();
            dto.setMsg(reply == null ? "OK" : reply);
            future.complete(dto);
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(NODE_ID, session);
    }

    private List<String> sentTypes() throws Exception {
        List<String> types = new ArrayList<>();
        for (TextMessage message : sentMessages()) {
            types.add(JSON.parseObject(message.getPayload()).getString("type"));
        }
        return types;
    }

    private JSONArray sentServicesOfType(String type) throws Exception {
        JSONArray services = new JSONArray();
        for (TextMessage message : sentMessages()) {
            JSONObject parsed = JSON.parseObject(message.getPayload());
            if (!type.equals(parsed.getString("type"))) {
                continue;
            }
            Object data = parsed.get("data");
            if (data instanceof JSONArray) {
                services.addAll((JSONArray) data);
            }
        }
        return services;
    }

    private List<TextMessage> sentMessages() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(NODE_ID), atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    private static Object staticField(String name) throws Exception {
        java.lang.reflect.Field field = WebSocketServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(USER_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(0);
        return forward;
    }

    private static ChainTunnel ingress() {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL_ID);
        row.setNodeId(NODE_ID);
        row.setChainType(1);
        row.setInx(0);
        return row;
    }

    private static ForwardPort forwardPort() {
        ForwardPort row = new ForwardPort();
        row.setId(901L);
        row.setForwardId(FORWARD_ID);
        row.setNodeId(NODE_ID);
        row.setPort(PORT);
        return row;
    }

    private static Node node() {
        Node node = new Node();
        node.setId(NODE_ID);
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setType(1);
        return tunnel;
    }
}
