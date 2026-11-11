package com.admin.ddns.service;

import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsForwardApplier;
import com.admin.ddns.DdnsResolver;
import com.admin.ddns.DdnsTarget;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.ddns.dto.DdnsUpdateRequest;
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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 闸门外快照读的 TOCTOU 复现（全离线：DNS、节点下发、数据库全部是 mock，不连任何服务器）。
 *
 * <p><b>被测的两个读点</b>（行号对应 DdnsService.java：668 行，sha256 3ff0c54c…）：
 * <ul>
 *   <li>{@code checkNow}：第 277 行 {@code selectById} 先读，第 281 行 {@code acquire} 后加闸门。
 *       读和加锁之间没有任何互斥，update/delete 可以在这条缝里完整跑完。</li>
 *   <li>{@code checkDueRecords}：第 302–303 行 {@code selectList} 先取列表，第 308 行
 *       {@code checking.add(record.getId())} 才对单条记录加闸门。列表里的行同样是加锁前的旧快照。</li>
 * </ul>
 *
 * <p><b>复现手法</b>：把暂停点放在 mock 的 {@code selectById} <em>已经读出旧行、尚未返回给调用线程</em>
 * 的位置（{@code selectList} 同理：结果集已定型、尚未交给调度器）。这是真实查询延迟 / 线程切换的
 * 等价位置，不是把竞态窗口人为拉长；主线程随后调用 update/delete，等它完整返回后再放行检查线程。
 * 于是「旧快照 + 新库值」同时存在，正是 TOCTOU 的判定条件。
 *
 * <p><b>假库语义</b>：{@code updateById} 按 MyBatis-Plus 3.4.1 的默认口径（非空字段整体回写，
 * {@code DdnsRecord} 上没有 {@code @Version}）合并进内存行，并记录每次实际写出的
 * {@code enabled / intervalSeconds / primaryIp}；{@code deleteById} 直接删行并让后续
 * {@code updateById} 返回 0。下发通道每次被调用时记录真正要写进节点配置的地址：
 * 优先取 {@code target.renderedTargets()}（apply 与 rollback 都显式渲染），没有显式渲染
 * 时才回落到注册表口径。
 *
 * <p><b>结论</b>：两条 TOCTOU 都成立。本类前四个用例断言「正确行为」，对当前实现是红灯；
 * 第五个用例是对照组（update 先完成、checkNow 后读库），它是绿灯 —— 说明覆盖不是假库造成的，
 * 只来自「读发生在闸门之外」。
 *
 * <p><b>实测记录</b>：在 sha256 3ff0c54c…（668 行，2026-11-11 03:59:59）上本类 4 红 1 绿；
 * 该版本之后 DdnsService 在闸门内补了重读（现 sha256 a3351046…，681 行：{@code checkNow} 第 288 行、
 * {@code checkDueRecords} 第 321–324 行按库里当前行重新确认），同一份用例随即 5 绿 ——
 * 红→绿的翻转点就是缺陷本身，改回旧顺序同样会红。
 *
 * <p>删除场景的损害口径：{@code delete} 的语义是「记录没了，节点回落到域名」。旧快照继续跑时，
 * 先按新地址改一次节点配置，落库 {@code updateById} 返回 0 触发回滚，再把节点改回旧 IP ——
 * 共两次节点写入，最终停在旧 IP 上，而库里已无记录可对账。注册表本身不会被写回
 * （当前实现的 rollback 明确不碰注册表），因此那一条只作护栏，不作缺陷断言。
 */
class DdnsServiceSnapshotToctouTest {

    private static final String DOMAIN = "app.example.com";
    private static final long ID = 7L;
    private static final long NODE_ID = 1L;
    private static final String MARKER_UPDATED = "marker:updated";
    private static final String MARKER_DELETED = "marker:deleted";

    /** 内存里的 ddns_record 行。 */
    private final Map<Long, DdnsRecord> rows = new ConcurrentHashMap<>();
    /** 交叉事件日志：db:update / db:delete / push / marker。 */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger readCalls = new AtomicInteger();
    private final AtomicReference<Runnable> pauseAfterFirstRead = new AtomicReference<>();
    private final AtomicReference<Runnable> pauseAfterListSnapshot = new AtomicReference<>();

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
    private ExecutorService pool;

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

        // 读快照 → 暂停（旧行已在手）→ 返回调用线程
        when(mapper.selectById(any())).thenAnswer(invocation -> {
            Long id = (Long) invocation.getArgument(0);
            DdnsRecord snapshot = copy(rows.get(id));
            if (readCalls.incrementAndGet() == 1) {
                runHook(pauseAfterFirstRead.getAndSet(null));
            }
            return snapshot;
        });
        // 结果集定型 → 暂停（旧列表已在手）→ 返回调度器
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            boolean enabledOnly = filtersOnEnabled(invocation.getArgument(0));
            List<DdnsRecord> snapshot = new ArrayList<>();
            synchronized (rows) {
                for (DdnsRecord row : rows.values()) {
                    if (!enabledOnly || Integer.valueOf(1).equals(row.getEnabled())) {
                        snapshot.add(copy(row));
                    }
                }
            }
            snapshot.sort(Comparator.comparing(DdnsRecord::getId));
            runHook(pauseAfterListSnapshot.getAndSet(null));
            return snapshot;
        });
        // MyBatis-Plus updateById：非空字段整体回写；行不存在返回 0
        when(mapper.updateById(any(DdnsRecord.class))).thenAnswer(invocation -> {
            DdnsRecord incoming = invocation.getArgument(0);
            synchronized (rows) {
                DdnsRecord current = rows.get(incoming.getId());
                if (current == null) {
                    events.add("db:update-miss:" + incoming.getId());
                    return 0;
                }
                mergeNonNull(current, incoming);
                events.add("db:update:" + incoming.getId()
                        + ":enabled=" + current.getEnabled()
                        + ":interval=" + current.getIntervalSeconds()
                        + ":primary=" + current.getPrimaryIp());
                return 1;
            }
        });
        when(mapper.deleteById(any())).thenAnswer(invocation -> {
            Long id = (Long) invocation.getArgument(0);
            events.add("db:delete:" + id);
            return rows.remove(id) == null ? 0 : 1;
        });

        when(applier.isOnline(anyLong())).thenReturn(true);
        // 记录真正写进节点配置的地址：apply/rollback 走显式 renderedTargets，其余走注册表口径
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
        if (pool != null) {
            pool.shutdownNow();
        }
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("checkNow：selectById 在 acquire 之前，update 完成后旧快照整实体回写覆盖 enabled/interval")
    void checkNowOverwritesUpdateThatCompletedBeforeAcquire() throws Exception {
        seed(1, 60, "1.1.1.1", 0L);
        oneForwardWithOneEntryNode();
        when(resolver.resolve(DOMAIN)).thenReturn(List.of("2.2.2.2"));

        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch updateDone = new CountDownLatch(1);
        pauseAfterFirstRead.set(() -> {
            snapshotTaken.countDown();
            await(updateDone, "并发 update 完成");
        });

        pool = Executors.newSingleThreadExecutor();
        try {
            Future<R> check = pool.submit(() -> service.checkNow(ID));
            assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS), "checkNow 未执行到 selectById");

            DdnsUpdateRequest request = new DdnsUpdateRequest();
            request.setId(ID);
            request.setEnabled(0);
            request.setIntervalSeconds(600);
            R updateResponse = service.update(request);
            assertEquals(0, updateResponse.getCode(), "此时闸门是空的，update 应当成功：" + updateResponse.getMsg());
            assertEquals(0, rows.get(ID).getEnabled(), "update 已把记录停用");
            assertEquals(600, rows.get(ID).getIntervalSeconds());

            updateDone.countDown();
            R checkResponse = check.get(5, TimeUnit.SECONDS);
            assertEquals(0, checkResponse.getCode(), "检查线程应完整跑完下发+落库：" + checkResponse.getMsg());
            assertEquals("2.2.2.2", rows.get(ID).getPrimaryIp(), "旧快照确实下发了新地址并落了库");

            assertEquals(0, rows.get(ID).getEnabled(),
                    "TOCTOU：checkNow 用 acquire 之前读到的旧实体整实体回写，把 update 刚写的 enabled=0 覆盖回 1");
            assertEquals(600, rows.get(ID).getIntervalSeconds(),
                    "TOCTOU：旧快照里的 intervalSeconds=60 覆盖了 update 刚写的 600");
        } finally {
            updateDone.countDown();
        }
    }

    @Test
    @DisplayName("checkNow：delete 完成后旧快照继续向节点下发，并把渲染映射写回已删除的域名")
    void checkNowKeepsDispatchingAfterConcurrentDelete() throws Exception {
        seed(1, 60, "1.1.1.1", 0L);
        registry.put(DOMAIN, "1.1.1.1");
        oneForwardWithOneEntryNode();
        when(resolver.resolve(DOMAIN)).thenReturn(List.of("2.2.2.2"));

        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch deleteDone = new CountDownLatch(1);
        pauseAfterFirstRead.set(() -> {
            snapshotTaken.countDown();
            await(deleteDone, "并发 delete 完成");
        });

        pool = Executors.newSingleThreadExecutor();
        try {
            Future<R> check = pool.submit(() -> service.checkNow(ID));
            assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS), "checkNow 未执行到 selectById");

            R deleteResponse = service.delete(ID);
            assertEquals(0, deleteResponse.getCode(), "此时闸门是空的，delete 应当成功：" + deleteResponse.getMsg());
            assertTrue(rows.isEmpty(), "记录已从库里删除");
            assertNull(registry.get(DOMAIN), "delete 已清空渲染映射");

            events.add(MARKER_DELETED);
            deleteDone.countDown();
            R checkResponse = check.get(5, TimeUnit.SECONDS);
            assertTrue(checkResponse.getCode() != 0,
                    "记录已删除，checkNow 不得返回成功（否则就是拿旧快照复活记录）："
                            + checkResponse.getMsg() + "；事件日志=" + events);

            List<String> pushes = pushesAfter(MARKER_DELETED);
            assertAll(
                    () -> assertEquals(0, pushes.size(),
                            "TOCTOU：记录删除完成后，旧快照仍改了节点配置（先下发新地址、落库失败后又回滚成旧 IP），"
                                    + "库里已无记录可对账，delete 刚写的域名回落被覆盖；实际下发=" + pushes
                                    + "；事件日志=" + events),
                    () -> assertNull(registry.get(DOMAIN),
                            "护栏：当前实现回滚不写注册表，这里应保持为空；事件日志=" + events));
        } finally {
            deleteDone.countDown();
        }
    }

    @Test
    @DisplayName("checkDueRecords：列表快照在闸门之前，update 停用后调度器仍检查并把 enabled 写回 1")
    void schedulerSnapshotReChecksRecordDisabledBeforeGate() throws Exception {
        seed(1, 60, "1.1.1.1", 0L);
        oneForwardWithOneEntryNode();
        when(resolver.resolve(DOMAIN)).thenReturn(List.of("2.2.2.2"));

        CountDownLatch listTaken = new CountDownLatch(1);
        CountDownLatch updateDone = new CountDownLatch(1);
        pauseAfterListSnapshot.set(() -> {
            listTaken.countDown();
            await(updateDone, "并发 update 完成");
        });

        pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> round = pool.submit(() -> service.checkDueRecords());
            assertTrue(listTaken.await(5, TimeUnit.SECONDS), "调度器未完成 selectList");

            DdnsUpdateRequest request = new DdnsUpdateRequest();
            request.setId(ID);
            request.setEnabled(0);
            R updateResponse = service.update(request);
            assertEquals(0, updateResponse.getCode(), updateResponse.getMsg());
            assertEquals(0, rows.get(ID).getEnabled(), "update 已把记录停用");

            events.add(MARKER_UPDATED);
            updateDone.countDown();
            String summary = round.get(5, TimeUnit.SECONDS);

            assertEquals(0, rows.get(ID).getEnabled(),
                    "TOCTOU：调度器拿的是加闸门前的旧列表，仍把已停用记录当成 enabled=1 检查，"
                            + "整实体回写把 enabled 覆盖回 1（摘要 " + summary + "）");
            List<String> pushes = pushesAfter(MARKER_UPDATED);
            assertEquals(0, pushes.size(),
                    "TOCTOU：已停用的记录不得再向节点下发地址；实际下发=" + pushes + "；事件日志=" + events);
        } finally {
            updateDone.countDown();
        }
    }

    @Test
    @DisplayName("checkDueRecords：delete 完成后旧列表快照仍检查已删除记录并写回渲染映射")
    void schedulerSnapshotReChecksDeletedRecord() throws Exception {
        seed(1, 60, "1.1.1.1", 0L);
        registry.put(DOMAIN, "1.1.1.1");
        oneForwardWithOneEntryNode();
        when(resolver.resolve(DOMAIN)).thenReturn(List.of("2.2.2.2"));

        CountDownLatch listTaken = new CountDownLatch(1);
        CountDownLatch deleteDone = new CountDownLatch(1);
        pauseAfterListSnapshot.set(() -> {
            listTaken.countDown();
            await(deleteDone, "并发 delete 完成");
        });

        pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> round = pool.submit(() -> service.checkDueRecords());
            assertTrue(listTaken.await(5, TimeUnit.SECONDS), "调度器未完成 selectList");

            R deleteResponse = service.delete(ID);
            assertEquals(0, deleteResponse.getCode(), "此时闸门是空的，delete 应当成功：" + deleteResponse.getMsg());
            assertTrue(rows.isEmpty(), "记录已从库里删除");
            assertNull(registry.get(DOMAIN), "delete 已清空渲染映射");

            events.add(MARKER_DELETED);
            deleteDone.countDown();
            round.get(5, TimeUnit.SECONDS);

            List<String> pushes = pushesAfter(MARKER_DELETED);
            assertAll(
                    () -> assertEquals(0, pushes.size(),
                            "TOCTOU：删除完成后，调度器旧列表快照仍改了节点配置；实际下发=" + pushes
                                    + "；事件日志=" + events),
                    () -> assertNull(registry.get(DOMAIN),
                            "护栏：当前实现回滚不写注册表，这里应保持为空；事件日志=" + events));
        } finally {
            deleteDone.countDown();
        }
    }

    @Test
    @DisplayName("对照组：update 先完成、checkNow 后读库时不会被覆盖（覆盖只源于读在闸门外）")
    void updateBeforeCheckNowIsNotOverwritten() throws Exception {
        seed(1, 60, "1.1.1.1", 0L);
        oneForwardWithOneEntryNode();
        when(resolver.resolve(DOMAIN)).thenReturn(List.of("2.2.2.2"));

        DdnsUpdateRequest request = new DdnsUpdateRequest();
        request.setId(ID);
        request.setEnabled(0);
        request.setIntervalSeconds(600);
        assertEquals(0, service.update(request).getCode());

        R checkResponse = service.checkNow(ID);
        assertEquals(0, checkResponse.getCode(), checkResponse.getMsg());

        assertEquals(0, rows.get(ID).getEnabled(), "读到的已是 update 之后的值，回写不会覆盖");
        assertEquals(600, rows.get(ID).getIntervalSeconds(), "读到的已是 update 之后的值，回写不会覆盖");
    }

    // ---------------------------------------------------------------- 夹具

    private void seed(int enabled, int intervalSeconds, String primaryIp, long lastCheckTime) {
        long now = System.currentTimeMillis();
        DdnsRecord record = new DdnsRecord();
        record.setId(ID);
        record.setDomain(DOMAIN);
        record.setPrimaryIp(primaryIp);
        record.setResolvedIps(primaryIp);
        record.setIntervalSeconds(intervalSeconds);
        record.setEnabled(enabled);
        record.setLastCheckTime(lastCheckTime);
        record.setLastChangeTime(0L);
        record.setLastError("");
        record.setFailCount(0);
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        rows.put(ID, record);
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
        forward.setRemoteAddr("app.example.com:8080");
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

    /** 只看 wrapper 是否按 enabled 过滤；本测试里 list() 用的是 orderByAsc("id")。 */
    private static boolean filtersOnEnabled(Object wrapper) {
        if (wrapper instanceof QueryWrapper<?> queryWrapper) {
            String sql = queryWrapper.getSqlSegment();
            return sql != null && sql.contains("enabled");
        }
        return false;
    }

    private List<String> pushesAfter(String marker) {
        synchronized (events) {
            int index = events.lastIndexOf(marker);
            if (index < 0) {
                throw new AssertionError("事件日志里没有标记 " + marker + "：" + events);
            }
            return events.subList(index + 1, events.size()).stream()
                    .filter(event -> event.startsWith("push:"))
                    .toList();
        }
    }

    private static void runHook(Runnable hook) {
        if (hook != null) {
            hook.run();
        }
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("等待超时：" + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断：" + what, e);
        }
    }
}
