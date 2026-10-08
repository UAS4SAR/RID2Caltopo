import Testing
@testable import R2CCore

@Test func teamsCredentialDraftRequiresCompleteOrEmptyCredentials() {
    #expect(OperationalTeamsCredentialDraft().validationMessage == nil)
    #expect(OperationalTeamsCredentialDraft(teamID: "team").validationMessage != nil)
    #expect(OperationalTeamsCredentialDraft(teamID: "team", credentialID: "id").validationMessage != nil)
    #expect(OperationalTeamsCredentialDraft(teamID: "team", credentialID: "id", secret: "secret").validationMessage == nil)
}
@Test func teamsCredentialDraftNormalizesWithoutChangingSavedInput() {
    let saved = OperationalTeamsCredentialDraft(teamID: "team", credentialID: "id", secret: "secret")
    var draft = saved
    draft.teamID = " other "
    draft.domain = " "
    #expect(saved.teamID == "team")
    #expect(draft.normalized.teamID == "other")
    #expect(draft.normalized.domain == "caltopo.com")
    #expect(draft.normalized != saved)
}
