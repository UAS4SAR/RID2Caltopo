import Foundation
import Testing
@testable import R2CCore

private func fixture(_ name:String) throws -> OperationalSurfacePackage {
    let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return try OperationalSurfacePackage(data:Data(contentsOf:root.appendingPathComponent("test-fixtures/aol/\(name).aol")))
}
@Test func surfaceSignedClearanceAndExactDisk() throws {
    let p=try fixture("complete"), point=OperationalSurfacePackage.Point(latitude:39,longitude:-121)
    let a=p.disk(point)
    #expect(a.complete)
    #expect(abs(a.peak!.elevation-312.42)<0.001)
    #expect(p.disk(point,radius:66).peak!.elevation==500)
    for (height,expected) in [(0.0,-25.0),(7.62,0.0),(15.24,25.0)] {
        let result=OperationalAOLState.calculate(package:p,position:point,takeoff:point,height:height)
        #expect(abs(result.feet!-expected)<0.001)
    }
    #expect(OperationalAOLState.calculate(package:p,position:point,takeoff:point,height:nil).feet==nil)
}
@Test func surfaceHolesEdgesAndBriefing() throws {
    let point=OperationalSurfacePackage.Point(latitude:39,longitude:-121)
    #expect(!(try fixture("hole")).disk(point).complete)
    let p=try fixture("complete")
    #expect(!p.disk(.init(latitude:39.0007,longitude:-121)).complete)
    let points=[OperationalSurfacePackage.Point(latitude:39,longitude:-121.00005),.init(latitude:39,longitude:-120.99995)]
    let b=p.briefing(points:points,corridor:10,polygon:false)
    #expect(b.complete)
    #expect(abs(b.peak!.elevation-312.42)<0.001)
    #expect(abs(b.peak!.ground!-304.8)<0.001)
}
@Test func surfaceRejectsCorruptionAndAirborneFirstFix() throws {
    #expect(throws: (any Error).self) { try OperationalSurfacePackage(data:Data("bad".utf8)) }
    var c=OperationalAltitudeCoordinator()
    c.ingest(.init(source:.bluetoothLegacy,aircraftId:"a",receivedAt:Date(),latitude:39,longitude:-121,altitudeMeters:400,heightMeters:30,heightReference:.takeoff))
    #expect(c.aolTakeoffCoordinate==nil)
    c.ingest(.init(source:.bluetoothLegacy,aircraftId:"a",receivedAt:Date(),latitude:39,longitude:-121,altitudeMeters:370,heightMeters:0,heightReference:.takeoff,grounded:true))
    #expect(c.aolTakeoffCoordinate != nil)
    c.applyAOL(.init(feet:-25,reason:"Available"))
    #expect(c.display.aol.feet == -25)
    c.ingest(.init(source:.bluetoothLegacy,aircraftId:"a",receivedAt:Date().addingTimeInterval(-6),latitude:39,longitude:-121,altitudeMeters:370,heightMeters:0,heightReference:.takeoff,grounded:true))
    #expect(c.display.aol.feet==nil)
    #expect(c.display.aol.reason.contains("stale"))
}

@Test func realNevadaCountySurfaceOfflineQuery() throws {
    let p=try fixture("nevada-city-1m")
    let point=OperationalSurfacePackage.Point(latitude:39.24144906749628,longitude:-121.03819679099078)
    let a=p.disk(point)
    #expect(a.complete)
    #expect(a.peak != nil)
    #expect(a.peak!.elevation > a.peak!.ground!)
    #expect(OperationalAOLState.calculate(package:p,position:point,takeoff:point,height:50).feet != nil)
    let start=Date()
    for _ in 0..<100 { _=p.disk(point) }
    print("AOL real-data mean query ms: \(Date().timeIntervalSince(start)*10); cells: \(a.checked); peak: \(a.peak!)")
}

@Test func surfaceRejectsChecksumUnknownDatumAndWrongUnits() {
    for name in ["bad-checksum","bad-reference","bad-units"] {
        #expect(throws: (any Error).self) { try fixture(name) }
    }
}

@Test func groundTypedDJILaunchThenTakeoffHeightSupportsAOL() throws {
    var c=OperationalAltitudeCoordinator()
    c.ingest(.init(source:.bluetoothLegacy,aircraftId:"a",receivedAt:Date(),latitude:39,longitude:-121,altitudeMeters:521,heightMeters:0,heightReference:.ground,grounded:true))
    #expect(c.aolTakeoffCoordinate != nil)
    #expect(c.aolHeight==nil)
    c.ingest(.init(source:.bluetoothLegacy,aircraftId:"a",receivedAt:Date(),latitude:39,longitude:-121,altitudeMeters:522,heightMeters:0.5,heightReference:.takeoff,grounded:false))
    #expect(c.aolTakeoffCoordinate != nil)
    #expect(c.aolHeight==0.5)
    let result=OperationalAOLState.calculate(package:try fixture("complete"),position:.init(latitude:39,longitude:-121),takeoff:.init(latitude:39,longitude:-121),height:c.aolHeight)
    #expect(result.feet != nil)
    var late=OperationalAltitudeCoordinator()
    late.ingest(.init(source:.bluetoothLegacy,aircraftId:"a",receivedAt:Date(),latitude:39,longitude:-121,altitudeMeters:522,heightMeters:0.5,heightReference:.takeoff,grounded:false))
    #expect(late.aolTakeoffCoordinate==nil)
}

@Test func measurementStatusesDistinguishUnknownPendingAndStale() {
    #expect(OperationalAOLState().label=="Unk")
    #expect(OperationalAOLState(reason:"Working",status:.pending).label=="--")
    #expect(OperationalAOLState(reason:"Old telemetry",status:.stale).label=="POS?")
    #expect(OperationalAOLState(feet:-25).label=="-25")
    #expect(OperationalAircraftDisplay.statusLabel(atoFeet:nil,aglFeet:nil,aglStale:false,rangeFeet:nil,headingDegrees:nil,aol:.init(reason:"Working",status:.pending),atoStatus:.unknown,aglStatus:.pending)=="ATO:Unk AGL:-- AOL:-- RNG:Unk HDG:Unk")
    #expect(OperationalAircraftDisplay.statusLabel(atoFeet:20,aglFeet:20,aglStale:false,rangeFeet:10,headingDegrees:90,positionStale:true,aol:.init(feet:10))=="ATO:POS? AGL:POS? AOL:POS? RNG:POS? HDG:POS?")
}

@Test func terrainPendingIsNotConfusedWithMissingDataOrStaleTelemetry() {
    var c=OperationalAltitudeCoordinator()
    c.ingest(.init(source:.bluetoothLegacy,aircraftId:"a",receivedAt:Date(),latitude:39,longitude:-121,altitudeMeters:400))
    c.setTerrainPending(true)
    #expect(c.display.aglLabel=="--")
    c.setTerrainPending(false)
    #expect(c.display.aglLabel=="Unk")
    c.ingest(.init(source:.bluetoothLegacy,aircraftId:"a",receivedAt:Date().addingTimeInterval(-6),latitude:39,longitude:-121,altitudeMeters:400,heightMeters:10,heightReference:.takeoff))
    #expect(c.display.atoLabel=="POS?")
    #expect(c.display.aglLabel=="POS?")
    #expect(c.display.aolLabel=="POS?")
    #expect(c.display.rangeLabel=="POS?")
}

@Test func manualHoverCalibrationSuppliesLaunchReferenceWithoutGroundFlag() {
    var c=OperationalAltitudeCoordinator()
    c.ingest(.init(source:.bluetoothLegacy,aircraftId:"a",receivedAt:Date(),latitude:39,longitude:-121,altitudeMeters:500,heightMeters:5,heightReference:.takeoff,grounded:false))
    c.applyCurrentTerrain(.init(elevationMeters:300),coordinate:c.currentCoordinate!)
    c.manualCalibrateAtFiftyFeet()
    #expect(c.aolTakeoffCoordinate==c.currentCoordinate)
    #expect(abs((c.aolHeight ?? 0)-15.24)<0.00001)
    #expect(abs((c.display.atoFeet ?? 0)-50)<0.001)
    #expect(abs((c.display.aglFeet ?? 0)-50)<0.001)
}
@Test func streamConfirmationRequiresOneConfiguredDesignator() {
    #expect(OperationalStreamConfirmationMatch.remoteID(designator:" A ",mappings:[("one","a")])=="one")
    #expect(OperationalStreamConfirmationMatch.remoteID(designator:"a",mappings:[("one","A"),("two","a")])==nil)
    #expect(OperationalStreamConfirmationMatch.remoteID(designator:"missing",mappings:[("one","a")])==nil)
    var lifecycle=CurrentFlightConfirmationLifecycle()
    #expect(lifecycle.reconcile(orderedRemoteIDs:["one"],confirmedRemoteIDs:[],ignoredRemoteIDs:[]).candidateRemoteID=="one")
    #expect(lifecycle.reconcile(orderedRemoteIDs:["one","one"],confirmedRemoteIDs:["one"],ignoredRemoteIDs:[]).candidateRemoteID==nil)
}

@Test func unchangedAOLInputsRetainResultAndStalenessIsShared() {
    let now = Date()
    func observation(_ at: Date, latitude: Double = 39, height: Double = 0) -> RidObservation {
        RidObservation(source: .bluetoothLegacy, aircraftId: "stationary", receivedAt: at,
                       latitude: latitude, longitude: -121, altitudeMeters: 100,
                       heightMeters: height, heightReference: .takeoff, grounded: true)
    }
    var coordinator = OperationalAltitudeCoordinator()
    coordinator.ingest(observation(now))
    coordinator.applyAOL(.init(feet: -9))
    coordinator.ingest(observation(now.addingTimeInterval(1)))
    let fresh = coordinator.display(at: now.addingTimeInterval(2))
    #expect(fresh.aolLabel == "-9 ft")
    #expect(!fresh.positionStale)
    let stale = coordinator.display(at: now.addingTimeInterval(6))
    #expect(stale.aolLabel == "POS?")
    #expect(stale.atoLabel == "POS?")
    #expect(stale.aglLabel == "POS?")
    coordinator.ingest(observation(now.addingTimeInterval(7)))
    #expect(coordinator.display(at: now.addingTimeInterval(7)).aolLabel == "-9 ft")
    coordinator.ingest(observation(now.addingTimeInterval(8), latitude: 39.00001))
    #expect(coordinator.display(at: now.addingTimeInterval(9.5)).aol.status == .pending)
}

@Test func aolRefreshGraceIsBoundedAndDoesNotMaskStaleTelemetryOrFailure() {
    let now = Date()
    var coordinator = OperationalAltitudeCoordinator()
    func sample(_ seconds: Double, height: Double, grounded: Bool = false) -> RidObservation {
        RidObservation(source: .bluetoothLegacy, aircraftId: "hover", receivedAt: now.addingTimeInterval(seconds),
                       latitude: 39, longitude: -121, altitudeMeters: 100 + height,
                       heightMeters: height, heightReference: .takeoff, grounded: grounded)
    }
    coordinator.ingest(sample(0, height: 0, grounded: true))
    coordinator.ingest(sample(0.1, height: 20))
    coordinator.applyAOL(.init(feet: -9))
    coordinator.ingest(sample(1, height: 20.1))
    #expect(coordinator.display(at: now.addingTimeInterval(1)).aolLabel == "-9 ft")
    coordinator.ingest(sample(2, height: 20.2))
    #expect(coordinator.display(at: now.addingTimeInterval(2.49)).aolLabel == "-9 ft")
    #expect(coordinator.display(at: now.addingTimeInterval(2.5)).aol.status == .pending)
    coordinator.applyAOL(.init(feet: -8))
    #expect(coordinator.display(at: now.addingTimeInterval(2.6)).aolLabel == "-8 ft")
    coordinator.ingest(sample(3, height: 20.3))
    coordinator.applyAOL(.init(reason: "Missing tile"))
    #expect(coordinator.display(at: now.addingTimeInterval(3)).aolLabel == "Unk")
    coordinator.applyAOL(.init(feet: -7))
    let stale = coordinator.display(at: now.addingTimeInterval(8))
    #expect(stale.aolLabel == "POS?" && stale.atoLabel == "POS?" && stale.aglLabel == "POS?")
}

@Test func videoWithoutLaunchReferenceStaysUnknownAcrossMovingSamplesUntilCalibration() {
    var coordinator = OperationalAltitudeCoordinator()
    let now = Date().addingTimeInterval(-2)
    for index in 0..<20 {
        let date = now.addingTimeInterval(Double(index) / 10)
        coordinator.ingest(.init(source: .djiVideo, aircraftId: "video", receivedAt: date,
            latitude: 39 + Double(index) / 100000, longitude: -121,
            altitudeMeters: 500 + Double(index), heightMeters: 10 + Double(index), heightReference: .takeoff))
        let display = coordinator.display(at: date)
        #expect(display.aolLabel == "Unk")
        #expect(display.atoFeet != nil)
    }
    coordinator.manualCalibrateAtFiftyFeet()
    #expect(coordinator.aolTakeoffCoordinate != nil)
    #expect(coordinator.display(at: now.addingTimeInterval(2)).aol.status == .pending)
    coordinator.applyAOL(.init(feet: 8))
    #expect(coordinator.display(at: now.addingTimeInterval(2)).aolLabel == "8 ft")
    #expect(coordinator.display(at: now.addingTimeInterval(8)).aolLabel == "POS?")
}

@Test func videoReferenceInitializesAOLWhenJoiningAirborneWithoutRID() async throws {
    var coordinator = OperationalAltitudeCoordinator()
    let now = Date()
    func sample(referenceLatitude: Double? = 39.15, receivedAt: Date = now) -> RidObservation {
        .init(source: .djiVideo, aircraftId: "matrice", receivedAt: receivedAt,
              latitude: 39.16, longitude: -121.12, altitudeMeters: 520,
              heightMeters: 20, heightReference: .takeoff,
              videoReferenceLatitude: referenceLatitude, videoReferenceLongitude: -121.13)
    }
    let store = RidTrackStore()
    _ = await store.ingest(sample())
    let accepted = try #require(await store.snapshot().first)
    coordinator.ingest(accepted.lastObservation)
    #expect(coordinator.aolTakeoffCoordinate == .init(latitude: 39.15, longitude: -121.13))
    #expect(coordinator.aolTakeoffCoordinate != coordinator.currentCoordinate)
    #expect(coordinator.aolHeight == 20)
    #expect(coordinator.display(at: now).aol.status == .pending)
    coordinator.applyAOL(.init(feet: 12))
    #expect(coordinator.display(at: now).aolLabel == "12 ft")
    coordinator.manualCalibrateAtFiftyFeet()
    let manualReference = coordinator.aolTakeoffCoordinate
    coordinator.ingest(sample(referenceLatitude: 39.14))
    #expect(coordinator.aolTakeoffCoordinate == manualReference)
    #expect(abs((coordinator.aolHeight ?? 0) - 15.24) < 0.001)
    for observation in [sample(referenceLatitude: nil), sample(referenceLatitude: .nan),
                        sample(receivedAt: now.addingTimeInterval(-6))] {
        var freshFlight = OperationalAltitudeCoordinator()
        freshFlight.ingest(observation)
        #expect(freshFlight.aolTakeoffCoordinate == nil)
    }
}

@Test func roundedMeasurementZeroHasNoNegativeSign() {
    for value in [-0.49, -0.0, 0.0, 0.49] {
        #expect(OperationalMeasurementStatus.available.label(value, suffix: "'") == "0'")
    }
    #expect(OperationalMeasurementStatus.available.label(-0.6, suffix: "'") == "-1'")
    #expect(OperationalMeasurementStatus.stale.label(-0.1) == "POS?")
    #expect(OperationalMeasurementStatus.available.label(nil) == "Unk")
}

@Test func videoReferenceRefinementsRetainOnlyBoundedAOLGrace() {
    var c = OperationalAltitudeCoordinator()
    let start = Date().addingTimeInterval(-3)
    func sample(_ seconds: Double, _ latitude: Double) -> RidObservation {
        .init(source: .djiVideo, aircraftId: "video", receivedAt: start.addingTimeInterval(seconds),
              latitude: 39, longitude: -121, altitudeMeters: 100, heightMeters: 0, heightReference: .takeoff,
              videoReferenceLatitude: latitude, videoReferenceLongitude: -121)
    }
    c.ingest(sample(0, 39))
    c.applyAOL(.init(feet: -63))
    c.ingest(sample(0.1, 39.000001))
    #expect(c.display(at: start.addingTimeInterval(0.1)).aolLabel == "-63 ft")
    c.ingest(sample(1, 39.000002))
    #expect(c.display(at: start.addingTimeInterval(1.599)).aolLabel == "-63 ft")
    #expect(c.display(at: start.addingTimeInterval(1.6)).aol.status == .pending)
    #expect(c.aolTakeoffCoordinate?.latitude == 39.000002)
    c.applyAOL(.init(feet: -62))
    c.manualCalibrateAtFiftyFeet()
    #expect(c.display(at: start.addingTimeInterval(1.7)).aol.status == .pending)
}

@Test func movingVideoAcceptsRecentCompletedAOLWithoutExtendingAge() {
    var c = OperationalAltitudeCoordinator()
    let start = Date().addingTimeInterval(-2)
    func sample(_ seconds: Double) -> RidObservation {
        .init(source: .djiVideo, aircraftId: "video", receivedAt: start.addingTimeInterval(seconds),
              latitude: 39 + seconds * 0.00001, longitude: -121, altitudeMeters: 100,
              heightMeters: 20, heightReference: .takeoff,
              videoReferenceLatitude: 39, videoReferenceLongitude: -121)
    }
    c.ingest(sample(0))
    let input = c.aolInput
    c.ingest(sample(0.2))
    let accepted = c.applyCompletedAOL(.init(feet: 42), input: input, startedAt: start, now: start.addingTimeInterval(0.3))
    #expect(accepted)
    #expect(c.display(at: start.addingTimeInterval(0.4)).aol.feet == 42)
    c.ingest(sample(1))
    #expect(c.display(at: start.addingTimeInterval(1.499)).aol.feet == 42)
    #expect(c.display(at: start.addingTimeInterval(1.5)).aol.status == .pending)
    let expired = c.applyCompletedAOL(.init(feet: 43), input: input, startedAt: start, now: start.addingTimeInterval(1.8))
    #expect(!expired)
    c.manualCalibrateAtFiftyFeet()
    let recalibrated = c.applyCompletedAOL(.init(feet: 43), input: input, startedAt: start, now: start.addingTimeInterval(1.1))
    #expect(!recalibrated)
}

@Test func pendingTerrainDisplaysProvisionalAGLWithoutHidingMissingOrStaleData() {
    #expect(OperationalMeasurementStatus.pending.label(112, suffix: "'", showPendingValue: true) == "112'?")
    #expect(OperationalMeasurementStatus.pending.label(nil, showPendingValue: true) == "--")
    #expect(OperationalMeasurementStatus.pending.label(112) == "--")
    #expect(OperationalMeasurementStatus.stale.label(112, showPendingValue: true) == "POS?")
    #expect(OperationalMeasurementStatus.unknown.label(112, showPendingValue: true) == "Unk")
}
