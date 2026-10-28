package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MapFolderVisibilityTest {
    private class Fixture : AutoCloseable {
        val ui = Executors.newSingleThreadExecutor { Thread(it, "test-ui") }.asCoroutineDispatcher()
        val disk = Executors.newSingleThreadExecutor { Thread(it, "test-disk") }.asCoroutineDispatcher()
        val preparationExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-prepare") }
        val preparation = preparationExecutor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val data = ConcurrentHashMap<String, String>()
        val writes = AtomicInteger()
        val diskThreads = ConcurrentHashMap.newKeySet<String>()
        var beforeRead: (String) -> Unit = {}
        var beforeWrite: () -> Unit = {}
        val preferences = newPreferences()
        fun newPreferences() = MapFolderVisibilityPreferences(disk,
            read = { key -> diskThreads.add(Thread.currentThread().name.substringBefore(" @")); beforeRead(key); data[key] },
            write = { key, value ->
                diskThreads.add(Thread.currentThread().name.substringBefore(" @"))
                beforeWrite()
                data[key] = value
                writes.incrementAndGet()
            })
        fun controller(preferences: MapFolderVisibilityPreferences = this.preferences) =
            MapFolderVisibilityController(scope, preferences, preparation)
        suspend fun restore(controller: MapFolderVisibilityController, key: String) {
            withContext(ui) { controller.restore(key) }?.join()
        }
        override fun close() {
            scope.cancel()
            ui.close()
            disk.close()
            preparation.close()
        }
    }

    private val taylor = mapFolderVisibilityKey("caltopo.com", "Taylor-ID")!!

    @Test fun reconnectAndNewControllerRestoreFolderAndIndividualChoices() = runBlocking {
        Fixture().use { f ->
            val first = f.controller()
            f.restore(first, taylor)
            withContext(f.ui) {
                first.applyFolderDefault("server-hidden", false)
                first.setFolder("server-hidden", true)
                first.setFolder("tracks", false)
                first.setItem("clue-1", false)
                first.reset()
            }
            f.restore(first, taylor)
            withContext(f.ui) {
                assertEquals(setOf("tracks"), first.state.hiddenFolders.toSet())
                assertEquals(setOf("clue-1"), first.state.hiddenItems.toSet())
            }
            // A new preferences/controller instance reads the durable backend, not VM memory.
            val restarted = f.controller(f.newPreferences())
            f.restore(restarted, taylor)
            withContext(f.ui) {
                restarted.applyFolderDefault("server-hidden", false)
                assertFalse("explicit show must beat a repeated server hide", "server-hidden" in restarted.state.hiddenFolders)
                assertEquals(false, restarted.state.folderOverrides["tracks"])
                assertEquals(setOf("clue-1"), restarted.state.hiddenItems.toSet())
            }
            assertEquals(setOf("test-disk"), f.diskThreads)
        }
    }

    @Test fun mapAndServerIdsIsolateSettingsIncludingSimilarMapIds() = runBlocking {
        Fixture().use { f ->
            val otherMap = mapFolderVisibilityKey("caltopo.com", "Taylor_ID")!!
            val otherServer = mapFolderVisibilityKey("sartopo.com", "Taylor-ID")!!
            val controller = f.controller()
            f.restore(controller, taylor)
            withContext(f.ui) { controller.setFolder("same-folder-id", false) }
            for (key in listOf(otherMap, otherServer)) {
                f.restore(controller, key)
                withContext(f.ui) { assertTrue(controller.state.hiddenFolders.isEmpty()) }
            }
            f.restore(controller, taylor)
            withContext(f.ui) { assertTrue("same-folder-id" in controller.state.hiddenFolders) }
            assertEquals(taylor, mapFolderVisibilityKey(" CALTOPO.COM ", "Taylor-ID"))
            assertNull(mapFolderVisibilityKey("caltopo.com", " "))
        }
    }

    @Test fun slowWriteDoesNotBlockGesturesAndRapidSavesCoalesce() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            f.preferences.save(taylor, SavedMapFolderVisibility(hiddenItems = setOf("initial")))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                val caller = Executors.newSingleThreadExecutor()
                try {
                    caller.submit {
                        repeat(1000) { index ->
                            f.preferences.save(taylor, SavedMapFolderVisibility(hiddenItems = setOf("item-$index")))
                        }
                    }.get(2, TimeUnit.SECONDS)
                } finally { caller.shutdownNow() }
            } finally { release.countDown() }
            assertEquals(setOf("item-999"), f.preferences.load(taylor).hiddenItems)
            assertEquals(2, f.writes.get())
        }
    }

    @Test fun queuedSnapshotCannotBeChangedByLaterUiEdits() = runBlocking {
        Fixture().use { f ->
            val current = MapFolderVisibilityState()
            current.hiddenItems.add("original")
            current.folderOverrides["folder"] = false
            val saved = current.saved()
            current.hiddenItems.clear()
            current.hiddenItems.add("later")
            current.folderOverrides["folder"] = true
            f.preferences.save(taylor, saved)
            val loaded = f.preferences.load(taylor)
            assertEquals(setOf("original"), loaded.hiddenItems)
            assertEquals(mapOf("folder" to false), loaded.folderOverrides)
        }
    }

    @Test fun switchingMapsWhileDiskReadIsBlockedCannotApplyOldSettings() = runBlocking {
        Fixture().use { f ->
            val second = mapFolderVisibilityKey("caltopo.com", "second")!!
            f.data[taylor] = SavedMapFolderVisibility(hiddenFolders = setOf("old-map")).encode()
            f.data[second] = SavedMapFolderVisibility(hiddenItems = setOf("new-map")).encode()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.beforeRead = { key ->
                if (key == taylor) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            val controller = f.controller()
            val firstJob = withContext(f.ui) { controller.restore(taylor) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val secondJob = try {
                withContext(f.ui) {
                    controller.setFolder("during-load", false)
                    controller.restore(second)
                }
            } finally { release.countDown() }
            secondJob?.join()
            firstJob?.join()
            withContext(f.ui) {
                assertEquals(second, controller.state.mapKey)
                assertTrue(controller.state.hiddenFolders.isEmpty())
                assertEquals(setOf("new-map"), controller.state.hiddenItems.toSet())
            }
            assertEquals(0, f.writes.get())
        }
    }

    @Test fun bulkChoicesPreserveInterveningSingleItemGesture() = runBlocking {
        Fixture().use { f ->
            val controller = f.controller()
            f.restore(controller, taylor)
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.preparationExecutor.submit { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val bulk = withContext(f.ui) { controller.setItems(listOf("bulk-1", "bulk-2"), false) }
            try {
                withContext(f.ui) {
                    controller.setItem("single", false)
                    controller.setItem("bulk-1", true)
                }
            } finally { release.countDown() }
            bulk.join()
            assertEquals(setOf("bulk-2", "single"), f.preferences.load(taylor).hiddenItems)
            // A later bulk gesture may override the earlier individual choice again.
            withContext(f.ui) { controller.setItems(listOf("bulk-1", "bulk-2"), false) }.join()
            assertEquals(setOf("bulk-1", "bulk-2", "single"), f.preferences.load(taylor).hiddenItems)
            withContext(f.ui) { controller.setItems(listOf("bulk-1", "bulk-2"), true) }.join()
            assertEquals(setOf("single"), f.preferences.load(taylor).hiddenItems)
        }
    }

    @Test fun failedReadCannotEraseOrReplaceExistingPreferences() = runBlocking {
        Fixture().use { f ->
            f.data[taylor] = "corrupt-settings"
            val controller = f.controller()
            f.restore(controller, taylor)
            withContext(f.ui) {
                assertNotNull(controller.state.loadError)
                controller.setFolder("folder", false)
                controller.setItem("item", false)
                controller.setItems(listOf("bulk"), false)
            }.join()
            assertEquals("corrupt-settings", f.data[taylor])
            assertEquals(0, f.writes.get())
        }
    }

    @Test fun queuedSaveSurvivesOwningScreenScopeCancellation() = runBlocking {
        Fixture().use { f ->
            val first = f.controller()
            f.restore(first, taylor)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            withContext(f.ui) { first.setFolder("saved-before-close", false) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try { f.scope.cancel() } finally { release.countDown() }
            val newScreenScope = CoroutineScope(SupervisorJob() + f.ui)
            try {
                val reopened = MapFolderVisibilityController(newScreenScope, f.preferences, f.preparation)
                withContext(f.ui) { reopened.restore(taylor) }?.join()
                withContext(f.ui) { assertTrue("saved-before-close" in reopened.state.hiddenFolders) }
            } finally { newScreenScope.cancel() }
        }
    }

    @Test fun mapSwitchDiscardsUnfinishedBulkResultFromOldSession() = runBlocking {
        Fixture().use { f ->
            val controller = f.controller()
            f.restore(controller, taylor)
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.preparationExecutor.submit { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val bulk = withContext(f.ui) { controller.setItems(listOf("old-item"), false) }
            val second = mapFolderVisibilityKey("caltopo.com", "second")!!
            val restore = try {
                withContext(f.ui) { controller.restore(second) }
            } finally { release.countDown() }
            bulk.join()
            restore?.join()
            withContext(f.ui) {
                assertEquals(second, controller.state.mapKey)
                assertTrue(controller.state.hiddenItems.isEmpty())
            }
            assertFalse(f.data.containsKey(second))
        }
    }
}
