import Foundation
import Security
import R2CCore

/// Device-only Keychain item; never part of a mission, export or preferences file.
public enum PilotCloudCredentialStore {
    private static var query:[String:Any]{[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"org.ncssar.RID2CaltopoApple.pilot-cloud",kSecAttrAccount as String:"dji-cloud-api"]}
    public static func load()throws->PilotCloudCredentials?{try PilotCloudProvisioner.prepare();return try loadStored()}
    static func loadStored()throws->PilotCloudCredentials?{var q=query;q[kSecReturnData as String]=true;q[kSecMatchLimit as String]=kSecMatchLimitOne;var item:CFTypeRef?;let status=SecItemCopyMatching(q as CFDictionary,&item);if status==errSecItemNotFound{return nil};guard status==errSecSuccess,let data=item as? Data else{throw MappingMission.Failure("Unable to read protected Pilot 2 setup. Unlock this device and retry.")};let value=try JSONDecoder().decode(PilotCloudCredentials.self,from:data);return try PilotCloudCredentials(appId:value.appId,appKey:value.appKey,appLicense:value.appLicense)}
    public static func save(_ value:PilotCloudCredentials)throws{let data=try JSONEncoder().encode(value),attributes:[String:Any]=[kSecValueData as String:data,kSecAttrAccessible as String:kSecAttrAccessibleWhenUnlockedThisDeviceOnly];let status=SecItemUpdate(query as CFDictionary,attributes as CFDictionary);if status==errSecItemNotFound{var q=query;attributes.forEach{q[$0]=$1};guard SecItemAdd(q as CFDictionary,nil)==errSecSuccess else{throw MappingMission.Failure("Could not save protected Pilot 2 setup.")}}else if status != errSecSuccess{throw MappingMission.Failure("Could not update protected Pilot 2 setup.")}}
}
