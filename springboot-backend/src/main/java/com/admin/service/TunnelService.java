package com.admin.service;

import com.admin.common.dto.TunnelDto;
import com.admin.common.dto.TunnelTopologyDto;
import com.admin.common.dto.TunnelUpdateDto;

import com.admin.common.lang.R;
import com.admin.entity.Tunnel;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 隧道服务类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface TunnelService extends IService<Tunnel> {

    /**
     * 创建隧道
     * @param tunnelDto 隧道数据
     * @return 结果
     */
    R createTunnel(TunnelDto tunnelDto);

    /**
     * 获取隧道列表
     * @return 结果
     */
    R getAllTunnels();

    /**
     * 更新隧道（只允许修改名称、流量计费、端口范围）
     * @param tunnelUpdateDto 更新数据
     * @return 结果
     */
    R updateTunnel(TunnelUpdateDto tunnelUpdateDto);

    /**
     * 变更隧道拓扑（入口节点/转发链/出口）。名称、计费与流量倍率仍走 {@link #updateTunnel}。
     * 拓扑变更会重建节点上的 chains 与 service；隧道下存在转发时拒绝，避免入口迁移时
     * forward_port 记录与实际监听脱节。
     */
    R updateTunnelTopology(TunnelTopologyDto tunnelTopologyDto);

    /**
     * 删除隧道
     * @param id 隧道ID
     * @return 结果
     */
    R deleteTunnel(Long id);

    /**
     * 获取用户可用的隧道列表
     * @return 结果
     */
    R userTunnel();

    /**
     * 隧道诊断功能
     * @param tunnelId 隧道ID
     * @return 诊断结果
     */
    R diagnoseTunnel(Long tunnelId);
}
