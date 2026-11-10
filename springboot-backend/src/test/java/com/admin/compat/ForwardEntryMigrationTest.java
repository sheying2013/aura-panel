package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
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
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 入口节点热迁移（round24）回归测试。
 *
 * <p>隧道下已有转发时改入口节点，是"库改了、节点没改"最容易发生的场景：入口服务的监听端口在
 * 节点本机，迁移必须先在目标节点把服务起起来、确认旧节点撤掉、最后才改 forward_port。
 * 本测试守住的顺序与补偿：
 * 1. 先 AddService 到新入口，再 DeleteService 旧入口，最后写库；
 * 2. 旧端口在新节点仍可用时沿用，避免无谓换口；
 * 3. 新节点报 bind 失败时换下一个候选端口重试（与创建路径同口径）；
 * 4. 旧入口清理未确认时撤回新入口、且库不动；
 * 5. 暂停的转发只搬端口记录，不在新入口起监听；
 * 6. 后续转发失败时，前面已迁移的转发整体回滚（新入口撤回 + 旧入口按原端口重建 + 库改回）。
 */
class ForwardEntryMigrationTest {

    private static final long TUNNEL_ID = 9L;
    private static final int USER_ID = 2;
    private static final long OLD_NODE = 1L;
    private static final long NEW_NODE = 2L;
    private static final long FORWARD_ID = 31L;
    private static final long FORWARD_ID_2 = 32L;
    private static final long PORT_ROW_ID = 77L;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private List<ForwardPort> portRows;
    private List<Forward> forwards;
    private final Map<Long, String> forcedDeleteFailure = new HashMap<>();
    private final Map<Long, AtomicInteger> bindFailuresLeft = new HashMap<>();
    private final List<String> timeline = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        forcedDeleteFailure.clear();
        bindFailuresLeft.clear();
        timeline.clear();

        portRows = new ArrayList<>();
        forwards = new ArrayList<>();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", mock(ChainTunnelService.class));

        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(forwardMapper.selectList(any())).thenAnswer(invocation -> forwards);
        when(forwardPortService.list(any())).thenAnswer(invocation -> {
            QueryWrapper<ForwardPort> wrapper = invocation.getArgument(0);
            // MyBatis-Plus 的参数值是 getSqlSegment() 时惰性写入的，必须先求值再取 map
            String sql = wrapper.getSqlSegment();
            if (sql.contains("<>")) {
                // getNodePort 的「别的转发的端口占用」查询：本用例不造干扰项
                return List.of();
            }
            Object forwardId = wrapper.getParamNameValuePairs().values().iterator().next();
            List<ForwardPort> mine = new ArrayList<>();
            for (ForwardPort row : portRows) {
                if (row.getForwardId().equals(forwardId)) {
                    mine.add(row);
                }
            }
            return mine;
        });
        when(forwardPortService.updateById(any())).thenReturn(true);

        registerNode(OLD_NODE);
        registerNode(NEW_NODE);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void activeForwardMovesToNewEntryBeforeDatabaseRowIsRewritten() throws Exception {
        forwards.add(forward(FORWARD_ID, 1));
        portRows.add(portRow(PORT_ROW_ID, FORWARD_ID, OLD_NODE, 30120));

        String error = migrate(list(OLD_NODE), list(NEW_NODE));

        assertNull(error, "正常迁移必须成功，实际：" + error);
        List<JSONObject> newEntry = messages(NEW_NODE);
        List<JSONObject> oldEntry = messages(OLD_NODE);

        assertEquals(List.of("31_2_0_tcp", "31_2_0_udp"), addedNames(newEntry), "新入口要起 tcp/udp 两份服务");
        assertTrue(addedAddrs(newEntry).stream().allMatch(addr -> addr.endsWith(":30200")),
                "旧端口 30120 不在新节点端口段，应取第一个可用口 30200，实际：" + addedAddrs(newEntry));
        assertEquals(List.of("DeleteService"), deleteTypes(oldEntry), "旧入口只做清理");
        assertEquals(List.of("31_2_0_tcp", "31_2_0_udp"), deletedNames(oldEntry));

        assertTrue(timeline.indexOf(NEW_NODE + ":AddService") >= 0, "新入口先起服务：" + timeline);
        assertTrue(timeline.indexOf(NEW_NODE + ":AddService") < timeline.indexOf(OLD_NODE + ":DeleteService"),
                "必须先在新入口起服务，再撤旧入口，否则中间空档直接断流：" + timeline);

        ForwardPort written = capturedPortWrite();
        assertEquals(NEW_NODE, written.getNodeId());
        assertEquals(30200, written.getPort());
        assertEquals(PORT_ROW_ID, written.getId(), "必须是原地改行，不能新增一条留下旧行");
    }

    @Test
    void keepsOriginalPortWhenItIsStillFreeOnNewEntry() throws Exception {
        forwards.add(forward(FORWARD_ID, 1));
        portRows.add(portRow(PORT_ROW_ID, FORWARD_ID, OLD_NODE, 30205));

        String error = migrate(list(OLD_NODE), list(NEW_NODE));

        assertNull(error, error);
        assertTrue(addedAddrs(messages(NEW_NODE)).stream().allMatch(addr -> addr.endsWith(":30205")),
                "30205 在新节点段内且没人占用，应沿用，实际：" + addedAddrs(messages(NEW_NODE)));
        assertEquals(30205, capturedPortWrite().getPort());
    }

    @Test
    void bindFailureOnNewEntryRetriesNextCandidatePort() throws Exception {
        forwards.add(forward(FORWARD_ID, 1));
        portRows.add(portRow(PORT_ROW_ID, FORWARD_ID, OLD_NODE, 30120));
        bindFailuresLeft.put(NEW_NODE, new AtomicInteger(1));

        String error = migrate(list(OLD_NODE), list(NEW_NODE));

        assertNull(error, "换口后应成功，实际：" + error);
        List<String> addrs = addedAddrs(messages(NEW_NODE));
        assertTrue(addrs.get(0).endsWith(":30200"), "先试第一个候选：" + addrs);
        assertTrue(addrs.get(addrs.size() - 1).endsWith(":30201"), "bind 失败后应换到 30201：" + addrs);
        assertEquals(30201, capturedPortWrite().getPort(), "落库必须是最终生效端口，不能还是失败的那个");
        assertTrue(deleteTypes(messages(NEW_NODE)).contains("DeleteService"),
                "换口前要先清掉同名残留，否则新端口起来两份 service");
    }

    @Test
    void oldEntryCleanupFailureRollsNewEntryBackAndLeavesDatabaseUntouched() throws Exception {
        forwards.add(forward(FORWARD_ID, 1));
        portRows.add(portRow(PORT_ROW_ID, FORWARD_ID, OLD_NODE, 30120));
        forcedDeleteFailure.put(OLD_NODE, "delete service failed: timeout");

        String error = migrate(list(OLD_NODE), list(NEW_NODE));

        assertNotNull(error);
        assertTrue(error.contains("清理未确认"), error);
        verify(forwardPortService, never()).updateById(any());
        assertEquals(List.of("AddService", "DeleteService"), types(messages(NEW_NODE)),
                "旧入口没确认撤掉时，新入口必须先撤回，不能留两份同时在跑");
        assertEquals(OLD_NODE, portRow(PORT_ROW_ID, FORWARD_ID, OLD_NODE, 30120).getNodeId());
    }

    @Test
    void pausedForwardOnlyMovesPortRowAndNeverListensOnNewEntry() throws Exception {
        forwards.add(forward(FORWARD_ID, 0));
        portRows.add(portRow(PORT_ROW_ID, FORWARD_ID, OLD_NODE, 30120));

        String error = migrate(list(OLD_NODE), list(NEW_NODE));

        assertNull(error, error);
        assertTrue(addedNames(messages(NEW_NODE)).isEmpty(), "暂停的转发不能在新入口复活监听");
        assertEquals(NEW_NODE, capturedPortWrite().getNodeId());
        assertEquals(30200, capturedPortWrite().getPort());
    }

    @Test
    void laterForwardFailureRollsBackEarlierMigration() throws Exception {
        forwards.add(forward(FORWARD_ID, 1));
        forwards.add(forward(FORWARD_ID_2, 1));
        // 只有 31 有端口行；32 缺行 → 第二条失败，触发第一条回滚
        portRows.add(portRow(PORT_ROW_ID, FORWARD_ID, OLD_NODE, 30120));

        String error = migrate(list(OLD_NODE), list(NEW_NODE));

        assertNotNull(error);
        assertTrue(error.contains("缺少端口记录"), error);
        // 回滚 = 新入口撤 31 的服务 + 旧入口按原端口 30120 重建 31 的服务
        assertTrue(deletedNames(messages(NEW_NODE)).contains("31_2_0_tcp"), "回滚要撤回新入口服务");
        List<String> oldAddrs = addedAddrs(messages(OLD_NODE));
        assertTrue(oldAddrs.stream().allMatch(addr -> addr.endsWith(":30120")),
                "旧入口必须按迁移前的原端口重建，实际：" + oldAddrs);

        ArgumentCaptor<ForwardPort> captor = ArgumentCaptor.forClass(ForwardPort.class);
        verify(forwardPortService, atLeast(1)).updateById(captor.capture());
        List<ForwardPort> writes = captor.getAllValues();
        ForwardPort last = writes.get(writes.size() - 1);
        assertEquals(OLD_NODE, last.getNodeId(), "回滚后库里的入口节点必须回到旧节点");
        assertEquals(30120, last.getPort());
    }

    private String migrate(List<ChainTunnel> oldEntries, List<ChainTunnel> newEntries) {
        Map<Long, Node> nodes = new HashMap<>();
        nodes.put(OLD_NODE, node(OLD_NODE));
        nodes.put(NEW_NODE, node(NEW_NODE));
        return forwardService.migrateEntryNodes(TUNNEL_ID, oldEntries, newEntries, nodes);
    }

    private List<ChainTunnel> list(long nodeId) {
        ChainTunnel entry = new ChainTunnel();
        entry.setTunnelId(TUNNEL_ID);
        entry.setChainType(1);
        entry.setNodeId(nodeId);
        return List.of(entry);
    }

    private Forward forward(long id, int status) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setName("entry-migrate-" + id);
        forward.setUserId(USER_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(status);
        return forward;
    }

    private ForwardPort portRow(long id, long forwardId, long nodeId, int port) {
        ForwardPort row = new ForwardPort();
        row.setId(id);
        row.setForwardId(forwardId);
        row.setNodeId(nodeId);
        row.setPort(port);
        return row;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("entry-migrate");
        tunnel.setType(2);
        tunnel.setStatus(1);
        return tunnel;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + nodeId);
        node.setPort(nodeId == NEW_NODE ? "30200-30220" : "30100-30120");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private ForwardPort capturedPortWrite() {
        ArgumentCaptor<ForwardPort> captor = ArgumentCaptor.forClass(ForwardPort.class);
        verify(forwardPortService).updateById(captor.capture());
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
                future.complete(reply(nodeId, message));
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private GostDto reply(long nodeId, JSONObject message) {
        GostDto reply = new GostDto();
        String type = message.getString("type");
        timeline.add(nodeId + ":" + type);
        if ("AddService".equals(type) && bindFailuresLeft.containsKey(nodeId)
                && bindFailuresLeft.get(nodeId).getAndDecrement() > 0) {
            reply.setMsg("create service 31_2_0_tcp failed: listen tcp 0.0.0.0:30200: bind: address already in use");
            return reply;
        }
        if ("DeleteService".equals(type) && forcedDeleteFailure.containsKey(nodeId)) {
            reply.setMsg(forcedDeleteFailure.get(nodeId));
            return reply;
        }
        reply.setMsg("OK");
        return reply;
    }

    private List<JSONObject> messages(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private List<String> types(List<JSONObject> messages) {
        List<String> types = new ArrayList<>();
        for (JSONObject message : messages) {
            types.add(message.getString("type"));
        }
        return types;
    }

    private int typeOrder(List<JSONObject> messages, String type) {
        List<String> types = types(messages);
        int index = types.indexOf(type);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private List<String> deleteTypes(List<JSONObject> messages) {
        List<String> types = new ArrayList<>();
        for (String type : types(messages)) {
            if (type != null && type.startsWith("Delete")) {
                types.add(type);
            }
        }
        return types;
    }

    private List<String> addedNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
            if (!"AddService".equals(message.getString("type"))) {
                continue;
            }
            JSONArray services = message.getJSONArray("data");
            for (int i = 0; i < services.size(); i++) {
                names.add(services.getJSONObject(i).getString("name"));
            }
        }
        return names;
    }

    private List<String> addedAddrs(List<JSONObject> messages) {
        List<String> addrs = new ArrayList<>();
        for (JSONObject message : messages) {
            if (!"AddService".equals(message.getString("type"))) {
                continue;
            }
            JSONArray services = message.getJSONArray("data");
            for (int i = 0; i < services.size(); i++) {
                addrs.add(services.getJSONObject(i).getString("addr"));
            }
        }
        return addrs;
    }

    private List<String> deletedNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
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
}
