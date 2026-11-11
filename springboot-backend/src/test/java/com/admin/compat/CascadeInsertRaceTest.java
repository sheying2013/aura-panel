package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.ResourceLifecycleLocks;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsDomainGate;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.SpeedLimit;
import com.admin.entity.StatisticsFlow;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.SpeedLimitService;
import com.admin.service.StatisticsFlowService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.CascadeDeletePurger;
import com.admin.service.impl.TunnelServiceImpl;
import com.admin.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「最后一道漂移检查通过之后、级联删库之前」新插入一条转发的窗口测试。
 *
 * <p>被测顺序（TunnelServiceImpl.deleteTunnel / UserServiceImpl.deleteUser 同构）：
 * <pre>
 *   lease = ResourceLifecycleLocks.acquire(tunnel|user 键)  // 现由生命周期锁包住整段
 *   forwardList = list(tunnel_id|user_id)      // 快照
 *   ddnsLease = beginDispatch(...)             // 只锁快照里出现过的域名
 *   drift #1：list 重读，与快照 id 求差集
 *   for (...) deleteForward(id)                // 逐条删节点服务 + 删行
 *   drift #2：list 重读，与快照 id 求差集      // 「最后一道」
 *   purge*DatabaseRecords(..., forwardIds)     // 按 tunnel_id|user_id 无条件 DELETE
 *   release
 * </pre>
 *
 * <p>三层断言，缺一不可：
 * <ol>
 *   <li><b>锁把窗口关掉</b>（tunnelDeleteBlocksRacingCreateForwardInsideTheWindow /
 *       userDeleteBlocksRacingCreateForwardInsideTheWindow）：竞态一侧照抄真实 createForward 的次序 ——
 *       先 acquire(用户键, 隧道键)，拿到锁之后才重读隧道/权限，再落库 + 下发。删除方在 drift #2 之后
 *       仍处于临界区内，创建方此刻必然拿不到锁（ReentrantLock 互斥，与调度无关）；删除结束释放后
 *       创建方重读发现隧道/授权行已被 purge，直接失败，一行一服务都不会留下。</li>
 *   <li><b>绕过锁就复现</b>（*PurgesForwardInsertedInsideTheWindowWhenTheLifecycleLockIsBypassed）：
 *       直接往 Store 写行并下发节点服务，模拟锁失效（多进程/多 JVM 部署，或将来有人把 acquire 删掉）
 *       时的失效模式。drift 的两条判据都不命中：新 id 不在快照里（没人调 deleteForward，节点服务没人删），
 *       但它落在 purge 的 {@code where tunnel_id=} / {@code where user_id=} 里（行照删）。
 *       断言的是这个不变量被打破：节点上留下一条库里再也查不到的服务，端口行成孤儿。
 *       残留不会永远存在 —— 节点下次上报服务清单时 NodeOrphanSweep 会清掉（判据是服务名首段没有对应行），
 *       但从 purge 到那次上报之间它一直在转发，且这正是「锁」而不是「兜底清扫」才是第一道保证的原因。</li>
 *   <li><b>对照组</b>（*AbortsWhenInsertIsVisibleToTheFinalDriftCheck /
 *       forwardPresentAtSnapshotTimeIsCleanedUpCompletely）：插入发生在 drift #2 读取之前 → 整体放弃、
 *       不删任何数据；插入发生在快照里 → 被逐条 deleteForward 清干净。后者同时校验替身可信：
 *       deleteForward 确实会删掉节点服务，所以第 2 层「服务残留」的唯一原因就是它没被调用。</li>
 * </ol>
 *
 * <p>这不是「方法没被调用」这类调用序列断言：{@link Store} 同时实现了库表与节点上的 gost 资源，
 * 被测代码经 mock 的 mapper 层（list/remove/deleteForward）真实读写它。所以断言的是
 * 「删完之后节点上还剩什么、库里还剩什么」，而不是「某个方法调用了几次」。
 * 唯一被替身替代的只有两处被测代码已经留出替换点的位置：节点下发钩子
 * （deleteService/deleteChains/deleteLimiters，真实实现是 GostUtil 静态调用）与 mapper 本身。
 *
 * <p>竞态是确定性的：第 2 层的插入发生在 {@code forwardService.list} 第 3 次调用（即 drift #2）
 * 返回之后，由另一条线程写入并 join —— 既保证「读已经发生」，也保证「写对后续读取可见」。
 * 第 1 层的创建线程在同一个钩子里启动，钩子返回前先确认「此刻仍未拿到锁」，删除方退出临界区它才继续。
 */
class CascadeInsertRaceTest {

    private static final long TUNNEL_ID = 7L;
    private static final long USER_ID = 3L;
    private static final long USER_TUNNEL_ID = 21L;
    private static final long ENTRY_NODE_ID = 101L;
    private static final long SNAPSHOT_FORWARD_ID = 401L;
    private static final long SNAPSHOT_PORT_ID = 1401L;
    private static final long LIMITER_ID = 501L;
    /** 竞态插入的转发：纯 IP 目标，不经过 DDNS 闸门，正是真实新增转发的形态。 */
    private static final long RACING_FORWARD_ID = 402L;
    private static final long RACING_PORT_ID = 1402L;
    private static final String RACING_REMOTE = "1.1.1.1:8080";
    private static final String DOMAIN = "race.example.com";
    /** 快照里的转发挂在已注册域名上，删除方才会真的拿到该域名的读锁。 */
    private static final String SNAPSHOT_REMOTE = DOMAIN + ":8080";
    /** deleteTunnel / deleteUser 都是第 3 次读转发 = 「最后一道」漂移检查。 */
    private static final int FINAL_DRIFT_READ = 3;
    /** 真实值是 CREATE_WAIT_MILLIS=10s；这里取 5s，锁没被释放时测试快速失败而不是挂住。 */
    private static final long CREATE_WAIT_MILLIS = 5_000L;

    private final Store store = new Store();
    /** setUp 里 reset 之后重建：DdnsAddressRegistry 的构造器才会装上闸门探针。 */
    private DdnsAddressRegistry registry;

    private ForwardService forwardService;
    private ForwardPortService forwardPortService;
    private UserTunnelService userTunnelService;
    private SpeedLimitService speedLimitService;
    private ChainTunnelService chainTunnelService;
    private StatisticsFlowService statisticsFlowService;
    private TestTunnelService tunnelService;
    private TestUserService userService;

    private int forwardReads;
    private int hookOrdinal = -1;
    private Runnable hookBefore;
    private Runnable hookAfter;
    /** 删除阶段是否仍独占着该域名的读锁（DDNS 切换进不来）——漏洞成立的前提条件。 */
    private boolean ddnsTransitionBlocked;

    @BeforeEach
    void setUp() {
        DdnsTargetRenderer.reset();
        // 构造器安装 render / dispatch / digestTrust 探针：真实进程里由 Spring 单例完成
        registry = new DdnsAddressRegistry();
        registry.put(DOMAIN, "10.0.0.1");
        store.seed();

        forwardService = mock(ForwardService.class);
        forwardPortService = mock(ForwardPortService.class);
        userTunnelService = mock(UserTunnelService.class);
        speedLimitService = mock(SpeedLimitService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        statisticsFlowService = mock(StatisticsFlowService.class);

        when(forwardService.list(any())).thenAnswer(invocation -> {
            int ordinal = ++forwardReads;
            if (ordinal == hookOrdinal && hookBefore != null) {
                hookBefore.run();
            }
            List<Forward> rows = store.selectForwards(invocation.getArgument(0));
            store.timeline.add("read:forward:" + ordinal + ":size=" + rows.size());
            if (ordinal == hookOrdinal && hookAfter != null) {
                hookAfter.run();
            }
            return rows;
        });
        when(chainTunnelService.list(any())).thenAnswer(invocation -> store.selectChainTunnels(invocation.getArgument(0)));
        when(speedLimitService.list(any())).thenAnswer(invocation -> store.selectSpeedLimits(invocation.getArgument(0)));
        when(forwardService.deleteForward(anyLong())).thenAnswer(invocation -> store.deleteForward(invocation.getArgument(0)));
        when(forwardService.remove(any())).thenAnswer(invocation -> store.removeForwards(invocation.getArgument(0)));
        when(forwardPortService.remove(any())).thenAnswer(invocation -> store.removePorts(invocation.getArgument(0)));
        when(userTunnelService.remove(any())).thenAnswer(invocation -> store.removeUserTunnels(invocation.getArgument(0)));
        when(speedLimitService.remove(any())).thenAnswer(invocation -> store.removeSpeedLimits(invocation.getArgument(0)));
        when(chainTunnelService.remove(any())).thenAnswer(invocation -> store.removeChainTunnels(invocation.getArgument(0)));
        when(statisticsFlowService.remove(any())).thenAnswer(invocation -> store.removeFlows(invocation.getArgument(0)));

        tunnelService = new TestTunnelService();
        ReflectionTestUtils.setField(tunnelService, "forwardService", forwardService);
        ReflectionTestUtils.setField(tunnelService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(tunnelService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(tunnelService, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(tunnelService, "forwardPortService", forwardPortService);

        userService = new TestUserService();
        ReflectionTestUtils.setField(userService, "forwardService", forwardService);
        ReflectionTestUtils.setField(userService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(userService, "statisticsFlowService", statisticsFlowService);

        CascadeDeletePurger purger = new CascadeDeletePurger();
        ReflectionTestUtils.setField(purger, "forwardService", forwardService);
        ReflectionTestUtils.setField(purger, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(purger, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(purger, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(purger, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(purger, "statisticsFlowService", statisticsFlowService);
        ReflectionTestUtils.setField(purger, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(purger, "userService", userService);

        ReflectionTestUtils.setField(tunnelService, "cascadeDeletePurger", purger);
        ReflectionTestUtils.setField(userService, "cascadeDeletePurger", purger);
    }

    @AfterEach
    void tearDown() {
        DdnsTargetRenderer.reset();
        if (registry != null) {
            registry.clear();
        }
    }

    // ------------------------------------------------------------------
    // 第 2 层：生命周期锁被绕过时的失效模式（机制回归，不是期望行为）
    // ------------------------------------------------------------------

    /**
     * 锁一旦失效（多进程/多 JVM 部署，或将来把 acquire 删掉），这条通道立刻复活：插入落在
     * drift #2 之后、purge 之前 —— 检查看不见它（id 不在快照里），purge 却按 tunnel_id 把行删掉，
     * 节点上留下一条没有库记录能对上的服务。本用例把这套不变量破坏钉成回归。
     */
    @Test
    void tunnelDeletePurgesForwardInsertedInsideTheWindowWhenTheLifecycleLockIsBypassed() {
        hookOrdinal = FINAL_DRIFT_READ;
        hookAfter = () -> {
            // 删除方此刻仍握着该域名的读锁：DDNS 切换在这段窗口里排队，这就是闸门唯一能保护的范围。
            DdnsDomainGate.Lease probe = registry.beginTransitionLease(DOMAIN, "race-probe", 50L);
            ddnsTransitionBlocked = probe == null;
            if (probe != null) {
                probe.close();
            }
            insertRacingForwardOnAnotherThread();
        };

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), "竞态对调用方完全不可见，删除返回成功：" + result.getMsg());
        assertEquals(FINAL_DRIFT_READ, forwardReads, "必须真的走到了最后一道检查");
        assertTrue(ddnsTransitionBlocked, "删除阶段持有该域名读锁：DDNS 切换被挡在外面，而新转发根本不在这把锁里");

        // 最后一道检查读到的节点/库状态里没有这条新转发（快照里的行已被逐条删掉）
        String finalRead = "read:forward:" + FINAL_DRIFT_READ + ":size=0";
        assertTrue(store.timeline.contains(finalRead), "最后一道检查应读到空表：" + store.timeline);
        assertTrue(store.timeline.indexOf(finalRead) < store.timeline.indexOf("insert:forward:" + RACING_FORWARD_ID),
                "插入必须发生在最后一道检查之后：" + store.timeline);
        assertTrue(store.timeline.indexOf("insert:forward:" + RACING_FORWARD_ID) < store.timeline.indexOf("db:forward"),
                "插入必须发生在级联删库之前：" + store.timeline);

        // 库：purge 按 tunnel_id 无条件删，新转发的行被一起删掉（数据库里查不到它了）
        assertFalse(store.forwards.containsKey(RACING_FORWARD_ID), "新转发的行已被级联删掉");
        assertFalse(store.tunnels.containsKey(TUNNEL_ID), "隧道行已删");

        // 节点：这条转发的服务从来没被下发过删除。它不会永远留着 —— 节点下次上报服务清单时
        // NodeOrphanSweep 会按「服务名首段找不到转发行」清掉；但从 purge 到那次上报之间，
        // 它一直占着端口在转发，且库与节点的不一致是真实存在的。锁是唯一不依赖上报时机的保证。
        assertTrue(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_tcp"),
                "节点上残留 tcp 服务：" + store.nodeServices);
        assertTrue(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_udp"),
                "节点上残留 udp 服务：" + store.nodeServices);

        // 端口行：purge 只按快照里的 forward_id 反查，新行的端口行成了查不回来的孤儿
        assertTrue(store.ports.containsKey(RACING_PORT_ID), "新转发的 forward_port 行成了孤儿");

        // 反证：快照里的转发与隧道级资源确实被清干净了，残留只来自这个窗口
        assertFalse(store.hasNodeService(ENTRY_NODE_ID, serviceName(SNAPSHOT_FORWARD_ID) + "_tcp"), store.nodeServices.toString());
        assertFalse(store.hasNodeService(ENTRY_NODE_ID, TUNNEL_ID + "_tls"), store.nodeServices.toString());
        assertFalse(store.hasNodeChain(ENTRY_NODE_ID, "chains_" + TUNNEL_ID), store.nodeChains.toString());
        assertFalse(store.hasNodeLimiter(ENTRY_NODE_ID, LIMITER_ID), store.nodeLimiters.toString());
        assertFalse(store.ports.containsKey(SNAPSHOT_PORT_ID), "快照里的端口行已删");

        // 这条转发从头到尾没进过 deleteForward，所以谁也没去删它的节点服务
        verify(forwardService, never()).deleteForward(RACING_FORWARD_ID);

        // 被打破的不变量：节点上的服务在库里必须有一条转发行与之对应
        assertTrue(store.hasOrphanNodeService(), "节点服务必须找不到对应转发行：" + store.nodeServices);
    }

    /** 同一条通道的用户侧：purge 按 user_id 无条件 DELETE，绕过锁的插入同样只剩节点残留。 */
    @Test
    void userDeletePurgesForwardInsertedInsideTheWindowWhenTheLifecycleLockIsBypassed() {
        hookOrdinal = FINAL_DRIFT_READ;
        hookAfter = () -> {
            DdnsDomainGate.Lease probe = registry.beginTransitionLease(DOMAIN, "race-probe", 50L);
            ddnsTransitionBlocked = probe == null;
            if (probe != null) {
                probe.close();
            }
            insertRacingForwardOnAnotherThread();
        };

        R result = userService.deleteUser(USER_ID);

        assertEquals(0, result.getCode(), "竞态对调用方完全不可见，删除返回成功：" + result.getMsg());
        assertEquals(FINAL_DRIFT_READ, forwardReads, "必须真的走到了最后一道检查");
        assertTrue(ddnsTransitionBlocked, "删除阶段持有该域名读锁");

        String finalRead = "read:forward:" + FINAL_DRIFT_READ + ":size=0";
        assertTrue(store.timeline.contains(finalRead), "最后一道检查应读到空表：" + store.timeline);
        assertTrue(store.timeline.indexOf(finalRead) < store.timeline.indexOf("insert:forward:" + RACING_FORWARD_ID),
                "插入必须发生在最后一道检查之后：" + store.timeline);
        assertTrue(store.timeline.indexOf("insert:forward:" + RACING_FORWARD_ID) < store.timeline.indexOf("db:forward"),
                "插入必须发生在级联删库之前：" + store.timeline);

        assertFalse(store.forwards.containsKey(RACING_FORWARD_ID), "新转发的行已被按 user_id 级联删掉");
        assertFalse(store.users.containsKey(USER_ID), "用户行已删");
        assertTrue(store.flows.isEmpty(), "流量行已删");

        assertTrue(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_tcp"),
                "节点上残留 tcp 服务：" + store.nodeServices);
        assertTrue(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_udp"),
                "节点上残留 udp 服务：" + store.nodeServices);
        assertTrue(store.ports.containsKey(RACING_PORT_ID), "新转发的 forward_port 行成了孤儿");

        assertFalse(store.hasNodeService(ENTRY_NODE_ID, serviceName(SNAPSHOT_FORWARD_ID) + "_tcp"), store.nodeServices.toString());
        assertFalse(store.ports.containsKey(SNAPSHOT_PORT_ID));
        verify(forwardService, never()).deleteForward(RACING_FORWARD_ID);
        assertTrue(store.hasOrphanNodeService(), "节点服务必须找不到对应转发行：" + store.nodeServices);
    }

    // ------------------------------------------------------------------
    // 第 1 层：真实创建路径（先拿锁）进不了这个窗口
    // ------------------------------------------------------------------

    /**
     * 创建侧照抄 ForwardServiceImpl.createForward 的次序：acquire(用户键, 隧道键) →
     * 锁内重读隧道 → 落库 + 逐入口节点下发。
     *
     * <p>删除方在 drift #2 之后仍握着自己的键，创建线程在这段窗口里必定拿不到锁；等删除方释放，
     * 隧道行已被 purge 删掉，创建方在锁内重读只拿到「隧道不存在」—— 一行一服务都不会留下。
     */
    @Test
    void tunnelDeleteBlocksRacingCreateForwardInsideTheWindow() {
        RacingCreate racing = new RacingCreate();
        hookOrdinal = FINAL_DRIFT_READ;
        hookAfter = racing::startInsideDeleteWindow;

        R result = tunnelService.deleteTunnel(TUNNEL_ID);
        R racingResult = racing.awaitResult();

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(FINAL_DRIFT_READ, forwardReads, "必须真的走到了最后一道检查");
        assertFalse(racing.insertAttempted, "锁内的创建方不得在删除期间落库");
        assertNotEquals(0, racingResult.getCode(), "创建必须被拒");
        assertTrue(racingResult.getMsg().contains("隧道不存在"), racingResult.getMsg());
        assertTrue(store.timeline.contains("create:acquired"), store.timeline.toString());

        assertFalse(store.forwards.containsKey(RACING_FORWARD_ID), "不得留下转发行");
        assertFalse(store.ports.containsKey(RACING_PORT_ID), "不得留下端口行");
        assertFalse(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_tcp"), store.nodeServices.toString());
        assertFalse(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_udp"), store.nodeServices.toString());
        assertTrue(store.timeline.indexOf("db:tunnel") < store.timeline.indexOf("create:acquired"),
                "创建方只能在级联删库之后才拿到锁：" + store.timeline);
        assertFalse(store.hasOrphanNodeService(), "不变量：不存在库查不到的服务：" + store.nodeServices);
    }

    /**
     * 用户侧同构。锁顺序里隧道键在前（acquire 内部按字符串升序），删除方只握用户键，创建方会先在
     * 隧道键上通过、再卡在用户键上，只能等 deleteUser 释放。释放时用户-隧道授权行已被 purge 删掉，
     * 真实 createForward 的 checkUserPermissions 在这一步就会拒绝，不会走到落库。
     */
    @Test
    void userDeleteBlocksRacingCreateForwardInsideTheWindow() {
        RacingCreate racing = new RacingCreate();
        hookOrdinal = FINAL_DRIFT_READ;
        hookAfter = racing::startInsideDeleteWindow;

        R result = userService.deleteUser(USER_ID);
        R racingResult = racing.awaitResult();

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(FINAL_DRIFT_READ, forwardReads, "必须真的走到了最后一道检查");
        assertFalse(racing.insertAttempted, "锁内的创建方不得在删除期间落库");
        assertNotEquals(0, racingResult.getCode(), "创建必须被拒：" + racingResult.getMsg());
        assertTrue(store.timeline.contains("create:acquired"), store.timeline.toString());

        assertFalse(store.forwards.containsKey(RACING_FORWARD_ID), "不得留下转发行");
        assertFalse(store.ports.containsKey(RACING_PORT_ID), "不得留下端口行");
        assertFalse(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_tcp"), store.nodeServices.toString());
        assertFalse(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_udp"), store.nodeServices.toString());
        assertTrue(store.timeline.indexOf("db:user_tunnel") < store.timeline.indexOf("create:acquired"),
                "创建方只能在级联删库之后才拿到锁：" + store.timeline);
        assertFalse(store.hasOrphanNodeService(), "不变量：不存在库查不到的服务：" + store.nodeServices);
    }

    // ------------------------------------------------------------------
    // 对照组：证明第 2 层的残留只可能来自「检查之后」这个窗口
    // ------------------------------------------------------------------

    /** 同样的插入，只是发生在最后一道检查读到之前 —— 检查命中，整体放弃，行与节点资源都留着。 */
    @Test
    void tunnelDeleteAbortsWhenInsertIsVisibleToTheFinalDriftCheck() {
        hookOrdinal = FINAL_DRIFT_READ;
        hookBefore = this::insertRacingForwardOnAnotherThread;

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(-1, result.getCode(), "可见的插入必须被最后一道检查挡下");
        assertTrue(result.getMsg().contains("转发" + RACING_FORWARD_ID), result.getMsg());
        assertTrue(result.getMsg().contains("请重试"), result.getMsg());
        assertTrue(store.timeline.contains("read:forward:" + FINAL_DRIFT_READ + ":size=1"), store.timeline.toString());

        assertTrue(store.forwards.containsKey(RACING_FORWARD_ID), "放弃时不得删库");
        assertTrue(store.ports.containsKey(RACING_PORT_ID));
        assertTrue(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_tcp"), store.nodeServices.toString());
        assertTrue(store.tunnels.containsKey(TUNNEL_ID), "放弃时必须保留隧道行");
        assertFalse(store.timeline.contains("db:forward"), "放弃时不得进入级联删库：" + store.timeline);
    }

    @Test
    void userDeleteAbortsWhenInsertIsVisibleToTheFinalDriftCheck() {
        hookOrdinal = FINAL_DRIFT_READ;
        hookBefore = this::insertRacingForwardOnAnotherThread;

        R result = userService.deleteUser(USER_ID);

        assertEquals(-1, result.getCode(), "可见的插入必须被最后一道检查挡下");
        assertTrue(result.getMsg().contains("转发" + RACING_FORWARD_ID), result.getMsg());
        assertTrue(store.timeline.contains("read:forward:" + FINAL_DRIFT_READ + ":size=1"), store.timeline.toString());

        assertTrue(store.forwards.containsKey(RACING_FORWARD_ID), "放弃时不得删库");
        assertTrue(store.ports.containsKey(RACING_PORT_ID));
        assertTrue(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_tcp"), store.nodeServices.toString());
        assertTrue(store.users.containsKey(USER_ID), "放弃时必须保留用户行");
        assertFalse(store.timeline.contains("db:forward"), "放弃时不得进入级联删库：" + store.timeline);
    }

    /**
     * 同一条转发只要在快照时就存在，就会被逐条 deleteForward 清干净。
     * 这条同时校验替身本身是可信的：它确实会因为 deleteForward 而删掉节点服务，
     * 所以上一个测试里「服务残留」的唯一原因就是 deleteForward 没被调用。
     */
    @Test
    void forwardPresentAtSnapshotTimeIsCleanedUpCompletely() {
        insertRacingForward();

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        assertTrue(store.timeline.contains("read:forward:1:size=2"), store.timeline.toString());
        assertTrue(store.timeline.contains("db:deleteForward:" + RACING_FORWARD_ID), "快照里的转发必须被逐条删除：" + store.timeline);

        assertFalse(store.forwards.containsKey(RACING_FORWARD_ID));
        assertFalse(store.ports.containsKey(RACING_PORT_ID));
        assertFalse(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_tcp"), store.nodeServices.toString());
        assertFalse(store.hasNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_udp"), store.nodeServices.toString());
    }

    // ------------------------------------------------------------------
    // 竞态插入：另一条线程写入并 join，读已经发生、写对后续可见
    // ------------------------------------------------------------------

    private void insertRacingForwardOnAnotherThread() {
        Thread writer = new Thread(this::insertRacingForward, "racing-forward-insert");
        writer.setDaemon(true);
        writer.start();
        try {
            writer.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("并发插入线程被中断");
        }
        assertFalse(writer.isAlive(), "并发插入没有在 10s 内结束");
        assertTrue(store.forwards.containsKey(RACING_FORWARD_ID), "并发插入必须已经提交");
    }

    /** 一条新转发提交：forward 行 + forward_port 行 + 节点上已经下发好的 tcp/udp 服务。 */
    private void insertRacingForward() {
        Forward forward = new Forward();
        forward.setId(RACING_FORWARD_ID);
        forward.setUserId((int) USER_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setName("racing");
        forward.setRemoteAddr(RACING_REMOTE);
        store.forwards.put(RACING_FORWARD_ID, forward);

        ForwardPort port = new ForwardPort();
        port.setId(RACING_PORT_ID);
        port.setForwardId(RACING_FORWARD_ID);
        port.setNodeId(ENTRY_NODE_ID);
        port.setPort(20999);
        store.ports.put(RACING_PORT_ID, port);

        store.addNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_tcp");
        store.addNodeService(ENTRY_NODE_ID, serviceName(RACING_FORWARD_ID) + "_udp");
        store.timeline.add("insert:forward:" + RACING_FORWARD_ID);
    }

    /**
     * 真实创建路径的替身：与 ForwardServiceImpl.createForward 同序 —— 先抢生命周期锁（用户键 + 隧道键），
     * 拿到锁之后才重读隧道 / 用户-隧道授权，最后落库并逐入口节点下发。锁外的前置（取当前用户）不参与竞态。
     */
    private final class RacingCreate implements Runnable {

        final CountDownLatch attempted = new CountDownLatch(1);
        final CountDownLatch acquired = new CountDownLatch(1);
        final AtomicReference<R> result = new AtomicReference<>();
        volatile boolean insertAttempted;
        private Thread thread;

        /** 在删除方的临界区里启动；返回前确认创建方此刻仍未拿到锁，所以那个窗口里不可能有插入。 */
        void startInsideDeleteWindow() {
            thread = new Thread(this, "racing-forward-create");
            thread.setDaemon(true);
            thread.start();
            try {
                assertTrue(attempted.await(5, TimeUnit.SECONDS), "创建方没有开始抢锁");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待创建方抢锁被中断");
            }
            // 固定观察 200ms：删除方握着同一把 ReentrantLock，锁在的实现里创建方这段时间内不可能拿到；
            // 一旦 acquire 被移除/失效，创建方会在这个窗口里立刻拿到锁并落库，本断言随即失败（用例非空转）。
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200L);
            while (acquired.getCount() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, acquired.getCount(), "创建方在删除窗口内拿到了生命周期锁：窗口已经打开");
        }

        R awaitResult() {
            try {
                thread.join(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待创建方结束被中断");
            }
            assertFalse(thread.isAlive(), "创建方没有在 10s 内结束：锁可能没被释放");
            return result.get();
        }

        @Override
        public void run() {
            attempted.countDown();
            ResourceLifecycleLocks.Lease lease = ResourceLifecycleLocks.acquire(CREATE_WAIT_MILLIS,
                    ResourceLifecycleLocks.userKey(USER_ID), ResourceLifecycleLocks.tunnelKey(TUNNEL_ID));
            if (lease == null) {
                result.set(R.err("该用户或隧道正在被创建/删除，本次未改动任何节点，请稍后重试"));
                return;
            }
            try {
                acquired.countDown();
                store.timeline.add("create:acquired");
                if (store.tunnels.get(TUNNEL_ID) == null) {
                    result.set(R.err("隧道不存在"));
                    return;
                }
                if (store.userTunnels.get((int) USER_TUNNEL_ID) == null) {
                    result.set(R.err("用户没有该隧道的权限"));
                    return;
                }
                insertAttempted = true;
                insertRacingForward();
                result.set(R.ok());
            } finally {
                lease.close();
            }
        }
    }

    /** 与 ForwardServiceImpl.buildServiceName 同口径：forwardId_userId_userTunnelId。 */
    private static String serviceName(long forwardId) {
        return forwardId + "_" + USER_ID + "_" + USER_TUNNEL_ID;
    }

    // ------------------------------------------------------------------
    // 内存里的「库 + 节点」
    // ------------------------------------------------------------------

    private static final class Store {

        final Map<Long, Tunnel> tunnels = new LinkedHashMap<>();
        final Map<Long, User> users = new LinkedHashMap<>();
        final Map<Long, Forward> forwards = new LinkedHashMap<>();
        final Map<Long, ForwardPort> ports = new LinkedHashMap<>();
        final Map<Long, SpeedLimit> speedLimits = new LinkedHashMap<>();
        final Map<Integer, UserTunnel> userTunnels = new LinkedHashMap<>();
        final List<ChainTunnel> chainTunnels = new ArrayList<>();
        final Map<Long, StatisticsFlow> flows = new LinkedHashMap<>();

        /** 节点上真实存在的 gost 资源（service / chain / limiter）。 */
        final Map<Long, Set<String>> nodeServices = new LinkedHashMap<>();
        final Map<Long, Set<String>> nodeChains = new LinkedHashMap<>();
        final Map<Long, Set<Long>> nodeLimiters = new LinkedHashMap<>();

        /** 库写、节点下发、读、并发插入按发生顺序记在这里，用来断言先后关系。 */
        final List<String> timeline = new ArrayList<>();

        /** 节点上转发服务的命名：ForwardServiceImpl.buildServiceName(...) + _tcp/_udp。 */
        static final Pattern FORWARD_SERVICE = Pattern.compile("(\\d+)_\\d+_\\d+_(?:tcp|udp)");

        void seed() {
            Tunnel tunnel = new Tunnel();
            tunnel.setId(TUNNEL_ID);
            tunnel.setName("race");
            tunnel.setType(1);
            tunnel.setStatus(1);
            tunnels.put(TUNNEL_ID, tunnel);

            User user = new User();
            user.setId(USER_ID);
            user.setUser("race");
            user.setRoleId(1);
            users.put(USER_ID, user);

            UserTunnel permission = new UserTunnel();
            permission.setId((int) USER_TUNNEL_ID);
            permission.setUserId((int) USER_ID);
            permission.setTunnelId((int) TUNNEL_ID);
            userTunnels.put((int) USER_TUNNEL_ID, permission);

            Forward forward = new Forward();
            forward.setId(SNAPSHOT_FORWARD_ID);
            forward.setUserId((int) USER_ID);
            forward.setTunnelId((int) TUNNEL_ID);
            forward.setName("snapshot");
            forward.setRemoteAddr(SNAPSHOT_REMOTE);
            forwards.put(SNAPSHOT_FORWARD_ID, forward);

            ForwardPort port = new ForwardPort();
            port.setId(SNAPSHOT_PORT_ID);
            port.setForwardId(SNAPSHOT_FORWARD_ID);
            port.setNodeId(ENTRY_NODE_ID);
            port.setPort(20001);
            ports.put(SNAPSHOT_PORT_ID, port);

            ChainTunnel entry = new ChainTunnel();
            entry.setId(900L);
            entry.setTunnelId(TUNNEL_ID);
            entry.setNodeId(ENTRY_NODE_ID);
            entry.setChainType(1);
            entry.setPort(20001);
            chainTunnels.add(entry);

            SpeedLimit limit = new SpeedLimit();
            limit.setId(LIMITER_ID);
            limit.setTunnelId(TUNNEL_ID);
            limit.setName("limit");
            limit.setSpeed(10);
            speedLimits.put(LIMITER_ID, limit);

            StatisticsFlow flow = new StatisticsFlow();
            flow.setId(1L);
            flow.setUserId(USER_ID);
            flow.setFlow(1L);
            flow.setTotalFlow(1L);
            flow.setTime("2026-11-11");
            flows.put(1L, flow);

            addNodeService(ENTRY_NODE_ID, serviceName(SNAPSHOT_FORWARD_ID) + "_tcp");
            addNodeService(ENTRY_NODE_ID, serviceName(SNAPSHOT_FORWARD_ID) + "_udp");
            addNodeService(ENTRY_NODE_ID, TUNNEL_ID + "_tls");
            nodeChains.computeIfAbsent(ENTRY_NODE_ID, ignored -> new LinkedHashSet<>()).add("chains_" + TUNNEL_ID);
            nodeLimiters.computeIfAbsent(ENTRY_NODE_ID, ignored -> new LinkedHashSet<>()).add(LIMITER_ID);
        }

        // ---- 读（mapper 层 select） ----

        List<Forward> selectForwards(Wrapper<Forward> wrapper) {
            Condition condition = Condition.of(wrapper);
            List<Forward> rows = new ArrayList<>();
            for (Forward row : forwards.values()) {
                if (condition.matches(column(row.getId(), boxed(row.getTunnelId()), boxed(row.getUserId()), condition.column))) {
                    rows.add(row);
                }
            }
            return rows;
        }

        List<ChainTunnel> selectChainTunnels(Wrapper<ChainTunnel> wrapper) {
            Condition condition = Condition.of(wrapper);
            List<ChainTunnel> rows = new ArrayList<>();
            for (ChainTunnel row : chainTunnels) {
                if (condition.matches(column(row.getId(), row.getTunnelId(), null, condition.column))) {
                    rows.add(row);
                }
            }
            return rows;
        }

        List<SpeedLimit> selectSpeedLimits(Wrapper<SpeedLimit> wrapper) {
            Condition condition = Condition.of(wrapper);
            List<SpeedLimit> rows = new ArrayList<>();
            for (SpeedLimit row : speedLimits.values()) {
                if (condition.matches(column(row.getId(), row.getTunnelId(), null, condition.column))) {
                    rows.add(row);
                }
            }
            return rows;
        }

        // ---- 写（mapper 层 delete） ----

        boolean removeForwards(Wrapper<Forward> wrapper) {
            Condition condition = Condition.of(wrapper);
            boolean removed = forwards.values().removeIf(row -> condition.matches(column(row.getId(), boxed(row.getTunnelId()), boxed(row.getUserId()), condition.column)));
            if (removed) {
                timeline.add("db:forward");
            }
            return removed;
        }

        boolean removePorts(Wrapper<ForwardPort> wrapper) {
            Condition condition = Condition.of(wrapper);
            boolean removed = ports.values().removeIf(row -> condition.matches(column(row.getId(), null, row.getForwardId(), condition.column)));
            if (removed) {
                timeline.add("db:forward_port");
            }
            return removed;
        }

        boolean removeUserTunnels(Wrapper<UserTunnel> wrapper) {
            Condition condition = Condition.of(wrapper);
            boolean[] removed = {false};
            userTunnels.values().removeIf(row -> {
                Long tunnelId = row.getTunnelId() == null ? null : row.getTunnelId().longValue();
                Long userId = row.getUserId() == null ? null : row.getUserId().longValue();
                if (matchesCondition(condition, tunnelId, userId, row.getId() == null ? null : row.getId().longValue())) {
                    removed[0] = true;
                    return true;
                }
                return false;
            });
            if (removed[0]) {
                timeline.add("db:user_tunnel");
            }
            return removed[0];
        }

        boolean removeSpeedLimits(Wrapper<SpeedLimit> wrapper) {
            Condition condition = Condition.of(wrapper);
            boolean removed = speedLimits.values().removeIf(row -> condition.matches(column(row.getId(), row.getTunnelId(), null, condition.column)));
            if (removed) {
                timeline.add("db:speed_limit");
            }
            return removed;
        }

        boolean removeChainTunnels(Wrapper<ChainTunnel> wrapper) {
            Condition condition = Condition.of(wrapper);
            boolean removed = chainTunnels.removeIf(row -> condition.matches(column(row.getId(), row.getTunnelId(), null, condition.column)));
            if (removed) {
                timeline.add("db:chain_tunnel");
            }
            return removed;
        }

        boolean removeFlows(Wrapper<StatisticsFlow> wrapper) {
            Condition condition = Condition.of(wrapper);
            boolean removed = flows.values().removeIf(row -> condition.matches(column(row.getId(), null, row.getUserId(), condition.column)));
            if (removed) {
                timeline.add("db:statistics_flow");
            }
            return removed;
        }

        /**
         * ForwardServiceImpl.deleteForward 的等价实现：先按隧道下的入口节点逐节点删同名 gost 服务，
         * 再删 forward_port 行与 forward 行。节点删除发生在库删除之前是原实现的顺序，这里照抄。
         */
        R deleteForward(Long forwardId) {
            Forward row = forwards.get(forwardId);
            if (row == null) {
                return R.err("转发不存在");
            }
            if (row.getTunnelId() != null) {
                long tunnelId = row.getTunnelId().longValue();
                for (ChainTunnel chainTunnel : chainTunnels) {
                    if (chainTunnel.getTunnelId() == null || chainTunnel.getTunnelId() != tunnelId) {
                        continue;
                    }
                    if (!Integer.valueOf(1).equals(chainTunnel.getChainType())) {
                        continue;
                    }
                    for (String suffix : new String[]{"_tcp", "_udp"}) {
                        String name = serviceName(forwardId) + suffix;
                        if (removeNodeService(chainTunnel.getNodeId(), name)) {
                            timeline.add("rpc:delete-service:" + chainTunnel.getNodeId() + ":" + name);
                        }
                    }
                }
            }
            ports.values().removeIf(port -> port.getForwardId() != null && port.getForwardId().equals(forwardId));
            forwards.remove(forwardId);
            timeline.add("db:deleteForward:" + forwardId);
            return R.ok();
        }

        // ---- 节点状态 ----

        void addNodeService(long nodeId, String name) {
            nodeServices.computeIfAbsent(nodeId, ignored -> new LinkedHashSet<>()).add(name);
        }

        boolean hasNodeService(long nodeId, String name) {
            Set<String> services = nodeServices.get(nodeId);
            return services != null && services.contains(name);
        }

        /** 不变量：节点上的每条转发服务都必须在库里有对应的转发行，否则就是没人还认得出来的残留。 */
        boolean hasOrphanNodeService() {
            for (Set<String> services : nodeServices.values()) {
                for (String name : services) {
                    Matcher matcher = FORWARD_SERVICE.matcher(name);
                    if (matcher.matches() && !forwards.containsKey(Long.valueOf(matcher.group(1)))) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean removeNodeService(Long nodeId, String name) {
            return nodeId != null && nodeServices.containsKey(nodeId) && nodeServices.get(nodeId).remove(name);
        }

        boolean hasNodeChain(long nodeId, String name) {
            Set<String> chains = nodeChains.get(nodeId);
            return chains != null && chains.contains(name);
        }

        boolean removeNodeChain(Long nodeId, String name) {
            return nodeId != null && nodeChains.containsKey(nodeId) && nodeChains.get(nodeId).remove(name);
        }

        boolean hasNodeLimiter(long nodeId, long limiterId) {
            Set<Long> limiters = nodeLimiters.get(nodeId);
            return limiters != null && limiters.contains(limiterId);
        }

        boolean removeNodeLimiter(Long nodeId, Long limiterId) {
            return nodeId != null && limiterId != null && nodeLimiters.containsKey(nodeId) && nodeLimiters.get(nodeId).remove(limiterId);
        }
    }

    /** 把 QueryWrapper 编译成「列 = 值 / 列 IN (值...)」。 */
    private static final class Condition {

        private static final Pattern COLUMN = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*(=|IN\\b)", Pattern.CASE_INSENSITIVE);
        private static final Pattern PARAM = Pattern.compile("#\\{[^}]*?([A-Za-z0-9_]+)}");

        private final String column;
        private final boolean in;
        private final List<Object> values;

        private Condition(String column, boolean in, List<Object> values) {
            this.column = column;
            this.in = in;
            this.values = values;
        }

        static Condition of(Wrapper<?> wrapper) {
            String segment = wrapper.getSqlSegment();
            Matcher matcher = COLUMN.matcher(segment);
            if (!matcher.find()) {
                throw new IllegalStateException("无法解析的查询条件：" + segment);
            }
            String column = matcher.group(1).toLowerCase(Locale.ROOT);
            boolean in = "IN".equalsIgnoreCase(matcher.group(2));
            Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
            List<Object> values = new ArrayList<>();
            Matcher paramMatcher = PARAM.matcher(segment);
            while (paramMatcher.find()) {
                Object value = params.get(paramMatcher.group(1));
                if (value != null) {
                    values.add(value);
                }
            }
            if (values.isEmpty()) {
                values.addAll(params.values());
            }
            return new Condition(column, in, values);
        }

        boolean matches(Long actual) {
            if (actual == null || values.isEmpty()) {
                return false;
            }
            if (!in) {
                return equalsNumber(values.get(0), actual);
            }
            for (Object value : values) {
                if (equalsNumber(value, actual)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean equalsNumber(Object expected, Long actual) {
            return expected instanceof Number && ((Number) expected).longValue() == actual.longValue();
        }
    }

    private static Long boxed(Integer value) {
        return value == null ? null : value.longValue();
    }

    /** 按列名把一行映射成它的列值；不认识的列返回 null（不匹配任何行）。 */
    private static Long column(Long id, Long tunnelId, Long foreignId, String column) {
        return switch (column) {
            case "id" -> id;
            case "tunnel_id" -> tunnelId;
            case "user_id", "forward_id" -> foreignId;
            default -> null;
        };
    }

    private static boolean matchesCondition(Condition condition, Long tunnelId, Long userId, Long id) {
        Long actual = switch (condition.column) {
            case "id" -> id;
            case "tunnel_id" -> tunnelId;
            case "user_id" -> userId;
            default -> null;
        };
        return condition.matches(actual);
    }

    private static GostDto reply(String msg) {
        GostDto gostDto = new GostDto();
        gostDto.setMsg(msg);
        return gostDto;
    }

    private class TestTunnelService extends TunnelServiceImpl {

        @Override
        public Tunnel getById(Serializable id) {
            return store.tunnels.get(TUNNEL_ID);
        }

        @Override
        public boolean removeById(Serializable id) {
            store.tunnels.remove(TUNNEL_ID);
            store.timeline.add("db:tunnel");
            return true;
        }

        @Override
        protected GostDto deleteService(Long nodeId, String name) {
            store.removeNodeService(nodeId, name);
            store.timeline.add("rpc:service:" + nodeId + ":" + name);
            return reply("OK");
        }

        @Override
        protected GostDto deleteChains(Long nodeId, String name) {
            store.removeNodeChain(nodeId, name);
            store.timeline.add("rpc:chain:" + nodeId + ":" + name);
            return reply("OK");
        }

        @Override
        protected GostDto deleteLimiters(Long nodeId, Long limiterId) {
            store.removeNodeLimiter(nodeId, limiterId);
            store.timeline.add("rpc:limiter:" + nodeId + ":" + limiterId);
            return reply("OK");
        }
    }

    private class TestUserService extends UserServiceImpl {

        @Override
        public User getById(Serializable id) {
            return store.users.get(USER_ID);
        }

        @Override
        public boolean removeById(Serializable id) {
            store.users.remove(USER_ID);
            store.timeline.add("db:user");
            return true;
        }
    }
}
