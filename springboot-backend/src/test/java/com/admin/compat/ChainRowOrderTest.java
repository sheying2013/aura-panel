package com.admin.compat;

import com.admin.entity.ChainTunnel;
import com.admin.service.impl.ForwardServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中继链行序必须由 (chain_type, inx, id) 决定，不得依赖 SQL 返回顺序（round42）。
 *
 * <p>真机缺陷：ForwardServiceImpl.planChainForNode 只按 inx 分 TreeMap 组，组内顺序直接来自查询
 * 返回的行序；发布路径按 eq(tunnel_id) 单条查、对账路径按 in(tunnel_id) 批量查，两次查询形态不同，
 * 组内顺序不保证一致。顺序一抖，「第 i 组的下一跳」就换了节点，摘要每轮判漂移，链被反复删了重建。
 *
 * <p>这里锁死排序口径：chain_type 升序（null 排最后，视为 9）→ inx 升序（null 视为 0）→ id 升序
 * （null 视为最大）；入口(1) 恒在最前、出口(3) 恒在最后；排序幂等且不改入参。
 */
class ChainRowOrderTest {

    private static final int ENTRY = 1;
    private static final int RELAY = 2;
    private static final int EXIT = 3;

    // ========== 1) 乱序输入按 inx 排序 ==========

    @Test
    void outOfOrderRowsAreSortedByInx() {
        List<ChainTunnel> rows = Arrays.asList(
                row(30L, RELAY, 3),
                row(10L, RELAY, 1),
                row(20L, RELAY, 2));

        assertEquals(Arrays.asList(10L, 20L, 30L), ids(ForwardServiceImpl.sortChainRows(rows)),
                "组内顺序必须按 inx 升序，不能跟着查询行序走");
    }

    // ========== 2) 同 inx 按 id 升序 ==========

    @Test
    void sameInxIsOrderedById() {
        List<ChainTunnel> rows = Arrays.asList(
                row(9L, RELAY, 2),
                row(3L, RELAY, 2),
                row(7L, RELAY, 2),
                row(1L, RELAY, 2));

        assertEquals(Arrays.asList(1L, 3L, 7L, 9L), ids(ForwardServiceImpl.sortChainRows(rows)),
                "同一 inx 的组内顺序按 id：两次查询行序不同也要算出同一条链");
    }

    // ========== 3) null inx 视为 0 ==========

    @Test
    void nullInxIsTreatedAsZero() {
        List<ChainTunnel> rows = Arrays.asList(
                row(5L, RELAY, 2),
                row(4L, RELAY, null),
                row(6L, RELAY, 1));

        List<Long> sorted = ids(ForwardServiceImpl.sortChainRows(rows));

        assertEquals(Arrays.asList(4L, 6L, 5L), sorted, "inx 为 null 按 0 归组，排在有值的 1、2 之前");
        assertNull(rows.get(1).getInx(), "排序只读不改：null inx 仍然是 null，不能被写成 0");
    }

    // ========== 4) 入口前置、出口后置、未知类型最后 ==========

    @Test
    void entryFirstExitLastAndNullTypeTrails() {
        List<ChainTunnel> rows = Arrays.asList(
                row(40L, null, 5),
                row(30L, EXIT, 9),
                row(20L, RELAY, 1),
                row(10L, ENTRY, 3));

        assertEquals(Arrays.asList(10L, 20L, 30L, 40L), ids(ForwardServiceImpl.sortChainRows(rows)),
                "chain_type 升序：入口(1) → 转发链(2) → 出口(3) → 未知/为空(9)");
    }

    // ========== 5) 排序幂等 ==========

    @Test
    void sortingIsIdempotent() {
        List<ChainTunnel> rows = Arrays.asList(
                row(7L, EXIT, null),
                row(3L, RELAY, 2),
                row(5L, ENTRY, 1),
                row(2L, RELAY, null),
                row(9L, RELAY, 2));

        List<ChainTunnel> once = ForwardServiceImpl.sortChainRows(rows);
        List<ChainTunnel> twice = ForwardServiceImpl.sortChainRows(once);

        assertEquals(ids(once), ids(twice), "重复排序结果必须完全一致，否则每轮对账都会判漂移");
        assertEquals(ids(ForwardServiceImpl.sortChainRows(twice)), ids(twice));
    }

    // ========== 6) 不改入参顺序（查询结果只读） ==========

    @Test
    void inputListIsNotReorderedInPlace() {
        List<ChainTunnel> rows = new ArrayList<>(Arrays.asList(
                row(20L, RELAY, 2),
                row(10L, RELAY, 1)));

        ForwardServiceImpl.sortChainRows(rows);

        assertEquals(Arrays.asList(20L, 10L), ids(rows), "返回新列表，调用方的查询结果顺序不动");
    }

    // ========== 7) 空 / null 安全 ==========

    @Test
    void emptyAndNullAreSafe() {
        assertTrue(ForwardServiceImpl.sortChainRows(Collections.emptyList()).isEmpty());
        assertNull(ForwardServiceImpl.sortChainRows(null), "null 原样返回，不在对账路径上抛 NPE");
    }

    // ========== 脚手架 ==========

    private static ChainTunnel row(Long id, Integer chainType, Integer inx) {
        ChainTunnel row = new ChainTunnel();
        row.setId(id);
        row.setChainType(chainType);
        row.setInx(inx);
        row.setNodeId(id == null ? null : id + 100);
        return row;
    }

    private static List<Long> ids(List<ChainTunnel> rows) {
        return rows.stream().map(ChainTunnel::getId).collect(Collectors.toList());
    }
}
