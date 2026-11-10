package org.ncssar.rid2caltopo.data

import org.json.JSONObject

/**
 * Pilot and claim facts that must survive a crash. Absent facts stay absent:
 * a legacy journal is not given a pilot, and an unverified Mine selection is
 * not treated as tracker-verified ownership.
 *
 * Unconfirmed flights (no Mine / Not Mine) follow [CurrentFlightPublicationGate]:
 * they stay local and do not publish. The omitted "option 1" wording from the
 * earlier discussion is not in the repo; this is the explicit policy already
 * implemented on both platforms, not a guess that an unselected flight is owned.
 */
object FlightOwnershipContext {
    const val MINE = "mine"
    const val NOT_MINE = "not_mine"
    const val IGNORED = "ignored"
    const val UNCONFIRMED = "unconfirmed"
    const val VERIFIED = "verified"
    const val PENDING = "pending"
    const val REJECTED = "rejected"
    const val CONFLICT = "conflict"
    const val EXPIRED = "expired"
    const val NONE = "none"

    data class Snapshot(
        val known: Boolean,
        val confirmation: String = "",
        val claim: String = "",
        val pilot: String = "",
        val org: String = "",
        val model: String = "",
        val mappedId: String = "",
        val localOnly: Boolean = false,
    ) {
        /** Pilot entered for a Mine selection. Never invented, and never copied from another claim. */
        val recordedPilot: String get() = if (known && confirmation == MINE) pilot.trim() else ""
        val adverseClaim: Boolean get() = isAdverseClaim(claim)
        /** Mine was selected and tracker has not verified it, or the journal has no claim context. */
        val requiresHold: Boolean get() = !known || (confirmation == MINE && adverseClaim)
        /** A recorded Mine selection whose claim is verified, or was never sent to tracker. */
        val mayAutoPublish: Boolean get() = known && confirmation == MINE && !adverseClaim && !localOnly
        /** Known Not Mine / ignored / unconfirmed: retain the flight and do not offer publication. */
        val blocksOperatorPublish: Boolean get() = known && !requiresHold && !mayAutoPublish
    }

    @JvmStatic fun isAdverseClaim(claim: String): Boolean =
        claim == PENDING || claim == REJECTED || claim == CONFLICT || claim == EXPIRED

    /** Tracker gave this flight to another tablet. A plain review must not publish it. */
    @JvmStatic fun isLostToAnotherTablet(claim: String): Boolean =
        claim == REJECTED || claim == CONFLICT || claim == EXPIRED

    @JvmStatic fun legacy(): Snapshot = Snapshot(known = false)

    @JvmStatic fun capture(
        confirmed: Boolean,
        ignored: Boolean,
        localOnly: Boolean,
        claim: String,
        pilot: String?,
        org: String?,
        model: String?,
        mappedId: String?,
    ): Snapshot {
        val confirmation = when {
            ignored -> IGNORED
            localOnly || (confirmed && localOnly) -> NOT_MINE
            confirmed -> MINE
            else -> UNCONFIRMED
        }
        val normalized = when (claim) {
            VERIFIED, PENDING, REJECTED, CONFLICT, EXPIRED, NONE -> claim
            else -> NONE
        }
        val mine = confirmation == MINE
        return Snapshot(
            known = true,
            confirmation = confirmation,
            claim = normalized,
            pilot = if (mine) pilot?.trim().orEmpty() else "",
            org = org?.trim().orEmpty(),
            model = model?.trim().orEmpty(),
            mappedId = mappedId?.trim().orEmpty(),
            localOnly = !mine,
        )
    }

    /** Current in-memory drone. Claim "none" means tracker was not asked; that is not a failed verification. */
    @JvmStatic fun capture(spec: CtDroneSpec): Snapshot {
        val claim = runCatching {
            R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.ownershipClaimState(spec.remoteId)
        }.getOrDefault(NONE)
        val confirmed = spec.isCurrentFlightConfirmed
        val localOnly = spec.isLocalArchiveOnly
        return capture(
            confirmed = confirmed,
            ignored = localOnly && !confirmed,
            localOnly = localOnly,
            claim = claim,
            pilot = spec.owner,
            org = spec.org,
            model = spec.model,
            mappedId = spec.mappedId,
        )
    }

    @JvmStatic fun reviewMessage(known: Boolean, claim: String, pilot: String, publicationBlocked: Boolean): String {
        if (publicationBlocked) {
            return "This flight ended without Mine selected. It stays on this device and will not be published or given a pilot."
        }
        val reason = when {
            !known -> "This flight was recovered without a recorded ownership claim."
            claim == CONFLICT -> "Tracker reported a conflicting claim. Publishing will not adopt the other pilot."
            claim == REJECTED -> "Tracker rejected this ownership claim."
            claim == EXPIRED -> "The tracker ownership lease expired before it was verified."
            else -> "Tracker did not verify ownership before this flight ended."
        }
        val who = if (pilot.isNotBlank()) "Recorded pilot: $pilot." else "No pilot was recorded. Publishing will not name an RPIC."
        return "$reason $who Confirm this flight before publishing."
    }

    @JvmStatic fun toJson(snapshot: Snapshot): JSONObject {
        val row = JSONObject().put("type", "context").put("known", snapshot.known)
        if (!snapshot.known) return row
        row.put("confirmation", snapshot.confirmation)
            .put("claim", snapshot.claim)
            .put("localOnly", snapshot.localOnly)
        if (snapshot.pilot.isNotEmpty()) row.put("pilot", snapshot.pilot)
        if (snapshot.org.isNotEmpty()) row.put("org", snapshot.org)
        if (snapshot.model.isNotEmpty()) row.put("model", snapshot.model)
        if (snapshot.mappedId.isNotEmpty()) row.put("mappedId", snapshot.mappedId)
        return row
    }

    @JvmStatic fun fromJson(row: JSONObject?): Snapshot {
        if (row == null || row.optString("type") != "context" || !row.optBoolean("known")) return legacy()
        return Snapshot(
            known = true,
            confirmation = row.optString("confirmation"),
            claim = row.optString("claim").ifEmpty { NONE },
            pilot = row.optString("pilot"),
            org = row.optString("org"),
            model = row.optString("model"),
            mappedId = row.optString("mappedId"),
            localOnly = row.optBoolean("localOnly"),
        )
    }
}
