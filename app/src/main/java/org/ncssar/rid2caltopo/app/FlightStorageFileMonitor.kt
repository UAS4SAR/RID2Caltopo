package org.ncssar.rid2caltopo.app

import android.os.FileObserver
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Inotify reports native recorder and private-file activity without polling the archive. */
internal class FlightStorageFileMonitor(roots: List<File>, private val changed: (File) -> Unit) {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val watchers = mutableMapOf<String, FileObserver>()
    private val pending = mutableSetOf<String>()
    @Volatile private var stopped = false
    init { worker.execute { roots.forEach { it.mkdirs(); discover(it) } } }
    fun stop() { stopped = true; worker.execute { watchers.values.forEach { it.stopWatching() }; watchers.clear(); worker.shutdown() } }
    private fun discover(directory: File) {
        if (stopped || !directory.isDirectory || directory.path in watchers) return
        val observer = object : FileObserver(directory, CREATE or DELETE or MODIFY or CLOSE_WRITE or MOVED_FROM or MOVED_TO or DELETE_SELF or MOVE_SELF) {
            override fun onEvent(event: Int, path: String?) {
                if (stopped) return
                val file = if (path == null) directory else File(directory, path)
                try { worker.execute {
                    if (stopped || !pending.add(file.path)) return@execute
                    // One-shot coalescing of actual write events, never an idle polling loop.
                    worker.schedule({
                        pending.remove(file.path)
                        if (!stopped) {
                            if (file.isDirectory) discover(file) else changed(file)
                            if (!file.exists()) {
                                val removed = watchers.keys.filter { it == file.path || it.startsWith(file.path + File.separator) }
                                removed.forEach { watchers.remove(it)?.stopWatching() }
                            }
                        }
                    }, 250, TimeUnit.MILLISECONDS)
                } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Already stopped. */ }
            }
        }
        watchers[directory.path] = observer
        observer.startWatching()
        directory.listFiles().orEmpty().forEach { if (it.isDirectory) discover(it) else changed(it) }
    }
}
