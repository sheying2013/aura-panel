package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * forward.pause_reason 的幂等迁移（round19）。
 *
 * <p>schema.sql 由 spring.sql.init 以 always 模式执行，但里面全是
 * {@code CREATE TABLE IF NOT EXISTS}——对已经建好的库不会加列，而 MyBatis-Plus 生成的
 * SELECT/INSERT 会带上实体里的 pause_reason 列，缺列就会整条查询报错。
 * 所以启动完成后补一次 ALTER TABLE，重复执行按 SQLite 的 duplicate column 报错吃掉。
 *
 * <p>用 ApplicationRunner 而不是 @PostConstruct：ApplicationRunner 在上下文刷新完成、
 * schema.sql 初始化之后才跑，不会出现「表还没建就先 ALTER」的竞态。
 */
@Slf4j
@Component
@Order(0)
public class ForwardSchemaMigration implements ApplicationRunner {

    @Resource
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE forward ADD COLUMN pause_reason VARCHAR(24) NOT NULL DEFAULT ''");
            log.warn("数据库迁移：forward 表已新增 pause_reason 列");
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("duplicate column")) {
                log.info("数据库迁移：forward.pause_reason 已存在，跳过");
            } else {
                log.error("数据库迁移：forward.pause_reason 添加失败，暂停原因将写不进去（其余功能不受影响）", e);
            }
        }
    }
}
