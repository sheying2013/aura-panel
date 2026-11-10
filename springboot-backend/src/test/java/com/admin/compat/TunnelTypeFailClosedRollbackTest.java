package com.admin.compat;

import com.admin.common.dto.ForwardDto;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * tunnel.type 非法时 createForward 必须回滚已落库记录（round44）。
 *
 * <p>原缺陷（真机 type=9 实测）：GostUtil 在构建服务时抛错，异常直接冒到全局处理器，
 * 但 forward 与 forward_port 已经落库 —— 面板多出一条"正常"转发，节点上没有任何服务，
 * 该行还会长期占用自动分配到的端口。这里把"抛错必须等价于下发失败"钉住。
 */
class TunnelTypeFailClosedRollbackTest {

    private static final long FORWARD_ID = 7L;
    private static final int TUNNEL_ID = 27;
    private static final int USER_ID = 1;

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private TunnelService tunnelService;

    @BeforeEach
    void setUp() {
        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        when(forwardMapper.insert(any(Forward.class))).thenAnswer(invocation -> {
            ((Forward) invocation.getArgument(0)).setId(FORWARD_ID);
            return 1;
        });
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(chainTunnelService.list(any())).thenReturn(List.of(entryRow()));
        when(nodeService.getById(any())).thenReturn(node());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("tunnel.type=9：拒绝下发并删除刚写入的转发与端口记录")
    void invalidTunnelTypeRollsBackCreatedRows() {
        authenticate();
        when(tunnelService.getById(any())).thenReturn(tunnel(9));

        R result = forwardService.createForward(dto());

        assertEquals(-1, result.getCode(), "非法隧道类型必须创建失败，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("隧道类型不合法"), "实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("拒绝下发"), "实际：" + result.getMsg());
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
    }

    @Test
    @DisplayName("tunnel.type=null：同样拒绝下发并回滚，不按直连猜")
    void nullTunnelTypeRollsBackCreatedRows() {
        authenticate();
        when(tunnelService.getById(any())).thenReturn(tunnel(null));

        R result = forwardService.createForward(dto());

        assertEquals(-1, result.getCode(), "type 为 null 必须创建失败，实际：" + result.getMsg());
        assertTrue(result.getMsg().contains("tunnel.type=null"), "实际：" + result.getMsg());
        verify(forwardMapper).deleteById(FORWARD_ID);
        verify(forwardPortService).remove(any());
    }

    private void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ForwardDto dto() {
        ForwardDto dto = new ForwardDto();
        dto.setName("type-guard");
        dto.setTunnelId(TUNNEL_ID);
        dto.setRemoteAddr("backend:6365");
        dto.setStrategy("round");
        return dto;
    }

    private Tunnel tunnel(Integer type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(type);
        tunnel.setStatus(1);
        return tunnel;
    }

    private ChainTunnel entryRow() {
        ChainTunnel row = new ChainTunnel();
        row.setId(96L);
        row.setTunnelId((long) TUNNEL_ID);
        row.setNodeId(7L);
        row.setChainType(1);
        row.setPort(30413);
        row.setProtocol("tcp");
        return row;
    }

    private Node node() {
        Node node = new Node();
        node.setId(7L);
        node.setName("node-7");
        node.setServerIp("172.18.0.7");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setPort("30400-30420");
        return node;
    }
}