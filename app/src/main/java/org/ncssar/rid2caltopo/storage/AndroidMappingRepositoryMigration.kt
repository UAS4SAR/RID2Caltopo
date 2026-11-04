package org.ncssar.rid2caltopo.storage

import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest

/**
 * Archive Dir layout for the Local Maps repository. Mapping data is a top-level
 * `mapping/` tree (consistent with iOS) instead of living under `cache/local-maps/`,
 * so cache cleanup and cache accounting can never see or touch it.
 */
internal object MappingRepositoryLayout {
    const val ROOT = "mapping"
    const val LEGACY_ROOT = "cache/local-maps"
    const val MARKER = "$ROOT/.mapping-repo-migration-v1-done"

    /** [id] matches the OperationalStorage area id. */
    data class Move(val id: String, val legacy: String, val current: String)

    val MOVES: List<Move> = listOf(
        Move("missions", "$LEGACY_ROOT/mapping-missions", "$ROOT/missions"),
        Move("layers", "$LEGACY_ROOT/mapping-layers", "$ROOT/layers"),
        Move("processing", "$LEGACY_ROOT/mapping-processing", "$ROOT/processing"),
        Move("map-statistics", "$LEGACY_ROOT/mapping-storage-stats", "$ROOT/stats"),
    )

    fun move(areaId: String): Move? = MOVES.firstOrNull { it.id == areaId }

    private val mappingPath = Regex("(^|/)(mapping/(missions|layers|processing|stats)|mapping-(missions|layers|processing|storage-stats))(/|$)")

    /**
     * True for any retained mapping path (current or legacy), for plain archive paths and for
     * provider URIs with percent-encoded separators. Cache code must ignore these.
     */
    fun isMappingPath(path: String): Boolean =
        mappingPath.containsMatchIn(path.lowercase().replace("%2f", "/"))
}

/**
 * One-time relocation of Archive Dir mapping data from
 * `cache/local-maps/{mapping-missions,mapping-layers,mapping-processing,mapping-storage-stats}` to
 * `mapping/{missions,layers,processing,stats}`.
 *
 * Crash-safe: copy -> verify (size + SHA-256, then stable listing) -> commit marker -> remove old.
 * Originals are never deleted before the marker is durable, and afterwards only files whose
 * verified copy is identical to the still-unchanged source. Resumable at every step. Refuses
 * to run while any `processing-status.json` under missions says `"active": true`.
 *
 * Worker-only (never Main). Uses its own [ArchiveTree] so it holds no lock shared with
 * publication, live uploads, or bulk hydration.
 */
internal object AndroidMappingRepositoryMigration {
    enum class Outcome {
        /** No legacy data; the marker is (now) present. */
        NOTHING_TO_DO,
        /** Reconstruction/transfer is running; nothing was copied or removed. */
        DEFERRED_ACTIVE,
        /** Sources kept changing or verification failed; nothing was removed. */
        DEFERRED_UNSTABLE,
        /** Copied, verified, committed; legacy removed. */
        COMPLETED,
        /** Committed, but some legacy files were retained (changed, conflicting or unverified). */
        COMPLETED_WITH_LEFTOVERS,
    }

    data class Result(val outcome: Outcome, val files: Int = 0, val bytes: Long = 0, val removed: Int = 0, val retained: Int = 0) {
        val committed: Boolean get() = outcome == Outcome.NOTHING_TO_DO || outcome == Outcome.COMPLETED || outcome == Outcome.COMPLETED_WITH_LEFTOVERS
    }

    private const val MAX_STABLE_PASSES = 4
    private const val STATUS = "processing-status.json"
    private const val STATUS_SCAN_DEPTH = 4

    private data class Node(val path: String, val directory: Boolean, val bytes: Long, val modified: Long)

    /**
     * Cheap layout choice for a prepare session. The current layout is used once the marker
     * exists, or when no legacy tree exists (fresh archive). Failures propagate: guessing the
     * legacy layout after a transient error would hydrate an empty tree.
     */
    fun usesCurrentLayout(tree: ArchiveTree): Boolean =
        tree.exists(MappingRepositoryLayout.MARKER) || MappingRepositoryLayout.MOVES.none { tree.exists(it.legacy) }

    /** True when retained legacy mapping data (or its marker-less state) still needs [migrate]. */
    fun needsWork(tree: ArchiveTree): Boolean =
        !tree.exists(MappingRepositoryLayout.MARKER) || MappingRepositoryLayout.MOVES.any { tree.exists(it.legacy) }

    /**
     * @param localMissionRoots app-private/external mission trees; any active status there defers too
     * @param processingActive in-process reconstruction/cloud transfer indicator
     * @param onCommitted called once the marker is durable, before legacy removal (switch live layout here)
     */
    fun migrate(
        tree: ArchiveTree,
        localMissionRoots: List<File> = emptyList(),
        processingActive: () -> Boolean = { false },
        cancelled: () -> Unit = {},
        onCommitted: () -> Unit = {},
        log: (String) -> Unit = {},
    ): Result {
        val main = Looper.getMainLooper()
        check(main == null || Looper.myLooper() !== main) { "Mapping repository migration must run off Main" }
        cancelled()
        tree.refresh()
        val alreadyCommitted = tree.exists(MappingRepositoryLayout.MARKER)
        val moves = MappingRepositoryLayout.MOVES.filter { tree.exists(it.legacy) }
        if (moves.isEmpty()) {
            if (!alreadyCommitted) {
                ensureRoot(tree)
                writeMarker(tree, 0, 0L)
                log("Mapping repository: no legacy data; marker written")
            }
            return Result(Outcome.NOTHING_TO_DO)
        }
        if (isActive(tree, localMissionRoots, processingActive)) {
            log("Mapping repository migration deferred: reconstruction active")
            return Result(Outcome.DEFERRED_ACTIVE)
        }

        // Before the marker the legacy copy is authoritative; afterwards (sweep) the new copy is.
        val overwrite = !alreadyCommitted
        var verified: Map<String, List<Node>>? = null
        val conflicts = HashSet<String>()
        var files = 0
        var bytes = 0L
        for (pass in 1..MAX_STABLE_PASSES) {
            cancelled()
            tree.refresh()
            val listing = moves.associate { it.id to snapshot(tree, it.legacy, cancelled) }
            files = listing.values.sumOf { nodes -> nodes.count { !it.directory } }
            bytes = listing.values.sumOf { nodes -> nodes.filter { !it.directory }.sumOf { it.bytes } }
            ensureRoot(tree)
            conflicts.clear()
            for (move in moves) conflicts += copyAll(tree, move, listing.getValue(move.id), overwrite, cancelled)
            tree.refresh()
            val again = moves.associate { it.id to snapshot(tree, it.legacy, cancelled) }
            if (again == listing) { verified = listing; break }
            log("Mapping repository sources changed during copy pass=$pass")
        }
        if (verified == null) return Result(Outcome.DEFERRED_UNSTABLE, files, bytes)

        // Each verified source file must have an identically sized copy in the destination.
        tree.refresh()
        for (move in moves) {
            val destination = snapshot(tree, move.current, cancelled).filter { !it.directory }.associateBy { it.path }
            for (node in verified.getValue(move.id)) {
                if (node.directory || "${move.id}/${node.path}" in conflicts) continue
                val copy = destination[node.path]
                if (copy == null || copy.bytes != node.bytes) {
                    log("Mapping repository verification failed; legacy retained")
                    return Result(Outcome.DEFERRED_UNSTABLE, files, bytes)
                }
            }
        }
        // Reconstruction may have started while copying; its output is still landing in legacy.
        if (isActive(tree, localMissionRoots, processingActive)) {
            log("Mapping repository migration deferred before commit: reconstruction active")
            return Result(Outcome.DEFERRED_ACTIVE, files, bytes)
        }
        cancelled()
        if (!alreadyCommitted) writeMarker(tree, files, bytes)
        onCommitted()

        var removed = 0
        var retained = 0
        for (move in moves) {
            cancelled()
            tree.refresh()
            val now = snapshot(tree, move.legacy, cancelled)
            val expected = verified.getValue(move.id)
            val kept = conflicts.any { it.startsWith("${move.id}/") }
            if (now == expected && !kept) {
                tree.delete(move.legacy)
                removed += expected.count { !it.directory }
                continue
            }
            // Changed after verification, or a newer destination copy exists: remove only
            // verified-unchanged files whose destination is identical.
            val unchanged = expected.filter { !it.directory && "${move.id}/${it.path}" !in conflicts }.toSet()
            for (node in now.filter { !it.directory }) {
                if (node in unchanged) { tree.delete("${move.legacy}/${node.path}"); removed++ } else retained++
            }
            pruneEmptyDirectories(tree, move.legacy, now.filter { it.directory }.map { it.path })
        }
        runCatching {
            if (tree.exists(MappingRepositoryLayout.LEGACY_ROOT) &&
                tree.list(MappingRepositoryLayout.LEGACY_ROOT).isEmpty()
            ) tree.delete(MappingRepositoryLayout.LEGACY_ROOT)
        }
        log("Mapping repository migrated files=$files removed=$removed retained=$retained")
        return Result(if (retained == 0) Outcome.COMPLETED else Outcome.COMPLETED_WITH_LEFTOVERS, files, bytes, removed, retained)
    }

    private fun ensureRoot(tree: ArchiveTree) {
        tree.mkdirs(MappingRepositoryLayout.ROOT)
        if (!tree.exists("${MappingRepositoryLayout.ROOT}/.nomedia")) {
            tree.write("${MappingRepositoryLayout.ROOT}/.nomedia", byteArrayOf().inputStream())
        }
    }

    private fun writeMarker(tree: ArchiveTree, files: Int, bytes: Long) {
        val content = JSONObject().put("version", 1).put("files", files).put("bytes", bytes)
            .put("completedAt", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
        tree.write(MappingRepositoryLayout.MARKER, content.inputStream())
        check(tree.read(MappingRepositoryLayout.MARKER).use { it.readBytes() }.contentEquals(content)) {
            "Mapping migration marker could not be verified; legacy copies were retained."
        }
    }

    /** Recursive listing (relative paths) of one tree, sorted for stable comparison. */
    private fun snapshot(tree: ArchiveTree, root: String, cancelled: () -> Unit): List<Node> {
        val out = ArrayList<Node>()
        fun walk(path: String, relative: String) {
            cancelled()
            for (entry in tree.list(path)) {
                val rel = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
                if (entry.directory) {
                    out += Node(rel, true, 0L, 0L)
                    walk("$path/${entry.name}", rel)
                } else out += Node(rel, false, entry.bytes, entry.modified)
            }
        }
        if (tree.exists(root)) walk(root, "")
        return out.sortedBy { it.path }
    }

    /** Returns `"<id>/<relative>"` for every source whose destination differs and was kept (sweep conflicts). */
    private fun copyAll(tree: ArchiveTree, move: MappingRepositoryLayout.Move, nodes: List<Node>, overwrite: Boolean, cancelled: () -> Unit): Set<String> {
        val conflicts = HashSet<String>()
        tree.mkdirs(move.current)
        for (node in nodes.filter { it.directory }) tree.mkdirs("${move.current}/${node.path}")
        for (node in nodes) {
            if (node.directory) continue
            cancelled()
            if (!copyFile(tree, "${move.legacy}/${node.path}", "${move.current}/${node.path}", node.bytes, overwrite)) {
                conflicts += "${move.id}/${node.path}"
            }
        }
        return conflicts
    }

    /**
     * Raw byte copy (sidecars, versions and chunks included). A same-content destination is
     * skipped (resume). A different destination is replaced before the marker; after it,
     * the destination wins and the source stays behind as a retained leftover.
     * Returns true when the destination now holds the source's exact bytes.
     */
    private fun copyFile(tree: ArchiveTree, source: String, destination: String, bytes: Long, overwrite: Boolean): Boolean {
        if (tree.exists(destination)) {
            val parent = destination.substringBeforeLast('/')
            val existing = tree.list(parent).firstOrNull { it.name == destination.substringAfterLast('/') }
            if (existing != null && !existing.directory && existing.bytes == bytes && sameContent(tree, source, destination)) return true
            if (!overwrite) return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        tree.read(source).use { input ->
            tree.write(destination, object : FilterInputStream(input) {
                override fun read(): Int = super.read().also { if (it >= 0) { digest.update(it.toByte()); copied++ } }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { digest.update(b, off, it); copied += it } }
            })
        }
        val expected = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(copied == bytes || bytes == 0L) { "Mapping file changed while copying; legacy retained." }
        val actual = tree.read(destination).use { VerifiedArchiveTree.digest(it) }
        check(actual == expected) { "Mapping copy verification failed; legacy retained." }
        return true
    }

    private fun sameContent(tree: ArchiveTree, a: String, b: String): Boolean =
        tree.read(a).use { VerifiedArchiveTree.digest(it) } == tree.read(b).use { VerifiedArchiveTree.digest(it) }

    private fun pruneEmptyDirectories(tree: ArchiveTree, root: String, directories: List<String>) {
        for (relative in directories.sortedByDescending { it.length }) {
            val path = "$root/$relative"
            if (tree.exists(path) && tree.list(path).isEmpty()) tree.delete(path)
        }
        if (tree.exists(root) && tree.list(root).isEmpty()) tree.delete(root)
    }

    // --- active reconstruction detection -------------------------------------------------

    private fun isActive(tree: ArchiveTree, localRoots: List<File>, processingActive: () -> Boolean): Boolean {
        if (processingActive()) return true
        for (root in localRoots) if (localActive(root)) return true
        val missions = MappingRepositoryLayout.move("missions") ?: return false
        // Legacy is the live tree until committed; the new tree is checked too for sweeps.
        return listOf(missions.legacy, missions.current).any { tree.exists(it) && archiveActive(tree, it, 0) }
    }

    private fun archiveActive(tree: ArchiveTree, directory: String, depth: Int): Boolean {
        for (entry in tree.list(directory)) {
            if (entry.directory) {
                if (entry.name == ".r2c-versions" || depth >= STATUS_SCAN_DEPTH) continue
                if (archiveActive(tree, "$directory/${entry.name}", depth + 1)) return true
            } else if (entry.name == STATUS) {
                // An unreadable status is unknown, so treat it as active (fail safe).
                val active = runCatching { statusActive(tree.read("$directory/${entry.name}").use { VerifiedArchiveTree.bytes(it, 1024 * 1024) }) }
                    .getOrElse { error -> if (error is org.json.JSONException) false else true }
                if (active) return true
            }
        }
        return false
    }

    private fun localActive(root: File): Boolean {
        if (!root.isDirectory) return false
        return root.walkTopDown().maxDepth(STATUS_SCAN_DEPTH + 1).any { file ->
            file.isFile && file.name == STATUS &&
                runCatching { statusActive(file.readBytes()) }.getOrElse { error -> error !is org.json.JSONException }
        }
    }

    private fun statusActive(bytes: ByteArray): Boolean =
        JSONObject(String(bytes, Charsets.UTF_8)).optBoolean("active", false)
}
