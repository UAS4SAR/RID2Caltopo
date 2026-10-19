import SwiftUI
import WebKit
import MapKit
import R2CCore
import R2CMapping

/// Serial actor keeps geometry, file-system queries and Keychain access off MainActor.
private actor AppleMappingMissionPreviewWorker {
    static let shared=AppleMappingMissionPreviewWorker()
    func credentialsAvailable() -> Bool {(try? PilotCloudCredentialStore.load()) != nil}
    func saveCredentials(appID: String, appKey: String, license: String) throws {
        _ = try PilotCloudCredentialStore.load()
        if !appID.isEmpty || !appKey.isEmpty || !license.isEmpty {
            try PilotCloudCredentialStore.save(PilotCloudCredentials(appId: appID.trimmingCharacters(in: .whitespacesAndNewlines),
                appKey: appKey.trimmingCharacters(in: .whitespacesAndNewlines), appLicense: license.trimmingCharacters(in: .whitespacesAndNewlines)))
        }
        guard try PilotCloudCredentialStore.load() != nil else { throw MappingMission.Failure("The app's DJI registration is unavailable. Install a complete app build.") }
    }
    func prepare(mission:MappingMission,camera:MappingResolution.Profile,gsd:Double,capture:MappingProcessingPolicy.CaptureProfile,
                 canopy:Double,obstacle:Double?,clearance:Double,captureSpeed:Double,transitSpeed:Double) throws -> (MappingPlanningEstimate,MappingFlightRoute,Int64) {
        try Task.checkCancellation()
        if capture==MappingProcessingPolicy.brushTrees && obstacle==nil {throw MappingMission.Failure("Enter the maximum obstacle top above takeoff for brush/tree photo spacing and clearance.")}
        let data=try mission.json(cameraProfile:camera,desiredGsdCmPerPixel:gsd,captureProfile:capture,canopyHeightMeters:canopy,maximumObstacleHeightMeters:obstacle,clearanceMeters:clearance,maximumCaptureSpeedMetersPerSecond:captureSpeed,transitSpeedMetersPerSecond:transitSpeed)
        let root=try MappingMissionStore.applicationRoot()
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        let attributes=try FileManager.default.attributesOfFileSystem(forPath:root.path)
        let free=(attributes[.systemFreeSize] as? NSNumber)?.int64Value ?? 0,total=(attributes[.systemSize] as? NSNumber)?.int64Value ?? 0
        let result=(try MappingPlanningEstimate(mission:data,totalCapacity:total,availableCapacity:free),try MappingFlightRoute(mission:data),free)
        try Task.checkCancellation()
        return result
    }
}

struct AppleMappingMissionView:View {
    @AppStorage(DisplayUnits.storageKey) private var unitsPreference = DisplayUnits.imperial.rawValue
    private var units: DisplayUnits { DisplayUnits.current }
    private var gsdCm: Double? { Double(gsd).map(units.resolutionCm) }
    private var canopyMeters: Double? { obstacleMeters }
    let mission:MappingMission
    var mapName:String="Connected incident map"
    var onClose:(()->Void)? = nil
    var onViewMap: ((MappingLayer) -> Void)? = nil
    var onConnectionPresentationChanged: (Bool) -> Void = { _ in }
    @Environment(\.dismiss) private var dismiss
    @State private var selectedAircraft="DJI Matrice 4TD"
    @State private var selectedCamera="m4td.visible.wide"
    @State private var aircraftPreferenceReady=false
    @State private var captureID=MappingProcessingPolicy.brushTrees.id
    @State private var gsd=DisplayUnits.current.number(DisplayUnits.current.resolutionValue(2), decimals: 4)
    @State private var clearanceText=DisplayUnits.current.number(DisplayUnits.current.lengthValue(15),decimals:2)
    @State private var clearanceMeters=15.0
    @State private var captureSpeedText=DisplayUnits.current.number(DisplayUnits.current.lengthValue(4),decimals:2)
    @State private var captureSpeedMeters=4.0
    @State private var transitSpeedText=DisplayUnits.current.number(DisplayUnits.current.lengthValue(4),decimals:2)
    @State private var transitSpeedMeters=4.0
    @State private var obstacle=""
    private var obstacleMeters:Double?{obstacle.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty ? nil:units.lengthMeters(Double(obstacle) ?? .nan)}
    @State private var showDetails=false
    @State private var showConnection=false
    @State private var showSettings=false
    @State private var cloudConfigured=false
    @State private var busy=false
    @State private var estimate:MappingPlanningEstimate?
    @State private var route:MappingFlightRoute?
    @State private var freeBytes:Int64?
    @State private var error:String?
    private var camera:MappingResolution.Profile?{MappingResolution.profiles.first{$0.aircraft==selectedAircraft && $0.id==selectedCamera}}
    private var capture:MappingProcessingPolicy.CaptureProfile{camera?.isVisibleWide==true ? MappingProcessingPolicy.captureProfiles.first{$0.id==captureID} ?? MappingProcessingPolicy.brushTrees:MappingProcessingPolicy.openGround}
    var body:some View {
        AppleMappingDialog(title:"Define Map Mission") {
            field("Map / incident",value:mapName)
            field("Search assignment",value:mission.title)
            Text("Capture settings").font(.headline)
            Text("Aircraft").font(.caption)
            Picker("Aircraft",selection:$selectedAircraft){
                ForEach(MappingResolution.aircraftModels,id:\.self){Text(MappingResolution.aircraftLabel($0)).tag($0)}
            }.pickerStyle(.menu).disabled(!aircraftPreferenceReady || busy).accessibilityIdentifier("mapping-aircraft")
            if camera != nil {Text("Camera / sensor").font(.caption);Picker("Camera / sensor",selection:$selectedCamera){ForEach(MappingResolution.profiles.filter{$0.aircraft==selectedAircraft}){Text($0.label).tag($0.id)}}.pickerStyle(.menu)}
            else{Text("Camera and sensor profiles for \(selectedAircraft) have not been verified in R2C yet. Mission height/resolution estimates are disabled for this aircraft.")}
            VStack(alignment:.leading){Text("Target ground resolution (\(units.resolutionUnit))").font(.caption);TextField("Target ground resolution (\(units.resolutionUnit))",text:$gsd).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).accessibilityIdentifier("mapping-target-gsd")}.disabled(camera==nil)
            if let camera,let value=gsdCm,let height=MappingResolution.heightMeters(gsdCmPerPixel:value,camera:camera.sensor){
                Text("Estimated height above takeoff: \(units.length(height)) · about \(units.number((units == .metric ? 100 : 30.48) / value)) pixels/\(units.lengthUnit)")
                if showDetails{Text("Height uses level-ground nadir sampling and \(Int(camera.sensor.widthPixels)) × \(Int(camera.sensor.heightPixels)) originals. Pixel sampling is not surveyed accuracy.")}
            }else if camera != nil{Text("Enter a target between \(units.resolution(0.2)) and \(units.resolution(100)).")}
            if camera?.isVisibleWide==true {
                Text("Capture pattern").font(.caption)
                Picker("Capture pattern",selection:$captureID){ForEach(MappingProcessingPolicy.captureProfiles,id:\.id){Text($0.label).tag($0.id)}}.pickerStyle(.menu).accessibilityIdentifier("mapping-capture-pattern")
                Text(capture.description)
            }
            if camera?.focalLength35mm != nil {Text("Telephoto survey · nadir −90°.")}
            if camera?.mode == .visible {
                Text("Automatic setup: selected lens, VIS-only capture, and original-photo return when connected to Pilot 2.")
                Text((camera?.captureSettings ?? "") + " R2C cannot yet set or verify these camera settings before capture.")
            }
            VStack(alignment:.leading){Text("Maximum speed while capturing (\(units.lengthUnit)/s)").font(.caption);TextField("Maximum speed while capturing (\(units.lengthUnit)/s)",text:$captureSpeedText).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).accessibilityIdentifier("mapping-capture-speed")}
                .onChange(of:captureSpeedText){_,value in captureSpeedMeters=Double(value).map(units.lengthMeters) ?? .nan}
            Text("The planner may reduce capture speed to allow time between photos.")
            VStack(alignment:.leading){Text("Transit speed (\(units.lengthUnit)/s)").font(.caption);TextField("Transit speed (\(units.lengthUnit)/s)",text:$transitSpeedText).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).accessibilityIdentifier("mapping-transit-speed")}
                .onChange(of:transitSpeedText){_,value in transitSpeedMeters=Double(value).map(units.lengthMeters) ?? .nan}
            Text("Used to reach the first waypoint and resume an interrupted route. Return-to-Home speed is controlled by the aircraft/Pilot 2.")
            Text("Obstacle clearance").font(.headline)
            VStack(alignment:.leading){Text("Maximum obstacle top above takeoff (\(units.lengthUnit) ATO)").font(.caption);TextField("Maximum obstacle top above takeoff (\(units.lengthUnit) ATO)",text:$obstacle).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).accessibilityIdentifier("mapping-obstacle-height")}
            Text("Include terrain rise and antennas, trees or buildings along the full route, including outside the boundary. For brush/tree surveys, this also sets photo spacing and is required. For open ground, blank means not assessed.")
            VStack(alignment:.leading){Text("Clearance margin (\(units.lengthUnit))").font(.caption);TextField("Clearance margin (\(units.lengthUnit))",text:$clearanceText).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).accessibilityIdentifier("mapping-clearance-margin")}
                .onChange(of:clearanceText){_,value in clearanceMeters=Double(value).map(units.lengthMeters) ?? .nan}
            if let top=obstacleMeters,top.isFinite,clearanceMeters.isFinite,clearanceMeters>0{Text("Minimum flight height: \(units.length(top+clearanceMeters)) above takeoff")}
            if let obstacleMeters,obstacleMeters.isFinite,obstacleMeters>=0,let camera,let resolution=gsdCm,let height=MappingResolution.heightMeters(gsdCmPerPixel:resolution,camera:camera.sensor){Text("Clearance above entered obstacle: \(units.length(height-obstacleMeters)). Selected clearance margin: \(units.length(clearanceMeters)).")}
            if let top=obstacleMeters,top.isFinite,top>=0,clearanceMeters.isFinite,clearanceMeters>0,let camera,let minimum=MappingResolution.gsdCmPerPixel(heightMeters:top+clearanceMeters,camera:camera.sensor) {
                Text("Finest resolution at minimum flight height: \(units.resolution(minimum)) with this camera.")
            }
            Text("Route estimate").font(.headline)
            if let route {
                Text("Planned capture speed: \(units.length(route.speed,decimals:2))/s · Transit speed: \(units.length(route.transitSpeed,decimals:2))/s").accessibilityIdentifier("mapping-planned-speed")
                Text(String(format:"About %d originals · %.1f min on the route · %.1f s between photos while moving",route.photoEstimate,Double(route.durationEstimateSeconds)/60,route.spacing/route.speed)).accessibilityIdentifier("mapping-route-estimate")
                if showDetails {
                    Text("Photos are triggered by distance while moving along capture legs.")
                    ForEach(Array(route.passes.enumerated()),id:\.offset){index,pass in Text(String(format:"%.0f° pitch · %.0f° grid (alternating headings) · %.0f%% forward / %.0f%% side overlap · %.2f s/photo",pass.gimbalPitchDegrees,pass.directionDegrees,pass.forwardOverlapPercent,pass.sideOverlapPercent,route.spacings[index]/route.speed))}
                    Text("Route time excludes takeoff, landing, transit and battery reserve. Review the complete route and clearance in Pilot 2 before flight.")
                }
            }
            Text("Storage").font(.headline)
            if let estimate {
                Text("Estimated storage needed: \(ByteCountFormatter.string(fromByteCount:estimate.storage.required,countStyle:.file)) at peak · originals, processing files and finished maps")
                if estimate.capacityWarning{Text("Storage warning: this flight may leave the tablet over 80% full. Free space or reduce the assignment before capture.").foregroundStyle(.red)}
            }
            if let freeBytes{Text("Free tablet storage: \(ByteCountFormatter.string(fromByteCount:freeBytes,countStyle:.file))")}
            if let error{
                if capture==MappingProcessingPolicy.brushTrees,let camera,let canopyHeight=canopyMeters,let resolution=gsdCm,let height=MappingResolution.heightMeters(gsdCmPerPixel:resolution,camera:camera.sensor),canopyHeight>height-clearanceMeters,let minimum=MappingResolution.gsdCmPerPixel(heightMeters:canopyHeight+clearanceMeters,camera:camera.sensor){
                    Text("Flight height \(units.length(height)) must be at least \(units.length(canopyHeight+clearanceMeters)) for \(units.length(canopyHeight)) maximum obstacle top plus \(units.length(clearanceMeters)) clearance. Set ground resolution to at least \(units.resolution(ceil(minimum*10)/10)), or correct the maximum obstacle top estimate.").foregroundStyle(.red)
                }else{Text(error).foregroundStyle(.red)}
            }
            Button(showDetails ? "Hide route details":"Route details"){showDetails.toggle()}
            if showDetails {
                if let launch=mission.launchLocation {Text(String(format:"R2C launch reference: %.5f, %.5f",launch.latitude,launch.longitude) + " (±\(units.length(launch.horizontalAccuracyMeters))). Terrain elevation is not included.")}
                else{Text("No fresh R2C location fix was available for the launch reference.")}
            }
            if !cloudConfigured{Text("The app’s DJI registration is unavailable. Install a complete app build.").foregroundStyle(.red)}
            Button("Pilot 2 connection settings"){showSettings=true}
        } actions:{Button("Close"){close()}.disabled(busy);Button(busy ? "Preparing HTTP…":"Test Pilot 2 using HTTP"){prepare()}.disabled(!aircraftPreferenceReady || busy || route==nil || !capture.exportQualified || !cloudConfigured).accessibilityIdentifier("mapping-http-connect")}
        .task {
            let model=await MappingAircraftPreference.shared.load()
            guard !Task.isCancelled else{return}
            selectedAircraft=model
            if !MappingResolution.profiles.contains(where:{$0.aircraft==model && $0.id==selectedCamera}) {
                selectedCamera=MappingResolution.profiles.first{$0.aircraft==model}?.id ?? ""
            }
            aircraftPreferenceReady=true
        }
        .onChange(of:selectedAircraft){_,model in
            selectedCamera=MappingResolution.profiles.first{$0.aircraft==model}?.id ?? ""
            if aircraftPreferenceReady {
                let revision=DispatchTime.now().uptimeNanoseconds
                Task {await MappingAircraftPreference.shared.save(model,revision:revision)}
            }
        }
        .task(id:"\(selectedAircraft):\(selectedCamera):\(captureID):\(gsd):\(obstacle):\(clearanceMeters):\(captureSpeedMeters):\(transitSpeedMeters)"){await updateEstimate()}
        .task(id:showSettings){
            if !showSettings {
                let configured=await AppleMappingMissionPreviewWorker.shared.credentialsAvailable()
                if !Task.isCancelled {cloudConfigured=configured}
            }
        }
        .sheet(isPresented:$showConnection){AppleMappingTransferView(missionID:mission.id.uuidString.lowercased(),onViewMap:onViewMap)}
        .onChange(of:showConnection,initial:true){_,presented in onConnectionPresentationChanged(presented)}
        .sheet(isPresented:$showSettings){ApplePilotCloudSetupView{cloudConfigured=true;showSettings=false}}

    }
    private func field(_ label:String,value:String)->some View{VStack(alignment:.leading){Text(label).font(.caption);Text(value).frame(maxWidth:.infinity,alignment:.leading).padding(10).overlay(RoundedRectangle(cornerRadius:6).stroke(.secondary.opacity(0.5)))}}
    private func close(){if let onClose{onClose()}else{dismiss()}}
    private func prepare(){guard let camera,let gsd=gsdCm else{return};busy=true
        Task {
        do{try await AppleLocalMappingModel.shared.save(mission:mission,profile:camera,gsd:gsd,capture:capture,canopy:canopyMeters ?? .nan,maximumObstacleHeightMeters:obstacleMeters,clearanceMeters:clearanceMeters,maximumCaptureSpeedMetersPerSecond:captureSpeedMeters,transitSpeedMetersPerSecond:transitSpeedMeters);showConnection=true}catch{self.error=error.localizedDescription};busy=false
        }
    }
    private func updateEstimate()async{
        route=nil;estimate=nil;error=nil
        guard let camera else{return}
        do {
            let value=try await AppleMappingMissionPreviewWorker.shared.prepare(mission:mission,camera:camera,gsd:gsdCm ?? .nan,capture:capture,
                canopy:canopyMeters ?? .nan,obstacle:obstacleMeters,clearance:clearanceMeters,captureSpeed:captureSpeedMeters,transitSpeed:transitSpeedMeters)
            try Task.checkCancellation();estimate=value.0;route=value.1;freeBytes=value.2
        }catch is CancellationError{}catch{if !Task.isCancelled {self.error=error.localizedDescription}}
    }
}

/// SwiftUI's checkbox style is macOS-only; use the same explicit checkbox on iPad.
private struct AppleMappingExportCheckboxStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack {
                Image(systemName:configuration.isOn ? "checkmark.square.fill":"square")
                configuration.label
            }
        }
        .buttonStyle(.plain)
        .accessibilityValue(configuration.isOn ? "Checked":"Unchecked")
    }
}

struct AppleMappingLayerInspector:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    let layer:MappingLayer
    let onDelete:()->Void
    var onView:(()->Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var surface=false
    @State private var confirmingDelete=false
    @State private var softEdges=true
    @State private var files: MappingResultFiles?
    @State private var originals=false
    @State private var sharingSurfaces=false
    @State private var exportTask: Task<Void,Never>?
    @State private var exportWorker: Task<MappingGeoTiff.Result,Error>?
    @State private var geoTiff: MappingGeoTiff.Result?
    @State private var limitGeoTiffForCalTopo=false
    @State private var fileError: String?
    var body:some View {
        AppleMappingDialog(title:"Map Data Inspector") {
            Text(layer.title)
            Text("Captured: \(layer.captureTimeSummary ?? "Unavailable")")
            Text("Camera: \(files?.cameraLabel ?? MappingResolution.cameraLabel(profileID: layer.cameraProfileId))")
            Text("\(layer.photoCount ?? 0) source photos used to create this map")
            if let value=layer.requestedResolutionCm{Text("Estimated ground sampling: \(DisplayUnits.current.resolution(value))")}
            if let spacing=layer.surfaceModel?.meshSpacingMeters{Text("3D preview mesh: \(DisplayUnits.current.length(spacing, decimals: 1)) spacing")}
            Text("Zoom levels: \(layer.minZoom)–\(layer.maxZoom)")
            if let engine=layer.engine{Text(engine)}
            if let quality=layer.quality{Text(quality)}
            if layer.assignmentBoundary != nil {Toggle(softEdges ? "Target region · 5 m outer fade":"Full captured extent",isOn:$softEdges).onChange(of:softEdges){_,value in AppleMappingLayerStore.shared.setSoft(layer.id,enabled:value)}}
            if let onView {Button("View 2D map",action:onView)}
            if layer.surfaceModel != nil{Button("View 3D surface"){surface=true}}
            if let files {
                if files.surfaces.count == 1, let file = files.surfaces.first { ShareLink("Share 3D surface (.glb)", item: file) }
                else if !files.surfaces.isEmpty { Button("Share 3D surfaces (.glb)…") { sharingSurfaces = true } }
                Toggle("Limit to 40 MB for CalTopo",isOn:$limitGeoTiffForCalTopo)
                    .toggleStyle(AppleMappingExportCheckboxStyle())
                    .disabled(exportTask != nil)
                    .accessibilityIdentifier("mapping-geotiff-caltopo-limit")
                    .onChange(of:limitGeoTiffForCalTopo){_,_ in geoTiff=nil;fileError=nil}
                Button("Prepare compressed GeoTIFF (.tif)") {
                    let root=AppleMappingTileCache.layerRoot(layer.id),minZoom=layer.minZoom,maxZoom=layer.maxZoom,assignmentName=files.assignmentName,limitForCalTopo=limitGeoTiffForCalTopo
                    fileError=nil
                    let worker=Task.detached(priority:.userInitiated) {try MappingGeoTiff.export(root:root,minZoom:minZoom,maxZoom:maxZoom,assignmentName:assignmentName,limitForCalTopo:limitForCalTopo)}
                    exportWorker=worker
                    exportTask=Task { @MainActor in
                        defer {exportTask=nil;exportWorker=nil}
                        do {let result=try await worker.value;try Task.checkCancellation();geoTiff=result}
                        catch is CancellationError {} catch {if !Task.isCancelled {fileError=error.localizedDescription}}
                    }
                }.disabled(exportTask != nil || geoTiff != nil)
                if exportTask != nil {ProgressView("Preparing GeoTIFF…");Button("Cancel GeoTIFF export"){exportWorker?.cancel();exportTask?.cancel()}}
                if let geoTiff {
                    Text("GeoTIFF ready · " + (geoTiff.reducedDetail ? "reduced detail to fit 40 MB":"full saved detail") + " · zoom \(geoTiff.zoom) · \(ByteCountFormatter.string(fromByteCount:geoTiff.byteCount,countStyle:.file))")
                    if geoTiff.byteCount>40_000_000 {Text("CalTopo documents a 40 MB Map Sheet upload limit. This full-resolution export is larger and may be rejected.")}
                    ShareLink("Share GeoTIFF (.tif)",item:geoTiff.url)
                }
                if let archive = files.archive { ShareLink("Share this layer (.zip)", item: archive) }
                if files.hasOriginals { Button("Browse original photos") { originals = true }.accessibilityIdentifier("mapping-browse-originals") }
                else { Text("Original photos are not stored on this device for this result.").font(.footnote) }
            } else { ProgressView("Loading local files…") }
            if let fileError { Text(fileError).foregroundStyle(.red) }
            Text("GeoTIFF uses lossless compression and preserves transparent gaps. Leave the limit unchecked for full saved detail. When checked, the highest saved resolution whose compressed file fits 40 MB is used. In CalTopo, choose + Add → Map Sheet → Upload New.")
            Text("The ZIP contains this result’s 2D tiles, 3D data when available, and metadata. Original flight photos are shared separately.")
        } actions:{Button("Delete layer from this tablet",role:.destructive){confirmingDelete=true}.disabled(exportTask != nil);Button("Done"){dismiss()}}
        .task(id:layer.id) {
            let layer = self.layer, result = AppleMappingTileCache.layerRoot(layer.id)
            do {
                files = try await Task.detached(priority: .userInitiated) {
                    try MappingResultFiles.inspect(result: result, layer: layer, missionsRoot: MappingMissionStore.applicationRoot())
                }.value
            } catch { fileError = error.localizedDescription }
        }
        .fullScreenCover(isPresented:$originals) { if let id = files?.missionID { AppleMappingOriginalPhotosView(missionID: id) } }
        .sheet(isPresented:$sharingSurfaces) {
            NavigationStack {
                List(Array((files?.surfaces ?? []).enumerated()), id: \.element) { index, file in ShareLink("Share region \(index + 1) (.glb)", item: file) }
                    .navigationTitle("3D surfaces").toolbar { Button("Done") { sharingSurfaces = false } }
            }
        }
        .onDisappear{exportWorker?.cancel();exportTask?.cancel()}
        .onChange(of:layer.id){_,_ in exportWorker?.cancel();exportTask?.cancel();geoTiff=nil;limitGeoTiffForCalTopo=false}
        .onAppear{softEdges=UserDefaults.standard.object(forKey:"mapping.soft.\(layer.id)") as? Bool ?? true}
        .fullScreenCover(isPresented:$surface){AppleMappingSurfaceView(result:AppleMappingTileCache.layerRoot(layer.id))}
        .alert("Delete layer from this tablet?",isPresented:$confirmingDelete){Button("Delete layer",role:.destructive){onDelete()};Button("Cancel",role:.cancel){}}message:{Text("Delete this generated result. Original flight photos and the plan are retained.")}
    }
}

@MainActor final class AppleMappingLayerStore: ObservableObject {
    static let shared = AppleMappingLayerStore()
    @Published private(set) var layers: [MappingLayer]
    @Published var localPreviewID:String?
    @Published private var hidden: Set<String>
    private init() {
        layers = (UserDefaults.standard.data(forKey:"mappingLayers").flatMap { try? JSONDecoder().decode([MappingLayer].self,from:$0) }) ?? []
        hidden=Set(UserDefaults.standard.stringArray(forKey:"hiddenMappingLayers") ?? [])
    }
    func isVisible(_ layerID:String) -> Bool { !hidden.contains(layerID) }
    func setVisible(_ layerID:String,visible:Bool) {
        guard layers.contains(where:{$0.id==layerID}) else { return }
        if visible { hidden.remove(layerID) } else { hidden.insert(layerID) }
        save()
    }
    func importLayer(link:String,incidentID:String) async throws {
        guard let url=URL(string:link.trimmingCharacters(in:.whitespacesAndNewlines)),["http","https"].contains(url.scheme ?? ""),url.host != nil,url.user == nil,url.password == nil else { throw MappingMission.Failure("Enter the layer link from the mapping station.") }
        var request=URLRequest(url:url);request.timeoutInterval=25
        let (bytes,response)=try await URLSession.shared.bytes(for:request)
        guard (response as? HTTPURLResponse)?.statusCode==200 else { throw MappingMission.Failure("Layer link is unavailable or expired. Get a new link from the station.") }
        var data=Data()
        for try await byte in bytes { data.append(byte);if data.count>1024*1024 { throw MappingMission.Failure("Layer manifest is too large.") } }
        let layer=try MappingLayer.decode(data,incidentID:incidentID)
        hidden.remove(layer.id)
        layers.removeAll { $0.id==layer.id && $0.incidentId==layer.incidentId };layers.append(layer);save()
    }
    func setSoft(_ id:String,enabled:Bool){objectWillChange.send();UserDefaults.standard.set(enabled,forKey:"mapping.soft.\(id)")}
    func deleteLocally(_ layer:MappingLayer) {
        hidden.insert(layer.id)
        layers.removeAll { $0.id==layer.id }
        let root = AppleMappingTileCache.layerRoot(layer.id)
        Task.detached(priority: .utility) { try? FileManager.default.removeItem(at: root) }
        UserDefaults.standard.removeObject(forKey:"mapping.result.\(layer.id)")
        save()
    }
    func installShared(_ incoming:[MappingLayer],incidentID:String) throws {
        guard incoming.allSatisfy({$0.incidentId==incidentID}) else { throw MappingMission.Failure("This package contains a layer from another incident map.") }
        for layer in incoming {
            let data=try JSONEncoder().encode(layer)
            _ = try MappingLayer.decode(data,incidentID:incidentID)
            hidden.remove(layer.id)
            layers.removeAll { $0.id==layer.id && $0.incidentId==incidentID }
            layers.append(layer)
        }
        save()
    }
    private func save() {
        UserDefaults.standard.set(Array(hidden),forKey:"hiddenMappingLayers")
        if let data=try? JSONEncoder().encode(layers) { UserDefaults.standard.set(data,forKey:"mappingLayers") }
    }
    func refreshShared(prefix:String,token:String,incidentID:String) async -> Bool {
        guard !token.isEmpty,!incidentID.isEmpty,let base=URL(string:prefix),var components=URLComponents(url:base.appendingPathComponent("api/v1/mapping-layers"),resolvingAgainstBaseURL:false) else { return false }
        components.queryItems=[URLQueryItem(name:"incidentId",value:incidentID)]
        guard let url=components.url else { return false }
        var request=URLRequest(url:url);request.timeoutInterval=25
        request.setValue(token,forHTTPHeaderField:"X-SAR-Token")
        request.setValue(String(TrackerCoordinationClient.trackerFunctionalityRelease),forHTTPHeaderField:"X-R2C-Functionality-Release")
        do {
            let (bytes,response)=try await URLSession.shared.bytes(for:request)
            let status=(response as? HTTPURLResponse)?.statusCode ?? 0
            if [401,403,404,503].contains(status) { return false }
            guard status==200 else { return true }
            var data=Data()
            for try await byte in bytes { data.append(byte);if data.count>1024*1024 { return true } }
            guard !Task.isCancelled,let descriptors=try JSONSerialization.jsonObject(with:data) as? [[String:Any]] else { return true }
            var additions:[MappingLayer]=[]
            for descriptor in descriptors {
                guard var manifest=descriptor["manifest"] as? [String:Any],let template=descriptor["tileUrlTemplate"] as? String else { continue }
                manifest["tileUrlTemplate"]=template
                if let layer=try? MappingLayer.decode(JSONSerialization.data(withJSONObject:manifest),incidentID:incidentID),!hidden.contains(layer.id) { additions.append(layer) }
            }
            let ids=Set(additions.map(\.id))
            let next=layers.filter { !ids.contains($0.id) }+additions
            if next != layers { layers=next;save() }
        } catch { /* Retain local layers during network interruptions. */ }
        return true
    }
}

private struct MappingTileResultTransfer: @unchecked Sendable {
    let result: (Data?, Error?) -> Void
}

// NSCache supports concurrent access; bridge Foundation objects only inside
// this wrapper so tile workers capture Swift Sendable values.
private final class MappingRenderedTileCache: @unchecked Sendable {
    private let cache = NSCache<NSString, NSData>()
    init() {
        cache.totalCostLimit = 16 * 1024 * 1024
        cache.countLimit = 192
    }
    func data(for key: String) -> Data? { cache.object(forKey: key as NSString).map { $0 as Data } }
    func insert(_ data: Data, for key: String) { cache.setObject(data as NSData, forKey: key as NSString, cost: data.count) }
}

final class MappingLayerTileOverlay: MKTileOverlay {
    // Let operators zoom beyond the native tile pyramid. The requested child
    // tile is cropped from its highest-resolution parent by loadTile(at:).
    private static let displayMaximumZ = 25
    let layer: MappingLayer
    private let renderedTiles = MappingRenderedTileCache()
    private static let tileWorkers: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "org.ncssar.mapping.tiles"
        queue.qualityOfService = .userInitiated
        queue.maxConcurrentOperationCount = 2
        return queue
    }()
    init(_ layer:MappingLayer) {
        self.layer=layer;super.init(urlTemplate:nil)
        minimumZ=layer.minZoom;maximumZ=Self.displayMaximumZ;tileSize=CGSize(width:256,height:256)
        canReplaceMapContent=false
    }
    override func url(forTilePath path:MKTileOverlayPath) -> URL { layer.tileURL(zoom:path.z,x:path.x,y:path.y)! }
    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, (any Error)?) -> Void) {
        let completion = MappingTileResultTransfer(result: result)
        let soft = UserDefaults.standard.object(forKey: "mapping.soft.\(layer.id)") as? Bool ?? true
        let boundary = layer.assignmentBoundary
        let cache = renderedTiles
        let cacheKey = "\(soft)-\(path.z)-\(path.x)-\(path.y)"
        if let cached = cache.data(for: cacheKey) { completion.result(cached, nil); return }
        let overzoom = OperationalOverzoomTile.resolve(requestedZoom: path.z, requestedX: path.x, requestedY: path.y, sourceMaximumZoom: layer.maxZoom)
        let sourcePath = overzoom.map { MKTileOverlayPath(x: $0.sourceX, y: $0.sourceY, z: $0.sourceZoom, contentScaleFactor: path.contentScaleFactor) } ?? path
        let root = AppleMappingTileCache.layerRoot(layer.id)
        let local = AppleMappingTileCache.savedResultRoot(layer.id) != nil
        let destination = root.appendingPathComponent("\(path.z)/\(path.x)/\(path.y).png")
        let sourceDestination = root.appendingPathComponent("\(sourcePath.z)/\(sourcePath.x)/\(sourcePath.y).png")
        let sourceURL = layer.tileURL(zoom: sourcePath.z, x: sourcePath.x, y: sourcePath.y)
        let render: @Sendable (Data) -> Void = { data in
            autoreleasepool {
                if let cached = cache.data(for: cacheKey) { completion.result(cached, nil); return }
                let fade = soft ? boundary.flatMap(MappingBoundaryFade.init(ring:)) : nil
                let output = fade.map { AppleMappingBoundaryPresentation.tile(data, fade: $0, zoom: path.z, x: path.x, y: path.y) } ?? data
                if let output { cache.insert(output, for: cacheKey) }
                completion.result(output, output == nil ? CocoaError(.fileReadCorruptFile) : nil)
            }
        }
        Self.tileWorkers.addOperation {
            autoreleasepool {
                if let cached = cache.data(for: cacheKey) { completion.result(cached, nil); return }
                if let data = try? Data(contentsOf: destination) { render(data); return }
                if let source = try? Data(contentsOf: sourceDestination), let output = Self.overzoomedTile(source, overzoom: overzoom) {
                    try? FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try? output.write(to: destination, options: .atomic)
                    render(output); return
                }
                // Native results are complete, sparse local pyramids. A missing
                // tile is transparent coverage, not a request to a local server.
                guard !local else { completion.result(nil, CocoaError(.fileNoSuchFile)); return }
                guard let sourceURL else { completion.result(nil, MappingMission.Failure("Invalid mapping tile URL.")); return }
                URLSession.shared.dataTask(with: sourceURL) { data, response, error in
                    guard let data, (response as? HTTPURLResponse)?.statusCode == 200 else {
                        completion.result(nil, error ?? MappingMission.Failure("Mapping tile is unavailable.")); return
                    }
                    Self.tileWorkers.addOperation {
                        autoreleasepool {
                            guard let output = Self.overzoomedTile(data, overzoom: overzoom) else { completion.result(nil, CocoaError(.fileReadCorruptFile)); return }
                            try? FileManager.default.createDirectory(at: sourceDestination.deletingLastPathComponent(), withIntermediateDirectories: true)
                            try? data.write(to: sourceDestination, options: .atomic)
                            if destination != sourceDestination {
                                try? FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                                try? output.write(to: destination, options: .atomic)
                            }
                            render(output)
                        }
                    }
                }.resume()
            }
        }
    }

    private static func overzoomedTile(_ data:Data,overzoom:OperationalOverzoomTile?)->Data? {
        guard let overzoom else { return data }
        guard let image=UIImage(data:data) else { return nil }
        let scale=CGFloat(1 << overzoom.zoomDelta),size=image.size
        let width=size.width/scale,height=size.height/scale
        let origin=CGPoint(x:CGFloat(overzoom.childX)*width,y:CGFloat(overzoom.childY)*height)
        let format=UIGraphicsImageRendererFormat();format.scale=1;format.opaque=false
        let renderer=UIGraphicsImageRenderer(size:CGSize(width:256,height:256),format:format)
        return renderer.image { _ in image.draw(in:CGRect(x:-origin.x*scale,y:-origin.y*scale,width:size.width*scale,height:size.height*scale)) }.pngData()
    }
}

enum AppleMappingTileCache {
    static var root:URL {
        let base=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask).first ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("RID2Caltopo/MappingLayers",isDirectory:true)
    }
    static func savedResultRoot(_ layerID: String) -> URL? {
        guard let saved = UserDefaults.standard.string(forKey: "mapping.result.\(layerID)"),
              let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        return MappingMissionStore.savedResult(saved, layerID: layerID, root: support.appendingPathComponent("mapping-missions", isDirectory: true))
    }
    static func layerRoot(_ layerID:String)->URL {
        savedResultRoot(layerID) ?? root.appendingPathComponent(layerID,isDirectory:true)
    }
    static func tile(layerID:String,zoom:Int,x:Int,y:Int)->URL {
        return layerRoot(layerID).appendingPathComponent(String(zoom),isDirectory:true)
            .appendingPathComponent(String(x),isDirectory:true).appendingPathComponent("\(y).png")
    }
}

// Display-only boundary fade; original tiles remain unchanged for sharing.
private enum AppleMappingBoundaryPresentation {
    private static let transparent: Data = {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: format).image { _ in }.pngData()!
    }()
    static func tile(_ data:Data,fade:MappingBoundaryFade,zoom:Int,x:Int,y:Int)->Data? {
        switch fade.coverage(zoom: zoom, x: x, y: y) {
        case .inside: return data
        case .outside: return transparent
        case .edge: break
        }
        guard let image=UIImage(data:data)?.cgImage,image.width==256,image.height==256 else{return data}
        let world=MappingBoundaryFade.world,unit=world/(256*pow(2,Double(zoom)))
        var bytes=[UInt8](repeating:0,count:256*256*4)
        let valid=bytes.withUnsafeMutableBytes{buffer->Bool in guard let context=CGContext(data:buffer.baseAddress,width:256,height:256,bitsPerComponent:8,bytesPerRow:1024,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue|CGBitmapInfo.byteOrder32Big.rawValue) else{return false};context.draw(image,in:CGRect(x:0,y:0,width:256,height:256));return true}
        guard valid else{return nil}
        for row in 0..<256{for col in 0..<256{let index=(row*256+col)*4;if bytes[index+3]==0{continue}
            let px=(Double(x*256+col)+0.5)*unit-world/2,py=world/2-(Double(y*256+row)+0.5)*unit
            let alpha = fade.opacity(projectedX: px, projectedY: py)
            if alpha < 1 { for channel in 0..<4 { bytes[index+channel] = UInt8((Double(bytes[index+channel]) * alpha).rounded()) } }
        }}
        guard let provider=CGDataProvider(data:Data(bytes) as CFData),let result=CGImage(width:256,height:256,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:1024,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.premultipliedLast.rawValue|CGBitmapInfo.byteOrder32Big.rawValue),provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent) else{return nil}
        return UIImage(cgImage:result).pngData()
    }
}

struct ApplePilotCloudSetupView:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    var saved:()->Void
    @Environment(\.dismiss) private var dismiss
    @State private var appID=""
    @State private var appKey=""
    @State private var license=""
    @State private var configured=false
    @State private var loading=true
    @State private var saving=false
    @State private var error:String?
    var body:some View{AppleMappingDialog(title:"Pilot 2 connection setup") {
        Text("DJI app registration is included with R2C. These fields are for administrator overrides, protected by this device’s Keychain and excluded from backup and mission exports.")
        if configured{Text("Cloud API credentials are already saved. Leave these fields empty to retain them.")}
        TextField("Cloud API app ID",text:$appID).textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled().disabled(loading || saving)
        SecureField("Cloud API app key",text:$appKey).textFieldStyle(.roundedBorder).disabled(loading || saving)
        SecureField("App basic license",text:$license).textFieldStyle(.roundedBorder).disabled(loading || saving)
        Text("Connect this device and the controller to the same Wi-Fi/LAN. In Pilot 2, use Cloud Services → Open Platforms to load the mission and return original photos automatically.")
        if let error{Text(error).foregroundStyle(.red)}
    } actions:{
        Button("Cancel"){dismiss()}.disabled(saving)
        Button(saving ? "Saving…" : "Save setup") {
            let id=appID, key=appKey, value=license
            saving=true;error=nil
            Task {
                do {
                    try await AppleMappingMissionPreviewWorker.shared.saveCredentials(appID:id,appKey:key,license:value)
                    appKey="";license="";saving=false;saved()
                } catch {saving=false;self.error=error.localizedDescription}
            }
        }.disabled(loading || saving)
    }
    .interactiveDismissDisabled(saving)
    .task {
        let available=await AppleMappingMissionPreviewWorker.shared.credentialsAvailable()
        guard !Task.isCancelled else{return}
        configured=available;loading=false
    }}
}
