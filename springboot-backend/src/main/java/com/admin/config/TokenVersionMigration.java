package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * user.token_version 的幂等迁移（round51）。
 *
 * <p>schema.sql 由 spring.sql.init 以 always 模式执行，但里面全是
 * {@code CREATE TABLE IF NOT EXISTS}——对已经建好的库不会加列，而 User 实体新增了
 * tokenVersion，MyBatis-Plus 生成的 SELECT/INSERT 都会带上 token_version：缺列时连登录都查不出用户。
 * 所以启动完成后补一次 ALTER TABLE，重复执行按 SQLite 的 duplicate column 报错吃掉。
 *
 * <p>{@code @Order(5)} 排在 ForwardSchemaMigration(0)、TunnelTypeMigration(1)、TopologyDataAudit(2)、
 * UserTunnelIndexMigration(3)、UserUniqueIndexMigration(4) 之后，启动期不与其他迁移抢写锁。
 *
 * <p>失败只记日志不抛：迁移失败时应用仍能起来（便于人工进库处理），不能因为补列失败就连启动都失败。
 */
@Slf4j
@Component
@Order(5)
public class TokenVersionMigration implements ApplicationRunner {

    static final String ADD_TOKEN_VERSION_SQL =
            "ALTER TABLE user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute(ADD_TOKEN_VERSION_SQL);
            log.info("数据库迁移：user 表已新增 token_version 列");
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("duplicate column")) {
                log.info("数据库迁移：user.token_version 已存在，跳过");
            } else {
                // 缺列时 user 表的 SELECT/INSERT 全部会报错（登录、鉴权都受影响），必须人工介入
                log.error("数据库迁移：user.token_version 添加失败，user 表查询将因缺列报错（登录/鉴权不可用），需人工补列", e);
            }
        }
    }
}
