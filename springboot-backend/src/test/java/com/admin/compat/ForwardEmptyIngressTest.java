package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.User;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 隧道入口节点链为空（chain_tunnel 里没有任何 chain_type=1 的记录）时，createForward 必须先报错：
 * 指定 inPort 的旧路径会跳过整个发放循环直接返回 R.ok()，转发记录却已经落库 —— 一条永远不通的幽灵转发。
 * 未指定 inPort 的旧路径则在 get_port 里对空列表取首元素抛 NoSuchElementException，同样不能算正常返回。
 */
class ForwardEmptyIngressTest {

    private static final long FORWARD_ID = 42L;
    private static final int TUNNEL_ID = 7;
    private static final int USER_ID = 1;
    private static final long NODE_ID = 101L;

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
        // checkUserPermissions 持锁后先重读账号行：管理员替身必须能从库里读到一条 role_id=0 的行，否则按账号不存在拒绝。
        UserService stubbedUserService = mock(UserService.class);
        User stubbedAdminRow = new User();
        stubbedAdminRow.setId((long) USER_ID);
        stubbedAdminRow.setRoleId(0);
        when(stubbedUserService.getById(any())).thenReturn(stubbedAdminRow);
        ReflectionTestUtils.setField(forwardService, "userService", stubbedUserService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", mock(UserTunnelService.class));

        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(forwardMapper.insert(any(Forward.class))).thenAnswer(invocation -> {
            ((Forward) invocation.getArgument(0)).setId(FORWARD_ID);
            return 1;
        });
        when(forwardMapper.deleteById(any())).thenReturn(1);
        when(forwardPortService.save(any(ForwardPort.class))).thenReturn(true);
        when(forwardPortService.remove(any())).thenReturn(true);
        when(forwardPortService.list(any())).thenReturn(new ArrayList<>());
        when(nodeService.getById(any())).thenAnswer(invocation -> node((Long) invocation.getArgument(0)));
        authenticate();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void emptyIngressWithInPortWritesNothingAndFails() {
        when(chainTunnelService.list(any())).thenReturn(Collections.emptyList());

        R result = forwardService.createForward(forwardDto(20000));

        assertEquals(-1, result.getCode(), "入口节点为空时必须返回错误，不能返回成功");
        assertNotNull(result.getMsg());
        assertTrue(result.getMsg().contains("入口节点"), "错误信息要说明缺入口节点，实际：" + result.getMsg());
        verify(forwardMapper, never()).insert(any(Forward.class));
        verify(forwardPortService, never()).save(any(ForwardPort.class));
        verify(forwardPortService, never()).remove(any());
    }

    @Test
    void emptyIngressWithoutInPortFailsInsteadOfThrowing() {
        when(chainTunnelService.list(any())).thenReturn(Collections.emptyList());

        R result = forwardService.createForward(forwardDto(null));

        assertEquals(-1, result.getCode(), "入口节点为空时必须返回错误而不是抛异常");
        assertTrue(result.getMsg().contains("入口节点"), "错误信息要说明缺入口节点，实际：" + result.getMsg());
        verify(forwardMapper, never()).insert(any(Forward.class));
    }

    @Test
    void updateEmptyIngressWithInPortWritesNothingAndFails() {
        when(forwardMapper.selectById(FORWARD_ID)).thenReturn(existForward());
        when(chainTunnelService.list(any())).thenReturn(Collections.emptyList());

        R result = forwardService.updateForward(forwardUpdateDto(20000));

        assertEquals(-1, result.getCode(), "更新时入口节点为空必须返回错误");
        assertTrue(result.getMsg().contains("入口节点"), "错误信息要说明缺入口节点，实际：" + result.getMsg());
        verify(forwardMapper, never()).updateById(any(Forward.class));
    }

    @Test
    void updateEmptyIngressWithoutInPortFailsInsteadOfThrowing() {
        when(forwardMapper.selectById(FORWARD_ID)).thenReturn(existForward());
        when(chainTunnelService.list(any())).thenReturn(Collections.emptyList());

        R result = forwardService.updateForward(forwardUpdateDto(null));

        assertEquals(-1, result.getCode(), "更新时无inPort且入口为空不能抛异常，必须返回友好错误");
        assertTrue(result.getMsg().contains("入口节点"), "错误信息要说明缺入口节点，实际：" + result.getMsg());
        verify(forwardMapper, never()).updateById(any(Forward.class));
    }

    private static Forward existForward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setName("f-1");
        forward.setRemoteAddr("10.0.0.2:80");
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private static ForwardUpdateDto forwardUpdateDto(Integer inPort) {
        ForwardUpdateDto dto = new ForwardUpdateDto();
        dto.setId(FORWARD_ID);
        dto.setName("f-updated");
        dto.setRemoteAddr("10.0.0.3:80");
        dto.setStrategy("round");
        dto.setInPort(inPort);
        return dto;
    }

    private static ForwardDto forwardDto(Integer inPort) {
        ForwardDto forwardDto = new ForwardDto();
        forwardDto.setName("empty-ingress");
        forwardDto.setTunnelId(TUNNEL_ID);
        forwardDto.setRemoteAddr("10.10.10.10:8080");
        forwardDto.setStrategy("round");
        forwardDto.setInPort(inPort);
        return forwardDto;
    }

    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
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
        node.setPort("20000-20010");
        node.setTcpListenAddr("0.0.0.0");
        node.setUdpListenAddr("0.0.0.0");
        node.setStatus(1);
        return node;
    }

    static void authenticate() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":0,\"name\":\"admin\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
