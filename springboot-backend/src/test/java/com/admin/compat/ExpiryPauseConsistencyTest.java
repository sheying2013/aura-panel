package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.task.ResetFlowAsync;
import com.admin.common.utils.PauseReason;
import com.admin.common.utils.QuotaResume;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到期/停用自动暂停（ResetFlowAsync.user / userTunnel）一致性回归：
 * <ol>
 *   <li>库是唯一真相：节点在线与否都必须先把 status=0 落库；</li>
 *   <li>顺序：落库必须在节点下发之前，否则「节点已停、库里 status=1」会被重连对账补发 ResumeService 而放开；</li>
 *   <li>节点失败只记说明，不阻断落库、不回滚库（重连由 WebSocketServer.pauseForwardsExpectingPaused 补偿）；</li>
 *   <li>到期暂停会打上 pause_reason=expiry，因此配额恢复路径不会把到期用户放开，也不会误恢复手动暂停。</li>
 * </ol>
 */
class ExpiryPauseConsistencyTest {

    /** 节点全部在线：每条到期转发都要落库 + 逐入口下发 PauseService。 */
    @Test
    void expiryPausePersistsEveryForwardWhenAllNodesOnline() {
        Harness h = new Harness();
        Forward first = forward(11L, 7, 100);
        Forward second = forward(12L, 7, 200);
        User user = expiredUser(7L);
        h.users = List.of(user);
        h.forwards = List.of(first, second);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100), userTunnel(9, 7, 200));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L)), List.of(chain(9002L)));

        h.task.user();

        assertEquals(List.of("9001:11_7_5", "9002:12_7_9"), h.pausedServices);
        assertEquals(0, first.getStatus());
        assertEquals(0, second.getStatus());
        assertEquals(PauseReason.EXPIRY, first.getPauseReason());
        assertEquals(PauseReason.EXPIRY, second.getPauseReason());
        assertNotNull(first.getUpdatedTime(), "到期暂停落库时应刷新 updatedTime");
        assertTrue(first.getUpdatedTime() > 0, "updatedTime 必须是有效时间戳");
        verify(h.forwardService).updateById(first);
        verify(h.forwardService).updateById(second);
        assertEquals(0, user.getStatus(), "到期用户本身也要被停用");
        verify(h.userService).updateById(user);
    }

    /** 某个入口节点离线：仍然必须落库，离线入口只记说明。 */
    @Test
    void expiryPausePersistsEvenWhenOneEntryNodeOffline() {
        Harness h = new Harness();
        Forward forward = forward(11L, 7, 100);
        h.users = List.of(expiredUser(7L));
        h.forwards = List.of(forward);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L), chain(9005L)));
        h.nodeFailures.put(9005L, "节点不在线");

        h.task.user();

        assertEquals(List.of("9001:11_7_5", "9005:11_7_5"), h.pausedServices);
        assertEquals(0, forward.getStatus(), "入口离线也必须把暂停落库，否则在线入口会一直放行到期流量");
        assertEquals(PauseReason.EXPIRY, forward.getPauseReason());
        verify(h.forwardService).updateById(forward);
    }

    /** 先落库、后下发：库里 status=0 必须早于任何节点指令。 */
    @Test
    void writesDatabaseBeforeAskingNodes() {
        Harness h = new Harness();
        Forward forward = forward(11L, 7, 100);
        h.users = List.of(expiredUser(7L));
        h.forwards = List.of(forward);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L)));

        h.task.user();

        int dbIndex = h.events.indexOf("db:11");
        int nodeIndex = h.events.indexOf("node:9001:11_7_5");
        assertTrue(dbIndex >= 0, "必须先落库：" + h.events);
        assertTrue(nodeIndex > dbIndex, "落库要先于节点下发，否则中途异常会让节点已停而库里仍 status=1：" + h.events);
    }

    /** 过期隧道维度同理：落库 + 打 expiry 标记 + 隧道本身停用。 */
    @Test
    void expiresUserTunnelForwardsEvenWhenNodeOffline() {
        Harness h = new Harness();
        Forward forward = forward(21L, 7, 300);
        UserTunnel userTunnel = expiredUserTunnel(5, 7, 300);
        h.userTunnels = List.of(userTunnel);
        h.forwards = List.of(forward);
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9003L)));
        h.nodeFailures.put(9003L, "节点不在线");

        h.task.userTunnel();

        assertEquals(List.of("9003:21_7_5"), h.pausedServices);
        assertEquals(0, forward.getStatus());
        assertEquals(PauseReason.EXPIRY, forward.getPauseReason());
        verify(h.forwardService).updateById(forward);
        assertEquals(0, userTunnel.getStatus());
        verify(h.userTunnelService).updateById(userTunnel);
    }

    /** 到期用户不会被配额恢复路径放开：状态闸门与到期闸门都拦住。 */
    @Test
    void expiredUserIsNotResumedByQuotaReset() {
        long now = System.currentTimeMillis();
        Forward forward = forward(11L, 7, 100);
        forward.setStatus(0);
        forward.setPauseReason(PauseReason.EXPIRY);
        UserTunnel userTunnel = userTunnel(5, 7, 100);

        User expiredButEnabled = expiredUser(7L);
        assertFalse(QuotaResume.shouldResume(forward, expiredButEnabled, userTunnel, true, now),
                "expiry 标记 + 账号已到期，绝不能因流量重置被恢复");

        User disabledUser = expiredUser(7L);
        disabledUser.setStatus(0);
        assertFalse(QuotaResume.shouldResume(forward, disabledUser, userTunnel, true, now),
                "到期任务同时停用了账号，status!=1 的账号不得恢复转发");

        User futureUser = activeUser(7L, now + 86400000L);
        assertFalse(QuotaResume.shouldResume(forward, futureUser, userTunnel, true, now),
                "即便续费，pause_reason=expiry 的转发也不该由配额恢复路径放开");
    }

    /** 手动暂停不会被配额恢复误放开，同时真正的配额暂停仍能恢复（避免过度收紧）。 */
    @Test
    void manualPauseIsNotResumedButQuotaPauseStillIs() {
        long now = System.currentTimeMillis();
        User user = activeUser(7L, now + 86400000L);
        UserTunnel userTunnel = userTunnel(5, 7, 100);

        Forward manual = forward(31L, 7, 100);
        manual.setStatus(0);
        manual.setPauseReason(PauseReason.MANUAL);
        assertFalse(QuotaResume.shouldResume(manual, user, userTunnel, true, now),
                "手动暂停的转发不能被流量重置放开");

        Forward quota = forward(32L, 7, 100);
        quota.setStatus(0);
        quota.setPauseReason(PauseReason.QUOTA);
        assertTrue(QuotaResume.shouldResume(quota, user, userTunnel, true, now),
                "配额暂停在额度重置后仍应恢复，否则 round18 的修复被破坏");
    }

    /** 到期暂停写入的原因只能是 expiry，绝不能写成 quota（否则会被重置恢复）。 */
    @Test
    void expiryPauseNeverLabelsReasonAsQuota() {
        Harness h = new Harness();
        Forward forward = forward(41L, 7, 100);
        h.users = List.of(expiredUser(7L));
        h.forwards = List.of(forward);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L)));

        h.task.user();

        assertEquals(PauseReason.EXPIRY, forward.getPauseReason());
        assertFalse(PauseReason.isQuota(forward.getPauseReason()), "到期暂停不得被标成配额暂停");
    }

    /** 没有入口节点的转发：仍要落库（库是唯一真相），只是没有节点指令。 */
    @Test
    void persistsWhenForwardHasNoEntranceNode() {
        Harness h = new Harness();
        Forward forward = forward(51L, 7, 100);
        h.users = List.of(expiredUser(7L));
        h.forwards = List.of(forward);
        when(h.userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100));
        when(h.chainTunnelService.list(any())).thenReturn(List.of());

        h.task.user();

        assertTrue(h.pausedServices.isEmpty());
        assertEquals(0, forward.getStatus());
        assertEquals(PauseReason.EXPIRY, forward.getPauseReason());
        verify(h.forwardService).updateById(forward);
    }

    /** 已停（status=0）的转发不在到期任务的目标集合里，不会被改写原因。 */
    @Test
    void doesNotTouchForwardsAlreadyPaused() {
        Harness h = new Harness();
        Forward alreadyPaused = forward(61L, 7, 100);
        alreadyPaused.setStatus(0);
        alreadyPaused.setPauseReason(PauseReason.MANUAL);
        h.users = List.of(expiredUser(7L));
        h.forwards = List.of();

        h.task.user();

        assertTrue(h.pausedServices.isEmpty(), "status=0 的转发不会进入到期任务的目标集合");
        verify(h.forwardService, never()).updateById(alreadyPaused);
        assertEquals(PauseReason.MANUAL, alreadyPaused.getPauseReason());
        assertEquals(0, alreadyPaused.getStatus());
    }

    /**
     * 流量上报判定到期/停用后走的是 FlowController.pauseService（与配额共用落库点）：
     * 同样必须先把 status=0 + pause_reason 落库，再逐入口下发，入口离线只记说明。
     */
    @Test
    void flowReportPauseWritesDatabaseBeforeDispatching() {
        FlowHarness h = new FlowHarness();
        Forward forward = forward(71L, 7, 100);
        when(h.userService.getById(any())).thenReturn(activeUser(7L, System.currentTimeMillis() - 60000L));
        when(h.chainTunnelService.list(any())).thenReturn(List.of(chain(9001L), chain(9005L)));
        h.nodeFailures.put(9005L, "节点不在线");

        h.controller.pauseService(List.of(forward), PauseReason.EXPIRY);

        int dbIndex = h.events.indexOf("db:71");
        int firstNodeIndex = h.events.indexOf("node:9001:71_7_5");
        int offlineNodeIndex = h.events.indexOf("node:9005:71_7_5");
        assertTrue(dbIndex >= 0, "到期/停用暂停必须先落库：" + h.events);
        assertTrue(firstNodeIndex > dbIndex && offlineNodeIndex > dbIndex,
                "落库要先于所有入口下发，否则中途异常会留下「节点已停、库里 status=1」被重连对账补发 ResumeService：" + h.events);
        assertEquals(0, forward.getStatus(), "入口离线也必须把暂停落库");
        assertEquals(PauseReason.EXPIRY, forward.getPauseReason());
        verify(h.forwardService).updateById(forward);
    }

    private static Forward forward(long id, int userId, int tunnelId) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId(userId);
        forward.setTunnelId(tunnelId);
        forward.setStatus(1);
        return forward;
    }

    private static ChainTunnel chain(long nodeId) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setNodeId(nodeId);
        chainTunnel.setChainType(1);
        return chainTunnel;
    }

    private static User expiredUser(long id) {
        User user = new User();
        user.setId(id);
        user.setRoleId(2);
        user.setStatus(1);
        user.setExpTime(System.currentTimeMillis() - 60000L);
        return user;
    }

    private static User activeUser(long id, long expTime) {
        User user = new User();
        user.setId(id);
        user.setRoleId(2);
        user.setStatus(1);
        user.setExpTime(expTime);
        return user;
    }

    private static UserTunnel userTunnel(int id, int userId, int tunnelId) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(id);
        userTunnel.setUserId(userId);
        userTunnel.setTunnelId(tunnelId);
        userTunnel.setStatus(1);
        return userTunnel;
    }

    private static UserTunnel expiredUserTunnel(int id, int userId, int tunnelId) {
        UserTunnel userTunnel = userTunnel(id, userId, tunnelId);
        userTunnel.setExpTime(System.currentTimeMillis() - 60000L);
        return userTunnel;
    }

    /**
     * 用 mock 的服务驱动 ResetFlowAsync，记录「落库」与「节点下发」的先后顺序。
     */
    private static final class Harness {

        private final ForwardService forwardService = mock(ForwardService.class);
        private final ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);
        private final UserTunnelService userTunnelService = mock(UserTunnelService.class);
        private final UserService userService = mock(UserService.class);
        private final List<String> pausedServices = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final Map<Long, String> nodeFailures = new HashMap<>();
        private final ResetFlowAsync task;

        private List<User> users = List.of();
        private List<Forward> forwards = List.of();
        private List<UserTunnel> userTunnels = List.of();

        private Harness() {
            task = new ResetFlowAsync() {
                @Override
                protected GostDto pauseNodeService(Long nodeId, String name) {
                    pausedServices.add(nodeId + ":" + name);
                    events.add("node:" + nodeId + ":" + name);
                    GostDto gostDto = new GostDto();
                    gostDto.setMsg(nodeFailures.getOrDefault(nodeId, "OK"));
                    return gostDto;
                }
            };
            when(userService.list(any())).thenAnswer(invocation -> users);
            when(userTunnelService.list(any())).thenAnswer(invocation -> userTunnels);
            when(forwardService.list(any())).thenAnswer(invocation -> forwards);
            when(forwardService.updateById(any(Forward.class))).thenAnswer(invocation -> {
                Forward forward = invocation.getArgument(0);
                events.add("db:" + forward.getId());
                return true;
            });
            when(userService.updateById(any(User.class))).thenReturn(true);
            when(userTunnelService.updateById(any(UserTunnel.class))).thenReturn(true);
            inject(task, "userService", userService);
            inject(task, "userTunnelService", userTunnelService);
            inject(task, "forwardService", forwardService);
            inject(task, "chainTunnelService", chainTunnelService);
        }

        private static void inject(Object target, String field, Object value) {
            try {
                Field declaredField = ResetFlowAsync.class.getDeclaredField(field);
                declaredField.setAccessible(true);
                declaredField.set(target, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("注入 ResetFlowAsync." + field + " 失败", e);
            }
        }
    }

    /**
     * 用 mock 的服务驱动 FlowController.pauseService，同样记录「落库」与「节点下发」的先后顺序。
     */
    private static final class FlowHarness {

        private final ForwardService forwardService = mock(ForwardService.class);
        private final ChainTunnelService chainTunnelService = mock(ChainTunnelService.class);
        private final UserTunnelService userTunnelService = mock(UserTunnelService.class);
        private final UserService userService = mock(UserService.class);
        private final List<String> events = new ArrayList<>();
        private final Map<Long, String> nodeFailures = new HashMap<>();
        private final FlowController controller;

        private FlowHarness() {
            when(userTunnelService.getOne(any())).thenReturn(userTunnel(5, 7, 100));
            when(forwardService.updateById(any(Forward.class))).thenAnswer(invocation -> {
                Forward forward = invocation.getArgument(0);
                events.add("db:" + forward.getId());
                return true;
            });
            controller = new FlowController() {
                @Override
                protected GostDto pauseNodeService(Long nodeId, String name) {
                    events.add("node:" + nodeId + ":" + name);
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
