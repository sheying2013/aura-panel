package com.admin.common.nodehealth;

/**
 * 探测通道：对某个节点做一次「发一条命令、等一个回包」的往返。
 *
 * <p>抽成接口是为了让 {@link NodeProbeService} 的判定逻辑（无会话 / 无回包 / 有回包 / 异常）
 * 可以脱开真实连接做单测，也使仪表板刷新路径在测试里可以被断定为「没有发起任何探测」。
 */
@FunctionalInterface
public interface NodeProbeTransport {

    /** 对节点做一次往返探测，任何情况下都要返回结果，不向上抛异常。 */
    Reply probe(Long nodeId);

    /**
     * 往返结果：{@code responded=false} 表示没拿到回包（超时、通道不在、下发失败），
     * 此时 {@code detail} 必须带上可读原因，便于直接展示给运维。
     */
    record Reply(boolean responded, long latencyMs, String detail) {

        public static Reply ok(long latencyMs, String detail) {
            return new Reply(true, latencyMs, detail);
        }

        public static Reply noReply(String detail) {
            return new Reply(false, -1L, detail);
        }
    }
}
