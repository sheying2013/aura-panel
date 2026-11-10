package com.admin.compat;

import com.admin.common.aop.LogAspect;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LogAspect 请求参数脱敏（P0 凭据泄漏）。
 *
 * <p>旧实现在 {@code LogAspect.java:149-192} 把 {@code joinPoint.getArgs()} 直接
 * {@code JSON.toJSONString}：/flow/config、/flow/upload 的 secret 与 /user/login 的 password
 * 会明文落进日志；带 {@code HttpServletResponse} 的 OpenApiController.create 序列化失败后
 * 整条参数只剩 "获取参数失败"，连排查线索都没有。</p>
 *
 * <p>{@code getRequestParams} 仍是 private，测试在 {@code com.admin.compat} 包下，
 * 与本类不同包，因此只能反射调用 —— 把可见性改成 package-private 同样跨不过包边界，
 * 所以选择反射，源码保持最小改动。</p>
 */
class LogMaskTest {

    /** 序列化必然失败的 Map：模拟旧实现里 Servlet 容器对象那种 JSON 化异常。 */
    private static class BrokenMap extends LinkedHashMap<String, Object> {
        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            throw new IllegalStateException("boom");
        }
    }

    private String invoke(String[] names, Object... args) {
        JoinPoint joinPoint = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getParameterNames()).thenReturn(names);
        when(joinPoint.getArgs()).thenReturn(args);
        try {
            Method method = LogAspect.class.getDeclaredMethod("getRequestParams", JoinPoint.class);
            method.setAccessible(true);
            Object out = method.invoke(new LogAspect(), joinPoint);
            assertNotNull(out);
            assertTrue(out instanceof String, "getRequestParams 必须始终返回字符串");
            return (String) out;
        } catch (InvocationTargetException e) {
            throw new AssertionError("getRequestParams 不能抛异常，实际抛出: " + e.getCause(), e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射调用 getRequestParams 失败", e);
        }
    }

    @Test
    @DisplayName("pwd 参数被脱敏")
    void pwdIsMasked() {
        Map<String, Object> form = new HashMap<>();
        form.put("user", "alice");
        form.put("pwd", "SuperSecret123");

        String out = invoke(new String[]{"form"}, form);

        assertTrue(out.contains("\"pwd\":\"***\""), out);
        assertFalse(out.contains("SuperSecret123"), out);
        assertTrue(out.contains("alice"), out);
    }

    @Test
    @DisplayName("X-Auth-Pwd 头参数（pwdHeader）被脱敏")
    void pwdHeaderIsMasked() {
        String out = invoke(new String[]{"user", "pwdHeader"}, "alice", "SuperSecret123");

        assertTrue(out.contains("\"pwdHeader\":\"***\""), out);
        assertFalse(out.contains("SuperSecret123"), out);
        assertTrue(out.contains("alice"), out);
    }

    @Test
    @DisplayName("secret 参数被脱敏（/flow/config、/flow/upload）")
    void secretIsMasked() {
        Map<String, Object> config = new HashMap<>();
        config.put("id", 3);
        config.put("secret", "nsec_9f8e7d6c");

        String out = invoke(new String[]{"config"}, config);

        assertTrue(out.contains("\"secret\":\"***\""), out);
        assertFalse(out.contains("nsec_9f8e7d6c"), out);
    }

    @Test
    @DisplayName("嵌套 Map / List 里的敏感键也被脱敏")
    void nestedMapIsMasked() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("newPassword", "p@ss-inner");
        inner.put("keep", "visible");
        Map<String, Object> outer = new HashMap<>();
        outer.put("payload", inner);
        outer.put("items", new ArrayList<Object>(Arrays.asList(inner)));

        String out = invoke(new String[]{"outer"}, outer);

        assertFalse(out.contains("p@ss-inner"), out);
        assertTrue(out.contains("\"newPassword\":\"***\""), out);
        assertTrue(out.contains("visible"), out);
    }

    @Test
    @DisplayName("已经是 JSON 字符串的参数按正则脱敏")
    void jsonStringIsMasked() {
        String body = "{\"user\":\"alice\",\"token\":\"sk-live-123\",\"data\":{\"Authorization\":\"Bearer abc\"}}";

        String out = invoke(new String[]{"body"}, body);

        assertFalse(out.contains("sk-live-123"), out);
        assertFalse(out.contains("Bearer abc"), out);
        assertTrue(out.contains("\"token\":\"***\""), out);
        assertTrue(out.contains("alice"), out);
    }

    @Test
    @DisplayName("HttpServletResponse 被跳过，不再整条退化成「获取参数失败」")
    void httpServletResponseIsOmitted() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String out = invoke(new String[]{"user", "pwd", "response"}, "alice", "plain", response);

        assertTrue(out.contains("<omitted:MockHttpServletResponse>"), out);
        assertFalse(out.contains("获取参数失败"), out);
        assertTrue(out.contains("alice"), out);
    }

    @Test
    @DisplayName("byte[] 参数被跳过")
    void byteArrayIsOmitted() {
        String out = invoke(new String[]{"blob"}, (Object) new byte[]{1, 2, 3});

        assertTrue(out.contains("<omitted:byte[]>"), out);
    }

    @Test
    @DisplayName("普通参数原样保留")
    void plainParamsAreKept() {
        String out = invoke(new String[]{"user", "count"}, "alice", 42);

        assertTrue(out.contains("alice"), out);
        assertTrue(out.contains("42"), out);
        assertFalse(out.contains("***"), out);
    }

    @Test
    @DisplayName("序列化失败时退化为短值拼接，且总长不超过 300 字符")
    void unserializableArgsTruncatedToLimit() {
        List<Object> args = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add("payload");
        args.add(new BrokenMap());
        for (int i = 0; i < 30; i++) {
            names.add("arg" + i);
            args.add(repeat("x", 120));
        }

        String out = invoke(names.toArray(new String[0]), args.toArray());

        assertFalse(out.contains("获取参数失败"), out);
        assertTrue(out.length() <= 300, "兜底拼接长度应 <= 300，实际 " + out.length());
        assertTrue(out.endsWith("..."), out);
    }

    @Test
    @DisplayName("兜底路径里 pwd 这类敏感参数名同样被替换")
    void fallbackMasksSensitiveName() {
        String out = invoke(new String[]{"payload", "pwd"}, new BrokenMap(), "plain-text-pwd");

        assertTrue(out.contains("pwd=***"), out);
        assertFalse(out.contains("plain-text-pwd"), out);
        assertFalse(out.contains("获取参数失败"), out);
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("null 参数不抛异常")
    void nullArgIsSafe() {
        String out = invoke(new String[]{"token", "nothing"}, "abc", null);

        assertTrue(out.contains("\"token\":\"***\""), out);
        assertFalse(out.contains("abc"), out);
    }
}
