package com.admin.compat;

import com.admin.common.utils.NodeOrphanSweeper;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Tunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 节点感知型（Node-Scoped）孤儿清扫回归测试套件。
 *
 * <p>核心解决架构盲区：
 * 1. 入口节点 (chainType=1): 仅允许 chains_<tunnelId>，严禁存在 <tunnelId>_tls 监听；
 * 2. 中继节点 (chainType=2): 同时需要 chains_<tunnelId> 与 <tunnelId>_tls 监听；
 * 3. 出口节点 (chainType=3): 仅允许 <tunnelId>_tls 监听，严禁存在 chains_<tunnelId>；
 * 4. 转发服务: 仅当 forward_port 存在该节点的记录时才保留，迁移后在老节点的残留自动清扫；
 * 5. 直连隧道: 任何节点上都不允许存在 _tls 或 chains_；
 * 6. 向下兼容与优雅降级: 未注入关联 Service 或 DB 异常时不抛异常且安全降级。
 */
public class NodeOrphanScopedSweepTest {

    private final List<String> deletedServices = new ArrayList<>();
    private final List<String> deletedChains = new ArrayList<>();

    @BeforeEach
    void setUp() {
        deletedServices.clear();
        deletedChains.clear();
    }

    @Test
    void entryNodeSweepsTlsServiceAndRetainsChains() {
        // Node 1 是隧道 10 的入口 (chainType=1)
        NodeOrphanSweeper sweeper = new NodeOrphanSweeper(
                (nodeId, forwardId) -> true,
                (nodeId, tunnelId) -> {
                    // 入口节点不需要 _tls 服务
                    return !(nodeId == 1L && tunnelId == 10L);
                },
                (nodeId, tunnelId) -> {
                    // 入口节点需要 chains 链路
                    return nodeId == 1L && tunnelId == 10L;
                },
                (nodeId, names) -> {
                    deletedServices.addAll(names.toJavaList(String.class));
                    return true;
                },
                (nodeId, name) -> {
                    deletedChains.add(name);
                    return true;
                }
        );

        NodeOrphanSweeper.SweepResult result = sweeper.sweep(1L,
                List.of("10_tls"), List.of("chains_10", "hop_10"));

        assertEquals(1, result.getDeleted(), "入口节点的 10_tls 必须作为孤儿删除");
        assertEquals(List.of("10_tls"), deletedServices);
        assertTrue(deletedChains.isEmpty(), "入口节点的 chains_10/hop_10 必须保留");
    }

    @Test
    void exitNodeSweepsChainsAndRetainsTlsService() {
        // Node 3 是隧道 10 的出口 (chainType=3)
        NodeOrphanSweeper sweeper = new NodeOrphanSweeper(
                (nodeId, forwardId) -> true,
                (nodeId, tunnelId) -> {
                    // 出口节点需要 _tls 监听
                    return nodeId == 3L && tunnelId == 10L;
                },
                (nodeId, tunnelId) -> {
                    // 出口节点不需要 chains 链路
                    return !(nodeId == 3L && tunnelId == 10L);
                },
                (nodeId, names) -> {
                    deletedServices.addAll(names.toJavaList(String.class));
                    return true;
                },
                (nodeId, name) -> {
                    deletedChains.add(name);
                    return true;
                }
        );

        NodeOrphanSweeper.SweepResult result = sweeper.sweep(3L,
                List.of("10_tls"), List.of("chains_10", "hop_10"));

        assertEquals(2, result.getDeleted(), "出口节点的 chains_10 与 hop_10 必须作为孤儿删除");
        assertTrue(deletedServices.isEmpty(), "出口节点的 10_tls 必须保留");
        assertEquals(List.of("chains_10", "hop_10"), deletedChains);
    }

    @Test
    void relayNodeRetainsBothTlsAndChains() {
        // Node 2 是中继节点 (chainType=2)，两者皆需
        NodeOrphanSweeper sweeper = new NodeOrphanSweeper(
                (nodeId, forwardId) -> true,
                (nodeId, tunnelId) -> nodeId == 2L && tunnelId == 10L,
                (nodeId, tunnelId) -> nodeId == 2L && tunnelId == 10L,
                (nodeId, names) -> {
                    deletedServices.addAll(names.toJavaList(String.class));
                    return true;
                },
                (nodeId, name) -> {
                    deletedChains.add(name);
                    return true;
                }
        );

        NodeOrphanSweeper.SweepResult result = sweeper.sweep(2L,
                List.of("10_tls"), List.of("chains_10"));

        assertEquals(0, result.getDeleted(), "中继节点两者皆在用，不得删除");
        assertTrue(deletedServices.isEmpty());
        assertTrue(deletedChains.isEmpty());
    }

    @Test
    void migratedForwardIsSweptOnOldNodeAndRetainedOnNewNode() {
        // Forward 88 原在 Node 1，后迁移至 Node 2
        Set<String> activeAssignments = new HashSet<>();
        activeAssignments.add("2:88"); // (nodeId:forwardId)

        NodeOrphanSweeper sweeper = new NodeOrphanSweeper(
                (nodeId, forwardId) -> activeAssignments.contains(nodeId + ":" + forwardId),
                (nodeId, tunnelId) -> true,
                (nodeId, tunnelId) -> true,
                (nodeId, names) -> {
                    deletedServices.addAll(names.toJavaList(String.class));
                    return true;
                },
                (nodeId, name) -> {
                    deletedChains.add(name);
                    return true;
                }
        );

        // 老节点 Node 1 上线自报
        NodeOrphanSweeper.SweepResult r1 = sweeper.sweep(1L, List.of("88_1_0_tcp"), List.of());
        assertEquals(1, r1.getDeleted(), "老节点上的 88_1_0_tcp 必须被清扫");
        assertEquals(List.of("88_1_0_tcp"), deletedServices);

        deletedServices.clear();

        // 新节点 Node 2 上线自报
        NodeOrphanSweeper.SweepResult r2 = sweeper.sweep(2L, List.of("88_1_0_tcp"), List.of());
        assertEquals(0, r2.getDeleted(), "新节点上的 88_1_0_tcp 属于正常在用服务，不得删除");
        assertTrue(deletedServices.isEmpty());
    }

    @Test
    void removedNodeFromTunnelSweepsAllResources() {
        // Node 4 已被从隧道 10 移除
        NodeOrphanSweeper sweeper = new NodeOrphanSweeper(
                (nodeId, forwardId) -> false,
                (nodeId, tunnelId) -> false,
                (nodeId, tunnelId) -> false,
                (nodeId, names) -> {
                    deletedServices.addAll(names.toJavaList(String.class));
                    return true;
                },
                (nodeId, name) -> {
                    deletedChains.add(name);
                    return true;
                }
        );

        NodeOrphanSweeper.SweepResult result = sweeper.sweep(4L,
                List.of("10_tls", "99_1_0_udp"), List.of("chains_10"));

        assertEquals(3, result.getDeleted(), "非成员节点的所有残留资源必须全量清扫");
        assertEquals(List.of("10_tls", "99_1_0_udp"), deletedServices);
        assertEquals(List.of("chains_10"), deletedChains);
    }

    @Test
    void backwardsCompatibilityConstructorsWork() {
        // 4 参构造向下兼容测试
        NodeOrphanSweeper sweeper4 = new NodeOrphanSweeper(
                id -> id == 10L,
                id -> id == 88L,
                (nodeId, names) -> {
                    deletedServices.addAll(names.toJavaList(String.class));
                    return true;
                },
                (nodeId, name) -> {
                    deletedChains.add(name);
                    return true;
                }
        );

        NodeOrphanSweeper.SweepResult r4 = sweeper4.sweep(1L,
                List.of("10_tls", "99_1_0_tcp"), List.of("chains_10", "chains_99"));
        assertEquals(2, r4.getDeleted());
        assertEquals(List.of("99_1_0_tcp"), deletedServices);
        assertEquals(List.of("chains_99"), deletedChains);

        deletedServices.clear();
        deletedChains.clear();

        // 5 参构造向下兼容测试 (tunnelNeedsChain = false)
        NodeOrphanSweeper sweeper5 = new NodeOrphanSweeper(
                id -> id == 10L,
                id -> id == 88L,
                (nodeId, names) -> {
                    deletedServices.addAll(names.toJavaList(String.class));
                    return true;
                },
                (nodeId, name) -> {
                    deletedChains.add(name);
                    return true;
                },
                id -> false // 直连隧道不需要
        );

        NodeOrphanSweeper.SweepResult r5 = sweeper5.sweep(1L,
                List.of("10_tls", "88_1_0_tcp"), List.of("chains_10"));
        assertEquals(2, r5.getDeleted(), "10_tls 和 chains_10 应当被删，88 转发保留");
        assertEquals(List.of("10_tls"), deletedServices);
        assertEquals(List.of("chains_10"), deletedChains);
    }

    @Test
    void webSocketServerNodeScopedHelperMethodsDirectTest() {
        WebSocketServer server = new WebSocketServer();

        ForwardService forwardService = mock(ForwardService.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);
        TunnelService tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);

        ReflectionTestUtils.setField(server, "forwardService", forwardService);
        ReflectionTestUtils.setField(server, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(server, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(server, "chainTunnelService", chainTunnelService);

        // 1. 测试 isForwardActiveOnNode
        Forward f88 = new Forward();
        f88.setId(88L);
        when(forwardService.getById(88L)).thenReturn(f88);
        when(forwardPortService.count(any())).thenAnswer(inv -> {
            QueryWrapper<ForwardPort> qw = inv.getArgument(0);
            String seg = qw.getExpression().getSqlSegment();
            return (seg != null && seg.contains("2")) ? 1L : 0L;
        });

        // 2. 测试 isTunnelServiceNeededOnNode 与 isTunnelChainNeededOnNode
        Tunnel t10 = new Tunnel();
        t10.setId(10L);
        t10.setType(2); // 中继隧道
        when(tunnelService.getById(10L)).thenReturn(t10);

        // 校验安全降级与异常保护：查库抛异常时必须返回 true (保守保留)
        when(tunnelService.getById(999L)).thenThrow(new RuntimeException("DB glitch"));
        when(forwardService.getById(999L)).thenThrow(new RuntimeException("DB glitch"));
        assertTrue(server.isTunnelServiceNeededOnNode(1L, 999L), "异常时必须保守返回 true");
        assertTrue(server.isTunnelChainNeededOnNode(1L, 999L), "异常时必须保守返回 true");
        assertTrue(server.isForwardActiveOnNode(1L, 999L), "异常时必须保守返回 true");

        // 校验直连隧道 (type=1) 不需要任何中继资源
        Tunnel directTunnel = new Tunnel();
        directTunnel.setId(20L);
        directTunnel.setType(1);
        when(tunnelService.getById(20L)).thenReturn(directTunnel);
        assertFalse(server.isTunnelServiceNeededOnNode(1L, 20L), "直连隧道不需要 _tls");
        assertFalse(server.isTunnelChainNeededOnNode(1L, 20L), "直连隧道不需要 chains_");

        // 校验 Service 为 null 时的降级保护
        WebSocketServer uninitializedServer = new WebSocketServer();
        ReflectionTestUtils.setField(uninitializedServer, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(uninitializedServer, "forwardService", forwardService);
        // chainTunnelService 和 forwardPortService 为 null
        assertTrue(uninitializedServer.isForwardActiveOnNode(1L, 88L), "未注入 forwardPortService 时降级为 true");
        assertTrue(uninitializedServer.isTunnelServiceNeededOnNode(1L, 10L), "未注入 chainTunnelService 时降级为 tunnelNeedsChain");
        assertTrue(uninitializedServer.isTunnelChainNeededOnNode(1L, 10L), "未注入 chainTunnelService 时降级为 tunnelNeedsChain");
    }
}
