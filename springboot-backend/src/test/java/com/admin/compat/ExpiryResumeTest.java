package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.common.utils.PauseReason;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.ForwardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round20：续期/启用后自动恢复「到期或停用」暂停的转发。
 *
 * <p>覆盖的原缺陷：FlowController/ResetFlowAsync 在到期或停用时把转发暂停并写
 * pause_reason=expiry/disabled，但续费、重新启用之后没有任何路径把它们放开，
 * 只能人工逐条点恢复；而手动暂停（manual）必须是「谁来都不许自动放开」的语义。
 *
 * <p>用例全部走真实 applyStatusChange 落库路径（节点用 null 模拟不可达，只验落库决策），
 * 不下发真实 WebSocket 指令。
 */
class ExpiryResumeTest {

    private static final long USER_ID = 7L;
    private static final int TUNNEL_ID = 3;
    private static final long NODE_ID = 501L;
    private static final long NOW = System.currentTimeMillis();

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private UserService userService;
    private UserTunnelService userTunnelService;
    private TunnelService tunnelService;
    private NodeService nodeService;
    private ChainTunnelService chainTunnelService;

    @BeforeEach
    void setUp() {
        forwardMapper = mock(ForwardMapper.class);
        userService = mock(UserService.class);
        userTunnelService = mock(UserTunnelService.class);
        tunnelService = mock(TunnelService.class);
        nodeService = mock(NodeService.class);
        chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", mock(ForwardPortService.class));
        ReflectionTestUtils.setField(forwardService, "userService", userService);
        ReflectionTestUtils.setField(forwardService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);

        when(tunnelService.getById(any())).thenReturn(tunnel());
        when(chainTunnelService.list(any())).thenReturn(List.of(chainTunnel()));
        // 节点不可达：applyStatusChange 只记说明，仍把库改对
        when(nodeService.getById(any())).thenReturn(null);
        when(forwardMapper.selectById(any())).thenAnswer(invocation -> {
            List<Forward> rows = forwardMapper.selectList(null);
            return rows == null ? null : rows.stream()
                    .filter(f -> f.getId().equals(invocation.getArgument(0))).findFirst().orElse(null);
        });
        when(forwardMapper.updateById(any(Forward.class))).thenReturn(1);
        when(userTunnelService.getOne(any())).thenReturn(null);
    }

    @Test
    void renewedUserResumesExpiryPausedForward() {
        when(userService.getById(any())).thenReturn(user(1, NOW + 86_400_000L));
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(forward(0, PauseReason.EXPIRY))));

        List<String> notes = forwardService.resumeExpiryDisabledPausedForUser(USER_ID);

        Forward updated = capturedUpdate();
        assertEquals(1, updated.getStatus(), "续期后应把到期暂停的转发恢复");
        assertEquals("", updated.getPauseReason(), "恢复后必须清掉暂停原因");
        assertEquals(1, notes.size(), "节点不可达应只报一条说明");
        assertTrue(notes.get(0).contains("已记为待执行"), notes.get(0));
    }

    @Test
    void manualPauseIsNeverResumedByRenewal() {
        when(userService.getById(any())).thenReturn(user(1, NOW + 86_400_000L));
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(forward(0, PauseReason.MANUAL))));

        List<String> notes = forwardService.resumeExpiryDisabledPausedForUser(USER_ID);

        verify(forwardMapper, never()).updateById(any(Forward.class));
        assertTrue(notes.isEmpty(), "手动暂停不该产生恢复说明");
    }

    @Test
    void expiredUserStillKeepsForwardPaused() {
        when(userService.getById(any())).thenReturn(user(1, NOW - 1_000L));
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(forward(0, PauseReason.EXPIRY))));

        forwardService.resumeExpiryDisabledPausedForUser(USER_ID);

        verify(forwardMapper, never()).updateById(any(Forward.class));
    }

    @Test
    void tunnelStillDisabledKeepsForwardPaused() {
        when(userService.getById(any())).thenReturn(user(1, NOW + 86_400_000L));
        UserTunnel tunnel = userTunnel(0, NOW + 86_400_000L);
        when(userTunnelService.getOne(any())).thenReturn(tunnel);
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(forward(0, PauseReason.EXPIRY))));

        forwardService.resumeExpiryDisabledPausedForUser(USER_ID);

        verify(forwardMapper, never()).updateById(any(Forward.class));
    }

    @Test
    void renewedUserTunnelResumesExpiryPausedForward() {
        when(userService.getById(any())).thenReturn(user(1, NOW + 86_400_000L));
        when(userTunnelService.getById(any())).thenReturn(userTunnel(1, NOW + 86_400_000L));
        when(userTunnelService.getOne(any())).thenReturn(userTunnel(1, NOW + 86_400_000L));
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(forward(0, PauseReason.DISABLED))));

        forwardService.resumeExpiryDisabledPausedForUserTunnel(11);

        Forward updated = capturedUpdate();
        assertEquals(1, updated.getStatus());
        assertEquals("", updated.getPauseReason());
    }

    @Test
    void userTunnelUpdateReturnsOkInsteadOfAlwaysFailing() {
        com.admin.service.impl.UserTunnelServiceImpl service = new com.admin.service.impl.UserTunnelServiceImpl();
        com.admin.mapper.UserTunnelMapper mapper = mock(com.admin.mapper.UserTunnelMapper.class);
        com.admin.service.ForwardService forwardSvc = mock(com.admin.service.ForwardService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "forwardService", forwardSvc);

        UserTunnel existing = userTunnel(1, NOW + 86_400_000L);
        existing.setId(11);
        existing.setSpeedId(null);
        when(mapper.selectById(any())).thenReturn(existing);
        when(mapper.updateById(any(UserTunnel.class))).thenReturn(1);
        when(forwardSvc.resumeExpiryDisabledPausedForUserTunnel(any())).thenReturn(Collections.emptyList());

        com.admin.common.dto.UserTunnelUpdateDto dto = new com.admin.common.dto.UserTunnelUpdateDto();
        dto.setId(11);
        dto.setFlow(10L);
        dto.setNum(10);
        R r = service.updateUserTunnel(dto);

        assertEquals(0, r.getCode(), "更新成功后必须返回成功（基线无条件返回失败）");
    }

    @Test
    void autoResumeRechecksOwnerAfterTakingForwardLock() {
        when(userService.getById(any())).thenReturn(user(1, NOW + 86_400_000L), user(0, NOW + 86_400_000L));
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(forward(0, PauseReason.EXPIRY))));
        List<String> notes = forwardService.resumeExpiryDisabledPausedForUser(USER_ID);
        verify(forwardMapper, never()).updateById(any(Forward.class));
        assertTrue(notes.stream().anyMatch(n -> n.contains("账号当前不可用")), notes.toString());
    }

    @Test
    void staleDisableOperationDoesNotPauseReenabledAccount() {
        when(userService.getById(any())).thenReturn(user(1, NOW + 86_400_000L));
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(forward(1, ""))));
        forwardService.pauseForDisabledUser(USER_ID);
        verify(forwardMapper, never()).updateById(any(Forward.class));
    }

    @Test
    void disabledPauseCanRetryAfterForwardLockIsReleased() {
        when(userService.getById(any())).thenReturn(user(0, NOW + 86_400_000L));
        when(forwardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(forward(1, ""))));
        try (var held = com.admin.common.utils.ForwardWriteLocks.acquire(0, 31L)) {
            assertTrue(held != null);
            List<String> first = forwardService.pauseForDisabledUser(USER_ID);
            assertTrue(first.stream().anyMatch(n -> n.contains("正在修改")), first.toString());
            verify(forwardMapper, never()).updateById(any(Forward.class));
        }
        forwardService.pauseForDisabledUser(USER_ID);
        Forward updated = capturedUpdate();
        assertEquals(0, updated.getStatus());
        assertEquals(PauseReason.DISABLED, updated.getPauseReason());
    }

    private Forward capturedUpdate() {
        ArgumentCaptor<Forward> captor = ArgumentCaptor.forClass(Forward.class);
        verify(forwardMapper).updateById(captor.capture());
        return captor.getValue();
    }

    private Forward forward(int status, String reason) {
        Forward forward = new Forward();
        forward.setId(31L);
        forward.setUserId((int) USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setName("expiry-test");
        forward.setRemoteAddr("backend:6365");
        forward.setStrategy("round");
        forward.setStatus(status);
        forward.setPauseReason(reason);
        return forward;
    }

    private User user(int status, Long expTime) {
        User user = new User();
        user.setId(USER_ID);
        user.setStatus(status);
        user.setExpTime(expTime);
        user.setFlow(10L);
        return user;
    }

    private UserTunnel userTunnel(int status, Long expTime) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(11);
        userTunnel.setUserId((int) USER_ID);
        userTunnel.setTunnelId(TUNNEL_ID);
        userTunnel.setStatus(status);
        userTunnel.setExpTime(expTime);
        return userTunnel;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) TUNNEL_ID);
        tunnel.setName("t");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    private ChainTunnel chainTunnel() {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId((long) TUNNEL_ID);
        chainTunnel.setNodeId(NODE_ID);
        chainTunnel.setChainType(1);
        return chainTunnel;
    }
}
