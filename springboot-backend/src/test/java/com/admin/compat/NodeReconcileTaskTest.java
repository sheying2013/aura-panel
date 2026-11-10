package com.admin.compat;

import com.admin.common.task.NodeReconcileTask;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 节点对账轮询：定期向在线节点索取资源清单，让"库里有、节点没有 / 配置漂移"在没有管理操作时也能被发现。
 *
 * <p>这组用例只验证调度侧的取舍：只发给在线节点、单点异常不拖垮整轮、空表不发。
 * 真正的清扫与补齐逻辑在 NodeOrphanSweeperTest / ResyncMissingServiceTest / ServiceDriftResyncTest 里。
 */
class NodeReconcileTaskTest {

    private static Node node(long id) {
        Node n = new Node();
        n.setId(id);
        return n;
    }

    @Test
    void onlyOnlineNodesAreRequested() {
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Arrays.asList(node(1L), node(2L), node(3L)));
        NodeReconcileTask task = new NodeReconcileTask(nodeService);
        Set<Long> asked = new HashSet<>();
        task.setOnlineCheck(id -> id != 3L); // 3 号离线
        task.setRequester(id -> {
            asked.add(id);
            return true;
        });

        int sent = task.reconcileOnce();

        assertEquals(2, sent, "只应给两个在线节点发消息");
        assertEquals(new HashSet<>(Arrays.asList(1L, 2L)), asked, "离线节点不得收到 info_request");
    }

    @Test
    void singleNodeFailureDoesNotAbortTheRound() {
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Arrays.asList(node(1L), node(2L), node(3L)));
        NodeReconcileTask task = new NodeReconcileTask(nodeService);
        List<Long> asked = new ArrayList<>();
        task.setOnlineCheck(id -> true);
        task.setRequester(id -> {
            asked.add(id);
            if (id == 1L) {
                throw new IllegalStateException("session closed");
            }
            return true;
        });

        int sent = task.reconcileOnce();

        assertEquals(2, sent, "失败的那个不计入成功数");
        assertEquals(Arrays.asList(1L, 2L, 3L), asked, "1 号异常之后仍要继续处理 2、3 号");
    }

    @Test
    void emptyNodeTableSendsNothing() {
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Collections.emptyList());
        NodeReconcileTask task = new NodeReconcileTask(nodeService);
        task.setOnlineCheck(id -> true);
        task.setRequester(id -> {
            throw new AssertionError("没有节点时不应下发任何消息");
        });

        assertEquals(0, task.reconcileOnce(), "空节点表应直接返回 0");
    }

    @Test
    void nodeQueryFailureIsSwallowed() {
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenThrow(new IllegalStateException("database is locked"));
        NodeReconcileTask task = new NodeReconcileTask(nodeService);
        task.setOnlineCheck(id -> true);
        task.setRequester(id -> {
            throw new AssertionError("查库失败时不应下发任何消息");
        });

        assertEquals(0, task.reconcileOnce(), "查库异常应被吞掉并返回 0，不能让调度线程挂掉");
    }

    @Test
    void nodeWithoutIdIsIgnored() {
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Arrays.asList(new Node(), node(7L)));
        NodeReconcileTask task = new NodeReconcileTask(nodeService);
        List<Long> asked = new ArrayList<>();
        task.setOnlineCheck(id -> true);
        task.setRequester(id -> {
            asked.add(id);
            return true;
        });

        assertEquals(1, task.reconcileOnce(), "无 id 的行要跳过");
        assertEquals(Collections.singletonList(7L), asked);
        assertTrue(asked.stream().allMatch(id -> id != null), "不得把 null 当节点 id 下发");
    }

    @Test
    void offlineRoundStillReturnsZeroWithoutRequesting() {
        NodeService nodeService = mock(NodeService.class);
        when(nodeService.list()).thenReturn(Collections.singletonList(node(1L)));
        NodeReconcileTask task = new NodeReconcileTask(nodeService);
        task.setOnlineCheck(id -> false);
        task.setRequester(id -> {
            throw new AssertionError("全离线时不应下发任何消息");
        });

        assertEquals(0, task.reconcileOnce(), "全部离线时成功数为 0");
    }
}
