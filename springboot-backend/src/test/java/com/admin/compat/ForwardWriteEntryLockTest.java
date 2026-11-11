package com.admin.compat;

import com.admin.common.utils.ForwardWriteLocks;
import com.admin.service.impl.ForwardServiceImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForwardWriteEntryLockTest {
    @Test void deleteForceDeletePauseResumeFailClosedDuringWrite() {
        ForwardServiceImpl service = new ForwardServiceImpl();
        try (ForwardWriteLocks.Lease held = ForwardWriteLocks.acquire(0, 1234L)) {
            assertNotNull(held);
            assertNotEquals(0, service.deleteForward(1234L).getCode());
            assertNotEquals(0, service.forceDeleteForward(1234L).getCode());
            assertNotEquals(0, service.pauseForward(1234L).getCode());
            assertNotEquals(0, service.resumeForward(1234L).getCode());
        }
    }
}
