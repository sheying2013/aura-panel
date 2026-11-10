package com.admin.controller;


import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * <p>
 *  网站配置控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-07-24
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/config")
public class ViteConfigController extends BaseController {

    /**
     * 匿名可读配置白名单。
     *
     * <p>/api/v1/config/get 在 WebMvcConfig 里被排除在 JWT 之外：前端 App.tsx 与 config/site.ts
     * 在登录前就要读取 app_name 渲染站点名。因此「匿名可达」本身不能去掉，只能在这里收窄范围 ——
     * 仅有 app_name 放行，其它 name 一律返回同一句「配置不存在」，
     * 既不能匿名枚举配置项，也不能通过差异化错误判断某个 key 是否存在。</p>
     *
     * <p>匹配规则：trim 后精确匹配、区分大小写（配置名是固定的小写下划线风格，不放宽成忽略大小写/模糊匹配）。</p>
     */
    private static final Set<String> ANONYMOUS_CONFIG_WHITELIST = Set.of("app_name");

    /**
     * 获取所有网站配置
     * 需要管理员权限：/list 会返回全部配置值（含 ip、验证码开关等），只有后台配置页使用
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R getConfigs() {
        return viteConfigService.getConfigs();
    }

    /**
     * 根据配置名获取配置值（响应只含 name/value）
     * 匿名可访问，但仅限白名单内的 key（见 {@link #ANONYMOUS_CONFIG_WHITELIST}），目前只有 app_name
     */
    @LogAnnotation
    @PostMapping("/get")
    public R getConfigByName(@RequestBody(required = false) Map<String, Object> params) {
        // 之前直接 params.get("name").toString()：缺 name 或空 body 时抛 NPE，
        // 再被 GlobalExceptionHandler 以 e.getMessage() 原样回给调用方，泄漏内部实现细节。
        Object name = params == null ? null : params.get("name");
        if (name == null || name.toString().trim().isEmpty()) {
            return R.err("配置名称不能为空");
        }
        String configName = name.toString().trim();
        if (!ANONYMOUS_CONFIG_WHITELIST.contains(configName)) {
            return R.err("配置不存在");
        }
        return viteConfigService.getConfigByName(configName);
    }

    /**
     * 批量更新网站配置
     * 需要管理员权限
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R updateConfigs(@RequestBody Map<String, String> configMap) {
        return viteConfigService.updateConfigs(configMap);
    }

    /**
     * 更新单个配置项
     * 需要管理员权限
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/update-single")
    public R updateConfig(@RequestBody(required = false) Map<String, Object> params) {
        // 与 /get 同一类空指针：缺 name/value 时先把异常挡在 controller 层
        Object name = params == null ? null : params.get("name");
        Object value = params == null ? null : params.get("value");
        if (name == null || name.toString().trim().isEmpty()) {
            return R.err("配置名称不能为空");
        }
        if (value == null) {
            return R.err("配置值不能为空");
        }
        return viteConfigService.updateConfig(name.toString(), value.toString());
    }

}
