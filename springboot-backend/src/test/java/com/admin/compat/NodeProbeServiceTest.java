package com.admin.compat;

import com.admin.common.nodehealth.NodeHealthDataException;
import com.admin.common.nodehealth.NodeProbeResult;
import com.admin.common.nodehealth.NodeProbeService;
import com.admin.common.nodehealth.NodeProbeStatus;
import com.admin.common.nodehealth.NodeProbeTransport;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点在线探测判定回归测试。
 *
 * <p>覆盖的原缺陷：{@code Node.status} 只在 WS 建连/断连时被改写，网络黑洞、容器 pause、
 * kill -9 都不会触发 afterConnectionClosed，库里于是长期留着 status=1，前端把它当在线。
 * 这里锁死三件事：
 * ① 从未探测 = UNKNOWN，绝不折算成 ONLINE；
 * ② 控制通道不在 = OFFLINE，且根本不发命令（不白等 10 秒）；
 * ③ 通道在但命令无回包 = DEGRADED（半死连接），只有拿到回包才是 ONLINE；
 * ④ 结论超过保鲜期降为 UNKNOWN，但保留上次检测时间供界面展示。
 */
class NodeProbeServiceTest {

    private static final long INTERVAL = NodeProbeService.DEFAULT_INTERVAL_MS;
    private static final long FRESH = NodeProbeService.DEFAULT_FRESHNESS_MS;
    private static final long T0 = 1_700_000_000_000L;

    private NodeService nodeService;
    private NodeProbeTransport transport;
    private NodeProbeService service;
    private Set<Long> connected;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        nodeService = mock(NodeService.class);
        transport = mock(NodeProbeTransport.class);
        connected = new HashSet<>();
        clock = new MutableClock(T0);
        service = new NodeProbeService(nodeService, transport, id -> connected.contains(id), clock, INTERVAL, FRESH);
    }

    // ========== 1) 没测过 ≠ 在线 ==========

    @Test
    void neverProbedIsUnknownNotOnline() {
        NodeProbeResult result = service.effective(7L);

        assertEquals(NodeProbeStatus.UNKNOWN, result.status(), "没有探测结论只能报未知");
        assertFalse(result.controlPlaneReachable(), "未检测的节点不得被折算成在线");
        assertFalse(result.probed(), "checkedAt 必须为 0，调用方才能区分「测过失败」和「根本没测」");
        assertFalse(service.isStale(result), "没测过不是「过期」，是「未检测」");
    }

    // ========== 2) 控制通道不在：判离线且不发命令 ==========

    @Test
    void noControlChannelIsOfflineAndSendsNothing() {
        NodeProbeResult result = service.probeNode(node(7L));

        assertEquals(NodeProbeStatus.OFFLINE, result.status());
        assertTrue(result.probed(), "离线也是测过的结论，要落检测时间");
        verify(transport, never()).probe(any());
    }

    // ========== 3) 通道在但无回包 = DEGRADED ==========

    @Test
    void connectedButNoReplyIsDegraded() {
        connected.add(7L);
        when(transport.probe(7L)).thenReturn(NodeProbeTransport.Reply.noReply("等待响应超时"));

        NodeProbeResult result = service.probeNode(node(7L));

        assertEquals(NodeProbeStatus.DEGRADED, result.status(), "半死连接不能被说成在线");
        assertFalse(result.controlPlaneReachable());
        assertTrue(result.detail().contains("等待响应超时"), "失败原因要能直接展示给运维");
    }

    // ========== 4) 拿到回包 = ONLINE ==========

    @Test
    void replyMakesNodeOnlineWithLatency() {
        connected.add(7L);
        when(transport.probe(7L)).thenReturn(NodeProbeTransport.Reply.ok(41L, "命令往返成功"));

        NodeProbeResult result = service.probeNode(node(7L));

        assertEquals(NodeProbeStatus.ONLINE, result.status());
        assertTrue(result.controlPlaneReachable());
        assertEquals(41L, result.latencyMs());
        assertEquals(T0, result.checkedAt());
    }

    // ========== 5) 探测抛异常 = UNKNOWN，不给在线 ==========

    @Test
    void probeExceptionIsUnknownNotOnline() {
        connected.add(7L);
        when(transport.probe(7L)).thenThrow(new IllegalStateException("boom"));

        assertEquals(NodeProbeStatus.UNKNOWN, service.probeNode(node(7L)).status());
    }

    @Test
    void connectionCheckExceptionIsUnknown() {
        NodeProbeService broken = new NodeProbeService(nodeService, transport,
                id -> {
                    throw new IllegalStateException("session map broken");
                }, clock, INTERVAL, FRESH);

        assertEquals(NodeProbeStatus.UNKNOWN, broken.probeNode(node(9L)).status());
    }

    // ========== 6) 结论过期：降为 UNKNOWN，但保留上次检测时间 ==========

    @Test
    void staleResultFallsBackToUnknownButKeepsCheckedAt() {
        connected.add(7L);
        when(transport.probe(7L)).thenReturn(NodeProbeTransport.Reply.ok(10L, "OK"));
        service.probeNode(node(7L));
        assertEquals(NodeProbeStatus.ONLINE, service.effective(7L).status());

        clock.advance(FRESH + 1L);

        NodeProbeResult stale = service.effective(7L);
        assertEquals(NodeProbeStatus.UNKNOWN, stale.status(), "超过保鲜期只承认未知，不能拿两天前的结论充数");
        assertFalse(stale.controlPlaneReachable());
        assertEquals(T0, stale.checkedAt(), "过期仍要保留上次检测时间，界面才能显示「上次检测」");
        assertTrue(service.isStale(stale));
    }

    // ========== 7) 一轮的聚合计数 ==========

    @Test
    void roundAggregatesCountsAndNextDue() {
        when(nodeService.list()).thenReturn(Arrays.asList(node(1L), node(2L), node(3L)));
        connected.add(2L);
        connected.add(3L);
        when(transport.probe(2L)).thenReturn(NodeProbeTransport.Reply.noReply("等待响应超时"));
        when(transport.probe(3L)).thenReturn(NodeProbeTransport.Reply.ok(5L, "OK"));

        NodeProbeService.ProbeRound round = service.probeIfDue("test").orElseThrow();

        assertEquals(3, round.total());
        assertEquals(1, round.online());
        assertEquals(1, round.degraded());
        assertEquals(1, round.offline());
        assertEquals(0, round.unknown());
        assertEquals(T0, round.startedAt());
        assertNotNull(service.lastRound());
        assertEquals(INTERVAL, service.nextDueAt() - round.startedAt(),
                "下一轮 = 上次尝试（= 本轮开始）+ 12 小时：节流只看尝试时间，完成时间不参与");
    }

    // ========== 8) 查节点列表失败必须显式失败，且不消耗本轮节奏 ==========

    @Test
    void nodeListFailureIsReportedAndDoesNotConsumeTheRound() {
        when(nodeService.list()).thenThrow(new IllegalStateException("db down"));

        NodeHealthDataException failure = assertThrows(NodeHealthDataException.class,
                () -> service.probeIfDue("test"));
        assertTrue(failure.getMessage().contains("db down"), "失败原因要带出来，不能只说「失败」");
        assertNull(service.lastRound(), "没读到节点列表就没有轮次，不能编一个 total=0 的成功轮次");
        assertNull(service.nextDueAt(), "失败不记账，重启后仍应立即补测");

        doReturn(Arrays.asList(node(1L))).when(nodeService).list();
        assertTrue(service.probeIfDue("test").isPresent(), "上一次根本没探测，不该把这 12 小时算掉");
    }

    @Test
    void halfBrokenConnectionIsNotTreatedAsConnected() {
        NodeProbeService broken = new NodeProbeService(nodeService, transport,
                id -> {
                    throw new IllegalStateException("session map broken");
                }, clock, INTERVAL, FRESH);

        assertFalse(broken.isConnected(7L), "读连接态异常只能算连不上，不能算连着");
    }

    // ========== 脚手架 ==========

    private static Node node(Long id) {
        Node node = new Node();
        node.setId(id);
        node.setName("node-" + id);
        node.setStatus(1);
        return node;
    }

    private static final class MutableClock implements LongSupplier {

        private long now;

        MutableClock(long now) {
            this.now = now;
        }

        void advance(long delta) {
            this.now += delta;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }
}
