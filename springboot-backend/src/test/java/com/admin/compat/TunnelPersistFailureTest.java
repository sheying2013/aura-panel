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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createTunnel 的数据库写入失败路径：this.save 与 chainTunnelService.saveBatch 返回 false 或抛异常时，
 * 不允许带着空隧道 id 继续下发节点，也不允许返回成功。
 *
 * <p>隧道记录已落库、节点记录没落库时必须清理隧道记录；清理失败或无法确认要显式报告。
 * 这里只做尽力补偿，不声称数据库与节点之间有原子事务。
 */
class TunnelPersistFailureTest {

    private static final long TUNNEL_ID = 7L;

    private final Map<Long, Node> nodes = new LinkedHashMap<>();
    private PersistFailingTunnelService service;
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

        service = new PersistFailingTunnelService();
        ReflectionTestUtils.setField(service, "nodeService", nodeService);
        ReflectionTestUtils.setField(service, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(service, "forwardPortService", forwardPortService);
    }

    @Test
    void tunnelRecordSaveFalseBlocksPublishAndFails() {
        useNodes(1L, 2L);
        service.saveResult = false;

        R result = service.createTunnel(tunnelDto());

        assertEquals(-1, result.getCode(), "隧道记录没落库时必须返回错误，不能返回成功");
        assertTrue(result.getMsg().contains("隧道记录保存失败"), result.getMsg());
        assertTrue(result.getMsg().contains("未下发任何节点"), result.getMsg());
        assertEquals(List.of(), service.log, "隧道记录没落库时不得下发任何节点资源");
        assertEquals(List.of(), service.removedTunnelIds, "没落库就没有可清理的记录");
        verify(chainTunnelService, never()).saveBatch(any());
        verify(chainTunnelService, never()).remove(any());
    }

    @Test
    void tunnelRecordSaveExceptionBlocksPublishAndFails() {
        useNodes(1L, 2L);
        service.saveException = new RuntimeException("disk full");

        R result = service.createTunnel(tunnelDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("隧道记录保存异常"), result.getMsg());
        assertTrue(result.getMsg().contains("disk full"), "异常原因要透出，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("未下发任何节点"), result.getMsg());
        assertEquals(List.of(), service.log);
        verify(chainTunnelService, never()).saveBatch(any());
    }

    @Test
    void chainRecordSaveFalseCleansTunnelAndBlocksPublish() {
        useNodes(1L, 2L);
        when(chainTunnelService.saveBatch(any())).thenReturn(false);

        R result = service.createTunnel(tunnelDto());

        assertEquals(-1, result.getCode(), "节点记录没落库时必须返回错误");
        assertTrue(result.getMsg().contains("隧道节点记录保存失败"), result.getMsg());
        assertTrue(result.getMsg().contains("未下发任何节点"), result.getMsg());
        assertEquals(List.of(), service.log, "节点记录没落库时不得下发任何节点资源");
        assertEquals(List.of(TUNNEL_ID), service.removedTunnelIds, "已落库的隧道记录必须清理");
        verify(chainTunnelService).remove(any());
        assertFalse(result.getMsg().contains("记录清理未完成"),
                "清理成功时不得报清理失败，实际：" + result.getMsg());
    }

    @Test
    void chainRecordSaveExceptionCleansTunnelAndBlocksPublish() {
        useNodes(1L, 2L);
        when(chainTunnelService.saveBatch(any())).thenThrow(new RuntimeException("db locked"));

        R result = service.createTunnel(tunnelDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("隧道节点记录保存异常"), result.getMsg());
        assertTrue(result.getMsg().contains("db locked"), result.getMsg());
        assertEquals(List.of(), service.log);
        assertEquals(List.of(TUNNEL_ID), service.removedTunnelIds);
        verify(chainTunnelService).remove(any());
    }

    @Test
    void chainRecordSaveFalseReportsRecordCleanupFailure() {
        useNodes(1L, 2L);
        when(chainTunnelService.saveBatch(any())).thenReturn(false);
        service.removeResult = false;

        R result = service.createTunnel(tunnelDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("记录清理未完成"), result.getMsg());
        assertTrue(result.getMsg().contains("隧道记录"), result.getMsg());
        assertTrue(result.getMsg().contains("未确认已清理"), result.getMsg());
        assertEquals(List.of(), service.log);
    }

    @Test
    void chainRecordSaveFalseReportsRecordCleanupException() {
        useNodes(1L, 2L);
        when(chainTunnelService.saveBatch(any())).thenReturn(false);
        service.removeException = new RuntimeException("db locked");

        R result = service.createTunnel(tunnelDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("记录清理未完成"), result.getMsg());
        assertTrue(result.getMsg().contains("隧道记录 id=" + TUNNEL_ID + " 清理失败"), result.getMsg());
        assertTrue(result.getMsg().contains("db locked"), result.getMsg());
        assertEquals(List.of(), service.log);
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

    private static TunnelDto tunnelDto() {
        TunnelDto tunnelDto = new TunnelDto();
        tunnelDto.setName("tunnel-" + TUNNEL_ID);
        tunnelDto.setType(2);
        tunnelDto.setFlow(1);
        tunnelDto.setTrafficRatio(BigDecimal.ONE);
        tunnelDto.setInNodeId(List.of(chainTunnel(1L, 1)));
        tunnelDto.setChainNodes(new ArrayList<>());
        tunnelDto.setOutNodeId(List.of(chainTunnel(2L, 3)));
        return tunnelDto;
    }

    /**
     * 替身：数据库写入结果与节点下发结果都由测试指定，覆盖发送出口避免真实网络。
     */
    static class PersistFailingTunnelService extends TunnelServiceImpl {

        final List<String> log = new ArrayList<>();
        final List<Long> removedTunnelIds = new ArrayList<>();
        boolean saveResult = true;
        RuntimeException saveException;
        boolean removeResult = true;
        RuntimeException removeException;

        @Override
        public int count(Wrapper<Tunnel> queryWrapper) {
            return 0;
        }

        @Override
        public boolean save(Tunnel entity) {
            if (saveException != null) {
                throw saveException;
            }
            if (saveResult) {
                entity.setId(TUNNEL_ID);
            }
            return saveResult;
        }

        @Override
        public boolean removeById(Serializable id) {
            removedTunnelIds.add((Long) id);
            if (removeException != null) {
                throw removeException;
            }
            return removeResult;
        }

        @Override
        protected GostDto createChains(Long nodeId, List<ChainTunnel> chainTunnels, Map<Long, Node> nodes, Tunnel tunnel) {
            return reply("chains:" + nodeId, "chains_" + chainTunnels.get(0).getTunnelId());
        }

        @Override
        protected GostDto createChainService(Long nodeId, ChainTunnel chainTunnel, Map<Long, Node> nodes, Tunnel tunnel) {
            return reply("service:" + nodeId, chainTunnel.getTunnelId() + "_tls");
        }

        @Override
        protected GostDto deleteChains(Long nodeId, String name) {
            return reply("delete-chains:" + nodeId, name);
        }

        @Override
        protected GostDto deleteService(Long nodeId, String name) {
            return reply("delete-service:" + nodeId, name);
        }

        private GostDto reply(String key, String name) {
            log.add(key + "|" + name);
            GostDto gostDto = new GostDto();
            gostDto.setMsg("OK");
            return gostDto;
        }
    }
}
