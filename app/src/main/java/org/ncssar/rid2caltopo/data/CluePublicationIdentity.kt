package org.ncssar.rid2caltopo.data

/**
 * One personal account identity for the team stored at Submit and the team
 * used when a connected map looks for pending clues.
 */
object CluePublicationIdentity {
    @JvmStatic
    fun publicationTeamId(): String {
        val personal = CaltopoPersonalSession.capture("")
        if (personal == null) return CaltopoClient.GetCaltopoCredentials().teamId.orEmpty().trim()
        val account = personal.accountID.trim().ifEmpty { CaltopoPersonalSession.accountID.trim() }
        // A personal session must not fall through to a team credential. That stored a
        // different id than the later retry and the clue never matched.
        return if (account.isNotEmpty()) "personal:$account" else ""
    }

    @JvmStatic
    fun normalizeTeamId(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (!trimmed.startsWith("personal:")) return trimmed
        val account = trimmed.removePrefix("personal:").trim()
        return if (account.isEmpty()) "personal:" else "personal:$account"
    }

    /** True when both sides name the same personal account, or the same team id. */
    @JvmStatic
    fun sameTeam(stored: String?, connected: String?): Boolean {
        val left = normalizeTeamId(stored)
        val right = normalizeTeamId(connected)
        if (left.isNotEmpty() && left == right) return true
        val leftAccount = personalAccount(left)
        val rightAccount = personalAccount(right)
        if (leftAccount != null && rightAccount != null) return leftAccount == rightAccount
        if (leftAccount == null && rightAccount == null) return false
        // personal: with no account was saved before the account id was copied.
        return left == "personal:" || right == "personal:"
    }

    private fun personalAccount(teamId: String): String? {
        if (!teamId.startsWith("personal:")) return null
        return teamId.removePrefix("personal:").trim().ifEmpty { null }
    }
}
