package com.admin.common.utils;

/**
 * 转发暂停原因（持久化到 forward.pause_reason，round19）。
 *
 * <p>背景：库里的 status=0 只说明「停了」，说不出「谁停的」。于是流量重置时只能靠
 * 「重置前是否超额」去猜，手动暂停的转发只要恰好处于超额状态就会被一并放开（round18 遗留缺口）；
 * 到期/停用暂停的转发也没有任何标记，续费后无法自动恢复。
 *
 * <p>取值只有四种，存量行（算法上线前的数据）为 null，读取方必须把 null 当作「未标注」，
 * 走旧的推断口径，不能当作「手动暂停」直接否定，否则存量环境会集体停在暂停态。
 */
public final class PauseReason {

    /** 用户在面板上手动暂停（/forward/pause）。 */
    public static final String MANUAL = "manual";

    /** 流量超额触发的自动暂停（用户额度或用户隧道额度）。 */
    public static final String QUOTA = "quota";

    /** 到期触发的自动暂停（用户到期、用户隧道到期）。 */
    public static final String EXPIRY = "expiry";

    /** 被停用触发的自动暂停（用户 status!=1、用户隧道 status!=1）。 */
    public static final String DISABLED = "disabled";

    private PauseReason() {
    }

    /** 是否配额暂停（只有这一类允许在流量重置后自动恢复）。 */
    public static boolean isQuota(String reason) {
        return QUOTA.equals(reason);
    }

    /** 是否未标注（存量数据）。 */
    public static boolean isUnlabeled(String reason) {
        return reason == null || reason.isEmpty();
    }

    /** 给日志/前端看的可读名。 */
    public static String label(String reason) {
        if (reason == null) {
            return "未标注";
        }
        switch (reason) {
            case MANUAL:
                return "手动暂停";
            case QUOTA:
                return "配额暂停";
            case EXPIRY:
                return "到期暂停";
            case DISABLED:
                return "停用暂停";
            default:
                return reason;
        }
    }
}
