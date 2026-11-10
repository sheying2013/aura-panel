package com.admin.compat;

import com.admin.config.UserTunnelIndexMigration;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * user_tunnel(user_id, tunnel_id) 唯一索引迁移（UserTunnelIndexMigration）的本地夹具锁定。
 *
 * <p>要守住的行为：① 老库已有重复组时只告警、绝不建索引、更不删数据（人工决定保留哪一行）；
 * ② 没有重复时建的是 (user_id, tunnel_id) 上的唯一索引，且带 IF NOT EXISTS 保证重启幂等；
 * ③ 重复检查本身失败只告警，不建索引也不阻塞启动；④ 建索引失败同样只告警，应用照常起来。
 */
class UserTunnelIndexMigrationTest {

    private JdbcTemplate jdbcTemplate;
    private UserTunnelIndexMigration migration;
    private ApplicationArguments args;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        args = mock(ApplicationArguments.class);
        migration = new UserTunnelIndexMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void duplicateGroupsWarnAndSkipIndex() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(duplicates(2));

        migration.run(args);

        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).update(anyString());
    }

    @Test
    void cleanTableCreatesIdempotentUniqueIndex() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());

        migration.run(args);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).execute(sqlCaptor.capture());
        String sql = sqlCaptor.getValue().toUpperCase();
        assertTrue(sql.contains("CREATE UNIQUE INDEX"), "必须是唯一索引，实际：" + sqlCaptor.getValue());
        assertTrue(sql.contains("IF NOT EXISTS"), "重启必须幂等，实际：" + sqlCaptor.getValue());
        assertTrue(sql.contains("ON USER_TUNNEL(USER_ID, TUNNEL_ID)"),
                "索引列必须是 (user_id, tunnel_id)，实际：" + sqlCaptor.getValue());
    }

    @Test
    void duplicateProbeUsesGroupByHavingLimit() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());

        migration.run(args);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).queryForList(sqlCaptor.capture());
        String sql = sqlCaptor.getValue().toUpperCase().replaceAll("\\s+", " ");
        assertTrue(sql.contains("GROUP BY USER_ID, TUNNEL_ID"), "必须按 (user_id, tunnel_id) 分组，实际：" + sqlCaptor.getValue());
        assertTrue(sql.contains("HAVING COUNT(*) > 1"), "只看重复组，实际：" + sqlCaptor.getValue());
        assertTrue(sql.contains("LIMIT 5"), "只取前 5 组收敛日志，实际：" + sqlCaptor.getValue());
    }

    @Test
    void probeFailureDoesNotBlockStartup() {
        when(jdbcTemplate.queryForList(anyString()))
                .thenThrow(new DataAccessResourceFailureException("database is locked"));

        assertDoesNotThrow(() -> migration.run(args), "重复检查失败必须只告警");
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void indexCreationFailureDoesNotBlockStartup() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());
        doThrow(new DataAccessResourceFailureException("UNIQUE constraint failed: user_tunnel.user_id, user_tunnel.tunnel_id"))
                .when(jdbcTemplate).execute(anyString());

        assertDoesNotThrow(() -> migration.run(args), "建索引失败必须只告警，应用照常启动");
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    private List<Map<String, Object>> duplicates(int groups) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= groups; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_id", i);
            row.put("tunnel_id", 10 + i);
            row.put("c", 2);
            rows.add(row);
        }
        return rows;
    }
}
