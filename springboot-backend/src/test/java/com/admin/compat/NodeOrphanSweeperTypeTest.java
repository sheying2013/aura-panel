package com.admin.compat;

import com.admin.common.utils.NodeOrphanSweeper;
import com.alibaba.fastjson.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点孤儿清扫按「隧道类型」判定配套资源是否还应当存在（round41）。
 *
 * <p>真机缺陷：隧道类型从 2（隧道转发，需要 chains_&lt;id&gt; + &lt;id&gt;_tls）改成 1（直连，完全不需要这两类资源）后，
 * tunnel 行还在，旧判据只看「行是否存在」，于是节点上残留的 chains_&lt;id&gt; / &lt;id&gt;_tls 永远算活着，
 * 端口被长期占死（bind: address already in use），面板再也选不到该端口 —— 属于永久泄漏。
 *
 * <p>这里锁死：隧道类型决定这些资源该不该在；行不存在照旧删；4 参构造保持旧语义（只按行存在）；
 * hop_&lt;id&gt; 与 chains_&lt;id&gt; 同判据；白名单之外的名字任何情况都不动；查类型失败必须保守保留。
 * 转发服务 &lt;fid&gt;_&lt;uid&gt;_&lt;utid&gt;_(tcp|udp) 的判据不在这里，仍只看 forward 行。
 */
class NodeOrphanSweeperTypeTest {

    private static final long NODE_ID = 1L;
    /** 直连：节点上不该有 &lt;id&gt;_tls / chains_&lt;id&gt; / hop_&lt;id&gt; */
    private static final int DIRECT = 1;
    /** 隧道转发：需要 &lt;id&gt;_tls / chains_&lt;id&gt; / hop_&lt;id&gt; */
    private static final int RELAY = 2;

    /** tunnelId -&gt; 库里的隧道类型（key 不存在表示库里没有这行，value 为 null 表示类型为空） */
    private final Map<Long, Integer> tunnels = new HashMap<>();
    private final List<String> deletedServices = new ArrayList<>();
    private final List<String> deletedChains = new ArrayList<>();
    private boolean typeLookupFails;

    @BeforeEach
    void setUp() {
        tunnels.clear();
        deletedServices.clear();
        deletedChains.clear();
        typeLookupFails = false;
    }

    // ========== 1) 直连隧道（type=1）的配套资源必须回收 ==========

    @Test
    void chainsOfDirectTunnelAreDeletedEvenThoughTheTunnelRowExists() {
        tunnels.put(9L, DIRECT);

        NodeOrphanSweeper.SweepResult result = typed().sweep(NODE_ID,
                Collections.emptyList(), List.of("chains_9"));

        assertEquals(List.of("chains_9"), deletedChains, "类型1 不需要链，行还在也必须回收");
        assertEquals(1, result.getChecked(), "名字命中白名单就要真的查一遍");
        assertEquals(1, result.getDeleted());
        assertEquals(0, result.getFailed());
        assertEquals(List.of("chains_9"), result.getDeletedNames());
    }

    @Test
    void tlsServiceOfDirectTunnelIsDeleted() {
        tunnels.put(9L, DIRECT);

        NodeOrphanSweeper.SweepResult result = typed().sweep(NODE_ID, List.of("9_tls"), Collections.emptyList());

        assertEquals(List.of("9_tls"), deletedServices, "类型1 走单跳，中继服务 <id>_tls 是残留");
        assertEquals(1, result.getDeleted());
        assertEquals(0, result.getFailed());
    }

    @Test
    void hopOfDirectTunnelIsDeletedToo() {
        tunnels.put(9L, DIRECT);

        NodeOrphanSweeper.SweepResult result = typed().sweep(NODE_ID,
                Collections.emptyList(), List.of("hop_9", "chains_9"));

        assertEquals(List.of("hop_9", "chains_9"), deletedChains, "hop_<id> 与 chains_<id> 同判据");
        assertEquals(2, result.getDeleted());
    }

    // ========== 2) 隧道转发（type=2）的配套资源一律保留 ==========

    @Test
    void relayTunnelKeepsChainsAndTlsService() {
        tunnels.put(9L, RELAY);

        NodeOrphanSweeper.SweepResult result = typed().sweep(NODE_ID,
                List.of("9_tls"), List.of("chains_9", "hop_9"));

        assertTrue(deletedServices.isEmpty(), "类型2 的在用中继服务不能删：" + deletedServices);
        assertTrue(deletedChains.isEmpty(), "类型2 的在用链路不能删：" + deletedChains);
        assertEquals(3, result.getChecked());
        assertEquals(0, result.getDeleted());
        assertEquals(0, result.getFailed());
    }

    // ========== 3) 行不存在：旧行为不变 ==========

    @Test
    void missingTunnelRowStillDeletesBothKinds() {
        NodeOrphanSweeper.SweepResult result = typed().sweep(NODE_ID, List.of("9_tls"), List.of("chains_9"));

        assertEquals(List.of("9_tls"), deletedServices, "隧道行不存在时照旧删除");
        assertEquals(List.of("chains_9"), deletedChains);
        assertEquals(2, result.getDeleted());
        assertEquals(0, result.getFailed());
    }

    // ========== 4) 4 参构造：旧语义（只按行存在）没被改坏 ==========

    @Test
    void legacyFourArgConstructorStillTrustsTheRowAlone() {
        tunnels.put(9L, DIRECT);

        NodeOrphanSweeper.SweepResult result = legacy().sweep(NODE_ID,
                List.of("9_tls"), List.of("chains_9"));

        assertTrue(deletedServices.isEmpty(), "4 参构造等价旧判据：行还在就保留：" + deletedServices);
        assertTrue(deletedChains.isEmpty(), "4 参构造等价旧判据：行还在就保留：" + deletedChains);
        assertEquals(2, result.getChecked());
        assertEquals(0, result.getDeleted());
    }

    // ========== 5) 同一份清单里各隧道按自己的类型判 ==========

    @Test
    void directAndRelayAreJudgedIndependentlyInTheSameReport() {
        tunnels.put(9L, DIRECT);
        tunnels.put(10L, RELAY);

        NodeOrphanSweeper.SweepResult result = typed().sweep(NODE_ID,
                List.of("9_tls", "10_tls"), List.of("chains_9", "chains_10"));

        assertEquals(List.of("9_tls"), deletedServices, "只有直连隧道的 9_tls 是残留");
        assertEquals(List.of("chains_9"), deletedChains, "只有直连隧道的 chains_9 是残留");
        assertEquals(4, result.getChecked());
        assertEquals(2, result.getDeleted());
    }

    // ========== 6) 白名单之外一律不动 ==========

    @Test
    void unknownNamesAreUntouchedRegardlessOfType() {
        tunnels.put(9L, DIRECT);
        tunnels.put(10L, RELAY);

        NodeOrphanSweeper.SweepResult result = typed().sweep(NODE_ID,
                Arrays.asList("node_9", "9_relay", "9_tlss", "9_1_tcp", null, "  "),
                Arrays.asList("node_9", "chains_", "hop-9", "chains_x"));

        assertTrue(deletedServices.isEmpty(), "白名单之外的服务名一律不动：" + deletedServices);
        assertTrue(deletedChains.isEmpty(), "白名单之外的链路名一律不动：" + deletedChains);
        assertEquals(0, result.getChecked(), "白名单之外连类型都不查");
        assertEquals(0, result.getDeleted());
        assertEquals(0, result.getFailed());
    }

    // ========== 7) 类型查不到：宁可少删 ==========

    @Test
    void typeLookupFailureKeepsTheResources() {
        tunnels.put(9L, DIRECT);
        typeLookupFails = true;

        NodeOrphanSweeper.SweepResult result = typed().sweep(NODE_ID, List.of("9_tls"), List.of("chains_9"));

        assertTrue(deletedServices.isEmpty(), "查类型失败必须保守保留，不许误删在用的中继服务");
        assertTrue(deletedChains.isEmpty(), "查类型失败必须保守保留，不许误删在用的链路");
        assertTrue(result.getDeletedNames().isEmpty());
        assertTrue(result.getFailed() >= 2, "两处查询异常都要计入 failed，实际：" + result.getFailed());
        assertEquals(0, result.getDeleted());
    }

    // ========== 8) 类型为空（NULL）：保守保留，不许删 ==========

    @Test
    void nullTypeIsKeptConservatively() {
        tunnels.put(9L, null);

        NodeOrphanSweeper.SweepResult result = typed().sweep(NODE_ID, List.of("9_tls"), List.of("chains_9"));

        assertTrue(deletedServices.isEmpty(), "类型为空无法判断，9_tls 必须保留：" + deletedServices);
        assertTrue(deletedChains.isEmpty(), "类型为空无法判断，chains_9 必须保留：" + deletedChains);
        assertTrue(result.getDeletedNames().isEmpty(), "不得产生任何 Delete 调用：" + result.getDeletedNames());
        assertEquals(0, result.getDeleted());
        assertEquals(0, result.getFailed(), "类型读到了（只是为空），不算查询失败");
        assertEquals(2, result.getChecked(), "名字命中白名单仍然要查一遍类型");
    }

    // ========== 脚手架 ==========

    /** 5 参构造：与 WebSocketServer 接线同口径 —— 行存在，且类型不是明确的直连(1)，才认为资源该在。 */
    private NodeOrphanSweeper typed() {
        return new NodeOrphanSweeper(
                this::rowExists,
                id -> false,
                (id, names) -> recordServices(names),
                (id, name) -> recordChain(name),
                this::needsChain);
    }

    /** 4 参构造：旧语义，只按行是否存在。 */
    private NodeOrphanSweeper legacy() {
        return new NodeOrphanSweeper(
                this::rowExists,
                id -> false,
                (id, names) -> recordServices(names),
                (id, name) -> recordChain(name));
    }

    private boolean rowExists(long tunnelId) {
        return tunnels.containsKey(tunnelId);
    }

    /**
     * 与 WebSocketServer#tunnelNeedsChain 同口径（round42）：类型为空按"需要"保守保留并打 WARN，
     * 只有明确 type=1（直连）才判定为残留；查库异常照旧抛出（上层按保留处理）。
     */
    private boolean needsChain(long tunnelId) {
        if (typeLookupFails) {
            throw new IllegalStateException("db down");
        }
        Integer type = tunnels.get(tunnelId);
        if (type == null) {
            return true;
        }
        return type != DIRECT;
    }

    private boolean recordServices(JSONArray names) {
        for (Object name : names) {
            deletedServices.add((String) name);
        }
        return true;
    }

    private boolean recordChain(String name) {
        deletedChains.add(name);
        return true;
    }
}
