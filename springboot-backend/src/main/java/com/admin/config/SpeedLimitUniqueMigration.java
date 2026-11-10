package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * speed_limit 的 (tunnel_id, name) 唯一索引迁移（round51）。
 *
 * <p>真机基线：createSpeedLimit 只校验隧道与速度，不查同隧道同名。4 并发 POST 创建同名规则，
 * 库中落下多条同名行，每条各自在隧道节点上下发一个 limiter；面板上分不清哪条是"真"的，
 * 删除其中一条后另一条仍在限速。应用层预查（SpeedLimitServiceImpl#createSpeedLimit）
 * 只能挡住常态，两条并发请求仍会双双通过预查——唯一性必须由数据库承担。
 *
 * <p>老库可能已经存在重复数据，<b>绝不清理</b>：先探针重复组数，有重复就只告警（列出前 5 组
 * tunnel_id/name/count）并跳过建索引，交人工决定保留哪一行；重复清理干净后下次启动会自动补上索引。
 * 探针失败、建索引失败都只告警不阻断启动——写库冲突仍由 createSpeedLimit 的 catch 兜住。
 *
 * <p>{@code @Order(6)} 排在 ForwardSchemaMigration(0)、TunnelTypeMigration(1)、TopologyDataAudit(2)、
 * UserTunnelIndexMigration(3)、UserUniqueIndexMigration(4) 之后，启动期不与其他迁移抢写锁。
 */
@Slf4j
@Component
@Order(6)
public class SpeedLimitUniqueMigration implements ApplicationRunner {

    /** 重复探针：数有多少组 (tunnel_id, name) 重复，不关心具体是哪组。 */
    static final String DUPLICATE_COUNT_SQL =
            "SELECT COUNT(*) FROM (SELECT tunnel_id, name, COUNT(*) c FROM speed_limit "
                    + "GROUP BY tunnel_id, name HAVING c > 1)";

    /** 采样只取前 5 组：够定位问题，又不会把启动日志刷爆。 */
    static final String DUPLICATE_GROUPS_SQL =
            "SELECT tunnel_id, name, COUNT(*) c FROM speed_limit "
                    + "GROUP BY tunnel_id, name HAVING COUNT(*) > 1 LIMIT 5";

    static final String CREATE_UNIQUE_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_speed_limit_tunnel_name ON speed_limit(tunnel_id, name)";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Long duplicates;
        try {
            duplicates = jdbcTemplate.queryForObject(DUPLICATE_COUNT_SQL, Long.class);
        } catch (Exception e) {
            log.warn("数据库迁移：speed_limit 同隧道同名重复检查失败，本次跳过唯一索引（下次启动会重试）", e);
            return;
        }

        if (duplicates != null && duplicates > 0) {
            // round51 QA P1：这不是普通告警——索引建不上，createSpeedLimit 的并发兜底就只剩应用层预查，
            // 多实例/双击仍可能写进两行同名。必须用 ERROR 让人看见并人工清库后重启。
            log.error("数据库迁移：speed_limit 存在 {} 组重复的 (tunnel_id, name)，唯一索引 "
                    + "uk_speed_limit_tunnel_name 未创建，并发创建同名规则将失去数据库兜底；"
                    + "请人工确认每组保留哪一行并删除其余行，然后重启服务：{}",
                    duplicates, sampleDuplicates());
            return;
        }

        try {
            jdbcTemplate.execute(CREATE_UNIQUE_INDEX_SQL);
            log.info("数据库迁移：已确保 speed_limit(tunnel_id, name) 唯一索引 uk_speed_limit_tunnel_name 存在");
        } catch (Exception e) {
            log.warn("数据库迁移：speed_limit 唯一索引创建失败，并发重复仍由 createSpeedLimit 的冲突捕获兜底", e);
        }
    }

    /** 采样查询只是给告警加可读信息，它自己失败不能改变「跳过建索引」这个决定。 */
    private String sampleDuplicates() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(DUPLICATE_GROUPS_SQL);
            if (rows == null || rows.isEmpty()) {
                return "（采样查询无结果）";
            }
            List<String> groups = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                groups.add("tunnel_id=" + row.get("tunnel_id")
                        + ",name=" + row.get("name")
                        + ",count=" + row.get("c"));
            }
            return groups.toString();
        } catch (Exception e) {
            log.warn("数据库迁移：speed_limit 重复组采样失败，仅告警", e);
            return "（采样失败：" + e.getMessage() + "）";
        }
    }
}
