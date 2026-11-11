package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.config.ForwardPortUniqueMigration;
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
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * forward_port 端口并发分配的回归测试（round52）。
 *
 * <p>覆盖的原缺陷：createForward 分配端口是「查空闲端口 → 写 forward_port」两步，中间没有任何互斥。
 * 两个并发创建指向同一个入口节点时各自都看到 30201 可用，于是两条转发把同一个 (node_id, port) 写进库；
 * 节点上只有一条真能监听成功，另一条面板显示正常却永远连不通，而且这条脏行会一直占着该端口，
 * 后续自动分配永远分不到它（schema.sql 里 forward_port 当时连唯一索引都没有）。
 *
 * <p>这里锁死的行为：
 * 1. 启动迁移：历史数据干净时建 uk_forward_port_node_port；有重复只告警跳过（绝不擅自删数据）；
 *    探针本身失败也不能拦住启动；
 * 2. 同一入口节点的并发创建必须各自拿到不同端口（节点级端口锁把「查 → 写」串起来）；
 * 3. 两个请求的入口节点集合重叠时按 nodeId 升序取锁，不会互等死锁；
 * 4. 万一仍撞上唯一索引（多实例部署），端口写入把 SQL 冲突翻译成"请重试"，而不是把 SQL 原文丢给用户。
 */
class ForwardPortConcurrencyTest {

    private static final long NODE_ID = 101L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    /** 10 个候选端口：两个并发创建只需要其中两个，够用且能看出是否分到同一个。 */
    private static final String NODE_PORT_RANGE = "30201-30210";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private ChainTunnelService chainTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, ?> pendingRequests;
    /** 库里的 forward_port 行：save 时按 (node_id, port) 查重，模拟 uk_forward_port_node_port 唯一索引。 */
    private final List<ForwardPort> storedPorts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong forwardIds = new AtomicLong();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, ?>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        storedPorts.clear();
        forwardIds.set(0);
        authenticate();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        TunnelService tunnelService = mock(TunnelService.class);

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

        // 每条转发一个自增 id：并发创建拿到不同 forward_id，端口占用查询才不会把别人的行当成自己的
        when(forwardMapper.insert(any(Forward.class))).thenAnswer(invocation -> {
            ((Forward) invocation.getArgument(0)).setId(forwardIds.incrementAndGet());
            return 1;
        });
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> forward());
        when(forwardMapper.updateById(any(Forward.class))).thenReturn(1);
        when(forwardMapper.deleteById(any(Serializable.class))).thenReturn(1);
        saveEnforcesUniqueIndex();
        when(forwardPortService.updateById(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        // getNodePort 用 list(QueryWrapper) 查该节点已被占用的端口，这里直接回库里的行
        when(forwardPortService.list(any())).thenAnswer(invocation -> snapshotPorts());
        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainTunnels());
        when(nodeService.getById(any())).thenAnswer(invocation -> node());

        registerNode();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
        storedPorts.clear();
    }

    // ========== 启动迁移 ==========

    @Test
    void migrationCreatesUniqueIndexWhenHistoryIsClean() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        migration(jdbcTemplate).run(null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());
        String ddl = sql.getValue();
        assertTrue(ddl.contains("CREATE UNIQUE INDEX IF NOT EXISTS"), ddl);
        assertTrue(ddl.contains("uk_forward_port_node_port"), ddl);
        assertTrue(ddl.contains("ON forward_port(node_id, port)"), ddl);
        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    void migrationSkipsIndexWhenHistoryHasDuplicatePorts() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(sampleDuplicates());

        assertDoesNotThrow(() -> migration(jdbcTemplate).run(null));

        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate).queryForList(anyString());
    }

    /** 探针本身抛错（库被锁、表还没建）不能把启动拦下来：端口并发兜底还有进程内的节点端口锁。 */
    @Test
    void migrationDoesNotBlockStartupWhenProbeThrows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new RuntimeException("SQLITE_BUSY: database is locked"));

        assertDoesNotThrow(() -> migration(jdbcTemplate).run(null));

        verify(jdbcTemplate, never()).execute(anyString());
    }

    /** 有重复时采样查询自己失败，也不能改变"跳过建索引"这个决定。 */
    @Test
    void migrationKeepsAlarmingWhenDuplicateSamplingFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString())).thenThrow(new RuntimeException("no such column: c"));

        assertDoesNotThrow(() -> migration(jdbcTemplate).run(null));

        verify(jdbcTemplate, never()).execute(anyString());
    }

    // ========== 并发分配端口 ==========

    @Test
    void concurrentCreatesOnSameEntryNodeGetDistinctPorts() throws Exception {
        int racers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(racers, daemonThreads());
        CyclicBarrier startTogether = new CyclicBarrier(racers);
        try {
            List<Future<R>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    // RequestContextHolder 是 ThreadLocal：工作线程必须自己放一份请求上下文，
                    // 否则拿不到 JWT，createForward 会在取当前用户时就 NPE
                    authenticate();
                    startTogether.await(10, TimeUnit.SECONDS);
                    return forwardService.createForward(autoPortForwardDto("race-" + index));
                }));
            }
            List<R> results = new ArrayList<>();
            for (Future<R> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            for (R result : results) {
                assertEquals(0, result.getCode(), "同一入口节点的并发创建不应失败：" + result.getMsg());
            }
        } finally {
            pool.shutdownNow();
        }

        List<Integer> ports = snapshotPorts().stream()
                .map(ForwardPort::getPort)
                .sorted()
                .collect(Collectors.toList());
        assertEquals(racers, ports.size(), "两次创建都应写进 forward_port：" + ports);
        assertEquals(racers, new HashSet<>(ports).size(), "同一入口节点的端口必须互不相同：" + ports);
    }

    /**
     * 两个请求的入口节点集合重叠（{1,2} 与 {2,1}）时，若各自按自己的顺序取锁就会互等死锁。
     * 锁按 nodeId 升序获取，所以两边都只能排队：能按时返回就说明没成环。
     */
    @Test
    void nodePortLocksAreTakenInNodeIdOrderSoOverlappingCreatesCannotDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2, daemonThreads());
        try {
            Future<Boolean> ascending = pool.submit(() -> lockThenUnlock(List.of(1L, 2L)));
            Future<Boolean> descending = pool.submit(() -> lockThenUnlock(List.of(2L, 1L)));
            assertTrue(ascending.get(10, TimeUnit.SECONDS), "升序取锁的请求应立刻拿到锁");
            assertTrue(descending.get(10, TimeUnit.SECONDS), "逆序请求的节点集合也必须能按升序取到锁");
        } finally {
            pool.shutdownNow();
        }
    }

    // ========== 唯一索引冲突的落地处理 ==========

    @Test
    void uniqueConflictOnPortSaveIsReportedAsRetryableConflict() {
        when(forwardPortService.save(any(ForwardPort.class))).thenThrow(new RuntimeException(
                "### Error updating database. Cause: java.sql.SQLException: "
                        + "UNIQUE constraint failed: forward_port.node_id, forward_port.port"));

        String error = saveForwardPort(30201);

        assertNotNull(error);
        assertTrue(error.contains("30201"), error);
        assertTrue(error.contains("并发分配冲突"), error);
        assertTrue(error.contains("重试"), error);
        assertFalse(error.contains("UNIQUE constraint failed"), "不能把 SQL 原文丢给用户：" + error);
    }

    @Test
    void nonUniqueSaveFailureKeepsOriginalReason() {
        when(forwardPortService.save(any(ForwardPort.class)))
                .thenThrow(new RuntimeException("SQLITE_BUSY: database is locked"));

        String error = saveForwardPort(30201);

        assertNotNull(error);
        assertTrue(error.contains("database is locked"), error);
        assertFalse(error.contains("并发分配冲突"), "不是唯一冲突就不能说成端口被占：" + error);
    }

    @Test
    void saveReturningFalseMeansPortRecordWasNotPersisted() {
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(false);

        String error = saveForwardPort(30201);

        assertNotNull(error, "save 返回 false 不代表已落库，必须报错");
        assertTrue(error.contains("端口记录保存失败"), error);
    }

    // ========== 夹具 ==========

    /** 模拟数据库唯一索引：同一个 (node_id, port) 第二次写入必然抛 UNIQUE 冲突。 */
    private void saveEnforcesUniqueIndex() {
        when(forwardPortService.save(any(ForwardPort.class))).thenAnswer(invocation -> {
            ForwardPort row = invocation.getArgument(0);
            synchronized (storedPorts) {
                for (ForwardPort existing : storedPorts) {
                    if (Objects.equals(existing.getNodeId(), row.getNodeId())
                            && Objects.equals(existing.getPort(), row.getPort())) {
                        throw new RuntimeException("### Error updating database. Cause: java.sql.SQLException: "
                                + "UNIQUE constraint failed: forward_port.node_id, forward_port.port");
                    }
                }
                storedPorts.add(row);
            }
            return true;
        });
    }

    private List<ForwardPort> snapshotPorts() {
        synchronized (storedPorts) {
            return new ArrayList<>(storedPorts);
        }
    }

    private String saveForwardPort(int port) {
        ForwardPort row = new ForwardPort();
        row.setForwardId(99L);
        row.setNodeId(NODE_ID);
        row.setPort(port);
        return ReflectionTestUtils.invokeMethod(forwardService, "saveForwardPort", row, NODE_ID);
    }

    private static boolean lockThenUnlock(List<Long> nodeIds) throws Exception {
        Method lockNodePorts = ForwardServiceImpl.class.getDeclaredMethod("lockNodePorts", List.class);
        lockNodePorts.setAccessible(true);
        List<?> locks = (List<?>) lockNodePorts.invoke(null, nodeIds);
        assertNotNull(locks);
        // 必须在同一个线程里按逆序释放，否则 ReentrantLock 会抛 IllegalMonitorStateException
        Method unlockNodePorts = ForwardServiceImpl.class.getDeclaredMethod("unlockNodePorts", List.class);
        unlockNodePorts.setAccessible(true);
        unlockNodePorts.invoke(null, locks);
        return true;
    }

    private static java.util.concurrent.ThreadFactory daemonThreads() {
        return runnable -> {
            Thread thread = new Thread(runnable, "forward-port-race");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ForwardPortUniqueMigration migration(JdbcTemplate jdbcTemplate) {
        ForwardPortUniqueMigration migration = new ForwardPortUniqueMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbcTemplate);
        return migration;
    }

    private static List<Map<String, Object>> sampleDuplicates() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("node_id", NODE_ID);
        row.put("port", 30201);
        row.put("c", 2);
        rows.add(row);
        return rows;
    }

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static ForwardDto autoPortForwardDto(String name) {
        ForwardDto dto = new ForwardDto();
        dto.setName(name);
        dto.setTunnelId(TUNNEL_ID);
        dto.setRemoteAddr("10.10.10.10:8080");
        dto.setStrategy("round");
        return dto;
    }

    private static Forward forward() {
        Forward forward = new Forward();
        forward.setId(1L);
        forward.setName("forward-test");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("10.10.10.10:8080");
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    /** 每次都给一份新的行：get_port 会把选中的端口写回 ChainTunnel，共用实例会让并发调用互相踩。 */
    private static List<ChainTunnel> chainTunnels() {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId((long) TUNNEL_ID);
        chainTunnel.setChainType(1);
        chainTunnel.setNodeId(NODE_ID);
        List<ChainTunnel> rows = new ArrayList<>();
        rows.add(chainTunnel);
        return rows;
    }

    private static Node node() {
        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("node-101");
        node.setServerIp("10.0.0.101");
        node.setPort(NODE_PORT_RANGE);
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setStatus(1);
        return node;
    }

    @SuppressWarnings("unchecked")
    private void registerNode() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + NODE_ID);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
            java.util.concurrent.CompletableFuture<GostDto> future =
                    ((Map<String, java.util.concurrent.CompletableFuture<GostDto>>) pendingRequests)
                            .get(message.getString("requestId"));
            if (future != null) {
                GostDto gostDto = new GostDto();
                gostDto.setMsg("OK");
                future.complete(gostDto);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(NODE_ID, session);
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
