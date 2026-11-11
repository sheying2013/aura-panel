package com.admin.ddns;

import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** DDNS 必须真正进入下发报文，而不是只存在注册表里。 */
class DdnsGostRenderingTest {

    @AfterEach
    void resetRenderer() {
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("已登记域名在下发报文中变成当前主 IP，端口保留")
    void rendersRegisteredDomainIntoServiceConfig() throws Exception {
        DdnsAddressRegistry registry = new DdnsAddressRegistry();
        registry.put("app.example.com", "2.2.2.2");

        JSONObject service = build("app.example.com:8080,other.example.com:9090");
        JSONArray nodes = service.getJSONObject("forwarder").getJSONArray("nodes");

        assertEquals("2.2.2.2:8080", nodes.getJSONObject(0).getString("addr"));
        assertEquals("other.example.com:9090", nodes.getJSONObject(1).getString("addr"));
    }

    @Test
    @DisplayName("IPv6 主地址进入下报文时补方括号，端口才不会被误判")
    void rendersIpv6WithBrackets() throws Exception {
        DdnsAddressRegistry registry = new DdnsAddressRegistry();
        registry.put("app.example.com", "2001:db8::1");

        JSONObject service = build("app.example.com:8080");
        JSONArray nodes = service.getJSONObject("forwarder").getJSONArray("nodes");
        assertEquals("[2001:db8::1]:8080", nodes.getJSONObject(0).getString("addr"));
    }

    @Test
    @DisplayName("未登记域名保持原样，老行为不变")
    void leavesUnregisteredDomainUntouched() throws Exception {
        DdnsTargetRenderer.reset();
        JSONObject service = build("app.example.com:8080");
        JSONArray nodes = service.getJSONObject("forwarder").getJSONArray("nodes");
        assertEquals("app.example.com:8080", nodes.getJSONObject(0).getString("addr"));
    }

    private static JSONObject build(String remoteAddr) throws Exception {
        Method method = GostUtil.class.getDeclaredMethod("buildForwardService", String.class, Integer.class,
                Node.class, Forward.class, ForwardPort.class, Tunnel.class, String.class);
        method.setAccessible(true);
        try {
            return (JSONObject) method.invoke(null, "21_1_0", null, node(), forward(remoteAddr),
                    forwardPort(), tunnel(), "tcp");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(16L);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        return tunnel;
    }

    private static Node node() {
        Node node = new Node();
        node.setId(1L);
        node.setName("node");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setInterfaceName("eth0");
        return node;
    }

    private static Forward forward(String remoteAddr) {
        Forward forward = new Forward();
        forward.setId(21L);
        forward.setUserId(1);
        forward.setTunnelId(16);
        forward.setRemoteAddr(remoteAddr);
        forward.setStrategy("round");
        return forward;
    }

    private static ForwardPort forwardPort() {
        ForwardPort port = new ForwardPort();
        port.setForwardId(21L);
        port.setNodeId(1L);
        port.setPort(30200);
        return port;
    }
}
