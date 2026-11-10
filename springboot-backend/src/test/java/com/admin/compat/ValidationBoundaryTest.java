package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ResetFlowDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.dto.UserDto;
import com.admin.common.dto.UserTunnelDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO 入参边界校验（Round 23 / round48 W1）。
 *
 * <p>只做注解层校验，不起 Spring：用 {@link Validation#buildDefaultValidatorFactory()} 拿到
 * 与线上同一套 Hibernate Validator，直接 validate DTO 实例。线上由
 * {@code GlobalExceptionHandler:20-26} 把 MethodArgumentNotValidException 转成 R.err(500, 第一条 message)，
 * 所以这里断言 message 文案就是前端最终看到的提示。
 *
 * <p>为什么要在注解层拦：这些字段过去没有上限/范围，超长串或 type=0 会一路进到 service，
 * 落库后只能靠 GostUtil/NodeServiceImpl 的兜底抛 IllegalStateException，提示看不到具体字段。
 */
class ValidationBoundaryTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    private static final Validator VALIDATOR = FACTORY.getValidator();

    /** 取指定字段的第一条校验消息；该字段没触发任何约束时返回 null。 */
    private static <T> String fieldMessage(T dto, String field) {
        return VALIDATOR.validate(dto).stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse(null);
    }

    private static <T> String allMessages(T dto) {
        ArrayList<String> messages = new ArrayList<>();
        for (ConstraintViolation<T> violation : VALIDATOR.validate(dto)) {
            messages.add(violation.getPropertyPath() + "=" + violation.getMessage());
        }
        return String.join(" | ", messages);
    }

    // ---------- 各 DTO 的「全部合法」基线 ----------

    private static TunnelDto validTunnel() {
        TunnelDto dto = new TunnelDto();
        dto.setName("tunnel-a");
        dto.setInNodeId(new ArrayList<>());
        dto.setType(1);
        dto.setFlow(1);
        return dto;
    }

    private static UserDto validUser() {
        UserDto dto = new UserDto();
        dto.setUser("alice");
        dto.setPwd("secret123");
        dto.setFlow(100L);
        dto.setNum(10);
        dto.setExpTime(0L);
        dto.setFlowResetTime(0L);
        return dto;
    }

    private static ForwardDto validForward() {
        ForwardDto dto = new ForwardDto();
        dto.setName("forward-a");
        dto.setTunnelId(1);
        dto.setRemoteAddr("1.2.3.4:8080");
        dto.setInPort(null);
        return dto;
    }

    private static UserTunnelDto validUserTunnel() {
        UserTunnelDto dto = new UserTunnelDto();
        dto.setUserId(1);
        dto.setTunnelId(1);
        dto.setFlow(0L);
        dto.setNum(1);
        dto.setFlowResetTime(0L);
        dto.setExpTime(0L);
        dto.setSpeedId(null);
        return dto;
    }

    private static ResetFlowDto validReset() {
        ResetFlowDto dto = new ResetFlowDto();
        dto.setId(1);
        dto.setType(1);
        return dto;
    }

    // ---------- TunnelDto ----------

    @Test
    @DisplayName("TunnelDto：名称超过 64 位被拦，提示带长度上限")
    void tunnelNameTooLong() {
        TunnelDto dto = validTunnel();
        dto.setName(repeat('a', 65));
        String message = fieldMessage(dto, "name");
        assertNotNull(message, "65 位名称必须被 @Size(max=64) 拦下");
        assertTrue(message.contains("64"), "提示要带长度上限，实际：" + message);

        dto.setName(repeat('a', 64));
        assertNull(fieldMessage(dto, "name"), "64 位名称必须放行");
    }

    @Test
    @DisplayName("TunnelDto：type=3 被拦，文案含「隧道类型只能是 1 或 2」")
    void tunnelTypeOutOfRange() {
        TunnelDto dto = validTunnel();
        dto.setType(3);
        String message = fieldMessage(dto, "type");
        assertNotNull(message, "type=3 必须被 @Max(2) 拦下");
        assertTrue(message.contains("隧道类型只能是 1 或 2"), "实际：" + message);

        dto.setType(0);
        assertNotNull(fieldMessage(dto, "type"), "type=0 必须被 @Min(1) 拦下");
    }

    @Test
    @DisplayName("TunnelDto：flow=0 被拦，合法值 1/2 放行")
    void tunnelFlowOutOfRange() {
        TunnelDto dto = validTunnel();
        dto.setFlow(0);
        String message = fieldMessage(dto, "flow");
        assertNotNull(message, "flow=0 必须被 @Min(1) 拦下");
        assertTrue(message.contains("流量计算类型"), "实际：" + message);

        dto.setFlow(2);
        assertNull(fieldMessage(dto, "flow"), "flow=2 必须放行");
    }

    // ---------- UserDto ----------

    @Test
    @DisplayName("UserDto：用户名 2 位被拦，文案含「3-32」")
    void userNameTooShort() {
        UserDto dto = validUser();
        dto.setUser("ab");
        String message = fieldMessage(dto, "user");
        assertNotNull(message, "2 位用户名必须被 @Size(min=3) 拦下");
        assertTrue(message.contains("3-32"), "实际：" + message);
    }

    @Test
    @DisplayName("UserDto：密码 5 位被拦，文案含「6-64」")
    void userPasswordTooShort() {
        UserDto dto = validUser();
        dto.setPwd("12345");
        String message = fieldMessage(dto, "pwd");
        assertNotNull(message, "5 位密码必须被 @Size(min=6) 拦下");
        assertTrue(message.contains("6-64"), "实际：" + message);
    }

    @Test
    @DisplayName("UserDto：expTime=-1 被拦，0 放行")
    void userExpTimeNegative() {
        UserDto dto = validUser();
        dto.setExpTime(-1L);
        String message = fieldMessage(dto, "expTime");
        assertNotNull(message, "expTime=-1 必须被 @Min(0) 拦下");
        assertTrue(message.contains("过期时间不能为负数"), "实际：" + message);

        dto.setExpTime(0L);
        assertNull(fieldMessage(dto, "expTime"), "expTime=0 必须放行");
    }

    @Test
    @DisplayName("UserDto：flowResetTime 负数被拦，num 上限 1000000 生效")
    void userFlowResetAndNumCap() {
        UserDto dto = validUser();
        dto.setFlowResetTime(-86400000L);
        assertNotNull(fieldMessage(dto, "flowResetTime"), "负的 flowResetTime 必须被拦下");

        dto.setFlowResetTime(0L);
        dto.setNum(1000001);
        String message = fieldMessage(dto, "num");
        assertNotNull(message, "num 超过 100 万必须被拦下");
        assertTrue(message.contains("1000000"), "实际：" + message + "（全量：" + allMessages(dto) + "）");
    }

    // ---------- ForwardDto ----------

    @Test
    @DisplayName("ForwardDto：inPort=0 被拦")
    void forwardInPortZero() {
        ForwardDto dto = validForward();
        dto.setInPort(0);
        String message = fieldMessage(dto, "inPort");
        assertNotNull(message, "inPort=0 必须被 @Min(1) 拦下");
        assertTrue(message.contains("1-65535"), "实际：" + message);
    }

    @Test
    @DisplayName("ForwardDto：inPort=70000 被拦")
    void forwardInPortTooLarge() {
        ForwardDto dto = validForward();
        dto.setInPort(70000);
        String message = fieldMessage(dto, "inPort");
        assertNotNull(message, "inPort=70000 必须被 @Max(65535) 拦下");
        assertTrue(message.contains("1-65535"), "实际：" + message);
    }

    @Test
    @DisplayName("ForwardDto：inPort=null 表示自动分配，必须放行")
    void forwardInPortNullIsAutoAssign() {
        ForwardDto dto = validForward();
        assertNull(fieldMessage(dto, "inPort"), "null 端口是自动分配语义，注解对 null 必须放行");

        dto.setInPort(65535);
        assertNull(fieldMessage(dto, "inPort"), "边界值 65535 必须放行");
        dto.setInPort(1);
        assertNull(fieldMessage(dto, "inPort"), "边界值 1 必须放行");
    }

    // ---------- UserTunnelDto ----------

    @Test
    @DisplayName("UserTunnelDto：speedId=0 被拦，null（不限速）放行")
    void userTunnelSpeedIdBoundary() {
        UserTunnelDto dto = validUserTunnel();
        dto.setSpeedId(0);
        String message = fieldMessage(dto, "speedId");
        assertNotNull(message, "speedId=0 必须被 @Min(1) 拦下");
        assertTrue(message.contains("限速规则ID"), "实际：" + message);

        dto.setSpeedId(null);
        assertNull(fieldMessage(dto, "speedId"), "null 表示不限速，必须放行");
    }

    @Test
    @DisplayName("UserTunnelDto：userId/tunnelId 为 0、expTime 为负、num 超上限都被拦")
    void userTunnelOtherBounds() {
        UserTunnelDto dto = validUserTunnel();
        dto.setUserId(0);
        dto.setTunnelId(0);
        dto.setExpTime(-1000L);
        dto.setNum(1000001);
        assertNotNull(fieldMessage(dto, "userId"), "userId=0 必须被拦下");
        assertNotNull(fieldMessage(dto, "tunnelId"), "tunnelId=0 必须被拦下");
        assertNotNull(fieldMessage(dto, "expTime"), "负的 expTime 必须被拦下");
        assertNotNull(fieldMessage(dto, "num"), "num 超过 100 万必须被拦下");
    }

    // ---------- ResetFlowDto ----------

    @Test
    @DisplayName("ResetFlowDto：type=3 被拦，文案含「重置类型」；id=0 被拦")
    void resetFlowTypeOutOfRange() {
        ResetFlowDto dto = validReset();
        dto.setType(3);
        String message = fieldMessage(dto, "type");
        assertNotNull(message, "type=3 必须被 @Max(2) 拦下");
        assertTrue(message.contains("重置类型"), "实际：" + message);

        dto.setType(1);
        assertNull(fieldMessage(dto, "type"), "type=1（账号）必须放行");
        dto.setType(2);
        assertNull(fieldMessage(dto, "type"), "type=2（隧道）必须放行");

        dto.setId(0);
        assertNotNull(fieldMessage(dto, "id"), "id=0 必须被 @Min(1) 拦下");
    }

    // ---------- 正例 ----------

    @Test
    @DisplayName("全部字段合法时零 violation（含 null 可选字段）")
    void allValidHasNoViolation() {
        Set<ConstraintViolation<TunnelDto>> tunnel = VALIDATOR.validate(validTunnel());
        assertTrue(tunnel.isEmpty(), "合法 TunnelDto 不应有 violation，实际：" + allMessages(validTunnel()));

        Set<ConstraintViolation<UserDto>> user = VALIDATOR.validate(validUser());
        assertTrue(user.isEmpty(), "合法 UserDto 不应有 violation，实际：" + allMessages(validUser()));

        Set<ConstraintViolation<ForwardDto>> forward = VALIDATOR.validate(validForward());
        assertTrue(forward.isEmpty(), "合法 ForwardDto 不应有 violation，实际：" + allMessages(validForward()));

        Set<ConstraintViolation<UserTunnelDto>> userTunnel = VALIDATOR.validate(validUserTunnel());
        assertTrue(userTunnel.isEmpty(), "合法 UserTunnelDto 不应有 violation，实际：" + allMessages(validUserTunnel()));

        Set<ConstraintViolation<ResetFlowDto>> reset = VALIDATOR.validate(validReset());
        assertTrue(reset.isEmpty(), "合法 ResetFlowDto 不应有 violation，实际：" + allMessages(validReset()));
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
