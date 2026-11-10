package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.SpeedLimitDto;
import com.admin.common.dto.SpeedLimitUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.service.*;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <p>
 * 限速规则服务实现类
 * 提供限速规则的增删改查功能，包括与Gost服务的集成
 * 支持限速器的创建、更新、删除和查询操作
 * </p>
 *
 * @author QAQ
 * @since 2025-06-04
 */
@Slf4j
@Service
public class SpeedLimitServiceImpl extends ServiceImpl<SpeedLimitMapper, SpeedLimit> implements SpeedLimitService {

    /** 单轮最多回收的孤儿限流器条数：一次异常上报不至于被删掉一大片。 */
    private static final int MAX_ORPHAN_REAP = 20;

    /**
     * 单轮最多尝试的条目数（含失败）：删除失败不占成功额度，否则前 20 条持续失败会把
     * 更大 id 的孤儿永久饿死；但也要有硬上限，避免一轮里对节点发几百条命令。
     */
    private static final int MAX_ORPHAN_ATTEMPTS = 40;

    /** 本面板的 limiter 名就是 speed_limit.id：1~18 位纯数字（18 位以内 Long.parseLong 不会溢出）。 */
    private static final Pattern NUMERIC_LIMITER_NAME = Pattern.compile("\\d{1,18}");

    /** 同隧道同名限速规则在业务上无意义：预查与唯一索引冲突共用这一句，前端不必区分两种来源。 */
    private static final String DUPLICATE_NAME_MSG = "该隧道下已存在同名限速规则";

    @Resource
    @Lazy
    TunnelService tunnelService;

    @Resource
    NodeService nodeService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    ChainTunnelService chainTunnelService;


    @Override
    public R createSpeedLimit(SpeedLimitDto speedLimitDto) {
        if (speedLimitDto == null) {
            return R.err("参数不能为空");
        }
        if (speedLimitDto.getTunnelId() == null) {
            return R.err("隧道ID不能为空");
        }
        Tunnel tunnel = tunnelService.getById(speedLimitDto.getTunnelId());
        if (tunnel == null) return R.err("隧道不存在");
        if (!isPositiveSpeed(speedLimitDto.getSpeed())) return R.err("速度限制必须大于0");

        // 预查只负责快速失败给友好提示，并发防重靠 uk_speed_limit_tunnel_name（SpeedLimitUniqueMigration）。
        // name 为空/纯空白不在这里造新文案：交给 DTO 的 @NotBlank，服务层只跳过查重。
        String name = speedLimitDto.getName() == null ? "" : speedLimitDto.getName().trim();
        if (!name.isEmpty()) {
            int sameName = this.count(new QueryWrapper<SpeedLimit>()
                    .eq("tunnel_id", speedLimitDto.getTunnelId())
                    .eq("name", name));
            if (sameName > 0) return R.err(DUPLICATE_NAME_MSG);
        }

        SpeedLimit speedLimit = new SpeedLimit();
        BeanUtils.copyProperties(speedLimitDto, speedLimit);
        // 隧道名称必须对齐数据库真实隧道名，防伪造/不一致
        speedLimit.setTunnelName(tunnel.getName());
        // 落库也写 trim 后的名字：否则 "a" 与 " a" 各自通过预查，查重的 trim 口径就永远漏一半。
        if (!name.isEmpty()) {
            speedLimit.setName(name);
        }
        long currentTime = System.currentTimeMillis();
        speedLimit.setCreatedTime(currentTime);
        speedLimit.setUpdatedTime(currentTime);
        speedLimit.setStatus(1);

        List<Long> nodeIds = tunnelNodeIds(speedLimit.getTunnelId());
        String offline = offlineNodes(nodeIds);
        if (offline != null) {
            return R.err(offline + "，限速规则未创建");
        }

        // 先入库再下发：入库失败时节点上不该出现一条库中不存在的限流器。
        // MyBatis 的写失败分两种：返回 false 和直接抛 SQLException（如 SQLITE_BUSY），
        // 后者不会走到 if 分支，必须显式接住，否则会出现"库没写、节点已下发"的漂移。
        boolean saved;
        try {
            saved = this.save(speedLimit);
        } catch (Exception e) {
            // 预查与写入之间的窗口：并发双击两条都能过预查，由唯一索引在这里兜住
            if (isUniqueConflict(e)) return R.err(DUPLICATE_NAME_MSG);
            return R.err("限速规则入库失败，未下发节点：" + describeDbError(e));
        }
        if (!saved) {
            return R.err("限速规则入库失败，未下发节点");
        }

        String speedInMBps = convertBitsToMBps(speedLimit.getSpeed());
        List<Long> applied = new ArrayList<>();
        for (Long nodeId : nodeIds) {
            GostDto gostDto = GostUtil.AddLimiters(nodeId, speedLimit.getId(), speedInMBps);
            if (!isOk(gostDto)) {
                String rollback = rollbackCreatedLimiters(applied, speedLimit.getId());
                String dbNote = "";
                try {
                    if (!this.removeById(speedLimit.getId())) {
                        dbNote = "；库记录删除失败，id=" + speedLimit.getId() + " 需手工清理";
                    }
                } catch (Exception e) {
                    dbNote = "；库记录删除异常（" + describeDbError(e) + "），id=" + speedLimit.getId() + " 需手工清理";
                }
                return R.err(appendNote("节点" + nodeId + " 下发限流器失败：" + msgOf(gostDto), rollback) + dbNote);
            }
            applied.add(nodeId);
        }
        return R.ok();
    }

    @Override
    public R getAllSpeedLimits() {
        List<SpeedLimit> speedLimits = this.list();
        return R.ok(speedLimits);
    }

    @Override
    public R updateSpeedLimit(SpeedLimitUpdateDto speedLimitUpdateDto) {
        if (speedLimitUpdateDto == null) {
            return R.err("参数不能为空");
        }
        if (speedLimitUpdateDto.getId() == null) {
            return R.err("限速ID不能为空");
        }
        SpeedLimit stored = this.getById(speedLimitUpdateDto.getId());
        if (stored == null) return R.err("限速不存在");
        if (!isPositiveSpeed(speedLimitUpdateDto.getSpeed())) return R.err("速度限制必须大于0");

        // 改名同样要查重：唯一索引只在写库时拦截，等到那时节点上的速率已经被改过一轮，
        // 回滚只是把速率改回去，用户还会收到一条「入库失败 + SQL 约束」的通用文案。
        String newName = speedLimitUpdateDto.getName() == null ? "" : speedLimitUpdateDto.getName().trim();
        if (newName.isEmpty()) return R.err("限速规则名称不能为空");
        if (!newName.equals(stored.getName())) {
            int sameName = this.count(new QueryWrapper<SpeedLimit>()
                    .eq("tunnel_id", stored.getTunnelId())
                    .eq("name", newName)
                    .ne("id", stored.getId()));
            if (sameName > 0) return R.err(DUPLICATE_NAME_MSG);
        }

        List<Long> nodeIds = tunnelNodeIds(stored.getTunnelId());
        String offline = offlineNodes(nodeIds);
        if (offline != null) {
            return R.err(offline + "，限速规则未变更");
        }

        String oldSpeedInMBps = convertBitsToMBps(stored.getSpeed());
        String newSpeedInMBps = convertBitsToMBps(speedLimitUpdateDto.getSpeed());

        // round51：先写库、后动节点。改成「先动节点」时，并发改名撞唯一键只能靠回滚救——
        // 回滚瞬间节点断网就会永久停留在新速率（库旧/节点新）。写库在前，唯一冲突在节点
        // 尚未被触碰前就被数据库拦住；节点失败再按旧值把库改回去（本地事务，比网络回滚可靠）。
        SpeedLimit update = new SpeedLimit();
        update.setId(stored.getId());
        update.setName(newName);
        update.setSpeed(speedLimitUpdateDto.getSpeed());
        update.setUpdatedTime(System.currentTimeMillis());
        try {
            if (!this.updateById(update)) {
                return R.err("限速规则入库失败，未下发节点：记录不存在");
            }
        } catch (Exception e) {
            if (isUniqueConflict(e)) {
                // 预查之后、写库之前被并发改名抢先：此时一个节点都还没动，直接拒绝
                return R.err(DUPLICATE_NAME_MSG);
            }
            return R.err("限速规则入库失败，未下发节点：" + describeDbError(e));
        }

        List<Long> applied = new ArrayList<>();
        for (Long nodeId : nodeIds) {
            GostDto gostDto = GostUtil.UpdateLimiters(nodeId, stored.getId(), newSpeedInMBps);
            if (!isOk(gostDto)) {
                String rollback = rollbackUpdatedLimiters(applied, stored.getId(), oldSpeedInMBps);
                String dbRollback = restoreSpeedLimit(stored);
                return R.err(appendNote("节点" + nodeId + " 更新限流器失败：" + msgOf(gostDto),
                        rollback + "；" + dbRollback));
            }
            applied.add(nodeId);
        }
        return R.ok();
    }

    @Override
    public R deleteSpeedLimit(Long id) {
        if (id == null) {
            return R.err("限速规则ID不能为空");
        }
        // 1. 验证限速规则是否存在
        SpeedLimit speedLimit = this.getById(id);
        if (speedLimit == null) return R.err("限速规则不存在");


        int userCount = userTunnelService.count(new QueryWrapper<UserTunnel>().eq("speed_id", speedLimit.getId()));
        if (userCount != 0) return R.err("该限速规则还有用户在使用 请先取消分配");

        List<Long> nodeIds = tunnelNodeIds(speedLimit.getTunnelId());
        String offline = offlineNodes(nodeIds);
        if (offline != null) {
            return R.err(offline + "，限速规则未删除");
        }
        String speedInMBps = convertBitsToMBps(speedLimit.getSpeed());

        List<Long> applied = new ArrayList<>();
        for (Long nodeId : nodeIds) {
            GostDto gostDto = GostUtil.DeleteLimiters(nodeId, speedLimit.getId());
            if (!isOk(gostDto)) {
                String rollback = rollbackDeletedLimiters(applied, speedLimit.getId(), speedInMBps);
                return R.err(appendNote("节点" + nodeId + " 删除限流器失败：" + msgOf(gostDto), rollback));
            }
            applied.add(nodeId);
        }
        boolean removed;
        try {
            removed = this.removeById(id);
        } catch (Exception e) {
            String rollback = rollbackDeletedLimiters(applied, speedLimit.getId(), speedInMBps);
            return R.err(appendNote("限速规则删除失败：" + describeDbError(e) + "；节点限流器已重建", rollback));
        }
        if (!removed) {
            String rollback = rollbackDeletedLimiters(applied, speedLimit.getId(), speedInMBps);
            return R.err(appendNote("限速规则删除失败", rollback));
        }
        return R.ok();
    }

    /** 节点下发失败后把库恢复成旧名称+旧速率；本地同库写，比逐节点网络回滚可靠。 */
    private String restoreSpeedLimit(SpeedLimit stored) {
        if (stored == null || stored.getId() == null) {
            return "库回滚失败：缺少旧记录";
        }
        SpeedLimit restore = new SpeedLimit();
        restore.setId(stored.getId());
        restore.setName(stored.getName());
        restore.setSpeed(stored.getSpeed());
        restore.setUpdatedTime(System.currentTimeMillis());
        try {
            return this.updateById(restore) ? "库已恢复旧值" : "库回滚失败：记录不存在";
        } catch (Exception e) {
            return "库回滚失败：" + describeDbError(e);
        }
    }

    /** 隧道拓扑涉及的全部节点（去重、保序），只判库不探测节点。 */
    private List<Long> tunnelNodeIds(Long tunnelId) {
        List<Long> nodeIds = new ArrayList<>();
        if (tunnelId == null) {
            return nodeIds;
        }
        List<ChainTunnel> rows = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnelId));
        if (rows == null) {
            return nodeIds;
        }
        Set<Long> seen = new HashSet<>();
        for (ChainTunnel row : rows) {
            if (row != null && row.getNodeId() != null && seen.add(row.getNodeId())) {
                nodeIds.add(row.getNodeId());
            }
        }
        return nodeIds;
    }

    /** 任一节点不在线就返回可直接回给前端的说明；全部在线返回 null。 */
    private String offlineNodes(List<Long> nodeIds) {
        List<Long> offline = new ArrayList<>();
        for (Long nodeId : nodeIds) {
            if (!WebSocketServer.isNodeOnline(nodeId)) {
                offline.add(nodeId);
            }
        }
        if (offline.isEmpty()) {
            return null;
        }
        return "节点不在线：" + offline.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** 取最内层异常的消息并压成一行：SQL 原文太长，回给前端只要可判读的根因。 */
    private static String describeDbError(Throwable t) {
        Throwable root = t;
        // 限深 16 层：异常链理论上可能自引用（A.cause=B, B.cause=A），无上限会死循环
        for (int depth = 0; depth < 16 && root.getCause() != null && root.getCause() != root; depth++) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            msg = root.getClass().getSimpleName();
        }
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 120 ? msg.substring(0, 120) + "…" : msg;
    }

    private static boolean isPositiveSpeed(Integer speed) {
        return speed != null && speed > 0;
    }

    /**
     * 是否是 (tunnel_id, name) 唯一键冲突。
     *
     * <p>SQLite 的 UNIQUE 冲突在 Spring 侧常常不是 {@link DuplicateKeyException}（驱动抛
     * SQLITE_CONSTRAINT_UNIQUE，被归到 UncategorizedSQLException），所以除了异常类型还要看 message；
     * 与 UserServiceImpl#isUniqueConflict 保持同一判定口径。
     */
    private static boolean isUniqueConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("unique constraint failed")
                    || lower.contains("constraint_unique")
                    || lower.contains("duplicate entry")
                    || lower.contains("duplicate key")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOk(GostDto gostDto) {
        return gostDto != null && Objects.equals(gostDto.getMsg(), "OK");
    }

    private static String msgOf(GostDto gostDto) {
        return gostDto == null ? "节点无响应" : gostDto.getMsg();
    }

    /** 回滚本次新建的限流器：删掉已下发成功的那些，失败项要显式带回前端。 */
    private String rollbackCreatedLimiters(List<Long> applied, Long speedLimitId) {
        List<String> failures = new ArrayList<>();
        for (Long nodeId : applied) {
            GostDto gostDto = GostUtil.DeleteLimiters(nodeId, speedLimitId);
            if (!isOk(gostDto)) {
                failures.add("node" + nodeId + "(" + msgOf(gostDto) + ")");
            }
        }
        return rollbackNote(applied.size(), failures);
    }

    /** 回滚本次改过的速率：按库里旧速率重新 UpdateLimiters。 */
    private String rollbackUpdatedLimiters(List<Long> applied, Long speedLimitId, String oldSpeedInMBps) {
        List<String> failures = new ArrayList<>();
        for (Long nodeId : applied) {
            GostDto gostDto = GostUtil.UpdateLimiters(nodeId, speedLimitId, oldSpeedInMBps);
            if (!isOk(gostDto)) {
                failures.add("node" + nodeId + "(" + msgOf(gostDto) + ")");
            }
        }
        return rollbackNote(applied.size(), failures);
    }

    /** 回滚本次删掉的限流器：按库里速率重新 AddLimiters，exists 归一成成功。 */
    private String rollbackDeletedLimiters(List<Long> applied, Long speedLimitId, String speedInMBps) {
        List<String> failures = new ArrayList<>();
        for (Long nodeId : applied) {
            GostDto gostDto = GostUtil.AddLimiters(nodeId, speedLimitId, speedInMBps);
            if (!isOk(gostDto)) {
                failures.add("node" + nodeId + "(" + msgOf(gostDto) + ")");
            }
        }
        return rollbackNote(applied.size(), failures);
    }

    private static String rollbackNote(int attempted, List<String> failures) {
        if (attempted == 0) {
            return "";
        }
        if (failures.isEmpty()) {
            return "已回滚" + attempted + "个节点";
        }
        return "回滚未完成：" + String.join("，", failures);
    }

    /** 拼接回滚说明：空说明不产生尾巴，避免出现「回滚：；已回滚N个节点」这种双分隔符。 */
    private static String appendNote(String base, String note) {
        return note == null || note.isEmpty() ? base : base + "；" + note;
    }

    /**
     * 节点重连后按库补齐限流器。
     *
     * <p>原缺口：限流器只在 createSpeedLimit 时逐节点下发一次，之后没有任何对账。
     * 节点容器重装/清盘/手工删配置后 `limiters` 整段消失，但节点上的服务仍带着
     * `"limiter":"&lt;id&gt;"` 引用——gost 查不到限流器时既不报错也不限速，面板全绿。
     *
     * <p>判据只来自库：speed_limit.tunnel_id 命中该节点所在的隧道。
     * 先 CreateLimiters（原样返回），命中 exists 再 UpdateLimiters 校准速率，
     * 避免"存在但速率是旧值"这种更隐蔽的漂移。
     */
    @Override
    public List<String> resyncNodeLimiters(Long nodeId) {
        List<String> notes = new ArrayList<>();
        if (nodeId == null) {
            return notes;
        }
        List<ChainTunnel> rows = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("node_id", nodeId));
        if (rows == null || rows.isEmpty()) {
            // 该节点不在任何隧道拓扑里，本来就不该有限流器
            return notes;
        }
        Set<Long> tunnelIds = new HashSet<>();
        for (ChainTunnel row : rows) {
            if (row != null && row.getTunnelId() != null) {
                tunnelIds.add(row.getTunnelId());
            }
        }
        if (tunnelIds.isEmpty()) {
            return notes;
        }
        List<SpeedLimit> limits = this.list(new QueryWrapper<SpeedLimit>().in("tunnel_id", tunnelIds));
        if (limits == null || limits.isEmpty()) {
            return notes;
        }
        for (SpeedLimit limit : limits) {
            if (limit == null || limit.getId() == null || !isPositiveSpeed(limit.getSpeed())) {
                continue;
            }
            String speedInMBps = convertBitsToMBps(limit.getSpeed());
            GostDto created = GostUtil.CreateLimiters(nodeId, limit.getId(), speedInMBps);
            String msg = created == null ? "节点无响应" : created.getMsg();
            if (msg != null && msg.contains("exists")) {
                GostDto updated = GostUtil.UpdateLimiters(nodeId, limit.getId(), speedInMBps);
                if (updated == null || !Objects.equals(updated.getMsg(), "OK")) {
                    notes.add("限流器" + limit.getId() + "@node" + nodeId + " 已存在但校准失败："
                            + (updated == null ? "节点无响应" : updated.getMsg()));
                }
                continue;
            }
            if (!Objects.equals(msg, "OK")) {
                notes.add("限流器" + limit.getId() + "@node" + nodeId + " 下发失败：" + msg);
            } else {
                notes.add("已按库补齐限流器" + limit.getId() + "@node" + nodeId + "（" + speedInMBps + "MB/s）");
            }
        }
        return notes;
    }

    /**
     * 按节点本次上报的 limiters 校准速率。与 resyncNodeLimiters 的分工：
     * 那个方法只看「库里有没有」，节点上同名限流器被改成别的速率时它照样返回成功；
     * 这里拿节点自报的实际 limits 与库里 speed 的换算值逐条比对。
     *
     * <p>处置口径：节点没有该限流器 → AddLimiters 补齐；名称在但 limits 不一致 → UpdateLimiters 纠正；
     * 一致 → 不动。单条失败（节点报错或异常）只记一条说明，绝不抛出、不影响同一节点其余条目。
     */
    @Override
    public List<String> reconcileReportedLimiters(Long nodeId, Map<String, List<String>> reportedLimiters) {
        List<String> notes = new ArrayList<>();
        if (nodeId == null || reportedLimiters == null || reportedLimiters.isEmpty()) {
            // 空清单说明这份上报不可信（与 resync 同一口径）：不据此补发任何限流器
            return notes;
        }
        List<ChainTunnel> rows = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("node_id", nodeId));
        Set<Long> tunnelIds = new HashSet<>();
        if (rows != null) {
            for (ChainTunnel row : rows) {
                if (row != null && row.getTunnelId() != null) {
                    tunnelIds.add(row.getTunnelId());
                }
            }
        }
        List<SpeedLimit> limits;
        if (tunnelIds.isEmpty()) {
            // 纯转发节点不挂任何链资源：该节点名下没有 tunnel_id 可查，但仍必须继续走到孤儿回收，
            // 否则这类节点的孤儿限流器永远清不掉（round46 交叉验证指出：原来这里直接 return）。
            limits = new ArrayList<>();
        } else {
            try {
                limits = this.list(new QueryWrapper<SpeedLimit>().in("tunnel_id", tunnelIds));
            } catch (RuntimeException e) {
                notes.add("节点 " + nodeId + " 查询限速规则失败，本轮跳过限流器校准：" + e.getMessage());
                limits = new ArrayList<>();
            }
        }
        if (limits == null) {
            // 该节点所在隧道暂时没有限速规则也不能提前返回：整表被清正是孤儿回收要处理的场景
            limits = new ArrayList<>();
        }
        // 同一次上报里同一 limiter 只处理一次：库行理论上去重，这里再兜一层，
        // 避免同名条目被连发两次 AddLimiters/UpdateLimiters
        Set<String> handled = new LinkedHashSet<>();
        for (SpeedLimit limit : limits) {
            if (limit == null || limit.getId() == null || !isPositiveSpeed(limit.getSpeed())) {
                continue;
            }
            String limiterName = String.valueOf(limit.getId());
            if (!handled.add(limiterName)) {
                continue;
            }
            String speedInMBps = convertBitsToMBps(limit.getSpeed());
            String expected = expectedLimits(speedInMBps);
            List<String> actual = reportedLimiters.get(limiterName);
            try {
                if (actual == null) {
                    GostDto created = GostUtil.AddLimiters(nodeId, limit.getId(), speedInMBps);
                    String msg = created == null ? "节点无响应" : created.getMsg();
                    if (Objects.equals("OK", msg)) {
                        notes.add("节点 " + nodeId + " 限流器补齐：id=" + limiterName + " 期望 " + expected);
                    } else {
                        notes.add("节点 " + nodeId + " 限流器补齐失败：id=" + limiterName + " 原因 " + msg);
                    }
                    continue;
                }
                if (limitsMatch(expected, actual)) {
                    continue;
                }
                GostDto updated = GostUtil.UpdateLimiters(nodeId, limit.getId(), speedInMBps);
                String msg = updated == null ? "节点无响应" : updated.getMsg();
                if (Objects.equals("OK", msg)) {
                    notes.add("节点 " + nodeId + " 限流器纠正：id=" + limiterName
                            + " 期望 " + expected + " 实际 " + String.join("，", actual));
                } else {
                    notes.add("节点 " + nodeId + " 限流器纠正失败：id=" + limiterName + " 原因 " + msg);
                }
            } catch (RuntimeException e) {
                // 单条失败只记录：一条限流器下发炸掉不能拖垮同一节点的其余限流器与服务对账
                notes.add("节点 " + nodeId + " 限流器" + limiterName + " 对账异常：" + e.getMessage());
            }
        }
        reapOrphanLimiters(nodeId, reportedLimiters, notes);
        return notes;
    }

    /**
     * 回收节点上报里"库内已无对应限速规则"的孤儿限流器。
     *
     * <p>缺口：限流器只在 createSpeedLimit 下发一次，deleteSpeedLimit 也只在删除成功路径里删节点侧；
     * 库里那行限速记录若因为回滚/人工清表/迁移漏行消失，节点上的 limiter 就永久残留。
     * 每个 limiter 都占 gost 的内存与配置行，名字又是纯数字 id，与后来新建的限速规则撞名时行为不可预期。
     *
     * <p>判据口径：名字 trim 后是纯数字（本面板只会用 {@code speed_limit.id} 命名限流器）、
     * 能解析成正整数、且不在 speed_limit 全表 id 里，三条同时满足才算孤儿。
     * 非数字名字（别的系统/人工建的 limiter）一律不碰。
     *
     * <p>安全阀：查库异常时直接放弃本轮回收——空集合会把节点上的限流器全部误判成孤儿；
     * 单轮最多成功删除 {@link #MAX_ORPHAN_REAP} 条、最多尝试 {@link #MAX_ORPHAN_ATTEMPTS} 次，
     * 避免一次异常上报造成批量删除，也不让持续失败的条目把额度占死。
     * 补齐/纠正与孤儿回收同一轮并存：本轮刚 Add/Update 过的 id 一定在 speed_limit 表里，
     * 按定义不可能是孤儿，不需要用「本轮动过就整轮推迟」这种会饿死回收的闸门。
     */
    private void reapOrphanLimiters(Long nodeId, Map<String, List<String>> reportedLimiters,
                                    List<String> notes) {
        List<SpeedLimit> all;
        try {
            all = this.list(new QueryWrapper<SpeedLimit>().select("id"));
        } catch (RuntimeException e) {
            notes.add("节点 " + nodeId + " 查询限速规则失败，跳过孤儿限流器回收：" + e.getMessage());
            return;
        }
        if (all == null) {
            notes.add("节点 " + nodeId + " 查询限速规则失败，跳过孤儿限流器回收：查询无结果");
            return;
        }
        Set<String> knownIds = new HashSet<>();
        for (SpeedLimit limit : all) {
            if (limit != null && limit.getId() != null) {
                knownIds.add(String.valueOf(limit.getId()));
            }
        }
        // 删除前先看引用：speed_limit 行没了不代表节点侧就该拔掉限速器。
        // user_tunnel.speed_id 仍指向这个 id 时（删规则失败/人工清表/迁移漏行），节点上的 limiter
        // 还在给这个用户限速；直接删掉会让服务退化成完全不限速（gost 解析不到 limiter 就静默放行），
        // 属于"面板看着已删、实际在偷跑带宽"。这类先只记账，等人把引用清掉（QA round46 P1-3）。
        Set<Long> referenced = new HashSet<>();
        if (userTunnelService == null) {
            // 依赖未注入时无法判断引用，宁可这轮不回收（fail-closed）。
            log.warn("节点 {} 未注入 UserTunnelService，跳过孤儿限流器回收", nodeId);
            return;
        }
        try {
            List<UserTunnel> refs = userTunnelService.list(new QueryWrapper<UserTunnel>().select("speed_id"));
            if (refs != null) {
                for (UserTunnel ref : refs) {
                    if (ref != null && ref.getSpeedId() != null) {
                        referenced.add(Long.valueOf(ref.getSpeedId()));
                    }
                }
            }
        } catch (RuntimeException e) {
            notes.add("节点 " + nodeId + " 查询限流器引用失败，跳过孤儿限流器回收：" + e.getMessage());
            return;
        }
        List<Long> orphans = new ArrayList<>();
        List<Long> stillReferenced = new ArrayList<>();
        List<String> nonNumeric = new ArrayList<>();
        for (String rawName : reportedLimiters.keySet()) {
            Long id = parseLimiterId(rawName);
            if (id == null) {
                if (rawName != null && !rawName.isBlank()) {
                    nonNumeric.add(rawName.trim());
                }
                continue;
            }
            if (knownIds.contains(String.valueOf(id))) {
                continue;
            }
            if (referenced.contains(id)) {
                stillReferenced.add(id);
                continue;
            }
            orphans.add(id);
        }
        if (!stillReferenced.isEmpty()) {
            stillReferenced.sort(Long::compareTo);
            notes.add("节点 " + nodeId + " 限流器 " + stillReferenced + " 库内已无规则但仍被 user_tunnel 引用，"
                    + "跳过删除（需先清理引用，否则服务会退化成不限速）");
        }
        if (!nonNumeric.isEmpty()) {
            // 非数字名不是本面板下发的（别的系统/人工建的），一律不碰；但要留痕，
            // 否则节点上永久残留且没有任何提示（round46 交叉验证指出）。
            log.info("节点 {} 有 {} 个非数字限流器名未参与回收：{}", nodeId, nonNumeric.size(),
                    nonNumeric.size() > 5 ? nonNumeric.subList(0, 5) + "…" : nonNumeric);
        }
        if (orphans.isEmpty()) {
            return;
        }
        orphans.sort(Long::compareTo);
        int reaped = 0;
        int attempted = 0;
        for (int i = 0; i < orphans.size() && reaped < MAX_ORPHAN_REAP && attempted < MAX_ORPHAN_ATTEMPTS; i++) {
            Long limiterId = orphans.get(i);
            attempted++;
            boolean deleted = false;
            try {
                GostDto gostDto = GostUtil.DeleteLimiters(nodeId, limiterId);
                String msg = gostDto == null ? "节点无响应" : gostDto.getMsg();
                if (Objects.equals("OK", msg) || (msg != null && msg.contains("not found"))) {
                    deleted = true;
                    notes.add("节点 " + nodeId + " 清理孤儿限流器 id=" + limiterId + "（库内已无该限速规则）");
                } else {
                    notes.add("节点 " + nodeId + " 清理孤儿限流器 id=" + limiterId + " 失败：" + msg);
                }
            } catch (RuntimeException e) {
                // 单条异常只记一条说明：一条删不掉不能拖垮同一节点其余孤儿（与上面循环同一口径）
                notes.add("节点 " + nodeId + " 清理孤儿限流器 id=" + limiterId + " 失败：" + e.getMessage());
            }
            if (deleted) {
                reaped++;
            }
        }
        if (orphans.size() > attempted) {
            notes.add("节点 " + nodeId + " 孤儿限流器其余 " + (orphans.size() - attempted) + " 条下轮再处理");
        }
        if (attempted > 0 && reaped == 0 && orphans.size() <= attempted) {
            log.warn("节点 {} 本轮 {} 条孤儿限流器全部删除失败，下轮重试", nodeId, attempted);
        }
    }

    /** 只有纯数字、且能解析成正整数才算本面板下发的 limiter 名；其余返回 null，一律不碰。 */
    private static Long parseLimiterId(String rawName) {
        if (rawName == null) {
            return null;
        }
        String name = rawName.trim();
        if (!NUMERIC_LIMITER_NAME.matcher(name).matches()) {
            return null;
        }
        try {
            long id = Long.parseLong(name);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 与 GostUtil.createLimiterData 同一口径：单条 "$ &lt;speed&gt;MB &lt;speed&gt;MB"。 */
    private String expectedLimits(String speedInMBps) {
        return "$ " + speedInMBps + "MB " + speedInMBps + "MB";
    }

    /** 只有一条 limits 且与期望完全一致才算对；多条/空/缺值一律视为漂移。 */
    private boolean limitsMatch(String expected, List<String> actual) {
        if (actual == null || actual.size() != 1) {
            return false;
        }
        String value = actual.get(0);
        return value != null && expected.equals(value.trim());
    }

    private String convertBitsToMBps(Integer speedInBits) {
        if (speedInBits == null || speedInBits <= 0) {
            return "0.0";
        }
        double mbs = speedInBits / 8.0;
        BigDecimal bd = BigDecimal.valueOf(mbs).setScale(1, RoundingMode.HALF_UP);
        return bd.doubleValue() + "";
    }
}
