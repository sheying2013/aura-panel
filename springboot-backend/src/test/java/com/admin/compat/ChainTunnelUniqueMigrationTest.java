package com.admin.compat;

import com.admin.config.ChainTunnelUniqueMigration;
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
 * chain_tunnel(node_id, port) 唯一索引迁移的回归测试（round54）。
 */
class ChainTunnelUniqueMigrationTest {

    private JdbcTemplate jdbcTemplate;
    private ChainTunnelUniqueMigration migration;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        migration = new ChainTunnelUniqueMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbcTemplate);
    }

    // ========== 1) 有重复：只 error 告警，不建索引 ==========

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
        assertTrue(sampled.contains("node_id=3"), "采样要写出 node_id，实际：" + sampled);
        assertTrue(sampled.contains("port=30201"), "采样要写出 port，实际：" + sampled);
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
        assertTrue(sql.contains("uk_chain_tunnel_node_port"), "索引名要稳定：" + sql);
        assertTrue(sql.contains("on chain_tunnel(node_id, port)"),
                "索引列必须是 (node_id, port)，允许入口节点 NULL 端口同时存在：" + sql);
        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    // ========== 4) 探针与异常安全 ==========

    @Test
    void probeExceptionDoesNotThrow() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new BadSqlGrammarException("audit", "select", new SQLException("table missing")));

        assertDoesNotThrow(() -> migration.run(null));
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void executeExceptionDoesNotThrow() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        doThrow(new BadSqlGrammarException("create", "create index", new SQLException("disk full")))
                .when(jdbcTemplate).execute(anyString());

        assertDoesNotThrow(() -> migration.run(null));
    }

    // ========== 辅助方法 ==========

    private static List<Map<String, Object>> sampleRows(int n) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node_id", 3L + i);
            row.put("port", 30201 + i);
            row.put("c", 2L + i);
            list.add(row);
        }
        return list;
    }

    private static String normalize(String sql) {
        return sql == null ? "" : sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
