package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.User;
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
import com.admin.service.impl.TunnelServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createForward 的数据库写入失败路径：save / forwardPortService.save 返回 false 或抛异常时，
 * 既不能继续下发节点，也不能返回成功。
 *
 * <p>已下发节点后才发现端口记录没落库的，必须撤回已下发成功的服务；
 * 撤回本身失败或无法确认时，错误信息要显式说出来，不能声称"已清理"。
 * 数据库与节点之间没有事务，这里只保证"尽力补偿 + 如实报告"。
 */
class ForwardPersistFailureTest {

    private static final long FORWARD_ID = 1L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    private static final int IN_PORT = 20000;
    private static final long[] NODE_IDS = {101L, 102L, 103L};
    private static final String SERVICE_NAME = FORWARD_ID + "_" + USER_ID + "_0";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private final Map<Long, String> addServiceResults = new HashMap<>();
    private final Set<Long> cleanupFailureNodes = new HashSet<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        addServiceResults.clear();
        cleanupFailureNodes.clear();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        NodeService nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        // checkUserPermissions 持锁后先重读账号行：管理员替身必须能从库里读到一条 role_id=0 的行，否则按账号不存在拒绝。
        UserService stubbedUserService = mock(UserService.class);
        User stubbedAdminRow = new User();
        stubbedAdminRow.setId((long) USER_ID);
        stubbedAdminRow.setRoleId(0);
        when(stubbedUserService.getById(any())).thenReturn(stubbedAdminRow);
        ReflectionTestUtils.setField(forwardService, "userService", stubbedUserService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        when(forwardMapper.insert(any(Forward.class))).thenAnswer(invocation -> {
            ((Forward) invocation.getArgument(0)).setId(FORWARD_ID);
            return 1;
        });
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainTunnels());
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));

        for (long nodeId : NODE_IDS) {
            registerNode(nodeId);
        }
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void forwardRecordSaveFalseBlocksDispatchAndFails() throws Exception {
        authenticate();
        when(forwardMapper.insert(any(Forward.class))).thenReturn(0);

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode(), "转发记录没落库时必须返回错误，不能返回成功");
        assertTrue(result.getMsg().contains("转发记录保存失败"), result.getMsg());
        assertTrue(result.getMsg().contains("未下发任何节点"), result.getMsg());
        for (long nodeId : NODE_IDS) {
            assertEquals(List.of(), sentPayloads(nodeId), "node" + nodeId + " 不应收到任何下发消息");
        }
        verify(forwardPortService, never()).save(any(ForwardPort.class));
    }

    @Test
    void forwardRecordSaveExceptionBlocksDispatchAndFails() throws Exception {
        authenticate();
        when(forwardMapper.insert(any(Forward.class))).thenThrow(new RuntimeException("disk full"));

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode(), "写库抛异常时必须返回错误，不能返回成功");
        assertTrue(result.getMsg().contains("转发记录保存异常"), result.getMsg());
        assertTrue(result.getMsg().contains("disk full"), "异常原因要透出，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("未下发任何节点"), result.getMsg());
        for (long nodeId : NODE_IDS) {
            assertEquals(List.of(), sentPayloads(nodeId), "node" + nodeId + " 不应收到任何下发消息");
        }
        verify(forwardPortService, never()).save(any(ForwardPort.class));
    }

    @Test
    void portRecordSaveFalseRollsBackDispatchedNodes() throws Exception {
        authenticate();
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true, false);

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点" + NODE_IDS[1] + " 端口记录保存失败"), result.getMsg());
        assertTrue(result.getMsg().contains("已撤回"), "撤回动作要在错误信息里说明，实际：" + result.getMsg());
        assertEquals(List.of("AddService", "DeleteService"), sentMessageTypes(NODE_IDS[0]),
                "已下发成功的入口必须被撤回");
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_IDS[0]));
        assertEquals(List.of(), sentPayloads(NODE_IDS[1]), "端口记录没落库的节点不得下发");
        assertEquals(List.of(), sentPayloads(NODE_IDS[2]), "失败后不应继续下发后续入口");
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
    }

    @Test
    void portRecordSaveExceptionRollsBackDispatchedNodes() throws Exception {
        authenticate();
        when(forwardPortService.save(any(ForwardPort.class)))
                .thenReturn(true)
                .thenThrow(new RuntimeException("db locked"));

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点" + NODE_IDS[1] + " 端口记录保存异常"), result.getMsg());
        assertTrue(result.getMsg().contains("db locked"), result.getMsg());
        assertEquals(List.of("AddService", "DeleteService"), sentMessageTypes(NODE_IDS[0]));
        assertEquals(List.of(), sentPayloads(NODE_IDS[1]));
        assertEquals(List.of(), sentPayloads(NODE_IDS[2]));
        verify(forwardMapper).deleteById(FORWARD_ID);
    }

    @Test
    void nodeCleanupFailureAfterPortSaveFailureIsReported() throws Exception {
        authenticate();
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true, false);
        cleanupFailureNodes.add(NODE_IDS[0]);

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("清理失败"), result.getMsg());
        assertTrue(result.getMsg().contains("node" + NODE_IDS[0]), result.getMsg());
        assertTrue(result.getMsg().contains("delete failed"), result.getMsg());
        assertFalse(result.getMsg().contains("已撤回"),
                "节点服务撤回失败时不能声称已撤回，实际：" + result.getMsg());
    }

    @Test
    void recordCleanupFailureAfterPortSaveFailureIsReported() throws Exception {
        authenticate();
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true, false);
        when(forwardMapper.deleteById(any())).thenReturn(0);

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("未确认已清理"), result.getMsg());
        assertFalse(result.getMsg().contains("已撤回"),
                "记录删除返回 false 时不能声称已撤回，实际：" + result.getMsg());
    }

    @Test
    void portRecordCleanupExceptionAfterPortSaveFailureIsReported() throws Exception {
        authenticate();
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true, false);
        when(forwardPortService.remove(any())).thenThrow(new RuntimeException("ports locked"));

        R result = forwardService.createForward(newForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("端口记录保存失败"), result.getMsg());
        assertTrue(result.getMsg().contains("转发端口记录清理失败"), result.getMsg());
        assertTrue(result.getMsg().contains("ports locked"), result.getMsg());
        assertFalse(result.getMsg().contains("已撤回"),
                "端口记录清理异常时不能声称已撤回，实际：" + result.getMsg());
    }

    @Test
    void createPathsDoNotDeclareTransactional() throws Exception {
        // 数据库与节点之间没有跨库事务：不加 @Transactional，不对外承诺原子性
        assertNull(ForwardServiceImpl.class.getMethod("createForward", ForwardDto.class)
                .getAnnotation(Transactional.class));
        assertNull(TunnelServiceImpl.class.getMethod("createTunnel", TunnelDto.class)
                .getAnnotation(Transactional.class));
    }

    // ========== 测试脚手架 ==========

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ForwardDto newForwardDto() {
        ForwardDto forwardDto = new ForwardDto();
        forwardDto.setName("persist-failure-test");
        forwardDto.setTunnelId(TUNNEL_ID);
        forwardDto.setRemoteAddr("10.10.10.10:8080");
        forwardDto.setStrategy("round");
        forwardDto.setInPort(IN_PORT);
        return forwardDto;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private List<ChainTunnel> chainTunnels() {
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        for (long nodeId : NODE_IDS) {
            ChainTunnel chainTunnel = new ChainTunnel();
            chainTunnel.setTunnelId((long) TUNNEL_ID);
            chainTunnel.setChainType(1);
            chainTunnel.setNodeId(nodeId);
            chainTunnels.add(chainTunnel);
        }
        return chainTunnels;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + (nodeId % 100));
        node.setPort(IN_PORT + "-" + (IN_PORT + 10));
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                future.complete(nodeReply(nodeId, message.getString("type")));
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private GostDto nodeReply(long nodeId, String type) {
        GostDto gostDto = new GostDto();
        if ("AddService".equals(type)) {
            gostDto.setMsg(addServiceResults.getOrDefault(nodeId, "OK"));
        } else if ("DeleteService".equals(type)) {
            gostDto.setMsg(cleanupFailureNodes.contains(nodeId) ? "delete failed" : "OK");
        } else {
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    private List<String> sentMessageTypes(long nodeId) throws Exception {
        List<String> types = new ArrayList<>();
        for (String payload : sentPayloads(nodeId)) {
            types.add(JSON.parseObject(payload).getString("type"));
        }
        return types;
    }

    private List<String> deletedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (String payload : sentPayloads(nodeId)) {
            JSONObject message = JSON.parseObject(payload);
            if (!"DeleteService".equals(message.getString("type"))) {
                continue;
            }
            JSONArray services = message.getJSONObject("data").getJSONArray("services");
            for (int i = 0; i < services.size(); i++) {
                names.add(services.getString(i));
            }
        }
        return names;
    }

    private List<String> sentPayloads(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<String> payloads = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            payloads.add(message.getPayload());
        }
        return payloads;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
