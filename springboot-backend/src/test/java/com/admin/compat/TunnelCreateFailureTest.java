package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.NodeService;
import com.admin.service.impl.TunnelServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 隧道创建下沉（chains / service）失败的回归测试：
 * 无中间跳时失败不能被吞掉，多入口先成功后失败要回滚已建资源并给出明确反馈；
 * 节点返回 exists 时视为冲突而不是创建成功，既不删既存资源，也不声称其配置一致。
 */
class TunnelCreateFailureTest {

    private static final long TUNNEL_ID = 7L;

    private final Map<Long, Node> nodes = new LinkedHashMap<>();
    private RecordingTunnelService service;
    private ChainTunnelService chainTunnelService;

    @BeforeEach
    void setUp() {
        NodeService nodeService = mock(NodeService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        ForwardPortService forwardPortService = mock(ForwardPortService.class);

        when(nodeService.getById(any())).thenAnswer(invocation -> nodes.get((Long) invocation.getArgument(0)));
        when(nodeService.list(any())).thenAnswer(invocation -> new ArrayList<>(nodes.values()));
        when(chainTunnelService.list(any())).thenReturn(Collections.emptyList());
        when(chainTunnelService.saveBatch(any())).thenReturn(true);
        when(chainTunnelService.remove(any())).thenReturn(true);
        when(forwardPortService.list(any())).thenReturn(Collections.emptyList());

        service = new RecordingTunnelService();
        ReflectionTestUtils.setField(service, "nodeService", nodeService);
        ReflectionTestUtils.setField(service, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(service, "forwardPortService", forwardPortService);
    }

    @Test
    void entranceChainFailureWithoutIntermediateHopIsReported() {
        // 无中间跳：入口直连出口，入口 AddChains 失败曾因空 isError() 被吞掉，隧道记录残留
        useNodes(1L, 2L);
        service.failAdd("chains:1", "节点不在线");

        R result = service.createTunnel(tunnelDto(List.of(1L), List.of(), List.of(2L)));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点不在线"), result.getMsg());
        assertTrue(result.getMsg().contains("节点1"), result.getMsg());
        assertEquals(List.of("chains:1|chains_" + TUNNEL_ID), service.log);
        assertEquals(List.of(TUNNEL_ID), service.removedTunnelIds);
        verify(chainTunnelService).remove(any());
    }

    @Test
    void failClosedGuardExceptionDuringPublishCleansRecordsInsteadOfEscaping() {
        // Fail-Closed 守卫（或任何未预期运行时异常）在 publishChains 里抛出时，
        // 隧道行与节点行已经落库：既不能冒到 GlobalExceptionHandler 变成 500，
        // 也不能把悬空记录留在库里永久占端口段（round45 QA P0-1）。
        useNodes(1L, 2L);
        service.throwOnChainPublish = new IllegalStateException(
                "隧道类型不合法（tunnel.type=1），已拒绝下发chains_" + TUNNEL_ID + "：直连隧道没有下一跳");

        R result = assertDoesNotThrow(() -> service.createTunnel(tunnelDto(List.of(1L), List.of(), List.of(2L))));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("隧道创建失败"), result.getMsg());
        assertTrue(result.getMsg().contains("隧道类型不合法"), result.getMsg());
        assertTrue(result.getMsg().contains("已中止下发"), result.getMsg());
        assertEquals(List.of(TUNNEL_ID), service.removedTunnelIds, "隧道行必须被清理");
        verify(chainTunnelService).remove(any());
    }

    @Test
    void outNodeServiceFailureRollsBackEntranceChain() {
        // 单节点失败：入口成功、出口 service 失败，成功目标要回滚，失败目标不删
        useNodes(1L, 2L);
        service.failAdd("service:2", "节点连接已断开");

        R result = service.createTunnel(tunnelDto(List.of(1L), List.of(), List.of(2L)));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点连接已断开"), result.getMsg());
        assertEquals(List.of(
                "chains:1|chains_" + TUNNEL_ID,
                "service:2|" + TUNNEL_ID + "_tls",
                "delete-chains:1|chains_" + TUNNEL_ID
        ), service.log);
        assertFalse(result.getMsg().contains("回滚未完成"), result.getMsg());
        assertEquals(List.of(TUNNEL_ID), service.removedTunnelIds);
        verify(chainTunnelService).remove(any());
    }

    @Test
    void secondEntranceFailureRollsBackFirstEntrance() {
        // 多入口先成功后失败：只回滚已确认创建成功的入口
        useNodes(1L, 2L, 3L);
        service.failAdd("chains:3", "等待响应超时");

        R result = service.createTunnel(tunnelDto(List.of(1L, 3L), List.of(), List.of(2L)));

        assertEquals(-1, result.getCode());
        assertEquals(List.of(
                "chains:1|chains_" + TUNNEL_ID,
                "chains:3|chains_" + TUNNEL_ID,
                "delete-chains:1|chains_" + TUNNEL_ID
        ), service.log);
        assertTrue(result.getMsg().contains("节点3"), result.getMsg());
        assertFalse(result.getMsg().contains("回滚未完成"), result.getMsg());
    }

    @Test
    void rollbackFailureIsReportedExplicitly() {
        // 回滚本身失败：不能假装清理干净，必须把残留目标写进错误信息
        useNodes(1L, 2L, 3L);
        service.failAdd("chains:3", "节点不在线");
        service.failDelete("delete-chains:1", "等待响应超时");

        R result = service.createTunnel(tunnelDto(List.of(1L, 3L), List.of(), List.of(2L)));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("回滚未完成"), result.getMsg());
        assertTrue(result.getMsg().contains("节点1 chain chains_" + TUNNEL_ID), result.getMsg());
        assertTrue(result.getMsg().contains("等待响应超时"), result.getMsg());
    }

    @Test
    void chainHopFailureRollsBackChainsAndServicesCreatedBefore() {
        // 中间跳失败：之前建的 service 与 chain 全部回滚，service 先于 chain 删除
        useNodes(1L, 2L, 3L, 4L);
        service.failAdd("service:4", "发送消息失败");

        R result = service.createTunnel(tunnelDto(List.of(1L), List.of(List.of(3L), List.of(4L)), List.of(2L)));

        assertEquals(-1, result.getCode());
        assertEquals(List.of(
                "chains:1|chains_" + TUNNEL_ID,
                "chains:3|chains_" + TUNNEL_ID,
                "service:3|" + TUNNEL_ID + "_tls",
                "chains:4|chains_" + TUNNEL_ID,
                "service:4|" + TUNNEL_ID + "_tls",
                "delete-service:3|" + TUNNEL_ID + "_tls",
                "delete-chains:1|chains_" + TUNNEL_ID,
                "delete-chains:3|chains_" + TUNNEL_ID,
                "delete-chains:4|chains_" + TUNNEL_ID
        ), service.log);
        assertEquals(List.of(TUNNEL_ID), service.removedTunnelIds);
    }

    @Test
    void successfulCreateDeletesNothing() {
        // 全成功不触发任何回滚，隧道记录保留
        useNodes(1L, 2L, 3L, 4L);

        R result = service.createTunnel(tunnelDto(List.of(1L), List.of(List.of(3L), List.of(4L)), List.of(2L)));

        assertEquals(0, result.getCode(), result.getMsg());
        assertEquals(List.of(
                "chains:1|chains_" + TUNNEL_ID,
                "chains:3|chains_" + TUNNEL_ID,
                "service:3|" + TUNNEL_ID + "_tls",
                "chains:4|chains_" + TUNNEL_ID,
                "service:4|" + TUNNEL_ID + "_tls",
                "service:2|" + TUNNEL_ID + "_tls"
        ), service.log);
        assertTrue(service.removedTunnelIds.isEmpty());
        verify(chainTunnelService, never()).remove(any());
    }

    @Test
    void existingChainOnSecondEntranceIsConflictAndNotDeleted() {
        // 已有 Add OK 后遇到 exists：只回滚本次确认新建的入口，冲突目标既不认领也不删除
        useNodes(1L, 2L, 3L);
        service.conflictAdd("chains:3", "chains_" + TUNNEL_ID);

        R result = service.createTunnel(tunnelDto(List.of(1L, 3L), List.of(), List.of(2L)));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("已存在同名资源"), result.getMsg());
        assertTrue(result.getMsg().contains("节点3"), result.getMsg());
        assertFalse(result.getMsg().contains("回滚未完成"), result.getMsg());
        assertEquals(List.of(
                "chains:1|chains_" + TUNNEL_ID,
                "chains:3|chains_" + TUNNEL_ID,
                "delete-chains:1|chains_" + TUNNEL_ID
        ), service.log);
        assertEquals(List.of(TUNNEL_ID), service.removedTunnelIds);
    }

    @Test
    void existingServiceOnOutNodeIsConflictAndNotDeleted() {
        // 新建 chain 成功、出口 service 已存在：只删本次新建的 chain，既存 service 不动
        useNodes(1L, 2L);
        service.conflictAdd("service:2", TUNNEL_ID + "_tls");

        R result = service.createTunnel(tunnelDto(List.of(1L), List.of(), List.of(2L)));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("已存在同名资源"), result.getMsg());
        assertTrue(result.getMsg().contains("节点2"), result.getMsg());
        assertFalse(result.getMsg().contains("回滚未完成"), result.getMsg());
        assertEquals(List.of(
                "chains:1|chains_" + TUNNEL_ID,
                "service:2|" + TUNNEL_ID + "_tls",
                "delete-chains:1|chains_" + TUNNEL_ID
        ), service.log);
        assertEquals(List.of(TUNNEL_ID), service.removedTunnelIds);
    }

    @Test
    void timeoutFailureIsReportedAsUnconfirmedNotAsConflict() {
        // 超时：状态未知，既不能当成功，也不能当 exists 冲突，失败目标不删
        useNodes(1L, 2L);
        service.failAdd("service:2", "等待响应超时");

        R result = service.createTunnel(tunnelDto(List.of(1L), List.of(), List.of(2L)));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("下发结果未确认"), result.getMsg());
        assertFalse(result.getMsg().contains("已存在同名资源"), result.getMsg());
        assertEquals(List.of(
                "chains:1|chains_" + TUNNEL_ID,
                "service:2|" + TUNNEL_ID + "_tls",
                "delete-chains:1|chains_" + TUNNEL_ID
        ), service.log);
    }

    private void useNodes(Long... nodeIds) {
        for (Long nodeId : nodeIds) {
            nodes.put(nodeId, node(nodeId));
        }
    }

    private static Node node(Long nodeId) {
        Node node = new Node();
        node.setId(nodeId);
        node.setName("node-" + nodeId);
        node.setServerIp("10.0.0." + nodeId);
        node.setPort("20000-20005");
        node.setTcpListenAddr("0.0.0.0");
        node.setStatus(1);
        return node;
    }

    private static ChainTunnel chainTunnel(Long nodeId, int chainType) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setNodeId(nodeId);
        chainTunnel.setChainType(chainType);
        chainTunnel.setProtocol("tcp");
        chainTunnel.setStrategy("round");
        return chainTunnel;
    }

    private static TunnelDto tunnelDto(List<Long> inNodeIds, List<List<Long>> chainNodeIds, List<Long> outNodeIds) {
        TunnelDto tunnelDto = new TunnelDto();
        tunnelDto.setName("tunnel-" + TUNNEL_ID);
        tunnelDto.setType(2);
        tunnelDto.setFlow(1);
        tunnelDto.setTrafficRatio(BigDecimal.ONE);
        tunnelDto.setInNodeId(inNodeIds.stream().map(nodeId -> chainTunnel(nodeId, 1)).collect(Collectors.toList()));
        tunnelDto.setChainNodes(chainNodeIds.stream()
                .map(hop -> hop.stream().map(nodeId -> chainTunnel(nodeId, 2)).collect(Collectors.toList()))
                .collect(Collectors.toList()));
        tunnelDto.setOutNodeId(outNodeIds.stream().map(nodeId -> chainTunnel(nodeId, 3)).collect(Collectors.toList()));
        return tunnelDto;
    }

    /**
     * 替身：GostUtil 是静态方法，离线环境没有 mockito-inline，改为覆盖下发出口来模拟节点响应。
     */
    static class RecordingTunnelService extends TunnelServiceImpl {

        final List<String> log = new ArrayList<>();
        final List<Long> removedTunnelIds = new ArrayList<>();
        private final Map<String, String> addFailures = new HashMap<>();
        /** 非 null 时在下发链的接缝直接抛出，模拟 Fail-Closed 守卫或未预期运行时异常。 */
        private RuntimeException throwOnChainPublish;
        private final Map<String, String> deleteFailures = new HashMap<>();

        void failAdd(String key, String msg) {
            addFailures.put(key, msg);
        }

        void failDelete(String key, String msg) {
            deleteFailures.put(key, msg);
        }

        // 节点对同名资源回 exists，按真实返回原样透传
        void conflictAdd(String key, String name) {
            addFailures.put(key, "1 error(s) occurred: " + name + " exists");
        }

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
            if (throwOnChainPublish != null) {
                throw throwOnChainPublish;
            }
            return reply("chains:" + nodeId, "chains_" + chainTunnels.get(0).getTunnelId(), addFailures);
        }

        @Override
        protected GostDto createChainService(Long nodeId, ChainTunnel chainTunnel, Map<Long, Node> nodes, Tunnel tunnel) {
            return reply("service:" + nodeId, chainTunnel.getTunnelId() + "_tls", addFailures);
        }

        @Override
        protected GostDto deleteChains(Long nodeId, String name) {
            return reply("delete-chains:" + nodeId, name, deleteFailures);
        }

        @Override
        protected GostDto deleteService(Long nodeId, String name) {
            return reply("delete-service:" + nodeId, name, deleteFailures);
        }

        private GostDto reply(String key, String name, Map<String, String> failures) {
            log.add(key + "|" + name);
            GostDto gostDto = new GostDto();
            gostDto.setMsg(failures.getOrDefault(key, "OK"));
            return gostDto;
        }
    }
}
