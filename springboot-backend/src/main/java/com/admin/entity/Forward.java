package com.admin.entity;

import java.io.Serializable;
import java.util.List;

import com.admin.common.dto.ForwardPortDto;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(autoResultMap = true)
public class Forward extends BaseEntity{

    private static final long serialVersionUID = 1L;

    private Integer userId;

    private String userName;

    private String name;

    private Integer tunnelId;

    private String remoteAddr;

    private String strategy;

    private Long inFlow;

    private Long outFlow;

    private Integer inx;

    /**
     * 暂停原因（round19）：manual / quota / expiry / disabled。
     * 存量数据为 null，读取方按「未标注」处理，走旧的超额推断口径。
     */
    private String pauseReason;
}
