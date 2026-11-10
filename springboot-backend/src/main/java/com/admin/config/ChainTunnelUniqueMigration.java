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
 * chain_tunnel 的 (node_id, port) 唯一索引迁移（round54）。
 *
 * <p>真机基线：schema.sql 建 chain_tunnel 时只有主键，没有 (node_id, port) 唯一约束。
 * 中继节点与出口节点的监听服务需要占用宿主机的物理端口。如果并发创建隧道或拓扑调整，
 * 容易出现多个隧道在同一个节点上分配相同端口的情况，导致节点上只有一个服务能监听成功，
 * 其它服务 bind 冲突失败。
 *
 * <p>SQLite 和标准 SQL 中，唯一索引对 NULL 值视为互不相同，因此入口节点（port 为 null）
 * 可以安全共存。对于非空 port（中继与出口），唯一约束可以从数据库底层杜绝端口重叠。
 *
 * <p>老库可能已经存在重复数据，<b>绝不自动删除</b>：先探针重复组数，有重复就只告警（列出前 5 组），
 * 并跳过建索引，交人工核对；清理干净后下次启动会自动补上索引。探针或建索引失败只告警不阻断启动。
 *
 * <p>{@code @Order(8)} 排在 ForwardPortUniqueMigration(7) 之后。
 */
@Slf4j
@Component
@Order(8)
public class ChainTunnelUniqueMigration implements ApplicationRunner {

    /** 重复探针：数有多少组 (node_id, port) 重复（仅检查非空端口）。 */
    static final String DUPLICATE_COUNT_SQL =
            "SELECT COUNT(*) FROM (SELECT node_id, port, COUNT(*) c FROM chain_tunnel "
                    + "WHERE port IS NOT NULL GROUP BY node_id, port HAVING c > 1)";

    /** 采样只取前 5 组。 */
    static final String DUPLICATE_GROUPS_SQL =
            "SELECT node_id, port, COUNT(*) c FROM chain_tunnel "
                    + "WHERE port IS NOT NULL GROUP BY node_id, port HAVING COUNT(*) > 1 LIMIT 5";

    static final String CREATE_UNIQUE_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_chain_tunnel_node_port ON chain_tunnel(node_id, port)";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Long duplicates;
        try {
            duplicates = jdbcTemplate.queryForObject(DUPLICATE_COUNT_SQL, Long.class);
        } catch (Exception e) {
            log.warn("数据库迁移：chain_tunnel 端口占用重复检查失败，本次跳过唯一索引（下次启动会重试）", e);
            return;
        }

        if (duplicates != null && duplicates > 0) {
            log.error("数据库迁移：chain_tunnel 存在历史重复端口占用，未创建唯一索引 uk_chain_tunnel_node_port"
                            + "（{} 组重复，采样 {}）；同一 (node_id, port) 上的两条中继/出口链路只有一条能在节点上监听成功，"
                            + "请人工确认每组保留哪一行并清理其余行，然后重启服务",
                    duplicates, sampleDuplicates());
            return;
        }

        try {
            jdbcTemplate.execute(CREATE_UNIQUE_INDEX_SQL);
            log.info("数据库迁移：已确保 chain_tunnel(node_id, port) 唯一索引 uk_chain_tunnel_node_port 存在");
        } catch (Exception e) {
            log.warn("数据库迁移：chain_tunnel 唯一索引创建失败，并发重复仍由应用层检查兜底", e);
        }
    }

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
            log.warn("数据库迁移：chain_tunnel 重复组采样失败，仅告警", e);
            return "（采样失败：" + e.getMessage() + "）";
        }
    }
}
