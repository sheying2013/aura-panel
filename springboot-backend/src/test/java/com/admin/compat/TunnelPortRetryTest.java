package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.TunnelServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动分配的端口撞上节点上的僵尸监听（DB 里查不到、节点上还在听）时必须换下一个候选端口重试。
 *
 * <p>真机缺陷：15_tls 删除时节点不在线，节点重启后服务复活并占着 30201，DB 已无记录；
 * 分配器只查 DB 不探测节点，于是每次都给 30201，创建永久失败在
 * "listen tcp 0.0.0.0:30201: bind: address already in use"。
 *
 * <p>这里锁死：自动端口绑定失败换口重试（最多 3 个候选），换口成功要回写库；
 * 候选耗尽整体失败且节点侧不留残留；显式指定端口的行一律不换口。
 */
class TunnelPortRetryTest {

    private static final long TUNNEL_ID = 7L;
    private static final String NODE_PORT_RANGE = "30201-30210";
    private static final String BIND_FAIL = "-1: create service %s failed: listen tcp 0.0.0.0:%d: bind: address already in use";

    private TunnelServiceImpl service;
    private TunnelMapper tunnelMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private final Map<Long, Node> nodes = new LinkedHashMap<>();
    private List<ChainTunnel> occupiedChainTunnels = new ArrayList<>();
    private List<ForwardPort> occupiedForwardPorts = new ArrayList<>();
    // key = "nodeId:port"，未登记的端口回 OK
    private final Map<String, String> addServiceReplies = new HashMap<>();
    // key = "nodeId:port"，按 AddChains 里指向的下一跳端口登记失败
    private final Map<String, String> chainReplies = new HashMap<>();
    // 按发生顺序记录节点侧动作，形如 "2:AddService:30201" / "2:DeleteService"
    private final List<String> ops = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        nodes.clear();
        ops.clear();
        addServiceReplies.clear();
        chainReplies.clear();
        occupiedChainTunnels = new ArrayList<>();
        occupiedForwardPorts = new ArrayList<>();

        tunnelMapper = mock(TunnelMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);

        service = new TunnelServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", tunnelMapper);
        ReflectionTestUtils.setField(service, "nodeService", nodeService);
        ReflectionTestUtils.setField(service, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(service, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(service, "forwardService", mock(ForwardService.class));
        ReflectionTestUtils.setField(service, "userTunnelService", mock(UserTunnelService.class));

        when(nodeService.getById(any())).thenAnswer(invocation -> nodes.get((Long) invocation.getArgument(0)));
        when(nodeService.list(any())).thenAnswer(invocation -> new ArrayList<>(nodes.values()));
        when(chainTunnelService.list(any())).thenAnswer(invocation -> new ArrayList<>(occupiedChainTunnels));
        when(chainTunnelService.saveBatch(any())).thenReturn(true);
        when(chainTunnelService.remove(any())).thenReturn(true);
        when(chainTunnelService.update(any(Wrapper.class))).thenReturn(true);
        when(forwardPortService.list(any())).thenAnswer(invocation -> new ArrayList<>(occupiedForwardPorts));
        when(tunnelMapper.selectCount(any())).thenReturn(0);
        when(tunnelMapper.deleteById(any(Serializable.class))).thenReturn(1);
        when(tunnelMapper.insert(any(Tunnel.class))).thenAnswer(invocation -> {
            ((Tunnel) invocation.getArgument(0)).setId(TUNNEL_ID);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        nodeSessions.clear();
        pendingRequests.clear();
    }

    @Test
    void autoPortRetriesNextCandidateWhenNodeReportsBindFailure() throws Exception {
        useNodes(1L, 2L);
        registerNodes(1L, 2L);
        bindFailsOn(2L, 30201);

        R result = service.createTunnel(autoPortTunnel());

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(List.of("2:AddService:30201", "2:DeleteService", "2:AddService:30202"), nodeOps(2L),
                "30201 被占后必须清掉残留再换 30202，不能直接叠加下发");
        assertEquals(30202, writtenPort(2L).intValue(), "换口后库里必须是最终端口");
        assertEquals(30202, updatedPort(), "换口成功后要回写隧道节点记录的端口");
    }

    @Test
    void threeBindFailuresFailWholeCreateWithoutLeftovers() throws Exception {
        useNodes(1L, 2L);
        registerNodes(1L, 2L);
        bindFailsOn(2L, 30201);
        bindFailsOn(2L, 30202);
        bindFailsOn(2L, 30203);

        R result = service.createTunnel(autoPortTunnel());

        assertEquals(-1, result.getCode(), "3 个候选端口全被占必须整体失败");
        String msg = result.getMsg();
        assertTrue(msg.contains("bind: address already in use"), msg);
        assertTrue(msg.contains("30201") && msg.contains("30202") && msg.contains("30203"), msg);
        assertTrue(msg.contains("端口尝试"), "每次尝试的端口与原因要带出来：" + msg);
        assertEquals(List.of("2:AddService:30201", "2:DeleteService",
                "2:AddService:30202", "2:DeleteService",
                "2:AddService:30203", "2:DeleteService"), nodeOps(2L),
                "候选耗尽后节点上不能留下本次尝试创建的 service");
        verify(chainTunnelService, never()).update(any(Wrapper.class));
        assertTrue(msg.contains("隧道记录 id=" + TUNNEL_ID) == false && !msg.contains("回滚未完成"),
                "记录清理要成功，不能留下孤儿：" + msg);
    }

    @Test
    void explicitPortFailureDoesNotRetry() throws Exception {
        useNodes(1L, 2L);
        registerNodes(1L, 2L);
        bindFailsOn(2L, 30203);

        R result = service.createTunnel(explicitPortTunnel(30203));

        assertEquals(-1, result.getCode(), "调用方指定的端口不可用要立刻失败");
        assertTrue(result.getMsg().contains("address already in use"), result.getMsg());
        assertEquals(List.of("2:AddService:30203"), nodeOps(2L), "显式端口不允许换口重试");
        verify(chainTunnelService, never()).update(any(Wrapper.class));
    }

    @Test
    void republishesUpstreamChainsAfterPortSwitch() throws Exception {
        useNodes(1L, 2L);
        registerNodes(1L, 2L);
        bindFailsOn(2L, 30201);

        R result = service.createTunnel(autoPortTunnel());

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(List.of("1:AddChains:30201", "1:DeleteChains", "1:AddChains:30202"), nodeOps(1L),
                "换口后入口 chains 必须按新端口整条重发，否则入口一直拨旧端口，隧道实际不可用");
    }

    @Test
    void chainRefreshFailureRollsBackWholeCreate() throws Exception {
        useNodes(1L, 2L);
        registerNodes(1L, 2L);
        bindFailsOn(2L, 30201);
        chainReplies.put("1:30202", "-1: create chain chains_" + TUNNEL_ID + " failed: boom");

        R result = service.createTunnel(autoPortTunnel());

        assertEquals(-1, result.getCode(), "重发链路失败不能当成功返回");
        String msg = result.getMsg();
        assertTrue(msg.contains("boom"), msg);
        assertEquals(List.of("2:AddService:30201", "2:DeleteService", "2:AddService:30202", "2:DeleteService"), nodeOps(2L),
                "重发失败要回滚已下发的 service：" + msg);
        assertEquals(List.of("1:AddChains:30201", "1:DeleteChains", "1:AddChains:30202", "1:DeleteChains"), nodeOps(1L),
                "重发失败要把已存在的 chains 也清掉：" + msg);
    }

    @Test
    void autoPortNonBindFailureIsNotRetried() throws Exception {
        useNodes(1L, 2L);
        registerNodes(1L, 2L);
        addServiceReplies.put("2:30201", "-1: invalid listener type");

        R result = service.createTunnel(autoPortTunnel());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("invalid listener type"), result.getMsg());
        assertEquals(List.of("2:AddService:30201"), nodeOps(2L), "不是端口占用就照旧失败，不能换口掩盖真错因");
        assertFalse(result.getMsg().contains("端口尝试"), result.getMsg());
    }

    /** "bind: cannot assign requested address" 是监听地址问题，换端口不会成功，不能当端口冲突重试。 */
    @Test
    void addressNotAvailableIsNotTreatedAsPortConflict() throws Exception {
        useNodes(1L, 2L);
        registerNodes(1L, 2L);
        addServiceReplies.put("2:30201", "-1: create service " + TUNNEL_ID
                + "_tls failed: listen tcp4 192.0.2.10:30201: bind: cannot assign requested address");

        R result = service.createTunnel(autoPortTunnel());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("cannot assign requested address"), result.getMsg());
        assertEquals(List.of("2:AddService:30201"), nodeOps(2L),
                "监听地址不可用不是端口占用，不允许换口重试");
        assertFalse(result.getMsg().contains("端口尝试"), result.getMsg());
    }

    // ========== 夹具 ==========

    private void bindFailsOn(long nodeId, int port) {
        addServiceReplies.put(nodeId + ":" + port, String.format(BIND_FAIL, TUNNEL_ID + "_tls", port));
    }

    @SuppressWarnings("unchecked")
    private Integer writtenPort(long nodeId) {
        ArgumentCaptor<Collection<ChainTunnel>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(chainTunnelService).saveBatch(captor.capture());
        return captor.getValue().stream()
                .filter(row -> row.getNodeId() == nodeId)
                .map(ChainTunnel::getPort)
                .findFirst()
                .orElseThrow(() -> new AssertionError("节点" + nodeId + " 没有落库任何端口"));
    }

    @SuppressWarnings("unchecked")
    private Integer updatedPort() {
        ArgumentCaptor<Wrapper<ChainTunnel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(chainTunnelService).update(captor.capture());
        UpdateWrapper<ChainTunnel> wrapper = (UpdateWrapper<ChainTunnel>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("port"), "回写语句要改 port：" + wrapper.getSqlSet());
        return wrapper.getParamNameValuePairs().values().stream()
                .filter(value -> value instanceof Integer)
                .map(value -> (Integer) value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("回写语句里没有端口值"));
    }

    private List<String> nodeOps(long nodeId) {
        String prefix = nodeId + ":";
        List<String> result = new ArrayList<>();
        for (String op : ops) {
            if (op.startsWith(prefix)) {
                result.add(op);
            }
        }
        return result;
    }

    private void useNodes(Long... nodeIds) {
        for (Long nodeId : nodeIds) {
            nodes.put(nodeId, node(nodeId));
        }
    }

    private void registerNodes(Long... nodeIds) throws Exception {
        for (Long nodeId : nodeIds) {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.isOpen()).thenReturn(true);
            when(session.getId()).thenReturn("fake-node-" + nodeId);
            when(session.getAttributes()).thenReturn(new HashMap<>());
            doAnswer(invocation -> {
                JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
                CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
                if (future != null) {
                    GostDto gostDto = new GostDto();
                    gostDto.setMsg(replyFor(nodeId, message.getString("type"), message.get("data")));
                    future.complete(gostDto);
                }
                return null;
            }).when(session).sendMessage(any());
            nodeSessions.put(nodeId, session);
        }
    }

    private String replyFor(long nodeId, String type, Object data) {
        if ("AddChains".equals(type)) {
            int port = firstChainHopPort(data);
            ops.add(nodeId + ":AddChains:" + port);
            String reply = chainReplies.get(nodeId + ":" + port);
            return reply == null ? "OK" : reply;
        }
        if (!"AddService".equals(type)) {
            ops.add(nodeId + ":" + type);
            return "OK";
        }
        int port = firstServicePort(data);
        ops.add(nodeId + ":AddService:" + port);
        String reply = addServiceReplies.get(nodeId + ":" + port);
        return reply == null ? "OK" : reply;
    }

    // AddChains 的 data 形如 {name, hops:[{nodes:[{addr:"ip:port"}]}]}
    private static int firstChainHopPort(Object data) {
        JSONArray hops = ((JSONObject) data).getJSONArray("hops");
        JSONArray chainNodes = hops.getJSONObject(0).getJSONArray("nodes");
        String addr = chainNodes.getJSONObject(0).getString("addr");
        return Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1));
    }

    private static int firstServicePort(Object data) {
        JSONArray services = (JSONArray) data;
        JSONObject service = services.getJSONObject(0);
        String addr = service.getString("addr");
        return Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1));
    }

    private static Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + nodeId);
        node.setPort(NODE_PORT_RANGE);
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setStatus(1);
        return node;
    }

    private static ChainTunnel chainTunnel(long nodeId, int chainType, Integer port) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setNodeId(nodeId);
        chainTunnel.setChainType(chainType);
        chainTunnel.setPort(port);
        chainTunnel.setProtocol("tls");
        chainTunnel.setStrategy("round");
        return chainTunnel;
    }

    // 入口 → 出口（无中间跳），出口端口留空走自动分配
    private static TunnelDto autoPortTunnel() {
        return dto(List.of(chainTunnel(1L, 1, null)), List.of(), List.of(chainTunnel(2L, 3, null)));
    }

    private static TunnelDto explicitPortTunnel(int port) {
        return dto(List.of(chainTunnel(1L, 1, null)), List.of(), List.of(chainTunnel(2L, 3, port)));
    }

    private static TunnelDto dto(List<ChainTunnel> inNodes, List<List<ChainTunnel>> chainNodes, List<ChainTunnel> outNodes) {
        TunnelDto tunnelDto = new TunnelDto();
        tunnelDto.setName("tunnel-" + TUNNEL_ID);
        tunnelDto.setType(2);
        tunnelDto.setFlow(1);
        tunnelDto.setTrafficRatio(BigDecimal.ONE);
        tunnelDto.setInNodeId(new ArrayList<>(inNodes));
        tunnelDto.setChainNodes(new ArrayList<>(chainNodes));
        tunnelDto.setOutNodeId(new ArrayList<>(outNodes));
        return tunnelDto;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
