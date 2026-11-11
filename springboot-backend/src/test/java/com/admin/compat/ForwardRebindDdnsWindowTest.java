package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.entity.ChainTunnel;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计项 2 的本地回归：rebindServicesForChain 先 DeleteService 后 render。
 *
 * <p>缺陷现场（ForwardServiceImpl.rebindServicesForChain，:3835/:3840）：
 * 节点上入口服务 21_1_0 还在、但 chains_16 丢了，补齐链之后必须删掉重发才能让 handler 重新挂链。
 * 代码顺序是 deleteServices() → GostUtil.CreateForwardService()，而 CreateForwardService 在
 * 构建报文时才会走过 DdnsTargetRenderer.render；DDNS 切换窗口内 render 抛
 * DdnsTransitionBusyException，异常被 :3856 的 catch (RuntimeException) 吞成一条 notes。
 * 结果：节点上的服务已经被删掉，重发根本没发出去，也没有任何补偿，入口监听直接消失。
 *
 * <p>这两个用例不靠调用次数猜结论，而是用「节点可见状态」证明：
 * 假会话维护一份 nodePresence（DeleteService 真的从里面移除、AddService 才加回来），
 * 断言全过程结束后那份清单里仍有 21_1_0_tcp。当前代码在删除后抛异常 → 清单里没有 → 红。
 *
 * <p>边界（用例名即边界）：窗口只需落在「DeleteService 应答之后、CreateForwardService 渲染之前」；
 * 切换位在进入本方法前就已占住时，删除同样照发（删除路径根本不看切换位），
 * 所以这个缺口不需要极小概率的并发穿插，只要切位存在就成立。
 */
class ForwardRebindDdnsWindowTest {

    private static final long TUNNEL_ID = 16L;
    private static final long IN_NODE = 1L;
    private static final long CHAIN_NODE = 2L;
    private static final long OUT_NODE = 3L;
    private static final int CHAIN_PORT = 30201;
    private static final int OUT_PORT = 30202;
    private static final int ENTRY_PORT = 30100;
    private static final String DOMAIN = "ddns.example.com";
    private static final String SERVICE = "21_1_0";
    private static final String SERVICE_TCP = SERVICE + "_tcp";
    private static final String SERVICE_UDP = SERVICE + "_udp";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private ForwardPortService forwardPortService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** 真实注册表：构造即通过 DdnsTargetRenderer.install 接管渲染，不加任何静态 mock。 */
    private DdnsAddressRegistry registry;
    /** 节点上「真实存在」的服务名，只有 DeleteService/AddService 应答才改动它。 */
    private final Set<String> nodePresence = new LinkedHashSet<>();
    /** 需要在 DeleteService 应答之后立刻抢占切换位（复现时序窗口）时置 true。 */
    private boolean transitionOnDeleteAck;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        nodePresence.clear();
        nodePresence.add(SERVICE_TCP);

        registry = new DdnsAddressRegistry();
        registry.put(DOMAIN, "1.1.1.1");
        transitionOnDeleteAck = false;

        forwardMapper = mock(ForwardMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        forwardPortService = mock(ForwardPortService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));

        List<Forward> forwards = List.of(forward());
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> forwards.stream()
                .filter(f -> f.getId().equals(invocation.getArgument(0))).findFirst().orElse(null));
        when(forwardMapper.selectList(any())).thenAnswer(invocation -> {
            Object wrapper = invocation.getArgument(0);
            String segment = wrapper instanceof QueryWrapper<?> qw ? qw.getSqlSegment() : null;
            return segment != null && segment.contains("status") ? forwards : forwards;
        });
        when(chainTunnelService.list(any())).thenReturn(chainRows());
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        when(nodeService.listByIds(any())).thenReturn(List.of(node(IN_NODE), node(CHAIN_NODE), node(OUT_NODE)));
        when(tunnelService.getById(any())).thenReturn(tunnel());
        // 入口节点上这条转发有自己的端口行，否则 rebind 分支直接 continue（什么都不删）
        when(forwardPortService.getOne(any())).thenReturn(forwardPort(ENTRY_PORT, IN_NODE));

        registerNode(IN_NODE);
        registerNode(CHAIN_NODE);
        registerNode(OUT_NODE);
    }

    @AfterEach
    void tearDown() {
        registry.endTransition(DOMAIN);
        registry.clear();
        nodeSessions.clear();
        pendingRequests.clear();
        nodePresence.clear();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("切换位在 DeleteService 应答后、CreateForwardService 渲染前占住 → 入口服务不得消失")
    void transitionStartedBetweenDeleteAndRecreateLosesEntryService() throws Exception {
        // 时序就卡在这一刻：删除已应答（节点上真的没了），重发还没开始构建报文
        transitionOnDeleteAck = true;

        List<String> notes = forwardService.resyncNodeServices(IN_NODE,
                List.of(SERVICE_TCP), List.of());

        // 只断言用户可见的结果：窗口结束时节点上还得有这条入口服务。
        // 「不许先删后失败」既可以靠不删实现，也可以靠删了再补回来实现，两种修法都该让这条绿。
        assertTrue(nodePresence.contains(SERVICE_TCP),
                "切换窗口内不得把入口服务留在已删除状态（节点当前服务清单：" + nodePresence
                        + "，下发序列：" + typeNames(sentMessages(IN_NODE)) + "，notes：" + notes + "）");
    }

    @Test
    @DisplayName("进入重挂前切换位就已占住 → 不需要穿插，入口服务同样不得消失")
    void alreadyActiveTransitionStillDeletesEntryService() throws Exception {
        // 这个用例不需要任何穿插：说明缺口不是「极小概率并发」，只要窗口存在就成立
        assertTrue(registry.beginTransition(DOMAIN, "已推送新地址，等待节点确认"));

        List<String> notes = forwardService.resyncNodeServices(IN_NODE,
                List.of(SERVICE_TCP), List.of());

        assertTrue(nodePresence.contains(SERVICE_TCP),
                "切换位已占用时同样不得把入口服务留在已删除状态（节点当前服务清单：" + nodePresence
                        + "，下发序列：" + typeNames(sentMessages(IN_NODE)) + "，notes：" + notes + "）");
    }

    @Test
    @DisplayName("对照组：没有切换位时删除+重发正常走完 → 服务仍在（证明上面的红是切换位造成，不是假节点失真）")
    void controlRebindWithoutTransitionKeepsEntryService() throws Exception {
        List<String> notes = forwardService.resyncNodeServices(IN_NODE,
                List.of(SERVICE_TCP), List.of());

        assertTrue(nodePresence.contains(SERVICE_TCP),
                "正常重挂后服务必须在（节点当前服务清单：" + nodePresence + "，notes：" + notes + "）");
        assertTrue(notes.stream().anyMatch(n -> n.contains("已重挂入口服务 21_1_0@node1")),
                "正常路径必须走到重挂成功分支，实际：" + notes);
    }

    @Test
    void rebindSkipsWhileForwardMutationIsHeld() throws Exception {
        var lease = com.admin.common.utils.ForwardWriteLocks.acquire(0, 21L);
        assertTrue(lease != null);
        try {
            List<String> notes = forwardService.resyncNodeServices(IN_NODE, List.of(SERVICE_TCP), List.of());
            assertTrue(nodePresence.contains(SERVICE_TCP), "互斥时不得删除既有服务");
            assertTrue(notes.stream().anyMatch(n -> n.contains("正在修改")), "必须报告跳过：" + notes);
            assertTrue(!typeNames(sentMessages(IN_NODE)).contains("DeleteService"));
        } finally {
            lease.close();
        }
    }

    @Test
    void rebindRereadsForwardAfterSnapshot() throws Exception {
        Forward updated = forward();
        updated.setStatus(0);
        when(forwardMapper.selectById(any())).thenReturn(updated);
        forwardService.resyncNodeServices(IN_NODE, List.of(SERVICE_TCP), List.of());
        assertTrue(nodePresence.contains(SERVICE_TCP), "库中已暂停的规则不得按旧快照重建");
        assertTrue(!typeNames(sentMessages(IN_NODE)).contains("DeleteService"));
    }

    // ---------------------------------------------------------------- 工具

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(21L);
        forward.setName("ddns-rebind");
        forward.setUserId(1);
        forward.setTunnelId((int) TUNNEL_ID);
        // 目标必须是已登记域名，CreateForwardService 构建报文时才会走进 DdnsTargetRenderer.render
        forward.setRemoteAddr(DOMAIN + ":8080");
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(2);
        tunnel.setStatus(1);
        return tunnel;
    }

    /** 入口(1) → 链(2, inx=1) → 出口(3)，与 publishChains 的分组口径一致。 */
    private List<ChainTunnel> chainRows() {
        return List.of(
                chainRow(1, IN_NODE, null, 1),
                chainRow(2, CHAIN_NODE, CHAIN_PORT, 1),
                chainRow(3, OUT_NODE, OUT_PORT, null));
    }

    private ChainTunnel chainRow(int chainType, long nodeId, Integer port, Integer inx) {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL_ID);
        row.setChainType(chainType);
        row.setNodeId(nodeId);
        row.setPort(port);
        row.setInx(inx);
        row.setProtocol("tcp");
        row.setStrategy("round");
        return row;
    }

    private Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + nodeId);
        node.setPort("30000-30100");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private ForwardPort forwardPort(int port, long nodeId) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(21L);
        forwardPort.setNodeId(nodeId);
        forwardPort.setPort(port);
        return forwardPort;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * 假节点：用 nodePresence 当节点上的真实配置，DeleteService 先移除、AddService 才加回。
     * DeleteService 应答之后可选地抢占 DDNS 切换位——这一步与「删除生效」在同一线程上完成，
     * 因此窗口位置是确定的，不依赖 sleep 或线程调度。
     */
    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            JSONObject message = JSON.parseObject(payload);
            String requestId = message.getString("requestId");
            if (requestId == null) {
                return null;
            }
            CompletableFuture<GostDto> future = pendingRequests.get(requestId);
            if (future == null) {
                return null;
            }
            GostDto reply = new GostDto();
            String type = message.getString("type");
            if ("DeleteService".equals(type)) {
                JSONArray services = message.getJSONObject("data").getJSONArray("services");
                for (int i = 0; i < services.size(); i++) {
                    nodePresence.remove(services.getString(i));
                }
                reply.setMsg("OK");
                future.complete(reply);
                if (transitionOnDeleteAck) {
                    // 删除已应答、重发尚未构建：审计指的正是在这之间开启切换
                    registry.beginTransition(DOMAIN, "已推送新地址，等待节点确认");
                }
                return null;
            }
            if ("AddService".equals(type)) {
                JSONArray services = message.getJSONArray("data");
                for (int i = 0; i < services.size(); i++) {
                    nodePresence.add(services.getJSONObject(i).getString("name"));
                }
                reply.setMsg("OK");
            } else {
                reply.setMsg("OK");
            }
            future.complete(reply);
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
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

    private static List<String> typeNames(List<JSONObject> messages) {
        List<String> names = new ArrayList<>();
        for (JSONObject message : messages) {
            names.add(message.getString("type"));
        }
        return names;
    }
}
