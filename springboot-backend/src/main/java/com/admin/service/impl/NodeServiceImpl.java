package com.admin.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.service.*;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Service
public class NodeServiceImpl extends ServiceImpl<NodeMapper, Node> implements NodeService {

    /** 发布包基址默认值：与原硬编码保持一致，未配置时不改变上游行为。 */
    static final String DEFAULT_RELEASE_BASE_URL = "https://github.com/bqlpfy/flux-panel/releases/download";
    /** 发布包版本默认值：与原硬编码保持一致。 */
    static final String DEFAULT_RELEASE_VERSION = "2.0.7-beta";
    static final String RELEASE_BASE_URL_PROPERTY = "flux.panel.release.baseUrl";
    static final String RELEASE_BASE_URL_ENV = "FLUX_RELEASE_BASE_URL";
    static final String RELEASE_VERSION_PROPERTY = "flux.panel.release.version";
    static final String RELEASE_VERSION_ENV = "FLUX_RELEASE_VERSION";
    /**
     * 基址白名单：只放行 http/https 链接，字符集限定在 URL 常见安全字符内，
     * 空格、单双引号、反引号、$、;、|、&、重定向符、括号、反斜杠等 shell 元字符一律不匹配。
     */
    private static final Pattern RELEASE_BASE_URL_PATTERN =
            Pattern.compile("^https?://[A-Za-z0-9._~:/@%+=,-]+$");
    /** 版本号白名单：只允许 [A-Za-z0-9._-]，杜绝把任意串拼进路径。 */
    private static final Pattern RELEASE_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    /** 服务器地址白名单：只允许字母、数字、点、冒号、方括号、短横线、下划线，杜绝 shell 元字符注入。 */
    private static final Pattern SERVER_ADDR_PATTERN = Pattern.compile("^[a-zA-Z0-9._:\\[\\]-]+$");
    /** 节点密钥白名单：只允许字母、数字、下划线、短横线。 */
    private static final Pattern SECRET_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");


    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    ViteConfigService viteConfigService;

    @Resource
    ChainTunnelService chainTunnelService;

    @Resource
    ForwardPortService forwardPortService;


    @Override
    public R createNode(NodeDto nodeDto) {
        if (nodeDto == null) {
            return R.err("节点参数不能为空");
        }
        if (StrUtil.isBlank(nodeDto.getName())) {
            return R.err("节点名称不能为空");
        }
        if (StrUtil.isBlank(nodeDto.getServerIp())) {
            return R.err("服务器ip不能为空");
        }
        try {
            validatePortRange(nodeDto.getPort());
        } catch (Exception e) {
            return R.err(e.getMessage());
        }
        String listenAddrError = validateListenAddr(nodeDto.getTcpListenAddr(), "tcpListenAddr");
        if (listenAddrError == null) {
            listenAddrError = validateListenAddr(nodeDto.getUdpListenAddr(), "udpListenAddr");
        }
        if (listenAddrError != null) {
            return R.err(listenAddrError);
        }
        Node node = new Node();
        node.setSecret(IdUtil.simpleUUID());
        node.setStatus(0);
        node.setPort(nodeDto.getPort());
        node.setName(nodeDto.getName());
        node.setServerIp(nodeDto.getServerIp());
        long currentTime = System.currentTimeMillis();
        node.setCreatedTime(currentTime);
        node.setUpdatedTime(currentTime);
        node.setInterfaceName(nodeDto.getInterfaceName());
        // 未显式传监听地址时必须显式落 0.0.0.0：之前这里不赋值，字段为 null，
        // MyBatis-Plus 默认 NOT_NULL 插入策略会跳过该列，MySQL 便套用了
        // schema.sql:41-42 的列默认值 '[::]'。
        node.setTcpListenAddr(normalizeListenAddr(nodeDto.getTcpListenAddr(), "0.0.0.0"));
        node.setUdpListenAddr(normalizeListenAddr(nodeDto.getUdpListenAddr(), "0.0.0.0"));
        this.save(node);
        return R.ok();
    }

    @Override
    public R getAllNodes() {
        List<Node> nodeList = this.list(new QueryWrapper<Node>().orderByDesc("status"));
        nodeList.forEach(node -> node.setSecret(null));
        return R.ok(nodeList);
    }

    @Override
    public R updateNode(NodeUpdateDto nodeUpdateDto) {
        if (nodeUpdateDto == null || nodeUpdateDto.getId() == null) {
            return R.err("节点ID不能为空");
        }
        Node node = this.getById(nodeUpdateDto.getId());
        if (node == null) {
            return R.err("节点不存在");
        }

        String portRangeError = checkPortRangeCoversAllocated(node, nodeUpdateDto.getPort());
        if (portRangeError != null) {
            return R.err(portRangeError);
        }

        // 监听地址写错的后果是"节点起不来/绑到别的地址，库与节点摘要永远对不上"，
        // 必须在落库前挡住，不能等节点报错再回滚。
        String listenAddrError = validateListenAddr(nodeUpdateDto.getTcpListenAddr(), "tcpListenAddr");
        if (listenAddrError == null) {
            listenAddrError = validateListenAddr(nodeUpdateDto.getUdpListenAddr(), "udpListenAddr");
        }
        if (listenAddrError != null) {
            return R.err(listenAddrError);
        }

        boolean online = node.getStatus() != null && node.getStatus() == 1;
        Integer newHttp = nodeUpdateDto.getHttp();
        Integer newTls = nodeUpdateDto.getTls();
        Integer newSocks = nodeUpdateDto.getSocks();

        boolean httpChanged = newHttp != null && !newHttp.equals(node.getHttp());
        boolean tlsChanged = newTls != null && !newTls.equals(node.getTls());
        boolean socksChanged = newSocks != null && !newSocks.equals(node.getSocks());

        if (online && (httpChanged || tlsChanged || socksChanged)) {
            JSONObject req = new JSONObject();
            req.put("http", newHttp);
            req.put("tls", newTls);
            req.put("socks", newSocks);

            GostDto gostResult = WebSocketServer.send_msg(node.getId(), req, "SetProtocol");
            if (!Objects.equals(gostResult.getMsg(), "OK")){
                return R.err(gostResult.getMsg());
            }
        }



        Node updateNode = buildUpdateNode(nodeUpdateDto);
        this.updateById(updateNode);
        return R.ok();
    }

    @Override
    public R deleteNode(Long id) {
        if (id == null) {
            return R.err("节点ID不能为空");
        }
        Node node = this.getById(id);
        if (node == null) {
            return R.err("节点不存在");
        }

        List<ChainTunnel> list = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("node_id", id));
        Set<Long> tunnelIds = new LinkedHashSet<>();
        if (list != null) {
            for (ChainTunnel row : list) {
                if (row != null && row.getTunnelId() != null) {
                    tunnelIds.add(row.getTunnelId());
                }
            }
        }
        List<String> leftovers = new ArrayList<>();
        for (Long tunnelId : tunnelIds) {
            R result = tunnelService.deleteTunnel(tunnelId);
            // 旧实现把 deleteTunnel 的返回值直接丢掉：节点侧 service/chain 没删掉也当成功，
            // 节点记录一删就再也查不到这些端口是谁的，只能留给人肉排查。
            if (result == null) {
                continue;
            }
            if (result.getCode() != 0) {
                leftovers.add("隧道" + tunnelId + "删除失败：" + result.getMsg());
                continue;
            }
            for (String item : asMessages(result.getData())) {
                leftovers.add("隧道" + tunnelId + "：" + item);
            }
        }
        // 节点已不存在，它名下的端口占用与剩余链记录必须一并清掉：
        // getNodePort 只查库，这些悬空记录会让同一端口段重新加节点后永久分配不到。
        try {
            forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("node_id", id));
        } catch (RuntimeException e) {
            leftovers.add("端口占用记录清理失败：" + e.getMessage());
        }
        try {
            chainTunnelService.remove(new QueryWrapper<ChainTunnel>().eq("node_id", id));
        } catch (RuntimeException e) {
            leftovers.add("链记录清理失败：" + e.getMessage());
        }
        this.removeById(id);
        return leftovers.isEmpty() ? R.ok() : R.ok(leftovers);
    }


    @Override
    public R getInstallCommand(Long id) {
        if (id == null) {
            return R.err("节点ID不能为空");
        }
        Node node = this.getById(id);
        if (node == null) {
            return R.err("节点不存在");
        }
        ViteConfig viteConfig = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", "ip"));
        if (viteConfig == null) return R.err("请先前往网站配置中设置ip");

        // 发布包基址/版本改为可配置（系统属性 > 环境变量 > 默认值），默认值与原先硬编码完全一致，
        // 未配置时仍拉上游同一份 install.sh，行为不回退；自建 fork 只需设 FLUX_RELEASE_BASE_URL。
        String releaseBaseUrl = stripTrailingSlash(
                resolveConfig(RELEASE_BASE_URL_PROPERTY, RELEASE_BASE_URL_ENV, DEFAULT_RELEASE_BASE_URL));
        String releaseVersion = resolveConfig(RELEASE_VERSION_PROPERTY, RELEASE_VERSION_ENV, DEFAULT_RELEASE_VERSION);

        // 校验不通过直接返回，绝不把未校验字符串拼进下面这条 shell 命令。
        if (!RELEASE_BASE_URL_PATTERN.matcher(releaseBaseUrl).matches()) {
            return R.err("发布包基址不合法：" + releaseBaseUrl
                    + "（只允许 http/https 地址，且不能包含空格、引号、分号、管道等 shell 特殊字符）");
        }
        if (!RELEASE_VERSION_PATTERN.matcher(releaseVersion).matches()) {
            return R.err("发布包版本号不合法：" + releaseVersion
                    + "（只允许字母、数字、点、下划线、短横线）");
        }

        StringBuilder command = new StringBuilder();
        command.append("curl -L ").append(releaseBaseUrl).append('/').append(releaseVersion).append("/install.sh")
                .append(" -o ./install.sh && chmod +x ./install.sh && ");
        String processedServerAddr = GostUtil.processServerAddress(viteConfig.getValue());
        if (processedServerAddr == null || processedServerAddr.trim().isEmpty()
                || !SERVER_ADDR_PATTERN.matcher(processedServerAddr.trim()).matches()
                || processedServerAddr.length() > 255) {
            return R.err("网站配置中的服务器地址不合法：" + processedServerAddr
                    + "（只允许字母、数字、点、下划线、短横线、冒号及方括号，不能包含空格或 shell 特殊字符）");
        }
        if (node.getSecret() == null || !SECRET_PATTERN.matcher(node.getSecret()).matches()) {
            return R.err("节点密钥格式不合法");
        }
        command.append("./install.sh")
                .append(" -a ").append(processedServerAddr.trim())  // 服务器地址
                .append(" -s ").append(node.getSecret());    // 节点密钥
        return R.ok(command);

    }


    /**
     * 配置读取顺序：系统属性 > 环境变量 > 默认值；空白值视为未配置（继续往下取）。
     */
    private static String resolveConfig(String propertyName, String envName, String defaultValue) {
        String value = trimToNull(System.getProperty(propertyName));
        if (value == null) {
            value = trimToNull(System.getenv(envName));
        }
        return value == null ? defaultValue : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stripTrailingSlash(String baseUrl) {
        String result = baseUrl;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }


    private Node buildUpdateNode(NodeUpdateDto nodeUpdateDto) {
        validatePortRange(nodeUpdateDto.getPort());
        Node node = new Node();
        node.setId(nodeUpdateDto.getId());
        node.setName(nodeUpdateDto.getName());
        node.setServerIp(nodeUpdateDto.getServerIp());
        node.setPort(nodeUpdateDto.getPort());
        node.setHttp(nodeUpdateDto.getHttp());
        node.setTls(nodeUpdateDto.getTls());
        node.setSocks(nodeUpdateDto.getSocks());
        node.setUpdatedTime(System.currentTimeMillis());
        node.setInterfaceName(nodeUpdateDto.getInterfaceName());
        // 没传就不动该列（null 会被 MyBatis-Plus 的 NOT_NULL 更新策略跳过），
        // 避免只是改个名字就把已有的 "::" 打回默认值。
        node.setTcpListenAddr(normalizeListenAddr(nodeUpdateDto.getTcpListenAddr(), null));
        node.setUdpListenAddr(normalizeListenAddr(nodeUpdateDto.getUdpListenAddr(), null));
        return node;
    }


    /**
     * 监听地址归一：空白（未传/空串）记为 fallback，其余原样保留。
     * create 传 "0.0.0.0" 兜底；update 传 null，交给 NOT_NULL 更新策略跳过该列。
     *
     * <p>裸 IPv6（"::"、"fe80::1"）在这里不做改写：库里保留运维输入的原样，
     * 拼 addr 时的方括号由下发侧 GostUtil.canonicalListenAddr 统一补，
     * 避免"库值被静默改写"与"节点拿到的 addr 与库对不上"两种口径分叉。
     */
    private static String normalizeListenAddr(String listenAddr, String fallback) {
        return StrUtil.isBlank(listenAddr) ? fallback : listenAddr;
    }

    /**
     * 监听地址合法性：空表示"这次不改"（update）或走兜底（create），一律放行。
     * 其余拒绝会拼出非法 addr 或让 GOST 解析成别的地址的写法。
     *
     * @return null 表示合法；否则是可直接回给前端的错误说明
     */
    private static String validateListenAddr(String listenAddr, String field) {
        if (StrUtil.isBlank(listenAddr)) {
            return null;
        }
        String value = listenAddr.trim();
        if (value.length() > 64) {
            return field + " 长度不能超过 64 个字符";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == ',' || c == '#' || c == '@') {
                return field + " 只能是 IP 或主机名，不能包含空白、斜杠、逗号等字符";
            }
        }
        boolean open = value.startsWith("[");
        boolean close = value.endsWith("]");
        if (open != close) {
            return field + " 的方括号不匹配";
        }
        if (open) {
            String inner = value.substring(1, value.length() - 1);
            if (inner.indexOf(':') < 0) {
                return field + " 的方括号内必须是 IPv6 地址";
            }
            if (inner.contains("[") || inner.contains("]")) {
                return field + " 的方括号嵌套不合法";
            }
            return null;
        }
        if (value.indexOf(':') >= 0) {
            // 裸 IPv6：允许（normalizeListenAddr 会补括号），但不能有多个 "::"
            int first = value.indexOf("::");
            if (first >= 0 && value.indexOf("::", first + 2) >= 0) {
                return field + " 里的 \"::\" 出现次数不合法";
            }
        }
        return null;
    }


    /**
     * 改端口段前必须确认新段覆盖节点上已分配的端口。
     * 旧实现直接 updateById：既有转发端口与中继端口被留在新段之外，
     * 面板此后既不会回收也不会再分配它们，库里的占用与节点上的真实监听永久脱节。
     *
     * @return null 表示可以改；否则是可直接返回给前端的错误说明
     */
    private String checkPortRangeCoversAllocated(Node node, String newPort) {
        if (newPort == null || newPort.equals(node.getPort())) {
            return null;
        }
        Set<Integer> newPorts = new HashSet<>(TunnelServiceImpl.parsePorts(newPort));
        List<Integer> allocated = new ArrayList<>();
        // 只统计合法端口（1..65535）：历史脏行的 70000 永远不可能落进任何新端口段，
        // 计进来会让这台节点的端口段永久锁死——改哪儿都报「端口段不包含已分配的端口：70000」
        // （round45 QA P1-3）。脏行本身由 getNodePort/expectedChainNodes 忽略并告警，待人工清理。
        List<Integer> dirty = new ArrayList<>();
        for (ForwardPort forwardPort : forwardPortService.list(
                new QueryWrapper<ForwardPort>().eq("node_id", node.getId()))) {
            if (forwardPort.getPort() == null) {
                continue;
            }
            if (forwardPort.getPort() >= 1 && forwardPort.getPort() <= 65535) {
                allocated.add(forwardPort.getPort());
            } else {
                dirty.add(forwardPort.getPort());
            }
        }
        for (ChainTunnel chainTunnel : chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().eq("node_id", node.getId()))) {
            if (chainTunnel.getPort() == null) {
                continue;
            }
            if (chainTunnel.getPort() >= 1 && chainTunnel.getPort() <= 65535) {
                allocated.add(chainTunnel.getPort());
            } else {
                dirty.add(chainTunnel.getPort());
            }
        }
        if (!dirty.isEmpty()) {
            log.warn("节点[{}] 存在越界端口占用记录，已忽略不参与端口段校验（需人工清理）：{}",
                    node.getId(), dirty.stream().distinct().sorted().toList());
        }
        List<Integer> outside = allocated.stream()
                .filter(port -> !newPorts.contains(port))
                .distinct()
                .sorted()
                .toList();
        if (outside.isEmpty()) {
            return null;
        }
        return "端口段不包含已分配的端口：" + outside.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining("，"));
    }

    /** deleteTunnel 的 data 是 String 列表（清理失败明细），其它形状一律当无内容。 */
    private static List<String> asMessages(Object data) {
        if (!(data instanceof List)) {
            return Collections.emptyList();
        }
        List<String> messages = new ArrayList<>();
        for (Object item : (List<?>) data) {
            if (item != null) {
                messages.add(String.valueOf(item));
            }
        }
        return messages;
    }

    private void validatePortRange(String port) {
        Pattern PORT_PATTERN = Pattern.compile(   "([0-9]{1,5})(-([0-9]{1,5}))?");
        if (port == null || port.isEmpty()) {
            throw new RuntimeException("可用端口不合法");
        }
        String[] parts = port.split(",");
        for (String part : parts) {
            part = part.trim();
            if (!PORT_PATTERN.matcher(part).matches()) {
                throw new RuntimeException("可用端口不合法");
            }
            if (part.contains("-")) {
                String[] range = part.split("-");
                int start = Integer.parseInt(range[0]);
                int end = Integer.parseInt(range[1]);
                if (start < 1 || end < 1 || end > 65535 || start > end) {
                    throw new RuntimeException("可用端口不合法");
                }
            } else {
                int ports = Integer.parseInt(part);
                if (ports < 1 || ports > 65535) {
                    throw new RuntimeException("可用端口不合法");
                }
            }
        }
    }




}
