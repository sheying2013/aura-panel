package com.admin.compat;

import com.admin.common.utils.GostUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重连补发暂停时，节点返回 "service x_tcp not found" 表示服务本来就不在节点上
 * （配置被清空或被改名），不该按失败刷日志 —— 恢复会走重建路径。
 */
class PauseNotFoundTest {

    @Test
    void notFoundIsRecognised() {
        assertTrue(GostUtil.notFound("service 7_2_1_tcp not found"));
        assertTrue(GostUtil.notFound("not found"));
    }

    @Test
    void okIsNotNotFound() {
        assertFalse(GostUtil.notFound("OK"));
    }

    @Test
    void otherFailureIsNotNotFound() {
        assertFalse(GostUtil.notFound("节点未连接"));
        assertFalse(GostUtil.notFound("service 7_2_1_tcp is already paused"));
    }

    @Test
    void nullMessageIsNotNotFound() {
        assertFalse(GostUtil.notFound(null));
    }
}
