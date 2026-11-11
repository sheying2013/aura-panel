package com.admin.common.nodehealth;

/**
 * 探测节奏账本的持久化入口。
 *
 * <p>契约：{@link #load()} 永不向上抛异常（读不到就返回保守估计，避免启动即崩）；
 * {@link #save(NodeProbeState)} 失败必须抛 {@link NodeProbeStateException}，由调用方决定放弃本轮。
 */
public interface NodeProbeStateStore {

    NodeProbeState load();

    void save(NodeProbeState state);
}
