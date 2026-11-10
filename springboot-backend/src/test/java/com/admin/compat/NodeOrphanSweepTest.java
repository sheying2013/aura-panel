package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.NodeOrphanSweeper;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点自报资源清单（ServiceListReport）的孤儿清扫。
 *
 * <p>真机事故：隧道删了节点上还留着 5_tls 长期占着 30201，新建隧道一直 bind: address already in use；
 * 节点离线时删隧道只返回 leftovers，节点重启后 17_tls / chains_17 又活了，库里已经没有这行 → 永久僵尸。
 *
 * <p>这里锁死判定的边界：只有能对上"面板自己的资源"且库里查不到的名字才删；
 * node_*、未知名字、空清单一律不动；查询失败绝不删；单条失败只计数不影响其余。
 */
class NodeOrphanSweepTest {

    private static final long NODE_ID = 1L;

    // ===== 被测清扫器的可注入状态 =====
    private final Set<Long> existingTunnels = new HashSet<>();
    private final Set<Long> existingForwards = new HashSet<>();
    private final List<String> deletedServices = new ArrayList<>();
    private final List<String> deletedChains = new ArrayList<>();
    private boolean tunnelLookupFails;
    private boolean forwardLookupFails;
    private boolean nodeRejectsDelete;

    // ===== WebSocketServer 集成用 =====
    private WebSocketServer server;
    private TunnelService tunnelService;
    private ForwardService forwardService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private WebSocketSession session;
    private final List<String> payloads = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        existingTunnels.clear();
        existingForwards.clear();
        deletedServices.clear();
        deletedChains.clear();
        tunnelLookupFails = false;
        forwardLookupFails = false;
        nodeRejectsDelete = false;

        payloads.clear();
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        ((Set<Long>) staticField("sweepingNodes")).clear();

        tunnelService = mock(TunnelService.class);
        forwardService = mock(ForwardService.class);
        when(tunnelService.getById(any())).thenAnswer(invocation ->
                existingTunnels.contains(((Number) invocation.getArgument(0)).longValue()) ? tunnel() : null);
        when(forwardService.getById(any())).thenAnswer(invocation ->
                existingForwards.contains(((Number) invocation.getArgument(0)).longValue()) ? forward() : null);

        server = new WebSocketServer();
        ReflectionTestUtils.setField(server, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(server, "forwardService", forwardService);

        session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", String.valueOf(NODE_ID));
        attributes.put("type", "1");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + NODE_ID);
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            payloads.add(payload);
            JSONObject message = JSON.parseObject(payload);
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto gostDto = new GostDto();
                gostDto.setMsg(nodeRejectsDelete ? "-1: delete failed" : "OK");
                future.complete(gostDto);
            }
            return null;
        }).when(session).sendMessage(any());
    }

    @AfterEach
    void tearDown() throws Exception {
        nodeSessions.clear();
        pendingRequests.clear();
        payloads.clear();
        ((Set<Long>) staticField("sweepingNodes")).clear();
    }

    // ========== 1) 隧道服务 5_tls ==========

    @Test
    void orphanTlsServiceIsDeleted() {
        existingTunnels.add(16L);

        NodeOrphanSweeper.SweepResult result = sweeper().sweep(NODE_ID,
                List.of("5_tls", "16_tls"), Collections.emptyList());

        assertEquals(List.of("5_tls"), deletedServices, "库里没有 tunnel 5，节点上的 5_tls 是僵尸，必须删");
        assertEquals(2, result.getChecked(), "两个名字都命中白名单，都要查库");
        assertEquals(1, result.getDeleted());
        assertEquals(0, result.getFailed());
        assertEquals(List.of("5_tls"), result.getDeletedNames());
    }

    @Test
    void liveTlsServiceIsKept() {
        existingTunnels.add(16L);

        NodeOrphanSweeper.SweepResult result = sweeper().sweep(NODE_ID,
                List.of("16_tls"), Collections.emptyList());

        assertTrue(deletedServices.isEmpty(), "库里还有 tunnel 16，节点上的 16_tls 是在用的服务");
        assertEquals(1, result.getChecked());
        assertEquals(0, result.getDeleted());
    }

    // ========== 2) 转发服务 forwardId_userId_userTunnelId_proto ==========

    @Test
    void forwardServiceWithExistingRowIsKept() {
        existingForwards.add(9L);

        NodeOrphanSweeper.SweepResult result = sweeper().sweep(NODE_ID,
                List.of("9_1_0_tcp"), Collections.emptyList());

        assertTrue(deletedServices.isEmpty(), "库里还有 forward 9，9_1_0_tcp 不能动");
        assertEquals(1, result.getChecked());
        assertEquals(0, result.getDeleted());
    }

    @Test
    void forwardServiceWithMissingRowIsDeleted() {
        NodeOrphanSweeper.SweepResult result = sweeper().sweep(NODE_ID,
                List.of("99_1_0_udp"), Collections.emptyList());

        assertEquals(List.of("99_1_0_udp"), deletedServices, "forward 99 不存在，转发服务是孤儿");
        assertEquals(1, result.getChecked());
        assertEquals(1, result.getDeleted());
    }

    @Test
    void forwardServiceIsJudgedByItsFirstNumberOnly() {
        existingForwards.add(9L);
        existingTunnels.add(1L);

        sweeper().sweep(NODE_ID, List.of("9_1_0_tcp", "99_1_0_udp"), Collections.emptyList());

        assertEquals(List.of("99_1_0_udp"), deletedServices, "只看第一个数字（forwardId），不看 userId/userTunnelId");
    }

    // ========== 3) chains_ / hop_ ==========

    @Test
    void chainsAndHopOfMissingTunnelAreDeleted() {
        NodeOrphanSweeper.SweepResult result = sweeper().sweep(NODE_ID,
                Collections.emptyList(), List.of("chains_17", "hop_17"));

        assertEquals(List.of("chains_17", "hop_17"), deletedChains, "tunnel 17 已不存在，链路和跳板都是僵尸");
        assertEquals(2, result.getChecked());
        assertEquals(2, result.getDeleted());
    }

    @Test
    void chainsAndHopOfLiveTunnelAreKept() {
        existingTunnels.add(9L);

        NodeOrphanSweeper.SweepResult result = sweeper().sweep(NODE_ID,
                Collections.emptyList(), List.of("chains_9", "hop_9"));

        assertTrue(deletedChains.isEmpty(), "tunnel 9 还在用，链路不能删");
        assertEquals(2, result.getChecked());
        assertEquals(0, result.getDeleted());
    }

    // ========== 4) 白名单之外一律不动 ==========

    @Test
    void nodePrefixedAndUnknownNamesAreNeverDeleted() {
        NodeOrphanSweeper.SweepResult result = sweeper().sweep(NODE_ID,
                Arrays.asList("node_1", "node_2", "限流_1", "5_tlss", "5_TLS", "tls_5", "5_tls_tcp", "  ", null),
                Arrays.asList("node_1", "chains_", "chains_x", "hop_", "hop-17", "chains17"));

        assertTrue(deletedServices.isEmpty(), "node_* / 未知命名一律不动：" + deletedServices);
        assertTrue(deletedChains.isEmpty(), "chains_ / hop_ 之外的命名一律不动：" + deletedChains);
        assertEquals(0, result.getChecked(), "白名单之外的名字连库都不查");
        assertEquals(0, result.getDeleted());
        assertEquals(0, result.getFailed());
    }

    @Test
    void emptyAndNullListsProduceNothing() {
        NodeOrphanSweeper.SweepResult empty = sweeper().sweep(NODE_ID, List.of(), List.of());
        NodeOrphanSweeper.SweepResult nulls = sweeper().sweep(NODE_ID, null, null);

        assertTrue(deletedServices.isEmpty());
        assertTrue(deletedChains.isEmpty());
        assertEquals(0, empty.getChecked() + nulls.getChecked());
        assertEquals(0, empty.getDeleted() + nulls.getDeleted());
        assertEquals(0, empty.getFailed() + nulls.getFailed());
    }

    @Test
    void nodeIdIsPassedThroughToEveryDelete() {
        final List<Long> nodes = new ArrayList<>();
        NodeOrphanSweeper sweeper = new NodeOrphanSweeper(
                id -> false,
                id -> false,
                (id, names) -> {
                    nodes.add(id);
                    return true;
                },
                (id, name) -> {
                    nodes.add(id);
                    return true;
                });

        sweeper.sweep(77L, List.of("5_tls"), List.of("chains_17"));

        assertEquals(List.of(77L, 77L), nodes, "删除必须打到上报清单的那个节点上");
    }

    // ========== 5) 失败路径 ==========

    @Test
    void lookupFailureDeletesNothingAndCountsAsFailed() {
        tunnelLookupFails = true;

        NodeOrphanSweeper.SweepResult result = sweeper().sweep(NODE_ID,
                List.of("5_tls"), List.of("chains_17"));

        assertTrue(deletedServices.isEmpty(), "查库失败不能当成'孤儿'去删");
        assertTrue(deletedChains.isEmpty());
        assertTrue(result.getDeletedNames().isEmpty());
        assertEquals(2, result.getFailed());
        assertEquals(0, result.getDeleted());
    }

    @Test
    void oneLookupFailureDoesNotStopTheRest() {
        NodeOrphanSweeper sweeper = new NodeOrphanSweeper(
                id -> {
                    if (id == 5L) {
                        throw new IllegalStateException("db down");
                    }
                    return false;
                },
                id -> false,
                (id, names) -> {
                    for (Object name : names) {
                        deletedServices.add((String) name);
                    }
                    return true;
                },
                (id, name) -> {
                    deletedChains.add(name);
                    return true;
                });

        NodeOrphanSweeper.SweepResult result = sweeper.sweep(NODE_ID,
                List.of("5_tls", "6_tls"), List.of("chains_17"));

        assertEquals(1, result.getFailed(), "只有 5_tls 那条失败");
        assertEquals(2, result.getDeleted(), "6_tls 与 chains_17 照删：单条查询失败不许中断整轮清扫");
        assertEquals(List.of("6_tls"), deletedServices);
        assertEquals(List.of("chains_17"), deletedChains);
    }

    @Test
    void deleteRejectedByNodeCountsAsFailed() {
        nodeRejectsDelete = true;

        NodeOrphanSweeper.SweepResult result = sweeper().sweep(NODE_ID,
                List.of("5_tls"), List.of("chains_17"));

        assertTrue(result.getDeletedNames().isEmpty(), "节点没确认就不算删掉");
        assertEquals(2, result.getFailed());
        assertEquals(0, result.getDeleted());
    }

    // ========== 6) WebSocketServer 接入 ==========

    @Test
    void serviceListReportTriggersSweepForReportedNode() throws Exception {
        existingTunnels.add(16L);
        existingTunnels.add(9L);
        nodeSessions.put(NODE_ID, session);

        server.handleTextMessage(session, new TextMessage(report(
                "5_tls", "16_tls", "99_1_0_udp")));

        List<JSONObject> deletes = awaitDeletes(2);
        assertEquals(2, deletes.size(), "5_tls 与 99_1_0_udp 各下发一次，16_tls 不动：" + deletes);
        assertEquals(List.of("5_tls"), serviceNames(deletes.get(0)), "DeleteService 载荷是单元素数组");
        assertEquals(List.of("99_1_0_udp"), serviceNames(deletes.get(1)));
        verify(tunnelService).getById(5L);
        verify(tunnelService, atLeastOnce()).getById(16L);
        verify(forwardService).getById(99L);
    }

    @Test
    void chainEntriesOfATunnelStillInDbAreNotDeleted() throws Exception {
        existingTunnels.add(9L);
        nodeSessions.put(NODE_ID, session);

        server.handleTextMessage(session, new TextMessage(reportWithChains("chains_9", "hop_9", "chains_17")));

        List<JSONObject> deletes = awaitDeletes(1);
        assertEquals(1, deletes.size(), "只有 chains_17 是孤儿：" + deletes);
        assertEquals("chains_17", deletes.get(0).getJSONObject("data").getString("chain"));
    }

    @Test
    void reportWithNothingOrphanedSendsNoDelete() throws Exception {
        existingTunnels.add(5L);
        nodeSessions.put(NODE_ID, session);

        server.handleTextMessage(session, new TextMessage(report("5_tls")));

        Thread.sleep(400);
        assertTrue(deletes().isEmpty(), "全都在库里，一条删除都不许发：" + payloads);
        verify(tunnelService, atLeastOnce()).getById(5L);
    }

    @Test
    void failedReportIsIgnored() throws Exception {
        nodeSessions.put(NODE_ID, session);
        JSONObject payload = JSON.parseObject(report("5_tls"));
        payload.put("success", false);

        server.handleTextMessage(session, new TextMessage(payload.toJSONString()));

        Thread.sleep(400);
        assertTrue(deletes().isEmpty(), "节点自己说清单不可靠，不能拿它当证据删东西：" + payloads);
        verify(tunnelService, never()).getById(any());
    }

    @Test
    void nonReportMessageDoesNotTouchTheDatabase() throws Exception {
        nodeSessions.put(NODE_ID, session);

        server.handleTextMessage(session, new TextMessage("{\"type\":\"PingResponse\",\"message\":\"OK\"}"));

        Thread.sleep(300);
        assertTrue(deletes().isEmpty());
        verify(tunnelService, never()).getById(any());
        verify(forwardService, never()).getById(any());
    }

    @Test
    void inFlightSweepSkipsDuplicateReport() throws Exception {
        @SuppressWarnings("unchecked")
        Set<Long> sweeping = (Set<Long>) staticField("sweepingNodes");
        nodeSessions.put(NODE_ID, session);

        sweeping.add(NODE_ID);
        server.handleTextMessage(session, new TextMessage(report("5_tls")));
        Thread.sleep(400);
        assertTrue(deletes().isEmpty(), "同一节点正在清扫时，重连风暴带来的重复上报直接丢弃");
        verify(tunnelService, never()).getById(any());

        sweeping.remove(NODE_ID);
        server.handleTextMessage(session, new TextMessage(report("5_tls")));
        assertEquals(1, awaitDeletes(1).size(), "标记释放后，下一次上报照常清扫");
    }

    // ========== 脚手架 ==========

    private NodeOrphanSweeper sweeper() {
        return new NodeOrphanSweeper(
                id -> {
                    if (tunnelLookupFails) {
                        throw new IllegalStateException("db down");
                    }
                    return existingTunnels.contains(id);
                },
                id -> {
                    if (forwardLookupFails) {
                        throw new IllegalStateException("db down");
                    }
                    return existingForwards.contains(id);
                },
                (id, names) -> {
                    if (nodeRejectsDelete) {
                        return false;
                    }
                    for (Object name : names) {
                        deletedServices.add((String) name);
                    }
                    return true;
                },
                (id, name) -> {
                    if (nodeRejectsDelete) {
                        return false;
                    }
                    deletedChains.add(name);
                    return true;
                });
    }

    private String report(String... services) {
        JSONArray array = new JSONArray();
        array.addAll(Arrays.asList(services));
        JSONObject data = new JSONObject();
        data.put("services", array);
        data.put("chains", new JSONArray());
        JSONObject report = new JSONObject();
        report.put("type", "ServiceListReport");
        report.put("success", true);
        report.put("message", "OK");
        report.put("data", data);
        return report.toJSONString();
    }

    private String reportWithChains(String... chains) {
        JSONObject report = JSON.parseObject(report());
        JSONArray array = new JSONArray();
        array.addAll(Arrays.asList(chains));
        report.getJSONObject("data").put("chains", array);
        return report.toJSONString();
    }

    private List<JSONObject> deletes() {
        List<JSONObject> found = new ArrayList<>();
        synchronized (payloads) {
            for (String payload : payloads) {
                JSONObject message = JSON.parseObject(payload);
                String type = message.getString("type");
                if ("DeleteService".equals(type) || "DeleteChains".equals(type)) {
                    found.add(message);
                }
            }
        }
        return found;
    }

    private List<JSONObject> awaitDeletes(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (deletes().size() >= expected) {
                break;
            }
            Thread.sleep(10);
        }
        Thread.sleep(100);
        return deletes();
    }

    private List<String> serviceNames(JSONObject deleteMessage) {
        return deleteMessage.getJSONObject("data").getJSONArray("services").toJavaList(String.class);
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(1L);
        tunnel.setName("tunnel");
        // 库里的真实行都有类型：只有隧道转发（type=2）才需要 <id>_tls / chains_<id>，清扫器按类型判定
        tunnel.setType(2);
        return tunnel;
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(1L);
        forward.setUserId(1);
        forward.setTunnelId(1);
        return forward;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
