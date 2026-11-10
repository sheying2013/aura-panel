package com.admin.compat;

import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隧道类型未知时必须 Fail-Closed（round44 QA 裁决）。
 *
 * <p>为什么不能猜：tunnel.type 决定 handler 里挂不挂 chains_&lt;tid&gt;。
 * 历史行 type 为 NULL 时若按"直连"下发，本该走中继加密的流量会在入口节点
 * 直接明文连目标，入口机真实 IP 暴露；若按"中继"猜，节点上没有对应链，
 * 服务起来即解析失败。两种猜测都错，只能拒发并让上层记录。
 */
class GostUtilTunnelTypeGuardTest {

    @Test
    @DisplayName("tunnel 为 null：拒绝下发")
    void nullTunnelIsRejected() {
        assertThrows(IllegalStateException.class, () -> build("tcp", null));
    }

    @Test
    @DisplayName("tunnel.type 为 null：拒绝下发，不按直连猜")
    void nullTypeIsRejected() {
        Tunnel tunnel = tunnel(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> build("tcp", tunnel));
        assertTrue(ex.getMessage().contains("tunnel.type=null"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("拒绝下发"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("tunnel.type 非法值（9）：拒绝下发")
    void unknownTypeIsRejected() {
        Tunnel tunnel = tunnel(9);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> build("udp", tunnel));
        assertTrue(ex.getMessage().contains("tunnel.type=9"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("type=1 直连：不挂 chain，且按节点网卡下发 metadata.interface")
    void directTunnelHasNoChain() throws Exception {
        JSONObject service = build("tcp", tunnel(1));
        assertEquals("21_1_0_tcp", service.getString("name"));
        assertFalse(service.getJSONObject("handler").containsKey("chain"), "直连不该挂 chains_<tid>");
        assertEquals("eth0", service.getJSONObject("metadata").getString("interface"));
    }

    @Test
    @DisplayName("type=2 中继：挂 chains_<tid>，且不设入口网卡 metadata")
    void relayTunnelCarriesChain() throws Exception {
        JSONObject service = build("udp", tunnel(2));
        assertEquals("chains_16", service.getJSONObject("handler").getString("chain"));
        assertFalse(service.containsKey("metadata"), "隧道转发的 interface 在链节点上设置，入口不该带");
    }

    private static JSONObject build(String protocol, Tunnel tunnel) throws Exception {
        Method method = GostUtil.class.getDeclaredMethod("buildForwardService", String.class, Integer.class,
                Node.class, Forward.class, ForwardPort.class, Tunnel.class, String.class);
        method.setAccessible(true);
        try {
            return (JSONObject) method.invoke(null, "21_1_0", null, node(), forward(), forwardPort(), tunnel, protocol);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static Tunnel tunnel(Integer type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(16L);
        tunnel.setName("tunnel");
        tunnel.setType(type);
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

    private static Forward forward() {
        Forward forward = new Forward();
        forward.setId(21L);
        forward.setUserId(1);
        forward.setTunnelId(16);
        forward.setRemoteAddr("backend:6365");
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
