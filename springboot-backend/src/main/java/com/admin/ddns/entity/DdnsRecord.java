package com.admin.ddns.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 一个 DDNS 目标域名当前被面板接管的状态。
 *
 * <p>这张表刻意独立于 forward：转发规则只保存用户填写的域名，解析结果、检查节奏和
 * 最近一次失败都记在这里。这样旧库升级只需新增表，不动 forward 既有列，也不会因为
 * 某次 DNS 故障把转发规则本身写坏。
 */
@Data
@TableName("ddns_record")
public class DdnsRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规范化后的域名（小写、去尾点、IDN 已转 ASCII）。 */
    private String domain;

    /** 最近一次成功解析到的全部地址，逗号分隔，IPv4 在前。 */
    private String resolvedIps;

    /**
     * 当前实际下发给 GOST 的主地址。
     *
     * <p>只有这个值漂移才会触发热重载；仅新增/移除备用地址不会造成无意义的断流。
     */
    private String primaryIp;

    /** 检查间隔（秒）。 */
    private Integer intervalSeconds;

    /** 1 启用，0 暂停自动检查；暂停不会删除已下发的地址。 */
    private Integer enabled;

    private Long lastCheckTime;

    private Long lastChangeTime;

    /** 最近一次失败原因，成功后清空。 */
    private String lastError;

    private Integer failCount;

    private Long createdTime;

    private Long updatedTime;
}
