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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「删除先撤注册表、后删库」的顺序缺口复现（全离线：数据库、DNS、节点下发全部是 mock，
 * 不连任何服务器）。
 *
 * <p><b>被验证的推断</b>（行号对应 DdnsService.java，681 行版本）：
 * <ol>
 *   <li>第 260 行 {@code registry.remove(domain)} 先执行，第 263 行 {@code deleteById} 后执行 ——
 *       顺序确实如此。</li>
 *   <li>删除落库失败（抛异常或影响 0 行）后走第 269 行 {@code restoreRegistry(record)}；
 *       而第 379 行只在 {@code DdnsAddresses.isIpLiteral(record.getPrimaryIp())} 成立时才会
 *       {@code registry.put}。记录从未解析出主地址时 {@code primaryIp} 是 {@code ""}
 *       （{@link DdnsService#create} 就是这么写的，第 123 行），于是<b>什么都不恢复</b>：
 *       第 310 行 {@code remove} 已经把这个域名从 {@code knownDomains} 里摘掉，
 *       库里那条记录却还在。</li>
 * </ol>
 *
 * <p><b>危害不是「少一条缓存」</b>：{@code knownDomains} 是「这个域名归 DDNS 管，只是还没解析出
 * 地址」的唯一载体。{@link DdnsAddressRegistry#isDegraded()} = 装载未就绪 或 存在未解析域名，
 * 它被 {@code WebSocketServer.trustedReportedDigests}（WebSocketServer.java:971）当作全局闸门：
 * 未降级就直接 {@code return digests}，跳过逐条 {@code anyDigestTrustIssue} 对话。
 * 也就是说这条未解析域名一旦被摘掉，节点上报的摘要重新变成「可信」，
 * 按摘要删服务/重建的通道重新打开 —— 而这正是 {@code knownDomains} 要挡的东西。
 *
 * <p><b>实测口径</b>：本类前两个用例与第五个用例断言「正确行为」，在当前实现上是红灯；
 * 第三、第四个用例是对照组（主地址已知时能恢复、删除成功时应当摘除），是绿灯 ——
 * 说明红灯来自「主地址为空时 restore 是空操作」，不是假库或场景构造的副作用。
 *
 * <p><b>实测记录</b>（2026-11-11，JDK 21 + Maven 3.9.9 离线跑）：
 * {@code Tests run: 5, Failures: 3, Errors: 0}。三处红灯原文：
 * {@code knownSize expected: <1> but was: <0>}（前两个用例）、
 * {@code digestTrustDegraded expected: <true> but was: <false>} 与
 * {@code anyDigestTrustIssue expected: not <null>}（第五个用例）。
 * 事件日志为 {@code [push:21_1_0@app.example.com:8080, db:delete:7]}，证明回落已下发、删除已尝试。
 * 产品修复（主地址为空也 {@code put} 回 {@code knownDomains}）落地后，本类应整体转绿。
 *
 * <p>注意：本类断言的是「应该怎样」，修复前为红灯是预期结果，不是测试写错。
 *
 * <p><b>假库语义</b>：{@code deleteById} 由用例注入行为（抛异常 / 返回 0 / 返回 1），
 * 只有返回 &gt;0 时才真的删行；{@code selectById/selectList} 返回行的副本；
 * {@code updateById} 按 MyBatis-Plus 默认口径合并非空字段。
 */
class DdnsDeleteRegistryRestoreGapTest {

    private static final String DOMAIN = "app.example.com";
    private static final long ID = 7L;
    private static final long NODE_ID = 1L;

    /** 内存里的 ddns_record 行。 */
    private final Map<Long, DdnsRecord> rows = new ConcurrentHashMap<>();
    /** 交叉事件日志：db:delete / db:update / push。 */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    /** {@code deleteById} 的行为：抛异常、返回 0、返回 1，由各用例注入。 */
    private final AtomicReference<Supplier<Integer>> deleteResponder = new AtomicReference<>();

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

        deleteResponder.set(() -> 1);

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
            Supplier<Integer> responder = deleteResponder.get();
            int affected = responder == null ? 1 : responder.get();
            if (affected > 0) {
                rows.remove(id);
            }
            return affected;
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
        when(userService.getById(any(Integer.class))).thenReturn(user(1));
    }

    @AfterEach
    void tearDown() {
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("删除落库抛异常 + 主地址为空：库里的记录还在，注册表却把它从 knownDomains 摘掉了")
    void failedDeleteKeepsDomainKnownWhenPrimaryAddressMissing() {
        seed("");                     // create() 的初始状态：primaryIp=""
        oneForwardWithOneEntryNode();
        loadRegistryLikeStartup("");  // 复刻 loadRegistry()：put(domain, "") + markLoaded([domain])

        assertTrue(registry.isDegraded(),
                "前置：库里有记录但还没有主地址，注册表必须报降级（否则摘要闸门本来就是开的）");
        assertEquals(List.of(DOMAIN), registry.unresolvedDomains(), "前置：该域名应登记为未解析");

        deleteResponder.set(() -> {
            throw new IllegalStateException("db: connection reset");
        });

        R response = service.delete(ID);

        assertTrue(response.getCode() != 0, "删除未生效必须返回错误：" + response.getMsg());
        assertTrue(rows.containsKey(ID), "假库前置：记录没有被删掉，它还在库里");
        assertTrue(response.getMsg().contains("删除未生效"), "错误文案应说明删除未生效：" + response.getMsg());

        assertEquals(List.of("push:" + serviceName() + "@" + DOMAIN + ":8080"), pushes(),
                "顺序护栏：节点已经被回落到域名，此时记录仍在库里");
        assertEquals(1, registry.knownSize(),
                "删除失败后库里还有这条记录，注册表就必须继续认这个域名归 DDNS 管；"
                        + "现在它被 remove 摘掉、restoreRegistry 又因为 primaryIp=\"\" 跳过 put，"
                        + "knownDomains 少了一条，库与内存状态不一致；事件日志=" + events);
        assertEquals(List.of(DOMAIN), registry.unresolvedDomains(),
                "删除失败应还原成「已知但未解析」，不能变成「这个域名不归 DDNS 管」；事件日志=" + events);
        assertTrue(registry.isDegraded(),
                "删除失败后仍存在未解析域名，isDegraded() 必须继续为 true；事件日志=" + events);
        assertNull(registry.get(DOMAIN), "主地址本来就没有，恢复也不该凭空造一个");
    }

    @Test
    @DisplayName("删除落库影响 0 行 + 主地址为空：同样丢掉 knownDomains（与抛异常同一条路径）")
    void zeroRowDeleteKeepsDomainKnownWhenPrimaryAddressMissing() {
        seed("");
        oneForwardWithOneEntryNode();
        loadRegistryLikeStartup("");

        deleteResponder.set(() -> 0);

        R response = service.delete(ID);

        assertTrue(response.getCode() != 0, "删除未生效必须返回错误：" + response.getMsg());
        assertTrue(response.getMsg().contains("受影响 0 行"), "错误文案应带上受影响行数：" + response.getMsg());
        assertTrue(rows.containsKey(ID), "假库前置：记录还在库里");
        assertEquals(1, registry.knownSize(),
                "受影响 0 行与抛异常走的是同一个 restoreRegistry 分支，knownDomains 同样被丢掉；"
                        + "事件日志=" + events);
        assertTrue(registry.isDegraded(),
                "库里仍有未解析域名，isDegraded() 不能变成 false；事件日志=" + events);
    }

    @Test
    @DisplayName("对照组：主地址已知时删除失败能把映射原样放回（restore 不是完全没用）")
    void failedDeleteRestoresMappingWhenPrimaryAddressKnown() {
        seed("1.1.1.1");
        oneForwardWithOneEntryNode();
        loadRegistryLikeStartup("1.1.1.1");

        assertFalse(registry.isDegraded(), "前置：映射完整，不该报降级");

        deleteResponder.set(() -> 0);

        R response = service.delete(ID);

        assertTrue(response.getCode() != 0, response.getMsg());
        assertEquals(1, registry.knownSize(), "记录还在，域名必须仍被登记");
        assertEquals("1.1.1.1", registry.get(DOMAIN), "恢复路径在这个分支上是有效的");
        assertFalse(registry.isDegraded(), "主地址回来了，降级状态应当解除");
    }

    @Test
    @DisplayName("对照组：删除真正生效时域名应被摘除（所以上一组不是在要求「永不 remove」）")
    void successfulDeleteDropsDomainFromRegistry() {
        seed("");
        oneForwardWithOneEntryNode();
        loadRegistryLikeStartup("");

        R response = service.delete(ID);

        assertEquals(0, response.getCode(), "删库成功时 delete 应返回成功：" + response.getMsg());
        assertFalse(rows.containsKey(ID), "记录已从库里删除");
        assertEquals(0, registry.knownSize(), "记录没了，knownDomains 应当清空");
        assertEquals(List.of(), registry.unresolvedDomains(), "记录没了，不该还挂着未解析域名");
        assertFalse(registry.isDegraded(), "健康的空表不算降级");
        assertNull(registry.get(DOMAIN), "记录没了，映射也应当为空");
    }

    @Test
    @DisplayName("缺口的下游后果：删除失败后摘要可信度闸门被打开（WebSocketServer.java:971 提前放行）")
    void failedDeleteReopensDigestTrustGate() {
        seed("");
        oneForwardWithOneEntryNode();
        loadRegistryLikeStartup("");

        String target = DOMAIN + ":8080";
        assertTrue(DdnsTargetRenderer.digestTrustDegraded(),
                "前置：注册表降级，摘要闸门处于工作状态");
        assertNotNull(DdnsTargetRenderer.anyDigestTrustIssue(target),
                "前置：该域名应被判为「尚未解析出可用主地址」，本轮不按摘要删建");

        deleteResponder.set(() -> {
            throw new IllegalStateException("db: timeout");
        });

        R response = service.delete(ID);
        assertTrue(response.getCode() != 0, response.getMsg());

        assertAll(
                () -> assertTrue(DdnsTargetRenderer.digestTrustDegraded(),
                        "删除失败后库里仍有未解析的 DDNS 域名，摘要闸门必须继续工作；"
                                + "现在 isDegraded() 变成 false，trustedReportedDigests 会在第 971 行直接放行整台节点的摘要"),
                () -> assertNotNull(DdnsTargetRenderer.anyDigestTrustIssue(target),
                        "该域名仍归 DDNS 管且没有主地址，逐条判定也必须说「不可信」；"
                                + "现在它不在 knownDomains 里，判定返回 null = 可信；事件日志=" + events));

        assertTrue(service.loadRegistry(), "重读一次库能把状态修回来（缺口只在内存里，不是持久化损坏）");
        assertEquals(1, registry.knownSize(), "重读后 knownDomains 恢复");
        assertTrue(registry.isDegraded(), "重读后重新报降级");
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

    /** 复刻 {@link DdnsService#loadRegistry()} 对注册表的两次写入：先 put(域名, 主地址)，再 markLoaded(域名集)。 */
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

    /** 与 ForwardServiceImpl.buildServiceName 一致：非管理员属主、没有 user_tunnel 行 → {@code 21_1_0}。 */
    private static String serviceName() {
        return "21_1_0";
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
