package com.admin.config;

import com.admin.common.interceptor.JwtInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;


@Configuration
@EnableWebMvc
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * JWT拦截器由 Spring 单例注入（round51）：拦截器现在要查库校验账号状态与 token 版本，
     * 不能再 new 一个没有 userMapper 的裸对象，也避免出现第二个实例。
     */
    @Resource
    private JwtInterceptor jwtInterceptor;

    /**
     * 允许跨域的源。默认设为通配 *，兼容外网域名反向代理、TLS 终结及多端口前后端分离访问；
     * 生产环境如需严格收窄，可通过环境变量 APP_CORS_ALLOWED_ORIGIN_PATTERNS 显式指定允许的 Origin 列表。
     */
    @Value("${app.cors.allowed-origin-patterns:*}")
    private String[] allowedOriginPatterns;

    /**
     * CORS 只在这里定义一次：以前 CorsFilter 与 addCorsMappings 各配一遍，两边不一致时以哪份为准
     * 取决于过滤器与 MVC 的先后顺序，排查困难。统一走过滤器后，预检请求也在拦截器之前被应答。
     */
    private CorsConfiguration buildConfig() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOriginPatterns(Arrays.asList(allowedOriginPatterns));
        corsConfiguration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        corsConfiguration.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "X-Requested-With", "X-Node-Secret", "X-Secret", "X-Auth-Pwd"));
        corsConfiguration.setExposedHeaders(Collections.singletonList("Authorization"));
        corsConfiguration.setMaxAge(3600L);
        return corsConfiguration;
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfig());
        return new CorsFilter(source);
    }

    /**
     * 添加JWT拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 添加JWT拦截器，不拦截登录接口
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/flow/**")
                .excludePathPatterns("/api/v1/open_api/**")
                // 唯一需要匿名读取配置的入口：前端登录前要取 app_name 渲染站点名。
                // 范围由 ViteConfigController.ANONYMOUS_CONFIG_WHITELIST 兜底（只放行 app_name）。
                .excludePathPatterns("/api/v1/config/get")
                .excludePathPatterns("/api/v1/user/login")
                .excludePathPatterns("/api/v1/captcha/**");
    }
}
