package com.admin.compat;

import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsDomainGate;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.entity.Forward;
import com.admin.entity.User;
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
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 普通下发与 DDNS 切换窗口互斥的回归（节点下发通道由假 WebSocket 会话接管）。
 *
 * <p>锁住四件事：
 * 1. 切换窗口开着时，删除转发被拒，且一个 DeleteService 都不发（删前被切换挡住）；
 * 2. 切换窗口开着时，编辑转发被拒，节点零下发、库零写入；
 * 3. 切换窗口开着时，补偿路径（restoreNodeServices）里 GostUtil 抛的 DdnsTransitionBusyException
 *    被逐节点吞成失败明细，后面的节点继续补偿，不中断调用方正在上报的原始错误；
 * 4. 无窗口时部分成功（node41 OK / node42 不在线）仍返回明细，不抛异常。
 */
class DdnsDispatchGateTest {

    private static final String DOMAIN = "app.example.com";
    private static final String REMOTE_ADDR = DOMAIN + ":8080";
    private static final long FORWARD_ID = 9L;
    private static final long NODE_OK = 41L;
    private static final long NODE_ABSENT = 42L;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;
    private DdnsAddressRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();

        registry = new DdnsAddressRegistry();
        registry.put(DOMAIN, "1.1.1.1");

        forwardMapper = mock(ForwardMapper.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);
        NodeService nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        // checkUserPermissions 持锁后先重读账号行：管理员替身必须能从库里读到一条 role_id=0 的行，否则按账号不存在拒绝。
        UserService stubbedUserService = mock(UserService.class);
        User stubbedAdminRow = new User();
        stubbedAdminRow.setId(1L);   // authenticate() 用的 sub=1
        stubbedAdminRow.setRoleId(0);
        when(stubbedUserService.getById(any())).thenReturn(stubbedAdminRow);
        ReflectionTestUtils.setField(forwardService, "userService", stubbedUserService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>(List.of(entry())));
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>(List.of(entryPort())));
        when(forwardMapper.selectById(FORWARD_ID)).thenReturn(forward());
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(forwardMapper.updateById(any())).thenReturn(1);
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));

        registerNode(NODE_OK);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
        DdnsTargetRenderer.reset();
    }

    @Test
    void deleteForwardIsRejectedWithoutTouchingNodesWhileSwitchWindowIsOpen() throws Exception {
        authenticate();
        // 切换窗口必须由另一个线程持有：同一线程里读写锁可重入，测不出互斥
        try (SwitchWindow window = new SwitchWindow(registry).awaitHeld()) {
            R result = forwardService.deleteForward(FORWARD_ID);

            assertEquals(-1, result.getCode(), "切换窗口内删除必须被拒");
            assertTrue(result.getMsg().contains("切换"), result.getMsg());
            assertTrue(sentPayloads(NODE_OK).isEmpty(), "被拒的删除不能碰节点");
            verify(forwardMapper, never()).deleteById(any());
        }
    }

    @Test
    void updateForwardIsRejectedWithoutTouchingNodesWhileSwitchWindowIsOpen() throws Exception {
        authenticate();
        try (SwitchWindow window = new SwitchWindow(registry).awaitHeld()) {
            R result = forwardService.updateForward(updateDto());

            assertEquals(-1, result.getCode(), "切换窗口内编辑必须被拒");
            assertTrue(result.getMsg().contains("切换"), result.getMsg());
            assertTrue(sentPayloads(NODE_OK).isEmpty(), "被拒的编辑不能碰节点");
            verify(forwardMapper, never()).updateById(any());
        }
    }

    @Test
    void deleteBlocksUntilSwitchWindowClosesThenSucceeds() throws Exception {
        authenticate();
        SwitchWindow window = new SwitchWindow(registry).awaitHeld();
        // 删除在窗口上等待（2000ms 超时）→ 必须失败返回，不能凭「检查过标志」直接删
        R rejected = forwardService.deleteForward(FORWARD_ID);
        assertEquals(-1, rejected.getCode(), rejected.getMsg());
        assertTrue(sentPayloads(NODE_OK).isEmpty(), "窗口未关就不能删节点服务");

        window.close();
        R deleted = forwardService.deleteForward(FORWARD_ID);
        assertEquals(0, deleted.getCode(), "窗口关闭后删除应正常： " + deleted.getMsg());
        assertFalse(sentPayloads(NODE_OK).isEmpty(), "窗口关闭后删除必须真的下发");
    }

    @Test
    void restoreNodeServicesKeepsCompensatingWhenGateThrowsInsideCompensation() throws Exception {
        DdnsDomainGate.Lease window = registry.beginTransitionLease(DOMAIN, "切换中", 0);
        assertNotNull(window);

        String outcome = invokeRestore(appliedOn(NODE_OK, NODE_ABSENT));

        assertNotNull(outcome, "补偿有失败时必须返回明细");
        assertTrue(outcome.contains("node" + NODE_OK + "(补偿异常"), outcome);
        assertTrue(outcome.contains("node" + NODE_ABSENT + "(补偿异常"), "前一个节点抛错不能中断后续补偿：" + outcome);
        assertTrue(outcome.contains("需人工确认"), outcome);
        assertTrue(sentPayloads(NODE_OK).isEmpty(), "渲染阶段就该拒绝，一个包都不能发");
        window.close();
    }

    @Test
    void restoreNodeServicesReportsPartialSuccessWithoutThrowing() throws Exception {
        String outcome = invokeRestore(appliedOn(NODE_OK, NODE_ABSENT));

        assertNotNull(outcome, "node42 不在线时必须报告，而不是静默成功");
        assertTrue(outcome.contains("node" + NODE_ABSENT), outcome);
        assertFalse(outcome.contains("node" + NODE_OK + "("), "node41 下发成功不应出现在失败明细：" + outcome);
        assertEquals(List.of("UpdateService"), sentMessageTypes(NODE_OK));
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

        SwitchWindow(DdnsAddressRegistry registry) {
            thread = new Thread(() -> {
                lease = registry.beginTransitionLease(DOMAIN, "主地址 1.1.1.1 → 2.2.2.2 下发中", 2000);
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

    private String invokeRestore(List<?> applied) throws Exception {
        Method method = ForwardServiceImpl.class.getDeclaredMethod("restoreNodeServices",
                List.class, Forward.class, String.class, Integer.class, Tunnel.class);
        method.setAccessible(true);
        return (String) method.invoke(forwardService, applied, forward(), FORWARD_ID + "_1_0", null, tunnel());
    }

    private List<Object> appliedOn(long... nodeIds) throws Exception {
        Class<?> type = Class.forName("com.admin.service.impl.ForwardServiceImpl$NodeServiceUpdate");
        Constructor<?> constructor = type.getDeclaredConstructor(Node.class, ForwardPort.class, Integer.class);
        constructor.setAccessible(true);
        List<Object> applied = new ArrayList<>();
        for (long nodeId : nodeIds) {
            ForwardPort port = new ForwardPort();
            port.setPort(30200);
            applied.add(constructor.newInstance(node(nodeId), port, 30200));
        }
        return applied;
    }

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":1,\"role_id\":0,\"name\":\"admin\"}"
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ForwardUpdateDto updateDto() {
        ForwardUpdateDto dto = new ForwardUpdateDto();
        dto.setId(FORWARD_ID);
        dto.setUserId(1);
        dto.setName("updated");
        dto.setRemoteAddr(REMOTE_ADDR);
        dto.setStrategy("round");
        dto.setInPort(20000);
        return dto;
    }

    private static Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(1);
        forward.setTunnelId(16);
        forward.setName("gate-test");
        forward.setStatus(1);
        forward.setRemoteAddr(REMOTE_ADDR);
        forward.setStrategy("round");
        return forward;
    }

    private static com.admin.entity.ChainTunnel entry() {
        com.admin.entity.ChainTunnel entry = new com.admin.entity.ChainTunnel();
        entry.setTunnelId(16L);
        entry.setChainType(1);
        entry.setNodeId(NODE_OK);
        return entry;
    }

    private static ForwardPort entryPort() {
        ForwardPort port = new ForwardPort();
        port.setForwardId(FORWARD_ID);
        port.setNodeId(NODE_OK);
        port.setPort(20000);
        return port;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(16L);
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
            JSONObject message = JSON.parseObject(payload);
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

    private List<String> sentMessageTypes(long nodeId) throws Exception {
        List<String> types = new ArrayList<>();
        for (String payload : sentPayloads(nodeId)) {
            types.add(JSON.parseObject(payload).getString("type"));
        }
        return types;
    }

    private List<String> sentPayloads(long nodeId) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(nodeSessions.get(nodeId), atLeast(0)).sendMessage(captor.capture());
        List<String> payloads = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            payloads.add(message.getPayload());
        }
        return payloads;
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}