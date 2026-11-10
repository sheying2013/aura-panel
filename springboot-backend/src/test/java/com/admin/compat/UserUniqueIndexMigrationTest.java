package com.admin.compat;

import com.admin.config.UserUniqueIndexMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * user(user) 唯一索引迁移（UserUniqueIndexMigration）的本地夹具锁定。
 *
 * <p>真机基线：6 并发创建同名用户后 user 表有 3 条重复行（id 8,9,10），{@code .indexes user} 为空。
 * 要守住的行为：① 老库已有重复用户名时只告警、绝不建索引、更不删数据（人工决定保留哪一行）；
 * ② 没有重复时建的是 user 列上的唯一索引，且带 IF NOT EXISTS 保证重启幂等；
 * ③ 探针本身失败只告警，不建索引也不阻塞启动；④ 索引已存在（建索引抛错）同样只告警；
 * ⑤ 探针返回 0 仍是"无重复"，必须建索引——不能因为拿到 0 就当作查询失败跳过。
 */
class UserUniqueIndexMigrationTest {

    private JdbcTemplate jdbcTemplate;
    private UserUniqueIndexMigration migration;
    private ApplicationArguments args;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        args = mock(ApplicationArguments.class);
        migration = new UserUniqueIndexMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void duplicatesSkipIndex() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(duplicates(2));

        migration.run(args);

        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).update(anyString());
        // 采样只查前 5 组，且绝不删数据
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).queryForList(sqlCaptor.capture());
        String sampleSql = sqlCaptor.getValue().toUpperCase().replaceAll("\\s+", " ");
        assertTrue(sampleSql.contains("GROUP BY USER"), "采样必须按用户名分组，实际：" + sqlCaptor.getValue());
        assertTrue(sampleSql.contains("HAVING COUNT(*) > 1"), "采样只看重复名，实际：" + sqlCaptor.getValue());
        assertTrue(sampleSql.contains("LIMIT 5"), "采样只取前 5 组收敛日志，实际：" + sqlCaptor.getValue());
    }

    @Test
    void cleanCreatesIndex() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        migration.run(args);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).execute(sqlCaptor.capture());
        String sql = sqlCaptor.getValue().toUpperCase();
        assertTrue(sql.contains("CREATE UNIQUE INDEX"), "必须是唯一索引，实际：" + sqlCaptor.getValue());
        assertTrue(sql.contains("IF NOT EXISTS"), "重启必须幂等，实际：" + sqlCaptor.getValue());
        assertTrue(sql.contains("UK_USER_USER"), "索引名必须是 uk_user_user，实际：" + sqlCaptor.getValue());
        assertTrue(sql.contains("ON USER(USER)"), "索引列必须是 user(user)，实际：" + sqlCaptor.getValue());
    }

    @Test
    void sqlErrorDoesNotThrow() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("database is locked"));

        assertDoesNotThrow(() -> migration.run(args), "重复检查失败必须只告警");
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void existingIndexNoop() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        // 索引已存在时驱动可能直接抛错（老版本 SQLite 的 IF NOT EXISTS 未生效等），启动不能因此挂掉
        doThrow(new DataAccessResourceFailureException("index uk_user_user already exists"))
                .when(jdbcTemplate).execute(anyString());

        assertDoesNotThrow(() -> migration.run(args), "索引已存在必须当作无操作");
        verify(jdbcTemplate, times(1)).execute(anyString());
        verify(jdbcTemplate, never()).update(anyString(), anyString(), anyString());
    }

    @Test
    void zeroCountStillCreates() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        migration.run(args);

        verify(jdbcTemplate, times(1)).execute(anyString());
        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    void probeCountsDuplicatesOverGroupedNames() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        migration.run(args);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).queryForObject(sqlCaptor.capture(), eq(Long.class));
        String sql = sqlCaptor.getValue().toUpperCase().replaceAll("\\s+", " ");
        assertTrue(sql.contains("SELECT COUNT(*) FROM (SELECT USER, COUNT(*) C FROM USER GROUP BY USER HAVING C > 1)"),
                "探针必须是按用户名分组后的重复计数，实际：" + sqlCaptor.getValue());
    }

    private List<Map<String, Object>> duplicates(int groups) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= groups; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user", "dup_user_" + i);
            row.put("c", 3);
            rows.add(row);
        }
        return rows;
    }
}
