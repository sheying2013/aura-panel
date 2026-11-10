package com.admin.compat;

import com.admin.controller.BaseController;
import com.admin.controller.FlowController;
import com.admin.entity.Node;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round49：/flow/upload 的入参不可信。
 * <p>
 * 节点上报的 n 字段以前直接 split("_") 取前三段，一条 "abc" 就会抛
 * ArrayIndexOutOfBoundsException 把整批上报打成 500（同批里的正常条目也一起丢）；
 * d/u 直接拼进 setSql，负数等于把已计流量洗回去，超大值会溢出成负数，两者都能绕配额。
 */
class FlowUploadHardeningTest {

    @Test
    void malformedServiceNameIsDroppedWithoutTouchingDatabase() {
        Harness h = new Harness();

        assertDoesNotThrow(() -> h.controller.uploadFlowData("[{\"n\":\"abc\",\"d\":100,\"u\":100}]", NODE_SECRET));

        verify(h.forwardService, never()).update(any(), any());
        verify(h.userService, never()).update(any(), any());
    }

    @Test
    void malformedEntryDoesNotAbortTheRestOfTheBatch() {
        Harness h = new Harness();

        h.controller.uploadFlowData(
                "[{\"n\":\"only_two_1\",\"d\":1,\"u\":1},{\"n\":\"31_1_0\",\"d\":200,\"u\":300}]",
                NODE_SECRET);

        List<String> sql = h.forwardSqlSets();
        assertEquals(1, sql.size(), "坏条目被丢弃，好条目必须照常计费");
        assertTrue(sql.get(0).contains("+ 200"), sql.get(0));
        assertTrue(sql.get(0).contains("+ 300"), sql.get(0));
    }

    @Test
    void realNodeServiceNameWithProtocolSuffixIsBilled() {
        Harness h = new Harness();

        h.controller.uploadFlowData("[{\"n\":\"43_1_0_tcp\",\"d\":10,\"u\":20}]", NODE_SECRET);

        List<String> sql = h.forwardSqlSets();
        assertEquals(1, sql.size(), "带 _tcp 的真实服务名必须照常计费");
        assertTrue(sql.get(0).contains("+ 10"), sql.get(0));
        assertTrue(sql.get(0).contains("+ 20"), sql.get(0));
    }

    @Test
    void unknownProtocolSuffixIsStillRejected() {
        Harness h = new Harness();

        h.controller.uploadFlowData("[{\"n\":\"43_1_0_icmp\",\"d\":10,\"u\":20}]", NODE_SECRET);

        verify(h.forwardService, never()).update(any(), any());
    }

    @Test
    void negativeDeltaCannotWashAwayBilledTraffic() {
        Harness h = new Harness();

        h.controller.uploadFlowData("[{\"n\":\"31_1_0\",\"d\":-5000,\"u\":-1}]", NODE_SECRET);

        String sql = h.forwardSqlSets().get(0);
        assertTrue(sql.contains("in_flow = in_flow + 0"), sql);
        assertTrue(sql.contains("out_flow = out_flow + 0"), sql);
    }

    @Test
    void oversizeDeltaIsClampedInsteadOfOverflowing() {
        Harness h = new Harness();

        h.controller.uploadFlowData("[{\"n\":\"31_1_0\",\"d\":" + Long.MAX_VALUE + ",\"u\":0}]", NODE_SECRET);

        String sql = h.forwardSqlSets().get(0);
        assertTrue(sql.contains("+ " + (1L << 50)), sql);
        assertTrue(!sql.contains("-"), "钳制后不得出现负号：" + sql);
    }

    @Test
    void defaultUserTunnelSuffixStillSkipsTunnelAndQuotaChecks() {
        Harness h = new Harness();

        h.controller.uploadFlowData("[{\"n\":\"31_1_0\",\"d\":10,\"u\":20}]", NODE_SECRET);

        verify(h.userTunnelService, never()).update(any(), any());
        verify(h.userService).update(any(), any());
    }

    private static final String NODE_SECRET = "round49-node-secret";

    private static final class Harness {

        private final ForwardService forwardService = mock(ForwardService.class);
        private final UserService userService = mock(UserService.class);
        private final UserTunnelService userTunnelService = mock(UserTunnelService.class);
        private final NodeService nodeService = mock(NodeService.class);
        private final FlowController controller = new FlowController();

        private Harness() {
            Node node = new Node();
            node.setId(1L);
            when(nodeService.getOne(any())).thenReturn(node);
            when(forwardService.getById(any())).thenReturn(null);
            inject(controller, BaseController.class, "forwardService", forwardService);
            inject(controller, BaseController.class, "userService", userService);
            inject(controller, BaseController.class, "userTunnelService", userTunnelService);
            inject(controller, BaseController.class, "nodeService", nodeService);
        }

        private List<String> forwardSqlSets() {
            ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(forwardService, atLeastOnce()).update(any(), captor.capture());
            return captor.getAllValues().stream()
                    .map(wrapper -> ((UpdateWrapper<?>) wrapper).getSqlSet())
                    .toList();
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
