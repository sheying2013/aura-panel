package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.User;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
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
 * deleteForward / forceDeleteForward 清理结果回归测试。
 *
 * <p>覆盖的原缺陷：删除转发时把 GostUtil.DeleteService 的返回值直接丢掉，
 * 节点上没删掉也照样回「操作成功」——库里记录没了、端口却一直占着，
 * 面板随后反复分配失败，而用户看不到任何提示。
 *
 * <p>现在的要求：
 * 1. 任一入口节点清理失败，返回成功但 data 里带失败明细（前端弹警告）；
 * 2. 失败的节点必须立刻收到一次 call/info_request，让孤儿清扫接盘；
 * 3. 全部成功时 data 为 null，不发多余的下行消息；
 * 4. 节点记录不存在也要给出提示，但库记录照样删掉（不能卡死无法重试）。
 */
class ForwardDeleteCleanupTest {

    private static final long FORWARD_ID = 1L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    private static final long[] NODE_IDS = {101L, 102L, 103L};
    private static final String SERVICE_NAME = FORWARD_ID + "_" + USER_ID + "_0";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private ChainTunnelService chainTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 清理（DeleteService）失败的节点 */
    private final Set<Long> cleanupFailureNodes = new HashSet<>();
    /** 节点记录查不到的节点 */
    private final Set<Long> missingNodes = new HashSet<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        cleanupFailureNodes.clear();
        missingNodes.clear();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);

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

        when(forwardMapper.selectById(any())).thenReturn(forward());
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainTunnels());
        when(forwardPortService.remove(any())).thenReturn(true);
        when(nodeService.getById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return missingNodes.contains(nodeId) ? null : node(nodeId);
        });

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
    void failedNodeCleanupIsReportedAndTriggersServiceList() throws Exception {
        authenticate();
        cleanupFailureNodes.add(NODE_IDS[0]);

        R result = forwardService.deleteForward(FORWARD_ID);

        assertEquals(0, result.getCode(), "库记录已删除，接口本身仍应返回成功");
        List<String> warnings = warnings(result);
        assertEquals(1, warnings.size(), "只有一个节点失败，明细应只列一条，实际：" + warnings);
        assertTrue(warnings.get(0).contains("节点" + NODE_IDS[0]), "必须指明失败节点，实际：" + warnings);
        assertTrue(warnings.get(0).contains("delete failed"), "必须保留节点返回的失败原因，实际：" + warnings);

        assertEquals(List.of("DeleteService", "call"), sentMessageTypes(NODE_IDS[0]),
                "清理失败的节点必须立刻补发一次资源清单请求");
        assertEquals(List.of("DeleteService"), sentMessageTypes(NODE_IDS[1]), "成功的节点不应被额外索取清单");
        assertEquals(List.of("DeleteService"), sentMessageTypes(NODE_IDS[2]));
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_IDS[0]));

        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
    }

    @Test
    void allNodesCleanedReturnsEmptyDataWithoutExtraMessages() throws Exception {
        authenticate();

        R result = forwardService.deleteForward(FORWARD_ID);

        assertEquals(0, result.getCode());
        assertNull(result.getData(), "全部清理成功时不应返回任何警告明细");
        for (long nodeId : NODE_IDS) {
            assertEquals(List.of("DeleteService"), sentMessageTypes(nodeId),
                    "node" + nodeId + " 全部成功时不应补发 call/info_request");
            assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(nodeId));
        }
        verify(forwardMapper).deleteById(FORWARD_ID);
    }

    @Test
    void forceDeleteAsksEveryIngressNodeForServiceList() throws Exception {
        authenticate();

        R result = forwardService.forceDeleteForward(FORWARD_ID);

        assertEquals(0, result.getCode());
        assertNull(result.getData());
        for (long nodeId : NODE_IDS) {
            assertEquals(List.of("call"), sentMessageTypes(nodeId),
                    "强制删除不保证节点侧已清理，必须每个入口都索取一次清单");
            assertEquals("info_request", callTypes(nodeId).get(0));
        }
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
    }

    @Test
    void missingNodeRecordIsReportedButDeletionStillHappens() throws Exception {
        authenticate();
        missingNodes.add(NODE_IDS[1]);

        R result = forwardService.deleteForward(FORWARD_ID);

        assertEquals(0, result.getCode());
        List<String> warnings = warnings(result);
        assertEquals(1, warnings.size(), "实际：" + warnings);
        assertTrue(warnings.get(0).contains("节点" + NODE_IDS[1]), warnings.toString());
        assertTrue(warnings.get(0).contains("不存在"), warnings.toString());
        assertEquals(List.of(), sentMessageTypes(NODE_IDS[1]), "节点记录都没有，不可能下发清理");
        assertEquals(List.of("DeleteService"), sentMessageTypes(NODE_IDS[0]));
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
    }

    @Test
    void strictModeKeepsRowWhenAnyIngressNodeFails() throws Exception {
        authenticate();
        cleanupFailureNodes.add(NODE_IDS[0]);

        R result = forwardService.deleteForward(FORWARD_ID, true);

        assertEquals(-1, result.getCode(), "严格模式下节点没删掉就必须报错");
        assertTrue(result.getMsg().contains("节点侧服务未删除"), result.getMsg());
        assertTrue(result.getMsg().contains("节点" + NODE_IDS[0]), result.getMsg());
        assertTrue(result.getMsg().contains("库里记录已保留"), result.getMsg());
        verify(forwardMapper, never()).deleteById(any());
        verify(forwardPortService, never()).remove(any());
    }

    // ========== 测试脚手架 ==========

    private List<String> warnings(R result) {
        List<String> warnings = new ArrayList<>();
        Object data = result.getData();
        if (data instanceof List) {
            for (Object item : (List<?>) data) {
                warnings.add(String.valueOf(item));
            }
        }
        return warnings;
    }

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("delete-test");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("10.10.10.10:8080");
        forward.setStrategy("round");
        forward.setStatus(1);
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
        node.setPort("20000-20010");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    /**
     * 注册假节点会话：收到带 requestId 的下发时立刻完成对应 future；
     * 不带 requestId 的 call/info_request 只记录，不回应（真实节点是异步上报）。
     */
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
                future.complete(nodeReply(nodeId, message.getString("type")));
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private GostDto nodeReply(long nodeId, String type) {
        GostDto gostDto = new GostDto();
        if ("DeleteService".equals(type) && cleanupFailureNodes.contains(nodeId)) {
            gostDto.setMsg("delete failed");
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

    private List<String> callTypes(long nodeId) throws Exception {
        List<String> callTypes = new ArrayList<>();
        for (String payload : sentPayloads(nodeId)) {
            JSONObject message = JSON.parseObject(payload);
            if ("call".equals(message.getString("type"))) {
                callTypes.add(message.getString("callType"));
            }
        }
        return callTypes;
    }

    private List<String> deletedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (String payload : sentPayloads(nodeId)) {
            JSONObject message = JSON.parseObject(payload);
            if (!"DeleteService".equals(message.getString("type"))) {
                continue;
            }
            com.alibaba.fastjson.JSONArray services = message.getJSONObject("data").getJSONArray("services");
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
