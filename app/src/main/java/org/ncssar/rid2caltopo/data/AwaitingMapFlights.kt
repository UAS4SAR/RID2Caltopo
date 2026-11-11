package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.app.R2CApplication
import java.io.File
import java.util.UUID

/** Durable per-flight destination choices; only unassigned Publish decisions are offered for review. */
object AwaitingMapFlights {
    internal fun destinationScope(personalAccountId: String?, teamId: String): String =
        personalAccountId?.trim()?.let { if (it.isEmpty()) "" else "personal:$it" } ?: teamId

    @JvmStatic fun selectedPublicationScope(): String = CluePublicationIdentity.publicationTeamId()

    private var testFile: File? = null
    internal fun useFileForTesting(file: File?) {
        testFile = file; loaded = false; entries = JSONArray(); lastWrite.clear()
        publicationChoices = null; pendingPublicationChoices.clear(); pendingPublicationStarted.clear()
    }
    private val pendingPublicationChoices = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Confirm/enqueue marked publicationStarted before the durable write lands. */
    private val pendingPublicationStarted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** A queued first intent must suppress publication until its destination/consent is durable. */
    @JvmStatic fun reservePublication(remote: String, id: String) { pendingPublicationChoices[remote] = id }
    /** Reserved flight id while the first durable intent write is still in flight; null when none. */
    @JvmStatic fun reservedPublicationId(remote: String): String? = pendingPublicationChoices[remote]
    private fun intentSaved(remote: String, id: String) { pendingPublicationChoices.remove(remote, id) }
    /** Hot confirm/enqueue: finished bound must not promote to deferred publish while this is set. */
    @JvmStatic fun markPublicationStarted(remote: String) {
        if (remote.isNotEmpty()) pendingPublicationStarted.add(remote)
    }

    private data class PublicationChoice(val id: String, val map: String, val team: String, val decision: String)
    @Volatile private var publicationChoices: Map<String, PublicationChoice>? = null
    private fun publishChoices() {
        publicationChoices = all().filterNot { it.optBoolean("finished") }.associate { item ->
            item.optString("remote") to PublicationChoice(item.optString("id"), item.optString("map"),
                item.optString("team"), if (item.optBoolean("ownershipReviewPending")) "review" else item.optString("decision"))
        }
    }
    private var loaded = false
    private var entries = JSONArray()
    private val lastWrite = mutableMapOf<String, Long>()
    private val persist = java.util.concurrent.atomic.AtomicBoolean(false)
    /** App-private path only — never triggers Archive Dir prepare on the caller. */
    private fun file() = testFile ?: R2CApplication.getAppCtxt()?.let { context ->
        org.ncssar.rid2caltopo.storage.ArchiveDirAvailability.operationalStateFile(
            context.filesDir,
            "awaiting-map-flights.json",
        )
    }
    private fun load() {
        if (loaded) return
        val file = file() ?: return
        entries = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        all().forEach {
            it.put("finished", true)
            if (it.optString("decision") == "bound" && !it.optBoolean("publicationStarted")) it.put("decision", "review")
        }
        loaded = true
        publishChoices()
    }
    private fun save() {
        val file = file() ?: return
        file.parentFile?.mkdirs()
        // App-private atomic write; Archive Dir copy via retainLater (never sync retain).
        PlainFileSafeWriter.replaceLocal(file, entries.toString().toByteArray(Charsets.UTF_8)) { it.isNotEmpty() }
        org.ncssar.rid2caltopo.storage.OperationalStorage.retainLater(file)
    }
    /** Persist on the archive worker when the caller may be Main; otherwise write immediately. */
    private fun saveOffMainIfNeeded() {
        val main = android.os.Looper.getMainLooper()
        if (main != null && android.os.Looper.myLooper() == main) {
            schedulePersist()
        } else {
            save()
        }
    }
    private fun schedulePersist() {
        if (!persist.compareAndSet(false, true)) return
        CaltopoRecoveryWork.offMainArchive {
            try {
                while (true) {
                    persist.set(false)
                    synchronized(this) {
                        load()
                        save()
                    }
                    if (!persist.get()) break
                }
            } catch (error: Exception) {
                persist.set(false)
                CaltopoClient.CTWarn("AwaitingMapFlights", "persist failed: ${error.message}", error)
            }
        }
    }
    private fun all() = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }

    @JvmStatic @Synchronized fun recordOwned(
        id: String, remote: String, label: String, points: JSONArray, finished: Boolean,
        selectedMap: String, selectedTeam: String, ownershipReviewPending: Boolean,
        recordedPilot: String, claim: String, contextKnown: Boolean, blockAutomaticPublication: Boolean,
    ) = record(id, remote, label, points, finished, selectedMap, selectedTeam, ownershipReviewPending,
        recordedPilot, claim, contextKnown, blockAutomaticPublication)

    @JvmStatic @JvmOverloads @Synchronized fun record(id: String, remote: String, label: String, points: JSONArray, finished: Boolean,
        selectedMap: String = CaltopoMap.GetMapId(), selectedTeam: String = selectedPublicationScope(), ownershipReviewPending: Boolean? = null,
        recordedPilot: String? = null, claim: String? = null, contextKnown: Boolean? = null, blockAutomaticPublication: Boolean = false) {
        load()
        if (points.length() == 0) return
        val existing = all().firstOrNull { it.optString("id") == id }
        // Never infer a new publication request from a completed archive or another map's flight.
        if (existing == null && finished) return
        // Discarded / declined / published ledger tombstones must never re-enter the inbox.
        if (existing == null && FlightPublicationLedger.blocksAwaitingMapPromotion(id)) return
        val initialMap = selectedMap
        val item = existing ?: JSONObject().put("id", id).put("remote", remote).put("map", initialMap)
            .put("team", selectedTeam).put("decision", if (initialMap.isEmpty()) "review" else "bound").also { entries.put(it) }
        item.put("label", label).put("points", JSONArray(points.toString())).put("finished", finished)
        if (ownershipReviewPending != null) item.put("ownershipReviewPending", ownershipReviewPending)
        if (recordedPilot != null) {
            if (recordedPilot.isBlank()) item.remove("recordedPilot") else item.put("recordedPilot", recordedPilot.trim())
        }
        if (claim != null) item.put("claim", claim)
        if (contextKnown != null) item.put("ownershipContextKnown", contextKnown)
        if (finished && item.optBoolean("ownershipReviewPending")) {
            item.put("decision", "review").put("publicationStarted", false)
            pendingPublicationStarted.remove(remote)
        }
        // An archive callback can finish before its queued final intent checkpoint.
        // Keep the completed flight's compact ownership endpoints in that ordering too.
        if (finished && item.optString("decision") == "published" && points.length() > 1) {
            item.put("points", JSONArray().put(points.get(0)).put(points.get(points.length() - 1)))
        }
        // Confirm/enqueue may have marked publicationStarted before this durable write.
        if (pendingPublicationStarted.remove(remote)) {
            item.put("publicationStarted", true)
        }
        if (finished && item.optString("decision") == "bound" && !item.optBoolean("publicationStarted")) {
            // A flight that already had a connected map should publish through the archive,
            // not wait in the review inbox while that same map is still up.
            item.put("decision", if (item.optString("map").isNotEmpty()) "publish" else "review")
        }
        if (blockAutomaticPublication || item.optBoolean("automaticPublicationBlocked")) {
            item.put("automaticPublicationBlocked", true)
            item.put("decision", "review").put("publicationStarted", false)
            pendingPublicationStarted.remove(remote)
        }
        if (finished && item.optString("decision") in listOf("bound", "local")) {
            entries = JSONArray(all().filterNot { it.optString("id") == id }); saveOffMainIfNeeded(); publishChoices(); intentSaved(remote, id); return
        }
        val now = System.currentTimeMillis()
        if (finished || existing == null || now - (lastWrite[id] ?: 0) >= 5_000) {
            saveOffMainIfNeeded(); lastWrite[id] = now
        }
        publishChoices()
        intentSaved(remote, id)
        if (finished && item.optString("decision") == "publish") queueCompleted(item)
    }
    /** Hot RID/live-track decisions read immutable state without the storage writer's monitor. */
    @JvmStatic @JvmOverloads fun publicationId(remote: String, map: String, team: String = selectedPublicationScope()): String? {
        if (pendingPublicationChoices.containsKey(remote)) return ""
        val choices = publicationChoices ?: synchronized(this) {
            CaltopoRecoveryWork.requireOffMain()
            load()
            publicationChoices.orEmpty()
        }
        val item = choices[remote] ?: return null
        return when {
            item.map != map || (item.team != team && !CluePublicationIdentity.sameTeam(item.team, team)) -> ""
            item.decision == "bound" -> null
            item.decision == "publish" -> item.id
            else -> ""
        }
    }

    /**
     * An in-progress flight that captured an empty-map review intent before the map connected
     * must bind to the now-connected map when local ownership is granted, so LiveTrack can start
     * without waiting for the review inbox. Finished review offers stay in the inbox.
     */
    @JvmStatic @JvmOverloads @Synchronized fun bindConnectedMapIfOpenReview(
        remote: String, map: String, team: String = selectedPublicationScope(),
    ): String? {
        if (map.isEmpty()) return null
        load()
        reservedPublicationId(remote)?.let { return it }
        val item = all().lastOrNull { it.optString("remote") == remote && !it.optBoolean("finished") } ?: return null
        if (item.optString("decision") != "review" || item.optBoolean("ownershipReviewPending") || item.optBoolean("automaticPublicationBlocked")) return null
        if (item.optString("map").isNotEmpty()) return null
        item.put("map", map).put("team", team).put("decision", "bound")
        save()
        publishChoices()
        CaltopoClient.CTDebug("AwaitingMapFlights",
            "Bound open review intent remote=$remote map=$map id=${item.optString("id")}")
        return item.optString("id").ifEmpty { null }
    }
    @JvmStatic @JvmOverloads @Synchronized fun notePublication(
        remote: String, map: String, label: String? = null,
    ) {
        if (map.isEmpty()) return
        load()
        // Live publish to a connected map must bind an empty-map review intent captured before
        // connect; requiring map equality left those rows as finished review forever.
        val entry = all().lastOrNull {
            it.optString("remote") == remote && !it.optBoolean("finished") && !it.optBoolean("ownershipReviewPending") &&
                (it.optString("map") == map ||
                    (it.optString("map").isEmpty() && it.optString("decision") == "review"))
        } ?: return
        var changed = false
        if (entry.optString("map").isEmpty()) {
            entry.put("map", map).put("decision", "bound")
            changed = true
        }
        if (!entry.optBoolean("publicationStarted")) {
            entry.put("publicationStarted", true)
            changed = true
        }
        pendingPublicationStarted.remove(remote)
        val trimmedLabel = label?.trim().orEmpty()
        if (trimmedLabel.isNotEmpty() && entry.optString("label") != trimmedLabel) {
            entry.put("label", trimmedLabel)
            changed = true
        }
        if (changed) save()
        if (changed) publishChoices()
    }

    /**
     * Stable RidFlightID for an open bound/publish intent (or a reserved first write).
     * Unlike [publicationId], bound flights still return their id so LiveTrack create/finish
     * never mint a random UUID that races the deferred archive path.
     */
    @JvmStatic @JvmOverloads fun stableFlightId(
        remote: String, map: String = org.ncssar.rid2caltopo.data.CaltopoMap.GetMapId() ?: "",
    ): String? {
        reservedPublicationId(remote)?.let { return it }
        val choices = publicationChoices ?: synchronized(this) {
            CaltopoRecoveryWork.requireOffMain()
            load()
            publicationChoices.orEmpty()
        }
        val item = choices[remote] ?: return null
        if (item.map.isNotEmpty() && item.map != map) return null
        return when (item.decision) {
            "bound", "publish" -> item.id.ifEmpty { null }
            else -> null
        }
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
    /** True when this flight's live track had already started before the process died. */
    @JvmStatic @Synchronized fun publicationAlreadyStarted(id: String): Boolean {
        if (id.isEmpty()) return false
        load()
        return all().any { it.optString("id") == id && it.optBoolean("publicationStarted") }
    }
    @Synchronized fun pending(): List<JSONObject> {
        load()
        return all().filter {
            TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
                decision = it.optString("decision"),
                finished = it.optBoolean("finished"),
                archiveConfirmed = it.optBoolean("archiveConfirmed"),
                hasArchiveId = it.optString("caltopoArchiveId").isNotEmpty(),
                flightId = it.optString("id"),
            )
        }.map { JSONObject(it.toString()) }
    }
    /**
     * Operator explicitly releases one held flight. Does not copy a pilot from the
     * current device or from another tablet's claim. Tracker upload is released only
     * when this flight already recorded a Mine pilot and the claim was merely unverified.
     */
    @JvmStatic @Synchronized fun authorizeOwnershipReview(id: String): Boolean =
        authorizeOwnershipReview(id, false)

    @JvmStatic @Synchronized fun authorizeOwnershipReview(id: String, peerOverride: Boolean): Boolean {
        load()
        val item = all().firstOrNull {
            it.optString("id") == id && it.optString("decision") == "review" && it.optBoolean("ownershipReviewPending")
                && !it.optBoolean("automaticPublicationBlocked")
        } ?: return false
        val pilot = item.optString("recordedPilot")
        val claim = item.optString("claim")
        val known = item.optBoolean("ownershipContextKnown")
        if (FlightOwnershipContext.isLostToAnotherTablet(claim) && !peerOverride) return false
        item.put("ownershipReviewPending", false).put("ownershipReviewAuthorized", true)
        saveOffMainIfNeeded()
        publishChoices()
        val releasable = claim == FlightOwnershipContext.PENDING || peerOverride
        if (known && pilot.isNotBlank() && releasable) {
            OwnershipReviewReleases.allowTrackerUpload(id)
        }
        return true
    }

    fun decide(id: String, map: String?, team: String) {
        val bindItem: JSONObject?
        val bindOthers: List<JSONObject>?
        val finishedPublish: JSONObject?
        synchronized(this) {
            load()
            val item = all().firstOrNull { it.optString("id") == id && it.optString("decision") == "review" } ?: return
            check(map == null || !item.optBoolean("ownershipReviewPending")) { "This flight requires an ownership review before publication." }
            check(map == null || !item.optBoolean("automaticPublicationBlocked")) { "This flight was not confirmed and cannot be published." }
            if (map != null) require(map.isNotBlank() && team.isNotBlank())
            val prior = entries.toString()
            item.put("decision", if (map == null) "local" else "publish").put("map", map ?: "").put("team", team)
            try { saveOffMainIfNeeded() } catch (error: Exception) { entries = JSONArray(prior); throw error }
            publishChoices()
            if (map == null) {
                FlightPublicationLedger.remember(id, FlightPublicationLedger.Disposition.DECLINED)
            }
            if (map != null) {
                bindItem = JSONObject(item.toString())
                bindOthers = all().map { JSONObject(it.toString()) }
                finishedPublish = if (item.optBoolean("finished")) JSONObject(item.toString()) else null
            } else {
                bindItem = null
                bindOthers = null
                finishedPublish = null
            }
        }
        // Clue-store / interrupted-journal I/O must not run under the AwaitingMap monitor.
        if (map != null && bindItem != null && bindOthers != null) {
            val ctxt = R2CApplication.getAppCtxt()
            if (ctxt != null) {
                val mapTitle = org.ncssar.rid2caltopo.data.CaltopoMap.GetMapName().orEmpty()
                org.ncssar.rid2caltopo.video.AndroidClueStore.shared(ctxt)
                    .bindAwaitingFlight(bindItem, bindOthers, map, team, mapTitle)
            }
            if (finishedPublish != null) queueCompleted(finishedPublish)
        }
    }
    private fun queueCompleted(item: JSONObject) {
        // An already-started / finishing / archived publication owns finalization.
        val id = item.optString("id")
        val remote = item.optString("remote")
        val started = firstTime(item)
        if (CaltopoLiveTrack.hasActivePublication(id)) return
        if (CaltopoLiveTrack.hasActiveOrFinishingFlight(remote, started)) return
        if (CaltopoInterruptedTrackJournal.hasFlight(remote, started, id)) return
        val source = item.getJSONArray("points")
        val geometry = JSONArray()
        for (index in 0 until source.length()) {
            val point = source.getJSONArray(index)
            geometry.put(JSONArray().put(point.getDouble(0)).put(point.getDouble(1)).put(point.getDouble(2)))
        }
        CaltopoInterruptedTrackJournal.save(item.optString("map"), remote, id,
            item.optString("label"), "", geometry, started)
    }
    @JvmStatic fun reconcile(map: String, team: String) {
        val ctxt = R2CApplication.getAppCtxt()
        val bindings = synchronized(this) {
            load()
            val others = all().map { JSONObject(it.toString()) }
            all().filter { it.optString("decision") in listOf("publish", "published") && it.optString("map") == map && it.optString("team") == team }
                .map { JSONObject(it.toString()) to others }
        }
        // Clue-store file checks must not run while this journal lock is held.
        if (ctxt != null) {
            val mapTitle = org.ncssar.rid2caltopo.data.CaltopoMap.GetMapName().orEmpty()
            bindings.forEach { (item, others) ->
                org.ncssar.rid2caltopo.video.AndroidClueStore.shared(ctxt)
                    .bindAwaitingFlight(item, others, map, team, mapTitle)
            }
        }
        synchronized(this) {
            load()
            all().filter { it.optString("decision") == "publish" && it.optString("map") == map && it.optString("team") == team && it.optBoolean("finished") }
                .forEach { queueCompleted(it) }
        }
    }
    /**
     * Removes exactly one undecided or local entry and records a durable discarded
     * disposition so leftover journals / archive hydrate cannot resurrect it.
     * Returns false when nothing matched.
     *
     * The ledger tombstone is applied in memory on the caller thread first so publication
     * cannot race the worker. Journal load/save always run off Main.
     */
    @JvmStatic fun discard(id: String): Boolean {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return false
        // Permanent refusal visible to publication gates before any disk I/O.
        FlightPublicationLedger.remember(trimmed, FlightPublicationLedger.Disposition.DISCARDED)
        val main = android.os.Looper.getMainLooper()
        if (main != null && android.os.Looper.myLooper() == main) {
            var matched = false
            synchronized(this) {
                if (loaded) {
                    matched = all().any { it.optString("id") == trimmed }
                    if (matched) {
                        entries = JSONArray(all().filterNot { it.optString("id") == trimmed })
                        publishChoices()
                    }
                }
            }
            CaltopoRecoveryWork.offMainArchive {
                runCatching { discardBody(trimmed) }.onFailure { error ->
                    CaltopoClient.CTWarn("AwaitingMapFlights", "discard persist failed: ${error.message}", error)
                }
            }
            return matched
        }
        return synchronized(this) { discardBody(trimmed) }
    }

    @Synchronized
    private fun discardBody(id: String): Boolean {
        load()
        val matched = all().firstOrNull { it.optString("id") == id } ?: return false
        entries = JSONArray(all().filterNot { it.optString("id") == id })
        publishChoices()
        save()
        return true
    }
    /** Copies of every journal entry, including decided ones, for clue ownership checks. */
    @Synchronized fun allEntries(): List<JSONObject> { load(); return all().map { JSONObject(it.toString()) } }
    @JvmStatic @Synchronized fun published(id: String) {
        confirmArchive(archiveId = id, remote = null, flightId = id, folderTitle = null)
    }

    /**
     * Durable CalTopo Shape-archive confirmation. Prunes confirmed rows so stale
     * queued/published entries cannot linger. Never deletes unpublished review/local offers.
     */
    @JvmStatic @JvmOverloads @Synchronized fun confirmArchive(
        archiveId: String,
        remote: String? = null,
        flightId: String? = null,
        folderTitle: String? = null,
    ): Boolean {
        load()
        val trimmedId = archiveId.trim()
        if (trimmedId.isEmpty()) return false
        val match = all().lastOrNull { entry ->
            val id = entry.optString("id")
            when {
                flightId != null && id == flightId -> true
                id == trimmedId -> true
                remote != null && entry.optString("remote") == remote &&
                    entry.optString("decision") in listOf(
                        "review", "queued", "publish", "bound", "published",
                    ) -> true
                else -> false
            }
        }
        if (match != null) {
            match.put("decision", "published")
            match.put("caltopoArchiveId", trimmedId)
            match.put("archiveConfirmed", true)
            match.put("finished", true)
            if (!folderTitle.isNullOrBlank()) match.put("caltopoArchiveFolder", folderTitle)
            val points = match.optJSONArray("points")
            if (points != null && points.length() > 1) {
                match.put("points", JSONArray().put(points.get(0)).put(points.get(points.length() - 1)))
            }
            FlightPublicationLedger.remember(
                match.optString("id").ifEmpty { trimmedId },
                FlightPublicationLedger.Disposition.PUBLISHED,
            )
        }
        val remaining = all().filterNot {
            TrackArchiveConfirmation.isConfirmed(
                decision = it.optString("decision"),
                archiveConfirmed = it.optBoolean("archiveConfirmed"),
                hasArchiveId = it.optString("caltopoArchiveId").isNotEmpty(),
            )
        }
        if (match == null && remaining.size == entries.length()) {
            // Still record the ledger tombstone when the awaiting-map row was already pruned.
            if (flightId != null) {
                FlightPublicationLedger.remember(flightId, FlightPublicationLedger.Disposition.PUBLISHED)
                return true
            }
            return false
        }
        entries = JSONArray(remaining)
        saveOffMainIfNeeded()
        publishChoices()
        return true
    }

    /** Removes entries whose CalTopo Shape archive is already confirmed. */
    @JvmStatic @Synchronized fun pruneConfirmedArchives() {
        load()
        val remaining = all().filterNot {
            TrackArchiveConfirmation.isConfirmed(
                decision = it.optString("decision"),
                archiveConfirmed = it.optBoolean("archiveConfirmed"),
                hasArchiveId = it.optString("caltopoArchiveId").isNotEmpty(),
            )
        }
        if (remaining.size == entries.length()) return
        entries = JSONArray(remaining)
        saveOffMainIfNeeded()
        publishChoices()
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
