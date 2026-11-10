package com.admin.common.interceptor;


import com.admin.common.exception.UnauthorizedException;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * JWT拦截器，验证用户是否登录
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Resource
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = request.getHeader("Authorization");

        if (!StringUtils.hasText(token)) {
            throw new UnauthorizedException("未登录或token已过期");
        }


        if (!JwtUtil.validateToken(token)) {
            throw new UnauthorizedException("无效的token或token已过期");
        }

        // round51：签名与 exp 只能证明 token 是自己签的，证明不了它还该被信任——改密/登出/停用之后
        // 旧 token 仍在 90 天有效期内。这里回库比一次账号状态与 token 版本号，两者任一不符立即失效。
        User user = userMapper.selectById(JwtUtil.getUserIdFromToken(token));
        if (user == null || user.getStatus() == null || user.getStatus() == 0) {
            throw new UnauthorizedException("账号不存在或已停用");
        }
        int dbVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (JwtUtil.getTokenVersion(token) != dbVersion) {
            throw new UnauthorizedException("登录状态已失效，请重新登录");
        }

        // 角色同样回库比对：@RequireRole 只读 token 里的 role_id，若账号被降权（库里改了 role_id）
        // 而旧 token 仍带 0，管理员接口会一直对他开放到 token 过期。账号角色变更等同于吊销。
        Integer tokenRole = JwtUtil.getRoleIdFromToken(token);
        if (user.getRoleId() == null || tokenRole == null || !user.getRoleId().equals(tokenRole)) {
            throw new UnauthorizedException("账号权限已变更，请重新登录");
        }

        return true;
    }
} 
