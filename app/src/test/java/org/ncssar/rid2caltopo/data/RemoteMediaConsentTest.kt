package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RemoteMediaConsentTest {
    @Test fun staleServerHintWhileOffNeedsApproval() {
        val c = RemoteMediaConsent()
        assertFalse(c.receive("video|1", "camera", false).isAllowed)
        assertFalse(c.receive("recording|1", "file", false).isAllowed)
    }
    @Test fun requiredPromptStaysRequiredWhileEnabled() {
        val c = RemoteMediaConsent()
        c.update(true)
        assertFalse(c.receive("video|1", "camera", true).isAllowed)
    }
    @Test fun withdrawalReenableAndReplayDoNotReviveOldOffer() {
        val c = RemoteMediaConsent()
        c.update(true)
        val old = c.receive("video|1", "camera", false)
        assertTrue(old.isAllowed)
        c.update(false)
        c.update(true)
        assertFalse(old.isAllowed)
        assertFalse(c.receive("video|1", "camera", false).isAllowed)
        assertTrue(c.receive("video|2", "camera", false).isAllowed)
    }
    @Test fun explicitApprovalIsPerRequestAndSurvivesSwitchOff() {
        val c = RemoteMediaConsent()
        val old = c.receive("video|1", "camera", false)
        val manual = c.approve("video|1")!!
        c.update(false)
        assertFalse(old.isAllowed)
        assertTrue(manual.isAllowed)
        assertFalse(manual.automatic)
        assertFalse(c.receive("video|2", "camera", false).isAllowed)
        assertNull(c.find("video|1", "other-camera"))
        assertNull(c.find("recording|1", "camera"))
    }
    @Test fun reconnectAndRestartDoNotRestorePerRequestGrants() {
        val c = RemoteMediaConsent()
        c.update(true)
        c.receive("video|1", "camera", false)
        c.reset()
        assertFalse(c.receive("video|1", "camera", false).isAllowed)
        assertNull(RemoteMediaConsent().find("video|1", "camera"))
    }
    @Test fun activeUploadCancellationRunsOnceAndLateRegistrationCancels() {
        val c = RemoteMediaConsent()
        c.update(true)
        val grant = c.receive("recording|1", "file", false)
        val canceled = AtomicInteger()
        grant.onRevocation { canceled.incrementAndGet() }
        c.update(false)
        c.update(false)
        assertEquals(1, canceled.get())
        grant.onRevocation { canceled.incrementAndGet() }
        assertEquals(2, canceled.get())
    }
    @Test fun completedTransferDoesNotSendAWithdrawal() {
        val c = RemoteMediaConsent()
        c.update(true)
        val grant = c.receive("recording|1", "file", false)
        val canceled = AtomicInteger()
        grant.onRevocation { canceled.incrementAndGet() }
        c.finish(grant.key, grant)
        c.update(false)
        assertFalse(grant.isAllowed)
        assertEquals(0, canceled.get())
    }
    @Test fun queuedRecordingCannotWriteItsFirstChunkAfterWithdrawal() {
        val file = File.createTempFile("consent-queued", ".mp4")
        try {
            file.writeBytes(ByteArray(100))
            val c = RemoteMediaConsent()
            c.update(true)
            val grant = c.receive("recording|1", "file", false)
            val body = ConsentedRecordingBody(file, 0, file.length()) { grant.isAllowed }
            c.update(false)
            val sink = Buffer()
            assertThrows(IOException::class.java) { body.writeTo(sink) }
            assertEquals(0L, sink.size)
        } finally { file.delete() }
    }
    @Test fun actualRecordingBodyStopsBetweenNetworkWrites() {
        val file = File.createTempFile("consent-stream", ".mp4")
        try {
            file.writeBytes(ByteArray(256 * 1024))
            val c = RemoteMediaConsent()
            c.update(true)
            val grant = c.receive("recording|1", "file", false)
            val received = Buffer()
            val sink = object : ForwardingSink(received) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    c.update(false)
                }
            }.buffer()
            val body = ConsentedRecordingBody(file, 0, file.length()) { grant.isAllowed }
            assertThrows(IOException::class.java) { body.writeTo(sink) }
            assertTrue(received.size > 0)
            assertTrue(received.size < file.length())
        } finally { file.delete() }
    }
    @Test fun actualRecordingBodyKeepsExplicitApprovalAndHonorsChunkBounds() {
        val file = File.createTempFile("consent-explicit", ".mp4")
        try {
            val original = ByteArray(1024) { (it % 251).toByte() }
            file.writeBytes(original)
            val c = RemoteMediaConsent()
            c.receive("recording|1", "file", true)
            val grant = c.approve("recording|1")!!
            c.update(false)
            val sink = Buffer()
            ConsentedRecordingBody(file, 50, 100) { grant.isAllowed }.writeTo(sink)
            assertArrayEquals(original.copyOfRange(50, 150), sink.readByteArray())
        } finally { file.delete() }
    }
}
