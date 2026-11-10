package com.admin.compat;

import com.admin.common.dto.UserTunnelDto;
import com.admin.common.dto.UserTunnelUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.SpeedLimit;
import com.admin.entity.UserTunnel;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.ForwardService;
import com.admin.service.impl.UserTunnelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户隧道授权：并发重复 + speedId 引用校验（round23）。
 *
 * <p>真机缺陷：admin 面板连点两次「分配」或重放请求，两条请求都通过 "先 count 再 save" 的预查，
 * user_tunnel 里落下两条 (user_id, tunnel_id) 完全相同的授权；改限速时填一个已被删掉的 speed_id
 * 也照样落库，节点侧 limiters 里没有这个 id，限速静默失效、面板全绿。
 *
 * <p>这里锁住五件事：① 预查命中直接拒且不写库；② 预查放行但写库撞唯一索引 → 归一成"已拥有"；
 * ③ 其它写库异常必须原样报失败，绝不能变成成功；④ speedId 指向不存在的规则 → 写库前就拒；
 * ⑤ speedId 为 null 或确实存在 → 放行。
 */
class UserTunnelGuardTest {

    private static final int USER_ID = 3;
    private static final int TUNNEL_ID = 8;
    private static final int USER_TUNNEL_ID = 7;

    private UserTunnelServiceImpl userTunnelService;
    private UserTunnelMapper userTunnelMapper;
    private SpeedLimitMapper speedLimitMapper;
    private ForwardService forwardService;
    private UserMapper userMapper;
    private TunnelMapper tunnelMapper;

    @BeforeEach
    void setUp() {
        userTunnelMapper = mock(UserTunnelMapper.class);
        speedLimitMapper = mock(SpeedLimitMapper.class);
        forwardService = mock(ForwardService.class);
        userMapper = mock(UserMapper.class);
        tunnelMapper = mock(TunnelMapper.class);

        userTunnelService = new UserTunnelServiceImpl();
        ReflectionTestUtils.setField(userTunnelService, "baseMapper", userTunnelMapper);
        ReflectionTestUtils.setField(userTunnelService, "speedLimitMapper", speedLimitMapper);
        ReflectionTestUtils.setField(userTunnelService, "forwardService", forwardService);
        ReflectionTestUtils.setField(userTunnelService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userTunnelService, "tunnelMapper", tunnelMapper);

        when(userMapper.selectById(any())).thenReturn(new User());
        when(tunnelMapper.selectById(any())).thenReturn(new Tunnel());
        when(userTunnelMapper.selectCount(any())).thenReturn(0);
        when(userTunnelMapper.insert(any(UserTunnel.class))).thenReturn(1);
        when(userTunnelMapper.selectById(any())).thenReturn(storedUserTunnel());
        when(userTunnelMapper.updateById(any(UserTunnel.class))).thenReturn(1);
        // 改限速会触发"重下发该用户隧道下的转发"，本用例只关心校验与落库，链路上给空集
        when(forwardService.list(any())).thenReturn(new ArrayList<>());
        when(forwardService.resumeExpiryDisabledPausedForUserTunnel(any())).thenReturn(new ArrayList<>());
    }

    @Test
    void assignRejectsWhenPreCheckFindsRow() {
        when(userTunnelMapper.selectCount(any())).thenReturn(1);

        R result = userTunnelService.assignUserTunnel(assignDto());

        assertEquals(-1, result.getCode(), "已存在的授权必须拒绝");
        assertEquals("该用户已拥有此隧道权限", result.getMsg());
        verify(userTunnelMapper, never()).insert(any(UserTunnel.class));
    }

    @Test
    void assignTranslatesUniqueViolationIntoOwnedMessage() {
        when(userTunnelMapper.insert(any(UserTunnel.class))).thenThrow(
                new DuplicateKeyException("UNIQUE constraint failed: user_tunnel.user_id, user_tunnel.tunnel_id"));

        R result = userTunnelService.assignUserTunnel(assignDto());

        assertEquals(-1, result.getCode(), "并发抢先写入后不能报成功");
        assertEquals("该用户已拥有此隧道权限", result.getMsg(), "唯一索引冲突要翻译成友好提示");
        verify(userTunnelMapper, times(1)).insert(any(UserTunnel.class));
    }

    @Test
    void assignReportsWriteFailureForNonDuplicateError() {
        when(userTunnelMapper.insert(any(UserTunnel.class)))
                .thenThrow(new RuntimeException("disk I/O error"));

        R result = userTunnelService.assignUserTunnel(assignDto());

        assertEquals(-1, result.getCode(), "写库失败绝不能返回成功");
        assertTrue(result.getMsg().startsWith("隧道权限写入失败："), result.getMsg());
        assertTrue(result.getMsg().contains("disk I/O error"), "错误信息要带最内层原因：" + result.getMsg());
    }

    @Test
    void otherSqliteConstraintViolationsAreNotReportedAsDuplicate() {
        // SQLITE_CONSTRAINT_NOTNULL / _FOREIGNKEY 与 UNIQUE 同属 SQLITE_CONSTRAINT_* 前缀，
        // 但那是字段缺失或外键问题；把它们译成「已拥有权限」会让真实故障无从排查。
        when(userTunnelMapper.insert(any(UserTunnel.class))).thenThrow(
                new RuntimeException("[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: user_tunnel.status)"));

        R result = userTunnelService.assignUserTunnel(assignDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().startsWith("隧道权限写入失败："), result.getMsg());
        assertTrue(result.getMsg().contains("NOT NULL constraint failed"), result.getMsg());
    }

    @Test
    void assignSucceedsWhenNothingConflicts() {
        R result = userTunnelService.assignUserTunnel(assignDto());

        assertEquals(0, result.getCode(), "预查放行且写库成功应返回成功");
        ArgumentCaptor<UserTunnel> captor = ArgumentCaptor.forClass(UserTunnel.class);
        verify(userTunnelMapper, times(1)).insert(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals(TUNNEL_ID, captor.getValue().getTunnelId());
        assertEquals(1, captor.getValue().getStatus(), "新授权默认启用（status=1）");
    }

    @Test
    void updateRejectsUnknownSpeedIdBeforeWriting() {
        when(speedLimitMapper.selectById(99L)).thenReturn(null);

        R result = userTunnelService.updateUserTunnel(updateDto(99));

        assertEquals(-1, result.getCode(), "引用不存在的限速规则必须拒绝");
        assertEquals("限速规则不存在：id=99", result.getMsg());
        verify(userTunnelMapper, never()).updateById(any(UserTunnel.class));
    }

    @Test
    void updateAllowsNullSpeedId() {
        R result = userTunnelService.updateUserTunnel(updateDto(null));

        assertEquals(0, result.getCode(), "speedId=null 表示不限速，必须放行");
        verify(speedLimitMapper, never()).selectById(any());
        verify(userTunnelMapper, times(1)).updateById(any(UserTunnel.class));
    }

    @Test
    void updateAllowsExistingSpeedId() {
        when(speedLimitMapper.selectById(5L)).thenReturn(speedLimit(5L));

        R result = userTunnelService.updateUserTunnel(updateDto(5));

        assertEquals(0, result.getCode(), "限速规则存在时放行");
        verify(speedLimitMapper, times(1)).selectById(5L);
        verify(userTunnelMapper, times(1)).updateById(any(UserTunnel.class));
    }

    @Test
    void assignRejectsUnknownUserId() {
        when(userMapper.selectById((long) USER_ID)).thenReturn(null);

        R result = userTunnelService.assignUserTunnel(assignDto());

        assertEquals(-1, result.getCode());
        assertEquals("用户不存在：id=" + USER_ID, result.getMsg());
        verify(userTunnelMapper, never()).insert(any(UserTunnel.class));
    }

    @Test
    void assignRejectsUnknownTunnelId() {
        when(tunnelMapper.selectById((long) TUNNEL_ID)).thenReturn(null);

        R result = userTunnelService.assignUserTunnel(assignDto());

        assertEquals(-1, result.getCode());
        assertEquals("隧道不存在：id=" + TUNNEL_ID, result.getMsg());
        verify(userTunnelMapper, never()).insert(any(UserTunnel.class));
    }

    @Test
    void assignRejectsUnknownSpeedId() {
        UserTunnelDto dto = assignDto();
        dto.setSpeedId(99);
        when(speedLimitMapper.selectById(99L)).thenReturn(null);

        R result = userTunnelService.assignUserTunnel(dto);

        assertEquals(-1, result.getCode());
        assertEquals("限速规则不存在：id=99", result.getMsg());
        verify(userTunnelMapper, never()).insert(any(UserTunnel.class));
    }

    @Test
    void assignAllowsExistingSpeedId() {
        UserTunnelDto dto = assignDto();
        dto.setSpeedId(5);
        when(speedLimitMapper.selectById(5L)).thenReturn(speedLimit(5L));

        R result = userTunnelService.assignUserTunnel(dto);

        assertEquals(0, result.getCode());
        verify(speedLimitMapper, times(1)).selectById(5L);
        verify(userTunnelMapper, times(1)).insert(any(UserTunnel.class));
    }

    @Test
    void updateRejectsNullArguments() {
        R r1 = userTunnelService.updateUserTunnel(null);
        assertEquals(-1, r1.getCode());
        assertEquals("参数不能为空", r1.getMsg());

        UserTunnelUpdateDto empty = new UserTunnelUpdateDto();
        R r2 = userTunnelService.updateUserTunnel(empty);
        assertEquals(-1, r2.getCode());
        assertEquals("权限ID不能为空", r2.getMsg());
    }

    @Test
    void removeRejectsNullId() {
        R r = userTunnelService.removeUserTunnel(null);
        assertEquals(-1, r.getCode());
        assertEquals("权限ID不能为空", r.getMsg());
    }

    // ========== 测试脚手架 ==========

    private UserTunnelDto assignDto() {
        UserTunnelDto dto = new UserTunnelDto();
        dto.setUserId(USER_ID);
        dto.setTunnelId(TUNNEL_ID);
        dto.setFlow(1000L);
        dto.setNum(5);
        dto.setFlowResetTime(1700000000L);
        dto.setExpTime(1800000000L);
        return dto;
    }

    private UserTunnelUpdateDto updateDto(Integer speedId) {
        UserTunnelUpdateDto dto = new UserTunnelUpdateDto();
        dto.setId(USER_TUNNEL_ID);
        dto.setFlow(2000L);
        dto.setNum(6);
        dto.setFlowResetTime(1700000000L);
        dto.setExpTime(1800000000L);
        dto.setStatus(1);
        dto.setSpeedId(speedId);
        return dto;
    }

    private UserTunnel storedUserTunnel() {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(USER_TUNNEL_ID);
        userTunnel.setUserId(USER_ID);
        userTunnel.setTunnelId(TUNNEL_ID);
        userTunnel.setFlow(1000L);
        userTunnel.setNum(5);
        userTunnel.setFlowResetTime(1700000000L);
        userTunnel.setExpTime(1800000000L);
        userTunnel.setStatus(1);
        return userTunnel;
    }

    @Test
    void assignRejectsCrossTunnelSpeedLimit() {
        UserTunnelDto dto = assignDto();
        dto.setSpeedId(5);
        // 限速规则属于其他隧道 999，当前授权的是隧道 8
        when(speedLimitMapper.selectById(5L)).thenReturn(speedLimit(5L, 999L));

        R result = userTunnelService.assignUserTunnel(dto);

        assertEquals(-1, result.getCode(), "跨隧道分配限速规则必须拒绝");
        assertTrue(result.getMsg().contains("限速规则不属于该隧道"), result.getMsg());
        verify(userTunnelMapper, never()).insert(any(UserTunnel.class));
    }

    @Test
    void updateRejectsCrossTunnelSpeedLimit() {
        // 限速规则属于其他隧道 999，当前授权的是隧道 8
        when(speedLimitMapper.selectById(5L)).thenReturn(speedLimit(5L, 999L));

        R result = userTunnelService.updateUserTunnel(updateDto(5));

        assertEquals(-1, result.getCode(), "更新为其他隧道的限速规则必须拒绝");
        assertTrue(result.getMsg().contains("限速规则不属于该隧道"), result.getMsg());
        verify(userTunnelMapper, never()).updateById(any(UserTunnel.class));
    }

    private SpeedLimit speedLimit(long id) {
        return speedLimit(id, (long) TUNNEL_ID);
    }

    private SpeedLimit speedLimit(long id, Long tunnelId) {
        SpeedLimit speedLimit = new SpeedLimit();
        speedLimit.setId(id);
        speedLimit.setName("rule-" + id);
        speedLimit.setSpeed(10);
        speedLimit.setStatus(0);
        speedLimit.setTunnelId(tunnelId);
        return speedLimit;
    }
}
