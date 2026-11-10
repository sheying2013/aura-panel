package com.admin.common.dto;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Min;
import javax.validation.constraints.Max;
import javax.validation.constraints.Size;

@Data
public class ForwardUpdateDto {
    
    @NotNull(message = "ID不能为空")
    private Long id;
    
    @NotNull(message = "用户ID不能为空")
    private Integer userId;
    
    @NotBlank(message = "转发名称不能为空")
    @Size(max = 64, message = "转发名称长度不能超过 64 位")
    private String name;

    @NotBlank(message = "远程地址不能为空")
    @Size(max = 512, message = "远程地址长度不能超过 512 位")
    private String remoteAddr;

    private String strategy;

    /** null 表示不改端口（沿用既有值），两个注解对 null 均放行。 */
    @Min(value = 1, message = "入口端口必须在 1-65535 之间")
    @Max(value = 65535, message = "入口端口必须在 1-65535 之间")
    private Integer inPort;

} 