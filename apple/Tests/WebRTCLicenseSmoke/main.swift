import Foundation
import WebRTC

final class OfferResult: @unchecked Sendable {
    private let lock = NSLock()
    private var result: (String?, String?)?
    func set(_ sdp: String?, _ error: String?) { lock.lock(); result = (sdp, error); lock.unlock() }
    func get() -> (String?, String?)? { lock.lock(); defer { lock.unlock() }; return result }
}

precondition(RTCInitializeSSL(), "SSL initialization failed")
let encoders = RTCDefaultVideoEncoderFactory.supportedCodecs().map(\.name)
let decoders = RTCDefaultVideoDecoderFactory().supportedCodecs().map(\.name)
precondition(encoders.contains("H264") && encoders.contains("VP8"))
precondition(decoders.contains("H264") && decoders.contains("VP8"))
let factory = RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
let configuration = RTCConfiguration()
configuration.sdpSemantics = .unifiedPlan
let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
guard let peer = factory.peerConnection(with: configuration, constraints: constraints, delegate: nil) else { fatalError("Peer creation failed") }
let transceiver = RTCRtpTransceiverInit()
transceiver.direction = .recvOnly
precondition(peer.addTransceiver(of: .video, init: transceiver) != nil)
let result = OfferResult()
peer.offer(for: constraints) { description, error in result.set(description?.sdp, error?.localizedDescription) }
let deadline = Date().addingTimeInterval(15)
while result.get() == nil && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.05)) }
guard let (sdp, error) = result.get(), error == nil, let sdp, sdp.contains("m=video"), sdp.contains("a=fingerprint:sha-256") else { fatalError("SDP offer failed: \(String(describing: result.get()))") }
peer.close()
let report: [String: Any] = ["result": "passed", "ssl": true, "encoders": encoders, "decoders": decoders, "peer_created": true, "receive_video_sdp_offer": true, "dtls_fingerprint": true, "capture_started": false, "remote_connection_started": false]
let data = try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
print(String(decoding: data, as: UTF8.self))
RTCCleanupSSL()
