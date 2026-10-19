import Foundation

public struct MappingPlanningEstimate:Sendable {
    public let photos,flightSeconds:Int
    public let storage:MappingProcessingPolicy.StorageEstimate
    public let capacityWarning:Bool
    public init(mission:Data,totalCapacity:Int64,availableCapacity:Int64)throws{
        let route=try MappingFlightRoute(mission:mission)
        guard let object=try JSONSerialization.jsonObject(with:mission) as? [String:Any],let camera=object["cameraSpecifications"] as? [String:Any],let parameters=object["parameters"] as? [String:Any],let width=camera["widthPixels"] as? Int,let height=camera["heightPixels"] as? Int,let resolution=parameters["desiredGroundResolutionCmPerPixel"] as? Double else{throw MappingMission.Failure("Invalid storage planning inputs.")}
        photos=route.photoEstimate;flightSeconds=route.durationEstimateSeconds
        let points=route.legs.flatMap{[$0.start,$0.end]},pixel=resolution/100
        let east=points.map(\.x).max()!,west=points.map(\.x).min()!,north=points.map(\.y).max()!,south=points.map(\.y).min()!
        let footprint=hypot(Double(width),Double(height))*pixel
        let area=(east-west+footprint)*(north-south+footprint)
        storage=try MappingProcessingPolicy.storage(photos:photos,width:width,height:height,areaSquareMeters:area,pixelMeters:pixel,regional:area>2*MappingProcessingPolicy.acre || photos>192)
        capacityWarning=storage.approachesCapacity(total:totalCapacity,available:availableCapacity)
    }
}
