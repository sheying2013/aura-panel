package com.admin.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 登出吊销表（round63）：按 token 的会话标识 sid 吊销「这一次登录」。
 *
 * <p>round51 的登出是自增 user.token_version，那是一个用户级开关：同一个用户的所有 token
 * 共用同一个版本号。于是出现跨标签竞态——标签 A 的旧会话迟到的登出请求（鉴权发生在新的
 * 登录之前）把版本号推到 N+1，标签 B 刚拿到、版本号为 N 的新 token 一起被判失效。
 *
 * <p>这里改成「只吊销发起登出的那一个 sid」：别人的 token、用户后来重新登录拿到的 token
 * 都不受这条记录影响。用户级吊销（改密、停用、删号）仍然由 user.token_version 负责，
 * 两条通道互不干扰。
 *
 * <p>落库而不是放内存：进程重启后已登出的 token 不能复活，多实例部署也要看到同一份吊销集合。
 * 表由 schema.sql 的 CREATE TABLE IF NOT EXISTS 建立（不依赖 ALTER，存量库重启即生效）；
 * 行上带 token 自己的 exp，过期后由 {@link #purgeOccasionally(long)} 定期删除，表大小与登出速率同阶。
 *
 * <p>查询异常或空结果视为存储不可用，受保护请求必须中止并返回 503，不能当作未吊销。
 */
@Slf4j
@Component
public class TokenRevocationStore {

    /** 同一 sid 重复登出（并发/重放）只留一行。SQLite 语法，本服务固定 SQLite。 */
    static final String REVOKE_SQL =
            "INSERT OR IGNORE INTO revoked_token (token_id, expires_at) VALUES (?, ?)";

    /** 过期行即使还在表里也不算吊销：token 自己已过不了 exp 校验。 */
    static final String IS_REVOKED_SQL =
            "SELECT COUNT(1) FROM revoked_token WHERE token_id = ? AND expires_at > ?";

    static final String PURGE_SQL =
            "DELETE FROM revoked_token WHERE expires_at <= ?";

    /** 清理最小间隔，避免每次登出都跑一次 DELETE。 */
    static final long PURGE_INTERVAL_MS = 60_000L;

    @Resource
    JdbcTemplate jdbcTemplate;

    private final AtomicLong lastPurgeAt = new AtomicLong(0L);

    /**
     * 吊销一个会话。
     *
     * @param sessionId       token 里的 sid
     * @param expiresAtMillis token 的过期时刻；缺失/非法传 0，这里按「至少保留到现在」兜底
     * @return true 表示这条吊销已经落库；false 表示 sid 为空或写库失败（调用方必须报错，不能谎称已登出）
     */
    public boolean revoke(String sessionId, long expiresAtMillis) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expiresAt = Math.max(expiresAtMillis, now);
        try {
            jdbcTemplate.update(REVOKE_SQL, sessionId, expiresAt);
            purgeOccasionally(now);
            return true;
        } catch (Exception e) {
            log.warn("登出吊销写库失败");
            return false;
        }
    }

    /**
     * 该会话是否已被登出吊销。
     *
     * @param sessionId 已验签票据对应的吊销键；空键视为存储不可用
     */
    public boolean isRevoked(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) throw new RevocationStoreUnavailableException();
        try {
            Integer count = jdbcTemplate.queryForObject(IS_REVOKED_SQL, Integer.class, sessionId, System.currentTimeMillis());
            if (count == null || count < 0) throw new RevocationStoreUnavailableException();
            return count > 0;
        } catch (RevocationStoreUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("登出吊销查询失败");
            throw new RevocationStoreUnavailableException();
        }
    }

    /** 存储不可用与已吊销是不同终态，调用方应返回 503 而非 401。 */
    public static final class RevocationStoreUnavailableException extends RuntimeException {
        public RevocationStoreUnavailableException() {
            super("会话状态暂时不可用，请稍后重试");
        }
    }

    private void purgeOccasionally(long now) {
        long last = lastPurgeAt.get();
        if (now - last < PURGE_INTERVAL_MS || !lastPurgeAt.compareAndSet(last, now)) {
            return;
        }
        try {
            int removed = jdbcTemplate.update(PURGE_SQL, now);
            if (removed > 0) {
                log.debug("登出吊销表清理 {} 条过期记录", removed);
            }
        } catch (Exception e) {
            log.debug("登出吊销表清理失败：{}", e.getMessage());
        }
    }
}
