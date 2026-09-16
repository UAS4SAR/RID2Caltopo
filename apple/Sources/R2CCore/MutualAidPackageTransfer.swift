import Foundation
import CryptoKit
import Security

/// Android's R2CMAPKG1 wire format. Keep payload bytes intact: '+' and '/' are not URL query encoding.
public struct MutualAidPackageTransferToken: Sendable, Equatable {
    public let host: String
    public let port: Int
    public let sessionID: String
    public let packageName: String
    public let sizeBytes: Int64
    public let sha256: String
    public let publicKeySHA256: String
    public let expiresAtMilliseconds: Int64

    public static func decode(_ raw: String) -> Self? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let encoded: String
        if trimmed.lowercased().hasPrefix("r2cmapkg1://") {
            encoded = String(trimmed.dropFirst("r2cmapkg1://".count))
        } else if trimmed.hasPrefix("R2CMAPKG1:") {
            encoded = String(trimmed.dropFirst("R2CMAPKG1:".count))
        } else { return nil }
        let standard = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
        let custom = Array("r2cNOPQRSTUVWXYZABCDEFGHIJKLMnopqstuvwxyzabdefghijklm013456789+/=")
        let remapped = String(encoded.map { c in custom.firstIndex(of: c).map { standard[$0] } ?? c })
        guard let bytes = Data(base64Encoded: remapped) else { return nil }
        let key = Array("RID2CaltopoQR".utf8)
        let payload = Data(bytes.enumerated().map { $0.element ^ key[$0.offset % key.count] })
        struct Wire: Decodable {
            let h: String; let p: Int; let s: String; let n: String
            let z: Int64; let d: String; let k: String; let e: Int64; let v: Int
        }
        guard let wire = try? JSONDecoder().decode(Wire.self, from: payload), wire.v == 1,
              (1...65535).contains(wire.p), !wire.s.isEmpty,
              wire.z > 0, wire.e > 0,
              isDigest(wire.d), isDigest(wire.k) else { return nil }
        // Current Android shares advertise IPv4 literals. Do not interpret a QR as an arbitrary URL.
        let octets = wire.h.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4, octets.allSatisfy({ part in
            !part.isEmpty && part.allSatisfy { $0.isASCII && $0.isNumber } &&
                UInt8(part) != nil
        }) else { return nil }
        return Self(host: wire.h, port: wire.p, sessionID: wire.s, packageName: wire.n,
                    sizeBytes: wire.z, sha256: wire.d.lowercased(), publicKeySHA256: wire.k.lowercased(),
                    expiresAtMilliseconds: wire.e)
    }

    private static func isDigest(_ value: String) -> Bool {
        value.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }
    }
}

public enum MutualAidPackageTransferError: LocalizedError {
    case expired, invalidResponse, integrity, tooLarge
    public var errorDescription: String? {
        switch self {
        case .expired: "This MA share has expired. Ask the sender to prepare a new share and scan its QR."
        case .invalidResponse: "The sharing device did not return the MA package. Keep its QR panel open and scan the current QR."
        case .integrity: "The downloaded MA package did not match its QR. Prepare a new share and try again."
        case .tooLarge: "This MA package exceeds the 512 MB transfer limit. Ask the sender to export a smaller area."
        }
    }
}

/// Extract the exact DER SubjectPublicKeyInfo used by Android's PublicKey.encoded pin.
public enum MutualAidCertificatePin {
    public static func subjectPublicKeyInfo(_ certificate: Data) -> Data? {
        let bytes = Array(certificate)
        func element(_ offset: Int, limit: Int) -> (tag: UInt8, body: Range<Int>, whole: Range<Int>)? {
            guard offset >= 0, offset + 2 <= limit, limit <= bytes.count else { return nil }
            var cursor = offset + 2
            var length = Int(bytes[offset + 1])
            if length & 0x80 != 0 {
                let count = length & 0x7f
                guard (1...4).contains(count), cursor + count <= limit else { return nil }
                length = 0
                for _ in 0..<count { length = (length << 8) | Int(bytes[cursor]); cursor += 1 }
            }
            guard length <= limit - cursor else { return nil }
            return (bytes[offset], cursor..<(cursor + length), offset..<(cursor + length))
        }
        guard let outer = element(0, limit: bytes.count), outer.tag == 0x30,
              let tbs = element(outer.body.lowerBound, limit: outer.body.upperBound), tbs.tag == 0x30 else { return nil }
        var cursor = tbs.body.lowerBound
        if let version = element(cursor, limit: tbs.body.upperBound), version.tag == 0xa0 { cursor = version.whole.upperBound }
        // serial number, signature algorithm, issuer, validity, subject
        for _ in 0..<5 {
            guard let field = element(cursor, limit: tbs.body.upperBound) else { return nil }
            cursor = field.whole.upperBound
        }
        guard let spki = element(cursor, limit: tbs.body.upperBound), spki.tag == 0x30 else { return nil }
        return Data(bytes[spki.whole])
    }
}

private final class MutualAidPinnedSession: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    let pin: String
    let expectedBytes: Int64
    init(pin: String, expectedBytes: Int64) { self.pin = pin; self.expectedBytes = expectedBytes }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > expectedBytes || totalBytesExpectedToWrite > expectedBytes { downloadTask.cancel() }
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let certificate = SecTrustCopyCertificateChain(trust).flatMap({ ($0 as? [SecCertificate])?.first }),
              let spki = MutualAidCertificatePin.subjectPublicKeyInfo(SecCertificateCopyData(certificate) as Data),
              SHA256.hash(data: spki).map({ String(format: "%02x", $0) }).joined() == pin,
              SecTrustSetAnchorCertificates(trust, [certificate] as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess,
              SecTrustEvaluateWithError(trust, nil) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

public enum MutualAidPackageDownloader {
    public static func download(_ token: MutualAidPackageTransferToken, receiverName: String) async throws -> Data {
        guard Date().timeIntervalSince1970 * 1_000 < Double(token.expiresAtMilliseconds) else {
            throw MutualAidPackageTransferError.expired
        }
        guard token.sizeBytes <= 512 * 1_024 * 1_024 else { throw MutualAidPackageTransferError.tooLarge }
        var components = URLComponents()
        components.scheme = "https"; components.host = token.host; components.port = token.port
        components.path = "/ma-package"
        components.queryItems = [URLQueryItem(name: "sid", value: token.sessionID)]
        guard let url = components.url else { throw MutualAidPackageTransferError.invalidResponse }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 15 * 60
        config.waitsForConnectivity = false
        let session = URLSession(configuration: config, delegate: MutualAidPinnedSession(pin: token.publicKeySHA256, expectedBytes: token.sizeBytes), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url)
        request.setValue(receiverName, forHTTPHeaderField: "X-R2C-Receiver")
        let (file, response) = try await session.download(for: request)
        defer { try? FileManager.default.removeItem(at: file) }
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw MutualAidPackageTransferError.invalidResponse
        }
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize
        guard size == Int(token.sizeBytes) else { throw MutualAidPackageTransferError.integrity }
        let data = try Data(contentsOf: file, options: .mappedIfSafe)
        guard SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == token.sha256 else {
            throw MutualAidPackageTransferError.integrity
        }
        return data
    }
}
