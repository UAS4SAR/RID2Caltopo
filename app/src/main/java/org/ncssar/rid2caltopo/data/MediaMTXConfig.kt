package org.ncssar.rid2caltopo.data

import java.io.File

object MediaMTXConfig {
    private const val RECORD_FORMAT_FMP4 = "fmp4"

    @JvmStatic
    fun buildRuntimeConfig(
        baseConfig: String,
        captureEnabled: Boolean,
        recordingRoot: File,
    ): String {
        val normalizedBase = baseConfig.trimEnd()
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

    private fun yamlSingleQuoted(value: String): String = value.replace("'", "''")
}
