import Foundation
import Security
import CryptoKit
import R2CCore

/// Administrator provisioning: only ciphertext addressed to this installation is
/// imported. The public enrollment is safe to copy; the private key stays in Keychain.
public enum PilotCloudProvisioner {
    private static let lock=NSRecursiveLock()
    private static let tag=Data("org.ncssar.RID2CaltopoApple.pilot-cloud.provisioning".utf8)
    private static var keyQuery:[String:Any]{[kSecClass as String:kSecClassKey,kSecAttrApplicationTag as String:tag,kSecAttrKeyType as String:kSecAttrKeyTypeRSA]}
    public static func prepare()throws {
        lock.lock();defer{lock.unlock()}
        var folder=try FileManager.default.url(for:.applicationSupportDirectory,in:.userDomainMask,appropriateFor:nil,create:true).appendingPathComponent("PilotCloudProvisioning",isDirectory:true)
        try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
        var values=URLResourceValues();values.isExcludedFromBackup=true;try folder.setResourceValues(values)
        let key=try privateKey(),publicBytes=try enrollment(key)
        let encoded=publicBytes.base64EncodedString(options:[.lineLength64Characters,.endLineWithLineFeed])
        try Data(("-----BEGIN PUBLIC KEY-----\n"+encoded+"\n-----END PUBLIC KEY-----\n").utf8).write(to:folder.appendingPathComponent("enrollment.pem"),options:.atomic)
        let incoming=folder.appendingPathComponent("sealed.json")
        let bundled=Bundle.main.url(forResource:"sealed",withExtension:"json",subdirectory:"dji-provisioning")
        if let source=FileManager.default.fileExists(atPath:incoming.path) ? incoming:bundled {
            guard (try source.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? Int.max)<=32768 else{throw MappingMission.Failure("Protected deployment bundle is too large.")}
            let bytes=try Data(contentsOf:source),hash=pilotHash(bytes),accepted=folder.appendingPathComponent("accepted.sha256")
            let hasCredentials=try PilotCloudCredentialStore.loadStored() != nil
            if (try? String(contentsOf:accepted,encoding:.utf8)) != hash || !hasCredentials {
                let credentials=try open(bytes,privateKey:key)
                try PilotCloudCredentialStore.save(credentials)
                try Data(hash.utf8).write(to:accepted,options:.atomic)
            }
            if source==incoming{try FileManager.default.removeItem(at:incoming)}
        }
        try pilotJSON(["version":2,"recipientSha256":pilotHash(publicBytes),"configured":try PilotCloudCredentialStore.loadStored() != nil],to:folder.appendingPathComponent("status.json"))
    }
    private static func privateKey()throws->SecKey {
        var query=keyQuery;query[kSecReturnRef as String]=true;var item:CFTypeRef?
        let status=SecItemCopyMatching(query as CFDictionary,&item)
        if status==errSecSuccess{return item as! SecKey}
        guard status==errSecItemNotFound else{throw MappingMission.Failure("Unlock this device to prepare protected Pilot 2 setup.")}
        let attributes:[String:Any]=[kSecAttrKeyType as String:kSecAttrKeyTypeRSA,kSecAttrKeySizeInBits as String:2048,kSecPrivateKeyAttrs as String:[kSecAttrIsPermanent as String:true,kSecAttrApplicationTag as String:tag,kSecAttrAccessible as String:kSecAttrAccessibleWhenUnlockedThisDeviceOnly]]
        var error:Unmanaged<CFError>?
        guard let key=SecKeyCreateRandomKey(attributes as CFDictionary,&error) else{throw MappingMission.Failure("Could not create protected Pilot 2 enrollment.")};return key
    }
    static func enrollment(_ privateKey:SecKey)throws->Data {
        guard let publicKey=SecKeyCopyPublicKey(privateKey),let raw=SecKeyCopyExternalRepresentation(publicKey,nil) as Data? else{throw MappingMission.Failure("Cannot read public enrollment.")}
        func der(_ tag:UInt8,_ bytes:Data)->Data{let count=bytes.count;let length:Data=count<128 ? Data([UInt8(count)]):count<256 ? Data([129,UInt8(count)]):Data([130,UInt8(count>>8),UInt8(count&255)]);return Data([tag])+length+bytes}
        let rsaAlgorithm=Data([0x30,0x0d,0x06,0x09,0x2a,0x86,0x48,0x86,0xf7,0x0d,0x01,0x01,0x01,0x05,0x00])
        return der(0x30,rsaAlgorithm+der(0x03,Data([0])+raw))
    }
    static func open(_ bytes:Data,privateKey:SecKey)throws->PilotCloudCredentials {
        guard bytes.count<=32768,let value=try JSONSerialization.jsonObject(with:bytes) as? [String:Any],value["type"] as? String=="r2c_dji_device_provisioning",value["version"] as? Int==2,value["algorithm"] as? String=="RSA-OAEP-SHA256+A256GCM" else{throw MappingMission.Failure("Unsupported protected deployment bundle.")}
        let recipient=pilotHash(try enrollment(privateKey))
        guard value["recipientSha256"] as? String==recipient else{throw MappingMission.Failure("This protected bundle belongs to another tablet.")}
        func data(_ name:String)throws->Data{guard let text=value[name] as? String,let result=Data(base64Encoded:text) else{throw MappingMission.Failure("Invalid protected deployment bundle.")};return result}
        let wrapped=try data("wrappedKey"),iv=try data("iv"),ciphertext=try data("ciphertext")
        guard wrapped.count==256,iv.count==12,ciphertext.count>=16,let unwrapped=SecKeyCreateDecryptedData(privateKey,.rsaEncryptionOAEPSHA256,wrapped as CFData,nil) as Data?,unwrapped.count==32 else{throw MappingMission.Failure("Protected deployment could not be decrypted.")}
        let box=try AES.GCM.SealedBox(nonce:AES.GCM.Nonce(data:iv),ciphertext:ciphertext.dropLast(16),tag:ciphertext.suffix(16))
        var clear=try AES.GCM.open(box,using:SymmetricKey(data:unwrapped),authenticating:Data(recipient.utf8));defer{clear.resetBytes(in:0..<clear.count)}
        let credentials=try JSONDecoder().decode(PilotCloudCredentials.self,from:clear)
        return try PilotCloudCredentials(appId:credentials.appId,appKey:credentials.appKey,appLicense:credentials.appLicense)
    }
}
