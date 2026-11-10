package com.admin.common.utils;

import com.admin.entity.Forward;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;

import java.util.Objects;

/**
 * 流量重置后的「配额暂停」恢复判据。
 *
 * <p>背景（round18）：配额已超时 {@link com.admin.controller.FlowController} 会把转发暂停并落库（status=0），
 * 但库里没有任何字段记录「这条转发是被配额停的、还是用户手动停的、还是到期停的」。
 * 于是流量重置（手动 {@code /user/reset} 或每天 0 点的 {@code ResetFlowAsync}）只清了 in_flow/out_flow，
 * 被暂停的转发永远停在暂停态——用户额度恢复了，业务却还是断的。
 *
 * <p>这里不引入新表/新列，改用「重置前的总量是否超额」推断原因：调用方在把流量清零**之前**
 * 用 {@link #userOverQuota} / {@link #userTunnelOverQuota} 判一次，把结果传进来。
 * 这样既能恢复真正被配额停掉的转发，又不会把用户手动暂停的转发一起放开
 * （手动暂停时账号通常并没有超额；即便恰好超额，也还有状态/到期闸门兜底）。
 *
 * <p>判据口径必须与 {@link com.admin.controller.FlowController} 保持一致，否则会出现
 * 「暂停按 A 口径、恢复按 B 口径」的错位：用户是「额度 &lt; 已用」才停（严格大于），
 * 用户隧道是「已用 &gt;= 额度」就停。
 */
public final class QuotaResume {

    /** 1GB = 1024^3 字节，与 FlowController#BYTES_TO_GB 一致。 */
    public static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    private QuotaResume() {
    }

    /**
     * 用户是否已超额（口径同 FlowController#checkUserRelatedLimits：{@code limit < in + out}）。
     *
     * @param flowGb 用户额度（GB）；null 或 &lt;=0 视为不限量，永不因配额暂停
     */
    public static boolean userOverQuota(Long flowGb, Long in, Long out) {
        if (flowGb == null || flowGb <= 0) {
            return false;
        }
        long limit = flowGb * BYTES_TO_GB;
        return limit < safe(in) + safe(out);
    }

    /**
     * 用户隧道是否已超额（口径同 FlowController#checkUserTunnelRelatedLimits：{@code in + out >= limit}）。
     *
     * @param flowGb 用户隧道额度（GB）；null 或 &lt;=0 视为不限量
     */
    public static boolean userTunnelOverQuota(Long flowGb, Long in, Long out) {
        if (flowGb == null || flowGb <= 0) {
            return false;
        }
        long limit = flowGb * BYTES_TO_GB;
        return safe(in) + safe(out) >= limit;
    }

    /**
     * 这条转发是否应因「配额暂停」在流量重置后自动恢复。
     *
     * <p>除了配额推断，还必须过四道闸门，避免把「到期/停用」状态误恢复：
     * 转发本身是暂停态、账号是启用且未过期、所属用户隧道（若有）是启用且未过期。
     * 「另一维度配额是否仍超额」由调用方判断——只重置用户额度时若用户隧道仍超，
     * 恢复了也会被下一次流量上报立刻再停，所以不进 {@code toResume}。
     *
     * @param quotaExceededBefore 重置前该维度是否超额（必须在清零前算）
     */
    public static boolean shouldResume(Forward forward, User user, UserTunnel userTunnel,
                                       boolean quotaExceededBefore, long now) {
        return denyReason(forward, user, userTunnel, quotaExceededBefore, now) == null;
    }

    /**
     * 同 {@link #shouldResume}，但返回「为什么不能恢复」的可读原因；可以恢复时返回 {@code null}。
     *
     * <p>round19 补：原来日志一律写「账号或用户隧道已过期/停用」，把「手动暂停」也报成过期，
     * 排障时看不到真正原因。判据与 {@link #shouldResume} 完全同源，避免两套口径漂移。
     */
    public static String denyReason(Forward forward, User user, UserTunnel userTunnel,
                                    boolean quotaExceededBefore, long now) {
        if (forward == null || forward.getId() == null) {
            return "转发记录不存在";
        }
        if (!Objects.equals(forward.getStatus(), 0)) {
            return "当前不是暂停态";
        }
        if (user == null) {
            return "账号不存在";
        }
        if (!Objects.equals(user.getStatus(), 1)) {
            return "账号已停用";
        }
        if (expired(user.getExpTime(), now)) {
            return "账号已过期";
        }
        // round19：有明确暂停原因时以原因为准，只有「配额暂停」才允许自动恢复；
        // 手动/到期/停用一律不碰。存量行 pause_reason 为 null，退回到调用方传入的超额推断。
        String reason = forward.getPauseReason();
        if (!PauseReason.isUnlabeled(reason) && !PauseReason.isQuota(reason)) {
            return "暂停原因=" + PauseReason.label(reason);
        }
        if (userTunnel != null) {
            if (!Objects.equals(userTunnel.getStatus(), 1)) {
                return "用户隧道已停用";
            }
            if (expired(userTunnel.getExpTime(), now)) {
                return "用户隧道已过期";
            }
        }
        if (!quotaExceededBefore) {
            return "重置前未超额";
        }
        return null;
    }

    /**
     * 账号/用户隧道此刻是否可用：启用（status==1）且未过期。
     *
     * <p>round20：续期/启用后恢复「到期/停用暂停」的转发时，用它做闸门，避免
     * 只改了到期时间但账号仍是停用态、或反过来，就把转发误放开。
     */
    public static boolean usable(Integer status, Long expTime, long now) {
        return Objects.equals(status, 1) && !expired(expTime, now);
    }

    private static boolean expired(Long expTime, long now) {
        return expTime != null && expTime > 0 && expTime <= now;
    }

    private static long safe(Long value) {
        return value == null ? 0L : value;
    }
}
