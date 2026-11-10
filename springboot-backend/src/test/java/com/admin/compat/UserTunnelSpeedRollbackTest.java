package com.admin.compat;

import com.admin.common.dto.UserTunnelUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.SpeedLimit;
import com.admin.entity.UserTunnel;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.impl.UserTunnelServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户隧道限速变更的「库/节点一致性」（round49）。
 *
 * <p>真机缺陷：改限速时先 updateById 写库（含 speed_id），再逐条 updateForward 让节点重下发限速，
 * 但 updateForward 的返回值被整个丢弃 —— 节点离线/下发失败时接口照样返回成功：库里限速是新值、
 * 节点仍按旧限速跑，面板全绿。删权限行时同样丢掉 deleteForward 的返回值，某个转发没删掉也会照样
 * 删掉权限行，节点上的转发继续监听却再也没有授权。
 *
 * <p>这里锁住七件事：① 任一下发失败必须把 speed_id 回滚成旧值并报错；② 全部成功才保留新值；
 * ③ 旧值为 null 时也要能回滚成 null（UpdateWrapper.set，实体 setter 会跳过 null）；
 * ④ 多条转发失败要在文案里逐条列出；⑤ deleteForward 失败必须中止、不删权限行；
 * ⑥ deleteForward 的 R.ok(List) 残留说明要带回前端；⑦ 限速没变就不重下发。
 */
class UserTunnelSpeedRollbackTest {

    private static final int USER_ID = 3;
    private static final int TUNNEL_ID = 8;
    private static final int USER_TUNNEL_ID = 7;
    private static final int OLD_SPEED_ID = 3;
    private static final int NEW_SPEED_ID = 5;
    private static final long INGRESS_NODE_ID = 1L;

    /** UpdateWrapper 的 SET 参数占位符是 #{ew.paramNameValuePairs.MPGENVALn}，这里把参数名抠出来查真实取值。 */
    private static final Pattern SET_PARAM = Pattern.compile("paramNameValuePairs\\.([A-Za-z0-9_]+)");

    private UserTunnelServiceImpl userTunnelService;
    private UserTunnelMapper userTunnelMapper;
    private SpeedLimitMapper speedLimitMapper;
    private ForwardService forwardService;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;

    @BeforeEach
    void setUp() {
        userTunnelMapper = mock(UserTunnelMapper.class);
        speedLimitMapper = mock(SpeedLimitMapper.class);
        forwardService = mock(ForwardService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);

        userTunnelService = new UserTunnelServiceImpl();
        ReflectionTestUtils.setField(userTunnelService, "baseMapper", userTunnelMapper);
        ReflectionTestUtils.setField(userTunnelService, "speedLimitMapper", speedLimitMapper);
        ReflectionTestUtils.setField(userTunnelService, "forwardService", forwardService);
        // round50：removeUserTunnel 先做入口节点在线预检，这里给一个恒在线的入口，避免本类的
        // 「删除中止」用例被预检提前拦下（预检本身由 UserTunnelRemovePreflightTest 覆盖）。
        ReflectionTestUtils.setField(userTunnelService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(userTunnelService, "nodeService", nodeService);
        ReflectionTestUtils.setField(userTunnelService, "nodeOnlineCheck", (LongPredicate) nodeId -> true);
        when(chainTunnelService.list(any())).thenReturn(Arrays.asList(ingressNode()));
        when(nodeService.getById(INGRESS_NODE_ID)).thenReturn(ingressNodeRecord());

        when(userTunnelMapper.updateById(any(UserTunnel.class))).thenReturn(1);
        when(userTunnelMapper.update(any(), any())).thenReturn(1);
        when(userTunnelMapper.deleteById(any(Serializable.class))).thenReturn(1);
        when(speedLimitMapper.selectById(any(Serializable.class))).thenReturn(speedLimit(NEW_SPEED_ID));
        when(forwardService.list(any())).thenReturn(new ArrayList<>());
        when(forwardService.resumeExpiryDisabledPausedForUserTunnel(any())).thenReturn(new ArrayList<>());
    }

    @Test
    void dbWriteFailureStopsBeforePushingToNodes() {
        givenStoredUserTunnel(OLD_SPEED_ID);
        when(userTunnelMapper.updateById(any(UserTunnel.class))).thenReturn(0);
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A")));

        R result = userTunnelService.updateUserTunnel(updateDto(NEW_SPEED_ID));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("影响 0 行"), result.getMsg());
        verify(forwardService, never()).updateForward(any());
        verify(userTunnelMapper, never()).update(any(), any());
    }

    @Test
    void speedChangeFailureRollsBackDb() {
        givenStoredUserTunnel(OLD_SPEED_ID);
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A")));
        when(forwardService.updateForward(any())).thenReturn(R.err("节点1：i/o timeout"));

        R result = userTunnelService.updateUserTunnel(updateDto(NEW_SPEED_ID));

        assertEquals(-1, result.getCode(), "节点没按新限速生效，接口不能报成功");
        assertTrue(result.getMsg().startsWith("限速下发失败，其余字段已更新；限速已回滚为原值（speedId="), result.getMsg());
        assertTrue(result.getMsg().contains("其余字段已更新"), "必须说清其它字段未回滚：" + result.getMsg());
        assertTrue(result.getMsg().contains("转发A(节点1：i/o timeout)"), "失败明细要带转发名与原因：" + result.getMsg());
        assertEquals(Integer.valueOf(OLD_SPEED_ID), rolledBackSpeedId(), "库内 speed_id 必须回到旧值");
    }

    @Test
    void speedChangeSuccessKeepsNewValue() {
        givenStoredUserTunnel(OLD_SPEED_ID);
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A")));
        when(forwardService.updateForward(any())).thenReturn(R.ok());

        R result = userTunnelService.updateUserTunnel(updateDto(NEW_SPEED_ID));

        assertEquals(0, result.getCode(), "全部转发下发成功才算成功：" + result.getMsg());
        ArgumentCaptor<UserTunnel> captor = ArgumentCaptor.forClass(UserTunnel.class);
        verify(userTunnelMapper, times(1)).updateById(captor.capture());
        assertEquals(Integer.valueOf(NEW_SPEED_ID), captor.getValue().getSpeedId(), "新限速要落库");
        verify(userTunnelMapper, never()).update(isNull(), any());
        verify(forwardService, times(1)).updateForward(any());
    }

    @Test
    void rollbackToNullWorks() {
        // 原本不限速（speed_id = null），这次设了限速但节点没生效 → 必须能回滚成 null
        givenStoredUserTunnel(null);
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A")));
        when(forwardService.updateForward(any())).thenReturn(R.err("节点1：节点离线"));

        R result = userTunnelService.updateUserTunnel(updateDto(NEW_SPEED_ID));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().startsWith("限速下发失败，其余字段已更新；限速已回滚为原值（speedId="), result.getMsg());
        assertTrue(result.getMsg().contains("其余字段已更新"), "必须说清其它字段未回滚：" + result.getMsg());
        assertNull(rolledBackSpeedId(), "旧值为 null 时必须 SET speed_id = null");
    }

    @Test
    void multipleForwardsAggregateFailures() {
        givenStoredUserTunnel(OLD_SPEED_ID);
        when(forwardService.list(any())).thenReturn(Arrays.asList(
                forward(11L, "A"), forward(12L, "B"), forward(13L, "C")));
        when(forwardService.updateForward(any()))
                .thenReturn(R.ok())
                .thenReturn(R.err("节点2：节点离线"))
                .thenReturn(null);

        R result = userTunnelService.updateUserTunnel(updateDto(NEW_SPEED_ID));

        assertEquals(-1, result.getCode(), "有任何一条失败就不能报成功");
        String msg = result.getMsg();
        assertTrue(msg.contains("转发B(节点2：节点离线)"), "失败明细要逐条列出：" + msg);
        assertTrue(msg.contains("转发C(节点无返回)"), "空返回值也要算失败并说明：" + msg);
        assertTrue(!msg.contains("转发A("), "成功的转发不该出现在失败明细里：" + msg);
        assertEquals(Integer.valueOf(OLD_SPEED_ID), rolledBackSpeedId(), "多条失败只回滚一次，值为旧值");
    }

    @Test
    void removeAbortsWhenDeleteForwardFails() {
        givenStoredUserTunnel(OLD_SPEED_ID);
        when(forwardService.list(any())).thenReturn(Arrays.asList(
                forward(11L, "A"), forward(12L, "B"), forward(13L, "C")));
        when(forwardService.deleteForward(11L, true)).thenReturn(R.ok());
        when(forwardService.deleteForward(12L, true)).thenReturn(R.err("节点2：i/o timeout"));

        R result = userTunnelService.removeUserTunnel(USER_TUNNEL_ID);

        assertEquals(-1, result.getCode(), "转发没删掉就不能报成功");
        assertEquals("转发B删除失败：节点2：i/o timeout，隧道权限未删除", result.getMsg());
        verify(userTunnelMapper, never()).deleteById(any(Serializable.class));
        verify(forwardService, times(2)).deleteForward(anyLong(), eq(true));
    }

    @Test
    void removeAbortsWhenStrictDeleteReportsNodeLeftover() {
        givenStoredUserTunnel(OLD_SPEED_ID);
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A")));
        // 严格模式把「节点没删掉」升级成错误：库行必须保留，否则离线节点会按已撤销的权限继续放量
        when(forwardService.deleteForward(11L, true))
                .thenReturn(R.err("节点侧服务未删除：节点1：i/o timeout，库里记录已保留"));

        R result = userTunnelService.removeUserTunnel(USER_TUNNEL_ID);

        assertEquals(-1, result.getCode(), "节点残留必须阻止权限删除");
        assertTrue(result.getMsg().contains("隧道权限未删除"), result.getMsg());
        assertTrue(result.getMsg().contains("节点1"), result.getMsg());
        verify(userTunnelMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void nonSpeedUpdateDoesNotRepush() {
        givenStoredUserTunnel(NEW_SPEED_ID);
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A")));

        R result = userTunnelService.updateUserTunnel(updateDto(NEW_SPEED_ID));

        assertEquals(0, result.getCode(), "限速没变时更新应成功：" + result.getMsg());
        verify(forwardService, never()).updateForward(any());
        verify(userTunnelMapper, never()).update(isNull(), any());
    }

    @Test
    void rollbackFailureMentionsManualCheck() {
        givenStoredUserTunnel(OLD_SPEED_ID);
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A")));
        when(forwardService.updateForward(any())).thenReturn(R.err("节点1：节点离线"));
        when(userTunnelMapper.update(any(), any())).thenReturn(0);

        R result = userTunnelService.updateUserTunnel(updateDto(NEW_SPEED_ID));

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("回滚失败"), result.getMsg());
        assertTrue(result.getMsg().contains("库内 speed_id 仍为 " + NEW_SPEED_ID), result.getMsg());
        assertTrue(result.getMsg().contains("需人工核对"), result.getMsg());
    }

    // ========== 测试脚手架 ==========

    private void givenStoredUserTunnel(Integer speedId) {
        UserTunnel stored = new UserTunnel();
        stored.setId(USER_TUNNEL_ID);
        stored.setUserId(USER_ID);
        stored.setTunnelId(TUNNEL_ID);
        stored.setFlow(1000L);
        stored.setNum(5);
        stored.setFlowResetTime(1700000000L);
        stored.setExpTime(1800000000L);
        stored.setStatus(1);
        stored.setSpeedId(speedId);
        when(userTunnelMapper.selectById(any(Serializable.class))).thenReturn(stored);
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

    private ChainTunnel ingressNode() {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId((long) TUNNEL_ID);
        chainTunnel.setChainType(1);
        chainTunnel.setNodeId(INGRESS_NODE_ID);
        return chainTunnel;
    }

    private Node ingressNodeRecord() {
        Node node = new Node();
        node.setId(INGRESS_NODE_ID);
        node.setName("entry");
        node.setStatus(1);
        return node;
    }

    private Forward forward(Long id, String name) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setName(name);
        forward.setRemoteAddr("127.0.0.1:8080");
        forward.setStrategy("round");
        forward.setStatus(0);
        return forward;
    }

    private SpeedLimit speedLimit(long id) {
        SpeedLimit speedLimit = new SpeedLimit();
        speedLimit.setId(id);
        speedLimit.setName("rule-" + id);
        speedLimit.setSpeed(10);
        speedLimit.setStatus(0);
        return speedLimit;
    }

    /** 取回滚语句里真正写进 speed_id 的值：既验证「只改这一列」，也验证 null 回滚是真的 null。 */
    private Object rolledBackSpeedId() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userTunnelMapper, times(1)).update(isNull(), captor.capture());
        Wrapper<?> wrapper = captor.getValue();
        assertTrue(wrapper instanceof UpdateWrapper, "回滚必须用 UpdateWrapper：只允许改 speed_id 一列");
        UpdateWrapper<?> updateWrapper = (UpdateWrapper<?>) wrapper;
        String setSql = updateWrapper.getSqlSet();
        assertNotNull(setSql, "回滚必须带 SET 子句");
        assertTrue(setSql.startsWith("speed_id="), "回滚只允许改 speed_id：" + setSql);
        Matcher matcher = SET_PARAM.matcher(setSql);
        assertTrue(matcher.find(), "无法解析 SET 参数占位符：" + setSql);
        return updateWrapper.getParamNameValuePairs().get(matcher.group(1));
    }
}
