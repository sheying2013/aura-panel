package com.admin.ddns;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DdnsDomainGateInternationalTest {
    @Test
    void unicodeAndPunycodeTargetsShareTheSameLock() throws Exception {
        DdnsDomainGate gate = new DdnsDomainGate();
        DdnsDomainGate.Lease transition = gate.exclusive("xn--fsqu00a.xn--0zwm56d", 1000);
        assertNotNull(transition);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = executor.submit(() -> {
                DdnsDomainGate.Lease lease = gate.sharedForTargets("例子.测试:443", 100);
                if (lease != null) { lease.close(); return true; }
                return false;
            });
            assertFalse(result.get(2, TimeUnit.SECONDS), "Unicode alias must be locked during Punycode transition");
        } finally {
            transition.close();
            executor.shutdownNow();
        }
    }
}
