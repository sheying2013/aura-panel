package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「清单里有 ≠ 服务在跑」的回归测试。
 *
 * <p>覆盖的原缺陷：gost 的暂停是软状态——pauseServices 只释放注册名并把
 * Metadata["paused"]=true 写进配置，进程重启后服务条目仍在，节点自报清单照样带上它。
 * 后端 resync 的规则是「节点没有才补」，于是离线恢复（库里 status=1、节点仍暂停）
 * 永远不收敛：面板显示运行，端口实际关闭。
 *
 * <p>修法：清单里成对出现的活跃服务补发一次 ResumeService。运行中的服务在
 * resumeServices 里是 skipped（返回成功），只有 paused 的才重建，因此幂等。
 */
class ResumeReportedServicesTest {

    private static final long NODE_ID = 211L;
    private static final long FORWARD_ID = 31L;
    private static final int USER_ID = 2;
    private static final int TUNNEL_ID = 1;
    private static final int PORT = 30112;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 让 ResumeService 返回失败（模拟节点拒绝恢复） */
    private boolean resumeFails;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        resumeFails = false;

        forwardMapper = mock(ForwardMapper.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", mock(ChainTunnelService.class));

        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(forwardMapper.selectById(any())).thenReturn(forward(1));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(userTunnelService.getOne(any())).thenReturn(null);
        when(forwardPortService.list(any())).thenAnswer(invocation -> List.of(forwardPort(PORT)));
        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("resume-test");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(status);
        return forward;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private ForwardPort forwardPort(int port) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(FORWARD_ID);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(port);
        return forwardPort;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.6");
        node.setPort("30100-30120");
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
                if (resumeFails && "ResumeService".equals(message.getString("type"))) {
                    reply.setMsg("resume service 31_2_0_tcp failed: bind: address already in use");
                } else {
                    reply.setMsg("OK");
                }
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

    private List<JSONObject> messagesOfType(long nodeId, String type) throws Exception {
        List<JSONObject> matched = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
            if (type.equals(message.getString("type"))) {
                matched.add(message);
            }
        }
        return matched;
    }

    @Test
    void reportedPairIsResumedEvenThoughItLooksPresent() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("31_2_0_tcp", "31_2_0_udp"));

        assertTrue(notes.isEmpty(), "恢复成功不该产生告警文本，实际：" + notes);
        List<JSONObject> resumes = messagesOfType(NODE_ID, "ResumeService");
        assertEquals(1, resumes.size(), "成对出现就要补一发恢复，实际：" + sentMessages(NODE_ID));
        com.alibaba.fastjson.JSONArray services = resumes.get(0).getJSONObject("data")
                .getJSONArray("services");
        assertEquals(List.of("31_2_0_tcp", "31_2_0_udp"), services);
        assertTrue(messagesOfType(NODE_ID, "AddService").isEmpty(), "已经在清单里就不该重发 AddService");
    }

    @Test
    void resumeFailureKeepsNodeReason() throws Exception {
        resumeFails = true;

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("31_2_0_tcp", "31_2_0_udp"));

        assertEquals(1, notes.size(), "失败必须上报，实际：" + notes);
        assertTrue(notes.get(0).contains("恢复失败"), "要说清是恢复失败，实际：" + notes);
        assertTrue(notes.get(0).contains("address already in use"), "保留节点返回的原始原因，实际：" + notes);
    }

    @Test
    void pausedForwardIsNotResumed() throws Exception {
        when(forwardMapper.selectById(any())).thenReturn(forward(0));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("31_2_0_tcp", "31_2_0_udp"));

        assertTrue(notes.isEmpty(), "暂停的转发本来就不该被拉起，实际：" + notes);
        assertTrue(messagesOfType(NODE_ID, "ResumeService").isEmpty(), "不能把用户主动停掉的服务复活");
    }

    @Test
    void halfBrokenPairIsRepairedInsteadOfResumed() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("31_2_0_tcp"));

        assertEquals(1, notes.size(), "半残补齐一次，实际：" + notes);
        assertTrue(notes.get(0).contains("已按库补齐缺失协议 31_2_0_udp@node211"), "实际：" + notes);
        assertTrue(messagesOfType(NODE_ID, "ResumeService").isEmpty(), "半残不走恢复：配置条目还在，恢复不会重建缺失协议");
        assertEquals(1, messagesOfType(NODE_ID, "AddService").size(), "要补一次 AddService");
    }

    @Test
    void missingPairIsCreatedNotResumed() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, List.of("99_9_9_tcp", "99_9_9_udp"));

        assertEquals(1, notes.size(), "整对缺失应该走补齐，实际：" + notes);
        assertTrue(notes.get(0).startsWith("已按库补齐缺失服务 31_2_0@node211"), "实际：" + notes);
        assertTrue(messagesOfType(NODE_ID, "ResumeService").isEmpty(), "配置都不在，恢复必然失败，不该发");
        assertEquals(1, messagesOfType(NODE_ID, "AddService").size());
    }
}
