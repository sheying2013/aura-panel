package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsDomainGate;
import com.admin.ddns.DdnsForwardApplier;
import com.admin.ddns.DdnsResolver;
import com.admin.ddns.DdnsTarget;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.ddns.entity.DdnsRecord;
import com.admin.ddns.mapper.DdnsRecordMapper;
import com.admin.ddns.service.DdnsService;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.StatisticsFlowService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.CascadeDeletePurger;
import com.admin.service.impl.ForwardServiceImpl;
import com.admin.service.impl.TunnelServiceImpl;
import com.admin.service.impl.UserServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「DDNS 已计划下发」与「删除隧道 / 删除用户」交叉时的确定性回归。
 *
 * <p>被测链路与来源（行号基于 .worktrees/beta 当前内容）：
 * <ul>
 *   <li>TunnelServiceImpl.java:1334-1372 deleteTunnel：:1346-1348 逐条 {@code forwardService.deleteForward(...)}
 *       并把返回值丢掉；随后 :1351 无条件调用 {@code cascadeDeletePurger.purgeTunnelDatabaseRecords(...)}，
 *       隧道级节点清理（:1363 {@code cleanTunnelResources}）只删 {@code <tunnelId>_tls} 与 {@code chains_<tunnelId>}，
 *       删不到按转发命名的 {@code <forwardId>_<userId>_<userTunnelId>_tcp/_udp}。</li>
 *   <li>UserServiceImpl.java:266-284 deleteUser：:277-279 同样丢弃 {@code deleteForward} 的返回值，
 *       :282 无条件 {@code purgeUserDatabaseRecords}，且整条路径没有任何节点侧清理。</li>
 *   <li>CascadeDeletePurger.java:79-107：两个 purge 都是纯本地多表删除，分别在 :81 / :101 删 forward 行。</li>
 *   <li>ForwardServiceImpl.java:1449-1471 deleteForward：:1462 {@code DdnsTargetRenderer.beginDispatch(remoteAddr)}
 *       取不到凭证就 :1463-1465 直接 {@code R.err(...)}，:1525-1526 的端口行 + 转发行删除因此不会执行。</li>
 *   <li>DdnsAddressRegistry.java:102-133 beginTransitionLease（写锁 + transition 标记）与
 *       :163-181 beginDispatch（读锁，等待 2s 拿不到返回 null）；DdnsService.java:489-537 apply
 *       在整段「规划 + 逐节点下发 + 落库」期间持有该写锁。</li>
 * </ul>
 *
 * <p>本类锁死的事实：DDNS 切换一旦进入下发（窗口已开、目标已按新地址规划好），
 * 并发的 deleteTunnel / deleteUser 里那条 deleteForward 会被闸门拒绝，但调用方不看返回值，
 * 于是库行照删、接口照回成功、节点上按转发命名的服务与它刚拿到的新地址原样留着 ——
 * 库里再没有任何记录能对账出这条服务（NodeOrphanSweeper 之外无人认领）。
 * 两条控制用例证明该信号不是空断言（无窗口时同一路径确实会下发 DeleteService），
 * 一条对照用例证明闸门是当前唯一挡住「先规划、后删除」的东西。
 */
class CascadeDdnsConcurrencyTest {

    private static final String DOMAIN = "app.example.com";
    private static final String REMOTE_ADDR = DOMAIN + ":8080";
    private static final String OLD_IP = "1.1.1.1";
    private static final String NEW_IP = "3.3.3.3";
    private static final String RENDERED_NEW_ADDR = NEW_IP + ":8080";
    private static final long RECORD_ID = 1L;
    private static final long FORWARD_ID = 21L;
    private static final int USER_ID = 7;
    private static final long TUNNEL_ID = 16L;
    private static final long NODE_ID = 51L;
    /** 与 ForwardServiceImpl.buildServiceName 同口径：forwardId + "_" + userId + "_" + userTunnelId（管理员属主为 0）。 */
    private static final String SERVICE_NAME = FORWARD_ID + "_" + USER_ID + "_0";

    /** 发往节点的原始报文（DDNS 走假 applier，不产生报文；这里只有 GostUtil 的真实报文）。 */
    private final List<String> sent = new CopyOnWriteArrayList<>();
    /** 库写动作，按发生顺序。 */
    private final List<String> dbEvents = new CopyOnWriteArrayList<>();

    private final List<WebSocketSession> sessions = new ArrayList<>();
    private final BlockingApplier applier = new BlockingApplier();

    private DdnsAddressRegistry registry;
    private DdnsRecord record;
    private DdnsService ddnsService;
    private ExecutorService ddnsExecutor;

    private ForwardMapper forwardMapper;
    private TunnelMapper tunnelMapper;
    private UserMapper userMapper;
    private ForwardPortService forwardPortService;
    private ChainTunnelService chainTunnelService;
    private UserTunnelService userTunnelService;
    private SpeedLimitService speedLimitService;
    private StatisticsFlowService statisticsFlowService;
    private NodeService nodeService;
    private UserService userService;

    private ForwardServiceImpl forwardService;
    private TunnelServiceImpl tunnelService;
    private UserServiceImpl userServiceBean;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        sent.clear();
        dbEvents.clear();

        // 构造即把真实闸门（render / transitionProbe / dispatch）装到 DdnsTargetRenderer 上
        registry = new DdnsAddressRegistry();
        registry.put(DOMAIN, OLD_IP);

        forwardMapper = mock(ForwardMapper.class);
        tunnelMapper = mock(TunnelMapper.class);
        userMapper = mock(UserMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        speedLimitService = mock(SpeedLimitService.class);
        statisticsFlowService = mock(StatisticsFlowService.class);
        nodeService = mock(NodeService.class);
        userService = mock(UserService.class);

        // 库夹具：一条 forward(21) 属于 user(7)，挂在 tunnel(16) 上，入口节点 51，目标地址是 DOMAIN:8080
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> forward());
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(forward())));
        when(forwardMapper.deleteById(any())).thenAnswer(invocation -> {
            dbEvents.add("forward-delete:" + invocation.getArgument(0));
            return 1;
        });
        when(forwardMapper.delete(any())).thenAnswer(invocation -> {
            dbEvents.add("forward-purge");
            return 1;
        });
        when(tunnelMapper.selectById(any())).thenReturn(tunnel());
        when(tunnelMapper.deleteById(any())).thenAnswer(invocation -> {
            dbEvents.add("tunnel-delete:" + invocation.getArgument(0));
            return 1;
        });
        when(userMapper.selectById(any())).thenAnswer(invocation -> user());
        when(userMapper.deleteById(any())).thenAnswer(invocation -> {
            dbEvents.add("user-delete:" + invocation.getArgument(0));
            return 1;
        });
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>(List.of(entry(NODE_ID))));
        when(chainTunnelService.remove(any())).thenReturn(true);
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>(List.of(entryPort())));
        when(forwardPortService.getOne(any(), anyBoolean())).thenReturn(entryPort());
        when(forwardPortService.remove(any())).thenReturn(true);
        when(userTunnelService.getOne(any())).thenReturn(null);
        when(userTunnelService.getOne(any(), anyBoolean())).thenReturn(null);
        when(userTunnelService.remove(any())).thenReturn(true);
        when(speedLimitService.list(any())).thenReturn(new ArrayList<>());
        when(speedLimitService.remove(any())).thenReturn(true);
        when(statisticsFlowService.remove(any())).thenReturn(true);
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(userService.getById(any())).thenAnswer(invocation -> {
            // 操作者(sub=1)在库里是 role_id=0 的管理员；id=7 才是被删的普通账号。
            Object rawId = invocation.getArgument(0);
            if (rawId instanceof Number && ((Number) rawId).longValue() == 1L) {
                User admin = new User();
                admin.setId(1L);
                admin.setRoleId(0);
                return admin;
            }
            return user();
        });

        // 真实删除链路：ForwardServiceImpl / TunnelServiceImpl / UserServiceImpl 共用同一条真实级联清理
        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);

        tunnelService = new TunnelServiceImpl();
        ReflectionTestUtils.setField(tunnelService, "baseMapper", tunnelMapper);
        ReflectionTestUtils.setField(tunnelService, "forwardService", forwardService);
        ReflectionTestUtils.setField(tunnelService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(tunnelService, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);

        CascadeDeletePurger purger = new CascadeDeletePurger();
        ReflectionTestUtils.setField(purger, "forwardService", forwardService);
        ReflectionTestUtils.setField(purger, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(purger, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(purger, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(purger, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(purger, "statisticsFlowService", statisticsFlowService);
        ReflectionTestUtils.setField(purger, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(purger, "userService", userServiceBean = newUserServiceBean(purger));

        ReflectionTestUtils.setField(tunnelService, "cascadeDeletePurger", purger);

        // DDNS 侧：真实 DdnsService.apply + 真实闸门，只有解析结果与节点下发是替身
        record = ddnsRecord();
        DdnsRecordMapper ddnsRecordMapper = mock(DdnsRecordMapper.class);
        when(ddnsRecordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(ddnsRecordMapper.updateById(any())).thenReturn(1);
        DdnsResolver resolver = mock(DdnsResolver.class);
        when(resolver.resolve(DOMAIN)).thenReturn(List.of(NEW_IP));
        ddnsService = new DdnsService(ddnsRecordMapper, registry, resolver, applier,
                forwardService, tunnelService, chainTunnelService, nodeService,
                forwardPortService, userTunnelService, userService);
        ddnsExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ddns-check");
            thread.setDaemon(true);
            return thread;
        });

        registerNode(NODE_ID);
        authenticate();
    }

    @AfterEach
    void tearDown() {
        applier.release.countDown();
        if (ddnsExecutor != null) {
            ddnsExecutor.shutdownNow();
        }
        for (WebSocketSession session : sessions) {
            nodeSessions.clear();
        }
        nodeSessions.clear();
        pendingRequests.clear();
        RequestContextHolder.resetRequestAttributes();
        DdnsTargetRenderer.reset();
    }

    // ===================== 竞态本体 =====================

    @Test
    void deleteTunnelSkipsForwardServiceAfterDdnsPlannedDispatch() throws Exception {
        CompletableFuture<R> ddns = startDdns();
        awaitDispatchStarted();

        R deleted = tunnelService.deleteTunnel(TUNNEL_ID);

        assertTrue(deleted.getCode() != 0, "切换期间应中止删除：" + deleted.getMsg());
        assertNotNull(registry.activeTransition());
        assertFalse(sentServices().contains(SERVICE_NAME + "_tcp"));
        assertFalse(dbEvents.contains("forward-delete:" + FORWARD_ID));
        assertFalse(dbEvents.contains("forward-purge"), "锁不可用时禁止级联删除：" + dbEvents);
        verify(forwardPortService, never()).remove(any());
        assertFalse(dbEvents.contains("tunnel-delete:" + TUNNEL_ID), "须保留隧道：" + dbEvents);

        applier.release.countDown();
        assertEquals(0, ddns.get(10, TimeUnit.SECONDS).getCode(), "DDNS 切换本身应成功");

        // 删除中止后，原转发和隧道保留，DDNS 可正常完成切换。
        assertEquals(List.of(SERVICE_NAME + "@" + RENDERED_NEW_ADDR), applier.updates,
                "DDNS 应更新仍保留的转发服务");
        assertEquals(NEW_IP, registry.get(DOMAIN), "注册表已确认新地址");
        assertEquals(NEW_IP, record.getPrimaryIp(), "DDNS 记录已落库为新主地址");
        verify(forwardMapper, never()).deleteById(any());
        assertTrue(sentServices().isEmpty() || sentServices().stream().noneMatch(s -> s.startsWith(SERVICE_NAME)),
                "节点上按转发命名的服务从头到尾没收到删除：" + sentServices());
        assertFalse(sentServices().contains("" + TUNNEL_ID + "_tls"),
                "删除中止时也不应清理隧道服务：" + sentServices());
    }

    @Test
    void deleteUserSkipsForwardServiceAfterDdnsPlannedDispatch() throws Exception {
        CompletableFuture<R> ddns = startDdns();
        awaitDispatchStarted();

        R deleted = userServiceBean.deleteUser((long) USER_ID);

        assertTrue(deleted.getCode() != 0, "切换期间应中止删除：" + deleted.getMsg());
        assertNotNull(registry.activeTransition());
        assertFalse(dbEvents.contains("forward-purge"), "禁止级联删除：" + dbEvents);
        assertFalse(dbEvents.contains("user-delete:" + USER_ID), "须保留用户：" + dbEvents);
        verify(forwardMapper, never()).deleteById(any());
        assertTrue(sentServices().isEmpty(), "deleteUser 这条路径根本没有节点侧清理：" + sentServices());

        applier.release.countDown();
        assertEquals(0, ddns.get(10, TimeUnit.SECONDS).getCode());
        assertEquals(List.of(SERVICE_NAME + "@" + RENDERED_NEW_ADDR), applier.updates);
        assertEquals(NEW_IP, registry.get(DOMAIN));
        assertEquals(NEW_IP, record.getPrimaryIp());
    }

    // ===================== 控制组：证明信号不是空断言 =====================

    @Test
    void deleteTunnelWithoutSwitchWindowClearsForwardServiceOnNode() throws Exception {
        R deleted = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(0, deleted.getCode(), deleted.getMsg());
        assertTrue(sentServices().contains(SERVICE_NAME + "_tcp," + SERVICE_NAME + "_udp"),
                "无窗口时同一条路径确实会下发按转发命名的 DeleteService：" + sentServices());
        assertTrue(dbEvents.contains("forward-delete:" + FORWARD_ID), dbEvents.toString());
        assertTrue(dbEvents.contains("forward-purge"), dbEvents.toString());
        assertTrue(dbEvents.contains("tunnel-delete:" + TUNNEL_ID), dbEvents.toString());
        assertTrue(sentCallTypes().contains("DeleteChains"), "隧道级 chains 清理照旧：" + sentCallTypes());
        // 控制组：端口行被删两次 —— deleteForwardInWindow 一次、级联清理一次；被拒时只会有级联那一次
        verify(forwardPortService, times(2)).remove(any());
    }

    @Test
    void deleteUserWithoutSwitchWindowClearsForwardServiceOnNode() throws Exception {
        R deleted = userServiceBean.deleteUser((long) USER_ID);

        assertEquals(0, deleted.getCode(), deleted.getMsg());
        assertTrue(sentServices().contains(SERVICE_NAME + "_tcp," + SERVICE_NAME + "_udp"),
                "无窗口时删除用户照样会先删节点上的转发服务：" + sentServices());
        assertTrue(dbEvents.contains("forward-delete:" + FORWARD_ID), dbEvents.toString());
        assertTrue(dbEvents.contains("user-delete:" + USER_ID), dbEvents.toString());
    }

    // ===================== 对照：闸门就是唯一挡住交叉的东西 =====================

    @Test
    void withoutTheDispatchGateTheDeleteRunsStraightIntoTheDispatch() throws Exception {
        // 等价于「没有 beginDispatch 这道闸门」的实现（ForwardServiceImpl.java:1462 之前的历史行为）
        DdnsTargetRenderer.installDispatch(addr -> DdnsDomainGate.Lease.NONE);

        CompletableFuture<R> ddns = startDdns();
        awaitDispatchStarted();

        R deleted = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(0, deleted.getCode(), deleted.getMsg());
        assertTrue(sentServices().contains(SERVICE_NAME + "_tcp," + SERVICE_NAME + "_udp"),
                "没有闸门时删除会直接插进正在推进的切换里，删掉同一条服务：" + sentServices());
        assertTrue(dbEvents.contains("forward-delete:" + FORWARD_ID), dbEvents.toString());

        applier.release.countDown();
        assertEquals(0, ddns.get(10, TimeUnit.SECONDS).getCode());
        assertEquals(NEW_IP, registry.get(DOMAIN), "切换仍会把新地址写进注册表，而这条服务已被删除");
    }

    // ===================== 脚手架 =====================

    private CompletableFuture<R> startDdns() {
        return CompletableFuture.supplyAsync(() -> ddnsService.checkNow(RECORD_ID), ddnsExecutor);
    }

    private void awaitDispatchStarted() throws InterruptedException {
        assertTrue(applier.dispatchStarted.await(5, TimeUnit.SECONDS),
                "DDNS 应已抢到切换窗口、规划完目标并开始下发");
        assertNotNull(registry.activeTransition(), "前置：切换窗口处于打开状态");
        assertEquals(DOMAIN, registry.activeTransition().domain());
    }

    private UserServiceImpl newUserServiceBean(CascadeDeletePurger purger) {
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        ReflectionTestUtils.setField(service, "forwardService", forwardService);
        ReflectionTestUtils.setField(service, "cascadeDeletePurger", purger);
        return service;
    }

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":1,\"role_id\":0,\"name\":\"admin\"}"
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private DdnsRecord ddnsRecord() {
        DdnsRecord entity = new DdnsRecord();
        entity.setId(RECORD_ID);
        entity.setDomain(DOMAIN);
        entity.setPrimaryIp(OLD_IP);
        entity.setResolvedIps(OLD_IP);
        entity.setEnabled(1);
        entity.setIntervalSeconds(60);
        entity.setFailCount(0);
        return entity;
    }

    private static Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(USER_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setName("cascade-race");
        forward.setStatus(1);
        forward.setRemoteAddr(REMOTE_ADDR);
        forward.setStrategy("round");
        return forward;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("tunnel-16");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private static User user() {
        User user = new User();
        user.setId((long) USER_ID);
        user.setRoleId(1);
        user.setUser("worker");
        return user;
    }

    private static ChainTunnel entry(long nodeId) {
        ChainTunnel entry = new ChainTunnel();
        entry.setTunnelId(TUNNEL_ID);
        entry.setChainType(1);
        entry.setNodeId(nodeId);
        return entry;
    }

    private static ForwardPort entryPort() {
        ForwardPort port = new ForwardPort();
        port.setForwardId(FORWARD_ID);
        port.setNodeId(NODE_ID);
        port.setPort(20000);
        return port;
    }

    private static Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + (nodeId % 100));
        node.setPort("20000-20010");
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
            sent.add(payload);
            JSONObject message = JSON.parseObject(payload);
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto reply = new GostDto();
                reply.setMsg("OK");
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        sessions.add(session);
        nodeSessions.put(nodeId, session);
    }

    private List<String> sentCallTypes() {
        List<String> callTypes = new ArrayList<>();
        for (String payload : sent) {
            callTypes.add(JSON.parseObject(payload).getString("type"));
        }
        return callTypes;
    }

    /** 每条 DeleteService 报文里的服务名，按逗号拼接，便于对「删了哪些名字」做整体断言。 */
    private List<String> sentServices() {
        List<String> services = new ArrayList<>();
        for (String payload : sent) {
            JSONObject message = JSON.parseObject(payload);
            if (!"DeleteService".equals(message.getString("type"))) {
                continue;
            }
            JSONArray array = message.getJSONObject("data").getJSONArray("services");
            List<String> names = new ArrayList<>();
            for (Object name : array) {
                names.add(String.valueOf(name));
            }
            services.add(String.join(",", names));
        }
        return Collections.unmodifiableList(services);
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /** 假的节点下发：记录被下发的服务名与渲染结果，第一条目标就卡住，让调用方在窗口内并发删除。 */
    private static final class BlockingApplier implements DdnsForwardApplier {

        private final List<String> updates = new CopyOnWriteArrayList<>();
        private final CountDownLatch dispatchStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean isOnline(Long nodeId) {
            return true;
        }

        @Override
        public String update(DdnsTarget target) {
            updates.add(target.serviceName() + "@" + String.join(",", target.renderedTargets()));
            if (SERVICE_NAME.equals(target.serviceName())) {
                dispatchStarted.countDown();
                try {
                    // 卡住下发，模拟「已计划、正在写节点」的那段时间；窗口由调用线程持有着
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "OK";
        }
    }
}
