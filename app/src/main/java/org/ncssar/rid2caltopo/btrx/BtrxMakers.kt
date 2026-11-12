package org.ncssar.rid2caltopo.btrx

import java.util.Locale

/**
 * Bluetooth SIG company identifiers of common makers, checked against the official
 * assigned-numbers list (bitbucket.org/bluetooth-SIG/public, assigned_numbers/
 * company_identifiers/company_identifiers.yaml, last modified Nov 10 2026). Short display
 * names; the SIG names are e.g. "GN Hearing A/S" (ReSound), "Demant A/S" (Oticon),
 * "Sonova AG" (Phonak), "WS Audiology Denmark A/S" (Widex, Signia), "Sivantos GmbH"
 * (Signia), "Anhui Huami" (Amazfit). Fitbit has no entry of its own (0x0224 is Comarch SA).
 * The iPad has the same table (R2CCore BtrxMakers.swift). Keep them matching.
 */
object BtrxMakers {
    val NAMES: Map<Int, String> = mapOf(
        0x0006 to "Microsoft",
        0x0008 to "Motorola",
        0x004C to "Apple",
        0x0055 to "Plantronics",
        0x0057 to "Harman",
        0x0067 to "GN Hearing",
        0x006B to "Polar",
        0x0075 to "Samsung",
        0x0078 to "Nike",
        0x0087 to "Garmin",
        0x0089 to "GN Hearing",
        0x009E to "Bose",
        0x009F to "Suunto",
        0x00BA to "Starkey",
        0x00C4 to "LG",
        0x00CC to "Beats",
        0x00E0 to "Google",
        0x0103 to "Bang & Olufsen",
        0x0107 to "Demant",
        0x012D to "Sony",
        0x0157 to "Huami",
        0x0171 to "Amazon",
        0x018E to "Google",
        0x01AB to "Meta",
        0x01DA to "Logitech",
        0x01E0 to "WS Audiology",
        0x027D to "Huawei",
        0x0282 to "Sonova",
        0x0295 to "Sivantos",
        0x02B2 to "Oura",
        0x02C5 to "Lenovo",
        0x02F2 to "GoPro",
        0x038F to "Xiaomi",
        0x03FF to "Withings",
        0x0494 to "Sennheiser",
        0x058E to "Meta",
        0x067C to "Tile",
        0x072F to "OnePlus",
        0x08AA to "DJI",
        0x08C3 to "Chipolo",
        0x0BA3 to "Sonova",
        0x0CC2 to "Anker",
        0x0CCB to "Nothing",
    )

    /** Maker for a company_id: the short name, "0x1234" when not in the table, null for 0 (none). */
    fun maker(companyId: Int): String? = when {
        companyId == 0 -> null
        else -> NAMES[companyId] ?: "0x%04x".format(Locale.US, companyId)
    }

    /**
     * One short clue line from STEER company_id and dev_class: "Apple · wearable",
     * "Samsung · phone", "0x1234 · audio", "unknown · phone", "Garmin · unknown";
     * "unknown device" when both are unknown.
     */
    fun clue(companyId: Int, devClass: Int): String {
        val maker = maker(companyId)
        val kind = BtrxProtocol.devClassName(devClass).takeIf { devClass != BtrxProtocol.DEV_UNKNOWN }
        if (maker == null && kind == null) return "unknown device"
        return "${maker ?: "unknown"} · ${kind ?: "unknown"}"
    }
}
