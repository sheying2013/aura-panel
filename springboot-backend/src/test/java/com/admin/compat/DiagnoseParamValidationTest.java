package com.admin.compat;

import com.admin.common.exception.GlobalExceptionHandler;
import com.admin.common.lang.R;
import com.admin.controller.ForwardController;
import com.admin.service.ForwardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 缺陷2：POST /api/v1/forward/diagnose 传 {@code {"id":9}}（正确字段是 forwardId）时返回
 * {@code {"code":-2,"msg":"Cannot invoke \"Object.toString()\" because the return value of
 * "java.util.Map.get(Object)" is null"}} —— Map 取参缺键直接 NPE，异常文本原样泄给前端。
 *
 * <p>修复后：缺参数 / 非法参数返回可读文案；正常参数仍原样透传到 service。</p>
 */
class DiagnoseParamValidationTest {

    private ForwardService forwardService;
    private ForwardController controller;

    @BeforeEach
    void setUp() {
        forwardService = mock(ForwardService.class);
        controller = new ForwardController();
        ReflectionTestUtils.setField(controller, "forwardService", forwardService);
    }

    private static void assertReadableParamError(R result, String expectedMsg) {
        assertNotNull(result);
        assertEquals(-2, result.getCode(), "参数错误沿用原有 -2 码，只换文案");
        assertEquals(expectedMsg, result.getMsg());
        assertFalse(String.valueOf(result.getMsg()).contains("Cannot invoke"), "不能把 NPE 原始文本泄给调用方");
    }

    @Test
    @DisplayName("真机事故形状 {\"id\":9}（缺 forwardId）→ 返回 forwardId 不能为空，不再 NPE")
    void diagnoseWithoutForwardIdReturnsReadableError() {
        Map<String, Object> params = new HashMap<>();
        params.put("id", 9);

        R result = controller.diagnoseForward(params);

        assertReadableParamError(result, "forwardId 不能为空");
        verifyNoInteractions(forwardService);
    }

    @Test
    @DisplayName("空 body（null）/ forwardId 为 null 或空白 → 同样返回可读错误")
    void diagnoseWithNullBodyOrBlankIdReturnsReadableError() {
        assertReadableParamError(controller.diagnoseForward(null), "forwardId 不能为空");

        Map<String, Object> nullId = new HashMap<>();
        nullId.put("forwardId", null);
        assertReadableParamError(controller.diagnoseForward(nullId), "forwardId 不能为空");

        Map<String, Object> blankId = new HashMap<>();
        blankId.put("forwardId", "   ");
        assertReadableParamError(controller.diagnoseForward(blankId), "forwardId 不能为空");

        verifyNoInteractions(forwardService);
    }

    @Test
    @DisplayName("forwardId 非数字 → 返回格式不正确，不再抛 NumberFormatException")
    void diagnoseWithNonNumericIdReturnsReadableError() {
        Map<String, Object> params = new HashMap<>();
        params.put("forwardId", "abc");

        assertReadableParamError(controller.diagnoseForward(params), "forwardId 格式不正确");
        verifyNoInteractions(forwardService);
    }

    @Test
    @DisplayName("正常 forwardId（Long / Integer / 数字字符串）→ 仍然透传到 service")
    void diagnoseWithValidIdDelegatesToService() {
        when(forwardService.diagnoseForward(anyLong())).thenReturn(R.ok("diagnosis"));

        Map<String, Object> asLong = new HashMap<>();
        asLong.put("forwardId", 9L);
        assertEquals("diagnosis", controller.diagnoseForward(asLong).getData());

        Map<String, Object> asInteger = new HashMap<>();
        asInteger.put("forwardId", 9);
        assertEquals("diagnosis", controller.diagnoseForward(asInteger).getData());

        Map<String, Object> asText = new HashMap<>();
        asText.put("forwardId", " 9 ");
        assertEquals("diagnosis", controller.diagnoseForward(asText).getData());

        verify(forwardService, org.mockito.Mockito.times(3)).diagnoseForward(9L);
    }

    @Test
    @DisplayName("同 controller 的 delete / force-delete / pause / resume 缺 id 也不再 NPE")
    void otherMapEndpointsRejectMissingId() {
        assertReadableParamError(controller.delete(null), "id 不能为空");
        assertReadableParamError(controller.forceDelete(new HashMap<>()), "id 不能为空");
        assertReadableParamError(controller.pause(null), "id 不能为空");
        assertReadableParamError(controller.resume(new HashMap<>()), "id 不能为空");

        verifyNoInteractions(forwardService);
    }

    @Test
    @DisplayName("delete / pause 正常 id → 透传到 service，且 update-order 空 body 给出可读提示")
    void otherMapEndpointsStillDelegate() {
        when(forwardService.deleteForward(anyLong())).thenReturn(R.ok());
        when(forwardService.pauseForward(anyLong())).thenReturn(R.ok());
        when(forwardService.updateForwardOrder(any())).thenReturn(R.ok());

        Map<String, Object> params = new HashMap<>();
        params.put("id", 5);

        assertEquals(0, controller.delete(params).getCode());
        assertEquals(0, controller.pause(params).getCode());
        verify(forwardService).deleteForward(5L);
        verify(forwardService).pauseForward(5L);

        assertReadableParamError(controller.updateForwardOrder(null), "forwards 不能为空");
        verify(forwardService, never()).updateForwardOrder(any());
    }

    @Test
    @DisplayName("兜底异常处理：e.getMessage() 为 null 时返回「类名（无异常信息）」，前端不显示 null")
    void globalHandlerRendersClassNameWhenMessageIsNull() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        R result = handler.Exception(new RuntimeException());

        assertEquals(-2, result.getCode());
        assertEquals("RuntimeException（无异常信息）", result.getMsg());
    }

    @Test
    @DisplayName("兜底异常处理：有 message 时原样返回，不改变既有行为")
    void globalHandlerKeepsExistingMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        R result = handler.Exception(new IllegalStateException("数据库连接失败"));

        assertEquals(-2, result.getCode());
        assertEquals("数据库连接失败", result.getMsg());
    }
}
