package org.ncssar.rid2caltopo.video

import CenterpointElevationSample
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPipInsetFrameTest {
    @Test fun centerpointPointAolStaysAbsoluteWhenTerrainReferenceChanges() {
        val sample = CenterpointElevationSample(39.0,-121.0,1000,1,true,50)
        assertEquals("1000' MSL · 1m DEM · AOL 50' · Assumed ↓90°",centerpointElevationLabel(sample))
        assertEquals("+10' REF · 1m DEM · AOL 50' · Assumed ↓90°",centerpointElevationLabel(sample,990,CenterpointElevationDisplayMode.REFERENCE))
    }

    @Test
    fun collapsedSplitDividerGetsFullEdgeRestoreTarget() {
        assertEquals(192, splitDividerTouchHeightDp(0f))
        assertEquals(96, splitDividerTouchHeightDp(0.5f))
        assertEquals(192, splitDividerTouchHeightDp(1f))
    }

    @Test
    fun bottomCollapsedDividerStaysAboveMandatoryHomeGesture() {
        assertEquals(
            860f,
            portraitSplitDividerTouchOffsetPx(
                fraction = 1f,
                availablePx = 1000f,
                dividerTouchSizePx = 96f,
                mandatoryBottomGestureInsetPx = 32f,
                bottomClearancePx = 12f,
            ),
            0f,
        )
        assertEquals(
            452f,
            portraitSplitDividerTouchOffsetPx(
                fraction = 0.5f,
                availablePx = 1000f,
                dividerTouchSizePx = 96f,
                mandatoryBottomGestureInsetPx = 32f,
                bottomClearancePx = 12f,
            ),
            0f,
        )
    }

    @Test
    fun splitDividerDrag_changesFractionUsingScreenPixels() {
        assertEquals(0.6f, adjustedSplitFraction(0.5f, 100f, 1000f), 0.0001f)
        assertEquals(0.4f, adjustedSplitFraction(0.5f, -100f, 1000f), 0.0001f)
    }

    @Test
    fun splitDividerDrag_reachesCollapsedEdgeStates() {
        assertEquals(MAX_SPLIT_FRACTION, adjustedSplitFraction(0.5f, 1000f, 1000f), 0f)
        assertEquals(MIN_SPLIT_FRACTION, adjustedSplitFraction(0.5f, -1000f, 1000f), 0f)
        assertEquals(MIN_SPLIT_FRACTION, adjustedSplitFraction(0f, 100f, 0f), 0f)
    }

    @Test
    fun splitDividerEdgeStates_selectSinglePaneLayoutsWithoutLosingTheHandle() {
        assertEquals(StreamsLayoutMode.Map, layoutModeForSplitFraction(MIN_SPLIT_FRACTION))
        assertEquals(StreamsLayoutMode.Both, layoutModeForSplitFraction(0.5f))
        assertEquals(StreamsLayoutMode.Streams, layoutModeForSplitFraction(MAX_SPLIT_FRACTION))
    }

    @Test
    fun splitDividerEdgeStates_leaveHalfTheDragPadVisible() {
        assertEquals(-24f, splitDividerOffsetPx(0f, 1000f, 48f), 0f)
        assertEquals(976f, splitDividerOffsetPx(1f, 1000f, 48f), 0f)
    }

    @Test
    fun splitDividerEdgeStates_keepTheFullTouchTargetInsideSystemGestureEdges() {
        assertEquals(0f, splitDividerTouchOffsetPx(0f, 1000f, 48f), 0f)
        assertEquals(476f, splitDividerTouchOffsetPx(0.5f, 1000f, 48f), 0f)
        assertEquals(952f, splitDividerTouchOffsetPx(1f, 1000f, 48f), 0f)
        assertEquals(0f, splitDividerTouchOffsetPx(0f, 1000f, 96f), 0f)
        assertEquals(452f, splitDividerTouchOffsetPx(0.5f, 1000f, 96f), 0f)
        assertEquals(904f, splitDividerTouchOffsetPx(1f, 1000f, 96f), 0f)
    }

    @Test
    fun splitDividerDrag_snapsWithinTwoHandleWidthsOfEitherEdge() {
        assertEquals(MIN_SPLIT_FRACTION, snappedSplitFraction(0.096f, 1000f, 48f), 0f)
        assertEquals(0.097f, snappedSplitFraction(0.097f, 1000f, 48f), 0f)
        assertEquals(MAX_SPLIT_FRACTION, snappedSplitFraction(0.904f, 1000f, 48f), 0f)
        assertEquals(0.903f, snappedSplitFraction(0.903f, 1000f, 48f), 0f)
    }

    @Test
    fun streamClueCaptureButton_isVisibleOnlyForInteractiveLiveStreams() {
        assertEquals(true, shouldShowStreamClueCaptureButton(true, false, StreamState.LIVE))
        assertEquals(false, shouldShowStreamClueCaptureButton(false, false, StreamState.LIVE))
        assertEquals(false, shouldShowStreamClueCaptureButton(true, true, StreamState.LIVE))
        assertEquals(false, shouldShowStreamClueCaptureButton(true, false, StreamState.CONNECTING))
        assertEquals(false, shouldShowStreamClueCaptureButton(true, false, StreamState.ERROR))
    }

    @Test
    fun centerpointToggleTap_acceptsOnlyTheConfiguredCenterRadius() {
        assertTrue(isNearStreamCenter(Offset(500f, 300f), 1000f, 600f, 80f))
        assertTrue(isNearStreamCenter(Offset(560f, 340f), 1000f, 600f, 80f))
        assertFalse(isNearStreamCenter(Offset(581f, 300f), 1000f, 600f, 80f))
        assertFalse(isNearStreamCenter(Offset(500f, 300f), 0f, 600f, 80f))
    }

    @Test
    fun centerpointToggleTap_doesNotConsumeFirstTapOnImplicitlyFocusedSingleStream() {
        assertFalse(
            shouldToggleCenterpointElevation(
                explicitlyFocused = false,
                tapNearCenter = true,
            )
        )
        assertTrue(
            shouldToggleCenterpointElevation(
                explicitlyFocused = true,
                tapNearCenter = true,
            )
        )
        assertFalse(
            shouldToggleCenterpointElevation(
                explicitlyFocused = true,
                tapNearCenter = false,
            )
        )
    }

    @Test
    fun centerpointReferenceLongPress_requiresActiveExplicitlyFocusedCenterpoint() {
        assertTrue(
            shouldSetCenterpointElevationReference(
                explicitlyFocused = true,
                elevationEnabled = true,
                pressNearCenter = true,
            )
        )
        assertFalse(
            shouldSetCenterpointElevationReference(
                explicitlyFocused = false,
                elevationEnabled = true,
                pressNearCenter = true,
            )
        )
        assertFalse(
            shouldSetCenterpointElevationReference(
                explicitlyFocused = true,
                elevationEnabled = false,
                pressNearCenter = true,
            )
        )
        assertFalse(
            shouldSetCenterpointElevationReference(
                explicitlyFocused = true,
                elevationEnabled = true,
                pressNearCenter = false,
            )
        )
    }

    @Test
    fun centerpointElevationLabel_distinguishesKnownResolutionFromOnlineDEM() {
        assertEquals(
            "4812' MSL · 1m DEM",
            centerpointElevationLabel(CenterpointElevationSample(39.0, -121.0, 4812, 1)),
        )
        assertEquals(
            "4812' MSL · USGS DEM",
            centerpointElevationLabel(CenterpointElevationSample(39.0, -121.0, 4812, null)),
        )
        assertEquals("--' MSL", centerpointElevationLabel(null))
        assertEquals(
            "+37' REF · 1m DEM",
            centerpointElevationLabel(
                sample = CenterpointElevationSample(39.0, -121.0, 4849, 1),
                referenceElevationFeet = 4812,
                displayMode = CenterpointElevationDisplayMode.REFERENCE,
            ),
        )
        assertEquals(
            "-12' REF · USGS DEM",
            centerpointElevationLabel(
                sample = CenterpointElevationSample(39.0, -121.0, 4800, null),
                referenceElevationFeet = 4812,
                displayMode = CenterpointElevationDisplayMode.REFERENCE,
            ),
        )
        assertEquals(
            "4800' MSL · USGS DEM",
            centerpointElevationLabel(
                sample = CenterpointElevationSample(39.0, -121.0, 4800, null),
                referenceElevationFeet = 4812,
                displayMode = CenterpointElevationDisplayMode.MSL,
            ),
        )
    }

    @Test
    fun streamPipInsetSize_matchesLandscapeFullFrameAspectRatio() {
        val size = streamPipInsetSize(
            maxWidth = 1000f,
            maxHeight = 600f,
            insetFraction = 0.33f
        )

        assertEquals(322.08f, size.width, 0.01f)
        assertEquals(193.25f, size.height, 0.01f)
        assertEquals(1000f / 600f, size.width / size.height, 0.0001f)
    }

    @Test
    fun streamPipInsetSize_matchesPortraitFullFrameAspectRatio() {
        val size = streamPipInsetSize(
            maxWidth = 600f,
            maxHeight = 1000f,
            insetFraction = 0.33f
        )

        assertEquals(190.08f, size.width, 0.01f)
        assertEquals(316.8f, size.height, 0.01f)
        assertEquals(600f / 1000f, size.width / size.height, 0.0001f)
    }

    @Test
    fun streamPipHasStreamContent_allowsSingleVisibleStreamWithoutFocus() {
        assertEquals(true, streamPipHasStreamContent(visibleStreamCount = 1, focusedPath = null))
        assertEquals(true, streamPipHasStreamContent(visibleStreamCount = 0, focusedPath = "drone-1"))
        assertEquals(false, streamPipHasStreamContent(visibleStreamCount = 0, focusedPath = null))
    }

    @Test
    fun streamPipInsetTap_swapsBetweenStreamsAndMapLayouts() {
        assertEquals(StreamsLayoutMode.Map, streamPipInsetTapLayoutMode(StreamsLayoutMode.Streams))
        assertEquals(StreamsLayoutMode.Streams, streamPipInsetTapLayoutMode(StreamsLayoutMode.Map))
        assertEquals(StreamsLayoutMode.Both, streamPipInsetTapLayoutMode(StreamsLayoutMode.Both))
    }

    @Test
    fun mapInsetVisibility_doesNotRequireStreamContent() {
        assertEquals(
            true,
            shouldShowMapPipInset(
                pipEnabled = true,
                layoutMode = StreamsLayoutMode.Streams
            )
        )
        assertEquals(
            false,
            shouldShowMapPipInset(
                pipEnabled = true,
                layoutMode = StreamsLayoutMode.Map
            )
        )
    }

    @Test
    fun streamsInsetVisibility_showsEmptyStreamsPaneWithoutStreamContent() {
        assertEquals(
            true,
            shouldShowStreamsPipInset(
                pipEnabled = true,
                layoutMode = StreamsLayoutMode.Map
            )
        )
        assertEquals(
            false,
            shouldShowStreamsPipInset(
                pipEnabled = false,
                layoutMode = StreamsLayoutMode.Map
            )
        )
        assertEquals(
            false,
            shouldShowStreamsPipInset(
                pipEnabled = true,
                layoutMode = StreamsLayoutMode.Streams
            )
        )
    }

    @Test
    fun streamsFullScreenChrome_hidesTopBarOnlyWhenAllowedAndActive() {
        assertEquals(
            StreamsFullScreenChrome(showTopBar = true, showExitChip = false),
            streamsFullScreenChrome(fullScreen = false, externalContentActive = false)
        )
        assertEquals(
            StreamsFullScreenChrome(showTopBar = false, showExitChip = true),
            streamsFullScreenChrome(fullScreen = true, externalContentActive = false)
        )
        assertEquals(
            StreamsFullScreenChrome(showTopBar = true, showExitChip = false),
            streamsFullScreenChrome(fullScreen = true, externalContentActive = true)
        )
    }

    @Test
    fun enterFullScreenChip_showsOnlyWhenPhoneTopBarCanEnterFullScreen() {
        assertEquals(
            true,
            shouldShowEnterFullScreenChip(fullScreen = false, externalContentActive = false)
        )
        assertEquals(
            false,
            shouldShowEnterFullScreenChip(fullScreen = true, externalContentActive = false)
        )
        assertEquals(
            false,
            shouldShowEnterFullScreenChip(fullScreen = false, externalContentActive = true)
        )
    }

    @Test
    fun fullScreenExitChipLayout_keepsChipAwayFromSettingsWithLargeHitTarget() {
        assertEquals(
            FullScreenExitChipLayout(minWidthDp = 96f, minHeightDp = 48f, endPaddingDp = 84f),
            fullScreenExitChipLayout()
        )
    }

    @Test
    fun streamTileChromePresentation_fillsContainerAndHidesDuplicateTelemetryInFullScreen() {
        assertEquals(
            StreamTileChromePresentation(
                fillContainer = false,
                showStandaloneTelemetryOverlay = false
            ),
            streamTileChromePresentation(fullScreenContent = false, focused = true)
        )
        assertEquals(
            StreamTileChromePresentation(
                fillContainer = true,
                showStandaloneTelemetryOverlay = false
            ),
            streamTileChromePresentation(fullScreenContent = true, focused = true)
        )
    }

    @Test
    fun streamClueCaptureReady_requiresFfmpegTextureViewAndRenderedFrame() {
        assertEquals(
            false,
            streamClueCaptureReady(
                hasCaptureTarget = false,
                renderedFrameCount = 0,
                requiresRenderedFrame = true
            )
        )
        assertEquals(
            false,
            streamClueCaptureReady(
                hasCaptureTarget = true,
                renderedFrameCount = 0,
                requiresRenderedFrame = true
            )
        )
        assertEquals(
            true,
            streamClueCaptureReady(
                hasCaptureTarget = true,
                renderedFrameCount = 1,
                requiresRenderedFrame = true
            )
        )
    }

    @Test
    fun streamClueCaptureReady_allowsPlayerTextureViewWithoutFfmpegFrameCallback() {
        assertEquals(
            false,
            streamClueCaptureReady(
                hasCaptureTarget = false,
                renderedFrameCount = 0,
                requiresRenderedFrame = false
            )
        )
        assertEquals(
            true,
            streamClueCaptureReady(
                hasCaptureTarget = true,
                renderedFrameCount = 0,
                requiresRenderedFrame = false
            )
        )
    }

    @Test
    fun streamClueCaptureStateKey_survivesStreamRevisionChanges() {
        assertEquals(
            streamClueCaptureStateKey(designator = "1sar34DjN2", streamRevision = 2L),
            streamClueCaptureStateKey(designator = "1sar34DjN2", streamRevision = 3L)
        )
        assertEquals(
            false,
            streamClueCaptureStateKey(designator = "1sar34DjN2", streamRevision = 2L) ==
                streamClueCaptureStateKey(designator = "neo2", streamRevision = 2L)
        )
    }

    @Test
    fun streamTileFocusPresentation_treatsSingleDisplayedTileAsFocusedWithoutBorder() {
        assertEquals(
            StreamTileFocusPresentation(effectiveFocused = true, showFocusBorder = false),
            streamTileFocusPresentation(displayedTileCount = 1, explicitlyFocused = false)
        )
        assertEquals(
            StreamTileFocusPresentation(effectiveFocused = true, showFocusBorder = false),
            streamTileFocusPresentation(displayedTileCount = 1, explicitlyFocused = true)
        )
    }

    @Test
    fun streamTileFocusPresentation_requiresExplicitFocusWhenGridHasMultipleTiles() {
        assertEquals(
            StreamTileFocusPresentation(effectiveFocused = false, showFocusBorder = false),
            streamTileFocusPresentation(displayedTileCount = 2, explicitlyFocused = false)
        )
        assertEquals(
            StreamTileFocusPresentation(effectiveFocused = true, showFocusBorder = true),
            streamTileFocusPresentation(displayedTileCount = 2, explicitlyFocused = true)
        )
    }
}
