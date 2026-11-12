import Foundation

/// Bluetooth SIG company identifiers of common makers, checked against the official
/// assigned-numbers list (bitbucket.org/bluetooth-SIG/public, assigned_numbers/
/// company_identifiers/company_identifiers.yaml, last modified Nov 10 2026). Short display
/// names; the SIG names are e.g. "GN Hearing A/S" (ReSound), "Demant A/S" (Oticon),
/// "Sonova AG" (Phonak), "WS Audiology Denmark A/S" (Widex, Signia), "Sivantos GmbH"
/// (Signia), "Anhui Huami" (Amazfit). Fitbit has no entry of its own (0x0224 is Comarch SA).
/// Same table as Android's BtrxMakers.kt. Keep them matching.
public enum BtrxMakers {
    public static let names: [Int: String] = [
        0x0006: "Microsoft",
        0x0008: "Motorola",
        0x004C: "Apple",
        0x0055: "Plantronics",
        0x0057: "Harman",
        0x0067: "GN Hearing",
        0x006B: "Polar",
        0x0075: "Samsung",
        0x0078: "Nike",
        0x0087: "Garmin",
        0x0089: "GN Hearing",
        0x009E: "Bose",
        0x009F: "Suunto",
        0x00BA: "Starkey",
        0x00C4: "LG",
        0x00CC: "Beats",
        0x00E0: "Google",
        0x0103: "Bang & Olufsen",
        0x0107: "Demant",
        0x012D: "Sony",
        0x0157: "Huami",
        0x0171: "Amazon",
        0x018E: "Google",
        0x01AB: "Meta",
        0x01DA: "Logitech",
        0x01E0: "WS Audiology",
        0x027D: "Huawei",
        0x0282: "Sonova",
        0x0295: "Sivantos",
        0x02B2: "Oura",
        0x02C5: "Lenovo",
        0x02F2: "GoPro",
        0x038F: "Xiaomi",
        0x03FF: "Withings",
        0x0494: "Sennheiser",
        0x058E: "Meta",
        0x067C: "Tile",
        0x072F: "OnePlus",
        0x08AA: "DJI",
        0x08C3: "Chipolo",
        0x0BA3: "Sonova",
        0x0CC2: "Anker",
        0x0CCB: "Nothing",
    ]

    /// Maker for a company_id: the short name, "0x1234" when not in the table, nil for 0 (none).
    public static func maker(_ companyId: Int) -> String? {
        if companyId == 0 { return nil }
        return names[companyId] ?? String(format: "0x%04x", UInt32(truncatingIfNeeded: companyId))
    }

    /// One short clue line from STEER company_id and dev_class: "Apple · wearable",
    /// "Samsung · phone", "0x1234 · audio", "unknown · phone", "Garmin · unknown";
    /// "unknown device" when both are unknown.
    public static func clue(companyId: Int, devClass: Int) -> String {
        let maker = maker(companyId)
        let kind: String? = devClass == BtrxProtocol.DEV_UNKNOWN ? nil : BtrxProtocol.devClassName(devClass)
        if maker == nil && kind == nil { return "unknown device" }
        return "\(maker ?? "unknown") · \(kind ?? "unknown")"
    }
}
