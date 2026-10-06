package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.app.R2CApplication
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Durable per-flight destination choices; only unassigned Publish decisions are offered for review. */
object AwaitingMapFlights {
    internal fun destinationScope(personalAccountId: String?, teamId: String): String =
        personalAccountId?.trim()?.let { if (it.isEmpty()) "" else "personal:$it" } ?: teamId

    @JvmStatic fun selectedPublicationScope(): String {
        val personal = CaltopoPersonalSession.capture("/")
        return destinationScope(personal?.accountID, CaltopoClient.GetCaltopoCredentials().teamId.orEmpty())
    }

    private var testFile: File? = null
    internal fun useFileForTesting(file: File?) { testFile = file; loaded = false; entries = JSONArray(); lastWrite.clear() }
    private var loaded = false
    private var entries = JSONArray()
    private val lastWrite = mutableMapOf<String, Long>()
    private fun file() = testFile ?: R2CApplication.getAppCtxt()?.let { File(it.filesDir, "awaiting-map-flights.json") }
    private fun load() {
        if (loaded) return
        val file = file() ?: return
        entries = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        all().forEach {
            it.put("finished", true)
            if (it.optString("decision") == "bound" && !it.optBoolean("publicationStarted")) it.put("decision", "review")
        }
        loaded = true
    }
    private fun save() {
        val file = file() ?: return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(entries.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    private fun all() = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }

    @JvmStatic @JvmOverloads @Synchronized fun record(id: String, remote: String, label: String, points: JSONArray, finished: Boolean,
        selectedMap: String = CaltopoMap.GetMapId(), selectedTeam: String = selectedPublicationScope()) {
        load()
        if (points.length() == 0) return
        val existing = all().firstOrNull { it.optString("id") == id }
        // Never infer a new publication request from a completed archive or another map's flight.
        if (existing == null && finished) return
        val initialMap = selectedMap
        val item = existing ?: JSONObject().put("id", id).put("remote", remote).put("map", initialMap)
            .put("team", selectedTeam).put("decision", if (initialMap.isEmpty()) "review" else "bound").also { entries.put(it) }
        item.put("label", label).put("points", JSONArray(points.toString())).put("finished", finished)
        if (finished && item.optString("decision") == "bound" && !item.optBoolean("publicationStarted")) {
            item.put("decision", "review")
        }
        if (finished && item.optString("decision") in listOf("bound", "local")) {
            entries = JSONArray(all().filterNot { it.optString("id") == id }); save(); return
        }
        val now = System.currentTimeMillis()
        if (finished || existing == null || now - (lastWrite[id] ?: 0) >= 5_000) {
            save(); lastWrite[id] = now
        }
        if (finished && item.optString("decision") == "publish") queueCompleted(item)
    }
    @JvmStatic @JvmOverloads @Synchronized fun publicationId(remote: String, map: String, team: String = selectedPublicationScope()): String? {
        load()
        val item = all().lastOrNull { it.optString("remote") == remote && !it.optBoolean("finished") } ?: return null
        return when {
            item.optString("map") != map || item.optString("team") != team -> ""
            item.optString("decision") == "bound" -> null
            item.optString("decision") == "publish" -> item.optString("id")
            else -> ""
        }
    }
    @JvmStatic @Synchronized fun notePublication(remote: String, map: String) {
        load()
        val entry = all().lastOrNull { it.optString("remote") == remote && !it.optBoolean("finished") && it.optString("map") == map } ?: return
        if (!entry.optBoolean("publicationStarted")) { entry.put("publicationStarted", true); save() }
    }
    @Synchronized fun clueDestination(remote: String): Pair<String, String>? {
        load()
        val entry = all().lastOrNull { it.optString("remote") == remote && !it.optBoolean("finished") } ?: return null
        if (entry.optString("decision") == "local") return null
        return entry.optString("map") to entry.optString("team")
    }
    /** Destination for a clue bound to journal entry [id] (null when the flight is local-only or unknown). */
    @Synchronized fun clueDestinationForFlight(id: String): Pair<String, String>? {
        load()
        val entry = all().lastOrNull { it.optString("id") == id } ?: return null
        if (entry.optString("decision") == "local") return null
        return entry.optString("map") to entry.optString("team")
    }
    @Synchronized fun hasEntry(id: String): Boolean { load(); return all().any { it.optString("id") == id } }
    @Synchronized fun pending(): List<JSONObject> { load(); return all().filter { it.optString("decision") == "review" }.map { JSONObject(it.toString()) } }
    @Synchronized fun decide(id: String, map: String?, team: String) {
        load()
        val item = all().firstOrNull { it.optString("id") == id && it.optString("decision") == "review" } ?: return
        if (map != null) require(map.isNotBlank() && team.isNotBlank())
        val prior = entries.toString()
        item.put("decision", if (map == null) "local" else "publish").put("map", map ?: "").put("team", team)
        try { save() } catch (error: Exception) { entries = JSONArray(prior); throw error }
        if (map != null) {
            val ctxt = R2CApplication.getAppCtxt()
            if (ctxt != null) org.ncssar.rid2caltopo.video.AndroidClueStore.shared(ctxt).bindAwaitingFlight(
                JSONObject(item.toString()), all().map { JSONObject(it.toString()) }, map, team)
            if (item.optBoolean("finished")) queueCompleted(item)
        }
    }
    private fun queueCompleted(item: JSONObject) {
        // An already-started live publication owns finalization and its full buffered geometry.
        val id = item.optString("id")
        if (CaltopoLiveTrack.hasActivePublication(id)) return
        val source = item.getJSONArray("points")
        val geometry = JSONArray()
        for (index in 0 until source.length()) {
            val point = source.getJSONArray(index)
            geometry.put(JSONArray().put(point.getDouble(0)).put(point.getDouble(1)).put(point.getDouble(2)))
        }
        CaltopoInterruptedTrackJournal.save(item.optString("map"), item.optString("remote"), id,
            item.optString("label"), "", geometry)
    }
    @JvmStatic @Synchronized fun reconcile(map: String, team: String) {
        load()
        val ctxt = R2CApplication.getAppCtxt()
        if (ctxt != null) all().filter { it.optString("decision") in listOf("publish", "published") && it.optString("map") == map && it.optString("team") == team }.forEach {
            org.ncssar.rid2caltopo.video.AndroidClueStore.shared(ctxt).bindAwaitingFlight(
                JSONObject(it.toString()), all().map { other -> JSONObject(other.toString()) }, map, team)
        }
        all().filter { it.optString("decision") == "publish" && it.optString("map") == map && it.optString("team") == team && it.optBoolean("finished") }
            .forEach { queueCompleted(it) }
    }
    /** Removes exactly one undecided or local entry. Returns false when nothing matched. */
    @JvmStatic @Synchronized fun discard(id: String): Boolean {
        load()
        val remaining = all().filterNot { it.optString("id") == id && it.optString("decision") in listOf("review", "bound", "local") }
        if (remaining.size == entries.length()) return false
        val prior = entries
        entries = JSONArray(remaining)
        try { save() } catch (error: Exception) { entries = prior; throw error }
        return true
    }
    /** Copies of every journal entry, including decided ones, for clue ownership checks. */
    @Synchronized fun allEntries(): List<JSONObject> { load(); return all().map { JSONObject(it.toString()) } }
    @JvmStatic @Synchronized fun published(id: String) {
        load(); all().firstOrNull { it.optString("id") == id }?.let { it.put("decision", "published")
            val points = it.optJSONArray("points")
            if (points != null && points.length() > 1) it.put("points", JSONArray().put(points.get(0)).put(points.get(points.length()-1)))
            save() }
    }
    fun firstTime(item: JSONObject): Long = item.optJSONArray("points")?.optJSONArray(0)?.optLong(3) ?: 0
    fun lastTime(item: JSONObject): Long = item.optJSONArray("points")?.let { it.optJSONArray(it.length() - 1)?.optLong(3) } ?: 0

    /** IC only: travel tracks, arbitrary points, and the viewport never establish relevance. */
    fun suggested(item: JSONObject, features: List<JSONObject>, now: Long = System.currentTimeMillis()): Boolean {
        val age = now - lastTime(item)
        if (age !in 0..86_400_000L) return false
        val ic = features.filter { it.optJSONObject("properties")?.optString("title")?.trim()?.equals("IC", true) == true &&
            it.optJSONObject("geometry")?.optString("type") == "Point" }
        val points = item.optJSONArray("points") ?: return false
        return ic.any { feature ->
            val location = feature.getJSONObject("geometry").optJSONArray("coordinates") ?: return@any false
            (0 until points.length()).any { index ->
                val point = points.optJSONArray(index) ?: return@any false
                distanceMeters(point.optDouble(1), point.optDouble(0), location.optDouble(1), location.optDouble(0)) <= 16_093.44
            }
        }
    }
    private fun distanceMeters(a: Double, b: Double, c: Double, d: Double): Double {
        if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite() || a !in -90.0..90.0 || c !in -90.0..90.0 || b !in -180.0..180.0 || d !in -180.0..180.0) return Double.POSITIVE_INFINITY
        val lat = Math.toRadians(c-a); val lon = Math.toRadians(d-b)
        val h = kotlin.math.sin(lat/2).let { it*it } + kotlin.math.cos(Math.toRadians(a))*kotlin.math.cos(Math.toRadians(c))*kotlin.math.sin(lon/2).let { it*it }
        return 12_742_000 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0,1.0)))
    }
}
