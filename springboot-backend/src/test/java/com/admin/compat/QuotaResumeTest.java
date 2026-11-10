package com.admin.compat;

import com.admin.common.utils.PauseReason;
import com.admin.common.utils.QuotaResume;
import com.admin.entity.Forward;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额暂停恢复判据（round18）——纯函数回归。
 *
 * <p>原缺陷：配额超限把转发暂停并落库后，流量重置只清 in_flow/out_flow，
 * 被停掉的转发永远停在暂停态；库里没有暂停原因字段，只能用「重置前是否超额」推断。
 *
 * <p>判据必须与 FlowController 的暂停口径一致（用户：额度 &lt; 已用；用户隧道：已用 &gt;= 额度），
 * 否则会出现「暂停按 A 口径、恢复按 B 口径」的错位。
 */
class QuotaResumeTest {

    private static final long GB = 1024L * 1024L * 1024L;
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void userOverQuotaFollowsStrictGreaterThan() {
        // 正好用满不算超额（FlowController: userFlowLimit < current 才停）
        assertFalse(QuotaResume.userOverQuota(1L, GB, 0L));
        assertTrue(QuotaResume.userOverQuota(1L, GB + 1, 0L));
        assertTrue(QuotaResume.userOverQuota(2L, GB, GB + 2));
    }

    @Test
    void userTunnelOverQuotaFollowsGreaterOrEqual() {
        // 用户隧道口径是 >=（FlowController: flow >= limit*GB 就停）
        assertTrue(QuotaResume.userTunnelOverQuota(1L, GB, 0L));
        assertFalse(QuotaResume.userTunnelOverQuota(1L, GB - 1, 0L));
    }

    @Test
    void unlimitedQuotaNeverCountsAsOver() {
        assertFalse(QuotaResume.userOverQuota(null, GB, GB));
        assertFalse(QuotaResume.userOverQuota(0L, GB, GB));
        assertFalse(QuotaResume.userTunnelOverQuota(null, GB, GB));
        assertFalse(QuotaResume.userTunnelOverQuota(0L, GB, GB));
    }

    @Test
    void pausedForwardOfActiveUserIsResumed() {
        assertTrue(QuotaResume.shouldResume(forward(0), user(1, null), null, true, NOW));
    }

    @Test
    void runningForwardIsNeverTouched() {
        assertFalse(QuotaResume.shouldResume(forward(1), user(1, null), null, true, NOW),
                "已经在跑的转发不该被重复恢复");
    }

    @Test
    void expiredOrDisabledUserBlocksResume() {
        assertFalse(QuotaResume.shouldResume(forward(0), user(0, null), null, true, NOW),
                "账号停用后不应恢复");
        assertFalse(QuotaResume.shouldResume(forward(0), user(1, NOW - 1), null, true, NOW),
                "账号到期后不应恢复（到期暂停和配额暂停要区分）");
    }

    @Test
    void expiredOrDisabledUserTunnelBlocksResume() {
        assertFalse(QuotaResume.shouldResume(forward(0), user(1, null), userTunnel(0, null), true, NOW));
        assertFalse(QuotaResume.shouldResume(forward(0), user(1, null), userTunnel(1, NOW - 1), true, NOW));
    }

    @Test
    void noQuotaEvidenceMeansManualPauseIsRespected() {
        assertFalse(QuotaResume.shouldResume(forward(0), user(1, null), null, false, NOW),
                "重置前没超额，说明是用户手动暂停，不能自动恢复");
    }

    @Test
    void denyReasonNamesTheRealBlocker() {
        // round19 补：日志原来一律写「账号或用户隧道已过期/停用」，把手动暂停也报成过期
        Forward manual = forward(0);
        manual.setPauseReason(PauseReason.MANUAL);
        assertEquals("暂停原因=手动暂停", QuotaResume.denyReason(manual, user(1, null), null, true, NOW));
        assertEquals("账号已停用", QuotaResume.denyReason(forward(0), user(0, null), null, true, NOW));
        assertEquals("账号已过期", QuotaResume.denyReason(forward(0), user(1, NOW - 1), null, true, NOW));
        assertEquals("用户隧道已停用",
                QuotaResume.denyReason(forward(0), user(1, null), userTunnel(0, null), true, NOW));
        assertEquals("重置前未超额", QuotaResume.denyReason(forward(0), user(1, null), null, false, NOW));
        assertNull(QuotaResume.denyReason(forward(0), user(1, null), null, true, NOW));
    }

    private Forward forward(int status) {
        Forward forward = new Forward();
        forward.setId(1L);
        forward.setUserId(1);
        forward.setTunnelId(1);
        forward.setStatus(status);
        return forward;
    }

    private User user(int status, Long expTime) {
        User user = new User();
        user.setId(1L);
        user.setStatus(status);
        user.setExpTime(expTime);
        user.setFlow(1L);
        return user;
    }

    private UserTunnel userTunnel(int status, Long expTime) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(1);
        userTunnel.setUserId(1);
        userTunnel.setTunnelId(1);
        userTunnel.setStatus(status);
        userTunnel.setExpTime(expTime);
        return userTunnel;
    }
}
