package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class NodeUpdateDto {

    @NotNull(message = "节点ID不能为空")
    private Long id;

    @NotBlank(message = "节点名称不能为空")
    private String name;

    @NotBlank(message = "服务器ip不能为空")
    private String serverIp;

    @NotBlank(message = "可用port不能为空")
    @Size(max = 200, message = "可用端口串长度不能超过 200 位")
    private String port;

    private String interfaceName;
    private Integer http;
    private Integer tls;
    private Integer socks;

    /**
     * null/空白 表示本次不改该列；显式传值（如 "::" 或具体 IP）原样落库。
     */
    private String tcpListenAddr;

    private String udpListenAddr;
} 