package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.common.nodehealth.NodeHealthViewService;
import com.admin.common.utils.MapParamUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 节点健康只读接口：给仪表板一个稳定、可读的「上次检测真实状态」来源。
 *
 * <p>安全边界与其它 {@code /api/v1/**} 一致：JWT 拦截器校验登录态，{@code @RequireRole}
 * 再收紧到管理员；三个接口都是 POST + 只读，不会触发探测、不会改动任何节点状态，
 * 因此可以被仪表板按任意频率刷新。
 *
 * <p>语义提醒：返回里的 {@code status} 是上次探测结论（过期会降为 UNKNOWN 并保留 checkedAt），
 * {@code connection} 是当前控制通道是否在，{@code dbOnline} 才是库里那条历史标记。
 * 三者不可互相替代——把 {@code dbOnline=true} 当成在线正是这次要修的问题。
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/node/health")
public class NodeHealthController extends BaseController {

    private static final int PARAM_ERROR_CODE = -2;

    @Autowired
    private NodeHealthViewService nodeHealthViewService;

    /** 全局摘要：节点计数、上次轮次、探测节奏与状态字典。 */
    @LogAnnotation
    @RequireRole
    @PostMapping("/summary")
    public R summary() {
        return R.ok(nodeHealthViewService.summary());
    }

    /** 按隧道展示：每条隧道下的节点名称、角色、跳序与上次检测结论。 */
    @LogAnnotation
    @RequireRole
    @PostMapping("/tunnels")
    public R tunnels() {
        return R.ok(nodeHealthViewService.tunnels());
    }

    /** 单节点详情：状态 + 它出现在哪些隧道的哪个位置。入参 {@code {"nodeId":7}}。 */
    @LogAnnotation
    @RequireRole
    @PostMapping("/node")
    public R node(@RequestBody(required = false) Map<String, Object> params) {
        Long nodeId = MapParamUtils.toLong(params, "nodeId");
        if (nodeId == null) {
            return R.err(PARAM_ERROR_CODE, MapParamUtils.error(params, "nodeId"));
        }
        Map<String, Object> data = nodeHealthViewService.node(nodeId);
        if (data == null) {
            return R.err(PARAM_ERROR_CODE, "节点不存在");
        }
        return R.ok(data);
    }
}
