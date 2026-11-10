package com.admin.compat;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.controller.ViteConfigController;
import com.admin.entity.ViteConfig;
import com.admin.mapper.ViteConfigMapper;
import com.admin.service.ViteConfigService;
import com.admin.service.impl.ViteConfigServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * round51：/api/v1/config 暴露面收敛。
 *
 * <ul>
 *   <li>/get 在 WebMvcConfig 里排除 JWT（登录前要读 app_name），controller 侧必须收窄成白名单，
 *       并且响应只投影 name/value，不能把 {@link ViteConfig} 实体的 id/time 一起吐出去；</li>
 *   <li>/list 会返回全部配置值，必须要求管理员（{@link RequireRole}）；</li>
 *   <li>非白名单的 name 与不存在的 name 必须回同一句「配置不存在」，不能借错误差异探测 key 是否存在。</li>
 * </ul>
 */
class ConfigExposureTest {

    private ViteConfigMapper viteConfigMapper;
    private ViteConfigServiceImpl realService;
    private ViteConfigService mockService;

    @BeforeEach
    void setUp() {
        viteConfigMapper = mock(ViteConfigMapper.class);
        realService = new ViteConfigServiceImpl();
        ReflectionTestUtils.setField(realService, "baseMapper", viteConfigMapper);

        mockService = mock(ViteConfigService.class);
    }

    private ViteConfigController controllerWith(ViteConfigService service) {
        ViteConfigController controller = new ViteConfigController();
        ReflectionTestUtils.setField(controller, "viteConfigService", service);
        return controller;
    }

    private static Map<String, Object> nameParam(String name) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        return params;
    }

    /**
     * mybatis-plus 3.4.x 的 ServiceImpl#getOne(Wrapper) 可能走 selectOne，也可能走 selectList，
     * 两条路都给出同一行，避免测试绑死具体实现路径。
     */
    private void stubRow(ViteConfig config) {
        when(viteConfigMapper.selectOne(any())).thenReturn(config);
        when(viteConfigMapper.selectList(any())).thenReturn(List.of(config));
    }

    private static ViteConfig row(Long id, String name, String value, Long time) {
        ViteConfig config = new ViteConfig();
        config.setId(id);
        config.setName(name);
        config.setValue(value);
        config.setTime(time);
        return config;
    }

    private static void assertConfigNotFound(R result) {
        assertNotNull(result);
        assertEquals(-1, result.getCode());
        assertEquals("配置不存在", result.getMsg());
    }

    @Test
    @DisplayName("app_name 匿名可取，data 只有 name/value，不含 id/time")
    void appNameIsReadableAnonymouslyWithProjectedPayload() {
        stubRow(row(7L, "app_name", "Flux Panel", 1730000000000L));

        R result = controllerWith(realService).getConfigByName(nameParam("app_name"));

        assertEquals(0, result.getCode(), "白名单内的 app_name 必须可取");
        assertFalse(result.getData() instanceof ViteConfig, "不能直接返回实体");
        assertTrue(result.getData() instanceof Map, "必须是投影 Map");

        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertEquals(Set.of("name", "value"), data.keySet());
        assertEquals("app_name", data.get("name"));
        assertEquals("Flux Panel", data.get("value"));
        assertFalse(data.containsKey("id"), "响应里不能出现 id");
        assertFalse(data.containsKey("time"), "响应里不能出现 time");
    }

    @Test
    @DisplayName("非白名单 name：-1 + 配置不存在，且根本不调 service")
    void nonWhitelistedNameIsRejectedBeforeService() {
        assertConfigNotFound(controllerWith(mockService).getConfigByName(nameParam("ip")));
        // 与实际不存在的 key 完全同一个响应，不能靠错误差异判断 key 是否存在
        assertConfigNotFound(controllerWith(mockService).getConfigByName(nameParam("no_such_key")));

        verifyNoInteractions(mockService);
    }

    @Test
    @DisplayName("缺失/空白 name 仍是「配置名称不能为空」，不是「配置不存在」")
    void missingOrBlankNameKeepsNameRequiredMessage() {
        ViteConfigController controller = controllerWith(mockService);

        R nullBody = controller.getConfigByName(null);
        assertEquals(-1, nullBody.getCode());
        assertEquals("配置名称不能为空", nullBody.getMsg());

        Map<String, Object> noNameKey = new HashMap<>();
        noNameKey.put("other", "value");
        assertEquals("配置名称不能为空", controller.getConfigByName(noNameKey).getMsg());

        assertEquals("配置名称不能为空", controller.getConfigByName(nameParam(null)).getMsg());
        assertEquals("配置名称不能为空", controller.getConfigByName(nameParam("   ")).getMsg());

        verifyNoInteractions(mockService);
    }

    @Test
    @DisplayName("service 投影后 key 集合恰为 {name,value}")
    void serviceProjectsExactlyNameAndValue() {
        stubRow(row(9L, "app_name", "Flux", 1730000000000L));

        R result = realService.getConfigByName("app_name");

        assertEquals(0, result.getCode());
        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertEquals(2, data.size());
        assertEquals(Set.of("name", "value"), data.keySet());
        assertEquals("Flux", data.get("value"));
    }

    @Test
    @DisplayName("/list 上有 @RequireRole（管理员），且保留 @LogAnnotation")
    void listRequiresAdminRole() throws Exception {
        Method list = ViteConfigController.class.getMethod("getConfigs");

        assertNotNull(list.getAnnotation(RequireRole.class), "/list 必须要求管理员");
        assertNotNull(list.getAnnotation(LogAnnotation.class), "/list 的审计日志不能丢");
    }

    @Test
    @DisplayName("/get 保持匿名：不能被加上 @RequireRole")
    void getStaysAnonymous() throws Exception {
        Method get = ViteConfigController.class.getMethod("getConfigByName", Map.class);

        assertNull(get.getAnnotation(RequireRole.class), "/get 是登录前入口，不能挂管理员注解");
    }

    @Test
    @DisplayName("白名单：trim 后精确匹配、区分大小写")
    void whitelistTrimsButIsCaseSensitive() {
        when(mockService.getConfigByName("app_name")).thenReturn(R.ok("Flux"));

        R padded = controllerWith(mockService).getConfigByName(nameParam("  app_name  "));
        assertEquals(0, padded.getCode(), "前后空白应被 trim 后放行");

        verify(mockService).getConfigByName("app_name");

        assertConfigNotFound(controllerWith(mockService).getConfigByName(nameParam("App_Name")));
        assertConfigNotFound(controllerWith(mockService).getConfigByName(nameParam("APP_NAME")));
        assertConfigNotFound(controllerWith(mockService).getConfigByName(nameParam("app_name_x")));

        verify(mockService, never()).getConfigByName("App_Name");
        verify(mockService, never()).getConfigByName("APP_NAME");
        verifyNoMoreInteractions(mockService);
    }

    @Test
    @DisplayName("白名单常量形状：private static final Set<String>")
    void whitelistFieldShape() throws Exception {
        Field field = ViteConfigController.class.getDeclaredField("ANONYMOUS_CONFIG_WHITELIST");

        assertTrue(Modifier.isPrivate(field.getModifiers()), "白名单必须 private");
        assertTrue(Modifier.isStatic(field.getModifiers()), "白名单必须 static");
        assertTrue(Modifier.isFinal(field.getModifiers()), "白名单必须 final");
        assertEquals(Set.class, field.getType());

        field.setAccessible(true);
        assertEquals(Set.of("app_name"), field.get(null));
    }
}
