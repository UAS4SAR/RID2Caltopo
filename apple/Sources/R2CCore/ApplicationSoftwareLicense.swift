import Foundation

public enum ApplicationSoftwareLicense {
    /// Preserve every character while limiting the amount of text laid out per row.
    /// Call on a worker when loading a complete bundle of third-party notices.
    public static func sections(from text: String, maximumCharacters: Int = 8_000) -> [String] {
        precondition(maximumCharacters > 0)
        var sections: [String] = []
        var start = text.startIndex
        while start < text.endIndex {
            let end = text.index(start, offsetBy: maximumCharacters, limitedBy: text.endIndex) ?? text.endIndex
            sections.append(String(text[start..<end]))
            start = end
        }
        return sections
    }
}
