package com.admin.compat;

import com.admin.common.utils.FlowReportDedupStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * round21：流量上报幂等从进程内 Map 迁到库表后的行为测试。
 *
 * <p>重点验证三件事：① 首次插入放行、主键冲突判重；② 键的口径与内存版一致（节点|服务|桶|d|u）；
 * ③ 去重表本身故障时降级到内存版，不阻断上报链路。
 */
public class FlowReportDedupStoreTest {

    private FlowReportDedupStore store;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        store = new FlowReportDedupStore();
        jdbcTemplate = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(store, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void firstInsertIsNotDuplicateAndKeyMatchesMemoryFormat() {
        when(jdbcTemplate.update(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(1);

        assertFalse(store.isDuplicate("7", "3_1_0_tcp", 1_700_000_000_000L, 100L, 200L));

        ArgumentCaptor<Object> key = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> bucket = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> created = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), key.capture(), bucket.capture(), created.capture());
        assertEquals("7|3_1_0_tcp|170000000|100|200", String.valueOf(key.getValue()));
        assertEquals(170000000L, ((Number) bucket.getValue()).longValue());
        assertEquals(1_700_000_000_000L, ((Number) created.getValue()).longValue());
    }

    @Test
    void duplicateKeyMeansDuplicateReport() {
        when(jdbcTemplate.update(anyString(), any(Object.class), any(Object.class), any(Object.class)))
                .thenThrow(new DuplicateKeyException("UNIQUE constraint failed: flow_report_dedup.dedup_key"));

        assertTrue(store.isDuplicate("1", "9_1_0_udp", 1_700_000_000_000L, 5L, 6L));
    }

    @Test
    void sqlitePrimaryKeyViolationIsTreatedAsDuplicate() {
        // 真机实测：sqlite-jdbc 抛 UncategorizedSQLException(SQLITE_CONSTRAINT_PRIMARYKEY, errorCode 19)，
        // 不是 DuplicateKeyException；若不识别，第二次上报会被当成"写表失败"降级并再次计费。
        java.sql.SQLException sqlite = new java.sql.SQLException(
                "[SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed (UNIQUE constraint failed: flow_report_dedup.dedup_key)",
                null, 19);
        when(jdbcTemplate.update(anyString(), any(Object.class), any(Object.class), any(Object.class)))
                .thenThrow(new org.springframework.jdbc.UncategorizedSQLException(
                        "PreparedStatementCallback; uncategorized SQLException", "INSERT INTO flow_report_dedup", sqlite));

        assertTrue(store.isDuplicate("1", "24_4_3_tcp", 1_700_000_000_000L, 1000L, 1000L));
    }

    @Test
    void databaseFailureFallsBackToInMemoryDedup() {
        when(jdbcTemplate.update(anyString(), any(Object.class), any(Object.class), any(Object.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertFalse(store.isDuplicate("2", "4_1_0_tcp", 1_700_000_000_000L, 10L, 20L));
        assertTrue(store.isDuplicate("2", "4_1_0_tcp", 1_700_000_000_000L, 10L, 20L));
    }

    @Test
    void differentContentInSameBucketIsNotSwallowed() {
        when(jdbcTemplate.update(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(1);

        assertFalse(store.isDuplicate("3", "5_1_0_tcp", 1_700_000_000_001L, 100L, 200L));
        assertFalse(store.isDuplicate("3", "5_1_0_tcp", 1_700_000_000_002L, 101L, 200L));
        verify(jdbcTemplate, times(2)).update(anyString(), any(Object.class), any(Object.class), any(Object.class));
    }
}
