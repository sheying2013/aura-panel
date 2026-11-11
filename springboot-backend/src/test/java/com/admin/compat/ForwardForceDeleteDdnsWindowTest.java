package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsDomainGate;
import com.admin.ddns.DdnsTargetRenderer;
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
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 强制删除（forceDeleteForward）与 DDNS 切换窗口交叉的确定性回归。
 *
 * <p>缺陷原型：强制删除只删库、不碰节点，然后靠 {@code requestServiceList} 让
 * NodeOrphanSweeper 按「库里没有」把节点服务回收。如果此刻 DDNS 切换窗口正在同一批服务上
 * 逐条推进新地址，两条路就会交叉：清扫删掉的服务被切换的剩余下发重新建回来（库里无记录、
 * 节点仍在监听），或者已删服务被改回旧地址。修复后强制删除与普通删除同口径，先取共享下发
 * 凭证，窗口内直接拒绝，节点上一个包都不发。
 *
 * <p>锁住四件事：
 * 1. 切换窗口开着（命中本转发 remoteAddr 的任一域名）时强制删除被拒，库零写入、节点零下发，
 *    而且不得动到别人的切换状态；
 * 2. 拿不到下发窗口（别的下发在飞）时同样是「未改动任何节点」的失败，不是静默放行；
 * 3. 放行时「删端口记录 → 删转发 → 索取资源清单」全部发生在窗口内，窗口在最后才释放；
 * 4. 删完立刻能把该域名的切换窗口抢回来（凭证没泄漏），IP 字面量的转发不被无关切换挡住。
 */
class ForwardForceDeleteDdnsWindowTest {

    private static final String DOMAIN_PRIMARY = "app.example.com";
    private static final String DOMAIN_SECOND = "backup.example.com";
    private static final String REMOTE_ADDR = DOMAIN_PRIMARY + ":8080," + DOMAIN_SECOND + ":9090";
    private static final String IP_REMOTE_ADDR = "10.10.10.10:8080";
    private static final long FORWARD_ID = 21L;
    private static final long TUNNEL_ID = 16L;
    private static final long NODE_ID = 51L;
    private static final long MISSING_NODE_ID = 52L;

    /** 按发生顺序记录「窗口/库/节点」动作，用来证明互斥区间覆盖了哪些副作用。 */
    private final List<String> events = new ArrayList<>();
    /** 发往节点的原始报文，按发送顺序。 */
    private final List<String> sent = new ArrayList<>();

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private DdnsAddressRegistry registry;
    private String remoteAddr = REMOTE_ADDR;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        events.clear();
        sent.clear();
        remoteAddr = REMOTE_ADDR;

        // 构造即把真实闸门（render/transitionTrust/dispatch）接到 DdnsTargetRenderer 上
        registry = new DdnsAddressRegistry();
        registry.put(DOMAIN_PRIMARY, "1.1.1.1");
        registry.put(DOMAIN_SECOND, "2.2.2.2");

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        NodeService nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        UserService userService = mock(UserService.class);
        com.admin.entity.User admin = new com.admin.entity.User();
        admin.setId(1L);
        admin.setRoleId(0);
        when(userService.getById(any())).thenReturn(admin);
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any()))
                .thenReturn(new ArrayList<>(List.of(entry(NODE_ID), entry(MISSING_NODE_ID))));
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>(List.of(entryPort())));
        when(forwardMapper.selectById(FORWARD_ID)).thenAnswer(invocation -> forward());
        when(forwardMapper.deleteById(any())).thenAnswer(invocation -> {
            events.add("forward-delete:" + invocation.getArgument(0));
            return 1;
        });
        doAnswer(invocation -> {
            events.add("port-remove");
            return true;
        }).when(forwardPortService).remove(any());
        when(nodeService.getById(any())).thenAnswer(invocation -> {
            long nodeId = (Long) invocation.getArgument(0);
            return nodeId == NODE_ID ? node(nodeId) : null;
        });

        registerNode(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
        DdnsTargetRenderer.reset();
    }

    @Test
    void forceDeleteIsRejectedWhileSwitchWindowIsOpenOnAnyOfItsDomains() throws Exception {
        authenticate();
        // 窗口只开在第二个域名上：同一条转发的 remoteAddr 是逗号分隔的多域名，任何一个在切换都得挡住
        try (SwitchWindow window = new SwitchWindow(registry, DOMAIN_SECOND).awaitHeld()) {
            R result = forwardService.forceDeleteForward(FORWARD_ID);

            assertEquals(-1, result.getCode(), "切换窗口内强制删除必须被拒");
            assertTrue(result.getMsg().contains("切换"), result.getMsg());
            assertTrue(result.getMsg().contains("未改动任何节点"), result.getMsg());
            assertTrue(events.isEmpty(), "被拒的强制删除不能动库也不能碰节点：" + events);
            assertTrue(sentPayloads().isEmpty(), "被拒的强制删除不能向节点发任何包");
            verify(forwardMapper, never()).deleteById(any());
            verify(forwardPortService, never()).remove(any());
            assertNotNull(registry.activeTransition(), "拒绝本次删除不得动到别人正在跑的切换");
        }
    }

    @Test
    void forceDeleteFailsClosedWhenAnotherDispatchHoldsTheWindow() throws Exception {
        authenticate();
        // 没有切换在跑，但下发窗口抢不到（别的编辑/对账正持有读锁且等待超时）：
        // 同样必须「未改动任何节点」地失败，而不是绕过闸门直接删库
        DdnsTargetRenderer.installDispatch(ignored -> null);

        R result = forwardService.forceDeleteForward(FORWARD_ID);

        assertEquals(-1, result.getCode(), "拿不到下发窗口必须失败");
        assertTrue(result.getMsg().contains("其它下发"), result.getMsg());
        assertTrue(result.getMsg().contains("未改动任何节点"), result.getMsg());
        assertTrue(events.isEmpty(), "拿不到窗口时不能动库也不能碰节点：" + events);
        assertTrue(sentPayloads().isEmpty());
    }

    @Test
    void forceDeleteMutatesAndAsksForServiceListInsideTheDispatchWindow() throws Exception {
        authenticate();
        // 用可观测凭证替换闸门：只关心「窗口从哪儿开到哪儿」以及窗口内拿到了什么 remoteAddr
        DdnsTargetRenderer.installDispatch(addr -> {
            events.add("lease-open:" + addr);
            return () -> events.add("lease-close");
        });

        R result = forwardService.forceDeleteForward(FORWARD_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        assertNull(result.getData());
        assertEquals(List.of(
                        "lease-open:" + REMOTE_ADDR,
                        "port-remove",
                        "forward-delete:" + FORWARD_ID,
                        "node-call:" + NODE_ID + ":info_request",
                        "lease-close"),
                events,
                "删端口记录、删转发、索取资源清单都必须落在同一次下发窗口内，窗口最后才释放");
        assertEquals(List.of("info_request"), sentCallTypes(),
                "每个在线入口节点都要收到一次资源清单索取，交给 NodeOrphanSweeper 回收残留服务");
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
    }

    @Test
    void forceDeleteSucceedsOnceTheSwitchWindowCloses() throws Exception {
        authenticate();
        SwitchWindow window = new SwitchWindow(registry, DOMAIN_PRIMARY).awaitHeld();
        // 窗口在等 2000ms 超时后失败返回，不能凭「检查过标记」直接把库删了
        R rejected = forwardService.forceDeleteForward(FORWARD_ID);
        assertEquals(-1, rejected.getCode(), rejected.getMsg());
        assertTrue(events.isEmpty(), "窗口还没关就不能删：" + events);

        window.close();
        R deleted = forwardService.forceDeleteForward(FORWARD_ID);
        assertEquals(0, deleted.getCode(), "窗口关闭后强制删除应正常：" + deleted.getMsg());
        assertEquals(List.of("port-remove", "forward-delete:" + FORWARD_ID, "node-call:" + NODE_ID + ":info_request"), events);
    }

    @Test
    void withoutTheGateTheVerySameWindowDeletesAnyway() throws Exception {
        authenticate();
        // 对照实验：把凭证换成「永远放行」（等价于修复前的实现），同样的切换窗口下删除照旧执行。
        // 这条保证上面「窗口内被拒」确实来自 forceDeleteForward 自己取的凭证，
        // 而不是测试里恰好什么也没发生 —— 否则回归就是假阳性。
        DdnsTargetRenderer.installDispatch(addr -> DdnsDomainGate.Lease.NONE);

        try (SwitchWindow window = new SwitchWindow(registry, DOMAIN_SECOND).awaitHeld()) {
            R result = forwardService.forceDeleteForward(FORWARD_ID);

            assertEquals(0, result.getCode(), result.getMsg());
            assertEquals(List.of("port-remove", "forward-delete:" + FORWARD_ID,
                    "node-call:" + NODE_ID + ":info_request"), events);
        }
    }

    @Test
    void forceDeleteReleasesTheDispatchWindowAfterSuccess() throws Exception {
        authenticate();
        assertEquals(0, forwardService.forceDeleteForward(FORWARD_ID).getCode());

        // 读锁没释放的话这里抢不到写锁（同线程也不允许读锁升级）
        DdnsDomainGate.Lease after = registry.beginTransitionLease(DOMAIN_PRIMARY, "后续切换", 0);
        assertNotNull(after, "强制删除结束后不得继续占着该域名的下发窗口");
        after.close();
    }

    @Test
    void forceDeleteOfIpLiteralForwardIgnoresUnrelatedSwitchWindow() throws Exception {
        authenticate();
        remoteAddr = IP_REMOTE_ADDR;

        try (SwitchWindow window = new SwitchWindow(registry, DOMAIN_PRIMARY).awaitHeld()) {
            R result = forwardService.forceDeleteForward(FORWARD_ID);

            assertEquals(0, result.getCode(), "字面量 IP 不参与 DDNS 闸门，不该被无关切换挡住：" + result.getMsg());
            assertEquals(List.of("port-remove", "forward-delete:" + FORWARD_ID, "node-call:" + NODE_ID + ":info_request"), events);
        }
    }

    /**
     * 在独立线程里持有切换窗口：ReentrantReadWriteLock 的读锁在同一线程里可以越过自己持有的写锁，
     * 只有跨线程才真正互斥，所以窗口必须由别的线程拿、也必须由它释放。
     */
    private static final class SwitchWindow implements AutoCloseable {

        private final Thread thread;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile DdnsDomainGate.Lease lease;

        SwitchWindow(DdnsAddressRegistry registry, String domain) {
            thread = new Thread(() -> {
                lease = registry.beginTransitionLease(domain, "主地址切换下发中", 2000);
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (lease != null) {
                    lease.close();
                }
            }, "ddns-switch-window");
            thread.setDaemon(true);
            thread.start();
        }

        SwitchWindow awaitHeld() throws InterruptedException {
            assertTrue(held.await(5, TimeUnit.SECONDS), "切换窗口线程应已启动");
            assertNotNull(lease, "测试前置：切换窗口应能打开");
            return this;
        }

        @Override
        public void close() {
            release.countDown();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ========== 脚手架 ==========

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":1,\"role_id\":0,\"name\":\"admin\"}"
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(1);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setName("force-delete-gate");
        forward.setStatus(1);
        forward.setRemoteAddr(remoteAddr);
        forward.setStrategy("round");
        return forward;
    }

    private static ChainTunnel entry(long nodeId) {
        ChainTunnel entry = new ChainTunnel();
        entry.setTunnelId(TUNNEL_ID);
        entry.setChainType(1);
        entry.setNodeId(nodeId);
        return entry;
    }

    private static ForwardPort entryPort() {
        ForwardPort port = new ForwardPort();
        port.setForwardId(FORWARD_ID);
        port.setNodeId(NODE_ID);
        port.setPort(20000);
        return port;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private static Node node(long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + (nodeId % 100));
        node.setPort("20000-20010");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        return node;
    }

    private void registerNode(long nodeId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + nodeId);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            sent.add(payload);
            JSONObject message = JSON.parseObject(payload);
            events.add("node-call:" + nodeId + ":" + message.getString("callType"));
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto reply = new GostDto();
                reply.setMsg("OK");
                future.complete(reply);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(nodeId, session);
    }

    private List<String> sentCallTypes() {
        List<String> callTypes = new ArrayList<>();
        for (String payload : sentPayloads()) {
            callTypes.add(JSON.parseObject(payload).getString("callType"));
        }
        return callTypes;
    }

    private List<String> sentPayloads() {
        return new ArrayList<>(sent);
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
