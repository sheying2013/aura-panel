package com.admin.compat;

import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.controller.BaseController;
import com.admin.controller.FlowController;
import com.admin.entity.Node;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点密钥从 URL query 迁到请求头（X-Node-Secret / X-Secret）。
 * <p>
 * query 里的 secret 会被 Nginx / 反向代理 access_log 原样记录，等于把节点身份写进日志；
 * 但现网还有未升级的节点，所以 query 必须继续可用。本测试锁死三件事：
 * 请求头优先于 query、纯请求头能鉴权成功、纯 query 仍兼容成功、非法密钥一律拒绝。
 */
class FlowAuthHardeningTest {

    private static final String VALID_SECRET = "round-auth-header-secret";
    private static final String WRONG_SECRET = "not-a-node-secret";

    @Test
    void headerSecretTakesPrecedenceOverQuerySecret() {
        Harness h = new Harness();
        MockHttpServletRequest request = requestWithHeader("X-Node-Secret", VALID_SECRET);

        h.controller.uploadFlowData("[{\"n\":\"61_1_0\",\"d\":200,\"u\":300}]", WRONG_SECRET, request);

        List<String> sql = h.forwardSqlSets();
        assertEquals(1, sql.size(), "请求头里的密钥有效时，query 里的假密钥必须被忽略");
        assertTrue(sql.get(0).contains("+ 200"), sql.get(0));
        assertTrue(h.nodeQueries().get(0).containsValue(VALID_SECRET), "鉴权查询必须用请求头里的密钥");
    }

    @Test
    void headerOnlySecretAuthenticates() {
        Harness h = new Harness();

        h.controller.uploadFlowData("[{\"n\":\"62_1_0\",\"d\":10,\"u\":20}]", null,
                requestWithHeader("X-Node-Secret", VALID_SECRET));

        List<String> sql = h.forwardSqlSets();
        assertEquals(1, sql.size(), "只带 X-Node-Secret 的节点必须能正常上报");
        assertTrue(sql.get(0).contains("+ 10"), sql.get(0));
    }

    @Test
    void legacyXSecretHeaderIsAccepted() {
        Harness h = new Harness();

        h.controller.uploadFlowData("[{\"n\":\"64_1_0\",\"d\":7,\"u\":8}]", null,
                requestWithHeader("X-Secret", VALID_SECRET));

        List<String> sql = h.forwardSqlSets();
        assertEquals(1, sql.size(), "X-Secret 是 X-Node-Secret 的历史别名，必须同样被接受");
        assertTrue(sql.get(0).contains("+ 7"), sql.get(0));
    }

    @Test
    void querySecretStillWorksForLegacyNodes() {
        Harness h = new Harness();

        h.controller.uploadFlowData("[{\"n\":\"63_1_0\",\"d\":100,\"u\":400}]", VALID_SECRET,
                new MockHttpServletRequest());

        List<String> sql = h.forwardSqlSets();
        assertEquals(1, sql.size(), "未升级的节点只带 ?secret= 时必须继续兼容");
        assertTrue(sql.get(0).contains("+ 100"), sql.get(0));
        assertTrue(sql.get(0).contains("+ 400"), sql.get(0));
    }

    @Test
    void unknownSecretIsRejectedWhateverChannelItUses() {
        Harness h = new Harness();
        MockHttpServletRequest request = requestWithHeader("X-Node-Secret", WRONG_SECRET);

        String response = h.controller.uploadFlowData("[{\"n\":\"66_1_0\",\"d\":999,\"u\":999}]",
                WRONG_SECRET, request);

        assertEquals("ok", response);
        verify(h.forwardService, never()).update(any(), any());
        verify(h.userService, never()).update(any(), any());
    }

    @Test
    void encryptedPayloadIsDecryptedWithTheHeaderSecret() {
        Harness h = new Harness();
        String plain = "[{\"n\":\"65_1_0\",\"d\":5,\"u\":6}]";
        String payload = "{\"encrypted\":true,\"data\":\""
                + AESCrypto.create(VALID_SECRET).encrypt(plain) + "\"}";

        h.controller.uploadFlowData(payload, WRONG_SECRET, requestWithHeader("X-Node-Secret", VALID_SECRET));

        List<String> sql = h.forwardSqlSets();
        assertEquals(1, sql.size(), "解密必须用解析后的密钥（请求头），否则只能拿到密文");
        assertTrue(sql.get(0).contains("+ 5"), sql.get(0));
    }

    @Test
    void configAcceptsHeaderSecret() {
        Harness h = new Harness();

        String response = h.controller.config("{}", null, requestWithHeader("X-Node-Secret", VALID_SECRET));

        assertEquals("ok", response);
        verify(h.checkGostConfigAsync).cleanNodeConfigs(any(), any());
    }

    @Test
    void configRejectsUnknownHeaderSecret() {
        Harness h = new Harness();

        h.controller.config("{}", null, requestWithHeader("X-Node-Secret", WRONG_SECRET));

        verify(h.checkGostConfigAsync, never()).cleanNodeConfigs(any(), any());
    }

    private static MockHttpServletRequest requestWithHeader(String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(name, value);
        return request;
    }

    private static final class Harness {

        private final ForwardService forwardService = mock(ForwardService.class);
        private final UserService userService = mock(UserService.class);
        private final UserTunnelService userTunnelService = mock(UserTunnelService.class);
        private final NodeService nodeService = mock(NodeService.class);
        private final CheckGostConfigAsync checkGostConfigAsync = mock(CheckGostConfigAsync.class);
        private final FlowController controller = new FlowController();

        private Harness() {
            Node node = new Node();
            node.setId(1L);
            node.setSecret(VALID_SECRET);
            // 只有查询条件里带着真密钥才返回节点，等价于数据库按 secret 精确匹配。
            // MyBatis-Plus 的参数名是懒生成的：必须先取一次 sqlSegment，paramNameValuePairs 才有值。
            when(nodeService.getOne(any())).thenAnswer(invocation -> {
                Wrapper<?> wrapper = invocation.getArgument(0);
                wrapper.getSqlSegment();
                return paramsOf(wrapper).containsValue(VALID_SECRET) ? node : null;
            });
            inject(controller, BaseController.class, "forwardService", forwardService);
            inject(controller, BaseController.class, "userService", userService);
            inject(controller, BaseController.class, "userTunnelService", userTunnelService);
            inject(controller, BaseController.class, "nodeService", nodeService);
            inject(controller, FlowController.class, "checkGostConfigAsync", checkGostConfigAsync);
        }

        private List<String> forwardSqlSets() {
            ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(forwardService, atLeastOnce()).update(any(), captor.capture());
            return captor.getAllValues().stream()
                    .map(wrapper -> ((UpdateWrapper<?>) wrapper).getSqlSet())
                    .toList();
        }

        private List<Map<String, Object>> nodeQueries() {
            ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(nodeService, atLeastOnce()).getOne(captor.capture());
            return captor.getAllValues().stream().map(Harness::paramsOf).toList();
        }

        private static Map<String, Object> paramsOf(Wrapper<?> wrapper) {
            return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
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
