import SwiftUI
import UniformTypeIdentifiers
import WebKit
import CoreLocation
import R2CCore
import R2CMapping

@MainActor final class AppleLocalMappingModel: ObservableObject {
    static let shared=AppleLocalMappingModel()
    @Published var entries:[MappingLocalMission]=[]
    @Published var loading=false
    @Published var error:String?
    @Published var loadingError:String?
    @Published var progress:MappingProgressSnapshot?
    @Published var busy=false
    @Published var ready:URL?
    struct Outcome {let layer:MappingLayer?;let error:String?}
    @Published var outcome:Outcome?
    var hasProcessingPresentation: Bool { progress != nil || outcome != nil }
    var jobTitle="",jobHeading="Reconstructing mission imagery"
    private let queue=DispatchQueue(label:"org.ncssar.mapping",qos:.userInitiated)
    private var cancellation=MappingCancellation()
    private func store() throws -> MappingMissionStore {try MappingMissionStore(root:MappingMissionStore.applicationRoot())}
    func refresh(incident: String,newest: Bool) {
        guard !loading else{return};loading=true
        queue.async {
            do {let entries=try MappingMissionStore(root:MappingMissionStore.applicationRoot()).list(newestFirst:newest)
                Task{@MainActor in self.entries=entries;self.loading=false;self.loadingError=nil}
            }catch{let message=error.localizedDescription;Task{@MainActor in self.loadingError=message;self.loading=false}}
        }
    }
    func save(mission: MappingMission,profile: MappingResolution.Profile,gsd: Double,capture: MappingProcessingPolicy.CaptureProfile,canopy: Double,maximumObstacleHeightMeters: Double? = nil,clearanceMeters:Double = 15,maximumCaptureSpeedMetersPerSecond:Double? = nil,transitSpeedMetersPerSecond:Double? = nil) async throws {
        try await Task.detached(priority: .userInitiated) {
        let store=try MappingMissionStore(root:MappingMissionStore.applicationRoot()),data=try mission.json(cameraProfile:profile,desiredGsdCmPerPixel:gsd,captureProfile:capture,canopyHeightMeters:canopy,maximumObstacleHeightMeters:maximumObstacleHeightMeters,clearanceMeters:clearanceMeters,maximumCaptureSpeedMetersPerSecond:maximumCaptureSpeedMetersPerSecond,transitSpeedMetersPerSecond:transitSpeedMetersPerSecond)
        let route=try MappingFlightRoute(mission:data),kmz=try route.kmz()
        let folder=try store.savePlan(data,boundary:mission.boundaryKML())
        try kmz.write(to:folder.appendingPathComponent("mission.kmz"),options:.atomic)
        try route.summary().write(to:folder.appendingPathComponent("route-summary.json"),options:.atomic)
        }.value
    }
    func importFiles(_ urls:[URL],id:String,incident:String) {
        guard !busy else{return};busy=true;error=nil;jobTitle=entries.first{$0.id==id}?.title ?? "";jobHeading="Importing original photos";let started=Date();cancellation=MappingCancellation();let cancel=cancellation
        UIApplication.shared.isIdleTimerDisabled=true
        queue.async {
            do {let store=try MappingMissionStore(root:MappingMissionStore.applicationRoot());var digests:[String]=[]
                for (i,url) in urls.enumerated(){try cancel.check();let original=try store.importOriginal(url,missionID:id){stage in
                    try cancel.check();let value=MappingProgressSnapshot(stage:stage,fraction:Double(i)/Double(urls.count),startedAt:started)
                    Task{@MainActor in self.progress=value}
                };digests.append(original.digest)}
                try store.completeSelection(Array(Set(digests)).sorted(),missionID:id)
                Task{@MainActor in self.finish();self.refresh(incident:incident,newest:true)}
            }catch{let message=error.localizedDescription;Task{@MainActor in self.error=message;self.finish();self.refresh(incident:incident,newest:true)}}
        }
    }
    func reconstruct(id:String,incident:String,title:String,selectedDigests:Set<String>? = nil) {
        guard !busy else{return}
        jobTitle=title;jobHeading="Reconstructing mission imagery";outcome=nil
        busy=true;error=nil;ready=nil;cancellation=MappingCancellation();let cancel=cancellation
        AppleLog.info("Mapping", "Reconstruction started mission=\(id) selectedPhotos=\(selectedDigests?.count ?? -1)")
        progress=MappingProgressSnapshot(stage:"Preparing local map",fraction:0,startedAt:Date());UIApplication.shared.isIdleTimerDisabled=true
        queue.async {
            do {let result=try MappingReconstruction.run(store:MappingMissionStore(root:MappingMissionStore.applicationRoot()),missionID:id,selectedDigests:selectedDigests,cancellation:cancel){value in Task{@MainActor in self.progress=value}}
                let data=try Data(contentsOf:result.appendingPathComponent("layer.json")),layer=try MappingLayer.decode(data,incidentID:incident)
                // A persistent lookup keeps products in their owning mission, avoiding duplicate copies.
                UserDefaults.standard.set("\(id)/results/\(layer.id)",forKey:"mapping.result.\(layer.id)")
                Task{@MainActor in
                    do{try AppleMappingLayerStore.shared.installShared([layer],incidentID:incident);self.ready=result;self.outcome=Outcome(layer:layer,error:nil);AppleLog.info("Mapping", "Reconstruction completed mission=\(id) layer=\(layer.id) photos=\(layer.photoCount ?? 0)")}catch{self.error=error.localizedDescription;self.outcome=Outcome(layer:nil,error:error.localizedDescription)}
                    self.finish();self.refresh(incident:incident,newest:true)
                }
            }catch{let message=error is CancellationError ? "Processing cancelled; originals and completed regions retained.":error.localizedDescription
                Task{@MainActor in self.error=message;self.outcome=Outcome(layer:nil,error:message);self.finish();self.refresh(incident:incident,newest:true)}}
        }
    }
    func remove(_ entry:MappingLocalMission,originals:Bool) {
        guard !busy else{return};busy=true;error=nil
        Task {
        do {
        let connection = try await AppleMappingTransferModel.shared.stopForDeletion(entry.id)
        queue.async {
            do{try connection?.closeAndWait();let store=try MappingMissionStore(root:MappingMissionStore.applicationRoot());if originals{try store.deleteMission(entry.id,originalsConfirmed:true)}else{try store.deleteResults(entry.id)}
                let ids=entry.results.map(\.lastPathComponent)
                Task{@MainActor in AppleMappingTransferModel.shared.deletionFinished(entry.id);for layer in AppleMappingLayerStore.shared.layers where ids.contains(layer.id){AppleMappingLayerStore.shared.deleteLocally(layer);UserDefaults.standard.removeObject(forKey:"mapping.result.\(layer.id)")};self.finish();self.refresh(incident:entry.incidentID,newest:true)}
            }catch{let message=error.localizedDescription;Task{@MainActor in self.error=message;self.finish()}}
        }
        } catch { self.error = error.localizedDescription; self.finish() }
        }
    }
    func cancel(){cancellation.cancel()}
    private func finish(){busy=false;progress=nil;UIApplication.shared.isIdleTimerDisabled=AppleMappingTransferModel.shared.active}
}

// Android mapping dialogs are the reference for ordering, labels and navigation.
struct AppleMappingDialog<Content:View,Actions:View>:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    let title:String
    @ViewBuilder var content:()->Content
    @ViewBuilder var actions:()->Actions
    var body:some View {NavigationStack{VStack(spacing:0){
        ScrollView{VStack(alignment:.leading,spacing:10,content:content).frame(maxWidth:.infinity,alignment:.leading).padding(20)}
        Divider();HStack{Spacer();actions()}.padding(16)
    }.navigationTitle(title).navigationBarTitleDisplayMode(.inline)}}
}
struct AppleLocalMappingView:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    let incidentID:String
    var location:CLLocation? = nil
    var assignments:[MappingMission] = []
    var mapName:String = "Connected incident map"
    @ObservedObject private var model=AppleLocalMappingModel.shared
    @ObservedObject private var layers=AppleMappingLayerStore.shared
    @Environment(\.dismiss) private var dismiss
    @AppStorage("mappingLibraryNewestFirst") private var newest=true
    @State private var choosing=false
    @State private var planning:MappingMission?
    @State private var selected:MappingLocalMission?
    @State private var inspecting:MappingLayer?
    @State private var surface:MappingSurfaceSelection?
    @State private var pendingSurface:MappingSurfaceSelection?
    @State private var pendingMap: MappingLayer?
    @State private var connectionPresented = false
    private struct Row:Identifiable {let id,date:String;let mission:MappingLocalMission?;let layer:MappingLayer?}
    private var rows:[Row] {
        let entries=model.entries,owned=Set(entries.flatMap(\.results).map(\.lastPathComponent))
        var result=entries.map{Row(id:$0.id,date:$0.date,mission:$0,layer:nil)}
        result += layers.layers.filter{!owned.contains($0.id)}.map{Row(id:$0.id,date:$0.captureTimesLocal?.first ?? $0.processedAt ?? "",mission:nil,layer:$0)}
        func normalized(_ date:String)->String{String(date.prefix(10)).replacingOccurrences(of:":",with:"-")+String(date.dropFirst(10)).replacingOccurrences(of:" ",with:"T")}
        return result.sorted{newest ? normalized($0.date)>normalized($1.date):normalized($0.date)<normalized($1.date)}
    }
    var body:some View {
        Group {
            if let planning {AppleMappingMissionView(mission:planning,mapName:mapName,onClose:{self.planning=nil;model.refresh(incident:incidentID,newest:newest)},onViewMap:show,onConnectionPresentationChanged:{connectionPresented=$0})}
            else if choosing {
                AppleMappingDialog(title:"New local map · choose assignment") {
                    if assignments.isEmpty{Text("Connect or refresh the incident map to load assignment boundaries. Saved flights and results remain available offline.")}
                    ForEach(assignments){assignment in Button(assignment.title){planning=assignment;choosing=false}.accessibilityIdentifier("mapping-assignment-\(assignment.id)")}
                } actions:{Button("Back to local maps"){choosing=false}}
            } else if let selected {
                AppleMappingMissionDetail(entry:model.entries.first{$0.id==selected.id} ?? selected,onBack:{self.selected=nil},onView:show,onInspect:{inspecting=$0},onConnectionPresentationChanged:{connectionPresented=$0})
            } else {
                AppleMappingDialog(title:"Local maps") {
                    Text("Create a flight, collect photos, reconstruct, and view or share your maps here.")
                    Button("New flight"){choosing=true}.accessibilityIdentifier("mapping-new-flight")
                    Text("Mapping missions").font(.headline)
                    HStack{Button(newest ? "✓ Newest first":"Newest first"){newest=true};Button(newest ? "Oldest first":"✓ Oldest first"){newest=false}}
                    Text("Sorted by capture time; unflown missions use planning time.").font(.footnote)
                    if model.loading && rows.isEmpty {ProgressView("Loading saved missions and storage sizes…")}
                    if let error=model.error ?? model.loadingError {Text(error).foregroundStyle(.red)}
                    if !model.loading && rows.isEmpty && model.error==nil && model.loadingError==nil{Text("No mapping missions yet.")}
                    ForEach(rows){row in
                        VStack(alignment:.leading,spacing:4) {
                            Text(row.mission.map{($0.photos.isEmpty ? "Planned: "+MappingLayer.generatedLabel($0.date):"Captured: "+MappingLayer.capturedLabel($0.date))} ?? row.layer?.captureTimeSummary ?? "Capture time unavailable").font(.footnote)
                            Text(row.mission?.title ?? row.layer?.title ?? "Mission").font(.headline)
                            if let entry=row.mission {
                                Text("Map prepared · \(entry.photos.count) images captured · \(entry.results.isEmpty ? "No map results": "2D / 3D layers available")")
                                Text(entry.status).font(.footnote)
                                distance(latitude:entry.latitude,longitude:entry.longitude)
                                Text("Planning files: \(size(entry.planningBytes)) · Captured images: \(size(entry.photoBytes))")
                                Text("2D tiles: \(size(entry.tileBytes)) · 3D results: \(size(entry.modelBytes))")
                                Text("Total: \(size(entry.totalBytes)) · includes exports, temporary files and other metadata").font(.footnote)
                                Button("Open mission"){selected=entry;model.error=nil}.accessibilityIdentifier("mapping-open-\(entry.id)")
                            } else if let layer=row.layer {
                                Text(layer.surfaceModel == nil ? "2D layer available":"2D / 3D layers available")
                                Text("Imported map result · \(layer.photoCount ?? 0) source photos used · originals not stored here").font(.footnote)
                                distance(latitude:layer.bounds.map{($0[1]+$0[3])/2},longitude:layer.bounds.map{($0[0]+$0[2])/2})
                                AppleImportedMappingStorage(layerID:layer.id)
                                Button("Open mission"){inspecting=layer}
                            }
                        }.frame(maxWidth:.infinity,alignment:.leading).padding(12).background(.quaternary,in:RoundedRectangle(cornerRadius:12))
                    }
                } actions:{Button("Hide local preview"){layers.localPreviewID=nil;for layer in layers.layers{layers.setVisible(layer.id,visible:false)};dismiss()};Button("Done"){dismiss()}}
            }
        }
        .task{repeat{if !model.busy{model.refresh(incident:incidentID,newest:newest)};do{try await Task.sleep(for:.seconds(5))}catch{return}}while !Task.isCancelled}
        .sheet(item:$inspecting){layer in AppleMappingLayerInspector(layer:layer,onDelete:{layers.deleteLocally(layer);inspecting=nil;model.refresh(incident:incidentID,newest:newest)},onView:{show(layer)})}
        .fullScreenCover(item:$surface){AppleMappingSurfaceView(result:$0.url)}
        .fullScreenCover(isPresented:Binding(get:{!connectionPresented && model.hasProcessingPresentation},set:{_ in}),onDismiss:{
            if let pendingSurface { surface=pendingSurface;self.pendingSurface=nil }
            if let pendingMap { self.pendingMap=nil;show(pendingMap) }
        }) {
            AppleMappingProcessingContent(onViewMap:{pendingMap=$0;model.outcome=nil},onViewSurface:{pendingSurface=MappingSurfaceSelection(url:AppleMappingTileCache.layerRoot($0.id));model.outcome=nil})
        }

    }
    private func show(_ layer:MappingLayer){do{try layers.installShared([layer],incidentID:layer.incidentId);layers.setVisible(layer.id,visible:true);layers.localPreviewID=layer.id;inspecting=nil;dismiss()}catch{model.error=error.localizedDescription}}
    @ViewBuilder private func distance(latitude:Double?,longitude:Double?)->some View{
        if let location,let latitude,let longitude{Text("Distance to area center: \(DisplayUnits.current.distance(location.distance(from:CLLocation(latitude:latitude,longitude:longitude))))")}
        else{Text("Distance: current location unavailable")}
    }
}
private struct AppleMappingProcessingContent: View {
    @ObservedObject private var model = AppleLocalMappingModel.shared
    let onViewMap: (MappingLayer) -> Void
    let onViewSurface: (MappingLayer) -> Void
    var body: some View {
        Group {
            if let progress = model.progress {
                AppleMappingProgressView(snapshot: progress, title: model.jobTitle, heading: model.jobHeading, cancel: model.cancel)
            } else if let outcome = model.outcome {
                AppleMappingDialog(title: outcome.error == nil ? "Map ready" : "Reconstruction stopped") {
                    Text(model.jobTitle)
                    if let error = outcome.error { Text(error).foregroundStyle(.red) }
                    if let layer = outcome.layer {
                        Text("\(layer.photoCount ?? 0) visible photos")
                        Text(layer.quality ?? "")
                        Text("Reconstruction finished. Original photos are retained.")
                    }
                } actions: {
                    if let layer = outcome.layer {
                        if layer.surfaceModel != nil { Button("View 3D surface") { onViewSurface(layer) } }
                        Button("View map") { onViewMap(layer) }.accessibilityIdentifier("mapping-view-completed-map")
                    }
                    Button("Done") { model.outcome = nil }
                }
                .accessibilityIdentifier("mapping-processing-outcome")
            }
        }.interactiveDismissDisabled()
    }
}

private func size(_ bytes:Int64)->String{ByteCountFormatter.string(fromByteCount:bytes,countStyle:.file)}
private struct AppleImportedMappingStorage:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    let layerID:String
    @State private var tiles:Int64=0
    @State private var models:Int64=0
    @State private var total:Int64=0
    var body:some View{Group{Text("Planning files: 0 bytes · Captured images: 0 bytes");Text("2D tiles: \(size(tiles)) · 3D results: \(size(models))");Text("Total: \(size(total)) · includes exports, temporary files and other metadata").font(.footnote)}.task{
        let root=AppleMappingTileCache.layerRoot(layerID)
        let usage=await Task.detached{()->(Int64,Int64,Int64) in var t:Int64=0,m:Int64=0,a:Int64=0
            if let files=FileManager.default.enumerator(at:root,includingPropertiesForKeys:[.isRegularFileKey,.fileSizeKey]){for file in ({files.allObjects.compactMap{$0 as? URL}})(){if let info=try? file.resourceValues(forKeys:[.isRegularFileKey,.fileSizeKey]),info.isRegularFile==true{let n=Int64(info.fileSize ?? 0);a+=n;if file.pathExtension=="png"{t+=n}else if file.path.contains("/surface/"){m+=n}}}}
            return(t,m,a)}.value;tiles=usage.0;models=usage.1;total=usage.2
    }}
}
private struct AppleMappingMissionDetail:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    let entry:MappingLocalMission
    let onBack:()->Void,onView:(MappingLayer)->Void,onInspect:(MappingLayer)->Void
    var onConnectionPresentationChanged: (Bool) -> Void = { _ in }
    @ObservedObject private var model=AppleLocalMappingModel.shared
    @State private var route:[String:Double]=[:]
    @State private var results:[MappingLayer]=[]
    @State private var completed:[String]=[]
    @State private var pending=0
    @State private var connect=false
    @State private var importing=false
    @State private var browsingOriginals=false
    @State private var deleteOriginals=false
    @State private var confirmingDeletion=false
    var body:some View {
        AppleMappingDialog(title:entry.title) {
            Text(entry.status)
            Button("Delete generated results · keep photos"){deleteOriginals=false;confirmingDeletion=true}.accessibilityIdentifier("mapping-delete-results")
            Button("Delete entire saved flight…",role:.destructive){deleteOriginals=true;confirmingDeletion=true}.accessibilityIdentifier("mapping-delete-flight")
            if model.busy{Text("Please wait…")}
            if let error=model.error{Text(error).foregroundStyle(.red)}
            Text("Flight plan").font(.headline)
            Text("Generated: \(MappingLayer.generatedLabel(entry.plannedAt))")
            if let count=route["estimatedPhotos"]{Text("Planned: \(Int(count)) photos"+(route["estimatedFlightSeconds"].map{String(format:" · %.1f min route time",$0/60)} ?? ""))}
            if let height=route["heightMeters"]{Text("Planned height: \(DisplayUnits.current.length(height, decimals: 1)) above takeoff")}
            if entry.photos.isEmpty{Button("Connect to Pilot 2"){connect=true};Text("No photos collected yet. Review the route and start the flight in Pilot 2.")}
            Text("Captured images").font(.headline)
            Text("Captured (first photo): \(MappingLayer.capturedLabel(entry.photos.first(where:{!$0.capturedAt.isEmpty})?.capturedAt ?? ""))")
            Text("Camera: \(entry.cameraLabel)")
            Text("\(entry.photos.count) originals verified")
            if !entry.photos.isEmpty { Button("Browse original photos") { browsingOriginals = true }.accessibilityIdentifier("mapping-browse-originals") }
            if !entry.photos.isEmpty{
                Text("Total mapping storage: \(size(entry.totalBytes))").font(.headline)
                Text("Original photos: \(size(entry.photoBytes))");Text("Other controller files / partial uploads: \(size(entry.controllerBytes))");Text("Retained controller files and unfinished transfers, including unmatched files or additional upload copies. Verified mission originals are listed separately.").font(.footnote);Text("Export packages: \(size(entry.exportBytes))");Text("Other metadata: \(size(entry.metadataBytes))");Text("2D map tiles: \(size(entry.tileBytes))");Text("3D models and surface data: \(size(entry.modelBytes))");Text("Temporary processing files: \(size(entry.workBytes))");Text("Planning files: \(size(entry.planningBytes))")
            }
            Button("Import original photos from Files / SD card"){importing=true}
            if pending>0{Text("\(pending) originals still transferring")}
            if !completed.isEmpty {Text("Completed transfer selection: \(completed.count) originals")
                if entry.photos.count>completed.count {Text("\(entry.photos.count-completed.count) additional originals are retained outside that selection. Choose the completed set or all received originals explicitly.")}
            } else if !entry.photos.isEmpty {Text("No completed transfer selection is recorded. Received originals can be selected explicitly for reconstruction.")}
            Text("Map results · \(results.count)").font(.headline)
            ForEach(results){layer in VStack(alignment:.leading,spacing:6){Text(layer.resultTitle);Text("Generated: \(layer.generatedLabel)");Text("\(layer.photoCount ?? 0) photos · \(layer.surfaceModel == nil ? "2D":"2D and 3D")");Button("View 2D map"){onView(layer)};Button("Inspect / share result · 2D and 3D"){onInspect(layer)}}.padding(12).frame(maxWidth:.infinity,alignment:.leading).background(.quaternary,in:RoundedRectangle(cornerRadius:12))}
            if results.isEmpty{Text("No completed map results yet.")}
            if completed.count>=3{Button("Reconstruct completed set · \(completed.count) originals"){model.reconstruct(id:entry.id,incident:entry.incidentID,title:entry.title,selectedDigests:Set(completed))}.accessibilityIdentifier("mapping-reconstruct-completed")}
            if entry.photos.count>=3 && pending==0 && (completed.isEmpty || completed.count<entry.photos.count){Button("Reconstruct all received · \(entry.photos.count) originals"){model.reconstruct(id:entry.id,incident:entry.incidentID,title:entry.title)}.accessibilityIdentifier("mapping-reconstruct-all")}
            if !entry.photos.isEmpty{Text("Reconstruction stays on screen until it finishes or you cancel it. Originals are retained.");Button("Connect to Pilot 2"){connect=true}}
            Text("The connection receives originals for this saved flight. Review clearance and start any flight in Pilot 2.")
        } actions:{Button("Back to local maps",action:onBack)}
        .disabled(model.busy)
        .task(id:"\(entry.photos.count):\(entry.results.count):\(connect)"){await load()}
        .sheet(isPresented:$connect,onDismiss:{model.refresh(incident:entry.incidentID,newest:true)}){AppleMappingTransferView(missionID:entry.id,onViewMap:onView)}
        .onChange(of:connect,initial:true){_,presented in onConnectionPresentationChanged(presented)}
        .fullScreenCover(isPresented:$browsingOriginals) { AppleMappingOriginalPhotosView(missionID: entry.id) }
        .fileImporter(isPresented:$importing,allowedContentTypes:[.jpeg],allowsMultipleSelection:true){value in do{model.importFiles(try value.get(),id:entry.id,incident:entry.incidentID)}catch{model.error=error.localizedDescription}}
        .alert(deleteOriginals ? "Delete entire saved flight?":"Delete generated results?",isPresented:$confirmingDeletion){
            Button(deleteOriginals ? "Delete flight and original photos":"Delete results",role:.destructive){model.remove(entry,originals:deleteOriginals);onBack()}
            Button("Cancel",role:.cancel){}
        } message:{Text(("Any Pilot 2 connection for this mission will close before deletion. ") + (deleteOriginals ? "Permanently delete this flight plan, ALL original photos and controller returns, generated 2D/3D results, export packages, and unfinished processing files from this device. This cannot be undone. Copies already shared elsewhere are unaffected.":"Delete this flight’s generated 2D/3D results, export packages, and unfinished processing files. Keep the original photos and saved flight so you can reconstruct again."))}
    }
    private func load()async{
        do{let id=entry.id,incident=entry.incidentID,urls=entry.results
            let value=try await Task.detached{()->([String:Double],[String],Int,[MappingLayer]) in
                let store=try MappingMissionStore(root:MappingMissionStore.applicationRoot()),root=try store.directory(id)
                let raw=(try? JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("route-summary.json")))) as? [String:Any] ?? [:]
                let route=raw.compactMapValues{($0 as? NSNumber)?.doubleValue}
                let pending=(try? FileManager.default.contentsOfDirectory(atPath:root.appendingPathComponent("incoming").path).filter{$0.hasSuffix(".json")}.count) ?? 0
                return(route,try store.completedSelection(id),pending,try urls.map{try MappingLayer.decode(Data(contentsOf:$0.appendingPathComponent("layer.json")),incidentID:incident)})
            }.value;route=value.0;completed=value.1;pending=value.2;results=value.3.sorted { a,b in
                if a.generatedDate != b.generatedDate { return (a.generatedDate ?? .distantPast) > (b.generatedDate ?? .distantPast) }; return a.id < b.id
            }
        }catch{model.error=error.localizedDescription}
    }
}
struct AppleMappingProgressView:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    let snapshot:MappingProgressSnapshot
    var title:String=""
    var heading:String="Reconstructing mission imagery"
    let cancel:()->Void
    var body:some View{VStack(alignment:.leading,spacing:16){
        Text(heading).font(.title)
        ScrollView{VStack(alignment:.leading,spacing:16){Text(title).font(.title2);Text(snapshot.stage);ProgressView(value:snapshot.fraction)
            TimelineView(.periodic(from:.now,by:1)){context in let elapsed=snapshot.elapsed(at:context.date);VStack(alignment:.leading,spacing:8){Text("\(Int(snapshot.fraction*100))% · \(elapsed/60) min \(elapsed%60) sec elapsed");Text(snapshot.activity(at:context.date))}.frame(maxWidth:.infinity,alignment:.leading)}
            Text(MappingProgressSnapshot.explanation)
            Text("Processing on this tablet. This job stays on screen until it finishes or you cancel it. Originals are retained.")
            Text("Keep R2C open while processing; the operating system may suspend it in the background.").font(.footnote)
        }.frame(maxWidth:.infinity,alignment:.leading)}
        HStack{Spacer();Button("Cancel processing",role:.destructive,action:cancel).buttonStyle(.bordered)}
    }.padding(24).interactiveDismissDisabled()}
}
private struct MappingSurfaceSelection:Identifiable{let id=UUID();let url:URL}
struct AppleMappingSurfaceView:View{
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    let result:URL
    @State private var paths:[String]=[]
    @State private var selected = -1
    @State private var error:String?
    @Environment(\.dismiss) private var dismiss
    var body:some View{
        NavigationStack{VStack{
            if paths.count>1{Text("This map contains \(paths.count) 3D regions. View them together or inspect one region.")
                HStack{Button("Previous region"){selected-=1}.disabled(selected == -1);Picker("3D region",selection:$selected){Text("All regions together").tag(-1);ForEach(paths.indices,id:\.self){Text("Region \($0+1) of \(paths.count)").tag($0)}};Button("Next region"){selected+=1}.disabled(selected==paths.count-1)}}
            if !paths.isEmpty{AppleSurfaceWebView(result:result,relative:selected == -1 ? "":paths[selected]).id(selected).frame(maxWidth:.infinity,maxHeight:.infinity)}
            if let error{Text(error).foregroundStyle(.red)}
        }.navigationTitle("3D mission surface").toolbar{Button("Done"){dismiss()}}.task{
            do {
                let result = self.result
                let loaded = try await Task.detached(priority: .userInitiated) { () -> ([String], Bool) in
                    let collection = result.appendingPathComponent("surface/collection.json")
                    guard FileManager.default.fileExists(atPath: collection.path) else { return (["surface.glb"], false) }
                    let document = try JSONSerialization.jsonObject(with: Data(contentsOf: collection)) as? [String: Any]
                    let regions = document?["regions"] as? [[String: Any]] ?? []
                    let paths = try regions.enumerated().map { i, region in
                        guard region["path"] as? String == "regions/\(i)/surface.glb" else { throw MappingMission.Failure("Invalid surface collection.") }
                        return "regions/\(i)/surface.glb"
                    }
                    return (paths, true)
                }.value
                guard !Task.isCancelled else { return }
                paths = loaded.0
                if !loaded.1 { selected = 0 }
            } catch { self.error = error.localizedDescription }
        }}
    }
}
private struct AppleSurfaceWebView:UIViewRepresentable{
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    let result:URL
    let relative:String
    var qualification:((String)->Void)? = nil
    func makeCoordinator()->Handler{Handler(result:result,relative:relative,qualification:qualification)}
    func makeUIView(context:Context)->WKWebView{let config=WKWebViewConfiguration();config.setURLSchemeHandler(context.coordinator,forURLScheme:"r2c-surface");let view=WKWebView(frame:.zero,configuration:config);view.navigationDelegate=context.coordinator;view.load(URLRequest(url:URL(string:"r2c-surface://local/viewer/index.html?units=\(DisplayUnits.current.rawValue)&surface=\(relative)#\(result.lastPathComponent)")!));return view}
    func updateUIView(_ uiView:WKWebView,context:Context){}
    @MainActor final class Handler:NSObject,WKURLSchemeHandler,WKNavigationDelegate{
        private let disk = OperationalWorkQueue(label: "org.ncssar.mapping-surface.files")
        private var requests: [ObjectIdentifier: Task<Void, Never>] = [:]
        let result:URL,relative:String
        let qualification:((String)->Void)?
        private var reported=false
        init(result:URL,relative:String,qualification:((String)->Void)?){self.result=result;self.relative=relative;self.qualification=qualification}
        func webView(_ webView:WKWebView,didFinish navigation:WKNavigation!){if qualification != nil{poll(webView,remaining:40)}}
        private func poll(_ webView:WKWebView,remaining:Int){
            webView.evaluateJavaScript("document.getElementById('status')?.textContent || ''"){value,error in
                guard !self.reported else{return};let text=value as? String ?? error?.localizedDescription ?? ""
                if text.contains("triangles") || remaining==0 {
                    self.reported=true
                    #if R2C_MAPPING_DEVICE_ACCEPTANCE
                    if ProcessInfo.processInfo.arguments.contains("--mapping-device-acceptance") {
                        DispatchQueue.main.asyncAfter(deadline:.now()+1){
                            webView.takeSnapshot(with:nil){image,_ in
                                let directory=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("mapping-device-acceptance")
                                let name=self.relative.isEmpty ? "combined":self.relative.replacingOccurrences(of:"/",with:"-")
                                if let data=image?.pngData(){try? data.write(to:directory.appendingPathComponent(name+".png"),options:.atomic)}
                                self.qualification?(text)
                            }
                        };return
                    }
                    #endif
                    self.qualification?(text);return
                }
                DispatchQueue.main.asyncAfter(deadline:.now()+0.25){self.poll(webView,remaining:remaining-1)}
            }
        }
        func webView(_ webView: WKWebView, start task: any WKURLSchemeTask) {
            guard let url = task.request.url else { task.didFailWithError(URLError(.badURL)); return }
            let id = ObjectIdentifier(task as AnyObject), result = self.result, relative = self.relative
            requests[id] = Task { [weak self] in
                guard let self else { return }
                defer { requests.removeValue(forKey: id) }
                do {
                    let (data, mime) = try await disk.run { try Self.read(url: url, result: result, relative: relative) }
                    guard !Task.isCancelled else { return }
                    task.didReceive(HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": mime, "Content-Length": String(data.count)])!)
                    task.didReceive(data)
                    task.didFinish()
                } catch {
                    if !Task.isCancelled { task.didFailWithError(error) }
                }
            }
        }
        func webView(_ webView: WKWebView, stop task: any WKURLSchemeTask) {
            requests.removeValue(forKey: ObjectIdentifier(task as AnyObject))?.cancel()
        }
        private nonisolated static func read(url: URL, result: URL, relative: String) throws -> (Data, String) {
            let data: Data, mime: String
                if url.path.hasPrefix("/viewer/"){let name=url.lastPathComponent;guard ["index.html","viewer.js","boundary.js","collection.js","scale.js"].contains(name),let file=Bundle.main.url(forResource:name,withExtension:nil,subdirectory:"mapping-surface") else{throw URLError(.fileDoesNotExist)};data=try Data(contentsOf:file);mime=name.hasSuffix("html") ? "text/html":"application/javascript"}
                else if url.path=="/model/\(result.lastPathComponent)/boundary.json"{
                    let manifest=(try? JSONSerialization.jsonObject(with:Data(contentsOf:result.appendingPathComponent("layer.json")))) as? [String:Any]
                    let cached=UserDefaults.standard.data(forKey:"mappingLayers").flatMap{try? JSONDecoder().decode([MappingLayer].self,from:$0)}?.first{$0.id==result.lastPathComponent}
                    guard let ring=manifest?["assignmentBoundary"] as? [[Double]] ?? cached?.assignmentBoundary else{throw URLError(.fileDoesNotExist)}
                    data=try JSONSerialization.data(withJSONObject:["coordinates":ring,"soft":UserDefaults.standard.object(forKey:"mapping.soft.\(result.lastPathComponent)") as? Bool ?? true]);mime="application/json"
                }
                else if url.path=="/model/\(result.lastPathComponent)/surface.glb"{data=try Data(contentsOf:result.appendingPathComponent("surface/"+relative));mime="model/gltf-binary"}
                else if url.path.hasPrefix("/model/\(result.lastPathComponent)/") {
                    let name=String(url.path.dropFirst("/model/\(result.lastPathComponent)/".count))
                    guard name=="collection.json" || name.range(of:"^regions/[0-9]{1,3}/surface\\.(glb|json)$",options:.regularExpression) != nil else{throw URLError(.fileDoesNotExist)}
                    let file=result.appendingPathComponent("surface/"+name)
                    guard (try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? Int.max)<=32*1024*1024 else{throw URLError(.dataLengthExceedsMaximum)}
                    data=try Data(contentsOf:file);mime=name.hasSuffix("json") ? "application/json":"model/gltf-binary"
                }
                else{throw URLError(.fileDoesNotExist)}
            return (data, mime)
        }

    }
}

@MainActor private final class AppleMappingTransferModel:ObservableObject{
    static let shared=AppleMappingTransferModel()
    private var missionID:String?
    private var host:String?
    private var generation=UUID()
    @Published var url=""
    @Published var code=""
    @Published var status="Waiting for a Wi-Fi or Ethernet address…"
    @Published var flags:[String:Bool]=[:]
    private var server:PilotCloudServer?
    private let connectionQueue = OperationalWorkQueue(label: "org.ncssar.mapping-connection")
    private final class ConnectionState: @unchecked Sendable {
        // Accessed only by connectionQueue, including cancelled startup cleanup.
        var server: PilotCloudServer?
        var missionID: String?
    }
    private var deleting = Set<String>()
    private let connectionState = ConnectionState()
    private var starting = false
    private var draining:[String:PilotCloudServer]=[:]
    var active:Bool{server != nil || starting}
    private var checkingCompleted=false
    func start(_ id:String,host:String?){
        guard draining[id]==nil, !deleting.contains(id) else{status="This mission’s previous connection is closing. Retry the deletion before reconnecting.";return}
        if missionID==id && self.host==host && (server != nil || starting){return}
        stop();missionID=id;self.host=host;let request=generation
        guard let host else{status="No Wi-Fi or Ethernet address is available. Connect this device and the controller to the same LAN; the connection will start automatically.";return}
        status="Starting Pilot 2 connection…"
        starting = true
        UIApplication.shared.isIdleTimerDisabled = true
        let state = connectionState
        Task {
            guard generation == request else { return }
            do {
                let connection = try await connectionQueue.run {
                    try state.server?.closeAndWait(); state.server = nil
                    state.missionID = id
                    guard let credentials = try PilotCloudCredentialStore.load() else { throw MappingMission.Failure("The app’s DJI registration is unavailable. Install a complete app build.") }
                    let connection = try PilotCloudServer(store: MappingMissionStore(root: MappingMissionStore.applicationRoot()), missionID: id, host: host, credentials: credentials, assets: { name in
                        guard let file = Bundle.main.url(forResource: name, withExtension: nil, subdirectory: "dji-cloud") else { throw MappingMission.Failure("Pilot 2 connection page unavailable.") }
                        return try Data(contentsOf: file)
                    }, changed: { [weak self] message, flags in
                        Task { @MainActor in
                            guard let self, self.generation == request else { return }
                            let newlyComplete = flags["originalsComplete"] == true && self.flags["originalsComplete"] != true
                            self.status = message; self.flags = flags
                            if newlyComplete { self.processCompleted(id) }
                            if message.hasPrefix("Native connection expired") { self.stop(); self.status = message }
                        }
                    })
                    state.server = connection
                    return connection
                }
                guard generation == request else { connectionQueue.enqueue { connection.close() }; return }
                starting = false; server = connection
                url = connection.url + "/cloud"; code = connection.code; status = "Waiting for Pilot 2"
                UIApplication.shared.isIdleTimerDisabled = true
                connectionQueue.enqueue { connection.start() }
            } catch {
                guard generation == request else { return }
                stop(); status = error.localizedDescription
            }
        }
    }

    private func processCompleted(_ id:String){
        guard !checkingCompleted,!AppleLocalMappingModel.shared.busy else{return};checkingCompleted=true;let request=generation
        Task {
            defer{checkingCompleted=false}
            do{
                let result=try await Task.detached{()->(Set<String>,String,String,URL)? in
                    let store=try MappingMissionStore(root:MappingMissionStore.applicationRoot()),folder=try store.directory(id),selection=try store.completedSelection(id)
                    guard selection.count>=3 else{return nil}
                    let marker=folder.appendingPathComponent("cloud/automatic-reconstruction-selection.json")
                    if let old=try? JSONDecoder().decode([String].self,from:Data(contentsOf:marker)),Set(old)==Set(selection){return nil}
                    let plan=try JSONSerialization.jsonObject(with:Data(contentsOf:folder.appendingPathComponent("mission.json"))) as! [String:Any]
                    guard plan["profile"] as? String != "thermal" else{return nil}
                    return(Set(selection),plan["incidentId"] as? String ?? "",plan["title"] as? String ?? "Mapping mission",marker)
                }.value
                guard generation==request,let result,!AppleLocalMappingModel.shared.busy else{return}
                try await Task.detached(priority: .utility) {
                    try JSONEncoder().encode(result.0.sorted()).write(to: result.3, options: .atomic)
                }.value
                guard generation == request, !AppleLocalMappingModel.shared.busy else {
                    Task.detached(priority: .utility) { try? FileManager.default.removeItem(at: result.3) }
                    return
                }
                AppleLocalMappingModel.shared.reconstruct(id:id,incident:result.1,title:result.2,selectedDigests:result.0)
            }catch{status="Originals retained. Automatic reconstruction could not start: \(error.localizedDescription)"}
        }
    }
    func stopForDeletion(_ id: String) async throws -> PilotCloudServer? {
        deleting.insert(id)
        if missionID == id { stop() }
        let state = connectionState, retained = draining[id]
        // This barrier also waits for a constructor that was already queued
        // when deletion began; no worker may recreate files after deletion.
        let connection = try await connectionQueue.run { () throws -> PilotCloudServer? in
            if let retained { try retained.closeAndWait(); return retained }
            guard state.missionID == id else { return nil as PilotCloudServer? }
            try state.server?.closeAndWait()
            return state.server
        }
        if let connection { draining[id] = connection }
        return connection
    }
    func deletionFinished(_ id: String) { draining.removeValue(forKey: id); deleting.remove(id) }
    func stop() {
        generation = UUID(); starting = false
        let state = connectionState
        connectionQueue.enqueue { state.server?.close() }
        server = nil; missionID = nil; host = nil; url = ""; code = ""; flags = [:]
        UIApplication.shared.isIdleTimerDisabled = AppleLocalMappingModel.shared.busy
    }
}
struct AppleMappingTransferView:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    let missionID:String
    var onViewMap: ((MappingLayer) -> Void)? = nil
    @State private var pendingMap: MappingLayer?
    @State private var pendingSurface: MappingSurfaceSelection?
    @State private var surface: MappingSurfaceSelection?
    @ObservedObject private var model=AppleMappingTransferModel.shared
    @ObservedObject private var processing=AppleLocalMappingModel.shared
    @ObservedObject private var network=AppleNetworkDiagnosticCenter.shared
    @Environment(\.dismiss) private var dismiss
    @State private var title="Mapping mission"
    @State private var incident=""
    @State private var height=0.0
    @State private var photos=0
    @State private var planned=0
    @State private var minutes=0
    var body:some View{
        AppleMappingDialog(title:"Pilot 2 · \(title)") {
            Text("In Pilot 2, open Cloud Services → Open Platforms. Enter this URL, then the access code. Keep both devices on the same Wi-Fi.")
            Text("HTTP diagnostic · controlled LAN only. Credentials and original photos travel without transport encryption.")
            if !model.url.isEmpty && !model.code.isEmpty {
                Text(model.url).font(.headline).textSelection(.enabled)
                Text("Access code: \(model.code)").font(.title2.monospaced()).textSelection(.enabled)
            } else {
                Text(model.status).foregroundStyle(.orange)
                Button("Retry connection"){model.start(missionID,host:network.currentWiredIPv4Address ?? network.currentControllerIPv4Address)}
            }
            Text("Flight height: \(DisplayUnits.current.length(height)) above takeoff").font(.headline)
            Text("Return-home height: \(DisplayUnits.current.length(max(30,height))) above takeoff")
            Text("The route uses a fixed height relative to takeoff and assumes level ground. Confirm waypoint heights and terrain clearance in Pilot 2 before starting.")
            if !model.url.isEmpty{Text("Mapping is active: automatic app shutdown is paused and the screen stays awake. Back to mission keeps the connection running; Close connection ends it.")}
            Text("Estimated \(planned) photos · \(minutes) minutes on the route, excluding launch transit, takeoff, landing and reserve. Review terrain, route and clearance outside the assignment in Pilot 2.")
            Text(model.status)
            ForEach(["androidPageReached","licenseVerified","mqttConnected","wsConnected","missionLoaded","automaticOriginals"],id:\.self){key in
                Text("\(["androidPageReached":"Android connection page","licenseVerified":"DJI license","mqttConnected":"Controller connection","wsConnected":"Mission connection","missionLoaded":"Route library","automaticOriginals":"Automatic original photos"][key] ?? key): \(model.flags[key]==true ? "Ready":"Waiting")")
            }
            Text("\(photos) mission originals verified")
            if photos>=3{Button("Reconstruct \(photos) originals · 3D surface + 2D map"){processing.reconstruct(id:missionID,incident:incident,title:title)}.disabled(processing.busy)}
            if let error=processing.error{Text(error).foregroundStyle(.red)}

            Text("Pilot 2 reviews and starts the flight. R2C hosts the route and retains the returned original bytes on this tablet.")
        } actions:{Button("Back to mission"){dismiss()};Button("Close connection"){model.stop();dismiss()}}
        .fullScreenCover(isPresented:Binding(get:{processing.hasProcessingPresentation},set:{_ in}),onDismiss:{
            if let pendingSurface { surface=pendingSurface;self.pendingSurface=nil }
            if let pendingMap {
                self.pendingMap=nil
                if let onViewMap { onViewMap(pendingMap) }
                else { AppleMappingLayerStore.shared.setVisible(pendingMap.id,visible:true);AppleMappingLayerStore.shared.localPreviewID=pendingMap.id;dismiss() }
            }
        }) {
            AppleMappingProcessingContent(onViewMap:{pendingMap=$0;processing.outcome=nil},onViewSurface:{pendingSurface=MappingSurfaceSelection(url:AppleMappingTileCache.layerRoot($0.id));processing.outcome=nil})
        }
        .fullScreenCover(item:$surface){AppleMappingSurfaceView(result:$0.url)}
        .onReceive(network.$currentControllerIPv4Address.combineLatest(network.$currentWiredIPv4Address)){wifi,wired in model.start(missionID,host:wired ?? wifi)}
        .task {network.start();model.start(missionID,host:network.currentWiredIPv4Address ?? network.currentControllerIPv4Address)
            while !Task.isCancelled {
                let id=missionID
                if let value=try? await Task.detached(operation:{()->(String,Double,Int,Int,Int,String) in
                    let store=try MappingMissionStore(root:MappingMissionStore.applicationRoot()),root=try store.directory(id)
                    let plan=try JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("mission.json"))) as! [String:Any]
                    let route=(try? JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("route-summary.json")))) as? [String:Any] ?? [:]
                    return(plan["title"] as? String ?? "Mapping mission",route["heightMeters"] as? Double ?? 0,try store.originals(id).count,route["estimatedPhotos"] as? Int ?? 0,((route["estimatedFlightSeconds"] as? Int ?? 0)+59)/60,plan["incidentId"] as? String ?? "")
                }).value {title=value.0;height=value.1;photos=value.2;planned=value.3;minutes=value.4;incident=value.5}
                try? await Task.sleep(for:.seconds(2))
            }
        }
    }
}

#if DEBUG && targetEnvironment(simulator)
struct AppleMappingUIProbe:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    @State private var ready=false
    private let assignment=try! MappingMission(feature:Data(#"{"type":"Feature","id":"ui-assignment","properties":{"class":"Assignment","title":"MapTest"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.999,39],[-120.999,39.001],[-121,39.001],[-121,39]]]}}"#.utf8),incidentID:"FIXTURE")
    var body:some View{Group{if ready {
            if ProcessInfo.processInfo.arguments.contains("--mapping-ui-planner"){AppleMappingMissionView(mission:assignment,mapName:"MapTest")}
            else if ProcessInfo.processInfo.arguments.contains("--mapping-ui-progress"){AppleMappingProgressView(snapshot:MappingProgressSnapshot(stage:"Saving region 2/2 · file 1005",fraction:0.92,startedAt:Date().addingTimeInterval(-660)),title:"MapTest",cancel:{})}
            else if ProcessInfo.processInfo.arguments.contains("--mapping-ui-mission"),let entry=AppleLocalMappingModel.shared.entries.first{AppleMappingMissionDetail(entry:entry,onBack:{},onView:{_ in},onInspect:{_ in})}
            else {AppleLocalMappingView(incidentID:ProcessInfo.processInfo.arguments.contains("--mapping-ui-no-incident") ? "":"FIXTURE",location:CLLocation(latitude:0,longitude:0),assignments:[assignment],mapName:"MapTest")}
        }else{ProgressView("Preparing simulator fixtures…")}}.task{
        if ProcessInfo.processInfo.arguments.contains("--mapping-ui-deletion-fixture"),let store=try? MappingMissionStore(root:MappingMissionStore.applicationRoot()) {
            let id="00000000-0000-0000-0000-000000000081"
            let data=try! JSONSerialization.data(withJSONObject:["missionId":id,"incidentId":"FIXTURE","title":"Deletion fixture","createdAt":"2026-10-15T14:00:00Z"])
            _ = try? store.savePlan(data,boundary:Data())
        }
        if let store=try? MappingMissionStore(root:MappingMissionStore.applicationRoot()),let entries=try? store.list(incidentID:"FIXTURE",newestFirst:true) {AppleLocalMappingModel.shared.entries=entries;for entry in entries{for result in entry.results{UserDefaults.standard.set(result.path,forKey:"mapping.result.\(result.lastPathComponent)")}}}
        ready=true
    }}
}
struct AppleMappingSimulatorProbe:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    @State private var paths:[String]=[]
    @State private var selected=0
    @State private var messages:[String]=[]
    @State private var completed=false
    private var directory:URL {FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("mapping-simulator")}
    private var result:URL {directory.appendingPathComponent("00000000-0000-0000-0000-000000000070")}
    var body:some View {
        VStack{Text("Mapping simulator qualification · region \(selected+1) / \(paths.count)")
            if !paths.isEmpty && !completed{AppleSurfaceWebView(result:result,relative:paths[selected],qualification:{message in
                messages.append(message)
                if selected+1<paths.count && message.contains("triangles"){selected+=1}else{
                    completed=true
                    let report:[String:Any]=["passed":messages.count==paths.count && messages.allSatisfy{$0.contains("triangles")},"regions":messages]
                    try? JSONSerialization.data(withJSONObject:report).write(to:directory.appendingPathComponent("result.json"),options:.atomic)
                }
            }).id(selected)}
            if completed{Text(messages.joined(separator:"\n"))}
        }.task{do{let value=try JSONSerialization.jsonObject(with:Data(contentsOf:result.appendingPathComponent("surface/collection.json"))) as! [String:Any];paths=(value["regions"] as! [[String:Any]]).map{$0["path"] as! String}}catch{completed=true;try? JSONSerialization.data(withJSONObject:["passed":false,"error":error.localizedDescription]).write(to:directory.appendingPathComponent("result.json"))}}
    }
}
#endif

#if R2C_MAPPING_DEVICE_ACCEPTANCE
// Explicit local-only device qualification. Not compiled into ordinary builds;
// mounted inside ContentView's existing device-owner authentication gate.
struct AppleMappingDeviceAcceptanceModifier: ViewModifier {
    @State private var presented=ProcessInfo.processInfo.arguments.contains("--mapping-device-acceptance")
    func body(content:Content)->some View{content.fullScreenCover(isPresented:$presented){AppleMappingDeviceAcceptanceView()}}
}
@MainActor private final class AppleMappingDeviceAcceptance:ObservableObject {
    static let shared=AppleMappingDeviceAcceptance()
    @Published var status="Preparing existing photos"
    @Published var result:URL?
    @Published var paths:[String]=[]
    @Published var selected=0
    @Published var done=false
    private var started=false
    private var messages:[String]=[]
    private let directory=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("mapping-device-acceptance")
    func record(_ values:[String:Any]){try? JSONSerialization.data(withJSONObject:values,options:[.prettyPrinted,.sortedKeys]).write(to:directory.appendingPathComponent("status.json"),options:.atomic)}
    func run()async {
        guard !started else{return};started=true;UIApplication.shared.isIdleTimerDisabled=true
        do {
            let input=directory.appendingPathComponent("input")
            let info=try await Task.detached{()->(String,String,Int) in
                let store=try MappingMissionStore(root:MappingMissionStore.applicationRoot())
                if let argument=ProcessInfo.processInfo.arguments.first(where:{$0.hasPrefix("--mapping-existing-mission=")}) {
                    let id=String(argument.dropFirst("--mapping-existing-mission=".count)),folder=try store.directory(id)
                    guard let plan=try JSONSerialization.jsonObject(with:Data(contentsOf:folder.appendingPathComponent("mission.json"))) as? [String:Any],let incident=plan["incidentId"] as? String else{throw MappingMission.Failure("Missing saved mission")}
                    return(id,incident,try store.originals(id).count)
                }
                let data=try Data(contentsOf:input.appendingPathComponent("mission.json"))
                guard let plan=try JSONSerialization.jsonObject(with:data) as? [String:Any],let id=plan["missionId"] as? String,let incident=plan["incidentId"] as? String else{throw MappingMission.Failure("Missing acceptance input plan")}
                guard let feature=plan["feature"] as? [String:Any] else{throw MappingMission.Failure("Missing acceptance boundary")}
                let boundary=try MappingMission(feature:JSONSerialization.data(withJSONObject:feature),incidentID:incident).boundaryKML()
                _=try store.savePlan(data,boundary:boundary)
                let records=try FileManager.default.contentsOfDirectory(at:input.appendingPathComponent("inventory"),includingPropertiesForKeys:nil).filter{$0.pathExtension=="json"}
                var digests:[String]=[]
                for file in records.sorted(by:{$0.path<$1.path}) {
                    guard let item=try JSONSerialization.jsonObject(with:Data(contentsOf:file)) as? [String:Any],let digest=item["sha256"] as? String,let name=item["name"] as? String else{throw MappingMission.Failure("Invalid acceptance inventory")}
                    let original=try store.importOriginal(input.appendingPathComponent("originals/"+digest+".jpg"),missionID:id,originalName:name){_ in}
                    guard original.digest==digest else{throw MappingMission.Failure("Acceptance original checksum mismatch")};digests.append(digest)
                }
                try store.completeSelection(digests,missionID:id)
                return(id,incident,digests.count)
            }.value
            record(["stage":"imported","mission":info.0,"photos":info.2]);status="Reconstructing existing MapTest photos"
            let model=AppleLocalMappingModel.shared
            let existingResult=ProcessInfo.processInfo.arguments.first(where:{$0.hasPrefix("--mapping-view-existing-result=")}).map{String($0.dropFirst("--mapping-view-existing-result=".count))}
            if existingResult==nil{model.reconstruct(id:info.0,incident:info.1,title:"MapTest")}
            while model.busy {
                if let progress=model.progress{record(["stage":progress.stage,"mission":info.0,"photos":info.2,"fraction":progress.fraction])}
                try await Task.sleep(for:.seconds(2))
            }
            let ready:URL
            if let existingResult {
                guard UUID(uuidString:existingResult) != nil else{throw MappingMission.Failure("Invalid saved result ID")}
                ready=try MappingMissionStore(root:MappingMissionStore.applicationRoot()).directory(info.0).appendingPathComponent("results/"+existingResult)
            }else{
                guard let result=model.ready,model.outcome?.error==nil else{throw MappingMission.Failure(model.error ?? "Reconstruction did not produce a result")};ready=result
            }
            let collection=try JSONSerialization.jsonObject(with:Data(contentsOf:ready.appendingPathComponent("surface/collection.json"))) as? [String:Any]
            guard let regions=collection?["regions"] as? [[String:Any]],!regions.isEmpty else{throw MappingMission.Failure("Expected reconstructed regions")}
            paths=[""]+regions.compactMap{$0["path"] as? String};result=ready
            record(["stage":"checking viewer","result":ready.path,"photos":info.2]);status="Checking combined and individual 3D regions"
        }catch{status=error.localizedDescription;done=true;record(["passed":false,"error":status]);UIApplication.shared.isIdleTimerDisabled=false}
    }
    func viewed(_ message:String) {
        guard !done else{return};messages.append(message)
        let good=message.contains("triangles") && (selected != 0 || message.contains("\(paths.count-1) regions together") || paths.count==2)
        if good && selected+1<paths.count {selected+=1}
        else{done=true;status=good ? "Device reconstruction and all 3D views passed":"3D viewer check failed";record(["passed":good && messages.count==paths.count,"stage":status,"result":result?.path ?? "","views":messages]);UIApplication.shared.isIdleTimerDisabled=false}
    }
}
private struct AppleMappingDeviceAcceptanceView:View {
    @AppStorage(DisplayUnits.storageKey) private var displayUnitsPreference = DisplayUnits.imperial.rawValue
    @ObservedObject private var check=AppleMappingDeviceAcceptance.shared
    @ObservedObject private var model=AppleLocalMappingModel.shared
    var body:some View{VStack{
        Text("Local MapTest device check").font(.headline)
        if let progress=model.progress{AppleMappingProgressView(snapshot:progress,title:"MapTest",cancel:model.cancel)}
        else if let result=check.result,!check.done {AppleSurfaceWebView(result:result,relative:check.paths[check.selected],qualification:check.viewed).id(check.selected)}
        else{Text(check.status)}
    }.task{Task{await check.run()}}}
}
#endif

private struct MappingOriginalShareSelection: Identifiable {
    let id = UUID()
    let urls: [URL]
}
private struct MappingOriginalShareSheet: UIViewControllerRepresentable {
    let urls: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: urls, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct AppleMappingOriginalPhotosView: View {
    let missionID: String
    @Environment(\.dismiss) private var dismiss
    @State private var photos: [MappingOriginalPhoto] = []
    @State private var selection = Set<String>()
    @State private var loading = true
    @State private var preparing = false
    @State private var error: String?
    @State private var preview: URL?
    @State private var share: MappingOriginalShareSelection?
    @State private var cancellation = MappingCancellation()
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("\(photos.count) verified originals · original filenames and bytes are preserved")
                    if loading { ProgressView("Loading originals…") }
                    if preparing { ProgressView("Preparing original photos…"); Button("Cancel") { cancellation.cancel() } }
                    if let error { Text(error).foregroundStyle(.red) }
                    if !loading && photos.isEmpty { Text("No verified original photos are stored on this device for this mission.") }
                    HStack {
                        Button(selection.count == photos.count ? "Clear selection" : "Select all") { selection = selection.count == photos.count ? [] : Set(photos.map(\.id)) }
                        Button("Share selected (\(selection.count))") { prepare(photos.filter { selection.contains($0.id) }, view: false) }.disabled(selection.isEmpty)
                    }.disabled(preparing)
                }
                ForEach(photos) { photo in
                    VStack(alignment: .leading, spacing: 8) {
                        Button {
                            if selection.contains(photo.id) { selection.remove(photo.id) } else { selection.insert(photo.id) }
                        } label: {
                            HStack { Image(systemName: selection.contains(photo.id) ? "checkmark.circle.fill" : "circle"); Text(photo.name) }
                        }
                        Text("\(MappingLayer.capturedLabel(photo.capturedAt)) · \(size(photo.bytes))").font(.footnote)
                        HStack { Button("View") { prepare([photo], view: true) }; Button("Share") { prepare([photo], view: false) } }
                    }.disabled(preparing).buttonStyle(.borderless)
                }
            }
            .navigationTitle("Original photos")
            .toolbar { Button("Done") { cancellation.cancel(); dismiss() } }
            .quickLookPreview($preview)
            .sheet(item: $share) { MappingOriginalShareSheet(urls: $0.urls) }
            .task(id: missionID) {
                do {
                    let id = missionID
                    let loaded = try await Task.detached(priority: .userInitiated) {
                        try MappingOriginalAccess.list(store: MappingMissionStore(root: MappingMissionStore.applicationRoot()), missionID: id)
                    }.value
                    guard !Task.isCancelled else { return }
                    photos = loaded
                } catch { self.error = error.localizedDescription }
                loading = false
            }
            .onDisappear { cancellation.cancel() }
        }
    }
    private func prepare(_ photos: [MappingOriginalPhoto], view: Bool) {
        guard !preparing, !photos.isEmpty else { return }
        preparing = true; error = nil; cancellation = MappingCancellation()
        let cancel = cancellation
        Task {
            do {
                let urls = try await Task.detached(priority: .userInitiated) { try MappingOriginalAccess.prepare(photos, cancellation: cancel) }.value
                try cancel.check()
                if view { preview = urls.first } else { share = MappingOriginalShareSelection(urls: urls) }
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
            preparing = false
        }
    }
}
