package com.admin.compat;

import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.admin.service.impl.NodeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷5：新建节点不传 tcpListenAddr/udpListenAddr 时，落库必须是 0.0.0.0。
 *
 * <p>真实根因链（不是 DTO 未生效）：createNode 从不给这两个字段赋值 → 实体字段为 null →
 * MyBatis-Plus 3.4.1 默认 insert 策略 NOT_NULL 跳过该列 → MySQL 套用 schema.sql:41-42 的列默认值
 * `DEFAULT '[::]'`。所以断言点在「交给 mapper.insert 的实体」上，而不是 DTO 上的初始化值。</p>
 *
 * <p>update 路径的反例：未传监听地址时不能把已有值（可能是 "::"）打回默认值，
 * 这里通过「updateById 收到的实体该字段为 null（NOT_NULL 策略会跳过该列）」来表达。</p>
 */
class NodeListenAddrTest {

    private static final String DEFAULT_ADDR = "0.0.0.0";

    private NodeMapper nodeMapper;
    private NodeServiceImpl nodeService;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(NodeMapper.class);
        nodeService = new NodeServiceImpl();
        ReflectionTestUtils.setField(nodeService, "baseMapper", nodeMapper);
    }

    private NodeDto newNodeDto() {
        NodeDto dto = new NodeDto();
        dto.setName("beta-node-1");
        dto.setServerIp("127.0.0.1");
        dto.setPort("30001");
        dto.setInterfaceName("eth0");
        return dto;
    }

    private Node insertCapturedNode() {
        ArgumentCaptor<Node> captor = ArgumentCaptor.forClass(Node.class);
        verify(nodeMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("create 未传监听地址 → 落库 0.0.0.0（不再依赖 DB 列默认值 [::]）")
    void createWithoutListenAddrFallsBackToIpv4Wildcard() {
        R result = nodeService.createNode(newNodeDto());

        assertEquals(0, result.getCode(), "create 应返回成功");
        Node inserted = insertCapturedNode();
        assertEquals(DEFAULT_ADDR, inserted.getTcpListenAddr());
        assertEquals(DEFAULT_ADDR, inserted.getUdpListenAddr());
    }

    @Test
    @DisplayName("create 传空白监听地址（前端 '' / '  '）同样兜底 0.0.0.0")
    void createWithBlankListenAddrFallsBackToIpv4Wildcard() {
        NodeDto dto = newNodeDto();
        dto.setTcpListenAddr("   ");
        dto.setUdpListenAddr("");

        nodeService.createNode(dto);

        Node inserted = insertCapturedNode();
        assertEquals(DEFAULT_ADDR, inserted.getTcpListenAddr());
        assertEquals(DEFAULT_ADDR, inserted.getUdpListenAddr());
    }

    @Test
    @DisplayName("create 显式传值（[::] / :: / 具体 IP）必须原样保留，不被兜底覆盖")
    void createKeepsExplicitListenAddr() {
        NodeDto dto = newNodeDto();
        dto.setTcpListenAddr("[::]");
        dto.setUdpListenAddr("192.168.1.9");

        nodeService.createNode(dto);

        Node inserted = insertCapturedNode();
        assertEquals("[::]", inserted.getTcpListenAddr(), "显式 [::] 是双栈通配，不能被改成 0.0.0.0");
        assertEquals("192.168.1.9", inserted.getUdpListenAddr());
    }

    @Test
    @DisplayName("DTO 不再自带 0.0.0.0 初始化值（否则「未传」与「显式传」不可区分）")
    void dtosLeaveListenAddrNullByDefault() {
        assertNull(new NodeDto().getTcpListenAddr());
        assertNull(new NodeDto().getUdpListenAddr());
        assertNull(new NodeUpdateDto().getTcpListenAddr());
        assertNull(new NodeUpdateDto().getUdpListenAddr());
    }

    private NodeUpdateDto newNodeUpdateDto() {
        NodeUpdateDto dto = new NodeUpdateDto();
        dto.setId(101L);
        dto.setName("beta-node-1-renamed");
        dto.setServerIp("127.0.0.1");
        dto.setPort("30001");
        dto.setInterfaceName("eth0");
        return dto;
    }

    private Node existingNode() {
        Node node = new Node();
        node.setId(101L);
        node.setName("beta-node-1");
        node.setServerIp("127.0.0.1");
        node.setPort("30001");
        node.setStatus(0);
        node.setTcpListenAddr("[::]");
        node.setUdpListenAddr("[::]");
        return node;
    }

    @Test
    @DisplayName("update 未传监听地址 → 该字段保持 null，交给 NOT_NULL 策略跳过列，已有 [::] 不被改")
    void updateWithoutListenAddrSkipsColumn() {
        Node existing = existingNode();
        when(nodeMapper.selectById(101L)).thenReturn(existing);
        when(nodeMapper.updateById(any(Node.class))).thenReturn(1);

        R result = nodeService.updateNode(newNodeUpdateDto());

        assertEquals(0, result.getCode(), "只改名字应成功");
        ArgumentCaptor<Node> captor = ArgumentCaptor.forClass(Node.class);
        verify(nodeMapper).updateById(captor.capture());
        Node updated = captor.getValue();
        assertNull(updated.getTcpListenAddr(), "未传监听地址时不能写入默认值，否则会把已有 [::] 打回 0.0.0.0");
        assertNull(updated.getUdpListenAddr());
        assertEquals("beta-node-1-renamed", updated.getName());
    }

    @Test
    @DisplayName("update 显式传监听地址 → 原样写入")
    void updateWritesExplicitListenAddr() {
        when(nodeMapper.selectById(101L)).thenReturn(existingNode());
        when(nodeMapper.updateById(any(Node.class))).thenReturn(1);

        NodeUpdateDto dto = newNodeUpdateDto();
        dto.setTcpListenAddr(DEFAULT_ADDR);
        dto.setUdpListenAddr("::");

        nodeService.updateNode(dto);

        ArgumentCaptor<Node> captor = ArgumentCaptor.forClass(Node.class);
        verify(nodeMapper).updateById(captor.capture());
        assertEquals(DEFAULT_ADDR, captor.getValue().getTcpListenAddr());
        assertEquals("::", captor.getValue().getUdpListenAddr());
    }

    @Test
    @DisplayName("update 不存在的节点 → 直接报错，不写库")
    void updateMissingNodeDoesNotWrite() {
        when(nodeMapper.selectById(101L)).thenReturn(null);

        R result = nodeService.updateNode(newNodeUpdateDto());

        assertEquals(-1, result.getCode());
        assertEquals("节点不存在", result.getMsg());
        verify(nodeMapper, never()).updateById(any(Node.class));
    }
}
