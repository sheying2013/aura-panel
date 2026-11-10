package com.admin.compat;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.SpeedLimit;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.SpeedLimitService;
import com.admin.service.StatisticsFlowService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.CascadeDeletePurger;
import com.admin.service.impl.TunnelServiceImpl;
import com.admin.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * deleteTunnel / deleteUser 的多表级联清理回归测试。
 *
 * <p>覆盖的原缺陷：
 * 1. deleteTunnel 完全漏删 speed_limit —— 隧道删了、限速行留在库里当孤儿，节点上的限流器也再没人认领；
 * 2. deleteTunnel 只按 tunnel_id 删 forward，forward_port 从未按 forward_id 清理；
 * 3. deleteUser 里 deleteForward 在节点侧失败时会跳过库清理（只回错误），
 *    随后 forwardService.remove(user_id) 把 forward 行删了，对应的 forward_port 就再也追不回来；
 * 4. 多表删除各删各的，没有事务兜底，中途失败留一半孤儿行。
 *
 * <p>另外固定一条顺序约束：库写全部做完之后才允许下发节点（事务里不能夹长耗时网络调用）。
 */
class CascadeDeleteTest {

    private static final long TUNNEL_ID = 7L;
    private static final long USER_ID = 3L;
    private static final long FORWARD_ID_1 = 11L;
    private static final long FORWARD_ID_2 = 12L;
    private static final long[] NODE_IDS = {101L, 102L};
    private static final long[] SPEED_LIMIT_IDS = {501L, 502L};

    /** 按发生顺序记录库写（db:）与节点下发（rpc:），用来断言"下发在库写之后"。 */
    private final List<String> events = new ArrayList<>();
    /** 这些 "nodeId:limiterId" 的限流器清理会被模拟成节点离线。 */
    private final Set<String> failingLimiters = new HashSet<>();

    private ForwardService forwardService;
    private ForwardPortService forwardPortService;
    private UserTunnelService userTunnelService;
    private SpeedLimitService speedLimitService;
    private ChainTunnelService chainTunnelService;
    private StatisticsFlowService statisticsFlowService;
    private TestTunnelService tunnelService;
    private TestUserService userService;

    @BeforeEach
    void setUp() {
        forwardService = mock(ForwardService.class);
        forwardPortService = mock(ForwardPortService.class);
        userTunnelService = mock(UserTunnelService.class);
        speedLimitService = mock(SpeedLimitService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        statisticsFlowService = mock(StatisticsFlowService.class);

        recordDbWrite(forwardService, "db:forward");
        recordDbWrite(forwardPortService, "db:forward_port");
        recordDbWrite(userTunnelService, "db:user_tunnel");
        recordDbWrite(speedLimitService, "db:speed_limit");
        recordDbWrite(chainTunnelService, "db:chain_tunnel");
        recordDbWrite(statisticsFlowService, "db:statistics_flow");

        when(forwardService.list(any())).thenReturn(forwards());
        when(chainTunnelService.list(any())).thenReturn(chainTunnels());
        when(speedLimitService.list(any())).thenReturn(speedLimits());
        when(forwardService.deleteForward(anyLong())).thenReturn(R.ok());

        tunnelService = new TestTunnelService();
        ReflectionTestUtils.setField(tunnelService, "forwardService", forwardService);
        ReflectionTestUtils.setField(tunnelService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(tunnelService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(tunnelService, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(tunnelService, "forwardPortService", forwardPortService);

        userService = new TestUserService();
        ReflectionTestUtils.setField(userService, "forwardService", forwardService);
        ReflectionTestUtils.setField(userService, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(userService, "statisticsFlowService", statisticsFlowService);

        CascadeDeletePurger purger = new CascadeDeletePurger();
        ReflectionTestUtils.setField(purger, "forwardService", forwardService);
        ReflectionTestUtils.setField(purger, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(purger, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(purger, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(purger, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(purger, "statisticsFlowService", statisticsFlowService);
        // 事务方法内部删隧道行/用户行仍走同一个服务实例，测试里自引用即可
        ReflectionTestUtils.setField(purger, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(purger, "userService", userService);

        ReflectionTestUtils.setField(tunnelService, "cascadeDeletePurger", purger);
        ReflectionTestUtils.setField(userService, "cascadeDeletePurger", purger);
    }

    @Test
    void deleteTunnelPurgesSpeedLimitAndForwardPort() {
        tunnelService.tunnel = tunnel();

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        assertNull(result.getData(), "全部清理成功时不应带失败明细：" + result.getData());

        assertRemove(forwardService, "tunnel_id", TUNNEL_ID);
        // forward_port 只能按 forward_id 反查：漏掉就是永远查不回来的孤儿端口行
        ArgumentCaptor<Wrapper<ForwardPort>> portCaptor = wrapperCaptor();
        verify(forwardPortService).remove(portCaptor.capture());
        assertIn(portCaptor.getValue(), "forward_id", FORWARD_ID_1, FORWARD_ID_2);
        assertRemove(userTunnelService, "tunnel_id", TUNNEL_ID);
        // 原缺陷：这条删除完全没有
        assertRemove(speedLimitService, "tunnel_id", TUNNEL_ID);
        assertRemove(chainTunnelService, "tunnel_id", TUNNEL_ID);
        assertTrue(events.contains("db:tunnel"), "隧道行必须被删：" + events);

        verify(forwardService).deleteForward(FORWARD_ID_1);
        verify(forwardService).deleteForward(FORWARD_ID_2);

        // 每个节点 × 每条限速记录都要下发一次清理
        for (long nodeId : NODE_IDS) {
            for (long limiterId : SPEED_LIMIT_IDS) {
                assertTrue(events.contains("rpc:limiter:" + nodeId + ":" + limiterId),
                        "节点" + nodeId + " 的限流器 " + limiterId + " 没清：" + events);
            }
            assertTrue(events.contains("rpc:service:" + nodeId + ":" + TUNNEL_ID + "_tls"), events.toString());
            assertTrue(events.contains("rpc:chain:" + nodeId + ":chains_" + TUNNEL_ID), events.toString());
        }
        assertDbWritesBeforeRpc();
    }

    @Test
    void deleteTunnelWithoutForwardsOrSpeedLimitsSkipsEmptyInClause() {
        tunnelService.tunnel = tunnel();
        when(forwardService.list(any())).thenReturn(Collections.emptyList());
        when(speedLimitService.list(any())).thenReturn(Collections.emptyList());

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        // IN () 是非法 SQL：没有 forward 时绝不能拼出这条语句
        verify(forwardPortService, never()).remove(any());
        assertRemove(speedLimitService, "tunnel_id", TUNNEL_ID);
        assertTrue(events.stream().noneMatch(event -> event.startsWith("rpc:limiter:")),
                "没有限速记录就不该下发限流器清理：" + events);
        assertDbWritesBeforeRpc();
    }

    @Test
    void limiterCleanupFailureIsReportedButNotFatal() {
        tunnelService.tunnel = tunnel();
        failingLimiters.add(NODE_IDS[0] + ":" + SPEED_LIMIT_IDS[0]);

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(0, result.getCode(), "节点侧残留只是尽力清理，不能把已完成的删除回滚成失败");
        List<String> leftovers = failures(result);
        assertEquals(1, leftovers.size(), "实际：" + leftovers);
        assertTrue(leftovers.get(0).contains("节点" + NODE_IDS[0]), leftovers.toString());
        assertTrue(leftovers.get(0).contains("limiter " + SPEED_LIMIT_IDS[0]), leftovers.toString());
    }

    @Test
    void deleteTunnelOfMissingTunnelTouchesNothing() {
        tunnelService.tunnel = null;

        R result = tunnelService.deleteTunnel(TUNNEL_ID);

        assertEquals(-1, result.getCode());
        assertEquals("隧道不存在", result.getMsg());
        assertEquals(List.of(), events, "隧道不存在时不该删任何表，也不该下发任何节点");
        verify(forwardService, never()).remove(any());
        verify(speedLimitService, never()).remove(any());
        verify(chainTunnelService, never()).remove(any());
    }

    @Test
    void deleteUserPurgesForwardPortEvenWhenForwardDeleteFailed() {
        userService.user = user(1);
        // 节点离线：deleteForward 返回错误并保留自己的库记录，端口行只能靠级联清理兜住
        when(forwardService.deleteForward(anyLong())).thenReturn(R.err("节点侧服务未删除"));

        R result = userService.deleteUser(USER_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        assertRemove(forwardService, "user_id", USER_ID);
        ArgumentCaptor<Wrapper<ForwardPort>> portCaptor = wrapperCaptor();
        verify(forwardPortService).remove(portCaptor.capture());
        assertIn(portCaptor.getValue(), "forward_id", FORWARD_ID_1, FORWARD_ID_2);
        assertRemove(userTunnelService, "user_id", USER_ID);
        assertRemove(statisticsFlowService, "user_id", USER_ID);
        assertTrue(events.contains("db:user"), "用户行必须被删：" + events);
        assertTrue(events.indexOf("db:user") > events.indexOf("db:forward_port"),
                "用户行要最后删，失败才好整条重试：" + events);
    }

    @Test
    void deleteUserWithoutForwardsSkipsEmptyInClause() {
        userService.user = user(1);
        when(forwardService.list(any())).thenReturn(Collections.emptyList());

        R result = userService.deleteUser(USER_ID);

        assertEquals(0, result.getCode(), result.getMsg());
        verify(forwardPortService, never()).remove(any());
        assertRemove(userTunnelService, "user_id", USER_ID);
        assertTrue(events.contains("db:user"), events.toString());
    }

    @Test
    void deleteMissingOrAdminUserIsRejectedWithoutWrites() {
        userService.user = null;
        R missing = userService.deleteUser(USER_ID);
        assertEquals(-1, missing.getCode());
        assertEquals("用户不存在", missing.getMsg());

        userService.user = user(0);
        R admin = userService.deleteUser(USER_ID);
        assertEquals(-1, admin.getCode());
        assertEquals("请不要作死", admin.getMsg());

        assertEquals(List.of(), events, "前置校验失败时不允许有任何库写");
        verify(forwardService, never()).remove(any());
        verify(forwardPortService, never()).remove(any());
    }

    // ========== 测试脚手架 ==========

    /** 所有库写必须发生在任何节点下发之前（事务里不允许夹网络调用）。 */
    private void assertDbWritesBeforeRpc() {
        int lastDb = -1;
        int firstRpc = Integer.MAX_VALUE;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith("db:")) {
                lastDb = i;
            } else if (events.get(i).startsWith("rpc:") && firstRpc == Integer.MAX_VALUE) {
                firstRpc = i;
            }
        }
        assertTrue(lastDb >= 0, "没有任何库写：" + events);
        assertTrue(firstRpc > lastDb, "节点下发必须排在全部库写之后：" + events);
    }

    private List<String> failures(R result) {
        List<String> failures = new ArrayList<>();
        if (result.getData() instanceof List) {
            for (Object item : (List<?>) result.getData()) {
                failures.add(String.valueOf(item));
            }
        }
        return failures;
    }

    private <T> void recordDbWrite(IService<T> service, String event) {
        when(service.remove(any())).thenAnswer(invocation -> {
            events.add(event);
            return true;
        });
    }

    private <T> void assertRemove(IService<T> service, String column, Object value) {
        ArgumentCaptor<Wrapper<T>> captor = wrapperCaptor();
        verify(service).remove(captor.capture());
        Wrapper<T> wrapper = captor.getValue();
        String segment = wrapper.getSqlSegment().toLowerCase(Locale.ROOT);
        assertTrue(segment.contains(column), "SQL 片段缺少列 " + column + "：" + segment);
        Collection<Object> bound = boundValues(wrapper).values();
        assertTrue(bound.contains(value), "绑定参数缺少 " + value + "，实际：" + bound);
    }

    private static void assertIn(Wrapper<?> wrapper, String column, Object... values) {
        String segment = wrapper.getSqlSegment().toUpperCase(Locale.ROOT);
        assertTrue(segment.contains(column.toUpperCase(Locale.ROOT)), "SQL 片段缺少列 " + column + "：" + segment);
        assertTrue(segment.contains("IN"), "必须是 IN 而不是等值：" + segment);
        Collection<Object> bound = boundValues(wrapper).values();
        for (Object value : values) {
            assertTrue(bound.contains(value), "绑定参数缺少 " + value + "，实际：" + bound);
        }
    }

    /** getParamNameValuePairs 只在 AbstractWrapper 上，实际传入的都是 QueryWrapper 家族。 */
    private static Map<String, Object> boundValues(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private List<Forward> forwards() {
        List<Forward> forwards = new ArrayList<>();
        for (long id : new long[]{FORWARD_ID_1, FORWARD_ID_2}) {
            Forward forward = new Forward();
            forward.setId(id);
            forward.setUserId((int) USER_ID);
            forward.setTunnelId((int) TUNNEL_ID);
            forwards.add(forward);
        }
        return forwards;
    }

    private List<ChainTunnel> chainTunnels() {
        List<ChainTunnel> rows = new ArrayList<>();
        for (long nodeId : NODE_IDS) {
            ChainTunnel row = new ChainTunnel();
            row.setId(nodeId);
            row.setTunnelId(TUNNEL_ID);
            row.setNodeId(nodeId);
            row.setChainType(1);
            rows.add(row);
        }
        return rows;
    }

    private List<SpeedLimit> speedLimits() {
        List<SpeedLimit> limits = new ArrayList<>();
        for (long id : SPEED_LIMIT_IDS) {
            SpeedLimit limit = new SpeedLimit();
            limit.setId(id);
            limit.setTunnelId(TUNNEL_ID);
            limit.setName("limit-" + id);
            limit.setSpeed(10);
            limits.add(limit);
        }
        return limits;
    }

    private Tunnel tunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(TUNNEL_ID);
        tunnel.setName("cascade-test");
        tunnel.setType(1);
        tunnel.setStatus(1);
        return tunnel;
    }

    /** roleId=0 是管理员，删不得。 */
    private User user(int roleId) {
        User user = new User();
        user.setId(USER_ID);
        user.setUser("cascade-test");
        user.setRoleId(roleId);
        return user;
    }

    private static GostDto reply(String msg) {
        GostDto gostDto = new GostDto();
        gostDto.setMsg(msg);
        return gostDto;
    }

    private class TestTunnelService extends TunnelServiceImpl {

        Tunnel tunnel;

        @Override
        public Tunnel getById(Serializable id) {
            return tunnel;
        }

        @Override
        public boolean removeById(Serializable id) {
            events.add("db:tunnel");
            return true;
        }

        @Override
        protected GostDto deleteService(Long nodeId, String name) {
            events.add("rpc:service:" + nodeId + ":" + name);
            return reply("OK");
        }

        @Override
        protected GostDto deleteChains(Long nodeId, String name) {
            events.add("rpc:chain:" + nodeId + ":" + name);
            return reply("OK");
        }

        @Override
        protected GostDto deleteLimiters(Long nodeId, Long limiterId) {
            events.add("rpc:limiter:" + nodeId + ":" + limiterId);
            return failingLimiters.contains(nodeId + ":" + limiterId) ? reply("offline") : reply("OK");
        }
    }

    private class TestUserService extends UserServiceImpl {

        User user;

        @Override
        public User getById(Serializable id) {
            return user;
        }

        @Override
        public boolean removeById(Serializable id) {
            events.add("db:user");
            return true;
        }
    }
}
