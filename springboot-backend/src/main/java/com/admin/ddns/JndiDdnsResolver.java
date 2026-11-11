package com.admin.ddns;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * 用 JDK 自带的 JNDI DNS 客户端做解析。
 *
 * <p>刻意不用 {@link java.net.InetAddress#getAllByName(String)}：JVM 会按
 * {@code networkaddress.cache.ttl} 缓存结果，而 DDNS 的全部意义就是尽快看到 IP 变化。
 * JNDI 每次查询都走系统 DNS，不受 JVM 地址缓存影响；查询超时和重试次数在这里显式限制，
 * 避免一次 DNS 故障把调度线程拖死。
 */
@Component
public class JndiDdnsResolver implements DdnsResolver {

    private final String providerUrl;

    public JndiDdnsResolver(@Value("${ddns.dns-provider-url:}") String providerUrl) {
        this.providerUrl = providerUrl;
    }

    @Override
    public List<String> resolve(String domain) throws Exception {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        if (providerUrl != null && !providerUrl.isBlank()) {
            env.put(Context.PROVIDER_URL, providerUrl.trim());
        }

        List<String> addresses = new ArrayList<>();
        DirContext context = new InitialDirContext(env);
        try {
            Attributes attributes = context.getAttributes(domain, new String[]{"A", "AAAA"});
            collect(attributes.get("A"), addresses);
            collect(attributes.get("AAAA"), addresses);
        } finally {
            context.close();
        }
        if (addresses.isEmpty()) {
            throw new javax.naming.NameNotFoundException("DNS 未返回 A/AAAA 地址：" + domain);
        }
        return addresses;
    }

    private static void collect(Attribute attribute, List<String> addresses) throws Exception {
        if (attribute == null) {
            return;
        }
        NamingEnumeration<?> values = attribute.getAll();
        try {
            while (values.hasMore()) {
                Object value = values.next();
                if (value != null) {
                    addresses.add(value.toString());
                }
            }
        } finally {
            values.close();
        }
    }
}
