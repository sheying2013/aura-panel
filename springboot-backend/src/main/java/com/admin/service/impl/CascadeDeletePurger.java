package com.admin.service.impl;

import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.SpeedLimit;
import com.admin.entity.StatisticsFlow;
import com.admin.entity.UserTunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.SpeedLimitService;
import com.admin.service.StatisticsFlowService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * 隧道 / 用户删除时的本地多表级联清理。
 *
 * <p>单独成 bean 的唯一原因：{@code @Transactional} 靠 Spring 代理生效。删表方法若写在
 * TunnelServiceImpl / UserServiceImpl 里再由自己调用，走的是原始对象、事务注解完全失效，
 * 多表删除依旧是各删各的——中途抛异常就留下一半孤儿行。
 *
 * <p>这里只做本地库写，不发起任何节点下发：节点调用由调用方在事务外做，
 * 事务里等网络会把数据库连接一直占住（节点离线时能占满连接池）。
 */
@Service
public class CascadeDeletePurger {

    @Resource
    @Lazy
    ForwardService forwardService;

    @Resource
    @Lazy
    ForwardPortService forwardPortService;

    @Resource
    @Lazy
    UserTunnelService userTunnelService;

    @Resource
    @Lazy
    SpeedLimitService speedLimitService;

    @Resource
    @Lazy
    ChainTunnelService chainTunnelService;

    @Resource
    @Lazy
    StatisticsFlowService statisticsFlowService;

    @Resource
    @Lazy
    TunnelService tunnelService;

    @Resource
    @Lazy
    UserService userService;

    /**
     * 删掉一条隧道的全部库内从属行。
     *
     * <p>speed_limit 此前完全漏删：隧道没了、限速行还在，节点上的限流器也再没人认领。
     * forward_port 只能按 forward_id 反查，所以调用方必须先把该隧道的 forward id 收集进来，
     * 否则这里删完 forward 表后端口行就再也追不回来了。
     *
     * <p>隧道行放在最后删：前面任何一张表抛异常，整个事务回滚，隧道行仍在，用户可以整条重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public void purgeTunnelDatabaseRecords(Long tunnelId, List<Long> forwardIds) {
        forwardService.remove(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId));
        if (forwardIds != null && !forwardIds.isEmpty()) {
            forwardPortService.remove(new QueryWrapper<ForwardPort>().in("forward_id", forwardIds));
        }
        userTunnelService.remove(new QueryWrapper<UserTunnel>().eq("tunnel_id", tunnelId));
        speedLimitService.remove(new QueryWrapper<SpeedLimit>().eq("tunnel_id", tunnelId));
        chainTunnelService.remove(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnelId));
        tunnelService.removeById(tunnelId);
    }

    /**
     * 删掉一个用户的全部库内从属行。
     *
     * <p>forward_port 必须按 forward_id 反查：deleteForward 在节点侧失败时会返回错误并跳过库清理，
     * 这些转发的端口行只能靠这里兜住，否则 user 删了、forward 删了，端口行还在表里当孤儿。
     *
     * <p>用户行放在最后删，理由同 {@link #purgeTunnelDatabaseRecords}。
     */
    @Transactional(rollbackFor = Exception.class)
    public void purgeUserDatabaseRecords(Long userId, List<Long> forwardIds) {
        forwardService.remove(new QueryWrapper<Forward>().eq("user_id", userId));
        if (forwardIds != null && !forwardIds.isEmpty()) {
            forwardPortService.remove(new QueryWrapper<ForwardPort>().in("forward_id", forwardIds));
        }
        userTunnelService.remove(new QueryWrapper<UserTunnel>().eq("user_id", userId));
        statisticsFlowService.remove(new QueryWrapper<StatisticsFlow>().eq("user_id", userId));
        userService.removeById(userId);
    }
}
