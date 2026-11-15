package com.admin.compat;

import com.admin.common.dto.ChangePasswordDto;
import com.admin.common.dto.UserUpdateDto;
import com.admin.common.exception.UnauthorizedException;
import com.admin.common.exception.GlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.dao.DataAccessResourceFailureException;
import com.admin.common.interceptor.JwtInterceptor;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.Md5Util;
import com.admin.common.utils.TokenRevocationStore;
import com.admin.config.WebMvcConfig;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.admin.service.ForwardService;
import com.admin.service.impl.UserServiceImpl;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 吊销与账号状态校验（round51）。
 *
 * <p>真机基线：token 有效期 90 天，payload 里没有任何版本号，拦截器只验签名与 exp。改密、登出、
 * 停用之后旧 token 仍然一路通行到 90 天——被停用的账号、被挤掉的会话、被换掉密码后留在别人机器上的
 * token 全部继续有效。
 *
 * <p>这里锁住的行为：① 新签发的 token 带 ver，且能原样读回；② round51 之前签发的 token 没有 ver，
 * 必须读成 0（升级瞬间不能把已登录用户全踢下线）；③ ver 非法/坏 token 一律读成 0，不抛异常；
 * ④ 拦截器回库比对账号状态与版本号，停用、删号、版本不符、无 token、坏 token 全部拒绝且文案可辨；
 * ⑤ 改密（用户自己的与管理员代改）把版本号写进与密码同一条 updateById，不出现「密码已换、旧 token
 * 仍可用」的中间态；⑥ round63 起登出只吊销本次登录的 sid（不再自增用户级版本号，跨标签迟到登出不误杀
 * 新登录），老 token 没有 sid 时按其摘要吊销；⑦ WebMvcConfig 不再 new 拦截器，避免两个实例。
 */
class TokenRevocationTest {

    private static final String TEST_SECRET = "test-secret";
    private static final long USER_ID = 5L;

    private final UserMapper userMapper = mock(UserMapper.class);
    private final ForwardService forwardService = mock(ForwardService.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private UserServiceImpl userService;
    private TokenRevocationStore tokenRevocations;
    private JwtInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // SECRET_KEY 是 private static 且非 final，init() 由 @PostConstruct 在容器里赋值；
        // 单测没有容器，只能反射塞进去，否则 calculateSignature 里 SECRET_KEY.getBytes() 直接 NPE。
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", TEST_SECRET);

        // round63：登出改成按 sid 吊销（revoked_token 表），单测用 mock 的 JdbcTemplate 顶替库表
        tokenRevocations = new TokenRevocationStore();
        ReflectionTestUtils.setField(tokenRevocations, "jdbcTemplate", jdbcTemplate);
        when(jdbcTemplate.update(anyString(), any(Object.class), any(Object.class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object.class), any(Object.class)))
                .thenReturn(0);

        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "forwardService", forwardService);
        ReflectionTestUtils.setField(userService, "tokenRevocations", tokenRevocations);
        when(userMapper.selectCount(any())).thenReturn(0);
        when(userMapper.updateById(any())).thenReturn(1);
        when(userMapper.update(any(), any())).thenReturn(1);
        when(forwardService.pauseForDisabledUser(anyLong())).thenReturn(new ArrayList<>());
        when(forwardService.resumeExpiryDisabledPausedForUser(anyLong())).thenReturn(new ArrayList<>());

        interceptor = new JwtInterceptor();
        ReflectionTestUtils.setField(interceptor, "userMapper", userMapper);
        ReflectionTestUtils.setField(interceptor, "tokenRevocations", tokenRevocations);
    }

    @AfterEach
    void tearDown() {
        // 请求上下文是 ThreadLocal，不清理会漏到同一线程的下一个测试
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------------------------------------------------------------- JwtUtil

    @Test
    void generatedTokenCarriesTokenVersion() {
        User user = user(USER_ID, 1, 7);

        String token = JwtUtil.generateToken(user);

        assertEquals(7, JwtUtil.getTokenVersion(token), "payload 里的 ver 必须能原样读回");
        assertTrue(JwtUtil.validateToken(token), "新增 ver 不能破坏原有签名/exp 校验");
    }

    @Test
    void nullTokenVersionSignsAsZero() {
        User user = user(USER_ID, 1, null);
        user.setTokenVersion(null);

        String token = JwtUtil.generateToken(user);

        assertEquals(0, JwtUtil.getTokenVersion(token), "库里的 token_version 为 NULL 时按 0 签发");
    }

    @Test
    void legacyTokenWithoutVerReadsZero() {
        // round51 之前签发的 token：payload 只有 sub/iat/exp/user/name/role_id
        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", String.valueOf(USER_ID));
        payload.put("iat", System.currentTimeMillis() / 1000);
        payload.put("exp", System.currentTimeMillis() / 1000 + 3600);
        payload.put("user", "legacy");
        payload.put("name", "legacy");
        payload.put("role_id", 1);

        assertEquals(0, JwtUtil.getTokenVersion(fakeToken(payload)),
                "没有 ver 的老 token 必须读成 0，升级瞬间不能把已登录用户全部踢下线");
    }

    @Test
    void nonNumericVerReadsZero() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", String.valueOf(USER_ID));
        payload.put("ver", "abc");

        assertEquals(0, JwtUtil.getTokenVersion(fakeToken(payload)), "ver 不是数字时按 0 处理，不能抛异常");
        assertFalse(JwtUtil.validateToken("abc.def.ghi"), "坏 token 仍然验签失败");
        assertEquals(0, JwtUtil.getTokenVersion("abc"), "段数不足按 0");
        assertEquals(0, JwtUtil.getTokenVersion(null), "null 按 0");
    }

    // -------------------------------------------------------------- 拦截器

    @Test
    void interceptorAcceptsMatchingVersion() {
        User user = user(USER_ID, 1, 3);
        when(userMapper.selectById(USER_ID)).thenReturn(user);

        assertTrue(interceptor.preHandle(requestWith(JwtUtil.generateToken(user)),
                mock(javax.servlet.http.HttpServletResponse.class), new Object()),
                "签名有效 + 库里的版本号一致 + 账号启用，必须放行");
    }

    @Test
    void interceptorRejectsStaleVersion() {
        // token 里 ver=2，库里已经被 logout/改密推到 3
        User tokenOwner = user(USER_ID, 1, 2);
        when(userMapper.selectById(USER_ID)).thenReturn(user(USER_ID, 1, 3));

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith(JwtUtil.generateToken(tokenOwner)),
                        mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        assertTrue(e.getMessage().contains("重新登录"), "文案必须提示重新登录，实际：" + e.getMessage());
    }

    @Test
    void interceptorRejectsMissingUser() {
        User user = user(USER_ID, 1, 0);
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith(JwtUtil.generateToken(user)),
                        mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        assertTrue(e.getMessage().contains("停用"), "账号不存在/被删要与版本失效区分开，实际：" + e.getMessage());
    }

    @Test
    void interceptorRejectsDisabledUser() {
        User tokenOwner = user(USER_ID, 1, 2);
        // status 0 = 停用（createUser 写 1 表示启用，login 里 status==0 判停用）
        when(userMapper.selectById(USER_ID)).thenReturn(user(USER_ID, 0, 2));

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith(JwtUtil.generateToken(tokenOwner)),
                        mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        assertTrue(e.getMessage().contains("停用"), "停用账号的既有 token 必须立刻失效，实际：" + e.getMessage());
    }

    @Test
    void interceptorRejectsAbsentOrInvalidToken() {
        UnauthorizedException absent = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith(null), mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        assertTrue(absent.getMessage().contains("未登录"), "无 token 走未登录文案，实际：" + absent.getMessage());

        UnauthorizedException invalid = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith("abc.def.ghi"), mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        assertTrue(invalid.getMessage().contains("无效"), "坏 token 走无效文案，实际：" + invalid.getMessage());
        verify(userMapper, never()).selectById(anyLong());
    }

    @Test
    void revocationLookupFailureStopsValidSessionAndMapsTo503() throws Exception {
        User stored = user(USER_ID, 1, 4);
        when(userMapper.selectById(USER_ID)).thenReturn(stored);
        String token = JwtUtil.generateToken(stored);
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object.class), any(Object.class)))
                .thenThrow(new DataAccessResourceFailureException("unavailable"))
                .thenReturn(null)
                .thenReturn(0);
        assertThrows(TokenRevocationStore.RevocationStoreUnavailableException.class,
                () -> interceptor.preHandle(requestWith(token), mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        assertThrows(TokenRevocationStore.RevocationStoreUnavailableException.class,
                () -> interceptor.preHandle(requestWith(token), mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        assertTrue(interceptor.preHandle(requestWith(token), mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        Method handler = GlobalExceptionHandler.class.getMethod("handleRevocationStoreUnavailable", TokenRevocationStore.RevocationStoreUnavailableException.class);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, handler.getAnnotation(ResponseStatus.class).value());
        assertEquals(503, new GlobalExceptionHandler().handleRevocationStoreUnavailable(new TokenRevocationStore.RevocationStoreUnavailableException()).getCode());
    }

    @RestController
    static class RevocationProbeController {
        @GetMapping("/revocation-probe")
        public String probe() { return "ok"; }
    }

    @Test
    void interceptorFailureReallyReturnsHttp503() throws Exception {
        User stored = user(USER_ID, 1, 4);
        when(userMapper.selectById(USER_ID)).thenReturn(stored);
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object.class), any(Object.class)))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        MockMvcBuilders.standaloneSetup(new RevocationProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(interceptor).build()
                .perform(get("/revocation-probe").header("Authorization", JwtUtil.generateToken(stored)))
                .andExpect(status().isServiceUnavailable());
    }

    // ---------------------------------------------------------------- 登出

    @Test
    void logoutRevokesOnlyPresentedSession() {
        User user = user(USER_ID, 1, 4);
        String token = JwtUtil.generateToken(user);
        givenRequestToken(token);

        R result = userService.logout();

        assertEquals(0, result.getCode(), "登出应返回成功，实际：" + result.getMsg());
        // round63：吊销的是请求头里这条 token 的 sid，TTL 用 token 自己的 exp（过期后由清理逻辑删行）
        ArgumentCaptor<Object> sessionId = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> expiresAt = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate, times(1)).update(anyString(), sessionId.capture(), expiresAt.capture());
        assertEquals(JwtUtil.getSessionId(token), String.valueOf(sessionId.getValue()),
                "吊销记录必须落在这一次登录的 sid 上，实际：" + sessionId.getValue());
        assertEquals(JwtUtil.getExpireAtMillis(token), ((Number) expiresAt.getValue()).longValue(),
                "吊销记录的 TTL 必须取 token 自己的 exp");
        verify(userMapper, never()).update(any(), any());
        assertEquals(4, user.getTokenVersion(), "登出不能再动用户级版本号，否则会连带踢掉别的会话");
    }

    @Test
    void logoutOfLegacyTokenWithoutSidRevokesItsDigest() throws Exception {
        // 旧 token 没有 sid：只吊销签名验证后的该份票据。
        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", String.valueOf(USER_ID));
        payload.put("iat", System.currentTimeMillis() / 1000);
        payload.put("exp", System.currentTimeMillis() / 1000 + 3600);
        payload.put("user", "legacy");
        payload.put("name", "legacy");
        payload.put("role_id", 1);
        payload.put("ver", 4);
        String legacyToken = signedToken(payload);
        assertNull(JwtUtil.getSessionId(legacyToken), "老 token 没有 sid");
        assertTrue(JwtUtil.validateToken(legacyToken));
        givenRequestToken(legacyToken);

        R result = userService.logout();

        assertEquals(0, result.getCode(), "老 token 登出仍必须成功，实际：" + result.getMsg());
        ArgumentCaptor<Object> key = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> expires = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), key.capture(), expires.capture());
        assertEquals(JwtUtil.getRevocationKey(legacyToken), key.getValue());
        assertTrue(String.valueOf(key.getValue()).startsWith("L"));
        assertEquals(JwtUtil.getExpireAtMillis(legacyToken), ((Number) expires.getValue()).longValue());
        verify(userMapper, never()).update(any(), any());
    }

    // ---------------------------------------------------------------- 改密

    @Test
    @SuppressWarnings("unchecked")
    void updatePasswordBumpsVersionInSameStatement() {
        User stored = user(USER_ID, 1, 4);
        stored.setPwd(Md5Util.md5("old-pass"));
        // getUserIdFromToken() 无参版本返回 Integer，getById(5) 传下去的是 Integer 而不是 Long，
        // 用具体值打桩会因 Integer.equals(Long) == false 拿到 null；这里按 Serializable 匹配。
        when(userMapper.selectById(any(java.io.Serializable.class))).thenReturn(stored);
        givenRequestToken(JwtUtil.generateToken(stored));

        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setNewUsername(stored.getUser());
        dto.setCurrentPassword("old-pass");
        dto.setNewPassword("new-pass");
        dto.setConfirmPassword("new-pass");

        R result = userService.updatePassword(dto);
        assertEquals(0, result.getCode(), "改密应成功，实际：" + result.getMsg());

        ArgumentCaptor<User> entity = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<Wrapper<User>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper, times(1)).update(entity.capture(), wrapper.capture());
        assertEquals(Md5Util.md5("new-pass"), entity.getValue().getPwd(), "同一条 UPDATE 里要有新密码");
        assertNull(entity.getValue().getTokenVersion(),
                "版本号不能以 entity 的「旧值+1」写下去，否则会覆盖并发 logout 已经 +1 的结果");
        String sqlSet = ((UpdateWrapper<User>) wrapper.getValue()).getSqlSet();
        assertNotNull(sqlSet, "改密必须带上 token_version 的 SQL 自增");
        String normalized = sqlSet.toLowerCase().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("token_version"), "自增语句必须落在 token_version 上，实际：" + sqlSet);
        assertTrue(normalized.contains("+ 1"), "必须是自增，实际：" + sqlSet);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminPasswordChangeBumpsVersionInSameStatement() {
        User stored = user(3L, 1, 2);
        when(userMapper.selectById(any(java.io.Serializable.class))).thenReturn(stored);

        R result = userService.updateUser(adminDto("changed-pass"));

        assertEquals(0, result.getCode(), "管理员改密应成功，实际：" + result.getMsg());
        ArgumentCaptor<User> entity = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<Wrapper<User>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper, times(1)).update(entity.capture(), wrapper.capture());
        assertNotNull(entity.getValue().getPwd(), "同一条 UPDATE 里必须有新密码哈希");
        assertNull(entity.getValue().getTokenVersion(), "版本号交给 SQL 自增，不能写读到的旧值+1");
        assertNotNull(((UpdateWrapper<User>) wrapper.getValue()).getSqlSet(),
                "管理员改密同样要吊销该用户旧 token");
    }

    @Test
    void logoutWithoutRequestContextFailsLoudly() {
        RequestContextHolder.resetRequestAttributes();

        R result = userService.logout();

        assertEquals(-1, result.getCode(), "没有请求上下文时不能静默返回成功：" + result.getMsg());
        assertTrue(result.getMsg().contains("未登录"), "实际：" + result.getMsg());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void interceptorRejectsRoleMismatch() {
        User tokenOwner = user(USER_ID, 1, 3);
        tokenOwner.setRoleId(0);
        User stored = user(USER_ID, 1, 3);
        stored.setRoleId(1);
        when(userMapper.selectById(USER_ID)).thenReturn(stored);

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith(JwtUtil.generateToken(tokenOwner)),
                        mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        assertTrue(e.getMessage().contains("权限已变更"), "降权后旧 token 必须立刻失效，实际：" + e.getMessage());
    }

    @Test
    void tokenWithoutRoleIdIsRejectedInsteadOfNpe() {
        User tokenOwner = user(USER_ID, 1, 3);
        tokenOwner.setRoleId(null); // fastjson 默认不写 null，签出来的 token 就没有 role_id
        User stored = user(USER_ID, 1, 3);
        stored.setRoleId(1);
        when(userMapper.selectById(USER_ID)).thenReturn(stored);

        String token = JwtUtil.generateToken(tokenOwner);
        assertNull(JwtUtil.getRoleIdFromToken(token), "缺 role_id 必须返回 null，不能 NPE");
        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith(token),
                        mock(javax.servlet.http.HttpServletResponse.class), new Object()));
        assertTrue(e.getMessage().contains("权限已变更"), "实际：" + e.getMessage());
    }

    @Test
    void adminUpdateWithoutPasswordKeepsVersionUntouched() {
        User stored = user(3L, 1, 2);
        when(userMapper.selectById(any(java.io.Serializable.class))).thenReturn(stored);

        userService.updateUser(adminDto(""));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(1)).updateById(captor.capture());
        // copyProperties 之后 tokenVersion 是 null，只有改密分支才显式赋值；NULL 字段不参与 UPDATE
        assertEquals(null, captor.getValue().getTokenVersion(),
                "不改密码时不能顺手吊销，否则管理员改个备注就把用户踢下线");
        assertEquals(null, captor.getValue().getPwd(), "留空表示不改密码");
    }

    // ---------------------------------------------------------- 单实例校验

    @Test
    void webMvcConfigInjectsSingleInterceptorInstance() {
        for (Method method : WebMvcConfig.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Bean.class)
                            && JwtInterceptor.class.isAssignableFrom(method.getReturnType()),
                    "WebMvcConfig 不能再声明 @Bean 返回 JwtInterceptor，否则与 @Component 单例并存会有两个实例：" + method.getName());
        }
        Field field = null;
        try {
            field = WebMvcConfig.class.getDeclaredField("jwtInterceptor");
        } catch (NoSuchFieldException e) {
            throw new AssertionError("WebMvcConfig 必须以字段注入 JwtInterceptor", e);
        }
        assertEquals(JwtInterceptor.class, field.getType());
        assertNotNull(field.getAnnotation(Resource.class), "字段必须用 @Resource 注入容器里的那个单例");
    }

    // ---------------------------------------------------------------- 夹具

    private static User user(long id, int status, Integer tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setUser("u" + id);
        user.setRoleId(1);
        user.setStatus(status);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private static UserUpdateDto adminDto(String pwd) {
        UserUpdateDto dto = new UserUpdateDto();
        dto.setId(3L);
        dto.setUser("u3");
        dto.setPwd(pwd);
        dto.setFlow(1024L);
        dto.setNum(10);
        dto.setExpTime(System.currentTimeMillis() + 86400000L);
        dto.setFlowResetTime(System.currentTimeMillis());
        dto.setStatus(1);
        return dto;
    }

    private static HttpServletRequest requestWith(String token) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(token);
        return request;
    }

    private static void givenRequestToken(String token) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(requestWith(token)));
    }

    private static String signedToken(Map<String, Object> payload) throws Exception {
        String unsigned = fakeToken(payload);
        String prefix = unsigned.substring(0, unsigned.lastIndexOf('.'));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return prefix + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(prefix.getBytes(StandardCharsets.UTF_8)));
    }

    /** 手工拼一个 payload 可控的 token：getTokenVersion 只读 payload，不过签名。 */
    private static String fakeToken(Map<String, Object> payload) {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"HmacSHA256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(
                JSON.toJSONString(payload).getBytes(StandardCharsets.UTF_8));
        return header + "." + body + ".sig";
    }
}
