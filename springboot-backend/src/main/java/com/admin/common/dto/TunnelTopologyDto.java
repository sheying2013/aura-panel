package com.admin.common.dto;

import com.admin.entity.ChainTunnel;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

/**
 * 隧道拓扑变更入参：只改拓扑（入口/转发链/出口），不改名称/计费/流量倍率。
 * 拓扑变更会重建节点上的 chains 与 service，因此要求隧道下没有任何转发记录。
 */
@Data
public class TunnelTopologyDto {

    @NotNull(message = "隧道ID不能为空")
    private Long id;

    /** 1=直连 2=隧道转发；不传沿用隧道当前类型 */
    @Min(value = 1, message = "隧道类型只能是 1 或 2")
    @Max(value = 2, message = "隧道类型只能是 1 或 2")
    private Integer type;

    @NotNull(message = "入口节点不能为空")
    private List<ChainTunnel> inNodeId;

    private List<List<ChainTunnel>> chainNodes;

    private List<ChainTunnel> outNodeId;

    /** 留空则按新入口节点的 serverIp 重新计算 */
    @Size(max = 64, message = "入口 IP 长度不能超过 64")
    private String inIp;
}
