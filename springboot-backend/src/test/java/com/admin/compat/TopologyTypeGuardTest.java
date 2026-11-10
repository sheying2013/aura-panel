package com.admin.compat;

import com.admin.common.dto.TunnelTopologyDto;
import org.junit.jupiter.api.Test;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * round48：拓扑变更入参的类型边界。
 *
 * <p>基线行为（2026-11-08 圣何塞真机取证）：`POST /api/v1/tunnel/create` 传 type=3 返回 code 0，
 * 库里落下 `tunnel.type=3`，而 GostUtil 只把 2 当隧道转发、其余一律按直连下发，
 * 于是「库里的类型」和「节点上实际行为」长期不一致，且没有任何接口会报错。
 * 这里先在 DTO 层堵住拓扑变更路径（创建路径由 TunnelDto 的同类约束负责）。
 */
class TopologyTypeGuardTest {

    private static final Validator VALIDATOR;

    static {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        VALIDATOR = factory.getValidator();
    }

    @Test
    void typeThreeIsRejected() {
        TunnelTopologyDto dto = base();
        dto.setType(3);
        Set<ConstraintViolation<TunnelTopologyDto>> violations = VALIDATOR.validate(dto);
        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("隧道类型只能是 1 或 2"));
    }

    @Test
    void typeZeroIsRejected() {
        TunnelTopologyDto dto = base();
        dto.setType(0);
        assertEquals(1, VALIDATOR.validate(dto).size());
    }

    @Test
    void nullTypeIsAllowedAndMeansKeepCurrent() {
        TunnelTopologyDto dto = base();
        dto.setType(null);
        assertTrue(VALIDATOR.validate(dto).isEmpty());
    }

    @Test
    void typeOneAndTwoAreAllowed() {
        TunnelTopologyDto dto = base();
        dto.setType(1);
        assertTrue(VALIDATOR.validate(dto).isEmpty());
        dto.setType(2);
        assertTrue(VALIDATOR.validate(dto).isEmpty());
    }

    @Test
    void overlongInIpIsRejected() {
        TunnelTopologyDto dto = base();
        dto.setInIp(String.join("", Collections.nCopies(65, "a")));
        Set<ConstraintViolation<TunnelTopologyDto>> violations = VALIDATOR.validate(dto);
        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("入口 IP 长度"));
    }

    private TunnelTopologyDto base() {
        TunnelTopologyDto dto = new TunnelTopologyDto();
        dto.setId(1L);
        dto.setInNodeId(Collections.emptyList());
        return dto;
    }
}
