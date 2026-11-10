package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.entity.ChainTunnel;
import com.admin.entity.SpeedLimit;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.impl.SpeedLimitServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * resyncNodeLimiters 的回归测试。
 *
 * <p>覆盖的原缺口：限流器只在 createSpeedLimit 时逐节点下发一次，之后没有任何对账。
 * 节点容器重装/清盘/手工删配置后 gost.json 里的 limiters 整段消失，
 * 而节点上的服务仍带着 "limiter":"&lt;id&gt;" 引用——gost 查不到限流器时不报错也不限速，
 * 面板全绿，用户以为限速还在生效。
 *
 * <p>要求：
 * 1. 判据只来自库（speed_limit.tunnel_id 命中该节点所在隧道），不猜；
 * 2. 已存在（exists）必须再校准一次速率，避免"有对象但速率是旧值"；
 * 3. 失败必须显式上报，不能当成功。
 */
class SpeedLimitResyncTest {

    private static final long NODE = 1L;
    private static final long TUNNEL = 16L;

    private SpeedLimitServiceImpl speedLimitService;
    private ChainTunnelService chainTunnelService;
    private String addReply;
    private String updateReply;
    private List<SpeedLimit> limits;

    @BeforeEach
    void setUp() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();

        addReply = "OK";
        updateReply = "OK";
        limits = List.of(limit(1L, 8000000));

        SpeedLimitMapper mapper = mock(SpeedLimitMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);

        speedLimitService = new SpeedLimitServiceImpl();
        ReflectionTestUtils.setField(speedLimitService, "baseMapper", mapper);
        ReflectionTestUtils.setField(speedLimitService, "chainTunnelService", chainTunnelService);

        when(mapper.selectList(any())).thenReturn(limits);
        when(chainTunnelService.list(any())).thenReturn(List.of(chainRow()));
        registerNode(NODE);
    }

    @AfterEach
    void tearDown() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();
    }

    @Test
    void missingLimiterIsCreatedWithLibrarySpeed() throws Exception {
        List<String> notes = speedLimitService.resyncNodeLimiters(NODE);

        assertEquals(1, notes.size(), "缺一个补一个，实际：" + notes);
        assertTrue(notes.get(0).contains("已按库补齐限流器1@node1"), "实际：" + notes);
        assertTrue(notes.get(0).contains("1000000.0MB/s"), "速率换算应为 8Mbit/s → 1000000.0MB/s，实际：" + notes);

        List<JSONObject> messages = sentMessages(NODE);
        assertEquals(1, messages.size(), "只该下发一次，实际：" + typeNames(messages));
        assertEquals("AddLimiters", messages.get(0).getString("type"));
        JSONObject data = messages.get(0).getJSONObject("data");
        assertEquals("1", data.getString("name"));
        JSONArray limitArray = data.getJSONArray("limits");
        assertEquals(1, limitArray.size());
        assertEquals("$ 1000000.0MB 1000000.0MB", limitArray.getString(0));
    }

    @Test
    void existingLimiterIsRecalibrated() throws Exception {
        addReply = "create limiter 1 failed: limiter 1 exists";

        List<String> notes = speedLimitService.resyncNodeLimiters(NODE);

        assertTrue(notes.isEmpty(), "exists 且校准成功不该产生告警，实际：" + notes);
        WebSocketSession session = nodeSessions().get(NODE);
        InOrder inOrder = inOrder(session);
        inOrder.verify(session).sendMessage(argThat(m -> "AddLimiters".equals(typeOf(m))));
        inOrder.verify(session).sendMessage(argThat(m -> "UpdateLimiters".equals(typeOf(m))));

        // send_msg 会把业务参数整体塞进 data，所以 UpdateLimiters 的实际形状是
        // {"type":"UpdateLimiters","data":{"limiter":"1","data":{name,limits}}}
        JSONObject updatePayload = sentMessages(NODE).get(1).getJSONObject("data");
        assertEquals("1", updatePayload.getString("limiter"));
        assertEquals("$ 1000000.0MB 1000000.0MB",
                updatePayload.getJSONObject("data").getJSONArray("limits").getString(0));
    }

    @Test
    void calibrationFailureIsReported() throws Exception {
        addReply = "limiter 1 exists";
        updateReply = "update limiter 1 failed: invalid limits";

        List<String> notes = speedLimitService.resyncNodeLimiters(NODE);

        assertEquals(1, notes.size(), "校准失败必须上报，实际：" + notes);
        assertTrue(notes.get(0).contains("已存在但校准失败"), "实际：" + notes);
        assertTrue(notes.get(0).contains("invalid limits"), "要带原始错误，实际：" + notes);
    }

    @Test
    void createFailureIsReported() throws Exception {
        addReply = "create limiter 1 failed: disk full";

        List<String> notes = speedLimitService.resyncNodeLimiters(NODE);

        assertEquals(1, notes.size(), "实际：" + notes);
        assertTrue(notes.get(0).contains("下发失败"), "实际：" + notes);
        assertTrue(notes.get(0).contains("disk full"), "实际：" + notes);
    }

    @Test
    void nodeOutsideAnyTunnelIsLeftAlone() throws Exception {
        when(chainTunnelService.list(any())).thenReturn(List.of());

        List<String> notes = speedLimitService.resyncNodeLimiters(NODE);

        assertTrue(notes.isEmpty(), "实际：" + notes);
        assertTrue(sentMessages(NODE).isEmpty(), "不在拓扑里的节点不该收到任何命令，实际：" + typeNames(sentMessages(NODE)));
        verify(nodeSessions().get(NODE), never()).sendMessage(any());
    }

    // ---------------------------------------------------------------- 工具

    private SpeedLimit limit(long id, int speedBits) {
        SpeedLimit limit = new SpeedLimit();
        limit.setId(id);
        limit.setName("speed-" + id);
        limit.setSpeed(speedBits);
        limit.setTunnelId(TUNNEL);
        limit.setStatus(1);
        return limit;
    }

    private ChainTunnel chainRow() {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL);
        row.setChainType(1);
        row.setNodeId(NODE);
        row.setProtocol("tcp");
        row.setStrategy("round");
        return row;
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
                GostDto reply = new GostDto();
                String type = message.getString("type");
                if ("AddLimiters".equals(type)) {
                    reply.setMsg(addReply);
                } else if ("UpdateLimiters".equals(type)) {
                    reply.setMsg(updateReply);
                } else {
                    reply.setMsg("OK");
                }
                future.complete(reply);
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

    private static String typeOf(Object message) {
        return JSON.parseObject(((TextMessage) message).getPayload()).getString("type");
    }

    private static List<String> typeNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
            names.add(message.getString("type"));
        }
        return names;
    }
}
