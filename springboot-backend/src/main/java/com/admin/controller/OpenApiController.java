package com.admin.controller;


import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.admin.common.utils.AuthAttemptLimiter;
import com.admin.common.utils.IpUtils;
import com.admin.common.utils.Md5Util;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.regex.Pattern;

@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/api/v1/open_api")
public class OpenApiController extends BaseController {

    private static final long GIGA = 1024L * 1024L * 1024L;
    private static final Pattern TUNNEL_ID_PATTERN = Pattern.compile("^[0-9]+$");

    /** 进程内失败计数（滑动窗口）；非 final 以便测试注入可控时钟。 */
    private AuthAttemptLimiter limiter = new AuthAttemptLimiter();

    @LogAnnotation
    @RequestMapping(value = "/sub_store", method = {RequestMethod.GET, RequestMethod.POST})
    public Object create(
            @RequestParam("user") String user,
            @RequestParam(value = "pwd", required = false) String pwd,
            @RequestHeader(value = "X-Auth-Pwd", required = false) String pwdHeader,
            @RequestParam(value = "tunnel", required = false, defaultValue = "-1") String tunnel,
            HttpServletRequest request,
            HttpServletResponse response) {
        JSONObject result = new JSONObject();
        result.put("upload", 0);
        result.put("download", 0);
        result.put("total", 0);
        result.put("expire", 0);
        // 撞库限流：key 精确到 IP + 账号，先于任何查库动作
        String limitKey = "open_api|" + IpUtils.getIpAddr(request) + "|" + user;
        if (!limiter.allow(limitKey)) {
            return R.err("尝试过于频繁，请稍后再试");
        }
        // 校验 user 是否为空
        if (user == null || user.isEmpty()) {
            return R.err("用户不能为空");
        }
        // 密码优先取 X-Auth-Pwd 头：走 URL query 会落进 nginx/代理的访问日志
        boolean pwdInHeader = pwdHeader != null && !pwdHeader.trim().isEmpty();
        String effectivePwd = pwdInHeader ? pwdHeader : pwd;
        if (effectivePwd == null || effectivePwd.isEmpty()) {
            return R.err("密码不能为空");
        }
        if (!pwdInHeader) {
            log.warn("open_api 密码经 URL query 传输，建议改用 X-Auth-Pwd 头；user={}", user);
        }

        User userInfo = userService.getOne(new QueryWrapper<User>().eq("user", user));
        if (userInfo == null) {
            limiter.recordFailure(limitKey);
            return R.err("鉴权失败");
        }

        String pwdMd5 = Md5Util.md5(effectivePwd);
        // 常量时间比较：String.equals 的短路比较会一位位泄露哈希前缀
        if (!Md5Util.constantTimeEquals(pwdMd5, userInfo.getPwd())) {
            limiter.recordFailure(limitKey);
            return R.err("鉴权失败");
        }
        if (Objects.equals(userInfo.getStatus(), 0)) {
            limiter.recordFailure(limitKey);
            return R.err("账号已停用");
        }
        limiter.reset(limitKey);

        String headerValue;

        if ("-1".equals(tunnel)) {
            headerValue = buildSubscriptionHeader(
                    nz(userInfo.getInFlow()),
                    nz(userInfo.getOutFlow()),
                    gigaBytes(userInfo.getFlow()),
                    epochSeconds(userInfo.getExpTime())
            );
        } else {
            if (tunnel == null || !TUNNEL_ID_PATTERN.matcher(tunnel.trim()).matches()) {
                return R.err("隧道不存在");
            }
            UserTunnel tunnelInfo = userTunnelService.getById(tunnel.trim());
            if (tunnelInfo == null) return R.err("隧道不存在");
            if (!String.valueOf(tunnelInfo.getUserId()).equals(String.valueOf(userInfo.getId()))) return R.err("隧道不存在");
            if (Objects.equals(tunnelInfo.getStatus(), 0)) {
                return R.err("用户隧道已停用");
            }
            headerValue = buildSubscriptionHeader(
                    nz(tunnelInfo.getInFlow()),
                    nz(tunnelInfo.getOutFlow()),
                    gigaBytes(tunnelInfo.getFlow()),
                    epochSeconds(tunnelInfo.getExpTime())
            );
        }

        response.setHeader("subscription-userinfo", headerValue);
        return headerValue;
    }

    private String buildSubscriptionHeader(long uploadBytes, long downloadBytes, long totalBytes, long expireSeconds) {
        return String.format("upload=%d; download=%d; total=%d; expire=%d", uploadBytes, downloadBytes, totalBytes, expireSeconds);
    }

    /** flow 单位为 GB（Long）→ 字节；null/非正按 0，溢出饱和到 Long.MAX_VALUE 而不是回绕成负数。 */
    private static long gigaBytes(Long flow) {
        if (flow == null || flow <= 0) {
            return 0L;
        }
        return flow > Long.MAX_VALUE / GIGA ? Long.MAX_VALUE : flow * GIGA;
    }

    /** expTime 为毫秒时间戳（Long）→ 秒；null/非正按 0。 */
    private static long epochSeconds(Long expTime) {
        return (expTime == null || expTime <= 0) ? 0L : expTime / 1000L;
    }

    /** 流量字段可能为 null，先归零再做 long 运算，避免拆箱 NPE。 */
    private static long nz(Long value) {
        return value == null ? 0L : value;
    }


}
