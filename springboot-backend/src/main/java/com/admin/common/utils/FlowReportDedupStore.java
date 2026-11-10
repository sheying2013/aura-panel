package com.admin.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流量上报幂等的持久化实现（round21）。
 *
 * <p>原实现 {@link FlowReportDedup} 是进程内 ConcurrentHashMap：面板多实例部署时各实例各记一份，
 * 同一份重试报文会被不同实例分别累加；进程重启后 Map 清空，节点重试窗口内的旧报文也会被二次计费。
 * 这里用一张唯一键表把「已见过的上报键」落到库里，跨实例、跨重启都只认一次。
 *
 * <p>表由 schema.sql 的 CREATE TABLE IF NOT EXISTS 建立（不依赖 ALTER，存量库重启即生效），
 * 键与内存版一致：节点 + 服务 + 时间桶 + d/u 增量。插入冲突即重复，捕获 DuplicateKeyException 返回 true。
 *
 * <p>数据库不可用时的降级：记 warn 并退回内存版去重——宁可少去重一次，也不能因为去重表故障把整条上报链打断。
 *
 * <p>清理：每 {@link #PURGE_INTERVAL_MS} 最多删一次超过 {@link #RETENTION_MS} 的旧键，表大小与上报速率同阶。
 */
@Slf4j
@Component
public class FlowReportDedupStore {

    /** 时间桶宽度与内存版保持一致（2 倍于节点 5 秒上报节拍）。 */
    public static final long WINDOW_MS = FlowReportDedup.DEFAULT_WINDOW_MS;

    /** 键保留时长：覆盖重试窗口即可，过期行定期删除。 */
    public static final long RETENTION_MS = 120_000L;

    /** 清理最小间隔，避免每条上报都跑一次 DELETE。 */
    public static final long PURGE_INTERVAL_MS = 60_000L;

    private static final String INSERT_SQL =
            "INSERT INTO flow_report_dedup (dedup_key, bucket, created_time) VALUES (?, ?, ?)";
    private static final String PURGE_SQL =
            "DELETE FROM flow_report_dedup WHERE created_time < ?";

    @Resource
    JdbcTemplate jdbcTemplate;

    /** 数据库异常时的降级去重，进程内有效。 */
    private final FlowReportDedup fallback = new FlowReportDedup();

    private final AtomicLong lastPurgeAt = new AtomicLong(0L);

    /**
     * 登记一条上报。
     *
     * @return true 表示该键已存在（重复，调用方跳过累加）
     */
    public boolean isDuplicate(String nodeId, String serviceName, long epochMillis, long downloadBytes, long uploadBytes) {
        long bucket = FlowReportDedup.bucketOf(epochMillis, WINDOW_MS);
        String key = FlowReportDedup.key(nodeId, serviceName, bucket, downloadBytes, uploadBytes);
        if (jdbcTemplate == null) {
            return fallback.isDuplicate(nodeId, serviceName, epochMillis, downloadBytes, uploadBytes);
        }
        try {
            jdbcTemplate.update(INSERT_SQL, key, bucket, epochMillis);
            purgeOccasionally(epochMillis);
            return false;
        } catch (DataAccessException e) {
            // SQLite 的唯一键冲突不是 DuplicateKeyException（JDBC 驱动抛 SQLITE_CONSTRAINT_PRIMARYKEY，
            // Spring 归到 UncategorizedSQLException），必须自己认；否则重复报文会被当成"写表失败"降级，
            // 进程内 fallback 首次看到该键仍然放行 —— 真机实测同一批报文被累加两次。
            if (isUniqueViolation(e)) {
                return true;
            }
            log.warn("流量上报去重表写入失败，退回进程内去重：{}", e.getMessage());
            return fallback.isDuplicate(nodeId, serviceName, epochMillis, downloadBytes, uploadBytes);
        }
    }

    /**
     * 判断异常链里是否含唯一键/主键冲突。
     * 覆盖 SQLite（errorCode 19 / UNIQUE constraint failed）、MySQL（Duplicate entry）、PostgreSQL（duplicate key）。
     */
    static boolean isUniqueViolation(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException && ((java.sql.SQLException) t).getErrorCode() == 19) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("unique constraint failed")
                    || lower.contains("sqlite_constraint")
                    || lower.contains("duplicate entry")
                    || lower.contains("duplicate key")) {
                return true;
            }
        }
        return false;
    }

    private void purgeOccasionally(long now) {
        long last = lastPurgeAt.get();
        if (now - last < PURGE_INTERVAL_MS || !lastPurgeAt.compareAndSet(last, now)) {
            return;
        }
        try {
            int removed = jdbcTemplate.update(PURGE_SQL, now - RETENTION_MS);
            if (removed > 0) {
                log.debug("流量上报去重表清理 {} 条过期键", removed);
            }
        } catch (DataAccessException e) {
            log.debug("流量上报去重表清理失败：{}", e.getMessage());
        }
    }
}
