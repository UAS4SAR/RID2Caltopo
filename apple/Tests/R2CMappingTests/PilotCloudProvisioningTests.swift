import XCTest
import Security
import CryptoKit
@testable import R2CMapping

final class PilotCloudProvisioningTests:XCTestCase {
    private func key()throws->SecKey {try XCTUnwrap(SecKeyCreateRandomKey([kSecAttrKeyType:kSecAttrKeyTypeRSA,kSecAttrKeySizeInBits:2048] as CFDictionary,nil))}
    private func envelope(_ key:SecKey)throws->[String:Any] {
        let recipient=pilotHash(try PilotCloudProvisioner.enrollment(key)),secret=SymmetricKey(size:.bits256)
        let clear=try JSONEncoder().encode(PilotCloudCredentials(appId:"123456",appKey:String(repeating:"k",count:32),appLicense:String(repeating:"l",count:64)))
        let box=try AES.GCM.seal(clear,using:secret,authenticating:Data(recipient.utf8)),publicKey=try XCTUnwrap(SecKeyCopyPublicKey(key))
        let wrapped=try XCTUnwrap(SecKeyCreateEncryptedData(publicKey,.rsaEncryptionOAEPSHA256,secret.withUnsafeBytes{Data($0)} as CFData,nil) as Data?)
        return ["type":"r2c_dji_device_provisioning","version":2,"algorithm":"RSA-OAEP-SHA256+A256GCM","recipientSha256":recipient,"wrappedKey":wrapped.base64EncodedString(),"iv":box.nonce.withUnsafeBytes{Data($0)}.base64EncodedString(),"ciphertext":(box.ciphertext+box.tag).base64EncodedString()]
    }
    func testOnlyEnrolledRecipientCanImportAuthenticatedCredentials()throws {
        let recipient=try key(),other=try key(),value=try envelope(recipient),bytes=try JSONSerialization.data(withJSONObject:value)
        let opened=try PilotCloudProvisioner.open(bytes,privateKey:recipient)
        XCTAssertEqual(opened.appId,"123456");XCTAssertEqual(opened.appKey,String(repeating:"k",count:32))
        XCTAssertThrowsError(try PilotCloudProvisioner.open(bytes,privateKey:other))
        var tampered=value;var cipher=Data(base64Encoded:value["ciphertext"] as! String)!;cipher[0]^=1;tampered["ciphertext"]=cipher.base64EncodedString()
        XCTAssertThrowsError(try PilotCloudProvisioner.open(JSONSerialization.data(withJSONObject:tampered),privateKey:recipient))
        var old=value;old["version"]=1
        XCTAssertThrowsError(try PilotCloudProvisioner.open(JSONSerialization.data(withJSONObject:old),privateKey:recipient))
        XCTAssertThrowsError(try PilotCloudProvisioner.open(Data(repeating:0,count:32769),privateKey:recipient))
        let publicBytes=try PilotCloudProvisioner.enrollment(recipient)
        XCTAssertFalse(publicBytes.isEmpty)
        XCTAssertFalse(String(decoding:bytes,as:UTF8.self).contains(opened.appKey))
    }
}
