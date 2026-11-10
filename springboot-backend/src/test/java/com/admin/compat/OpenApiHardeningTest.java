package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.common.utils.AuthAttemptLimiter;
import com.admin.common.utils.Md5Util;
import com.admin.controller.OpenApiController;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenApiController（/api/v1/open_api/sub_store）加固回归。
 *
 * <p>覆盖旧实现的四处缺陷（{@code OpenApiController.java:47-70} 一带）：
 * MD5 用 {@code String.equals} 非常量时间比较、{@code expTime/1000} 与 {@code flow*GIGA}
 * 对 null/负数/溢出没有保护、失败无次数限制可无限撞库；同时验证 GET+POST 双方法与
 * subscription-userinfo 头格式未变。</p>
 */
class OpenApiHardeningTest {

    private static final long GIGA = 1024L * 1024L * 1024L;
    private static final String PASSWORD = "correct-pwd";

    private UserService userService;
    private UserTunnelService userTunnelService;
    private OpenApiController controller;
    private User user;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        userTunnelService = mock(UserTunnelService.class);
        controller = new OpenApiController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(controller, "limiter", new AuthAttemptLimiter());

        user = new User();
        user.setId(7L);
        user.setUser("alice");
        user.setPwd(Md5Util.md5(PASSWORD));
        user.setFlow(10L);
        user.setInFlow(1L);
        user.setOutFlow(2L);
        user.setExpTime(1700000000000L);
        when(userService.getOne(any())).thenReturn(user);

        request = new MockHttpServletRequest();
        // 固定 x-forwarded-for，模拟可信反代后的真实客户端
        request.addHeader("x-forwarded-for", "10.1.2.3");
        response = new MockHttpServletResponse();
    }

    private Object call(String pwd) {
        return controller.create("alice", pwd, null, "-1", request, response);
    }

    private Object call(String tunnel, String pwd) {
        return controller.create("alice", pwd, null, tunnel, request, response);
    }

    /** 显式指定 X-Auth-Pwd 头值（null = 未发送该头）。 */
    private Object callWithHeader(String pwdHeader, String pwd) {
        return controller.create("alice", pwd, pwdHeader, "-1", request, response);
    }

    private static String okHeader() {
        return "upload=1; download=2; total=" + (10 * GIGA) + "; expire=1700000000";
    }

    private static String messageOf(Object result) {
        assertTrue(result instanceof R, "失败分支应返回 R，实际: " + result);
        String msg = ((R) result).getMsg();
        assertNotNull(msg);
        return msg;
    }

    @Test
    @DisplayName("正确密码返回 subscription-userinfo 头与同内容字符串")
    void correctPasswordReturnsHeader() {
        Object result = call(PASSWORD);

        String expected = "upload=1; download=2; total=" + (10 * GIGA) + "; expire=1700000000";
        assertEquals(expected, result);
        assertEquals(expected, response.getHeader("subscription-userinfo"));
    }

    @Test
    @DisplayName("错误密码返回鉴权失败且不写头")
    void wrongPasswordIsRejected() {
        Object result = call("wrong-pwd");

        assertEquals("鉴权失败", messageOf(result));
        assertNull(response.getHeader("subscription-userinfo"));
    }

    @Test
    @DisplayName("账号不存在同样按鉴权失败处理，且计入失败次数")
    void unknownUserIsRejected() {
        when(userService.getOne(any())).thenReturn(null);

        assertEquals("鉴权失败", messageOf(call(PASSWORD)));
    }

    @Test
    @DisplayName("连续 10 次失败后第 11 次被限流")
    void eleventhAttemptIsRateLimited() {
        for (int i = 0; i < 10; i++) {
            assertEquals("鉴权失败", messageOf(call("wrong-pwd")), "第 " + (i + 1) + " 次仍应走鉴权失败");
        }

        assertEquals("尝试过于频繁，请稍后再试", messageOf(call(PASSWORD)));
        assertNull(response.getHeader("subscription-userinfo"));
    }

    @Test
    @DisplayName("成功一次后失败计数被重置")
    void successResetsCounter() {
        for (int i = 0; i < 5; i++) {
            call("wrong-pwd");
        }
        assertEquals("upload=1; download=2; total=" + (10 * GIGA) + "; expire=1700000000", call(PASSWORD));

        for (int i = 0; i < 10; i++) {
            assertEquals("鉴权失败", messageOf(call("wrong-pwd")), "重置后第 " + (i + 1) + " 次不应被限流");
        }
        assertEquals("尝试过于频繁，请稍后再试", messageOf(call("wrong-pwd")));
    }

    @Test
    @DisplayName("expTime 为 null 或负数时 expire=0 且不抛异常")
    void expTimeNullAndNegativeBecomeZero() {
        user.setExpTime(null);
        assertEquals("upload=1; download=2; total=" + (10 * GIGA) + "; expire=0", call(PASSWORD));

        ReflectionTestUtils.setField(controller, "limiter", new AuthAttemptLimiter());
        user.setExpTime(-1000L);
        assertEquals("upload=1; download=2; total=" + (10 * GIGA) + "; expire=0", call(PASSWORD));
    }

    @Test
    @DisplayName("flow 为 null 或负数时 total=0")
    void flowNullAndNegativeBecomeZero() {
        user.setFlow(null);
        assertEquals("upload=1; download=2; total=0; expire=1700000000", call(PASSWORD));

        ReflectionTestUtils.setField(controller, "limiter", new AuthAttemptLimiter());
        user.setFlow(-5L);
        assertEquals("upload=1; download=2; total=0; expire=1700000000", call(PASSWORD));
    }

    @Test
    @DisplayName("超大 flow 饱和到 Long.MAX_VALUE，不回绕成负数")
    void hugeFlowSaturates() {
        user.setFlow(Long.MAX_VALUE);

        String result = String.valueOf(call(PASSWORD));

        assertTrue(result.endsWith("; total=" + Long.MAX_VALUE + "; expire=1700000000"), result);
        assertFalse(result.contains("total=-"), result);
        assertNotEquals("total=0", result);
    }

    @Test
    @DisplayName("失败响应体不包含密码明文或哈希")
    void failureBodyLeaksNothing() {
        String secretPwd = "leak-me-please";

        String msg = messageOf(call(secretPwd));

        assertFalse(msg.contains(secretPwd), msg);
        assertFalse(msg.contains(Md5Util.md5(secretPwd)), msg);
        assertEquals("鉴权失败", msg);
    }

    @Test
    @DisplayName("仅 X-Auth-Pwd 头（不带 pwd query）即可登录")
    void headerOnlyLoginWorks() {
        Object result = callWithHeader(PASSWORD, null);

        assertEquals(okHeader(), result);
        assertEquals(okHeader(), response.getHeader("subscription-userinfo"));
    }

    @Test
    @DisplayName("X-Auth-Pwd 头优先于 pwd query：头正确 query 错误仍登录成功")
    void headerTakesPrecedenceOverQuery() {
        assertEquals(okHeader(), callWithHeader(PASSWORD, "wrong-pwd"));

        // 反向证明：头错误时不会回落到（正确的）query 密码
        ReflectionTestUtils.setField(controller, "limiter", new AuthAttemptLimiter());
        assertEquals("鉴权失败", messageOf(callWithHeader("wrong-pwd", PASSWORD)));
    }

    @Test
    @DisplayName("头与 query 皆空时报密码不能为空")
    void bothEmptyReturnsPasswordRequired() {
        assertEquals("密码不能为空", messageOf(callWithHeader(null, null)));
        assertEquals("密码不能为空", messageOf(call(null)));
        assertNull(response.getHeader("subscription-userinfo"));
    }

    @Test
    @DisplayName("空白头视为未提供，回落 pwd query 仍可登录")
    void blankHeaderFallsBackToQuery() {
        assertEquals(okHeader(), callWithHeader("   ", PASSWORD));
        assertEquals("密码不能为空", messageOf(callWithHeader("   ", "")));
    }

    @Test
    @DisplayName("隧道分支：归属校验通过后使用隧道流量与到期时间")
    void tunnelBranchUsesTunnelQuota() {
        UserTunnel tunnel = new UserTunnel();
        tunnel.setId(5);
        tunnel.setUserId(7);
        tunnel.setFlow(3L);
        tunnel.setInFlow(4L);
        tunnel.setOutFlow(5L);
        tunnel.setExpTime(1800000000000L);
        when(userTunnelService.getById("5")).thenReturn(tunnel);

        assertEquals("upload=4; download=5; total=" + (3 * GIGA) + "; expire=1800000000", call("5", PASSWORD));
    }
}
