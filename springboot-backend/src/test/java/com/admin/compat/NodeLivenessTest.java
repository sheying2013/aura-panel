package com.admin.compat;

import com.admin.common.utils.WebSocketServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 节点存活判定：{@link WebSocketServer#isNodeOnline(Long)} 除了会话是否 open，还要看节点最近有没有发声。
 *
 * <p>为什么需要这层：节点每 2 秒发一次心跳，但网络黑洞、容器 pause、宿主机挂起时 TCP 不会立刻 FIN，
 * {@code session.isOpen()} 会一直返回 true。旧实现只看 isOpen，批量变更（限速器增删改、拓扑热改）
 * 的预检就会放行，然后逐台下发全部失败再回滚——预检形同虚设。
 */
class NodeLivenessTest {

    private static final long NODE_ID = 7L;

    private Map<Long, WebSocketSession> nodeSessions;
    private Map<Long, Long> nodeLastSeen;
    private Method touchNode;
    private Method forgetNode;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        nodeSessions = (Map<Long, WebSocketSession>) staticField("nodeSessions");
        nodeLastSeen = (Map<Long, Long>) staticField("nodeLastSeen");
        nodeSessions.clear();
        nodeLastSeen.clear();
        touchNode = WebSocketServer.class.getDeclaredMethod("touchNode", String.class);
        touchNode.setAccessible(true);
        forgetNode = WebSocketServer.class.getDeclaredMethod("forgetNode", Long.class);
        forgetNode.setAccessible(true);
    }

    @Test
    void openSessionWithFreshHeartbeatIsOnline() throws Exception {
        nodeSessions.put(NODE_ID, openSession());
        touchNode.invoke(null, String.valueOf(NODE_ID));

        assertTrue(WebSocketServer.isNodeOnline(NODE_ID), "刚发过心跳的会话应判在线");
        assertTrue(WebSocketServer.nodeSilenceMs(NODE_ID) >= 0, "静默时长应可读");
    }

    @Test
    void openSessionSilentBeyondWindowIsOffline() throws Exception {
        nodeSessions.put(NODE_ID, openSession());
        // 直接改记录时间，等价于「TCP 还在、对端已死」：心跳早已停止但连接没被关闭
        nodeLastSeen.put(NODE_ID, System.currentTimeMillis() - 60_000L);

        assertFalse(WebSocketServer.isNodeOnline(NODE_ID), "静默超过窗口必须判离线，否则预检放行半死节点");
        assertTrue(WebSocketServer.nodeSilenceMs(NODE_ID) >= 60_000L);
    }

    @Test
    void openSessionWithoutAnyRecordStillOnline() {
        nodeSessions.put(NODE_ID, openSession());

        assertTrue(WebSocketServer.isNodeOnline(NODE_ID), "没有活动记录的会话要回落到 isOpen，避免刚连上就被判离线");
        assertEquals(-1L, WebSocketServer.nodeSilenceMs(NODE_ID));
    }

    @Test
    void closedSessionIsOfflineEvenWithFreshHeartbeat() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);
        nodeSessions.put(NODE_ID, session);
        touchNode.invoke(null, String.valueOf(NODE_ID));

        assertFalse(WebSocketServer.isNodeOnline(NODE_ID));
    }

    @Test
    void forgetNodeDropsSilenceRecord() throws Exception {
        nodeSessions.put(NODE_ID, openSession());
        nodeLastSeen.put(NODE_ID, System.currentTimeMillis() - 120_000L);
        assertFalse(WebSocketServer.isNodeOnline(NODE_ID));

        forgetNode.invoke(null, NODE_ID);
        assertEquals(-1L, WebSocketServer.nodeSilenceMs(NODE_ID), "重连前必须先清掉旧记录");
        assertTrue(WebSocketServer.isNodeOnline(NODE_ID), "清掉旧记录后按新会话的 isOpen 判定");
    }

    @Test
    void nonNumericIdIsIgnored() throws Exception {
        touchNode.invoke(null, "not-a-node");
        touchNode.invoke(null, "   ");
        touchNode.invoke(null, (Object) null);

        assertTrue(nodeLastSeen.isEmpty(), "非节点会话不该写活动记录");
    }

    private static WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static Object staticField(String name) throws Exception {
        Field field = WebSocketServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
