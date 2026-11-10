package com.admin.common.utils;

import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 节点上线自报资源清单（ServiceListReport）的孤儿清扫。
 *
 * <p>节点重启后 gost 会把清单里"面板已经不该有"的服务重新拉起来（隧道删了但节点上还留着
 * 5_tls 占着 30201、chains_17 还在监听），面板再也选不到那个端口，只能反复 bind 失败。
 *
 * <p>判定是严格白名单：只有名字形态能对到"面板自己的资源"、且库里查不到对应行，才下发删除。
 * 其它一切名字（node_* 跳板、限流器、未知命名）一律不动 —— 缺库不是所有权证据。
 *
 * <p>依赖全部由构造参数注入，方便单测；不读 Spring 上下文，也不向调用方抛异常。
 * 计数口径：checked = 命中白名单、真的查过库的名字数；deleted = 下发删除且节点确认的名字数；
 * failed = 查询或下发失败的名字数（失败的名字不进 deletedNames，也不重试）。
 */
@Slf4j
public class NodeOrphanSweeper {

    /** 面板隧道服务：&lt;tunnelId&gt;_tls */
    private static final Pattern TLS_SERVICE = Pattern.compile("^(\\d+)_tls$");
    /** 面板转发服务：&lt;forwardId&gt;_&lt;userId&gt;_&lt;userTunnelId&gt;_(tcp|udp) */
    private static final Pattern FORWARD_SERVICE = Pattern.compile("^(\\d+)_(\\d+)_(\\d+)_(tcp|udp)$");
    /** 面板链路：chains_&lt;tunnelId&gt; / hop_&lt;tunnelId&gt; */
    private static final Pattern CHAIN = Pattern.compile("^chains_(\\d+)$");
    private static final Pattern HOP = Pattern.compile("^hop_(\\d+)$");

    private final BiPredicate<Long, Long> forwardExistsOnNode;
    private final BiPredicate<Long, Long> tunnelServiceNeededOnNode;
    private final BiPredicate<Long, Long> tunnelChainNeededOnNode;
    private final BiPredicate<Long, JSONArray> deleteServices;
    private final BiPredicate<Long, String> deleteChains;

    /**
     * 兼容旧判据的 4 参构造：只要隧道/转发还在库中，就认为在节点上都该存在。
     */
    public NodeOrphanSweeper(LongPredicate tunnelExists,
                             LongPredicate forwardExists,
                             BiPredicate<Long, JSONArray> deleteServices,
                             BiPredicate<Long, String> deleteChains) {
        this(tunnelExists, forwardExists, deleteServices, deleteChains, id -> true);
    }

    /**
     * 兼容旧判据的 5 参构造：加入 tunnelNeedsChain 区分隧道类型。
     */
    public NodeOrphanSweeper(LongPredicate tunnelExists,
                             LongPredicate forwardExists,
                             BiPredicate<Long, JSONArray> deleteServices,
                             BiPredicate<Long, String> deleteChains,
                             LongPredicate tunnelNeedsChain) {
        this((nodeId, forwardId) -> forwardExists.test(forwardId),
             (nodeId, tunnelId) -> tunnelExists.test(tunnelId) && tunnelNeedsChain.test(tunnelId),
             (nodeId, tunnelId) -> tunnelExists.test(tunnelId) && tunnelNeedsChain.test(tunnelId),
             deleteServices,
             deleteChains);
    }

    /**
     * 节点感知型（Node-Scoped）孤儿清扫构造器：
     * 精确区分节点角色与服务分配，解决老节点在拓扑变更后残留孤儿服务与链的问题。
     *
     * @param forwardExistsOnNode        (nodeId, forwardId) -> 该转发服务是否应当运行在该节点上（依据 forward_port 表）
     * @param tunnelServiceNeededOnNode  (nodeId, tunnelId) -> 该节点是否需要该隧道的 &lt;id&gt;_tls 监听（中继/出口节点）
     * @param tunnelChainNeededOnNode    (nodeId, tunnelId) -> 该节点是否需要该隧道的 chains_&lt;id&gt;/hop_&lt;id&gt; 链（入口/中继节点）
     * @param deleteServices             向节点下发 DeleteService
     * @param deleteChains               向节点下发 DeleteChains
     */
    public NodeOrphanSweeper(BiPredicate<Long, Long> forwardExistsOnNode,
                             BiPredicate<Long, Long> tunnelServiceNeededOnNode,
                             BiPredicate<Long, Long> tunnelChainNeededOnNode,
                             BiPredicate<Long, JSONArray> deleteServices,
                             BiPredicate<Long, String> deleteChains) {
        this.forwardExistsOnNode = forwardExistsOnNode;
        this.tunnelServiceNeededOnNode = tunnelServiceNeededOnNode;
        this.tunnelChainNeededOnNode = tunnelChainNeededOnNode;
        this.deleteServices = deleteServices;
        this.deleteChains = deleteChains;
    }

    /**
     * 扫描一份资源清单，删掉能确认是孤儿的条目。单条失败只记 WARN 继续，整体不抛异常。
     */
    public SweepResult sweep(long nodeId, List<String> services, List<String> chains) {
        SweepResult result = new SweepResult();
        try {
            sweepServices(nodeId, services, result);
        } catch (Exception e) {
            log.warn("节点 {} 孤儿服务清扫中断: {}", nodeId, e.toString());
        }
        try {
            sweepChains(nodeId, chains, result);
        } catch (Exception e) {
            log.warn("节点 {} 孤儿链路清扫中断: {}", nodeId, e.toString());
        }
        return result;
    }

    private void sweepServices(long nodeId, List<String> services, SweepResult result) {
        if (services == null) {
            return;
        }
        for (String raw : services) {
            String name = normalize(raw);
            if (name == null) {
                continue;
            }
            Long tunnelId = matchId(TLS_SERVICE, name);
            Long forwardId = tunnelId == null ? matchId(FORWARD_SERVICE, name) : null;
            if (tunnelId == null && forwardId == null) {
                continue;
            }
            try {
                result.checked++;
                boolean alive = tunnelId != null
                        ? tunnelServiceNeededOnNode.test(nodeId, tunnelId)
                        : forwardExistsOnNode.test(nodeId, forwardId);
                if (alive) {
                    continue;
                }
                if (deleteServices.test(nodeId, names(name))) {
                    result.deleted++;
                    result.deletedNames.add(name);
                } else {
                    result.failed++;
                    log.warn("节点 {} 删除孤儿服务 {} 未被确认，跳过", nodeId, name);
                }
            } catch (Exception e) {
                result.failed++;
                log.warn("节点 {} 检查/删除服务 {} 失败: {}", nodeId, name, e.toString());
            }
        }
    }

    private void sweepChains(long nodeId, List<String> chains, SweepResult result) {
        if (chains == null) {
            return;
        }
        for (String raw : chains) {
            String name = normalize(raw);
            if (name == null) {
                continue;
            }
            Long tunnelId = matchId(CHAIN, name);
            if (tunnelId == null) {
                tunnelId = matchId(HOP, name);
            }
            if (tunnelId == null) {
                continue;
            }
            try {
                result.checked++;
                if (tunnelChainNeededOnNode.test(nodeId, tunnelId)) {
                    continue;
                }
                if (deleteChains.test(nodeId, name)) {
                    result.deleted++;
                    result.deletedNames.add(name);
                } else {
                    result.failed++;
                    log.warn("节点 {} 删除孤儿链路 {} 未被确认，跳过", nodeId, name);
                }
            } catch (Exception e) {
                result.failed++;
                log.warn("节点 {} 检查/删除链路 {} 失败: {}", nodeId, name, e.toString());
            }
        }
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        return name.isEmpty() ? null : name;
    }

    private static JSONArray names(String name) {
        JSONArray array = new JSONArray();
        array.add(name);
        return array;
    }

    /** 名字命中 pattern 时返回第一个数字组的 id；不命中或数字溢出都返回 null（当未知名字处理）。 */
    private static Long matchId(Pattern pattern, String name) {
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 一次清扫的计数结果，只用来打日志/断言，不含任何业务逻辑。 */
    public static class SweepResult {

        private int checked;
        private int deleted;
        private int failed;
        private final List<String> deletedNames = new ArrayList<>();

        public int getChecked() {
            return checked;
        }

        public int getDeleted() {
            return deleted;
        }

        public int getFailed() {
            return failed;
        }

        public List<String> getDeletedNames() {
            return deletedNames;
        }
    }
}
