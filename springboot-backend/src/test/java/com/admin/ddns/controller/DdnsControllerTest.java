package com.admin.ddns.controller;

import com.admin.common.lang.R;
import com.admin.ddns.dto.DdnsCreateRequest;
import com.admin.ddns.service.DdnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 控制器只做参数收敛和转发；这里锁死 /api/v1/ddns 的入口契约。 */
class DdnsControllerTest {

    private DdnsService service;
    private DdnsController controller;

    @BeforeEach
    void setUp() {
        service = mock(DdnsService.class);
        controller = new DdnsController();
        ReflectionTestUtils.setField(controller, "ddnsService", service);
    }

    @Test
    @DisplayName("list 直接返回服务层数据")
    void listDelegates() {
        when(service.list()).thenReturn(List.of());
        R response = controller.list();
        assertEquals(0, response.getCode());
        assertTrue(response.getData() instanceof List);
        verify(service).list();
    }

    @Test
    @DisplayName("create 原样转发 DTO")
    void createDelegates() {
        DdnsCreateRequest request = new DdnsCreateRequest();
        request.setDomain("app.example.com");
        when(service.create(request)).thenReturn(R.ok("created"));
        assertEquals("created", controller.create(request).getData());
        verify(service).create(request);
    }

    @Test
    @DisplayName("delete 缺 id 时不调用服务层，返回参数错误")
    void deleteRejectsMissingId() {
        R response = controller.delete(Map.of());
        assertEquals(-2, response.getCode());
        assertTrue(response.getMsg().contains("id"));
    }

    @Test
    @DisplayName("delete 正确解析字符串 id")
    void deleteParsesId() {
        when(service.delete(7L)).thenReturn(R.ok("deleted"));
        assertEquals("deleted", controller.delete(Map.of("id", "7")).getData());
        verify(service).delete(7L);
    }

    @Test
    @DisplayName("check 正确解析数字 id")
    void checkParsesId() {
        when(service.checkNow(9L)).thenReturn(R.ok("checked"));
        assertEquals("checked", controller.check(Map.of("id", 9)).getData());
        verify(service).checkNow(9L);
    }
}
