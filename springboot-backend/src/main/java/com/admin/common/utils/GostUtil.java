package com.admin.common.utils;

import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.entity.*;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class GostUtil {


    public static GostDto AddLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "AddLimiters");
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    /**
     * 仅供补齐流程使用：原样返回节点响应，exists 只说明同名限流器已存在，
     * 不能证明它的速率与库里一致，因此调用方必须再走一次 UpdateLimiters 校准。
     */
    public static GostDto CreateLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        return WebSocketServer.send_msg(node_id, data, "AddLimiters");
    }

    public static GostDto UpdateLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        req.put("data", data);
        return WebSocketServer.send_msg(node_id, req, "UpdateLimiters");
    }

    public static GostDto DeleteLimiters(Long node_id, Long name) {
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        GostDto gostDto = WebSocketServer.send_msg(node_id, req, "DeleteLimiters");
        if (gostDto.getMsg().contains("not found")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto AddChains(Long node_id, List<ChainTunnel> chainTunnels, Map<Long, Node> node_s) {
        GostDto gostDto = CreateChains(node_id, chainTunnels, node_s);
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    /**
     * 仅供创建流程使用：原样返回节点响应。exists 只说明同名资源已存在，
     * 不能证明其配置与本次下发一致，所以不归一成 OK。
     *
     * <p>本重载拿不到 tunnel，判定不了类型，因此不做 Fail-Closed：新调用方一律走
     * {@link #CreateChains(Long, List, Map, Tunnel)}。
     */
    public static GostDto CreateChains(Long node_id, List<ChainTunnel> chainTunnels, Map<Long, Node> node_s) {
        return createChainsPayload(node_id, chainTunnels, node_s);
    }

    /**
     * 带隧道类型的下发口径：中继链 chains_&lt;tid&gt; 的存在意义同样由 tunnel.type 定，
     * 类型未知（NULL / 9）时与 {@link #buildForwardService} 同一口径 Fail-Closed。
     */
    public static GostDto CreateChains(Long node_id, List<ChainTunnel> chainTunnels, Map<Long, Node> node_s,
                                      Tunnel tunnel) {
        requireKnownTunnelType(tunnel, "中继链 " + chainName(tunnel, chainTunnels));
        return createChainsPayload(node_id, chainTunnels, node_s);
    }

    private static GostDto createChainsPayload(Long node_id, List<ChainTunnel> chainTunnels, Map<Long, Node> node_s) {
        JSONArray nodes = new JSONArray();
        for (ChainTunnel chainTunnel : chainTunnels) {
            JSONObject dialer = new JSONObject();
            dialer.put("type", chainTunnel.getProtocol());

            JSONObject connector = new JSONObject();
            connector.put("type", "relay");

            Node node_info = node_s.get(chainTunnel.getNodeId());
            JSONObject node = new JSONObject();
            // 出口行没有 inx（只有 chainNodes 才按组序编号），用 inx 会拼出 node_null；
            // nodeId 在一条链里唯一，既能定位也不会变
            node.put("name", "node_" + chainTunnel.getNodeId());
            node.put("addr", processServerAddress(node_info.getServerIp() + ":" + chainTunnel.getPort()));
            node.put("connector", connector);
            node.put("dialer", dialer);



            nodes.add(node);
        }
        JSONObject hop = new JSONObject();
        hop.put("name", "hop_" + chainTunnels.getFirst().getTunnelId());

        // interface设置在转发链
        if (StringUtils.isNotBlank(node_s.get(node_id).getInterfaceName())) {
            hop.put("interface", node_s.get(node_id).getInterfaceName());
        }


        JSONObject selector = new JSONObject();
        selector.put("strategy", chainTunnels.getFirst().getStrategy());
        selector.put("maxFails", 1);
        selector.put("failTimeout", 600000000000L); // 600 秒（纳秒单位）


        hop.put("selector", selector);
        hop.put("nodes", nodes);

        JSONArray hops = new JSONArray();
        hops.add(hop);

        JSONObject data = new JSONObject();
        data.put("name", "chains_" + chainTunnels.getFirst().getTunnelId());
        data.put("hops", hops);

        return WebSocketServer.send_msg(node_id, data, "AddChains");
    }

    public static GostDto DeleteChains(Long node_id, String name) {
        JSONObject data = new JSONObject();
        data.put("chain", name);
        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "DeleteChains");
        if (gostDto.getMsg().contains("not found")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto AddChainService(Long node_id, ChainTunnel chainTunnel, Map<Long, Node> node_s) {
        GostDto gostDto = CreateChainService(node_id, chainTunnel, node_s);
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    /**
     * 仅供创建流程使用：原样返回节点响应，exists 冲突不归一成 OK。
     *
     * <p>本重载拿不到 tunnel，判定不了类型，因此不做 Fail-Closed：新调用方一律走
     * {@link #CreateChainService(Long, ChainTunnel, Map, Tunnel)}。
     */
    public static GostDto CreateChainService(Long node_id, ChainTunnel chainTunnel, Map<Long, Node> node_s) {
        return createChainServicePayload(node_id, chainTunnel, node_s);
    }

    /**
     * 带隧道类型的中继服务下发口径：&lt;tid&gt;_tls 与 chains_&lt;tid&gt; 成对存在，
     * 类型未知（NULL / 9）时与入口服务同一口径 Fail-Closed。
     */
    public static GostDto CreateChainService(Long node_id, ChainTunnel chainTunnel, Map<Long, Node> node_s,
                                             Tunnel tunnel) {
        requireKnownTunnelType(tunnel, "中继服务 " + chainServiceName(tunnel, chainTunnel));
        return createChainServicePayload(node_id, chainTunnel, node_s);
    }

    private static GostDto createChainServicePayload(Long node_id, ChainTunnel chainTunnel, Map<Long, Node> node_s) {
        JSONArray services = new JSONArray();
        Node node_info = node_s.get(chainTunnel.getNodeId());
        JSONObject service_item = new JSONObject();
        service_item.put("name", chainTunnel.getTunnelId() + "_tls");
        // 与入口服务同一口径：库里可能存裸 IPv6（"::"），裸拼会得到 ":::30202" 这种非法 addr
        service_item.put("addr", canonicalListenAddr(node_info.getTcpListenAddr()) + ":" + chainTunnel.getPort());
        
        // 只为出口节点(chainType=3)设置 interface
        if (chainTunnel.getChainType() == 3 && StringUtils.isNotBlank(node_s.get(node_id).getInterfaceName())) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", node_s.get(node_id).getInterfaceName());
            service_item.put("metadata", metadata);
        }

        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        if (chainTunnel.getChainType() == 2){
            handler.put("chain","chains_" + chainTunnel.getTunnelId());
        }
        service_item.put("handler", handler);

        JSONObject listener = new JSONObject();
        listener.put("type", chainTunnel.getProtocol());
        service_item.put("listener", listener);

        services.add(service_item);

        return WebSocketServer.send_msg(node_id, services, "AddService");
    }

    /**
     * Fail-Closed：中继链 chains_&lt;tid&gt; 与 &lt;tid&gt;_tls 中继服务**只属于中继隧道（type=2）**。
     *
     * <p>拿不到 tunnel、type 为 NULL 或任何其它值，都判定不了这条资源该不该存在；
     * 而直连隧道（type=1）上出现链资源同样非法——它没有下一跳，挂上去只会是一条死链
     * 外加一个白占端口的监听。猜错任何一种都会把半套配置留在节点上，只能拒发让上层记录
     * （round45 QA P0-2：守卫必须严格等值 type==2，不能写成「非 1/2」）。
     */
    private static void requireKnownTunnelType(Tunnel tunnel, String resourceName) {
        Integer tunnelType = tunnel == null ? null : tunnel.getType();
        if (!Integer.valueOf(2).equals(tunnelType)) {
            String reason = Integer.valueOf(1).equals(tunnelType)
                    ? "直连隧道没有下一跳"
                    : "无法判定直连还是中继";
            throw new IllegalStateException("隧道类型不合法（tunnel.type=" + tunnelType
                    + "），已拒绝下发" + resourceName + "：" + reason);
        }
    }

    /** 报错文案里的链名：优先取 tunnel.id，拿不到 tunnel 时退回链行的 tunnelId。 */
    private static String chainName(Tunnel tunnel, List<ChainTunnel> chainTunnels) {
        Long tunnelId = tunnel == null ? null : tunnel.getId();
        if (tunnelId == null && chainTunnels != null && !chainTunnels.isEmpty()) {
            tunnelId = chainTunnels.getFirst().getTunnelId();
        }
        return "chains_" + tunnelId;
    }

    /** 报错文案里的服务名：口径与 {@link #CreateChainService} 下发的 name 一致。 */
    private static String chainServiceName(Tunnel tunnel, ChainTunnel chainTunnel) {
        Long tunnelId = tunnel == null ? null : tunnel.getId();
        if (tunnelId == null && chainTunnel != null) {
            tunnelId = chainTunnel.getTunnelId();
        }
        return tunnelId + "_tls";
    }

    public static GostDto AddAndUpdateService(String name, Integer limiter, Node node, Forward forward, ForwardPort forwardPort, Tunnel tunnel, String meth) {
        GostDto gostDto = WebSocketServer.send_msg(node.getId(), buildForwardServices(name, limiter, node, forward, forwardPort, tunnel), meth);
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    /**
     * 仅供创建流程使用：原样返回节点响应。exists 只说明同名服务早已存在，
     * 不能证明其配置与本次下发一致，也不能算作本次创建成功。
     */
    public static GostDto CreateForwardService(String name, Integer limiter, Node node, Forward forward, ForwardPort forwardPort, Tunnel tunnel) {
        return WebSocketServer.send_msg(node.getId(), buildForwardServices(name, limiter, node, forward, forwardPort, tunnel), "AddService");
    }

    /**
     * 只下发单个协议的服务。用于节点上只剩 tcp 或只剩 udp 的半残状态：tcp 与 udp
     * 的端口空间彼此独立，补缺失的那一半不会顶掉已有的监听，也不会和已有服务抢端口。
     */
    public static GostDto CreateSingleForwardService(String name, Integer limiter, Node node, Forward forward,
                                                     ForwardPort forwardPort, Tunnel tunnel, String protocol) {
        JSONArray services = new JSONArray();
        services.add(buildForwardService(name, limiter, node, forward, forwardPort, tunnel, protocol));
        return WebSocketServer.send_msg(node.getId(), services, "AddService");
    }

    private static JSONArray buildForwardServices(String name, Integer limiter, Node node, Forward forward, ForwardPort forwardPort, Tunnel tunnel) {
        JSONArray services = new JSONArray();
        String[] protocols = {"tcp", "udp"};
        for (String protocol : protocols) {
            services.add(buildForwardService(name, limiter, node, forward, forwardPort, tunnel, protocol));
        }
        return services;
    }

    private static JSONObject buildForwardService(String name, Integer limiter, Node node, Forward forward,
                                                 ForwardPort forwardPort, Tunnel tunnel, String protocol) {
        // Fail-Closed：tunnel.type 决定要不要挂 chains_<tid>。历史行 type 可能为 NULL，
        // 若当成直连下发，本该走中继加密的流量会在入口节点直接明文连目标（round44 QA 裁决）。
        // 这类数据只能拦下来报错，不能猜，也不能静默降级。
        Integer tunnelType = tunnel == null ? null : tunnel.getType();
        if (!Integer.valueOf(1).equals(tunnelType) && !Integer.valueOf(2).equals(tunnelType)) {
            throw new IllegalStateException("隧道类型不合法（tunnel.type=" + tunnelType
                    + "），已拒绝下发转发服务 " + name + "_" + protocol + "：无法判定直连还是中继，按中继兜底会 bind 失败、按直连下发会明文穿透");
        }
        JSONObject service = new JSONObject();
        service.put("name", name + "_" + protocol);
        String listenHost = Objects.equals(protocol, "tcp") ? node.getTcpListenAddr() : node.getUdpListenAddr();
        service.put("addr", canonicalListenAddr(listenHost) + ":" + forwardPort.getPort());

        // 只在端口转发时设置 interface（隧道转发时 interface 在转发链的节点上设置）
        // 用 equals 而不是 ==：tunnel.type 是 Integer，历史行可能为 NULL，
        // 直接拆箱会 NPE 把整条转发服务下发打挂（round44 scout 实测）。
        if (Integer.valueOf(1).equals(tunnelType) && StringUtils.isNotBlank(node.getInterfaceName())) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", node.getInterfaceName());
            service.put("metadata", metadata);
        }

        // 添加限流器配置
        if (limiter != null) {
            service.put("limiter", limiter.toString());
        }

        // 配置处理器
        JSONObject handler = new JSONObject();
        handler.put("type", protocol);
        if (Integer.valueOf(2).equals(tunnelType)) {
            handler.put("chain", "chains_" + forward.getTunnelId());
        }
        service.put("handler", handler);

        // 配置监听器
        JSONObject listener = createListener(protocol);
        service.put("listener", listener);

        JSONObject forwarder = createForwarder(forward.getRemoteAddr(), forward.getStrategy());
        service.put("forwarder", forwarder);
        return service;
    }

    /**
     * 老的四参版本：签名与输出逐字不变（target 为 null 就是 "-"），内部委托多目标口径。
     *
     * <p>对历史输入（纯端口、通配地址）结果与扩展前完全一致；具体监听地址与多目标两个扩展
     * 见 {@link #normalizeServiceDigestList}。拼接规则只此一处，禁止各调用方自己拼。
     */
    public static String normalizeServiceDigest(String addrOrPort, String handler, String chain, String target) {
        return normalizeServiceDigestList(addrOrPort, handler, chain,
                target == null ? null : java.util.Collections.singletonList(target));
    }

    /**
     * 摘要的完整口径：{@code listen|handler|chain|t1,t2,...}。
     *
     * <p>与老的四参版本相比只有两处扩展，且对老输入逐字等价：
     * <ul>
     *   <li>listen 不只取端口：库/节点给了具体监听地址（127.0.0.1、[fe80::1]）时必须带 host，
     *       否则把 tcp_listen_addr 从 0.0.0.0 改成 127.0.0.1 这种漂移永远发现不了。
     *       通配地址（空 / 0.0.0.0 / :: / *）仍只输出端口，保证老数据、老节点零抖动。</li>
     *   <li>targets 是全部 forwarder 目标（按顺序），不再只取第一条：多目标少一条/多一条/换顺序
     *       在节点上都是另一套负载均衡行为。</li>
     * </ul>
     *
     * <p>拼接规则只此一处，节点上报与后端按库计算都走这里。
     */
    public static String normalizeServiceDigestList(String addrOrPort, String handler, String chain,
                                                    List<String> targets) {
        return digestListen(addrOrPort) + "|" + digestText(handler).toLowerCase(Locale.ROOT)
                + "|" + digestText(chain) + "|" + digestTargets(targets);
    }

    /**
     * 把库里的监听 host 补成能直接拼 {@code host:port} 的形式：
     * 空白或未配置时按通配 0.0.0.0；IPv6（含冒号）补方括号，已经是 {@code [..]} 的原样返回。
     *
     * <p>库里保留运维输入原样（{"::"} 仍是 {"::"}），只有下发与摘要这两处出口做规范化，
     * 避免"库值被静默改写"与"节点实际 addr 与库对不上"两种口径分叉。
     */
    public static String canonicalListenAddr(String listenAddr) {
        String value = StringUtils.trimToEmpty(listenAddr);
        if (value.isEmpty()) {
            return "0.0.0.0";
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            return value;
        }
        return value.indexOf(':') >= 0 ? "[" + value + "]" : value;
    }

    /** 摘要里的监听段：具体地址带 host，通配只留端口（老格式）。 */
    private static String digestListen(String addrOrPort) {
        String value = StringUtils.trimToEmpty(addrOrPort);
        int lastColon = value.lastIndexOf(':');
        if (lastColon < 0) {
            return digestText(value);
        }
        String host = value.substring(0, lastColon).trim();
        String port = value.substring(lastColon + 1).trim();
        if (port.isEmpty()) {
            // 没有端口的 addr 不是可下发的监听地址；老口径这里同样写 "-"，两边必须一致
            return "-";
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        // 通配形式的等价写法必须全部折叠：gost 可能回报未压缩的 IPv6 全零（0:0:0:0:0:0:0:0 / ::0），
        // 漏一种就会与库里的 "::" 永久判漂移，形成删建风暴（QA round37 P1）
        if (host.isEmpty() || "0.0.0.0".equals(host) || "*".equals(host)
                || isWildcardV6(host)) {
            return port;
        }
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    /** IPv6 通配（含未压缩全零与 "::0" 这类写法）。 */
    private static boolean isWildcardV6(String host) {
        return "0:0:0:0:0:0:0:0".equals(host) || "::0".equals(host) || "::".equals(host)
                || "0:0:0:0:0:0:0:0:0".equals(host);
    }

    /**
     * 摘要里的目标段：逐个 trim、丢空、按顺序逗号连接；全空写 "-"。
     *
     * <p>目标里的 host 一律小写：DNS 域名大小写不敏感（RFC 4343），库填 {@code Example.COM:8080}
     * 而节点回报 {@code example.com:8080} 是同一条配置。不归一就会被判成永久漂移，
     * 每次心跳都删建一次服务（QA round37 P0）。
     */
    private static String digestTargets(List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (String target : targets) {
            String value = StringUtils.trimToEmpty(target).toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    /**
     * 摘要里的端口：addr 形如 "[::]:30200" / "0.0.0.0:30200" 时取最后一段，
     * 直接给了端口号（"30200"）时原样返回。
     */
    private static String digestPort(String addrOrPort) {
        String value = StringUtils.trimToEmpty(addrOrPort);
        int lastColon = value.lastIndexOf(':');
        if (lastColon >= 0) {
            value = value.substring(lastColon + 1);
        }
        return digestText(value);
    }

    private static String digestText(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        return trimmed.isEmpty() ? "-" : trimmed;
    }

    public static GostDto DeleteService(Long node_id, JSONArray services) {
        JSONObject data = new JSONObject();
        data.put("services", services);
        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "DeleteService");
        if (gostDto.getMsg().contains("not found")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    /** 节点返回 not found：目标服务本来就不在节点上（配置被清空/被改名），对「补发暂停」属正常情况。 */
    public static boolean notFound(String msg) {
        return msg != null && msg.contains("not found");
    }

    public static GostDto PauseAndResumeService(Long node_id, String name, String meth) {
        List<String> services = new ArrayList<>();
        services.add(name + "_tcp");
        services.add(name + "_udp");
        return PauseAndResumeServices(node_id, services, meth);
    }

    /**
     * 只对指定服务名补暂停/恢复。
     *
     * <p>不能一律按「基名 + _tcp/_udp」凑两个名字：节点 pauseServices 第一阶段逐个校验，
     * 只要有一个名字既没注册、配置里也没有暂停标记就整批失败（go-gost/x/socket/service.go:273-281），
     * 半残（只剩单协议）的服务会因此连着运行中的那一半也停不下来。
     */
    public static GostDto PauseAndResumeServices(Long node_id, List<String> services, String meth) {
        JSONObject data = new JSONObject();
        JSONArray array = new JSONArray();
        array.addAll(services);
        data.put("services", array);
        return WebSocketServer.send_msg(node_id, data, meth);
    }



    private static JSONObject createLimiterData(Long name, String speed) {
        JSONObject data = new JSONObject();
        data.put("name", name.toString());
        JSONArray limits = new JSONArray();
        limits.add("$ " + speed + "MB " + speed + "MB");
        data.put("limits", limits);
        return data;
    }

    private static JSONObject createListener(String protocol) {
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        if (Objects.equals(protocol, "udp")) {
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            listener.put("metadata", metadata);
        }
        return listener;
    }

    private static JSONObject createForwarder(String remoteAddr, String strategy) {
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            String trimmed = addr == null ? "" : addr.trim();
            if (trimmed.isEmpty()) {
                continue; // 逗号后面多打的空格/多留的逗号不该下发成空目标
            }
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num);
            node.put("addr", trimmed);
            nodes.add(node);
            num++;
        }

        if (nodes.isEmpty()) {
            // 全是空白时按原样兜一个：空 nodes 会被 gost 直接拒掉，整条服务都下发不出去
            JSONObject node = new JSONObject();
            node.put("name", "node_1");
            node.put("addr", remoteAddr);
            nodes.add(node);
        }

        if (strategy == null || strategy.isEmpty()) {
            strategy = "fifo";
        }

        forwarder.put("nodes", nodes);

        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);
        return forwarder;
    }

    public static String processServerAddress(String serverAddr) {
        if (StrUtil.isBlank(serverAddr)) {
            return serverAddr;
        }

        // 如果已经被方括号包裹，直接返回
        if (serverAddr.startsWith("[")) {
            return serverAddr;
        }

        // 查找最后一个冒号，分离主机和端口
        int lastColonIndex = serverAddr.lastIndexOf(':');
        if (lastColonIndex == -1) {
            // 没有端口号，直接检查是否需要包裹
            return isIPv6Address(serverAddr) ? "[" + serverAddr + "]" : serverAddr;
        }

        String host = serverAddr.substring(0, lastColonIndex);
        String port = serverAddr.substring(lastColonIndex);

        // 检查主机部分是否为IPv6地址
        if (isIPv6Address(host)) {
            return "[" + host + "]" + port;
        }

        return serverAddr;
    }

    private static boolean isIPv6Address(String address) {
        // IPv6地址包含多个冒号，至少2个
        if (!address.contains(":")) {
            return false;
        }

        // 计算冒号数量，IPv6地址至少有2个冒号
        long colonCount = address.chars().filter(ch -> ch == ':').count();
        return colonCount >= 2;
    }
}
