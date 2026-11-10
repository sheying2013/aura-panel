package com.admin.common.task;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.PauseReason;
import com.admin.common.utils.QuotaResume;
import com.admin.entity.*;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Configuration
@EnableScheduling
public class ResetFlowAsync {

    /**
     * 到期扫描的重入保护：cron 30 秒一次，单次扫描若因为节点 RPC 慢而超时，
     * 下一拍不能并发跑同一批用户/隧道（会重复下发暂停并重复写 pause_reason）。
     */
    private final AtomicBoolean expirySweeping = new AtomicBoolean(false);

    @Resource
    UserService userService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    ForwardService forwardService;

    @Resource
    TunnelService tunnelService;

    @Resource
    ChainTunnelService chainTunnelService;

    /**
     * 每天0点执行流量重置任务
     * 查询出用户和隧道的重置流量日期是今天的数据，将上下流量重置为0
     * 考虑当月是29、30天，但是选择是31的这种边界情况
     * 
     * 并发安全说明：
     * - 使用setSql()进行原子SQL更新，只更新流量字段(in_flow, out_flow)
     * - 不会影响DelayQueueManager的到期任务对status等其他字段的更新
     * - 避免了并发修改导致的数据覆盖问题
     */
    @Scheduled(cron = "5 0 0 * * ?")
    public void reset_flow(){
        log.info("开始执行流量重置任务");
        
        try {
            // 获取当前日期信息
            LocalDate today = LocalDate.now();
            int currentDay = today.getDayOfMonth(); // 当前是几号
            int lastDayOfMonth = today.lengthOfMonth(); // 当月最后一天
            
            log.info("当前日期: {}, 当月第{}天, 当月最后一天: {}", today, currentDay, lastDayOfMonth);
            
            // 重置用户流量
            resetUserFlow(currentDay, lastDayOfMonth);
            
            // 重置用户隧道流量
            resetUserTunnelFlow(currentDay, lastDayOfMonth);
            
            log.info("流量重置任务执行完成");


            // 处理过期账号
            user();

            // 处理过期隧道
            userTunnel();

            log.info("到期任务执行完成");
            
        } catch (Exception e) {
            log.info("定时任务执行失败", e);
        }
    }
    
    /**
     * 到期/停用扫描（round21）。
     *
     * <p>原实现只挂在每日 00:00:05 的 reset_flow 上：用户到期后最多要等近 24 小时才被暂停，
     * 期间账号已过期、转发仍在放行流量，等下一次流量上报才被 FlowController 兜住——
     * 节点 5 秒一报还算快，但节点离线/无流量时就是纯敞口。
     *
     * <p>这里把 user() / userTunnel() 抽成 30 秒一次的独立扫描：两方法本身幂等
     * （只查 status=1 且 exp_time &lt; now 的行），重复执行不会二次暂停已处理过的转发。
     * reset_flow 里保留原调用，兼容原有语义（重置流量后立刻处理到期）。
     */
    @Scheduled(cron = "0/30 * * * * ?")
    public void expirySweep() {
        if (!expirySweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            user();
        } catch (Exception e) {
            log.warn("到期用户扫描失败", e);
        }
        try {
            userTunnel();
        } catch (Exception e) {
            log.warn("到期用户隧道扫描失败", e);
        } finally {
            expirySweeping.set(false);
        }
    }

    /**
     * 重置用户流量
     * @param currentDay 当前日期（几号）
     * @param lastDayOfMonth 当月最后一天
     */
    private void resetUserFlow(int currentDay, int lastDayOfMonth) {
        try {
            // flowResetTime字段存储的是0-31的数字，0表示不重置，1-31表示每月第几号重置
            // 构建查询条件：重置日期等于今天，或者重置日期大于当月最大天数且今天是月末
            // 排除flowResetTime为0的记录（不重置）
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.ne("flow_reset_time", 0); // 排除不重置的用户
            
            if (currentDay == lastDayOfMonth) {
                // 如果今天是月末，查询重置日期等于今天或者大于当月最大天数的记录
                // 例如：当月30天，但用户设置31号重置，则在30号执行重置
                queryWrapper.and(wrapper -> wrapper.eq("flow_reset_time", currentDay)
                                                  .or().gt("flow_reset_time", lastDayOfMonth));
            } else {
                // 否则只查询重置日期等于今天的记录
                queryWrapper.eq("flow_reset_time", currentDay);
            }
            
            // 查询需要重置的用户
            List<User> usersToReset = userService.list(queryWrapper);
            
            if (usersToReset.isEmpty()) {
                log.info("没有需要重置流量的用户");
                return;
            }
            
            log.info("找到{}个需要重置流量的用户", usersToReset.size());
            
            // 批量重置用户流量 - 使用SQL原子操作避免与到期任务的并发冲突
            for (User user : usersToReset) {
                // 清零前先判断是否处于「配额暂停」（setSql 只改库，本地实体仍是重置前的值）
                boolean overQuotaBefore = QuotaResume.userOverQuota(user.getFlow(), user.getInFlow(), user.getOutFlow());
                UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("id", user.getId())
                           .setSql("in_flow = 0, out_flow = 0"); // 使用SQL原子操作，只更新流量字段
                
                boolean success = userService.update(null, updateWrapper);
                if (success) {
                    log.info("用户[ID: {}, 用户名: {}]流量重置成功，重置日期: 每月{}号", 
                           user.getId(), user.getUser(), user.getFlowResetTime());
                    resumePausedForUser(user, overQuotaBefore);
                } else {
                    log.info("用户[ID: {}, 用户名: {}]流量重置失败", user.getId(), user.getUser());
                }
            }
            
        } catch (Exception e) {
            log.info("重置用户流量失败", e);
        }
    }
    
    /**
     * 重置用户隧道流量
     * @param currentDay 当前日期（几号）
     * @param lastDayOfMonth 当月最后一天
     */
    private void resetUserTunnelFlow(int currentDay, int lastDayOfMonth) {
        try {
            // flowResetTime字段存储的是0-31的数字，0表示不重置，1-31表示每月第几号重置
            // 构建查询条件：重置日期等于今天，或者重置日期大于当月最大天数且今天是月末
            // 排除flowResetTime为0的记录（不重置）
            QueryWrapper<UserTunnel> queryWrapper = new QueryWrapper<>();
            queryWrapper.ne("flow_reset_time", 0); // 排除不重置的用户隧道
            
            if (currentDay == lastDayOfMonth) {
                // 如果今天是月末，查询重置日期等于今天或者大于当月最大天数的记录
                // 例如：当月30天，但用户设置31号重置，则在30号执行重置
                queryWrapper.and(wrapper -> wrapper.eq("flow_reset_time", currentDay)
                                                  .or().gt("flow_reset_time", lastDayOfMonth));
            } else {
                // 否则只查询重置日期等于今天的记录
                queryWrapper.eq("flow_reset_time", currentDay);
            }
            
            // 查询需要重置的用户隧道
            List<UserTunnel> userTunnelsToReset = userTunnelService.list(queryWrapper);
            
            if (userTunnelsToReset.isEmpty()) {
                log.info("没有需要重置流量的用户隧道");
                return;
            }
            
            log.info("找到{}个需要重置流量的用户隧道", userTunnelsToReset.size());
            
            // 批量重置用户隧道流量 - 使用SQL原子操作避免与到期任务的并发冲突
            for (UserTunnel userTunnel : userTunnelsToReset) {
                // 同上：清零前先取「是否超额」，用于决定重置后要不要恢复被配额暂停的转发
                boolean overQuotaBefore = QuotaResume.userTunnelOverQuota(userTunnel.getFlow(),
                        userTunnel.getInFlow(), userTunnel.getOutFlow());
                UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("id", userTunnel.getId())
                           .setSql("in_flow = 0, out_flow = 0"); // 使用SQL原子操作，只更新流量字段
                
                boolean success = userTunnelService.update(null, updateWrapper);
                if (success) {
                    log.info("用户隧道[ID: {}, 用户ID: {}, 隧道ID: {}]流量重置成功，重置日期: 每月{}号", 
                           userTunnel.getId(), userTunnel.getUserId(), userTunnel.getTunnelId(), userTunnel.getFlowResetTime());
                    resumePausedForUserTunnel(userTunnel, overQuotaBefore);
                } else {
                    log.info("用户隧道[ID: {}, 用户ID: {}, 隧道ID: {}]流量重置失败",
                            userTunnel.getId(), userTunnel.getUserId(), userTunnel.getTunnelId());
                }
            }
            
        } catch (Exception e) {
            log.info("重置用户隧道流量失败", e);
        }
    }


    public void user(){
        // 查询过期用户
        List<User> user_list = userService.list(new QueryWrapper<User>().ne("role_id", 0).eq("status", 1).isNotNull("exp_time").lt("exp_time", new Date().getTime()));
        for (User user : user_list) {
            // 查询对应转发
            List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", user.getId()).eq("status", 1));
            for (Forward forward : forwardList) {

                UserTunnel userTunnel = userTunnelService.getOne(new QueryWrapper<UserTunnel>().eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()));
                pauseForwardForExpiry(forward, userTunnel);
            }
            user.setStatus(0);
            userService.updateById(user);
        }
    }


    public void userTunnel(){
        // 查询过期隧道
        List<UserTunnel> user_tunnel_list = userTunnelService.list(new QueryWrapper<UserTunnel>().eq("status", 1).isNotNull("exp_time").lt("exp_time", new Date().getTime()));
        // 查询对应转发
        for (UserTunnel userTunnel : user_tunnel_list) {
            List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", userTunnel.getTunnelId()).eq("user_id", userTunnel.getUserId()).eq("status", 1));
            for (Forward forward : forwardList) {

                pauseForwardForExpiry(forward, userTunnel);
            }


            userTunnel.setStatus(0);
            userTunnelService.updateById(userTunnel);
        }
    }




    /**
     * 流量重置成功后，把「因配额暂停」的转发恢复回来（round18）。
     * 恢复动作本身以库为准（先落库再下发），节点离线只会返回说明，由重连对账兜底。
     */
    private void resumePausedForUser(User user, boolean overQuotaBefore) {
        try {
            List<String> notes = forwardService.resumeQuotaPausedForUser(user.getId(), overQuotaBefore, System.currentTimeMillis());
            if (!notes.isEmpty()) {
                log.warn("用户[ID: {}, 用户名: {}]流量重置后恢复转发，{} 个入口节点未执行：{}",
                        user.getId(), user.getUser(), notes.size(), String.join("；", notes));
            }
        } catch (Exception e) {
            log.warn("用户[ID: {}]流量重置后恢复转发失败", user.getId(), e);
        }
    }

    /**
     * 用户隧道维度的配额暂停恢复，语义同 {@link #resumePausedForUser}。
     */
    private void resumePausedForUserTunnel(UserTunnel userTunnel, boolean overQuotaBefore) {
        try {
            List<String> notes = forwardService.resumeQuotaPausedForUserTunnel(userTunnel.getId(), overQuotaBefore, System.currentTimeMillis());
            if (!notes.isEmpty()) {
                log.warn("用户隧道[ID: {}]流量重置后恢复转发，{} 个入口节点未执行：{}",
                        userTunnel.getId(), notes.size(), String.join("；", notes));
            }
        } catch (Exception e) {
            log.warn("用户隧道[ID: {}]流量重置后恢复转发失败", userTunnel.getId(), e);
        }
    }

    /**
     * 到期/停用自动暂停的单条转发处理（与 FlowController.pauseService、ForwardServiceImpl.applyStatusChange 同一口径）：
     * 先把 status=0 落库，再逐入口下发 PauseService。节点离线/失败只收集说明写日志，
     * 不阻断落库、也不回滚库——未确认的入口由节点重连时 WebSocketServer.pauseForwardsExpectingPaused 补发。
     * <p>
     * 顺序不能再倒过来：若先下发后落库，中途异常会让「节点已停、库里仍是 status=1」，
     * 而 ForwardServiceImpl.resyncForwardServices 对 status=1 的服务会主动补发 ResumeService，
     * 等于把到期用户重新放开。
     *
     * @param userTunnel 该转发对应的用户隧道，可能为 null；服务名按全局口径退化为 0
     */
    private void pauseForwardForExpiry(Forward forward, UserTunnel userTunnel) {
        forward.setStatus(0);
        // round19：到期自动暂停要留痕，否则续费后无人知道这些转发是被到期停的
        forward.setPauseReason(PauseReason.EXPIRY);
        forward.setUpdatedTime(System.currentTimeMillis());
        forwardService.updateById(forward);

        int userTunnelId = (userTunnel != null && userTunnel.getId() != null) ? userTunnel.getId() : 0;
        String name = buildServiceName(forward.getId(), forward.getUserId(), userTunnelId);
        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", forward.getTunnelId()).eq("chain_type", 1));
        List<String> notes = new ArrayList<>();
        for (ChainTunnel chainTunnel : chainTunnels) {
            if (chainTunnel == null || chainTunnel.getNodeId() == null) {
                continue;
            }
            GostDto gostDto = pauseNodeService(chainTunnel.getNodeId(), name);
            if (gostDto == null || !Objects.equals(gostDto.getMsg(), "OK")) {
                notes.add("节点" + chainTunnel.getNodeId() + "：" + (gostDto == null ? "无响应" : gostDto.getMsg()));
            }
        }
        if (!notes.isEmpty()) {
            log.warn("转发 {} 已按到期暂停落库，{} 个入口节点未确认：{}，等待节点重连后补偿",
                    forward.getId(), notes.size(), String.join("；", notes));
        }
    }

    /**
     * 停节点上的服务，抽出来便于测试替换 gost 调用（与 FlowController 的测试缝同构）。
     */
    protected GostDto pauseNodeService(Long nodeId, String name) {
        return GostUtil.PauseAndResumeService(nodeId, name, "PauseService");
    }

    private String buildServiceName(Long forwardId, Integer userId, Integer userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }
}
