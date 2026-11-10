package com.admin.service;

import com.admin.common.dto.SpeedLimitDto;
import com.admin.common.dto.SpeedLimitUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.SpeedLimit;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 限速规则服务类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-04
 */
public interface SpeedLimitService extends IService<SpeedLimit> {

    /**
     * 创建限速规则
     * @param speedLimitDto 限速规则数据
     * @return 结果
     */
    R createSpeedLimit(SpeedLimitDto speedLimitDto);

    /**
     * 获取所有限速规则
     * @return 结果
     */
    R getAllSpeedLimits();

    /**
     * 更新限速规则
     * @param speedLimitUpdateDto 更新数据
     * @return 结果
     */
    R updateSpeedLimit(SpeedLimitUpdateDto speedLimitUpdateDto);

    /**
     * 删除限速规则
     * @param id 限速规则ID
     * @return 结果
     */
    R deleteSpeedLimit(Long id);

    /**
     * 节点重连/丢配置后按库补齐该节点应持有的限流器。
     *
     * <p>限流器只在 createSpeedLimit 时下发一次，节点 gost.json 丢失后限流器一起消失，
     * 节点上的服务仍然带着 limiter 引用却查不到对应限流器，限速静默失效（面板全绿）。
     *
     * @param nodeId 上报节点
     * @return 本次动作说明（空表示无需补，或本来就一致）
     */
    java.util.List<String> resyncNodeLimiters(Long nodeId);

    /**
     * 按节点本次上报的 limiters 校准该节点上每个限流器的速率。
     *
     * <p>resyncNodeLimiters 只看「库里有没有」，节点上同名限流器速率被改成别的值
     * （手工改 gost.json、旧版本下发错值）时既不报错也不纠正，面板与节点长期不一致。
     *
     * <p>判据两侧都确定：名字 = speed_limit.id，期望值 = 按库里 speed 换算出的 limits 字符串。
     * 缺失补 AddLimiters、不一致走 UpdateLimiters、一致不动；单条失败只记录，不影响其余条目。
     *
     * @param nodeId           上报节点
     * @param reportedLimiters 节点自报的 name → limits（调用方已按 name 去重；空表示清单不可信）
     * @return 每条处置说明（调用方逐条记 WARN）
     */
    java.util.List<String> reconcileReportedLimiters(Long nodeId,
                                                     java.util.Map<String, java.util.List<String>> reportedLimiters);
}
