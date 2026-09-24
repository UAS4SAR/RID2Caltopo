package org.ncssar.rid2caltopo.data

import java.io.File

object MediaMTXConfig {
    private const val RECORD_FORMAT_FMP4 = "fmp4"

    @JvmStatic
    fun buildRuntimeConfig(
        baseConfig: String,
        captureEnabled: Boolean,
        recordingRoot: File,
        restrictNetworkAccess: Boolean = true,
    ): String {
        val normalizedBase = withNetworkAccess(baseConfig, restrictNetworkAccess).trimEnd()
        val logLevel = if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelDebug) "debug" else "info"
        val recordSettings = mutableListOf<String>()
        if (!captureEnabled) {
            recordSettings.add("record: no")
            return buildString {
                append(withPathDefaultsSettings(normalizedBase, recordSettings))
                append("\nlogLevel: ")
                append(logLevel)
                append('\n')
            }
        }

        val recordPath = File(
            recordingRoot,
            "%path/%path_%Y-%m-%d_%H-%M-%S-%f",
        ).absolutePath
        recordSettings.add("record: yes")
        recordSettings.add("recordPath: '${yamlSingleQuoted(recordPath)}'")
        recordSettings.add("recordFormat: $RECORD_FORMAT_FMP4")
        recordSettings.add("recordDeleteAfter: 0s")
        return buildString {
            append(withPathDefaultsSettings(normalizedBase, recordSettings))
            append('\n')
            append("logLevel: ")
            append(logLevel)
            append('\n')
        }
    }

    private fun withPathDefaultsSettings(baseConfig: String, settings: List<String>): String {
        val lines = baseConfig.lines().toMutableList()
        val pathDefaultsIndex = lines.indexOfFirst { it.trim() == "pathDefaults:" }
        val renderedSettings = settings.map { "  $it" }
        if (pathDefaultsIndex >= 0) {
            lines.addAll(pathDefaultsIndex + 1, renderedSettings)
            return lines.joinToString("\n")
        }
        return buildString {
            append(baseConfig)
            append("\npathDefaults:\n")
            renderedSettings.forEach { setting ->
                append(setting)
                append('\n')
            }
        }.trimEnd()
    }

    /** RTMP stays reachable for controllers; read permissions are independent of publishing. */
    internal fun withNetworkAccess(base: String, restricted: Boolean): String {
        val host = if (restricted) "127.0.0.1" else ""
        val settings = linkedMapOf(
            "rtmpAddress" to ":1935",
            "rtspAddress" to "$host:8554",
            "rtspTransports" to if (restricted) "[tcp]" else "[udp, multicast, tcp]",
            "hlsAddress" to "$host:8888",
            "webrtc" to if (restricted) "no" else "yes",
            "webrtcAddress" to "$host:8889",
            "webrtcLocalUDPAddress" to "$host:8189",
            "webrtcLocalTCPAddress" to "$host:8189",
            "webrtcICEServers2" to "[]",
            "apiAddress" to "127.0.0.1:9997",
            "metricsAddress" to "127.0.0.1:9998",
            "pprofAddress" to "127.0.0.1:9999",
            "playback" to "no",
            "srt" to "no",
            "authMethod" to "internal",
            "authInternalUsers" to """
  - user: any
    ips: []
    permissions:
      - action: publish
${if (restricted) "" else "      - action: read\n      - action: playback\n"}  - user: any
    ips: [127.0.0.1, '::1']
    permissions:
      - action: read
      - action: playback
      - action: api
      - action: metrics
      - action: pprof""",
        )
        // Replace entire top-level YAML blocks, including existing auth users, rather than
        // appending duplicate keys or leaving a permissive default user in the list.
        val lines = mutableListOf<String>()
        var replacing = false
        base.lineSequence().forEach { line ->
            if (line.isNotBlank() && !line.first().isWhitespace() && !line.startsWith("#")) {
                replacing = line.substringBefore(':') in settings
            }
            if (!replacing) lines.add(line)
        }
        return lines.joinToString("\n").trimEnd() + "\n" +
            settings.entries.joinToString("\n") { (key, value) -> "$key: $value" } + "\n"
    }

    private fun yamlSingleQuoted(value: String): String = value.replace("'", "''")
}
