import Foundation

public enum ApplicationInformationDocument: String, Sendable {
    case privacyPolicy = "PrivacyPolicyApple"
    case serviceAcknowledgments = "ServiceAcknowledgmentsApple"

    public var title: String {
        switch self {
        case .privacyPolicy: "Privacy policy"
        case .serviceAcknowledgments: "Service & data acknowledgments"
        }
    }
    /// Call on a worker; policy text remains readable without a network connection.
    public func sections() throws -> [String] {
        guard let url = Bundle.module.url(forResource: rawValue, withExtension: "txt") else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        return ApplicationSoftwareLicense.sections(from: try String(contentsOf: url, encoding: .utf8))
    }
}
