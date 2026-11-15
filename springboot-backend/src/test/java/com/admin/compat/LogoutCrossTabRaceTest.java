package com.admin.compat;

import com.admin.common.exception.UnauthorizedException;
import com.admin.common.interceptor.JwtInterceptor;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.TokenRevocationStore;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.admin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import com.alibaba.fastjson2.JSON;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round63：跨标签「旧会话的登出迟到」不得连坐新登录。
 *
 * <p>真机竞态：标签 A 用版本号 4 的 token 打开着面板；用户在新标签 B 重新登录，login 不自增版本号，
 * 所以 B 的 token 也是 4；A 的登出请求在服务端已经通过鉴权（拦截器读到的还是旧状态），但落库晚于
 * B 的登录——旧实现里那条 {@code token_version = token_version + 1} 把库推到 5，B 手里刚拿到的
 * token 立刻被判「登录状态已失效」，用户表现为「刚登录就被踢下线」。
 *
 * <p>这里锁住的语义：① 每次登录拿到独立 sid；② 登出只吊销请求头里那一条 token，新登录的 token
 * 继续可用、旧 token 失效；③ 整条登出路径不碰 user.token_version；④ 用户级吊销（改密/停用导致版本
 * 号变化）仍然一刀切掉该用户所有会话；⑤ 吊销写不进库时登出必须报错，不能谎称已登出。
 */
class LogoutCrossTabRaceTest {

    private static final String TEST_SECRET = "test-secret";
    private static final long USER_ID = 7L;

    private final UserMapper userMapper = mock(UserMapper.class);
    private final InMemoryRevocations revocations = new InMemoryRevocations();
    private UserServiceImpl userService;
    private JwtInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // SECRET_KEY 是 private static 且非 final，容器里由 @PostConstruct 赋值；单测只能反射塞进去
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", TEST_SECRET);

        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "tokenRevocations", revocations);

        interceptor = new JwtInterceptor();
        ReflectionTestUtils.setField(interceptor, "userMapper", userMapper);
        ReflectionTestUtils.setField(interceptor, "tokenRevocations", revocations);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void eachLoginGetsItsOwnSessionId() {
        String first = JwtUtil.generateToken(user(4));
        String second = JwtUtil.generateToken(user(4));

        assertNotEquals(JwtUtil.getSessionId(first), JwtUtil.getSessionId(second),
                "同一用户两次登录必须拿到不同 sid，否则吊销粒度会退化成整个用户");
        assertEquals(4, JwtUtil.getTokenVersion(second), "登录仍然只带当前版本号，不在登录时自增");
    }

    @Test
    void staleLogoutFromOldTabCannotKillFreshLogin() {
        User stored = user(4);
        when(userMapper.selectById(USER_ID)).thenReturn(stored);

        String oldTabToken = JwtUtil.generateToken(user(4));
        String newTabToken = JwtUtil.generateToken(user(4)); // 用户刚在另一个标签登录，版本号同样是 4

        // 标签 A 的登出迟到：先通过鉴权，落库晚于标签 B 的登录
        givenRequestToken(oldTabToken);
        R logout = userService.logout();
        assertEquals(0, logout.getCode(), "旧标签的登出自身仍应成功，实际：" + logout.getMsg());

        assertTrue(interceptor.preHandle(requestWith(newTabToken), mock(HttpServletResponse.class), new Object()),
                "新登录的 token 版本号与库里一致、也没有被吊销，必须继续可用");

        UnauthorizedException rejected = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith(oldTabToken), mock(HttpServletResponse.class), new Object()));
        assertTrue(rejected.getMessage().contains("重新登录"), "旧 token 必须失效，实际：" + rejected.getMessage());

        assertEquals(4, stored.getTokenVersion(), "登出路径不许动用户级版本号");
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void legacyLogoutAfterNewLoginRevokesOnlyLegacyToken() {
        User stored = user(4);
        when(userMapper.selectById(USER_ID)).thenReturn(stored);
        String fresh = JwtUtil.generateToken(stored);
        String[] parts = JwtUtil.generateToken(stored).split("\\.");
        Map<String, Object> payload = JSON.parseObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), Map.class);
        payload.remove("sid");
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.toJSONString(payload).getBytes(StandardCharsets.UTF_8));
        String signature = ReflectionTestUtils.invokeMethod(JwtUtil.class, "calculateSignature", parts[0], body);
        String legacy = parts[0] + "." + body + "." + signature;
        assertTrue(JwtUtil.validateToken(legacy));
        assertTrue(interceptor.preHandle(requestWith(legacy), mock(HttpServletResponse.class), new Object()));
        givenRequestToken(legacy);
        assertEquals(0, userService.logout().getCode());
        assertThrows(UnauthorizedException.class, () -> interceptor.preHandle(requestWith(legacy), mock(HttpServletResponse.class), new Object()));
        assertTrue(interceptor.preHandle(requestWith(fresh), mock(HttpServletResponse.class), new Object()));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void userLevelRevocationStillKillsEverySession() {
        // 改密/停用走的是 user.token_version：库里版本被推到 5 之后，该用户所有会话一律失效
        when(userMapper.selectById(USER_ID)).thenReturn(user(5));

        String first = JwtUtil.generateToken(user(4));
        String second = JwtUtil.generateToken(user(4));

        assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith(first), mock(HttpServletResponse.class), new Object()));
        assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(requestWith(second), mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void repeatedLogoutOfSameSessionIsIdempotent() {
        User stored = user(4);
        when(userMapper.selectById(USER_ID)).thenReturn(stored);
        givenRequestToken(JwtUtil.generateToken(user(4)));

        assertEquals(0, userService.logout().getCode(), "首次登出成功");
        assertEquals(0, userService.logout().getCode(), "重复登出（并发/重放）同样返回成功，不能报错吓用户");
        assertEquals(4, stored.getTokenVersion());
    }

    @Test
    void logoutReportsFailureWhenRevocationCannotBePersisted() {
        ReflectionTestUtils.setField(userService, "tokenRevocations", new TokenRevocationStore() {
            @Override
            public boolean revoke(String sessionId, long expiresAtMillis) {
                return false;
            }
        });
        givenRequestToken(JwtUtil.generateToken(user(4)));

        R result = userService.logout();

        assertEquals(-1, result.getCode(), "写不进吊销表时必须报错，不能谎称已登出，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("退出登录失败"), "实际：" + result.getMsg());
    }

    // ---------------------------------------------------------------- 夹具

    /** 用内存集合顶替 revoked_token 表：本用例只验证吊销粒度与竞态语义，SQL 由 TokenRevocationStoreTest 覆盖。 */
    private static final class InMemoryRevocations extends TokenRevocationStore {
        private final Set<String> revoked = new HashSet<>();

        @Override
        public boolean revoke(String sessionId, long expiresAtMillis) {
            if (sessionId == null || sessionId.isEmpty()) {
                return false;
            }
            revoked.add(sessionId);
            return true;
        }

        @Override
        public boolean isRevoked(String sessionId) {
            return sessionId != null && revoked.contains(sessionId);
        }
    }

    private static User user(Integer tokenVersion) {
        User user = new User();
        user.setId(USER_ID);
        user.setUser("u" + USER_ID);
        user.setRoleId(1);
        user.setStatus(1);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private static HttpServletRequest requestWith(String token) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(token);
        return request;
    }

    private static void givenRequestToken(String token) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(requestWith(token)));
    }
}
