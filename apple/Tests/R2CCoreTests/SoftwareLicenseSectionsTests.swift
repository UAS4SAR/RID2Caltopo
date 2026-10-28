import Foundation
import Testing
@testable import R2CCore

@Test func licenseSectionsPreserveAllCharactersAndUnicodeBoundaries() {
    let text = String(repeating: "Copyright © Contributors 👩🏽‍💻\n\n", count: 1_000)
    let sections = ApplicationSoftwareLicense.sections(from: text, maximumCharacters: 127)
    #expect(sections.joined() == text)
    #expect(sections.allSatisfy { !$0.isEmpty && $0.count <= 127 })
}

@Test func emptyLicenseTextDoesNotCreateAnEmptyDisplayRow() {
    #expect(ApplicationSoftwareLicense.sections(from: "").isEmpty)
}
