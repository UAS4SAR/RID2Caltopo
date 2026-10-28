package org.ncssar.rid2caltopo.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-owned UI cache plus coalesced, serial durable writes. No I/O under its queue lock. */
internal class OrderedBooleanSettings(
    initial: Map<String, Boolean>,
    private val dispatch: (() -> Unit) -> Unit,
    private val persist: (String, Boolean) -> Boolean,
    private val failed: (String, Throwable?) -> Unit = { _, _ -> }
) {
    data class Value(val enabled: Boolean, val pending: Boolean = false, val failed: Boolean = false,
                     internal val revision: Long = 0)
    private data class Write(val key: String, val value: Value)
    private val values = ConcurrentHashMap(initial.mapValues { Value(it.value) })
    private val lock = Any()
    private val pending = LinkedHashMap<String, Write>()
    private var running = false
    private var revision = 0L
    private val updates = MutableStateFlow(0L)
    val changes = updates.asStateFlow()
    fun get(key: String): Value = values[key] ?: Value(false)
    fun set(key: String, enabled: Boolean) {
        val start = synchronized(lock) {
            val write = Write(key, Value(enabled, pending = true, revision = ++revision))
            values[key] = write.value
            pending[key] = write
            if (running) false else { running = true; true }
        }
        updates.update { it + 1 }
        if (start) dispatch(::drain)
    }
    private fun drain() {
        while (true) {
            val write = synchronized(lock) {
                val first = pending.entries.firstOrNull()
                if (first == null) { running = false; null }
                else first.value.also { pending.remove(first.key) }
            } ?: return
            var error: Throwable? = null
            val saved = try { persist(write.key, write.value.enabled) }
                catch (failure: Throwable) { error = failure; false }
            values.computeIfPresent(write.key) { _, current ->
                if (current.revision == write.value.revision) current.copy(pending = false, failed = !saved)
                else current
            }
            updates.update { it + 1 }
            if (!saved) failed(write.key, error)
        }
    }
}
