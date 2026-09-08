package org.ncssar.rid2caltopo.video

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex

class MapPaneArtifactOverlayStateTest {
    @Test
    fun localDeviceMarkerOutline_buildsACompleteThreePixelHalo() {
        val offsets = markerOutlineOffsets(radiusPx = 3).toSet()

        assertTrue(0 to 0 in offsets)
        assertTrue(-3 to 0 in offsets)
        assertTrue(3 to 0 in offsets)
        assertTrue(0 to -3 in offsets)
        assertTrue(0 to 3 in offsets)
        assertEquals(29, offsets.size)
    }

    @Test
    fun mapAttribution_combinesSourcesIntoOneWidthResponsiveLabel() {
        assertEquals(
            "© OpenStreetMap contributors · DEM: USGS",
            mapPaneAttributionText(contoursEnabled = false)
        )
        assertEquals(
            "© OpenStreetMap contributors · DEM: USGS · Contours: USGS",
            mapPaneAttributionText(contoursEnabled = true)
        )
    }

    @Test
    fun demPlannerDefaultsToS1mAndPricesFinerProductsHigher() {
        val bounds = BoundingBox(39.75, -104.98, 39.73, -105.01)
        assertEquals(1, DemResolutionOption.values().first().meters)
        val count = estimateDemDownloadCount(bounds, DemResolutionOption.STANDARD_30M)
        assertEquals(2, count)
        assertTrue(
            conservativeDemBytes(count, DemResolutionOption.ENHANCED_10M) >
                conservativeDemBytes(count, DemResolutionOption.STANDARD_30M)
        )
        assertEquals(demPrefetchCellKey(39.001, -121.001), demPrefetchCellKey(39.049, -121.049))
    }

    @Test
    fun offlineCapacityIncludesExistingCacheAndRecommendsHeadroom() {
        val capacity = OfflinePrepCapacity(
            currentTileCacheBytes = 900_000_000L,
            estimatedTileBytes = 300_000_000L,
            estimatedDemBytes = 400_000_000L,
            maximumTileCacheBytes = 1_000_000_000L,
            availableVolumeBytes = 10_000_000_000L
        )

        assertEquals(1_200_000_000L, capacity.projectedTileCacheBytes)
        assertEquals(700_000_000L, capacity.estimatedDownloadBytes)
        assertTrue(capacity.exceedsCacheLimit)
        assertEquals(2_000_000_000L, capacity.recommendedMaximumBytes)
    }

    @Test
    fun offlineCapacitySeparatesCacheLimitFromVolumeCapacity() {
        val capacity = OfflinePrepCapacity(
            currentTileCacheBytes = 100_000_000L,
            estimatedTileBytes = 200_000_000L,
            estimatedDemBytes = 800_000_000L,
            maximumTileCacheBytes = 2_000_000_000L,
            availableVolumeBytes = 900_000_000L
        )

        assertTrue(!capacity.exceedsCacheLimit)
        assertTrue(capacity.exceedsAvailableVolume)
    }

    @Test
    fun offlineCapacityCanRecommendMoreThanLegacy64GbLimit() {
        assertEquals(110_000_000_000L, recommendedCacheMaximumBytes(100_000_000_000L))
    }

    @Test
    fun reasonableCacheMaximumLeavesFreeSpaceHeadroom() {
        assertEquals(
            869_000_000_000L,
            reasonableCacheMaximumBytes(
                currentCacheBytes = 14_000_000_000L,
                availableVolumeBytes = 900_000_000_000L
            )
        )
    }

    @Test
    fun tnmCatalogRetriesTransientGatewayFailure() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(504))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"items\":[],\"total\":0}"))
        server.start()
        try {
            val delays = mutableListOf<Long>()
            val page = fetchTnmCatalogPage(
                client = OkHttpClient(),
                request = Request.Builder().url(server.url("/api/v1/products")).build(),
                maxAttempts = 2,
                sleeper = delays::add
            )

            assertEquals(0, page.getJSONArray("items").length())
            assertEquals(2, server.requestCount)
            assertEquals(listOf(1_000L), delays)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun s1mCatalogSelectsOnlyTheTileContainingTheRequestedPoint() {
        val page = JSONObject().put("items", JSONArray()
            .put(JSONObject()
                .put("downloadURL", "https://example.test/outside.tif")
                .put("boundingBox", JSONObject()
                    .put("minX", -120.0).put("maxX", -119.9)
                    .put("minY", 38.0).put("maxY", 38.1)))
            .put(JSONObject()
                .put("downloadURL", "https://example.test/S1M_tile.tif")
                .put("sizeInBytes", 350_000_000L)
                .put("boundingBox", JSONObject()
                    .put("minX", -121.1).put("maxX", -121.0)
                    .put("minY", 39.2).put("maxY", 39.3))))

        val result = parseS1mDownloadForLocation(page, 39.2616, -121.0608)!!

        assertEquals("https://example.test/S1M_tile.tif", result.url)
        assertTrue(result.fileName.startsWith("R2C_S1M_"))
        assertEquals(350_000_000L, result.expectedBytes)
    }

    @Before
    fun setUp() {
        CaltopoClient.ResetPersistedClientState()
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun usgsContoursTileSource_exportsTransparentImageForTileBounds() {
        val tileIndex = MapTileIndex.getTileIndex(1, 1, 1)

        val url = UsgsContoursTileSource.getTileURLString(tileIndex)

        assertEquals(
            "https://carto.nationalmap.gov/arcgis/rest/services/contours/MapServer/export" +
                "?bbox=0.000000,-20037508.342789,20037508.342789,0.000000" +
                "&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&f=image",
            url
        )
    }

    @Test
    fun usgsContoursTileSource_allowsMapPaneDisplayZoomRequests() {
        assertEquals(MAP_DISPLAY_MAX_ZOOM.toInt(), UsgsContoursTileSource.maximumZoomLevel)
    }

    @Test
    fun replaceTileCompletionCallback_replacesStaleCallbacksWithRedrawCallback() {
        val callbacks = linkedSetOf<String?>(null, "stale-map")

        replaceTileCompletionCallback(callbacks, "active-map-redraw")

        assertEquals(listOf("active-map-redraw"), callbacks.toList())
    }

    @Test
    fun needsBaseTileProviderRestart_detectsLayerSourceMismatch() {
        assertEquals(
            true,
            needsBaseTileProviderRestart(
                currentSourceName = ArcGisWorldImageryTileSource.name(),
                desiredTileSource = OsmStandardTileSource
            )
        )
        assertEquals(
            false,
            needsBaseTileProviderRestart(
                currentSourceName = OsmStandardTileSource.name(),
                desiredTileSource = OsmStandardTileSource
            )
        )
    }

    @Test
    fun needsViewportTileProviderRestart_neverRestartsForZoomBookkeepingOnly() {
        assertEquals(
            false,
            needsViewportTileProviderRestart(
                previousTileZoom = 16,
                currentTileZoom = 18,
                baseSourceChanged = false
            )
        )
        assertEquals(
            false,
            needsViewportTileProviderRestart(
                previousTileZoom = 16,
                currentTileZoom = 18,
                baseSourceChanged = true
            )
        )
    }

    @Test
    fun mapArtifactRenderCache_preservesOverlayForSameMapRemount() {
        val cache = MapArtifactRenderCache()
        assertEquals(true, cache.resetIfMapChanged("map-a"))
        cache.replace(
            features = linkedMapOf("feature-1" to JSONObject("""{"id":"feature-1"}""")),
            overlayState = ArtifactOverlayState(totalFeatures = 1)
        )

        assertEquals(false, cache.resetIfMapChanged("map-a"))
        assertEquals(1, cachedArtifactOverlayState(cache.overlayState).totalFeatures)
        assertEquals(1, cache.featuresById.size)
    }

    @Test
    fun mapArtifactRenderCache_clearsOverlayWhenMapChanges() {
        val cache = MapArtifactRenderCache()
        cache.resetIfMapChanged("map-a")
        cache.replace(
            features = linkedMapOf("feature-1" to JSONObject("""{"id":"feature-1"}""")),
            overlayState = ArtifactOverlayState(totalFeatures = 1)
        )

        assertEquals(true, cache.resetIfMapChanged("map-b"))
        assertEquals(0, cachedArtifactOverlayState(cache.overlayState).totalFeatures)
        assertEquals(0, cache.featuresById.size)
    }

    @Test
    fun mapArtifactRenderCache_mergesDeltasThatArriveDuringHydration() {
        val cache = MapArtifactRenderCache()
        cache.resetIfMapChanged("map-a")
        val hydrationStartVersion = cache.featureVersion

        cache.putFeature("feature-2", JSONObject("""{"id":"feature-2"}"""))

        val merged = cache.mergedHydrationFeatures(
            hydratedFeatures = linkedMapOf("feature-1" to JSONObject("""{"id":"feature-1"}""")),
            hydrationStartVersion = hydrationStartVersion
        )

        assertEquals(listOf("feature-1", "feature-2"), merged.keys.toList())
    }

    @Test
    fun mapArtifactRenderCache_preservesDeletesThatArriveDuringHydration() {
        val cache = MapArtifactRenderCache()
        cache.resetIfMapChanged("map-a")
        cache.replace(
            features = linkedMapOf("feature-1" to JSONObject("""{"id":"feature-1"}""")),
            overlayState = ArtifactOverlayState(totalFeatures = 1)
        )
        val hydrationStartVersion = cache.featureVersion

        cache.removeFeature("feature-1")

        val merged = cache.mergedHydrationFeatures(
            hydratedFeatures = linkedMapOf("feature-1" to JSONObject("""{"id":"feature-1"}""")),
            hydrationStartVersion = hydrationStartVersion
        )

        assertEquals(emptyList<String>(), merged.keys.toList())
    }

    @Test
    fun movedDroneFolderMarkerIds_hidesClueMarkerMovedOutOfDroneFolder() {
        val previous = markerFeature("clue-guid", "Possible clue", "drone-folder")
            .apply { optJSONObject("properties")?.put("created", 1_000L) }
        val incoming = markerFeature("clue-guid", "Possible clue", "hidden-folder")
            .apply { optJSONObject("properties")?.put("created", 1_000L) }

        val movedIds = movedDroneFolderMarkerIds(
            previousFeatures = mapOf("clue-guid" to previous),
            incomingFeatures = mapOf("clue-guid" to incoming),
            expectedDroneFolderId = "drone-folder"
        )

        assertEquals(setOf("clue-guid"), movedIds)
    }

    @Test
    fun movedDroneFolderMarkerIds_hidesMarkerMovedOutOfDroneFolderWithoutClueMetadata() {
        val previous = markerFeature("clue-guid", "Possible clue", "drone-folder")
        val incoming = markerFeature("clue-guid", "Possible clue", "hidden-folder")

        val movedIds = movedDroneFolderMarkerIds(
            previousFeatures = mapOf("clue-guid" to previous),
            incomingFeatures = mapOf("clue-guid" to incoming),
            expectedDroneFolderId = "drone-folder"
        )

        assertEquals(setOf("clue-guid"), movedIds)
    }

    @Test
    fun movedDroneFolderMarkerIds_keepsClueMarkerStillInDroneFolder() {
        val previous = markerFeature("clue-guid", "Possible clue", "drone-folder")
            .apply { optJSONObject("properties")?.put("created", 1_000L) }
        val incoming = markerFeature("clue-guid", "Possible clue", "drone-folder")
            .apply { optJSONObject("properties")?.put("created", 1_000L) }

        val movedIds = movedDroneFolderMarkerIds(
            previousFeatures = mapOf("clue-guid" to previous),
            incomingFeatures = mapOf("clue-guid" to incoming),
            expectedDroneFolderId = "drone-folder"
        )

        assertEquals(emptySet<String>(), movedIds)
    }

    @Test
    fun movedDroneFolderMarkerIds_ignoresManagedR2cDeviceMarkerMoves() {
        val previous = markerFeature("device-marker", "R2C: Tablet", "drone-folder")
            .apply { optJSONObject("properties")?.put("r2c-guid", "tablet-guid") }
        val incoming = markerFeature("device-marker", "R2C: Tablet", "hidden-folder")
            .apply { optJSONObject("properties")?.put("r2c-guid", "tablet-guid") }

        val movedIds = movedDroneFolderMarkerIds(
            previousFeatures = mapOf("device-marker" to previous),
            incomingFeatures = mapOf("device-marker" to incoming),
            expectedDroneFolderId = "drone-folder"
        )

        assertEquals(emptySet<String>(), movedIds)
    }

    @Test
    fun visibleTileNetworkActive_staysEnabledUnlessOfflinePrepSuppressesNetwork() {
        assertEquals(true, visibleTileNetworkActive(suppressLiveMapNetwork = false))
        assertEquals(false, visibleTileNetworkActive(suppressLiveMapNetwork = true))
    }

    @Test
    fun offlineFirstForVisibleTiles_prioritizesDownloaderWhenNetworkIsAvailable() {
        assertEquals(false, offlineFirstForVisibleTiles(tileNetworkActive = true))
        assertEquals(true, offlineFirstForVisibleTiles(tileNetworkActive = false))
    }

    @Test
    fun offlinePrepTileSources_addsContoursWhenRequested() {
        assertEquals(
            listOf(ArcGisWorldImageryTileSource.name()),
            offlinePrepTileSources(BaseLayerOption.Imagery, includeContours = false).map { it.name() }
        )
        assertEquals(
            listOf(ArcGisWorldImageryTileSource.name(), UsgsContoursTileSource.name()),
            offlinePrepTileSources(BaseLayerOption.Imagery, includeContours = true).map { it.name() }
        )
    }

    @Test
    fun offlinePrepTileOperationCount_countsContourCompanionTiles() {
        assertEquals(12, offlinePrepTileOperationCount(baseTileCount = 12, includeContours = false))
        assertEquals(24, offlinePrepTileOperationCount(baseTileCount = 12, includeContours = true))
    }

    @Test
    fun isUsableMapViewportState_rejectsDefaultOriginViewport() {
        assertEquals(false, isUsableMapViewportState(0.0, 0.0, 14.0))
        assertEquals(false, isUsableMapViewportState(0.0000004, -0.0000004, 14.0))
        assertEquals(true, isUsableMapViewportState(38.9, -120.0, 14.0))
    }

    @Test
    fun orderedTileIndexesForOfflinePrep_prioritizesTabletMediumZoomTile() {
        val tablet = GeoPoint(38.9000, -120.0000)
        val bounds = BoundingBox(39.0000, -119.9000, 38.8000, -120.1000)
        val ordered = orderedTileIndexesForOfflinePrep(
            bounds = bounds,
            minZoom = 12,
            maxZoom = 16,
            tabletLocation = tablet
        )

        assertEquals(tileIndexForTestPoint(tablet, 14), ordered.first())
    }

    @Test
    fun orderedTileIndexesForOfflinePrep_prioritizesDronePathTilesBeforeAreaSweep() {
        val tablet = GeoPoint(38.9000, -120.0000)
        val droneStart = GeoPoint(38.8850, -120.0700)
        val droneEnd = GeoPoint(38.9500, -119.9300)
        val bounds = BoundingBox(39.0000, -119.9000, 38.8000, -120.1000)
        val ordered = orderedTileIndexesForOfflinePrep(
            bounds = bounds,
            minZoom = 12,
            maxZoom = 16,
            tabletLocation = tablet,
            dronePathPoints = listOf(droneStart, droneEnd)
        )

        assertEquals(tileIndexForTestPoint(tablet, 14), ordered[0])
        assertEquals(tileIndexForTestPoint(droneStart, 14), ordered[1])
        assertEquals(tileIndexForTestPoint(droneEnd, 14), ordered.take(8).last())
    }

    @Test
    fun liveTilePriorityRequests_usesVisibleMapZoomForTabletAndDrones() {
        val tablet = GeoPoint(38.9000, -120.0000)
        val drone = tilePriorityPoint(38.9300, -119.9600, headingDeg = 0.0)

        val requests = liveTilePriorityRequests(
            tabletLocation = tablet,
            dronePoints = listOf(drone),
            visibleZoom = 17
        )

        assertEquals(tileIndexForTestPoint(tablet, 17), requests[0].tileIndex)
        assertEquals(false, requests[0].requiresCurrentCached)
        assertEquals(tileIndexForTestPoint(GeoPoint(drone.lat, drone.lng), 17), requests[1].tileIndex)
        assertEquals(false, requests[1].requiresCurrentCached)
    }

    @Test
    fun liveTilePriorityRequests_prioritizesRidBroadcastCurrentTileThenHeadingAdjacentTile() {
        val drone = tilePriorityPoint(38.9300, -119.9600, headingDeg = 45.0)
        val zoom = 16

        val requests = liveTilePriorityRequests(
            tabletLocation = null,
            dronePoints = listOf(drone),
            visibleZoom = zoom
        )

        val current = tileIndexForTestPoint(GeoPoint(drone.lat, drone.lng), zoom)
        val headingAdjacent = adjacentTileIndexForTestTile(current, dx = 1, dy = -1)

        assertEquals(current, requests[0].tileIndex)
        assertEquals(current, requests[0].currentTileIndex)
        assertEquals(false, requests[0].requiresCurrentCached)
        assertEquals(headingAdjacent, requests[1].tileIndex)
        assertEquals(current, requests[1].currentTileIndex)
        assertEquals(true, requests[1].requiresCurrentCached)
    }

    @Test
    fun liveTilePriorityRequests_clampsDisplayZoomToFetchableSourceZoom() {
        val tablet = GeoPoint(38.9000, -120.0000)
        val drone = tilePriorityPoint(38.9300, -119.9600, headingDeg = 90.0)

        val requests = liveTilePriorityRequests(
            tabletLocation = tablet,
            dronePoints = listOf(drone),
            visibleZoom = 22
        )

        assertEquals(tileIndexForTestPoint(tablet, 19), requests[0].tileIndex)
        assertEquals(19, MapTileIndex.getZoom(requests[0].tileIndex))
        assertEquals(tileIndexForTestPoint(GeoPoint(drone.lat, drone.lng), 19), requests[1].tileIndex)
        assertEquals(19, MapTileIndex.getZoom(requests[1].tileIndex))
    }

    @Test
    fun droneTilePriorityRequests_ordersAllCurrentDroneTilesBeforeHeadingTiles() {
        val westDrone = tilePriorityPoint(38.9000, -120.0000, headingDeg = 90.0)
        val northDrone = tilePriorityPoint(38.9300, -119.9600, headingDeg = 0.0)
        val requests = droneTilePriorityRequests(listOf(westDrone, northDrone), 14)

        val westCurrent = tileIndexForTestPoint(GeoPoint(westDrone.lat, westDrone.lng), 14)
        val northCurrent = tileIndexForTestPoint(GeoPoint(northDrone.lat, northDrone.lng), 14)

        assertEquals(westCurrent, requests[0].tileIndex)
        assertEquals(false, requests[0].requiresCurrentCached)
        assertEquals(northCurrent, requests[1].tileIndex)
        assertEquals(false, requests[1].requiresCurrentCached)
        assertEquals(true, requests[2].requiresCurrentCached)
        assertEquals(westCurrent, requests[2].currentTileIndex)
        assertEquals(true, requests[3].requiresCurrentCached)
        assertEquals(northCurrent, requests[3].currentTileIndex)
    }

    @Test
    fun layoutDroneLabelGroups_movesOverlappingLabelsApart() {
        val labels = layoutDroneLabelGroups(
            labels = listOf(
                droneLabelLayoutInput("drone-a", anchorX = 100, anchorY = 100),
                droneLabelLayoutInput("drone-b", anchorX = 106, anchorY = 104)
            ),
            viewportWidth = 300,
            viewportHeight = 300
        )

        val a = labels.single { it.designator == "drone-a" }.bounds
        val b = labels.single { it.designator == "drone-b" }.bounds

        assertEquals(false, a.intersects(b))
    }

    @Test
    fun layoutDroneLabelGroups_addsLeaderLineWhenLabelIsDisplacedFromDefault() {
        val labels = layoutDroneLabelGroups(
            labels = listOf(
                droneLabelLayoutInput("drone-a", anchorX = 100, anchorY = 100),
                droneLabelLayoutInput("drone-b", anchorX = 106, anchorY = 104)
            ),
            viewportWidth = 300,
            viewportHeight = 300
        )

        val displaced = labels.single { it.designator == "drone-b" }

        assertEquals(true, displaced.leaderLine != null)
    }

    @Test
    fun fullFlightTrackMappedIds_includesOnlyEligibleConfirmedFlights() {
        val savedCurrentFlight = dronePoint("SAVEDFLIGHT", 38.9000, -120.0000)
        val visibleButUnconfirmed = dronePoint("UNCONFIRMED", 38.9100, -120.0100)
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            savedCurrentFlight.remoteId,
            "NCSSAR",
            "DJI Avata 360",
            "1SAR7",
            savedCurrentFlight.designator
        )

        val mappedIds = fullFlightTrackMappedIds(
            dronePoints = listOf(savedCurrentFlight, visibleButUnconfirmed),
            eligibleMappedIds = confirmedCurrentFlightMappedIds(listOf(savedCurrentFlight, visibleButUnconfirmed))
        )

        assertEquals(setOf("SAVEDFLIGHT"), mappedIds)
    }

    @Test
    fun fullFlightTrackMappedIds_includesConfirmedFlightAliasesAfterDesignatorChange() {
        val remoteId = "1581F6Z9C24BH0036EJL"
        val currentFlight = dronePoint(
            designator = "1sar7mn4pr",
            remoteId = remoteId,
            lat = 38.9000,
            lng = -120.0000
        )
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mini 4 Pro",
            "1SAR7",
            "1sar7DjMn4Pr"
        )

        val mappedIds = fullFlightTrackMappedIds(
            dronePoints = listOf(currentFlight),
            eligibleMappedIds = confirmedCurrentFlightMappedIds(listOf(currentFlight)),
            mappedIdsByRemoteId = mapOf(remoteId to setOf("1sar7DjMn4Pr", "1sar7mn4pr"))
        )

        assertEquals(setOf("1sar7DjMn4Pr", "1sar7mn4pr"), mappedIds)
    }

    @Test
    fun fullFlightTrackMappedIds_excludesAllDronesWhenNothingIsEligible() {
        val drone = dronePoint("TEAMDRONE", 38.9000, -120.0000)

        val mappedIds = fullFlightTrackMappedIds(
            dronePoints = listOf(drone),
            eligibleMappedIds = emptySet()
        )

        assertEquals(emptySet<String>(), mappedIds)
    }

    @Test
    fun allArtifactGeoPoints_and_boundingBoxFromPoints_span_allArtifactGeometry() {
        val state = ArtifactOverlayState(
            points = listOf(
                ArtifactPointSpec(
                    id = "marker-1",
                    lat = 38.2,
                    lng = -121.9,
                    title = "Marker",
                    markerSymbol = "point",
                    markerColor = "#ff0000"
                )
            ),
            lines = listOf(
                ArtifactLineSpec(
                    id = "line-1",
                    points = listOf(
                        GeoPoint(39.0, -120.5),
                        GeoPoint(37.8, -121.3)
                    ),
                    color = 0,
                    width = 2f,
                    title = "Line"
                )
            ),
            polygons = listOf(
                ArtifactPolygonSpec(
                    id = "polygon-1",
                    points = listOf(
                        GeoPoint(37.5, -122.5),
                        GeoPoint(37.7, -121.2),
                        GeoPoint(37.6, -122.0),
                        GeoPoint(37.5, -122.5)
                    ),
                    strokeColor = 0,
                    fillColor = 0,
                    strokeWidth = 2f,
                    title = "Polygon"
                )
            )
        )

        val bounds = boundingBoxFromPoints(allArtifactGeoPoints(state))

        assertEquals(7, allArtifactGeoPoints(state).size)
        assertEquals(39.0, bounds.latNorth, 0.0)
        assertEquals(-120.5, bounds.lonEast, 0.0)
        assertEquals(37.5, bounds.latSouth, 0.0)
        assertEquals(-122.5, bounds.lonWest, 0.0)
    }

    @Test
    fun buildArtifactOverlayState_ignoresItemsOutsideRepresentedFoldersAndHiddenBuiltIns() {
        val representedFolder = folderFeature("folder-visible", "Search Segment")
        val visibleLine = lineFeature("line-visible", "Represented Line", "folder-visible")
        val caltopoSystemLine = lineFeature("line-system", "System Line", "Lines & Polygons")
        val folderlessLine = lineFeature("line-folderless", "Folderless Line", "")

        val state = buildArtifactOverlayState(
            listOf(representedFolder, visibleLine, caltopoSystemLine, folderlessLine),
            hiddenFolderIds = setOf("__caltopo_lines_polygons__")
        )

        assertEquals(listOf("line-visible"), state.lines.map { it.id })
    }

    @Test
    fun buildArtifactOverlayState_stillHonorsHiddenRepresentedFolders() {
        val representedFolder = folderFeature("folder-hidden", "Archived Tracks")
        val hiddenLine = lineFeature("line-hidden", "Archived Line", "folder-hidden")

        val state = buildArtifactOverlayState(
            listOf(representedFolder, hiddenLine),
            hiddenFolderIds = setOf("folder-hidden")
        )

        assertEquals(0, state.lines.size)
    }

    @Test
    fun buildArtifactOverlayState_hidesMediaObjectWhenParentMarkerHidden() {
        val representedFolder = folderFeature("folder-hidden", "Hidden Clues")
        val marker = markerFeature("clue-marker", "Clue", "folder-hidden")
        val media = mediaObjectFeature("clue-media", "Clue photo", "clue-marker")

        val itemHiddenState = buildArtifactOverlayState(
            listOf(representedFolder, marker, media),
            hiddenItemIds = setOf("clue-marker")
        )
        val folderHiddenState = buildArtifactOverlayState(
            listOf(representedFolder, marker, media),
            hiddenFolderIds = setOf("folder-hidden")
        )

        assertEquals(emptyList<String>(), itemHiddenState.points.map { it.id })
        assertEquals(emptyList<String>(), folderHiddenState.points.map { it.id })
    }

    @Test
    fun buildArtifactHydrationResult_appliesServerHiddenFolderToOverlayImmediately() {
        val hiddenFolder = folderFeature("folder-hidden", "Hidden Clues")
            .also { it.getJSONObject("properties").put("visible", false) }
        val marker = markerFeature("clue-marker", "Clue", "folder-hidden")

        val result = buildArtifactHydrationResult(
            snapshot = listOf(hiddenFolder, marker),
            hiddenFolderIds = emptySet(),
            hiddenItemIds = emptySet()
        )

        assertEquals(false, result.folderDefaults.single().initiallyVisible)
        assertEquals(emptyList<String>(), result.overlayState.points.map { it.id })
    }

    @Test
    fun buildArtifactHydrationResult_operatorShowOverridesServerHiddenFolder() {
        val hiddenFolder = folderFeature("folder-hidden", "Hidden Clues")
            .also { it.getJSONObject("properties").put("visible", false) }
        val marker = markerFeature("clue-marker", "Clue", "folder-hidden")

        val result = buildArtifactHydrationResult(
            snapshot = listOf(hiddenFolder, marker),
            hiddenFolderIds = emptySet(),
            hiddenItemIds = emptySet(),
            folderVisibilityOverrides = mapOf("folder-hidden" to true)
        )

        assertEquals(listOf("clue-marker"), result.overlayState.points.map { it.id })
    }

    @Test
    fun resolveHiddenFolderIds_operatorChoicesWinWithoutDroppingOtherLocalHides() {
        val resolved = resolveHiddenFolderIds(
            localHiddenFolderIds = setOf("local-hidden", "operator-shown"),
            defaultHiddenFolderIds = setOf("server-hidden", "operator-shown"),
            operatorVisibilityOverrides = mapOf(
                "operator-shown" to true,
                "operator-hidden" to false
            )
        )

        assertEquals(
            setOf("local-hidden", "server-hidden", "operator-hidden"),
            resolved
        )
    }

    @Test
    fun folderHiddenAfterDefault_preservesOperatorShowAcrossRepeatedHiddenDefaults() {
        assertEquals(
            false,
            folderHiddenAfterDefault(
                currentlyHidden = false,
                defaultVisible = false,
                operatorVisibilityOverride = true
            )
        )
        assertEquals(
            true,
            folderHiddenAfterDefault(
                currentlyHidden = false,
                defaultVisible = false,
                operatorVisibilityOverride = null
            )
        )
    }

    @Test
    fun buildArtifactOverlayState_rendersFolderlessAssignments() {
        val assignment = assignmentPolygonFeature("assignment-aa", "AA")

        val state = buildArtifactOverlayState(listOf(assignment))

        assertEquals(listOf("assignment-aa"), state.polygons.map { it.id })
        assertEquals(0, state.lines.size)
        assertEquals(true, buildMapFolderUiStates(mapOf("assignment-aa" to assignment))
            .single().items.single().zoomableAssignment)
        assertEquals(state.polygons.single().points, artifactGeoPoints(assignment))
    }

    @Test
    fun assignmentDiagnosticsExplainGeometryVisibilityAndViewport() {
        val assignment = assignmentPolygonFeature("assignment-aa", "AA")
        val summary = assignmentDiagnosticSummary(
            features = mapOf("assignment-aa" to assignment),
            hiddenFolderIds = emptySet(),
            hiddenItemIds = emptySet(),
            viewport = boundingBoxFromPoints(artifactGeoPoints(assignment)),
        )

        assertTrue(summary.contains("assignments=1 geometryless=0"))
        assertTrue(summary.contains("assignment-aa points="))
        assertTrue(summary.contains("visible=true inViewport=true"))
    }

    @Test
    fun artifactOverlayRetainsDescriptionsForInspection() {
        val assignment = assignmentPolygonFeature("assignment-desc", "Area Delta").apply {
            getJSONObject("properties").put("description", "Search the north drainage")
        }

        val state = buildArtifactOverlayState(listOf(assignment))

        assertEquals("Area Delta", state.polygons.single().title)
        assertEquals("Search the north drainage", state.polygons.single().description)
    }

    @Test
    fun buildMapFolderUiStates_suppressesOnlyGeometrylessReplacementAssignments() {
        val oldAd = JSONObject()
            .put("id", "old-ad")
            .put("properties", JSONObject().put("class", "Assignment").put("title", "AD 105"))
        val currentAd = assignmentPolygonFeature("current-ad", "AD 105")
        val pendingAe = JSONObject()
            .put("id", "pending-ae")
            .put("properties", JSONObject().put("class", "Assignment").put("title", "AE 106"))

        val items = buildMapFolderUiStates(
            mapOf("old-ad" to oldAd, "current-ad" to currentAd, "pending-ae" to pendingAe)
        ).single().items

        assertEquals(listOf("current-ad", "pending-ae"), items.map { it.featureId }.sorted())
        assertEquals(true, items.single { it.featureId == "current-ad" }.zoomableAssignment)
    }

    @Test
    fun buildArtifactOverlayState_rendersUnnamedItemsInUnlistedFolders() {
        val line = lineFeature("assignment-shape", "", "folder-only-in-feature")

        val state = buildArtifactOverlayState(listOf(line))

        assertEquals(listOf("assignment-shape"), state.lines.map { it.id })
        assertEquals(listOf("Shape:assignment-shape"), state.lines.map { it.title })
    }

    @Test
    fun buildArtifactOverlayState_preservesFolderlessUnknownGeometry() {
        val line = unknownLineFeature("field-geometry", "")

        val state = buildArtifactOverlayState(listOf(line))

        assertEquals(listOf("field-geometry"), state.lines.map { it.id })
        assertEquals(listOf("FieldGeometry:field-geometry"), state.lines.map { it.title })
    }

    @Test
    fun buildMapFolderUiStates_listsUnnamedItemsInUnlistedFolders() {
        val line = lineFeature("assignment-shape", "", "folder-only-in-feature")

        val folders = buildMapFolderUiStates(mapOf("assignment-shape" to line))

        assertEquals(listOf("Unlisted Folder folder-o"), folders.map { it.title })
        assertEquals(true, folders.single().initiallyVisible)
        assertEquals(listOf("Shape:assignment-shape"), folders.single().items.map { it.title })
    }

    @Test
    fun buildMapFolderUiStates_listsFolderlessUnknownGeometry() {
        val line = unknownLineFeature("field-geometry", "")

        val folders = buildMapFolderUiStates(mapOf("field-geometry" to line))

        assertEquals(listOf("Other Map Items"), folders.map { it.title })
        assertEquals(true, folders.single().initiallyVisible)
        assertEquals(listOf("FieldGeometry:field-geometry"), folders.single().items.map { it.title })
    }

    @Test
    fun mapFolderUiDebugSummary_listsArchiveFolderVisibilityAndItems() {
        val archiveFolder = folderFeature("archive-folder", "Drone Tracks11Jun")
            .also { it.getJSONObject("properties").put("visible", false) }
        val archivedTrack = lineFeature("track-1", "1SAR34DjN2_113225Jun11", "archive-folder")
        val folders = buildMapFolderUiStates(
            mapOf(
                "archive-folder" to archiveFolder,
                "track-1" to archivedTrack
            )
        )

        val summary = mapFolderUiDebugSummary(
            folders = folders,
            hiddenFolderIds = setOf("archive-folder"),
            hiddenItemIds = emptySet()
        )

        assertEquals(
            "folders=1 | Drone Tracks11Jun id=archive-folder hidden=true defaultVisible=false items=1 hiddenItems=0 sample=[1SAR34DjN2_113225Jun11(track-1)]",
            summary
        )
    }

    @Test
    fun buildArtifactOverlayState_honorsHiddenAssignmentsGroup() {
        val assignment = assignmentPolygonFeature("assignment-aa", "AA")

        val state = buildArtifactOverlayState(
            listOf(assignment),
            hiddenFolderIds = setOf("__caltopo_assignments__")
        )

        assertEquals(0, state.polygons.size)
    }

    @Test
    fun buildArtifactOverlayState_rendersBuiltInFoldersWhenVisible() {
        val line = lineFeature("line-system", "System Line", "Lines & Polygons")
        val rangeRing = lineFeature("range-ring", "1 mi", "Range Rings")
        val marker = markerFeature("marker-system", "System Marker", "")
        val appTrack = appTrackFeature("app-track", "Tablet Track")

        val state = buildArtifactOverlayState(listOf(line, rangeRing, marker, appTrack))

        assertEquals(listOf("marker-system"), state.points.map { it.id })
        assertEquals(listOf("line-system", "range-ring", "app-track"), state.lines.map { it.id })
    }

    @Test
    fun buildMapFolderUiStates_defaultsBuiltInFoldersVisibleLikeApple() {
        val line = lineFeature("line-system", "System Line", "Lines & Polygons")
        val rangeRing = lineFeature("range-ring", "1 mi", "Range Rings")
        val marker = markerFeature("marker-system", "System Marker", "")
        val appTrack = appTrackFeature("app-track", "Tablet Track")

        val folders = buildMapFolderUiStates(
            mapOf(
                "line-system" to line,
                "range-ring" to rangeRing,
                "marker-system" to marker,
                "app-track" to appTrack,
            )
        )

        assertEquals(
            listOf("App Tracks", "Lines & Polygons", "Markers", "Range Rings"),
            folders.map { it.title },
        )
        assertTrue(folders.all { it.initiallyVisible })
    }

    @Test
    fun buildArtifactOverlayState_honorsHiddenBuiltInFolders() {
        val line = lineFeature("line-system", "System Line", "Lines & Polygons")
        val rangeRing = lineFeature("range-ring", "1 mi", "Range Rings")
        val marker = markerFeature("marker-system", "System Marker", "")
        val appTrack = appTrackFeature("app-track", "Tablet Track")

        val state = buildArtifactOverlayState(
            listOf(line, rangeRing, marker, appTrack),
            hiddenFolderIds = setOf(
                "__caltopo_lines_polygons__",
                "__caltopo_range_rings__",
                "__caltopo_markers__",
                "__caltopo_app_tracks__"
            )
        )

        assertEquals(0, state.points.size)
        assertEquals(0, state.lines.size)
    }

    @Test
    fun buildArtifactOverlayState_usesPilotArchivePreferenceForArchivedDroneTrack() {
        val archivedTrack = archivedDroneTrackFeature(
            id = "track-alpha",
            title = "ALPHA-M3",
            folderId = "archive-folder",
            owner = "alpha",
            stroke = "#FF00FF"
        )

        var requestedCallsign: String? = null
        val state = buildArtifactOverlayState(
            listOf(archivedTrack),
            pilotArchiveTrackColorForCallsign = { callsign ->
                requestedCallsign = callsign
                if (callsign == "ALPHA") "#43A047" else null
            }
        )

        assertEquals(1, state.lines.size)
        assertEquals("ALPHA", requestedCallsign)
    }

    @Test
    fun buildArtifactOverlayState_usesPilotArchivePreferenceFromCaltopoArchiveDescription() {
        val archivedTrack = caltopoArchivedDroneTrackFeature(
            id = "track-alpha-caltopo",
            title = "ALPHA-M3",
            folderId = "archive-folder",
            description = "Pilot Callsign: alpha\nPilot Organization: NCSSAR",
            stroke = "#FF00FF"
        )

        var requestedCallsign: String? = null
        val state = buildArtifactOverlayState(
            listOf(archivedTrack),
            pilotArchiveTrackColorForCallsign = { callsign ->
                requestedCallsign = callsign
                if (callsign == "ALPHA") "#43A047" else null
            }
        )

        assertEquals(1, state.lines.size)
        assertEquals("ALPHA", requestedCallsign)
    }

    @Test
    fun buildArtifactHydrationResult_preservesFeaturesAndReportsProgress() {
        val features = (1..25).map { index ->
            markerFeature("marker-$index", "Marker $index", "")
        }
        val progress = mutableListOf<ArtifactHydrationProgress>()

        val result = buildArtifactHydrationResult(
            snapshot = features,
            hiddenFolderIds = emptySet(),
            hiddenItemIds = emptySet(),
            progressInterval = 10,
            onProgress = progress::add
        )

        assertEquals(25, result.featuresById.size)
        assertEquals((1..25).map { "marker-$it" }, result.featuresById.keys.toList())
        assertEquals(25, result.overlayState.points.size)
        assertEquals(listOf(10, 20, 25), progress.map { it.completed })
        assertEquals(listOf(25, 25, 25), progress.map { it.total })
    }

    private fun folderFeature(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Folder")
                    .put("title", title)
            )

    private fun dronePoint(
        designator: String,
        lat: Double,
        lng: Double,
        headingDeg: Double? = null,
        remoteId: String = designator
    ): DroneMapPoint =
        DroneMapPoint(
            designator = designator,
            remoteId = remoteId,
            lat = lat,
            lng = lng,
            altitudeM = 0.0,
            timestampMsec = 1L,
            headingDeg = headingDeg
        )

    private fun tilePriorityPoint(
        lat: Double,
        lng: Double,
        headingDeg: Double? = null
    ): TilePriorityPoint =
        TilePriorityPoint(
            lat = lat,
            lng = lng,
            headingDeg = headingDeg
        )

    private fun droneLabelLayoutInput(
        designator: String,
        anchorX: Int,
        anchorY: Int
    ): DroneLabelLayoutInput =
        DroneLabelLayoutInput(
            designator = designator,
            anchorX = anchorX,
            anchorY = anchorY,
            nameWidth = 80,
            nameHeight = 24,
            statusWidth = 120,
            statusHeight = 22
        )

    private fun tileIndexForTestPoint(point: GeoPoint, zoom: Int): Long {
        val maxTile = (1 shl zoom) - 1
        val x = lonToTileXForTest(point.longitude, zoom).coerceIn(0, maxTile)
        val y = latToTileYForTest(point.latitude, zoom).coerceIn(0, maxTile)
        return MapTileIndex.getTileIndex(zoom, x, y)
    }

    private fun adjacentTileIndexForTestTile(tileIndex: Long, dx: Int, dy: Int): Long {
        val zoom = MapTileIndex.getZoom(tileIndex)
        val maxTile = (1 shl zoom) - 1
        val x = (MapTileIndex.getX(tileIndex) + dx).coerceIn(0, maxTile)
        val y = (MapTileIndex.getY(tileIndex) + dy).coerceIn(0, maxTile)
        return MapTileIndex.getTileIndex(zoom, x, y)
    }

    private fun lonToTileXForTest(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return kotlin.math.floor((lon + 180.0) / 360.0 * n).toInt()
    }

    private fun latToTileYForTest(lat: Double, zoom: Int): Int {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(clamped)
        val n = 1 shl zoom
        return kotlin.math.floor(
            (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n
        ).toInt()
    }

    private fun lineFeature(id: String, title: String, folderId: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Shape")
                    .put("title", title)
                    .put("folderId", folderId)
                    .put("stroke", "#FF5A1F")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "LineString")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(JSONArray().put(-122.0).put(37.0))
                            .put(JSONArray().put(-122.1).put(37.1))
                    )
            )

    private fun unknownLineFeature(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "FieldGeometry")
                    .put("title", title)
                    .put("stroke", "#FF5A1F")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "LineString")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(JSONArray().put(-122.0).put(37.0))
                            .put(JSONArray().put(-122.1).put(37.1))
                    )
            )

    private fun assignmentPolygonFeature(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Assignment")
                    .put("title", title)
                    .put("stroke", "#ff0000")
                    .put("fill", "#ff0000")
                    .put("fill-opacity", 0.2)
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "Polygon")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(
                                JSONArray()
                                    .put(JSONArray().put(-122.0).put(37.0))
                                    .put(JSONArray().put(-122.1).put(37.0))
                                    .put(JSONArray().put(-122.1).put(37.1))
                                    .put(JSONArray().put(-122.0).put(37.0))
                            )
                    )
            )

    private fun markerFeature(id: String, title: String, folderId: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Marker")
                    .put("title", title)
                    .put("folderId", folderId)
                    .put("marker-color", "#ff0000")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(-122.0).put(37.0))
            )

    private fun mediaObjectFeature(id: String, title: String, parentMarkerId: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "MapMediaObject")
                    .put("title", title)
                    .put("parentId", "Marker:$parentMarkerId")
                    .put("marker-color", "#ff00ff")
                    .put("marker-symbol", "aperture")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(-122.0).put(37.0))
            )

    private fun appTrackFeature(id: String, title: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "AppTrack")
                    .put("title", title)
                    .put("stroke", "#00cd00")
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "LineString")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(JSONArray().put(-122.0).put(37.0))
                            .put(JSONArray().put(-122.1).put(37.1))
                    )
            )

    private fun archivedDroneTrackFeature(
        id: String,
        title: String,
        folderId: String,
        owner: String,
        stroke: String
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Shape")
                    .put("title", title)
                    .put("folderId", folderId)
                    .put("stroke", stroke)
                    .put(
                        "r2c_prop",
                        JSONObject()
                            .put("owner", owner)
                            .put("mid", title)
                            .put("rid", "rid-$id")
                    )
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "LineString")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(JSONArray().put(-122.0).put(37.0))
                            .put(JSONArray().put(-122.1).put(37.1))
                    )
            )

    private fun caltopoArchivedDroneTrackFeature(
        id: String,
        title: String,
        folderId: String,
        description: String,
        stroke: String
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "properties",
                JSONObject()
                    .put("class", "Shape")
                    .put("title", title)
                    .put("folderId", folderId)
                    .put("description", description)
                    .put("stroke", stroke)
            )
            .put(
                "geometry",
                JSONObject()
                    .put("type", "LineString")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(JSONArray().put(-122.0).put(37.0))
                            .put(JSONArray().put(-122.1).put(37.1))
                    )
            )
}
