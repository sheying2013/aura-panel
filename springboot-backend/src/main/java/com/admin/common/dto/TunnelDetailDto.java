package com.admin.common.dto;

import com.admin.entity.ChainTunnel;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 隧道详情DTO - 包含节点配置信息
 */
@Data
public class TunnelDetailDto {
    
    private Long id;
    
    private String name;
    
    private Integer type; // 1: 端口转发, 2: 隧道转发
    
    private Integer flow; // 1: 单向, 2: 双向
    
    private BigDecimal trafficRatio;
    
    private Integer status;
    
    private Long createdTime;
    
    private Long updatedTime;

    private String inIp;
    
    // 入口节点列表
    private List<ChainTunnel> inNodeId = new ArrayList<>();
    
    // 转发链节点列表（二维数组结构）
    private List<List<ChainTunnel>> chainNodes = new ArrayList<>();
    
    // 出口节点列表
    private List<ChainTunnel> outNodeId = new ArrayList<>();
}

