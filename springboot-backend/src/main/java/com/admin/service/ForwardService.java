package com.admin.service;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface ForwardService extends IService<Forward> {

    /**
     * 创建端口转发
     * @param forwardDto 转发数据
     * @return 结果
     */
    R createForward(ForwardDto forwardDto);

    /**
     * 获取端口转发列表
     * @return 结果
     */
    R getAllForwards();

    /**
     * 更新端口转发
     * @param forwardUpdateDto 更新数据
     * @return 结果
     */
    R updateForward(ForwardUpdateDto forwardUpdateDto);

    /**
     * 删除端口转发
     * @param id 转发ID
     * @return 结果
     */
    R deleteForward(Long id);

    /**
     * 删除转发。
     * <p>
     * strict=true 时，只要有一个入口节点没删掉，就不动数据库并返回错误——
     * 供「撤销用户隧道权限」这类语义严格的场景使用：节点离线时若先删库，
     * 节点上的监听会继续放量，而权限记录已经消失，用户连重试入口都没有。
     */
    R deleteForward(Long id, boolean strict);

    /**
     * 强制删除端口转发
     * 跳过GOST节点验证，直接删除数据库记录
     * @param id 转发ID
     * @return 结果
     */
    R forceDeleteForward(Long id);

    /**
     * 暂停转发服务
     * @param id 转发ID
     * @return 结果
     */
    R pauseForward(Long id);

    /**
     * 恢复转发服务
     * @param id 转发ID
     * @return 结果
     */
    R resumeForward(Long id);

    /**
     * 转发诊断功能
     * @param id 转发ID
     * @return 诊断结果
     */
    R diagnoseForward(Long id);

    /**
     * 更新转发排序
     * @param params 包含forwards数组的参数
     * @return 更新结果
     */
    R updateForwardOrder(Map<String, Object> params);

    /**
     * 节点重连后按数据库补齐缺失的入口服务（以节点自报清单为准，整对缺失才补）。
     *
     * <p>没有链清单的旧调用方走这里：chains 按“一条都没有”处理，与节点上报语义一致。
     *
     * @param nodeId           上报清单的节点
     * @param reportedServices 节点自报的服务名列表
     * @return 处置说明，空表示无需处理
     */
    List<String> resyncNodeServices(Long nodeId, List<String> reportedServices);

    /**
     * 节点重连后按数据库补齐缺失的入口服务与中继链（chains_* / &lt;tunnelId&gt;_tls）。
     *
     * <p>只按节点自报清单判断缺什么，只补仍被活跃转发引用的隧道；
     * 同一隧道先补 chains 再补 service（service 的 handler 引用 chains）。
     *
     * @param nodeId           上报清单的节点
     * @param reportedServices 节点自报的服务名列表
     * @param reportedChains   节点自报的链名列表
     * @return 处置说明，空表示无需处理
     */
    List<String> resyncNodeServices(Long nodeId, List<String> reportedServices, List<String> reportedChains);

    /**
     * 在上面三参版本之上，额外按节点上报的配置摘要纠正「名字对得上、配置却不一样」的漂移。
     *
     * <p>只比服务名的补齐规则看不见端口/协议/链/目标被改过：面板显示正常，实际转发到旧目标。
     *
     * @param nodeId            上报清单的节点
     * @param reportedServices  节点自报的服务名列表
     * @param reportedChains    节点自报的链名列表
     * @param reportedDigests   节点自报的服务摘要（key=服务名，value=规范化摘要串）；
     *                          null 表示老节点没报摘要或这次上报为空，只按名字对账
     * @return 处置说明，空表示无需处理
     */
    List<String> resyncNodeServices(Long nodeId, List<String> reportedServices, List<String> reportedChains,
                                    Map<String, String> reportedDigests);

    /**
     * 再额外按节点上报的链摘要纠正「链名对得上、下一跳却不一样」的漂移。
     *
     * <p>只比链名的补齐规则看不见 <code>chains_*</code> 里 hop 地址/端口/协议被改过：
     * 链在、名字对，流量照样打到已经不存在的跳点，而且不会有任何报错。
     *
     * @param reportedChainDigests 节点自报的链摘要（key=链名，value=下一跳节点列表）；
     *                             null 表示老节点没报，只按链名对账
     */
    List<String> resyncNodeServices(Long nodeId, List<String> reportedServices, List<String> reportedChains,
                                    Map<String, String> reportedDigests,
                                    Map<String, List<String>> reportedChainDigests);

    /**
     * 流量重置后恢复「因配额暂停」的转发（round18）。
     *
     * <p>库里没有暂停原因字段，故原因由调用方在清零前用「是否超额」推断后传入；
     * 恢复动作与手动恢复同口径（applyStatusChange：先落库 status=1，再逐入口下发 ResumeService，
     * 节点离线只记说明不回滚，由 WebSocketServer 的重连对账兜底）。
     *
     * @param userId            被重置的用户
     * @param userWasOverQuota  重置前该用户是否已超额（必须在清零前算）
     * @param now               当前时间戳，用于到期闸门判断
     * @return 未执行的节点说明；空表示没有需要恢复的转发或全部执行成功
     */
    List<String> resumeQuotaPausedForUser(Long userId, boolean userWasOverQuota, long now);

    /**
     * 流量重置后恢复「因配额暂停」的转发（用户隧道维度，round18）。
     *
     * <p>只重置了某条 user_tunnel 的流量时，若账号本身仍超额则保持暂停——恢复了也会被下一次上报再停。
     *
     * @param userTunnelId          被重置的用户隧道
     * @param userTunnelWasOverQuota 重置前该用户隧道是否已超额（必须在清零前算）
     * @param now                   当前时间戳
     * @return 未执行的节点说明；空表示没有需要恢复的转发或全部执行成功
     */
    List<String> resumeQuotaPausedForUserTunnel(Integer userTunnelId, boolean userTunnelWasOverQuota, long now);

    /**
     * 用户续期/启用后，恢复因「到期/停用」被自动暂停的转发（round20）。
     *
     * <p>背景：到期和停用由 FlowController/ResetFlowAsync 自动暂停并写 pause_reason=expiry/disabled，
     * 但续费或重新启用后没有任何路径把它们放开，业务要人工逐条点恢复。
     * 这里只处理明确标记为 expiry/disabled 的行；手动暂停（manual）不碰。
     *
     * @return 逐条说明（节点未执行等），空表示全部已恢复或无需恢复
     */
    List<String> resumeExpiryDisabledPausedForUser(Long userId);

    /**
     * 用户隧道续期/启用后，恢复因「到期/停用」被自动暂停的转发（round20）。
     */
    List<String> resumeExpiryDisabledPausedForUserTunnel(Integer userTunnelId);

    /**
     * 停用用户时立即暂停其名下活跃转发（round21）。
     *
     * <p>原实现把账号 status 改成 0 后不碰转发，只能等节点下一次流量上报由 FlowController 兜住；
     * 节点离线或无流量时已停用账号仍在放行。这里先落库 status=0 + pause_reason=disabled，
     * 再逐入口下发 PauseService，节点失败只记说明。
     *
     * @return 未执行节点说明；空表示没有活跃转发或全部成功
     */
    List<String> pauseForDisabledUser(Long userId);

    /**
     * 隧道入口热迁移（round24）：隧道下仍有转发时改入口节点，按入口顺序把每条转发的入口服务
     * 与 forward_port 行迁到新节点。
     *
     * <p>新入口先建、旧入口再删、最后写库；失败时回滚本次已迁移的转发。
     *
     * @param oldEntries 变更前的入口行（按 id 升序，与 newEntries 一一对应）
     * @param newEntries 变更后的入口行（已挂好 tunnelId）
     * @param nodes      新拓扑涉及的节点（含新入口节点）
     * @return null 表示全部迁移成功；否则返回失败原因
     */
    String migrateEntryNodes(Long tunnelId, List<com.admin.entity.ChainTunnel> oldEntries,
                             List<com.admin.entity.ChainTunnel> newEntries, Map<Long, com.admin.entity.Node> nodes);

    /**
     * 入口热迁移的重载：{@code dispatchTunnel} 是「要按哪种类型下发」的隧道视图（type 可能已被改成新值），
     * 回滚仍按库里持久化的旧类型重建旧入口服务，避免把新类型语义写回旧拓扑。
     */
    String migrateEntryNodes(com.admin.entity.Tunnel dispatchTunnel, List<com.admin.entity.ChainTunnel> oldEntries,
                             List<com.admin.entity.ChainTunnel> newEntries, Map<Long, com.admin.entity.Node> nodes);

    /**
     * 隧道类型（1 直连 / 2 隧道转发）变更后，按传入的隧道视图重建该隧道下所有活跃转发的入口服务。
     *
     * <p>类型切换会改变 handler 是否挂 chains_&lt;id&gt;：只改库不重发，面板显示已切换、节点仍在按旧类型转发。
     * 本方法逐条「先删同名服务、再按新类型重建」，端口沿用 forward_port 记录，撞 bind 时按既有换口口径重试。
     * 暂停中的转发（status != 1）跳过：它们本就不该在节点上监听，不能因为切换类型被复活。
     *
     * @param tunnel       隧道视图，调用方必须已把 type 设成目标类型
     * @param entryNodeIds 新拓扑的入口节点（按顺序），为空表示无法确定入口
     * @return null 表示全部成功；否则返回失败明细（调用方负责按旧类型再调一次以回滚）
     */
    String rebuildTunnelForwardServices(com.admin.entity.Tunnel tunnel, List<Long> entryNodeIds);
}
