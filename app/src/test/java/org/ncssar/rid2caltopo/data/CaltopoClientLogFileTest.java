package org.ncssar.rid2caltopo.data;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CaltopoClientLogFileTest {
    @Test
    public void buildDebugLogFilename_includesTxtSuffixAtCreation() {
        String filename = CaltopoClient.BuildDebugLogFilename(1_777_928_118_000L);

        assertTrue(filename.startsWith("Log_"));
        assertTrue(filename.endsWith(".txt"));
        assertEquals(1, filename.split("\\.txt", -1).length - 1);
    }

    @Test
    public void staleShutdown_doesNotCloseLoggerAfterAppRestart() throws Exception {
        CloseTrackingOutputStream stream = new CloseTrackingOutputStream();
        CaltopoClient.InstallDebugOutputStreamForTests(stream);
        long staleShutdownGeneration = CaltopoClient.CaptureDebugLogGenerationForTests();

        CaltopoClient.MarkAppActive();
        CaltopoClient.CloseDebugOutputStreamForShutdownForTests(staleShutdownGeneration);

        assertFalse(stream.closed);

        CaltopoClient.CTDebug("CaltopoClientLogFileTest", "after stale shutdown");
        DiagnosticLogWork.drainForTests();
        assertTrue(stream.toString().contains("after stale shutdown"));
    }

    @Test
    public void currentShutdown_closesLoggerWhenNoRestartOccurred() {
        CloseTrackingOutputStream stream = new CloseTrackingOutputStream();
        CaltopoClient.InstallDebugOutputStreamForTests(stream);
        long shutdownGeneration = CaltopoClient.CaptureDebugLogGenerationForTests();

        CaltopoClient.CloseDebugOutputStreamForShutdownForTests(shutdownGeneration);

        assertTrue(stream.closed);
    }

    @Test
    public void blockedLogProviderDoesNotBlockProducersOrLifecycleReads() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        OutputStream blocked = new OutputStream() {
            @Override public void write(int value) throws IOException {
                entered.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
        };
        CaltopoClient.InstallDebugOutputStreamForTests(blocked);
        try {
            CaltopoClient.CTLog("TEST","logger","blocked provider");
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            caller.submit(() -> {
                CaltopoClient.CTLog("TEST","logger","another UI message");
                CaltopoClient.CaptureDebugLogGenerationForTests();
                CaltopoClient.MarkAppActive();
            }).get(500,java.util.concurrent.TimeUnit.MILLISECONDS);
        } finally {
            release.countDown(); DiagnosticLogWork.drainForTests(); caller.shutdownNow();
            CaltopoClient.InstallDebugOutputStreamForTests(new ByteArrayOutputStream());
        }
    }

    private static final class CloseTrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        boolean closed;

        @Override
        public void write(int b) {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            delegate.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
