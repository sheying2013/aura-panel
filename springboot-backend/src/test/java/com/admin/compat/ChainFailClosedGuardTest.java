package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中继链资源（chains_&lt;tid&gt; 与 &lt;tid&gt;_tls）只属于中继隧道（type=2），其余一律 Fail-Closed。
 *
 * <p>为什么不能猜：链资源的存在意义由 tunnel.type 定。历史行 type 为 NULL、非法值（9），
 * 或者干脆是直连隧道（1），都判定不了这条资源该不该存在——直连隧道根本没有下一跳。
 * 猜「直连」会在节点上留一条没人引用的链；猜「中继」会给链节点多开一个监听。
 * 半套配置留在节点上比拒发更难排查，只能拒发让上层记录（round45 QA P0-2：守卫严格等值 2）。
 *
 * <p>入口服务（buildForwardService）的守卫见 {@link GostUtilTunnelTypeGuardTest}；
 * 这里覆盖 CreateChains / CreateChainService 两个入口。
 */
class ChainFailClosedGuardTest {

    private static final Long CARRIER_NODE = 1L;
    private static final Long HOP_NODE = 2L;
    private static final Long TUNNEL_ID = 16L;

    @Test
    @DisplayName("CreateChains：tunnel.type 为 null → 拒绝下发，文案带 chains_<tid>")
    void createChainsRejectsNullType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GostUtil.CreateChains(CARRIER_NODE, nextHops(), nodes(), tunnel(null)));
        assertTrue(ex.getMessage().contains("tunnel.type=null"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("chains_16"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("拒绝下发"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("CreateChains：tunnel.type 为非法值（9）→ 拒绝下发，不按直连/中继猜")
    void createChainsRejectsUnknownType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GostUtil.CreateChains(CARRIER_NODE, nextHops(), nodes(), tunnel(9)));
        assertTrue(ex.getMessage().contains("tunnel.type=9"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("chains_16"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("CreateChains：tunnel 本身为 null → 同样拒绝下发")
    void createChainsRejectsMissingTunnel() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GostUtil.CreateChains(CARRIER_NODE, nextHops(), nodes(), null));
        assertTrue(ex.getMessage().contains("tunnel.type=null"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("chains_16"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("CreateChainService：tunnel.type 为 null → 拒绝下发，文案带 <tid>_tls")
    void createChainServiceRejectsNullType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GostUtil.CreateChainService(HOP_NODE, outRow(), nodes(), tunnel(null)));
        assertTrue(ex.getMessage().contains("tunnel.type=null"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("16_tls"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("CreateChainService：tunnel.type 为非法值（9）→ 拒绝下发，不落下 <tid>_tls")
    void createChainServiceRejectsUnknownType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GostUtil.CreateChainService(HOP_NODE, outRow(), nodes(), tunnel(9)));
        assertTrue(ex.getMessage().contains("tunnel.type=9"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("16_tls"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("type=2 中继：CreateChains / CreateChainService 都不拦，照常下发")
    void relayTunnelStillPublishesChainResources() {
        GostDto chain = assertDoesNotThrow(() -> GostUtil.CreateChains(CARRIER_NODE, nextHops(), nodes(), tunnel(2)));
        assertNotNull(chain, "中继隧道必须照常下发链资源");
        GostDto service = assertDoesNotThrow(() -> GostUtil.CreateChainService(HOP_NODE, outRow(), nodes(), tunnel(2)));
        assertNotNull(service, "中继隧道必须照常下发 <tid>_tls");
    }

    @Test
    @DisplayName("type=1 直连：同样拒发（直连隧道没有下一跳，链资源只可能是脏数据）")
    void directTunnelIsRejectedToo() {
        IllegalStateException chainError = assertThrows(IllegalStateException.class,
                () -> GostUtil.CreateChains(CARRIER_NODE, nextHops(), nodes(), tunnel(1)));
        assertTrue(chainError.getMessage().contains("tunnel.type=1"), chainError.getMessage());
        assertTrue(chainError.getMessage().contains("直连隧道没有下一跳"), chainError.getMessage());
        IllegalStateException serviceError = assertThrows(IllegalStateException.class,
                () -> GostUtil.CreateChainService(HOP_NODE, outRow(), nodes(), tunnel(1)));
        assertTrue(serviceError.getMessage().contains("16_tls"), serviceError.getMessage());
    }

    private static Tunnel tunnel(Integer type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(type);
        return tunnel;
    }

    /** 下一跳集合：一条链只有一个 hop。 */
    private static List<ChainTunnel> nextHops() {
        List<ChainTunnel> hops = new ArrayList<>();
        hops.add(row(3, HOP_NODE, 30201));
        return hops;
    }

    /** 出口行（chainType=3）：CreateChainService 的承接对象。 */
    private static ChainTunnel outRow() {
        return row(3, HOP_NODE, 30201);
    }

    private static ChainTunnel row(int chainType, Long nodeId, int port) {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL_ID);
        row.setChainType(chainType);
        row.setNodeId(nodeId);
        row.setPort(port);
        row.setProtocol("tls");
        row.setStrategy("fifo");
        return row;
    }

    private static Map<Long, Node> nodes() {
        Map<Long, Node> nodes = new HashMap<>();
        nodes.put(CARRIER_NODE, node(CARRIER_NODE, "10.0.0.1", "0.0.0.0"));
        nodes.put(HOP_NODE, node(HOP_NODE, "10.0.0.2", "0.0.0.0"));
        return nodes;
    }

    private static Node node(Long id, String serverIp, String listenAddr) {
        Node node = new Node();
        node.setId(id);
        node.setName("node-" + id);
        node.setServerIp(serverIp);
        node.setTcpListenAddr(listenAddr);
        node.setUdpListenAddr(listenAddr);
        node.setInterfaceName("eth0");
        return node;
    }
}
