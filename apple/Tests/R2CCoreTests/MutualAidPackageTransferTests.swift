import XCTest
import CryptoKit
import Security
@testable import R2CCore

final class MutualAidPackageTransferTests: XCTestCase {
    private func token(host: String = "127.0.0.1", port: Int = 43363, pin: String = String(repeating: "b", count: 64),
                       digest: String = String(repeating: "a", count: 64), size: Int64 = 1234,
                       expiry: Int64 = 4_000_000_000_000, version: Int = 1) throws -> String {
        let object: [String: Any] = ["h": host, "p": port, "s": "test-session", "n": "Incident", "z": size,
                                     "d": digest, "k": pin, "e": expiry, "v": version]
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let key = Array("RID2CaltopoQR".utf8)
        let encoded = Data(data.enumerated().map { $0.element ^ key[$0.offset % key.count] }).base64EncodedString()
        let standard = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
        let custom = Array("r2cNOPQRSTUVWXYZABCDEFGHIJKLMnopqstuvwxyzabdefghijklm013456789+/=")
        return "R2CMAPKG1:" + String(encoded.map { c in standard.firstIndex(of: c).map { custom[$0] } ?? c })
    }

    func testAndroidGoldenToken() throws {
        let value = try XCTUnwrap(MutualAidPackageTransferToken.decode("R2CMAPKG1:UGeeORwNHE0nHw5yKyj/p2j0Fv5IDA2XK1JsoyS2LmWpFwFCQlAsTxA3FlrC2Be2EvXlZR2lJymh2DW02qSpZtrxPtfcMvXrFsFCFG2qIH0j2RBJFFsXPO0dMNWzTFWtrrmFNsOYWNWlUcFDSqrXPA4BNurlWkqwEkSrNBEYOA4iWlWzTFWtrrmFNsOYWNWlUcFDSqrXPA4BNu2ipxehORwNNsIXOqmlWNrdTwrsri4GNBSXWlriUkJASAWYPqmCNDWiWcexEcONNsIXOqmlWNrdTwrsri4GNBSXWlriUkJASEXrFqaCFG2dIywmryXBHOBpAP99McBdpqW+"))
        XCTAssertEqual(value.host, "192.168.68.67")
        XCTAssertEqual(value.port, 43363)
        XCTAssertEqual(value.sessionID, "test-session")
        XCTAssertEqual(value.packageName, "Old_Airport_op1")
        XCTAssertEqual(value.sizeBytes, 123456789)
        XCTAssertEqual(value.expiresAtMilliseconds, 1900000000000)
        XCTAssertEqual(value.sha256, String(repeating: "a", count: 64))
        XCTAssertEqual(value.publicKeySHA256, String(repeating: "b", count: 64))
    }

    func testTokenAndQRHaveIdenticalTransferDetails() throws {
        let text = try token()
        let decoded = try XCTUnwrap(MutualAidPackageTransferToken.decode(text))
        XCTAssertEqual(decoded.host, "127.0.0.1")
        XCTAssertEqual(decoded.port, 43363)
        XCTAssertEqual(decoded, MutualAidPackageTransferToken.decode("  r2cmapkg1://" + text.dropFirst("R2CMAPKG1:".count) + "\n"))
    }

    func testInvalidTransportMetadataIsRejected() throws {
        for text in [try token(port: 0), try token(port: 65536), try token(pin: ""), try token(digest: "no digest"),
                     try token(size: -1), try token(version: 2), try token(host: "127.0.0.1/other"),
                     try token(host: "999.0.0.1"), "R2C2:other", "r2cmapkg1://broken"] {
            XCTAssertNil(MutualAidPackageTransferToken.decode(text))
        }
    }

    func testExpiredShareFailsBeforeConnecting() async throws {
        let expired = try XCTUnwrap(MutualAidPackageTransferToken.decode(token(expiry: 1)))
        do {
            _ = try await MutualAidPackageDownloader.download(expired, receiverName: "Test")
            XCTFail("Expired share was accepted")
        } catch MutualAidPackageTransferError.expired { }
    }

    func testOversizeShareIsRecognizedWithActionableError() async throws {
        let value = try XCTUnwrap(MutualAidPackageTransferToken.decode(token(size: 513 * 1_024 * 1_024)))
        do {
            _ = try await MutualAidPackageDownloader.download(value, receiverName: "Test")
            XCTFail("Oversize share was downloaded")
        } catch MutualAidPackageTransferError.tooLarge { }
    }

    func testMalformedCertificateCannotSupplyPin() {
        for bytes in [Data(), Data([0x30, 0x80]), Data([0x30, 0x84, 0xff, 0xff, 0xff, 0xff]), Data([0x30, 0x03, 0x30, 0x02, 0x00])] {
            XCTAssertNil(MutualAidCertificatePin.subjectPublicKeyInfo(bytes))
        }
    }

    #if os(macOS)
    func testPinnedTLSTransferIntegrityAndRetry() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        func run(_ args: [String]) throws {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
            process.arguments = args
            process.currentDirectoryURL = root
            process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
            try process.run(); process.waitUntilExit()
            XCTAssertEqual(process.terminationStatus, 0)
        }
        try """
        [req]
        distinguished_name = dn
        x509_extensions = extensions
        prompt = no
        [dn]
        CN = 127.0.0.1
        [extensions]
        subjectAltName = IP:127.0.0.1
        basicConstraints = critical,CA:FALSE
        keyUsage = critical,digitalSignature
        extendedKeyUsage = serverAuth
        """.write(to: root.appendingPathComponent("cert.cnf"), atomically: true, encoding: .utf8)
        try run(["req", "-x509", "-sha256", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-pkeyopt", "ec_param_enc:named_curve", "-nodes", "-keyout", "key.pem", "-out", "cert.pem", "-days", "2", "-config", "cert.cnf"])
        try run(["x509", "-in", "cert.pem", "-outform", "DER", "-out", "cert.der"])
        try run(["pkey", "-in", "key.pem", "-pubout", "-outform", "DER", "-out", "spki.der"])
        let certificate = try Data(contentsOf: root.appendingPathComponent("cert.der"))
        _ = try XCTUnwrap(SecCertificateCreateWithData(nil, certificate as CFData))
        let publicKey = try Data(contentsOf: root.appendingPathComponent("spki.der"))
        XCTAssertEqual(MutualAidCertificatePin.subjectPublicKeyInfo(certificate), publicKey)
        func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
        let package = try OperationalZipArchive.encode([.init(path: "manifest.json", data: Data("{\"profile_enc\":\"\"}".utf8))])
        try package.write(to: root.appendingPathComponent("package.zip"))
        let script = """
        import http.server, ssl, pathlib
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != '/ma-package?sid=test-session':
                    self.send_error(404); return
                body = pathlib.Path('package.zip').read_bytes()
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args): pass
        class Server(http.server.ThreadingHTTPServer):
            def get_request(self):
                try: return super().get_request()
                except Exception as error:
                    print(repr(error), file=__import__('sys').stderr, flush=True)
                    raise
        server = Server(('127.0.0.1', 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.set_ecdh_curve('prime256v1')
        context.load_cert_chain('cert.pem', 'key.pem')
        server.socket = context.wrap_socket(server.socket, server_side=True)
        print(server.server_port, flush=True)
        server.serve_forever()
        """
        let server = Process(); let output = Pipe()
        server.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        server.arguments = ["python3", "-u", "-c", script]; server.currentDirectoryURL = root
        server.standardOutput = output; server.standardError = FileHandle.standardError
        try server.run()
        defer { server.terminate(); server.waitUntilExit() }
        let port = try XCTUnwrap(Int(String(decoding: output.fileHandleForReading.availableData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)))
        let good = try XCTUnwrap(MutualAidPackageTransferToken.decode(token(port: port, pin: sha(publicKey), digest: sha(package), size: Int64(package.count))))
        let badPin = try XCTUnwrap(MutualAidPackageTransferToken.decode(token(port: port, digest: sha(package), size: Int64(package.count))))
        do { _ = try await MutualAidPackageDownloader.download(badPin, receiverName: "Test"); XCTFail("Wrong pin accepted") } catch { }
        let badHash = try XCTUnwrap(MutualAidPackageTransferToken.decode(token(port: port, pin: sha(publicKey), size: Int64(package.count))))
        do { _ = try await MutualAidPackageDownloader.download(badHash, receiverName: "Test"); XCTFail("Wrong checksum accepted") }
        catch MutualAidPackageTransferError.integrity { }
        // Failed attempts must not poison a subsequent download of the same sharing session.
        let downloaded = try await MutualAidPackageDownloader.download(good, receiverName: "Test")
        XCTAssertEqual(downloaded, package)
        XCTAssertEqual(try OperationalZipArchive.decode(downloaded).first?.path, "manifest.json")
    }
    #endif
}
