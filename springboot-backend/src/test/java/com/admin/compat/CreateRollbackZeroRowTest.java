package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.ForwardServiceImpl;
import com.admin.service.impl.TunnelServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清理时 remove 返回 false 的语义：它只说明 SQL 影响 0 行。
 * <ul>
 *   <li>此前没有任何记录"确认落库"（首个写入就失败/抛异常）时，0 行删除是预期结果，报"未确认已清理"就是误报；</li>
 *   <li>此前已确认写入过记录时，0 行删除说明该删的没删掉，必须如实报"未确认已清理"，不能当成功。</li>
 * </ul>
 * 两类都必须与"清理抛异常（结果未知）"分开表达。
 */
class CreateRollbackZeroRowTest {

    private static final long FORWARD_ID = 42L;
    private static final long TUNNEL_ID = 7L;
    private static final int TUNNEL_ID_INT = 7;
    private static final int USER_ID = 1;
    private static final long NODE_ID = 101L;
    private static final int IN_PORT = 20000;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private ChainTunnelService chainTunnelService;

    @BeforeEach
    void setUp() {
        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(forwardMapper.insert(any(Forward.class))).thenAnswer(invocation -> {
            ((Forward) invocation.getArgument(0)).setId(FORWARD_ID);
            return 1;
        });
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        when(chainTunnelService.list(any())).thenReturn(entryChainTunnels());
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        authenticate();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void firstPortSaveFailureDoesNotReportUnconfirmedCleanup() {
        // 第一个节点端口记录就没落库，端口表本来为空：DELETE 影响 0 行不是"清理未确认"
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(false);
        when(forwardPortService.remove(any())).thenReturn(false);

        R result = forwardService.createForward(forwardDto());

        assertEquals(-1, result.getCode(), "端口记录没落库时必须返回错误");
        assertTrue(result.getMsg().contains("端口记录保存失败"), result.getMsg());
        assertFalse(result.getMsg().contains("未确认已清理"),
                "没有写入过任何端口记录时，0 行删除不能报成清理未确认，实际：" + result.getMsg());
        verify(forwardPortService).remove(any());
        verify(forwardMapper).deleteById(FORWARD_ID);
    }

    @Test
    void confirmedPortWriteThenZeroRowDeleteIsReported() {
        // 第 1 个节点的端口记录确认落库（save 返回 true），随后回滚时 DELETE 只影响 0 行 —— 该删的没删掉
        when(nodeService.getById(NODE_ID)).thenReturn(node(NODE_ID), (Node) null);
        when(forwardPortService.remove(any())).thenReturn(false);

        R result = forwardService.createForward(forwardDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("未确认已清理"),
                "确认写入过端口记录后删除影响 0 行，必须报清理未确认，实际：" + result.getMsg());
    }

    @Test
    void tunnelChainSaveFailureDoesNotReportUnconfirmedCleanup() {
        StubTunnelService service = newTunnelService();
        when(chainTunnelService.saveBatch(any())).thenReturn(false);
        when(chainTunnelService.remove(any())).thenReturn(false);

        R result = service.createTunnel(tunnelDto());

        assertEquals(-1, result.getCode(), "节点记录没落库时必须返回错误");
        assertTrue(result.getMsg().contains("隧道节点记录保存失败"), result.getMsg());
        assertFalse(result.getMsg().contains("未确认已清理"),
                "节点记录一次都没落库时，0 行删除不能报成清理未确认，实际：" + result.getMsg());
        assertFalse(result.getMsg().contains("记录清理未完成"),
                "没有残留时不得报清理未完成，实际：" + result.getMsg());
        assertEquals(List.of(TUNNEL_ID), service.removedTunnelIds, "已落库的隧道记录必须尝试清理");
        verify(chainTunnelService).remove(any());
    }

    @Test
    void tunnelConfirmedChainWriteThenZeroRowDeleteIsReported() {
        StubTunnelService service = newTunnelService();
        service.chainFailure = "boom";
        when(chainTunnelService.saveBatch(any())).thenReturn(true);
        when(chainTunnelService.remove(any())).thenReturn(false);

        R result = service.createTunnel(tunnelDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("未确认已清理"),
                "确认写入过节点记录后删除影响 0 行，必须报清理未确认，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("记录清理未完成"), result.getMsg());
    }

    // ========== 测试脚手架 ==========

    private StubTunnelService newTunnelService() {
        when(nodeService.list(any())).thenReturn(List.of(node(101L), node(102L)));
        StubTunnelService service = new StubTunnelService();
        ReflectionTestUtils.setField(service, "nodeService", nodeService);
        ReflectionTestUtils.setField(service, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(service, "forwardPortService", forwardPortService);
        return service;
    }

    private static void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static ForwardDto forwardDto() {
        ForwardDto forwardDto = new ForwardDto();
        forwardDto.setName("zero-row");
        forwardDto.setTunnelId(TUNNEL_ID_INT);
        forwardDto.setRemoteAddr("10.10.10.10:8080");
        forwardDto.setStrategy("round");
        forwardDto.setInPort(IN_PORT);
        return forwardDto;
    }

    private static TunnelDto tunnelDto() {
        TunnelDto tunnelDto = new TunnelDto();
        tunnelDto.setName("tunnel-" + TUNNEL_ID);
        tunnelDto.setType(2);
        tunnelDto.setFlow(1);
        tunnelDto.setTrafficRatio(BigDecimal.ONE);
        tunnelDto.setInNodeId(List.of(chainTunnel(101L, 1)));
        tunnelDto.setChainNodes(new ArrayList<>());
        tunnelDto.setOutNodeId(List.of(chainTunnel(102L, 3)));
        return tunnelDto;
    }

    private static List<ChainTunnel> entryChainTunnels() {
        ChainTunnel chainTunnel = chainTunnel(NODE_ID, 1);
        return new ArrayList<>(List.of(chainTunnel));
    }

    private static ChainTunnel chainTunnel(Long nodeId, int chainType) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setNodeId(nodeId);
        chainTunnel.setChainType(chainType);
        chainTunnel.setProtocol("tcp");
        chainTunnel.setStrategy("round");
        return chainTunnel;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID_INT);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private static Node node(Long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0.1");
        node.setPort(IN_PORT + "-" + (IN_PORT + 10));
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setStatus(1);
        return node;
    }

    /**
     * 替身：隧道侧写入/删除结果与下发结果都由测试指定，覆盖发送出口，避免真实网络与真实库。
     */
    static class StubTunnelService extends TunnelServiceImpl {

        final List<Long> removedTunnelIds = new ArrayList<>();
        String chainFailure;

        @Override
        public int count(Wrapper<Tunnel> queryWrapper) {
            return 0;
        }

        @Override
        public boolean save(Tunnel entity) {
            entity.setId(TUNNEL_ID);
            return true;
        }

        @Override
        public boolean removeById(Serializable id) {
            removedTunnelIds.add((Long) id);
            return true;
        }

        @Override
        protected GostDto createChains(Long nodeId, List<ChainTunnel> chainTunnels, Map<Long, Node> nodes, Tunnel tunnel) {
            return reply(chainFailure == null ? "OK" : chainFailure);
        }

        @Override
        protected GostDto createChainService(Long nodeId, ChainTunnel chainTunnel, Map<Long, Node> nodes, Tunnel tunnel) {
            return reply("OK");
        }

        @Override
        protected GostDto deleteChains(Long nodeId, String name) {
            return reply("OK");
        }

        @Override
        protected GostDto deleteService(Long nodeId, String name) {
            return reply("OK");
        }

        private static GostDto reply(String msg) {
            GostDto gostDto = new GostDto();
            gostDto.setMsg(msg);
            return gostDto;
        }
    }
}
