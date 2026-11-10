package com.admin.service.impl;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.AuthAttemptLimiter;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.IpUtils;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.Md5Util;
import com.admin.common.utils.QuotaResume;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.admin.entity.*;
import com.admin.mapper.UserMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Resource
    @Lazy
    ForwardService forwardService;

    @Resource
    UserMapper userMapper;

    @Resource
    @Lazy
    TunnelService tunnelService;
    
    @Resource
    @Lazy
    NodeService nodeService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    ViteConfigService viteConfigService;

    @Resource
    StatisticsFlowService statisticsFlowService;

    @Resource
    @Lazy
    ForwardPortService forwardPortService;

    @Resource
    CascadeDeletePurger cascadeDeletePurger;

    @Resource
    ImageCaptchaApplication application;

    /** 登录防暴力破解 / 撞库限流器（5分钟窗口最多失败10次）；非 final 允许单元测试注入模拟时钟。 */
    private AuthAttemptLimiter loginLimiter = new AuthAttemptLimiter();

    private String resolveClientIp() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes) {
                return IpUtils.getIpAddr(((ServletRequestAttributes) attributes).getRequest());
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    @Override
    public R login(LoginDto loginDto) {
        if (loginDto == null || StrUtil.isBlank(loginDto.getUsername()) || StrUtil.isBlank(loginDto.getPassword())) {
            return R.err("账号或密码错误");
        }
        String clientIp = resolveClientIp();
        String username = loginDto.getUsername().trim();
        String limitKey = "user_login|" + clientIp + "|" + username;
        if (!loginLimiter.allow(limitKey)) {
            return R.err("尝试过于频繁，请稍后再试");
        }

        ViteConfig viteConfig = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", "captcha_enabled"));
        if (viteConfig != null && Objects.equals(viteConfig.getValue(), "true")) {
            if (StringUtils.isBlank(loginDto.getCaptchaId())) {
                loginLimiter.recordFailure(limitKey);
                return R.err("验证码校验失败");
            }
            boolean valid = ((SecondaryVerificationApplication) application).secondaryVerification(loginDto.getCaptchaId());
            if (!valid) {
                loginLimiter.recordFailure(limitKey);
                return R.err("验证码校验失败");
            }
        }

        User user = this.getOne(new QueryWrapper<User>().eq("user", loginDto.getUsername()));
        if (user == null) {
            loginLimiter.recordFailure(limitKey);
            return R.err("账号或密码错误");
        }
        // 常量时间比较：equals 在首个不同字符就返回，可被响应时间测出哈希前缀
        if (!Md5Util.constantTimeEquals(user.getPwd(), Md5Util.md5(loginDto.getPassword()))) {
            loginLimiter.recordFailure(limitKey);
            return R.err("账号或密码错误");
        }
        if (Integer.valueOf(0).equals(user.getStatus())) return R.err("账号被停用");
        loginLimiter.reset(limitKey);
        String token = JwtUtil.generateToken(user);
        boolean requirePasswordChange = Objects.equals(loginDto.getUsername(), "admin_user") || Objects.equals(loginDto.getPassword(), "admin_user");
        return R.ok(MapUtil.builder()
                .put("token", token)
                .put("name", user.getUser())
                .put("role_id", user.getRoleId())
                .put("requirePasswordChange", requirePasswordChange)
                .build());
    }

    @Override
    public R logout() {
        Integer userId;
        try {
            userId = JwtUtil.getUserIdFromToken();
        } catch (Exception e) {
            // 没有请求上下文 / token 缺 sub 时 getUserIdFromToken 会直接抛异常。
            // 这里必须报「未登录」，不能落进下面的「退出登录失败」把内部异常文案透出去。
            return R.err("未登录或token已过期");
        }
        if (userId == null) {
            // 不能对 eq("id", null) 静默返回成功，否则前后端都以为已经吊销，实际一行没动。
            return R.err("未登录或token已过期");
        }
        try {
            // 只自增，不做「先读再写」：并发登出/改密时读改写会互相覆盖，版本号就不再单调。
            boolean updated = this.update(new UpdateWrapper<User>()
                    .eq("id", userId)
                    .setSql("token_version = COALESCE(token_version,0) + 1"));
            if (!updated) return R.err("退出登录失败：账号不存在");
            return R.ok();
        } catch (Exception e) {
            return R.err("退出登录失败：" + innermostMessage(e));
        }
    }

    @Override
    public R createUser(UserDto userDto) {
        if (userDto == null) {
            return R.err("用户参数不能为空");
        }
        if (StrUtil.isBlank(userDto.getUser())) {
            return R.err("用户名不能为空");
        }
        if (StrUtil.isBlank(userDto.getPwd())) {
            return R.err("密码不能为空");
        }
        // 预查只负责快速失败给出友好提示，真正防重靠 uk_user_user（见 UserUniqueIndexMigration）
        int count = this.count(new QueryWrapper<User>().eq("user", userDto.getUser().trim()));
        if (count > 0) return R.err("用户名已存在");
        User user = new User();
        BeanUtils.copyProperties(userDto, user);
        user.setPwd(Md5Util.md5(userDto.getPwd()));
        user.setStatus(1);
        user.setRoleId(1);
        long currentTime = System.currentTimeMillis();
        user.setCreatedTime(currentTime);
        user.setUpdatedTime(currentTime);
        try {
            if (this.save(user)) return R.ok();
            return R.err("用户创建失败");
        } catch (Exception e) {
            // 预查与写入之间的窗口：并发双击/重放两条都能通过预查，唯一索引在这里兜住
            if (isUniqueConflict(e)) return R.err("用户名已存在");
            return R.err("用户创建失败：" + innermostMessage(e));
        }
    }

    @Override
    public R getAllUsers() {
        List<User> list = this.list(new QueryWrapper<User>().ne("role_id", 0));
        // 列表接口不回传密码哈希：前端编辑弹窗本来就留空表示「不改密码」，
        // 返回原值等于把可直接撞库的 MD5 哈希发给每一个能调用该接口的人。
        list.forEach(user -> user.setPwd(null));
        return R.ok(list);
    }

    @Override
    public R updateUser(UserUpdateDto userUpdateDto) {
        if (userUpdateDto == null || userUpdateDto.getId() == null) {
            return R.err("用户ID不能为空");
        }
        User user = this.getById(userUpdateDto.getId());
        if (user == null) return R.err("用户不存在");
        if (Integer.valueOf(0).equals(user.getRoleId())) return R.err("请不要作死");
        if (StrUtil.isBlank(userUpdateDto.getUser())) {
            return R.err("用户名不能为空");
        }

        int count = this.count(new QueryWrapper<User>().eq("user", userUpdateDto.getUser().trim()).ne("id", userUpdateDto.getId()));
        if (count > 0) return R.err("用户名已存在");


        boolean passwordChanged = StrUtil.isNotBlank(userUpdateDto.getPwd());
        User updateUser = new User();
        BeanUtils.copyProperties(userUpdateDto, updateUser);
        if (passwordChanged) {
            updateUser.setPwd(Md5Util.md5(userUpdateDto.getPwd()));
        } else {
            updateUser.setPwd(null); // 不更新密码字段
        }
        // 版本号永远不放进 entity：改密时由 UpdateWrapper 的 setSql 原子自增，
        // 避免「读到旧值 → 并发 logout 已 +1 → 这里再写旧值+1」把一次吊销覆盖掉。
        updateUser.setTokenVersion(null);
        updateUser.setUpdatedTime(System.currentTimeMillis());
        try {
            boolean updated;
            if (passwordChanged) {
                updated = this.update(updateUser, new UpdateWrapper<User>()
                        .eq("id", updateUser.getId())
                        .setSql("token_version = COALESCE(token_version,0) + 1"));
            } else {
                updated = this.updateById(updateUser);
            }
            if (!updated) return R.err("用户更新失败：用户不存在");
        } catch (Exception e) {
            // 预查只挡"改名撞已有名字"的常态；并发改名由 uk_user_user 兜底，这里翻译成同一句提示
            if (isUniqueConflict(e)) return R.err("用户名已存在");
            return R.err("用户更新失败：" + innermostMessage(e));
        }

        List<String> notes = new ArrayList<>();
        // round21：停用立即暂停。原实现只改账号 status，转发要等下一次流量上报才被 FlowController 停掉，
        // 节点离线或无流量时已停用账号仍在放行；这里先落库 pause_reason=disabled 再下发。
        if (userUpdateDto.getStatus() != null && userUpdateDto.getStatus() == 0) {
            notes.addAll(forwardService.pauseForDisabledUser(updateUser.getId()));
        }
        // round20：续期/启用后，把因到期或停用被自动暂停的转发重新拉起；
        // 只处理 pause_reason=expiry/disabled 的行，手动暂停不动。
        notes.addAll(forwardService.resumeExpiryDisabledPausedForUser(updateUser.getId()));
        if (!notes.isEmpty()) {
            log.info("用户 {} 更新后转发处置说明：{}", updateUser.getId(), notes);
            return R.ok(notes);
        }
        return R.ok();
    }

    @Override
    public R deleteUser(Long id) {
        if (id == null) {
            return R.err("用户ID不能为空");
        }
        User user = this.getById(id);
        if (user == null) return R.err("用户不存在");
        if (Integer.valueOf(0).equals(user.getRoleId())) return R.err("请不要作死");
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", id));
        // 先取 id：deleteForward 成功会把 forward 行删掉，之后再查就凑不齐 forward_id 了
        List<Long> forwardIds = forwardList.stream().map(Forward::getId).collect(Collectors.toList());
        for (Forward forward : forwardList) {
            forwardService.deleteForward(forward.getId());
        }
        // deleteForward 在节点侧失败时会跳过库清理（只回错误、库不动），这些转发的 forward_port
        // 只能按 forward_id 在这里兜住；只删 forward 表会把端口行留成孤儿
        cascadeDeletePurger.purgeUserDatabaseRecords(id, forwardIds);
        return R.ok();
    }

    @Override
    public R getUserPackageInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        if (userId == null) return R.err("未登录或token已过期");
        User user = this.getById(userId);
        if (user == null) return R.err("用户不存在");
        UserPackageDto.UserInfoDto userInfo = buildUserInfoDto(user);
        List<UserPackageDto.UserTunnelDetailDto> tunnelPermissions;
        if (user.getRoleId() != null && user.getRoleId() == 0) {
            tunnelPermissions = userMapper.getAllTunnelsForAdmin();
        } else {
            tunnelPermissions = userMapper.getUserTunnelDetails(userId);
        }
        List<UserPackageDto.UserForwardDetailDto> forwards = userMapper.getUserForwardDetails(user.getId().intValue());
        fillForwardInIpAndPort(forwards);
        List<StatisticsFlow> statisticsFlows = getLast24HoursFlowStatistics(user.getId());
        UserPackageDto packageDto = new UserPackageDto();
        packageDto.setUserInfo(userInfo);
        packageDto.setTunnelPermissions(tunnelPermissions);
        packageDto.setForwards(forwards);
        packageDto.setStatisticsFlows(statisticsFlows);
        return R.ok(packageDto);
    }

    @Override
    public R updatePassword(ChangePasswordDto changePasswordDto) {
        if (changePasswordDto == null || changePasswordDto.getCurrentPassword() == null
                || changePasswordDto.getNewPassword() == null || changePasswordDto.getConfirmPassword() == null) {
            return R.err("密码参数不能为空");
        }
        Integer userId = JwtUtil.getUserIdFromToken();
        if (userId == null) return R.err("未登录或token已过期");
        User user = this.getById(userId);
        if (user == null) return R.err("用户不存在");
        if (!changePasswordDto.getNewPassword().equals(changePasswordDto.getConfirmPassword())) {
            return R.err("新密码和确认密码不匹配");
        }
        String currentPasswordMd5 = Md5Util.md5(changePasswordDto.getCurrentPassword());
        // 常量时间比较，避免用响应时间逐位试出当前密码的哈希
        if (!Md5Util.constantTimeEquals(user.getPwd(), currentPasswordMd5)) {
            return R.err("当前密码错误");
        }
        if (!user.getUser().equals(changePasswordDto.getNewUsername())) {
            user.setPwd(Md5Util.md5(changePasswordDto.getNewPassword()));
            int count = this.count(new QueryWrapper<User>().eq("user", changePasswordDto.getNewUsername()).ne("id", user.getId()));
            if (count > 0) return R.err("用户名已存在");
        }
        User updateUser = new User();
        updateUser.setId(user.getId());
        updateUser.setUser(changePasswordDto.getNewUsername());
        updateUser.setPwd(Md5Util.md5(changePasswordDto.getNewPassword()));
        updateUser.setUpdatedTime(System.currentTimeMillis());
        // round51：改密与吊销在同一条 UPDATE 里完成。版本号走 setSql 自增而不是「旧值+1」，
        // 否则并发 logout 已经把版本推到旧值+1 时，这里再写旧值+1 会把它盖回去。
        updateUser.setTokenVersion(null);
        try {
            boolean updated = this.update(updateUser, new UpdateWrapper<User>()
                    .eq("id", updateUser.getId())
                    .setSql("token_version = COALESCE(token_version,0) + 1"));
            if (!updated) return R.err("用户不存在");
        } catch (Exception e) {
            if (isUniqueConflict(e)) return R.err("用户名已存在");
            return R.err("用户更新失败：" + innermostMessage(e));
        }
        return R.ok();
    }

    /**
     * 清零流量（账号或用户隧道）。
     *
     * <p>round18：清零之前先按「重置前的总量是否超额」判断这条账号/隧道是否处于配额暂停状态，
     * 清零后把被配额停掉的转发恢复回来（{@link QuotaResume} 里有原因判据与闸门），
     * 否则用户额度已经恢复、业务却永远停在暂停态。
     *
     * @return 全部恢复成功返回 R.ok()；有入口节点未执行返回 R.ok(List&lt;String&gt;)，库里状态已恢复为活跃
     */
    @Override
    public R reset(ResetFlowDto resetFlowDto) {
        if (resetFlowDto == null) {
            return R.err("参数不能为空");
        }
        if (resetFlowDto.getId() == null) {
            return R.err("重置账号id不能为空");
        }
        if (resetFlowDto.getType() == null) {
            return R.err("重置类型不能为空");
        }
        long now = System.currentTimeMillis();
        if (resetFlowDto.getType() == 1){ // 清零账号流量
            User user = this.getById(resetFlowDto.getId());
            if (user == null) return R.err("用户不存在");
            boolean overQuota = QuotaResume.userOverQuota(user.getFlow(), user.getInFlow(), user.getOutFlow());
            user.setInFlow(0L);
            user.setOutFlow(0L);
            this.updateById(user);
            List<String> notes = forwardService.resumeQuotaPausedForUser(user.getId(), overQuota, now);
            if (!notes.isEmpty()) {
                log.warn("用户 {} 流量重置后恢复转发，{} 个入口节点未执行：{}",
                        user.getId(), notes.size(), String.join("；", notes));
                return R.ok(notes);
            }
        } else if (resetFlowDto.getType() == 2) { // 清零隧道流量
            UserTunnel tunnel = userTunnelService.getById(resetFlowDto.getId());
            if (tunnel == null) return R.err("未找到对应的用户隧道权限记录");
            boolean overQuota = QuotaResume.userTunnelOverQuota(tunnel.getFlow(), tunnel.getInFlow(), tunnel.getOutFlow());
            tunnel.setInFlow(0L);
            tunnel.setOutFlow(0L);
            userTunnelService.updateById(tunnel);
            List<String> notes = forwardService.resumeQuotaPausedForUserTunnel(tunnel.getId(), overQuota, now);
            if (!notes.isEmpty()) {
                log.warn("用户隧道 {} 流量重置后恢复转发，{} 个入口节点未执行：{}",
                        tunnel.getId(), notes.size(), String.join("；", notes));
                return R.ok(notes);
            }
        } else {
            return R.err("重置类型只能是 1（账号）或 2（隧道）");
        }
        return R.ok();
    }

    private UserPackageDto.UserInfoDto buildUserInfoDto(User user) {
        UserPackageDto.UserInfoDto userInfo = new UserPackageDto.UserInfoDto();
        userInfo.setId(user.getId());
        userInfo.setUser(user.getUser());
        userInfo.setStatus(user.getStatus());
        userInfo.setFlow(user.getFlow());
        userInfo.setInFlow(user.getInFlow());
        userInfo.setOutFlow(user.getOutFlow());
        userInfo.setNum(user.getNum());
        userInfo.setExpTime(user.getExpTime());
        userInfo.setFlowResetTime(user.getFlowResetTime());
        userInfo.setCreatedTime(user.getCreatedTime());
        userInfo.setUpdatedTime(user.getUpdatedTime());
        return userInfo;
    }

    private List<StatisticsFlow> getLast24HoursFlowStatistics(Long userId) {
        List<StatisticsFlow> recentFlows = statisticsFlowService.list(
                new QueryWrapper<StatisticsFlow>()
                        .eq("user_id", userId)
                        .orderByDesc("id")
                        .last("LIMIT 24")
        );
        List<StatisticsFlow> result = new ArrayList<>(recentFlows);
        if (result.size() < 24) {
            int startHour = java.time.LocalDateTime.now().getHour();
            if (!result.isEmpty()) {
                String lastTime = result.getLast().getTime();
                startHour = parseHour(lastTime) - 1;
            }
            while (result.size() < 24) {
                if (startHour < 0 || startHour > 23) startHour = 23;
                StatisticsFlow emptyFlow = new StatisticsFlow();
                emptyFlow.setUserId(userId);
                emptyFlow.setFlow(0L);
                emptyFlow.setTotalFlow(0L);
                emptyFlow.setTime(String.format("%02d:00", startHour));
                result.add(emptyFlow);
                startHour--;
            }
        }
        return result;
    }

    private int parseHour(String timeStr) {
        if (timeStr != null && timeStr.contains(":")) {
            try {
                String hourPart = timeStr.split(":")[0].trim();
                int hour = Integer.parseInt(hourPart);
                if (hour >= 0 && hour <= 23) {
                    return hour;
                }
            } catch (Exception ignored) {
                // 遇到脏数据或非数字格式安全回退到当前小时，杜绝 500
            }
        }
        return java.time.LocalDateTime.now().getHour();
    }

    private void fillForwardInIpAndPort(List<UserPackageDto.UserForwardDetailDto> forwards) {
        for (UserPackageDto.UserForwardDetailDto forward : forwards) {
            if (forward == null || forward.getTunnelId() == null || forward.getId() == null) continue;
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null) continue;
            
            List<ForwardPort> forwardPorts = forwardPortService.list(
                    new QueryWrapper<ForwardPort>().eq("forward_id", forward.getId())
            );
            if (forwardPorts.isEmpty()) continue;
            
            boolean useTunnelInIp = tunnel.getInIp() != null && !tunnel.getInIp().trim().isEmpty();
            java.util.Set<String> ipPortSet = new java.util.LinkedHashSet<>();
            
            if (useTunnelInIp) {
                // 使用隧道的inIp（求笛卡尔积）
                List<String> ipList = new ArrayList<>();
                List<Integer> portList = new ArrayList<>();
                
                String[] tunnelInIps = tunnel.getInIp().split(",");
                for (String ip : tunnelInIps) {
                    if (ip != null && !ip.trim().isEmpty()) {
                        ipList.add(ip.trim());
                    }
                }
                
                for (ForwardPort forwardPort : forwardPorts) {
                    if (forwardPort.getPort() != null) {
                        portList.add(forwardPort.getPort());
                    }
                }
                
                List<String> uniqueIps = ipList.stream().distinct().toList();
                List<Integer> uniquePorts = portList.stream().distinct().toList();
                
                for (String ip : uniqueIps) {
                    for (Integer port : uniquePorts) {
                        ipPortSet.add(ip + ":" + port);
                    }
                }
                
                if (!uniquePorts.isEmpty()) {
                    forward.setInPort(uniquePorts.getFirst());
                }
            } else {
                // 使用节点的serverIp（一对一，不求笛卡尔积）
                for (ForwardPort forwardPort : forwardPorts) {
                    Node node = nodeService.getById(forwardPort.getNodeId());
                    if (node != null && node.getServerIp() != null && forwardPort.getPort() != null) {
                        ipPortSet.add(node.getServerIp() + ":" + forwardPort.getPort());
                    }
                }
                
                if (!forwardPorts.isEmpty() && forwardPorts.getFirst().getPort() != null) {
                    forward.setInPort(forwardPorts.getFirst().getPort());
                }
            }
            
            if (!ipPortSet.isEmpty()) {
                forward.setInIp(String.join(",", ipPortSet));
            }
        }
    }

    /**
     * 是否是用户名唯一键冲突。
     *
     * <p>SQLite 的 UNIQUE 冲突在 Spring 侧常常不是 {@link DuplicateKeyException}（驱动抛
     * SQLITE_CONSTRAINT_UNIQUE，被归到 UncategorizedSQLException），所以除了异常类型还要看最内层
     * message；与 UserTunnelServiceImpl#isUniqueConflict 保持同一判定口径。
     */
    private boolean isUniqueConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("unique constraint failed")
                    || lower.contains("constraint_unique")
                    || lower.contains("duplicate entry")
                    || lower.contains("duplicate key")) {
                return true;
            }
        }
        return false;
    }

    /** 错误信息取最内层异常，避免只看到 "### Error updating database" 这类外层壳。 */
    private String innermostMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

}
