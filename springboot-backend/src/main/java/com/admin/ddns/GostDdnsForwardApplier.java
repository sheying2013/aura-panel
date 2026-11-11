package com.admin.ddns;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import org.springframework.stereotype.Component;

/** 把 DDNS 目标按 ForwardServiceImpl 的同一口径重新下发给入口节点。 */
@Component
public class GostDdnsForwardApplier implements DdnsForwardApplier {

    @Override
    public boolean isOnline(Long nodeId) {
        return nodeId != null && WebSocketServer.isNodeOnline(nodeId);
    }

    @Override
    public String update(DdnsTarget target) {
        try {
            // 切换窗口内必须用 target 自带的目标列表：注册表此刻仍是已确认的旧值，
            // 走它渲染会把刚推下去的新地址改回旧地址。
            GostDto result = GostUtil.AddAndUpdateService(
                    target.serviceName(), target.limiter(), target.node(),
                    target.forward(), target.forwardPort(), target.tunnel(),
                    target.renderedTargets().isEmpty() ? null : target.renderedTargets(), "UpdateService");
            return result == null ? "节点无响应" : result.getMsg();
        } catch (RuntimeException e) {
            return "下发异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
