package com.admin.ddns;

import java.net.IDN;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DDNS 的纯字符串规则：域名规范化、目标 host 提取、IP 排序与主地址选择。
 *
 * <p>这里不做任何解析，也不碰网络，所有行为都能用单测锁死。解析归
 * {@link DdnsResolver}，下发归 {@link DdnsForwardApplier}。
 */
public final class DdnsAddresses {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private DdnsAddresses() {
    }

    /**
     * 规范化用户输入的域名。
     *
     * <p>拒绝 IP、协议、端口、路径和空白：这些写进来都不是「一个可解析的域名」，
     * 放过去只会在 GOST 侧变成一个永远连不上的 target。
     */
    public static String normalizeDomain(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("域名不能为空");
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("域名不能为空");
        }
        if (value.startsWith("[") || value.endsWith("]")
                || value.contains("://") || value.contains("/") || value.contains("@")
                || value.contains("?") || value.contains("#") || value.contains(":")
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("域名格式不合法：" + raw);
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (isIpLiteral(value)) {
            throw new IllegalArgumentException("这里要填域名，不是 IP：" + raw);
        }
        String ascii = toAsciiOrThrow(value, raw).toLowerCase(Locale.ROOT);
        if (ascii.isEmpty() || ascii.length() > 253) {
            throw new IllegalArgumentException("域名长度必须在 1 到 253 之间");
        }
        for (String label : ascii.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("域名标签不合法：" + label);
            }
        }
        return ascii;
    }

    /**
     * 转成 ASCII，但只接受「转换前后还是同一个名字」的结果。
     *
     * <p>{@link IDN#toASCII} 走的是 IDNA2003/nameprep，浏览器走 UTS46，两者在少数码点上
     * 给出的是**两个不同的 DNS 名称**：{@code faß.de} 在这里变成 {@code fass.de}、
     * {@code ẞ.de} 变成 {@code xn--kkg.de}、{@code faς.gr} 变成 {@code xn--fa-wbc.gr}、
     * 夹着零宽连接符（U+200D）的域名变成 {@code ab.de}。照原样返回，等于把用户写的名字悄悄
     * 换成另一个域名去解析、去下发，所以这里把输入和结果各回解码一遍，只有两者在
     * 大小写与 NFKC 折叠后完全相同才放行；对不上就报错，让用户自己决定换不换名字。
     *
     * <p>也刻意不再传 {@code ALLOW_UNASSIGNED}：{@code U+1E9E} 在 nameprep 的 Unicode 3.2
     * 表里没有分配，放过去会被编成 {@code xn--kkg.de}，而浏览器把它折叠成 ß 得到
     * {@code xn--zca.de} —— 又是一个换名字的口子。
     */
    private static String toAsciiOrThrow(String value, String raw) {
        String ascii;
        String expected;
        String actual;
        try {
            expected = IDN.toUnicode(value);
            ascii = IDN.toASCII(value);
            actual = IDN.toUnicode(ascii);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("域名格式不合法：" + raw);
        }
        if (!fold(expected).equals(fold(actual))) {
            throw new IllegalArgumentException(
                    "该域名在 IDNA2003 下会被写成另一个域名（" + ascii + "），已拒绝：" + raw);
        }
        return ascii;
    }

    /** 只抹平大小写和 NFKC（全角、兼容字符），不抹平 IDNA2003 特有的字符删除与替换。 */
    private static String fold(String value) {
        String lowered = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lowered, Normalizer.Form.NFKC);
    }

    /** 从 target 里取出 host；支持 domain:port、IPv4:port 和 [IPv6]:port。 */
    public static String hostOf(String target) {
        if (target == null) {
            return "";
        }
        String value = target.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 1 ? value.substring(1, end) : value;
        }
        int first = value.indexOf(':');
        int last = value.lastIndexOf(':');
        if (first >= 0 && first == last) {
            return value.substring(0, first);
        }
        // 没有冒号，或多个冒号（未加方括号的 IPv6）都按整个字符串当 host。
        return value;
    }

    /** 逗号分隔的 target 列表里是否有 host 命中这个域名。 */
    public static boolean targetUsesDomain(String remoteAddr, String domain) {
        if (remoteAddr == null || domain == null || domain.isEmpty()) {
            return false;
        }
        for (String target : remoteAddr.split(",")) {
            String host = hostOf(target);
            if (host == null || host.isEmpty()) {
                continue;
            }
            try {
                if (normalizeDomain(domain).equals(normalizeDomain(host))) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // IP 字面量和畸形目标都不是与该 DDNS 域名匹配的目标。
            }
        }
        return false;
    }

    /** 把 IP 渲染成 target 里的 host 形式：IPv6 必须加方括号，否则和端口拼起来会有歧义。 */
    public static String formatHost(String ip) {
        if (ip == null || ip.isEmpty()) {
            return ip;
        }
        return ip.indexOf(':') >= 0 && !ip.startsWith("[") ? "[" + ip + "]" : ip;
    }

    public static boolean isIpLiteral(String value) {
        if (value == null) {
            return false;
        }
        String candidate = value.trim();
        if (IPV4.matcher(candidate).matches()) {
            for (String part : candidate.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        if (!candidate.contains(":") || !IPV6_CHARS.matcher(candidate).matches()) {
            return false;
        }
        try {
            // 已限定包含冒号且仅为十六进制字符、冒号和点号；只解析 IP 字面量，不触发域名查询。
            // JDK 会将 IPv4-mapped IPv6 规范化为 Inet4Address，解析成功仍是合法字面量。
            return java.net.InetAddress.getByName(candidate) != null;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /**
     * 清洗 resolver 返回的地址：只留合法 IP、去重、IPv4 排前面。
     *
     * <p>排序是为了让持久化的 resolvedIps 和摘要比较稳定，避免每次查询顺序不同
     * 就误判成「地址集合变了」。
     */
    public static List<String> normalizeIps(Collection<String> addresses) {
        Set<String> unique = new LinkedHashSet<>();
        if (addresses != null) {
            for (String address : addresses) {
                if (address == null) {
                    continue;
                }
                String value = address.trim();
                int zone = value.indexOf('%');
                if (zone >= 0) {
                    value = value.substring(0, zone);
                }
                if (isIpLiteral(value)) {
                    unique.add(value.toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> result = new ArrayList<>(unique);
        result.sort((left, right) -> {
            boolean leftV4 = IPV4.matcher(left).matches();
            boolean rightV4 = IPV4.matcher(right).matches();
            if (leftV4 != rightV4) {
                return leftV4 ? -1 : 1;
            }
            return left.compareTo(right);
        });
        return result;
    }

    /**
     * 选择下发给 GOST 的主地址：老主地址仍在结果里就保持不动，否则取排序后的第一个。
     *
     * <p>保持不动很重要：多 A 记录轮换时，地址集合可能变化但主地址没变，这时重下发
     * 只会平白断一次监听。只有主地址真的漂移才值得付出热重载的代价。
     */
    public static String choosePrimary(String current, List<String> normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        if (current != null && !current.isEmpty()
                && normalized.stream().anyMatch(ip -> ip.equalsIgnoreCase(current))) {
            return current.toLowerCase(Locale.ROOT);
        }
        return normalized.get(0);
    }

    public static String join(Collection<String> values) {
        return values == null ? "" : String.join(",", values);
    }

    public static Set<String> splitCsv(String csv) {
        Set<String> result = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        for (String part : csv.split(",")) {
            if (part != null && !part.trim().isEmpty()) {
                result.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }
}
