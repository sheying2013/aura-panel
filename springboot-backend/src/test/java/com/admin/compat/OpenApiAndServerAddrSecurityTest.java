package com.admin.compat;

import com.admin.common.dto.ChangePasswordDto;
import com.admin.common.dto.UserPackageDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AuthAttemptLimiter;
import com.admin.common.utils.Md5Util;
import com.admin.controller.OpenApiController;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.entity.ViteConfig;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.ViteConfigMapper;
import com.admin.service.ForwardPortService;
import com.admin.service.StatisticsFlowService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.ViteConfigService;
import com.admin.service.impl.NodeServiceImpl;
import com.admin.service.impl.UserServiceImpl;
import com.admin.service.impl.ViteConfigServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * round59: OpenApi订阅安全、安装命令与配置注入防御、管理员全量隧道与时钟安全测试。
 */
class OpenApiAndServerAddrSecurityTest {

    private static final String PASSWORD = "secret-password";

    @Test
    @DisplayName("OpenApi: 停用账号(status=0)请求订阅直接被拦截并报账号已停用")
    void disabledUserIsRejectedInSubStore() {
        UserService userService = mock(UserService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        OpenApiController controller = new OpenApiController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(controller, "limiter", new AuthAttemptLimiter());

        User user = new User();
        user.setId(10L);
        user.setUser("bob");
        user.setPwd(Md5Util.md5(PASSWORD));
        user.setStatus(0); // 停用
        when(userService.getOne(any())).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Object res = controller.create("bob", PASSWORD, null, "-1", request, response);
        assertTrue(res instanceof R);
        R r = (R) res;
        assertEquals(-1, r.getCode());
        assertEquals("账号已停用", r.getMsg());
        assertNull(response.getHeader("subscription-userinfo"));
    }

    @Test
    @DisplayName("OpenApi: 停用用户隧道(status=0)直接被拦截并报用户隧道已停用")
    void disabledUserTunnelIsRejectedInSubStore() {
        UserService userService = mock(UserService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        OpenApiController controller = new OpenApiController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(controller, "limiter", new AuthAttemptLimiter());

        User user = new User();
        user.setId(10L);
        user.setUser("bob");
        user.setPwd(Md5Util.md5(PASSWORD));
        user.setStatus(1);
        when(userService.getOne(any())).thenReturn(user);

        UserTunnel ut = new UserTunnel();
        ut.setId(12);
        ut.setUserId(10);
        ut.setStatus(0); // 用户隧道停用
        when(userTunnelService.getById("12")).thenReturn(ut);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Object res = controller.create("bob", PASSWORD, null, "12", request, response);
        assertTrue(res instanceof R);
        R r = (R) res;
        assertEquals(-1, r.getCode());
        assertEquals("用户隧道已停用", r.getMsg());
    }

    @Test
    @DisplayName("OpenApi: 非法非数字隧道ID被拦截返回隧道不存在")
    void malformedTunnelIdIsRejectedInSubStore() {
        UserService userService = mock(UserService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        OpenApiController controller = new OpenApiController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(controller, "limiter", new AuthAttemptLimiter());

        User user = new User();
        user.setId(10L);
        user.setUser("bob");
        user.setPwd(Md5Util.md5(PASSWORD));
        user.setStatus(1);
        when(userService.getOne(any())).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Object res = controller.create("bob", PASSWORD, null, "12;rm -rf /", request, response);
        assertTrue(res instanceof R);
        R r = (R) res;
        assertEquals(-1, r.getCode());
        assertEquals("隧道不存在", r.getMsg());
        verify(userTunnelService, never()).getById(any(String.class));
    }

    @Test
    @DisplayName("NodeService: 安装命令遇恶意shell字符服务器地址直接拒绝并阻断命令生成")
    void maliciousServerAddressIsRejectedInInstallCommand() {
        NodeMapper nodeMapper = mock(NodeMapper.class);
        ViteConfigService viteConfigService = mock(ViteConfigService.class);

        Node node = new Node();
        node.setId(5L);
        node.setSecret("validSecret123");
        when(nodeMapper.selectById(5L)).thenReturn(node);

        String[] badAddrs = {
                "1.2.3.4; rm -rf /",
                "1.2.3.4 && cat /etc/passwd",
                "1.2.3.4 | bash",
                "1.2.3.4 $(id)",
                "1.2.3.4 `id`",
                "1.2.3.4'quoted",
                "1.2.3.4\"quoted",
                "1.2.3.4 > /tmp/out",
                "1.2.3.4 < /dev/zero"
        };

        NodeServiceImpl nodeService = new NodeServiceImpl();
        ReflectionTestUtils.setField(nodeService, "baseMapper", nodeMapper);
        ReflectionTestUtils.setField(nodeService, "viteConfigService", viteConfigService);

        for (String bad : badAddrs) {
            ViteConfig cfg = new ViteConfig();
            cfg.setName("ip");
            cfg.setValue(bad);
            when(viteConfigService.getOne(any())).thenReturn(cfg);

            R r = nodeService.getInstallCommand(5L);
            assertEquals(-1, r.getCode(), "应拦截恶意地址: " + bad);
            assertTrue(r.getMsg().contains("服务器地址不合法"), "文案提示错误: " + r.getMsg());
            assertNull(r.getData());
        }
    }

    @Test
    @DisplayName("NodeService: 节点密钥含非法字符被拒绝")
    void maliciousNodeSecretIsRejectedInInstallCommand() {
        NodeMapper nodeMapper = mock(NodeMapper.class);
        ViteConfigService viteConfigService = mock(ViteConfigService.class);

        Node node = new Node();
        node.setId(5L);
        node.setSecret("bad;secret|hack");
        when(nodeMapper.selectById(5L)).thenReturn(node);

        ViteConfig cfg = new ViteConfig();
        cfg.setName("ip");
        cfg.setValue("192.168.1.100:6365");
        when(viteConfigService.getOne(any())).thenReturn(cfg);

        NodeServiceImpl nodeService = new NodeServiceImpl();
        ReflectionTestUtils.setField(nodeService, "baseMapper", nodeMapper);
        ReflectionTestUtils.setField(nodeService, "viteConfigService", viteConfigService);

        R r = nodeService.getInstallCommand(5L);
        assertEquals(-1, r.getCode());
        assertEquals("节点密钥格式不合法", r.getMsg());
    }

    @Test
    @DisplayName("ViteConfigService: 更新服务器ip时拦截含有shell特殊字符的配置值")
    void updateConfigRejectsShellMetacharactersInIp() {
        ViteConfigMapper mapper = mock(ViteConfigMapper.class);
        ViteConfigServiceImpl configService = new ViteConfigServiceImpl();
        ReflectionTestUtils.setField(configService, "baseMapper", mapper);

        // 单项更新
        R r1 = configService.updateConfig("ip", "1.1.1.1; echo pwned");
        assertEquals(-1, r1.getCode());
        assertTrue(r1.getMsg().contains("服务器地址配置不合法"));

        // 批量更新
        Map<String, String> map = new HashMap<>();
        map.put("ip", "1.1.1.1 && evil.sh");
        map.put("app_name", "SafePanel");
        R r2 = configService.updateConfigs(map);
        assertEquals(-1, r2.getCode());
        assertTrue(r2.getMsg().contains("服务器地址配置不合法"));
    }

    @Test
    @DisplayName("UserService: 管理员查询套餐信息自动调用getAllTunnelsForAdmin获取全部隧道权限")
    void adminUserGetsAllTunnelsInPackageInfo() {
        UserMapper userMapper = mock(UserMapper.class);
        StatisticsFlowService statisticsFlowService = mock(StatisticsFlowService.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);
        TunnelService tunnelService = mock(TunnelService.class);

        UserServiceImpl userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "statisticsFlowService", statisticsFlowService);
        ReflectionTestUtils.setField(userService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(userService, "tunnelService", tunnelService);

        User admin = new User();
        admin.setId(1L);
        admin.setUser("admin");
        admin.setRoleId(0); // 管理员
        when(userMapper.selectById(1)).thenReturn(admin);

        UserPackageDto.UserTunnelDetailDto adminTunnel = new UserPackageDto.UserTunnelDetailDto();
        adminTunnel.setTunnelId(101);
        adminTunnel.setTunnelName("AdminTunnel");
        when(userMapper.getAllTunnelsForAdmin()).thenReturn(List.of(adminTunnel));
        when(userMapper.getUserForwardDetails(1)).thenReturn(Collections.emptyList());
        when(statisticsFlowService.list(any())).thenReturn(Collections.emptyList());

        // 模拟当前请求为管理员(userId=1)身份
        String json = "{\"sub\":1,\"role_id\":0,\"name\":\"admin\"}";
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            R r = userService.getUserPackageInfo();
            assertEquals(0, r.getCode());
            UserPackageDto dto = (UserPackageDto) r.getData();
            assertNotNull(dto);
            assertEquals(1, dto.getTunnelPermissions().size());
            assertEquals("AdminTunnel", dto.getTunnelPermissions().getFirst().getTunnelName());

            verify(userMapper, times(1)).getAllTunnelsForAdmin();
            verify(userMapper, never()).getUserTunnelDetails(anyInt());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("UserService: parseHour面对脏数据或非数字格式安全回退杜绝500")
    void parseHourSafeFallbackOnCorruptedFormat() {
        UserServiceImpl service = new UserServiceImpl();
        int nowHour = java.time.LocalDateTime.now().getHour();

        int h1 = ReflectionTestUtils.invokeMethod(service, "parseHour", "15:00");
        assertEquals(15, h1);

        int h2 = ReflectionTestUtils.invokeMethod(service, "parseHour", "abc:00");
        assertEquals(nowHour, h2);

        int h3 = ReflectionTestUtils.invokeMethod(service, "parseHour", ":00");
        assertEquals(nowHour, h3);

        int h4 = ReflectionTestUtils.invokeMethod(service, "parseHour", "25:00");
        assertEquals(nowHour, h4);

        int h5 = ReflectionTestUtils.invokeMethod(service, "parseHour", "-3:00");
        assertEquals(nowHour, h5);

        int h6 = ReflectionTestUtils.invokeMethod(service, "parseHour", (String) null);
        assertEquals(nowHour, h6);
    }
}
