import Foundation

/// Standard capture grids and DJI WPML, matching Android's distance/aircraft-first route contract.
public struct MappingFlightRoute: Sendable {
    public struct Point: Sendable, Equatable {public let x,y:Double}
    public struct Leg: Sendable {public let start,end:Point;public let pass:Int}
    public let legs:[Leg]
    public let photoEstimate,durationEstimateSeconds:Int
    public let height,speed,spacing,transitSpeed:Double
    public let photoTag:String
    public let passes:[MappingProcessingPolicy.Pass]
    public let spacings:[Double]
    private let lat0,lon0,scaleX:Double
    public let cameraProfile: MappingResolution.Profile
    public var aircraft: MappingResolution.Aircraft { cameraProfile.aircraftSpec }
    private let lens:String
    private let focalLength:Double?
    private let exportQualified:Bool
    public init(mission:Data) throws {
        guard let value=try JSONSerialization.jsonObject(with:mission) as? [String:Any],let id=value["missionId"] as? String,UUID(uuidString:id) != nil,
              let parameters=value["parameters"] as? [String:Any],let camera=value["cameraSpecifications"] as? [String:Any],let feature=value["feature"] as? [String:Any],let geometry=feature["geometry"] as? [String:Any],let ring=(geometry["coordinates"] as? [[[Double]]])?.first,ring.count>=3,
              let height=parameters["heightMeters"] as? Double,let resolution=parameters["desiredGroundResolutionCmPerPixel"] as? Double,
              let width=camera["widthPixels"] as? Double,let imageHeight=camera["heightPixels"] as? Double,
              MappingResolution.profiles.map(\.id).contains(value["cameraProfileId"] as? String ?? ""),parameters["captureTrigger"] as? String=="distance-v1",parameters["cameraHeadingPolicy"] as? String=="aircraft-first-v1" else{throw MappingMission.Failure("Unsupported flight plan or camera. Retain the saved route for legacy missions.")}
        let clearance=parameters["clearanceMeters"] == nil ? 15 : (parameters["clearanceMeters"] as? Double ?? .nan)
        guard clearance.isFinite,clearance>0,clearance<=500 else{throw MappingMission.Failure("Invalid clearance margin.")}
        if parameters["maximumObstacleHeightMeters"] != nil {
            guard let obstacle=parameters["maximumObstacleHeightMeters"] as? Double, obstacle.isFinite, obstacle>=0, obstacle<=height-clearance else { throw MappingMission.Failure("Flight height must allow at least \(DisplayUnits.current.length(clearance)) above the maximum obstacle top.") }
        }
        let capture:MappingProcessingPolicy.CaptureProfile
        if let object=value["captureProfile"] {capture=try JSONDecoder().decode(MappingProcessingPolicy.CaptureProfile.self,from:JSONSerialization.data(withJSONObject:object))}else{capture=MappingProcessingPolicy.openGround}
        guard MappingProcessingPolicy.captureProfiles.contains(capture) else{throw MappingMission.Failure("This capture profile has not been qualified for route export.")}
        let profile=MappingResolution.profiles.first{$0.id==value["cameraProfileId"] as? String}!
        self.cameraProfile=profile
        guard (value["aircraftModel"] as? String ?? profile.aircraft) == profile.aircraft else { throw MappingMission.Failure("Mission aircraft does not match the camera profile. Rebuild this plan.") }
        self.focalLength=profile.focalLength35mm
        guard value["profile"] as? String == (profile.mode == .thermal ? "thermal":"daylight-rgb") else {throw MappingMission.Failure("Mission sensor does not match the camera profile.")}
        guard width==profile.sensor.widthPixels,imageHeight==profile.sensor.heightPixels,let fov=camera["diagonalFOVDegrees"] as? Double,abs(fov-profile.sensor.diagonalFOVDegrees)<0.01 else{throw MappingMission.Failure("Mission camera geometry does not match the selected lens. Rebuild this plan.")}
        guard profile.isVisibleWide || capture==MappingProcessingPolicy.openGround else {throw MappingMission.Failure("Crossed surveys require the visible-wide camera.")}
        self.exportQualified=capture.exportQualified
        self.passes=capture.passes;self.height=height;photoTag="R2C"+id.replacingOccurrences(of:"-",with:"").prefix(12);lens=profile.lens
        lat0=ring.map{$0[1]}.reduce(0,+)/Double(ring.count);lon0=ring.map{$0[0]}.reduce(0,+)/Double(ring.count);scaleX=111319.49079327358*cos(lat0 * .pi/180)
        let brush=capture==MappingProcessingPolicy.brushTrees,canopy=brush ? parameters["planningSurfaceHeightMeters"] as? Double ?? .nan:0
        guard height.isFinite,(5...500).contains(height),canopy.isFinite,canopy>=0,canopy<=height-clearance else{throw MappingMission.Failure("Allow at least \(DisplayUnits.current.length(clearance)) above canopy and terrain.")}
        let reference=(height-canopy)/height,gsd=resolution/100
        func near(_ pass:MappingProcessingPolicy.Pass)->Double{let tilt=(90+pass.gimbalPitchDegrees) * .pi/180;return 1/max(1,cos(tilt)+imageHeight*gsd/(2*height)*sin(tilt))}
        spacings=passes.map{imageHeight*gsd*(1-$0.forwardOverlapPercent/100)*pow(near($0),2)*reference}
        spacing=spacings.min()!;transitSpeed=parameters["transitSpeedMetersPerSecond"] as? Double ?? parameters["speedMetersPerSecond"] as? Double ?? 4
        // Match Pilot 2's capture interval constraint and Android's downward tenth rounding.
        let minimumCaptureInterval=profile.minimumCaptureInterval
        let captureSpeedLimit=parameters["maximumCaptureSpeedMetersPerSecond"] as? Double ?? parameters["speedMetersPerSecond"] as? Double ?? 4
        guard captureSpeedLimit.isFinite,(1...15).contains(captureSpeedLimit) else { throw MappingMission.Failure("Maximum capture speed must be between 1 and 15 m/s.") }
        speed=floor(min(captureSpeedLimit,spacing/minimumCaptureInterval)*10+1e-9)/10
        guard speed.isFinite,(1...15).contains(speed),transitSpeed.isFinite,(1...15).contains(transitSpeed) else{throw MappingMission.Failure("Increase ground resolution or height so originals can be captured at least \(minimumCaptureInterval) seconds apart at the minimum route speed of 1 m/s.")}
        var polygon:[Point]=[]
        for p in ring {guard p.count>=2,abs(p[0]-lon0)<0.2,abs(p[1]-lat0)<0.2 else{throw MappingMission.Failure("Split this area into smaller local mapping assignments.")};polygon.append(Point(x:(p[0]-lon0)*scaleX,y:(p[1]-lat0)*111319.49079327358))}
        if polygon.first==polygon.last{polygon.removeLast()}
        guard polygon.count>=3 else{throw MappingMission.Failure("Assignment has no usable area.")}
        for i in polygon.indices{for j in (i+1)..<polygon.count{if j==i+1 || (i==0 && j==polygon.count-1){continue};if Self.intersects(polygon[i],polygon[(i+1)%polygon.count],polygon[j],polygon[(j+1)%polygon.count]){throw MappingMission.Failure("Repair the assignment's crossing/touching boundary first.")}}}
        var twiceArea=0.0
        for i in polygon.indices {let a=polygon[i],b=polygon[(i+1)%polygon.count];twiceArea += a.x*b.y - b.x*a.y}
        let area=abs(twiceArea)/2
        guard area>=1 else{throw MappingMission.Failure("Assignment has no usable area.")}
        var planned:[Leg]=[]
        for (index,pass) in passes.enumerated(){let angle=pass.directionDegrees * .pi/180,oriented=polygon.map{Point(x:$0.x*cos(angle)-$0.y*sin(angle),y:$0.x*sin(angle)+$0.y*cos(angle))}
            func unrotate(_ point:Point)->Point{Point(x:point.x*cos(angle)+point.y*sin(angle),y:-point.x*sin(angle)+point.y*cos(angle))}
            let minimum=oriented.map(\.x).min()!,maximum=oriented.map(\.x).max()!,crossSpacing=width*gsd*near(pass)*(1-pass.sideOverlapPercent/100)*reference
            guard crossSpacing.isFinite,crossSpacing>0 else{throw MappingMission.Failure("Invalid side overlap.")}
            let count=max(1,Int(ceil((maximum-minimum)/crossSpacing)));guard count<=500 else{throw MappingMission.Failure("Split this mission into smaller flight areas.")}
            for column in 0..<count {let x=minimum+(Double(column)+0.5)*(maximum-minimum)/Double(count)
                let hits=oriented.indices.compactMap{i->Double? in let a=oriented[i],b=oriented[(i+1)%oriented.count];return (a.x<=x && b.x>x)||(b.x<=x && a.x>x) ? a.y+(x-a.x)*(b.y-a.y)/(b.x-a.x):nil}.sorted()
                guard hits.count%2==0 else{throw MappingMission.Failure("Assignment cannot be clipped into a mapping grid.")}
                var pairs:[(Double,Double)]=[];for i in stride(from:0,to:hits.count,by:2){if hits[i+1]-hits[i]>0.2{pairs.append((hits[i],hits[i+1]))}};if column%2==1{pairs.reverse()}
                for ends in pairs{let margin=min(100,max(0,parameters["marginMeters"] as? Double ?? 10)),a=Point(x:x,y:ends.0-margin),b=Point(x:x,y:ends.1+margin),start=column%2==0 ? a:b,end=column%2==0 ? b:a
                    let offset=height*reference*tan((90+pass.gimbalPitchDegrees) * .pi/180)*(column%2==0 ? 1.0:-1.0)
                    planned.append(Leg(start:unrotate(Point(x:start.x,y:start.y-offset)),end:unrotate(Point(x:end.x,y:end.y-offset)),pass:index))
                }
            }
        }
        guard !planned.isEmpty,planned.count<=500 else{throw MappingMission.Failure("No usable grid or too many route points. Split the assignment.")}
        legs=planned;let captureSpacings=spacings
        photoEstimate=planned.map{Int(ceil(Self.distance($0.start,$0.end)/captureSpacings[$0.pass]))+1}.reduce(0,+)
        let path=planned.flatMap{[$0.start,$0.end]};durationEstimateSeconds=Int(ceil(zip(path,path.dropFirst()).map{Self.distance($0.0,$0.1)}.reduce(0,+)/speed+Double(planned.count)*4))
    }
    public func coordinate(_ point:Point)->[Double]{[lon0+point.x/scaleX,lat0+point.y/111319.49079327358]}
    /// M4TD Pilot 2 serializes VIS as "visable" (DJI's spelling), including per-photo actions.
    /// Migrate only the old camera fields; keep the saved flight geometry and other entries intact.
    public func correctingCameraSelection(in archive:Data)throws->Data {
        guard lens=="visable" else{return archive}
        var changed=false
        let entries=try OperationalZipArchive.decode(archive,maximumEntries:1000,maximumExpandedBytes:32*1024*1024).map { entry in
            guard ["wpmz/template.kml","wpmz/waylines.wpml"].contains(entry.path),let xml=String(data:entry.data,encoding:.utf8) else{return entry}
            var corrected=xml
            for field in ["imageFormat","payloadLensIndex"] {
                corrected=corrected.replacingOccurrences(of:"<wpml:\(field)>wide</wpml:\(field)>",with:"<wpml:\(field)>visable</wpml:\(field)>")
            }
            changed = changed || corrected != xml
            return OperationalZipArchive.Entry(path:entry.path,data:Data(corrected.utf8))
        }
        return changed ? try OperationalZipArchive.encode(entries,compress:true):archive
    }
    public func kmz()throws->Data{
        guard exportQualified else{throw MappingMission.Failure("This capture profile is preview-only pending qualification. No flight route was issued.")}
        guard photoEstimate<=10000 else{throw MappingMission.Failure("Estimated \(photoEstimate) originals exceed the 10,000-original intake capacity.")}
        return try OperationalZipArchive.encode([.init(path:"wpmz/template.kml",data:Data(xml(template:true).utf8)),.init(path:"wpmz/waylines.wpml",data:Data(xml(template:false).utf8))],compress:true)
    }
    public func summary() throws -> Data {
        try JSONSerialization.data(withJSONObject:["estimatedPhotos":photoEstimate,"estimatedFlightSeconds":durationEstimateSeconds,"transitSpeedMetersPerSecond":transitSpeed,"speedMetersPerSecond":speed,"captureIntervalSeconds":spacing/speed,"waypointCount":legs.count*2,"heightMeters":height,"returnHomeHeightMeters":max(30,height),"heightReference":"takeoff","photoTag":photoTag,"captureTrigger":"multipleDistance","cameraHeadingPolicy":"aircraft-first-v1","photoLimit":10000,"withinPhotoLimit":photoEstimate<=10000,"reviewRequired":true,"flightEstimateIncludes":"capture legs, connections and 4 s per leg; excludes takeoff, landing, launch transit, wind and reserve"],options:[.sortedKeys])
    }
    private func xml(template:Bool)->String{
        var text="<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\"><Document>"
        func value(_ key:String,_ content:Any){text+="<wpml:\(key)>\(String(describing:content).replacingOccurrences(of:"&",with:"&amp;").replacingOccurrences(of:"<",with:"&lt;").replacingOccurrences(of:">",with:"&gt;"))</wpml:\(key)>"}
        func number(_ value:Double)->String{String(format:"%.9f",locale:Locale(identifier:"en_US_POSIX"),value)}
        func action(_ id:Int,_ function:String,_ body:()->Void){text+="<wpml:action>";value("actionId",id);value("actionActuatorFunc",function);text+="<wpml:actionActuatorFuncParam>";body();text+="</wpml:actionActuatorFuncParam></wpml:action>"}
        value("author","RID2Caltopo");let now=Int64(Date().timeIntervalSince1970*1000);value("createTime",now);value("updateTime",now)
        text+="<wpml:missionConfig>";value("flyToWaylineMode","safely");value("finishAction","goHome");value("exitOnRCLost","executeLostAction");value("executeRCLostAction","goBack");value("takeOffSecurityHeight",min(20,height));value("globalTransitionalSpeed",number(transitSpeed));value("globalRTHHeight",number(max(30,height)))
        text+="<wpml:droneInfo>";value("droneEnumValue",aircraft.droneType);value("droneSubEnumValue",aircraft.droneSubtype);text+="</wpml:droneInfo><wpml:payloadInfo>";value("payloadEnumValue",aircraft.payloadType);value("payloadSubEnumValue",0);value("payloadPositionIndex",0);text+="</wpml:payloadInfo></wpml:missionConfig><Folder>"
        value("templateId",0);value("autoFlightSpeed",number(speed))
        if template {value("templateType","waypoint");text+="<wpml:waylineCoordinateSysParam>";value("coordinateMode","WGS84");value("heightMode","relativeToStartPoint");value("positioningType","GPS");text+="</wpml:waylineCoordinateSysParam>";value("globalHeight",number(height));value("gimbalPitchMode","usePointSetting");value("globalWaypointTurnMode","toPointAndStopWithDiscontinuityCurvature");text+="<wpml:globalWaypointHeadingParam>";value("waypointHeadingMode","followWayline");text+="</wpml:globalWaypointHeadingParam>"}
        else{value("waylineId",0);value("executeHeightMode","relativeToStartPoint")}
        for (index,point) in legs.flatMap({[$0.start,$0.end]}).enumerated(){let leg=legs[index/2],pass=passes[leg.pass],yaw=atan2(leg.end.x-leg.start.x,leg.end.y-leg.start.y)*180 / .pi,coordinate=coordinate(point)
            text+="<Placemark><Point><coordinates>\(number(coordinate[0])),\(number(coordinate[1]))</coordinates></Point>";value("index",index)
            if template{value("useGlobalHeight",1);value("useGlobalSpeed",1);value("useGlobalHeadingParam",0);value("useGlobalTurnParam",1);value("gimbalPitchAngle",number(pass.gimbalPitchDegrees))}
            else{value("executeHeight",number(height));value("waypointSpeed",number(speed))}
            text+="<wpml:waypointHeadingParam>";value("waypointHeadingMode","smoothTransition");value("waypointHeadingAngle",number(yaw));value("waypointHeadingPathMode","followBadArc");text+="</wpml:waypointHeadingParam>"
            if !template{text+="<wpml:waypointTurnParam>";value("waypointTurnMode","toPointAndStopWithDiscontinuityCurvature");value("waypointTurnDampingDist",0);text+="</wpml:waypointTurnParam>"}
            if index%2==0 {
                text+="<wpml:actionGroup>";value("actionGroupId",index);value("actionGroupStartIndex",index);value("actionGroupEndIndex",index);value("actionGroupMode","sequence");text+="<wpml:actionTrigger>";value("actionTriggerType","reachPoint");text+="</wpml:actionTrigger>"
                action(0,"rotateYaw"){let previous=index>0 ? legs[index/2-1]:leg,previousYaw=atan2(previous.end.x-previous.start.x,previous.end.y-previous.start.y)*180 / .pi,turn=(yaw-previousYaw+540).truncatingRemainder(dividingBy:360)-180;value("aircraftHeading",number(yaw));value("aircraftPathMode",turn<0 ? "counterClockwise":"clockwise")}
                action(1,"gimbalRotate"){value("payloadPositionIndex",0);value("gimbalHeadingYawBase","north");value("gimbalRotateMode","absoluteAngle");value("gimbalPitchRotateEnable",1);value("gimbalPitchRotateAngle",number(pass.gimbalPitchDegrees));value("gimbalRollRotateEnable",0);value("gimbalRollRotateAngle",0);value("gimbalYawRotateEnable",1);value("gimbalYawRotateAngle",number(yaw));value("gimbalRotateTimeEnable",0);value("gimbalRotateTime",0)}
                if let focalLength = focalLength ?? (cameraProfile.isVisibleWide && aircraft.droneType != 67 ? 24:nil) {action(2,"zoom"){value("payloadPositionIndex",0);value("focalLength",number(focalLength))}}
                if index==0{action(lens=="visable" ? 3:2,"customDirName"){value("payloadPositionIndex",0);value("directoryName",photoTag)}}
                text+="</wpml:actionGroup><wpml:actionGroup>";value("actionGroupId",index+1);value("actionGroupStartIndex",index);value("actionGroupEndIndex",index+1);value("actionGroupMode","sequence");text+="<wpml:actionTrigger>";value("actionTriggerType","multipleDistance");value("actionTriggerParam",number(spacings[leg.pass]));text+="</wpml:actionTrigger>"
                action(0,"takePhoto"){value("payloadPositionIndex",0);value("fileSuffix",photoTag);value("payloadLensIndex",lens);value("useGlobalPayloadLensIndex",0)};text+="</wpml:actionGroup>"
            }
            text+="</Placemark>"
        }
        text+="<wpml:payloadParam>";value("payloadPositionIndex",0);value("imageFormat",lens);text+="</wpml:payloadParam></Folder></Document></kml>";return text
    }
    private static func distance(_ a:Point,_ b:Point)->Double{hypot(a.x-b.x,a.y-b.y)}
    private static func intersects(_ a:Point,_ b:Point,_ c:Point,_ d:Point)->Bool{
        func cross(_ p:Point,_ q:Point,_ r:Point)->Double{(q.x-p.x)*(r.y-p.y)-(q.y-p.y)*(r.x-p.x)}
        return max(min(a.x,b.x),min(c.x,d.x))<=min(max(a.x,b.x),max(c.x,d.x)) && max(min(a.y,b.y),min(c.y,d.y))<=min(max(a.y,b.y),max(c.y,d.y)) && cross(a,b,c)*cross(a,b,d)<=0 && cross(c,d,a)*cross(c,d,b)<=0
    }
}
