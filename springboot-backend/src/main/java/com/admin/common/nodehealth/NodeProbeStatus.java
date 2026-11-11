package com.admin.common.nodehealth;

/**
 * 节点探测结论。
 *
 * <p>只回答一个问题：面板对节点 <b>agent 控制通道</b>「此刻是否可达」掌握到什么程度。
 * 它不是业务可达性：控制通道通只说明命令能送达并回包，业务端口能不能转发是另一条链路。
 * {@link #UNKNOWN} 是所有「没测过 / 测不准 / 结论过期」的归宿——把未知渲染成在线，
 * 正是本功能要修的病（节点库里 status=1 只是上次 WS 建连的落库痕迹，断连后不会因心跳超时回写）。
 */
public enum NodeProbeStatus {

    /** 探测命令拿到了节点回包：控制通道和节点侧命令处理链路都是活的（不代表业务数据面通）。 */
    ONLINE("ONLINE", "控制通道往返正常", 0),

    /** 没有可信结论：从未探测、探测异常，或上次结论已过期。任何展示都不得折算成在线。 */
    UNKNOWN("UNKNOWN", "未知（尚未检测或结果过期）", 1),

    /** 控制通道会话在（{@code WebSocketServer.isNodeOnline} 为真），但探测命令没有回包。 */
    DEGRADED("DEGRADED", "控制通道在，但命令无回包", 2),

    /** 控制通道不在：无会话或心跳静默超过 30 秒。 */
    OFFLINE("OFFLINE", "控制通道未连接", 3);

    private final String code;
    private final String label;
    private final int severity;

    NodeProbeStatus(String code, String label, int severity) {
        this.code = code;
        this.label = label;
        this.severity = severity;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 越严重数值越大，用于把一组节点的结论汇总成一条（取最坏）。 */
    public int severity() {
        return severity;
    }

    /** 取更严重的一个；两侧都为 null 时返回 {@link #UNKNOWN}，避免空集合被说成在线。 */
    public static NodeProbeStatus worst(NodeProbeStatus left, NodeProbeStatus right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.severity >= right.severity ? left : right;
    }

    /** 按 code 反查，未知 code 一律落到 {@link #UNKNOWN}。 */
    public static NodeProbeStatus fromCode(String code) {
        if (code != null) {
            for (NodeProbeStatus status : values()) {
                if (status.code.equals(code)) {
                    return status;
                }
            }
        }
        return UNKNOWN;
    }
}
