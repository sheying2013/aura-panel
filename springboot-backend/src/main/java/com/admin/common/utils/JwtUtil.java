package com.admin.common.utils;

import com.admin.entity.User;
import com.alibaba.fastjson2.JSON;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.servlet.http.HttpServletRequest;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类，不使用第三方库实现
 */
@Component
public class JwtUtil {
    
    @Value("${jwt-secret}")
    private String secretKey;
    
    private static String SECRET_KEY;
    
    // token有效期，7天
    private static final long EXPIRE_TIME = 90L * 24 * 60 * 60 * 1000;
    // 算法
    private static final String ALGORITHM = "HmacSHA256";

    @PostConstruct
    public void init() {
        SECRET_KEY = this.secretKey;
    }

    /**
     * 生成JWT Token
     *
     * @param user 用户信息
     * @return 生成的JWT Token
     */
    public static String generateToken(User user) {
        try {
            long nowMillis = System.currentTimeMillis();
            Date now = new Date(nowMillis);
            Date expireDate = new Date(nowMillis + EXPIRE_TIME);

            // Header
            Map<String, Object> header = new HashMap<>();
            header.put("alg", ALGORITHM);
            header.put("typ", "JWT");
            String headerJson = JSON.toJSONString(header);
            String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));

            // Payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", user.getId().toString());
            payload.put("iat", now.getTime() / 1000); // 发布时间
            payload.put("exp", expireDate.getTime() / 1000); // 过期时间
            payload.put("user", user.getUser());
            payload.put("name", user.getUser());
            payload.put("role_id", user.getRoleId());
            // round51：token 版本号，与 user.token_version 比对才能让旧 token 立即失效
            payload.put("ver", user.getTokenVersion() == null ? 0 : user.getTokenVersion());

            String payloadJson = JSON.toJSONString(payload);
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            // Signature
            String signature = calculateSignature(encodedHeader, encodedPayload);

            // Token
            return encodedHeader + "." + encodedPayload + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("JWT token generation failed", e);
        }
    }

    /**
     * 验证JWT Token
     *
     * @param token JWT Token
     * @return 验证是否通过
     */
    public static boolean validateToken(String token) {
        try {
            if (token == null || token.isEmpty()) {
                return false;
            }

            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }

            String encodedHeader = parts[0];
            String encodedPayload = parts[1];
            String signature = parts[2];

            // 验证签名
            String expectedSignature = calculateSignature(encodedHeader, encodedPayload);
            if (!expectedSignature.equals(signature)) {
                return false;
            }

            // 验证过期时间
            String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
            long exp = Long.parseLong(payload.get("exp").toString());
            long now = System.currentTimeMillis() / 1000;
            
            return exp > now;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从JWT Token中读取 token 版本号（round51）。
     *
     * <p>round51 之前签发的 token 没有 ver 字段：这里一律按 0 返回，与「库里 token_version
     * 为空当 0」的口径对齐，升级瞬间不会把已登录的用户全部踢下线。payload 解析不了、ver 不是
     * 数字同样返回 0 —— 签名和有效期由 {@link #validateToken(String)} 负责，这里只取号。
     *
     * @param token JWT Token
     * @return token 中的版本号，缺失或非法一律 0
     */
    public static int getTokenVersion(String token) {
        try {
            if (token == null || token.isEmpty()) {
                return 0;
            }
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return 0;
            }
            String decodedPayload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
            Object ver = payload.get("ver");
            if (ver == null) {
                return 0;
            }
            if (ver instanceof Number) {
                return ((Number) ver).intValue();
            }
            return Integer.parseInt(ver.toString().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 从JWT Token中获取用户ID
     *
     * @param token JWT Token
     * @return 用户ID
     */
    public static Long getUserIdFromToken(String token) {
        try {
            if (token == null || token.trim().isEmpty()) return null;
            String[] parts = token.trim().split("\\.");
            if (parts.length != 3) return null;
            String encodedPayload = parts[1];
            String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
            if (payload == null || payload.get("sub") == null) return null;
            return Long.parseLong(payload.get("sub").toString().trim());
        } catch (Exception e) {
            return null;
        }
    }


    public static Integer getUserIdFromToken() {
        try {
            HttpServletRequest req = HttpContextUtils.getHttpServletRequest();
            if (req == null) return null;
            String token = req.getHeader("Authorization");
            Long id = getUserIdFromToken(token);
            return id != null ? id.intValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String getNameFromToken() {
        try {
            HttpServletRequest req = HttpContextUtils.getHttpServletRequest();
            if (req == null) return null;
            String token = req.getHeader("Authorization");
            if (token == null || token.trim().isEmpty()) return null;
            String[] parts = token.trim().split("\\.");
            if (parts.length != 3) return null;
            String encodedPayload = parts[1];
            String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
            if (payload == null || payload.get("name") == null) return null;
            return payload.get("name").toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从JWT Token中获取用户角色ID
     *
     * @param token JWT Token
     * @return 角色ID
     */
    public static Integer getRoleIdFromToken(String token) {
        // round51：缺 role_id（历史 token / 外部伪造但签名合法的 token）不能在这里抛 NPE，
        // 否则拦截器拿到的是 500 而不是「权限已变更」的 401。返回 null 交给调用方判失效。
        try {
            if (token == null || token.isEmpty()) return null;
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String decodedPayload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
            Object role = payload.get("role_id");
            if (role == null) return null;
            return Integer.parseInt(role.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    @SneakyThrows
    public static Integer getRoleIdFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        if (token == null || token.isEmpty()) throw new Exception();
        // 解析失败/缺 role_id 返回 null（调用方按「非管理员」处理），不抛 NPE
        return getRoleIdFromToken(token);
    }
    /**
     * 计算签名
     *
     * @param encodedHeader  编码后的头部
     * @param encodedPayload 编码后的负载
     * @return 签名
     * @throws Exception 签名计算异常
     */
    private static String calculateSignature(String encodedHeader, String encodedPayload) throws Exception {
        String content = encodedHeader + "." + encodedPayload;
        Mac hmac = Mac.getInstance(ALGORITHM);
        SecretKeySpec secretKeySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        hmac.init(secretKeySpec);
        byte[] signatureBytes = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
    }
} 