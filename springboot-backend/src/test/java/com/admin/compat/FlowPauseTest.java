package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.PauseReason;
import com.admin.controller.BaseController;
import com.admin.controller.FlowController;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流量上报触发暂停的回归测试：
 * 多条转发必须各用自己的服务名（forwardId_userId_userTunnelId），
 * 节点失败不得把数据库状态改成已暂停，管理员转发没有 UserTunnel 时服务名后缀为 0，
 * 正常用户找不到 UserTunnel 时既不暂停也不写库。
 */
class FlowPauseTest {

    @Test
    void pausesEachForwardWithItsOwnServiceName() {
        Harness h = new Harness();
        Forward first = forward(11L, 7, 100);
        Forward second = forward(12L, 7, 200);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100), userTunnel(9, 7, 200));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L)), List.of(chain(9002L)));

        h.pause(List.of(first, second));

        assertEquals(List.of("9001:11_7_5", "9002:12_7_9"), h.pausedServices);
        assertEquals(0, first.getStatus());
        assertEquals(0, second.getStatus());
        verify(h.forwardService).updateById(first);
        verify(h.forwardService).updateById(second);
    }

    /**
     * 配额已超时，某个入口节点离线也必须把暂停落库：
     * 在线入口要立刻停、状态要立刻变，否则在线入口会继续放行超额流量。
     * 离线入口等它重连后由 WebSocketServer.pauseForwardsExpectingPaused 补发 PauseService。
     */
    @Test
    void persistsPausedEvenWhenOneEntryNodeIsOffline() {
        Harness h = new Harness();
        Forward first = forward(11L, 7, 100);
        Forward second = forward(12L, 7, 200);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100), userTunnel(9, 7, 200));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L), chain(9005L)), List.of(chain(9002L)));
        h.nodeFailures.put(9005L, "节点不在线");

        h.pause(List.of(first, second));

        assertEquals(List.of("9001:11_7_5", "9005:11_7_5", "9002:12_7_9"), h.pausedServices);
        assertEquals(0, first.getStatus());
        assertTrue(first.getUpdatedTime() != null && first.getUpdatedTime() > 0, "落库时应刷新 updatedTime");
        verify(h.forwardService).updateById(first);
        assertEquals(0, second.getStatus());
        verify(h.forwardService).updateById(second);
    }

    @Test
    void usesZeroSuffixForAdminForwardWithoutUserTunnel() {
        Harness h = new Harness();
        Forward forward = forward(21L, 3, 300);
        when(h.userTunnelService.getOne(any())).thenReturn(null);
        when(h.userService.getById(any())).thenReturn(user(3, 0));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9003L)));

        h.pause(List.of(forward));

        assertEquals(List.of("9003:21_3_0"), h.pausedServices);
        assertEquals(0, forward.getStatus());
        verify(h.forwardService).updateById(forward);
    }

    @Test
    void usesZeroSuffixForAdminEvenWhenUserTunnelExists() {
        Harness h = new Harness();
        Forward forward = forward(22L, 3, 300);
        when(h.userService.getById(any())).thenReturn(user(3, 0));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9003L)));

        h.pause(List.of(forward));

        assertEquals(List.of("9003:22_3_0"), h.pausedServices);
        verify(h.userTunnelService, never()).getOne(any());
    }

    @Test
    void skipsForwardWhenUserTunnelCannotBeResolved() {
        Harness h = new Harness();
        Forward forward = forward(31L, 8, 400);
        when(h.userTunnelService.getOne(any())).thenReturn(null);
        when(h.userService.getById(any())).thenReturn(user(8, 2));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9004L)));

        h.pause(List.of(forward));

        assertTrue(h.pausedServices.isEmpty());
        assertEquals(1, forward.getStatus());
        verify(h.forwardService, never()).updateById(forward);
    }

    @Test
    void labelsQuotaReasonForFlowPathPause() {
        Harness h = new Harness();
        Forward forward = forward(41L, 7, 100);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L)));

        h.pause(List.of(forward), PauseReason.QUOTA);

        assertEquals(PauseReason.QUOTA, forward.getPauseReason());
        verify(h.forwardService).updateById(forward);
    }

    @Test
    void labelsExpiryReasonWhenTriggeredByExpiredAccount() {
        Harness h = new Harness();
        Forward forward = forward(42L, 7, 100);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L)));

        h.pause(List.of(forward), PauseReason.EXPIRY);

        assertEquals(PauseReason.EXPIRY, forward.getPauseReason());
    }

    @Test
    void defaultPauseServiceKeepsQuotaSemantics() {
        Harness h = new Harness();
        Forward forward = forward(43L, 7, 100);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L)));

        h.pause(List.of(forward));

        assertEquals(PauseReason.QUOTA, forward.getPauseReason());
    }

    @Test
    void busyForwardIsNotPausedUsingStaleSnapshot() {
        Harness h = new Harness();
        Forward forward = forward(55L, 7, 100);
        try (var held = com.admin.common.utils.ForwardWriteLocks.acquire(0, 55L)) {
            assertTrue(held != null);
            h.pause(List.of(forward));
            verify(h.forwardService, never()).updateById(any(Forward.class));
            assertTrue(h.pausedServices.isEmpty());
        }
    }

    @Test
    void databaseWriteFailureDoesNotPauseNode() {
        Harness h = new Harness();
        Forward forward = forward(56L, 7, 100);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L)));
        when(h.forwardService.updateById(any(Forward.class))).thenReturn(false);
        h.pause(List.of(forward));
        assertTrue(h.pausedServices.isEmpty());
    }

    private static Forward forward(long id, int userId, int tunnelId) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId(userId);
        forward.setTunnelId(tunnelId);
        forward.setStatus(1);
        return forward;
    }

    private static UserTunnel userTunnel(int id, int userId, int tunnelId) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(id);
        userTunnel.setUserId(userId);
        userTunnel.setTunnelId(tunnelId);
        return userTunnel;
    }

    private static User user(int id, int roleId) {
        User user = new User();
        user.setId((long) id);
        user.setRoleId(roleId);
        return user;
    }

    private static ChainTunnel chain(long nodeId) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setNodeId(nodeId);
        chainTunnel.setChainType(1);
        return chainTunnel;
    }

    /**
     * 用 mock 的服务驱动 FlowController，并记录下来每条转发实际暂停的服务名与节点。
     */
    private static final class Harness {

        private final ForwardService forwardService = mock(ForwardService.class);
        private final ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);
        private final UserTunnelService userTunnelService = mock(UserTunnelService.class);
        private final UserService userService = mock(UserService.class);
        private final List<String> pausedServices = new ArrayList<>();
        private final Map<Long, String> nodeFailures = new HashMap<>();
        private final FlowController controller;

        private void pause(List<Forward> rows) {
            register(rows);
            controller.pauseService(rows);
        }

        private void pause(List<Forward> rows, String reason) {
            register(rows);
            controller.pauseService(rows, reason);
        }

        private void register(List<Forward> rows) {
            for (Forward row : rows) when(forwardService.getById(row.getId())).thenReturn(row);
        }

        private Harness() {
            when(forwardService.updateById(any(Forward.class))).thenReturn(true);
            when(userService.getById(any())).thenReturn(user(7, 2));
            controller = new FlowController() {
                @Override
                protected GostDto pauseNodeService(Long nodeId, String name) {
                    pausedServices.add(nodeId + ":" + name);
                    GostDto gostDto = new GostDto();
                    gostDto.setMsg(nodeFailures.getOrDefault(nodeId, "OK"));
                    return gostDto;
                }
            };
            inject(controller, BaseController.class, "forwardService", forwardService);
            inject(controller, BaseController.class, "userService", userService);
            inject(controller, BaseController.class, "userTunnelService", userTunnelService);
            inject(controller, FlowController.class, "chainTunnelService", chainTunnelService);
        }

        private static void inject(Object target, Class<?> owner, String field, Object value) {
            try {
                Field declaredField = owner.getDeclaredField(field);
                declaredField.setAccessible(true);
                declaredField.set(target, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("注入 " + owner.getSimpleName() + "." + field + " 失败", e);
            }
        }
    }
}
