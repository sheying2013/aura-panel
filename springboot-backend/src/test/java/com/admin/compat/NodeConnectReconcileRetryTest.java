package com.admin.compat;

import com.admin.common.utils.WebSocketServer;
import com.admin.service.ForwardService;
import com.admin.service.SpeedLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeConnectReconcileRetryTest {
    @SuppressWarnings("unchecked")
    private ConcurrentMap<Long, WebSocketSession> sessions() throws Exception {
        Field f = WebSocketServer.class.getDeclaredField("nodeSessions");
        f.setAccessible(true);
        return (ConcurrentMap<Long, WebSocketSession>) f.get(null);
    }
    private void reconcile(WebSocketServer server, long id, WebSocketSession session, int retries) throws Exception {
        Method m = WebSocketServer.class.getDeclaredMethod("reconcileNodeState", Long.class, WebSocketSession.class, int.class);
        m.setAccessible(true); m.invoke(server, id, session, retries);
    }
    @Test void staleSessionDoesNotOperateOnNewConnection() throws Exception {
        long id = 987654321L;
        WebSocketSession oldSession = mock(WebSocketSession.class);
        WebSocketSession newSession = mock(WebSocketSession.class);
        ForwardService forwards = mock(ForwardService.class);
        WebSocketServer server = new WebSocketServer();
        ReflectionTestUtils.setField(server, "forwardService", forwards);
        sessions().put(id, newSession);
        try { reconcile(server, id, oldSession, 6); verifyNoInteractions(forwards); }
        finally { sessions().remove(id, newSession); }
    }
    @Test void closedSessionDoesNotTriggerReconcile() throws Exception {
        long id = 987654322L;
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);
        ForwardService forwards = mock(ForwardService.class);
        WebSocketServer server = new WebSocketServer();
        ReflectionTestUtils.setField(server, "forwardService", forwards);
        sessions().put(id, session);
        try { reconcile(server, id, session, 6); verifyNoInteractions(forwards); }
        finally { sessions().remove(id, session); }
    }
    @Test void currentSessionRunsOneReconcile() throws Exception {
        long id = 987654323L;
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        ForwardService forwards = mock(ForwardService.class);
        SpeedLimitService limits = mock(SpeedLimitService.class);
        when(forwards.resyncNodeServices(eq(id), anyList(), anyList())).thenReturn(List.of());
        when(limits.resyncNodeLimiters(id)).thenReturn(List.of());
        WebSocketServer server = new WebSocketServer();
        ReflectionTestUtils.setField(server, "forwardService", forwards);
        ReflectionTestUtils.setField(server, "speedLimitService", limits);
        sessions().put(id, session);
        try { reconcile(server, id, session, 0); verify(forwards).resyncNodeServices(eq(id), anyList(), anyList()); }
        finally { sessions().remove(id, session); }
    }
}
