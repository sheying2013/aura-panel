package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * tunnel.type 的幂等回填迁移：历史行 type IS NULL 一律落成 1（直连）。
 *
 * <p>schema.sql 用的是 {@code CREATE TABLE IF NOT EXISTS}，对早已建好的库不会改动列定义；
 * 早期库的 tunnel.type 允许为空，于是存量行留着 NULL。空类型会让两处逻辑退化：
 * 孤儿清扫按类型判定「这条隧道该有哪些资源」时只能保守跳过、不回收节点上的残留，
 * 发布拓扑路径也要为 null 单独判空。这里在启动后补一次回填，让类型只有一个事实来源。
 *
 * <p>{@code @Order(1)} 排在 ForwardSchemaMigration(0) 之后：先补齐 forward 侧列，再做数据回填，
 * 两次迁移不抢 SQLite 的写锁；重复执行时 COUNT 为 0 直接返回，不做无谓的 UPDATE。
 * 迁移失败只告警不阻断启动——空类型行保持原样，功能退化但不影响可用性。
 */
@Slf4j
@Component
@Order(1)
public class TunnelTypeMigration implements ApplicationRunner {

    private static final String COUNT_NULL_TYPE_SQL = "SELECT COUNT(*) FROM tunnel WHERE type IS NULL";
    private static final String BACKFILL_NULL_TYPE_SQL = "UPDATE tunnel SET type = 1 WHERE type IS NULL";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer nullRows = jdbcTemplate.queryForObject(COUNT_NULL_TYPE_SQL, Integer.class);
            if (nullRows == null || nullRows == 0) {
                return;
            }
            int updated = jdbcTemplate.update(BACKFILL_NULL_TYPE_SQL);
            log.info("数据库迁移：tunnel.type 为空的 {} 行已回填为 1（直连），实际更新 {} 行", nullRows, updated);
        } catch (Exception e) {
            log.warn("数据库迁移：tunnel.type 回填失败，空类型行保持原样（孤儿清扫会保守跳过这些隧道）", e);
        }
    }
}
