package com.admin.ddns;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * GostUtil 与 DDNS 注册表之间的唯一接缝。
 *
 * <p>GostUtil 是静态工具，不能直接注入 Spring Bean；这里保留一个可替换的渲染函数：
 * 生产环境由 {@link DdnsAddressRegistry} 安装，单测里没人安装时就是恒等函数，
 * 老行为（域名原样下发）不受影响。
 *
 * <p>另有一个「是否正在切换」的探针，给普通转发编辑/对账在动手前先问一句：
 * 命中就直接拒绝或跳过，而不是先删了服务再等渲染抛错。
 *
 * <p>还有一组「摘要可信度」探针：域名映射没装载好、或者库里某个域名还没有主地址时，
 * 节点上报的摘要与按注册表算出的期望值天然不相等 —— 用这种摘要判漂移等于误删服务。
 * 对账入口据此在动手前先问一句，得到否定回答就按「这轮没摘要」处理（只补不删）。
 * 没有安装探针时默认「可信」，非 DDNS 环境的行为与以前完全一致。
 */
public final class DdnsTargetRenderer {

    private static volatile Function<String, String> renderer = Function.identity();
    private static volatile Function<String, Boolean> transitionProbe = host -> Boolean.FALSE;
    private static volatile Function<String, String> digestTrustProbe = host -> null;
    private static volatile Supplier<Boolean> digestDegradedProbe = () -> Boolean.FALSE;
    private static volatile Supplier<Boolean> digestReadyProbe = () -> Boolean.TRUE;
    private static volatile Function<String, DdnsDomainGate.Lease> dispatchProbe =
            remoteAddr -> DdnsDomainGate.Lease.NONE;

    private DdnsTargetRenderer() {
    }

    public static String render(String target) {
        return renderer.apply(target);
    }

    /** target（host 或 host:port）是否落在正在切换的域名上。 */
    public static boolean isTransitioning(String target) {
        if (target == null || target.isEmpty()) {
            return false;
        }
        String host = DdnsAddresses.hostOf(target);
        return !host.isEmpty() && Boolean.TRUE.equals(transitionProbe.apply(host));
    }

    /** remoteAddr 里任意一项是否落在正在切换的域名上（逗号分隔，含共享同一转发的情况）。 */
    public static boolean anyTransitioning(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            return false;
        }
        for (String addr : remoteAddr.split(",")) {
            String trimmed = addr == null ? "" : addr.trim();
            if (!trimmed.isEmpty() && isTransitioning(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * target（host 或 host:port）能不能用来判摘要漂移。
     *
     * @return null 表示可信；非 null 是不可信的原因
     */
    public static String digestTrustIssue(String target) {
        if (target == null || target.isEmpty()) {
            return null;
        }
        String host = DdnsAddresses.hostOf(target);
        if (host.isEmpty()) {
            return null;
        }
        return digestTrustProbe.apply(host);
    }

    /** remoteAddr（逗号分隔）里任意一项不可信就返回那条原因，全部可信返回 null。 */
    public static String anyDigestTrustIssue(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            return null;
        }
        for (String addr : remoteAddr.split(",")) {
            String trimmed = addr == null ? "" : addr.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String issue = digestTrustIssue(trimmed);
            if (issue != null) {
                return issue;
            }
        }
        return null;
    }

    /** 是否存在「按注册表算不出可信期望值」的域名。 */
    public static boolean digestTrustDegraded() {
        return Boolean.TRUE.equals(digestDegradedProbe.get());
    }

    /** 域名映射是否已经装载完成（与「是否降级」分开：装载完成但个别域名没主地址，只需按节点收窄）。 */
    public static boolean digestTrustReady() {
        return Boolean.TRUE.equals(digestReadyProbe.get());
    }

    /**
     * 普通下发的共享凭证：{@code remoteAddr} 覆盖到的域名统一加读锁，DDNS 切换窗口拿不到写锁。
     *
     * <p>没有安装探针时返回空凭证，非 DDNS 环境的行为完全不变。
     */
    public static DdnsDomainGate.Lease beginDispatch(String remoteAddr) {
        return dispatchProbe.apply(remoteAddr);
    }

    /** 一次下发涉及多个目标地址（旧 remoteAddr + 新 remoteAddr）时合用一份凭证。 */
    public static DdnsDomainGate.Lease beginDispatch(String first, String second) {
        if (second == null || second.isEmpty()) {
            return beginDispatch(first);
        }
        String merged = (first == null || first.isEmpty()) ? second : first + "," + second;
        return beginDispatch(merged);
    }

    public static void installDispatch(Function<String, DdnsDomainGate.Lease> probe) {
        dispatchProbe = probe == null ? remoteAddr -> DdnsDomainGate.Lease.NONE : probe;
    }

    public static void install(Function<String, String> newRenderer) {
        renderer = newRenderer == null ? Function.identity() : newRenderer;
    }

    public static void installTransitionProbe(Function<String, Boolean> probe) {
        transitionProbe = probe == null ? host -> Boolean.FALSE : probe;
    }

    public static void installDigestTrust(Function<String, String> issueProbe,
                                          Supplier<Boolean> degradedProbe,
                                          Supplier<Boolean> readyProbe) {
        digestTrustProbe = issueProbe == null ? host -> null : issueProbe;
        digestDegradedProbe = degradedProbe == null ? () -> Boolean.FALSE : degradedProbe;
        digestReadyProbe = readyProbe == null ? () -> Boolean.TRUE : readyProbe;
    }

    public static void reset() {
        renderer = Function.identity();
        transitionProbe = host -> Boolean.FALSE;
        digestTrustProbe = host -> null;
        digestDegradedProbe = () -> Boolean.FALSE;
        digestReadyProbe = () -> Boolean.TRUE;
        dispatchProbe = remoteAddr -> DdnsDomainGate.Lease.NONE;
    }
}
