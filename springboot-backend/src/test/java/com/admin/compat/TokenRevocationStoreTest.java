package com.admin.compat;

import com.admin.common.utils.TokenRevocationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round63：登出吊销表的读写契约。
 *
 * <p>它替代了 round51 那种「登出自增用户级 token_version」的做法。这里锁住：① 吊销按 sid 落行并带上
 * token 自己的过期时刻；② 过期行不算吊销；③ 库里已经过期/查不到都按未吊销处理；④ 写入失败必须返回
 * false（登出接口据此报错，不能谎称已登出）；⑤ 查询失败或空结果必须中止请求；⑥ 清理最多按配置间隔
 * 跑一次。
 */
class TokenRevocationStoreTest {

    private TokenRevocationStore store;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        store = new TokenRevocationStore();
        jdbcTemplate = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(store, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void revokeWritesSessionIdAndTokenExpiry() {
        when(jdbcTemplate.update(anyString(), any(Object.class), any(Object.class))).thenReturn(1);

        assertTrue(store.revoke("abc123", 1_800_000_000_000L));

        ArgumentCaptor<Object> sessionId = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> expiresAt = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), sessionId.capture(), expiresAt.capture());
        assertEquals("abc123", String.valueOf(sessionId.getValue()));
        assertEquals(1_800_000_000_000L, ((Number) expiresAt.getValue()).longValue(),
                "吊销行必须带 token 自己的 exp，过期后由清理逻辑删掉");
    }

    @Test
    void revokeNeverWritesAlreadyExpiredRow() {
        // 老 token 缺 exp 时调用方传 0：不能写一条「一写就过期」的行，否则等于没吊销
        when(jdbcTemplate.update(anyString(), any(Object.class), any(Object.class))).thenReturn(1);

        assertTrue(store.revoke("no-exp", 0L));

        ArgumentCaptor<Object> expiresAt = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), any(Object.class), expiresAt.capture());
        assertTrue(((Number) expiresAt.getValue()).longValue() > System.currentTimeMillis() - 1000L,
                "过期时刻必须被兜底到当前时间之后");
    }

    @Test
    void blankSessionIdIsNeverWritten() {
        assertFalse(store.revoke(null, 1L));
        assertFalse(store.revoke("", 1L));
        assertThrows(TokenRevocationStore.RevocationStoreUnavailableException.class, () -> store.isRevoked(null));
        assertThrows(TokenRevocationStore.RevocationStoreUnavailableException.class, () -> store.isRevoked(""));

        verify(jdbcTemplate, never()).update(anyString(), any(Object.class), any(Object.class));
        verify(jdbcTemplate, never()).queryForObject(anyString(), any(Class.class), any(Object.class), any(Object.class));
    }

    @Test
    void isRevokedReflectsStoredRow() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object.class), any(Object.class)))
                .thenReturn(1);
        assertTrue(store.isRevoked("abc123"), "表里有未过期行即为已吊销");

        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object.class), any(Object.class)))
                .thenReturn(0);
        assertFalse(store.isRevoked("other-session"), "没吊销过的会话必须放行");
    }

    @Test
    void writeFailureMustBeReportedToCaller() {
        when(jdbcTemplate.update(anyString(), any(Object.class), any(Object.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertFalse(store.revoke("abc123", 1_800_000_000_000L),
                "写不进吊销表时返回 false，登出接口据此报错而不是假装成功");
    }

    @Test
    void queryFailureAndNullFailClosed() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object.class), any(Object.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(null)
                .thenReturn(0);
        assertThrows(TokenRevocationStore.RevocationStoreUnavailableException.class, () -> store.isRevoked("abc123"));
        assertThrows(TokenRevocationStore.RevocationStoreUnavailableException.class, () -> store.isRevoked("abc123"));
        assertFalse(store.isRevoked("abc123"), "存储恢复且无记录时才放行");
    }

    @Test
    void purgeRunsAtMostOncePerInterval() {
        String insertSql = "INSERT OR IGNORE INTO revoked_token (token_id, expires_at) VALUES (?, ?)";
        String purgeSql = "DELETE FROM revoked_token WHERE expires_at <= ?";
        when(jdbcTemplate.update(eq(insertSql), any(Object.class), any(Object.class))).thenReturn(1);
        when(jdbcTemplate.update(eq(purgeSql), any(Object.class))).thenReturn(0);

        assertTrue(store.revoke("s1", 1_800_000_000_000L));
        assertTrue(store.revoke("s2", 1_800_000_000_000L));

        verify(jdbcTemplate, times(2)).update(eq(insertSql), any(Object.class), any(Object.class));
        verify(jdbcTemplate, times(1)).update(eq(purgeSql), any(Object.class));
    }
}
