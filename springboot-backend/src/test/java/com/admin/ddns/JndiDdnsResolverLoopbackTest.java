package com.admin.ddns;

import org.junit.jupiter.api.Test;

import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JndiDdnsResolver} 的纯环路行为测试：只和 127.0.0.1 上一个临时 UDP 假 DNS 服务器通信。
 *
 * <p>锁死的实际行为：A / AAAA / 双栈、连续两次解析看到 IP 变化（不走 JVM 地址缓存）、
 * NOERROR + 0 答案、NXDOMAIN、服务器不回应时的超时与耗时。
 *
 * <p>“无系统解析”的证据是间接但可判定的：查询名一律落在 RFC 2606 保留的 {@code .invalid}
 * 下，系统解析器不可能给出答案；断言拿到的地址完全等于假服务器返回的 TEST-NET 地址，
 * 并且断言假服务器收到的每一条查询都只针对本次请求的名字。
 *
 * <p>边界：这里只验证 resolver 层（providerUrl 指向本地假服务器），不涉及 GOST 下发，
 * 也不是 GOST 端到端测试。生产默认配置（providerUrl 为空 → 平台 DNS）不在此文件覆盖范围内。
 */
class JndiDdnsResolverLoopbackTest {

    @Test
    void resolvesThroughTheConfiguredLoopbackServerOnly() throws Exception {
        try (LoopbackDnsServer server = new LoopbackDnsServer()) {
            String domain = uniqueDomain("a");
            server.answerA("198.51.100.10");
            JndiDdnsResolver resolver = new JndiDdnsResolver(providerUrl(server));

            List<String> addresses = resolver.resolve(domain);

            assertEquals(List.of("198.51.100.10"), addresses, "必须原样返回假服务器给的 A 记录");
            assertTrue(server.boundToIpv4Loopback(), "假服务器必须只绑定 127.0.0.1");
            assertEquals(1, server.queries().size(),
                    "当前 JDK 用一次 ANY 查询同时取 A/AAAA；多出来的查询就意味着额外的 DNS 流量");
            LoopbackQuery query = server.queries().get(0);
            assertEquals(domain, query.name(), "查询名必须只来自本轮 resolve，不能是平台自己发的域名");
            assertEquals(LoopbackDnsServer.TYPE_ANY, query.type(),
                    "JDK 21 把 {A,AAAA} 合并成一次 ANY(255) 查询；类型变了就说明查询次数/流量假设也变了");
            assertEquals(LoopbackDnsServer.CLASS_IN, query.rrclass());
        }
    }

    @Test
    void returnsIpv6FromAaaaRecordsInJndiCompressedForm() throws Exception {
        try (LoopbackDnsServer server = new LoopbackDnsServer()) {
            String domain = uniqueDomain("aaaa");
            server.answerAaaa("2001:db8::10");
            JndiDdnsResolver resolver = new JndiDdnsResolver(providerUrl(server));

            List<String> addresses = resolver.resolve(domain);

            assertEquals(List.of("2001:db8::10"), addresses,
                    "JNDI 的 AAAA 解码会把最长零串压成 ::，这是 DdnsAddresses.isIpLiteral 能接受的形式");
        }
    }

    @Test
    void returnsBothFamiliesWithIpv4FirstRegardlessOfWireOrder() throws Exception {
        try (LoopbackDnsServer server = new LoopbackDnsServer()) {
            String domain = uniqueDomain("dual");
            server.answerA("198.51.100.20");
            server.answerAaaa("2001:db8::20");
            server.putAaaaFirstInAnswerSection(true);
            JndiDdnsResolver resolver = new JndiDdnsResolver(providerUrl(server));

            List<String> addresses = resolver.resolve(domain);

            assertEquals(List.of("198.51.100.20", "2001:db8::20"), addresses,
                    "resolver 先读 A 属性再读 AAAA 属性，顺序由属性决定，与报文里记录顺序无关");
        }
    }

    @Test
    void seesAddressChangesWithoutJvmAddressCache() throws Exception {
        try (LoopbackDnsServer server = new LoopbackDnsServer()) {
            String domain = uniqueDomain("change");
            server.answerA("198.51.100.30");
            JndiDdnsResolver resolver = new JndiDdnsResolver(providerUrl(server));

            assertEquals(List.of("198.51.100.30"), resolver.resolve(domain));

            server.answerA("198.51.100.31");
            assertEquals(List.of("198.51.100.31"), resolver.resolve(domain),
                    "第二次解析必须看到新地址：一旦命中 JVM 地址缓存，DDNS 就失去意义");
            assertEquals(2, server.queries().size(), "每个新 InitialDirContext 都应重新发一次查询");
        }
    }

    @Test
    void nxdomainSurfacesAsNameNotFoundException() throws Exception {
        try (LoopbackDnsServer server = new LoopbackDnsServer()) {
            String domain = uniqueDomain("nx");
            server.rcode(LoopbackDnsServer.RCODE_NAME_ERROR);
            JndiDdnsResolver resolver = new JndiDdnsResolver(providerUrl(server));

            NamingException failure = assertThrows(NamingException.class, () -> resolver.resolve(domain));

            assertTrue(failure instanceof NameNotFoundException,
                    "NXDOMAIN 必须映射成 NameNotFoundException，实际是 " + failure.getClass().getName());
        }
    }

    @Test
    void noDataAnswerFailsWithoutInventingAnAddress() throws Exception {
        try (LoopbackDnsServer server = new LoopbackDnsServer()) {
            String domain = uniqueDomain("nodata");
            server.rcode(LoopbackDnsServer.RCODE_NO_ERROR);
            JndiDdnsResolver resolver = new JndiDdnsResolver(providerUrl(server));

            assertThrows(NameNotFoundException.class, () -> resolver.resolve(domain));
        }
    }

    @Test
    void silentServerFailsWithinTheHardCodedRetryBudget() throws Exception {
        try (LoopbackDnsServer server = new LoopbackDnsServer()) {
            String domain = uniqueDomain("timeout");
            server.dropResponses(true);
            JndiDdnsResolver resolver = new JndiDdnsResolver(providerUrl(server));

            long start = System.nanoTime();
            NamingException failure = assertThrows(NamingException.class, () -> resolver.resolve(domain));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertFalse(failure instanceof NameNotFoundException, "超时必须报错，不能伪装成域名不存在");
            assertTrue(elapsedMs >= 1_500,
                    "resolver 写死 timeout.initial=2000/retries=1，必须真的等过第一轮再失败；实际 " + elapsedMs + " ms");
            assertTrue(elapsedMs < 20_000, "超时必须有上限，不能拖死调度线程；实际 " + elapsedMs + " ms");
            assertTrue(failure instanceof javax.naming.CommunicationException,
                    "超时必须是 CommunicationException（根因 SocketTimeoutException），实际是 " + failure.getClass().getName());
            assertEquals(java.net.SocketTimeoutException.class, failure.getRootCause().getClass(),
                    "根因必须是 socket 读超时，而不是解析失败");
            // 实测：timeout.retries=1 并不会换来第二次 UDP 尝试 —— 第一轮吃满 2000ms 后额度已用完。
            assertEquals(1, server.queries().size(),
                    "只应发出一次查询：retries=1 在首个 2000ms 超时耗尽后不会再试；实际 " + server.queries().size());
            assertEquals(domain, server.queries().get(0).name());
        }
    }

    private static String providerUrl(LoopbackDnsServer server) {
        return "dns://127.0.0.1:" + server.port() + "/";
    }

    private static String uniqueDomain(String label) {
        // .invalid 是 RFC 2606 保留 TLD：即使实现意外回落到系统解析，也不可能有外部答案。
        return "ddns-" + label + "-" + Long.toHexString(System.nanoTime()) + ".invalid";
    }

    private record LoopbackQuery(String name, int type, int rrclass) {
    }

    /** 只绑定 127.0.0.1 的最小 UDP DNS 应答器：记录每条查询，按当前配置回 A/AAAA 或 rcode。 */
    private static final class LoopbackDnsServer implements AutoCloseable {

        static final int TYPE_A = 1;
        static final int TYPE_AAAA = 28;
        static final int TYPE_ANY = 255;
        static final int CLASS_IN = 1;
        static final int RCODE_NO_ERROR = 0;
        static final int RCODE_NAME_ERROR = 3;

        private static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};

        private final DatagramSocket socket;
        private final Thread worker;
        private final List<LoopbackQuery> queries = new CopyOnWriteArrayList<>();
        private volatile List<byte[]> aRecords = List.of();
        private volatile List<byte[]> aaaaRecords = List.of();
        private volatile boolean aaaaFirst;
        private volatile int rcode;
        private volatile boolean dropResponses;
        private volatile boolean running = true;

        LoopbackDnsServer() throws IOException {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), 0));
            socket.setSoTimeout(100);
            worker = new Thread(this::serve, "fake-dns-loopback");
            worker.setDaemon(true);
            worker.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        boolean boundToIpv4Loopback() {
            return socket.getLocalAddress().isLoopbackAddress()
                    && "127.0.0.1".equals(socket.getLocalAddress().getHostAddress());
        }

        List<LoopbackQuery> queries() {
            return List.copyOf(queries);
        }

        void answerA(String... literals) {
            List<byte[]> records = new ArrayList<>(literals.length);
            for (String literal : literals) {
                records.add(ipv4(literal));
            }
            aRecords = records;
        }

        void answerAaaa(String... literals) {
            List<byte[]> records = new ArrayList<>(literals.length);
            for (String literal : literals) {
                records.add(ipv6(literal));
            }
            aaaaRecords = records;
        }

        void putAaaaFirstInAnswerSection(boolean value) {
            aaaaFirst = value;
        }

        void rcode(int value) {
            rcode = value;
        }

        void dropResponses(boolean value) {
            dropResponses = value;
        }

        private void serve() {
            byte[] buffer = new byte[512];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException idle) {
                    continue;
                } catch (IOException stopped) {
                    return;
                }
                try {
                    LoopbackQuery query = parse(packet);
                    queries.add(query);
                    if (dropResponses) {
                        continue;
                    }
                    byte[] response = buildResponse(packet, query);
                    socket.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                } catch (RuntimeException | IOException malformed) {
                    // 测试里只会出现自己构造的查询；畸形包直接丢掉，不制造额外流量。
                }
            }
        }

        private static LoopbackQuery parse(DatagramPacket packet) {
            byte[] buffer = packet.getData();
            int pos = 12;
            StringBuilder name = new StringBuilder();
            while (true) {
                int length = buffer[pos++] & 0xff;
                if (length == 0) {
                    break;
                }
                if ((length & 0xc0) != 0) {
                    throw new IllegalArgumentException("question name 不应带压缩指针");
                }
                for (int i = 0; i < length; i++) {
                    name.append((char) (buffer[pos++] & 0xff));
                }
                name.append('.');
            }
            int type = readShort(buffer, pos);
            int rrclass = readShort(buffer, pos + 2);
            String value = name.length() == 0 ? "" : name.substring(0, name.length() - 1);
            return new LoopbackQuery(value.toLowerCase(Locale.ROOT), type, rrclass);
        }

        private byte[] buildResponse(DatagramPacket packet, LoopbackQuery query) throws IOException {
            byte[] request = packet.getData();
            int questionEnd = questionEnd(request);

            List<byte[]> answers = new ArrayList<>();
            List<Integer> answerTypes = new ArrayList<>();
            if (rcode == RCODE_NO_ERROR) {
                boolean wantsA = query.type() == TYPE_ANY || query.type() == TYPE_A;
                boolean wantsAaaa = query.type() == TYPE_ANY || query.type() == TYPE_AAAA;
                if (wantsA && wantsAaaa && aaaaFirst) {
                    add(answers, answerTypes, TYPE_AAAA, aaaaRecords);
                    add(answers, answerTypes, TYPE_A, aRecords);
                } else {
                    if (wantsA) {
                        add(answers, answerTypes, TYPE_A, aRecords);
                    }
                    if (wantsAaaa) {
                        add(answers, answerTypes, TYPE_AAAA, aaaaRecords);
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            writeShort(out, readShort(request, 0));
            writeShort(out, 0x8180 | (rcode & 0x0f));
            writeShort(out, 1);
            writeShort(out, answers.size());
            writeShort(out, 0);
            writeShort(out, 0);
            out.write(request, 12, questionEnd - 12);
            for (int i = 0; i < answers.size(); i++) {
                out.write(0xc0);
                out.write(0x0c);
                writeShort(out, answerTypes.get(i));
                writeShort(out, CLASS_IN);
                writeInt(out, 30);
                byte[] rdata = answers.get(i);
                writeShort(out, rdata.length);
                out.write(rdata, 0, rdata.length);
            }
            return out.toByteArray();
        }

        private static void add(List<byte[]> answers, List<Integer> types, int type, List<byte[]> records) {
            for (byte[] record : records) {
                answers.add(record);
                types.add(type);
            }
        }

        private static int questionEnd(byte[] buffer) {
            int pos = 12;
            while (true) {
                int length = buffer[pos++] & 0xff;
                if (length == 0) {
                    break;
                }
                pos += length;
            }
            return pos + 4;
        }

        private static int readShort(byte[] buffer, int offset) {
            return ((buffer[offset] & 0xff) << 8) | (buffer[offset + 1] & 0xff);
        }

        private static void writeShort(ByteArrayOutputStream out, int value) {
            out.write((value >>> 8) & 0xff);
            out.write(value & 0xff);
        }

        private static void writeInt(ByteArrayOutputStream out, int value) {
            writeShort(out, (value >>> 16) & 0xffff);
            writeShort(out, value & 0xffff);
        }

        private static byte[] ipv4(String literal) {
            String[] parts = literal.split("\\.");
            byte[] data = new byte[4];
            for (int i = 0; i < 4; i++) {
                data[i] = (byte) Integer.parseInt(parts[i]);
            }
            return data;
        }

        private static byte[] ipv6(String literal) {
            try {
                // 字面量由 JDK 本地解析，不会触发 DNS 查询。
                return InetAddress.getByName(literal).getAddress();
            } catch (IOException e) {
                throw new IllegalArgumentException("不是合法 IPv6 字面量：" + literal, e);
            }
        }

        @Override
        public void close() {
            running = false;
            socket.close();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
