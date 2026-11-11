package com.admin.ddns.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class DdnsUpdateRequest {

    @NotNull(message = "id 不能为空")
    private Long id;

    /**
     * 只用于回显/校验，不允许修改。
     *
     * <p>域名一改，节点上已经按旧域名渲染成 IP 的服务就必须整体重下发；在没有完成
     * 事务化重下发前放开改名，等于把节点留在旧域名对应的 IP 上。这里显式拒绝，
     * 需要换域名请先删除再新建。
     */
    @Size(max = 253, message = "域名长度不能超过 253")
    private String domain;

    @Min(value = 10, message = "检查间隔不能小于 10 秒")
    @Max(value = 3600, message = "检查间隔不能大于 3600 秒")
    private Integer intervalSeconds;

    private Integer enabled;
}
