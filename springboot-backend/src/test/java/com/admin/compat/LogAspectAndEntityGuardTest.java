package com.admin.compat;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.aop.LogAspect;
import com.admin.common.dto.ChangePasswordDto;
import com.admin.common.dto.SpeedLimitDto;
import com.admin.common.dto.SpeedLimitUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.admin.entity.SpeedLimit;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.TunnelService;
import com.admin.service.impl.NodeServiceImpl;
import com.admin.service.impl.SpeedLimitServiceImpl;
import com.admin.service.impl.TunnelServiceImpl;
import com.admin.service.impl.UserServiceImpl;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * round60: LogAspect 切面异常兜底与脱敏、JwtUtil 空安全、实体删除与限速操作空指针防御回归测试。
 */
class LogAspectAndEntityGuardTest {

    @Test
    @DisplayName("JwtUtil: 空值、非法格式与畸形 token 解析安全返回 null，不抛异常")
    void jwtUtilNullAndMalformedSafety() {
        assertNull(JwtUtil.getUserIdFromToken(null));
        assertNull(JwtUtil.getUserIdFromToken(""));
        assertNull(JwtUtil.getUserIdFromToken("   "));
        assertNull(JwtUtil.getUserIdFromToken("singlepart"));
        assertNull(JwtUtil.getUserIdFromToken("part1.part2"));
        assertNull(JwtUtil.getUserIdFromToken("part1.invalidbase64%%.part3"));

        RequestContextHolder.resetRequestAttributes();
        assertNull(JwtUtil.getUserIdFromToken());
        assertNull(JwtUtil.getNameFromToken());
    }

    @Test
    @DisplayName("LogAspect: 敏感字段脱敏(apikey, privatekey, nodesecret, token, pwd)正确被打码")
    void logAspectMaskingTest() throws Exception {
        LogAspect aspect = new LogAspect();
        Method maskMethod = LogAspect.class.getDeclaredMethod("mask", Object.class);
        maskMethod.setAccessible(true);

        Map<String, Object> data = new HashMap<>();
        data.put("apiKey", "ak-12345678");
        data.put("privateKey", "pk-87654321");
        data.put("nodeSecret", "secret-key-xyz");
        data.put("refreshToken", "rt-abcdef");
        data.put("password", "p@ssword");
        data.put("normalField", "helloWorld");

        Object masked = maskMethod.invoke(aspect, data);
        assertTrue(masked instanceof Map);
        Map<?, ?> resultMap = (Map<?, ?>) masked;
        assertEquals("***", resultMap.get("apiKey"));
        assertEquals("***", resultMap.get("privateKey"));
        assertEquals("***", resultMap.get("nodeSecret"));
        assertEquals("***", resultMap.get("refreshToken"));
        assertEquals("***", resultMap.get("password"));
        assertEquals("helloWorld", resultMap.get("normalField"));
    }

    @Test
    @DisplayName("LogAspect: 切面执行遇到空头或异常安全兜底，不打断业务执行")
    void logAspectSafeExecutionWithMalformedAuth() throws Throwable {
        LogAspect aspect = new LogAspect();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "malformed.jwt.token");
        request.setRequestURI("/api/v1/test");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        JoinPoint joinPoint = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);

        class DummyTarget {
            @LogAnnotation
            public R testMethod() {
                return R.ok("success");
            }
        }
        Method targetMethod = DummyTarget.class.getMethod("testMethod");
        when(signature.getMethod()).thenReturn(targetMethod);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[0]);

        assertDoesNotThrow(() -> aspect.log(joinPoint, R.ok("success")));

        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("UserService: deleteUser/updatePassword/getUserPackageInfo 空值校验与防御")
    void userServiceNullSafety() {
        UserServiceImpl userService = new UserServiceImpl();
        UserMapper userMapper = mock(UserMapper.class);
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);

        R r1 = userService.deleteUser(null);
        assertEquals(-1, r1.getCode());
        assertEquals("用户ID不能为空", r1.getMsg());

        User admin = new User();
        admin.setId(1L);
        admin.setRoleId(0);
        when(userMapper.selectById(1L)).thenReturn(admin);

        R r2 = userService.deleteUser(1L);
        assertEquals(-1, r2.getCode());
        assertEquals("请不要作死", r2.getMsg());

        R r3 = userService.updatePassword(null);
        assertEquals(-1, r3.getCode());
        assertEquals("密码参数不能为空", r3.getMsg());

        ChangePasswordDto incompleteDto = new ChangePasswordDto();
        R r4 = userService.updatePassword(incompleteDto);
        assertEquals(-1, r4.getCode());
        assertEquals("密码参数不能为空", r4.getMsg());

        RequestContextHolder.resetRequestAttributes();
        R r5 = userService.getUserPackageInfo();
        assertEquals(-1, r5.getCode());
        assertEquals("未登录或token已过期", r5.getMsg());
    }

    @Test
    @DisplayName("NodeService: deleteNode 空 ID 校验防御")
    void nodeServiceNullSafety() {
        NodeServiceImpl nodeService = new NodeServiceImpl();
        NodeMapper nodeMapper = mock(NodeMapper.class);
        ReflectionTestUtils.setField(nodeService, "baseMapper", nodeMapper);

        R r = nodeService.deleteNode(null);
        assertEquals(-1, r.getCode());
        assertEquals("节点ID不能为空", r.getMsg());
    }

    @Test
    @DisplayName("TunnelService: deleteTunnel 空 ID 校验防御")
    void tunnelServiceNullSafety() {
        TunnelServiceImpl tunnelService = new TunnelServiceImpl();
        TunnelMapper tunnelMapper = mock(TunnelMapper.class);
        ReflectionTestUtils.setField(tunnelService, "baseMapper", tunnelMapper);

        R r = tunnelService.deleteTunnel(null);
        assertEquals(-1, r.getCode());
        assertEquals("隧道ID不能为空", r.getMsg());
    }

    @Test
    @DisplayName("SpeedLimitService: createSpeedLimit/updateSpeedLimit 空值防御")
    void speedLimitServiceNullSafety() {
        SpeedLimitServiceImpl speedLimitService = new SpeedLimitServiceImpl();
        SpeedLimitMapper speedLimitMapper = mock(SpeedLimitMapper.class);
        ReflectionTestUtils.setField(speedLimitService, "baseMapper", speedLimitMapper);

        R r1 = speedLimitService.createSpeedLimit(null);
        assertEquals(-1, r1.getCode());
        assertEquals("参数不能为空", r1.getMsg());

        SpeedLimitDto createDto = new SpeedLimitDto();
        R r2 = speedLimitService.createSpeedLimit(createDto);
        assertEquals(-1, r2.getCode());
        assertEquals("隧道ID不能为空", r2.getMsg());

        R r3 = speedLimitService.updateSpeedLimit(null);
        assertEquals(-1, r3.getCode());
        assertEquals("参数不能为空", r3.getMsg());

        SpeedLimitUpdateDto updateDto = new SpeedLimitUpdateDto();
        R r4 = speedLimitService.updateSpeedLimit(updateDto);
        assertEquals(-1, r4.getCode());
        assertEquals("限速ID不能为空", r4.getMsg());
    }
}
