import CoreVideo
import AVFoundation
import Foundation
import R2CCore
@preconcurrency import WebRTC

struct AppleManagedVideoMediaMetrics: Sendable {
    let connected: Bool
    let bytesSent: Int64
    let framesSent: Int64
    let width: Int
    let height: Int
    let framesPerSecond: Double
    let bitrateBps: Int64
    let routeKind: String
    let audioBytesSent: Int64
    let audioBytesReceived: Int64
}

private struct AppleManagedVideoPixelBuffer: @unchecked Sendable {
    let value: CVPixelBuffer
}

final class AppleManagedVideoMediaPeer: NSObject, @unchecked Sendable {
    typealias AnswerSink = @Sendable (String, String) -> Void
    typealias MetricsSink = @Sendable (String, AppleManagedVideoMediaMetrics) -> Void
    typealias FailureSink = @Sendable (String, String) -> Void
    typealias MicrophoneStateSink = @Sendable (String, Bool, String?) -> Void
    typealias ReadySink = @Sendable (String) -> Void

    private static let sslInitialized = RTCInitializeSSL()
    private let queue = DispatchQueue(label: "org.ncssar.r2c.video-media")
    private let factory: RTCPeerConnectionFactory
    private let answerSink: AnswerSink
    private let metricsSink: MetricsSink
    private let failureSink: FailureSink
    private let microphoneStateSink: MicrophoneStateSink
    private let readySink: ReadySink
    private var playbackStartGate = ManagedVideoPlaybackStartGate()

    private var requestID = ""
    private var peer: RTCPeerConnection?
    private var sender: RTCRtpSender?
    private var audioSender: RTCRtpSender?
    private var audioSource: RTCAudioSource?
    private var audioTrack: RTCAudioTrack?
    private var videoSource: RTCVideoSource?
    private var videoTrack: RTCVideoTrack?
    private var capturer: RTCVideoCapturer?
    private var statsTimer: DispatchSourceTimer?
    private var answerSent = false
    private var selectedWidth = 0
    private var selectedHeight = 0
    private var selectedFPS = 0.0
    private var selectedBitrateBps: Int64 = 0
    private var lastStatsAt: CFTimeInterval?
    private var lastBytesSent: Int64 = 0
    private var lastFramesSent: Int64 = 0
    private var lastDiagnosticAt: CFTimeInterval = 0
    private var localICECandidates: [RTCIceCandidate] = []
    private var decodedFramesReceived: Int64 = 0
    private var framesSubmittedToWebRTC: Int64 = 0
    private var framesDroppedBeforeReady: Int64 = 0

    init(
        answerSink: @escaping AnswerSink,
        metricsSink: @escaping MetricsSink,
        failureSink: @escaping FailureSink,
        microphoneStateSink: @escaping MicrophoneStateSink,
        readySink: @escaping ReadySink
    ) {
        _ = Self.sslInitialized
        factory = RTCPeerConnectionFactory()
        self.answerSink = answerSink
        self.metricsSink = metricsSink
        self.failureSink = failureSink
        self.microphoneStateSink = microphoneStateSink
        self.readySink = readySink
    }

    func start(
        requestID: String,
        offerSDP: String,
        iceServers: [AppleVideoICEServer],
        width: Int,
        height: Int,
        fps: Double,
        bitrateBps: Int64
    ) {
        queue.async { [weak self] in
            self?.startOnQueue(
                requestID: requestID,
                offerSDP: offerSDP,
                iceServers: iceServers,
                width: width,
                height: height,
                fps: fps,
                bitrateBps: bitrateBps
            )
        }
    }

    func close() {
        queue.async { [weak self] in self?.closeOnQueue() }
    }

    func setMicrophoneEnabled(_ enabled: Bool) {
        if !enabled {
            queue.async { [weak self] in self?.setMicrophoneEnabledOnQueue(false) }
            return
        }
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            queue.async { [weak self] in self?.setMicrophoneEnabledOnQueue(true) }
        case .denied:
            microphoneStateSink(requestID, false, "Microphone permission denied")
        case .undetermined:
            AVAudioApplication.requestRecordPermission { [weak self] granted in
                guard let self else { return }
                self.queue.async {
                    if granted {
                        self.setMicrophoneEnabledOnQueue(true)
                    } else {
                        self.microphoneStateSink(
                            self.requestID,
                            false,
                            "Microphone permission denied"
                        )
                    }
                }
            }
        @unknown default:
            microphoneStateSink(requestID, false, "Microphone permission unavailable")
        }
    }

    private func setMicrophoneEnabledOnQueue(_ enabled: Bool) {
        guard audioSender != nil, let audioTrack else {
            microphoneStateSink(requestID, false, "VoIP audio path unavailable")
            return
        }
        if enabled {
            audioTrack.isEnabled = true
        } else {
            audioTrack.isEnabled = false
        }
        AppleLog.info("VideoMedia", "request=\(requestID) microphone=\(enabled ? "enabled" : "disabled")")
        microphoneStateSink(requestID, enabled, nil)
    }

    private func startOnQueue(
        requestID: String,
        offerSDP: String,
        iceServers: [AppleVideoICEServer],
        width: Int,
        height: Int,
        fps: Double,
        bitrateBps: Int64
    ) {
        closeOnQueue()
        playbackStartGate = ManagedVideoPlaybackStartGate()
        configureWebRTCAudioRoutingOnQueue(microphoneEnabled: false)
        self.requestID = requestID
        selectedWidth = max(2, width)
        selectedHeight = max(2, height)
        selectedFPS = max(1, fps)
        selectedBitrateBps = max(150_000, bitrateBps)
        answerSent = false
        localICECandidates.removeAll(keepingCapacity: true)

        let configuration = RTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        // The browser is relay-only and signaling carries one complete answer
        // rather than trickled candidates. Require the tablet's answer to use
        // TURN as well so a prematurely sent server-reflexive candidate cannot
        // strand the peer while later relay candidates remain undisclosed.
        configuration.iceTransportPolicy = .relay
        configuration.continualGatheringPolicy = .gatherContinually
        configuration.iceServers = iceServers.map {
            RTCIceServer(
                urlStrings: $0.urls,
                username: $0.username,
                credential: $0.credential
            )
        }
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let peer = factory.peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: self
        ) else {
            failOnQueue("Unable to create the authorized video peer.")
            return
        }
        self.peer = peer

        let videoSource = factory.videoSource()
        videoSource.adaptOutputFormat(
            toWidth: Int32(selectedWidth),
            height: Int32(selectedHeight),
            fps: Int32(selectedFPS.rounded())
        )
        self.videoSource = videoSource
        capturer = RTCVideoCapturer(delegate: videoSource)
        let track = factory.videoTrack(with: videoSource, trackId: "r2c-video-\(requestID)")
        track.isEnabled = true
        videoTrack = track
        guard let sender = peer.add(track, streamIds: ["r2c-\(requestID)"]) else {
            failOnQueue("Unable to attach the authorized video track.")
            return
        }
        self.sender = sender
        applySenderLimitsOnQueue(stage: "before-negotiation")

        let offer = RTCSessionDescription(type: .offer, sdp: offerSDP)
        peer.setRemoteDescription(offer) { [weak self] error in
            guard let self else { return }
            self.queue.async {
                if let error {
                    self.failOnQueue("Unable to apply the browser media offer: \(Self.describe(error))")
                    return
                }
                if let transceiver = peer.transceivers.first(where: {
                    $0.mediaType == .audio
                }) {
                    var directionError: NSError?
                    transceiver.setDirection(.sendRecv, error: &directionError)
                    if let directionError {
                        self.failOnQueue(
                            "Unable to configure VoIP audio: \(Self.describe(directionError))"
                        )
                        return
                    }
                    self.audioSender = transceiver.sender
                    let audioConstraints = RTCMediaConstraints(
                        mandatoryConstraints: nil,
                        optionalConstraints: [
                            "googEchoCancellation": "true",
                            "googAutoGainControl": "true",
                            "googNoiseSuppression": "true",
                        ]
                    )
                    let audioSource = self.factory.audioSource(with: audioConstraints)
                    let audioTrack = self.factory.audioTrack(
                        with: audioSource,
                        trackId: "r2c-audio-\(requestID)"
                    )
                    // Negotiate a real bidirectional audio sender in the first
                    // answer, but do not capture operator audio until they
                    // explicitly enable the microphone control.
                    audioTrack.isEnabled = false
                    self.audioSource = audioSource
                    self.audioTrack = audioTrack
                    transceiver.sender.track = audioTrack
                    if let remoteAudioTrack = transceiver.receiver.track as? RTCAudioTrack {
                        remoteAudioTrack.isEnabled = true
                    }
                }
                peer.answer(for: constraints) { answer, error in
                    self.queue.async {
                        guard error == nil, let answer else {
                            self.failOnQueue("Unable to create the media answer\(Self.errorSuffix(error))")
                            return
                        }
                        peer.setLocalDescription(answer) { error in
                            self.queue.async {
                                if let error {
                                    self.failOnQueue("Unable to apply the media answer: \(Self.describe(error))")
                                } else {
                                    self.applySenderLimitsOnQueue(stage: "after-negotiation")
                                    self.maybeSendAnswerOnQueue()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private func configureWebRTCAudioRoutingOnQueue(
        microphoneEnabled: Bool,
        active: Bool = true
    ) {
        let configuration = RTCAudioSessionConfiguration.webRTC()
        // WebRTC's iOS VoiceProcessingIO device requires a full-duplex voice
        // session even when this peer is only rendering incoming audio. The
        // separately negotiated local track remains disabled until explicit
        // operator consent, so microphone-off still sends zero audio RTP.
        configuration.category = AVAudioSession.Category.playAndRecord.rawValue
        configuration.mode = AVAudioSession.Mode.voiceChat.rawValue
        configuration.categoryOptions = [.allowBluetoothHFP, .defaultToSpeaker]
        RTCAudioSessionConfiguration.setWebRTC(configuration)
        let audioSession = RTCAudioSession.sharedInstance()
        // This app also owns an AVPlayer/native decoder pipeline. Explicitly
        // gate WebRTC's VoIP audio unit so it is not left waiting behind that
        // pipeline and so it is torn down again when remote viewing ends.
        audioSession.useManualAudio = true
        // WebRTC does not reliably rebuild its voice-processing audio unit when
        // an already-running session changes between playback and full duplex.
        // Stop it before applying the new category, then restart it below.
        audioSession.isAudioEnabled = false
        audioSession.lockForConfiguration()
        defer { audioSession.unlockForConfiguration() }
        do {
            // Applying a category alone does not start WebRTC's audio device.
            // Keep one full-duplex session active for receive audio; the local
            // track above remains the operator-controlled microphone gate.
            try audioSession.setConfiguration(configuration, active: active)
            audioSession.isAudioEnabled = active
            let route = audioSession.currentRoute.outputs
                .map { "\($0.portType.rawValue):\($0.portName)" }
                .joined(separator: ",")
            AppleLog.info(
                "VideoMedia",
                "request=\(requestID) audio unit enabled=\(audioSession.isAudioEnabled) "
                    + "active=\(audioSession.isActive) microphone=\(microphoneEnabled) "
                    + "inputAvailable=\(audioSession.inputAvailable) route=\(route)"
            )
        } catch {
            AppleLog.error(
                "VideoMedia",
                "request=\(requestID) unable to apply microphone=\(microphoneEnabled) "
                    + "active=\(active) audio routing: \(Self.describe(error))"
            )
        }
    }

    func consumeDecodedVideoFrame(
        _ pixelBuffer: CVPixelBuffer,
        timestampNanoseconds: Int64
    ) {
        let transfer = AppleManagedVideoPixelBuffer(value: pixelBuffer)
        queue.async { [weak self, transfer] in
            guard let self else { return }
            self.decodedFramesReceived += 1
            guard let capturer, let videoSource, self.videoTrack?.isEnabled == true else {
                self.framesDroppedBeforeReady += 1
                if self.framesDroppedBeforeReady == 1 {
                    AppleLog.warning(
                        "VideoMedia",
                        "request=\(self.requestID) decoded frame arrived before sender was ready"
                    )
                }
                return
            }
            // Canonical I420 avoids relying on the hardware encoder accepting a
            // decoder-owned NV12/420v surface. That path connected successfully
            // on iPadOS but produced no encoded frames or RTP packets.
            let pixelFormat = CVPixelBufferGetPixelFormatType(transfer.value)
            let buffer = RTCCVPixelBuffer(pixelBuffer: transfer.value).toI420()
            let frame = RTCVideoFrame(
                buffer: buffer,
                rotation: ._0,
                timeStampNs: timestampNanoseconds
            )
            videoSource.capturer(capturer, didCapture: frame)
            self.framesSubmittedToWebRTC += 1
            if self.framesSubmittedToWebRTC == 1 {
                AppleLog.info(
                    "VideoMedia",
                    "request=\(self.requestID) first frame submitted to WebRTC "
                        + "source=\(CVPixelBufferGetWidth(transfer.value))x"
                        + "\(CVPixelBufferGetHeight(transfer.value)) "
                        + "pixelFormat=\(Self.fourCC(pixelFormat))"
                )
            }
        }
    }

    private func applySenderLimitsOnQueue(stage: String) {
        guard let sender, let peer else { return }
        let parameters = sender.parameters
        let bitratePolicy = ManagedVideoQualityPolicy.senderBitrates(
            targetBps: selectedBitrateBps
        )
        // WebRTC does not allow changing the number of RTP encodings. Before
        // negotiation the array can legitimately be empty; manufacturing an
        // encoding here can leave the iOS sender with no usable negotiated
        // layer. Reapply the limits after setLocalDescription, when WebRTC has
        // populated the negotiated encodings.
        for encoding in parameters.encodings {
            encoding.isActive = true
            encoding.minBitrateBps = NSNumber(value: bitratePolicy.minimumBps)
            encoding.maxBitrateBps = NSNumber(value: bitratePolicy.maximumBps)
            encoding.maxFramerate = NSNumber(value: selectedFPS)
            encoding.scaleResolutionDownBy = NSNumber(value: 1.0)
        }
        parameters.degradationPreference = NSNumber(
            value: RTCDegradationPreference.maintainResolution.rawValue
        )
        if !parameters.encodings.isEmpty {
            sender.parameters = parameters
        }
        let bweApplied = peer.setBweMinBitrateBps(
            NSNumber(value: bitratePolicy.minimumBps),
            currentBitrateBps: NSNumber(value: bitratePolicy.startupBps),
            maxBitrateBps: NSNumber(value: bitratePolicy.maximumBps)
        )
        AppleLog.info(
            "VideoMedia",
            "request=\(requestID) sender stage=\(stage) "
                + "target=\(selectedWidth)x\(selectedHeight)@\(selectedFPS) "
                + "bitrate min/current/max=\(bitratePolicy.minimumBps)/"
                + "\(bitratePolicy.startupBps)/\(bitratePolicy.maximumBps) "
                + "encodings=\(parameters.encodings.count) bweApplied=\(bweApplied)"
        )
    }

    private func maybeSendAnswerOnQueue(allowPartialGathering: Bool = false) {
        guard !answerSent,
              let peer,
              allowPartialGathering || peer.iceGatheringState == .complete,
              let answer = peer.localDescription,
              !answer.sdp.isEmpty
        else { return }
        let completeAnswer = ManagedVideoSDP.withICECandidates(
            answer.sdp,
            candidates: localICECandidates.map {
                ManagedVideoICECandidate(
                    sdp: $0.sdp,
                    mediaLineIndex: $0.sdpMLineIndex
                )
            }
        )
        guard ManagedVideoSDP.hasRelayICECandidate(completeAnswer) else {
            AppleLog.debug(
                "VideoMedia",
                "request=\(requestID) answer awaiting relay ICE candidate "
                    + "gathering=\(String(describing: peer.iceGatheringState)) "
                    + "gathered=\(localICECandidates.count)"
            )
            return
        }
        answerSent = true
        AppleLog.info(
            "VideoMedia",
            "request=\(requestID) sending answer "
                + "gathering=\(String(describing: peer.iceGatheringState)) "
                + "gathered=\(localICECandidates.count)"
        )
        answerSink(requestID, completeAnswer)
    }

    private func startStatsOnQueue() {
        guard statsTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: 2)
        timer.setEventHandler { [weak self] in self?.collectStatsOnQueue() }
        statsTimer = timer
        timer.resume()
    }

    private func collectStatsOnQueue() {
        guard let peer else { return }
        peer.statistics { [weak self] report in
            guard let self else { return }
            self.queue.async {
                var bytesSent: Int64 = 0
                var framesSent: Int64 = 0
                var width = self.selectedWidth
                var height = self.selectedHeight
                var reportedFPS = 0.0
                var audioBytesSent: Int64 = 0
                var audioBytesReceived: Int64 = 0
                var candidateTypes: [String: String] = [:]
                var selectedCandidateIDs: [String] = []
                var roundTripTimeSeconds: Double?
                var packetsLost: Int64 = 0
                var retransmittedBytesSent: Int64 = 0
                var nackCount: Int64 = 0
                var pliCount: Int64 = 0
                var qualityLimitationReason = "unknown"
                for statistic in report.statistics.values {
                    if statistic.type == "local-candidate" || statistic.type == "remote-candidate" {
                        candidateTypes[statistic.id] = statistic.values["candidateType"] as? String ?? ""
                    }
                    if statistic.type == "candidate-pair" {
                        let selected = (statistic.values["selected"] as? NSNumber)?.boolValue == true
                        let nominated = (statistic.values["nominated"] as? NSNumber)?.boolValue == true
                        if selected || nominated {
                            selectedCandidateIDs.append(statistic.values["localCandidateId"] as? String ?? "")
                            selectedCandidateIDs.append(statistic.values["remoteCandidateId"] as? String ?? "")
                            roundTripTimeSeconds = (
                                statistic.values["currentRoundTripTime"] as? NSNumber
                            )?.doubleValue ?? roundTripTimeSeconds
                        }
                    }
                    let kind = statistic.values["kind"] as? String
                        ?? statistic.values["mediaType"] as? String ?? ""
                    if statistic.type == "outbound-rtp", kind == "audio" {
                        audioBytesSent += (statistic.values["bytesSent"] as? NSNumber)?.int64Value ?? 0
                    } else if statistic.type == "inbound-rtp", kind == "audio" {
                        audioBytesReceived += (statistic.values["bytesReceived"] as? NSNumber)?.int64Value ?? 0
                    } else if statistic.type == "outbound-rtp", kind == "video" {
                        bytesSent += (statistic.values["bytesSent"] as? NSNumber)?.int64Value ?? 0
                        framesSent += (statistic.values["framesEncoded"] as? NSNumber)?.int64Value
                            ?? (statistic.values["framesSent"] as? NSNumber)?.int64Value ?? 0
                        width = (statistic.values["frameWidth"] as? NSNumber)?.intValue ?? width
                        height = (statistic.values["frameHeight"] as? NSNumber)?.intValue ?? height
                        reportedFPS = (statistic.values["framesPerSecond"] as? NSNumber)?.doubleValue ?? 0
                        retransmittedBytesSent += (
                            statistic.values["retransmittedBytesSent"] as? NSNumber
                        )?.int64Value ?? 0
                        nackCount += (statistic.values["nackCount"] as? NSNumber)?.int64Value ?? 0
                        pliCount += (statistic.values["pliCount"] as? NSNumber)?.int64Value ?? 0
                        qualityLimitationReason = statistic.values["qualityLimitationReason"] as? String
                            ?? qualityLimitationReason
                    } else if statistic.type == "remote-inbound-rtp", kind == "video" {
                        packetsLost += (statistic.values["packetsLost"] as? NSNumber)?.int64Value ?? 0
                        roundTripTimeSeconds = (
                            statistic.values["roundTripTime"] as? NSNumber
                        )?.doubleValue ?? roundTripTimeSeconds
                    }
                }
                let routeKind = selectedCandidateIDs.contains(where: {
                    candidateTypes[$0] == "relay"
                }) ? "routed" : "direct"
                let now = CACurrentMediaTime()
                let elapsed = self.lastStatsAt.map { now - $0 } ?? 0
                let deltaFrames = max(0, framesSent - self.lastFramesSent)
                let deltaBytes = max(0, bytesSent - self.lastBytesSent)
                let effectiveFPS = reportedFPS > 0
                    ? reportedFPS
                    : elapsed > 0 ? Double(deltaFrames) / elapsed : 0
                let effectiveBitrate = elapsed > 0
                    ? Int64(Double(deltaBytes * 8) / elapsed)
                    : 0
                if now - self.lastDiagnosticAt >= 10, elapsed > 0 {
                    self.lastDiagnosticAt = now
                    let message = "request=\(self.requestID) media stats route=\(routeKind) "
                        + "actual=\(width)x\(height)@\(String(format: "%.1f", effectiveFPS)) "
                        + "bitrate=\(effectiveBitrate) target=\(self.selectedBitrateBps) "
                        + "bytes=\(bytesSent) frames=\(framesSent) "
                        + "decoded=\(self.decodedFramesReceived) "
                        + "submitted=\(self.framesSubmittedToWebRTC) "
                        + "notReady=\(self.framesDroppedBeforeReady) "
                        + "audioSent=\(audioBytesSent) audioReceived=\(audioBytesReceived) "
                        + "qualityLimit=\(qualityLimitationReason) "
                        + "rttMs=\(roundTripTimeSeconds.map { Int(($0 * 1_000).rounded()) } ?? -1) "
                        + "packetsLost=\(packetsLost) retransmitBytes=\(retransmittedBytesSent) "
                        + "nack=\(nackCount) pli=\(pliCount)"
                    if effectiveBitrate > 0 && effectiveBitrate * 2 < self.selectedBitrateBps {
                        AppleLog.warning("VideoMedia", message + " underTarget=true")
                    } else {
                        AppleLog.info("VideoMedia", message)
                    }
                }
                self.lastStatsAt = now
                self.lastFramesSent = framesSent
                self.lastBytesSent = bytesSent
                self.metricsSink(self.requestID, AppleManagedVideoMediaMetrics(
                    connected: self.peer?.connectionState == .connected,
                    bytesSent: bytesSent,
                    framesSent: framesSent,
                    width: width,
                    height: height,
                    framesPerSecond: effectiveFPS,
                    bitrateBps: effectiveBitrate,
                    routeKind: routeKind,
                    audioBytesSent: audioBytesSent,
                    audioBytesReceived: audioBytesReceived
                ))
            }
        }
    }

    private func failOnQueue(_ reason: String) {
        let failedRequestID = requestID
        AppleLog.error("VideoMedia", "request=\(failedRequestID) \(reason)")
        closeOnQueue()
        failureSink(failedRequestID, reason)
    }

    private func closeOnQueue() {
        playbackStartGate.cancel()
        statsTimer?.cancel()
        statsTimer = nil
        let closingPeer = peer
        peer = nil
        closingPeer?.delegate = nil
        closingPeer?.close()
        sender = nil
        videoTrack?.isEnabled = false
        videoTrack = nil
        audioTrack?.isEnabled = false
        audioSender?.track = nil
        audioTrack = nil
        audioSource = nil
        audioSender = nil
        configureWebRTCAudioRoutingOnQueue(microphoneEnabled: false, active: false)
        capturer = nil
        videoSource = nil
        lastStatsAt = nil
        lastBytesSent = 0
        lastFramesSent = 0
        lastDiagnosticAt = 0
        answerSent = false
        localICECandidates.removeAll(keepingCapacity: false)
        decodedFramesReceived = 0
        framesSubmittedToWebRTC = 0
        framesDroppedBeforeReady = 0
    }

    private static func fourCC(_ value: OSType) -> String {
        let characters = [24, 16, 8, 0].map { shift -> Character in
            let byte = UInt8((value >> OSType(shift)) & 0xff)
            return byte >= 32 && byte <= 126 ? Character(UnicodeScalar(byte)) : "?"
        }
        return String(characters)
    }

    private static func errorSuffix(_ error: Error?) -> String {
        error.map { ": \(describe($0))" } ?? "."
    }

    private static func describe(_ error: Error) -> String {
        let value = error as NSError
        return "\(value.localizedDescription) [\(value.domain) \(value.code)]"
    }
}

extension AppleManagedVideoMediaPeer: AppleDecodedVideoFrameConsumer {}

extension AppleManagedVideoMediaPeer: RTCPeerConnectionDelegate {
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        queue.async { [weak self] in
            guard let self, self.peer === peerConnection else { return }
            // ICE alone does not establish the DTLS media transport. Start a
            // finite recording only after the whole connection is ready.
            if self.playbackStartGate.shouldStart(connected: newState == .connected) {
                AppleLog.info("VideoMedia", "request=\(self.requestID) media transport ready")
                self.readySink(self.requestID)
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        queue.async { [weak self] in
            guard let self else { return }
            guard self.peer === peerConnection else { return }
            AppleLog.info(
                "VideoMedia",
                "request=\(self.requestID) ICE state=\(String(describing: newState))"
            )
            if newState == .connected || newState == .completed {
                self.startStatsOnQueue()
            } else if newState == .failed || newState == .closed {
                self.failOnQueue("Media ICE connection \(String(describing: newState)).")
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        AppleLog.debug(
            "VideoMedia",
            "request=\(requestID) ICE gathering=\(String(describing: newState))"
        )
        guard newState == .complete else { return }
        queue.async { [weak self] in self?.maybeSendAnswerOnQueue() }
    }

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didGenerate candidate: RTCIceCandidate
    ) {
        queue.async { [weak self] in
            guard let self, self.peer != nil else { return }
            self.localICECandidates.append(candidate)
            let type = candidate.sdp.contains(" typ relay ")
                ? "relay"
                : candidate.sdp.contains(" typ srflx ") ? "srflx" : "host"
            AppleLog.debug(
                "VideoMedia",
                "request=\(self.requestID) gathered \(type) candidate "
                    + "mline=\(candidate.sdpMLineIndex) "
                    + "count=\(self.localICECandidates.count)"
            )
            guard ManagedVideoSDP.hasRoutableICECandidate(candidate.sdp) else {
                return
            }
            // Signaling sends one non-trickle answer. Allow the usual UDP/TCP/
            // TLS TURN candidates to arrive together instead of committing to
            // whichever relay callback happened to win the first race.
            self.queue.asyncAfter(deadline: .now() + .milliseconds(300)) { [weak self] in
                self?.maybeSendAnswerOnQueue(allowPartialGathering: true)
            }
        }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
