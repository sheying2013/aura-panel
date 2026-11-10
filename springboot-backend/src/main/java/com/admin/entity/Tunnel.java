package com.admin.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

import com.admin.common.dto.ChainNodesItems;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 隧道实体类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(autoResultMap = true)
public class Tunnel extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    private Integer type;

    private int flow;

    private BigDecimal trafficRatio;

    private String inIp;
}
