package com.admin.compat;

import com.admin.common.dto.ResetFlowDto;
import com.admin.common.lang.R;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.UserMapper;
import com.admin.service.ForwardService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManualResetColumnSafetyTest {
    UserServiceImpl service; UserMapper users; UserTunnelService tunnels; ForwardService forwards;
    @BeforeEach void setup() {
        users = mock(UserMapper.class); tunnels = mock(UserTunnelService.class); forwards = mock(ForwardService.class);
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", users);
        ReflectionTestUtils.setField(service, "userTunnelService", tunnels);
        ReflectionTestUtils.setField(service, "forwardService", forwards);
    }
    private ResetFlowDto request(int type) { ResetFlowDto d = new ResetFlowDto(); d.setId(7); d.setType(type); return d; }
    @Test void accountResetUpdatesOnlyFlowColumns() {
        User u = new User(); u.setId(7L); u.setExpTime(1234L); u.setStatus(1); u.setFlow(10L); u.setInFlow(8L); u.setOutFlow(3L);
        when(users.selectById(any())).thenReturn(u);
        when(users.update(isNull(), any())).thenReturn(1);
        when(forwards.resumeQuotaPausedForUser(eq(7L), eq(true), anyLong())).thenReturn(List.of());
        assertEquals(0, service.reset(request(1)).getCode());
        ArgumentCaptor<UpdateWrapper<User>> c = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(users).update(isNull(), c.capture());
        String sql = c.getValue().getSqlSet();
        assertTrue(sql.contains("in_flow") && sql.contains("out_flow"), sql);
        assertFalse(sql.contains("exp_time") || sql.contains("status") || sql.contains("token_version"), sql);
    }
    @Test void tunnelResetKeepsSpeedAndExpiryFields() {
        UserTunnel t = new UserTunnel(); t.setId(7); t.setSpeedId(42); t.setExpTime(1234L); t.setStatus(1); t.setFlow(10L); t.setInFlow(8L); t.setOutFlow(3L);
        when(tunnels.getById(7)).thenReturn(t);
        when(tunnels.update(isNull(), any())).thenReturn(true);
        when(forwards.resumeQuotaPausedForUserTunnel(eq(7), eq(true), anyLong())).thenReturn(List.of());
        assertEquals(0, service.reset(request(2)).getCode());
        ArgumentCaptor<UpdateWrapper<UserTunnel>> c = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(tunnels).update(isNull(), c.capture());
        String sql = c.getValue().getSqlSet();
        assertTrue(sql.contains("in_flow") && sql.contains("out_flow"), sql);
        assertFalse(sql.contains("speed_id") || sql.contains("exp_time") || sql.contains("status"), sql);
    }
    @Test void failedResetDoesNotResumeForward() {
        UserTunnel t = new UserTunnel(); t.setId(7); t.setFlow(10L); t.setInFlow(8L); t.setOutFlow(3L);
        when(tunnels.getById(7)).thenReturn(t);
        when(tunnels.update(isNull(), any())).thenReturn(false);
        R result = service.reset(request(2));
        assertNotEquals(0, result.getCode());
        verifyNoInteractions(forwards);
    }
}
