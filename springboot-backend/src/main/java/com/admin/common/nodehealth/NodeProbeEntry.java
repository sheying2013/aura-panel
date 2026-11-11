package com.admin.common.nodehealth;

/**
 * 一条随账本一起落盘的单节点结论。
 *
 * <p>为什么需要 {@code nodeIdentity}：账本是按节点 ID 索引的，而 {@code node} 表的 ID 会被复用
 * （删掉一台机器、再加一台新机器，自增 ID 可能回到同一个值）。只按 ID 认结论，新机器会直接顶着
 * 上一台机器的 ONLINE 出现在面板上——这正是「用历史记录谎称在线」的另一种形态。
 *
 * <p>优先用 {@code createdTime} 区分节点记录，老数据没有该字段时退回节点名；这些字段并非全局唯一，
 * 同一时间戳或同名重建仍可能碰撞。身份缺失时展示层按未知处理。
 */
public record NodeProbeEntry(Long nodeId, String nodeIdentity, NodeProbeResult result) {

    public NodeProbeEntry {
        if (nodeId == null) {
            throw new IllegalArgumentException("节点 ID 不能为空");
        }
        if (result == null) {
            throw new IllegalArgumentException("节点探测结论不能为空");
        }
    }

    /**
     * 指定身份时，历史身份缺失或不相等都视为不可信；null 参数用于兼容仅按 ID 读取的旧调用。
     */
    boolean identityConflicts(String identity) {
        return identity != null && !identity.equals(nodeIdentity);
    }
}
