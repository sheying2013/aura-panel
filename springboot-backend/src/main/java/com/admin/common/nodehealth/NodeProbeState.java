package com.admin.common.nodehealth;

import java.util.List;

/**
 * 跨重启的探测账本：节流时间 + 最近一轮的逐节点结论。
 *
 * <p>{@code lastAttemptAt} 是「开始探测节点」这个动作的时间，在真正下发探测前就落盘（预占），
 * 所以进程在探测中途被杀，重启后仍会认为这 12 小时已经用过一次，不会重复打点。
 * {@code lastCompletedAt} 只用于展示「上一轮什么时候跑完」，不参与节流判定。
 *
 * <p>{@code results} 是最近一次<b>完整跑完</b>的轮次留下的逐节点结论，与节流时间同一次原子写落盘：
 * 只写轮次时间不写结论，重启后整段时间里所有节点都只能报未知；而把结论单独写一个文件，
 * 又多出一份可能与节流记录不同步的状态。同一个文件、同一次改名，两者要么一起更新、要么一起不动。
 *
 * <p>{@code lastRound} 是那一轮的聚合计数（总数与各状态数量、起止时间）。它必须跟着同一份账本落盘：
 * 这些数字只属于跑完它的那一刻，事后<b>不能</b>从 {@code results} 反推——结论集会在下一轮按当时的节点表裁剪，
 * 节点增删之后重算出来的 total/online 与当初那一轮根本不是一回事，等于凭空编一轮历史。
 * 旧账本没有这个字段，读出来是 null，界面上就是「还没有上一轮摘要」，不补造。
 */
public record NodeProbeState(long lastAttemptAt, long lastCompletedAt, String lastTrigger,
                             List<NodeProbeEntry> results, NodeProbeService.ProbeRound lastRound) {

    public NodeProbeState {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** 兼容只关心节流时间的调用方（旧账本、单测）：没有结论集与轮次摘要。 */
    public NodeProbeState(long lastAttemptAt, long lastCompletedAt, String lastTrigger) {
        this(lastAttemptAt, lastCompletedAt, lastTrigger, List.of(), null);
    }

    /** 兼容只带结论集、不带轮次摘要的调用方。 */
    public NodeProbeState(long lastAttemptAt, long lastCompletedAt, String lastTrigger,
                          List<NodeProbeEntry> results) {
        this(lastAttemptAt, lastCompletedAt, lastTrigger, results, null);
    }

    public static NodeProbeState empty() {
        return new NodeProbeState(0L, 0L, null, List.of(), null);
    }

    public boolean hasAttempt() {
        return lastAttemptAt > 0L;
    }

    /** 预占新一轮：上一轮的完成时间、结论集与轮次摘要原样保留，直到这一轮真正跑完。 */
    public NodeProbeState reserved(long at, String trigger) {
        return new NodeProbeState(at, lastCompletedAt, trigger, results, lastRound);
    }

    public NodeProbeState completed(long at, String trigger) {
        return new NodeProbeState(lastAttemptAt, at, trigger, results, lastRound);
    }

    /** 只替换结论集，节流时间与轮次摘要原样保留。 */
    public NodeProbeState withResults(List<NodeProbeEntry> entries) {
        return new NodeProbeState(lastAttemptAt, lastCompletedAt, lastTrigger, entries, lastRound);
    }

    /** 只替换轮次摘要，节流时间与结论集原样保留。 */
    public NodeProbeState withRound(NodeProbeService.ProbeRound round) {
        return new NodeProbeState(lastAttemptAt, lastCompletedAt, lastTrigger, results, round);
    }
}
