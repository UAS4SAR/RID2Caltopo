import Testing
@testable import R2CCore

@Test func wiredControllerSupportsLocalOnlyAndCableDisconnect() {
    let wifi = ControllerIPv4Candidate(interfaceName: "wifi", address: "192.168.50.12")
    let wired = ControllerIPv4Candidate(interfaceName: "wired", address: "169.254.10.2")
    #expect(ControllerIPv4Selection.preferredAddress(
        candidates: [wifi, wired], wifiInterfaceNames: ["wifi"], wiredInterfaceNames: ["wired"]
    ) == wired.address)
    #expect(ControllerIPv4Selection.preferredAddress(
        candidates: [wifi], wifiInterfaceNames: ["wifi"], wiredInterfaceNames: ["wired"]
    ) == wifi.address)
    for invalid in ["0.0.0.0", "127.0.0.1", "224.0.0.1", "255.255.255.255", "256.1.2.3", "::1"] {
        #expect(!ControllerIPv4Selection.isUsableAddress(invalid))
    }
}
