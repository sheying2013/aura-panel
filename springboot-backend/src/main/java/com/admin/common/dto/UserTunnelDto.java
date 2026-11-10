package com.admin.common.dto;

import lombok.Data;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
public class UserTunnelDto {
    
    @NotNull(message = "用户ID不能为空")
    @Min(value = 1, message = "用户ID必须大于 0")
    private Integer userId;
    
    @NotNull(message = "隧道ID不能为空")
    @Min(value = 1, message = "隧道ID必须大于 0")
    private Integer tunnelId;
    
    @NotNull(message = "流量限制不能为空")
    @Min(value = 0, message = "流量限制不能小于0")
    private Long flow;
    
    @NotNull(message = "转发数量不能为空")
    @Min(value = 0, message = "转发数量不能小于0")
    @Max(value = 1000000, message = "转发数量不能超过 1000000")
    private Integer num;
    
    /**
     * 流量重置时间（时间戳）
     */
    @NotNull(message = "流量重置时间不能为空")
    @Min(value = 0, message = "流量重置时间不能为负数")
    private Long flowResetTime;
    
    /**
     * 到期时间（时间戳）
     */
    @NotNull(message = "到期时间不能为空")
    @Min(value = 0, message = "到期时间不能为负数")
    private Long expTime;
    
    /**
     * 限速规则ID（可选，null表示不限速）
     */
    @Min(value = 1, message = "限速规则ID必须大于 0")
    private Integer speedId;
} 