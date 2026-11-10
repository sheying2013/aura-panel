package com.admin.compat;

import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import com.admin.common.dto.CaptchaVerifyDto;
import com.admin.common.dto.ResetFlowDto;
import com.admin.common.dto.UserTunnelQueryDto;
import com.admin.common.dto.UserTunnelUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.PauseReason;
import com.admin.controller.CaptchaController;
import com.admin.controller.FlowController;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.entity.ViteConfig;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.ViteConfigService;
import com.admin.service.impl.ForwardServiceImpl;
import com.admin.service.impl.UserServiceImpl;
import com.admin.service.impl.UserTunnelServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * round61: 流量限制口径统一、不限量账户误暂停防御、验证码与重置接口空指针/类型强转防御测试。
 */
class FlowLimitAndUnboxingSafetyTest {

    private static final long GB = 1024L * 1024L * 1024L;

    @Test
    @DisplayName("FlowController: 不限量用户 (flow=0 或 null) 即使产生流量也不会被误暂停")
    void flowControllerUnlimitedUserNotPaused() throws Exception {
        FlowController flowController = new FlowController();
        UserService userService = mock(UserService.class);
        ForwardService forwardService = mock(ForwardService.class);
        ReflectionTestUtils.setField(flowController, "userService", userService);
        ReflectionTestUtils.setField(flowController, "forwardService", forwardService);

        // 1. flow=0 (不限量)，inFlow+outFlow = 5GB
        User unlimitedUser = new User();
        unlimitedUser.setId(101L);
        unlimitedUser.setFlow(0L);
        unlimitedUser.setInFlow(2 * GB);
        unlimitedUser.setOutFlow(3 * GB);
        unlimitedUser.setStatus(1);
        when(userService.getById("101")).thenReturn(unlimitedUser);

        Method checkUserLimits = FlowController.class.getDeclaredMethod("checkUserRelatedLimits", String.class);
        checkUserLimits.setAccessible(true);
        checkUserLimits.invoke(flowController, "101");

        // 验证没有调用 forwardService 的查询/暂停
        verify(forwardService, never()).list(any(QueryWrapper.class));

        // 2. flow=null (不限量)
        unlimitedUser.setFlow(null);
        checkUserLimits.invoke(flowController, "101");
        verify(forwardService, never()).list(any(QueryWrapper.class));

        // 3. flow=1 (有限量 1GB)，产生了 2GB -> 应该触发配额暂停
        User limitedUser = new User();
        limitedUser.setId(102L);
        limitedUser.setFlow(1L);
        limitedUser.setInFlow(GB + 100);
        limitedUser.setOutFlow(GB);
        limitedUser.setStatus(1);
        when(userService.getById("102")).thenReturn(limitedUser);
        when(forwardService.list(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        checkUserLimits.invoke(flowController, "102");
        verify(forwardService, times(1)).list(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("FlowController: 不限量用户隧道 (flow=0 或 null) 即使产生流量也不会被误暂停")
    void flowControllerUnlimitedUserTunnelNotPaused() {
        FlowController flowController = new FlowController();
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        ForwardService forwardService = mock(ForwardService.class);
        ReflectionTestUtils.setField(flowController, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(flowController, "forwardService", forwardService);

        // 1. flow=0 (不限量)，有流量
        UserTunnel unlimitedTunnel = new UserTunnel();
        unlimitedTunnel.setId(201);
        unlimitedTunnel.setTunnelId(11);
        unlimitedTunnel.setFlow(0L);
        unlimitedTunnel.setInFlow(5 * GB);
        unlimitedTunnel.setOutFlow(5 * GB);
        unlimitedTunnel.setStatus(1);
        when(userTunnelService.getById("201")).thenReturn(unlimitedTunnel);

        flowController.checkUserTunnelRelatedLimits("201", "101");
        verify(forwardService, never()).list(any(QueryWrapper.class));

        // 2. flow=1 (限 1GB)，有 1GB 流量 -> 应该触发配额暂停
        UserTunnel limitedTunnel = new UserTunnel();
        limitedTunnel.setId(202);
        limitedTunnel.setTunnelId(12);
        limitedTunnel.setFlow(1L);
        limitedTunnel.setInFlow(GB);
        limitedTunnel.setOutFlow(100L);
        limitedTunnel.setStatus(1);
        when(userTunnelService.getById("202")).thenReturn(limitedTunnel);
        when(forwardService.list(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        flowController.checkUserTunnelRelatedLimits("202", "101");
        verify(forwardService, times(1)).list(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("ForwardServiceImpl: checkUserFlowLimits 对不限量与 null 安全检查")
    void forwardServiceCheckUserFlowLimits() throws Exception {
        ForwardServiceImpl forwardService = new ForwardServiceImpl();
        UserService userService = mock(UserService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);

        Method checkLimits = ForwardServiceImpl.class.getDeclaredMethod("checkUserFlowLimits", Integer.class, Tunnel.class);
        checkLimits.setAccessible(true);

        Tunnel tunnel = new Tunnel();
        tunnel.setId(10L);

        // 1. 用户不存在
        when(userService.getById(999)).thenReturn(null);
        R r1 = (R) checkLimits.invoke(forwardService, 999, tunnel);
        assertEquals(-1, r1.getCode());
        assertEquals("用户不存在", r1.getMsg());

        // 2. 用户不限量 (flow=0)，用户隧道不限量 (flow=0)，流量超出
        User user = new User();
        user.setId(1001L);
        user.setFlow(0L);
        user.setInFlow(10 * GB);
        user.setOutFlow(10 * GB);
        when(userService.getById(1001)).thenReturn(user);

        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(2001);
        userTunnel.setUserId(1001);
        userTunnel.setTunnelId(10);
        userTunnel.setFlow(0L);
        userTunnel.setInFlow(10 * GB);
        userTunnel.setOutFlow(10 * GB);
        when(userTunnelService.getOne(any(QueryWrapper.class))).thenReturn(userTunnel);

        R r2 = (R) checkLimits.invoke(forwardService, 1001, tunnel);
        assertEquals(0, r2.getCode()); // 成功通过！不限量不被拦截
    }

    @Test
    @DisplayName("ForwardServiceImpl: updateForwardOrder 畸形参数格式返回可读错误，不抛 ClassCastException")
    void updateForwardOrderParamFormatValidation() {
        ForwardServiceImpl forwardService = new ForwardServiceImpl();

        // 1. forwards 不是 List (例如传了 String)
        Map<String, Object> invalidParams1 = new HashMap<>();
        invalidParams1.put("forwards", "not_a_list");
        R r1 = forwardService.updateForwardOrder(invalidParams1);
        assertEquals(-1, r1.getCode());
        assertEquals("forwards参数格式不正确，必须为数组", r1.getMsg());

        // 2. forwards 内部元素不是 Map (例如传了整型数组)
        Map<String, Object> invalidParams2 = new HashMap<>();
        invalidParams2.put("forwards", Arrays.asList(123, 456));
        R r2 = forwardService.updateForwardOrder(invalidParams2);
        assertEquals(-1, r2.getCode());
        assertTrue(r2.getMsg().contains("第 1 条排序数据格式不正确，必须为对象"));

        // 3. forwards 为空列表
        Map<String, Object> invalidParams3 = new HashMap<>();
        invalidParams3.put("forwards", Collections.emptyList());
        R r3 = forwardService.updateForwardOrder(invalidParams3);
        assertEquals(-1, r3.getCode());
        assertEquals("forwards参数不能为空", r3.getMsg());
    }

    @Test
    @DisplayName("CaptchaController: verify 与 genCaptcha 空参数安全校验")
    void captchaControllerSafety() {
        CaptchaController captchaController = new CaptchaController();
        SecondaryVerificationApplication application = mock(SecondaryVerificationApplication.class);
        ViteConfigService viteConfigService = mock(ViteConfigService.class);
        ReflectionTestUtils.setField(captchaController, "application", application);
        ReflectionTestUtils.setField(captchaController, "viteConfigService", viteConfigService);

        // 1. verify(null)
        ApiResponse<?> res1 = captchaController.verify(null);
        assertFalse(res1.isSuccess());
        assertEquals("验证码参数不完整", res1.getMsg());

        // 2. verify 缺少 id 或 data
        CaptchaVerifyDto dto = new CaptchaVerifyDto();
        dto.setId("id_only");
        dto.setData(null);
        ApiResponse<?> res2 = captchaController.verify(dto);
        assertFalse(res2.isSuccess());
        assertEquals("验证码参数不完整", res2.getMsg());

        // 3. genCaptcha 在 viteConfig 为空或 RANDOM 时正常回退
        when(viteConfigService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(application.generateCaptcha(anyString())).thenReturn(null);
        assertDoesNotThrow(() -> captchaController.genCaptcha());
    }

    @Test
    @DisplayName("UserServiceImpl: reset 参数缺失与类型校验返回明确错误")
    void userServiceResetValidation() {
        UserServiceImpl userService = new UserServiceImpl();
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        ForwardService forwardService = mock(ForwardService.class);
        ReflectionTestUtils.setField(userService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(userService, "forwardService", forwardService);

        // 1. null 参数
        R r1 = userService.reset(null);
        assertEquals(-1, r1.getCode());
        assertEquals("参数不能为空", r1.getMsg());

        // 2. 缺 id
        ResetFlowDto dto = new ResetFlowDto();
        dto.setType(1);
        R r2 = userService.reset(dto);
        assertEquals(-1, r2.getCode());
        assertEquals("重置账号id不能为空", r2.getMsg());

        // 3. 缺 type
        dto.setId(10);
        dto.setType(null);
        R r3 = userService.reset(dto);
        assertEquals(-1, r3.getCode());
        assertEquals("重置类型不能为空", r3.getMsg());

        // 4. 非法 type
        dto.setType(3);
        R r4 = userService.reset(dto);
        assertEquals(-1, r4.getCode());
        assertEquals("重置类型只能是 1（账号）或 2（隧道）", r4.getMsg());

        // 5. type=2 且隧道不存在
        dto.setType(2);
        dto.setId(999);
        when(userTunnelService.getById(999)).thenReturn(null);
        R r5 = userService.reset(dto);
        assertEquals(-1, r5.getCode());
        assertEquals("未找到对应的用户隧道权限记录", r5.getMsg());
    }

    @Test
    @DisplayName("UserTunnelServiceImpl: getUserTunnelList 与 updateUserTunnel 空指针防护")
    void userTunnelServiceGuard() {
        UserTunnelServiceImpl userTunnelService = spy(new UserTunnelServiceImpl());

        // 1. getUserTunnelList(null)
        R r1 = userTunnelService.getUserTunnelList(null);
        assertEquals(-1, r1.getCode());
        assertEquals("用户ID不能为空", r1.getMsg());

        // 2. queryDto.userId 为 null
        R r2 = userTunnelService.getUserTunnelList(new UserTunnelQueryDto());
        assertEquals(-1, r2.getCode());
        assertEquals("用户ID不能为空", r2.getMsg());

        // 3. updateUserTunnel 权限记录不存在
        doReturn(null).when(userTunnelService).getById(any());
        UserTunnelUpdateDto updateDto = new UserTunnelUpdateDto();
        updateDto.setId(888);
        R r3 = userTunnelService.updateUserTunnel(updateDto);
        assertEquals(-1, r3.getCode());
        assertEquals("未找到对应的用户隧道权限记录", r3.getMsg());
    }
}
