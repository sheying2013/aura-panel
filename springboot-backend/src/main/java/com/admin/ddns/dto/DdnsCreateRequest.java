package com.admin.ddns.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class DdnsCreateRequest {

    @NotBlank(message = "域名不能为空")
    @Size(max = 253, message = "域名长度不能超过 253")
    private String domain;

    @Min(value = 10, message = "检查间隔不能小于 10 秒")
    @Max(value = 3600, message = "检查间隔不能大于 3600 秒")
    private Integer intervalSeconds;

    /** 缺省启用。 */
    private Integer enabled;
}
