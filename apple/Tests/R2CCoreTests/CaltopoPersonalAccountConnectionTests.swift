import XCTest
@testable import R2CCore

final class CaltopoPersonalAccountConnectionTests: XCTestCase {
    func testCredentialTitleRequestMatchesTheVerifiedFormPost() throws {
        let secret = Data(repeating: 4, count: 32).base64EncodedString()
        let record = try sampleRecord(secret: secret)
        let request = try CaltopoPersonalAccountConnection().credentialTitleRequest(record, now: Date(timeIntervalSince1970: 1_700_000_000))
        let json = "{\"id\":\"cred12345678\",\"properties\":{\"class\":\"UserCredential\",\"title\":\"Last synced by RID2Caltopo app\"}}"
        XCTAssertEqual(request.url.path, "/api/v1/acct/USER01/UserCredential/cred12345678")
        XCTAssertEqual(request.json, json)
        XCTAssertEqual(request.fields["json"], json)
        XCTAssertEqual(request.fields["expires"], "1700000120000")
        let signature = try CaltopoRequestSigner.signature(
            method: "POST", path: request.url.path, expiresMilliseconds: 1_700_000_120_000,
            payload: json, credentialSecretBase64: record.signingKey
        )
        XCTAssertEqual(request.fields["signature"], signature)
    }

    func testActivationSuccessPageReturnsToTheAppWithoutTheCode() throws {
        let nonce = "0123456789abcdef0123456789abcdef"
        let page = try CaltopoPersonalAccountConnection.activationSuccessPage(nonce: nonce)
        XCTAssertEqual(try CaltopoPersonalAccountConnection.appReturnURL(nonce: nonce), "rid2caltopo://caltopo-return/\(nonce)")
        XCTAssertTrue(page.contains("rid2caltopo://caltopo-return/\(nonce)"))
        XCTAssertFalse(page.contains("code="))
        XCTAssertEqual(CaltopoPersonalAccountConnection.activationPrompt, "Check the box, then tap Sync Account.")
        XCTAssertTrue(CaltopoPersonalAccountConnection.activationPrompt.contains("Sync"))
        XCTAssertFalse(CaltopoPersonalAccountConnection.activationPrompt.contains("Synch"))
    }

    func testLiveFeatureShapeIsAccepted() throws {
        let key = Data((0..<32).map { UInt8(($0 * 17 + 9) & 0xff) }).base64EncodedString()
        XCTAssertEqual(key.count, 44)
        XCTAssertEqual("Feature".count, 7)
        let json = #"{"code":"Ab12Cd34Ef56","key":"\#(key)","account":{"id":"ACCT01","type":"Feature","properties":{"class":"UserAccount","subscriptionExpires":null}}}"#
        let parsed = try CaltopoPersonalAccountConnection.parseActivationResponse(Data(json.utf8))
        XCTAssertEqual(parsed.accountType, "Feature")
        XCTAssertEqual(parsed.accountID, "ACCT01")
        XCTAssertEqual(parsed.credentialID, "Ab12Cd34Ef56")
        XCTAssertFalse(parsed.description.contains(key))
    }

    func testPersonalLoginModeKeepsAnExistingCookieSessionUntilChosen() {
        XCTAssertEqual(CaltopoPersonalLoginMode.resolve(stored: nil, cookieSessionAlreadyInUse: false), .credentials)
        XCTAssertEqual(CaltopoPersonalLoginMode.resolve(stored: nil, cookieSessionAlreadyInUse: true), .sessionCookies)
        XCTAssertEqual(CaltopoPersonalLoginMode.resolve(stored: "credentials", cookieSessionAlreadyInUse: true), .credentials)
        XCTAssertEqual(CaltopoPersonalLoginMode.resolve(stored: "sessionCookies", cookieSessionAlreadyInUse: false), .sessionCookies)
    }

    func testSignedPersonalMapDoesNotUseTheCookieConfiguration() throws {
        let connection = CaltopoPersonalAccountConnection()
        connection.setFeatureEnabled(true)
        let secret = Data("team-secret-1".utf8).base64EncodedString()
        try connection.useRecord(try sampleRecord(secret: secret))
        let cookie = CaltopoLiveConfiguration(mapID: "ABC123", credentialID: "", credentialSecretBase64: "", personalSessionID: UUID(), personalAccountID: "USER01", personalMediaOwnerID: "USER01")
        let resolved = connection.resolveLiveConfiguration(
            lookup: connection.lookup(host: "caltopo.com", accountID: "USER01"),
            mapID: "ABC123", connectKey: "DRONE", personalSessionID: nil,
            personalAccountID: "USER01", personalMediaOwnerID: "USER01",
            useSignedPersonalAccount: true, fallback: cookie
        )
        let signed = try XCTUnwrap(resolved)
        XCTAssertNil(signed.personalSessionID)
        XCTAssertTrue(signed.personalSignedAccount)
        XCTAssertEqual(signed.credentialID, "cred12345678")
        XCTAssertNil(connection.resolveLiveConfiguration(
            lookup: .absent, mapID: "ABC123", connectKey: "", personalSessionID: nil,
            personalAccountID: "USER01", personalMediaOwnerID: "USER01",
            useSignedPersonalAccount: true, fallback: cookie
        ))
    }

    func testFlagOffLeavesCookieAndTeamsConfigurationsUntouched() throws {
        let connection = CaltopoPersonalAccountConnection()
        let secret = Data("team-secret-1".utf8).base64EncodedString()
        let record = try sampleRecord(secret: secret)
        try connection.useRecord(record)
        let cookie = CaltopoLiveConfiguration(
            mapID: "ABC123", credentialID: "", credentialSecretBase64: "",
            personalSessionID: UUID(), personalAccountID: "USER01", personalMediaOwnerID: "USER01"
        )
        let teams = CaltopoLiveConfiguration(mapID: "ABC123", credentialID: "team-credential", credentialSecretBase64: secret)
        XCTAssertEqual(connection.resolveLiveConfiguration(lookup: connection.lookup(host: "caltopo.com", accountID: "USER01"), mapID: "ABC123", connectKey: "DRONE", personalSessionID: cookie.personalSessionID, personalAccountID: "USER01", personalMediaOwnerID: "USER01", fallback: cookie), cookie)
        XCTAssertEqual(connection.resolveLiveConfiguration(lookup: .inactive, mapID: "ABC123", connectKey: "DRONE", personalSessionID: nil, personalAccountID: "", personalMediaOwnerID: "", fallback: teams)?.credentialID, "team-credential")
        XCTAssertFalse(connection.isFeatureEnabled)
    }

    func testSignedPersonalConfigurationUsesTheExistingSignerAndNotCookies() async throws {
        let connection = CaltopoPersonalAccountConnection()
        connection.setFeatureEnabled(true)
        let secret = Data("team-secret-1".utf8).base64EncodedString()
        try connection.useRecord(try sampleRecord(secret: secret))
        let cookieID = UUID()
        let resolved = connection.resolveLiveConfiguration(
            lookup: connection.lookup(host: "caltopo.com", accountID: "USER01"),
            mapID: "ABC123", connectKey: "DRONE", personalSessionID: cookieID,
            personalAccountID: "USER01", personalMediaOwnerID: "WORK01",
            fallback: CaltopoLiveConfiguration(mapID: "ABC123", credentialID: "", credentialSecretBase64: "", personalSessionID: cookieID, personalAccountID: "USER01", personalMediaOwnerID: "WORK01")
        )
        let signed = try XCTUnwrap(resolved)
        XCTAssertNil(signed.personalSessionID)
        XCTAssertTrue(signed.personalSignedAccount)
        XCTAssertEqual(signed.credentialID, "cred12345678")
        XCTAssertNotEqual(signed.credentialSecretBase64, "")
        let client = try CaltopoLiveClient(configuration: signed)
        let request = try await client.makeMapChangesRequest(sinceMilliseconds: 0, now: Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(request.url?.path, "/api/v1/map/ABC123/since/0")
        XCTAssertNotNil(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "signature" }?.value)
        XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        let clue = CaltopoPhotoClue(latitude: 1, longitude: 2, title: "Clue", description: "Photo", createdMilliseconds: 1, jpegData: Data([0xff, 0xd8]), teamID: "personal:USER01")
        let bodies = try await client.makePhotoClueRequests(clue, now: Date(timeIntervalSince1970: 1_700_000_000)).compactMap { $0.httpBody }.map { String(decoding: $0, as: UTF8.self) }
        XCTAssertTrue(bodies.contains { $0.contains("signature=") })
        XCTAssertTrue(bodies.contains { $0.contains("WORK01") })
        let catalog = try await CaltopoTeamMapClient(configuration: try connection.teamMapConfiguration())
            .makeRequest(now: Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(catalog.url?.path, "/api/v1/acct/USER01/since/0")
        XCTAssertNotNil(URLComponents(url: catalog.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "id" && $0.value == "cred12345678" })
    }

    func testActivationCallbackStateAndResponseRedaction() throws {
        let connection = CaltopoPersonalAccountConnection()
        let pending = try connection.begin(host: "caltopo.com", deviceName: "RID2Caltopo", port: 9)
        XCTAssertTrue(CaltopoPersonalAccountConnection.isActivationURL(pending.activationURL))
        XCTAssertFalse(pending.description.contains(pending.pathToken))
        let redirect = URLComponents(url: pending.activationURL, resolvingAgainstBaseURL: false)?
            .queryItems?.first { $0.name == "redirect" }?.value
        XCTAssertEqual(redirect?.contains("?"), false)
        let returned = try connection.acceptCallback("/\(pending.pathToken)/client/finish-activate?code=abcd1234&name=R2C+probe")
        XCTAssertEqual(returned.code, "abcd1234")
        XCTAssertEqual(returned.deviceName, "R2C probe")
        let custom = try connection.begin(port: 9)
        let prefix = try CaltopoPersonalAccountConnection.customSchemePrefix(scheme: "caltopoapp", nonce: custom.pathToken)
        XCTAssertFalse(prefix.contains("?"))
        let customReturn = try connection.acceptCallback(prefix + "/client/finish-activate?code=abcd1234&name=R2C+probe")
        XCTAssertEqual(customReturn.deviceName, "R2C probe")
        let retry = try connection.begin(port: 9)
        XCTAssertThrowsError(try connection.acceptCallback("/\(retry.pathToken)?state=r2ctest123/client/finish-activate?code=abcd1234&name=R2C+probe"))
        XCTAssertThrowsError(try connection.acceptCallback("https://evil.test/\(retry.pathToken)/client/finish-activate?code=abcd1234&name=R2C+probe"))
        let secret = Data(repeating: 7, count: 32).base64EncodedString()
        let body = #"{"code":"cred12345678","key":"\#(secret)","account":{"id":"USER01","type":"account","properties":{"class":"UserAccount","firstName":"Test","lastName":"Pilot","title":"Pilot","email":"pilot@example.com","subscriptionExpires":1790000000000}}}"#.data(using: .utf8)!
        let record = try connection.completeExchange(body, code: "abcd1234", host: "caltopo.com", deviceName: "RID2Caltopo")
        XCTAssertEqual(record.displayName, "Test Pilot")
        XCTAssertFalse(record.description.contains(secret))
        XCTAssertFalse(record.debugDescription.contains(secret))
        let team = #"{"code":"cred12345678","key":"\#(secret)","account":{"id":"USER01","type":"account","properties":{"class":"TeamAccount"}}}"#.data(using: .utf8)!
        XCTAssertThrowsError(try CaltopoPersonalAccountConnection.parseActivationResponse(team)) { error in
            XCTAssertEqual(error as? CaltopoPersonalAccountError, .notPersonalAccount)
            XCTAssertFalse(String(describing: error).contains(secret))
        }
    }

    func testRevokedPersonalAccountDoesNotFallBackToTheCookieConfiguration() throws {
        let connection = CaltopoPersonalAccountConnection()
        connection.setFeatureEnabled(true)
        var record = try sampleRecord(secret: Data("another-secret".utf8).base64EncodedString())
        record.revoked = true
        try connection.useRecord(record)
        let cookie = CaltopoLiveConfiguration(mapID: "ABC123", credentialID: "", credentialSecretBase64: "", personalSessionID: UUID(), personalAccountID: "USER01", personalMediaOwnerID: "USER01")
        XCTAssertNil(connection.resolveLiveConfiguration(lookup: connection.lookup(host: "caltopo.com", accountID: "USER01"), mapID: "ABC123", connectKey: "", personalSessionID: cookie.personalSessionID, personalAccountID: "USER01", personalMediaOwnerID: "USER01", fallback: cookie))
        let teams = CaltopoLiveConfiguration(mapID: "ABC123", credentialID: "team-credential", credentialSecretBase64: "dGVhbQ==")
        XCTAssertEqual(connection.resolveLiveConfiguration(lookup: connection.lookup(host: "caltopo.com", accountID: "USER01"), mapID: "ABC123", connectKey: "", personalSessionID: nil, personalAccountID: "USER01", personalMediaOwnerID: "", fallback: teams)?.credentialID, "team-credential")
        connection.disconnect()
        XCTAssertNil(connection.reconnect(host: "caltopo.com"))
        XCTAssertThrowsError(try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(mapID: "ABC123", credentialID: "cred12345678", credentialSecretBase64: record.signingKey, personalSessionID: UUID(), personalAccountID: "USER01", personalMediaOwnerID: "USER01", personalSignedAccount: true)))
    }

    func testCachedUIReadDoesNotWaitForStorageOrAcceptAnObsoleteLoad() async throws {
        let connection = CaltopoPersonalAccountConnection()
        let store = DelayedPersonalAccountStore(record: try sampleRecord(secret: ""))
        connection.use(store)
        connection.setFeatureEnabled(true)
        let loading = Task.detached { connection.status(host: "caltopo.com") }
        let started = await Task.detached { store.awaitStart() }.value
        guard started == .success else {
            store.release.signal()
            XCTFail("Storage load did not start")
            return
        }
        let before = Date()
        XCTAssertNil(connection.cachedStatus(host: "caltopo.com"))
        XCTAssertEqual(connection.cachedLookup(host: "caltopo.com", accountID: "USER01"), .unavailable)
        XCTAssertLessThan(Date().timeIntervalSince(before), 0.1)
        connection.use(CaltopoPersonalAccountMemoryStore())
        store.release.signal()
        let obsolete = await loading.value
        XCTAssertNil(obsolete)
        XCTAssertNil(connection.cachedStatus(host: "caltopo.com"))
        XCTAssertNil(connection.status(host: "caltopo.com"))
        XCTAssertEqual(connection.cachedLookup(host: "caltopo.com", accountID: "USER01"), .absent)
    }

    private func sampleRecord(secret: String) throws -> CaltopoPersonalAccountRecord {
        let key = Data(base64Encoded: secret)?.count == 32 ? secret : Data(repeating: 1, count: 32).base64EncodedString()
        let body = #"{"code":"cred12345678","key":"\#(key)","account":{"id":"USER01","type":"account","properties":{"class":"UserAccount","title":"Pilot"}}}"#.data(using: .utf8)!
        return try CaltopoPersonalAccountConnection().completeExchange(body, code: "abcd1234", host: "caltopo.com", deviceName: "RID2Caltopo")
    }
}

private extension CaltopoPersonalAccountConnection {
    func useRecord(_ record: CaltopoPersonalAccountRecord) throws {
        let store = CaltopoPersonalAccountMemoryStore()
        try store.save(record)
        use(store)
    }
}

private final class DelayedPersonalAccountStore: CaltopoPersonalAccountStore, @unchecked Sendable {
    let started = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    let record: CaltopoPersonalAccountRecord
    init(record: CaltopoPersonalAccountRecord) { self.record = record }
    func awaitStart() -> DispatchTimeoutResult { started.wait(timeout: .now() + 2) }
    func load() throws -> CaltopoPersonalAccountRecord? {
        started.signal()
        guard release.wait(timeout: .now() + 5) == .success else {
            throw CaltopoPersonalAccountError.unavailable
        }
        return record
    }
    func save(_ record: CaltopoPersonalAccountRecord) throws {}
    func clear() throws {}
}
