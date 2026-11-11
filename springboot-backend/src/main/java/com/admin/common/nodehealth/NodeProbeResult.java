package com.admin.common.nodehealth;

/**
 * 一次探测的结论，以及「这个结论是什么时候、以多少延迟得到的」。
 *
 * <p>结论只覆盖 agent 控制通道，不代表节点承载的业务流量。
 * {@code checkedAt == 0} 表示从未探测（{@link #never()}）：这种结果不能出现在「在线」里，
 * 调用方必须能从 {@link #probed()} 区分「测过但失败」和「根本没测过」。
 * {@code latencyMs < 0} 表示没有测到往返耗时（通道不在、命令无回包、探测抛异常）。
 */
public record NodeProbeResult(NodeProbeStatus status, long checkedAt, long latencyMs, String detail) {

    public NodeProbeResult {
        if (status == null) {
            status = NodeProbeStatus.UNKNOWN;
        }
        if (detail == null || detail.isBlank()) {
            detail = status.label();
        }
    }

    /** 尚未探测过：状态未知、无时间戳。 */
    public static NodeProbeResult never() {
        return new NodeProbeResult(NodeProbeStatus.UNKNOWN, 0L, -1L, "尚未检测");
    }

    public static NodeProbeResult of(NodeProbeStatus status, long checkedAt, long latencyMs, String detail) {
        return new NodeProbeResult(status, checkedAt, latencyMs, detail);
    }

    /** 是否真的测过（有过一次探测结论，无论成败）。 */
    public boolean probed() {
        return checkedAt > 0L;
    }

    /**
     * 控制通道是否已确认可达：只有 {@link NodeProbeStatus#ONLINE} 才算。
     * DEGRADED / OFFLINE / UNKNOWN 一律不算——名字里带 controlPlane 是为了不让调用方
     * 把它读成「节点业务可达」。
     */
    public boolean controlPlaneReachable() {
        return status == NodeProbeStatus.ONLINE;
    }

    /**
     * 结论过期后的视图：状态降为 {@link NodeProbeStatus#UNKNOWN}，但保留上次检测时间与延迟，
     * 这样界面能同时说清「现在不知道」和「上次是什么时候测的」。
     */
    public NodeProbeResult asStale() {
        if (!probed()) {
            return this;
        }
        return new NodeProbeResult(NodeProbeStatus.UNKNOWN, checkedAt, latencyMs,
                "上次检测结论已过期（历史结论 " + status.code() + "：" + detail + "）");
    }
}
