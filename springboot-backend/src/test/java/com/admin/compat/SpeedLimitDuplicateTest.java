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
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.SpeedLimitServiceImpl;
import com.alibaba.fastjson.JSON;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限速规则「同隧道同名」防重的回归测试（round51）。
 *
 * <p>基线缺陷：createSpeedLimit 只校验隧道与速度，不查同隧道同名。并发下两条同名规则各自落库、
 * 各自在节点上下发一个 limiter；面板分不清哪条是"真"的，删掉一条另一条仍在该隧道限速。
 *
 * <p>要守住的口径：① 同名同隧道第二次被拒，且零 AddLimiters 下发、save 未被调用；
 * ② 不同隧道同名允许（tunnel_id 是 namescope 的一部分）；③ 名称前后空白 trim 后比较，
 * 落库也写 trim 后的名字；④ 预查与写入之间的并发窗口由唯一索引兜住，冲突翻译成同一句提示且不下发；
 * ⑤ 其它写库异常必须仍报「限速规则入库失败，未下发节点：…」，不能伪装成重名；
 * ⑥ 重名判定发生在节点在线预检之前：名字重复时不该因为节点离线改口说"节点不在线"。
 */
class SpeedLimitDuplicateTest {

    private static final long NODE = 1L;
    private static final long TUNNEL = 16L;
    private static final long OTHER_TUNNEL = 17L;
    private static final long LIMIT_ID = 7L;
    private static final String NAME = "speed-new";
    private static final String DUPLICATE_MSG = "该隧道下已存在同名限速规则";

    private SpeedLimitServiceImpl service;
    private SpeedLimitMapper mapper;
    private ChainTunnelService chainTunnelService;
    private UserTunnelService userTunnelService;
    private TunnelService tunnelService;

    @BeforeEach
    void setUp() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();

        mapper = mock(SpeedLimitMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        tunnelService = mock(TunnelService.class);

        service = new SpeedLimitServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(service, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);

        when(tunnelService.getById(any())).thenReturn(new Tunnel());
        when(chainTunnelService.list(any())).thenReturn(List.of(chainRow(NODE)));
        when(mapper.selectCount(any())).thenReturn(0);
        when(mapper.updateById(any())).thenReturn(1);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            SpeedLimit row = invocation.getArgument(0);
            row.setId(LIMIT_ID);
            return 1;
        });

        registerNode(NODE);
    }

    @AfterEach
    void tearDown() throws Exception {
        nodeSessions().clear();
        pendingRequests().clear();
    }

    // ========== 1) 同名同隧道第二次被拒，且不下发、不写库 ==========

    @Test
    void duplicateNameSameTunnelRejectedBeforeInsert() throws Exception {
        when(mapper.selectCount(any())).thenReturn(1);

        R result = service.createSpeedLimit(createDto(NAME));

        assertEquals(-1, result.getCode(), "同名同隧道不能报成功");
        assertEquals(DUPLICATE_MSG, result.getMsg());
        verify(mapper, never()).insert(any());
        assertTrue(sentMessages().isEmpty(), "被拒时不许下发任何 limiter，实际：" + typeNames());
    }

    // ========== 2) 不同隧道同名允许 ==========

    @Test
    void sameNameInAnotherTunnelIsAllowed() throws Exception {
        R result = service.createSpeedLimit(createDto(OTHER_TUNNEL, NAME));

        assertEquals(0, result.getCode(), "不同隧道同名是合法配置，实际：" + result.getMsg());
        ArgumentCaptor<QueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, times(1)).selectCount(wrapperCaptor.capture());
        QueryWrapper<?> wrapper = wrapperCaptor.getValue();
        // MP 的 #{ew.paramNameValuePairs.xxx} 是懒物化：不渲染 SQL 片段时参数表是空的
        wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(OTHER_TUNNEL),
                "查重必须带上本次请求的 tunnel_id：" + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(NAME),
                "查重必须带上本次请求的 name：" + wrapper.getParamNameValuePairs());
        assertEquals("[AddLimiters]", typeNames(), "允许时要正常下发一次 limiter");
    }

    // ========== 3) 名称前后空白 trim 后视为同名 ==========

    @Test
    void whitespaceIsTrimmedBeforeCompare() throws Exception {
        // 只有查重参数被 trim 成 "speed-new" 时才算命中既有规则
        when(mapper.selectCount(any())).thenAnswer(invocation -> {
            QueryWrapper<?> wrapper = invocation.getArgument(0);
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().containsValue(NAME) ? 1 : 0;
        });

        R result = service.createSpeedLimit(createDto("  " + NAME + "  "));

        assertEquals(-1, result.getCode(), "加空格绕不过重名检查");
        assertEquals(DUPLICATE_MSG, result.getMsg());
        verify(mapper, never()).insert(any());
        assertTrue(sentMessages().isEmpty(), "实际：" + typeNames());
    }

    @Test
    void storedNameIsTrimmed() throws Exception {
        service.createSpeedLimit(createDto(" " + NAME + " "));

        ArgumentCaptor<SpeedLimit> captor = ArgumentCaptor.forClass(SpeedLimit.class);
        verify(mapper, times(1)).insert(captor.capture());
        assertEquals(NAME, captor.getValue().getName(),
                "落库必须写 trim 后的名字，否则 'a' 与 ' a' 各自通过预查");
    }

    // ========== 4) 并发窗口：唯一索引冲突翻译成同一句提示且不下发 ==========

    @Test
    void uniqueConstraintOnSaveMapsToDuplicateMessage() throws Exception {
        doThrow(new RuntimeException("### Error updating database. Cause: org.sqlite.SQLiteException: "
                + "[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: "
                + "speed_limit.tunnel_id, speed_limit.name"))
                .when(mapper).insert(any());

        R result = service.createSpeedLimit(createDto(NAME));

        assertEquals(-1, result.getCode());
        assertEquals(DUPLICATE_MSG, result.getMsg(), "并发抢写必须和预查命中同一句提示");
        assertTrue(sentMessages().isEmpty(), "库没落盘就不许下发，实际：" + typeNames());
    }

    // ========== 5) 其它写库异常仍报入库失败，不伪装成重名 ==========

    @Test
    void otherSaveErrorKeepsInsertFailureText() throws Exception {
        doThrow(new RuntimeException("### Cause: org.sqlite.SQLiteException: "
                + "[SQLITE_BUSY] The database file is locked"))
                .when(mapper).insert(any());

        R result = service.createSpeedLimit(createDto(NAME));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().startsWith("限速规则入库失败，未下发节点："), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("SQLITE_BUSY"), "要带可判读的根因，实际：" + result.getMsg());
        assertFalse(result.getMsg().contains("同名限速规则"), "非唯一冲突不许说成重名：" + result.getMsg());
        assertTrue(sentMessages().isEmpty(), "实际：" + typeNames());
    }

    // ========== 6) 重名判定早于节点在线预检 ==========

    @Test
    void duplicateNameRejectedEvenWhenNodeOffline() throws Exception {
        nodeSessions().remove(NODE);
        when(mapper.selectCount(any())).thenReturn(1);

        R result = service.createSpeedLimit(createDto(NAME));

        assertEquals(DUPLICATE_MSG, result.getMsg(), "名字重复是确定性问题，不该被节点离线改写："
                + result.getMsg());
        verify(mapper, never()).insert(any());
    }

    // ========== 7) 改名：查重、trim、唯一冲突归一 ==========

    @Test
    void renameToExistingNameIsRejectedBeforeNodeUpdate() throws Exception {
        when(mapper.selectById(any())).thenReturn(storedLimit("old-name"));
        when(mapper.selectCount(any())).thenReturn(1);

        R result = service.updateSpeedLimit(updateDto(NAME));

        assertEquals(DUPLICATE_MSG, result.getMsg(), "改名撞同隧道既有名字必须在动节点之前拒绝，实际：" + result.getMsg());
        assertTrue(sentMessages().isEmpty(), "被拒时不许给节点下发 UpdateLimiters，实际：" + typeNames());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void renameToSameNameSkipsDuplicateQuery() throws Exception {
        when(mapper.selectById(any())).thenReturn(storedLimit(NAME));

        R result = service.updateSpeedLimit(updateDto(NAME));

        assertEquals(0, result.getCode(), "名字没变就不该查重，实际：" + result.getMsg());
        verify(mapper, never()).selectCount(any());
        assertEquals("[UpdateLimiters]", typeNames(), "只改速率时要正常下发一次");
    }

    @Test
    void renameIsTrimmedBeforeStore() throws Exception {
        when(mapper.selectById(any())).thenReturn(storedLimit("old-name"));

        R result = service.updateSpeedLimit(updateDto("  " + NAME + "  "));

        assertEquals(0, result.getCode(), "实际：" + result.getMsg());
        ArgumentCaptor<SpeedLimit> captor = ArgumentCaptor.forClass(SpeedLimit.class);
        verify(mapper, times(1)).updateById(captor.capture());
        assertEquals(NAME, captor.getValue().getName(), "落库必须写 trim 后的名字");
    }

    @Test
    void updateUniqueConflictMapsToDuplicateMessageWithoutTouchingNodes() throws Exception {
        when(mapper.selectById(any())).thenReturn(storedLimit("old-name"));
        when(mapper.updateById(any())).thenThrow(new RuntimeException(
                "### Cause: org.sqlite.SQLiteException: [SQLITE_CONSTRAINT_UNIQUE] "
                        + "UNIQUE constraint failed: speed_limit.tunnel_id, speed_limit.name"));

        R result = service.updateSpeedLimit(updateDto(NAME));

        assertEquals(-1, result.getCode());
        assertEquals(DUPLICATE_MSG, result.getMsg(), "并发改名冲突要归一到同一句，实际：" + result.getMsg());
        assertTrue(sentMessages().isEmpty(),
                "round51 先写库：唯一冲突时一个节点都不该被触碰，实际：" + typeNames());
    }

    // ---------------------------------------------------------------- 脚手架

    private SpeedLimit storedLimit(String name) {
        SpeedLimit limit = new SpeedLimit();
        limit.setId(LIMIT_ID);
        limit.setName(name);
        limit.setSpeed(4000000);
        limit.setTunnelId(TUNNEL);
        limit.setStatus(1);
        return limit;
    }

    private SpeedLimitUpdateDto updateDto(String name) {
        SpeedLimitUpdateDto dto = new SpeedLimitUpdateDto();
        dto.setId(LIMIT_ID);
        dto.setName(name);
        dto.setSpeed(8000000);
        return dto;
    }

    private SpeedLimitDto createDto(String name) {
        return createDto(TUNNEL, name);
    }

    private SpeedLimitDto createDto(long tunnelId, String name) {
        SpeedLimitDto dto = new SpeedLimitDto();
        dto.setName(name);
        dto.setSpeed(8000000);
        dto.setTunnelId(tunnelId);
        dto.setTunnelName("tunnel-" + tunnelId);
        return dto;
    }

    private ChainTunnel chainRow(long nodeId) {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL);
        row.setChainType(1);
        row.setNodeId(nodeId);
        row.setProtocol("tcp");
        row.setStrategy("round");
        return row;
    }

    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", String.valueOf(nodeId));
        attributes.put("type", "1");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            CompletableFuture<GostDto> future = pendingRequests().get(message.getString("requestId"));
            if (future != null) {
                GostDto reply = new GostDto();
                reply.setMsg("OK");
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions().put(nodeId, session);
    }

    private List<JSONObject> sentMessages() throws Exception {
        WebSocketSession session = nodeSessions().get(NODE);
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private String typeNames() {
        try {
            List<String> names = new ArrayList<>();
            for (JSONObject message : sentMessages()) {
                names.add(message.getString("type"));
            }
            return names.toString();
        } catch (Exception e) {
            return "读取下发消息失败：" + e.getMessage();
        }
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
}
