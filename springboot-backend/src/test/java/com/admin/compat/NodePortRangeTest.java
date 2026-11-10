package com.admin.compat;

import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.ForwardPort;
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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * updateNode 端口段变更回归测试。
 *
 * <p>覆盖的原缺陷：改节点端口段时只校验格式，直接 updateById。
 * 既有转发端口、中继端口被留在新段之外后，getNodePort 只按新段列空闲端口，
 * 这些端口既不会再被分配也不会被回收——库里的占用记录与节点上的真实监听永久脱节，
 * 面板显示一切正常。
 */
class NodePortRangeTest {

    private static final long NODE_ID = 8L;

    private NodeServiceImpl nodeService;
    private NodeMapper nodeMapper;
    private ChainTunnelService chainTunnelService;
    private ForwardPortService forwardPortService;

    @BeforeEach
    void setUp() {
        nodeService = new NodeServiceImpl();
        nodeMapper = mock(NodeMapper.class);
        chainTunnelService = mock(ChainTunnelService.class);
        forwardPortService = mock(ForwardPortService.class);
        ReflectionTestUtils.setField(nodeService, "baseMapper", nodeMapper);
        ReflectionTestUtils.setField(nodeService, "tunnelService", mock(TunnelService.class));
        ReflectionTestUtils.setField(nodeService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(nodeService, "forwardPortService", forwardPortService);

        Node node = new Node();
        node.setId(NODE_ID);
        node.setName("aura-sj-node-a");
        node.setServerIp("172.18.0.4");
        node.setPort("30100-30120");
        when(nodeMapper.selectById(NODE_ID)).thenReturn(node);
        when(nodeMapper.updateById(any())).thenReturn(1);
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>());
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
    }

    private NodeUpdateDto dto(String port) {
        NodeUpdateDto dto = new NodeUpdateDto();
        dto.setId(NODE_ID);
        dto.setName("aura-sj-node-a");
        dto.setServerIp("172.18.0.4");
        dto.setPort(port);
        return dto;
    }

    private static ForwardPort forwardPort(int port) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(1L);
        forwardPort.setNodeId(NODE_ID);
        forwardPort.setPort(port);
        return forwardPort;
    }

    @Test
    void shrinkingRangeThatDropsAllocatedForwardPortIsRejected() {
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>(List.of(forwardPort(30107))));

        R result = nodeService.updateNode(dto("30110-30120"));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("30107"));
        assertTrue(result.getMsg().startsWith("端口段不包含已分配的端口"));
    }

    @Test
    void allocatedPortsOutsideNewRangeAreAllListed() {
        when(forwardPortService.list(any()))
                .thenReturn(new ArrayList<>(List.of(forwardPort(30107), forwardPort(30101))));

        R result = nodeService.updateNode(dto("30110-30120"));

        assertEquals("端口段不包含已分配的端口：30101，30107", result.getMsg());
    }

    @Test
    void chainRelayPortOutsideNewRangeIsRejectedToo() {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId(9L);
        chainTunnel.setNodeId(NODE_ID);
        chainTunnel.setPort(30102);
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>(Collections.singletonList(chainTunnel)));

        R result = nodeService.updateNode(dto("30110-30120"));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("30102"));
    }

    @Test
    void wideningRangeIsAllowed() {
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>(List.of(forwardPort(30107))));

        R result = nodeService.updateNode(dto("30100-30150"));

        assertEquals(0, result.getCode());
    }

    @Test
    void unchangedRangeIsAllowedWithoutTouchingAllocations() {
        R result = nodeService.updateNode(dto("30100-30120"));

        assertEquals(0, result.getCode());
    }

    @Test
    void outOfRangeDirtyAllocationDoesNotLockThePortRange() {
        // 历史脏行（chain_tunnel port=70000）：永远不可能落进任何新端口段。
        // 计进占用就会让这台节点的端口段永久锁死，改哪儿都报「不包含已分配的端口：70000」。
        ChainTunnel dirty = new ChainTunnel();
        dirty.setTunnelId(19L);
        dirty.setNodeId(NODE_ID);
        dirty.setPort(70000);
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>(Collections.singletonList(dirty)));

        R result = nodeService.updateNode(dto("30500-30510"));

        assertEquals(0, result.getCode(), String.valueOf(result.getMsg()));
    }

    @Test
    void dirtyAllocationIsIgnoredWhileValidOnesAreStillEnforced() {
        ChainTunnel dirty = new ChainTunnel();
        dirty.setTunnelId(19L);
        dirty.setNodeId(NODE_ID);
        dirty.setPort(70000);
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>(Collections.singletonList(dirty)));
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>(List.of(forwardPort(30107))));

        R result = nodeService.updateNode(dto("30500-30510"));

        assertEquals("端口段不包含已分配的端口：30107", result.getMsg());
    }
}
