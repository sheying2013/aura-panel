package com.admin.compat;

import com.admin.common.dto.GostDto;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入口转发服务「转发策略档位」（forwarder.selector）漂移对账的回归测试（round40）。
 *
 * <p>覆盖的原缺口：round38 只比 strategy 一段。面板把 maxFails / failTimeout 改过、
 * 或节点上整条 selector 丢了时，strategy、监听地址、目标、listener、网卡全都没变，
 * 四段摘要与 strategy 维度都判「一致」，节点实际仍按旧的重试口径挑目标。
 *
 * <p>口径要求（Go 侧契约）：
 * <ul>
 *   <li>ServiceDigest 新增可选字段 {@code selector} = {@code <strategy>|<maxFails>|<failTimeoutNanos>}，
 *       例 {@code fifo|1|600000000000}；无 forwarder 的服务不带该字段；</li>
 *   <li>能力清单 features 新增 {@code fselector}；</li>
 *   <li>哨兵 {@code @fselector_supported} = 「本轮出现过 selector」OR「features 声明 fselector」，
 *       老节点两者皆无 → 整维度跳过（绝不能因为缺字段把在跑的服务删了重建）；</li>
 *   <li>期望值由库 forward.strategy 算：trim+小写、空白兜底 {@code fifo}，再拼固定 {@code |1|600000000000}
 *       （与 GostUtil.createForwarder 的 1 / 600s 同一口径）；</li>
 *   <li>漂移一律 DeleteService(tcp+udp) → AddService 整对重建，说明里带 {@code selector=} 便于 grep。</li>
 * </ul>
 */
class ServiceForwarderSelectorDriftTest {

    private static final long NODE_ID = 202L;
    private static final long FORWARD_ID = 22L;
    private static final int USER_ID = 1;
    private static final int TUNNEL_ID = 1;
    private static final int PORT = 30211;
    private static final String SERVICE_NAME = "22_1_0";
    private static final String REMOTE_ADDR = "backend:6365";

    private static final String TCP = SERVICE_NAME + "_tcp";
    private static final String UDP = SERVICE_NAME + "_udp";

    /** 节点上报的 selector 摘要（策略|maxFails|failTimeoutNanos） */
    private static final String NODE_SELECTOR_MATCH = "round|1|600000000000";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 库里 forward.strategy 的原值（含大小写/空白等历史脏数据写法） */
    private String libraryStrategy;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        libraryStrategy = "round";

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", mock(ChainTunnelService.class));

        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        // 必须用 thenAnswer：用例会在 setUp 之后改 libraryStrategy，thenReturn 会把 fixture 快照固定住
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> forward());
        when(tunnelService.getById(any())).thenAnswer(invocation -> tunnel());
        when(forwardPortService.list(any())).thenAnswer(invocation -> List.of(forwardPort()));
        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ---------------------------------------------------------------- 用例

    /** 库与节点的 selector 三段全一致：零下发。 */
    @Test
    void matchingSelectorLeavesServiceAlone() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@fselector", NODE_SELECTOR_MATCH);
        digests.put(UDP + "@fselector", NODE_SELECTOR_MATCH);
        digests.put("@fselector_supported", "true");

        List<String> notes = resync(digests);

        assertTrue(notes.isEmpty(), "selector 一致就不该有任何处置，实际：" + notes);
        assertNoServiceRebuild();
    }

    /** 库里 round、节点上还是 fifo：strategy 维度看不出来（这里也没报 @strategy），必须判漂移。 */
    @Test
    void strategyDriftRebuildsService() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@fselector", "fifo|1|600000000000");
        digests.put(UDP + "@fselector", "fifo|1|600000000000");
        digests.put("@fselector_supported", "true");

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "selector 的 strategy 段漂移必须整对删了再建，实际：" + typeNames());
        assertEquals(List.of(TCP, UDP), deletedServiceNames(), "删的是 tcp+udp 整对");
        assertTrue(notes.stream().anyMatch(n -> n.contains(
                        "库 selector=round|1|600000000000 / 节点 selector=fifo|1|600000000000")),
                "漂移说明要带两侧 selector，实际：" + notes);
    }

    /** strategy 相同、maxFails 被改过：只有 selector 维度能发现。 */
    @Test
    void maxFailsDriftRebuildsService() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@fselector", "round|3|600000000000");
        digests.put(UDP + "@fselector", "round|3|600000000000");
        digests.put("@fselector_supported", "true");

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "maxFails 漂移必须重建，实际：" + typeNames());
        assertTrue(notes.stream().anyMatch(n -> n.contains(
                        "库 selector=round|1|600000000000 / 节点 selector=round|3|600000000000")),
                "maxFails 要出现在两侧取值里，实际：" + notes);
    }

    /** strategy / maxFails 都相同、failTimeout 被改过（300s ≠ 600s）：同样只能靠 selector 发现。 */
    @Test
    void failTimeoutDriftRebuildsService() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@fselector", "round|1|300000000000");
        digests.put(UDP + "@fselector", "round|1|300000000000");
        digests.put("@fselector_supported", "true");

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "failTimeout 漂移必须重建，实际：" + typeNames());
        assertTrue(notes.stream().anyMatch(n -> n.contains(
                        "库 selector=round|1|600000000000 / 节点 selector=round|1|300000000000")),
                "failTimeout 要出现在两侧取值里，实际：" + notes);
    }

    /** 老节点没有哨兵（features 也没声明）：即便摘要里混进了错值，整维度也必须跳过。 */
    @Test
    void legacyNodeWithoutSentinelSkipsSelector() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@fselector", "fifo|9|1");
        digests.put(UDP + "@fselector", "fifo|9|1");

        List<String> notes = resync(digests);

        assertTrue(notes.isEmpty(), "老节点没有哨兵，selector 维度要跳过，实际：" + notes);
        assertNoServiceRebuild();
    }

    /**
     * 库里 strategy 是空白历史数据：{@code GostUtil.createForwarder} 兜底成 fifo，
     * 节点上报 fifo 就是一回事，不能判漂移（否则每次心跳都删建一次）。
     */
    @Test
    void blankLibraryStrategyExpectsFifoAndMatches() throws Exception {
        libraryStrategy = "   ";
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@fselector", "fifo|1|600000000000");
        digests.put(UDP + "@fselector", "fifo|1|600000000000");
        digests.put("@fselector_supported", "true");

        List<String> notes = resync(digests);

        assertTrue(notes.isEmpty(), "库空白按 fifo 期望，与节点 fifo 一致，实际：" + notes);
        assertNoServiceRebuild();
    }

    /** 库里写的是大写 ROUND：归一后与节点上报的小写 round 等价，不能每轮判漂移。 */
    @Test
    void uppercaseLibraryStrategyIsNormalized() throws Exception {
        libraryStrategy = " ROUND ";
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put(TCP + "@fselector", NODE_SELECTOR_MATCH);
        digests.put(UDP + "@fselector", NODE_SELECTOR_MATCH);
        digests.put("@fselector_supported", "true");

        List<String> notes = resync(digests);

        assertTrue(notes.isEmpty(), "大小写/首尾空格归一后一致，实际：" + notes);
        assertNoServiceRebuild();
    }

    /**
     * 节点这次只报了 tcp 一条（udp 侧压根没上报该条目）：comparison 的
     * {@code containsKey(name_protocol)} 前置必须挡住，不能把「udp 没有 selector」
     * 当成漂移去删建 —— 缺失/半残由其它分支补，不是这个维度的事。
     */
    @Test
    void missingUdpDigestIsNotTreatedAsSelectorDrift() throws Exception {
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put(TCP, PORT + "|tcp|-|" + REMOTE_ADDR);
        digests.put(TCP + "@fselector", NODE_SELECTOR_MATCH);
        digests.put("@fselector_supported", "true");

        List<String> notes = resync(digests);

        assertFalse(typeNames().contains("DeleteService"), "udp 没上报不能判漂移，实际：" + typeNames());
        assertTrue(notes.stream().noneMatch(n -> n.contains("selector=")),
                "文案里不该出现 selector 漂移，实际：" + notes);
    }

    /**
     * features 声明支持 fselector，但节点上服务一条 selector 都没报（forwarder 整段丢了）：
     * 这正是「只靠本轮有没有字段推断哨兵」永远漏判的场景，必须按库重建。
     */
    @Test
    void featuresSentinelRebuildsWhenNodeReportsNoSelectorAtAll() throws Exception {
        Map<String, String> digests = digestsWithBothProtocols();
        digests.put("@fselector_supported", "true"); // 来自节点 features=["fselector"]

        List<String> notes = resync(digests);

        assertEquals(List.of("DeleteService", "AddService"), typeNames(),
                "节点声明支持 selector 却一条都没报，等价于 forwarder 全丢，必须重建，实际：" + typeNames());
        assertTrue(notes.stream().anyMatch(n -> n.contains(
                        "库 selector=round|1|600000000000 / 节点 selector=-")),
                "节点侧缺失要写 -，实际：" + notes);
    }

    // ---------------------------------------------------------------- 工具

    private List<String> resync(Map<String, String> digests) {
        return forwardService.resyncNodeServices(NODE_ID, List.of(TCP, UDP), new ArrayList<>(), digests);
    }

    /** 四段摘要都对得上的底噪：tcp/udp 各一条，端口/链/目标都与库一致（端口转发 chain 段为 -）。 */
    private Map<String, String> digestsWithBothProtocols() {
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put(TCP, PORT + "|tcp|-|" + REMOTE_ADDR);
        digests.put(UDP, PORT + "|udp|-|" + REMOTE_ADDR);
        return digests;
    }

    /** 断言没有发生「删了再建」：这类用例只关心有没有被误判漂移，别的下发（如 ResumeService）不算。 */
    private void assertNoServiceRebuild() throws Exception {
        List<String> types = typeNames();
        assertFalse(types.contains("DeleteService"), "不该删服务，实际：" + types);
        assertFalse(types.contains("AddService"), "不该重建服务，实际：" + types);
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("fselector-drift");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr(REMOTE_ADDR);
        forward.setStrategy(libraryStrategy);
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
        node.setPort("30200-30220");
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
                reply.setMsg("OK");
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
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

    private List<String> typeNames() throws Exception {
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
}
