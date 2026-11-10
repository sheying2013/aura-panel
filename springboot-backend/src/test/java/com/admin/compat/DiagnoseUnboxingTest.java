package com.admin.compat;

import com.admin.common.dto.TunnelTopologyDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.ForwardServiceImpl;
import com.admin.service.impl.TunnelServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * round57 诊断与拓扑拆箱空指针安全回归测试。
 *
 * <p>排查确证缺陷：
 * 1. TunnelServiceImpl.diagnoseTunnel 与 ForwardServiceImpl.diagnoseForward 中，
 *    firstChainNode.getPort() 与 outNode.getPort() 为 Integer 包装类型，
 *    直接作为实参传入 performTcpPingDiagnosisWithConnectionCheck(..., int port, ...)，
 *    若库中端口为 null 会在调用现场直接触发自动拆箱 NullPointerException；
 * 2. performTcpPingDiagnosisWithConnectionCheck 缺乏 node、targetIp、port 的前置有效性校验；
 * 3. TunnelServiceImpl.diagnoseTunnel 中 tunnel.getType() == 1/2 在 type 为 null 时触发拆箱 NPE；
 * 4. ForwardServiceImpl.diagnoseForward 中 forward.getRemoteAddr().split(",") 缺乏 null 保护；
 * 5. TunnelServiceImpl.updateTunnelTopology 在 resolvedType 为 null 时必须返回友好错误而非自动拆箱。
 */
class DiagnoseUnboxingTest {

    private static final long TUNNEL_ID = 100L;
    private static final long FORWARD_ID = 200L;
    private static final int USER_ID = 1;
    private static final long IN_NODE_ID = 10L;
    private static final long CHAIN_NODE_ID = 20L;
    private static final long OUT_NODE_ID = 30L;

    private TunnelServiceImpl tunnelService;
    private TunnelMapper tunnelMapper;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;

    @BeforeEach
    void setUp() {
        tunnelMapper = mock(TunnelMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        forwardMapper = mock(ForwardMapper.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);
        TunnelService mockTunnelService = mock(TunnelService.class);

        // TunnelService setup
        tunnelService = new TunnelServiceImpl();
        ReflectionTestUtils.setField(tunnelService, "baseMapper", tunnelMapper);
        ReflectionTestUtils.setField(tunnelService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(tunnelService, "nodeService", nodeService);
        ReflectionTestUtils.setField(tunnelService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(tunnelService, "forwardService", mock(ForwardService.class));
        ReflectionTestUtils.setField(tunnelService, "userTunnelService", mock(UserTunnelService.class));

        // ForwardService setup
        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", mockTunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        when(nodeService.getById(IN_NODE_ID)).thenReturn(createNode(IN_NODE_ID, "入口节点", "10.0.0.1"));
        when(nodeService.getById(CHAIN_NODE_ID)).thenReturn(createNode(CHAIN_NODE_ID, "中继节点", "10.0.0.2"));
        when(nodeService.getById(OUT_NODE_ID)).thenReturn(createNode(OUT_NODE_ID, "出口节点", "10.0.0.3"));

        authenticate();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ==================== 1. TunnelServiceImpl.diagnoseTunnel ====================

    @Test
    @DisplayName("diagnoseTunnel：tunnel.type 为 null 时安全降级，不抛自动拆箱 NPE")
    void diagnoseTunnelNullTypeDoesNotNpe() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("null-type-tunnel");
        tunnel.setType(null); // null type
        when(tunnelMapper.selectById(TUNNEL_ID)).thenReturn(tunnel);

        List<ChainTunnel> chains = new ArrayList<>();
        chains.add(createChain(TUNNEL_ID, IN_NODE_ID, 1, 0, 10001));
        when(chainTunnelService.list(any())).thenReturn(chains);

        R result = tunnelService.diagnoseTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), "type 为 null 时诊断应当安全降级返回成功报告");
        assertNotNull(result.getData());
        assertTrue(result.getData() instanceof Map);
        Map<?, ?> report = (Map<?, ?>) result.getData();
        assertEquals("null-type-tunnel", report.get("tunnelName"));
    }

    @Test
    @DisplayName("diagnoseTunnel：type=2 且链节点 port 为 null 时安全跳过，不抛自动拆箱 NPE")
    void diagnoseTunnelType2NullChainPortDoesNotNpe() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("type2-tunnel");
        tunnel.setType(2);
        when(tunnelMapper.selectById(TUNNEL_ID)).thenReturn(tunnel);

        List<ChainTunnel> chains = new ArrayList<>();
        chains.add(createChain(TUNNEL_ID, IN_NODE_ID, 1, 0, 10001));
        // 中继节点端口为 null
        chains.add(createChain(TUNNEL_ID, CHAIN_NODE_ID, 2, 1, null));
        chains.add(createChain(TUNNEL_ID, OUT_NODE_ID, 3, 0, 10003));
        when(chainTunnelService.list(any())).thenReturn(chains);

        R result = tunnelService.diagnoseTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), "链节点端口为 null 时不应抛 NPE");
        Map<?, ?> report = (Map<?, ?>) result.getData();
        assertNotNull(report.get("results"));
    }

    @Test
    @DisplayName("diagnoseTunnel：type=2 且出口节点 port 为 null 时安全跳过，不抛自动拆箱 NPE")
    void diagnoseTunnelType2NullOutPortDoesNotNpe() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("type2-tunnel-no-chain");
        tunnel.setType(2);
        when(tunnelMapper.selectById(TUNNEL_ID)).thenReturn(tunnel);

        List<ChainTunnel> chains = new ArrayList<>();
        chains.add(createChain(TUNNEL_ID, IN_NODE_ID, 1, 0, 10001));
        // 无中继节点，直接连出口，出口端口为 null
        chains.add(createChain(TUNNEL_ID, OUT_NODE_ID, 3, 0, null));
        when(chainTunnelService.list(any())).thenReturn(chains);

        R result = tunnelService.diagnoseTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), "出口节点端口为 null 时不应抛 NPE");
        Map<?, ?> report = (Map<?, ?>) result.getData();
        assertNotNull(report.get("results"));
    }

    // ==================== 2. TunnelServiceImpl.updateTunnelTopology ====================

    @Test
    @DisplayName("updateTunnelTopology：topologyDto.type 为 null 且 tunnel.type 为 null 时安全拦截报错，不抛自动拆箱 NPE")
    void updateTunnelTopologyNullTypeReturnsError() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setType(null); // 库中 type 亦为 null
        when(tunnelMapper.selectById(TUNNEL_ID)).thenReturn(tunnel);

        TunnelTopologyDto dto = new TunnelTopologyDto();
        dto.setId(TUNNEL_ID);
        dto.setInNodeId(Collections.singletonList(createChain(TUNNEL_ID, IN_NODE_ID, 1, 0, null)));
        dto.setType(null); // null 拓扑类型

        R result = tunnelService.updateTunnelTopology(dto);

        assertEquals(-1, result.getCode());
        assertEquals("隧道类型只能是 1（直连）或 2（隧道转发）", result.getMsg());
    }

    @Test
    @DisplayName("updateTunnelTopology：topologyDto.type 为无效值时安全拦截报错")
    void updateTunnelTopologyInvalidTypeReturnsError() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setType(1);
        when(tunnelMapper.selectById(TUNNEL_ID)).thenReturn(tunnel);

        TunnelTopologyDto dto = new TunnelTopologyDto();
        dto.setId(TUNNEL_ID);
        dto.setInNodeId(Collections.singletonList(createChain(TUNNEL_ID, IN_NODE_ID, 1, 0, null)));
        dto.setType(99); // 非 1/2 拓扑类型

        R result = tunnelService.updateTunnelTopology(dto);

        assertEquals(-1, result.getCode());
        assertEquals("隧道类型只能是 1（直连）或 2（隧道转发）", result.getMsg());
    }

    // ==================== 3. ForwardServiceImpl.diagnoseForward ====================

    @Test
    @DisplayName("diagnoseForward：forward.remoteAddr 为 null 时安全处理，不抛 NPE")
    void diagnoseForwardNullRemoteAddrDoesNotNpe() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(USER_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setRemoteAddr(null); // null 远程地址
        when(forwardMapper.selectById(FORWARD_ID)).thenReturn(forward);

        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setType(1);
        TunnelService ts = (TunnelService) ReflectionTestUtils.getField(forwardService, "tunnelService");
        when(ts.getById(any())).thenReturn(tunnel);

        List<ChainTunnel> chains = new ArrayList<>();
        chains.add(createChain(TUNNEL_ID, IN_NODE_ID, 1, 0, 10001));
        when(chainTunnelService.list(any())).thenReturn(chains);

        R result = forwardService.diagnoseForward(FORWARD_ID);

        assertEquals(0, result.getCode(), "remoteAddr 为 null 时应当安全返回空诊断列表而不抛 NPE");
        assertNotNull(result.getData());
        assertTrue(result.getData() instanceof Map);
        Map<?, ?> report = (Map<?, ?>) result.getData();
        List<?> results = (List<?>) report.get("results");
        assertNotNull(results);
        assertEquals(0, results.size());
    }

    @Test
    @DisplayName("diagnoseForward：forward.remoteAddr 为空串时安全处理，不抛 NPE")
    void diagnoseForwardEmptyRemoteAddrDoesNotNpe() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(USER_ID);
        forward.setTunnelId((int) TUNNEL_ID);
        forward.setRemoteAddr("   ");
        when(forwardMapper.selectById(FORWARD_ID)).thenReturn(forward);

        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setType(1);
        TunnelService ts = (TunnelService) ReflectionTestUtils.getField(forwardService, "tunnelService");
        when(ts.getById(any())).thenReturn(tunnel);

        List<ChainTunnel> chains = new ArrayList<>();
        chains.add(createChain(TUNNEL_ID, IN_NODE_ID, 1, 0, 10001));
        when(chainTunnelService.list(any())).thenReturn(chains);

        R result = forwardService.diagnoseForward(FORWARD_ID);

        assertEquals(0, result.getCode());
        Map<?, ?> report = (Map<?, ?>) result.getData();
        List<?> results = (List<?>) report.get("results");
        assertNotNull(results);
        assertEquals(0, results.size());
    }

    // ==================== 测试辅助方法 ====================

    private static Node createNode(Long id, String name, String ip) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setServerIp(ip);
        node.setPort("10000-10100");
        node.setStatus(1);
        return node;
    }

    private static ChainTunnel createChain(Long tunnelId, Long nodeId, Integer type, Integer inx, Integer port) {
        ChainTunnel ct = new ChainTunnel();
        ct.setTunnelId(tunnelId);
        ct.setNodeId(nodeId);
        ct.setChainType(type);
        ct.setInx(inx);
        ct.setPort(port);
        return ct;
    }

    private static void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
