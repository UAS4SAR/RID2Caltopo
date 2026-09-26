package org.ncssar.rid2caltopo.ui

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Separate from organization configuration: importing settings must never accept terms. */
internal object ApplicationTerms {
    const val version = "2026-09-25.1"
    val fingerprint: String get() = fingerprint(LAUNCH_DISCLAIMER_TEXT)
    fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal data class TermsAcceptance(
    val version: String,
    val text: String,
    val acceptedAtMillis: Long,
) {
    fun isCurrent(currentVersion: String = ApplicationTerms.version, currentText: String = LAUNCH_DISCLAIMER_TEXT) =
        version == currentVersion && text == currentText && acceptedAtMillis > 0

    fun encode(): String = JSONObject().put("version", version).put("text", text)
        .put("acceptedAtMillis", acceptedAtMillis).toString()

    fun logMessage(event: String): String =
        "$event version=$version sha256=${ApplicationTerms.fingerprint(text)} acceptedAt=${java.time.Instant.ofEpochMilli(acceptedAtMillis)} scope=local-installation"

    companion object {
        fun decode(value: String?): TermsAcceptance? = try {
            val json = JSONObject(value ?: "")
            TermsAcceptance(json.getString("version"), json.getString("text"), json.getLong("acceptedAtMillis"))
        } catch (_: Exception) { null }
    }
}

internal class TermsAcceptanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("application_terms_acceptance", Context.MODE_PRIVATE)
    fun read(): TermsAcceptance? = runCatching { TermsAcceptance.decode(preferences.getString("record", null)) }.getOrNull()
    // One record prevents a partial version/timestamp update from being treated as acceptance.
    fun save(record: TermsAcceptance): Boolean = runCatching {
        preferences.edit().putString("record", record.encode()).commit()
    }.getOrDefault(false)
}
