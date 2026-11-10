package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.controller.ForwardController;
import com.admin.service.ForwardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /forward/create 与 /forward/update 的入口入参校验回归测试。
 *
 * <p>校验口径（ForwardController.validateForwardParams）：name 去空格后 1-64 字符且不含换行；
 * remoteAddr 非空、≤255、不含空白字符；inPort 为 null 或 1-65535；protocol 只认 tcp / udp；
 * type 只认 1 / 2。校验失败一律 R.err(可读文案)，且不触碰 service。</p>
 */
class ForwardParamValidationTest {

    private ForwardService forwardService;
    private ForwardController controller;

    @BeforeEach
    void setUp() {
        forwardService = mock(ForwardService.class);
        controller = new ForwardController();
        ReflectionTestUtils.setField(controller, "forwardService", forwardService);
    }

    @Test
    @DisplayName("合法 create：name / remoteAddr / inPort 都合规 → 原样透传给 service")
    void validCreateDelegatesToService() {
        when(forwardService.createForward(any())).thenReturn(R.ok());
        ForwardController.CreateForwardRequest request =
                createRequest("转发 A", "1.2.3.4:8080,5.6.7.8:9090", 20001);

        R result = controller.create(request);

        assertEquals(0, result.getCode());
        verify(forwardService).createForward(request);
    }

    @Test
    @DisplayName("name 超长（65 字符）→ 报错并指出 name，不调用 service")
    void rejectsTooLongName() {
        assertParamError(controller.create(createRequest("a".repeat(65), "1.2.3.4:8080", 20001)), "name");
        verifyNoInteractions(forwardService);
    }

    @Test
    @DisplayName("name 含换行 → 报错；去空格后 64 字符仍然放行")
    void rejectsNameWithLineBreakAndAllowsExactly64() {
        String withBreak = "第一行" + System.lineSeparator() + "第二行";
        assertParamError(controller.create(createRequest(withBreak, "1.2.3.4:8080", 20001)), "name");

        when(forwardService.createForward(any())).thenReturn(R.ok());
        assertEquals(0, controller.create(createRequest("a".repeat(64), "1.2.3.4:8080", 20001)).getCode());
    }

    @Test
    @DisplayName("remoteAddr 带空格 → 报错并指出 remoteAddr")
    void rejectsRemoteAddrWithSpace() {
        assertParamError(controller.create(createRequest("ok-name", "1.2.3.4:8080 5.6.7.8:9090", 20001)),
                "remoteAddr");
        verifyNoInteractions(forwardService);
    }

    @Test
    @DisplayName("remoteAddr 超长（256 字符）→ 报错")
    void rejectsTooLongRemoteAddr() {
        assertParamError(controller.create(createRequest("ok-name", "a".repeat(256), 20001)), "remoteAddr");
        verifyNoInteractions(forwardService);
    }

    @Test
    @DisplayName("inPort=0 → 报错并指出 inPort")
    void rejectsZeroInPort() {
        assertParamError(controller.create(createRequest("ok-name", "1.2.3.4:8080", 0)), "inPort");
        verifyNoInteractions(forwardService);
    }

    @Test
    @DisplayName("inPort=70000 → 报错；inPort=null（自动分配）仍然放行")
    void rejectsOutOfRangeInPortAndAllowsNull() {
        assertParamError(controller.create(createRequest("ok-name", "1.2.3.4:8080", 70000)), "inPort");

        when(forwardService.createForward(any())).thenReturn(R.ok());
        assertEquals(0, controller.create(createRequest("ok-name", "1.2.3.4:8080", null)).getCode());
    }

    @Test
    @DisplayName("protocol=FTP → 报错并指出 protocol；UDP 大小写不敏感且归一化为小写")
    void rejectsUnknownProtocolAndNormalizesCase() {
        ForwardController.CreateForwardRequest bad = createRequest("ok-name", "1.2.3.4:8080", 20001);
        bad.setProtocol("FTP");
        assertParamError(controller.create(bad), "protocol");

        when(forwardService.createForward(any())).thenReturn(R.ok());
        ForwardController.CreateForwardRequest upper = createRequest("ok-name", "1.2.3.4:8080", 20001);
        upper.setProtocol("UDP");
        assertEquals(0, controller.create(upper).getCode());
        assertEquals("udp", upper.getProtocol(), "protocol 落库口径是小写");
    }

    @Test
    @DisplayName("type=9 → 报错并指出 type；type=1 / 2 放行")
    void rejectsUnknownTypeAndAcceptsEntryAndExit() {
        ForwardController.CreateForwardRequest bad = createRequest("ok-name", "1.2.3.4:8080", 20001);
        bad.setType(9);
        assertParamError(controller.create(bad), "type");

        when(forwardService.createForward(any())).thenReturn(R.ok());
        ForwardController.CreateForwardRequest entry = createRequest("ok-name", "1.2.3.4:8080", 20001);
        entry.setType(1);
        assertEquals(0, controller.create(entry).getCode());
        ForwardController.CreateForwardRequest exit = createRequest("ok-name", "1.2.3.4:8080", 20001);
        exit.setType(2);
        assertEquals(0, controller.create(exit).getCode());
    }

    @Test
    @DisplayName("update 走同一套校验：合法请求透传，inPort=0 被拦下")
    void updateUsesSameValidation() {
        when(forwardService.updateForward(any())).thenReturn(R.ok());
        ForwardController.UpdateForwardRequest valid = updateRequest(1L, 7, "ok-name", "1.2.3.4:8080", 20001);
        assertEquals(0, controller.update(valid).getCode());
        verify(forwardService).updateForward(valid);

        ForwardController.UpdateForwardRequest bad = updateRequest(1L, 7, "ok-name", "1.2.3.4:8080", 0);
        assertParamError(controller.update(bad), "inPort");
    }

    @Test
    @DisplayName("合法请求不会被改写：未传 protocol / type 时保持 null，name 原样透传")
    void leavesValidPayloadUntouched() {
        when(forwardService.createForward(any())).thenReturn(R.ok());
        ForwardController.CreateForwardRequest request = createRequest("  含空格的名字  ", "1.2.3.4:8080", 20001);

        assertEquals(0, controller.create(request).getCode());

        assertEquals("  含空格的名字  ", request.getName(), "name 的裁剪由 service 负责，入口层不改写");
        assertNull(request.getProtocol());
        assertNull(request.getType());
    }

    private static ForwardController.CreateForwardRequest createRequest(String name, String remoteAddr, Integer inPort) {
        ForwardController.CreateForwardRequest request = new ForwardController.CreateForwardRequest();
        request.setName(name);
        request.setTunnelId(3);
        request.setRemoteAddr(remoteAddr);
        request.setInPort(inPort);
        return request;
    }

    private static ForwardController.UpdateForwardRequest updateRequest(Long id, Integer userId, String name,
                                                                       String remoteAddr, Integer inPort) {
        ForwardController.UpdateForwardRequest request = new ForwardController.UpdateForwardRequest();
        request.setId(id);
        request.setUserId(userId);
        request.setName(name);
        request.setRemoteAddr(remoteAddr);
        request.setInPort(inPort);
        return request;
    }

    private static void assertParamError(R result, String expectedField) {
        assertNotNull(result);
        assertEquals(-1, result.getCode(), "入口校验统一走 R.err(String)，即 code=-1");
        assertNotNull(result.getMsg());
        assertTrue(result.getMsg().contains(expectedField), "提示要能指出是哪个字段错了，实际：" + result.getMsg());
    }
}
