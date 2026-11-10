package com.admin.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = false)
public class ChainTunnel implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    // 关联的隧道ID
    private Long tunnelId;

    // 记录所选节点的类型： 1是入口节点  2是转发链  3是出口节点
    private Integer chainType;

    // 记录所选节点的ID
    private Long nodeId;

    // 记录节点使用的端口  入口没有次配置为null
    private Integer port;

    // 负载模式 入口没有次配置为null
    private String strategy;
    
    // 只有转发链有次参数 表示是第几条
    private Integer inx;

    // 使用的协议 入口没有次配置为null
    private String protocol;


}
