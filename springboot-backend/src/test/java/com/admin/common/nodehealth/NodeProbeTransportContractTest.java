package com.admin.common.nodehealth;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 探测通道契约测试。
 *
 * <p>锁死三点：① 探测 payload 的端口是 0——节点侧 handleTcpPing 会先校验端口范围，
 * 非法端口直接回包、不发起 dial，所以不产生对业务端口的连接（<b>不等于零副作用</b>：
 * 旧版 agent 每条命令都会 saveConfig() 重写 gost.json，本地修复未部署）；
 * ② 探测只覆盖 agent 控制通道，不能当作业务数据面可达；③ send_msg 的四类失败文案
 * 必须被判成「没拿到回包」，否则半死连接会被说成在线。
 */
class NodeProbeTransportContractTest {

    @Test
    void payloadUsesInvalidPortSoNodeAnswersWithoutDialing() {
        JSONObject payload = WebSocketNodeProbeTransport.probePayload();

        assertEquals(0, payload.getIntValue("port"), "端口 0 让节点在拨号前回包：只测控制通道，不连业务端口");
        assertEquals("127.0.0.1", payload.getString("ip"));
        assertEquals(1, payload.getIntValue("count"));
        assertEquals(1000, payload.getIntValue("timeout"));
    }

    @Test
    void failureTextsFromSendMsgAreTreatedAsNoReply() {
        assertTrue(WebSocketNodeProbeTransport.isTransportFailure(null));
        assertTrue(WebSocketNodeProbeTransport.isTransportFailure("   "));
        assertTrue(WebSocketNodeProbeTransport.isTransportFailure("节点不在线"));
        assertTrue(WebSocketNodeProbeTransport.isTransportFailure("节点连接已断开"));
        assertTrue(WebSocketNodeProbeTransport.isTransportFailure("等待响应超时"));
        assertTrue(WebSocketNodeProbeTransport.isTransportFailure("发送消息失败: java.io.IOException"));
    }

    @Test
    void nodeReplyIsTreatedAsResponded() {
        assertFalse(WebSocketNodeProbeTransport.isTransportFailure("OK"));
        assertFalse(WebSocketNodeProbeTransport.isTransportFailure("无响应消息"),
                "节点回了包但没带 message，也是「通道是通的」，不能算失败");
    }

    @Test
    void probeCommandIsTheReadOnlyDiagnosticOne() {
        assertEquals("TcpPing", WebSocketNodeProbeTransport.PROBE_COMMAND);
    }

    @Test
    void probeScopeAndNoticeDoNotClaimBusinessReachability() {
        assertEquals("CONTROL_PLANE", NodeProbeService.PROBE_SCOPE);
        assertTrue(NodeProbeService.PROBE_NOTICE.contains("控制通道"));
        assertTrue(NodeProbeService.PROBE_NOTICE.contains("不代表该节点承载的业务流量"),
                "接口口径必须写明这不是业务可达");
        assertTrue(NodeProbeService.PROBE_NOTICE.contains("gost.json"),
                "必须承认旧版 agent 会回写配置，不能宣称零副作用");
    }
}
