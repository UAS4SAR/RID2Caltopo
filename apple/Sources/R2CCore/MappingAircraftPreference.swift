import Foundation

/// UserDefaults access stays on this actor; revision checks preserve rapid selection order.
public actor MappingAircraftPreference {
    public static let shared=MappingAircraftPreference()
    private let suiteName:String?
    private var latestRevision:UInt64=0
    public init(suiteName:String?=nil){self.suiteName=suiteName}
    private var defaults:UserDefaults {suiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard}
    public func load()->String {Self.normalized(defaults.string(forKey:"mapping.selectedAircraft"))}
    public func save(_ model:String,revision:UInt64) {
        guard revision>=latestRevision else{return}
        latestRevision=revision
        defaults.set(Self.normalized(model),forKey:"mapping.selectedAircraft")
    }
    public nonisolated static func normalized(_ model:String?)->String {
        model.flatMap{MappingResolution.aircraftModels.contains($0) ? $0:nil} ?? "DJI Matrice 4TD"
    }
}
