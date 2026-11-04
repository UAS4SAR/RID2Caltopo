package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedVideoPresencePolicyTest {
    @Test
    fun shouldIncludeThumbnailJpeg_omitsUnchangedRevision() {
        assertFalse(
            ManagedVideoPresencePolicy.shouldIncludeThumbnailJpeg(
                "100",
                "abc",
                "100",
            ),
        )
    }

    @Test
    fun shouldIncludeThumbnailJpeg_includesWhenRevisionChanges() {
        assertTrue(
            ManagedVideoPresencePolicy.shouldIncludeThumbnailJpeg(
                "101",
                "abc",
                "100",
            ),
        )
    }

    @Test
    fun shouldIncludeThumbnailJpeg_includesFirstSend() {
        assertTrue(
            ManagedVideoPresencePolicy.shouldIncludeThumbnailJpeg(
                "100",
                "abc",
                null,
            ),
        )
    }

    @Test
    fun shouldIncludeThumbnailJpeg_requiresBytesAndRevision() {
        assertFalse(
            ManagedVideoPresencePolicy.shouldIncludeThumbnailJpeg(
                "100",
                null,
                null,
            ),
        )
        assertFalse(
            ManagedVideoPresencePolicy.shouldIncludeThumbnailJpeg(
                "",
                "abc",
                null,
            ),
        )
    }
}
