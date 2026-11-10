package com.admin.common.utils;

import java.util.Map;

/**
 * Map 取参统一入口。
 *
 * <p>控制器里原先普遍写 {@code Long.valueOf(params.get("id").toString())}：
 * body 为空、键缺失或值为 null 时直接 NPE，异常文本会被 GlobalExceptionHandler
 * 原样回给前端（Cannot invoke "Object.toString()" because ...），既不可读也不可判断。
 * 这里统一把「没传 / 空串 / 非数字」收敛成 null，由调用方给出可读文案。</p>
 */
public final class MapParamUtils {

    private MapParamUtils() {
    }

    /** 取 Long：params 为 null、键缺失、空白、非数字一律返回 null。 */
    public static Long toLong(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object raw = params.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取 Integer：规则同 {@link #toLong}，超出 int 范围也视为非法。 */
    public static Integer toInteger(Map<String, Object> params, String key) {
        Long value = toLong(params, key);
        if (value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            return null;
        }
        return value.intValue();
    }

    /** 与 toLong/toInteger 配套：区分「没传」和「传了但不是合法数字」，给出可读文案。 */
    public static String error(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null || raw.toString().trim().isEmpty()) {
            return key + " 不能为空";
        }
        return key + " 格式不正确";
    }
}
