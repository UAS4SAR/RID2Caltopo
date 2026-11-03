package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class OrphanEmptyRegistryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun rememberIsIdempotent() {
        val id = UUID.randomUUID().toString()
        assertTrue(OrphanEmptyRegistry.remember(folder.root, id))
        assertFalse(OrphanEmptyRegistry.remember(folder.root, id))
        assertEquals(setOf(id), OrphanEmptyRegistry.load(folder.root))
    }
}
