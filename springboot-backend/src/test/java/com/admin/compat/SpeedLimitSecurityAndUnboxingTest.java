package com.admin.compat;

import com.admin.common.dto.SpeedLimitDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.SpeedLimit;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.SpeedLimitServiceImpl;
import com.admin.service.impl.TunnelServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round58 限速规则跨隧道安全与拆箱空指针安全回归测试。
 *
 * <p>排查确证缺陷：
 * 1. UserTunnelServiceImpl.assignUserTunnel 与 updateUserTunnel 未校验 speed_limit.tunnel_id 与 user_tunnel.tunnel_id 一致性，
 *    导致可跨隧道绑定其他隧道的限速规则，GOST 节点找不到对应 limiter 静默放行造成偷跑带宽；
 * 2. SpeedLimitServiceImpl.convertBitsToMBps 直接接收包装类 Integer，遇到 null 或非法非正数自动拆箱 NPE，
 *    deleteSpeedLimit 遇到库中 speed 为 null 无法删除，resync/reconcile 循环同样存在未判空风险；
 * 3. SpeedLimitServiceImpl.createSpeedLimit 未对齐真实数据库 tunnel.name；
 * 4. TunnelServiceImpl.userTunnel 中 roleId == 0 遇到 token 无 role_id 时触发自动拆箱 NPE；
 * 5. cleanTunnelLimiters 对 speedLimits 集合或项缺少 null 防御；
 * 6. ForwardServiceImpl.UserInfo 中 roleId == null 在 isAdmin / roleId != 0 比较中引起拆箱 NPE。
 */
class SpeedLimitSecurityAndUnboxingTest {

    private SpeedLimitServiceImpl speedLimitService;
    private SpeedLimitMapper speedLimitMapper;
    private TunnelService tunnelService;
    private ChainTunnelService chainTunnelService;
    private UserTunnelService userTunnelService;

    private TunnelServiceImpl tunnelServiceImpl;
    private TunnelMapper tunnelMapper;
    private UserTunnelMapper userTunnelMapper;

    @BeforeEach
    void setUp() {
        speedLimitMapper = mock(SpeedLimitMapper.class);
        tunnelService = mock(TunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        userTunnelService = mock(UserTunnelService.class);

        speedLimitService = new SpeedLimitServiceImpl();
        ReflectionTestUtils.setField(speedLimitService, "baseMapper", speedLimitMapper);
        ReflectionTestUtils.setField(speedLimitService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(speedLimitService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(speedLimitService, "userTunnelService", userTunnelService);

        tunnelMapper = mock(TunnelMapper.class);
        userTunnelMapper = mock(UserTunnelMapper.class);
        tunnelServiceImpl = new TunnelServiceImpl();
        ReflectionTestUtils.setField(tunnelServiceImpl, "baseMapper", tunnelMapper);
        ReflectionTestUtils.setField(tunnelServiceImpl, "userTunnelMapper", userTunnelMapper);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("convertBitsToMBps 遇到 null 或非正数应安全返回 0.0 而非拆箱抛 NPE")
    void convertBitsToMBpsNullAndNonPositiveSafe() {
        String resNull = ReflectionTestUtils.invokeMethod(speedLimitService, "convertBitsToMBps", (Integer) null);
        assertEquals("0.0", resNull);

        String resNegative = ReflectionTestUtils.invokeMethod(speedLimitService, "convertBitsToMBps", -80);
        assertEquals("0.0", resNegative);

        String resZero = ReflectionTestUtils.invokeMethod(speedLimitService, "convertBitsToMBps", 0);
        assertEquals("0.0", resZero);

        String resPositive = ReflectionTestUtils.invokeMethod(speedLimitService, "convertBitsToMBps", 80);
        assertEquals("10.0", resPositive);

        String resDecimal = ReflectionTestUtils.invokeMethod(speedLimitService, "convertBitsToMBps", 12);
        assertEquals("1.5", resDecimal);
    }

    @Test
    @DisplayName("deleteSpeedLimit 当 id 为 null 时必须直接拦截并返回友好错误")
    void deleteSpeedLimitRejectsNullId() {
        R r = speedLimitService.deleteSpeedLimit(null);
        assertEquals(-1, r.getCode());
        assertEquals("限速规则ID不能为空", r.getMsg());
        verify(speedLimitMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("deleteSpeedLimit 当库中 speed 为 null 时不抛 NPE，安全校验使用者")
    void deleteSpeedLimitSafeWhenSpeedIsNull() {
        SpeedLimit limit = new SpeedLimit();
        limit.setId(10L);
        limit.setTunnelId(100L);
        limit.setSpeed(null); // speed 为空

        when(speedLimitMapper.selectById(10L)).thenReturn(limit);
        when(userTunnelService.count(any())).thenReturn(1); // 还有用户使用

        R r = speedLimitService.deleteSpeedLimit(10L);
        assertEquals(-1, r.getCode());
        assertEquals("该限速规则还有用户在使用 请先取消分配", r.getMsg());
    }

    @Test
    @DisplayName("createSpeedLimit 必须强制对齐数据库中真实隧道的名称，防止传入伪造名称")
    void createSpeedLimitAlignsRealTunnelName() {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(100L);
        tunnel.setName("RealVerifiedTunnel");

        when(tunnelService.getById(100L)).thenReturn(tunnel);
        when(speedLimitMapper.selectCount(any())).thenReturn(0);
        when(speedLimitMapper.insert(any(SpeedLimit.class))).thenReturn(1);
        when(chainTunnelService.list(any())).thenReturn(Collections.emptyList());

        SpeedLimitDto dto = new SpeedLimitDto();
        dto.setName("test-limit");
        dto.setTunnelId(100L);
        dto.setTunnelName("ForgedFakeTunnelName"); // 伪造的名字
        dto.setSpeed(8000000);

        R r = speedLimitService.createSpeedLimit(dto);
        assertEquals(0, r.getCode());

        ArgumentCaptor<SpeedLimit> captor = ArgumentCaptor.forClass(SpeedLimit.class);
        verify(speedLimitMapper, times(1)).insert(captor.capture());
        assertEquals("RealVerifiedTunnel", captor.getValue().getTunnelName(), "入库的隧道名称必须是真实隧道名");
    }

    @Test
    @DisplayName("TunnelServiceImpl.userTunnel 当 Token 缺乏 role_id (null) 时不抛 NPE，作为普通用户安全返回")
    void userTunnelSafeWhenRoleIdIsNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 构造一个合法的三段式 token，payload 包含 sub 无 role_id
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":123}".getBytes(StandardCharsets.UTF_8));
        String fakeToken = header + "." + payload + ".fakesig";

        request.addHeader("Authorization", fakeToken);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(userTunnelMapper.selectList(any())).thenReturn(Collections.emptyList());

        R r = tunnelServiceImpl.userTunnel();
        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertTrue(((List<?>) r.getData()).isEmpty());
    }

    @Test
    @DisplayName("TunnelServiceImpl.cleanTunnelLimiters 对 null 列表或项安全防守")
    void cleanTunnelLimitersSafeWithNull() {
        List<String> leftovers = new ArrayList<>();
        // 传入 null 列表
        ReflectionTestUtils.invokeMethod(tunnelServiceImpl, "cleanTunnelLimiters", 1L, null, leftovers);
        assertTrue(leftovers.isEmpty());

        // 传入含 null 项列表
        List<SpeedLimit> list = new ArrayList<>();
        list.add(null);
        SpeedLimit emptyId = new SpeedLimit();
        emptyId.setId(null);
        list.add(emptyId);

        ReflectionTestUtils.invokeMethod(tunnelServiceImpl, "cleanTunnelLimiters", 1L, list, leftovers);
        assertTrue(leftovers.isEmpty());
    }
}
