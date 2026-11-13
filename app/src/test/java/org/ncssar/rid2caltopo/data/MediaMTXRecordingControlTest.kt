package org.ncssar.rid2caltopo.data

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMTXRecordingControlTest {
    private val direct = Executor { it.run() }
    private val root = File("/data/user/0/org.ncssar.rid2caltopo/files/mediamtx-recordings")

    private class FakeApi(var accept: Boolean = true) : MediaMTXRecordingApi {
        val calls = mutableListOf<MediaMTXRecordingState>()
        override fun apply(state: MediaMTXRecordingState): Boolean { calls += state; return accept }
    }

    @Test fun patchBodyCarriesRecordSettingsAndEscapes() {
        val on = MediaMTXHttpRecordingApi.patchBody(MediaMTXRecordingState(true, File("/tmp/a\"b")))
        assertEquals(
            "{\"record\":true,\"recordPath\":\"/tmp/a\\\"b/%path/%path_%Y-%m-%d_%H-%M-%S-%f\"," +
                "\"recordFormat\":\"fmp4\",\"recordDeleteAfter\":\"0s\"}",
            on,
        )
        assertEquals("{\"record\":false}", MediaMTXHttpRecordingApi.patchBody(MediaMTXRecordingState(false, null)))
    }

    @Test fun httpApiPatchesPathDefaultsOnTheLocalApi() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(400))
            server.start()
            val api = MediaMTXHttpRecordingApi(server.url("/").toString().trimEnd('/'))
            assertTrue(api.apply(MediaMTXRecordingState(false, root)))
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("PATCH", request.method)
            assertEquals("/v3/config/pathdefaults/patch", request.path)
            assertTrue(request.body.readUtf8().startsWith("{\"record\":false,\"recordPath\":"))
            assertFalse(api.apply(MediaMTXRecordingState(true, root)))
        }
        assertFalse(MediaMTXHttpRecordingApi("http://127.0.0.1:1").apply(MediaMTXRecordingState(false, null)))
    }

    @Test fun recordingGoesOnOnceTheRecorderIsReadyWithoutRestart() {
        val api = FakeApi()
        val restarts = mutableListOf<Unit>()
        val r = MediaMTXRecordingReconciler(api, direct, sleeper = {}, onDisableFailed = { restarts += Unit })
        // Listener opened with recording off: recorder state still being prepared.
        r.onServerStarted(MediaMTXRecordingState(false, root), captureEnabled = true, storageBlocked = false, recordingRoot = null)
        assertTrue(api.calls.isEmpty())
        r.updateRecorderRoot(root)
        assertEquals(listOf(MediaMTXRecordingState(true, root)), api.calls)
        assertEquals(MediaMTXRecordingState(true, root), r.appliedState())
        r.updateRecorderRoot(root) // unchanged: no second call
        assertEquals(1, api.calls.size)
        assertTrue(restarts.isEmpty())
    }

    @Test fun storageOverTheLimitSwitchesRecordingOffPromptly() {
        val api = FakeApi()
        val r = MediaMTXRecordingReconciler(api, direct, sleeper = {})
        r.onServerStarted(MediaMTXRecordingState(true, root), captureEnabled = true, storageBlocked = false, recordingRoot = root)
        assertTrue(api.calls.isEmpty())
        r.updateStorageBlocked(true) // background scan found the allowance or reserve exceeded
        assertEquals(false, api.calls.single().record)
        r.updateStorageBlocked(false)
        assertEquals(MediaMTXRecordingState(true, root), api.calls.last())
    }

    @Test fun startupEstimateBlockedThenScanAllowsRecording() {
        val api = FakeApi()
        val r = MediaMTXRecordingReconciler(api, direct, sleeper = {})
        r.onServerStarted(MediaMTXRecordingState(false, root), captureEnabled = true, storageBlocked = true, recordingRoot = root)
        r.updateStorageBlocked(false)
        assertEquals(listOf(MediaMTXRecordingState(true, root)), api.calls)
    }

    @Test fun captureOffNeverRecords() {
        val api = FakeApi()
        val r = MediaMTXRecordingReconciler(api, direct, sleeper = {})
        r.onServerStarted(MediaMTXRecordingState(false, root), captureEnabled = false, storageBlocked = false, recordingRoot = root)
        r.updateStorageBlocked(false)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun unreachableApiWhileDisablingFallsBackToRestartAfterRetries() {
        val api = FakeApi(accept = false)
        val slept = mutableListOf<Long>()
        var restarts = 0
        val r = MediaMTXRecordingReconciler(api, direct, sleeper = { slept += it }, retryDelaysMs = listOf(10, 20), onDisableFailed = { restarts++ })
        r.onServerStarted(MediaMTXRecordingState(true, root), captureEnabled = true, storageBlocked = false, recordingRoot = root)
        r.updateStorageBlocked(true)
        assertEquals(3, api.calls.size)
        assertEquals(listOf(10L, 20L), slept)
        assertEquals(1, restarts)
    }

    @Test fun unreachableApiWhileEnablingNeverRestarts() {
        val api = FakeApi(accept = false)
        var restarts = 0
        val r = MediaMTXRecordingReconciler(api, direct, sleeper = {}, retryDelaysMs = listOf(1), onDisableFailed = { restarts++ })
        r.onServerStarted(MediaMTXRecordingState(false, root), captureEnabled = true, storageBlocked = false, recordingRoot = null)
        r.updateRecorderRoot(root)
        assertEquals(2, api.calls.size)
        assertEquals(0, restarts)
        assertEquals(false, r.appliedState()!!.record)
    }

    @Test fun stoppedServerIsNotPatched() {
        val api = FakeApi()
        val r = MediaMTXRecordingReconciler(api, direct, sleeper = {})
        r.onServerStarted(MediaMTXRecordingState(true, root), captureEnabled = true, storageBlocked = false, recordingRoot = root)
        r.onServerStopped()
        r.updateStorageBlocked(true)
        assertTrue(api.calls.isEmpty())
    }
}
