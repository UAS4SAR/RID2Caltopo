package org.ncssar.rid2caltopo.data

import java.util.Base64
import org.json.JSONObject

object MutualAidPackageTransferToken {
    const val MAGIC_PREFIX = "R2CMAPKG1:"

    data class Config(
        val host: String,
        val port: Int,
        val sessionId: String,
        val packageName: String,
        val sizeBytes: Long,
        val sha256: String,
        val tlsPublicKeySha256: String,
        val expiresAtEpochMs: Long,
        val version: Int = 1
    )

    fun encode(config: Config): String {
        val json = JSONObject()
            .put("h", config.host)
            .put("p", config.port)
            .put("s", config.sessionId)
            .put("n", config.packageName)
            .put("z", config.sizeBytes)
            .put("d", config.sha256)
            .put("k", config.tlsPublicKeySha256)
            .put("e", config.expiresAtEpochMs)
            .put("v", config.version)
            .toString()
        val xored = xorBytes(json.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder().encodeToString(xored)
        val remapped = buildString(b64.length) {
            for (c in b64) {
                val idx = STD_ALPHABET.indexOf(c)
                append(if (idx >= 0) CUSTOM_ALPHABET[idx] else c)
            }
        }
        return MAGIC_PREFIX + remapped
    }

    fun decode(token: String): Config? {
        return try {
            val normalized = normalize(token)
            if (!normalized.startsWith(MAGIC_PREFIX)) return null
            val encoded = normalized.removePrefix(MAGIC_PREFIX)
            val remapped = buildString(encoded.length) {
                for (c in encoded) {
                    val idx = CUSTOM_ALPHABET.indexOf(c)
                    append(if (idx >= 0) STD_ALPHABET[idx] else c)
                }
            }
            val xored = Base64.getDecoder().decode(remapped)
            val json = JSONObject(String(xorBytes(xored), Charsets.UTF_8))
            Config(
                host = json.optString("h", ""),
                port = json.optInt("p", 0),
                sessionId = json.optString("s", ""),
                packageName = json.optString("n", ""),
                sizeBytes = json.optLong("z", 0L),
                sha256 = json.optString("d", ""),
                tlsPublicKeySha256 = json.optString("k", ""),
                expiresAtEpochMs = json.optLong("e", 0L),
                version = json.optInt("v", 1)
            ).takeIf {
                it.host.isNotBlank() &&
                    it.port > 0 &&
                    it.sessionId.isNotBlank() &&
                    it.sha256.isNotBlank() &&
                    it.tlsPublicKeySha256.isNotBlank() &&
                    it.expiresAtEpochMs > 0L
            }
        } catch (_: Exception) {
            null
        }
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("r2cmapkg1://", ignoreCase = true)) {
            MAGIC_PREFIX + trimmed.substringAfter("://")
        } else trimmed
    }

    fun isValidToken(token: String): Boolean = decode(token.trim()) != null

    private fun xorBytes(input: ByteArray): ByteArray =
        ByteArray(input.size) { i ->
            (input[i].toInt() xor OrgConfigToken.XOR_KEY[i % OrgConfigToken.XOR_KEY.size].toInt()).toByte()
        }

    private const val STD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    private const val CUSTOM_ALPHABET =
        "r2cNOPQRSTUVWXYZABCDEFGHIJKLMnopqstuvwxyzabdefghijklm013456789+/="
}
