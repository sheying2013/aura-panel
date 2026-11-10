package com.admin.compat;

import com.admin.config.TokenVersionMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * user.token_version 补列迁移（TokenVersionMigration）的本地夹具锁定。
 *
 * <p>schema.sql 里全是 CREATE TABLE IF NOT EXISTS，老库启动时不会补列；而 User 实体新增了
 * tokenVersion，MyBatis-Plus 之后生成的 SELECT/INSERT 都带 token_version——缺列时登录直接查不出用户。
 *
 * <p>要守住的行为：① 干净库上执行的就是加列语句，NOT NULL DEFAULT 0 保证历史行可读；
 * ② 重复执行（duplicate column）只当无操作，不抛；③ 其它数据库错误同样只记日志不抛，
 * 启动不能被补列失败拖死；④ @Order(5) 必须排在已有迁移（0..4）之后，不与它们撞号。
 */
class TokenVersionMigrationTest {

    private JdbcTemplate jdbcTemplate;
    private TokenVersionMigration migration;
    private ApplicationArguments args;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        args = mock(ApplicationArguments.class);
        migration = new TokenVersionMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void cleanDatabaseAddsColumn() {
        migration.run(args);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).execute(captor.capture());
        String sql = captor.getValue().toUpperCase().replaceAll("\\s+", " ");
        assertTrue(sql.contains("ALTER TABLE USER ADD COLUMN TOKEN_VERSION"), "必须补 token_version 列，实际：" + captor.getValue());
        assertTrue(sql.contains("INTEGER NOT NULL DEFAULT 0"), "NOT NULL DEFAULT 0 才能让历史行读成 0，实际：" + captor.getValue());
    }

    @Test
    void duplicateColumnIsSkipped() {
        doThrow(new DataAccessResourceFailureException("duplicate column name: token_version"))
                .when(jdbcTemplate).execute(anyString());

        assertDoesNotThrow(() -> migration.run(args), "重复执行必须当无操作");
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void otherDatabaseErrorDoesNotThrow() {
        doThrow(new DataAccessResourceFailureException("database is locked"))
                .when(jdbcTemplate).execute(anyString());

        assertDoesNotThrow(() -> migration.run(args), "补列失败只记日志，不能阻塞启动");
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void orderedAfterExistingMigrations() {
        Order order = TokenVersionMigration.class.getAnnotation(Order.class);
        assertEquals(5, order.value(), "必须排在 ForwardSchemaMigration(0)..UserUniqueIndexMigration(4) 之后，不能撞号");
        assertTrue(ApplicationRunner.class.isAssignableFrom(TokenVersionMigration.class), "应在启动完成后执行");
    }
}
