package com.admin.ddns;

import com.admin.common.utils.GostUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切换窗口的隔离语义（全离线，不碰 Spring/DNS/节点）。
 *
 * <p>P0 竞态的本质不是「注册表早写了新值」，而是「节点上已是新值、注册表还是旧值」这段时间里，
 * 任何按注册表渲染的路径都会把节点改回旧地址。所以窗口内该域名（以及共享同一条 remoteAddr 的
 * 其它目标）的渲染必须直接失败，DDNS 自己则显式携带本窗口要下发的地址。
 */
class DdnsTransitionGuardTest {

    private static final String A = "a.example.com";
    private static final String B = "b.example.com";

    private DdnsAddressRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DdnsAddressRegistry();
        registry.put(A, "1.1.1.1");
        registry.put(B, "9.9.9.9");
    }

    @AfterEach
    void tearDown() {
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("窗口内按注册表渲染该域名直接抛异常：普通编辑/对账拿不到旧地址继续下发")
    void renderIsRejectedWhileTransitioning() {
        assertTrue(registry.beginTransition(A, "主地址 1.1.1.1 → 2.2.2.2 下发中"));

        DdnsTransitionBusyException single = assertThrows(DdnsTransitionBusyException.class,
                () -> DdnsTargetRenderer.render(A + ":8080"));
        assertEquals(A, single.getDomain());
        assertTrue(single.getMessage().contains("1.1.1.1 → 2.2.2.2"), single.getMessage());

        // GostUtil 是普通转发下发与期望摘要的唯一渲染入口，也必须被挡住
        assertThrows(DdnsTransitionBusyException.class,
                () -> GostUtil.renderForwardTargets(A + ":8080,backup.example.com:9090"));

        // 不相关域名照常渲染，窗口不是全局停摆
        assertEquals("9.9.9.9:8080", DdnsTargetRenderer.render(B + ":8080"));
        assertEquals(List.of("9.9.9.9:8080"), GostUtil.renderForwardTargets(B + ":8080"));
    }

    @Test
    @DisplayName("窗口结束后恢复按已确认映射渲染；释放只能由占用方执行")
    void windowEndsAndRenderUsesCommittedValue() {
        assertTrue(registry.beginTransition(A, "下发中"));
        assertFalse(registry.endTransition(B), "别的域名不能释放别人的窗口");
        assertThrows(DdnsTransitionBusyException.class, () -> DdnsTargetRenderer.render(A));

        assertTrue(registry.endTransition(A));
        assertEquals("1.1.1.1:8080", DdnsTargetRenderer.render(A + ":8080"));
        assertEquals("1.1.1.1", registry.get(A), "窗口不改变已确认值");

        registry.put(A, "2.2.2.2");
        assertEquals("2.2.2.2:8080", DdnsTargetRenderer.render(A + ":8080"));
    }

    @Test
    @DisplayName("进程级只允许一个切换位：第二个域名必须失败，避免共享转发交叉回滚")
    void onlyOneTransitionAtATime() {
        assertTrue(registry.beginTransition(A, "A 下发中"));
        assertFalse(registry.beginTransition(B, "B 下发中"));

        DdnsAddressRegistry.Transition active = registry.activeTransition();
        assertEquals(A, active.domain());
        assertTrue(registry.isTransitioning(A));
        assertFalse(registry.isTransitioning(B));
        assertTrue(registry.isTransitioningHost("A.EXAMPLE.COM."), "大小写与尾点按同一域名处理");

        assertTrue(registry.endTransition(A));
        assertNull(registry.activeTransition());
        assertTrue(registry.beginTransition(B, "现在轮到 B"));
        assertTrue(registry.endTransition(B));
    }

    @Test
    @DisplayName("DDNS 窗口内自带地址渲染：候选地址只进下发目标，绝不写进注册表")
    void renderTargetsStagesReplacementWithoutPublishing() {
        assertTrue(registry.beginTransition(A, "下发中"));

        assertEquals(List.of("2.2.2.2:8080", "9.9.9.9:9090"),
                registry.renderTargets(A + ":8080," + B + ":9090", A, "2.2.2.2"),
                "本域名用窗口内地址，其它域名仍取已确认映射");
        assertEquals("1.1.1.1", registry.get(A), "渲染不得顺带发布候选地址");
        assertEquals("1.1.1.1:8080", registry.renderTargets(A + ":8080", A, "1.1.1.1").get(0),
                "回滚窗口显式带旧地址");

        assertTrue(registry.endTransition(A));
        assertEquals(List.of(A + ":8080"), registry.renderTargets(A + ":8080", A, null),
                "删除回滚把目标还原成域名本身，而不是旧 IP");
    }

    @Test
    @DisplayName("共享同一条 remoteAddr 的目标整体判定为切换中：一个域名在切换就整条挡住")
    void sharedRemoteAddrIsBlockedAsAWhole() {
        assertTrue(registry.beginTransition(A, "下发中"));

        assertTrue(DdnsTargetRenderer.anyTransitioning(A + ":8080," + B + ":9090"));
        assertFalse(DdnsTargetRenderer.anyTransitioning(B + ":9090"));
        assertFalse(DdnsTargetRenderer.anyTransitioning(null));

        assertTrue(registry.endTransition(A));
        assertFalse(DdnsTargetRenderer.anyTransitioning(A + ":8080," + B + ":9090"));
        assertFalse(DdnsTargetRenderer.isTransitioning(A));
    }
}
