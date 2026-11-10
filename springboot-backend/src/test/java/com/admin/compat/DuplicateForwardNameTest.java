package com.admin.compat;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.common.lang.R;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.ForwardServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同一用户在同一隧道下的转发名称不允许重复。
 *
 * <p>真机缺陷（round26 并发创建）：/forward/create 连发 5 次同名 r27-race-1，库里出现 5 条同名不同 id 的转发，
 * 面板两行目标完全一样、诊断与日志只能靠 id 区分。基线 createForward/updateForward 都没有任何名称唯一性校验。
 *
 * <p>这里锁住四件事：create 遇到同名要拒、update 遇到同名要拒、update 的查重必须排除自身（否则改自己名字都被拒）、
 * 名字只有空白时不做查重（历史数据里允许空名）。
 */
class DuplicateForwardNameTest {

    private static final long FORWARD_ID = 1L;
    private static final int TUNNEL_ID = 1;
    private static final int USER_ID = 1;
    private static final String NAME = "dup-name";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private ChainTunnelService chainTunnelService;
    private TunnelService tunnelService;
    private UserService userService;
    private UserTunnelService userTunnelService;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;

    /** 查重查询返回的"同隧道的其他同名转发"；null 表示没有重名。 */
    private List<Forward> nameMatches;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        nameMatches = null;
        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        userService = mock(UserService.class);
        userTunnelService = mock(UserTunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);

        when(forwardMapper.selectById(any())).thenReturn(storedForward());
        when(forwardMapper.insert(any(Forward.class))).thenReturn(1);
        when(forwardMapper.updateById(any(Forward.class))).thenReturn(1);
        // 配额统计一律返回 0：本用例只关心名称唯一性，不能让数量上限抢先报错
        when(forwardMapper.selectCount(any())).thenReturn(0);
        when(forwardMapper.selectList(any())).thenAnswer(invocation -> {
            List<Forward> rows = new ArrayList<>();
            if (nameMatches != null) {
                rows.addAll(nameMatches);
            }
            return rows;
        });
        when(tunnelService.getById(any())).thenReturn(tunnel());
        // 空入口链：create 会在"隧道没有入口节点"处停住，update 会直接落库，两条路径都不会碰真实 WS
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>());
        when(userService.getById(any())).thenReturn(user());
        when(userTunnelService.getOne(any())).thenReturn(userTunnel());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createRejectsDuplicateName() {
        authenticate(1, "user");
        nameMatches = List.of(otherForward("他人已占用"));

        R result = forwardService.createForward(createDto(NAME));

        assertEquals(-1, result.getCode(), "同名转发必须拒绝创建");
        assertTrue(result.getMsg().contains("已存在同名转发"), result.getMsg());
        assertTrue(result.getMsg().contains(NAME), "错误信息要指出重名的名称：" + result.getMsg());
        verify(forwardMapper, never()).insert(any(Forward.class));
    }

    @Test
    void createPassesGateWhenNameIsUnique() {
        authenticate(1, "user");

        R result = forwardService.createForward(createDto(NAME));

        assertEquals(-1, result.getCode());
        assertEquals("隧道没有入口节点，无法创建转发", result.getMsg(),
                "查重放行后应继续走到入口链校验，而不是停在重名分支");
        verify(forwardMapper, never()).insert(any(Forward.class));
    }

    @Test
    void createSkipsDuplicateCheckForBlankName() {
        authenticate(1, "user");

        R result = forwardService.createForward(createDto("   "));

        assertEquals(-1, result.getCode());
        assertEquals("隧道没有入口节点，无法创建转发", result.getMsg());
        verify(forwardMapper, never()).selectList(any());
    }

    @Test
    void updateRejectsDuplicateName() {
        authenticate(1, "user");
        nameMatches = List.of(otherForward("他人已占用"));

        R result = forwardService.updateForward(updateDto(NAME));

        assertEquals(-1, result.getCode(), "改名撞上同隧道其他转发必须拒绝");
        assertTrue(result.getMsg().contains("已存在同名转发"), result.getMsg());
        verify(forwardMapper, never()).updateById(any(Forward.class));
    }

    @Test
    void updateDuplicateQueryExcludesItself() {
        authenticate(1, "user");
        nameMatches = List.of(otherForward(NAME));
        ArgumentCaptor<Wrapper<Forward>> captor = ArgumentCaptor.forClass(Wrapper.class);

        R result = forwardService.updateForward(updateDto("  " + NAME + "  "));

        assertEquals(-1, result.getCode(), "首尾空格要去掉后再比对，撞名必须拦：" + result.getMsg());
        verify(forwardMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("name"), sql);
        assertTrue(sql.contains("<>"), "查重必须排除自身，否则改自己名字也会被拒：" + sql);
    }

    @Test
    void trimNameDropsSurroundingSpaces() throws Exception {
        java.lang.reflect.Method method = ForwardServiceImpl.class.getDeclaredMethod("trimName", String.class);
        method.setAccessible(true);
        assertEquals("renamed", method.invoke(null, "  renamed  "));
        assertEquals(null, method.invoke(null, "   "));
        assertEquals(null, method.invoke(null, (Object) null));
    }

    // ========== 测试脚手架 ==========

    private void authenticate(int roleId, String name) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + USER_ID + ",\"role_id\":" + roleId + ",\"name\":\"" + name + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ForwardDto createDto(String name) {
        ForwardDto dto = new ForwardDto();
        dto.setName(name);
        dto.setTunnelId(TUNNEL_ID);
        dto.setRemoteAddr("10.2.2.2:9090");
        dto.setStrategy("round");
        return dto;
    }

    private ForwardUpdateDto updateDto(String name) {
        ForwardUpdateDto dto = new ForwardUpdateDto();
        dto.setId(FORWARD_ID);
        dto.setUserId(USER_ID);
        dto.setName(name);
        dto.setRemoteAddr("10.2.2.2:9090");
        dto.setStrategy("round");
        return dto;
    }

    private Forward storedForward() {
        Forward forward = new Forward();
        forward.setId(FORWARD_ID);
        forward.setUserId(USER_ID);
        forward.setUserName("user");
        forward.setName("old-name");
        forward.setTunnelId(TUNNEL_ID);
        forward.setRemoteAddr("10.1.1.1:8080");
        forward.setStrategy("fifo");
        forward.setStatus(1);
        forward.setCreatedTime(1000L);
        forward.setUpdatedTime(2000L);
        return forward;
    }

    private Forward otherForward(String name) {
        Forward forward = new Forward();
        forward.setId(99L);
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setName(name);
        return forward;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("tunnel");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private User user() {
        User user = new User();
        user.setId((long) USER_ID);
        user.setUser("user");
        user.setNum(10);
        user.setFlow(1000L);
        return user;
    }

    private UserTunnel userTunnel() {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(7);
        userTunnel.setUserId(USER_ID);
        userTunnel.setTunnelId(TUNNEL_ID);
        userTunnel.setStatus(1);
        userTunnel.setNum(10);
        userTunnel.setFlow(100L);
        return userTunnel;
    }
}
