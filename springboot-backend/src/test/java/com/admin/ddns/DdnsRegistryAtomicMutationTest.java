package com.admin.ddns;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DdnsRegistryAtomicMutationTest {
    @Test void writesAndCleanupUseTheSameMonitor() throws Exception {
        assertTrue(Modifier.isSynchronized(DdnsAddressRegistry.class.getMethod("put", String.class, String.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(DdnsAddressRegistry.class.getMethod("remove", String.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(DdnsAddressRegistry.class.getMethod("markLoaded", java.util.Collection.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(DdnsAddressRegistry.class.getMethod("clear").getModifiers()));
    }
    @Test void writeAfterCleanupPreservesNewAddress() throws Exception {
        DdnsAddressRegistry r = new DdnsAddressRegistry();
        r.put("race.example", "192.0.2.1");
        r.markLoaded(List.of("race.example"));
        CountDownLatch started = new CountDownLatch(1);
        Thread writer;
        synchronized (r) {
            writer = new Thread(() -> { started.countDown(); r.put("race.example", "192.0.2.2"); });
            writer.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            r.markLoaded(List.of());
        }
        writer.join(2000);
        assertFalse(writer.isAlive());
        assertEquals("192.0.2.2", r.get("race.example"));
        assertEquals(1, r.knownSize());
        r.clear(); DdnsTargetRenderer.reset();
    }
}
