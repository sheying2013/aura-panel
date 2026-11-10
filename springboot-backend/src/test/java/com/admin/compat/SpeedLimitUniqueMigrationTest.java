package com.admin.compat;

import com.admin.config.SpeedLimitUniqueMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * speed_limit(tunnel_id, name) 唯一索引迁移的回归测试（round51）。
 *
 * <p>迁移的口径是「能补就补，补不了绝不动数据」：有重复行时只告警并跳过建索引（唯一索引建不上，
 * 硬建会让应用起不来，而清理用户数据不是迁移该干的事）；探针失败、建索引失败同样只告警，
 * 因为启动期被一个可选优化挡住比缺索引更糟——并发重复还有 createSpeedLimit 的冲突捕获兜底。
 *
 * <p>断言只针对 JdbcTemplate 上真正发生的调用（SQL 文本从 ArgumentCaptor 里取），
 * 不依赖迁移类里的常量可见性；迁移改了 SQL 而语义跑偏时这里会红。
 */
class SpeedLimitUniqueMigrationTest {

    private JdbcTemplate jdbcTemplate;
    private SpeedLimitUniqueMigration migration;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        migration = new SpeedLimitUniqueMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbcTemplate);
    }

    // ========== 1) 有重复：只 warn，不建索引 ==========

    @Test
    void duplicateRowsSkipIndexCreation() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(sampleRows(5));

        assertDoesNotThrow(() -> migration.run(null));

        verify(jdbcTemplate, never()).execute(anyString());
    }

    // ========== 2) 采样最多 5 组，字段可读 ==========

    @Test
    void duplicateSampleIsCappedAtFiveGroups() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(9L);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(sampleRows(5));

        migration.run(null);

        String sampled = (String) ReflectionTestUtils.invokeMethod(migration, "sampleDuplicates");
        assertTrue(sampled.contains("tunnel_id=16"), "采样要写出 tunnel_id，实际：" + sampled);
        assertTrue(sampled.contains("name=speed-new"), "采样要写出 name，实际：" + sampled);
        assertTrue(sampled.contains("count="), "采样要写出 count，实际：" + sampled);
        assertEquals(5, countOccurrences(sampled, "tunnel_id="), "只采前 5 组，实际：" + sampled);
        verify(jdbcTemplate, never()).execute(anyString());
    }

    // ========== 3) 无重复：执行幂等唯一索引 ==========

    @Test
    void cleanTableCreatesUniqueIndex() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        migration.run(null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(captor.capture());
        String sql = normalize(captor.getValue());
        assertTrue(sql.startsWith("create unique index"), "必须是唯一索引：" + sql);
        assertTrue(sql.contains("if not exists"), "启动期反复执行必须幂等：" + sql);
        assertTrue(sql.contains("uk_speed_limit_tunnel_name"), "索引名要稳定：" + sql);
        assertTrue(sql.contains("on speed_limit(tunnel_id, name)"),
                "索引列必须是 (tunnel_id, name)，否则同名跨隧道会被误拒：" + sql);
        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    // ========== 4) 探针 SQL 形状 ==========

    @Test
    void duplicateCountSqlGroupsByTunnelAndName() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        migration.run(null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(captor.capture(), eq(Long.class));
        String sql = normalize(captor.getValue());
        assertTrue(sql.contains("count(*)"), sql);
        assertTrue(sql.contains("from speed_limit"), sql);
        assertTrue(sql.contains("group by tunnel_id, name"), "重名是 (tunnel_id, name) 二元组：" + sql);
        assertTrue(sql.contains("having c > 1"), "只数重复组，不数单条：" + sql);
    }

    // ========== 5) 探针失败不阻断启动 ==========

    @Test
    void probeFailureDoesNotBlockStartup() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new BadSqlGrammarException("query", "SELECT ...", new SQLException("no such table")));

        assertDoesNotThrow(() -> migration.run(null));

        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    // ========== 6) 采样失败不改变「跳过建索引」的决定 ==========

    @Test
    void sampleFailureKeepsSkipDecision() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString())).thenThrow(new BadSqlGrammarException(
                "query", "SELECT ...", new SQLException("no such column")));

        assertDoesNotThrow(() -> migration.run(null));

        verify(jdbcTemplate, never()).execute(anyString());
        String sampled = (String) ReflectionTestUtils.invokeMethod(migration, "sampleDuplicates");
        assertTrue(sampled.contains("采样失败"), "采样挂了也要给出可读占位，实际：" + sampled);
    }

    // ========== 7) 建索引失败不阻断启动 ==========

    @Test
    void indexCreationFailureDoesNotBlockStartup() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        doThrow(new BadSqlGrammarException("execute", "CREATE UNIQUE INDEX ...",
                new SQLException("index uk_speed_limit_tunnel_name already exists")))
                .when(jdbcTemplate).execute(anyString());

        assertDoesNotThrow(() -> migration.run(null));
    }

    // ---------------------------------------------------------------- 脚手架

    private static List<Map<String, Object>> sampleRows(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tunnel_id", 16L + i);
            row.put("name", "speed-new");
            row.put("c", 2L);
            rows.add(row);
        }
        return rows;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
