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
 * user_tunnel 的 (user_id, tunnel_id) 唯一索引迁移（round23）。
 *
 * <p>基线只有"先 count 再 save"的应用层预查，两个并发请求（双击、重放）会双双通过预查，
 * 在库里落下两条完全相同的用户隧道授权：面板出现重复行，流量统计按行翻倍，
 * 删除其中一条后另一条仍在，权限看起来"删不掉"。
 * 这里在启动后补一次唯一索引，把并发收敛到数据库；Java 侧再捕获冲突转成友好提示。
 *
 * <p>老库可能已经存在重复数据，<b>绝不清理</b>：先查重复组，有重复就只告警并跳过建索引，
 * 交人工决定保留哪一行；重复数据清理干净后下次启动会自动补上索引。
 * 建索引失败只告警不阻断启动——写库冲突仍由 assignUserTunnel 的 catch 兜住。
 *
 * <p>{@code @Order(3)} 排在 ForwardSchemaMigration(0)、TunnelTypeMigration(1)、
 * TopologyDataAudit(2) 之后，启动期不与其他迁移抢写锁。
 */
@Slf4j
@Component
@Order(3)
public class UserTunnelIndexMigration implements ApplicationRunner {

    /** 只看前 5 组：够定位问题，又不会把启动日志刷爆。 */
    static final String DUPLICATE_GROUPS_SQL =
            "SELECT user_id, tunnel_id, COUNT(*) c FROM user_tunnel GROUP BY user_id, tunnel_id HAVING COUNT(*) > 1 LIMIT 5";

    static final String CREATE_UNIQUE_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_user_tunnel_user_tunnel ON user_tunnel(user_id, tunnel_id)";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> duplicates;
        try {
            duplicates = jdbcTemplate.queryForList(DUPLICATE_GROUPS_SQL);
        } catch (Exception e) {
            log.warn("数据库迁移：user_tunnel 重复组检查失败，本次跳过唯一索引（下次启动会重试）", e);
            return;
        }

        if (duplicates != null && !duplicates.isEmpty()) {
            log.warn("数据库迁移：user_tunnel 存在 {} 组重复的 (user_id, tunnel_id)，跳过唯一索引创建，"
                    + "且未清理任何数据（需人工确认保留哪一行）：{}", duplicates.size(), describe(duplicates));
            return;
        }

        try {
            jdbcTemplate.execute(CREATE_UNIQUE_INDEX_SQL);
            log.info("数据库迁移：已确保 user_tunnel(user_id, tunnel_id) 唯一索引 uk_user_tunnel_user_tunnel 存在");
        } catch (Exception e) {
            log.warn("数据库迁移：user_tunnel 唯一索引创建失败，并发重复仍由 assignUserTunnel 的冲突捕获兜底", e);
        }
    }

    private List<String> describe(List<Map<String, Object>> duplicates) {
        List<String> groups = new ArrayList<>();
        for (Map<String, Object> row : duplicates) {
            groups.add("user_id=" + row.get("user_id")
                    + ",tunnel_id=" + row.get("tunnel_id")
                    + ",count=" + row.get("c"));
        }
        return groups;
    }
}
