package com.admin.compat;

import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.ForwardWriteLocks;
import com.admin.common.utils.GostUtil;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsDomainGate;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 审计项「编辑下发与自动对账交错」的确定性回归。
 *
 * <p><b>被审计的时序</b>（ForwardServiceImpl.updateForward :1394 → :1437）：
 * 编辑是「先下发、后落库」——节点按新目标生效之后，才在 nameLock 里写 forward 表。
 * 在这个窗口里，节点上已经是新目标，而库里仍是旧快照。
 * 此时节点重连/心跳触发的自动对账 resyncForwardServices（:3208）用 {@code this.getById}
 * 读到的正是旧快照，算出旧摘要，与节点上报的新摘要不一致 → 判漂移 → DeleteService + 按旧快照重建
 * （:3603 → GostUtil.CreateForwardService）。结果是：用户的新目标被清掉，节点回到旧目标，
 * 随后编辑线程把库写成新目标 → 库新、节点旧，静默不一致且没有任何报错。
 *
 * <p><b>为什么这条路径真的可达</b>：DDNS 里普通下发只取共享读锁
 * （DdnsTargetRenderer.beginDispatch = DdnsDomainGate.sharedForTargets 读锁），
 * 而自动对账同样是「普通下发」，取的也是读锁。读锁与读锁互不排斥，
 * 所以对账不会被「正在下发」挡住——它只在 DDNS 切换窗口（写锁）里才被挡。
 * 第三个用例用跨线程取证直接锁住这一点：同一个域名上两把读锁可以同时持有，
 * 而切换窗口的写锁拿不到。
 *
 * <p><b>用例的三条边界</b>：
 * <ol>
 *   <li>DDNS 域名目标（经注册表渲染成 1.1.1.1 / 2.2.2.2）——审计推断的主场景；</li>
 *   <li>纯 IP 目标——完全不过注册表（Lease.NONE、零锁），交错同样成立，说明缺口不是 DDNS 专属；</li>
 *   <li>锁语义本身——读锁不排斥普通下发、写锁才排斥，用来解释上面两条为什么能发生。</li>
 * </ol>
 *
 * <p><b>断言口径</b>：断言「期望的正确行为」（编辑的目标必须是最终生效的那个，且库与节点一致），
 * 缺陷存在时用例为红，失败信息里带节点实际状态、下发报文类型序列、对账 notes。
 * 交错点用锁存器卡死，不依赖 sleep 或线程调度先后：假节点在「已按新配置生效、尚未应答」处阻塞，
 * 测试线程此时才开始对账，因此「对账读到的是旧快照」是确定事件。
 */
class EditReconcileInterleaveTest {

    private static final long NODE_ID = 41L;
    private static final long FORWARD_ID = 9L;
    private static final int TUNNEL_ID = 16;
    private static final int PORT = 20000;
    private static final String DOMAIN = "edit.example.com";
    private static final String SERVICE = FORWARD_ID + "_" + 1 + "_" + 0;
    private static final String SERVICE_TCP = SERVICE + "_tcp";
    private static final String SERVICE_UDP = SERVICE + "_udp";

    private static final String OLD_DOMAIN_REMOTE = DOMAIN + ":8080";
    private static final String NEW_DOMAIN_REMOTE = "2.2.2.2:8080";
    private static final String OLD_IP_REMOTE = "10.9.8.7:8080";
    private static final String NEW_IP_REMOTE = "10.9.8.8:8080";

    /** 另一条转发（不同 id）的目标：用来证明写锁按 forwardId 分片，不是全局一把。 */
    private static final long OTHER_FORWARD_ID = 10L;
    private static final String OTHER_REMOTE = "10.9.8.9:8080";

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "edit-reconcile-interleave");
        thread.setDaemon(true);
        return thread;
    });

    /** 假节点上「当前真实存在」的服务名 → 服务报文（等价于节点 gost.json 里的一项）。 */
    private final Map<String, JSONObject> applied = new ConcurrentHashMap<>();
    /** 节点收到过的报文类型，按到达顺序（失败信息里用来证明下发序列）。 */
    private final List<String> sentTypes = new ArrayList<>();
    /** 让下一个 UpdateService 在「已生效、未应答」处阻塞，制造交错窗口。 */
    private final AtomicBoolean blockNextUpdate = new AtomicBoolean();
    private final CountDownLatch updateApplied = new CountDownLatch(1);
    private final CountDownLatch releaseUpdate = new CountDownLatch(1);

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private ChainTunnelService chainTunnelService;
    private TunnelService tunnelService;
    /** 节点上的 forward_port 行对应的转发 id；默认只有被测的那一条。 */
    private List<Long> portForwardIds = List.of(FORWARD_ID);
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private DdnsAddressRegistry registry;
    /** 库里的 forward.remoteAddr；只有 updateById / 对应回滚才会改它。 */
    private volatile String dbRemoteAddr;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        applied.clear();
        synchronized (sentTypes) {
            sentTypes.clear();
        }
        blockNextUpdate.set(false);

        // 真实注册表：构造即通过 DdnsTargetRenderer.install 接管渲染/探针/下发凭证
        registry = new DdnsAddressRegistry();
        registry.put(DOMAIN, "1.1.1.1");
        registry.markLoaded(Set.of(DOMAIN));

        dbRemoteAddr = OLD_DOMAIN_REMOTE;

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        NodeService nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        UserService userService = mock(UserService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);

        User adminRow = new User();
        adminRow.setId(1L);
        adminRow.setRoleId(0);
        when(userService.getById(any())).thenReturn(adminRow);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);

        // 每次 selectById 都按「此刻」的库值新建对象：对账读到的是它开始那一刻的库快照
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> dbForward(asLong(invocation.getArgument(0))));
        // duplicateForwardName 与 resyncChainResources 都走 this.list；空表 => 无重名、无中继链候选
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(forwardMapper.updateById(any())).thenAnswer(invocation -> {
            Forward written = invocation.getArgument(0);
            if (Objects.equals(written.getId(), FORWARD_ID)) {
                dbRemoteAddr = written.getRemoteAddr();
            }
            return 1;
        });
        when(forwardPortService.getOne(any())).thenAnswer(invocation -> portRow());
        when(forwardPortService.list(any())).thenAnswer(invocation -> {
            Object wrapper = invocation.getArgument(0);
            String segment = wrapper instanceof QueryWrapper<?> qw ? qw.getSqlSegment() : "";
            if (segment != null && segment.contains("forward_id")) {
                // getNodePort 用 ne("forward_id") 排除自己：自己占的端口不能算占用
                return new ArrayList<ForwardPort>();
            }
            List<ForwardPort> rows = new ArrayList<>();
            for (Long forwardId : portForwardIds) {
                rows.add(portRow(forwardId, (int) (PORT + (forwardId - FORWARD_ID))));
            }
            return rows;
        });
        when(forwardPortService.updateById(any())).thenReturn(true);
        when(chainTunnelService.list(any())).thenAnswer(invocation -> new ArrayList<>(List.of(chainEntry())));
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(tunnelService.getById(any())).thenReturn(tunnel());

        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        releaseUpdate.countDown();
        pool.shutdownNow();
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
        applied.clear();
        if (registry != null) {
            registry.clear();
        }
        DdnsTargetRenderer.reset();
    }

    // ================================================================ 用例

    @Test
    @DisplayName("DDNS 域名：编辑已让节点生效、尚未写库时自动对账不得按旧快照把节点改回去")
    void reconcileDuringEditWindowMustNotOverwriteNodeWithStaleSnapshot() throws Exception {
        Interleave result = runInterleavedEdit(OLD_DOMAIN_REMOTE, NEW_DOMAIN_REMOTE);

        assertEquals(0, result.editResult.getCode(), "编辑本身应成功：" + result.editResult.getMsg());
        assertEquals(NEW_DOMAIN_REMOTE, dbRemoteAddr, "编辑成功后库必须是用户的新目标");
        assertEquals(NEW_DOMAIN_REMOTE, nodeTarget(SERVICE_TCP),
                "编辑目标必须是最终生效的那个：节点与库不得新旧不一致" + result.evidence());
        assertTrue(result.notes.stream().anyMatch(note -> note.contains("转发" + FORWARD_ID + " 正在被编辑")),
                "对账必须是因为取不到该转发的写锁而整条跳过，不是靠时间差躲过去的" + result.evidence());
        assertEquals(List.of("AddService", "UpdateService"), result.sentTypes,
                "对账不得再对这条转发下发删除/重建" + result.evidence());
    }

    @Test
    @DisplayName("纯 IP 目标：同样的交错不得按旧快照回写（这条路径连锁都不取）")
    void reconcileDuringEditWindowOverwritesPlainIpTargetToo() throws Exception {
        assertTrue(DdnsDomainGate.targetHosts(OLD_IP_REMOTE).isEmpty(),
                "字面量 IP 不应产生域名锁键");
        DdnsDomainGate.Lease lease = registry.beginDispatch(OLD_IP_REMOTE, 0);
        assertSame(DdnsDomainGate.Lease.NONE, lease, "纯 IP 下发必须拿到空凭证（零锁）");
        lease.close();

        Interleave result = runInterleavedEdit(OLD_IP_REMOTE, NEW_IP_REMOTE);

        assertEquals(0, result.editResult.getCode(), "编辑本身应成功：" + result.editResult.getMsg());
        assertEquals(NEW_IP_REMOTE, dbRemoteAddr, "编辑成功后库必须是用户的新目标");
        assertEquals(NEW_IP_REMOTE, nodeTarget(SERVICE_TCP),
                "纯 IP 目标同样必须是编辑后的那个：节点与库不得新旧不一致" + result.evidence());
        assertTrue(result.notes.stream().anyMatch(note -> note.contains("转发" + FORWARD_ID + " 正在被编辑")),
                "纯 IP 路径同样要走写锁跳过，不能只靠 DDNS 闸门" + result.evidence());
        assertEquals(List.of("AddService", "UpdateService"), result.sentTypes,
                "对账不得再对这条转发下发删除/重建" + result.evidence());
    }

    @Test
    @DisplayName("对照：库与节点都是新目标时对账不判漂移（红是旧快照造成的，不是摘要算错）")
    void reconcileWithFreshSnapshotKeepsNodeUntouched() {
        dbRemoteAddr = NEW_DOMAIN_REMOTE;
        seedNodeWith(NEW_DOMAIN_REMOTE);
        List<String> notes = forwardService.resyncNodeServices(NODE_ID,
                new ArrayList<>(applied.keySet()), List.of(), nodeDigests());

        assertEquals(NEW_DOMAIN_REMOTE, nodeTarget(SERVICE_TCP), "摘要一致时对账不应重建服务");
        assertTrue(notes.stream().noneMatch(note -> note.contains("服务重建")),
                "摘要一致时不得判漂移：" + notes);
    }

    @Test
    @DisplayName("同名域名上普通下发之间只取共享读锁（互不排斥），只有 DDNS 切换的写锁被挡住")
    void sharedDispatchLeasesDoNotExcludeEachOtherWhileTransitionWaits() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<DdnsDomainGate.Lease> firstLease = new AtomicReference<>();
        // 读锁在同一线程里可重入，同线程再取一次证明不了任何事：第一个凭证必须由别的线程持有
        Thread holder = new Thread(() -> {
            firstLease.set(registry.beginDispatch(DOMAIN + ":8080", 2000));
            holding.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            DdnsDomainGate.Lease lease = firstLease.get();
            if (lease != null) {
                lease.close();
            }
        }, "edit-reconcile-lease-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS), "持有读锁的线程应已启动");
        assertNotNull(firstLease.get(), "测试前置：普通下发应能拿到共享凭证");

        DdnsDomainGate.Lease second = registry.beginDispatch(DOMAIN + ":9090", 0);
        assertNotNull(second, "普通下发之间是共享读锁，编辑/删除/对账不得互相排斥");
        try {
            DdnsDomainGate.Lease third = registry.beginDispatch(OLD_DOMAIN_REMOTE, 0);
            assertNotNull(third, "同域名上第三把读锁同样应成功（自动对账走的就是这条路）");
            third.close();
        } finally {
            second.close();
        }

        // 对照：真正该被挡住的是 DDNS 切换的写锁
        assertEquals(null, registry.beginTransitionLease(DOMAIN, "DDNS 规划新地址", 0),
                "切换窗口的写锁必须被在读的普通下发挡住，否则切换会与下发交叉");

        release.countDown();
        holder.join(5000);
        DdnsDomainGate.Lease window = registry.beginTransitionLease(DOMAIN, "DDNS 规划新地址", 0);
        assertNotNull(window, "普通下发全部释放后，切换窗口应能拿到写锁");
        window.close();
    }

    // ================================================================ 锁的释放与分片

    @Test
    @DisplayName("编辑抛异常：写锁必须释放，异常不能让这条转发永久锁死")
    void editExceptionReleasesForwardLock() throws Exception {
        when(chainTunnelService.list(any())).thenThrow(new IllegalStateException("模拟构建阶段异常"));
        authenticate();

        assertThrows(IllegalStateException.class,
                () -> forwardService.updateForward(updateDto(NEW_DOMAIN_REMOTE)),
                "构建阶段的异常照原样冒泡（既有行为），但释放必须发生在 finally 里");
        assertTrue(lockReleased(FORWARD_ID), "编辑抛异常后写锁必须已释放，否则这条转发再也编辑不了、也对不了账");

        // 重新打桩必须先 reset：对着「已经在抛异常」的桩再调一次 when(...)，抛的就是它本身
        reset(chainTunnelService);
        when(chainTunnelService.list(any())).thenAnswer(invocation -> new ArrayList<>(List.of(chainEntry())));
        R retry = forwardService.updateForward(updateDto(NEW_DOMAIN_REMOTE));
        assertEquals(0, retry.getCode(), "锁没泄漏，重试应能正常完成：" + retry.getMsg());
        assertEquals(NEW_DOMAIN_REMOTE, dbRemoteAddr, "重试成功后库应是新目标");
    }

    @Test
    @DisplayName("编辑下发失败：写锁必须释放，节点离线不算「编辑进行中」")
    void editFailureReleasesForwardLock() throws Exception {
        nodeSessions.remove(NODE_ID);
        authenticate();

        R result = forwardService.updateForward(updateDto(NEW_DOMAIN_REMOTE));

        assertTrue(result.getCode() != 0, "节点不在线时编辑必须报错，不能假装成功");
        assertTrue(String.valueOf(result.getMsg()).contains("节点不在线"), "错误信息应来自节点：" + result.getMsg());
        assertEquals(OLD_DOMAIN_REMOTE, dbRemoteAddr, "下发失败时库保持原值");
        assertTrue(lockReleased(FORWARD_ID), "下发失败后写锁必须已释放");
    }

    @Test
    @DisplayName("对账抛异常：写锁必须释放，下一轮对账照常进行")
    void reconcileExceptionReleasesForwardLock() throws Exception {
        dbRemoteAddr = OLD_DOMAIN_REMOTE;
        seedNodeWith(OLD_DOMAIN_REMOTE);
        when(tunnelService.getById(any())).thenThrow(new IllegalStateException("模拟隧道查询异常"));

        List<String> notes = forwardService.resyncNodeServices(NODE_ID,
                new ArrayList<>(applied.keySet()), List.of(), nodeDigests());

        assertTrue(notes.stream().anyMatch(note -> note.contains("补齐转发" + FORWARD_ID + "@node" + NODE_ID + " 异常")),
                "异常应被写进本轮 notes：" + notes);
        assertTrue(lockReleased(FORWARD_ID), "对账抛异常后写锁必须已释放，否则这条转发永远停留在锁内");

        reset(tunnelService);
        when(tunnelService.getById(any())).thenReturn(tunnel());
        List<String> again = forwardService.resyncNodeServices(NODE_ID,
                new ArrayList<>(applied.keySet()), List.of(), nodeDigests());
        assertTrue(again.stream().noneMatch(note -> note.contains("异常")), "释放后下一轮应正常完成：" + again);
    }

    @Test
    @DisplayName("不同转发互不阻塞：一条被编辑占住，另一条照常对账")
    void editingOneForwardDoesNotBlockReconcileOfAnother() throws Exception {
        portForwardIds = List.of(FORWARD_ID, OTHER_FORWARD_ID);
        dbRemoteAddr = OLD_DOMAIN_REMOTE;
        seedNodeWith(OLD_DOMAIN_REMOTE);

        blockNextUpdate.set(true);
        Future<R> edit = pool.submit(() -> {
            authenticate();
            return forwardService.updateForward(updateDto(NEW_DOMAIN_REMOTE));
        });
        assertTrue(updateApplied.await(5, TimeUnit.SECONDS), "假节点应已按新目标生效并阻塞在应答之前");

        List<String> notes = forwardService.resyncNodeServices(NODE_ID,
                new ArrayList<>(applied.keySet()), List.of(), nodeDigests());
        releaseUpdate.countDown();
        R editResult = edit.get(10, TimeUnit.SECONDS);

        assertEquals(0, editResult.getCode(), "编辑本身应成功：" + editResult.getMsg());
        assertTrue(notes.stream().anyMatch(note -> note.contains("转发" + FORWARD_ID + " 正在被编辑")),
                "被编辑的那条应整条跳过：" + notes);
        assertTrue(notes.stream().anyMatch(note -> note.contains(
                        "已按库补齐缺失服务 " + OTHER_FORWARD_ID + "_1_0@node" + NODE_ID)),
                "另一条转发不能被别人的锁连累：" + notes);
        assertTrue(applied.containsKey(OTHER_FORWARD_ID + "_1_0_tcp")
                        && applied.containsKey(OTHER_FORWARD_ID + "_1_0_udp"),
                "另一条转发的服务应已建到节点上：" + applied.keySet());
        assertEquals(NEW_DOMAIN_REMOTE, nodeTarget(SERVICE_TCP), "被编辑的那条仍是编辑后的目标");
    }

    /**
     * 从别的线程试取一次再放开：同一线程可重入，自己取永远成功，证明不了锁有没有释放。
     * 取证与释放必须在同一个线程里完成 —— ReentrantLock 只能由持锁线程解锁。
     */
    private boolean lockReleased(long forwardId) throws Exception {
        AtomicBoolean free = new AtomicBoolean();
        Thread probe = new Thread(() -> {
            ForwardWriteLocks.Lease lease = ForwardWriteLocks.acquire(500L, forwardId);
            if (lease != null) {
                free.set(true);
                lease.close();
            }
        }, "forward-lock-probe");
        probe.setDaemon(true);
        probe.start();
        probe.join(5000);
        return free.get();
    }

    // ================================================================ 交错装置

    /** 一轮「编辑下发 → 停在写库之前 → 自动对账 → 放行」的确定性交错。 */
    private Interleave runInterleavedEdit(String oldRemote, String newRemote) throws Exception {
        dbRemoteAddr = oldRemote;
        seedNodeWith(oldRemote);
        assertEquals(DdnsTargetRenderer.render(oldRemote), nodeTarget(SERVICE_TCP),
                "测试前置：节点上应是旧目标 " + oldRemote + "（DDNS 域名按注册表渲染成 IP）");

        blockNextUpdate.set(true);
        Future<R> edit = pool.submit(() -> {
            // JWT 解析走 RequestContextHolder（ThreadLocal），工作线程必须自己装配一次
            authenticate();
            return forwardService.updateForward(updateDto(newRemote));
        });
        assertTrue(updateApplied.await(5, TimeUnit.SECONDS),
                "假节点应已按新目标生效并阻塞在应答之前");
        assertEquals(oldRemote, dbRemoteAddr,
                "此刻正是审计窗口：节点已是新目标，库仍是旧快照");

        // 节点侧此时会上报「新目标」的清单与摘要；自动对账按库里的旧快照算期望值
        List<String> reportedServices = new ArrayList<>(applied.keySet());
        Map<String, String> reportedDigests = nodeDigests();
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, reportedServices, List.of(), reportedDigests);

        releaseUpdate.countDown();
        R editResult = edit.get(10, TimeUnit.SECONDS);
        List<String> types;
        synchronized (sentTypes) {
            types = List.copyOf(sentTypes);
        }
        return new Interleave(editResult, List.copyOf(notes), types);
    }

    private static final class Interleave {

        private final R editResult;
        private final List<String> notes;
        private final List<String> sentTypes;

        private Interleave(R editResult, List<String> notes, List<String> sentTypes) {
            this.editResult = editResult;
            this.notes = notes;
            this.sentTypes = sentTypes;
        }

        /** 失败信息里带上节点收到的报文序列与对账 notes，红的那一刻的因果链可复查。 */
        private String evidence() {
            return "（节点收到的报文：" + sentTypes + "；对账 notes：" + notes + "）";
        }
    }

    // ================================================================ 假节点

    /**
     * 假节点：applied 就是「节点上的真实配置」。
     * AddService/UpdateService 按服务名覆盖，DeleteService 按名删除；
     * UpdateService 可被要求「先生效、后阻塞应答」，交错的确定性来自这里，不来自 sleep。
     */
    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            String type = message.getString("type");
            synchronized (sentTypes) {
                sentTypes.add(type);
            }
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future == null) {
                return null;
            }
            GostDto reply = new GostDto();
            reply.setMsg("OK");
            if ("DeleteService".equals(type)) {
                JSONArray names = message.getJSONObject("data").getJSONArray("services");
                for (int i = 0; i < names.size(); i++) {
                    applied.remove(names.getString(i));
                }
                future.complete(reply);
                return null;
            }
            JSONArray services = message.getJSONArray("data");
            if (services != null) {
                for (int i = 0; i < services.size(); i++) {
                    JSONObject service = services.getJSONObject(i);
                    applied.put(service.getString("name"), service);
                }
            }
            if ("UpdateService".equals(type) && blockNextUpdate.compareAndSet(true, false)) {
                // 已按新配置生效；应答还没回，编辑线程停在这里 → 库尚未写入
                updateApplied.countDown();
                releaseUpdate.await(8, TimeUnit.SECONDS);
            }
            future.complete(reply);
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    /** 用真实下发报文把节点铺成某个目标地址的配置（等价于这条转发此前正常生效过）。 */
    private void seedNodeWith(String remoteAddr) {
        GostUtil.CreateForwardService(SERVICE, null, node(NODE_ID), forwardWith(FORWARD_ID, remoteAddr), portRow(), tunnel());
        assertEquals(Set.of(SERVICE_TCP, SERVICE_UDP), new LinkedHashSet<>(applied.keySet()),
                "测试前置：节点上应有 " + SERVICE_TCP + "/" + SERVICE_UDP);
    }

    /** 节点上报的摘要：与后端按库计算走同一个 normalizeServiceDigestList（口径唯一）。 */
    private Map<String, String> nodeDigests() {
        Map<String, String> digests = new HashMap<>();
        for (Map.Entry<String, JSONObject> entry : applied.entrySet()) {
            JSONObject service = entry.getValue();
            JSONObject handler = service.getJSONObject("handler");
            JSONArray nodes = service.getJSONObject("forwarder").getJSONArray("nodes");
            List<String> targets = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                targets.add(nodes.getJSONObject(i).getString("addr"));
            }
            digests.put(entry.getKey(), GostUtil.normalizeServiceDigestList(service.getString("addr"),
                    handler == null ? null : handler.getString("type"),
                    handler == null ? null : handler.getString("chain"),
                    targets));
        }
        return digests;
    }

    private String nodeTarget(String serviceName) {
        JSONObject service = applied.get(serviceName);
        if (service == null) {
            return "<服务缺失：" + applied.keySet() + ">";
        }
        JSONArray nodes = service.getJSONObject("forwarder").getJSONArray("nodes");
        return nodes.getJSONObject(0).getString("addr");
    }

    // ================================================================ 脚手架

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":1,\"role_id\":0,\"name\":\"admin\"}"
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ForwardUpdateDto updateDto(String remoteAddr) {
        ForwardUpdateDto dto = new ForwardUpdateDto();
        dto.setId(FORWARD_ID);
        dto.setUserId(1);
        dto.setName("edit-interleave");
        dto.setRemoteAddr(remoteAddr);
        dto.setStrategy("round");
        dto.setInPort(PORT);
        return dto;
    }

    private Forward dbForward(long forwardId) {
        return forwardWith(forwardId, forwardId == FORWARD_ID ? dbRemoteAddr : OTHER_REMOTE);
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static Forward forwardWith(long forwardId, String remoteAddr) {
        Forward forward = new Forward();
        forward.setId(forwardId);
        forward.setUserId(1);
        forward.setTunnelId(TUNNEL_ID);
        forward.setName("edit-interleave");
        forward.setStatus(1);
        forward.setRemoteAddr(remoteAddr);
        forward.setStrategy("round");
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

    private static ChainTunnel chainEntry() {
        ChainTunnel entry = new ChainTunnel();
        entry.setTunnelId((long) TUNNEL_ID);
        entry.setChainType(1);
        entry.setNodeId(NODE_ID);
        return entry;
    }

    private static ForwardPort portRow() {
        return portRow(FORWARD_ID, PORT);
    }

    private static ForwardPort portRow(long forwardId, int port) {
        ForwardPort row = new ForwardPort();
        row.setForwardId(forwardId);
        row.setNodeId(NODE_ID);
        row.setPort(port);
        return row;
    }

    private static Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.41");
        node.setPort("20000-20010");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
