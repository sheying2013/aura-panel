package com.admin.common.nodehealth;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSONObject;

/**
 * 默认探测通道：借既有的节点命令通道做一次真实往返。
 *
 * <p>为什么用 {@code TcpPing} 而不是另开一个 TCP 探活：节点 agent 是主动出站 WS 连到面板的，
 * 面板侧没有「节点监听端口」这个概念（{@code node.port} 是分配出去的端口段字符串），反向 TCP
 * 探测既找不到端口也会误伤业务流量。而 WS 命令通道本身就是「面板能不能指挥这个节点」的那条路。
 *
 * <p>为什么 payload 里端口刻意填 0：节点侧 {@code handleTcpPing} 会先校验端口范围，
 * 非法端口直接回包、不发起 dial（见 go-gost/x/socket/websocket_reporter.go）。所以这一轮
 * 不会连业务端口、不会碰隧道转发路径，只回答「命令能下发、节点能回包」。
 *
 * <p><b>但这不是「零副作用」，也不代表节点业务可达</b>：
 * ① 它只覆盖 agent 控制通道，代理端口能否转发流量属于数据面，本探测不下结论，字段名也不叫「业务可达」；
 * ② 已部署的旧版 agent 在 {@code routeCommand} 里无论成功失败都会调 {@code saveConfig()}，
 * 用 {@code os.Create("gost.json")} 整份重写配置——每轮探测 = 每个节点一次「内容不变」的写盘；
 * ③ 本地 go 侧已改成 TcpPing 不回写，但<b>尚未部署</b>，所以在旧 agent 上这个副作用真实存在。
 * 间隔调成分钟级之前，必须先确认节点已升级。
 *
 * <p>失败文案是 {@code WebSocketServer.send_msg} 的固定中文串（节点不在线 / 节点连接已断开 /
 * 等待响应超时 / 发送消息失败: ...），匹配规则全部收敛在本类，判定层只看
 * {@link NodeProbeTransport.Reply#responded()}。
 */
public class WebSocketNodeProbeTransport implements NodeProbeTransport {

    /** 节点侧支持的命令类型之一，也是唯一「只做一次往返、不改动拓扑」的诊断命令。 */
    static final String PROBE_COMMAND = "TcpPing";

    @Override
    public Reply probe(Long nodeId) {
        if (nodeId == null) {
            return Reply.noReply("节点 ID 为空，无法探测");
        }
        long startNs = System.nanoTime();
        GostDto reply;
        try {
            reply = WebSocketServer.send_msg(nodeId, probePayload(), PROBE_COMMAND);
        } catch (RuntimeException e) {
            return Reply.noReply("探测命令下发异常: " + e.getMessage());
        }
        long costMs = (System.nanoTime() - startNs) / 1_000_000L;
        String message = reply == null ? null : reply.getMsg();
        if (isTransportFailure(message)) {
            return Reply.noReply(message == null || message.isBlank() ? "节点无响应" : message.trim());
        }
        return Reply.ok(costMs, "命令往返成功（节点回包：" + message.trim() + "）");
    }

    /** 端口 0 让节点在拨号前就回包：不连业务端口。注意这不等于零副作用，见类注释②③。 */
    static JSONObject probePayload() {
        JSONObject data = new JSONObject();
        data.put("ip", "127.0.0.1");
        data.put("port", 0);
        data.put("count", 1);
        data.put("timeout", 1000);
        return data;
    }

    /** 是否为「没拿到回包」的文案；其余（含 "OK"、空响应消息）都算节点回包了。 */
    static boolean isTransportFailure(String message) {
        if (message == null || message.trim().isEmpty()) {
            return true;
        }
        String text = message.trim();
        return "节点不在线".equals(text)
                || "节点连接已断开".equals(text)
                || "等待响应超时".equals(text)
                || text.startsWith("发送消息失败");
    }
}
