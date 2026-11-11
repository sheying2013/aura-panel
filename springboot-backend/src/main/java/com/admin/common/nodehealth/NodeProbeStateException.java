package com.admin.common.nodehealth;

/**
 * 探测节奏状态持久化失败。
 *
 * <p>「12 小时最多探测一次」这条保证依赖把上一次探测时间落盘。预占时间写不进去时，
 * 本轮探测必须放弃：宁可这一轮没有新结论，也不能在无法记账的情况下对节点重复打点。
 */
public class NodeProbeStateException extends RuntimeException {

    public NodeProbeStateException(String message) {
        super(message);
    }

    public NodeProbeStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
