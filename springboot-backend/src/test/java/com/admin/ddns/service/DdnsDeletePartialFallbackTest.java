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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

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
 * 「删除 DDNS 记录：任一入口节点未确认回落就整批保留」的多节点行为测试
 * （全离线：数据库、DNS、节点下发全部是 mock，不连任何服务器，不解析真实 DNS）。
 *
 * <p>单节点场景由 {@code DdnsDeleteOfflineSafetyTest} 覆盖；这里补的是多入口节点时才会出现、
 * 也是这次修复真正容易做错的三处：
 * <ol>
 *   <li><b>先确认再动手</b>：任一入口节点离线（或在线查询本身抛异常/超时）时，一个节点都不下发，
 *       记录与注册表映射原样保留 —— 不能「先给在线节点发一半、再删库」。</li>
 *   <li><b>部分成功后失败</b>：A 节点已确认回落到域名、B 节点下发失败。记录还在，节点就该按记录
 *       里的地址渲染，所以已确认的 A 要改回旧 IP；改不回去时如实说「地址状态未确认」，
 *       并保留记录 + 映射，让下次服务上报能按库收敛（对账通道由保留的映射保证仍然可信）。</li>
 *   <li><b>回滚也失败不谎报</b>：返回错误、说明未确认，绝不返回成功。</li>
 * </ol>
 *
 * <p><b>假库语义</b>同前：{@code deleteById} 真的删行并返回受影响行数；{@code selectById/selectList}
 * 返回行副本；{@code updateById} 只合并非空字段。
 */
class DdnsDeletePartialFallbackTest {

    private static final String DOMAIN = "app.example.com";
    private static final String OLD_IP = "1.1.1.1";
    private static final String SERVICE_NAME = "21_1_0";
    private static final long ID = 7L;
    private static final long NODE_A = 1L;
    private static final long NODE_B = 2L;

    private final Map<Long, DdnsRecord> rows = new ConcurrentHashMap<>();
    /** 交叉事件日志：db:delete / push:nodeX:payload。 */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    /** 视为离线的入口节点。 */
    private final Set<Long> offline = new HashSet<>();
    /** 在线查询抛异常的入口节点（模拟节点侧接口异常/超时）。 */
    private final Set<Long> onlineQueryThrows = new HashSet<>();
    /** 哪些下发改判为失败：按 target 决定，用来构造「A 成功、B 失败」。 */
    private volatile Predicate<DdnsTarget> pushFailure = target -> false;

    private DdnsRecordMapper mapper;
    private DdnsAddressRegistry registry;
    private DdnsForwardApplier applier;
    private DdnsService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DdnsRecordMapper.class);
        registry = new DdnsAddressRegistry();
        DdnsResolver resolver = mock(DdnsResolver.class);
        applier = mock(DdnsForwardApplier.class);
        ForwardService forwardService = mock(ForwardService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);
        NodeService nodeService = mock(NodeService.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        UserService userService = mock(UserService.class);
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
                return 1;
            }
        });
        when(mapper.deleteById(any())).thenAnswer(invocation -> {
            Long id = (Long) invocation.getArgument(0);
            events.add("db:delete:" + id);
            return rows.remove(id) == null ? 0 : 1;
        });

        when(applier.isOnline(anyLong())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            if (onlineQueryThrows.contains(nodeId)) {
                throw new IllegalStateException("node " + nodeId + " 状态查询超时");
            }
            return !offline.contains(nodeId);
        });
        when(applier.update(any(DdnsTarget.class))).thenAnswer(invocation -> {
            DdnsTarget target = invocation.getArgument(0);
            String payload = target.hasRenderedTargets()
                    ? String.join(",", target.renderedTargets())
                    : "registry:" + registry.get(DOMAIN);
            events.add("push:node" + target.node().getId() + ":" + payload);
            return pushFailure.test(target) ? "service " + SERVICE_NAME + "_tcp not found" : "OK";
        });
        when(userService.getById(anyInt())).thenReturn(user(1));

        seed(OLD_IP);
        loadRegistryLikeStartup(OLD_IP);
        twoEntryNodes(tunnelService, forwardService, chainTunnelService, nodeService, forwardPortService);
    }

    @AfterEach
    void tearDown() {
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("两个入口节点、其中一个离线：一个节点都不下发，记录与映射原样保留")
    void anyOfflineEntryNodeAbortsWithoutAnyPush() {
        offline.add(NODE_B);

        R response = service.delete(ID);

        assertAll("任一节点离线=未确认，删除必须整批放弃",
                () -> assertNotEquals(0, response.getCode(), "不应返回成功：" + response.getMsg()),
                () -> assertTrue(response.getMsg().contains("入口节点 " + NODE_B),
                        "错误文案应点名未确认的节点：" + response.getMsg()),
                () -> assertEquals(List.of(), pushes(),
                        "预检失败时一个节点都不该被下发（尤其不该先给在线的 node1 发一半）：" + events),
                () -> assertTrue(rows.containsKey(ID), "记录必须保留：" + events),
                () -> assertEquals(1, registry.knownSize(), "域名必须继续被登记：" + events),
                () -> assertEquals(OLD_IP, registry.get(DOMAIN), "已确认映射必须保留：" + events));
    }

    @Test
    @DisplayName("在线查询本身抛异常/超时：同样按未确认处理，整批放弃")
    void onlineQueryExceptionIsTreatedAsUnconfirmed() {
        onlineQueryThrows.add(NODE_A);

        R response = service.delete(ID);

        assertAll("查询异常不能当成功，也不能当在线照发",
                () -> assertNotEquals(0, response.getCode(), "不应返回成功：" + response.getMsg()),
                () -> assertTrue(response.getMsg().contains("入口节点 " + NODE_A), response.getMsg()),
                () -> assertEquals(List.of(), pushes(), "一个节点都不该被下发：" + events),
                () -> assertTrue(rows.containsKey(ID), "记录必须保留：" + events),
                () -> assertEquals(OLD_IP, registry.get(DOMAIN), "映射必须保留：" + events));
    }

    @Test
    @DisplayName("A 已确认回落、B 下发失败：保留记录与映射，并把 A 改回旧 IP")
    void partialSuccessRollsConfirmedNodesBackAndKeepsRecord() {
        pushFailure = target -> target.node().getId() == NODE_B;

        R response = service.delete(ID);

        assertAll("部分成功也必须整体算未确认",
                () -> assertNotEquals(0, response.getCode(), "不应返回成功：" + response.getMsg()),
                () -> assertTrue(response.getMsg().contains("节点 " + NODE_B), response.getMsg()),
                () -> assertTrue(response.getMsg().contains("1 个入口节点状态未确认"),
                        "文案应说明未确认的节点数量：" + response.getMsg()),
                () -> assertTrue(rows.containsKey(ID), "记录必须保留：" + events),
                () -> assertEquals(1, registry.knownSize(), "映射必须保留：" + events),
                () -> assertEquals(OLD_IP, registry.get(DOMAIN), "记录里的地址必须还在映射里：" + events),
                () -> assertEquals(List.of(
                                "push:node1:" + DOMAIN + ":8080",
                                "push:node2:" + DOMAIN + ":8080",
                                "push:node1:" + OLD_IP + ":8080"),
                        pushes(), "A 回落后要按保留的记录改回旧 IP，B 不再重复下发：" + events));
    }

    @Test
    @DisplayName("部分成功后回滚也失败：如实说未确认，不算成功也不假装已恢复")
    void failedRollbackIsReportedAndRecordKept() {
        pushFailure = target -> target.node().getId() == NODE_B
                || (target.node().getId() == NODE_A && target.hasRenderedTargets()
                        && String.join(",", target.renderedTargets()).startsWith(OLD_IP));

        R response = service.delete(ID);

        assertAll("回滚失败必须如实返回",
                () -> assertNotEquals(0, response.getCode(), "不应返回成功：" + response.getMsg()),
                () -> assertTrue(response.getMsg().contains("回滚未确认"),
                        "文案应说明回滚未确认：" + response.getMsg()),
                () -> assertTrue(rows.containsKey(ID), "记录必须保留：" + events),
                () -> assertEquals(OLD_IP, registry.get(DOMAIN), "映射必须保留：" + events),
                () -> assertEquals(3, pushes().size(), "应为「A 回落、B 回落、A 回滚」共三次下发：" + events));
    }

    @Test
    @DisplayName("保留的记录让对账通道仍然可信：失败后摘要闸门不降级，节点可按库收敛")
    void retainedRecordKeepsReconcilePathTrusted() {
        pushFailure = target -> target.node().getId() == NODE_B;

        R response = service.delete(ID);
        assertNotEquals(0, response.getCode(), response.getMsg());

        assertAll("对账依据必须完好，否则节点会永久停在旧 IP 上",
                () -> assertFalse(DdnsTargetRenderer.digestTrustDegraded(),
                        "映射完整（记录里主地址是 IP）时摘要闸门不该降级：" + events),
                () -> assertNull(DdnsTargetRenderer.anyDigestTrustIssue(DOMAIN + ":8080"),
                        "该域名仍归 DDNS 管且有可用地址，摘要应当可信：" + events));
    }

    @Test
    @DisplayName("两个入口节点都确认回落：才允许删库并摘除映射")
    void allNodesConfirmedDeletesRecordAndMapping() {
        R response = service.delete(ID);

        assertAll("全确认才删除",
                () -> assertEquals(0, response.getCode(), "确认回落后应成功：" + response.getMsg()),
                () -> assertEquals("已删除，节点已回落到域名", response.getData(), response.getMsg()),
                () -> assertFalse(rows.containsKey(ID), "记录应被删除：" + events),
                () -> assertEquals(0, registry.knownSize(), "knownDomains 应清空：" + events),
                () -> assertNull(registry.get(DOMAIN), "映射应为空：" + events),
                () -> assertEquals(List.of(
                                "push:node1:" + DOMAIN + ":8080",
                                "push:node2:" + DOMAIN + ":8080"),
                        pushes(), "两个节点都应被回落到域名：" + events));
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

    /** 一条转发、两个入口节点（node1/node2），服务名与 ForwardServiceImpl 一致：{@code 21_1_0}。 */
    private void twoEntryNodes(TunnelService tunnelService, ForwardService forwardService,
                               ChainTunnelService chainTunnelService, NodeService nodeService,
                               ForwardPortService forwardPortService) {
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

        when(chainTunnelService.list(any())).thenReturn(List.of(
                entry(101L, NODE_A), entry(102L, NODE_B)));

        when(nodeService.getById(NODE_A)).thenReturn(node(NODE_A, "node-1"));
        when(nodeService.getById(NODE_B)).thenReturn(node(NODE_B, "node-2"));

        ForwardPort port = new ForwardPort();
        port.setForwardId(21L);
        port.setNodeId(NODE_A);
        port.setPort(30200);
        when(forwardPortService.getOne(any(), anyBoolean())).thenReturn(port);
    }

    private static ChainTunnel entry(long id, long nodeId) {
        ChainTunnel entry = new ChainTunnel();
        entry.setId(id);
        entry.setTunnelId(16L);
        entry.setChainType(1);
        entry.setNodeId(nodeId);
        entry.setPort(30200);
        return entry;
    }

    private static Node node(long id, String name) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setInterfaceName("eth0");
        return node;
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
}
