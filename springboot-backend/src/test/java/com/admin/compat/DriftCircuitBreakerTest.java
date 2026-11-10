package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.DriftCircuitBreaker;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置漂移重建熔断器的回归测试。
 *
 * <p>覆盖的原缺口：对账只看「摘要对不上」就删服务重建，但没有次数上限。节点侧口径只要有一处
 * 永远对不上（不可控字段、老节点残留、重建后节点又换了写法），每轮心跳都会删一遍建一遍，
 * 业务长连接被反复掐断且永不自愈。
 *
 * <p>要求：
 * 1. 连续 {@code threshold} 次漂移就熔断，冷却期内一次都不再删建；
 * 2. 冷却到期放行一次探测，再连续 {@code threshold} 次才重新熔断（不是一次漂移永久锁死）；
 * 3. 摘要一致立即清零并移除条目；
 * 4. 心跳是并发回调，同一 key 的计数不能丢、也不能两个线程同时判定可重建。
 */
class DriftCircuitBreakerTest {

    // ---------------------------------------------------------------- 熔断器本体

    @Test
    void allowsFirstDrifts() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(3, 300_000L);

        assertTrue(breaker.allowAttempt("k"), "还没漂移过，必须放行");
        assertEquals(1, breaker.recordDrift("k"), "第一次漂移计数 1");
        assertTrue(breaker.allowAttempt("k"), "1 次没到阈值，仍要放行");
        assertEquals(2, breaker.recordDrift("k"), "第二次漂移计数 2");
        assertTrue(breaker.allowAttempt("k"), "2 次没到阈值，仍要放行");
        assertFalse(breaker.isOpen("k"), "未到阈值不算熔断");
        assertEquals(0L, breaker.openUntil("k"), "未熔断时没有到期时间");
    }

    @Test
    void blocksAfterThresholdDrifts() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(3, 300_000L);

        breaker.recordDrift("k");
        breaker.recordDrift("k");
        assertEquals(3, breaker.recordDrift("k"), "第 3 次漂移");

        assertTrue(breaker.isOpen("k"), "第 3 次漂移后必须熔断");
        assertFalse(breaker.allowAttempt("k"), "冷却期内不允许再删建");
        assertEquals(3, breaker.consecutiveDrifts("k"), "熔断命中时日志要能拿到连续次数");
        assertTrue(breaker.remainingCooldownMs("k") > 0L, "冷却剩余时间必须为正");
    }

    @Test
    void cooldownExpiryAllowsOneProbe() throws Exception {
        // 冷却 50ms：到期后必须放行探测，否则一次漂移就永久锁死
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(3, 50L);
        breaker.recordDrift("k");
        breaker.recordDrift("k");
        breaker.recordDrift("k");
        assertFalse(breaker.allowAttempt("k"), "冷却中");

        Thread.sleep(120L);

        assertTrue(breaker.allowAttempt("k"), "冷却到期必须放行一次探测");
        assertFalse(breaker.allowAttempt("k"), "同一冷却窗口只能放行一次：并发心跳不能同时删建");
        assertEquals(3, breaker.consecutiveDrifts("k"), "探测不清零计数，失败才能立刻回到熔断");
        assertTrue(breaker.isOpen("k"), "探测窗口内仍按熔断处理（未成功前不算恢复）");
    }

    @Test
    void probeThatDriftsAgainReopensAfterThreshold() throws Exception {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(3, 50L);
        for (int i = 0; i < 3; i++) {
            breaker.recordDrift("k");
        }
        Thread.sleep(120L);
        assertTrue(breaker.allowAttempt("k"), "冷却到期放行探测");

        assertEquals(4, breaker.recordDrift("k"), "探测失败继续累加，不是新窗口从 1 数");
        assertTrue(breaker.isOpen("k"), "探测又漂移必须立刻回到熔断态");
        assertFalse(breaker.allowAttempt("k"), "重新熔断后冷却期内不再下发");
        assertTrue(breaker.remainingCooldownMs("k") > 0L, "重新熔断要刷新冷却到期时间");

        // 只有探测成功才清零，之后可以重新累计
        breaker.recordMatch("k");
        assertEquals(0, breaker.consecutiveDrifts("k"));
        assertTrue(breaker.allowAttempt("k"), "状态已清，恢复放行");
    }

    @Test
    void matchClearsState() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(3, 300_000L);
        for (int i = 0; i < 3; i++) {
            breaker.recordDrift("k");
        }
        assertTrue(breaker.isOpen("k"));

        breaker.recordMatch("k");

        assertFalse(breaker.isOpen("k"), "摘要一致后不得再算熔断");
        assertTrue(breaker.allowAttempt("k"), "清零后必须放行");
        assertEquals(0, breaker.consecutiveDrifts("k"), "计数已清零");
        assertEquals(0L, breaker.openUntil("k"), "条目已移除");
        assertEquals(0L, breaker.remainingCooldownMs("k"));
    }

    @Test
    void keysAreIndependent() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(3, 300_000L);
        for (int i = 0; i < 3; i++) {
            breaker.recordDrift("service|1|21_1_0");
        }

        assertFalse(breaker.allowAttempt("service|1|21_1_0"), "同一资源被熔断");
        assertTrue(breaker.allowAttempt("service|1|22_1_0"), "换了资源互不影响");
        assertTrue(breaker.allowAttempt("service|2|21_1_0"), "换了节点互不影响");
        assertTrue(breaker.allowAttempt("relay|1|21_1_0"), "换了资源类型互不影响");
    }

    @Test
    void concurrentDriftsAreNotLost() throws Exception {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(3, 60_000L);
        int threads = 8;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        breaker.recordDrift("service|201|21_1_0");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, breaker.consecutiveDrifts("service|201|21_1_0"),
                "8 线程各 10 次，一次都不能丢");
        assertTrue(breaker.isOpen("service|201|21_1_0"), "早就过阈值了");
        assertFalse(breaker.allowAttempt("service|201|21_1_0"));
    }

    @Test
    void openUntilMovesForwardWithEachReopen() throws Exception {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(3, 120L);
        long before = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            breaker.recordDrift("k");
        }
        long firstOpenUntil = breaker.openUntil("k");
        assertTrue(firstOpenUntil >= before + 120L, "到期时间 = 熔断时刻 + 冷却，实际：" + firstOpenUntil);
        long remaining = breaker.remainingCooldownMs("k");
        assertTrue(remaining > 0L && remaining <= 120L, "剩余冷却在 (0, 120] 内，实际：" + remaining);

        Thread.sleep(160L);
        assertTrue(breaker.allowAttempt("k"), "冷却到期放行");
        for (int i = 0; i < 3; i++) {
            breaker.recordDrift("k");
        }
        long secondOpenUntil = breaker.openUntil("k");

        assertTrue(secondOpenUntil > firstOpenUntil,
                "重新熔断的到期时间必须更晚：" + firstOpenUntil + " → " + secondOpenUntil);
        assertTrue(breaker.remainingCooldownMs("k") > 0L, "重新熔断后又有冷却");
    }

    @Test
    void productionDefaultsMatchReconcileCadence() {
        assertSame(DriftCircuitBreaker.shared(), DriftCircuitBreaker.shared(), "共享实例必须是同一个");
        assertEquals(2, DriftCircuitBreaker.DEFAULT_THRESHOLD,
                "阈值 2：第一次失败先观察，第二次失败就熔断");
        assertEquals(900_000L, DriftCircuitBreaker.DEFAULT_COOLDOWN_MS);
        assertTrue(DriftCircuitBreaker.DEFAULT_COOLDOWN_MS >= 3 * 300_000L,
                "冷却必须显著大于 NodeReconcileTask 的 300000ms 巡检周期；同频会让每轮巡检都放开探测，"
                        + "熔断形同虚设（QA P0-2）");
    }

    @Test
    void snapshotExposesExpectedActualAndRemaining() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 60_000L);
        breaker.recordDrift("service|1|21_1_0", "30111|tcp|-|backend:6365", "30112|tcp|-|backend:6365");
        breaker.recordDrift("service|1|21_1_0", "30111|tcp|-|backend:6365", "30112|tcp|-|backend:6365");

        Map<String, Map<String, Object>> snapshot = breaker.snapshot();
        assertEquals(1, snapshot.size());
        Map<String, Object> row = snapshot.get("service|1|21_1_0");
        assertEquals(2, row.get("count"), "熔断条目要带上连续次数");
        assertEquals(Boolean.TRUE, row.get("open"), "熔断条目要能一眼看出是开着还是半开");
        assertEquals("30111|tcp|-|backend:6365", row.get("expected"), "面板要能显示库里的期望值");
        assertEquals("30112|tcp|-|backend:6365", row.get("actual"), "面板要能显示节点上的实际值");
        assertTrue(((Long) row.get("remainingMs")) > 0L, "要能显示冷却剩余时间");
    }

    @Test
    void stateTableIsBoundedByMaxStates() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 60_000L, 8, 3_600_000L);
        for (int i = 0; i < 40; i++) {
            breaker.recordDrift("service|1|f" + i);
        }
        assertTrue(breaker.size() <= 8, "条目数必须被 maxStates 兜住，实际：" + breaker.size());
    }

    @Test
    void registeredInstancesAreDiscoverable() {
        DriftCircuitBreaker breaker = DriftCircuitBreaker.registered(2, 60_000L);
        breaker.recordDrift("service|9|1_1_0", "expected", "actual");

        Map<String, Map<String, Object>> all = DriftCircuitBreaker.snapshotAll();
        assertTrue(all.containsKey("service|9|1_1_0"), "登记实例的熔断状态必须能被管理端读到");
        assertTrue(DriftCircuitBreaker.liveInstances() >= 1, "登记表要能数出存活实例");
    }

    // ---------------------------------------------------------------- 接入层：连续 4 轮同一漂移

    private static final long NODE_ID = 201L;
    private static final long FORWARD_ID = 21L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;
    private static final int PORT = 30111;
    private static final String SERVICE_NAME = "21_1_0";
    private static final String REMOTE_ADDR = "backend:6365";
    /** 库里端口 30111，节点上却在听 30112：每轮上报都一样，正是「永远对不上」的风暴场景 */
    private static final String DRIFTED_DIGEST = (PORT + 1) + "|tcp|-|" + REMOTE_ADDR;

    /** AddService 的节点回包；非 null 时把回包改成失败，用来模拟"补了也起不来"的不可修复故障。 */
    private String addServiceFailure;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpNodeSessions() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @AfterEach
    void tearDownNodeSessions() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    /**
     * 接入层核心用例：节点连续 4 轮上报同一份漂移摘要。
     * 前 3 轮按原逻辑删建（第 3 轮后熔断），第 4 轮必须一个字节都不下发，只留一条「已熔断」说明。
     */
    @Test
    void fourthIdenticalDriftRoundSendsNothing() throws Exception {
        ForwardServiceImpl forwardService = serviceUnderTest();
        List<String> reported = List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp");

        List<String> notes1 = forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(),
                digests(DRIFTED_DIGEST));
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(),
                "第 1 轮漂移必须整对删了重建，实际：" + notes1);
        assertTrue(notes1.stream().anyMatch(n -> n.contains("服务重建")), "第 1 轮应重建，实际：" + notes1);

        forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(), digests(DRIFTED_DIGEST));
        forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(), digests(DRIFTED_DIGEST));
        List<String> messagesAfterThird = messageTypes();
        assertEquals(6, deletedServiceNames().size(), "前三轮各删一次（tcp+udp），实际：" + messagesAfterThird);
        assertTrue(messageTypes().contains("AddService"), "前三轮都要重建");

        List<String> notes4 = forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(),
                digests(DRIFTED_DIGEST));

        assertEquals(messagesAfterThird, messageTypes(), "第 4 轮已熔断：不能再下发任何消息");
        assertEquals(6, deletedServiceNames().size(), "第 4 轮不能再删服务");
        assertTrue(notes4.stream().anyMatch(n -> n.contains("已熔断")),
                "第 4 轮要留一条熔断说明，实际：" + notes4);
        assertTrue(notes4.stream().anyMatch(n -> n.contains("连续3次")),
                "说明里要带连续次数，实际：" + notes4);
        assertTrue(notes4.stream().anyMatch(n -> n.contains("库 " + PORT + "|tcp|-|" + REMOTE_ADDR)
                        && n.contains("节点 " + DRIFTED_DIGEST)),
                "说明里要带两侧摘要，实际：" + notes4);
        assertFalse(messageTypes().contains("ResumeService"),
                "熔断命中返回 true 表示已处置，不能再走恢复分支，实际：" + messageTypes());
    }

    /**
     * QA P0-1 的第二个漏口：服务整对被删（或节点重装）后"按库补齐"失败，同样必须熔断。
     * 真机基线：监听地址改成节点上不存在的 192.0.2.10 后，节点每重连一次就失败一次，永不停。
     */
    @Test
    void missingServiceCreateFailureStopsAfterThreshold() throws Exception {
        ForwardServiceImpl forwardService = serviceUnderTest();
        ReflectionTestUtils.setField(forwardService, "driftBreaker", new DriftCircuitBreaker(2, 80L));
        addServiceFailure = "create service 21_1_0_tcp failed: listen tcp4 192.0.2.10:30111: bind: "
                + "cannot assign requested address";
        // 清单非空（节点上还有别的服务），但这对外发服务整对都不在 → 走"补齐缺失"路径
        List<String> reported = List.of("9_tls");

        List<String> notes1 = forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(), null);
        assertTrue(notes1.stream().anyMatch(n -> n.contains("补齐服务") && n.contains("失败")),
                "第 1 轮必须真去创建一次并记录失败，实际：" + notes1);
        List<String> notes2 = forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(), null);
        assertTrue(notes2.stream().anyMatch(n -> n.contains("补齐服务") && n.contains("失败")),
                "阈值 2 时第 2 轮仍要尝试，实际：" + notes2);

        List<String> typesAfterTwo = messageTypes();
        List<String> notes3 = forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(), null);
        assertEquals(typesAfterTwo, messageTypes(), "第 3 轮已熔断：不能再向节点发创建请求");
        assertTrue(notes3.stream().anyMatch(n -> n.contains("已熔断")), "要留熔断说明，实际：" + notes3);
        assertTrue(notes3.stream().anyMatch(n -> n.contains("补齐缺失服务 " + SERVICE_NAME)),
                "说明要点明是哪条资源，实际：" + notes3);

        // 冷却到期放行一次探测；这次节点恢复正常，状态必须清掉
        addServiceFailure = null;
        Thread.sleep(160L);
        List<String> notes4 = forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(), null);
        assertTrue(notes4.stream().anyMatch(n -> n.contains("已按库补齐缺失服务")),
                "冷却到期应放行一次并成功补齐，实际：" + notes4);
        assertFalse(typesAfterTwo.equals(messageTypes()), "放行那轮必须真的下发");
        DriftCircuitBreaker breaker = (DriftCircuitBreaker) ReflectionTestUtils.getField(forwardService,
                "driftBreaker");
        assertEquals(0, breaker.consecutiveDrifts("service|" + NODE_ID + "|" + SERVICE_NAME),
                "补齐成功后熔断计数必须清零，否则下一次偶发失败会直接熔断");
    }

    /** 漂移消失（摘要对上）时熔断状态必须清掉，否则修好一次也永远锁死。 */
    @Test
    void matchingDigestAfterBreakerOpenedClearsIt() throws Exception {
        ForwardServiceImpl forwardService = serviceUnderTest();
        List<String> reported = List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp");
        for (int round = 0; round < 3; round++) {
            forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(), digests(DRIFTED_DIGEST));
        }

        List<String> notes = forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(),
                digests(PORT + "|tcp|-|" + REMOTE_ADDR));

        assertTrue(notes.isEmpty(), "摘要一致不该有任何动作，实际：" + notes);

        // 再漂移一次：计数已清零，所以这里必须重新删建而不是继续熔断
        List<String> again = forwardService.resyncNodeServices(NODE_ID, reported, new ArrayList<>(),
                digests(DRIFTED_DIGEST));
        assertTrue(again.stream().anyMatch(n -> n.contains("服务重建")),
                "熔断状态已清，漂移要恢复正常处置，实际：" + again);
    }

    // ---------------------------------------------------------------- 工具

    private ForwardServiceImpl serviceUnderTest() throws Exception {
        ForwardServiceImpl forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", mock(ForwardMapper.class));
        ForwardPortService forwardPortService = mock(ForwardPortService.class);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        NodeService nodeService = mock(NodeService.class);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        TunnelService tunnelService = mock(TunnelService.class);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", mock(ChainTunnelService.class));

        ForwardMapper forwardMapper = (ForwardMapper) ReflectionTestUtils.getField(forwardService, "baseMapper");
        when(forwardMapper.selectById(any())).thenReturn(forward());
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(userTunnelService.getOne(any())).thenReturn(null);
        when(forwardPortService.list(any())).thenAnswer(invocation -> List.of(forwardPort()));

        // 固定 3 次阈值 / 5 分钟冷却：下面的"4 轮故事"按老阈值写，改默认值不该改这个故事
        ReflectionTestUtils.setField(forwardService, "driftBreaker", new DriftCircuitBreaker(3, 300_000L));

        registerNode(NODE_ID);
        return forwardService;
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("drift-breaker");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr(REMOTE_ADDR);
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

    private ForwardPort forwardPort() {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(FORWARD_ID);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(PORT);
        return forwardPort;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.5");
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
            JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
            String requestId = message.getString("requestId");
            if (requestId == null) {
                return null;
            }
            CompletableFuture<GostDto> future = pendingRequests.get(requestId);
            if (future != null) {
                GostDto reply = new GostDto();
                reply.setMsg("AddService".equals(message.getString("type")) && addServiceFailure != null
                        ? addServiceFailure : "OK");
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private static Map<String, String> digests(String digest) {
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put(SERVICE_NAME + "_tcp", digest);
        return digests;
    }

    private List<JSONObject> sentMessages() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(NODE_ID), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private List<String> messageTypes() throws Exception {
        List<String> types = new ArrayList<>();
        for (JSONObject message : sentMessages()) {
            types.add(message.getString("type"));
        }
        return types;
    }

    private List<String> deletedServiceNames() throws Exception {
        List<String> names = new ArrayList<>();
        for (JSONObject message : sentMessages()) {
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

    // ---------------------------------------------------------------- 期望变化时的计数口径（round40 真机回归）

    @Test
    void differentExpectedDigestStartsFreshCount() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 900_000L);

        // 第一条漂移：strategy 不一致（重建成功，但节点下一次上报前又出现第二条不同漂移）
        assertEquals(1, breaker.recordDrift("service|2|3_1_0",
                "strategy=round", "strategy=fifo"));
        // 第二条漂移：selector 档位不一致，期望串不同 → 不能累计成「连续 2 次」
        assertEquals(1, breaker.recordDrift("service|2|3_1_0",
                "selector=round|1|600000000000", "selector=round|3|600000000000"));
        assertFalse(breaker.isOpen("service|2|3_1_0"),
                "两次不同的配置修改各自修复成功，不得把独立修复累计成熔断");

        // 同一处漂移真的反复出现（重建没生效）→ 必须继续累计并熔断
        assertEquals(2, breaker.recordDrift("service|2|3_1_0",
                "selector=round|1|600000000000", "selector=round|3|600000000000"));
        assertTrue(breaker.isOpen("service|2|3_1_0"), "同一期望反复漂移才熔断");
    }

    @Test
    void sameExpectedDigestAfterFailureStillTrips() {
        DriftCircuitBreaker breaker = new DriftCircuitBreaker(2, 900_000L);
        assertEquals(1, breaker.recordDrift("relay|2|9_tls", "iface=eth0", "iface=-"));
        assertEquals(2, breaker.recordDrift("relay|2|9_tls", "iface=eth0", "iface=-"));
        assertTrue(breaker.isOpen("relay|2|9_tls"), "同一期望连续失败 2 次必须熔断");

        // 期望换成另一维度（运维改了别的配置）→ 熔断解除、重新从 1 计数
        assertEquals(1, breaker.recordDrift("relay|2|9_tls", "listener=tls", "listener=tcp"));
        assertFalse(breaker.isOpen("relay|2|9_tls"), "换了期望维度应按新漂移重新累计");
    }
}
