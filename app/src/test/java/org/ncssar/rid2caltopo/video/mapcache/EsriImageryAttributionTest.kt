package org.ncssar.rid2caltopo.video.mapcache

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.ncssar.rid2caltopo.video.BaseLayerOption
import org.ncssar.rid2caltopo.video.mapPaneAttributionText

class EsriImageryAttributionTest {
    private val fixture = """{"contributors":[
        {"attribution":"Local High","coverageAreas":[{"zoomMin":10,"zoomMax":20,"score":100,"bbox":[30,-125,45,-110]}]},
        {"attribution":"Other Area","coverageAreas":[{"zoomMin":0,"zoomMax":23,"score":80,"bbox":[0,10,5,15]}]},
        {"attribution":"Overview","coverageAreas":[{"zoomMin":0,"zoomMax":10,"score":50,"bbox":[-85,-180,85,180]}]},
        {"attribution":"Local High","coverageAreas":[{"zoomMin":10,"zoomMax":20,"score":60,"bbox":[30,-125,45,-110]}]},
        {"attribution":"Dateline East","coverageAreas":[{"zoomMin":0,"zoomMax":23,"score":80,"bbox":[-20,175,20,180]}]},
        {"attribution":"Dateline West","coverageAreas":[{"zoomMin":0,"zoomMax":23,"score":80,"bbox":[-20,-180,20,-175]}]}
    ]}"""
    @Test fun creditsSelectAreaAndZoomAndDeduplicate() {
        val sources = EsriImageryAttribution(fixture).sources(EsriImageryViewport(37.7,-119.6,37.8,-119.5,18.0))
        assertTrue(sources.contains("Local High")); assertEquals(2, sources.split("Local High").size)
        assertFalse(sources.contains("Other Area")); assertFalse(sources.contains("Overview"))
        assertTrue(sources.contains("Esri") && sources.contains("Vantor"))
    }
    @Test fun datelineIncludesBothSidesAndExcludesUnrelatedAreas() {
        val sources = EsriImageryAttribution(fixture).sources(EsriImageryViewport(-1.0,178.0,1.0,-178.0,18.0))
        assertTrue(sources.contains("Dateline East") && sources.contains("Dateline West"))
        assertFalse(sources.contains("Other Area") || sources.contains("Local High"))
    }
    @Test fun fractionalZoomIncludesBothTileLevels() {
        val sources = EsriImageryAttribution(fixture).sources(EsriImageryViewport(37.0,-120.0,38.0,-119.0,9.5))
        assertTrue(sources.contains("Overview") && sources.contains("Local High"))
    }
    @Test fun invalidViewportUsesServiceCopyrightFallback() {
        assertEquals(EsriImageryAttribution.FALLBACK_SOURCES, EsriImageryAttribution(fixture).sources(EsriImageryViewport(Double.NaN,0.0,1.0,1.0,18.0)))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyCatalogCannotReplaceFallback() { EsriImageryAttribution("{\"contributors\":[]}") }
    @Test fun actualBundledMetadataHasAdditionalLocalContributors() {
        val path = listOf(File("src/main/assets/esri/world-imagery-attribution.json"),File("app/src/main/assets/esri/world-imagery-attribution.json")).first { it.isFile }
        val sources = EsriImageryAttribution(path.readText()).sources(EsriImageryViewport(37.7,-119.65,37.8,-119.5,18.0))
        assertTrue(sources.contains("Microsoft")); assertTrue(sources.contains("Vantor"))
    }
    @Test fun imageryFooterCannotUseOsmAttributionAndRetainsOverlayCredit() {
        val sources = "Imagery Supplier"
        val text = mapPaneAttributionText(true, BaseLayerOption.Imagery, sources)
        assertTrue(text.contains("Powered by Esri") && text.contains(sources) && text.contains("Contours: USGS"))
        assertFalse(text.contains("OpenStreetMap"))
        assertTrue(mapPaneAttributionText(false, BaseLayerOption.OpenStreetMap).contains("© OpenStreetMap contributors"))
    }
}
