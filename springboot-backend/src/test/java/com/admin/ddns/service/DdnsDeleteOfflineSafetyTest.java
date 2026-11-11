package com.admin.ddns.service;

import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsForwardApplier;
import com.admin.ddns.DdnsResolver;
import com.admin.ddns.DdnsTarget;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.ddns.entity.DdnsRecord;
import com.admin.ddns.mapper.DdnsRecordMapper;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「删除 DDNS 记录时入口节点未确认回落」的行为测试（全离线：数据库、DNS、节点下发全部是 mock，
 * 不连任何服务器，也不解析真实 DNS）。
 *
 * <p><b>被验证的审计推断</b>：{@link DdnsService#delete(Long)} 对每个入口节点只把失败计数加一
 * （DdnsService.java:254-259：{@code if (!applier.isOnline(...) || !isOk(safeUpdate(...))) failed++;}），
 * 随后无条件执行 {@code registry.remove(domain)}（第 260 行）与
 * {@code ddnsRecordMapper.deleteById(id)}（第 263 行）。节点离线时连 {@code update} 都不会发；
 * 下发失败时 {@code isOk} 为 false —— 两种情况都只影响返回值文案
 * （"已删除；N 个入口节点未确认回落…"，第 276-278 行），记录与渲染映射照样被删除。
 *
 * <p><b>危害不是「少一条日志」</b>：删除后注册表里没有这个域名、库里也没有这条记录，
 * 节点上却仍固定着删除前的旧 IP，下一次服务上报没有可对账的持久状态，也没有第二次回落机会。
 *
 * <p><b>本类断言的是期望行为</b>：未确认（节点离线 / 下发失败）时必须保留记录与 registry 映射，
 * 并返回错误；只有全部入口节点确认回落后才允许删除。第三种场景是全确认对照组，
 * 期望在修复前后都是绿灯 —— 用来证明红灯只来自「未确认也照删」，不是夹具或假库的副作用。
 *
 * <p><b>假库语义</b>：{@code deleteById} 按 MyBatis-Plus 口径返回受影响行数并真的删行；
 * {@code selectById/selectList} 返回行副本；{@code updateById} 只合并非空字段。
 *
 * <p><b>实测记录</b>（2026-11-11，JDK 21 + Maven 3.9.9，离线只跑本类；命令与原始输出见文件末尾注释）：
 * 未修复的当前实现下 <b>2 红 1 绿</b> —— 离线与下发失败两个场景各 4 条断言全红，
 * 全确认对照组全绿。红灯来自「未确认也照删」，不是夹具副作用。
 */
class DdnsDeleteOfflineSafetyTest {

    private static final String DOMAIN = "app.example.com";
    private static final long ID = 7L;
    private static final long NODE_ID = 1L;
    private static final String OLD_IP = "1.1.1.1";
    private static final String SERVICE_NAME = "21_1_0";

    /** 内存里的 ddns_record 行。 */
    private final Map<Long, DdnsRecord> rows = new ConcurrentHashMap<>();
    /** 交叉事件日志：db:delete / db:update / push。 */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private DdnsRecordMapper mapper;
    private DdnsAddressRegistry registry;
    private DdnsResolver resolver;
    private DdnsForwardApplier applier;
    private ForwardService forwardService;
    private TunnelService tunnelService;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private ForwardPortService forwardPortService;
    private UserTunnelService userTunnelService;
    private UserService userService;
    private DdnsService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DdnsRecordMapper.class);
        registry = new DdnsAddressRegistry();
        resolver = mock(DdnsResolver.class);
        applier = mock(DdnsForwardApplier.class);
        forwardService = mock(ForwardService.class);
        tunnelService = mock(TunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        forwardPortService = mock(ForwardPortService.class);
        userTunnelService = mock(UserTunnelService.class);
        userService = mock(UserService.class);
        service = new DdnsService(mapper, registry, resolver, applier, forwardService,
                tunnelService, chainTunnelService, nodeService, forwardPortService, userTunnelService,
                userService);

        when(mapper.selectById(any())).thenAnswer(invocation -> copy(rows.get((Long) invocation.getArgument(0))));
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            List<DdnsRecord> snapshot = new ArrayList<>();
            synchronized (rows) {
                for (DdnsRecord row : rows.values()) {
                    snapshot.add(copy(row));
                }
            }
            snapshot.sort(Comparator.comparing(DdnsRecord::getId));
            return snapshot;
        });
        when(mapper.updateById(any(DdnsRecord.class))).thenAnswer(invocation -> {
            DdnsRecord incoming = invocation.getArgument(0);
            synchronized (rows) {
                DdnsRecord current = rows.get(incoming.getId());
                if (current == null) {
                    return 0;
                }
                mergeNonNull(current, incoming);
                events.add("db:update:" + incoming.getId() + ":primary=" + current.getPrimaryIp());
                return 1;
            }
        });
        when(mapper.deleteById(any())).thenAnswer(invocation -> {
            Long id = (Long) invocation.getArgument(0);
            events.add("db:delete:" + id);
            return rows.remove(id) == null ? 0 : 1;
        });

        when(applier.isOnline(anyLong())).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenAnswer(invocation -> {
            DdnsTarget target = invocation.getArgument(0);
            String payload = target.hasRenderedTargets()
                    ? String.join(",", target.renderedTargets())
                    : "registry:" + registry.get(DOMAIN);
            events.add("push:" + target.serviceName() + "@" + payload);
            return "OK";
        });
        when(userService.getById(anyInt())).thenReturn(user(1));
    }

    @AfterEach
    void tearDown() {
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("入口节点离线（连下发都没发）：必须保留记录与映射，不能把节点留在旧 IP 上失去对账依据")
    void offlineEntryNodeKeepsRecordAndMapping() {
        seed(OLD_IP);
        loadRegistryLikeStartup(OLD_IP);
        oneForwardWithOneEntryNode();
        when(applier.isOnline(NODE_ID)).thenReturn(false);

        R response = service.delete(ID);

        assertAll("节点离线=未确认回落，删除必须失败并原样保留状态",
                () -> assertNotEquals(0, response.getCode(),
                        "未确认回落时 delete 不应返回成功：" + actual()),
                () -> assertTrue(rows.containsKey(ID),
                        "记录必须留在库里，否则没有任何持久状态能促成下次对账：" + actual()),
                () -> assertEquals(1, registry.knownSize(),
                        "域名必须继续被登记（knownDomains），否则注册表也失去这条域名：" + actual()),
                () -> assertEquals(OLD_IP, registry.get(DOMAIN),
                        "已确认的旧映射必须保留，后续渲染/对账才有依据：" + actual()),
                () -> assertTrue(pushes().isEmpty(),
                        "节点离线时不应下发任何内容（本次连 update 都没调用）：" + actual()));
    }

    @Test
    @DisplayName("节点在线但回落下发失败：同样未确认，必须保留记录与映射并返回错误")
    void failedRollbackUpdateKeepsRecordAndMapping() {
        seed(OLD_IP);
        loadRegistryLikeStartup(OLD_IP);
        oneForwardWithOneEntryNode();
        when(applier.isOnline(NODE_ID)).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenAnswer(invocation -> {
            DdnsTarget target = invocation.getArgument(0);
            events.add("push:" + target.serviceName() + "@" + String.join(",", target.renderedTargets()));
            return "service " + SERVICE_NAME + "_tcp not found";
        });

        R response = service.delete(ID);

        assertAll("下发失败=未确认回落，删除必须失败并原样保留状态",
                () -> assertNotEquals(0, response.getCode(),
                        "未确认回落时 delete 不应返回成功：" + actual()),
                () -> assertTrue(rows.containsKey(ID),
                        "记录必须留在库里，否则节点上的旧 IP 再也没有对账来源：" + actual()),
                () -> assertEquals(1, registry.knownSize(),
                        "域名必须继续被登记（knownDomains）：" + actual()),
                () -> assertEquals(OLD_IP, registry.get(DOMAIN),
                        "已确认的旧映射必须保留：" + actual()),
                () -> assertEquals(List.of("push:" + SERVICE_NAME + "@" + DOMAIN + ":8080"), pushes(),
                        "确实尝试过一次回落到域名（不是回落到旧 IP），但节点返回失败：" + actual()));
    }

    @Test
    @DisplayName("对照组：唯一入口节点确认回落，才允许删除记录并摘除映射")
    void confirmedFallbackDeletesRecordAndMapping() {
        seed(OLD_IP);
        loadRegistryLikeStartup(OLD_IP);
        oneForwardWithOneEntryNode();
        when(applier.isOnline(NODE_ID)).thenReturn(true);
        when(applier.update(any(DdnsTarget.class))).thenAnswer(invocation -> {
            DdnsTarget target = invocation.getArgument(0);
            events.add("push:" + target.serviceName() + "@" + String.join(",", target.renderedTargets()));
            return "OK";
        });

        R response = service.delete(ID);

        assertAll("全部确认后删除，节点已回落到域名",
                () -> assertEquals(0, response.getCode(), "确认回落后 delete 应成功：" + actual()),
                () -> assertEquals("已删除，节点已回落到域名", response.getData(),
                        "文案应说明节点已确认回落，而不是含糊的成功：" + actual()),
                () -> assertFalse(rows.containsKey(ID), "确认后记录应被删除：" + actual()),
                () -> assertEquals(0, registry.knownSize(), "记录没了，knownDomains 应清空：" + actual()),
                () -> assertNull(registry.get(DOMAIN), "记录没了，映射应为空：" + actual()),
                () -> assertEquals(List.of("push:" + SERVICE_NAME + "@" + DOMAIN + ":8080"), pushes(),
                        "应把 forwarder 目标回落成域名本身：" + actual()));
    }

    // ---------------------------------------------------------------- 夹具

    private void seed(String primaryIp) {
        long now = System.currentTimeMillis();
        DdnsRecord record = new DdnsRecord();
        record.setId(ID);
        record.setDomain(DOMAIN);
        record.setPrimaryIp(primaryIp);
        record.setResolvedIps(primaryIp);
        record.setIntervalSeconds(300);
        record.setEnabled(1);
        record.setLastCheckTime(now);
        record.setLastChangeTime(0L);
        record.setLastError("");
        record.setFailCount(0);
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        rows.put(ID, record);
    }

    /** 复刻 {@link DdnsService#loadRegistry()}：先 put(域名, 主地址)，再 markLoaded(域名集)。 */
    private void loadRegistryLikeStartup(String primaryIp) {
        registry.put(DOMAIN, primaryIp);
        registry.markLoaded(List.of(DOMAIN));
    }

    private void oneForwardWithOneEntryNode() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(16L);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        when(tunnelService.getById(16)).thenReturn(tunnel);

        Forward forward = new Forward();
        forward.setId(21L);
        forward.setUserId(1);
        forward.setTunnelId(16);
        forward.setRemoteAddr(DOMAIN + ":8080");
        forward.setStrategy("round");
        when(forwardService.list(any())).thenReturn(List.of(forward));

        ChainTunnel entry = new ChainTunnel();
        entry.setId(101L);
        entry.setTunnelId(16L);
        entry.setChainType(1);
        entry.setNodeId(NODE_ID);
        entry.setPort(30200);
        when(chainTunnelService.list(any())).thenReturn(List.of(entry));

        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("node-1");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setInterfaceName("eth0");
        when(nodeService.getById(NODE_ID)).thenReturn(node);

        ForwardPort port = new ForwardPort();
        port.setForwardId(21L);
        port.setNodeId(NODE_ID);
        port.setPort(30200);
        when(forwardPortService.getOne(any(), anyBoolean())).thenReturn(port);
    }

    private static User user(int roleId) {
        User owner = new User();
        owner.setId(1L);
        owner.setRoleId(roleId);
        return owner;
    }

    /** MyBatis-Plus updateById 的默认口径：非空字段才进 SET。 */
    private static void mergeNonNull(DdnsRecord target, DdnsRecord source) {
        if (source.getDomain() != null) target.setDomain(source.getDomain());
        if (source.getResolvedIps() != null) target.setResolvedIps(source.getResolvedIps());
        if (source.getPrimaryIp() != null) target.setPrimaryIp(source.getPrimaryIp());
        if (source.getIntervalSeconds() != null) target.setIntervalSeconds(source.getIntervalSeconds());
        if (source.getEnabled() != null) target.setEnabled(source.getEnabled());
        if (source.getLastCheckTime() != null) target.setLastCheckTime(source.getLastCheckTime());
        if (source.getLastChangeTime() != null) target.setLastChangeTime(source.getLastChangeTime());
        if (source.getLastError() != null) target.setLastError(source.getLastError());
        if (source.getFailCount() != null) target.setFailCount(source.getFailCount());
        if (source.getCreatedTime() != null) target.setCreatedTime(source.getCreatedTime());
        if (source.getUpdatedTime() != null) target.setUpdatedTime(source.getUpdatedTime());
    }

    private static DdnsRecord copy(DdnsRecord source) {
        if (source == null) {
            return null;
        }
        DdnsRecord copy = new DdnsRecord();
        copy.setId(source.getId());
        mergeNonNull(copy, source);
        return copy;
    }

    private List<String> pushes() {
        synchronized (events) {
            return events.stream().filter(event -> event.startsWith("push:")).toList();
        }
    }

    /** 失败文案里带上真实状态，避免只看断言名猜现场。 */
    private String actual() {
        synchronized (events) {
            return " 实际: rows=" + rows.size() + "条, rows.contains(" + ID + ")=" + rows.containsKey(ID)
                    + ", registry.knownSize=" + registry.knownSize()
                    + ", registry.get(" + DOMAIN + ")=" + registry.get(DOMAIN)
                    + ", events=" + events;
        }
    }
}

/*
 * ============================ 实测红绿记录（2026-11-11） ============================
 *
 * 环境：JDK 21.0.11（/usr/local/opt/openjdk@21）+ Maven 3.9.9（/Volumes/ssd1t/tools/maven/bin/mvn），
 * 全离线（-o），不连数据库、不解析真实 DNS、不连任何节点。
 *
 * 跑法（整包 test-compile 被既有文件 com/admin/compat/EditReconcileInterleaveTest.java:131 的
 * cannot find symbol: class CompletableFuture 阻断，与本类无关，不能改，故只编译本类）：
 *   CP=$(paste -sd: test-runs/ddns-delete-offline-20261111/cp.txt)
 *   $JAVA_HOME/bin/javac -encoding UTF-8 -cp "$CP" -d target/test-classes  *       src/test/java/com/admin/ddns/service/DdnsDeleteOfflineSafetyTest.java
 *   mvn -o surefire:test -Dtest=DdnsDeleteOfflineSafetyTest -Dsurefire.failIfNoSpecifiedTests=false
 *
 * 结果：Tests run: 3, Failures: 2, Errors: 0, Skipped: 0
 *
 * ① offlineEntryNodeKeepsRecordAndMapping —— 红（4 条断言）
 *    实际: rows=0条, rows.contains(7)=false, registry.knownSize=0,
 *          registry.get(app.example.com)=null, events=[db:delete:7]
 *    证据含义：节点离线时 isOnline 为 false，连 update 都没发（events 里只有 db:delete），
 *    registry.remove + deleteById 仍执行 —— 记录与映射全丢，节点上的旧 IP 再无对账来源。
 *
 * ② failedRollbackUpdateKeepsRecordAndMapping —— 红（4 条断言）
 *    实际: rows=0条, rows.contains(7)=false, registry.knownSize=0,
 *          registry.get(app.example.com)=null,
 *          events=[push:21_1_0@app.example.com:8080, db:delete:7]
 *    证据含义：回落已下发但节点上报 "service 21_1_0_tcp not found"（isOk=false），
 *    failed++ 只改文案，删除照旧执行。
 *
 * ③ confirmedFallbackDeletesRecordAndMapping —— 绿
 *    实际: code=0, data="已删除，节点已回落到域名", rows=0条, registry.knownSize=0,
 *          registry.get(app.example.com)=null,
 *          events=[push:21_1_0@app.example.com:8080, db:delete:7]
 *    证据含义：对照组证明夹具/假库/渲染回落链路本身是通的，①② 的红灯只源于缺少
 *    "未确认即中止" 的分支。
 *
 * 断言自纠：首轮 ③ 也红，原因是断言读 response.getMsg() 找文案，而 R.ok(Object) 只 setData、
 * msg 恒为默认 "操作成功"（R.java:16-20）。改为 assertEquals(..., response.getData()) 后转绿；
 * 这是测试自身的口径错误，已修正，未放宽任何被测行为。
 * =================================================================================
 */
