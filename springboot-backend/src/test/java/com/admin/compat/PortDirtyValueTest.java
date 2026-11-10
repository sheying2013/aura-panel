package com.admin.compat;

import com.admin.entity.ChainTunnel;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.NodeService;
import com.admin.service.impl.ForwardServiceImpl;
import com.admin.service.impl.TunnelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「端口脏值」治理的回归测试。
 *
 * <p>覆盖的原缺口（端口只有 Integer.parseInt，没有 1..65535 校验）：
 * <ol>
 *   <li>{@link TunnelServiceImpl#parsePorts} 会把 0 / 70000 这类非法端口原样解析出来；</li>
 *   <li>{@link ForwardServiceImpl#getNodePort} 把 {@code chain_tunnel.port} 只滤 null 就塞进 usedPorts，
 *       脏值永久占住端口段；</li>
 *   <li>{@code expectedChainNodes} 只判 port == null，脏值被当真值算进期望摘要，
 *       节点上永远对不上 → 每轮判配置漂移 → 删链重建，只能靠漂移熔断器兜底。</li>
 * </ol>
 *
 * <p>口径：合法端口 = 1..65535；脏值一律丢弃/忽略/跳过判定，不抛异常、不改库。
 */
class PortDirtyValueTest {

    private static final long NODE_ID = 5L;
    private static final long TUNNEL_ID = 21L;
    private static final String NODE_RANGE = "30100-30105";

    private ForwardServiceImpl forwardService;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private ForwardPortService forwardPortService;

    /** 当前用例里 node_id=NODE_ID 的 chain_tunnel 行。 */
    private final List<ChainTunnel> chainRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        forwardPortService = mock(ForwardPortService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", mock(ForwardMapper.class));
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);

        chainRows.clear();
        when(nodeService.getById(NODE_ID)).thenReturn(node());
        when(chainTunnelService.list(any())).thenAnswer(invocation -> new ArrayList<>(chainRows));
        when(forwardPortService.list(any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------ parsePorts

    /** 单值脏值：0 / 70000 / 负数 / 非数字一律丢弃，一个都不许流出解析层。 */
    @Test
    @DisplayName("parsePorts：0、70000、负数、非数字全部丢弃")
    void parsePortsDropsOutOfRangeAndGarbage() {
        assertEquals(List.of(3000), TunnelServiceImpl.parsePorts("0,70000,-1,abc,3000"),
                "只有 3000 是合法端口");
        assertEquals(List.of(), TunnelServiceImpl.parsePorts("0,70000,-1,abc,  "),
                "全是脏值就该得到空列表，不能抛异常");
        assertEquals(List.of(65535), TunnelServiceImpl.parsePorts("65535"),
                "65535 是合法上界，必须保留");
        assertEquals(List.of(), TunnelServiceImpl.parsePorts("65536"), "65536 越界");
        assertEquals(List.of(), TunnelServiceImpl.parsePorts(null));
        assertEquals(List.of(), TunnelServiceImpl.parsePorts("   "));
    }

    /** 区间写反：5-3 归一成 3,4,5（同一段、升序）。 */
    @Test
    @DisplayName("parsePorts：区间 5-3 归一为 3,4,5")
    void reversedRangeIsNormalized() {
        assertEquals(List.of(3, 4, 5), TunnelServiceImpl.parsePorts("5-3"));
        assertEquals(List.of(3000, 3001, 3002), TunnelServiceImpl.parsePorts("3000-3002"),
                "正序区间行为不变");
    }

    /** 越界/缺端点的区间整段丢弃；合法端口段的既有行为（去重升序）不变。 */
    @Test
    @DisplayName("parsePorts：越界区间整段丢弃，合法端口段行为不变")
    void illegalRangeIsDroppedButLegalRangeUnchanged() {
        assertEquals(List.of(), TunnelServiceImpl.parsePorts("0-5"), "下界 0 非法，整段丢弃");
        assertEquals(List.of(), TunnelServiceImpl.parsePorts("65530-70000"), "上界 70000 非法，整段丢弃");
        assertEquals(List.of(), TunnelServiceImpl.parsePorts("3000-"), "缺端点，整段丢弃");
        assertEquals(List.of(), TunnelServiceImpl.parsePorts("3000-x"), "端点非数字，整段丢弃");
        assertEquals(List.of(30100, 30101, 30102, 30999),
                TunnelServiceImpl.parsePorts("30100-30102,30999,30100"),
                "合法段 + 单值 + 重复值：去重升序，与 NodeServiceImpl 端口段比对依赖的口径一致");
    }

    // ----------------------------------------------------------- getNodePort

    /** 脏行 70000 / 0 不占端口段；同节点合法链端口 30102 仍然被排除。 */
    @Test
    @DisplayName("getNodePort：70000/0 不算占用，合法链端口 30102 仍被排除")
    void dirtyChainPortDoesNotOccupyRange() {
        chainRows.add(hop(2L, 70000, "tcp", 0));
        chainRows.add(hop(2L, 0, "tcp", 0));
        chainRows.add(hop(2L, 30102, "tcp", 0));

        List<Integer> free = forwardService.getNodePort(NODE_ID, 99L);

        assertEquals(List.of(30100, 30101, 30103, 30104, 30105), free,
                "合法占用 30102 要排除，70000/0 不许占位，实际：" + free);
        assertFalse(free.contains(70000), "70000 这种脏值绝不能留在可用端口表里");
    }

    /** 整段只被脏行「占用」时，端口段必须一个不少地全部可分配（脏行不影响自动分口）。 */
    @Test
    @DisplayName("getNodePort：只有脏行时整段仍全部可用")
    void dirtyOnlyLeavesWholeRangeAvailable() {
        chainRows.add(hop(2L, 70000, "tcp", 0));
        chainRows.add(hop(3L, 65536, "tcp", 1));

        assertEquals(List.of(30100, 30101, 30102, 30103, 30104, 30105),
                forwardService.getNodePort(NODE_ID, 99L));
    }

    /** forward_port 脏行 70000 / 0 / null 不占端口段；合法转发端口 30101 仍被正常排除。 */
    @Test
    @DisplayName("getNodePort：forward_port 脏值（70000/0/null）不算占用，合法转发端口仍被排除")
    void dirtyForwardPortDoesNotOccupyRange() {
        ForwardPort dirty1 = new ForwardPort();
        dirty1.setForwardId(1L);
        dirty1.setNodeId(NODE_ID);
        dirty1.setPort(70000);

        ForwardPort dirty2 = new ForwardPort();
        dirty2.setForwardId(2L);
        dirty2.setNodeId(NODE_ID);
        dirty2.setPort(0);

        ForwardPort dirty3 = new ForwardPort();
        dirty3.setForwardId(3L);
        dirty3.setNodeId(NODE_ID);
        dirty3.setPort(null);

        ForwardPort valid = new ForwardPort();
        valid.setForwardId(4L);
        valid.setNodeId(NODE_ID);
        valid.setPort(30101);

        when(forwardPortService.list(any())).thenReturn(List.of(dirty1, dirty2, dirty3, valid));

        List<Integer> free = forwardService.getNodePort(NODE_ID, 99L);
        assertEquals(List.of(30100, 30102, 30103, 30104, 30105), free,
                "合法占用 30101 排除，脏值 70000/0/null 不占位");
    }

    // ---------------------------------------------------- expectedChainNodes

    /** 跳点端口 70000 / 0：算不出期望摘要 → null（调用方跳过漂移判定，不再删链重建）。 */
    @Test
    @DisplayName("expectedChainNodes：70000 返回 null，不再产出假期望值")
    void invalidHopPortYieldsNullExpectation() throws Exception {
        assertNull(expectedChainNodes(List.of(hop(2L, 70000, "tcp", 0)),
                        Map.of(2L, node(2L, "10.0.0.2"))),
                "70000 端口算不出节点真实监听地址，必须返回 null");
        assertNull(expectedChainNodes(List.of(hop(2L, 0, "tcp", 0)),
                        Map.of(2L, node(2L, "10.0.0.2"))),
                "0 端口同理");
        assertNull(expectedChainNodes(List.of(hop(2L, null, "tcp", 0)),
                        Map.of(2L, node(2L, "10.0.0.2"))),
                "port=null 的既有行为不变（入口行）");
    }

    /** 合法端口：摘要口径不变 node_&lt;id&gt;@&lt;ip:port&gt;#&lt;proto&gt;，多跳按行序。 */
    @Test
    @DisplayName("expectedChainNodes：合法端口摘要格式与行序不变")
    void legalHopsKeepDigestFormat() throws Exception {
        List<ChainTunnel> rows = List.of(
                hop(2L, 30201, "tcp", 0),
                hop(3L, 65535, "udp", 1));
        Map<Long, Node> nodes = new LinkedHashMap<>();
        nodes.put(2L, node(2L, "10.0.0.2"));
        nodes.put(3L, node(3L, "10.0.0.3"));

        assertEquals(List.of("node_2@10.0.0.2:30201#tcp", "node_3@10.0.0.3:65535#udp"),
                expectedChainNodes(rows, nodes));
    }

    // ---------------------------------------------------------------- 辅助

    private static ChainTunnel hop(Long nodeId, Integer port, String protocol, int inx) {
        ChainTunnel row = new ChainTunnel();
        row.setTunnelId(TUNNEL_ID);
        row.setChainType(2);
        row.setNodeId(nodeId);
        row.setPort(port);
        row.setProtocol(protocol);
        row.setStrategy("round");
        row.setInx(inx);
        return row;
    }

    private static Node node() {
        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("aura-sj-node-a");
        node.setServerIp("172.18.0.4");
        node.setPort(NODE_RANGE);
        return node;
    }

    private static Node node(Long id, String serverIp) {
        Node node = new Node();
        node.setId(id);
        node.setName("node-" + id);
        node.setServerIp(serverIp);
        node.setPort(NODE_RANGE);
        return node;
    }

    /** expectedChainNodes 是 private static（纯函数），按既有测试的做法用反射直接调。 */
    @SuppressWarnings("unchecked")
    private static List<String> expectedChainNodes(List<ChainTunnel> rows, Map<Long, Node> nodes) throws Exception {
        Method method = ForwardServiceImpl.class.getDeclaredMethod("expectedChainNodes", List.class, Map.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(null, rows, nodes);
    }
}
