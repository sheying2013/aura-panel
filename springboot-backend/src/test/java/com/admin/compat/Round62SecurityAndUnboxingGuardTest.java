package com.admin.compat;

import com.admin.common.dto.LoginDto;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.dto.TunnelUpdateDto;
import com.admin.common.dto.UserDto;
import com.admin.common.dto.UserUpdateDto;
import com.admin.common.interceptor.JwtInterceptor;
import com.admin.common.lang.R;
import com.admin.controller.CustomErrorController;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.ViteConfigService;
import com.admin.service.impl.ForwardServiceImpl;
import com.admin.service.impl.NodeServiceImpl;
import com.admin.service.impl.TunnelServiceImpl;
import com.admin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.RequestDispatcher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * round62 全链路空指针安全、自动拆箱防护、REST API 404/错误响应与跨域预检测试套件。
 */
class Round62SecurityAndUnboxingGuardTest {

    private static final long TUNNEL_ID = 501L;
    private static final long FORWARD_ID = 601L;
    private static final long NODE_ID = 701L;
    private static final long USER_ID = 801L;

    private TunnelServiceImpl tunnelService;
    private TunnelMapper tunnelMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private TunnelService mockTunnelService;

    private NodeServiceImpl nodeServiceImpl;
    private NodeMapper nodeMapper;

    private UserServiceImpl userServiceImpl;
    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        tunnelMapper = mock(TunnelMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        forwardMapper = mock(ForwardMapper.class);
        nodeMapper = mock(NodeMapper.class);
        userMapper = mock(UserMapper.class);

        ForwardPortService forwardPortService = mock(ForwardPortService.class);
        mockTunnelService = mock(TunnelService.class);

        // TunnelService setup
        tunnelService = new TunnelServiceImpl();
        ReflectionTestUtils.setField(tunnelService, "baseMapper", tunnelMapper);
        ReflectionTestUtils.setField(tunnelService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(tunnelService, "nodeService", nodeService);
        ReflectionTestUtils.setField(tunnelService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(tunnelService, "forwardService", mock(ForwardService.class));
        ReflectionTestUtils.setField(tunnelService, "userTunnelService", mock(UserTunnelService.class));

        // ForwardService setup
        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", mockTunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        // NodeServiceImpl setup
        nodeServiceImpl = new NodeServiceImpl();
        ReflectionTestUtils.setField(nodeServiceImpl, "baseMapper", nodeMapper);
        ReflectionTestUtils.setField(nodeServiceImpl, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(nodeServiceImpl, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(nodeServiceImpl, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(nodeServiceImpl, "viteConfigService", mock(ViteConfigService.class));

        // UserServiceImpl setup
        userServiceImpl = new UserServiceImpl();
        ReflectionTestUtils.setField(userServiceImpl, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userServiceImpl, "forwardService", mock(ForwardService.class));
        ReflectionTestUtils.setField(userServiceImpl, "viteConfigService", mock(ViteConfigService.class));

        authenticate();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void authenticate() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = "{\"userId\":1,\"role_id\":0,\"version\":0}";
        String token = "hdr." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
        request.addHeader("Authorization", token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    // ==================== 1. diagnoseTunnel & diagnoseForward ct.chainType null-safety ====================

    @Test
    @DisplayName("diagnoseTunnel：chainTunnels 中存在 chainType 为 null 的记录时不抛拆箱 NPE")
    void testDiagnoseTunnelNullChainTypeSafety() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("diagnose-test-tunnel");
        tunnel.setType(1);
        when(tunnelMapper.selectById(TUNNEL_ID)).thenReturn(tunnel);

        List<ChainTunnel> chains = new ArrayList<>();
        ChainTunnel ctNull = new ChainTunnel();
        ctNull.setTunnelId(TUNNEL_ID);
        ctNull.setNodeId(NODE_ID);
        ctNull.setChainType(null); // null chainType
        ctNull.setPort(30100);
        chains.add(ctNull);

        ChainTunnel ctIn = new ChainTunnel();
        ctIn.setTunnelId(TUNNEL_ID);
        ctIn.setNodeId(NODE_ID);
        ctIn.setChainType(1);
        ctIn.setPort(30100);
        chains.add(ctIn);

        when(chainTunnelService.list(any())).thenReturn(chains);
        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("test-node");
        node.setServerIp("127.0.0.1");
        when(nodeService.getById(NODE_ID)).thenReturn(node);

        R r = tunnelService.diagnoseTunnel(TUNNEL_ID);
        assertEquals(0, r.getCode());
    }

    @Test
    @DisplayName("diagnoseForward：chainTunnels 中存在 chainType 为 null 的记录时不抛拆箱 NPE")
    void testDiagnoseForwardNullChainTypeSafety() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setName("diagnose-test-forward");
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setUserId(1);
        forward.setRemoteAddr("1.1.1.1:80");
        when(forwardMapper.selectById(FORWARD_ID)).thenReturn(forward);

        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("diagnose-test-tunnel");
        tunnel.setType(1);
        when(tunnelMapper.selectById(TUNNEL_ID)).thenReturn(tunnel);
        when(mockTunnelService.getById(any())).thenReturn(tunnel);

        List<ChainTunnel> chains = new ArrayList<>();
        ChainTunnel ctNull = new ChainTunnel();
        ctNull.setTunnelId(TUNNEL_ID);
        ctNull.setNodeId(NODE_ID);
        ctNull.setChainType(null); // null chainType
        ctNull.setPort(30100);
        chains.add(ctNull);

        ChainTunnel ctIn = new ChainTunnel();
        ctIn.setTunnelId(TUNNEL_ID);
        ctIn.setNodeId(NODE_ID);
        ctIn.setChainType(1);
        ctIn.setPort(30100);
        chains.add(ctIn);

        when(chainTunnelService.list(any())).thenReturn(chains);
        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("test-node");
        node.setServerIp("127.0.0.1");
        when(nodeService.getById(NODE_ID)).thenReturn(node);

        R r = forwardService.diagnoseForward(FORWARD_ID);
        assertEquals(0, r.getCode());
    }

    // ==================== 2. TunnelService createTunnel & updateTunnel ====================

    @Test
    @DisplayName("createTunnel：空 DTO、空名称或 type=2 缺出口时安全拦截")
    void testCreateTunnelGuards() {
        R r1 = tunnelService.createTunnel(null);
        assertEquals(-1, r1.getCode());
        assertEquals("隧道参数不能为空", r1.getMsg());

        TunnelDto dtoBlankName = new TunnelDto();
        dtoBlankName.setName("   ");
        R r2 = tunnelService.createTunnel(dtoBlankName);
        assertEquals(-1, r2.getCode());
        assertEquals("隧道名称不能为空", r2.getMsg());

        TunnelDto dtoType2NoOut = new TunnelDto();
        dtoType2NoOut.setName("valid-tunnel");
        dtoType2NoOut.setType(2);
        ChainTunnel inNode = new ChainTunnel();
        inNode.setNodeId(NODE_ID);
        inNode.setChainType(1);
        dtoType2NoOut.setInNodeId(Collections.singletonList(inNode));
        dtoType2NoOut.setOutNodeId(null);
        R r3 = tunnelService.createTunnel(dtoType2NoOut);
        assertEquals(-1, r3.getCode());
        assertEquals("出口不能为空", r3.getMsg());
    }

    @Test
    @DisplayName("updateTunnel：空 DTO 或空 ID 时安全拦截")
    void testUpdateTunnelGuards() {
        R r1 = tunnelService.updateTunnel(null);
        assertEquals(-1, r1.getCode());
        assertEquals("隧道ID不能为空", r1.getMsg());

        TunnelUpdateDto updateDto = new TunnelUpdateDto();
        updateDto.setId(null);
        R r2 = tunnelService.updateTunnel(updateDto);
        assertEquals(-1, r2.getCode());
        assertEquals("隧道ID不能为空", r2.getMsg());
    }

    // ==================== 3. NodeService createNode & updateNode & getInstallCommand ====================

    @Test
    @DisplayName("createNode：空 DTO、空名称、空 IP 安全拦截")
    void testCreateNodeGuards() {
        R r1 = nodeServiceImpl.createNode(null);
        assertEquals(-1, r1.getCode());
        assertEquals("节点参数不能为空", r1.getMsg());

        NodeDto dtoBlankName = new NodeDto();
        dtoBlankName.setName("  ");
        R r2 = nodeServiceImpl.createNode(dtoBlankName);
        assertEquals(-1, r2.getCode());
        assertEquals("节点名称不能为空", r2.getMsg());

        NodeDto dtoBlankIp = new NodeDto();
        dtoBlankIp.setName("valid-node");
        dtoBlankIp.setServerIp("  ");
        R r3 = nodeServiceImpl.createNode(dtoBlankIp);
        assertEquals(-1, r3.getCode());
        assertEquals("服务器ip不能为空", r3.getMsg());
    }

    @Test
    @DisplayName("updateNode & getInstallCommand：空参数及空 ID 安全拦截")
    void testUpdateNodeAndInstallCommandGuards() {
        R r1 = nodeServiceImpl.updateNode(null);
        assertEquals(-1, r1.getCode());
        assertEquals("节点ID不能为空", r1.getMsg());

        NodeUpdateDto updateDto = new NodeUpdateDto();
        updateDto.setId(null);
        R r2 = nodeServiceImpl.updateNode(updateDto);
        assertEquals(-1, r2.getCode());
        assertEquals("节点ID不能为空", r2.getMsg());

        R r3 = nodeServiceImpl.getInstallCommand(null);
        assertEquals(-1, r3.getCode());
        assertEquals("节点ID不能为空", r3.getMsg());
    }

    // ==================== 4. UserService login & createUser & updateUser ====================

    @Test
    @DisplayName("login：空 DTO 或空白用户名/密码安全拦截")
    void testUserLoginGuards() {
        R r1 = userServiceImpl.login(null);
        assertEquals(-1, r1.getCode());
        assertEquals("账号或密码错误", r1.getMsg());

        LoginDto loginDto = new LoginDto();
        loginDto.setUsername("  ");
        loginDto.setPassword("pass");
        R r2 = userServiceImpl.login(loginDto);
        assertEquals(-1, r2.getCode());
        assertEquals("账号或密码错误", r2.getMsg());
    }

    @Test
    @DisplayName("createUser：空 DTO 或空白用户名/密码安全拦截")
    void testUserCreateGuards() {
        R r1 = userServiceImpl.createUser(null);
        assertEquals(-1, r1.getCode());
        assertEquals("用户参数不能为空", r1.getMsg());

        UserDto dto = new UserDto();
        dto.setUser("  ");
        dto.setPwd("123456");
        R r2 = userServiceImpl.createUser(dto);
        assertEquals(-1, r2.getCode());
        assertEquals("用户名不能为空", r2.getMsg());

        UserDto dto2 = new UserDto();
        dto2.setUser("validuser");
        dto2.setPwd("  ");
        R r3 = userServiceImpl.createUser(dto2);
        assertEquals(-1, r3.getCode());
        assertEquals("密码不能为空", r3.getMsg());
    }

    @Test
    @DisplayName("updateUser：空 DTO、空 ID、库中 roleId 为 null 时安全防拆箱")
    void testUserUpdateGuards() {
        R r1 = userServiceImpl.updateUser(null);
        assertEquals(-1, r1.getCode());
        assertEquals("用户ID不能为空", r1.getMsg());

        UserUpdateDto updateDto = new UserUpdateDto();
        updateDto.setId(null);
        R r2 = userServiceImpl.updateUser(updateDto);
        assertEquals(-1, r2.getCode());
        assertEquals("用户ID不能为空", r2.getMsg());

        // 测试 user.roleId 为 null 时不抛 NPE
        updateDto.setId(USER_ID);
        updateDto.setUser("updated_user");
        User dbUser = new User();
        dbUser.setId(USER_ID);
        dbUser.setRoleId(null); // null roleId
        when(userMapper.selectById(USER_ID)).thenReturn(dbUser);
        when(userMapper.selectCount(any())).thenReturn(0);
        when(userMapper.updateById(any())).thenReturn(1);

        R r3 = userServiceImpl.updateUser(updateDto);
        assertEquals(0, r3.getCode());
    }

    // ==================== 5. CustomErrorController REST API 404/错误格式 ====================

    @Test
    @DisplayName("CustomErrorController：对 /api/ 路径或 Accept: application/json 返回 JSON 而非 HTML")
    void testCustomErrorControllerApiResponse() throws Exception {
        CustomErrorController controller = new CustomErrorController();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/unknown");
        request.setRequestURI("/api/v1/unknown");

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.handleError(request, response);

        assertEquals(404, response.getStatus());
        assertEquals("application/json; charset=UTF-8", response.getContentType());
        assertTrue(response.getContentAsString().contains("API endpoint error or not found (404)"));
    }

    // ==================== 6. JwtInterceptor OPTIONS preflight ====================

    @Test
    @DisplayName("JwtInterceptor：对 OPTIONS 请求直接放行，不阻断跨域预检")
    void testJwtInterceptorOptionsRequest() {
        JwtInterceptor interceptor = new JwtInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("OPTIONS");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());
        assertTrue(result, "OPTIONS 预检请求必须无条件直接放行");
    }
}
