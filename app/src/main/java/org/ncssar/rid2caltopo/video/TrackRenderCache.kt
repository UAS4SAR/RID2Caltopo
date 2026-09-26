package org.ncssar.rid2caltopo.video

/** Owned by one map. Callers supply immutable point snapshots, never a live mutable list. */
internal class TrackRenderCache<T> {
    private data class Entry<T>(val render: T, var points: List<LocalTrackPoint>)
    private val entries = mutableMapOf<String, Entry<T>>()

    fun getOrUpdate(
        key: String,
        points: List<LocalTrackPoint>,
        create: () -> T,
        update: (T, List<LocalTrackPoint>) -> Unit
    ): T {
        val entry = entries[key]
        if (entry == null) {
            val render = create()
            update(render, points)
            entries[key] = Entry(render, points)
            return render
        }
        if (entry.points != points) {
            update(entry.render, points)
            entry.points = points
        }
        return entry.render
    }

    fun retainKeys(keys: Set<String>) {
        entries.keys.retainAll(keys)
    }
}
