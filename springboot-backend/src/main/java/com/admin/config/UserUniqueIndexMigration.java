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
 * user 表用户名的唯一索引迁移（round49）。
 *
 * <p>真机基线：6 并发 POST /api/v1/user/create 同名，库里落下 3 条重复行（id 8,9,10），
 * {@code user} 表上没有任何唯一索引（{@code .indexes user} 为空）。
 * createUser 原本是 "count 预查 + save"（UserServiceImpl#createUser），两个并发请求双双通过预查，
 * 应用层再怎么写都挡不住——唯一性必须由数据库承担。
 *
 * <p>老库可能已经存在重复数据，<b>绝不清理</b>：先探针重复数，有重复就只告警（列出前 5 个重复用户名与条数）
 * 并跳过建索引，交人工决定保留哪一行；重复数据清理干净后下次启动会自动补上索引。
 * 建索引失败、探针失败都只告警不阻断启动——并发写冲突仍由 createUser/updateUser 的 catch 兜住。
 *
 * <p>{@code @Order(4)} 排在 ForwardSchemaMigration(0)、TunnelTypeMigration(1)、TopologyDataAudit(2)、
 * UserTunnelIndexMigration(3) 之后，启动期不与其他迁移抢写锁。
 */
@Slf4j
@Component
@Order(4)
public class UserUniqueIndexMigration implements ApplicationRunner {

    /** 重复探针：只数有多少个用户名重复，不关心具体是谁。 */
    static final String DUPLICATE_COUNT_SQL =
            "SELECT COUNT(*) FROM (SELECT user, COUNT(*) c FROM user GROUP BY user HAVING c > 1)";

    /** 采样只取前 5 组：够定位问题，又不会把启动日志刷爆。 */
    static final String DUPLICATE_SAMPLE_SQL =
            "SELECT user, COUNT(*) c FROM user GROUP BY user HAVING COUNT(*) > 1 LIMIT 5";

    static final String CREATE_UNIQUE_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_user_user ON user(user)";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Long duplicates;
        try {
            duplicates = jdbcTemplate.queryForObject(DUPLICATE_COUNT_SQL, Long.class);
        } catch (Exception e) {
            log.warn("数据库迁移：user 用户名重复检查失败，本次跳过唯一索引（下次启动会重试）", e);
            return;
        }

        if (duplicates != null && duplicates > 0) {
            log.warn("数据库迁移：user 表存在 {} 个重复用户名，跳过唯一索引 uk_user_user 创建，"
                    + "且未清理任何数据（需人工确认保留哪一行）：{}", duplicates, sampleDuplicates());
            return;
        }

        try {
            jdbcTemplate.execute(CREATE_UNIQUE_INDEX_SQL);
            log.info("数据库迁移：已确保 user(user) 唯一索引 uk_user_user 存在");
        } catch (Exception e) {
            log.warn("数据库迁移：user 用户名唯一索引创建失败，并发重复仍由 createUser/updateUser 的冲突捕获兜底", e);
        }
    }

    /** 采样查询只是给告警加可读信息，它自己失败不能改变「跳过建索引」这个决定。 */
    private String sampleDuplicates() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(DUPLICATE_SAMPLE_SQL);
            if (rows == null || rows.isEmpty()) {
                return "（采样查询无结果）";
            }
            List<String> groups = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                groups.add("user=" + row.get("user") + ",count=" + row.get("c"));
            }
            return groups.toString();
        } catch (Exception e) {
            log.warn("数据库迁移：user 重复用户名采样失败，仅告警", e);
            return "（采样失败：" + e.getMessage() + "）";
        }
    }
}
