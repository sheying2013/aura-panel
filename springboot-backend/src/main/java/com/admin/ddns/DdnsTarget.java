package com.admin.ddns;

import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;

import java.util.List;

/**
 * 一次 DDNS 热更新需要重下发的单个入口节点服务。
 *
 * <p>{@code renderedTargets} 是本次窗口内真正要写进 forwarder.nodes[].addr 的目标列表，
 * 由 {@link DdnsAddressRegistry#renderTargets(String, String, String)} 显式渲染：
 * 新主地址（apply）、旧主地址（回滚）或域名本身（删除回落）。DDNS 下发窗口内绝不能
 * 让 {@code GostUtil} 回头去读注册表 —— 注册表此时仍是已确认的旧值，读它等于把节点改回去。
 *
 * <p>为空表示走老口径（按注册表渲染），仅用于不涉及切换的调用方。
 */
public record DdnsTarget(
        String serviceName,
        Integer limiter,
        Node node,
        Forward forward,
        ForwardPort forwardPort,
        Tunnel tunnel,
        List<String> renderedTargets
) {

    public DdnsTarget {
        renderedTargets = renderedTargets == null ? List.of() : List.copyOf(renderedTargets);
    }

    public DdnsTarget(String serviceName, Integer limiter, Node node, Forward forward,
                      ForwardPort forwardPort, Tunnel tunnel) {
        this(serviceName, limiter, node, forward, forwardPort, tunnel, List.of());
    }

    /** 用本窗口的目标列表渲染同一条服务的另一个节点。 */
    public DdnsTarget withNode(Node other) {
        return new DdnsTarget(serviceName, limiter, other, forward, forwardPort, tunnel, renderedTargets);
    }

    public boolean hasRenderedTargets() {
        return !renderedTargets.isEmpty();
    }
}
