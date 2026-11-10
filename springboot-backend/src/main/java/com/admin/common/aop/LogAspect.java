package com.admin.common.aop;


import com.admin.common.utils.JwtUtil;
import com.alibaba.fastjson.JSON;
import com.admin.common.utils.HttpContextUtils;
import com.admin.common.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.CodeSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Aspect
@Slf4j
public class LogAspect {

    private static final String MASK = "***";
    private static final int MAX_FALLBACK_LEN = 300;
    private static final int MAX_FALLBACK_ITEM_LEN = 60;

    /** 敏感键名（大小写不敏感）：命中即把值替换为 MASK。 */
    private static final List<String> SENSITIVE_KEYS = Arrays.asList(
            "pwd", "pwdheader", "password", "newpassword", "confirmpassword", "currentpassword",
            "secret", "token", "authorization", "accesstoken",
            "apikey", "privatekey", "refreshtoken", "nodesecret");

    /** 已是 JSON 文本的参数：把 "secret":"xxx" 的值就地换成 "***"。 */
    private static final Pattern JSON_SECRET_PAIR = Pattern.compile(
            "(\"(" + String.join("|", SENSITIVE_KEYS) + ")\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s]+)",
            Pattern.CASE_INSENSITIVE);

    @Pointcut("@annotation(com.admin.common.aop.LogAnnotation)")
    public void pt() {

    }

    /**
     * 返回后通知（@AfterReturning）：在某连接点（joinpoint）
     * 正常完成后执行的通知：例如，一个方法没有抛出任何异常，正常返回
     * 方法执行完毕之后
     * 注意在这里不能使用ProceedingJoinPoint
     * 不然会报错ProceedingJoinPoint is only supported for around advice
     * crmAspect()指向需要控制的方法
     * returning  注解返回值
     *
     * @param joinPoint
     * @param returnValue 返回值
     * @throws Exception
     */
    @AfterReturning(value = "pt()", returning = "returnValue")
    public void log(JoinPoint joinPoint, Object returnValue) throws Throwable {
        try {
            // 获取请求信息
            HttpServletRequest request = HttpContextUtils.getHttpServletRequest();
            if (request == null) {
                return;
            }

            // 获取请求方法类型（POST/GET等）
            String requestMethod = request.getMethod();

            // 获取用户ID（兜底异常保护）
            String authorization = request.getHeader("Authorization");
            Object user_id = "未登录"; // 请求用户的id
            if (authorization != null && !authorization.trim().isEmpty() && !authorization.equalsIgnoreCase("null")) {
                try {
                    Long uid = JwtUtil.getUserIdFromToken(authorization);
                    if (uid != null) {
                        user_id = uid;
                    }
                } catch (Exception ignored) {
                    user_id = "未知用户";
                }
            }

            // 获取请求IP
            String ipAddr = IpUtils.getIpAddr(request);

            // 获取方法签名信息
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature != null ? signature.getMethod() : null;

            // 获取控制器方法名
            String className = joinPoint.getTarget() != null ? joinPoint.getTarget().getClass().getName() : (signature != null ? signature.getDeclaringTypeName() : "UnknownClass");
            String methodName = signature != null ? signature.getName() : "unknownMethod";
            String controllerMethod = className + "." + methodName;

            // 获取请求参数
            String requestParams = getRequestParams(joinPoint);

            // 获取返回参数（敏感信息脱敏）
            String responseParams = getResponseParams(returnValue);

            // 合并为一条完整的日志信息
            String logMessage = String.format(
                "【请求日志】用户ID:[%s], IP地址:[%s], 请求方式:[%s], 控制器方法:[%s], 请求参数:[%s], 返回参数:[%s]", user_id, ipAddr, requestMethod, controllerMethod, requestParams, responseParams
            );

            // 打印单条完整日志
            log.info(logMessage);
        } catch (Throwable t) {
            log.warn("记录请求日志时出错: {}", t.getMessage());
        }
    }


    /**
     * 抛出异常后通知（@AfterThrowing）：方法抛出异常退出时执行的通知
     * 注意在这里不能使用ProceedingJoinPoint
     * 不然会报错ProceedingJoinPoint is only supported for around advice
     * throwing注解为错误信息
     *
     * @param joinPoint
     * @param ex
     */
    @AfterThrowing(value = "pt()", throwing = "ex")
    public void recordLog(JoinPoint joinPoint, Exception ex) {
        try {
            // 获取请求信息
            HttpServletRequest request = HttpContextUtils.getHttpServletRequest();
            if (request == null) {
                return;
            }

            // 获取请求方法类型（POST/GET等）
            String requestMethod = request.getMethod();

            // 获取用户ID
            String authorization = request.getHeader("Authorization");
            Object user_id = "未登录"; // 请求用户的id
            if (authorization != null && !authorization.trim().isEmpty() && !authorization.equalsIgnoreCase("null")) {
                try {
                    Long uid = JwtUtil.getUserIdFromToken(authorization);
                    if (uid != null) {
                        user_id = uid;
                    }
                } catch (Exception ignored) {
                    user_id = "未知用户";
                }
            }

            // 获取请求IP
            String ipAddr = IpUtils.getIpAddr(request);

            // 获取方法签名信息
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature != null ? signature.getMethod() : null;

            // 获取控制器方法名
            String className = joinPoint.getTarget() != null ? joinPoint.getTarget().getClass().getName() : (signature != null ? signature.getDeclaringTypeName() : "UnknownClass");
            String methodName = signature != null ? signature.getName() : "unknownMethod";
            String controllerMethod = className + "." + methodName;

            // 获取请求参数
            String requestParams = getRequestParams(joinPoint);

            // 获取异常信息
            String exceptionMsg = ex != null ? ex.getMessage() : "未知异常";

            // 合并为一条完整的异常日志信息
            String errorMessage = String.format(
                "【异常日志】用户ID:[%s], IP地址:[%s], 请求方式:[%s], 控制器方法:[%s], 请求参数:[%s], 异常信息:[%s]", user_id, ipAddr, requestMethod, controllerMethod, requestParams, exceptionMsg
            );

            // 打印单条完整异常日志
            log.info(errorMessage, ex);
        } catch (Throwable e) {
            log.warn("记录异常日志时出错: {}", e.getMessage());
        }
    }

    /**
     * 获取返回参数并脱敏：防止 token/密码/密钥 等凭据落入日志。
     */
    private String getResponseParams(Object returnValue) {
        if (returnValue == null) {
            return "无返回值";
        }
        try {
            if (returnValue instanceof String) {
                String str = (String) returnValue;
                return looksLikeJson(str) ? maskJsonText(str) : str;
            }
            Object masked = mask(returnValue);
            String json = JSON.toJSONString(masked);
            return json != null ? maskJsonText(json) : shortValue(returnValue);
        } catch (Exception e) {
            return shortValue(returnValue);
        }
    }
    
    /**
     * 获取请求参数
     */
    private String getRequestParams(JoinPoint joinPoint) {
        Object[] args;
        try {
            args = joinPoint.getArgs();
        } catch (Exception e) {
            return "无参数";
        }
        if (args == null || args.length == 0) {
            return "无参数";
        }
        try {
            String[] names = parameterNames(joinPoint);
            // 单个 JSON 字符串入参照旧输出原始 JSON，只是先把敏感键脱敏
            if (args.length == 1 && args[0] instanceof String) {
                String only = (String) args[0];
                if (only.startsWith("{") && only.endsWith("}")) {
                    return maskJsonText(only);
                }
            }
            Map<String, Object> params = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String name = names != null && i < names.length && names[i] != null ? names[i] : ("arg" + i);
                params.put(name, isSensitiveKey(name) ? MASK : mask(args[i]));
            }
            String json = JSON.toJSONString(params);
            return json != null ? maskJsonText(json) : fallbackParams(joinPoint, args);
        } catch (Exception e) {
            // 序列化失败（Servlet 容器对象、畸形集合等）不再整条丢成"获取参数失败"，退化为 name=脱敏短值
            return fallbackParams(joinPoint, args);
        }
    }

    /**
     * 递归脱敏：Map/List/数组按 key 判断，JSON 文本按正则替换，容器对象只留占位符。
     */
    private Object mask(Object value) {
        if (value == null) {
            return null;
        }
        if (isOmitted(value)) {
            return "<omitted:" + value.getClass().getSimpleName() + ">";
        }
        if (value instanceof Map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                masked.put(key, isSensitiveKey(key) ? MASK : mask(entry.getValue()));
            }
            return masked;
        }
        if (value instanceof Collection) {
            List<Object> masked = new ArrayList<>(((Collection<?>) value).size());
            for (Object item : (Collection<?>) value) {
                masked.add(mask(item));
            }
            return masked;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> masked = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                masked.add(mask(Array.get(value, i)));
            }
            return masked;
        }
        if (value instanceof CharSequence) {
            String text = value.toString();
            return looksLikeJson(text) ? maskJsonText(text) : text;
        }
        return value;
    }

    /**
     * 不可序列化/无日志价值的入参：只留 <omitted:类名> 占位。
     */
    private boolean isOmitted(Object value) {
        return value instanceof HttpServletRequest
                || value instanceof HttpServletResponse
                || value instanceof MultipartFile
                || value instanceof InputStream
                || value instanceof OutputStream
                || value instanceof byte[];
    }

    private boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEYS.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    private boolean looksLikeJson(String text) {
        if (text == null || text.length() < 2) {
            return false;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }

    private String maskJsonText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = JSON_SECRET_PAIR.matcher(text);
        StringBuffer sb = new StringBuffer(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(1) + "\"" + MASK + "\""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 序列化失败时的兜底：name=脱敏短值 拼接，总长不超过 MAX_FALLBACK_LEN，任何情况下都不抛。
     */
    private String fallbackParams(JoinPoint joinPoint, Object[] args) {
        StringBuilder sb = new StringBuilder();
        String[] names = parameterNames(joinPoint);
        for (int i = 0; i < args.length && sb.length() <= MAX_FALLBACK_LEN; i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String name = names != null && i < names.length && names[i] != null ? names[i] : ("arg" + i);
            sb.append(name).append('=');
            sb.append(isSensitiveKey(name) ? MASK : shortValue(args[i]));
        }
        if (sb.length() > MAX_FALLBACK_LEN) {
            sb.setLength(MAX_FALLBACK_LEN - 3);
            sb.append("...");
        }
        return sb.length() == 0 ? "无参数" : sb.toString();
    }

    private String shortValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (isOmitted(value)) {
            return "<omitted:" + value.getClass().getSimpleName() + ">";
        }
        String text = null;
        try {
            text = value instanceof CharSequence ? value.toString() : JSON.toJSONString(value);
        } catch (Exception ignored) {
            // 落到下面的 toString 兜底
        }
        if (text == null) {
            try {
                text = String.valueOf(value);
            } catch (Exception ignored) {
                text = null;
            }
        }
        if (text == null) {
            return "<unavailable>";
        }
        text = maskJsonText(text);
        return text.length() > MAX_FALLBACK_ITEM_LEN ? text.substring(0, MAX_FALLBACK_ITEM_LEN) + "…" : text;
    }

    private String[] parameterNames(JoinPoint joinPoint) {
        try {
            if (joinPoint.getSignature() instanceof CodeSignature) {
                return ((CodeSignature) joinPoint.getSignature()).getParameterNames();
            }
        } catch (Exception ignored) {
            // 拿不到参数名时上层用 argN 兜底
        }
        return null;
    }
}
