package org.ncssar.rid2caltopo.data

import org.json.JSONObject

/**
 * Local Drone Ownership, edited like a RID Map entry.
 * Standalone devices and a current config_admin may change the value on this device.
 * Other organization members may read it. Organization distribution is the existing
 * System Admin configuration upload, not a settings-time network call.
 */
object DroneOwnershipPolicy {
    const val ALL = "all_detected"
    const val STREAMING_DEVICE = "streaming_device"
    const val SHARED_INCIDENT = "streaming_shared_incident"
    const val STANDALONE = "standalone"

    @JvmStatic fun scope(enrolled: Boolean, trackerPrefix: String?): String {
        if (!enrolled) return STANDALONE
        val prefix = trackerPrefix?.trim()?.trimEnd('/').orEmpty()
        return if (prefix.isEmpty()) STANDALONE else prefix
    }

    @JvmStatic fun normalize(value: String?): String = when (value) {
        STREAMING_DEVICE, SHARED_INCIDENT -> value
        else -> ALL
    }

    /** Same gate as RID Map editing: no organization, or a current config_admin check. */
    @JvmStatic fun canChange(belongsToOrganization: Boolean, configAdminVerified: Boolean): Boolean =
        !belongsToOrganization || configAdminVerified

    data class Record(val mode: String, val appliedMode: String, val staged: Boolean) {
        fun toJson(): JSONObject = JSONObject()
            .put("mode", mode).put("applied", appliedMode).put("staged", staged)

        companion object {
            val initial = Record(ALL, ALL, false)
            fun fromJson(value: JSONObject?): Record {
                if (value == null) return initial
                val mode = normalize(value.optString("mode"))
                val applied = normalize(value.optString("applied", mode))
                return Record(mode, applied, value.optBoolean("staged") && mode != applied)
            }
        }
    }

    /** A local edit takes effect immediately. It stays staged until a published snapshot adopts it. */
    @JvmStatic fun edit(record: Record, mode: String): Record {
        val next = normalize(mode)
        return Record(next, record.appliedMode, next != record.appliedMode)
    }

    /**
     * A refresh of the already-applied organization value does not replace a staged local edit.
     * A different published value replaces it, the same way a newer RID map snapshot replaces
     * the saved aircraft list.
     */
    @JvmStatic fun receive(record: Record, published: String): Record {
        val incoming = normalize(published)
        if (record.staged && incoming == record.appliedMode) return record
        return Record(incoming, incoming, false)
    }
}
