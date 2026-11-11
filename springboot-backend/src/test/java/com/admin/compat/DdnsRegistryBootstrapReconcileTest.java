package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsForwardApplier;
import com.admin.ddns.DdnsResolver;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.ddns.entity.DdnsRecord;
import com.admin.ddns.mapper.DdnsRecordMapper;
import com.admin.ddns.service.DdnsService;
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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DDNS 域名映射未就绪时，节点摘要对账必须保守跳过（回归护栏）。
 *
 * <p><b>缺陷原样（历史，已修）</b>：{@code DdnsAddressRegistry} 里没有该域名的映射时，
 * {@code ForwardServiceImpl.resyncForwardServices} 依旧按库摘要比对节点上报的摘要，判漂移后
 * 先 {@code DeleteService}、再 {@code CreateForwardService}；重建走
 * {@code GostUtil.renderForwardTargets(forward.getRemoteAddr())}，映射缺失时只剩恒等渲染 ——
 * 于是节点上本来在用的 IP 目标被改回域名，启动瞬间整对服务断流。两条独立触发路径：
 * <ol>
 *   <li>启动顺序窗口：{@code DdnsRegistryBootstrap}（{@code ApplicationRunner}）还没把映射装回来，
 *       节点已经连上并上报摘要 —— 库里数据完全健康，只是时序；</li>
 *   <li>装载失败：{@code DdnsService.loadRegistry()} 读库异常，以前只打一条 WARN 且返回 {@code void}。</li>
 * </ol>
 *
 * <p><b>修复后的契约（本类断言的就是这些）</b>：
 * <ul>
 *   <li>装载结果可观测：{@code loadRegistry()} 返回 {@code boolean}，成功后 registry
 *       {@code isReady()==true}（空表也算成功），失败则停在 {@code FAILED} 并留下原因；</li>
 *   <li>摘要可信度闸门：{@code WebSocketServer#trustedReportedDigests} 在映射未就绪、
 *       或该节点承载的转发踩到「已知但没有主地址」的域名时，把摘要置 null 交下游 ——
 *       下游按「这台节点没报摘要」处理，只补缺失、绝不删建；</li>
 *   <li>恢复即对账：{@code ensureRegistryLoaded()} 重试成功后可信度回到正常，
 *       真漂移（节点仍是旧 IP）仍然照常删建到库里的主地址；</li>
 *   <li>精确性：IP 字面量目标从不依赖映射；健康域名与健康节点不受降级牵连；
 *       空库不被当成降级。</li>
 * </ul>
 *
 * <p>证据等级：{@code [真实复现]} = 本 JVM 里跑通生产代码路径（不碰 DNS、不连网络、不读真实库）；
 * {@code [静态推断]} = 只依据源码结构断言，未被本用例执行到。
 */
class DdnsRegistryBootstrapReconcileTest {

    private static final long NODE_ID = 211L;
    private static final long OTHER_NODE_ID = 212L;
    private static final long FORWARD_ID = 31L;
    private static final long OTHER_FORWARD_ID = 32L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;
    private static final int PORT = 30100;
    private static final int TARGET_PORT = 8080;
    private static final String SERVICE_NAME = "31_1_0";

    /** RFC 5737 TEST-NET-3：只当字符串用，任何路径都不会去解析它。 */
    private static final String PRIMARY_IP = "203.0.113.9";
    /** 节点上还没有被改写过的旧地址：真漂移用例用它。 */
    private static final String STALE_IP = "198.51.100.7";
    /** 假域名：用例里从不做 DNS 解析，只比字符串。 */
    private static final String DOMAIN = "home.example.com";
    private static final String OTHER_DOMAIN = "other.example.com";
    private static final String DOMAIN_TARGET = DOMAIN + ":" + TARGET_PORT;
    private static final String OTHER_DOMAIN_TARGET = OTHER_DOMAIN + ":" + TARGET_PORT;
    private static final String IP_TARGET = PRIMARY_IP + ":" + TARGET_PORT;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
    private DdnsRecordMapper ddnsRecordMapper;
    private DdnsAddressRegistry registry;
    private DdnsService ddnsService;
    private WebSocketServer webSocketServer;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private Node node;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        node = node(NODE_ID);
        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        ddnsRecordMapper = mock(DdnsRecordMapper.class);

        // registry 构造器会把自己的 render / 切换探针 / 摘要可信探针装到
        // DdnsTargetRenderer 的进程级静态接缝上，这正是 GostUtil 渲染目标与算摘要共用的那一处。
        registry = new DdnsAddressRegistry();

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", mock(ChainTunnelService.class));

        ddnsService = new DdnsService(ddnsRecordMapper, registry, mock(DdnsResolver.class),
                mock(DdnsForwardApplier.class), forwardService, tunnelService,
                mock(ChainTunnelService.class), nodeService, forwardPortService,
                userTunnelService, mock(UserService.class));

        // 只用到两个依赖：闸门要按节点查它承载的转发，再问注册表可不可信。
        webSocketServer = new WebSocketServer();
        ReflectionTestUtils.setField(webSocketServer, "forwardService", forwardService);
        ReflectionTestUtils.setField(webSocketServer, "forwardPortService", forwardPortService);

        when(nodeService.getById(any())).thenReturn(node);
        // 库里这条转发填的是域名，节点实际在跑的可能是上一次映射还在时下发的 IP。
        when(forwardMapper.selectById(any())).thenReturn(forward(FORWARD_ID, DOMAIN_TARGET));
        when(tunnelService.getById(any())).thenReturn(tunnel(1));
        when(userTunnelService.getOne(any())).thenReturn(null);
        when(forwardPortService.list(any())).thenAnswer(invocation -> List.of(forwardPort()));
        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
        // 静态接缝是进程级的：不还原会把渲染函数 / 摘要可信探针泄漏给同 JVM 的其他测试类。
        DdnsTargetRenderer.reset();
        registry.clear();
    }

    // ------------------------------------------------------- 正确装载时：不该动节点

    /**
     * 对照组（保留并加强）：bootstrap 成功装载后，同一个「库里域名 / 节点 IP」组合必须判一致、零动作，
     * 且闸门放行的是<b>同一份</b>摘要 —— 证明下面那些「零动作」不是把摘要整体吞掉造成的假象。
     * {@code [真实复现]}
     */
    @Test
    void registryLoadedBeforeReconcileKeepsIpServiceUntouched() throws Exception {
        when(ddnsRecordMapper.selectList(any())).thenReturn(List.of(record(DOMAIN, PRIMARY_IP)));
        assertTrue(ddnsService.loadRegistry(), "装载成功必须返回 true");

        assertEquals(PRIMARY_IP, registry.get(DOMAIN), "映射装回来了，渲染应能还原成 IP");
        assertTrue(registry.isReady(), "装载成功后注册表必须就绪");
        assertFalse(registry.isDegraded(), "没有未解析域名时不算降级");
        assertEquals(IP_TARGET, DdnsTargetRenderer.render(DOMAIN_TARGET), "渲染接缝已生效");
        assertNull(DdnsTargetRenderer.digestTrustIssue(DOMAIN_TARGET), "已解析域名的摘要可信");
        Map<String, String> reported = ipDigests();
        assertSame(reported, trusted(reported), "可信时闸门原样放行，不改写摘要");

        List<String> notes = resync(trusted(reported));

        assertTrue(notes.isEmpty(), "映射在就直接判一致，不该有任何动作，实际：" + notes);
        assertNoNodeChanges("映射就绪且一致时");
    }

    // ------------------------------------------------------- 装载失败：可观测 + 停手

    /**
     * 装载失败必须可观测（加强版，取代旧的「void 且没有就绪信号」断言）：
     * {@code loadRegistry()} 返回 false、注册表停在 FAILED 并带原因、摘要闸门直接判定不可信。
     * 旧用例断言的是「接口上查不到失败」，本用例断言的是「查得到，而且会停手」。
     * {@code [真实复现]}（异常注入真的跑过 catch 分支）
     */
    @Test
    void loadRegistryReadFailureIsReportedAndMarkedNotReady() throws Exception {
        when(ddnsRecordMapper.selectList(any()))
                .thenThrow(new RuntimeException("SQLITE_BUSY: database is locked"));

        assertFalse(ddnsService.loadRegistry(), "读库失败必须返回 false，调用方要能看见"); // 且必须不抛

        assertEquals(0, registry.size(), "读失败后没有任何映射被装进来");
        assertTrue(registry.snapshot().isEmpty(), "快照为空");
        assertFalse(registry.isReady(), "失败后注册表不能是就绪态");
        assertEquals(DdnsAddressRegistry.LoadState.FAILED, registry.loadState(), "状态要停在 FAILED");
        assertNotNull(registry.loadError(), "失败原因要留下来");
        assertTrue(registry.loadError().contains("SQLITE_BUSY"), "原因要带上底层错误，实际：" + registry.loadError());
        assertTrue(registry.isDegraded(), "未就绪即降级");

        // 渲染接缝此时仍是恒等函数：这就是以前会被写进节点配置的那份值
        assertEquals(DOMAIN_TARGET, DdnsTargetRenderer.render(DOMAIN_TARGET), "映射缺失时域名原样下发");

        // 接口形状：装载结果是 boolean，「是否就绪 / 是否降级 / 哪些域名没解析」都可查
        assertEquals(boolean.class, methodOrNull(DdnsService.class, "loadRegistry").getReturnType(),
                "loadRegistry 必须把装载结果返回给调用方");
        assertNotNull(methodOrNull(DdnsAddressRegistry.class, "isReady"), "registry 必须暴露就绪信号");
        assertNotNull(methodOrNull(DdnsAddressRegistry.class, "isDegraded"), "registry 必须暴露降级信号");
        assertNotNull(methodOrNull(DdnsAddressRegistry.class, "unresolvedDomains"), "未解析域名要可枚举");

        // IP 字面量不依赖映射：降级也不该连累它
        assertNull(DdnsTargetRenderer.digestTrustIssue(IP_TARGET), "IP 目标与映射无关");
        assertNotNull(DdnsTargetRenderer.digestTrustIssue(DOMAIN_TARGET), "域名目标此时不可信");
        assertNull(trusted(ipDigests()), "未就绪 ⇒ 摘要不可信，闸门置 null");
    }

    /**
     * 核心用例（原复现 A 的反向断言）：装载失败 → 闸门把手 —— 节点上报「当时正确的 IP 摘要」
     * 也不会被删建。用例末尾再绕过闸门跑一次原始摘要，证明缺陷路径仍在、只是被拦住了
     * （否则「零动作」可能来自脚手架失效）。
     * {@code [真实复现]}
     */
    @Test
    void notReadyDigestsAreNotTrustedSoIpServiceSurvives() throws Exception {
        when(ddnsRecordMapper.selectList(any()))
                .thenThrow(new RuntimeException("SQLITE_BUSY: database is locked"));
        ddnsService.loadRegistry();
        assertEquals(0, registry.size(), "前置：装载失败后注册表为空");

        // 现有的切换窗口保护（anyTransitioning）不覆盖本场景：这不是切换，是没装载
        assertFalse(DdnsTargetRenderer.anyTransitioning(DOMAIN_TARGET), "没有切换窗口在跑");

        List<String> notes = resync(trusted(ipDigests()));

        assertTrue(notes.isEmpty(), "映射未就绪时不许按摘要做任何改动，实际：" + notes);
        assertNoNodeChanges("装载失败时");

        // 反证：同一份摘要不经闸门直接下去，仍然会删整对并改回域名 —— 拦住它的只有这道闸门
        List<String> raw = resync(ipDigests());
        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "绕过闸门才重现旧缺陷，实际：" + raw);
        assertEquals(List.of(DOMAIN_TARGET, DOMAIN_TARGET), addedTargetAddrs(NODE_ID),
                "绕过闸门时依然会把 IP 改回域名");
    }

    // ------------------------------------------------------- 启动顺序窗口（数据健康）

    /**
     * 启动窗口（原复现 B 的反向断言）：完全不需要异常，只是 bootstrap 还没跑到。
     * 此时闸门先拦住（节点继续跑 IP），bootstrap 一装载立刻恢复可信，且因为节点没被改过，
     * 对账判一致 —— 一次启动不再换来两次删建。
     * {@code [真实复现]}（后果）；{@code [静态推断]}（“节点可能先于 ApplicationRunner 上报”的时序）
     */
    @Test
    void nodeReportBeforeBootstrapIsSkippedAndBootstrapRestoresTrust() throws Exception {
        when(ddnsRecordMapper.selectList(any())).thenReturn(List.of(record(DOMAIN, PRIMARY_IP)));
        // 刻意不调用 loadRegistry()：模拟启动窗口内 WebSocketServer 已经在对账
        assertFalse(registry.isReady(), "前置：bootstrap 还没跑");

        List<String> notes = resync(trusted(ipDigests()));
        assertTrue(notes.isEmpty(), "启动窗口内不许删建，实际：" + notes);
        assertNoNodeChanges("bootstrap 未跑时");

        // 库里的答案一直在，晚一步装载就能拿到
        assertTrue(ddnsService.ensureRegistryLoaded(), "重试装载成功");
        assertTrue(registry.isReady());
        assertEquals(PRIMARY_IP, registry.get(DOMAIN), "库里有 IP，只是装载晚了一步");
        Map<String, String> reported = ipDigests();
        assertSame(reported, trusted(reported), "装载后闸门放行");

        List<String> afterLoad = resync(trusted(reported));
        assertTrue(afterLoad.isEmpty(), "节点没被改过，装载后判一致，实际：" + afterLoad);
        assertNoNodeChanges("装载后且一致时");
    }

    /**
     * 失败 → 重试恢复 → 恢复后真漂移仍然照常对账（原复现 C 的反向断言，增强版）。
     * 既证明不会「一次启动掐断两次」，也证明修复没有把对账能力一起关掉：
     * 节点上真的是旧 IP（{@value #STALE_IP}）时，必须删整对并重建到库里的主地址。
     * {@code [真实复现]}
     */
    @Test
    void failedLoadThenRetryRecoversAndReconcilesRealDrift() throws Exception {
        when(ddnsRecordMapper.selectList(any()))
                .thenThrow(new RuntimeException("SQLITE_BUSY: database is locked"))
                .thenReturn(List.of(record(DOMAIN, PRIMARY_IP)));

        assertFalse(ddnsService.loadRegistry(), "第一次装载失败");
        List<String> skipped = resync(trusted(ipDigests()));
        assertTrue(skipped.isEmpty(), "失败期间不删建，实际：" + skipped);
        assertNoNodeChanges("第一次失败后");

        assertTrue(ddnsService.ensureRegistryLoaded(), "调度轮次的重试入口把映射补齐");
        assertTrue(registry.isReady(), "重试成功后必须就绪");
        assertEquals(PRIMARY_IP, registry.get(DOMAIN));

        List<String> notes = resync(trusted(staleIpDigests()));

        assertEquals(List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp"), deletedServiceNames(NODE_ID),
                "真漂移照常删整对（同名整批 AddService 会失败），实际：" + notes);
        assertEquals(List.of(IP_TARGET, IP_TARGET), addedTargetAddrs(NODE_ID),
                "重建目标必须是库里的主地址，不是域名：" + notes);
        assertEquals(1, notes.size(), "只该有一条重建记录，实际：" + notes);
        assertFalse(addedTargetAddrs(NODE_ID).contains(DOMAIN_TARGET), "恢复后绝不能再把目标写成域名");
    }

    // ------------------------------------------------------- 空库 / 记录未就绪的精确边界

    /**
     * 空库是健康的：装载成功、就绪、不降级，摘要照常可信。
     * 这一条挡住「把 isReady 做成 size()>0」这类错误实现 —— 那样空库会永久停手。
     * {@code [真实复现]}
     */
    @Test
    void emptyDatabaseLoadsReadyAndKeepsDigestsTrusted() throws Exception {
        when(ddnsRecordMapper.selectList(any())).thenReturn(List.of());

        assertTrue(ddnsService.loadRegistry(), "空表也是成功的装载");
        assertTrue(registry.isReady(), "空库必须就绪");
        assertFalse(registry.isDegraded(), "空库不算降级");
        assertEquals(0, registry.knownSize(), "没有登记域名");
        assertTrue(registry.unresolvedDomains().isEmpty());
        Map<String, String> reported = ipDigests();
        assertSame(reported, trusted(reported), "空库不拦摘要");
        assertNull(DdnsTargetRenderer.digestTrustIssue(DOMAIN_TARGET),
                "库里没有这条 DDNS 记录时，域名不归映射管，行为与以前一致");
    }

    /**
     * 「缺失 primary」的精确定义（加强版，取代旧的「跳过即重建」断言）：
     * null / 空白 / 非 IP 的主地址都让该域名变成「已知但未解析」——装载本身成功、注册表就绪、
     * 但整体降级；只有踩到这条域名的节点摘要被拦，同批别的域名与 IP 字面量目标一律不受牵连。
     * {@code [真实复现]}
     */
    @Test
    void missingPrimaryIpMakesOnlyThatDomainUntrusted() throws Exception {
        String blankDomain = "blank.example.com";
        when(ddnsRecordMapper.selectList(any())).thenReturn(List.of(
                record(DOMAIN, null),                        // 还没解析过
                record(blankDomain, "   "),                  // 空白主地址
                record(OTHER_DOMAIN, STALE_IP)));            // 对照组：同一次装载里健康

        assertTrue(ddnsService.loadRegistry(), "个别域名没主地址不影响装载成功");
        assertTrue(registry.isReady(), "装载确实成功了");
        assertTrue(registry.isDegraded(), "有未解析域名 ⇒ 降级");
        assertEquals(List.of(blankDomain, DOMAIN), registry.unresolvedDomains(),
                "未解析域名必须精确定为这两条");

        assertNull(registry.get(DOMAIN), "primaryIp 为 null → 不进渲染表");
        assertNull(registry.get(blankDomain), "primaryIp 为空白 → 不进渲染表");
        assertEquals(STALE_IP, registry.get(OTHER_DOMAIN), "同一次装载里健康域名照常进来");

        assertTrue(DdnsTargetRenderer.digestTrustIssue(DOMAIN_TARGET).contains("尚未解析"),
                "未解析域名要给出可读原因");
        assertNotNull(DdnsTargetRenderer.digestTrustIssue(blankDomain + ":" + TARGET_PORT),
                "空白主地址同样不可信");
        assertNull(DdnsTargetRenderer.digestTrustIssue(OTHER_DOMAIN_TARGET),
                "健康域名不受牵连，摘要仍然可信");
        assertNull(DdnsTargetRenderer.digestTrustIssue(IP_TARGET), "IP 目标不受牵连");

        List<String> notes = resync(trusted(ipDigests()));
        assertTrue(notes.isEmpty(), "踩到未解析域名的节点本轮不许删建，实际：" + notes);
        assertNoNodeChanges("记录存在但主地址未就绪时");
    }

    /**
     * 闸门按节点收窄：降级只发生在「该节点承载的转发踩到未解析域名」时。
     * 健康节点（转发目标是已解析域名）的摘要必须原样放过 —— 否则一次坏记录会让整片节点停手。
     * {@code [真实复现]}
     */
    @Test
    void onlyNodesCarryingUnresolvedDomainLoseTheirDigests() throws Exception {
        when(ddnsRecordMapper.selectList(any())).thenReturn(List.of(
                record(DOMAIN, null),
                record(OTHER_DOMAIN, STALE_IP)));
        assertTrue(ddnsService.loadRegistry());
        assertTrue(registry.isDegraded(), "前置：整体降级");

        // 两个节点各承载一条转发：211 → 未解析域名，212 → 健康域名
        when(forwardPortService.list(any())).thenAnswer(invocation -> {
            QueryWrapper<?> wrapper = invocation.getArgument(0);
            // MyBatis-Plus 的参数表是懒填充的：先生成 SQL 片段，node_id 的值才进 paramNameValuePairs
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().containsValue(OTHER_NODE_ID)
                    ? List.of(forwardPort(OTHER_FORWARD_ID, OTHER_NODE_ID))
                    : List.of(forwardPort(FORWARD_ID, NODE_ID));
        });
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return Long.valueOf(OTHER_FORWARD_ID).equals(id)
                    ? forward(OTHER_FORWARD_ID, OTHER_DOMAIN_TARGET)
                    : forward(FORWARD_ID, DOMAIN_TARGET);
        });

        Map<String, String> reported = ipDigests();
        assertNull(webSocketServer.trustedReportedDigests(NODE_ID, reported),
                "踩到未解析域名的节点：摘要不可信");
        assertSame(reported, webSocketServer.trustedReportedDigests(OTHER_NODE_ID, reported),
                "健康节点：摘要照常可信，不被别人的坏记录拖住");
    }

    // ------------------------------------------------------- 复现边界

    /**
     * 边界（保留）：连接时那条对账（不带摘要）本来就不删服务，与闸门无关。
     * 这条把伤害面收窄到「带 digests 的上报」，避免把连接对账也算成肇事路径。
     * {@code [真实复现]}
     */
    @Test
    void connectTimeReconcileWithoutDigestsDoesNotRebuild() throws Exception {
        when(ddnsRecordMapper.selectList(any()))
                .thenThrow(new RuntimeException("SQLITE_BUSY: database is locked"));
        ddnsService.loadRegistry();
        assertEquals(0, registry.size());
        assertNull(trusted(null), "没有摘要时闸门原样返回 null");

        // 四参版本 digests=null，等价于生产里连接对账走的三参版本：不做摘要比对
        List<String> notes = forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), null);

        assertTrue(notes.isEmpty(), "没有摘要可比就不会有任何动作，实际 notes：" + notes);
        assertNoNodeChanges("连接对账无摘要时");
    }

    // ------------------------------------------------------- 摘要口径来源

    /**
     * 上面几条用例里"节点上报的摘要"是手写字符串。这条用例证明它不是拍出来的：
     * 走生产同一个入口 {@code WebSocketServer.toDigestMap} 把节点上报的 targets 归一成摘要，
     * 结果必须与 {@link #ipDigests()} / {@link #domainDigests()} / {@link #staleIpDigests()} 逐字相同。
     * {@code [真实复现]}
     */
    @Test
    void reportedDigestIsDerivedByTheSameProductionParser() throws Exception {
        Map<String, String> fromIp = toDigests(entries(
                entry(SERVICE_NAME + "_tcp", IP_TARGET),
                entry(SERVICE_NAME + "_udp", IP_TARGET)));
        assertEquals(ipDigests().get(SERVICE_NAME + "_tcp"), fromIp.get(SERVICE_NAME + "_tcp"),
                "节点跑 IP 时上报的 tcp 摘要，必须与本用例手写的期望一致");
        assertEquals(ipDigests().get(SERVICE_NAME + "_udp"), fromIp.get(SERVICE_NAME + "_udp"),
                "udp 同理");

        Map<String, String> fromDomain = toDigests(entries(
                entry(SERVICE_NAME + "_tcp", DOMAIN_TARGET),
                entry(SERVICE_NAME + "_udp", DOMAIN_TARGET)));
        assertEquals(domainDigests().get(SERVICE_NAME + "_tcp"), fromDomain.get(SERVICE_NAME + "_tcp"),
                "节点跑域名时上报的 tcp 摘要，必须与手写值一致");
        assertEquals(domainDigests().get(SERVICE_NAME + "_udp"), fromDomain.get(SERVICE_NAME + "_udp"),
                "udp 同理");

        Map<String, String> fromStale = toDigests(entries(
                entry(SERVICE_NAME + "_tcp", STALE_IP + ":" + TARGET_PORT),
                entry(SERVICE_NAME + "_udp", STALE_IP + ":" + TARGET_PORT)));
        assertEquals(staleIpDigests().get(SERVICE_NAME + "_tcp"), fromStale.get(SERVICE_NAME + "_tcp"),
                "真漂移用例的旧 IP 摘要同样来自生产解析口径");
        assertEquals(staleIpDigests().get(SERVICE_NAME + "_udp"), fromStale.get(SERVICE_NAME + "_udp"));
    }

    // ------------------------------------------------------- 脚手架

    /** 生产链路里 WS 层就是这么做的：先用闸门过滤，再把摘要交给 resync。 */
    private Map<String, String> trusted(Map<String, String> reported) {
        return webSocketServer.trustedReportedDigests(NODE_ID, reported);
    }

    private void assertNoNodeChanges(String scene) throws Exception {
        assertTrue(deletedServiceNames(NODE_ID).isEmpty(), scene + "不能删服务");
        assertTrue(addedServiceNames(NODE_ID).isEmpty(), scene + "不能重建服务");
        assertTrue(addedTargetAddrs(NODE_ID).isEmpty(), scene + "不能下发任何目标");
    }

    private Forward forward(long id, String remoteAddr) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setName("ddns-registry-race-test-" + id);
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr(remoteAddr);
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private Tunnel tunnel(int type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(type);
        tunnel.setStatus(1);
        return tunnel;
    }

    private ForwardPort forwardPort() {
        return forwardPort(FORWARD_ID, NODE_ID);
    }

    private ForwardPort forwardPort(long forwardId, long nodeId) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(forwardId);
        forwardPort.setNodeId(nodeId);
        forwardPort.setPort(PORT);
        return forwardPort;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.6");
        node.setPort("30100-30120");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private static DdnsRecord record(String domain, String primaryIp) {
        DdnsRecord record = new DdnsRecord();
        record.setDomain(domain);
        record.setPrimaryIp(primaryIp);
        record.setResolvedIps(primaryIp);
        record.setIntervalSeconds(60);
        record.setEnabled(1);
        return record;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static java.lang.reflect.Method methodOrNull(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
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
                reply.setMsg("OK");
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    /** 丢掉已捕获的下发记录，让第二阶段只看自己发出的报文。 */
    private void resetSent() {
        clearInvocations(nodeSessions.get(NODE_ID));
    }

    private List<JSONObject> sentMessages(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<JSONObject> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            messages.add(JSON.parseObject(message.getPayload()));
        }
        return messages;
    }

    private List<String> deletedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
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

    private List<String> addedServiceNames(long nodeId) throws Exception {
        List<String> names = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
            if (!"AddService".equals(message.getString("type"))) {
                continue;
            }
            JSONArray services = message.getJSONArray("data");
            for (int i = 0; i < services.size(); i++) {
                names.add(services.getJSONObject(i).getString("name"));
            }
        }
        return names;
    }

    /** 真正写进节点配置的 forwarder 目标（forwarder.nodes[].addr），按下发顺序摊平。 */
    private List<String> addedTargetAddrs(long nodeId) throws Exception {
        List<String> addrs = new ArrayList<>();
        for (JSONObject message : sentMessages(nodeId)) {
            if (!"AddService".equals(message.getString("type"))) {
                continue;
            }
            JSONArray services = message.getJSONArray("data");
            for (int i = 0; i < services.size(); i++) {
                JSONArray nodes = services.getJSONObject(i)
                        .getJSONObject("forwarder").getJSONArray("nodes");
                for (int j = 0; j < nodes.size(); j++) {
                    addrs.add(nodes.getJSONObject(j).getString("addr"));
                }
            }
        }
        return addrs;
    }

    /** 走 WebSocketServer.toDigestMap 拿"节点上报 → 可比摘要"，与生产同一个入口。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> toDigests(JSONArray reported) throws Exception {
        java.lang.reflect.Method toDigestMap = Class.forName("com.admin.common.utils.WebSocketServer")
                .getDeclaredMethod("toDigestMap", JSONArray.class);
        toDigestMap.setAccessible(true);
        return (Map<String, String>) toDigestMap.invoke(null, reported);
    }

    private static JSONArray entries(JSONObject... items) {
        JSONArray array = new JSONArray();
        for (JSONObject item : items) {
            array.add(item);
        }
        return array;
    }

    private static JSONObject entry(String name, String... targets) {
        JSONObject entry = new JSONObject();
        entry.put("name", name);
        entry.put("addr", "[::]:" + PORT);
        // handler 跟服务名协议走：udp 服务上报 handler=udp，写错会自己造出假漂移
        entry.put("handler", name.endsWith("_udp") ? "UDP" : "TCP");
        JSONArray list = new JSONArray();
        for (String target : targets) {
            list.add(target);
        }
        entry.put("targets", list);
        return entry;
    }

    /** 节点两侧都在（tcp+udp 名字齐全），把注意力集中在摘要上。 */
    private static List<String> bothProtocolsReported() {
        return List.of(SERVICE_NAME + "_tcp", SERVICE_NAME + "_udp");
    }

    private static Map<String, String> digests(String tcpDigest, String udpDigest) {
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put(SERVICE_NAME + "_tcp", tcpDigest);
        digests.put(SERVICE_NAME + "_udp", udpDigest);
        return digests;
    }

    /** 节点上跑的是上一次映射还在时下发的 IP（与库一致）。 */
    private static Map<String, String> ipDigests() {
        return digests(PORT + "|tcp|-|" + IP_TARGET, PORT + "|udp|-|" + IP_TARGET);
    }

    /** 节点上跑的是一个更老的 IP：恢复后必须被判成真漂移。 */
    private static Map<String, String> staleIpDigests() {
        String target = STALE_IP + ":" + TARGET_PORT;
        return digests(PORT + "|tcp|-|" + target, PORT + "|udp|-|" + target);
    }

    /** 节点上跑的是被改写过后的域名。 */
    private static Map<String, String> domainDigests() {
        return digests(PORT + "|tcp|-|" + DOMAIN_TARGET, PORT + "|udp|-|" + DOMAIN_TARGET);
    }

    private List<String> resync(Map<String, String> reported) {
        return forwardService.resyncNodeServices(NODE_ID, bothProtocolsReported(),
                new ArrayList<>(), reported);
    }
}
