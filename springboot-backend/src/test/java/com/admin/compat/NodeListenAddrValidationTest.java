package com.admin.compat;

import com.admin.service.impl.NodeServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点监听地址的落库前校验（NodeServiceImpl.validateListenAddr）。
 *
 * <p>为什么必须挡在落库前：下发时地址是 {@code listenAddr + ":" + port}（GostUtil.buildForwardService:206-210），
 * 库里的 host 写错（带空格、带斜杠、括号不匹配、裸写多个 "::"）会让节点起不来或绑到别处，
 * 而摘要对账又会因为"库与节点永远对不上"把服务反复删了重建。
 *
 * <p>注意：本类只测校验，不改写库值——裸 IPv6 的方括号由下发侧补，库里保留运维输入原样。
 */
class NodeListenAddrValidationTest {

    private static String validate(String value, String field) throws Exception {
        Method m = NodeServiceImpl.class.getDeclaredMethod("validateListenAddr", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value, field);
    }

    @Test
    @DisplayName("空白/空串/null 表示「这次不改」，一律放行")
    void blankIsAllowed() throws Exception {
        assertNull(validate(null, "tcpListenAddr"));
        assertNull(validate("", "tcpListenAddr"));
        assertNull(validate("   ", "tcpListenAddr"));
    }

    @Test
    @DisplayName("常见合法写法放行：通配、IPv4、带括号 IPv6、裸 IPv6、主机名")
    void validFormsAreAccepted() throws Exception {
        assertNull(validate("0.0.0.0", "tcpListenAddr"));
        assertNull(validate("[::]", "tcpListenAddr"));
        assertNull(validate("127.0.0.1", "tcpListenAddr"));
        assertNull(validate("fe80::1", "tcpListenAddr"));
        assertNull(validate("localhost", "tcpListenAddr"));
    }

    @Test
    @DisplayName("会拼出非法 addr 的写法被拒：空白、斜杠、逗号、#、@")
    void illegalCharactersAreRejected() throws Exception {
        assertNotNull(validate("1.2.3.4 5", "tcpListenAddr"));
        assertNotNull(validate("1.2.3.4/32", "tcpListenAddr"));
        assertNotNull(validate("1.2.3.4,5.6.7.8", "tcpListenAddr"));
        assertNotNull(validate("host#1", "tcpListenAddr"));
        assertNotNull(validate("user@host", "tcpListenAddr"));
    }

    @Test
    @DisplayName("方括号必须成对，且括号内必须是 IPv6")
    void bracketFormMustBeBalancedIpv6() throws Exception {
        assertNotNull(validate("[::", "tcpListenAddr"));
        assertNotNull(validate("::]", "tcpListenAddr"));
        assertNotNull(validate("[abc]", "tcpListenAddr"));
        assertNotNull(validate("[fe80::1", "tcpListenAddr"));
    }

    @Test
    @DisplayName("裸 IPv6 里 \"::\" 出现两次以上属于非法缩写")
    void doubleColonCannotRepeat() throws Exception {
        assertNotNull(validate("::::", "tcpListenAddr"));
        assertNotNull(validate("1::2::3", "tcpListenAddr"));
        assertNull(validate("1::2", "tcpListenAddr"));
    }

    @Test
    @DisplayName("长度上限 64，报错文案必须带字段名")
    void lengthLimitAndFieldName() throws Exception {
        StringBuilder longAddr = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            longAddr.append('a');
        }
        String error = validate(longAddr.toString(), "udpListenAddr");
        assertNotNull(error);
        assertTrue(error.startsWith("udpListenAddr"));

        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            ok.append('a');
        }
        assertNull(validate(ok.toString(), "udpListenAddr"));
    }
}
