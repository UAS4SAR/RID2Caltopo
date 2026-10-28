package org.ncssar.rid2caltopo.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ColdStartGateTest {
    @Test public void blockedColdReadDoesNotBlockObserversOrExposePartialSettings() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService ui = Executors.newSingleThreadExecutor();
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Runnable> deliveries = new CopyOnWriteArrayList<>();
        ColdStartGate gate = new ColdStartGate(worker);
        AtomicInteger savedValue = new AtomicInteger(), reads = new AtomicInteger();
        try {
            gate.start(() -> {
                reads.incrementAndGet(); reading.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                savedValue.set(431);
            });
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            AtomicInteger callbackValue = new AtomicInteger();
            ui.submit(() -> {
                assertFalse(gate.isReady());
                gate.whenComplete(deliveries::add, error -> {
                    assertNull(error); callbackValue.set(savedValue.get());
                });
                gate.start(() -> fail("A second startup must not read or overwrite saved settings"));
            }).get(500, TimeUnit.MILLISECONDS);
            assertEquals(0, callbackValue.get());
            assertTrue(deliveries.isEmpty());
            release.countDown();
            worker.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertTrue(gate.isReady());
            assertEquals(1, reads.get());
            assertEquals(1, deliveries.size());
            deliveries.get(0).run();
            assertEquals(431, callbackValue.get());
        } finally { release.countDown(); worker.shutdownNow(); ui.shutdownNow(); }
    }

    @Test public void failedReadNeverUnlocksAccessOrSubstitutesDefaults() {
        List<Runnable> work = new ArrayList<>(), deliveries = new ArrayList<>();
        ColdStartGate gate = new ColdStartGate(work::add);
        RuntimeException unreadable = new RuntimeException("protected settings unavailable");
        AtomicInteger actions = new AtomicInteger();
        gate.start(() -> { throw unreadable; });
        gate.whenComplete(deliveries::add, error -> {
            assertSame(unreadable, error);
            if (error == null) actions.incrementAndGet();
        });
        assertTrue(deliveries.isEmpty());
        work.remove(0).run();
        assertFalse(gate.isReady());
        deliveries.remove(0).run();
        assertEquals(0, actions.get());
        gate.start(() -> fail("Failed startup must not silently reset or retry"));
        assertTrue(work.isEmpty());
    }

    @Test public void warmObserverIsDeliveredWithoutRepeatingTheRead() {
        ColdStartGate gate = new ColdStartGate(Runnable::run);
        AtomicInteger reads = new AtomicInteger(), callbacks = new AtomicInteger();
        gate.start(reads::incrementAndGet);
        gate.whenComplete(Runnable::run, error -> { assertNull(error); callbacks.incrementAndGet(); });
        gate.start(reads::incrementAndGet);
        assertTrue(gate.isReady()); assertEquals(1, reads.get()); assertEquals(1, callbacks.get());
    }
}
