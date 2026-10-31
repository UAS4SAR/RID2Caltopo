package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import android.location.Location
import org.ncssar.rid2caltopo.video.MapOfflinePrepRuntime
import org.ncssar.rid2caltopo.mapping.MappingWorkRuntime
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Calendar
import java.util.GregorianCalendar

class CaltopoMapTest {
    @Test fun automaticRefreshRequestsRespectTwentySecondMinimum() {
        assertEquals(20_000L, CaltopoMap.minimumMapPollDelayMs(1_000, 1_000))
        assertEquals(1L, CaltopoMap.minimumMapPollDelayMs(20_999, 1_000))
        assertEquals(0L, CaltopoMap.minimumMapPollDelayMs(21_000, 1_000))
        assertEquals(0L, CaltopoMap.minimumMapPollDelayMs(1_000, 0))
    }

    @Test fun fullArtifactReadsRequireInitialLoadOrManualReload() {
        assertTrue(CaltopoMap.fullArtifactRefreshRequired(0, false))
        assertFalse(CaltopoMap.fullArtifactRefreshRequired(1_000, false))
        assertTrue(CaltopoMap.fullArtifactRefreshRequired(1_000, true))
    }

    @Test fun selectingActiveMapAgainPreservesConnectionWithoutAnotherRead() {
        val map = CaltopoNode.MapNode("map-test", "Map Test", 0L)
        beginSelection(map)
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        lastMapSyncField.setLong(null, 1_000L)
        val generation = mapRefreshGenerationField.getLong(null)
        repeat(5) { CaltopoMap.OpenMap(map.copy(title = "Updated catalog title", updated = 999L)) }
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("openMap"))
        assertEquals(CaltopoMap.MapStatusListener.mapStatus.up, CaltopoMap.GetMapStatus())
        assertEquals(generation, mapRefreshGenerationField.getLong(null))
        assertEquals(1_000L, lastMapSyncField.getLong(null))
        assertEquals("folder-test", CaltopoMap.GetFolderId())
        assertEquals("archive-folder-test", CaltopoMap.GetArchiveFolderId())
        assertTrue(map === CaltopoMap.GetMapNode())
    }

    @Test fun duplicateSelectionWhileConnectingKeepsOriginalRequestAndCompletion() {
        val map = CaltopoNode.MapNode("map-test", "Map Test", 0L)
        beginSelection(map)
        val generation = mapRefreshGenerationField.getLong(null)
        repeat(5) { CaltopoMap.OpenMap(map.copy(updated = it.toLong())) }
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("openMap"))
        assertEquals(generation, mapRefreshGenerationField.getLong(null))
        assertEquals(CaltopoMap.MapStatusListener.mapStatus.connecting, CaltopoMap.GetMapStatus())
        invokeInitialMapResponse(successfulMapResponse())
        assertEquals(CaltopoMap.MapStatusListener.mapStatus.up, CaltopoMap.GetMapStatus())
        CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test fun switchingMapsCancelsTheObsoleteInitialRequest() {
        val map = CaltopoNode.MapNode("map-test", "Map Test", 0L)
        beginSelection(map)
        val pending = initialMapRequestField.get(null) as CaltopoOp
        val future = java.util.concurrent.FutureTask { pending }
        pending.asyncFuture = future
        CaltopoMap.OpenMap(map)
        assertFalse(future.isCancelled)
        assertTrue(pending === initialMapRequestField.get(null))
        CaltopoMap.OpenMap(map.copy(id = "different-map"))
        assertTrue(future.isCancelled)
        assertFalse(pending === initialMapRequestField.get(null))
        assertEquals(2, fixture.calTopoSessionGateway.countOperations("openMap"))
    }

    @Test fun sameMapWithChangedServerOrCredentialsStartsNewConnection() {
        val map = CaltopoNode.MapNode("map-test", "Map Test", 0L)
        val credentials = CaltopoCredentials("team", "key", "secret")
        sessionCredentialsField.set(null, credentials)
        sessionDomainField.set(null, "caltopo.com")
        beginSelection(map)
        credentials.credentialSecret = "rotated-secret"
        CaltopoMap.OpenMap(map)
        assertEquals(2, fixture.calTopoSessionGateway.countOperations("openMap"))
        sessionDomainField.set(null, "another-server.example")
        CaltopoMap.OpenMap(map)
        assertEquals(3, fixture.calTopoSessionGateway.countOperations("openMap"))
    }

    @Test fun differentMapAndPersonalGrantAreNotReused() {
        val map = CaltopoNode.MapNode("map-test", "Map Test", 0L, "grant-one")
        beginSelection(map)
        CaltopoMap.OpenMap(map.copy(title = "New title"))
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("openMap"))
        CaltopoMap.OpenMap(map.copy(personalSessionID = "grant-two"))
        assertEquals(2, fixture.calTopoSessionGateway.countOperations("openMap"))
        CaltopoMap.OpenMap(map.copy(id = "another-map", personalSessionID = "grant-two"))
        assertEquals(3, fixture.calTopoSessionGateway.countOperations("openMap"))
        CaltopoPersonalSession.activate(null, "")
    }

    @Test fun sameMapSelectionClosesBrowserWithoutConnectingSpinner() {
        val map = CaltopoNode.MapNode("map-test", "Map Test", 0L)
        beginSelection(map)
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        val model = org.ncssar.rid2caltopo.ui.R2CViewModel(SimpleTimer())
        try {
            model.onUIEvent(org.ncssar.rid2caltopo.ui.UIEvent.SwitchMapRequested)
            assertEquals(org.ncssar.rid2caltopo.ui.OverlayState.MapBrowser, model.overlay)
            model.onUIEvent(org.ncssar.rid2caltopo.ui.UIEvent.MapSelected(map))
            assertEquals(org.ncssar.rid2caltopo.ui.OverlayState.None, model.overlay)
            assertEquals(org.ncssar.rid2caltopo.ui.CaltopoConnectionState.MapSelected(map), model.connectionState)
            assertEquals(1, fixture.calTopoSessionGateway.countOperations("openMap"))
        } finally {
            org.ncssar.rid2caltopo.ui.R2CViewModel::class.java.getDeclaredMethod("onCleared")
                .apply { isAccessible = true }.invoke(model)
        }
    }

    @Test fun failedAndIncompleteMapResponsesEndTheConnectingState() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.connecting)
        val incomplete = CaltopoOp(null).apply {
            responseJson = JSONObject()
            setOperationIsDone(true)
        }
        invokeInitialMapResponse(incomplete)
        assertEquals(CaltopoMap.MapStatusListener.mapStatus.down, CaltopoMap.GetMapStatus())
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.connecting)
        val failed = CaltopoOp(null).apply {
            response = "Connection failed"
            responseCode = 403
            setOperationIsDone(false)
        }
        invokeInitialMapResponse(failed)
        assertEquals(CaltopoMap.MapStatusListener.mapStatus.down, CaltopoMap.GetMapStatus())
    }

    private fun beginSelection(map: CaltopoNode.MapNode) {
        mapNodeField.set(null, null)
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        CaltopoMap.OpenMap(map)
    }

    @Test fun initialMapConnectsWithoutWaitingForPollOrFlightRecovery() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        CaltopoRecoveryWork.execute {
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.connecting)
            val op = successfulMapResponse()
            invokeInitialMapResponse(op)
            assertEquals(CaltopoMap.MapStatusListener.mapStatus.up, CaltopoMap.GetMapStatus())
            assertEquals(1_000L, lastMapSyncField.getLong(null))
            assertTrue(fixture.calTopoSessionGateway.snapshotOperations().none { it.kind == "openMap" })
        } finally {
            release.countDown()
            CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertTrue(fixture.calTopoSessionGateway.snapshotOperations().none { it.kind == "openMap" })
    }

    @Test fun initialMapWaitsForBothFoldersInEitherCompletionOrder() {
        for (first in listOf("Track", "Archive")) {
            mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.connecting)
            folderIdField.set(null, null)
            archiveFolderIdField.set(null, null)
            invokeInitialMapResponse(successfulMapResponse())
            assertEquals(CaltopoMap.MapStatusListener.mapStatus.connecting, CaltopoMap.GetMapStatus())

            fun completeFolder(kind: String) {
                val op = CaltopoOp(null)
                op.responseJson = JSONObject().put("id", "$kind-folder")
                op.setOperationIsDone(true)
                CaltopoMap::class.java.getDeclaredMethod("Create${kind}DirFinished", CaltopoOp::class.java)
                    .apply { isAccessible = true }.invoke(null, op)
            }
            completeFolder(first)
            assertEquals(CaltopoMap.MapStatusListener.mapStatus.connecting, CaltopoMap.GetMapStatus())
            completeFolder(if (first == "Track") "Archive" else "Track")
            assertEquals(CaltopoMap.MapStatusListener.mapStatus.up, CaltopoMap.GetMapStatus())
            CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertTrue(fixture.calTopoSessionGateway.snapshotOperations().none { it.kind == "openMap" })
    }

    @Test fun reusedStartupSnapshotKeepsItsCursorForTheNextIncrementalPoll() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.connecting)
        val op = successfulMapResponse()
        op.responseJson.put("timestamp", 700L)
        invokeInitialMapResponse(op) // Connection request started later, at 1,000 ms.
        assertEquals(700L, lastMapSyncField.getLong(null))
        CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test fun staleInitialResponseCannotCompleteAnotherMapsConnection() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.connecting)
        mapRefreshGenerationField.setLong(null, 2L)
        invokeInitialMapResponse(successfulMapResponse())
        assertEquals(CaltopoMap.MapStatusListener.mapStatus.connecting, CaltopoMap.GetMapStatus())
        assertEquals(0L, lastMapSyncField.getLong(null))
    }

    @Test fun markerJournalRunsOffCallerAndDoesNotBlockMapUI() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        CaltopoMarkerWork.execute { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val location = Location("test").apply { latitude = 39.1; longitude = -121.1 }
        CaltopoMap.MyLocation = location
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        val file = java.io.File(java.nio.file.Files.createTempDirectory("marker-thread-test").toFile(), "journal.json")
        val threads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val observed = object : java.io.File(file.path) {
            override fun exists(): Boolean { threads.add(Thread.currentThread().name); return super.exists() }
        }
        CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }
            .set(null, DeviceMarkerJournal(observed))
        try {
            // This must return while the marker worker is deliberately blocked.
            invokeMarkerPublish(location)
            assertTrue(threads.isEmpty())
            assertEquals(0, fixture.calTopoSessionGateway.countOperations("addMarker"))
            release.countDown()
            drainMarkers()
            assertTrue(threads.isNotEmpty())
            assertTrue(threads.all { it == "caltopo-marker-journal" })
            assertEquals(1, fixture.calTopoSessionGateway.countOperations("addMarker"))
            assertTrue(file.isFile)
        } finally { release.countDown(); drainMarkers(); file.parentFile.deleteRecursively() }
    }

    @Test fun queuedMarkerCannotPublishAfterMapChanges() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        CaltopoMarkerWork.execute { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val location = Location("test").apply { latitude = 39.1; longitude = -121.1 }
        CaltopoMap.MyLocation = location
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        try {
            invokeMarkerPublish(location)
            mapRefreshGenerationField.setLong(null, mapRefreshGenerationField.getLong(null) + 1)
            mapNodeField.set(null, CaltopoNode.MapNode("other-map", "other", 0L))
            release.countDown()
            drainMarkers()
            assertEquals(0, fixture.calTopoSessionGateway.countOperations("addMarker"))
            assertFalse(fixture.calTopoSessionGateway.markerCleanupMaps.contains("other-map"))
        } finally { release.countDown(); drainMarkers() }
    }

    @Test fun corruptMarkerJournalPreventsPostWithoutResettingOwnership() {
        val file = java.io.File.createTempFile("marker-corrupt", ".json")
        file.writeText("broken")
        CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }
            .set(null, DeviceMarkerJournal(file))
        val location = Location("test").apply { latitude = 39.1; longitude = -121.1 }
        CaltopoMap.MyLocation = location
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        invokeMarkerPublish(location)
        drainMarkers()
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("addMarker"))
        assertEquals("broken", file.readText())
        file.delete()
    }

    @Test fun coldMarkerJournalCannotBlockLocationCallbacksWhileStoragePrepares() {
        val factoryField = CaltopoMap::class.java.getDeclaredField("markerJournalFactory").apply { isAccessible = true }
        val journalField = CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }
        val originalFactory = factoryField.get(null)
        val originalJournal = journalField.get(null)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        val file = java.io.File(java.nio.file.Files.createTempDirectory("cold-marker-journal").toFile(), "journal.json")
        val initializationThread = java.util.concurrent.atomic.AtomicReference<String>()
        factoryField.set(null, java.util.function.Supplier {
            initializationThread.set(Thread.currentThread().name)
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            DeviceMarkerJournal(file)
        })
        journalField.set(null, null)
        try {
            CaltopoMap::class.java.getDeclaredMethod("retryMarkerCleanup").apply { isAccessible = true }.invoke(null)
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            // This is the exact accessor where the tablet's main thread was blocked.
            caller.submit { CaltopoMap.GetDeviceLocation(); CaltopoMap.GetMyLocation(); CaltopoMap.GetMyLocationOverride() }
                .get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertEquals("caltopo-marker-journal", initializationThread.get())
        } finally {
            release.countDown()
            drainMarkers()
            caller.shutdownNow()
            factoryField.set(null, originalFactory)
            journalField.set(null, originalJournal)
            file.parentFile.deleteRecursively()
        }
    }

    private fun invokeMarkerPublish(location: Location) {
        CaltopoMap::class.java.getDeclaredMethod("publishMyDeviceMarkerIfPossible", Location::class.java)
            .apply { isAccessible = true }.invoke(null, location)
    }

    @Test fun currentMarkerIntentSurvivesCleanupQueuedAheadOfItsPublication() {
        val journal=CaltopoMap::class.java.getDeclaredField("markerJournal").apply{isAccessible=true}.get(null) as DeviceMarkerJournal
        val key=CaltopoClient.GetCaltopoDomainAndPort()+"/"+CaltopoMap.GetMapId()+"/marker-guid-test"
        journal.arm(CaltopoClient.GetCaltopoDomainAndPort(),CaltopoMap.GetMapId(),"marker-guid-test")
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        CaltopoMarkerWork.execute{entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS)}
        assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        try {
            val location=Location("test")
            CaltopoMap.MyLocation=location
            mapStatusField.set(null,CaltopoMap.MapStatusListener.mapStatus.up)
            CaltopoMap::class.java.getDeclaredMethod("retryMarkerCleanup").apply{isAccessible=true}.invoke(null)
            invokeMarkerPublish(location)
            release.countDown();drainMarkers()
            assertEquals(0,fixture.calTopoSessionGateway.countOperations("deleteMarker"))
            assertEquals(1,fixture.calTopoSessionGateway.countOperations("addMarker"))
            assertTrue(journal.entries().any{it.key()==key})
        } finally {release.countDown();drainMarkers()}
    }

    @Test fun publicationWaitsForInflightCleanupAndCoalescesToLatestLocation() {
        val journal=CaltopoMap::class.java.getDeclaredField("markerJournal").apply{isAccessible=true}.get(null) as DeviceMarkerJournal
        journal.arm(CaltopoClient.GetCaltopoDomainAndPort(),CaltopoMap.GetMapId(),"marker-guid-test")
        fixture.calTopoSessionGateway.holdMarkerDeletes=true
        CaltopoMap::class.java.getDeclaredMethod("retryMarkerCleanup").apply{isAccessible=true}.invoke(null)
        drainMarkers()
        assertEquals(1,fixture.calTopoSessionGateway.markerDeleteCallbacks.size)
        CaltopoMap.MyLocation=Location("test")
        mapStatusField.set(null,CaltopoMap.MapStatusListener.mapStatus.up)
        repeat(3){invokeMarkerPublish(Location("test"))}
        drainMarkers()
        assertEquals(0,fixture.calTopoSessionGateway.countOperations("addMarker"))
        fixture.calTopoSessionGateway.markerDeleteCallbacks.removeAt(0).accept(CaltopoOp(null).apply{setOperationIsDone(true)})
        drainMarkers()
        assertEquals(1,fixture.calTopoSessionGateway.countOperations("addMarker"))
        assertEquals(1,journal.entries().size)
    }

    private fun successfulMapResponse() = CaltopoOp(null).apply {
        responseJson = JSONObject().put("state", JSONObject().put("features", JSONArray()))
        setOperationIsDone(true)
    }

    private fun invokeInitialMapResponse(op: CaltopoOp) {
        CaltopoMap::class.java.getDeclaredMethod(
            "OpenMapFinished", CaltopoOp::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(null, op, 1_000L, 1L)
    }


    @Test
    fun archiveFolderName_separatesTrackFolderAndDate() {
        val date = GregorianCalendar(2024, Calendar.JULY, 24, 12, 0).time

        assertEquals("Drone Tracks 24Jul", CaltopoMap.archiveFolderName(" Drone Tracks ", date))
    }

    @Test fun personalMapWithoutTeamInitializationHasSafeFolderNames() {
        val date = GregorianCalendar(2024, Calendar.JULY, 24, 12, 0).time
        assertEquals("DroneTracks 24Jul", CaltopoMap.archiveFolderName(null, date))
        assertEquals("DroneTracks 24Jul", CaltopoMap.archiveFolderName("  ", date))
        assertEquals("My Tracks", CaltopoMap.normalizedTrackFolderName(" My Tracks "))
    }

    @Test fun mapStartupIsBlockedDuringExitButAllowedAfterReopen() {
        appExitRequestedField.setBoolean(null, true)
        assertFalse(CaltopoClient.CanStartMapSession())
        appExitRequestedField.setBoolean(null, false)
        assertTrue(CaltopoClient.CanStartMapSession())
    }

    @Test fun personalMapReopensAfterCompletedShutdownWithoutTeamVerification() {
        val folder = CaltopoMap::class.java.getDeclaredField("FolderName").apply { isAccessible = true }
        val savedFolder = folder.get(null)
        try {
            folder.set(null, null)
            mapNodeField.set(null, null)
            shutdownInProgressField.setBoolean(null, true)
            CaltopoMap.OpenMap(CaltopoNode.MapNode("personal-test", "test", 0L, "session-test"))
            assertFalse(shutdownInProgressField.getBoolean(null))
            assertTrue((folder.get(null) as String).isNotBlank())
            drainMarkers()
        val operations = fixture.calTopoSessionGateway.snapshotOperations()
            assertTrue(operations.any { it.kind == "openMap" })
            assertFalse(operations.any { it.kind == "verifyAccount" })
        } finally {
            folder.set(null, savedFolder)
            CaltopoPersonalSession.activate(null, "")
        }
    }

    @Test fun mapRefreshCallbackDoesNotWaitForFlightStorage() {
        val storageEntered = java.util.concurrent.CountDownLatch(1)
        val releaseStorage = java.util.concurrent.CountDownLatch(1)
        val callers = java.util.concurrent.Executors.newFixedThreadPool(2)
        val file = java.io.File(java.nio.file.Files.createTempDirectory("recovery-thread-test").toFile(), "awaiting.json")
        AwaitingMapFlights.useFileForTesting(file)
        CaltopoInterruptedTrackJournal.setFileForTesting(java.io.File(file.parentFile, "interrupted.json"))
        try {
            callers.submit {
                synchronized(AwaitingMapFlights) {
                    storageEntered.countDown()
                    releaseStorage.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            assertTrue(storageEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            mapRefreshInFlightField.setBoolean(null, true)
            // Exercise the same completion handler identified in the fatal crash.
            callers.submit { invokeSuccessfulMapRefresh() }.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(mapRefreshInFlightField.getBoolean(null))
            releaseStorage.countDown()
            CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(mapRefreshInFlightField.getBoolean(null))
        } finally {
            releaseStorage.countDown()
            CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            callers.shutdownNow()
            AwaitingMapFlights.useFileForTesting(null)
            CaltopoInterruptedTrackJournal.setFileForTesting(null)
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun queuedFlightRecoverySkipsStorageAfterMapGenerationChanges() {
        val workerEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWorker = java.util.concurrent.CountDownLatch(1)
        val file = java.io.File(java.nio.file.Files.createTempDirectory("stale-recovery-test").toFile(), "awaiting.json")
        AwaitingMapFlights.useFileForTesting(file)
        try {
            CaltopoRecoveryWork.execute {
                workerEntered.countDown()
                releaseWorker.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertTrue(workerEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            mapRefreshInFlightField.setBoolean(null, true)
            invokeSuccessfulMapRefresh()
            mapRefreshGenerationField.setLong(null, 2L)
            mapNodeField.set(null, CaltopoNode.MapNode("different-map", "Other Map", 0L))
            releaseWorker.countDown()
            CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            val loaded = AwaitingMapFlights::class.java.getDeclaredField("loaded").apply { isAccessible = true }
            assertFalse(loaded.getBoolean(null))
            // The old completion cannot clear the new map's in-flight flag either.
            assertTrue(mapRefreshInFlightField.getBoolean(null))
        } finally {
            releaseWorker.countDown()
            CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            AwaitingMapFlights.useFileForTesting(null)
            file.parentFile.deleteRecursively()
        }
    }

    private fun invokeSuccessfulMapRefresh() {
        val method = CaltopoMap::class.java.getDeclaredMethod(
            "UpdateMapFinished", CaltopoOp::class.java, Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType, String::class.java,
        ).apply { isAccessible = true }
        val op = CaltopoOp(null)
        op.responseJson = JSONObject().put("state", JSONObject().put("features", JSONArray()))
        op.setOperationIsDone(true)
        method.invoke(null, op, 1_000L, false, 1L, "map-test")
    }

    private lateinit var fixture: TestR2cRuntimeFactory.Fixture
    private lateinit var mapStatusField: Field
    private lateinit var mapSelectionField: Field
    private lateinit var initialMapRequestField: Field
    private lateinit var sessionCredentialsField: Field
    private lateinit var sessionDomainField: Field
    private lateinit var mapNodeField: Field
    private lateinit var folderIdField: Field
    private lateinit var archiveFolderIdField: Field
    private lateinit var myUuidField: Field
    private lateinit var resolvedMarkerIdField: Field
    private lateinit var shutdownInProgressField: Field
    private lateinit var disconnectInProgressField: Field
    private lateinit var currentRuntimeField: Field
    private lateinit var lastStandaloneScopeField: Field
    private lateinit var standaloneStartedField: Field
    private lateinit var standaloneEnabledForActiveFlightsField: Field
    private lateinit var clientStateField: Field
    private lateinit var lastWaypointTimestampField: Field
    private lateinit var appExitRequestedField: Field
    private lateinit var lastMapSyncField: Field
    private lateinit var lastMapPollStartedAtField: Field
    private lateinit var mapRefreshInFlightField: Field
    private lateinit var mapRefreshPendingField: Field
    private lateinit var fullMapRefreshPendingField: Field
    private lateinit var mapRefreshGenerationField: Field
    private lateinit var setMapStatusMethod: Method
    private lateinit var finishMapRefreshMethod: Method
    private lateinit var parseMapUpdateMethod: Method
    private lateinit var reconcileArtifactStoreMethod: Method
    private lateinit var resetArtifactStoreMethod: Method

    private lateinit var originalMapStatus: CaltopoMap.MapStatusListener.mapStatus
    private var originalMapSelection: Any? = null
    private var originalInitialMapRequest: Any? = null
    private var originalSessionCredentials: Any? = null
    private var originalSessionDomain: Any? = null
    private var originalMapNode: Any? = null
    private var originalFolderId: String? = null
    private var originalArchiveFolderId: String? = null
    private var originalMyUuid: String? = null
    private var originalResolvedMarkerId: String? = null
    private var originalShutdownInProgress: Boolean = false
    private var originalDisconnectInProgress: Boolean = false
    private var originalCurrentRuntime: Any? = null
    private var originalLastStandaloneScope: String? = null
    private var originalStandaloneStarted: Boolean = false
    private var originalStandaloneEnabledForActiveFlights: Any? = null
    private var originalClientState: Any? = null
    private var originalTrackerApiKey: String = ""
    private var originalTrackerUrlPfx: String = ""
    private var originalMyLocation: Location? = null
    private var originalLastWaypointTimestamp: Long = 0L
    private var originalAppExitRequested: Boolean = false
    private var originalLastMapSync: Long = 0L
    private var originalLastMapPollStartedAt: Long = 0L
    private var originalMapRefreshInFlight: Boolean = false
    private var originalMapRefreshPending: Boolean = false
    private var originalFullMapRefreshPending: Boolean = false
    private var originalMapRefreshGeneration: Long = 0L

    private fun drainMarkers() {
        repeat(3) { CaltopoMarkerWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS) }
    }

    @Before
    fun setUp() {
        CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
        drainMarkers()
        CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }.set(null, DeviceMarkerJournal(java.io.File(java.nio.file.Files.createTempDirectory("marker-map-test").toFile(), "journal.json")))
        for (name in listOf("activeMarkerKeys", "deletingMarkerKeys")) {
            (CaltopoMap::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as MutableSet<*>).clear()
        }
        (CaltopoMap::class.java.getDeclaredField("deferredMarkerPublications").apply{isAccessible=true}.get(null) as MutableMap<*,*>).clear()
        fixture = TestR2cRuntimeFactory.create("map-test")
        fixture.setAsDefaultRuntime()

        mapStatusField = CaltopoMap::class.java.getDeclaredField("MapStatus").apply { isAccessible = true }
        mapSelectionField = CaltopoMap::class.java.getDeclaredField("ActiveMapSelection").apply { isAccessible = true }
        initialMapRequestField = CaltopoMap::class.java.getDeclaredField("InitialMapRequest").apply { isAccessible = true }
        sessionCredentialsField = CaltopoMap::class.java.getDeclaredField("MyCaltopoCredentials").apply { isAccessible = true }
        sessionDomainField = CaltopoMap::class.java.getDeclaredField("DomainAndPort").apply { isAccessible = true }
        mapNodeField = CaltopoMap::class.java.getDeclaredField("MapNode").apply { isAccessible = true }
        folderIdField = CaltopoMap::class.java.getDeclaredField("FolderId").apply { isAccessible = true }
        archiveFolderIdField = CaltopoMap::class.java.getDeclaredField("ArchiveFolderId").apply { isAccessible = true }
        myUuidField = CaltopoMap::class.java.getDeclaredField("MyUUID").apply { isAccessible = true }
        resolvedMarkerIdField = CaltopoMap::class.java.getDeclaredField("ResolvedMyDeviceMarkerId").apply { isAccessible = true }
        shutdownInProgressField = CaltopoMap::class.java.getDeclaredField("ShutdownInProgress").apply { isAccessible = true }
        disconnectInProgressField = CaltopoMap::class.java.getDeclaredField("DisconnectInProgress").apply { isAccessible = true }
        currentRuntimeField = CaltopoMap::class.java.getDeclaredField("CurrentRuntime").apply { isAccessible = true }
        lastStandaloneScopeField = CaltopoMap::class.java.getDeclaredField("LastStandaloneCoordinationScopeId").apply { isAccessible = true }
        standaloneStartedField = CaltopoMap::class.java.getDeclaredField("StandaloneCoordinationStarted").apply { isAccessible = true }
        standaloneEnabledForActiveFlightsField = CaltopoMap::class.java.getDeclaredField("StandaloneCoordinationEnabledForActiveFlights").apply { isAccessible = true }
        clientStateField = CaltopoClient::class.java.getDeclaredField("Ccstate").apply { isAccessible = true }
        lastWaypointTimestampField = CtDroneSpec::class.java.getDeclaredField("MostRecentWaypointTimestampInMsec").apply { isAccessible = true }
        appExitRequestedField = CaltopoClient::class.java.getDeclaredField("AppExitRequested").apply { isAccessible = true }
        lastMapSyncField = CaltopoMap::class.java.getDeclaredField("LastMapSync").apply { isAccessible = true }
        lastMapPollStartedAtField = CaltopoMap::class.java.getDeclaredField("LastMapPollStartedAt").apply { isAccessible = true }
        mapRefreshInFlightField = CaltopoMap::class.java.getDeclaredField("MapRefreshInFlight").apply { isAccessible = true }
        mapRefreshPendingField = CaltopoMap::class.java.getDeclaredField("MapRefreshPending").apply { isAccessible = true }
        fullMapRefreshPendingField = CaltopoMap::class.java.getDeclaredField("FullMapRefreshPending").apply { isAccessible = true }
        mapRefreshGenerationField = CaltopoMap::class.java.getDeclaredField("MapRefreshGeneration").apply { isAccessible = true }
        setMapStatusMethod = CaltopoMap::class.java.getDeclaredMethod(
            "SetMapStatus",
            CaltopoMap.MapStatusListener.mapStatus::class.java,
            String::class.java
        ).apply { isAccessible = true }
        parseMapUpdateMethod = CaltopoMap::class.java.getDeclaredMethod(
            "ParseMapUpdate",
            JSONObject::class.java
        ).apply { isAccessible = true }
        reconcileArtifactStoreMethod = CaltopoMap::class.java.getDeclaredMethod(
            "ReconcileArtifactStore",
            JSONObject::class.java,
            String::class.java
        ).apply { isAccessible = true }
        resetArtifactStoreMethod = CaltopoMap::class.java.getDeclaredMethod(
            "resetArtifactStore",
            String::class.java
        ).apply { isAccessible = true }
        finishMapRefreshMethod = CaltopoMap::class.java.getDeclaredMethod(
            "FinishMapRefresh",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        originalMapStatus = mapStatusField.get(null) as CaltopoMap.MapStatusListener.mapStatus
        originalMapSelection = mapSelectionField.get(null)
        originalInitialMapRequest = initialMapRequestField.get(null)
        initialMapRequestField.set(null, null)
        originalSessionCredentials = sessionCredentialsField.get(null)
        originalSessionDomain = sessionDomainField.get(null)
        mapSelectionField.set(null, null)
        originalMapNode = mapNodeField.get(null)
        originalFolderId = folderIdField.get(null) as String?
        originalArchiveFolderId = archiveFolderIdField.get(null) as String?
        originalMyUuid = myUuidField.get(null) as String?
        originalResolvedMarkerId = resolvedMarkerIdField.get(null) as String?
        originalShutdownInProgress = shutdownInProgressField.getBoolean(null)
        originalDisconnectInProgress = disconnectInProgressField.getBoolean(null)
        originalCurrentRuntime = currentRuntimeField.get(null)
        originalLastStandaloneScope = lastStandaloneScopeField.get(null) as String?
        originalStandaloneStarted = standaloneStartedField.getBoolean(null)
        originalStandaloneEnabledForActiveFlights = standaloneEnabledForActiveFlightsField.get(null)
        originalClientState = clientStateField.get(null)
        clientStateField.set(null, ClientClassState())
        originalTrackerApiKey = CaltopoClient.GetTrackerApiKey()
        originalTrackerUrlPfx = CaltopoClient.GetTrackerUrlPfx()
        originalMyLocation = CaltopoMap.MyLocation
        originalLastWaypointTimestamp = lastWaypointTimestampField.getLong(null)
        originalAppExitRequested = appExitRequestedField.getBoolean(null)
        originalLastMapSync = lastMapSyncField.getLong(null)
        originalLastMapPollStartedAt = lastMapPollStartedAtField.getLong(null)
        lastMapPollStartedAtField.setLong(null, 0L)
        originalMapRefreshInFlight = mapRefreshInFlightField.getBoolean(null)
        originalMapRefreshPending = mapRefreshPendingField.getBoolean(null)
        originalFullMapRefreshPending = fullMapRefreshPendingField.getBoolean(null)
        originalMapRefreshGeneration = mapRefreshGenerationField.getLong(null)

        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        mapNodeField.set(null, CaltopoNode.MapNode("map-test", "Map Test", 0L))
        folderIdField.set(null, "folder-test")
        archiveFolderIdField.set(null, "archive-folder-test")
        myUuidField.set(null, "marker-guid-test")
        resolvedMarkerIdField.set(null, "marker-guid-test")
        shutdownInProgressField.setBoolean(null, false)
        disconnectInProgressField.setBoolean(null, false)
        currentRuntimeField.set(null, fixture.runtime)
        lastStandaloneScopeField.set(null, "")
        standaloneStartedField.setBoolean(null, false)
        standaloneEnabledForActiveFlightsField.set(null, null)
        appExitRequestedField.setBoolean(null, false)
        lastMapSyncField.setLong(null, 0L)
        mapRefreshInFlightField.setBoolean(null, false)
        mapRefreshPendingField.setBoolean(null, false)
        fullMapRefreshPendingField.setBoolean(null, false)
        mapRefreshGenerationField.setLong(null, 1L)
        resetArtifactStoreMethod.invoke(null, "CaltopoMapTest.setUp")
        MapOfflinePrepRuntime.resetForTesting()
        MappingWorkRuntime.resetForTesting()
        CaltopoMap.resetAutoQuitRelocationForTesting()
    }

    @After
    fun tearDown() {
        drainMarkers()
        // Recovery callbacks also finish map refreshes. Let them complete before
        // restoring shared map fields or a later test can receive this completion.
        CaltopoRecoveryWork.worker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
        for (name in listOf("MapCheckerDelay", "ProfileExpiryPoll", "InitialMarkerPublishDelay", "ServerRetryDelay")) {
            (CaltopoMap::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as DelayedExec).stop()
        }
        mapStatusField.set(null, originalMapStatus)
        mapSelectionField.set(null, originalMapSelection)
        initialMapRequestField.set(null, originalInitialMapRequest)
        sessionCredentialsField.set(null, originalSessionCredentials)
        sessionDomainField.set(null, originalSessionDomain)
        mapNodeField.set(null, originalMapNode)
        folderIdField.set(null, originalFolderId)
        archiveFolderIdField.set(null, originalArchiveFolderId)
        myUuidField.set(null, originalMyUuid)
        resolvedMarkerIdField.set(null, originalResolvedMarkerId)
        shutdownInProgressField.setBoolean(null, originalShutdownInProgress)
        disconnectInProgressField.setBoolean(null, originalDisconnectInProgress)
        currentRuntimeField.set(null, originalCurrentRuntime)
        lastStandaloneScopeField.set(null, originalLastStandaloneScope)
        standaloneStartedField.setBoolean(null, originalStandaloneStarted)
        standaloneEnabledForActiveFlightsField.set(null, originalStandaloneEnabledForActiveFlights)
        CaltopoMap.MyLocation = originalMyLocation
        CaltopoClient.SetTrackerApiKey(originalTrackerApiKey)
        CaltopoClient.SetTrackerUrlPfx(originalTrackerUrlPfx)
        lastWaypointTimestampField.setLong(null, originalLastWaypointTimestamp)
        appExitRequestedField.setBoolean(null, originalAppExitRequested)
        lastMapSyncField.setLong(null, originalLastMapSync)
        lastMapPollStartedAtField.setLong(null, originalLastMapPollStartedAt)
        mapRefreshInFlightField.setBoolean(null, originalMapRefreshInFlight)
        mapRefreshPendingField.setBoolean(null, originalMapRefreshPending)
        fullMapRefreshPendingField.setBoolean(null, originalFullMapRefreshPending)
        mapRefreshGenerationField.setLong(null, originalMapRefreshGeneration)
        resetArtifactStoreMethod.invoke(null, "CaltopoMapTest.tearDown")
        MapOfflinePrepRuntime.resetForTesting()
        MappingWorkRuntime.resetForTesting()
        CaltopoMap.resetAutoQuitRelocationForTesting()
        clientStateField.set(null, originalClientState)
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun startupCleanupDoesNotDeleteAnUnownedClueMarker() {
        val domain = CaltopoClient.GetCaltopoDomainAndPort()
        val clueId = "833a8140-2a8b-3b23-8876-c3af62524ef0"
        val file = java.io.File(java.nio.file.Files.createTempDirectory("clue-marker-journal").toFile(), "journal.json")
        file.writeText("""[{"domain":"$domain","map":"4J0LF02","marker":"$clueId","revision":"rev-1"}]""")
        CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }
            .set(null, DeviceMarkerJournal(file))
        try {
            CaltopoMap::class.java.getDeclaredMethod("retryMarkerCleanup").apply { isAccessible = true }.invoke(null)
            drainMarkers()
            assertEquals(0, fixture.calTopoSessionGateway.countOperations("deleteMarker"))
            val journal = CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }.get(null) as DeviceMarkerJournal
            assertTrue(journal.entries().none { it.marker == clueId })
        } finally { file.parentFile.deleteRecursively() }
    }

    @Test
    fun deviceMarkerResponseThatNamesAClueIsNotOwned() {
        val device = "marker-guid-test"
        val clue = JSONObject()
            .put("id", "833a8140-2a8b-3b23-8876-c3af62524ef0")
            .put("properties", JSONObject().put("class", "Marker").put("title", "c").put("marker-symbol", "Drone"))
        assertEquals("", CaltopoMap.ownedDeviceMarkerId(clue, device))
        val ours = JSONObject()
            .put("id", device)
            .put("properties", JSONObject().put("r2c-guid", device).put("title", "R2C: tablet"))
        assertEquals(device, CaltopoMap.ownedDeviceMarkerId(ours, device))
        val renamed = JSONObject()
            .put("id", "server-assigned-id")
            .put("properties", JSONObject().put("r2c-guid", device))
        assertEquals("server-assigned-id", CaltopoMap.ownedDeviceMarkerId(renamed, device))
    }

    @Test
    fun startupCleanupDoesNotDeleteAnOwnedIdThatIsNotThisDevice() {
        val domain = CaltopoClient.GetCaltopoDomainAndPort()
        val journal = CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }.get(null) as DeviceMarkerJournal
        journal.arm(domain, "4J0LF02", "other-device-marker", "other-device")
        CaltopoMap::class.java.getDeclaredMethod("retryMarkerCleanup").apply { isAccessible = true }.invoke(null)
        drainMarkers()
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("deleteMarker"))
        assertTrue(journal.entries().none { it.marker == "other-device-marker" })
    }

    @Test
    fun startupCleanupDeletesAProvenDuplicateOfThisDevice() {
        val domain = CaltopoClient.GetCaltopoDomainAndPort()
        val journal = CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }.get(null) as DeviceMarkerJournal
        journal.arm(domain, "4J0LF02", "server-assigned-id", "marker-guid-test")
        CaltopoMap::class.java.getDeclaredMethod("retryMarkerCleanup").apply { isAccessible = true }.invoke(null)
        drainMarkers()
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("deleteMarker"))
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun startupCleanupDoesNotDeleteAClueLinkIdEvenWhenTheJournalNamesThisDevice() {
        val domain = CaltopoClient.GetCaltopoDomainAndPort()
        val clueId = "34ebccc2-1111-4111-8111-111111111111"
        val linkId = java.util.UUID.nameUUIDFromBytes((clueId + ":link").toByteArray(java.nio.charset.StandardCharsets.UTF_8)).toString()
        val journal = CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }.get(null) as DeviceMarkerJournal
        journal.arm(domain, "4J0LF02", linkId, "marker-guid-test")
        val supplierField = CaltopoMap::class.java.getDeclaredField("clueCleanupIds").apply { isAccessible = true }
        val original = supplierField.get(null)
        supplierField.set(null, java.util.function.Supplier {
            CaltopoMap.ClueCleanupIds(true, listOf(clueId))
        })
        try {
            CaltopoMap::class.java.getDeclaredMethod("retryMarkerCleanup").apply { isAccessible = true }.invoke(null)
            drainMarkers()
            assertEquals(0, fixture.calTopoSessionGateway.countOperations("deleteMarker"))
            assertTrue(CaltopoMap.clueDerivedMarkerId(linkId, clueId))
            assertTrue(journal.entries().none { it.marker == linkId })
        } finally { supplierField.set(null, original) }
    }

    @Test
    fun interruptedMarkerCleanupRetriesOriginalMapAfterFailure() {
        val journal = CaltopoMap::class.java.getDeclaredField("markerJournal").apply { isAccessible = true }.get(null) as DeviceMarkerJournal
        journal.arm(CaltopoClient.GetCaltopoDomainAndPort(), "previous-map", "marker-guid-test", "marker-guid-test")
        val retry = CaltopoMap::class.java.getDeclaredMethod("retryMarkerCleanup").apply { isAccessible = true }
        fixture.calTopoSessionGateway.markerDeleteSucceeds = false
        retry.invoke(null)
        drainMarkers()
        assertEquals(1, journal.entries().size)
        fixture.calTopoSessionGateway.markerDeleteSucceeds = true
        retry.invoke(null)
        drainMarkers()
        assertTrue(journal.entries().isEmpty())
        assertEquals(listOf("previous-map", "previous-map"), fixture.calTopoSessionGateway.markerCleanupMaps)
    }

    @Test
    fun resetMapConnection_deletesMyDeviceMarker() {
        CaltopoMap.ResetMapConnection(1_000L)

        drainMarkers()
        val operations = fixture.calTopoSessionGateway.snapshotOperations()
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("deleteMarker"))
        val deleteIndex = operations.indexOfFirst { it.kind == "deleteMarker" }
        assertTrue(operations.toString(), deleteIndex >= 0)
    }

    @Test
    fun connectedMap_peerCoordinationUsesStableMapIdInsteadOfDisplayTitle() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.connecting)

        setMapStatusMethod.invoke(null, CaltopoMap.MapStatusListener.mapStatus.up, null)

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertEquals("map-test", peerCoordinator.startedMapId)
    }

    @Test
    fun automaticRefreshUsesOldCursorWithoutFullDownload() {
        lastMapSyncField.setLong(null, 1_000L)
        CaltopoMap.RequestMapRefreshNow()
        val request = fixture.calTopoSessionGateway.snapshotOperations().first { it.kind == "openMap" }
        assertEquals(1_000L, request.payload?.optLong("lastSyncTimestamp"))
    }

    @Test
    fun manualReloadRequestsFullDownloadDespiteExistingCursor() {
        lastMapSyncField.setLong(null, 1_000L)
        CaltopoMap.ReloadMapArtifactsNow(null)
        val request = fixture.calTopoSessionGateway.snapshotOperations().first { it.kind == "openMap" }
        assertEquals(0L, request.payload?.optLong("lastSyncTimestamp"))
    }

    @Test
    fun archiveFeature_requestsIncrementalMapRefreshAfterLiveTrackDeleteFinishes() {
        lastMapSyncField.setLong(null, 12_345L)
        val feature = JSONObject()
            .put("id", "track-archive-refresh")
            .put(
                "properties",
                JSONObject()
                    .put("class", "LiveTrack")
                    .put("title", "RID_120000Jun12")
                    .put("folderId", "folder-test")
            )

        CaltopoMap.ArchiveFeature(feature, "LiveTrack", 1_000L, 0L)

        drainMarkers()
        val operations = fixture.calTopoSessionGateway.snapshotOperations()
        val editIndex = operations.indexOfFirst { it.kind == "editObject" }
        val deleteIndex = operations.indexOfFirst { it.kind == "deleteLiveTrack" }
        val refreshIndex = operations.indexOfFirst { it.kind == "openMap" }
        assertTrue(operations.toString(), editIndex >= 0)
        assertTrue(operations.toString(), deleteIndex > editIndex)
        assertTrue(operations.toString(), refreshIndex > deleteIndex)
        assertEquals(12_345L, operations[refreshIndex].payload?.optLong("lastSyncTimestamp"))
    }

    @Test
    fun artifactDeltasRenameMoveAndDeleteWithoutReloadingTheMap() {
        val initialState = JSONObject().put(
            "features",
            JSONArray()
                .put(JSONObject().put("id", "folder-a").put(
                    "properties",
                    JSONObject().put("class", "Folder").put("title", "Division A")
                ))
                .put(JSONObject().put("id", "marker-a").put(
                    "geometry",
                    JSONObject().put("type", "Point").put("coordinates", JSONArray().put(-121.1).put(39.1))
                ).put(
                    "properties",
                    JSONObject().put("class", "Marker").put("title", "Clue").put("folderId", "folder-a")
                ))
        )
        parseMapUpdateMethod.invoke(null, initialState)

        val changedState = JSONObject().put(
            "features",
            JSONArray()
                .put(JSONObject().put("id", "folder-a").put(
                    "properties",
                    JSONObject().put("class", "Folder").put("title", "Division Alpha")
                ))
                .put(JSONObject().put("id", "folder-b").put(
                    "properties",
                    JSONObject().put("class", "Folder").put("title", "Division B")
                ))
                .put(JSONObject().put("id", "marker-a").put(
                    "geometry",
                    JSONObject().put("type", "Point").put("coordinates", JSONArray().put(-121.1).put(39.1))
                ).put(
                    "properties",
                    JSONObject().put("class", "Marker").put("title", "Clue").put("folderId", "folder-b")
                ))
        )
        parseMapUpdateMethod.invoke(null, changedState)

        val changedFeatures = CaltopoMap.GetArtifactFeatureSnapshot().associateBy { it.getString("id") }
        assertEquals("Division Alpha", changedFeatures.getValue("folder-a").getJSONObject("properties").getString("title"))
        assertEquals("folder-b", changedFeatures.getValue("marker-a").getJSONObject("properties").getString("folderId"))

        parseMapUpdateMethod.invoke(
            null,
            JSONObject().put("features", JSONArray().put(JSONObject().put("id", "folder-a").put("deleted", true)))
        )
        assertFalse(CaltopoMap.GetArtifactFeatureSnapshot().any { it.optString("id") == "folder-a" })

        reconcileArtifactStoreMethod.invoke(
            null,
            JSONObject().put("features", JSONArray().put(changedFeatures.getValue("folder-b"))),
            "test-reconcile"
        )
        val reconciledIds = CaltopoMap.GetArtifactFeatureSnapshot().map { it.getString("id") }.toSet()
        assertEquals(setOf("folder-b"), reconciledIds)
    }

    @Test
    fun failedArtifactRefreshDoesNotAdvanceTheSuccessfulCursor() {
        shutdownInProgressField.setBoolean(null, true)
        lastMapSyncField.setLong(null, 12_345L)
        mapRefreshInFlightField.setBoolean(null, true)

        finishMapRefreshMethod.invoke(null, false, false, 20_000L, 1L)

        assertEquals(12_345L, lastMapSyncField.getLong(null))
        assertFalse(mapRefreshInFlightField.getBoolean(null))

        mapRefreshInFlightField.setBoolean(null, true)
        finishMapRefreshMethod.invoke(null, true, false, 20_000L, 1L)
        assertEquals(20_000L, lastMapSyncField.getLong(null))
    }

    @Test
    fun resetMapConnection_shutsDownPendingLiveTrackWithoutLiveTrackId() {
        val drone = CtDroneSpec("RID-PENDING-RESET")
        setDroneTrackLabel(drone, "RID-PENDING-RESET_123000Apr28")
        val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        liveTrack.publishDirect(39.2, -121.2, 501L, 2_000L)

        CaltopoMap.ResetMapConnection(0L)

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertEquals(1, peerCoordinator.countEvents("onDroneLost"))
        assertEquals(0, queuedPointCount(liveTrack))
        assertFalse(liveTrack.isActive)
    }

    @Test
    fun updateMyLocation_ignoresLegacyStandaloneOptIn() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)

        val location = Location("test").apply {
            latitude = 39.153061
            longitude = -121.132946
            accuracy = 5.0f
        }

        CaltopoMap.UpdateMyLocation(location)

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertFalse(peerCoordinator.isStarted())
    }

    @Test
    fun updateMyLocation_doesNotStartStandaloneTrackerWhenToggleOff() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)

        val location = Location("test").apply {
            latitude = 39.153061
            longitude = -121.132946
            accuracy = 5.0f
        }

        CaltopoMap.UpdateMyLocation(location)

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertFalse(peerCoordinator.isStarted())
    }

    @Test
    fun standaloneCachedLocationDoesNotStartCoordination() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)
        CaltopoMap.MyLocation = Location("cached").apply {
            latitude = 39.153062
            longitude = -121.132960
            accuracy = 8.0f
        }

        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertFalse(peerCoordinator.isStarted())
        // Location remains cached locally; the coordinator transport is not started.
        assertEquals(1, peerCoordinator.countEvents("updateMyPosition"))
    }

    @Test
    fun standaloneFlightRemainsIndependentAcrossLegacySettingChanges() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)
        CaltopoMap.MyLocation = Location("cached").apply {
            latitude = 39.153062
            longitude = -121.132960
            accuracy = 8.0f
        }
        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()
        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertFalse(peerCoordinator.isStarted())
        val drone = activateDrone("RIDTOGGLE1")

        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)

        assertFalse(peerCoordinator.isStarted())

        drone.reset()

        assertFalse(peerCoordinator.isStarted())
    }

    @Test
    fun legacyOptInCannotStartCoordinationDuringOrAfterStandaloneFlight() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)
        val drone = activateDrone("RIDTOGGLE2")
        CaltopoMap.MyLocation = Location("cached").apply {
            latitude = 39.153062
            longitude = -121.132960
            accuracy = 8.0f
        }

        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)
        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertFalse(peerCoordinator.isStarted())

        drone.reset()
        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()

        assertFalse(peerCoordinator.isStarted())
    }

    @Test
    fun ensureStandaloneTrackerCoordinationStarted_updatesPositionWithoutTrackerCredentials() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("")
        CaltopoClient.SetTrackerUrlPfx("")
        CaltopoMap.MyLocation = Location("cached").apply {
            latitude = 39.153062
            longitude = -121.132960
            accuracy = 8.0f
        }

        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertTrue(!peerCoordinator.isStarted())
        assertEquals(1, peerCoordinator.countEvents("updateMyPosition"))
    }

    @Test
    fun updateMyLocation_quitsAfterQuietAccurateTabletRelocation() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)

        assertTrue(CaltopoMap.isAutoQuitAfterRelocationEligibleForTesting(5.0f, nowMs))
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)

        assertEquals(0, quitCount)
        assertTrue(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(1, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun updateMyLocation_doesNotQuitWhenGpsAccuracyIsTooPoor() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 8.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 8.0f)

        assertEquals(0, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun updateMyLocation_doesNotQuitWhenDroneTelemetryWasRecent() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS + 1L)

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(0, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun screenOffFlightProtection_preservesAnchorUntilRidIsQuiet() {
        var nowMs = 1_000_000_000L
        var disconnectCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { disconnectCount++ }
        setLastWaypointTimestamp(nowMs - 1L)
        CaltopoMap.MyLocation = Location("screen-off").apply {
            latitude = 39.153000
            longitude = -121.132000
            accuracy = 5.0f
        }

        CaltopoMap.BeginIncidentDisplayInactive()
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(0, disconnectCount)
        assertTrue(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())

        nowMs += FIVE_MINUTES_MS + 1L
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(1, disconnectCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun updateMyLocation_doesNotQuitWhenDroneIsActive() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)
        activateDrone("RID-AUTOQUIT-ACTIVE")

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(0, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun updateMyLocation_doesNotQuitWhileOfflineMapDownloadIsActive() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)
        MapOfflinePrepRuntime.begin { _ -> }

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(0, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test fun nativeMappingConnectionPreventsInactiveMapDisconnectAndRelocationExit() {
        val nowMs=1_000_000_000L;var quitCount=0
        CaltopoMap.setTimeSourceForTesting{nowMs}
        CaltopoMap.setQuitHandlerForTesting{quitCount++}
        setLastWaypointTimestamp(nowMs-FIVE_MINUTES_MS-1L)
        val work=MappingWorkRuntime.begin{}
        try {
            assertFalse(CaltopoMap.DisconnectIncidentMapIfInactive("display inactive"))
            CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000,-121.132000,5.0f)
            CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200,-121.132000,5.0f)
            assertEquals(0,quitCount)
            assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
        }finally{MappingWorkRuntime.finish(work)}
    }

    @Test fun completedMappingRestartsIncidentMapQuietPeriod() {
        val work=MappingWorkRuntime.begin{}
        MappingWorkRuntime.finish(work)
        val ended=MappingWorkRuntime.snapshot().lastActivityAtMsec
        setLastWaypointTimestamp(0L)
        CaltopoMap.setTimeSourceForTesting{ended+FIVE_MINUTES_MS-1L}
        assertFalse(CaltopoMap.DisconnectIncidentMapIfInactive("display inactive"))
    }

    @Test
    fun updateMyLocation_doesNotDisconnectWhileCoordinationHasOperationalWork() {
        val nowMs = 1_000_000_000L
        var disconnectCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { disconnectCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)
        (fixture.peerCoordinator as FakePeerCoordinator)
            .setOperationalActivityPreventingMapDisconnect(true)

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(0, disconnectCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun completedPreparationRestartsIncidentMapQuietPeriod() {
        val prep = org.ncssar.rid2caltopo.video.MapOfflinePrepRuntime
        prep.resetForTesting()
        prep.begin(onCancel = {})
        prep.finish()
        val endedAt = prep.lastActivityAtMsec()
        setLastWaypointTimestamp(0L)
        try {
            CaltopoMap.setTimeSourceForTesting { endedAt + 9_000L }
            assertFalse(CaltopoMap.DisconnectIncidentMapIfInactive("display inactive"))
            CaltopoMap.setTimeSourceForTesting { endedAt + FIVE_MINUTES_MS - 1L }
            assertFalse(CaltopoMap.DisconnectIncidentMapIfInactive("display inactive"))
        } finally {
            prep.resetForTesting()
        }
    }

    @Test
    fun inactiveIncidentMapDisconnect_stopsCoordinationDespiteLegacyOptIn() {
        val nowMs = 1_000_000_000L
        CaltopoMap.setTimeSourceForTesting { nowMs }
        setLastWaypointTimestamp(0L)
        CaltopoClient.SetTrackerApiKey("tracker-key")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example/org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)
        CaltopoMap.MyLocation = Location("cached").apply {
            latitude = 39.153062
            longitude = -121.132960
            accuracy = 5.0f
        }

        assertTrue(CaltopoMap.DisconnectIncidentMapIfInactive("display inactive"))

        assertEquals(CaltopoMap.MapStatusListener.mapStatus.down, CaltopoMap.GetMapStatus())
        assertEquals(null, CaltopoMap.GetMapNode())
        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertFalse(peerCoordinator.isStarted())
    }

    @Test
    fun updateMyLocation_doesNotQuitWhenNotConnectedToMap() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(0, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun getIncident_usesSavedNameWhenNotConnectedToMap() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetIncident("Old Incident")

        assertEquals("Old Incident", CaltopoClient.GetIncident())
    }

    @Test
    fun getIncident_usesConnectedMapNameWithoutPersistingIt() {
        CaltopoClient.UpsertCaltopoProfile(
            CaltopoProfileRecord(
                "home-default",
                "Default",
                "HOME",
                CaltopoCredentials(),
                "caltopo.com",
                "Drone Tracks",
                "Old Incident",
                "1",
                "",
                "",
                false,
                0L,
                false,
                "",
                "",
                "",
                "",
                0L,
                ""
            ),
            true,
            false
        )
        mapNodeField.set(null, CaltopoNode.MapNode("map-test", "Search Alpha", 0L))
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)

        assertEquals("Search Alpha", CaltopoClient.GetIncident())
        assertEquals("Old Incident", CaltopoClient.GetCaltopoProfileById("home-default")!!.incident)

        mapNodeField.set(null, CaltopoNode.MapNode("map-test-2", "Search Bravo", 0L))

        assertEquals("Search Bravo", CaltopoClient.GetIncident())
        assertEquals("Old Incident", CaltopoClient.GetCaltopoProfileById("home-default")!!.incident)

        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)

        assertEquals("Old Incident", CaltopoClient.GetIncident())
    }

    private fun activateDrone(remoteId: String): CtDroneSpec {
        val drone = CtDroneSpec(remoteId)
        val trackLabelField = CtDroneSpec::class.java.getDeclaredField("trackLabel").apply {
            isAccessible = true
        }
        trackLabelField.set(drone, "${remoteId}_active")
        val state = clientStateField.get(null) as ClientClassState
        state.droneSpecTable[drone.remoteId] = drone
        CaltopoMap.OnDroneSpecStatusChanged(true)
        return drone
    }

    private fun setLastWaypointTimestamp(timestampMs: Long) {
        lastWaypointTimestampField.setLong(null, timestampMs)
    }

    private fun setDroneTrackLabel(drone: CtDroneSpec, trackLabel: String) {
        val mappedId = trackLabel.substringBefore('_')
        CtDroneSpec::class.java.getDeclaredField("mappedId").apply {
            isAccessible = true
            set(drone, mappedId)
        }
        CtDroneSpec::class.java.getDeclaredField("trackLabel").apply {
            isAccessible = true
            set(drone, trackLabel)
        }
    }

    private fun queuedPointCount(liveTrack: CaltopoLiveTrack): Int {
        val field = CaltopoLiveTrack::class.java.getDeclaredField("linePoints").apply {
            isAccessible = true
        }
        val points = field.get(liveTrack) as Collection<*>
        return points.size
    }

    companion object {
        private const val FIVE_MINUTES_MS = 5L * 60L * 1000L
    }
}
