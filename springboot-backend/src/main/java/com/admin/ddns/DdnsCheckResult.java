package com.admin.ddns;

import com.admin.ddns.entity.DdnsRecord;

/** 一次检查的结果；message 同时用于手动检查的错误响应和调度日志。 */
public record DdnsCheckResult(
        boolean success,
        boolean changed,
        int affectedForwards,
        String message,
        DdnsRecord record
) {

    public static DdnsCheckResult ok(boolean changed, int affectedForwards, String message, DdnsRecord record) {
        return new DdnsCheckResult(true, changed, affectedForwards, message, record);
    }

    public static DdnsCheckResult fail(String message, DdnsRecord record) {
        return new DdnsCheckResult(false, false, 0, message, record);
    }
}
