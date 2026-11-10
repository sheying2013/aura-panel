package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.UserTunnel;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.impl.UserTunnelServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 撤销隧道权限前的入口节点预检（round50）。
 *
 * <p>真机缺陷：removeUserTunnel 逐条 strict 删除转发，多条转发时前面的删成功、后面的因节点离线失败 ——
 * 权限行保留，转发却已经少了一部分（round49 遗留），用户既看不到完整授权也回不到删除前的状态。
 *
 * <p>这里锁住六件事：① 入口节点离线时一条转发都不删、权限行保留；② 入口节点记录缺失同样中止；
 * ③ 全部在线时正常删完并删权限行；④ 多入口节点里只要有一个离线就整单拒绝；
 * ⑤ 只有 chain_type=1 的入口节点参与判定（查询必须带上这个过滤）；⑥ 没有转发时不做任何节点查询。
 */
class UserTunnelRemovePreflightTest {

    private static final int USER_ID = 3;
    private static final int TUNNEL_ID = 8;
    private static final int USER_TUNNEL_ID = 7;
    private static final long INGRESS_NODE = 1L;
    private static final long SECOND_INGRESS_NODE = 2L;
    private static final long EXIT_NODE = 9L;

    private UserTunnelServiceImpl userTunnelService;
    private UserTunnelMapper userTunnelMapper;
    private ForwardService forwardService;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    /** 在线节点集合：测试里只把这里列出的节点当作在线，其余一律离线。 */
    private final List<Long> onlineNodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        userTunnelMapper = mock(UserTunnelMapper.class);
        forwardService = mock(ForwardService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);

        userTunnelService = new UserTunnelServiceImpl();
        ReflectionTestUtils.setField(userTunnelService, "baseMapper", userTunnelMapper);
        ReflectionTestUtils.setField(userTunnelService, "speedLimitMapper", mock(SpeedLimitMapper.class));
        ReflectionTestUtils.setField(userTunnelService, "forwardService", forwardService);
        ReflectionTestUtils.setField(userTunnelService, "chainTunnelService", chainTunnelService);
        ReflectionTestUtils.setField(userTunnelService, "nodeService", nodeService);
        ReflectionTestUtils.setField(userTunnelService, "nodeOnlineCheck",
                (LongPredicate) onlineNodes::contains);

        when(userTunnelMapper.deleteById(any(Serializable.class))).thenReturn(1);
        when(chainTunnelService.list(any())).thenReturn(Arrays.asList(ingress(TUNNEL_ID, INGRESS_NODE)));
        when(nodeService.getById(INGRESS_NODE)).thenReturn(node(INGRESS_NODE));
        when(nodeService.getById(SECOND_INGRESS_NODE)).thenReturn(node(SECOND_INGRESS_NODE));
        when(forwardService.deleteForward(anyLong(), eq(true))).thenReturn(R.ok());
        onlineNodes.add(INGRESS_NODE);
        onlineNodes.add(SECOND_INGRESS_NODE);
        givenStoredUserTunnel();
    }

    @Test
    void offlineIngressAbortsWithoutDeletingAnything() {
        onlineNodes.remove(INGRESS_NODE);
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A"), forward(12L, "B")));

        R result = userTunnelService.removeUserTunnel(USER_TUNNEL_ID);

        assertEquals(-1, result.getCode(), "入口节点离线时不能报成功");
        assertTrue(result.getMsg().contains("节点" + INGRESS_NODE + "不在线"), result.getMsg());
        assertTrue(result.getMsg().contains("隧道权限未删除"), result.getMsg());
        verify(forwardService, never()).deleteForward(anyLong(), eq(true));
        verify(userTunnelMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void missingIngressNodeRecordAborts() {
        when(nodeService.getById(INGRESS_NODE)).thenReturn(null);
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A")));

        R result = userTunnelService.removeUserTunnel(USER_TUNNEL_ID);

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点" + INGRESS_NODE + "记录不存在"), result.getMsg());
        assertTrue(result.getMsg().contains("隧道权限未删除"), result.getMsg());
        verify(forwardService, never()).deleteForward(anyLong(), eq(true));
        verify(userTunnelMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void allIngressOnlineDeletesEveryForwardAndThePermission() {
        when(forwardService.list(any())).thenReturn(Arrays.asList(
                forward(11L, "A"), forward(12L, "B"), forward(13L, "C")));

        R result = userTunnelService.removeUserTunnel(USER_TUNNEL_ID);

        assertEquals(0, result.getCode(), "预检通过后应按原逻辑删完：" + result.getMsg());
        verify(forwardService, times(3)).deleteForward(anyLong(), eq(true));
        verify(userTunnelMapper, times(1)).deleteById(any(Serializable.class));
    }

    @Test
    void oneOfMultipleIngressNodesOfflineAbortsWholeRequest() {
        when(chainTunnelService.list(any())).thenReturn(Arrays.asList(
                ingress(TUNNEL_ID, INGRESS_NODE), ingress(TUNNEL_ID, SECOND_INGRESS_NODE)));
        onlineNodes.remove(SECOND_INGRESS_NODE);
        when(forwardService.list(any())).thenReturn(Arrays.asList(
                forward(11L, "A"), forward(12L, "B"), forward(13L, "C")));

        R result = userTunnelService.removeUserTunnel(USER_TUNNEL_ID);

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("节点" + SECOND_INGRESS_NODE + "不在线"), result.getMsg());
        assertTrue(result.getMsg().contains("隧道权限未删除"), result.getMsg());
        verify(forwardService, never()).deleteForward(anyLong(), eq(true));
        verify(userTunnelMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void nonIngressChainRowsAreExcludedFromThePreflight() {
        when(forwardService.list(any())).thenReturn(Arrays.asList(forward(11L, "A")));

        R result = userTunnelService.removeUserTunnel(USER_TUNNEL_ID);

        assertEquals(0, result.getCode(), "入口在线即可删除，出口节点不该参与判定：" + result.getMsg());
        // 判定范围靠 SQL 过滤：只有 chain_type=1（入口）的行会进来，出口/中转节点离线也不该拦住删除
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(chainTunnelService, times(1)).list(captor.capture());
        assertTrue(captor.getValue() instanceof QueryWrapper, "预检必须用 QueryWrapper 带条件查询");
        QueryWrapper<?> wrapper = (QueryWrapper<?>) captor.getValue();
        String segment = wrapper.getSqlSegment();
        assertNotNull(segment, "预检查询必须带条件，不能全表扫");
        assertTrue(segment.contains("tunnel_id"), "必须按隧道过滤：" + segment);
        assertTrue(segment.contains("chain_type"), "必须按 chain_type 过滤：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(TUNNEL_ID),
                "tunnel_id 条件值必须是当前隧道：" + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1),
                "chain_type 条件值必须是 1（入口）：" + wrapper.getParamNameValuePairs());
        assertFalse(wrapper.getParamNameValuePairs().containsValue(EXIT_NODE), "出口节点不该进查询条件");
        verify(nodeService, never()).getById(EXIT_NODE);
    }

    @Test
    void noForwardSkipsThePreflightAndJustDropsThePermission() {
        when(forwardService.list(any())).thenReturn(new ArrayList<Forward>());

        R result = userTunnelService.removeUserTunnel(USER_TUNNEL_ID);

        assertEquals(0, result.getCode(), "没有转发就没有半删风险，不该被节点状态拦住：" + result.getMsg());
        verify(chainTunnelService, never()).list(any());
        verify(nodeService, never()).getById(anyLong());
        verify(userTunnelMapper, times(1)).deleteById(any(Serializable.class));
    }

    // ========== 测试脚手架 ==========

    private void givenStoredUserTunnel() {
        UserTunnel stored = new UserTunnel();
        stored.setId(USER_TUNNEL_ID);
        stored.setUserId(USER_ID);
        stored.setTunnelId(TUNNEL_ID);
        stored.setFlow(1000L);
        stored.setNum(5);
        stored.setFlowResetTime(1700000000L);
        stored.setExpTime(1800000000L);
        stored.setStatus(1);
        when(userTunnelMapper.selectById(any(Serializable.class))).thenReturn(stored);
    }

    private Forward forward(Long id, String name) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId(USER_ID);
        forward.setTunnelId(TUNNEL_ID);
        forward.setName(name);
        forward.setRemoteAddr("127.0.0.1:8080");
        forward.setStrategy("round");
        forward.setStatus(1);
        return forward;
    }

    private ChainTunnel ingress(long tunnelId, long nodeId) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId(tunnelId);
        chainTunnel.setChainType(1);
        chainTunnel.setNodeId(nodeId);
        return chainTunnel;
    }

    private Node node(long id) {
        Node node = new Node();
        node.setId(id);
        node.setName("node-" + id);
        node.setStatus(1);
        return node;
    }
}
