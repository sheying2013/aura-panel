package com.admin.common.nodehealth;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 只存在内存里的账本：单测与「不需要跨重启保证」的临时装配使用。
 *
 * <p>生产装配必须用 {@link FileNodeProbeStateStore}，否则重启后节流记录清零。
 */
public class InMemoryNodeProbeStateStore implements NodeProbeStateStore {

    private final AtomicReference<NodeProbeState> state = new AtomicReference<>(NodeProbeState.empty());

    @Override
    public NodeProbeState load() {
        return state.get();
    }

    @Override
    public void save(NodeProbeState value) {
        state.set(value == null ? NodeProbeState.empty() : value);
    }
}
