package com.admin.common.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 流量计费口径的唯一实现处（扣配额 / statistics_flow / 前端展示都读这里算出的计费字节）。
 * <p>
 * 口径来源（不是猜的）：
 * <ul>
 *   <li>倍率只取 {@code Tunnel.trafficRatio}（BigDecimal，TunnelDto 限制 0.0 &lt; r &lt;= 100.0）；</li>
 *   <li>方向只取 {@code Tunnel.flow}：1 = 单向计算（仅上传），2 = 双向计算（上传+下载）。
 *       依据前端表单文案：vite-frontend/src/pages/tunnel.tsx:757-758。</li>
 * </ul>
 * 计费字节 = 原始字节 × 倍率，四舍五入到整字节；方向为 1 时下载方向的计费字节为 0。
 */
public final class FlowBilling {

    /** Tunnel.flow：单向计算（仅上传）。 */
    public static final int DIRECTION_UPLOAD_ONLY = 1;

    /** Tunnel.flow：双向计算（上传+下载）。 */
    public static final int DIRECTION_BOTH = 2;

    /** 倍率缺省值：1 表示不放大也不缩小。 */
    public static final BigDecimal DEFAULT_RATIO = BigDecimal.ONE;

    /** 倍率上限，与 TunnelDto.trafficRatio 的 @DecimalMax("100.0") 对齐。 */
    public static final BigDecimal MAX_RATIO = new BigDecimal("100.0");

    private static final BigDecimal MAX_LONG = BigDecimal.valueOf(Long.MAX_VALUE);

    private FlowBilling() {
    }

    /** 方向是否合法（只认 1/2）。 */
    public static boolean isLegalDirection(int flow) {
        return flow == DIRECTION_UPLOAD_ONLY || flow == DIRECTION_BOTH;
    }

    /**
     * 归一化方向：非法值（0、负数、历史脏数据、更新的枚举值）一律按「双向全额」处理。
     * <p>
     * 依据：方向是枚举，不是倍数。把 0 当数字参与运算会让该隧道的全部流量变成 0，配额与计费永久不触发；
     * 回落成「双向全额」只会多计、不会漏计，是唯一安全的兜底方向。
     */
    public static int normalizeDirection(int flow) {
        return isLegalDirection(flow) ? flow : DIRECTION_BOTH;
    }

    /** 倍率是否合法（非 null、大于 0、不超过上限）。 */
    public static boolean isLegalRatio(BigDecimal ratio) {
        return ratio != null && ratio.signum() > 0 && ratio.compareTo(MAX_RATIO) <= 0;
    }

    /**
     * 归一化倍率：null / 0 / 负数一律按 1（不放大也不吞流量），超过上限按上限封顶。
     * <p>
     * 依据：倍率 0 = 该隧道所有流量乘 0 = 永久免计费，等价于把配额关掉；语义上「不放大」才是 1。
     */
    public static BigDecimal normalizeRatio(BigDecimal ratio) {
        if (ratio == null || ratio.signum() <= 0) {
            return DEFAULT_RATIO;
        }
        if (ratio.compareTo(MAX_RATIO) > 0) {
            return MAX_RATIO;
        }
        return ratio;
    }

    /**
     * 原始字节 × 倍率，四舍五入（HALF_UP）到整字节。
     * <p>
     * 取整：按单次上报独立取整，最大误差 0.5 字节/方向；不引入跨请求的小数余额，否则幂等去重不再是纯函数。
     * 溢出：乘积超过 long 上限时饱和到 Long.MAX_VALUE（ratio ≤ 100，正常上报不可能触发；触发说明上报值
     * 已损坏，饱和比静默回绕成负数安全）。负增量一律按 0：节点重传/计数器回退时累加负数会把配额倒着退回去。
     */
    public static long scale(long rawBytes, BigDecimal ratio) {
        if (rawBytes <= 0L) {
            return 0L;
        }
        BigDecimal scaled = BigDecimal.valueOf(rawBytes)
                .multiply(normalizeRatio(ratio))
                .setScale(0, RoundingMode.HALF_UP);
        if (scaled.compareTo(MAX_LONG) > 0) {
            return Long.MAX_VALUE;
        }
        return scaled.longValue();
    }

    /**
     * 按方向把一次上报换算成计费字节。
     * <p>
     * 字段方向以赋值处为准，勿按字段名字面猜：节点上报的 {@code d} 是<b>上传</b>、
     * {@code u} 是<b>下载</b>。依据：go-gost/x/service/service.go:495 是
     * {@code U: outputBytes, D: inputBytes}，而 inputBytes 是客户端写入服务端的字节（上传）。
     * 真机实测（圣何塞 30200，单次 200000 字节上行）节点上报为 {@code {"d":200163,"u":211}}。
     * x/service/traffic_reporter.go:25-26 的「u 上行 / d 下行」注释与实际赋值相反，以赋值处为准。
     *
     * @return {上传(d)计费字节, 下载(u)计费字节}；方向为 1 时下载计费字节为 0
     */
    public static long[] billed(long rawD, long rawU, int flow, BigDecimal ratio) {
        int direction = normalizeDirection(flow);
        long billedD = scale(rawD, ratio);
        long billedU = direction == DIRECTION_UPLOAD_ONLY ? 0L : scale(rawU, ratio);
        return new long[]{billedD, billedU};
    }
}
