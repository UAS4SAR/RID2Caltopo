package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

internal data class EsriImageryViewport(val south: Double, val west: Double, val north: Double, val east: Double, val zoom: Double) {
    val valid: Boolean get() = listOf(south, west, north, east, zoom).all { it.isFinite() } &&
        south <= north && south >= -90 && north <= 90 && west in -180.0..180.0 && east in -180.0..180.0
}

internal class EsriImageryAttribution(json: String) {
    private data class Area(val minZoom: Double, val maxZoom: Double, val score: Double, val bbox: List<Double>) {
        fun intersects(v: EsriImageryViewport): Boolean {
            if (bbox.size != 4 || !bbox.all { it.isFinite() } || bbox[0] > bbox[2] ||
                bbox[0] > v.north || bbox[2] < v.south || minZoom > ceil(v.zoom) || maxZoom < floor(v.zoom)) return false
            val views = if (v.west <= v.east) listOf(v.west to v.east) else listOf(v.west to 180.0, -180.0 to v.east)
            val areas = if (bbox[1] <= bbox[3]) listOf(bbox[1] to bbox[3]) else listOf(bbox[1] to 180.0, -180.0 to bbox[3])
            return views.any { view -> areas.any { it.first <= view.second && it.second >= view.first } }
        }
    }
    private data class Contributor(val name: String, val areas: List<Area>)
    private val contributors: List<Contributor> = JSONObject(json.removePrefix("\uFEFF")).getJSONArray("contributors").let { list ->
        (0 until list.length()).map { index ->
            val c = list.getJSONObject(index)
            val areas = c.getJSONArray("coverageAreas")
            Contributor(c.getString("attribution").trim(), (0 until areas.length()).map { i ->
                val a = areas.getJSONObject(i); val b = a.getJSONArray("bbox")
                Area(a.getDouble("zoomMin"), a.getDouble("zoomMax"), a.getDouble("score"), (0 until b.length()).map { b.getDouble(it) })
            })
        }.also { require(it.isNotEmpty()) }
    }
    fun sources(v: EsriImageryViewport): String {
        if (!v.valid) return FALLBACK_SOURCES
        val names = contributors.mapNotNull { c -> c.areas.filter { it.intersects(v) }.maxOfOrNull { it.score }?.let { c.name to it } }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        val seen = mutableSetOf("Esri", "Vantor", "Earthstar Geographics", "and the GIS User Community", "GIS User Community")
        return (listOf(FALLBACK_SOURCES) + names.mapNotNull { (name, _) -> name.takeIf { it.isNotEmpty() && seen.add(it) } }).joinToString("; ")
    }
    companion object {
        const val FALLBACK_SOURCES = "Esri, Vantor, Earthstar Geographics, and the GIS User Community"
        const val METADATA_URL = "https://static.arcgis.com/attribution/World_Imagery"
        const val SOURCE_URL = "https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9"
    }
}

/** Bundled fallback plus one coordinate-free metadata refresh per process, entirely on IO. */
internal object EsriImageryAttributionProvider {
    private val gate = Mutex()
    private var bundled: EsriImageryAttribution? = null
    private var remote: EsriImageryAttribution? = null
    private var refreshAttempted = false
    suspend fun sources(context: Context, viewport: EsriImageryViewport, refresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val catalog = gate.withLock {
            if (bundled == null) bundled = runCatching {
                context.assets.open("esri/world-imagery-attribution.json").bufferedReader().use { EsriImageryAttribution(it.readText()) }
            }.getOrNull()
            if (refresh && !refreshAttempted) {
                refreshAttempted = true
                val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build()
                remote = runCatching {
                    client.newCall(Request.Builder().url(EsriImageryAttribution.METADATA_URL).build()).execute().use { response ->
                        require(response.isSuccessful)
                        val bytes = response.body!!.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16_384)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 4_000_000)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        require(bytes.size <= 4_000_000)
                        EsriImageryAttribution(bytes.toString(Charsets.UTF_8))
                    }
                }.getOrNull()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
            if (refresh) remote ?: bundled else bundled
        }
        catalog?.sources(viewport) ?: EsriImageryAttribution.FALLBACK_SOURCES
    }
}
