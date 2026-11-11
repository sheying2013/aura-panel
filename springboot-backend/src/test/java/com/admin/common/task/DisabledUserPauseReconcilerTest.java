package com.admin.common.task;

import com.admin.entity.User;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DisabledUserPauseReconcilerTest {
    @Test void retriesPersistedDisabledUsersOnEverySweep() {
        UserService users = mock(UserService.class);
        ForwardService forwards = mock(ForwardService.class);
        User disabled = new User(); disabled.setId(7L); disabled.setStatus(0);
        when(users.list(any())).thenReturn(List.of(disabled));
        when(forwards.pauseForDisabledUser(7L)).thenReturn(List.of("busy"), List.of());
        DisabledUserPauseReconciler worker = new DisabledUserPauseReconciler(users, forwards);
        try {
            worker.reconcileBatch(); worker.reconcileBatch();
            verify(forwards, times(2)).pauseForDisabledUser(7L);
        } finally { worker.destroy(); }
    }
    @Test void databaseFailureDoesNotLookLikeEmptySuccessfulScan() {
        UserService users = mock(UserService.class);
        ForwardService forwards = mock(ForwardService.class);
        when(users.list(any())).thenReturn(null);
        DisabledUserPauseReconciler worker = new DisabledUserPauseReconciler(users, forwards);
        try {
            assertThrows(IllegalStateException.class, worker::reconcileBatch);
            verifyNoInteractions(forwards);
        } finally { worker.destroy(); }
    }
    @Test void oneUserFailureDoesNotStopOtherUsers() {
        UserService users = mock(UserService.class);
        ForwardService forwards = mock(ForwardService.class);
        User first = new User(); first.setId(7L); User second = new User(); second.setId(8L);
        when(users.list(any())).thenReturn(List.of(first, second));
        when(forwards.pauseForDisabledUser(7L)).thenThrow(new IllegalStateException("busy"));
        when(forwards.pauseForDisabledUser(8L)).thenReturn(List.of());
        DisabledUserPauseReconciler worker = new DisabledUserPauseReconciler(users, forwards);
        try {
            worker.reconcileBatch();
            verify(forwards).pauseForDisabledUser(8L);
        } finally { worker.destroy(); }
    }
}
