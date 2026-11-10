package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
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

import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 普通转发（createForward / updateForward）自动分配端口的换口重试回归测试。
 *
 * <p>覆盖的原缺陷：链路创建路径早就有"自动端口 bind 失败换下一个候选端口"的重试，
 * 普通转发却没有——节点上一个僵尸监听（上次崩溃残留、或 gost 之外的进程）占着自动分配到的端口时，
 * AddService 回 bind: address already in use，本次创建永久失败，而且这个端口只在库里被占着，
 * 节点上根本没人拥有它。
 *
 * <p>这里锁死的行为：
 * 1. 自动分配端口：bind 失败 → 先确认删掉同名残留 → 换下一个候选端口重试（最多 3 个，含首次），成功后回写库；
 * 2. 候选耗尽：整体失败，节点上不留本次尝试创建的 service；
 * 3. 显式指定 inPort：不换口，直接把占用原因报回去；
 * 4. 非 bind 类错误：原样失败，不掩盖真错因；
 * 5. 换口成功但端口记录回写失败：删掉刚下发的服务再报错（否则就是本次要修的僵尸态）；
 * 6. 编辑路径换口把它自己原来的服务删掉后最终仍失败：必须按库内原端口把原服务建回来。
 */
class ForwardPortRetryTest {

    private static final long FORWARD_ID = 1L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    private static final long NODE_ID = 101L;
    private static final String NODE_PORT_RANGE = "30201-30210";
    private static final String SERVICE_NAME = FORWARD_ID + "_" + USER_ID + "_0";
    private static final String BIND_FAIL =
            "-1: create service %s failed: listen tcp 0.0.0.0:%d: bind: address already in use";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private ChainTunnelService chainTunnelService;
    private Map<Long, WebSocketSession> nodeSessions;
    private Map<String, CompletableFuture<GostDto>> pendingRequests;

    /** key = "nodeId:type:port"，未登记的回复 OK；DeleteService 用 "nodeId:DeleteService" */
    private final Map<String, String> replies = new HashMap<>();
    /** 按发生顺序记录节点侧动作，形如 "101:AddService:30201" / "101:DeleteService" */
    private final List<String> ops = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        pendingRequests = (Map<String, CompletableFuture<GostDto>>) staticField("pendingRequests");
        nodeSessions.clear();
        pendingRequests.clear();
        replies.clear();
        ops.clear();
        authenticate();

        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        TunnelService tunnelService = mock(TunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        when(forwardMapper.insert(any(Forward.class))).thenAnswer(invocation -> {
            ((Forward) invocation.getArgument(0)).setId(FORWARD_ID);
            return 1;
        });
        when(forwardMapper.selectById(any())).thenReturn(forward());
        when(forwardMapper.updateById(any(Forward.class))).thenReturn(1);
        when(forwardMapper.deleteById(any(Serializable.class))).thenReturn(1);
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.updateById(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        // 库里没有别的转发占这份端口段
        when(forwardPortService.list(any())).thenAnswer(invocation -> new ArrayList<ForwardPort>());
        when(forwardPortService.getOne(any())).thenReturn(storedPort());
        when(tunnelService.getById(any())).thenReturn(tunnel());
        // 每次查询都给一份新的行（模拟按库读），端口仍为空表示待分配
        when(chainTunnelService.list(any())).thenAnswer(invocation -> chainTunnels());
        when(nodeService.getById(any())).thenAnswer(invocation -> node());

        registerNode();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        nodeSessions.clear();
        pendingRequests.clear();
    }

    // ========== 创建路径 ==========

    @Test
    void autoPortBindFailureSwitchesToNextCandidateAndWritesBack() {
        bindFailsOn("AddService", 30201);

        R result = forwardService.createForward(autoPortForwardDto());

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(List.of("101:AddService:30201", "101:DeleteService", "101:AddService:30202"), ops,
                "自动端口被节点占用时必须先清掉同名残留再换 30202，不能直接叠加下发");
        assertEquals(30202, writtenBackPort(), "换口成功后库里必须是最终生效的端口");
        verify(forwardPortService, times(1)).updateById(any(ForwardPort.class));
    }

    @Test
    void threeBindFailuresFailCreateAndLeaveNoServiceOnNode() {
        bindFailsOn("AddService", 30201);
        bindFailsOn("AddService", 30202);
        bindFailsOn("AddService", 30203);

        R result = forwardService.createForward(autoPortForwardDto());

        assertEquals(-1, result.getCode(), "3 个候选端口全被占必须整体失败");
        String msg = result.getMsg();
        assertTrue(msg.contains("address already in use"), msg);
        assertTrue(msg.contains("30201") && msg.contains("30202") && msg.contains("30203"),
                "失败信息要带上尝试过的端口列表：" + msg);
        assertTrue(msg.contains("端口尝试"), msg);
        assertEquals(List.of("101:AddService:30201", "101:DeleteService",
                "101:AddService:30202", "101:DeleteService",
                "101:AddService:30203", "101:DeleteService"), ops,
                "候选耗尽后节点上不能留下本次尝试创建的 service");
        verify(forwardPortService, never()).updateById(any(ForwardPort.class));
        verify(forwardMapper).deleteById(FORWARD_ID);
        assertFalse(msg.contains("未确认"), "节点清理与库清理都应确认成功：" + msg);
    }

    @Test
    void explicitPortBindFailureDoesNotRetry() {
        bindFailsOn("AddService", 30203);

        R result = forwardService.createForward(explicitPortForwardDto(30203));

        assertEquals(-1, result.getCode(), "调用方指定的端口不可用要立刻失败");
        String msg = result.getMsg();
        assertTrue(msg.contains("显式指定端口"), "要说明为什么不换口：" + msg);
        assertTrue(msg.contains("address already in use"), msg);
        assertEquals(List.of("101:AddService:30203"), ops, "显式端口不允许换口重试");
        verify(forwardPortService, never()).updateById(any(ForwardPort.class));
    }

    @Test
    void nonBindFailureIsNotRetried() {
        replies.put("101:AddService:30201", "-1: invalid listener type");

        R result = forwardService.createForward(autoPortForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("invalid listener type"), result.getMsg());
        assertEquals(List.of("101:AddService:30201"), ops, "不是端口占用就照旧失败，不能换口掩盖真错因");
        assertFalse(result.getMsg().contains("端口尝试"), result.getMsg());
    }

    /**
     * 监听地址在本机不存在（真机 192.0.2.10）会报 "bind: cannot assign requested address"，
     * 这不是端口冲突：换端口仍旧 bind 失败，只会把一次失败放大成三次下发并掩盖真错因。
     */
    @Test
    void addressNotAvailableIsNotTreatedAsPortConflict() {
        replies.put("101:AddService:30201",
                "-1: create service " + SERVICE_NAME
                        + " failed: listen tcp4 192.0.2.10:30201: bind: cannot assign requested address");

        R result = forwardService.createForward(autoPortForwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("cannot assign requested address"), result.getMsg());
        assertEquals(List.of("101:AddService:30201"), ops,
                "监听地址不可用不是端口占用，不允许换口重试");
        assertFalse(result.getMsg().contains("端口尝试"), result.getMsg());
        verify(forwardPortService, never()).updateById(any(ForwardPort.class));
    }

    @Test
    void portWriteBackFailureRemovesFreshlyCreatedService() {
        bindFailsOn("AddService", 30201);
        when(forwardPortService.updateById(any(ForwardPort.class))).thenReturn(false);

        R result = forwardService.createForward(autoPortForwardDto());

        assertEquals(-1, result.getCode(), "库里还是旧端口就不能当创建成功");
        String msg = result.getMsg();
        assertTrue(msg.contains("端口记录回写"), msg);
        assertTrue(msg.contains("已回滚删除刚下发的 service " + SERVICE_NAME), msg);
        assertEquals(List.of("101:AddService:30201", "101:DeleteService", "101:AddService:30202", "101:DeleteService"), ops,
                "节点在监听一个库里没有的端口正是本次要修的僵尸态：回写失败必须删掉刚下发的服务");
    }

    // ========== 编辑路径 ==========

    @Test
    void updateAutoPortExhaustionRestoresOriginalService() {
        bindFailsOn("UpdateService", 30201);
        bindFailsOn("AddService", 30202);
        bindFailsOn("AddService", 30203);

        R result = forwardService.updateForward(autoPortForwardUpdateDto());

        assertEquals(-1, result.getCode(), "候选耗尽必须整体失败");
        String msg = result.getMsg();
        assertTrue(msg.contains("端口尝试"), msg);
        assertTrue(msg.contains("已按库内原端口30201 重建原服务"), "换口把旧服务删掉后必须按库里的原端口建回来：" + msg);
        assertEquals(List.of("101:UpdateService:30201", "101:DeleteService",
                "101:AddService:30202", "101:DeleteService",
                "101:AddService:30203", "101:AddService:30201"), ops,
                "最后一次 30201 是重建原服务（UpdateService 对已删除的服务会回 not found）");
        verify(forwardMapper, never()).updateById(any(Forward.class));
        verify(forwardPortService, never()).updateById(any(ForwardPort.class));
    }

    // ========== 夹具 ==========

    private void bindFailsOn(String type, int port) {
        replies.put("101:" + type + ":" + port, String.format(BIND_FAIL, SERVICE_NAME + "_tcp", port));
    }

    /** 节点上自动分配到的端口 30201 被僵尸监听占着，30202 起可用。 */
    private int writtenBackPort() {
        ArgumentCaptor<ForwardPort> captor = ArgumentCaptor.forClass(ForwardPort.class);
        verify(forwardPortService).updateById(captor.capture());
        return captor.getValue().getPort();
    }

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static ForwardDto autoPortForwardDto() {
        ForwardDto dto = new ForwardDto();
        dto.setName("forward-test");
        dto.setTunnelId(TUNNEL_ID);
        dto.setRemoteAddr("10.10.10.10:8080");
        dto.setStrategy("round");
        return dto;
    }

    private static ForwardDto explicitPortForwardDto(int port) {
        ForwardDto dto = autoPortForwardDto();
        dto.setInPort(port);
        return dto;
    }

    private static ForwardUpdateDto autoPortForwardUpdateDto() {
        ForwardUpdateDto dto = new ForwardUpdateDto();
        dto.setId(FORWARD_ID);
        dto.setUserId(USER_ID);
        dto.setName("forward-renamed");
        dto.setRemoteAddr("10.10.10.10:9090");
        dto.setStrategy("round");
        return dto;
    }

    private static Forward forward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("forward-test");
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("10.10.10.10:8080");
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private static ForwardPort storedPort() {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setId(11L);
        forwardPort.setForwardId(FORWARD_ID);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(30201);
        return forwardPort;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private static List<ChainTunnel> chainTunnels() {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId((long) TUNNEL_ID);
        chainTunnel.setChainType(1);
        chainTunnel.setNodeId(NODE_ID);
        List<ChainTunnel> rows = new ArrayList<>();
        rows.add(chainTunnel);
        return rows;
    }

    private static Node node() {
        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("node-101");
        node.setServerIp("10.0.0.101");
        node.setPort(NODE_PORT_RANGE);
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setStatus(1);
        return node;
    }

    private void registerNode() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("fake-node-" + NODE_ID);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            JSONObject message = JSON.parseObject(((TextMessage) invocation.getArgument(0)).getPayload());
            CompletableFuture<GostDto> future = pendingRequests.get(message.getString("requestId"));
            if (future != null) {
                GostDto gostDto = new GostDto();
                gostDto.setMsg(replyFor(message.getString("type"), message.get("data")));
                future.complete(gostDto);
            }
            return null;
        }).when(session).sendMessage(any());
        nodeSessions.put(NODE_ID, session);
    }

    private String replyFor(String type, Object data) {
        if ("DeleteService".equals(type)) {
            ops.add(NODE_ID + ":DeleteService");
            String reply = replies.get(NODE_ID + ":DeleteService");
            return reply == null ? "OK" : reply;
        }
        int port = firstServicePort(data);
        ops.add(NODE_ID + ":" + type + ":" + port);
        String reply = replies.get(NODE_ID + ":" + type + ":" + port);
        return reply == null ? "OK" : reply;
    }

    private static int firstServicePort(Object data) {
        JSONArray services = (JSONArray) data;
        String addr = services.getJSONObject(0).getString("addr");
        return Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1));
    }

    private static Object staticField(String name) throws Exception {
        Field field = Class.forName("com.admin.common.utils.WebSocketServer").getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
