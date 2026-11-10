package com.admin.compat;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import com.admin.common.dto.LoginDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AuthAttemptLimiter;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.Md5Util;
import com.admin.entity.User;
import com.admin.entity.ViteConfig;
import com.admin.mapper.UserMapper;
import com.admin.service.ViteConfigService;
import com.admin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户登录防撞库与安全校验的单元测试套件。
 */
class UserLoginSecurityTest {

    private static final String TEST_SECRET = "test-secret-key-for-auth-attempt-test-1234567890";

    private UserServiceImpl userService;
    private UserMapper userMapper;
    private ViteConfigService viteConfigService;
    private ImageCaptchaApplication application;

    private User normalUser;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        userMapper = mock(UserMapper.class);
        viteConfigService = mock(ViteConfigService.class);
        application = mock(ImageCaptchaApplication.class);

        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "viteConfigService", viteConfigService);
        ReflectionTestUtils.setField(userService, "application", application);
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", TEST_SECRET);

        normalUser = new User();
        normalUser.setId(1L);
        normalUser.setUser("test_user");
        normalUser.setPwd(Md5Util.md5("correct_password"));
        normalUser.setStatus(1);
        normalUser.setRoleId(1);

        when(viteConfigService.getOne(any())).thenReturn(null);
    }

    @Test
    @DisplayName("错误密码连续触发10次后被锁定，第11次被限流拒绝")
    void wrongPasswordLocksAccountAfterMaxFailures() {
        when(userMapper.selectOne(any())).thenReturn(normalUser);

        LoginDto badDto = new LoginDto();
        badDto.setUsername("test_user");
        badDto.setPassword("wrong_password");

        for (int i = 0; i < 10; i++) {
            R r = userService.login(badDto);
            assertEquals(-1, r.getCode(), "前10次应返回业务错误");
            assertEquals("账号或密码错误", r.getMsg());
        }

        R locked = userService.login(badDto);
        assertEquals(-1, locked.getCode());
        assertEquals("尝试过于频繁，请稍后再试", locked.getMsg());
    }

    @Test
    @DisplayName("不存在账号连续10次探测后被锁定，第11次被限流拒绝")
    void nonExistentUserLocksAfterMaxFailures() {
        when(userMapper.selectOne(any())).thenReturn(null);

        LoginDto dto = new LoginDto();
        dto.setUsername("ghost_user");
        dto.setPassword("any_password");

        for (int i = 0; i < 10; i++) {
            R r = userService.login(dto);
            assertEquals("账号或密码错误", r.getMsg());
        }

        R locked = userService.login(dto);
        assertEquals("尝试过于频繁，请稍后再试", locked.getMsg());
    }

    @Test
    @DisplayName("登录成功后清空失败计数，允许后续正常重试")
    void successfulLoginResetsFailureCounter() {
        when(userMapper.selectOne(any())).thenReturn(normalUser);

        LoginDto badDto = new LoginDto();
        badDto.setUsername("test_user");
        badDto.setPassword("wrong_password");

        for (int i = 0; i < 5; i++) {
            R r = userService.login(badDto);
            assertEquals("账号或密码错误", r.getMsg());
        }

        LoginDto goodDto = new LoginDto();
        goodDto.setUsername("test_user");
        goodDto.setPassword("correct_password");
        R success = userService.login(goodDto);
        assertEquals(0, success.getCode());
        assertNotNull(success.getData());

        for (int i = 0; i < 9; i++) {
            R r = userService.login(badDto);
            assertEquals("账号或密码错误", r.getMsg(), "计数已被重置，不应提前锁定");
        }
    }

    @Test
    @DisplayName("停用账号密码正确时返回账号被停用")
    void disabledUserReturnsDisabledMessage() {
        normalUser.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(normalUser);

        LoginDto goodDto = new LoginDto();
        goodDto.setUsername("test_user");
        goodDto.setPassword("correct_password");

        R r = userService.login(goodDto);
        assertEquals(-1, r.getCode());
        assertEquals("账号被停用", r.getMsg());
    }

    @Test
    @DisplayName("开启验证码时若验证码为空则直接拦截并计入失败")
    void captchaEnabledRequiresCaptcha() {
        ViteConfig config = new ViteConfig();
        config.setName("captcha_enabled");
        config.setValue("true");
        when(viteConfigService.getOne(any())).thenReturn(config);

        LoginDto dto = new LoginDto();
        dto.setUsername("test_user");
        dto.setPassword("correct_password");
        dto.setCaptchaId(null);

        R r = userService.login(dto);
        assertEquals(-1, r.getCode());
        assertEquals("验证码校验失败", r.getMsg());
    }

    @Test
    @DisplayName("滑动窗口超时后自动解封")
    void timeWindowSlideRestoresAccess() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(normalUser);

        AtomicLong clock = new AtomicLong(1000000L);
        Constructor<AuthAttemptLimiter> ctor = AuthAttemptLimiter.class.getDeclaredConstructor(LongSupplier.class);
        ctor.setAccessible(true);
        AuthAttemptLimiter customLimiter = ctor.newInstance((LongSupplier) clock::get);

        ReflectionTestUtils.setField(userService, "loginLimiter", customLimiter);

        LoginDto badDto = new LoginDto();
        badDto.setUsername("test_user");
        badDto.setPassword("wrong_password");

        for (int i = 0; i < 10; i++) {
            assertEquals("账号或密码错误", userService.login(badDto).getMsg());
        }

        assertEquals("尝试过于频繁，请稍后再试", userService.login(badDto).getMsg());

        clock.addAndGet(300001L);

        assertEquals("账号或密码错误", userService.login(badDto).getMsg(), "窗口滑动后应恢复放行");
    }
}
