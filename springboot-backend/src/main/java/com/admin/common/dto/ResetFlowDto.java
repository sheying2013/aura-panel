package com.admin.common.dto;


import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class ResetFlowDto {

    @NotNull(message = "重置账号id不能为空")
    @Min(value = 1, message = "重置账号id必须大于 0")
    private Integer id;

    @NotNull(message = "重置类型不能为空")
    @Min(value = 1, message = "重置类型只能是 1（账号）或 2（隧道）")
    @Max(value = 2, message = "重置类型只能是 1（账号）或 2（隧道）")
    private Integer type;
}
