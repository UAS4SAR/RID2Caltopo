package org.ncssar.rid2caltopo.data;

import org.junit.Test;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class CaltopoClientColdSettingsTest {
    @Test public void startupWaitsForProvisionalRestoreWhileWarmGettersRemainAvailable() throws Exception {
        Field state = CaltopoClient.class.getDeclaredField("Ccstate"); state.setAccessible(true);
        Field reload = CaltopoClient.class.getDeclaredField("StateReloadLock"); reload.setAccessible(true);
        Object original = state.get(null), lock = reload.get(null);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        CountDownLatch restoring = new CountDownLatch(1), finish = new CountDownLatch(1);
        try {
            state.set(null, new ClientClassState());
            Future<?> owner = workers.submit(() -> {
                synchronized (lock) {
                    restoring.countDown();
                    try { finish.await(); } catch (InterruptedException error) { throw new RuntimeException(error); }
                }
            });
            assertTrue(restoring.await(2, TimeUnit.SECONDS));
            Future<?> prepare = workers.submit(CaltopoClient::PrepareSavedSettings);
            try { prepare.get(100, TimeUnit.MILLISECONDS); fail("Startup exposed provisional settings before restore finished"); }
            catch (TimeoutException expected) {}
            workers.submit(CaltopoClient::GetMaxIdleTimeInMinutes).get(500, TimeUnit.MILLISECONDS);
            finish.countDown();
            owner.get(2, TimeUnit.SECONDS); prepare.get(2, TimeUnit.SECONDS);
        } finally { finish.countDown(); workers.shutdownNow(); state.set(null, original); }
    }
}
