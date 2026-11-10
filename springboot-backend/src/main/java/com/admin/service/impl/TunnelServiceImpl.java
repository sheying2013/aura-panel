package com.admin.service.impl;

import com.admin.common.dto.*;

import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.*;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class TunnelServiceImpl extends ServiceImpl<TunnelMapper, Tunnel> implements TunnelService {

    // 同一隧道的拓扑变更串行化：updateTunnelTopology 会先删旧链再重建、还要迁移入口服务，
    // 两个请求同时进来会互相删对方的资源、把 forward_port 迁到不同节点。按隧道 id 加进程内互斥，
    // 抢不到的请求直接拒绝，让用户重试，避免半途交叉破坏。
    private static final ConcurrentHashMap<Long, ReentrantLock> TOPOLOGY_LOCKS = new ConcurrentHashMap<>();


    @Resource
    UserTunnelMapper userTunnelMapper;

    @Resource
    NodeService nodeService;

    @Resource
    ForwardService forwardService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    ChainTunnelService chainTunnelService;

    @Resource
    ForwardPortService forwardPortService;

    @Resource
    SpeedLimitService speedLimitService;

    @Resource
    CascadeDeletePurger cascadeDeletePurger;


    @Override
    public R createTunnel(TunnelDto tunnelDto) {
        if (tunnelDto == null) {
            return R.err("隧道参数不能为空");
        }
        if (tunnelDto.getName() == null || tunnelDto.getName().trim().isEmpty()) {
            return R.err("隧道名称不能为空");
        }
        int count = this.count(new QueryWrapper<Tunnel>().eq("name", tunnelDto.getName().trim()));
        if (count > 0) return R.err("隧道名称重复");
        if (tunnelDto.getInNodeId() == null || tunnelDto.getInNodeId().isEmpty()) return R.err("入口节点不能为空");
        if (Integer.valueOf(2).equals(tunnelDto.getType()) && (tunnelDto.getOutNodeId() == null || tunnelDto.getOutNodeId().isEmpty())) return R.err("出口不能为空");
        // 结构问题必须在写库前报出来：缺 chainType 等到 saveBatch 才失败时，异常 message 是 null
        R structureError = validateChainRows(tunnelDto);
        if (structureError != null) return structureError;


        List<ChainTunnel> chainTunnels = new ArrayList<>();
        Map<Long, Node> nodes = new HashMap<>();
        // 自动分配的端口在节点上可能已被僵尸服务占着（库查不到），只对这类行做换口重试
        Set<ChainTunnel> autoPortRows = Collections.newSetFromMap(new IdentityHashMap<ChainTunnel, Boolean>());

        List<Long> node_ids = new ArrayList<>();
        for (ChainTunnel in_node : tunnelDto.getInNodeId()) {
            node_ids.add(in_node.getNodeId());
            chainTunnels.add(in_node);

            Node node = nodeService.getById(in_node.getNodeId());
            if (node == null) return R.err("节点不存在");
            nodes.put(node.getId(), node);
        }

        if (tunnelDto.getType() == 2) {
            // 处理转发链节点，为每一跳设置inx
            int inx = 1;
            for (List<ChainTunnel> chainNode : tunnelDto.getChainNodes()) {
                for (ChainTunnel chain_node : chainNode) {
                    node_ids.add(chain_node.getNodeId());
                    Node node = nodeService.getById(chain_node.getNodeId());
                    if (node == null) return R.err("节点不存在");
                    nodes.put(node.getId(), node);
                    R portError = resolveChainPort(chain_node, node, autoPortRows);
                    if (portError != null) return portError;
                    chain_node.setInx(inx); // 设置转发链序号
                    chainTunnels.add(chain_node);
                }
                inx++; // 每一跳递增
            }
            for (ChainTunnel out_node : tunnelDto.getOutNodeId()) {
                node_ids.add(out_node.getNodeId());
                Node node = nodeService.getById(out_node.getNodeId());
                if (node == null) return R.err("节点不存在");
                nodes.put(node.getId(), node);
                R portError = resolveChainPort(out_node, node, autoPortRows);
                if (portError != null) return portError;
                chainTunnels.add(out_node);
            }

        }
        String flatDuplicateError = firstDuplicateMessage(node_ids);
        if (flatDuplicateError != null) return R.err(flatDuplicateError);

        List<Node> list = nodeService.list(new QueryWrapper<Node>().in("id", node_ids));
        if (list.size() != node_ids.size()) return R.err("部分节点不存在");
        for (Node node : list) {
            if (node.getStatus() != 1) return R.err("部分节点不在线");
        }


        Tunnel tunnel = new Tunnel();
        BeanUtils.copyProperties(tunnelDto, tunnel);
        tunnel.setStatus(1);
        long currentTime = System.currentTimeMillis();
        tunnel.setCreatedTime(currentTime);
        tunnel.setUpdatedTime(currentTime);
        if (StringUtils.isEmpty(tunnel.getInIp())){
            StringBuilder in_ip = new StringBuilder();
            for (ChainTunnel chainTunnel : tunnelDto.getInNodeId()) {
                Node node = nodes.get(chainTunnel.getNodeId());
                in_ip.append(node.getServerIp()).append(",");
            }
            in_ip.deleteCharAt(in_ip.length() - 1);
            tunnel.setInIp(in_ip.toString());
        }

        String tunnelWriteError = dbWriteError(() -> this.save(tunnel), "隧道记录", Collections.singletonList(tunnel));
        if (tunnelWriteError != null) {
            return R.err(tunnelWriteError + "，未下发任何节点");
        }
        for (ChainTunnel chainTunnel : chainTunnels) {
            chainTunnel.setTunnelId(tunnel.getId());
        }
        String chainWriteError = dbWriteError(() -> chainTunnelService.saveBatch(chainTunnels), "隧道节点记录", chainTunnels);
        if (chainWriteError != null) {
            // 隧道记录已落库、节点记录没落库：先清理隧道记录，清理结果如实返回
            String cleanupError = cleanupTunnelRecords(tunnel.getId(), false);
            return R.err(cleanupError == null
                    ? chainWriteError + "，未下发任何节点"
                    : chainWriteError + "，未下发任何节点；" + cleanupError);
        }

        if (tunnel.getType() == 2) {
            R failed;
            try {
                failed = publishChains(tunnel, tunnelDto, nodes, autoPortRows);
            } catch (RuntimeException e) {
                // Fail-Closed 守卫（隧道类型不合法）与任何未预期异常都在这里兜住：
                // 隧道行与节点行此时已经落库，抛到 GlobalExceptionHandler 只会给前端一个 500，
                // 库里留下悬空记录并永久占用端口段。改动前没有任何 try/catch（round45 QA P0-1）。
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                String cleanup = cleanupTunnelRecords(tunnel.getId(), true);
                return R.err("隧道创建失败：" + message + "，已中止下发；隧道与节点记录已清理"
                        + (cleanup == null ? "" : "；" + cleanup));
            }
            if (failed != null) {
                return failed;
            }
        }
        return R.ok();
    }

    // 转发链协议：取自前端协议选择器的取值域（vite-frontend/src/pages/tunnel.tsx 的 6 个 SelectItem），
    // 其它值 gost 无从解析，一定下发失败
    private static final Set<String> SUPPORTED_PROTOCOLS =
            new HashSet<>(Arrays.asList("tls", "wss", "tcp", "mtls", "mwss", "mtcp"));

    /**
     * 创建前的结构校验：chainType 缺失/矛盾、nodeId 缺失、端口非法、协议非法都在写库前判掉，
     * 不让它们变成 saveBatch 里 message 为 null 的异常（前端此前只能看到"保存异常：null"）。
     * 缺省 chainType 按位置推断补全（入口=1、链=2、出口=3），缺省 protocol 按 tls 补全；
     * 返回 null 表示校验通过，补全结果已写回 DTO 里的同一批对象，下发用的就是补全后的值。
     */
    private R validateChainRows(TunnelDto tunnelDto) {
        List<ChainTunnel> inNodes = tunnelDto.getInNodeId();
        // 同一节点在入口列表里出现两次会写两条 (tunnel_id, node_id, chain_type=1) 行：
        // 建转发时同一节点被写两条 forward_port、同名服务下发两次，必须在写库前拒掉。
        Set<Long> entryNodeIds = new LinkedHashSet<>();
        for (int i = 0; i < inNodes.size(); i++) {
            R error = checkChainRow(inNodes.get(i), 1, "第 " + (i + 1) + " 个入口节点");
            if (error != null) return error;
            Long nodeId = inNodes.get(i).getNodeId();
            if (!entryNodeIds.add(nodeId)) {
                return R.err(duplicateMessage("入口节点", "入口", nodeId));
            }
        }

        if (tunnelDto.getType() != 2) return null;

        List<List<ChainTunnel>> chainNodes = tunnelDto.getChainNodes();
        if (chainNodes == null) {
            // 直连出口的单跳隧道前端可能整个字段都不带，按空链处理，避免后面遍历 NPE
            chainNodes = new ArrayList<>();
            tunnelDto.setChainNodes(chainNodes);
        }
        for (int i = 0; i < chainNodes.size(); i++) {
            List<ChainTunnel> group = chainNodes.get(i);
            if (group == null) return R.err("第 " + (i + 1) + " 组转发链节点为空");
            // 去重范围只按"同一跳"算：同一跳里重复会造成同一 (tunnel_id, node_id, chain_type=2) 两条行，
            // 而一个节点的 chains_<id> 只承载它自己那一跳，跨跳重复是不同 inx 的独立行，不在这里拦。
            Set<Long> hopNodeIds = new LinkedHashSet<>();
            for (int j = 0; j < group.size(); j++) {
                R error = checkChainRow(group.get(j), 2, "第 " + (i + 1) + " 组第 " + (j + 1) + " 个转发链节点");
                if (error != null) return error;
                Long nodeId = group.get(j).getNodeId();
                if (!hopNodeIds.add(nodeId)) {
                    return R.err(duplicateMessage("中继第" + (i + 1) + "跳节点", "跳", nodeId));
                }
            }
        }

        List<ChainTunnel> outNodes = tunnelDto.getOutNodeId();
        if (outNodes == null) return null; // 已在入口按"出口不能为空"挡掉，这里不重复报
        Set<Long> outNodeIds = new LinkedHashSet<>();
        for (int j = 0; j < outNodes.size(); j++) {
            R error = checkChainRow(outNodes.get(j), 3, "第 " + (j + 1) + " 个出口节点");
            if (error != null) return error;
            Long nodeId = outNodes.get(j).getNodeId();
            if (!outNodeIds.add(nodeId)) {
                return R.err(duplicateMessage("出口节点", "出口", nodeId));
            }
        }

        // 同一节点只能出现在一个位置上：chains_<tid> 链名恒为 chains_+tunnelId（GostUtil.java:125）、
        // 中继服务名恒为 <tid>_tls（GostUtil.java:176），同一节点跨跳会在第二次下发时撞
        // chain already exists / service already exists 并触发整批回滚（go-gost x/socket/chain.go:21、
        // service.go:41）。创建与拓扑编辑入口虽然各自还有一次拍平兜底（createTunnel :119-120、
        // updateTunnelTopologyLocked :969-970），但那是最后一道，只能指名重复的 nodeId、给不出位置；
        // 这里按入口 → 中继 → 出口的顺序先拦，给出能直接改的位置信息（QA round47 WARN-2）。
        Set<Long> allNodeIds = new LinkedHashSet<>(entryNodeIds);
        for (int i = 0; i < chainNodes.size(); i++) {
            for (ChainTunnel row : chainNodes.get(i)) {
                Long nodeId = row.getNodeId();
                if (!allNodeIds.add(nodeId)) {
                    return R.err(duplicateMessage(null, null, nodeId));
                }
            }
        }
        for (Long nodeId : outNodeIds) {
            if (!allNodeIds.add(nodeId)) {
                return R.err(duplicateMessage(null, null, nodeId));
            }
        }
        return null;
    }

    /**
     * 重复节点提示的唯一出口：入口/中继同跳/出口的局部重复与"整个拓扑内跨位置重复"共用同一套骨架，
     * 细分校验和两处拍平兜底都只调这里，改文案不会再出现两套说法（QA round48 W3）。
     *
     * @param position 位置称呼（"入口节点"、"中继第1跳节点"、"出口节点"）；传 null 表示跨位置重复
     * @param unitHint position 非空时"同一X只能出现一次"里的 X（"入口"/"跳"/"出口"）
     */
    private static String duplicateMessage(String position, String unitHint, Long nodeId) {
        if (position == null) {
            return "节点重复：节点" + nodeId + " 在入口/中继/出口中重复出现（整个拓扑内只能出现一次）";
        }
        return position + "重复：节点" + nodeId + "（同一" + unitHint + "只能出现一次）";
    }

    /**
     * 拍平去重兜底：按出现顺序返回第一个重复节点的提示，没有重复返回 null。
     * 替换原先 {@code new HashSet<>(ids).size() != ids.size()} 的判 size 写法，校验强度不变，
     * 但报错能指名到具体 nodeId（原先只能说"节点重复"）。
     */
    private static String firstDuplicateMessage(List<Long> nodeIds) {
        Set<Long> seen = new HashSet<>();
        for (Long nodeId : nodeIds) {
            if (!seen.add(nodeId)) return duplicateMessage(null, null, nodeId);
        }
        return null;
    }

    /**
     * 单行校验。port/protocol 只对链(2)、出口(3)行有意义：入口行的这两个字段按设计就是空，
     * 不在这里补默认值，免得改掉入口既有的下发数据。
     */
    private R checkChainRow(ChainTunnel row, int expectedChainType, String position) {
        if (row == null) return R.err(position + "为空");
        if (row.getNodeId() == null) return R.err(position + "缺少 nodeId");
        if (row.getNodeId() <= 0) return R.err(position + "的 nodeId=" + row.getNodeId() + " 非法：必须是大于 0 的节点 id");

        Integer chainType = row.getChainType();
        if (chainType == null) {
            row.setChainType(expectedChainType);
            log.warn("缺省 chainType 已按位置补全：{} nodeId={} -> chainType={}", position, row.getNodeId(), expectedChainType);
        } else if (chainType != expectedChainType) {
            return R.err(position + "（nodeId=" + row.getNodeId() + "）的 chainType=" + chainType
                    + " 与位置矛盾：该位置只能是 " + expectedChainType);
        }

        if (expectedChainType == 1) return null;

        Integer port = row.getPort();
        if (port != null && (port <= 0 || port > 65535)) {
            return R.err(position + "（nodeId=" + row.getNodeId() + "）端口 " + port
                    + " 非法：必须在 1-65535 之间，留空则由系统自动分配");
        }

        String protocol = row.getProtocol();
        if (StringUtils.isBlank(protocol)) {
            row.setProtocol("tls");
            log.warn("缺省 protocol 已按 tls 补全：{} nodeId={}", position, row.getNodeId());
        } else if (!SUPPORTED_PROTOCOLS.contains(protocol.toLowerCase(Locale.ROOT))) {
            return R.err(position + "（nodeId=" + row.getNodeId() + "）protocol=" + protocol
                    + " 不支持：只能是 tls/wss/tcp/mtls/mwss/mtcp");
        }
        return null;
    }

    // 自动分口撞上节点上的僵尸监听时，最多试这么多个候选端口（含首次分配的那个）
    private static final int AUTO_PORT_ATTEMPTS = 3;

    /**
     * 下发入口、转发链、出口的 chains 与 service；全部成功返回 null，否则回滚本次已确认创建的资源。
     * autoPortRows 是自动分配端口的行，节点回 bind 失败时按 publishChainService 换口重试。
     */
    private R publishChains(Tunnel tunnel, TunnelDto tunnelDto, Map<Long, Node> nodes, Set<ChainTunnel> autoPortRows) {
        List<List<ChainTunnel>> chainNodes = tunnelDto.getChainNodes();
        List<ChainTunnel> outNodes = tunnelDto.getOutNodeId();
        List<Long> createdChains = new ArrayList<>();
        List<Long> createdServices = new ArrayList<>();
        List<String> portAttempts = new ArrayList<>();
        // 记录链路上下发过 chains 的节点与当时的 next，换口后要按新端口整条重发
        List<ChainPlan> chainPlans = new ArrayList<>();
        Set<ChainTunnel> changedPortRows = Collections.newSetFromMap(new IdentityHashMap<>());

        for (ChainTunnel inNode : tunnelDto.getInNodeId()) {
            // 入口指向第一跳，没有中间跳时直接指向出口
            List<ChainTunnel> next = chainNodes.isEmpty() ? outNodes : chainNodes.get(0);
            GostDto gostDto = createChains(inNode.getNodeId(), next, nodes, tunnel);
            if (!isOk(gostDto)) {
                return rollbackCreate(tunnel, inNode.getNodeId(), "chains_" + tunnel.getId(), gostDto, createdChains, createdServices, portAttempts);
            }
            createdChains.add(inNode.getNodeId());
            chainPlans.add(new ChainPlan(inNode.getNodeId(), next));
        }

        for (int i = 0; i < chainNodes.size(); i++) {
            // 每一跳的Chain指向下一跳，最后一跳指向出口
            List<ChainTunnel> next = i + 1 < chainNodes.size() ? chainNodes.get(i + 1) : outNodes;
            for (ChainTunnel chainNode : chainNodes.get(i)) {
                GostDto chainDto = createChains(chainNode.getNodeId(), next, nodes, tunnel);
                if (!isOk(chainDto)) {
                    return rollbackCreate(tunnel, chainNode.getNodeId(), "chains_" + tunnel.getId(), chainDto, createdChains, createdServices, portAttempts);
                }
                createdChains.add(chainNode.getNodeId());
                chainPlans.add(new ChainPlan(chainNode.getNodeId(), next));

                GostDto serviceDto = publishChainService(chainNode, nodes, autoPortRows.contains(chainNode), portAttempts, changedPortRows, tunnel);
                if (!isOk(serviceDto)) {
                    return rollbackCreate(tunnel, chainNode.getNodeId(), tunnel.getId() + "_tls", serviceDto, createdChains, createdServices, portAttempts);
                }
                createdServices.add(chainNode.getNodeId());
            }
        }

        for (ChainTunnel outNode : outNodes) {
            GostDto gostDto = publishChainService(outNode, nodes, autoPortRows.contains(outNode), portAttempts, changedPortRows, tunnel);
            if (!isOk(gostDto)) {
                return rollbackCreate(tunnel, outNode.getNodeId(), tunnel.getId() + "_tls", gostDto, createdChains, createdServices, portAttempts);
            }
            createdServices.add(outNode.getNodeId());
        }

        if (!changedPortRows.isEmpty()) {
            // chains 是在 service 之前下发的，那时用的还是自动分配的旧端口；
            // 换口只改了节点上的 service 和库里的记录，上游 chains 仍指向旧端口，链路必然是断的。
            return republishChains(tunnel, chainPlans, nodes, createdChains, createdServices, portAttempts);
        }
        return null;
    }

    /**
     * 换口后整条链重发：先删掉各节点上的同名 chains，再按换口后的端口重建。
     * 不能只改被换口的那个节点——它被上游引用，上游 chains 里写死了它的地址和端口。
     */
    private R republishChains(Tunnel tunnel, List<ChainPlan> chainPlans, Map<Long, Node> nodes,
                              List<Long> createdChains, List<Long> createdServices, List<String> portAttempts) {
        String chainName = "chains_" + tunnel.getId();
        for (ChainPlan plan : chainPlans) {
            GostDto deleted = deleteChains(plan.nodeId, chainName);
            if (!isOk(deleted)) {
                return rollbackCreate(tunnel, plan.nodeId, chainName, deleted, createdChains, createdServices, portAttempts);
            }
        }
        for (ChainPlan plan : chainPlans) {
            GostDto created = createChains(plan.nodeId, plan.next, nodes, tunnel);
            if (!isOk(created)) {
                return rollbackCreate(tunnel, plan.nodeId, chainName, created, createdChains, createdServices, portAttempts);
            }
        }
        log.warn("自动端口换口后已按新端口重发链路：tunnelId={} 涉及节点={}", tunnel.getId(),
                chainPlans.stream().map(plan -> plan.nodeId).collect(Collectors.toList()));
        return null;
    }

    // 一次创建里某个节点下发过的 chains：nodeId + 它当时指向的下一跳
    private static final class ChainPlan {
        private final Long nodeId;
        private final List<ChainTunnel> next;

        private ChainPlan(Long nodeId, List<ChainTunnel> next) {
            this.nodeId = nodeId;
            this.next = next;
        }
    }

    /**
     * 下发一条 chain/out 行的 service。自动分配的端口只保证"库里没人用"，节点上有没有僵尸服务占着
     * 只有下发时才知道：节点回 listen ... bind: address already in use 时换下一个候选端口重试。
     * 每次换口前先删掉同名 service 在上一次尝试留下的残留（下发失败不代表没登记），删不掉就停止换口——
     * 同名资源状态未知时换口重试的结果同样无法确认。显式端口由调用方指定，一律不换口。
     */
    private GostDto publishChainService(ChainTunnel chainTunnel, Map<Long, Node> nodes,
                                        boolean autoPort, List<String> portAttempts,
                                        Set<ChainTunnel> changedPortRows, Tunnel tunnel) {
        return publishChainService(chainTunnel, nodes, autoPort, portAttempts, changedPortRows, true, tunnel);
    }

    /**
     * @param persistPort 为 false 时不在下发成功后回写 chain_tunnel 端口：拓扑变更时这些行还没入库，
     *                    回写必然影响 0 行；最终端口由调用方在 saveBatch 时一并写入。
     */
    private GostDto publishChainService(ChainTunnel chainTunnel, Map<Long, Node> nodes,
                                        boolean autoPort, List<String> portAttempts,
                                        Set<ChainTunnel> changedPortRows, boolean persistPort, Tunnel tunnel) {
        Long nodeId = chainTunnel.getNodeId();
        String serviceName = chainTunnel.getTunnelId() + "_tls";
        GostDto gostDto = createChainService(nodeId, chainTunnel, nodes, tunnel);
        if (!autoPort || isOk(gostDto) || !isBindFailure(gostDto)) {
            return gostDto;
        }

        Set<Integer> tried = new LinkedHashSet<>();
        tried.add(chainTunnel.getPort());
        boolean stopDeleted = false;
        boolean portSwitched = false;
        while (isBindFailure(gostDto) && tried.size() < AUTO_PORT_ATTEMPTS) {
            Integer failedPort = chainTunnel.getPort();
            String reason = gostMsg(gostDto);
            List<Integer> candidates = candidatePorts(nodeId, tried);
            if (candidates.isEmpty()) {
                portAttempts.add("端口" + failedPort + " 失败(" + reason + ")，节点已无其它可用端口");
                log.warn("自动端口重试中止：节点{} 已无其它候选端口，tunnelId={}", nodeId, chainTunnel.getTunnelId());
                break;
            }
            GostDto cleanup = deleteService(nodeId, serviceName);
            if (!isOk(cleanup)) {
                // 同名 service 状态未知，再下发一次无法判断成败
                stopDeleted = true;
                portAttempts.add("端口" + failedPort + " 失败(" + reason + ")，换口前清理同名 service 未确认(" + gostMsg(cleanup) + ")");
                log.warn("自动端口重试中止：节点{} service {} 清理未确认({})，tunnelId={}",
                        nodeId, serviceName, gostMsg(cleanup), chainTunnel.getTunnelId());
                break;
            }
            Integer candidate = candidates.get(0);
            portSwitched = true;
            log.warn("自动端口被节点占用，换口重试：节点{} tunnelId={} 端口{} 失败原因={} 改用端口{}",
                    nodeId, chainTunnel.getTunnelId(), failedPort, reason, candidate);
            portAttempts.add("端口" + failedPort + " 失败(" + reason + ")，改用端口" + candidate);
            chainTunnel.setPort(candidate);
            tried.add(candidate);
            gostDto = createChainService(nodeId, chainTunnel, nodes, tunnel);
        }

        if (isOk(gostDto)) {
            if (!persistPort) {
                if (portSwitched) {
                    changedPortRows.add(chainTunnel);
                }
                return gostDto;
            }
            String syncError = updateChainPort(chainTunnel);
            if (syncError == null) {
                if (portSwitched) {
                    changedPortRows.add(chainTunnel);
                }
                return gostDto;
            }
            // 节点已监听新端口、库里还是旧端口：按失败处理，先删掉刚下发的 service，不留半成品
            GostDto cleanup = deleteService(nodeId, serviceName);
            portAttempts.add("端口" + chainTunnel.getPort() + " 换口成功但记录回写失败：" + syncError);
            log.warn("换口后回写隧道节点记录失败：节点{} tunnelId={} {}", nodeId, chainTunnel.getTunnelId(), syncError);
            GostDto failed = new GostDto();
            failed.setMsg(syncError + (isOk(cleanup)
                    ? "，已回滚删除刚下发的 service " + serviceName
                    : "，回滚删除 service " + serviceName + " 未确认(" + gostMsg(cleanup) + ")"));
            return failed;
        }

        if (isBindFailure(gostDto) && !stopDeleted) {
            // 候选耗尽：最后一次尝试可能在节点上留了同名 service，清掉再返回失败
            GostDto cleanup = deleteService(nodeId, serviceName);
            portAttempts.add(isOk(cleanup)
                    ? "已试 " + tried.size() + " 个候选端口，最后一次残留 service 已清理"
                    : "已试 " + tried.size() + " 个候选端口，最后一次残留 service 清理未确认(" + gostMsg(cleanup) + ")");
            log.warn("自动端口重试全部失败：节点{} tunnelId={} 尝试端口={}", nodeId, chainTunnel.getTunnelId(), tried);
        }
        return gostDto;
    }

    /**
     * 换口候选：节点端口段内、库里没人用、本次又没试过的端口，小的优先。
     * 只按库判断不探测节点——节点上的僵尸监听查不出来，正是要靠下发重试兜住的情况。
     */
    private List<Integer> candidatePorts(Long nodeId, Set<Integer> tried) {
        Node node = nodeService.getById(nodeId);
        List<Integer> candidates = new ArrayList<>();
        if (node == null) {
            return candidates;
        }
        Set<Integer> used = new HashSet<>(tried);
        for (ChainTunnel row : chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("node_id", nodeId))) {
            if (row.getPort() != null) {
                used.add(row.getPort());
            }
        }
        for (ForwardPort forwardPort : forwardPortService.list(new QueryWrapper<ForwardPort>().eq("node_id", nodeId))) {
            used.add(forwardPort.getPort());
        }
        for (Integer port : parsePorts(node.getPort())) {
            if (!used.contains(port)) {
                candidates.add(port);
            }
        }
        return candidates;
    }

    // 节点把端口占用报成 listen tcp ... bind: address already in use
    /** 只认端口占用；"bind: cannot assign requested address" 换端口也不会成功，不能算可换口的冲突。 */
    private static boolean isBindFailure(GostDto gostDto) {
        String msg = gostMsg(gostDto);
        return msg != null && (msg.contains("address already in use") || msg.contains("EADDRINUSE"));
    }

    // 换口成功后把最终端口写回隧道节点记录；返回 null 表示已落库
    private String updateChainPort(ChainTunnel chainTunnel) {
        try {
            boolean updated = chainTunnelService.update(new UpdateWrapper<ChainTunnel>()
                    .eq("tunnel_id", chainTunnel.getTunnelId())
                    .eq("node_id", chainTunnel.getNodeId())
                    .set("port", chainTunnel.getPort()));
            return updated ? null : "隧道节点记录端口回写影响 0 行";
        } catch (RuntimeException e) {
            return "隧道节点记录端口回写异常：" + e.getMessage();
        }
    }

    /**
     * 数据库写入结果必须显式判断：返回 false 或抛异常都不代表已落库，
     * 带着没落库的数据继续下发节点会留下"节点有资源、库里没记录"的孤儿。
     * 返回 null 表示写入成功，否则返回可直接拼进错误信息的描述。
     */
    private static String dbWriteError(BooleanSupplier write, String what) {
        return dbWriteError(write, what, null);
    }

    /**
     * @param rows 本次写入的行；异常 message 为空时用它兜底出可读原因，避免前端只看到"保存异常：null"
     */
    private static String dbWriteError(BooleanSupplier write, String what, List<?> rows) {
        try {
            return write.getAsBoolean() ? null : what + "保存失败";
        } catch (RuntimeException e) {
            return what + "保存异常：" + writeExceptionDetail(e, rows);
        }
    }

    /**
     * 写入异常的说明文案。message 为空时先翻原因链，仍然拿不到就按落库行报实体类名、行数与缺字段，
     * 让调用方知道该补哪个字段，而不是拿一个 null 去猜。
     */
    private static String writeExceptionDetail(Throwable e, List<?> rows) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (StringUtils.isNotBlank(cause.getMessage())) {
                return cause.getMessage();
            }
            if (cause.getCause() == cause) break;
        }
        if (rows == null || rows.isEmpty()) {
            return e.getClass().getSimpleName() + "（数据库未给出异常详情）";
        }
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row == null) {
                return "第 " + (i + 1) + " 行记录为空（数据库未给出异常详情）";
            }
            List<String> missing = missingRequiredFields(row);
            if (!missing.isEmpty()) {
                return row.getClass().getSimpleName() + " 第 " + (i + 1) + " 行缺少字段 "
                        + String.join("/", missing) + "（数据库未给出异常详情）";
            }
        }
        return rows.get(0).getClass().getSimpleName() + " 共 " + rows.size()
                + " 行写入失败，数据库未给出异常详情（可能是 NOT NULL、字段类型或连接问题）";
    }

    // 落库前不能为空的字段：缺了正是 saveBatch 抛 message 为 null 的那类异常
    private static List<String> missingRequiredFields(Object row) {
        List<String> missing = new ArrayList<>();
        if (row instanceof ChainTunnel) {
            ChainTunnel chainTunnel = (ChainTunnel) row;
            if (chainTunnel.getTunnelId() == null) missing.add("tunnelId");
            if (chainTunnel.getChainType() == null) missing.add("chainType");
            if (chainTunnel.getNodeId() == null) missing.add("nodeId");
        } else if (row instanceof Tunnel) {
            Tunnel tunnel = (Tunnel) row;
            if (tunnel.getName() == null) missing.add("name");
            if (tunnel.getType() == null) missing.add("type");
        }
        return missing;
    }

    /**
     * 清理创建失败的隧道记录与节点记录。这是尽力补偿，不是跨库事务：
     * 删除返回 false 说明清理结果无法确认，抛异常说明清理失败，两种都必须报给调用方（null 表示都清理成功）。
     *
     * @param chainsConfirmed 节点记录此前是否已确认落库；没有写过时删除影响 0 行属预期，不能报成"未确认已清理"
     */
    private String cleanupTunnelRecords(Long tunnelId, boolean chainsConfirmed) {
        List<String> leftovers = new ArrayList<>();
        try {
            if (!this.removeById(tunnelId)) {
                leftovers.add("隧道记录 id=" + tunnelId + " 删除返回 false，未确认已清理");
            }
        } catch (RuntimeException e) {
            leftovers.add("隧道记录 id=" + tunnelId + " 清理失败(" + e.getMessage() + ")");
        }
        try {
            if (!chainTunnelService.remove(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnelId))) {
                if (chainsConfirmed) {
                    leftovers.add("隧道节点记录删除返回 false，未确认已清理");
                } else {
                    log.info("隧道节点记录删除影响 0 行，本次未确认写入过节点记录，属预期：tunnelId={}", tunnelId);
                }
            }
        } catch (RuntimeException e) {
            leftovers.add("隧道节点记录清理失败(" + e.getMessage() + ")");
        }
        return leftovers.isEmpty() ? null : "记录清理未完成，需人工核查：" + String.join("，", leftovers);
    }

    /**
     * 回滚本次已确认下发成功的 chains 与 service，再清理隧道及其节点记录。
     * 失败目标一律不删：exists 说明同名资源早已存在（配置是否一致未知），超时/无响应说明结果未知，
     * 两者都不能当作本次创建物；回滚本身失败的目标写进错误信息由运维手工清理，不保证跨节点原子性。
     */
    private R rollbackCreate(Tunnel tunnel, Long failedNodeId, String failedName, GostDto failedDto,
                             List<Long> createdChains, List<Long> createdServices) {
        return rollbackCreate(tunnel, failedNodeId, failedName, failedDto, createdChains, createdServices, null);
    }

    // portAttempts 只为把换口重试的每一次尝试带进错误信息，不影响回滚动作
    private R rollbackCreate(Tunnel tunnel, Long failedNodeId, String failedName, GostDto failedDto,
                             List<Long> createdChains, List<Long> createdServices, List<String> portAttempts) {
        Long tunnelId = tunnel.getId();
        String chainName = "chains_" + tunnelId;
        String serviceName = tunnelId + "_tls";
        List<String> leftovers = new ArrayList<>();

        // service 依赖 chain，先删 service
        for (Long nodeId : createdServices) {
            GostDto deleteDto = deleteService(nodeId, serviceName);
            if (!isOk(deleteDto)) {
                leftovers.add("节点" + nodeId + " service " + serviceName + "(" + gostMsg(deleteDto) + ")");
            }
        }
        for (Long nodeId : createdChains) {
            GostDto deleteDto = deleteChains(nodeId, chainName);
            if (!isOk(deleteDto)) {
                leftovers.add("节点" + nodeId + " chain " + chainName + "(" + gostMsg(deleteDto) + ")");
            }
        }

        String recordCleanup = cleanupTunnelRecords(tunnelId, true);

        StringBuilder message = new StringBuilder("隧道创建失败: ").append(gostMsg(failedDto))
                .append("；节点").append(failedNodeId).append(" 的 ").append(failedName);
        if (isConflict(failedDto)) {
            message.append(" 已存在同名资源，无法确认其配置与本隧道一致，已保留原资源未删除");
        } else {
            message.append(" 下发结果未确认（可能已生效），未回滚删除");
        }
        if (portAttempts != null && !portAttempts.isEmpty()) {
            message.append("；端口尝试：").append(String.join("；", portAttempts));
        }
        if (!leftovers.isEmpty()) {
            message.append("；回滚未完成，请手工清理: ").append(String.join("，", leftovers));
        }
        if (recordCleanup != null) {
            message.append("；").append(recordCleanup);
        }
        return R.err(message.toString());
    }

    private static boolean isOk(GostDto gostDto) {
        return gostDto != null && "OK".equals(gostDto.getMsg());
    }

    // 节点对同名资源返回 exists；只说明存在，不代表配置一致
    private static boolean isConflict(GostDto gostDto) {
        return gostDto != null && gostDto.getMsg() != null && gostDto.getMsg().contains("exists");
    }

    private static String gostMsg(GostDto gostDto) {
        return gostDto == null || gostDto.getMsg() == null ? "无响应" : gostDto.getMsg();
    }

    // 下发动作的唯一出口；GostUtil 是静态方法，留出可替换点便于测试。
    // 创建流程走 Create* 变体：exists 冲突原样返回，不能算作本次创建成功。
    protected GostDto createChains(Long nodeId, List<ChainTunnel> chainTunnels, Map<Long, Node> nodes, Tunnel tunnel) {
        return GostUtil.CreateChains(nodeId, chainTunnels, nodes, tunnel);
    }

    protected GostDto createChainService(Long nodeId, ChainTunnel chainTunnel, Map<Long, Node> nodes, Tunnel tunnel) {
        return GostUtil.CreateChainService(nodeId, chainTunnel, nodes, tunnel);
    }

    protected GostDto deleteChains(Long nodeId, String name) {
        return GostUtil.DeleteChains(nodeId, name);
    }

    protected GostDto deleteService(Long nodeId, String name) {
        JSONArray services = new JSONArray();
        services.add(name);
        return GostUtil.DeleteService(nodeId, services);
    }

    protected GostDto deleteLimiters(Long nodeId, Long limiterId) {
        return GostUtil.DeleteLimiters(nodeId, limiterId);
    }


    @Override
    public R getAllTunnels() {
        List<Tunnel> tunnelList = this.list();
        
        // 查询所有隧道的ChainTunnel信息
        List<Long> tunnelIds = tunnelList.stream()
                .map(Tunnel::getId)
                .collect(Collectors.toList());
        
        if (tunnelIds.isEmpty()) {
            return R.ok(new ArrayList<TunnelDetailDto>());
        }
        
        // 批量查询所有ChainTunnel记录
        List<ChainTunnel> allChainTunnels = chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().in("tunnel_id", tunnelIds)
        );
        
        // 按tunnelId分组
        Map<Long, List<ChainTunnel>> chainTunnelMap = allChainTunnels.stream()
                .collect(Collectors.groupingBy(ChainTunnel::getTunnelId));
        
        // 转换为TunnelDetailDto列表
        List<TunnelDetailDto> detailDtoList = tunnelList.stream()
                .map(tunnel -> {
                    TunnelDetailDto detailDto = new TunnelDetailDto();
                    BeanUtils.copyProperties(tunnel, detailDto);
                    
                    // 组内顺序不能看 SQL 的行序：与对账路径（ForwardServiceImpl.resyncChainResources）共用
                    // 同一个比较器，(chain_type, inx, id) 一致，面板/发布/对账三处才算出同一条链
                    List<ChainTunnel> chainTunnels = ForwardServiceImpl.sortChainRows(
                            chainTunnelMap.getOrDefault(tunnel.getId(), new ArrayList<>()));
                    
                    // 按chainType分类节点
                    // 入口节点 (chainType = 1)
                    List<ChainTunnel> inNodes = chainTunnels.stream()
                            .filter(ct -> ct.getChainType() != null && ct.getChainType() == 1)
                            .collect(Collectors.toList());
                    detailDto.setInNodeId(inNodes);

                    detailDto.setInIp(tunnel.getInIp());
                    
                    // 转发链节点 (chainType = 2) - 按inx分组
                    Map<Integer, List<ChainTunnel>> chainNodesMap = chainTunnels.stream()
                            .filter(ct -> ct.getChainType() != null && ct.getChainType() == 2)
                            .collect(Collectors.groupingBy(
                                    ct -> ct.getInx() != null ? ct.getInx() : 0,
                                    Collectors.toList()
                            ));
                    
                    // 将Map转换为按inx排序的二维列表
                    List<List<ChainTunnel>> chainNodesList = chainNodesMap.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(Map.Entry::getValue)
                            .collect(Collectors.toList());
                    detailDto.setChainNodes(chainNodesList);
                    
                    // 出口节点 (chainType = 3)
                    List<ChainTunnel> outNodes = chainTunnels.stream()
                            .filter(ct -> ct.getChainType() != null && ct.getChainType() == 3)
                            .collect(Collectors.toList());
                    detailDto.setOutNodeId(outNodes);
                    
                    return detailDto;
                })
                .collect(Collectors.toList());
        
        return R.ok(detailDtoList);
    }


    @Override
    public R updateTunnel(TunnelUpdateDto tunnelUpdateDto) {
        if (tunnelUpdateDto == null || tunnelUpdateDto.getId() == null) {
            return R.err("隧道ID不能为空");
        }
        Tunnel existingTunnel = this.getById(tunnelUpdateDto.getId());
        if (existingTunnel == null) return R.err("隧道不存在");
        Tunnel tunnel = new Tunnel();
        tunnel.setId(tunnelUpdateDto.getId());
        tunnel.setName(tunnelUpdateDto.getName());
        tunnel.setFlow(tunnelUpdateDto.getFlow());
        tunnel.setTrafficRatio(tunnelUpdateDto.getTrafficRatio());
        tunnel.setInIp(tunnelUpdateDto.getInIp());

        if (StringUtils.isEmpty(tunnel.getInIp())){
            StringBuilder in_ip = new StringBuilder();
            List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));
            // 入口行缺失（历史数据不一致或并发删除）时不能 deleteCharAt(-1)：旧实现会直接抛
            // StringIndexOutOfBoundsException，接口回 500 而不是可读的错误。
            if (chainTunnels == null || chainTunnels.isEmpty()) {
                return R.err("隧道缺少入口节点，无法计算入口地址");
            }
            for (ChainTunnel chainTunnel : chainTunnels) {
                Node node = nodeService.getById(chainTunnel.getNodeId());
                if (node == null)return R.err("隧道节点数据错误，部分节点不存在");
                String serverIp = node.getServerIp();
                if (StringUtils.isEmpty(serverIp)) {
                    return R.err("隧道入口节点 " + node.getId() + " 缺少服务器地址");
                }
                in_ip.append(serverIp).append(",");
            }
            in_ip.deleteCharAt(in_ip.length() - 1);
            tunnel.setInIp(in_ip.toString());
        }

        this.updateById(tunnel);
        return R.ok();
    }


    @Override
    public R updateTunnelTopology(TunnelTopologyDto tunnelTopologyDto) {
        if (tunnelTopologyDto == null || tunnelTopologyDto.getId() == null) return R.err("隧道ID不能为空");
        Long tunnelId = tunnelTopologyDto.getId();
        ReentrantLock lock = TOPOLOGY_LOCKS.computeIfAbsent(tunnelId, key -> new ReentrantLock());
        if (!lock.tryLock()) {
            return R.err("该隧道正在处理拓扑变更，请稍后再试");
        }
        try {
            return updateTunnelTopologyLocked(tunnelTopologyDto);
        } finally {
            lock.unlock();
            // 没有等待者时移除，避免长期堆积；有等待者时移除也无害（它们持有旧引用），下次重新建锁
            if (!lock.hasQueuedThreads() && TOPOLOGY_LOCKS.get(tunnelId) == lock) {
                TOPOLOGY_LOCKS.remove(tunnelId, lock);
            }
        }
    }

    private R updateTunnelTopologyLocked(TunnelTopologyDto tunnelTopologyDto) {
        Tunnel tunnel = this.getById(tunnelTopologyDto.getId());
        if (tunnel == null) return R.err("隧道不存在");

        // 有转发时：中继/出口可以热改；入口节点换人也能做，但要求入口数量不变，按顺序把每条转发的
        // 入口服务与 forward_port 行迁到新节点（migrateEntryNodes）。数量一变就无法一一对应，只能先删转发。
        long forwardCount = forwardService.count(new QueryWrapper<Forward>().eq("tunnel_id", tunnel.getId()));
        List<ChainTunnel> oldRows = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()));
        List<ChainTunnel> oldEntries = oldRows.stream()
                .filter(row -> Objects.equals(row.getChainType(), 1))
                .sorted(Comparator.comparing(ChainTunnel::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        List<Long> oldEntryIds = oldEntries.stream().map(ChainTunnel::getNodeId).collect(Collectors.toList());
        List<Long> newEntryIds = tunnelTopologyDto.getInNodeId() == null ? Collections.emptyList()
                : tunnelTopologyDto.getInNodeId().stream().map(ChainTunnel::getNodeId).collect(Collectors.toList());
        boolean entriesChanged = !oldEntryIds.equals(newEntryIds);
        if (forwardCount > 0 && entriesChanged && oldEntryIds.size() != newEntryIds.size()) {
            return R.err("隧道下仍有 " + forwardCount + " 条转发，入口节点数量变化（"
                    + oldEntryIds + " → " + newEntryIds + "）：多入口迁移无法按序对应，请先删除这些转发；"
                    + "入口数量不变可以直接保存，中继/出口可单独改");
        }
        if (tunnelTopologyDto.getInNodeId() == null || tunnelTopologyDto.getInNodeId().isEmpty()) {
            return R.err("入口节点不能为空");
        }
        Integer resolvedType = tunnelTopologyDto.getType() != null ? tunnelTopologyDto.getType() : tunnel.getType();
        if (resolvedType == null || (resolvedType != 1 && resolvedType != 2)) return R.err("隧道类型只能是 1（直连）或 2（隧道转发）");
        int type = resolvedType;
        boolean hasChains = tunnelTopologyDto.getChainNodes() != null && !tunnelTopologyDto.getChainNodes().isEmpty();
        boolean hasOut = tunnelTopologyDto.getOutNodeId() != null && !tunnelTopologyDto.getOutNodeId().isEmpty();
        if (type == 1 && (hasChains || hasOut)) return R.err("直连隧道不能配置转发链或出口节点");
        if (type == 2 && !hasOut) return R.err("出口不能为空");

        // 类型切换会改变每条转发的 handler 是否挂 chains_<id>：库改了但节点不重发，面板显示已切换、
        // 节点仍按旧类型转发（round41 真机缺口）。类型切换与入口迁移同时做时回滚面会交叉
        // （旧类型的中继资源在新入口上并不存在），强制拆成两次保存，保证每次失败都能整体回退。
        Integer oldType = tunnel.getType();
        boolean typeChanged = !Objects.equals(oldType, type);
        if (typeChanged && entriesChanged && forwardCount > 0) {
            return R.err("隧道类型变更与入口节点变更不能同时进行（仍有 " + forwardCount
                    + " 条转发）：请先保存类型变更，确认无误后再迁移入口节点");
        }
        // 下发用的隧道视图：type 设成目标值；restoreTopology 仍用库里的 tunnel（旧 type），两者不能混用
        Tunnel dispatchTunnel = tunnel;
        if (typeChanged) {
            dispatchTunnel = new Tunnel();
            BeanUtils.copyProperties(tunnel, dispatchTunnel);
            dispatchTunnel.setType(type);
        }

        // 复用创建时的结构校验：缺省 chainType/protocol 会按位置补全，矛盾或非法值在写库、下发之前报出来
        TunnelDto topologyDto = new TunnelDto();
        topologyDto.setName(tunnel.getName());
        topologyDto.setType(type);
        topologyDto.setInNodeId(tunnelTopologyDto.getInNodeId());
        topologyDto.setChainNodes(tunnelTopologyDto.getChainNodes());
        topologyDto.setOutNodeId(tunnelTopologyDto.getOutNodeId());
        R structureError = validateChainRows(topologyDto);
        if (structureError != null) return structureError;

        List<ChainTunnel> newRows = new ArrayList<>();
        List<Long> newNodeIds = new ArrayList<>();
        Map<Long, Node> nodes = new HashMap<>();
        Set<ChainTunnel> autoPortRows = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ChainTunnel inNode : topologyDto.getInNodeId()) {
            Node node = loadTopologyNode(inNode.getNodeId(), nodes, newNodeIds);
            if (node == null) return R.err("入口节点不存在：" + inNode.getNodeId());
            newRows.add(inNode);
        }
        if (type == 2) {
            int inx = 1;
            for (List<ChainTunnel> group : topologyDto.getChainNodes()) {
                for (ChainTunnel chainNode : group) {
                    Node node = loadTopologyNode(chainNode.getNodeId(), nodes, newNodeIds);
                    if (node == null) return R.err("转发链节点不存在：" + chainNode.getNodeId());
                    R portError = resolveChainPort(chainNode, node, autoPortRows, tunnel.getId());
                    if (portError != null) return portError;
                    chainNode.setInx(inx);
                    newRows.add(chainNode);
                }
                inx++;
            }
            for (ChainTunnel outNode : topologyDto.getOutNodeId()) {
                Node node = loadTopologyNode(outNode.getNodeId(), nodes, newNodeIds);
                if (node == null) return R.err("出口节点不存在：" + outNode.getNodeId());
                R portError = resolveChainPort(outNode, node, autoPortRows, tunnel.getId());
                if (portError != null) return portError;
                newRows.add(outNode);
            }
        }
        // 下发用的资源名由行上的 tunnelId 拼（chains_<id>、<id>_tls）：这些行还没入库，
        // 不先挂上隧道就会发出 chains_null / null_tls（真机已踩，见台账 round22）
        for (ChainTunnel row : newRows) {
            row.setTunnelId(tunnel.getId());
        }
        String flatDuplicateError = firstDuplicateMessage(newNodeIds);
        if (flatDuplicateError != null) return R.err(flatDuplicateError);
        for (Node node : nodes.values()) {
            if (node.getStatus() == null || node.getStatus() != 1) {
                return R.err("部分节点不在线：节点" + node.getId() + "（" + node.getName() + "）");
            }
        }

        String targetInIp = StringUtils.isNotBlank(tunnelTopologyDto.getInIp())
                ? tunnelTopologyDto.getInIp()
                : buildInIp(topologyDto.getInNodeId(), nodes);
        if (StringUtils.isBlank(targetInIp)) {
            return R.err("入口节点缺少服务器地址，无法计算入口地址，请在请求里显式传 inIp");
        }

        Set<Long> oldNodeIds = oldRows.stream().map(ChainTunnel::getNodeId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> newNodeSet = new LinkedHashSet<>(newNodeIds);
        Set<Long> oldOnlyNodes = new LinkedHashSet<>(oldNodeIds);
        oldOnlyNodes.removeAll(newNodeSet);
        Set<Long> newOnlyNodes = new LinkedHashSet<>(newNodeSet);
        newOnlyNodes.removeAll(oldNodeIds);

        List<String> notes = new ArrayList<>();
        if (type == 2) {
            List<String> portAttempts = new ArrayList<>();
            Topology newTopology = new Topology(topologyDto.getInNodeId(),
                    topologyDto.getChainNodes() == null ? Collections.emptyList() : topologyDto.getChainNodes(),
                    topologyDto.getOutNodeId());
            // 下发视图用 dispatchTunnel（目标 type=2）：传库里的旧行会在 1→2 切换时拿 type=1 下发链资源，
            // 被 GostUtil 的 Fail-Closed 守卫拒发，整次拓扑变更直接失败
            String publishError;
            try {
                publishError = publishTopology(dispatchTunnel, newTopology, nodes, autoPortRows, false, portAttempts, "拓扑变更");
            } catch (RuntimeException e) {
                publishError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (publishError != null) {
                List<String> recovery = new ArrayList<>();
                String restore = restoreTopology(tunnel, oldRows, newOnlyNodes, recovery);
                StringBuilder message = new StringBuilder("拓扑变更失败: ").append(publishError);
                if (!portAttempts.isEmpty()) {
                    message.append("；端口尝试：").append(String.join("；", portAttempts));
                }
                if (restore != null) message.append("；").append(restore);
                if (!recovery.isEmpty()) message.append("；").append(String.join("；", recovery));
                return R.err(message.toString());
            }
        }

        // 入口换人：新入口的 chains_<id> 已由 publishTopology 建好，这里按顺序把每条转发的入口服务
        // 与 forward_port 行搬过去；任何一条失败都回滚本次已迁移的转发，再把旧拓扑恢复回来。
        if (forwardCount > 0 && entriesChanged) {
            String migrateError = forwardService.migrateEntryNodes(dispatchTunnel, oldEntries,
                    topologyDto.getInNodeId(), nodes);
            if (migrateError != null) {
                List<String> recovery = new ArrayList<>();
                String restore = restoreTopology(tunnel, oldRows, newOnlyNodes, recovery);
                StringBuilder message = new StringBuilder("入口迁移失败: ").append(migrateError);
                if (restore != null) message.append("；").append(restore);
                if (!recovery.isEmpty()) message.append("；").append(String.join("；", recovery));
                return R.err(message.toString());
            }
            notes.add("入口节点已迁移：" + oldEntryIds + " → " + newEntryIds + "，转发入口服务已按序重绑");
        }

        // 类型切换：存量转发必须按新类型重发一遍（handler 是否挂 chains_<id> 变了），否则库与节点语义不一致。
        // 这一步放在拓扑下发/入口迁移之后、换库之前：失败还能把旧类型服务重建回来并恢复旧拓扑。
        if (typeChanged && forwardCount > 0) {
            List<Long> targetEntryIds = topologyDto.getInNodeId().stream()
                    .map(ChainTunnel::getNodeId).collect(Collectors.toList());
            String rebuildError = forwardService.rebuildTunnelForwardServices(dispatchTunnel, targetEntryIds);
            if (rebuildError != null) {
                String rollback = forwardService.rebuildTunnelForwardServices(tunnel, targetEntryIds);
                List<String> problems = new ArrayList<>();
                problems.add("隧道类型变更失败：" + rebuildError);
                problems.add(rollback == null ? "已按旧类型恢复转发入口服务"
                        : "按旧类型回滚转发服务未完成：" + rollback);
                List<String> recovery = new ArrayList<>();
                String restore = restoreTopology(tunnel, oldRows, newOnlyNodes, recovery);
                if (restore != null) problems.add(restore);
                if (!recovery.isEmpty()) problems.addAll(recovery);
                return R.err(String.join("；", problems));
            }
            notes.add("隧道类型已切换：" + oldType + " → " + type + "，已按新类型重建存量转发入口服务（暂停中的转发保持暂停）");
        }

        // 2→1：中继链与 <tid>_tls 已经不再属于这个隧道，但入口集合没变的节点仍会留在 oldNodeIds 里，
        // 原实现只清 oldOnlyNodes，导致这些节点上的 chains_<id>/<tid>_tls 永久残留（孤儿清扫只看行是否存在，不会回收）。
        if (typeChanged && oldType != null && oldType == 2 && type == 1) {
            for (Long nodeId : oldNodeIds) {
                if (oldOnlyNodes.contains(nodeId)) {
                    continue; // 下面统一清，避免重复下发
                }
                cleanTunnelResources(nodeId, tunnel.getId(), notes);
            }
        }

        // 旧拓扑独有的节点必须趁行还在时清：库替换后这些节点已查不到，deleteTunnel 也不会再覆盖
        for (Long nodeId : oldOnlyNodes) {
            cleanTunnelResources(nodeId, tunnel.getId(), notes);
        }

        List<Long> oldIds = oldRows.stream().map(ChainTunnel::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (!oldIds.isEmpty()) {
            String removeError = dbWriteError(() -> chainTunnelService.removeByIds(oldIds), "旧拓扑记录");
            if (removeError != null) {
                String restore = restoreTopology(tunnel, oldRows, newOnlyNodes, notes);
                List<String> problems = new ArrayList<>();
                problems.add(removeError);
                problems.add(restore == null ? "节点已恢复旧拓扑" : restore);
                appendEntryRollback(problems, tunnel, entriesChanged, forwardCount, topologyDto.getInNodeId(), oldEntries);
                return R.err(String.join("；", problems));
            }
        }
        String saveError = dbWriteError(() -> chainTunnelService.saveBatch(newRows), "新拓扑记录", newRows);
        if (saveError != null) {
            // 旧行已经删掉：先尝试原样回填，再把节点恢复成旧拓扑，失败项如实带回
            List<String> recovery = new ArrayList<>();
            String reinsertError = dbWriteError(() -> chainTunnelService.saveBatch(oldRows), "旧拓扑记录回填", oldRows);
            if (reinsertError != null) recovery.add(reinsertError);
            String restore = restoreTopology(tunnel, oldRows, newOnlyNodes, recovery);
            if (restore != null) recovery.add(restore);
            recovery.add(saveError);
            appendEntryRollback(recovery, tunnel, entriesChanged, forwardCount, topologyDto.getInNodeId(), oldEntries);
            return R.err(recovery.isEmpty() ? "新拓扑记录写入失败" : String.join("；", recovery));
        }

        Tunnel patch = new Tunnel();
        patch.setId(tunnel.getId());
        patch.setType(type);
        patch.setInIp(targetInIp);
        patch.setUpdatedTime(System.currentTimeMillis());
        String patchError = dbWriteError(() -> this.updateById(patch), "隧道记录");
        if (patchError != null) {
            notes.add("拓扑已切换，但隧道记录更新失败：" + patchError);
        }
        log.warn("隧道 {} 拓扑已变更：类型={} 入口节点={} 中继分组={} 出口节点={}", tunnel.getId(), type,
                topologyDto.getInNodeId().stream().map(ChainTunnel::getNodeId).collect(Collectors.toList()),
                topologyDto.getChainNodes() == null ? 0 : topologyDto.getChainNodes().size(),
                topologyDto.getOutNodeId() == null ? 0 : topologyDto.getOutNodeId().size());
        return notes.isEmpty() ? R.ok() : R.ok(notes);
    }

    /**
     * 拓扑换库失败时把已经搬走的入口服务与 forward_port 行搬回旧节点。
     * 必须在 restoreTopology 之后调用：旧入口节点的 chains_&lt;id&gt; 要先恢复，转发服务才有链可挂。
     */
    private void appendEntryRollback(List<String> problems, Tunnel tunnel, boolean entriesChanged,
                                     long forwardCount, List<ChainTunnel> newEntries, List<ChainTunnel> oldEntries) {
        if (!entriesChanged || forwardCount <= 0) {
            return;
        }
        Map<Long, Node> oldNodes = new HashMap<>();
        for (ChainTunnel row : oldEntries) {
            Node node = nodeService.getById(row.getNodeId());
            if (node == null) {
                problems.add("入口服务回滚失败：旧入口节点" + row.getNodeId() + "不存在");
                return;
            }
            oldNodes.put(node.getId(), node);
        }
        String reverse = forwardService.migrateEntryNodes(tunnel.getId(),
                newEntries, oldEntries, oldNodes);
        problems.add(reverse == null
                ? "入口服务与端口记录已迁回旧节点"
                : "入口服务回滚失败：" + reverse);
    }

    private Node loadTopologyNode(Long nodeId, Map<Long, Node> nodes, List<Long> nodeIds) {
        if (nodeId == null) return null;
        Node node = nodeService.getById(nodeId);
        if (node == null) return null;
        nodes.put(nodeId, node);
        nodeIds.add(nodeId);
        return node;
    }

    private static String buildInIp(List<ChainTunnel> inNodes, Map<Long, Node> nodes) {
        StringBuilder inIp = new StringBuilder();
        for (ChainTunnel inNode : inNodes) {
            Node node = nodes.get(inNode.getNodeId());
            if (node == null || StringUtils.isBlank(node.getServerIp())) {
                return null;
            }
            if (inIp.length() > 0) inIp.append(',');
            inIp.append(node.getServerIp());
        }
        return inIp.length() == 0 ? null : inIp.toString();
    }

    /**
     * 按给定拓扑重建节点侧资源：先把拓扑内每个节点的 chains_&lt;id&gt; 与 &lt;id&gt;_tls 全删一遍，再按角色创建。
     * 名字是固定的，不会碰到其它隧道或转发的资源；删除对 not found 幂等，重复执行安全。
     * 返回 null 表示全部确认成功，否则返回可直接拼进错误信息的描述。
     */
    private String publishTopology(Tunnel tunnel, Topology topology, Map<Long, Node> nodes,
                                   Set<ChainTunnel> autoPortRows, boolean persistPort,
                                   List<String> portAttempts, String phase) {
        String chainName = "chains_" + tunnel.getId();
        String serviceName = tunnel.getId() + "_tls";
        Set<Long> allNodeIds = new LinkedHashSet<>();
        for (ChainTunnel row : topology.inNodes) allNodeIds.add(row.getNodeId());
        for (List<ChainTunnel> group : topology.chainGroups) {
            for (ChainTunnel row : group) allNodeIds.add(row.getNodeId());
        }
        for (ChainTunnel row : topology.outNodes) allNodeIds.add(row.getNodeId());

        for (Long nodeId : allNodeIds) {
            GostDto deletedChains = deleteChains(nodeId, chainName);
            if (!isOk(deletedChains)) {
                return phase + "失败：节点" + nodeId + " 清理旧 " + chainName + " 未成功(" + gostMsg(deletedChains) + ")";
            }
            GostDto deletedService = deleteService(nodeId, serviceName);
            if (!isOk(deletedService)) {
                return phase + "失败：节点" + nodeId + " 清理旧 " + serviceName + " 未成功(" + gostMsg(deletedService) + ")";
            }
        }

        List<ChainPlan> chainPlans = new ArrayList<>();
        Set<ChainTunnel> changedPortRows = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ChainTunnel inNode : topology.inNodes) {
            List<ChainTunnel> next = topology.chainGroups.isEmpty() ? topology.outNodes : topology.chainGroups.get(0);
            GostDto created = createChains(inNode.getNodeId(), next, nodes, tunnel);
            if (!isOk(created)) {
                return phase + "失败：节点" + inNode.getNodeId() + " 下发 " + chainName + " 未成功(" + gostMsg(created) + ")";
            }
            chainPlans.add(new ChainPlan(inNode.getNodeId(), next));
        }
        for (int i = 0; i < topology.chainGroups.size(); i++) {
            List<ChainTunnel> next = i + 1 < topology.chainGroups.size() ? topology.chainGroups.get(i + 1) : topology.outNodes;
            for (ChainTunnel chainNode : topology.chainGroups.get(i)) {
                GostDto created = createChains(chainNode.getNodeId(), next, nodes, tunnel);
                if (!isOk(created)) {
                    return phase + "失败：节点" + chainNode.getNodeId() + " 下发 " + chainName + " 未成功(" + gostMsg(created) + ")";
                }
                chainPlans.add(new ChainPlan(chainNode.getNodeId(), next));
                GostDto serviceDto = publishChainService(chainNode, nodes, autoPortRows.contains(chainNode),
                        portAttempts, changedPortRows, persistPort, tunnel);
                if (!isOk(serviceDto)) {
                    return phase + "失败：节点" + chainNode.getNodeId() + " 下发 " + serviceName + " 未成功(" + gostMsg(serviceDto) + ")";
                }
            }
        }
        for (ChainTunnel outNode : topology.outNodes) {
            GostDto serviceDto = publishChainService(outNode, nodes, autoPortRows.contains(outNode),
                    portAttempts, changedPortRows, persistPort, tunnel);
            if (!isOk(serviceDto)) {
                return phase + "失败：节点" + outNode.getNodeId() + " 下发 " + serviceName + " 未成功(" + gostMsg(serviceDto) + ")";
            }
        }
        if (!changedPortRows.isEmpty()) {
            // service 换口后上游 chains 仍指向旧端口：按最终端口把整条链删掉重建
            for (ChainPlan plan : chainPlans) {
                GostDto deleted = deleteChains(plan.nodeId, chainName);
                if (!isOk(deleted)) {
                    return phase + "失败：换口后节点" + plan.nodeId + " 清理 " + chainName + " 未成功(" + gostMsg(deleted) + ")";
                }
                GostDto created = createChains(plan.nodeId, plan.next, nodes, tunnel);
                if (!isOk(created)) {
                    return phase + "失败：换口后节点" + plan.nodeId + " 重建 " + chainName + " 未成功(" + gostMsg(created) + ")";
                }
            }
            log.warn("拓扑变更时自动端口换口，已按最终端口重发链路：tunnelId={} 换口节点={}", tunnel.getId(),
                    changedPortRows.stream().map(ChainTunnel::getNodeId).collect(Collectors.toList()));
        }
        return null;
    }

    /**
     * 尽力恢复到变更前的拓扑：按库里的旧行重建，再清掉新拓扑独有节点上的残留。
     * 返回 null 表示全部确认恢复；否则返回未完成项，调用方负责把它带给管理员。
     */
    private String restoreTopology(Tunnel tunnel, List<ChainTunnel> oldRows, Set<Long> newOnlyNodes, List<String> problems) {
        List<String> failures = new ArrayList<>();
        if (tunnel.getType() != null && tunnel.getType() == 2 && oldRows != null && !oldRows.isEmpty()) {
            Map<Long, Node> oldNodes = new HashMap<>();
            boolean missingNode = false;
            for (ChainTunnel row : oldRows) {
                Node node = nodeService.getById(row.getNodeId());
                if (node == null) {
                    failures.add("旧拓扑节点" + row.getNodeId() + "不存在，无法恢复其资源");
                    missingNode = true;
                } else {
                    oldNodes.put(node.getId(), node);
                }
            }
            if (!missingNode) {
                String restoreError = publishTopology(tunnel, topologyOfRows(oldRows), oldNodes,
                        Collections.emptySet(), true, new ArrayList<>(), "恢复旧拓扑");
                if (restoreError != null) failures.add(restoreError);
            }
        }
        // 旧拓扑是直连时 chains_<id>/<tid>_tls 本来就不该存在：本次 publishTopology 可能已在「与旧入口重合、
        // 不在 newOnlyNodes 里」的节点上建过这两类资源，失败回退时必须连这些节点一起清，否则残留会一直占端口。
        Set<Long> toClean = new LinkedHashSet<>();
        if (tunnel.getType() == null || tunnel.getType() != 2) {
            if (oldRows != null) {
                for (ChainTunnel row : oldRows) {
                    if (row.getNodeId() != null) {
                        toClean.add(row.getNodeId());
                    }
                }
            }
        }
        toClean.addAll(newOnlyNodes);
        for (Long nodeId : toClean) {
            List<String> clean = new ArrayList<>();
            cleanTunnelResources(nodeId, tunnel.getId(), clean);
            failures.addAll(clean);
        }
        if (failures.isEmpty()) return null;
        if (problems != null) problems.addAll(failures);
        return "旧拓扑恢复未完成：" + String.join("，", failures);
    }

    private static Topology topologyOfRows(List<ChainTunnel> rows) {
        List<ChainTunnel> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(ChainTunnel::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        List<ChainTunnel> inNodes = new ArrayList<>();
        List<ChainTunnel> outNodes = new ArrayList<>();
        Map<Integer, List<ChainTunnel>> groups = new TreeMap<>();
        for (ChainTunnel row : sorted) {
            if (row.getChainType() == null) continue;
            if (row.getChainType() == 1) {
                inNodes.add(row);
            } else if (row.getChainType() == 2) {
                groups.computeIfAbsent(row.getInx() == null ? 0 : row.getInx(), key -> new ArrayList<>()).add(row);
            } else if (row.getChainType() == 3) {
                outNodes.add(row);
            }
        }
        return new Topology(inNodes, new ArrayList<>(groups.values()), outNodes);
    }

    private static final class Topology {
        private final List<ChainTunnel> inNodes;
        private final List<List<ChainTunnel>> chainGroups;
        private final List<ChainTunnel> outNodes;

        private Topology(List<ChainTunnel> inNodes, List<List<ChainTunnel>> chainGroups, List<ChainTunnel> outNodes) {
            this.inNodes = inNodes == null ? Collections.emptyList() : inNodes;
            this.chainGroups = chainGroups == null ? Collections.emptyList() : chainGroups;
            this.outNodes = outNodes == null ? Collections.emptyList() : outNodes;
        }
    }

    @Override
    public R deleteTunnel(Long id) {
        if (id == null) return R.err("隧道ID不能为空");
        Tunnel tunnel = this.getById(id);
        if (tunnel == null) return R.err("隧道不存在");
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", id));
        // 行集必须在落库前读全：purgeTunnelDatabaseRecords 会把 forward / chain_tunnel / speed_limit 一起删掉，
        // 删完再查只会拿到空集，节点上的资源就再也没人清了。
        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", id));
        List<SpeedLimit> speedLimits = speedLimitService.list(new QueryWrapper<SpeedLimit>().eq("tunnel_id", id));

        // 转发自带的服务按转发粒度命名，隧道级清理覆盖不到，必须先逐条删
        for (Forward forward : forwardList) {
            forwardService.deleteForward(forward.getId());
        }

        // 多表删除收在一个事务里，且不与任何节点下发混在一起：节点离线/超时会一直占着数据库连接
        cascadeDeletePurger.purgeTunnelDatabaseRecords(id,
                forwardList.stream().map(Forward::getId).collect(Collectors.toList()));

        // 真机缺陷（tunnel 5）：入口行(chainType=1)只删 chains，节点上的 <tunnelId>_tls 服务留在 gost.json 里，
        // 重启被 loader 重新拉起继续占端口。不再按 chainType 猜该节点有什么资源，
        // 而是对每个出现过的节点都尝试删 chains、service 与限流器：Delete* 已把 not found 归一成 OK，
        // 重复调用幂等，多删一次不存在的东西不算失败。
        List<String> leftovers = new ArrayList<>();
        Set<Long> cleanedNodes = new LinkedHashSet<>();
        for (ChainTunnel chainTunnel : chainTunnels) {
            // 同一节点多行只清一次，避免重复下发
            if (!cleanedNodes.add(chainTunnel.getNodeId())) continue;
            cleanTunnelResources(chainTunnel.getNodeId(), id, leftovers);
            cleanTunnelLimiters(chainTunnel.getNodeId(), speedLimits, leftovers);
        }
        if (!leftovers.isEmpty()) {
            // 记录已删、节点侧可能还留着资源：如实带出，由运维手工核查
            log.warn("隧道 {} 已删除，但节点资源清理未完成：{}", id, String.join("，", leftovers));
            return R.ok(leftovers);
        }
        return R.ok();
    }

    /**
     * 清掉某节点上属于该隧道可能的全部 gost 资源：service 依赖 chain，先删 service 再删 chain。
     * 成功（含 not found）不记；真失败（节点离线/超时等）写进 leftovers。
     */
    private void cleanTunnelResources(Long nodeId, Long tunnelId, List<String> leftovers) {
        GostDto serviceDto = deleteService(nodeId, tunnelId + "_tls");
        if (!isOk(serviceDto)) {
            leftovers.add("节点" + nodeId + " service " + tunnelId + "_tls(" + gostMsg(serviceDto) + ")");
        }
        GostDto chainDto = deleteChains(nodeId, "chains_" + tunnelId);
        if (!isOk(chainDto)) {
            leftovers.add("节点" + nodeId + " chain chains_" + tunnelId + "(" + gostMsg(chainDto) + ")");
        }
    }

    /**
     * 删掉该隧道在每个节点上的限流器（名字 = speed_limit.id）。
     *
     * <p>限流器只在 speed_limit 行存在时才有归属：行删了名字就再没人认领，
     * 节点上那条 limiter 会永久占着 gost 的内存与配置行。与 service/chain 同一口径尽力而为，
     * 单条失败只记录，不回滚已完成的删除。
     */
    private void cleanTunnelLimiters(Long nodeId, List<SpeedLimit> speedLimits, List<String> leftovers) {
        if (speedLimits == null) return;
        for (SpeedLimit speedLimit : speedLimits) {
            if (speedLimit == null || speedLimit.getId() == null) continue;
            GostDto limiterDto = deleteLimiters(nodeId, speedLimit.getId());
            if (!isOk(limiterDto)) {
                leftovers.add("节点" + nodeId + " limiter " + speedLimit.getId() + "(" + gostMsg(limiterDto) + ")");
            }
        }
    }


    @Override
    public R userTunnel() {
        List<Tunnel> tunnelEntities;
        Integer roleId = JwtUtil.getRoleIdFromToken();
        Integer userId = JwtUtil.getUserIdFromToken();
        if (Integer.valueOf(0).equals(roleId)) {
            tunnelEntities = this.list(new QueryWrapper<Tunnel>().eq("status", 1));
        } else {
            if (userId == null) {
                return R.ok(java.util.Collections.emptyList());
            }
            tunnelEntities = java.util.Collections.emptyList(); // 返回空列表
            List<UserTunnel> userTunnels = userTunnelMapper.selectList(
                    new QueryWrapper<UserTunnel>().eq("user_id", userId)
            );
            if (userTunnels != null && !userTunnels.isEmpty()) {
                List<Integer> tunnelIds = userTunnels.stream()
                        .map(UserTunnel::getTunnelId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (!tunnelIds.isEmpty()) {
                    tunnelEntities = this.list(new QueryWrapper<Tunnel>()
                            .in("id", tunnelIds)
                            .eq("status", 1));
                }
            }

        }
        return R.ok(tunnelEntities);
    }


    @Override
    public R diagnoseTunnel(Long tunnelId) {
        Tunnel tunnel = this.getById(tunnelId);
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        List<ChainTunnel> chainTunnels = chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnelId)
        );

        if (chainTunnels.isEmpty()) {
            return R.err("隧道配置不完整");
        }

        List<ChainTunnel> inNodes = chainTunnels.stream()
                .filter(ct -> Integer.valueOf(1).equals(ct.getChainType()))
                .toList();

        Map<Integer, List<ChainTunnel>> chainNodesMap = chainTunnels.stream()
                .filter(ct -> Integer.valueOf(2).equals(ct.getChainType()))
                .collect(Collectors.groupingBy(
                        ct -> ct.getInx() != null ? ct.getInx() : 0,
                        Collectors.toList()
                ));

        List<List<ChainTunnel>> chainNodesList = chainNodesMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();

        List<ChainTunnel> outNodes = chainTunnels.stream()
                .filter(ct -> Integer.valueOf(3).equals(ct.getChainType()))
                .toList();

        List<DiagnosisResult> results = new ArrayList<>();

        if (Integer.valueOf(1).equals(tunnel.getType())) {
            for (ChainTunnel inNode : inNodes) {
                Node node = nodeService.getById(inNode.getNodeId());
                if (node != null) {
                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                            node, "www.google.com", 443, "入口(" + node.getName() + ")->外网"
                    );
                    result.setFromChainType(1); // 入口
                    results.add(result);
                }
            }
        } else if (Integer.valueOf(2).equals(tunnel.getType())) {
            for (ChainTunnel inNode : inNodes) {
                Node fromNode = nodeService.getById(inNode.getNodeId());
                
                if (fromNode != null) {
                    if (!chainNodesList.isEmpty()) {
                        for (ChainTunnel firstChainNode : chainNodesList.getFirst()) {
                            Node toNode = nodeService.getById(firstChainNode.getNodeId());
                            if (toNode != null) {
                                DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                        fromNode, toNode.getServerIp(), firstChainNode.getPort(),
                                        "入口(" + fromNode.getName() + ")->第1跳(" + toNode.getName() + ")"
                                );
                                result.setFromChainType(1); // 入口
                                result.setToChainType(2); // 链
                                result.setToInx(firstChainNode.getInx());
                                results.add(result);
                            }
                        }
                    } else if (!outNodes.isEmpty()) {
                        for (ChainTunnel outNode : outNodes) {
                            Node toNode = nodeService.getById(outNode.getNodeId());
                            if (toNode != null) {
                                DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                        fromNode, toNode.getServerIp(), outNode.getPort(),
                                        "入口(" + fromNode.getName() + ")->出口(" + toNode.getName() + ")"
                                );
                                result.setFromChainType(1);
                                result.setToChainType(3);
                                results.add(result);
                            }
                        }
                    }
                }
            }

            for (int i = 0; i < chainNodesList.size(); i++) {
                List<ChainTunnel> currentHop = chainNodesList.get(i);
                
                for (ChainTunnel currentNode : currentHop) {
                    Node fromNode = nodeService.getById(currentNode.getNodeId());
                    
                    if (fromNode != null) {
                        if (i + 1 < chainNodesList.size()) {
                            for (ChainTunnel nextNode : chainNodesList.get(i + 1)) {
                                Node toNode = nodeService.getById(nextNode.getNodeId());
                                if (toNode != null) {
                                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                            fromNode, toNode.getServerIp(), nextNode.getPort(),
                                            "第" + (i + 1) + "跳(" + fromNode.getName() + ")->第" + (i + 2) + "跳(" + toNode.getName() + ")"
                                    );
                                    result.setFromChainType(2);
                                    result.setFromInx(currentNode.getInx());
                                    result.setToChainType(2);
                                    result.setToInx(nextNode.getInx());
                                    results.add(result);
                                }
                            }
                        } else if (!outNodes.isEmpty()) {
                            for (ChainTunnel outNode : outNodes) {
                                Node toNode = nodeService.getById(outNode.getNodeId());
                                if (toNode != null) {
                                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                            fromNode, toNode.getServerIp(), outNode.getPort(),
                                            "第" + (i + 1) + "跳(" + fromNode.getName() + ")->出口(" + toNode.getName() + ")"
                                    );
                                    result.setFromChainType(2);
                                    result.setFromInx(currentNode.getInx());
                                    result.setToChainType(3);
                                    results.add(result);
                                }
                            }
                        }
                    }
                }
            }
            for (ChainTunnel outNode : outNodes) {
                Node node = nodeService.getById(outNode.getNodeId());
                if (node != null) {
                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                            node, "www.google.com", 443, "出口(" + node.getName() + ")->外网"
                    );
                    result.setFromChainType(3);
                    results.add(result);
                }
            }
        }

        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("tunnelId", tunnelId);
        diagnosisReport.put("tunnelName", tunnel.getName());
        diagnosisReport.put("tunnelType", Integer.valueOf(2).equals(tunnel.getType()) ? "隧道转发" : "端口转发");
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    public Integer getNodePort(Long nodeId) {
        return getNodePort(nodeId, null);
    }

    /**
     * @param excludeTunnelId 计算时忽略该隧道的既有 chain 行：拓扑变更时本隧道旧行还占着端口，
     *                        不排除就会把“自己正在用的端口”当成别人占用，自动分配莫名跳段。
     */
    private Integer getNodePort(Long nodeId, Long excludeTunnelId) {

        Node node = nodeService.getById(nodeId);
        if (node == null){
            throw new RuntimeException("节点不存在");
        }

        // 1. 查询隧道转发链占用的端口
        List<ChainTunnel> chainTunnels = chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().eq("node_id", nodeId)
        );
        if (excludeTunnelId != null) {
            chainTunnels = chainTunnels.stream()
                    .filter(row -> !Objects.equals(row.getTunnelId(), excludeTunnelId))
                    .collect(Collectors.toList());
        }
        Set<Integer> usedPorts = chainTunnels.stream()
                .map(ChainTunnel::getPort)
                .filter(Objects::nonNull)
                .filter(p -> p >= 1 && p <= 65535)
                .collect(Collectors.toSet());

        List<ForwardPort> list = forwardPortService.list(new QueryWrapper<ForwardPort>().eq("node_id", nodeId));
        Set<Integer> forwardUsedPorts = new HashSet<>();
        for (ForwardPort forwardPort : list) {
            if (forwardPort.getPort() != null && forwardPort.getPort() >= 1 && forwardPort.getPort() <= 65535) {
                forwardUsedPorts.add(forwardPort.getPort());
            }
        }
        usedPorts.addAll(forwardUsedPorts);

        // 3. 从可用端口范围中筛选未被占用的端口
        List<Integer> parsedPorts = parsePorts(node.getPort());
        List<Integer> availablePorts = parsedPorts.stream()
                .filter(p -> !usedPorts.contains(p))
                .toList();

        if (availablePorts.isEmpty()) {
            throw new RuntimeException("节点端口已满，无可用端口");
        }
        return availablePorts.getFirst();
    }

    /**
     * 转发链/出口节点的监听端口：显式传入就校验后采用，没传才走自动分配。
     * 真机缺陷：chainNodes 里显式写 port=30202 被 getNodePort 静默换成 30203，调用方无从得知。
     * 显式端口不可用时返回 R.err，让调用方看到原因，不再静默改端口。
     */
    private R resolveChainPort(ChainTunnel chainTunnel, Node node) {
        return resolveChainPort(chainTunnel, node, null);
    }

    /**
     * @param autoPortRows 自动分配到端口的行收集到这里；这些行下发命中节点端口占用时允许换口重试，
     *                     显式指定端口的行一律不重试
     */
    private R resolveChainPort(ChainTunnel chainTunnel, Node node, Set<ChainTunnel> autoPortRows) {
        return resolveChainPort(chainTunnel, node, autoPortRows, null);
    }

    /**
     * @param excludeTunnelId 拓扑变更时传本隧道 id：本隧道的旧 chain 行还没删，不能算作“被别人占用”
     */
    private R resolveChainPort(ChainTunnel chainTunnel, Node node, Set<ChainTunnel> autoPortRows, Long excludeTunnelId) {
        Integer requested = chainTunnel.getPort();
        if (requested == null) {
            chainTunnel.setPort(getNodePort(node.getId(), excludeTunnelId));
            if (autoPortRows != null) {
                autoPortRows.add(chainTunnel);
            }
            return null;
        }
        if (!parsePorts(node.getPort()).contains(requested)) {
            return R.err("节点[" + node.getName() + "]端口 " + requested + " 不在允许范围 " + node.getPort());
        }
        if (portOccupied(node.getId(), requested, excludeTunnelId)) {
            return R.err("节点[" + node.getName() + "]端口 " + requested
                    + (excludeTunnelId == null ? " 已被隧道或转发占用" : " 已被其它隧道或转发占用"));
        }
        return null;
    }

    private boolean portOccupied(Long nodeId, Integer port) {
        return portOccupied(nodeId, port, null);
    }

    private boolean portOccupied(Long nodeId, Integer port, Long excludeTunnelId) {
        QueryWrapper<ChainTunnel> chainQuery = new QueryWrapper<ChainTunnel>().eq("node_id", nodeId).eq("port", port);
        if (excludeTunnelId != null) {
            chainQuery.ne("tunnel_id", excludeTunnelId);
        }
        boolean usedByChainTunnel = !chainTunnelService.list(chainQuery).isEmpty();
        if (usedByChainTunnel) {
            return true;
        }
        return !forwardPortService.list(
                new QueryWrapper<ForwardPort>().eq("node_id", nodeId).eq("port", port)
        ).isEmpty();
    }

    /**
     * 解析节点可用端口段（{@code 30000-30010,30020}）。
     *
     * <p>只接受 1..65535：越界、非数字、区间端点缺失的片段一律丢弃，不抛异常。
     * 脏值一旦落进 {@code chain_tunnel.port} 就会永久占住端口段（见 ForwardServiceImpl.getNodePort），
     * 还会让节点侧跳点摘要永远对不上、每轮判漂移删链重建（见 ForwardServiceImpl.expectedChainNodes），
     * 所以必须在解析入口就掐掉，而不是留给下游各点各自兜。
     *
     * <p>区间写反（{@code 5-3}）按 3..5 归一；重复端口去重、结果升序。
     *
     * @return 升序列表；输入为 null、空白或全脏时返回空列表
     */
    public static List<Integer> parsePorts(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Set<Integer> set = new HashSet<>();
        for (String part : input.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            int dash = part.indexOf('-');
            if (dash < 0) {
                Integer single = parsePortOrNull(part);
                if (single != null) {
                    set.add(single);
                }
                continue;
            }
            Integer start = parsePortOrNull(part.substring(0, dash));
            Integer end = parsePortOrNull(part.substring(dash + 1));
            if (start == null || end == null) {
                continue; // 任一端非法就整段丢弃：端点脏掉，展开出来的整段都不可信
            }
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            for (int port = from; port <= to; port++) {
                set.add(port);
            }
        }
        return set.stream().sorted().collect(Collectors.toList());
    }

    /** 单个端口字面量：非数字或不在 1..65535 返回 null，由调用方丢弃。 */
    private static Integer parsePortOrNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty() || value.length() > 5) {
            return null; // 6 位以上不可能是合法端口，也顺带规避 parseInt 溢出
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return null;
            }
        }
        int port = Integer.parseInt(value);
        return port >= 1 && port <= 65535 ? port : null;
    }

    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 构建TCP ping请求数据
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 4);
            tcpPingData.put("timeout", 5000); // 5秒超时

            // 发送TCP ping命令到节点
            GostDto gostResult = WebSocketServer.send_msg(node.getId(), tcpPingData, "TcpPing");

            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setTimestamp(System.currentTimeMillis());

            if (gostResult != null && "OK".equals(gostResult.getMsg())) {
                // 尝试解析TCP ping响应数据
                try {
                    if (gostResult.getData() != null) {
                        JSONObject tcpPingResponse = (JSONObject) gostResult.getData();
                        boolean success = tcpPingResponse.getBooleanValue("success");

                        result.setSuccess(success);
                        if (success) {
                            result.setMessage("TCP连接成功");
                            result.setAverageTime(tcpPingResponse.getDoubleValue("averageTime"));
                            result.setPacketLoss(tcpPingResponse.getDoubleValue("packetLoss"));
                        } else {
                            result.setMessage(tcpPingResponse.getString("errorMessage"));
                            result.setAverageTime(-1.0);
                            result.setPacketLoss(100.0);
                        }
                    } else {
                        // 没有详细数据，使用默认值
                        result.setSuccess(true);
                        result.setMessage("TCP连接成功");
                        result.setAverageTime(0.0);
                        result.setPacketLoss(0.0);
                    }
                } catch (Exception e) {
                    // 解析响应数据失败，但TCP ping命令本身成功了
                    result.setSuccess(true);
                    result.setMessage("TCP连接成功，但无法解析详细数据");
                    result.setAverageTime(0.0);
                    result.setPacketLoss(0.0);
                }
            } else {
                result.setSuccess(false);
                result.setMessage(gostResult != null ? gostResult.getMsg() : "节点无响应");
                result.setAverageTime(-1.0);
                result.setPacketLoss(100.0);
            }

            return result;
        } catch (Exception e) {
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setSuccess(false);
            result.setMessage("诊断执行异常: " + e.getMessage());
            result.setTimestamp(System.currentTimeMillis());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    private DiagnosisResult performTcpPingDiagnosisWithConnectionCheck(Node node, String targetIp, Integer port, String description) {
        DiagnosisResult result = new DiagnosisResult();
        result.setNodeId(node != null ? node.getId() : null);
        result.setNodeName(node != null ? node.getName() : null);
        result.setTargetIp(targetIp);
        result.setTargetPort(port != null ? port : 0);
        result.setDescription(description);
        result.setTimestamp(System.currentTimeMillis());

        if (node == null) {
            result.setSuccess(false);
            result.setMessage("节点不存在");
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
        if (targetIp == null || targetIp.trim().isEmpty()) {
            result.setSuccess(false);
            result.setMessage("目标IP未配置或无效");
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
        if (port == null || port <= 0 || port > 65535) {
            result.setSuccess(false);
            result.setMessage("目标端口未配置或无效");
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }

        try {
            return performTcpPingDiagnosis(node, targetIp, port, description);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("连接检查异常: " + e.getMessage());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }


}
