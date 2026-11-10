package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 拓扑数据一致性巡检：只读，只告警，绝不删改任何数据。
 *
 * <p>为什么需要它：本轮补上了「入口节点重复」的写入侧校验（TunnelServiceImpl.validateChainRows），
 * 但两类历史脏数据在生产库里可能已经存在，应用层不会自动清理：
 * <ul>
 *   <li>chain_tunnel 同一 (tunnel_id, node_id, chain_type) 出现多行 —— 会让发布拓扑时给同一节点
 *       创建两条同名链/两个 <tid>_tls，节点侧一删一建后状态不可预期；</li>
 *   <li>forward_port 同一 (forward_id, node_id) 出现多行 —— round46 已用 handledForwards 兜住重复处置，
 *       但端口归属仍是二义（面板显示的端口与节点实际监听可能不是同一行）。</li>
 * </ul>
 *
 * <p>另外巡检两类悬空行：chain_tunnel 指向已删除的 tunnel / node，forward_port 指向已删除的 forward / node。
 * 这些行不会被本类删除：删除属于运维决策，必须由人工按 id 核对后再处理。
 *
 * <p>{@code @Order(2)} 排在 ForwardSchemaMigration(0)、TunnelTypeMigration(1) 之后，
 * 保证读到的是迁移后的结构；任何查询异常都只记 warn，不阻断启动。
 */
@Slf4j
@Component
@Order(2)
public class TopologyDataAudit implements ApplicationRunner {

    /** 同一隧道同一节点同一角色出现多行（重复下发/重复端口的根因）。 */
    static final String DUPLICATE_CHAIN_SQL =
            "SELECT tunnel_id, node_id, chain_type, COUNT(*) AS c FROM chain_tunnel "
                    + "GROUP BY tunnel_id, node_id, chain_type HAVING COUNT(*) > 1 ORDER BY c DESC";

    /** 同一转发同一节点出现多行端口记录。 */
    static final String DUPLICATE_FORWARD_PORT_SQL =
            "SELECT forward_id, node_id, COUNT(*) AS c FROM forward_port "
                    + "GROUP BY forward_id, node_id HAVING COUNT(*) > 1 ORDER BY c DESC";

    /** chain_tunnel 中非空端口重复（中继/出口监听端口冲突）。 */
    static final String DUPLICATE_CHAIN_PORT_SQL =
            "SELECT node_id, port, COUNT(*) AS c FROM chain_tunnel "
                    + "WHERE port IS NOT NULL GROUP BY node_id, port HAVING COUNT(*) > 1 ORDER BY c DESC";

    /** chain_tunnel 与 forward_port 物理端口跨表冲突。 */
    static final String CROSS_TABLE_PORT_COLLISION_SQL =
            "SELECT ct.node_id, ct.port, COUNT(*) AS c FROM chain_tunnel ct "
                    + "INNER JOIN forward_port fp ON ct.node_id = fp.node_id AND ct.port = fp.port "
                    + "WHERE ct.port IS NOT NULL GROUP BY ct.node_id, ct.port ORDER BY c DESC";

    /** chain_tunnel 指向已删除的隧道。 */
    static final String ORPHAN_CHAIN_TUNNEL_SQL =
            "SELECT COUNT(*) AS c FROM chain_tunnel ct LEFT JOIN tunnel t ON t.id = ct.tunnel_id "
                    + "WHERE t.id IS NULL";

    /** forward_port 指向已删除的转发。 */
    static final String ORPHAN_FORWARD_PORT_SQL =
            "SELECT COUNT(*) AS c FROM forward_port fp LEFT JOIN forward f ON f.id = fp.forward_id "
                    + "WHERE f.id IS NULL";

    /** 样例最多打印几条，避免大库把日志打满。 */
    private static final int SAMPLE_LIMIT = 5;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        int[] duplicates = auditDuplicates();
        int orphans = auditOrphans();
        // 干净库上以前一行日志都没有，运维无法区分「巡检跑过、没发现问题」和「巡检根本没跑」
        log.info("拓扑巡检完成：chain_tunnel 重复 {} 组，forward_port 重复 {} 组，悬空行 {} 行"
                + "（-1 表示该项检查失败；本巡检只读，不删数据）", duplicates[0], duplicates[1], orphans);
    }

    /** @return {chain_tunnel 重复组数, forward_port 重复组数}；检查失败返回 -1 */
    private int[] auditDuplicates() {
        int chainGroups = 0;
        int portGroups = 0;
        try {
            List<Map<String, Object>> chains = jdbcTemplate.queryForList(DUPLICATE_CHAIN_SQL);
            if (chains != null && !chains.isEmpty()) {
                log.warn("拓扑巡检：chain_tunnel 有 {} 组重复行（同一 tunnel/node/chain_type 多行），样例 {}；"
                                + "重复行会让同一节点收到重复的链与中继服务，请人工按 id 核对后处理（本巡检不删数据）",
                        chains.size(), sample(chains));
            }
            List<Map<String, Object>> ports = jdbcTemplate.queryForList(DUPLICATE_FORWARD_PORT_SQL);
            if (ports != null && !ports.isEmpty()) {
                log.warn("拓扑巡检：forward_port 有 {} 组重复行（同一 forward/node 多行），样例 {}；"
                                + "端口归属存在二义，恢复流程只会处置其中一条，请人工核对",
                        ports.size(), sample(ports));
            }
            List<Map<String, Object>> chainPorts = jdbcTemplate.queryForList(DUPLICATE_CHAIN_PORT_SQL);
            if (chainPorts != null && !chainPorts.isEmpty()) {
                log.warn("拓扑巡检：chain_tunnel 有 {} 组重复的 (node_id, port)，样例 {}；"
                                + "中继/出口监听端口冲突，将导致节点上服务 bind 失败，请人工核对",
                        chainPorts.size(), sample(chainPorts));
            }
            List<Map<String, Object>> crossCollisions = jdbcTemplate.queryForList(CROSS_TABLE_PORT_COLLISION_SQL);
            if (crossCollisions != null && !crossCollisions.isEmpty()) {
                log.warn("拓扑巡检：发现 {} 组 chain_tunnel 与 forward_port 跨表物理端口冲突，样例 {}；"
                                + "同一节点的同一个端口被同时用于中继链路与转发入口，请人工核对",
                        crossCollisions.size(), sample(crossCollisions));
            }
            chainGroups = chains == null ? 0 : chains.size();
            portGroups = ports == null ? 0 : ports.size();
        } catch (Exception e) {
            log.warn("拓扑巡检：重复行检查失败，本次跳过（不影响启动）", e);
            return new int[]{-1, -1};
        }
        return new int[]{chainGroups, portGroups};
    }

    /** @return 悬空行（chain_tunnel + forward_port）总数；检查失败返回 -1 */
    private int auditOrphans() {
        try {
            Integer orphanChains = jdbcTemplate.queryForObject(ORPHAN_CHAIN_TUNNEL_SQL, Integer.class);
            if (orphanChains != null && orphanChains > 0) {
                log.warn("拓扑巡检：chain_tunnel 有 {} 行指向已删除的隧道；孤儿清扫只处理节点侧资源，"
                        + "这些库内悬空行不会被自动删除", orphanChains);
            }
            Integer orphanPorts = jdbcTemplate.queryForObject(ORPHAN_FORWARD_PORT_SQL, Integer.class);
            if (orphanPorts != null && orphanPorts > 0) {
                log.warn("拓扑巡检：forward_port 有 {} 行指向已删除的转发；这些行会继续占用节点端口段，"
                        + "新建转发时可能被误判为已占用", orphanPorts);
            }
            return (orphanChains == null ? 0 : orphanChains) + (orphanPorts == null ? 0 : orphanPorts);
        } catch (Exception e) {
            log.warn("拓扑巡检：悬空行检查失败，本次跳过（不影响启动）", e);
            return -1;
        }
    }

    private String sample(List<Map<String, Object>> rows) {
        int limit = Math.min(rows.size(), SAMPLE_LIMIT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(rows.get(i));
        }
        if (rows.size() > limit) {
            sb.append("；……共 ").append(rows.size()).append(" 组");
        }
        return sb.toString();
    }
}
