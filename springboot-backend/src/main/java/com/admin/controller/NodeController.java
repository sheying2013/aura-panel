package com.admin.controller;


import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.DriftCircuitBreaker;
import com.admin.common.utils.MapParamUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/node")
public class NodeController extends BaseController {

    private static final int PARAM_ERROR_CODE = -2;

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody NodeDto nodeDto) {
        return nodeService.createNode(nodeDto);
    }


    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R list() {
        return nodeService.getAllNodes();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody NodeUpdateDto nodeUpdateDto) {
        return nodeService.updateNode(nodeUpdateDto);
    }

    /**
     * 漂移/补齐下发的熔断快照（QA 审计 P0-3：熔断过去只在内存里，面板上一切正常，运维看不见）。
     *
     * <p>返回每个资源 key 的连续失败次数、是否在冷却、剩余毫秒、最近一次库侧/节点侧摘要。
     * key 形如 {@code service|2|3_1_0}、{@code relay|2|9_tls}、{@code chain|2|chains_9}。
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/breaker")
    public R breaker() {
        Map<String, Map<String, Object>> states = DriftCircuitBreaker.snapshotAll();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("threshold", DriftCircuitBreaker.DEFAULT_THRESHOLD);
        data.put("cooldownMs", DriftCircuitBreaker.DEFAULT_COOLDOWN_MS);
        data.put("instances", DriftCircuitBreaker.liveInstances());
        data.put("size", states.size());
        data.put("states", states);
        return R.ok(data);
    }

    /**
     * 手动放行熔断（round38 遗留 3）：熔断是纯内存态，默认冷却 15 分钟，运维修好节点侧口径后
     * 需要能立刻让资源重试，而不是干等冷却到期。
     *
     * <p>入参三选一，优先级 {@code key > nodeId > all}：
     * {@code {"key":"service|7|41_1_0"}}、{@code {"nodeId":7}}、{@code {"all":true}}。
     * {@code all=true} 只在 key 与 nodeId 都缺失时才生效，避免「顺手带了个 key 却把全部状态清了」。
     * 返回 {@code {"released":<int>,"mode":"key|node|all"}}。
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/breaker/release")
    public R releaseBreaker(@RequestBody(required = false) Map<String, Object> params) {
        Object rawKey = params == null ? null : params.get("key");
        String key = rawKey == null ? null : rawKey.toString().trim();
        if (key != null && key.isEmpty()) {
            key = null;
        }
        Object rawNodeId = params == null ? null : params.get("nodeId");
        Long nodeId = MapParamUtils.toLong(params, "nodeId");
        if (key != null) {
            return R.ok(releaseResult(DriftCircuitBreaker.releaseEverywhere(key), "key"));
        }
        // 传了 nodeId 但不是合法数字：报错而不是落到 all，否则一个手滑就清空全表状态
        if (rawNodeId != null && nodeId == null && !rawNodeId.toString().trim().isEmpty()) {
            return R.err(PARAM_ERROR_CODE, MapParamUtils.error(params, "nodeId"));
        }
        if (nodeId != null) {
            return R.ok(releaseResult(DriftCircuitBreaker.releaseByNodeEverywhere(nodeId), "node"));
        }
        if (isTrue(params == null ? null : params.get("all"))) {
            return R.ok(releaseResult(DriftCircuitBreaker.releaseAllEverywhere(), "all"));
        }
        return R.err("请指定 key、nodeId 或 all");
    }

    private static Map<String, Object> releaseResult(int released, String mode) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("released", released);
        data.put("mode", mode);
        return data;
    }

    /** JSON 里 all 可能给 true / "true" / "TRUE"：只把这几形态当开启，其余一律视为没给。 */
    private static boolean isTrue(Object raw) {
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        return raw != null && "true".equalsIgnoreCase(raw.toString().trim());
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = MapParamUtils.toLong(params, "id");
        if (id == null) {
            return R.err(PARAM_ERROR_CODE, MapParamUtils.error(params, "id"));
        }
        return nodeService.deleteNode(id);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/install")
    public R getInstallCommand(@RequestBody Map<String, Object> params) {
        Long id = MapParamUtils.toLong(params, "id");
        if (id == null) {
            return R.err(PARAM_ERROR_CODE, MapParamUtils.error(params, "id"));
        }
        return nodeService.getInstallCommand(id);
    }

}
