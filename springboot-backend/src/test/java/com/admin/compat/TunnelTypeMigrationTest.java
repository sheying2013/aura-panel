package com.admin.compat;

import com.admin.config.TunnelTypeMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * tunnel.type 空值回填迁移（TunnelTypeMigration）的本地夹具锁定。
 *
 * <p>要守住的行为：① 没有 NULL 行时一次 UPDATE 都不能发；② 有 NULL 行时只回填一次，
 * 且 SQL 必须是 {@code UPDATE tunnel SET type = 1 WHERE type IS NULL}；
 * ③ UPDATE 抛异常不能冒到 ApplicationRunner 外面（否则整个应用起不来）；
 * ④ 重复执行幂等：第二次 COUNT 已经是 0，不再 UPDATE；
 * ⑤ 连 COUNT 都失败时同样只告警。
 */
class TunnelTypeMigrationTest {

    private JdbcTemplate jdbcTemplate;
    private TunnelTypeMigration migration;
    private ApplicationArguments args;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        args = mock(ApplicationArguments.class);
        migration = new TunnelTypeMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void noNullRowSkipsUpdateEntirely() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        migration.run(args);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).queryForObject(countSql.capture(), eq(Integer.class));
        String sql = countSql.getValue().toUpperCase();
        assertTrue(sql.contains("COUNT(*)") && sql.contains("FROM TUNNEL") && sql.contains("TYPE IS NULL"),
                "计数语句必须是 SELECT COUNT(*) FROM tunnel WHERE type IS NULL，实际：" + countSql.getValue());
        verify(jdbcTemplate, never()).update(anyString());
    }

    @Test
    void nullRowsAreBackfilledExactlyOnceWithTypeOne() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(3);
        when(jdbcTemplate.update(anyString())).thenReturn(3);

        migration.run(args);

        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).update(updateSql.capture());
        String sql = updateSql.getValue().toUpperCase();
        assertTrue(sql.startsWith("UPDATE TUNNEL"), "回填目标必须是 tunnel 表，实际：" + updateSql.getValue());
        assertTrue(sql.contains("SET TYPE = 1") || sql.contains("SET TYPE=1"),
                "空类型必须落成 1（直连），实际：" + updateSql.getValue());
        assertTrue(sql.contains("WHERE TYPE IS NULL"), "只能命中空类型行，实际：" + updateSql.getValue());
        assertEquals(1, countOfOccurrences(sql, "UPDATE "), "一次迁移只允许一条 UPDATE：" + updateSql.getValue());
    }

    @Test
    void updateFailureIsSwallowedAndDoesNotBlockStartup() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(5);
        when(jdbcTemplate.update(anyString()))
                .thenThrow(new DataAccessResourceFailureException("database is locked"));

        assertDoesNotThrow(() -> migration.run(args), "回填失败必须只告警，不能让 ApplicationRunner 抛出去");

        verify(jdbcTemplate, times(1)).update(anyString());
    }

    @Test
    void secondRunDoesNotUpdateAgain() {
        // 第一次 COUNT=3 → 回填；第二次 COUNT=0（已回填过）→ 直接返回
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(3, 0);
        when(jdbcTemplate.update(anyString())).thenReturn(3);

        migration.run(args);
        migration.run(args);

        verify(jdbcTemplate, times(2)).queryForObject(anyString(), eq(Integer.class));
        verify(jdbcTemplate, times(1)).update(anyString());
    }

    @Test
    void countFailureIsSwallowedAndSkipsBackfill() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("no such table: tunnel"));

        assertDoesNotThrow(() -> migration.run(args), "计数失败同样只告警，不能让应用起不来");

        verify(jdbcTemplate, never()).update(anyString());
    }

    private static int countOfOccurrences(String text, String keyword) {
        int count = 0;
        int index = text.indexOf(keyword);
        while (index >= 0) {
            count++;
            index = text.indexOf(keyword, index + keyword.length());
        }
        return count;
    }
}
