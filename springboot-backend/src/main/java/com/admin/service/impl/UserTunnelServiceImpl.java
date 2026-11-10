package com.admin.service.impl;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.entity.SpeedLimit;
import com.admin.entity.UserTunnel;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.ChainTunnelService;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.Node;
import com.admin.entity.ChainTunnel;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * <p>
 * 用户隧道权限服务实现类
 * 提供用户隧道权限的分配、查询、更新和删除功能
 * 支持流量限制、数量限制、过期时间和限速规则的管理
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Service
public class UserTunnelServiceImpl extends ServiceImpl<UserTunnelMapper, UserTunnel> implements UserTunnelService {


    @Resource
    @Lazy
    private ForwardService forwardService;

    /** 限速规则存在性校验直接走 mapper：SpeedLimitService 会牵出节点下发链路，绕开潜在的循环依赖。 */
    @Resource
    private SpeedLimitMapper speedLimitMapper;

    /** 撤销权限前的入口节点预检：只读 chain_tunnel，链路本身无下游依赖，不会反向牵出本类。 */
    @Resource
    private ChainTunnelService chainTunnelService;

    @Resource
    private NodeService nodeService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private TunnelMapper tunnelMapper;

    /** 在线判定钩子，默认走 WS 会话表；测试里替换成固定值，避免依赖真实连接（同 NodeReconcileTask 写法）。 */
    private LongPredicate nodeOnlineCheck = WebSocketServer::isNodeOnline;

    private static final String DUPLICATE_USER_TUNNEL_MSG = "该用户已拥有此隧道权限";

    @Override
    public R assignUserTunnel(UserTunnelDto userTunnelDto) {
        if (userTunnelDto == null) {
            return R.err("参数不能为空");
        }
        if (userTunnelDto.getUserId() == null || userTunnelDto.getTunnelId() == null) {
            return R.err("用户ID或隧道ID不能为空");
        }
        if (userMapper != null && userMapper.selectById(userTunnelDto.getUserId().longValue()) == null) {
            return R.err("用户不存在：id=" + userTunnelDto.getUserId());
        }
        if (tunnelMapper != null && tunnelMapper.selectById(userTunnelDto.getTunnelId().longValue()) == null) {
            return R.err("隧道不存在：id=" + userTunnelDto.getTunnelId());
        }
        if (userTunnelDto.getSpeedId() != null && speedLimitMapper != null) {
            SpeedLimit speedLimit = speedLimitMapper.selectById(userTunnelDto.getSpeedId().longValue());
            if (speedLimit == null) {
                return R.err("限速规则不存在：id=" + userTunnelDto.getSpeedId());
            }
            if (speedLimit.getTunnelId() != null && userTunnelDto.getTunnelId() != null && !Objects.equals(speedLimit.getTunnelId(), userTunnelDto.getTunnelId().longValue())) {
                return R.err("限速规则不属于该隧道：speedLimitTunnelId=" + speedLimit.getTunnelId()
                        + "，userTunnelId=" + userTunnelDto.getTunnelId());
            }
        }
        // 预查只负责给出友好提示，真正防重复靠 uk_user_tunnel_user_tunnel（见 UserTunnelIndexMigration）
        if (userTunnelExists(userTunnelDto)) return R.err(DUPLICATE_USER_TUNNEL_MSG);
        UserTunnel userTunnel = new UserTunnel();
        BeanUtils.copyProperties(userTunnelDto, userTunnel);
        userTunnel.setStatus(1);
        try {
            if (this.save(userTunnel)) return R.ok();
            if (userTunnelExists(userTunnelDto)) return R.err(DUPLICATE_USER_TUNNEL_MSG);
            return R.err("隧道权限写入失败：写入 0 行");
        } catch (DuplicateKeyException e) {
            // 预查与写入之间的窗口：并发双击/重放两条都能通过预查，唯一索引在这里兜住
            return R.err(DUPLICATE_USER_TUNNEL_MSG);
        } catch (Exception e) {
            // 写库失败绝不能报成功；落库异常后再查一次，确认是否已被并发请求写进同一条权限
            if (isUniqueConflict(e) || userTunnelExists(userTunnelDto)) {
                return R.err(DUPLICATE_USER_TUNNEL_MSG);
            }
            return R.err("隧道权限写入失败：" + innermostMessage(e));
        }
    }

    @Override
    public R getUserTunnelList(UserTunnelQueryDto queryDto) {
        if (queryDto == null || queryDto.getUserId() == null) {
            return R.err("用户ID不能为空");
        }
        List<UserTunnelWithDetailDto> userTunnelWithDetails = this.baseMapper.getUserTunnelWithDetails(queryDto.getUserId());
        return R.ok(userTunnelWithDetails);
    }

    @Override
    public R removeUserTunnel(Integer id) {
        if (id == null) {
            return R.err("权限ID不能为空");
        }
        UserTunnel userTunnel = this.getById(id);
        if (userTunnel == null) return R.err("未找到对应的用户隧道权限记录");

        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userTunnel.getUserId()).eq("tunnel_id", userTunnel.getTunnelId()));
        // 动任何一条之前先做只读预检：多条转发时逐条 strict 删除会遇到「前面的删成功、后面的因节点离线失败」，
        // 权限行留着、转发却已经少了一部分（round49 遗留）。任一入口节点缺席就整单拒绝，一条都不删。
        if (!forwardList.isEmpty()) {
            R preflightError = preflightIngressNodesOnline(userTunnel.getTunnelId());
            if (preflightError != null) {
                return preflightError;
            }
        }
        // 逐条删、逐条看结果：节点侧没删干净就中止，权限行留着让用户能重试。
        // 老实现把返回值全丢掉再无条件 removeById：节点离线时转发还在监听、授权却已经没了。
        List<String> leftovers = new ArrayList<>();
        for (Forward forward : forwardList) {
            // strict=true：节点侧没删掉就不删库、不删权限行，否则离线节点会继续按旧权限放量。
            R result = forwardService.deleteForward(forward.getId(), true);
            if (result == null || result.getCode() != 0) {
                return R.err(forwardLabel(forward) + "删除失败：" + forwardFailureReason(result) + "，隧道权限未删除");
            }
            // deleteForward 用 R.ok(List) 带回「库里已删、节点上没删掉」的说明，删行后原样转给前端
            Object removed = result.getData();
            if (removed instanceof List) {
                for (Object note : (List<?>) removed) {
                    if (note != null) {
                        leftovers.add(String.valueOf(note));
                    }
                }
            }
        }
        this.removeById(id);
        if (!leftovers.isEmpty()) {
            return R.ok(leftovers);
        }
        return R.ok();
    }

    /**
     * 撤销权限前的入口节点预检（只读，不改库）。
     *
     * <p>转发的下发/删除都落在隧道入口节点（chain_type=1）上。多条转发逐条 strict 删除时，
     * 节点中途离线会让前面几条已经删掉、后面几条失败，权限行保留但转发已经残缺 —— 用户既看不到
     * 完整授权、也回不到删除前的状态。这里先把所有入口节点的存在性与在线状态探一遍，全部通过才进删除循环。
     *
     * @param tunnelId 权限行指向的隧道（UserTunnel/Forward 侧都是 Integer，chain_tunnel 侧是 Long，交给 SQL 比较）
     * @return null 表示预检通过；非 null 是一条都不删的错误响应
     */
    private R preflightIngressNodesOnline(Integer tunnelId) {
        List<ChainTunnel> ingressNodes = chainTunnelService.list(new QueryWrapper<ChainTunnel>()
                .eq("tunnel_id", tunnelId)
                .eq("chain_type", 1));
        if (ingressNodes.isEmpty()) {
            return R.err("隧道 " + tunnelId + " 未找到入口节点，隧道权限未删除");
        }
        // 同一入口可能出现在多条 chain 行上，按 node_id 去重，避免同一节点被查/被判多次
        Set<Long> checked = new LinkedHashSet<>();
        for (ChainTunnel chainTunnel : ingressNodes) {
            Long nodeId = chainTunnel.getNodeId();
            if (nodeId == null) {
                return R.err("入口节点记录缺失（tunnel_id=" + tunnelId + "），隧道权限未删除");
            }
            if (!checked.add(nodeId)) {
                continue;
            }
            if (nodeService.getById(nodeId) == null) {
                return R.err("节点" + nodeId + "记录不存在，隧道权限未删除");
            }
            if (!nodeOnlineCheck.test(nodeId)) {
                return R.err("节点" + nodeId + "不在线，隧道权限未删除");
            }
        }
        return null;
    }

    @Override
    public R updateUserTunnel(UserTunnelUpdateDto updateDto) {
        if (updateDto == null) {
            return R.err("参数不能为空");
        }
        if (updateDto.getId() == null) {
            return R.err("权限ID不能为空");
        }
        UserTunnel userTunnel = this.getById(updateDto.getId());
        if (userTunnel == null) return R.err("未找到对应的用户隧道权限记录");
        // 引用与归属校验必须在写库之前：speed_id 指向不存在或跨隧道的规则时，节点侧限流器查不到对象，限速会静默失效
        if (updateDto.getSpeedId() != null && speedLimitMapper != null) {
            SpeedLimit speedLimit = speedLimitMapper.selectById(updateDto.getSpeedId().longValue());
            if (speedLimit == null) {
                return R.err("限速规则不存在：id=" + updateDto.getSpeedId());
            }
            if (speedLimit.getTunnelId() != null && userTunnel.getTunnelId() != null && !Objects.equals(speedLimit.getTunnelId(), userTunnel.getTunnelId().longValue())) {
                return R.err("限速规则不属于该隧道：speedLimitTunnelId=" + speedLimit.getTunnelId()
                        + "，userTunnelId=" + userTunnel.getTunnelId());
            }
        }
        boolean speedChanged = hasSpeedChanged(userTunnel.getSpeedId(), updateDto.getSpeedId());
        // 回滚要用改之前的旧值，必须在 setSpeedId 之前留存
        Integer oldSpeedId = userTunnel.getSpeedId();
        userTunnel.setFlow(updateDto.getFlow());
        userTunnel.setNum(updateDto.getNum());
        updateOptionalProperty(userTunnel::setFlowResetTime, updateDto.getFlowResetTime());
        updateOptionalProperty(userTunnel::setExpTime, updateDto.getExpTime());
        updateOptionalProperty(userTunnel::setStatus, updateDto.getStatus());
        userTunnel.setSpeedId(updateDto.getSpeedId());
        // round49：写库失败必须停在这里。以前不检查返回值，写 0 行也照样往下重下发，
        // 结果是「库里没改、节点已改」——比不改更糟，接口还回成功。
        if (!this.updateById(userTunnel)) {
            return R.err("隧道权限更新失败：影响 0 行，未下发节点");
        }
        if (speedChanged) {
            // 限速是靠「重下发转发」才落到节点上的：老实现把 updateForward 的返回值整个丢掉，
            // 节点离线/下发失败时接口照样回成功——库里限速已改、节点仍按旧限速跑，面板全绿。
            List<String> failures = new ArrayList<>();
            List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userTunnel.getUserId()).eq("tunnel_id", userTunnel.getTunnelId()));
            for (Forward forward : forwardList) {
                ForwardUpdateDto forwardUpdateDto = new ForwardUpdateDto();
                forwardUpdateDto.setId(forward.getId());
                forwardUpdateDto.setUserId(forward.getUserId());
                forwardUpdateDto.setName(forward.getName());
                forwardUpdateDto.setRemoteAddr(forward.getRemoteAddr());
                forwardUpdateDto.setStrategy(forward.getStrategy());
                R result = forwardService.updateForward(forwardUpdateDto);
                if (result == null || result.getCode() != 0) {
                    failures.add(forwardLabel(forward) + "(" + forwardFailureReason(result) + ")");
                }
            }
            if (!failures.isEmpty()) {
                return rollbackSpeedId(userTunnel.getId(), oldSpeedId, updateDto.getSpeedId(), failures);
            }
        }
        // round20：修掉基线「更新成功也返回失败」的缺陷（原来无条件 R.err，前端永远看到失败）；
        // 顺带在续期/启用后恢复因到期或停用被自动暂停的转发（只处理 pause_reason=expiry/disabled）。
        List<String> resumed = forwardService.resumeExpiryDisabledPausedForUserTunnel(userTunnel.getId());
        if (!resumed.isEmpty()) {
            return R.ok(resumed);
        }
        return R.ok();
    }

    private boolean userTunnelExists(UserTunnelDto userTunnelDto) {
        try {
            return this.count(new QueryWrapper<UserTunnel>()
                    .eq("user_id", userTunnelDto.getUserId())
                    .eq("tunnel_id", userTunnelDto.getTunnelId())) > 0;
        } catch (Exception e) {
            // 兜底查询自身失败时按"没查到"处理：后续会返回写库失败信息，不会谎报成功
            return false;
        }
    }

    /**
     * 是否是唯一键/主键冲突。
     *
     * <p>SQLite 的 UNIQUE 冲突在 Spring 侧不是 DuplicateKeyException（驱动抛 SQLITE_CONSTRAINT_UNIQUE，
     * 归到 UncategorizedSQLException），与 FlowReportDedupStore.isUniqueViolation 保持同一判定口径。
     */
    private boolean isUniqueConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            // 只认 UNIQUE 类错误：SQLITE_CONSTRAINT_NOTNULL / _FOREIGNKEY 同属 SQLITE_CONSTRAINT_*，
            // 但那是字段缺失或外键问题，硬说成「已拥有权限」会把真实故障藏起来。
            if (lower.contains("unique constraint failed")
                    || lower.contains("constraint_unique")
                    || lower.contains("duplicate entry")
                    || lower.contains("duplicate key")) {
                return true;
            }
        }
        return false;
    }

    /** 错误信息取最内层异常，避免只看到 "### Error updating database" 这类外层壳。 */
    private String innermostMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    private <T> void updateOptionalProperty(java.util.function.Consumer<T> setter, T value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private boolean hasSpeedChanged(Integer oldSpeedId, Integer newSpeedId) {
        if (oldSpeedId == null && newSpeedId == null) {
            return false;
        }
        if (oldSpeedId == null || newSpeedId == null) {
            return true;
        }
        return !oldSpeedId.equals(newSpeedId);
    }

    /**
     * 限速下发失败后的库内回滚：只把 speed_id 改回旧值（其它列是这次请求确实要改的，不回滚），
     * 返回的错误信息里说明回滚是否真的落库。
     */
    private R rollbackSpeedId(Integer userTunnelId, Integer oldSpeedId, Integer newSpeedId, List<String> failures) {
        String details = String.join("；", failures);
        String rollbackError = null;
        try {
            // 必须是 UpdateWrapper 而不是 updateById：只动 speed_id 这一列，
            // 且旧值可能为 null —— 实体 setter + 默认更新策略会跳过 null 字段，做不到「回滚成不限速」。
            boolean rolledBack = this.update(new UpdateWrapper<UserTunnel>()
                    .eq("id", userTunnelId)
                    .set("speed_id", oldSpeedId));
            if (!rolledBack) {
                rollbackError = "影响 0 行";
            }
        } catch (RuntimeException e) {
            rollbackError = innermostMessage(e);
        }
        if (rollbackError == null) {
            // round49 QA：只回滚 speed_id，flow/num/expTime/status 是本请求确实要改且已落库的，
            // 文案必须说清「其余字段已更新」，否则调用方会以为整条记录都被撤回。
            return R.err("限速下发失败，其余字段已更新；限速已回滚为原值（speedId=" + oldSpeedId + "）：" + details);
        }
        return R.err("限速下发失败，其余字段已更新；限速回滚失败（" + rollbackError + "），库内 speed_id 仍为 "
                + newSpeedId + "，需人工核对：" + details);
    }

    /** 报错文案里指代一条转发：优先用名字，名字为空时退回 id，避免出现「转发null」。 */
    private String forwardLabel(Forward forward) {
        String name = forward.getName();
        return "转发" + (name == null || name.trim().isEmpty() ? "id=" + forward.getId() : name.trim());
    }

    /** 节点下发/删除失败的原因：R 为空按无返回处理，msg 为空时退回错误码。 */
    private String forwardFailureReason(R result) {
        if (result == null) {
            return "节点无返回";
        }
        String msg = result.getMsg();
        return msg == null || msg.trim().isEmpty() ? "code=" + result.getCode() : msg.trim();
    }

}
