package org.ncssar.rid2caltopo.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.URLDecoder

/** One app-private link that pointed at the working copy on a card that is no longer in the tablet. */
internal data class DetachedArea(val volume: String, val areaId: String, val target: String)

/** Quiet Manage Storage note: data that stayed on a card the operator swapped out. */
internal data class DetachedCardNote(val volume: String, val areas: Set<String>)

/**
 * App-private record of what stayed behind on a removed card. Only the app's own pointers are
 * kept here; files on the card are never touched. Lives in noBackupFilesDir, which is never a
 * link to a card.
 */
internal class DetachedAreaLedger(private val file: File) {
    @Synchronized fun load(): List<DetachedArea> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 3 && parts.all { it.isNotBlank() }) DetachedArea(parts[0], parts[1], parts[2]) else null
        }.distinct()
    }.getOrDefault(emptyList())

    @Synchronized fun add(entry: DetachedArea): List<DetachedArea> = save((load().filterNot { it.areaId == entry.areaId && it.target == entry.target }) + entry)
    @Synchronized fun remove(entry: DetachedArea): List<DetachedArea> = save(load() - entry)
    @Synchronized fun forget(volume: String): List<DetachedArea> = save(load().filterNot { it.volume.equals(volume, ignoreCase = true) })

    private fun save(entries: List<DetachedArea>): List<DetachedArea> {
        if (entries.isEmpty()) { file.delete(); return entries }
        file.parentFile?.mkdirs()
        val staged = File(file.parentFile, file.name + ".partial")
        staged.writeText(entries.joinToString("") { "${it.volume}\t${it.areaId}\t${it.target}\n" })
        if (!staged.renameTo(file)) { file.delete(); check(staged.renameTo(file)) { "Cannot save ${file.name}" } }
        return entries
    }
}

/**
 * A card the operator deliberately swapped is not an error (Ken, 2026-11-18). When the active
 * archive folder is writable and an app-private link still points at the working copy on a card
 * that is gone, the link is dropped and rebuilt on the active archive. What stayed on the old
 * card is remembered here for a quiet, dismissible note; reinserting the card copies it into
 * the active archive folder, and Forget drops only these records. Nothing deletes card files.
 */
internal object DetachedPreviousStorage {
    private const val FILE_NAME = "detached-previous-storage.tsv"
    private val PROJECTION = Regex("^/(?:storage|mnt/media_rw)/([^/]+)/Android/data/org\\.ncssar\\.rid2caltopo/cache/operational-work/[0-9a-f]{16}/([^/]+)/?$")

    /** Areas that can hold the operator's own unfinished work. Pure caches are rebuilt silently. */
    val NOTED_AREAS = setOf(
        "recordings", "unfinished-recordings", "recording-work", "recording-originals",
        "missions", "layers", "clues", "review-annotations", "map-archives",
    )

    private val _notes = MutableStateFlow<List<DetachedCardNote>>(emptyList())
    val notes: StateFlow<List<DetachedCardNote>> = _notes
    @Volatile private var ledger: DetachedAreaLedger? = null

    /**
     * The missing card's volume id when [linkTarget] is this app's working copy of [areaId] on a
     * removable card that is not mounted; null for anything else (those keep the old, safe stop).
     */
    fun detachableVolume(areaId: String, linkTarget: String?, mounted: (String) -> Boolean): String? {
        val match = PROJECTION.matchEntire(linkTarget ?: return null) ?: return null
        val volume = match.groupValues[1]
        if (volume == "emulated" || volume == "self" || match.groupValues[2] != areaId) return null
        return volume.takeUnless(mounted)
    }

    fun attach(context: Context) { if (ledger == null) attach(File(context.noBackupFilesDir, FILE_NAME)) }

    @Synchronized fun attach(file: File) {
        ledger = DetachedAreaLedger(file)
        publish(ledger!!.load())
    }

    fun entries(): List<DetachedArea> = ledger?.load().orEmpty()

    /** Records a dropped link; pure caches are not noted. */
    fun detached(entry: DetachedArea) {
        if (entry.areaId !in NOTED_AREAS) return
        ledger?.let { publish(it.add(entry)) }
    }

    /** The card came back and this area was copied into the active archive folder. */
    fun recovered(entry: DetachedArea) { ledger?.let { publish(it.remove(entry)) } }

    /** Forget: drops only the app's records for [volume]; files on that card stay untouched. */
    fun forget(volume: String) { ledger?.let { publish(it.forget(volume)) } }

    internal fun resetForTesting() { ledger = null; _notes.value = emptyList() }

    private fun publish(entries: List<DetachedArea>) {
        _notes.value = entries.groupBy { it.volume.uppercase() }.map { (volume, items) -> DetachedCardNote(volume, items.map { it.areaId }.toSet()) }.sortedBy { it.volume }
    }

    /**
     * A leftover session log whose archive file sat on a card that is gone is copied to the same
     * day folder in the active archive instead ("pending:<day>/<name>"). Null keeps the old target.
     */
    fun retargetSessionLogKey(key: String, activeVolume: String?, mounted: (String) -> Boolean): String? {
        if (!key.startsWith("content://com.android.externalstorage.documents/")) return null
        val document = key.substringAfter("/document/", "").takeIf { it.isNotEmpty() } ?: return null
        val id = runCatching { URLDecoder.decode(document, "UTF-8") }.getOrNull() ?: return null
        val volume = id.substringBefore(':', "").takeIf { it.isNotEmpty() && it != "primary" } ?: return null
        if (activeVolume == null || volume.equals(activeVolume, ignoreCase = true) || mounted(volume)) return null
        val segments = id.substringAfter(':').split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        return "pending:${segments[segments.size - 2]}/${segments.last()}"
    }
}

internal object DetachedPreviousStorageText {
    const val FORGET = "Forget"

    fun note(note: DetachedCardNote): String {
        val what = MissingPreviousStorageText.describe(note.areas)
        return "Data from SD card ${note.volume} stayed on that card ($what). Reinsert it to copy that data into " +
            "the current archive folder, or tap $FORGET. Forget only clears this note; no files are deleted."
    }
}
