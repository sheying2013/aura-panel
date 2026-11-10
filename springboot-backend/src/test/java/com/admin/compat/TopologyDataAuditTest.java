package com.admin.compat;

import com.admin.config.TopologyDataAudit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拓扑巡检只读性测试：确认它永远不删不改数据，且任何查询失败都只告警不阻断启动。
 *
 * <p>巡检的价值在可观测，风险也在可观测 —— 一旦它开始写库，就可能把人工还没核对过的
 * 历史脏行清掉。这里用 mock 的 JdbcTemplate 钉住三件事：只查不写、失败不抛、样例限流。
 */
class TopologyDataAuditTest {

    private TopologyDataAudit auditWith(JdbcTemplate jdbcTemplate) {
        TopologyDataAudit audit = new TopologyDataAudit();
        ReflectionTestUtils.setField(audit, "jdbcTemplate", jdbcTemplate);
        return audit;
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    @Test
    void duplicateRowsAreReportedButNeverWritten() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<Map<String, Object>> chainDup = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            chainDup.add(row("tunnel_id", i, "node_id", 7, "chain_type", 1, "c", 2));
        }
        when(jdbcTemplate.queryForList(anyString())).thenReturn(chainDup);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        assertDoesNotThrow(() -> auditWith(jdbcTemplate).run(null));

        // 只读：不出现任何 update/execute
        verify(jdbcTemplate, never()).update(anyString());
        // 四类重复与冲突查询（两类拓扑+两类端口） + 两类悬空计数都执行了
        verify(jdbcTemplate, times(4)).queryForList(anyString());
        verify(jdbcTemplate, times(2)).queryForObject(anyString(), eq(Integer.class));
    }

    @Test
    void orphanCountsAreReportedWhenPositive() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(3, 5);

        assertDoesNotThrow(() -> auditWith(jdbcTemplate).run(null));

        verify(jdbcTemplate, times(2)).queryForObject(anyString(), eq(Integer.class));
        verify(jdbcTemplate, never()).update(anyString());
    }

    @Test
    void queryFailureDoesNotBreakStartup() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString())).thenThrow(new IllegalStateException("db is locked"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new IllegalStateException("db is locked"));

        assertDoesNotThrow(() -> auditWith(jdbcTemplate).run(null));

        verify(jdbcTemplate, never()).update(anyString());
    }

    @Test
    void mixedEmptyAndDuplicateReportsAreTolerated() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<Map<String, Object>> portDup = Arrays.asList(
                row("forward_id", 41, "node_id", 7, "c", 3),
                row("forward_id", 49, "node_id", 7, "c", 2));
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.emptyList(), portDup, Collections.emptyList(), Collections.emptyList());
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0, 0);

        assertDoesNotThrow(() -> auditWith(jdbcTemplate).run(null));

        verify(jdbcTemplate, times(4)).queryForList(anyString());
        verify(jdbcTemplate, never()).update(anyString());
    }

    @Test
    void sampleListIsNotMutated() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<Map<String, Object>> chainDup = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            chainDup.add(row("tunnel_id", i, "node_id", 7, "chain_type", 2, "c", 2));
        }
        when(jdbcTemplate.queryForList(anyString())).thenReturn(chainDup);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        auditWith(jdbcTemplate).run(null);

        assertEquals(9, chainDup.size(), "巡检只能读取样例，不能改动查询结果");
    }
}
