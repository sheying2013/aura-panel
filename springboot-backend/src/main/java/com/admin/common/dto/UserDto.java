package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

@Data
public class UserDto {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度必须为 3-32 位")
    private String user;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度必须为 6-64 位")
    private String pwd;

    @NotNull(message = "流量不能为空")
    @Min(value = 0, message = "流量不能小于0")
    private Long flow;

    @NotNull(message = "转发数量不能为空")
    @Min(value = 0, message = "转发数量不能小于0")
    @Max(value = 1000000, message = "转发数量不能超过 1000000")
    private Integer num;

    @NotNull(message = "过期时间不能为空")
    @Min(value = 0, message = "过期时间不能为负数")
    private Long expTime;

    @NotNull(message = "流量重置时间不能为空")
    @Min(value = 0, message = "流量重置时间不能为负数")
    private Long flowResetTime;

    private Integer status;
} 