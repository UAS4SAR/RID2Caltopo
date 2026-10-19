package org.ncssar.rid2caltopo.mapping

import java.io.File
import java.io.IOException

/** Library refreshes may overlap deletion. A vanished directory is an empty branch. */
internal object MappingLibraryFiles {
    fun contains(root: File, extension: String, children: (File) -> Array<File>? = { it.listFiles() }): Boolean {
        val pending = java.util.ArrayDeque<File>()
        val visited = HashSet<String>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val file = pending.removeLast()
            if (file.isFile) {
                if (file.extension == extension) return true
            } else if (file.isDirectory) {
                // Card-backed layers use aliases; avoid looping through any nested aliases.
                val path = try { file.canonicalPath } catch (_: IOException) { continue }
                if (visited.add(path)) children(file)?.forEach(pending::add)
            }
        }
        return false
    }
}
