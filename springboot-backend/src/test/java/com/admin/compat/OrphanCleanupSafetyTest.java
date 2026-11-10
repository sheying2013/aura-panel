package com.admin.compat;

import com.admin.common.dto.ConfigItem;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.SpeedLimit;
import com.admin.entity.Tunnel;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 配置上报自动巡检的安全性回归。
 *
 * <p>核心不变量：数据库里查不到同名记录时，巡检只允许记录告警，绝不允许向节点下发
 * DeleteService / DeleteChains / DeleteLimiters。缺库不是所有权证据 —— 同名冲突资源、
 * 下发超时未落库的资源都会出现在节点上报里，按缺库删除会误删别人的数据。
 *
 * <p>节点下发通道（WebSocketServer 静态会话表）由假会话接管：任何一次下发都会被记录成
 * 一条负载，测试直接断言负载为空，因此不依赖真实 gost 节点，也不需要 mockito-inline。
 */
class OrphanCleanupSafetyTest {

    private static final long NODE_ID = 1L;
    private static final String NODE_ID_TEXT = "1";
    private static final String EXISTING_TUNNEL_ID = "7";
    private static final String EXISTING_FORWARD_ID = "9";
    private static final String EXISTING_LIMITER_ID = "5";

    private CheckGostConfigAsync inspection;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private ForwardService forwardService;
    private SpeedLimitService speedLimitService;
    private WebSocketSession nodeSession;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private Logger inspectionLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        forwardService = mock(ForwardService.class);
        speedLimitService = mock(SpeedLimitService.class);

        when(nodeService.getById(any())).thenReturn(node());
        when(tunnelService.getById(EXISTING_TUNNEL_ID)).thenReturn(tunnel());
        when(forwardService.getById(EXISTING_FORWARD_ID)).thenReturn(forward());
        when(speedLimitService.getById(Long.valueOf(EXISTING_LIMITER_ID))).thenReturn(speedLimit());
        when(speedLimitService.getById(EXISTING_LIMITER_ID)).thenReturn(speedLimit());

        inspection = new CheckGostConfigAsync();
        ReflectionTestUtils.setField(inspection, "nodeService", nodeService);
        ReflectionTestUtils.setField(inspection, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(inspection, "forwardService", forwardService);
        ReflectionTestUtils.setField(inspection, "speedLimitService", speedLimitService);

        inspectionLogger = (Logger) LoggerFactory.getLogger(CheckGostConfigAsync.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        inspectionLogger.addAppender(logAppender);

        nodeSession = mock(WebSocketSession.class);
        when(nodeSession.isOpen()).thenReturn(true);
        when(nodeSession.getId()).thenReturn("fake-node-" + NODE_ID);
        when(nodeSession.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto reply = new GostDto();
                reply.setMsg("OK");
                future.complete(reply);
            }
            return null;
        }).when(nodeSession).sendMessage(any());
        nodeSessions.put(NODE_ID, nodeSession);

        assertEquals(Collections.emptyList(), payloads(), "前置条件：巡检开始前不应有下发记录");
    }

    @AfterEach
    void tearDown() {
        pendingRequests.clear();
        nodeSessions.clear();
        inspectionLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void missingServicesOfEveryKindAreNeverDeleted() {
        // tcp / udp / tls 三类服务全部缺库：只告警，一条删除命令都不能发
        inspection.cleanNodeConfigs(NODE_ID_TEXT, config(
                List.of("100_tls", "101_1_0_tcp", "101_1_0_udp"), List.of(), List.of()));

        assertEquals(Collections.emptyList(), payloads(), "缺库服务不得触发任何节点下发");
        assertTrue(pendingRequests.isEmpty(), "没有下发就不该产生待响应请求");
        verify(tunnelService).getById("100");
        verify(forwardService, atLeast(1)).getById("101");
        verify(tunnelService, never()).getById(EXISTING_TUNNEL_ID);
    }

    @Test
    void missingChainsAreNeverDeleted() {
        inspection.cleanNodeConfigs(NODE_ID_TEXT, config(List.of(), List.of("chains_100", "chains_101"), List.of()));

        assertEquals(Collections.emptyList(), payloads(), "缺库链不得触发 DeleteChains");
        verify(tunnelService).getById("100");
        verify(tunnelService).getById("101");
    }

    @Test
    void missingLimitersAreNeverDeleted() {
        inspection.cleanNodeConfigs(NODE_ID_TEXT, config(List.of(), List.of(), List.of("100", "101")));

        assertEquals(Collections.emptyList(), payloads(), "缺库限流器不得触发 DeleteLimiters");
        verify(speedLimitService).getById(100L);
        verify(speedLimitService).getById(101L);
    }

    @Test
    void existingRecordsAreLeftAlone() {
        // 数据库有记录：本来就不是残留，同样不发任何命令
        inspection.cleanNodeConfigs(NODE_ID_TEXT, config(
                List.of(EXISTING_TUNNEL_ID + "_tls", EXISTING_FORWARD_ID + "_1_0_tcp", EXISTING_FORWARD_ID + "_1_0_udp"),
                List.of("chains_" + EXISTING_TUNNEL_ID),
                List.of(EXISTING_LIMITER_ID)));

        assertEquals(Collections.emptyList(), payloads(), "已存在记录不得触发任何下发");
        verify(tunnelService, atLeast(1)).getById(EXISTING_TUNNEL_ID);
        verify(forwardService, atLeast(1)).getById(EXISTING_FORWARD_ID);
        verify(speedLimitService, atLeast(1)).getById(any());
    }

    @Test
    void mixedReportDeletesNothingEvenWhenSomeRecordsAreMissing() {
        // 混报：既有记录正常、又有缺库项，缺库项仍然只告警
        inspection.cleanNodeConfigs(NODE_ID_TEXT, config(
                List.of(EXISTING_TUNNEL_ID + "_tls", "100_tls", EXISTING_FORWARD_ID + "_1_0_tcp", "101_1_0_udp"),
                List.of("chains_" + EXISTING_TUNNEL_ID, "chains_102"),
                List.of(EXISTING_LIMITER_ID, "103")));

        assertEquals(Collections.emptyList(), payloads(), "混报时也不得有任何删除下发");
        verify(tunnelService).getById("100");
        verify(tunnelService).getById("102");
        verify(forwardService).getById("101");
        verify(speedLimitService).getById(103L);
    }

    @Test
    void malformedNamesAreSkippedWithoutDeletingOrBreakingTheLoop() {
        // 错误输入：web_api、无后缀、后缀不认识、token 数量不对、限流器名不是数字、空名
        inspection.cleanNodeConfigs(NODE_ID_TEXT, config(
                List.of("web_api", "abc", "a_b_c", "5_tls_extra", "9_1_tcp", ""),
                List.of("", "chains_"),
                List.of("abc", "", "5.0")));

        assertEquals(Collections.emptyList(), payloads(), "错误输入不得触发任何下发");
        assertTrue(pendingRequests.isEmpty(), "错误输入不得产生待响应请求");
    }

    @Test
    void emptyAndNullSectionsAreTolerated() {
        inspection.cleanNodeConfigs(NODE_ID_TEXT, new GostConfigDto());
        inspection.cleanNodeConfigs(NODE_ID_TEXT, config(null, null, null));

        assertEquals(Collections.emptyList(), payloads(), "空上报不得触发任何下发");
    }

    @Test
    void unknownNodeIsIgnored() {
        when(nodeService.getById(any())).thenReturn(null);

        inspection.cleanNodeConfigs("404", config(List.of("100_tls"), List.of("chains_100"), List.of("100")));

        assertEquals(Collections.emptyList(), payloads(), "节点记录不存在时不做巡检，更不得下发");
        verify(tunnelService, never()).getById(any());
    }

    @Test
    void nullConfigReportIsSkippedWithoutAnyLookup() {
        // 节点上报内容为空：既不能 NPE，也不能拿「查不到记录」的结论去查库或下发
        assertDoesNotThrow(() -> inspection.cleanNodeConfigs(NODE_ID_TEXT, null));

        assertEquals(Collections.emptyList(), payloads(), "空上报不得触发任何下发");
        verifyNoInteractions(tunnelService, forwardService, speedLimitService);
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("配置为空")),
                "空上报应以 WARN 留下线索，而不是静默");
    }

    @Test
    void nullAndBlankNamesWarnInsteadOfBeingSwallowedAsExceptions() {
        // null / 空名称既不能 NPE 后被 safeExecute 吞掉，也不能当成可解析名称去查库
        inspection.cleanNodeConfigs(NODE_ID_TEXT, config(
                Arrays.asList(null, "", "  "), Arrays.asList(null, ""), Arrays.asList(null, "")));

        assertEquals(Collections.emptyList(), payloads(), "名称缺失不得触发任何下发");
        verifyNoInteractions(tunnelService, forwardService, speedLimitService);

        List<String> warnings = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(3, warnings.stream().filter(message -> message.contains("无法解析的服务名称")).count(),
                "三个服务名称都不可解析");
        assertEquals(2, warnings.stream().filter(message -> message.contains("无法解析的链名称")).count(),
                "两个链名称都不可解析");
        assertEquals(2, warnings.stream().filter(message -> message.contains("无法解析的限流器名称")).count(),
                "两个限流器名称都不可解析");
        assertTrue(logAppender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("配置巡检执行失败")),
                "名称缺失属于不可解析输入，不应落进异常吞没路径");
    }

    @Test
    void unexpectedInspectionFailureIsWarnedInsteadOfHiddenAtInfo() {
        // 巡检自身异常（例如查库报错）必须暴露成 WARN，否则告警缺失会被当成「无异常」
        when(tunnelService.getById("100")).thenThrow(new IllegalStateException("boom"));

        inspection.cleanNodeConfigs(NODE_ID_TEXT, config(List.of("100_tls"), List.of(), List.of()));

        assertEquals(Collections.emptyList(), payloads(), "巡检异常也不得下发任何命令");
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("配置巡检执行失败")),
                "巡检异常必须以 WARN 暴露");
    }

    // ========== 测试脚手架 ==========

    private static GostConfigDto config(List<String> services, List<String> chains, List<String> limiters) {
        GostConfigDto dto = new GostConfigDto();
        dto.setServices(items(services));
        dto.setChains(items(chains));
        dto.setLimiters(items(limiters));
        return dto;
    }

    private static List<ConfigItem> items(List<String> names) {
        if (names == null) {
            return null;
        }
        List<ConfigItem> items = new ArrayList<>();
        for (String name : names) {
            ConfigItem item = new ConfigItem();
            item.setName(name);
            items.add(item);
        }
        return items;
    }

    private List<String> payloads() {
        try {
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(nodeSession, atLeast(0)).sendMessage(captor.capture());
            List<String> sent = new ArrayList<>();
            for (TextMessage message : captor.getAllValues()) {
                sent.add(message.getPayload());
            }
            return sent;
        } catch (Exception e) {
            throw new IllegalStateException("读取节点下发记录失败", e);
        }
    }

    private static Node node() {
        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("node-" + NODE_ID);
        node.setServerIp("10.0.0.1");
        return node;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(Long.valueOf(EXISTING_TUNNEL_ID));
        tunnel.setName("tunnel");
        tunnel.setType(1);
        return tunnel;
    }

    private static Forward forward() {
        Forward forward = new Forward();
        forward.setId(Long.valueOf(EXISTING_FORWARD_ID));
        forward.setName("forward");
        return forward;
    }

    private static SpeedLimit speedLimit() {
        SpeedLimit speedLimit = new SpeedLimit();
        speedLimit.setId(Long.valueOf(EXISTING_LIMITER_ID));
        return speedLimit;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
