package com.admin.compat;

import com.admin.config.ForwardPortUniqueMigration;
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
 * forward_port(node_id, port) 唯一索引迁移的回归测试（round52）。
 */
class ForwardPortUniqueMigrationTest {

    private JdbcTemplate jdbcTemplate;
    private ForwardPortUniqueMigration migration;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        migration = new ForwardPortUniqueMigration();
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
        assertTrue(sampled.contains("node_id=7"), "采样要写出 node_id，实际：" + sampled);
        assertTrue(sampled.contains("port=30401"), "采样要写出 port，实际：" + sampled);
        assertTrue(sampled.contains("count="), "采样要写出 count，实际：" + sampled);
        assertEquals(5, countOccurrences(sampled, "node_id="), "只采前 5 组，实际：" + sampled);
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
        assertTrue(sql.contains("uk_forward_port_node_port"), "索引名要稳定：" + sql);
        assertTrue(sql.contains("on forward_port(node_id, port)"),
                "索引列必须是 (node_id, port)，否则跨节点相同端口会被误拒：" + sql);
        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    // ========== 4) 探针 SQL 形状 ==========

    @Test
    void duplicateCountSqlGroupsByNodeAndPort() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        migration.run(null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(captor.capture(), eq(Long.class));
        String sql = normalize(captor.getValue());
        assertTrue(sql.contains("count(*)"), sql);
        assertTrue(sql.contains("from forward_port"), sql);
        assertTrue(sql.contains("group by node_id, port"), "端口占用是 (node_id, port) 二元组：" + sql);
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
                new SQLException("index uk_forward_port_node_port already exists")))
                .when(jdbcTemplate).execute(anyString());

        assertDoesNotThrow(() -> migration.run(null));
    }

    // ---------------------------------------------------------------- 脚手架

    private static List<Map<String, Object>> sampleRows(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node_id", 7L + i);
            row.put("port", 30401 + i);
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
