package com.admin.compat;

import com.admin.config.WebMvcConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.CorsFilter;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebMvcConfig CORS 默认放行行为与显式收窄回归测试。
 *
 * <p>背景：Nginx 在 443 终结 TLS 后向内网 HTTP 6365 反代时，若后端未解析 X-Forwarded-Proto，
 * Spring CorsUtils.isSameOrigin 会把 Origin: https://panel.example.test 与 http://...:6365 判定为跨源；
 * 默认若仅允许 localhost/127.0.0.1，会导致所有外网域名/公网 IP 的 POST 接口返回 403 Invalid CORS request。
 */
public class WebMvcConfigCorsTest {

    @Test
    void defaultAllowedOriginPatternsAnnotationIsWildcard() throws Exception {
        Field field = WebMvcConfig.class.getDeclaredField("allowedOriginPatterns");
        Value valueAnnotation = field.getAnnotation(Value.class);
        assertNotNull(valueAnnotation, "allowedOriginPatterns 必须保留 @Value 配置绑定");
        assertEquals("${app.cors.allowed-origin-patterns:*}", valueAnnotation.value(),
                "默认 CORS 模式必须兼容公网域名反代与 TLS 终结场景");
    }

    @Test
    void defaultCorsFilterAllowsHttpsOriginBehindHttpReverseProxy() throws Exception {
        WebMvcConfig config = new WebMvcConfig();
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", new String[]{"*"});
        CorsFilter filter = config.corsFilter();

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/user/package");
        req.setScheme("http");
        req.setServerName("panel.example.test");
        req.setServerPort(6365);
        req.addHeader("Origin", "https://panel.example.test");
        req.addHeader("Authorization", "dummy-jwt");

        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus(), "默认 CORS 策略必须放行外网 HTTPS 反代请求，不能误拦截为 403");
        assertNotNull(chain.getRequest(), "通过 CORS 校验后请求应继续传递给下游链路");
        assertEquals("https://panel.example.test", resp.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void defaultCorsFilterAllowsOptionsPreflightWithAuthHeaders() throws Exception {
        WebMvcConfig config = new WebMvcConfig();
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", new String[]{"*"});
        CorsFilter filter = config.corsFilter();

        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/v1/forward/list");
        req.setScheme("http");
        req.setServerName("192.0.2.10");
        req.setServerPort(6365);
        req.addHeader("Origin", "http://192.0.2.10:6366");
        req.addHeader("Access-Control-Request-Method", "POST");
        req.addHeader("Access-Control-Request-Headers", "Authorization, Content-Type");

        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertEquals("http://192.0.2.10:6366", resp.getHeader("Access-Control-Allow-Origin"));
        assertTrue(resp.getHeader("Access-Control-Allow-Headers").contains("Authorization"));
    }

    @Test
    void explicitCorsWhitelistBlocksUnlistedOriginsWith403() throws Exception {
        WebMvcConfig config = new WebMvcConfig();
        ReflectionTestUtils.setField(config, "allowedOriginPatterns",
                new String[]{"https://panel.example.test"});
        CorsFilter filter = config.corsFilter();

        MockHttpServletRequest allowedReq = new MockHttpServletRequest("POST", "/api/v1/tunnel/list");
        allowedReq.setScheme("http");
        allowedReq.setServerName("panel.example.test");
        allowedReq.setServerPort(6365);
        allowedReq.addHeader("Origin", "https://panel.example.test");
        MockHttpServletResponse allowedResp = new MockHttpServletResponse();
        MockFilterChain allowedChain = new MockFilterChain();
        filter.doFilter(allowedReq, allowedResp, allowedChain);
        assertEquals(200, allowedResp.getStatus());
        assertNotNull(allowedChain.getRequest());

        MockHttpServletRequest blockedReq = new MockHttpServletRequest("POST", "/api/v1/tunnel/list");
        blockedReq.setScheme("http");
        blockedReq.setServerName("panel.example.test");
        blockedReq.setServerPort(6365);
        blockedReq.addHeader("Origin", "https://untrusted.example.com");
        MockHttpServletResponse blockedResp = new MockHttpServletResponse();
        MockFilterChain blockedChain = new MockFilterChain();
        filter.doFilter(blockedReq, blockedResp, blockedChain);
        assertEquals(403, blockedResp.getStatus());
        assertNull(blockedChain.getRequest(), "显式白名单外 Origin 被拒绝时不能进入业务链路");
        assertEquals("Invalid CORS request", blockedResp.getContentAsString());
    }
}
