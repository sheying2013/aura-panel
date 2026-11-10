package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.TunnelService;
import com.admin.service.impl.NodeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * deleteNode 回归测试。
 *
 * <p>覆盖的原缺陷：
 * 1. 删节点时 {@code tunnelService.deleteTunnel} 的返回值被直接丢弃，节点侧 service/chain
 *    没删掉也照样回「操作成功」，节点记录一删便再也追不到这些端口属于谁；
 * 2. 节点删除后 forward_port / chain_tunnel 的残留行没有清理，getNodePort 只查库，
 *    同一端口段重新加节点时会被这些悬空记录永久占位；
 * 3. validatePortRange 允许端口 0（0 不是可监听端口）。
 */
class NodeDeleteCleanupTest {

    private static final long NODE_ID = 7L;
    private static final long TUNNEL_ID = 9L;

    private NodeServiceImpl nodeService;
    private NodeMapper nodeMapper;
    private TunnelService tunnelService;
    private ChainTunnelService chainTunnelService;
    private ForwardPortService forwardPortService;

    @BeforeEach
    void setUp() {
        nodeService = new NodeServiceImpl();
        nodeMapper = mock(NodeMapper.class);
        tunnelService = mock(TunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        forwardPortService = mock(ForwardPortService.class);

        ReflectionTestUtils.setField(nodeService, "baseMapper", nodeMapper);
        ReflectionTestUtils.setField(nodeService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(nodeService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(nodeService, "forwardPortService", forwardPortService);

        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("aura-sj-node-a");
        when(nodeMapper.selectById(NODE_ID)).thenReturn(node);
        when(nodeMapper.deleteById(NODE_ID)).thenReturn(1);

        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId(TUNNEL_ID);
        chainTunnel.setNodeId(NODE_ID);
        chainTunnel.setChainType(1);
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>(Collections.singletonList(chainTunnel)));
    }

    @Test
    void tunnelCleanupMessagesAreReportedToCaller() {
        when(tunnelService.deleteTunnel(TUNNEL_ID))
                .thenReturn(R.ok(Arrays.asList("节点7 service 9_tls(timeout)", "节点7 chain chains_9(timeout)")));

        R result = nodeService.deleteNode(NODE_ID);

        assertEquals(0, result.getCode());
        List<?> data = (List<?>) result.getData();
        assertEquals(2, data.size());
        assertTrue(((String) data.get(0)).startsWith("隧道9：节点7 service 9_tls"));
        assertTrue(((String) data.get(1)).contains("chains_9"));
    }

    @Test
    void cleanTunnelDeletionReturnsEmptyData() {
        when(tunnelService.deleteTunnel(TUNNEL_ID)).thenReturn(R.ok());

        R result = nodeService.deleteNode(NODE_ID);

        assertEquals(0, result.getCode());
        assertNull(result.getData());
    }

    @Test
    void failedTunnelDeletionIsReportedButNodeStillRemoved() {
        when(tunnelService.deleteTunnel(TUNNEL_ID)).thenReturn(R.err("隧道不存在"));

        R result = nodeService.deleteNode(NODE_ID);

        List<?> data = (List<?>) result.getData();
        assertEquals(1, data.size());
        assertEquals("隧道9删除失败：隧道不存在", data.get(0));
        verify(nodeMapper).deleteById(NODE_ID);
    }

    @Test
    void danglingPortAndChainRowsAreCleared() {
        when(tunnelService.deleteTunnel(TUNNEL_ID)).thenReturn(R.ok());

        nodeService.deleteNode(NODE_ID);

        verify(forwardPortService).remove(any());
        verify(chainTunnelService).remove(any());
        verify(nodeMapper).deleteById(NODE_ID);
    }

    @Test
    void storeFailuresDuringCleanupAreReportedInsteadOfThrown() {
        when(tunnelService.deleteTunnel(TUNNEL_ID)).thenReturn(R.ok());
        when(forwardPortService.remove(any())).thenThrow(new RuntimeException("db locked"));

        R result = nodeService.deleteNode(NODE_ID);

        List<?> data = (List<?>) result.getData();
        assertEquals(1, data.size());
        assertTrue(((String) data.get(0)).contains("端口占用记录清理失败：db locked"));
        verify(nodeMapper).deleteById(NODE_ID);
    }

    @Test
    void portZeroIsRejected() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(nodeService, "validatePortRange", "0-100"));
        assertEquals("可用端口不合法", e.getMessage());
    }

    @Test
    void multiHopChainTunnelsForSameTunnelOnlyTriggerSingleDeletion() {
        ChainTunnel hop1 = new ChainTunnel();
        hop1.setTunnelId(TUNNEL_ID);
        hop1.setNodeId(NODE_ID);
        hop1.setChainType(1);

        ChainTunnel hop2 = new ChainTunnel();
        hop2.setTunnelId(TUNNEL_ID);
        hop2.setNodeId(NODE_ID);
        hop2.setChainType(2);

        when(chainTunnelService.list(any())).thenReturn(Arrays.asList(hop1, hop2));
        when(tunnelService.deleteTunnel(TUNNEL_ID)).thenReturn(R.ok());

        R result = nodeService.deleteNode(NODE_ID);

        assertEquals(0, result.getCode());
        verify(tunnelService, org.mockito.Mockito.times(1)).deleteTunnel(TUNNEL_ID);
        verify(nodeMapper).deleteById(NODE_ID);
    }
}
