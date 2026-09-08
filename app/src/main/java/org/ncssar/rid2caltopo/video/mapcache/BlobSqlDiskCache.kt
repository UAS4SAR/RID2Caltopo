package org.ncssar.rid2caltopo.video.mapcache

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal data class CachedBlob(
    val bytes: ByteArray,
    val expiresAt: Long,
    val stale: Boolean
)

internal class BlobSqlDiskCache(
    private val cacheDir: File,
    private val dbName: String,
    private val maxBytes: Long,
    private val defaultTtlMs: Long
) : BlobCacheStore {
    private val lock = Any()
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val staleServedCount = AtomicLong(0)
    private val bytesUsedAtomic = AtomicLong(0L)

    private val db: SQLiteDatabase by lazy {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val dbFile = File(cacheDir, dbName)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS entries (
                    cache_key TEXT PRIMARY KEY,
                    blob_data BLOB NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    accessed_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            execSQL("CREATE INDEX IF NOT EXISTS idx_entries_accessed_at ON entries(accessed_at)")
            execSQL("CREATE INDEX IF NOT EXISTS idx_entries_expires_at ON entries(expires_at)")
        }
    }

    init {
        // Fail fast so callers can choose a fallback cache root if opening this DB path is invalid.
        db
        // Prime the bytes counter so snapshot() is always lock-free.
        bytesUsedAtomic.set(synchronized(lock) { bytesUsedLocked() })
    }

    override fun defaultExpiry(nowMs: Long): Long = nowMs + defaultTtlMs

    override fun put(cacheKey: String, bytes: ByteArray, expiresAtMs: Long) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("cache_key", cacheKey)
                put("blob_data", bytes)
                put("size_bytes", bytes.size)
                put("created_at", now)
                put("accessed_at", now)
                put("expires_at", expiresAtMs)
            }
            db.replaceOrThrow("entries", null, cv)
            MapCacheDebug.log("sql put db=$dbName key=$cacheKey bytes=${bytes.size} expiresAt=$expiresAtMs dir=${cacheDir.absolutePath}")
            bytesUsedAtomic.set(bytesUsedLocked())
        }
    }

    override fun get(cacheKey: String, countHitMiss: Boolean): CachedBlob? {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val cursor = db.query(
                "entries",
                arrayOf("blob_data", "expires_at"),
                "cache_key = ?",
                arrayOf(cacheKey),
                null,
                null,
                null
            )
            cursor.use {
                if (!it.moveToFirst()) {
                    if (countHitMiss) missCount.incrementAndGet()
                    if (countHitMiss) {
                        MapCacheDebug.log("sql miss db=$dbName key=$cacheKey dir=${cacheDir.absolutePath}")
                    }
                    return null
                }
                val bytes = it.getBlob(0)
                val expiresAt = it.getLong(1)
                val stale = expiresAt < now
                val touch = ContentValues().apply { put("accessed_at", now) }
                db.update("entries", touch, "cache_key = ?", arrayOf(cacheKey))
                if (countHitMiss) hitCount.incrementAndGet()
                if (countHitMiss) {
                    MapCacheDebug.log(
                        "sql hit db=$dbName key=$cacheKey bytes=${bytes.size} stale=$stale dir=${cacheDir.absolutePath}"
                    )
                }
                return CachedBlob(bytes = bytes, expiresAt = expiresAt, stale = stale)
            }
        }
    }

    override fun getExpiration(cacheKey: String): Long? {
        synchronized(lock) {
            val cursor = db.query(
                "entries",
                arrayOf("expires_at"),
                "cache_key = ?",
                arrayOf(cacheKey),
                null,
                null,
                null
            )
            cursor.use {
                if (!it.moveToFirst()) return null
                return it.getLong(0)
            }
        }
    }

    override fun exists(cacheKey: String): Boolean = getExpiration(cacheKey) != null

    override fun remove(cacheKey: String): Boolean {
        synchronized(lock) {
            val result = db.delete("entries", "cache_key = ?", arrayOf(cacheKey)) > 0
            if (result) bytesUsedAtomic.set(bytesUsedLocked())
            return result
        }
    }

    override fun clear() {
        synchronized(lock) {
            db.delete("entries", null, null)
            bytesUsedAtomic.set(0L)
        }
    }

    // Lock-free: all fields are AtomicLong; bytesUsedAtomic is kept current by put/remove/clear.
    override fun snapshot(): CacheStatsSnapshot = CacheStatsSnapshot(
        hits = hitCount.get(),
        misses = missCount.get(),
        bytesUsed = bytesUsedAtomic.get(),
        evictions = evictionCount.get(),
        staleServed = staleServedCount.get()
    )

    override fun markStaleServed() {
        staleServedCount.incrementAndGet()
    }

    override fun usageBytes(): Long = synchronized(lock) {
        bytesUsedLocked().also(bytesUsedAtomic::set)
    }

    override fun runMaintenance(
        maxEntryAgeCutoffMs: Long,
        trimToBytes: Long,
        shouldContinue: () -> Boolean
    ): CacheMaintenanceResult {
        synchronized(lock) {
            var agedOutEntries = 0
            var trimEvictedEntries = 0
            var bytesFreed = 0L

            if (maxEntryAgeCutoffMs > 0L) {
                while (shouldContinue()) {
                    val victims = ArrayList<Pair<String, Long>>()
                    val cursor = db.query(
                        "entries",
                        arrayOf("cache_key", "size_bytes"),
                        "created_at < ?",
                        arrayOf(maxEntryAgeCutoffMs.toString()),
                        null,
                        null,
                        "created_at ASC",
                        "128"
                    )
                    cursor.use {
                        while (it.moveToNext()) {
                            victims += it.getString(0) to it.getLong(1)
                        }
                    }
                    if (victims.isEmpty()) break
                    for ((key, size) in victims) {
                        if (!shouldContinue()) break
                        if (db.delete("entries", "cache_key = ?", arrayOf(key)) > 0) {
                            agedOutEntries += 1
                            bytesFreed += size
                        }
                    }
                }
            }

            var current = bytesUsedLocked()
            val trimTarget = trimToBytes.coerceAtLeast(0L)
            while (current > trimTarget && shouldContinue()) {
                val victims = ArrayList<Pair<String, Long>>()
                val cursor = db.query(
                    "entries",
                    arrayOf("cache_key", "size_bytes"),
                    null,
                    null,
                    null,
                    null,
                    "accessed_at ASC",
                    "128"
                )
                cursor.use {
                    while (it.moveToNext()) {
                        victims += it.getString(0) to it.getLong(1)
                    }
                }
                if (victims.isEmpty()) break
                for ((key, size) in victims) {
                    if (!shouldContinue()) break
                    if (db.delete("entries", "cache_key = ?", arrayOf(key)) > 0) {
                        trimEvictedEntries += 1
                        bytesFreed += size
                        evictionCount.incrementAndGet()
                        current -= size
                    }
                    if (current <= trimTarget) break
                }
            }

            val remaining = bytesUsedLocked()
            bytesUsedAtomic.set(remaining)
            return CacheMaintenanceResult(
                agedOutEntries = agedOutEntries,
                trimEvictedEntries = trimEvictedEntries,
                bytesFreed = bytesFreed,
                bytesRemaining = remaining
            )
        }
    }

    private fun bytesUsedLocked(): Long {
        val cursor = db.rawQuery("SELECT COALESCE(SUM(size_bytes), 0) FROM entries", null)
        cursor.use {
            if (!it.moveToFirst()) return 0L
            return it.getLong(0)
        }
    }
}
