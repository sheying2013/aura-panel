package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.controller.ViteConfigController;
import com.admin.service.ViteConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 缺陷8：/api/v1/config/get 缺 name 参数时曾经直接 NPE，再被 GlobalExceptionHandler 用
 * `e.getMessage()` 原样回给调用方，泄漏
 * "Cannot invoke \"Object.toString()\" because the return value of \"java.util.Map.get(Object)\" is null"。
 *
 * <p>匿名白名单见 {@code ViteConfigController.ANONYMOUS_CONFIG_WHITELIST}：只放行 app_name
 * （前端 App.tsx / site.ts 在登录前就要读它），这里只覆盖 controller 侧的空参数拦截。</p>
 */
class ConfigGetParamValidationTest {

    private ViteConfigService viteConfigService;
    private ViteConfigController controller;

    @BeforeEach
    void setUp() {
        viteConfigService = mock(ViteConfigService.class);
        controller = new ViteConfigController();
        ReflectionTestUtils.setField(controller, "viteConfigService", viteConfigService);
    }

    private void assertNameRequired(R result) {
        assertNotNull(result);
        assertEquals(-1, result.getCode(), "缺 name 应返回业务错误码而不是 500/NPE");
        assertEquals("配置名称不能为空", result.getMsg());
        assertFalse(String.valueOf(result.getMsg()).contains("Cannot invoke"), "不能把 NPE 原始文本泄给调用方");
    }

    @Test
    @DisplayName("空 body（null）→ 正常业务错误，不 NPE")
    void getWithNullBodyReturnsBusinessError() {
        assertNameRequired(controller.getConfigByName(null));
        verifyNoInteractions(viteConfigService);
    }

    @Test
    @DisplayName("body 里没有 name 键 → 正常业务错误，不 NPE")
    void getWithoutNameKeyReturnsBusinessError() {
        Map<String, Object> params = new HashMap<>();
        params.put("other", "value");

        assertNameRequired(controller.getConfigByName(params));
        verifyNoInteractions(viteConfigService);
    }

    @Test
    @DisplayName("name 为 null 或纯空白 → 同样挡住")
    void getWithBlankNameReturnsBusinessError() {
        Map<String, Object> nullName = new HashMap<>();
        nullName.put("name", null);
        assertNameRequired(controller.getConfigByName(nullName));

        Map<String, Object> blankName = new HashMap<>();
        blankName.put("name", "   ");
        assertNameRequired(controller.getConfigByName(blankName));

        verifyNoInteractions(viteConfigService);
    }

    @Test
    @DisplayName("白名单内的 name（前端 site.ts 的真实调用形状）→ 透传到 service")
    void getWithNameDelegatesToService() {
        when(viteConfigService.getConfigByName("app_name")).thenReturn(R.ok("panel.example.com"));

        Map<String, Object> params = new HashMap<>();
        params.put("name", "app_name");

        R result = controller.getConfigByName(params);

        assertEquals(0, result.getCode());
        assertEquals("panel.example.com", result.getData());
        verify(viteConfigService).getConfigByName("app_name");
    }

    @Test
    @DisplayName("update-single 同类空参数（缺 name / 缺 value）也不再 NPE")
    void updateSingleWithMissingParamsReturnsBusinessError() {
        assertNameRequired(controller.updateConfig(null));

        Map<String, Object> noName = new HashMap<>();
        noName.put("value", "x");
        assertNameRequired(controller.updateConfig(noName));

        Map<String, Object> noValue = new HashMap<>();
        noValue.put("name", "ip");
        R result = controller.updateConfig(noValue);
        assertEquals(-1, result.getCode());
        assertEquals("配置值不能为空", result.getMsg());

        verifyNoInteractions(viteConfigService);
    }

    @Test
    @DisplayName("update-single 带全参数 → 透传 name/value")
    void updateSingleWithFullParamsDelegates() {
        when(viteConfigService.updateConfig(anyString(), anyString())).thenReturn(R.ok());

        Map<String, Object> params = new HashMap<>();
        params.put("name", "ip");
        params.put("value", "1.2.3.4");

        R result = controller.updateConfig(params);

        assertEquals(0, result.getCode());
        verify(viteConfigService).updateConfig("ip", "1.2.3.4");
    }
}
