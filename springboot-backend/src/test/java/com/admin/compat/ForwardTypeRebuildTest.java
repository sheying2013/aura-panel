package com.admin.compat;

import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ForwardPortService;
import com.admin.service.NodeService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.ForwardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 隧道类型切换时"按隧道重建存量转发入口服务"的本地夹具锁定（ForwardServiceImpl.rebuildTunnelForwardServices）。
 *
 * <p>真机缺口：2→1 之后 entry 服务仍挂 chains_&lt;id&gt;（或 1→2 之后仍直连），必须整条重发；重发时
 * 暂停中的转发不能被复活，缺端口记录要报出来而不是静默跳过，单条失败要汇总但继续处理其它转发，
 * 否则一个坏转发会让整个类型切换回滚。
 */
class ForwardTypeRebuildTest {

    private RecordingForwardService service;
    private ForwardMapper forwardMapper;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private final List<Forward> forwards = new ArrayList<>();
    private final List<ForwardPort> ports = new ArrayList<>();
    private final Map<Long, Node> nodes = new HashMap<>();

    @BeforeEach
    void setUp() {
        forwardMapper = mock(ForwardMapper.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);

        service = new RecordingForwardService();
        ReflectionTestUtils.setField(service, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(service, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(service, "nodeService", nodeService);
        ReflectionTestUtils.setField(service, "userTunnelService", userTunnelService);

        forwards.clear();
        ports.clear();
        nodes.clear();
        nodes.put(1L, node(1L));
        nodes.put(2L, node(2L));
        when(nodeService.getById(any())).thenAnswer(invocation -> nodes.get((Long) invocation.getArgument(0)));
        when(userTunnelService.getOne(any())).thenReturn((UserTunnel) null);
        // 生产实现按 forward_id 查端口行；桩也必须按 wrapper 里的参数过滤，否则多转发用例会互相串行
        when(forwardPortService.list(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ForwardPort> wrapper =
                    invocation.getArgument(0);
            // MP 的 SQL 片段与参数表是懒生成的：先取 sqlSegment 触发一次，再读参数表才不是空
            wrapper.getSqlSegment();
            Object forwardId = wrapper.getParamNameValuePairs().values().stream().findFirst().orElse(null);
            String expected = String.valueOf(forwardId);
            List<ForwardPort> hit = new ArrayList<>();
            for (ForwardPort row : ports) {
                // 参数表里可能是 Integer 也可能是 Long，按字面值比，避免装箱类型不同导致过滤失效
                if (expected.equals(String.valueOf(row.getForwardId()))) {
                    hit.add(row);
                }
            }
            return hit;
        });
        when(forwardMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(forwards));
    }

    @Test
    void pausedForwardsAreSkippedAndActiveOnesRebuiltOnEveryEntry() {
        forwards.add(forward(3L, 1, 1));
        forwards.add(forward(4L, 1, 0)); // 手动暂停：类型切换不能把它复活
        ports.add(port(11L, 3L, 1L, 30100));
        ports.add(port(12L, 3L, 2L, 30200));
        ports.add(port(13L, 4L, 1L, 30101));

        String error = service.rebuildTunnelForwardServices(tunnel(1), List.of(1L, 2L));

        assertNull(error, error);
        assertEquals(List.of("3:1:1:30100", "3:2:1:30200"), service.rebuilt,
                "只重建活跃转发，且每个入口节点各一条：" + service.rebuilt);
        assertTrue(service.rebuilt.stream().noneMatch(s -> s.startsWith("4:")),
                "暂停转发不得被重建：" + service.rebuilt);
    }

    @Test
    void missingPortRecordIsReportedWhileOtherEntriesContinue() {
        forwards.add(forward(3L, 1, 1));
        ports.add(port(12L, 3L, 2L, 30200)); // node1 没有端口行

        String error = service.rebuildTunnelForwardServices(tunnel(2), List.of(1L, 2L));

        assertTrue(error != null && error.contains("转发3在入口节点1缺少端口记录"), String.valueOf(error));
        assertTrue(error.contains("本次已重建 1 个入口服务"), error);
        assertEquals(List.of("3:2:2:30200"), service.rebuilt);
    }

    @Test
    void singleRebuildFailureIsAggregatedAndOthersStillProcessed() {
        forwards.add(forward(3L, 1, 1));
        forwards.add(forward(5L, 1, 1));
        ports.add(port(11L, 3L, 1L, 30100));
        ports.add(port(12L, 5L, 1L, 30101));
        service.failOn.add("3:1");

        String error = service.rebuildTunnelForwardServices(tunnel(2), List.of(1L));

        assertTrue(error != null && error.contains("create service 3_1_0_tcp failed: boom"), String.valueOf(error));
        assertTrue(error.contains("本次已重建 1 个入口服务"), error);
        assertEquals(List.of("3:1", "5:1"), service.calls, "一条失败不能短路后续转发：" + service.calls);
    }

    @Test
    void noForwardInTunnelDoesNothing() {
        assertNull(service.rebuildTunnelForwardServices(tunnel(1), List.of(1L)));
        assertTrue(service.rebuilt.isEmpty(), service.rebuilt.toString());
    }

    @Test
    void serviceNameUsesUserTunnelIdWhenPresent() {
        forwards.add(forward(3L, 7, 1));
        ports.add(port(11L, 3L, 1L, 30100));
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(42);
        UserTunnelService userTunnelService = mock(UserTunnelService.class);
        when(userTunnelService.getOne(any())).thenReturn(userTunnel);
        ReflectionTestUtils.setField(service, "userTunnelService", userTunnelService);

        String error = service.rebuildTunnelForwardServices(tunnel(1), List.of(1L));

        assertNull(error, error);
        assertEquals(List.of("3_7_42"), service.names, "服务名必须仍是 forwardId_userId_userTunnelId：" + service.names);
    }

    // ---------- 夹具 ----------

    private static final class RecordingForwardService extends ForwardServiceImpl {
        private final List<String> rebuilt = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final List<String> failOn = new ArrayList<>();
        private boolean failNext;

        @Override
        protected String rebuildEntryService(Tunnel tunnel, Node node, Forward forward, ForwardPort stored,
                                             String serviceName, Integer limiter) {
            String key = forward.getId() + ":" + node.getId();
            calls.add(key);
            names.add(serviceName);
            if (failOn.contains(key)) {
                return "转发" + forward.getId() + "节点" + node.getId()
                        + "重建失败：create service " + serviceName + "_tcp failed: boom";
            }
            rebuilt.add(key + ":" + tunnel.getType() + ":" + stored.getPort());
            return null;
        }
    }

    private static Forward forward(Long id, Integer userId, int status) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId(userId);
        forward.setTunnelId(2);
        forward.setName("fwd-" + id);
        forward.setStatus(status);
        return forward;
    }

    private static ForwardPort port(Long id, Long forwardId, Long nodeId, int port) {
        ForwardPort row = new ForwardPort();
        row.setId(id);
        row.setForwardId(forwardId);
        row.setNodeId(nodeId);
        row.setPort(port);
        return row;
    }

    private static Node node(Long id) {
        Node node = new Node();
        node.setId(id);
        node.setName("node-" + id);
        node.setStatus(1);
        return node;
    }

    private static Tunnel tunnel(int type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(2L);
        tunnel.setName("sj-rebuild-fixture");
        tunnel.setType(type);
        return tunnel;
    }
}
