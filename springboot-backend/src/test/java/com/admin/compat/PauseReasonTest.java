package com.admin.compat;

import com.admin.common.utils.PauseReason;
import com.admin.common.utils.QuotaResume;
import com.admin.entity.Forward;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.service.impl.ForwardServiceImpl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * round19：暂停原因（forward.pause_reason）的写入与恢复判据。
 *
 * <p>核心回归点：round18 的恢复逻辑只看「重置前是否超额」，于是「用户手动暂停的转发，
 * 恰好账号处于超额状态」会在流量重置时被自动放开。有了显式原因后，手动/到期/停用一律不碰，
 * 只有 quota 才允许恢复；存量行（原因未标注）继续按旧的超额推断兜底。
 */
class PauseReasonTest {

    private static final long NOW = 1_900_000_000_000L;

    // ---------- 恢复判据 ----------

    @Test
    void manualReasonIsNeverResumedEvenWhenQuotaExceededBefore() {
        Forward forward = forward(0, PauseReason.MANUAL);
        assertFalse(QuotaResume.shouldResume(forward, user(1, null), null, true, NOW),
                "手动暂停的转发即使重置前超额，也不该被自动恢复");
    }

    @Test
    void expiryReasonIsNeverResumedByQuotaReset() {
        Forward forward = forward(0, PauseReason.EXPIRY);
        assertFalse(QuotaResume.shouldResume(forward, user(1, null), null, true, NOW));
    }

    @Test
    void disabledReasonIsNeverResumedByQuotaReset() {
        Forward forward = forward(0, PauseReason.DISABLED);
        assertFalse(QuotaResume.shouldResume(forward, user(1, null), null, true, NOW));
    }

    @Test
    void quotaReasonIsResumedOnlyWhenQuotaWasExceeded() {
        Forward forward = forward(0, PauseReason.QUOTA);
        assertTrue(QuotaResume.shouldResume(forward, user(1, null), null, true, NOW));
        assertFalse(QuotaResume.shouldResume(forward, user(1, null), null, false, NOW),
                "没超额就不该是配额暂停，不恢复");
    }

    @Test
    void legacyUnlabeledRowFallsBackToQuotaInference() {
        Forward forward = forward(0, null);
        assertTrue(QuotaResume.shouldResume(forward, user(1, null), null, true, NOW),
                "存量行没有原因，只能退回旧的超额推断");
        assertFalse(QuotaResume.shouldResume(forward, user(1, null), null, false, NOW));
    }

    @Test
    void expiredAccountStillBlocksQuotaResume() {
        Forward forward = forward(0, PauseReason.QUOTA);
        assertFalse(QuotaResume.shouldResume(forward, user(1, NOW - 1), null, true, NOW),
                "账号已过期时恢复也会被下一次上报再停，闸门必须拦住");
    }

    @Test
    void disabledUserTunnelStillBlocksQuotaResume() {
        Forward forward = forward(0, PauseReason.QUOTA);
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(5);
        userTunnel.setStatus(0);
        assertFalse(QuotaResume.shouldResume(forward, user(1, null), userTunnel, true, NOW));
    }

    @Test
    void activeForwardIsNeverResumed() {
        Forward forward = forward(1, PauseReason.QUOTA);
        assertFalse(QuotaResume.shouldResume(forward, user(1, null), null, true, NOW));
    }

    // ---------- 写入点 ----------

    @Test
    void manualPauseWritesManualReason() throws Exception {
        ForwardServiceImpl service = serviceWithMapper();
        Forward forward = forward(1, "");

        invokeApply(service, forward, 0);

        assertEquals(0, forward.getStatus());
        assertEquals(PauseReason.MANUAL, forward.getPauseReason());
        assertEquals(PauseReason.MANUAL, capturedUpdate(service).getPauseReason(),
                "落库对象必须带 manual 原因，否则重置时无法与配额暂停区分");
    }

    @Test
    void resumeClearsPauseReason() throws Exception {
        ForwardServiceImpl service = serviceWithMapper();
        Forward forward = forward(0, PauseReason.QUOTA);

        invokeApply(service, forward, 1);

        assertEquals(1, forward.getStatus());
        assertEquals("", forward.getPauseReason(), "恢复后不能还挂着旧原因（空串=无原因）");
        assertEquals("", capturedUpdate(service).getPauseReason(),
                "updateById 会跳过 null，必须写空串才能把旧原因冲掉");
    }

    private static ForwardServiceImpl serviceWithMapper() throws Exception {
        ForwardServiceImpl service = new ForwardServiceImpl();
        Field baseMapper = ServiceImpl.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(service, mock(ForwardMapper.class));
        return service;
    }

    private static void invokeApply(ForwardServiceImpl service, Forward forward, int targetStatus) throws Exception {
        Method method = ForwardServiceImpl.class.getDeclaredMethod("applyStatusChange",
                Forward.class, List.class, UserTunnel.class, int.class, String.class);
        method.setAccessible(true);
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(5);
        method.invoke(service, forward, List.of(), userTunnel, targetStatus,
                targetStatus == 1 ? "ResumeService" : "PauseService");
    }

    private static Forward capturedUpdate(ForwardServiceImpl service) throws Exception {
        Field baseMapper = ServiceImpl.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        ForwardMapper mapper = (ForwardMapper) baseMapper.get(service);
        ArgumentCaptor<Forward> captor = ArgumentCaptor.forClass(Forward.class);
        verify(mapper).updateById(captor.capture());
        return captor.getValue();
    }

    private static Forward forward(int status, String reason) {
        Forward forward = new Forward();
        forward.setId(31L);
        forward.setUserId(2);
        forward.setTunnelId(1);
        forward.setStatus(status);
        forward.setPauseReason(reason);
        return forward;
    }

    private static User user(int status, Long expTime) {
        User user = new User();
        user.setId(2L);
        user.setStatus(status);
        user.setExpTime(expTime);
        return user;
    }

}
