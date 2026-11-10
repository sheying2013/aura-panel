package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.SpeedLimitDto;
import com.admin.common.dto.SpeedLimitUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.SpeedLimit;
import com.admin.entity.Tunnel;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.SpeedLimitServiceImpl;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限速规则增删改的多节点一致性回归测试。
 *
 * <p>覆盖的原缺口（SpeedLimitServiceImpl 旧实现）：
 * <ul>
 *   <li>updateSpeedLimit 逐节点 UpdateLimiters，失败就直接 return R.err——前面节点已经改成新速率，
 *       库还是旧速率，节点之间速率不一致，前端只看到一句错误；</li>
 *   <li>updateSpeedLimit 不检查入库结果，库写失败时节点已全部改完，重启后速率回退；
 *       deleteSpeedLimit 同理，节点删了、库还在，用户以为限速仍然生效；</li>
 *   <li>round51 起 update 改为「先写库、再下发」：唯一冲突在节点被触碰前就被数据库拦住，
 *       节点失败再把库恢复旧值（库回滚是本地写，比逐节点网络回滚可靠）；</li>
 *   <li>createSpeedLimit 先 save 再逐节点下发，save 失败也照样下发，节点上留下一批库中不存在的限流器；</li>
 *   <li>三个方法都不预检节点在线，离线节点必然在中途失败，留下"改了一半"的状态。</li>
 * </ul>
 *
 * <p>修复口径：任一节点不在线整单拒绝（一个节点都不动）；下发失败按库里旧值回滚已改节点；
 * 库写失败同样回滚；回滚失败必须写进返回文案，不能吞掉。
 */
class SpeedLimitConsistencyTest {

    private static final long NODE_A = 1L;
    private static final long NODE_B = 2L;
    private static final long LIMIT_ID = 7L;
    private static final long TUNNEL_ID = 16L;

    private SpeedLimitServiceImpl service;
    private SpeedLimitMapper mapper;
    private ChainTunnelService chainTunnelService;
    private UserTunnelService userTunnelService;
    private TunnelService tunnelService;
    private Map<Long, Map<String, String>> replies;
    private int insertResult;
    private int updateResult;
    private int deleteResult;

    @BeforeEach
    void setUp() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();

        replies = new HashMap<>();
        insertResult = 1;
        updateResult = 1;
        deleteResult = 1;

        mapper = mock(SpeedLimitMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        tunnelService = mock(TunnelService.class);

        service = new SpeedLimitServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(service, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);

        when(mapper.insert(any())).thenAnswer(invocation -> {
            SpeedLimit row = invocation.getArgument(0);
            row.setId(LIMIT_ID);
            return insertResult;
        });
        when(mapper.updateById(any())).thenAnswer(invocation -> updateResult);
        when(mapper.deleteById(any())).thenAnswer(invocation -> deleteResult);
        when(mapper.selectById(any())).thenReturn(storedLimit(8000000));
        when(userTunnelService.count(any())).thenReturn(0);
        when(tunnelService.getById(any())).thenReturn(new Tunnel());
        when(chainTunnelService.list(any())).thenReturn(List.of(chainRow(NODE_A), chainRow(NODE_B)));

        registerNode(NODE_A);
        registerNode(NODE_B);
    }

    @AfterEach
    void tearDown() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();
    }

    @Test
    void updateRefusedWhenAnyNodeOffline() throws Exception {
        nodeSessions().remove(NODE_B);

        R result = service.updateSpeedLimit(updateDto(4000000));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点不在线"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("2"), "要点名离线节点，实际：" + result.getMsg());
        assertTrue(sentMessages(NODE_A).isEmpty(), "预检失败时一个节点都不该下发，实际：" + typeNames(sentMessages(NODE_A)));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void updateRollsBackEarlierNodesOnFailure() throws Exception {
        fail(NODE_B, "UpdateLimiters", "update limiter 7 failed: invalid limits");

        R result = service.updateSpeedLimit(updateDto(4000000));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点2"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("invalid limits"), "要带原始错误，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("已回滚1个节点"), "实际：" + result.getMsg());

        List<JSONObject> sent = sentMessages(NODE_A);
        assertEquals(2, sent.size(), "node1 应先是新速率、再回滚旧速率，实际：" + typeNames(sent));
        assertEquals("UpdateLimiters", sent.get(0).getString("type"));
        assertEquals("$ 500000.0MB 500000.0MB", limitsOf(sent.get(0)));
        assertEquals("UpdateLimiters", sent.get(1).getString("type"));
        assertEquals("$ 1000000.0MB 1000000.0MB", limitsOf(sent.get(1)), "回滚必须用库里的旧速率");

        // round51：先写库后下发，节点失败除了回滚节点，还必须把库恢复成旧值
        ArgumentCaptor<SpeedLimit> db = ArgumentCaptor.forClass(SpeedLimit.class);
        verify(mapper, times(2)).updateById(db.capture());
        assertEquals(Integer.valueOf(4000000), db.getAllValues().get(0).getSpeed(), "第一次写库是新速率");
        assertEquals("speed-7", db.getAllValues().get(0).getName());
        assertEquals(Integer.valueOf(8000000), db.getAllValues().get(1).getSpeed(), "节点失败后第二次写库恢复旧速率");
        assertEquals("speed-7", db.getAllValues().get(1).getName());
        assertTrue(result.getMsg().contains("库已恢复旧值"), "实际：" + result.getMsg());
    }

    @Test
    void updateDbFailureDoesNotTouchNodes() throws Exception {
        updateResult = 0;

        R result = service.updateSpeedLimit(updateDto(4000000));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("入库失败"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("未下发节点"), "实际：" + result.getMsg());
        assertTrue(sentMessages(NODE_A).isEmpty(), "库没写成就不该下发，实际：" + typeNames(sentMessages(NODE_A)));
        assertTrue(sentMessages(NODE_B).isEmpty());
    }

    @Test
    void createDoesNotPushWhenDbInsertFails() throws Exception {
        insertResult = 0;

        R result = service.createSpeedLimit(createDto(8000000));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("入库失败"), "实际：" + result.getMsg());
        assertTrue(sentMessages(NODE_A).isEmpty(), "库没落盘就不该下发，实际：" + typeNames(sentMessages(NODE_A)));
        assertTrue(sentMessages(NODE_B).isEmpty());
    }

    @Test
    void createRollsBackEarlierLimitersOnNodeFailure() throws Exception {
        fail(NODE_B, "AddLimiters", "create limiter 7 failed: disk full");

        R result = service.createSpeedLimit(createDto(8000000));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点2"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("已回滚1个节点"), "实际：" + result.getMsg());

        List<JSONObject> sent = sentMessages(NODE_A);
        assertEquals(2, sent.size(), "node1 应下发后回滚删除，实际：" + typeNames(sent));
        assertEquals("AddLimiters", sent.get(0).getString("type"));
        assertEquals("DeleteLimiters", sent.get(1).getString("type"));
        verify(mapper).deleteById(LIMIT_ID);
    }

    @Test
    void deleteRollsBackEarlierDeletesOnFailure() throws Exception {
        fail(NODE_B, "DeleteLimiters", "delete limiter 7 failed: limiter in use");

        R result = service.deleteSpeedLimit(LIMIT_ID);

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点2"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("已回滚1个节点"), "实际：" + result.getMsg());

        List<JSONObject> sent = sentMessages(NODE_A);
        assertEquals(2, sent.size(), "node1 应删除后按库重建，实际：" + typeNames(sent));
        assertEquals("DeleteLimiters", sent.get(0).getString("type"));
        assertEquals("AddLimiters", sent.get(1).getString("type"));
        assertEquals("$ 1000000.0MB 1000000.0MB", limitsOf(sent.get(1)), "重建必须用库里的速率");
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteRefusedWhenAnyNodeOffline() throws Exception {
        nodeSessions().remove(NODE_A);

        R result = service.deleteSpeedLimit(LIMIT_ID);

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点不在线"), "实际：" + result.getMsg());
        assertTrue(sentMessages(NODE_B).isEmpty(), "实际：" + typeNames(sentMessages(NODE_B)));
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void rollbackFailureIsReportedInsteadOfHidden() throws Exception {
        fail(NODE_B, "UpdateLimiters", "update limiter 7 failed: invalid limits");
        fail(NODE_A, "UpdateLimiters", "update limiter 7 failed: invalid limits", 2);

        R result = service.updateSpeedLimit(updateDto(4000000));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("回滚未完成"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("node1"), "实际：" + result.getMsg());
    }

    @Test
    void updateDbExceptionDoesNotTouchNodes() throws Exception {
        // 真机复现：SQLITE_BUSY 是抛异常而不是返回 0，旧的 if(!updateById) 分支根本进不去。
        // round51 改成先写库：写库抛异常时一个节点都还没动，不需要也不允许回滚节点。
        doAnswer(invocation -> {
            throw new RuntimeException("### Error updating database. Cause: org.sqlite.SQLiteException: [SQLITE_BUSY] The database file is locked");
        }).when(mapper).updateById(any());

        R result = service.updateSpeedLimit(updateDto(4000000));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("入库失败"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("SQLITE_BUSY"), "要带可判读的根因，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("未下发节点"), "实际：" + result.getMsg());
        assertFalse(result.getMsg().contains("已回滚"), "库没写成就不存在节点回滚，实际：" + result.getMsg());

        assertTrue(sentMessages(NODE_A).isEmpty(), "实际：" + typeNames(sentMessages(NODE_A)));
        assertTrue(sentMessages(NODE_B).isEmpty());
    }

    @Test
    void createDbExceptionDoesNotPush() throws Exception {
        doAnswer(invocation -> {
            throw new RuntimeException("### Cause: [SQLITE_BUSY] database is locked");
        }).when(mapper).insert(any());

        R result = service.createSpeedLimit(createDto(8000000));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("入库失败"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("SQLITE_BUSY"), "实际：" + result.getMsg());
        assertTrue(sentMessages(NODE_A).isEmpty(), "库没落盘就不该下发，实际：" + typeNames(sentMessages(NODE_A)));
        assertTrue(sentMessages(NODE_B).isEmpty());
    }

    @Test
    void deleteDbExceptionRebuildsLimiters() throws Exception {
        doAnswer(invocation -> {
            throw new RuntimeException("### Cause: [SQLITE_BUSY] database is locked");
        }).when(mapper).deleteById(any());

        R result = service.deleteSpeedLimit(LIMIT_ID);

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("删除失败"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("已回滚2个节点"), "实际：" + result.getMsg());

        for (long nodeId : new long[]{NODE_A, NODE_B}) {
            List<JSONObject> sent = sentMessages(nodeId);
            assertEquals(2, sent.size(), "node" + nodeId + " 应删除后重建，实际：" + typeNames(sent));
            assertEquals("DeleteLimiters", sent.get(0).getString("type"));
            assertEquals("AddLimiters", sent.get(1).getString("type"));
            assertEquals("$ 1000000.0MB 1000000.0MB", limitsOf(sent.get(1)));
        }
    }

    // ---------------------------------------------------------------- 工具

    private SpeedLimit storedLimit(int speedBits) {
        SpeedLimit limit = new SpeedLimit();
        limit.setId(LIMIT_ID);
        limit.setName("speed-" + LIMIT_ID);
        limit.setSpeed(speedBits);
        limit.setTunnelId(TUNNEL_ID);
        limit.setStatus(1);
        return limit;
    }

    private ChainTunnel chainRow(long nodeId) {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL_ID);
        row.setChainType(nodeId == NODE_A ? 1 : 2);
        row.setNodeId(nodeId);
        row.setProtocol("tcp");
        row.setStrategy("round");
        return row;
    }

    private SpeedLimitDto createDto(int speedBits) {
        SpeedLimitDto dto = new SpeedLimitDto();
        dto.setName("speed-new");
        dto.setSpeed(speedBits);
        dto.setTunnelId(TUNNEL_ID);
        dto.setTunnelName("tunnel-16");
        return dto;
    }

    private SpeedLimitUpdateDto updateDto(int speedBits) {
        SpeedLimitUpdateDto dto = new SpeedLimitUpdateDto();
        dto.setId(LIMIT_ID);
        dto.setName("speed-7");
        dto.setSpeed(speedBits);
        return dto;
    }

    /** nth（1 起）命中时返回错误，之前都成功：用来让"第一个节点成功、第二个失败"。 */
    private void fail(long nodeId, String type, String message) {
        fail(nodeId, type, message, 1);
    }

    private void fail(long nodeId, String type, String message, int nth) {
        Map<String, String> byType = replies.computeIfAbsent(nodeId, key -> new HashMap<>());
        byType.put(type + "#" + nth, message);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, WebSocketSession> nodeSessions() throws Exception {
        return (Map<Long, WebSocketSession>) staticField("nodeSessions");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CompletableFuture<GostDto>> pendingRequests() throws Exception {
        return (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private void registerNode(long nodeId) throws Exception {
        Map<String, Integer> counters = new HashMap<>();
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
            CompletableFuture<GostDto> future = pendingRequests().get(requestId);
            if (future != null) {
                String type = message.getString("type");
                int nth = counters.merge(type, 1, Integer::sum);
                String reply = replies.getOrDefault(nodeId, Map.of())
                        .getOrDefault(type + "#" + nth,
                                replies.getOrDefault(nodeId, Map.of()).getOrDefault(type, "OK"));
                GostDto dto = new GostDto();
                dto.setMsg(reply);
                future.complete(dto);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions().put(nodeId, session);
    }

    private List<JSONObject> sentMessages(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions().get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private static String limitsOf(JSONObject message) {
        JSONObject data = message.getJSONObject("data");
        if (data.containsKey("data")) {
            data = data.getJSONObject("data");
        }
        return data.getJSONArray("limits").getString(0);
    }

    private static List<String> typeNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
            names.add(message.getString("type"));
        }
        return names;
    }
}
