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
 * forward_port 的 (node_id, port) 唯一索引迁移（round52）。
 *
 * <p>真机基线：schema.sql 建 forward_port 时只有主键，没有 (node_id, port) 约束。createForward 的端口分配
 * 是「先查空闲端口、再写库」两步，中间没有任何互斥——两个并发创建指向同一个入口节点时，各自都看到
 * 30401 可用，于是两条转发把同一个 (node_id, port) 写进库；节点上只有一条真能监听成功，另一条面板显示正常
 * 却永远连不通，而且这条脏行会一直占着该端口，后续自动分配永远分不到它。
 *
 * <p>应用层的节点端口锁只能挡住单进程，唯一性必须由数据库承担。老库可能已经存在重复数据，
 * <b>绝不清理</b>：先探针重复组数，有重复就只告警（列出前 5 组 node_id/port/count）并跳过建索引，
 * 交人工决定保留哪一行；重复清理干净后下次启动会自动补上索引。探针失败、建索引失败都只告警不阻断启动
 * ——写库冲突仍由 ForwardServiceImpl#saveForwardPort 的 catch 兜住。
 *
 * <p>{@code @Order(7)} 排在 ForwardSchemaMigration(0)、TunnelTypeMigration(1)、TopologyDataAudit(2)、
 * UserTunnelIndexMigration(3)、UserUniqueIndexMigration(4)、SpeedLimitUniqueMigration(6) 之后，
 * 启动期不与其他迁移抢写锁。
 */
@Slf4j
@Component
@Order(7)
public class ForwardPortUniqueMigration implements ApplicationRunner {

    /** 重复探针：数有多少组 (node_id, port) 重复，不关心具体是哪组。 */
    static final String DUPLICATE_COUNT_SQL =
            "SELECT COUNT(*) FROM (SELECT node_id, port, COUNT(*) c FROM forward_port "
                    + "GROUP BY node_id, port HAVING c > 1)";

    /** 采样只取前 5 组：够定位问题，又不会把启动日志刷爆。 */
    static final String DUPLICATE_GROUPS_SQL =
            "SELECT node_id, port, COUNT(*) c FROM forward_port "
                    + "GROUP BY node_id, port HAVING COUNT(*) > 1 LIMIT 5";

    static final String CREATE_UNIQUE_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_forward_port_node_port ON forward_port(node_id, port)";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Long duplicates;
        try {
            duplicates = jdbcTemplate.queryForObject(DUPLICATE_COUNT_SQL, Long.class);
        } catch (Exception e) {
            log.warn("数据库迁移：forward_port 端口占用重复检查失败，本次跳过唯一索引（下次启动会重试）", e);
            return;
        }

        if (duplicates != null && duplicates > 0) {
            // 不是普通告警：索引建不上，saveForwardPort 的并发冲突兜底就只剩进程内的节点端口锁，
            // 多实例部署时同一个 (node_id, port) 仍会被写两次。必须用 ERROR 让人看见并人工清库后重启。
            log.error("数据库迁移：forward_port 存在历史重复端口占用，未创建唯一索引 uk_forward_port_node_port"
                            + "（{} 组重复，采样 {}）；同一 (node_id, port) 上的两条转发只有一条能在节点上监听成功，"
                            + "请人工确认每组保留哪一行并删除其余行，然后重启服务",
                    duplicates, sampleDuplicates());
            return;
        }

        try {
            jdbcTemplate.execute(CREATE_UNIQUE_INDEX_SQL);
            log.info("数据库迁移：已确保 forward_port(node_id, port) 唯一索引 uk_forward_port_node_port 存在");
        } catch (Exception e) {
            log.warn("数据库迁移：forward_port 唯一索引创建失败，并发重复仍由节点端口锁与 saveForwardPort 的冲突捕获兜底", e);
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
                groups.add("node_id=" + row.get("node_id")
                        + ",port=" + row.get("port")
                        + ",count=" + row.get("c"));
            }
            return groups.toString();
        } catch (Exception e) {
            log.warn("数据库迁移：forward_port 重复组采样失败，仅告警", e);
            return "（采样失败：" + e.getMessage() + "）";
        }
    }
}
