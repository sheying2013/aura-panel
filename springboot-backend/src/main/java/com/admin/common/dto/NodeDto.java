package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

@Data
public class NodeDto {

    @NotBlank(message = "节点名称不能为空")
    private String name;

    @NotBlank(message = "服务器ip不能为空")
    private String serverIp;

    @NotBlank(message = "可用端口不能为空")
    @Size(max = 200, message = "可用端口串长度不能超过 200 位")
    private String port;

    private String interfaceName;

    /**
     * 未显式传值时留空，由 NodeServiceImpl 统一兜底为 0.0.0.0。
     * 不要在这里写字段初始化值：那样「未传」与「显式传同一个值」不可区分，update 路径会覆盖已有值。
     */
    private String tcpListenAddr;

    private String udpListenAddr;

} 