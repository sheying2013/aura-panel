package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.controller.NodeController;
import com.admin.controller.SpeedLimitController;
import com.admin.controller.TunnelController;
import com.admin.controller.UserController;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.admin.service.impl.ForwardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Map 取参 NPE 专项：{@code params.get("id").toString()} 在缺键/空 body/非数字时抛 NPE，
 * 原始异常文本（Cannot invoke "Object.toString" ...）被 GlobalExceptionHandler 回给前端。
 *
 * <p>修复后统一走 MapParamUtils：缺参/非法参数返回 -2 + 可读文案，合法参数仍原样透传。</p>
 */
class MapParamValidationTest {

    private UserService userService;
    private NodeService nodeService;
    private TunnelService tunnelService;
    private UserTunnelService userTunnelService;
    private SpeedLimitService speedLimitService;

    private UserController userController;
    private NodeController nodeController;
    private TunnelController tunnelController;
    private SpeedLimitController speedLimitController;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        nodeService = mock(NodeService.class);
        tunnelService = mock(TunnelService.class);
        userTunnelService = mock(UserTunnelService.class);
        speedLimitService = mock(SpeedLimitService.class);

        userController = new UserController();
        ReflectionTestUtils.setField(userController, "userService", userService);

        nodeController = new NodeController();
        ReflectionTestUtils.setField(nodeController, "nodeService", nodeService);

        tunnelController = new TunnelController();
        ReflectionTestUtils.setField(tunnelController, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(tunnelController, "userTunnelService", userTunnelService);

        speedLimitController = new SpeedLimitController();
        ReflectionTestUtils.setField(speedLimitController, "speedLimitService", speedLimitService);
        ReflectionTestUtils.setField(speedLimitController, "tunnelService", tunnelService);
    }

    private static void assertParamError(R result, String expectedMsg) {
        assertNotNull(result);
        assertEquals(-2, result.getCode(), "参数错误沿用 -2 码");
        assertEquals(expectedMsg, result.getMsg());
        assertFalse(String.valueOf(result.getMsg()).contains("Cannot invoke"), "不能把 NPE 文本泄给前端");
    }

    private static Map<String, Object> body(String key, Object value) {
        Map<String, Object> params = new HashMap<>();
        if (key != null) {
            params.put(key, value);
        }
        return params;
    }

    @Test
    @DisplayName("user/delete：空 body、缺 id、id 非数字都返回可读错误且不调用 service")
    void userDeleteRejectsMissingOrInvalidId() {
        assertParamError(userController.delete(null), "id 不能为空");
        assertParamError(userController.delete(body(null, null)), "id 不能为空");
        assertParamError(userController.delete(body("id", "  ")), "id 不能为空");
        assertParamError(userController.delete(body("id", null)), "id 不能为空");
        assertParamError(userController.delete(body("id", "abc")), "id 格式不正确");
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("user/delete：合法 id（数字/数字字符串）照旧透传")
    void userDeletePassesValidIdThrough() {
        userController.delete(body("id", 7));
        verify(userService).deleteUser(7L);

        userController.delete(body("id", "8"));
        verify(userService).deleteUser(8L);
    }

    @Test
    @DisplayName("node/delete 与 node/install：缺 id 或非数字返回可读错误")
    void nodeEndpointsRejectInvalidId() {
        assertParamError(nodeController.delete(null), "id 不能为空");
        assertParamError(nodeController.delete(body("id", "abc")), "id 格式不正确");
        assertParamError(nodeController.getInstallCommand(body(null, null)), "id 不能为空");
        assertParamError(nodeController.getInstallCommand(body("id", "1.5")), "id 格式不正确");
        verifyNoInteractions(nodeService);
    }

    @Test
    @DisplayName("tunnel/delete、removeUserTunnel、diagnose：缺参/非数字返回可读错误")
    void tunnelEndpointsRejectInvalidParams() {
        assertParamError(tunnelController.delete(null), "id 不能为空");
        assertParamError(tunnelController.delete(body("id", "x")), "id 格式不正确");
        assertParamError(tunnelController.removeUserTunnel(body(null, null)), "id 不能为空");
        assertParamError(tunnelController.removeUserTunnel(body("id", "99999999999999999999")), "id 格式不正确");
        assertParamError(tunnelController.diagnoseTunnel(body("id", 3)), "tunnelId 不能为空");
        assertParamError(tunnelController.diagnoseTunnel(body("tunnelId", "abc")), "tunnelId 格式不正确");
        verifyNoInteractions(tunnelService);
        verifyNoInteractions(userTunnelService);
    }

    @Test
    @DisplayName("speed-limit/delete：缺 id 返回可读错误")
    void speedLimitDeleteRejectsMissingId() {
        assertParamError(speedLimitController.delete(null), "id 不能为空");
        assertParamError(speedLimitController.delete(body("id", "abc")), "id 格式不正确");
        verifyNoInteractions(speedLimitService);
    }

    @Test
    @DisplayName("forward/order：forwards 缺失、id/inx 缺失或非数字都在读用户上下文之前被拒")
    void updateForwardOrderValidatesRowsBeforeAuth() {
        ForwardServiceImpl service = new ForwardServiceImpl();

        R noKey = service.updateForwardOrder(new HashMap<>());
        assertEquals("缺少forwards参数", noKey.getMsg());

        R nullParams = service.updateForwardOrder(null);
        assertEquals("缺少forwards参数", nullParams.getMsg());

        R empty = service.updateForwardOrder(body("forwards", new ArrayList<>()));
        assertEquals("forwards参数不能为空", empty.getMsg());

        Map<String, Object> noId = new LinkedHashMap<>();
        noId.put("inx", 1);
        R missingId = service.updateForwardOrder(body("forwards", Collections.singletonList(noId)));
        assertEquals("第 1 条排序数据缺少有效的 id：id 不能为空", missingId.getMsg());

        Map<String, Object> badId = new LinkedHashMap<>();
        badId.put("id", "abc");
        badId.put("inx", 1);
        R invalidId = service.updateForwardOrder(body("forwards", Collections.singletonList(badId)));
        assertEquals("第 1 条排序数据缺少有效的 id：id 格式不正确", invalidId.getMsg());

        Map<String, Object> noInx = new LinkedHashMap<>();
        noInx.put("id", 12);
        R missingInx = service.updateForwardOrder(body("forwards", Collections.singletonList(noInx)));
        assertEquals("第 1 条排序数据缺少有效的 inx：inx 不能为空", missingInx.getMsg());

        Map<String, Object> badInx = new LinkedHashMap<>();
        badInx.put("id", 12);
        badInx.put("inx", "99999999999");
        R invalidInx = service.updateForwardOrder(body("forwards", Collections.singletonList(badInx)));
        assertEquals("第 1 条排序数据缺少有效的 inx：inx 格式不正确", invalidInx.getMsg());

        List<Map<String, Object>> mixed = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", 1);
        first.put("inx", 1);
        mixed.add(first);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", 2);
        mixed.add(second);
        R secondRow = service.updateForwardOrder(body("forwards", mixed));
        assertEquals("第 2 条排序数据缺少有效的 inx：inx 不能为空", secondRow.getMsg());
    }
}
