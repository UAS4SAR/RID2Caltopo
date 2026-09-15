package org.ncssar.rid2caltopo.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridge;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.JavaI420Buffer;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pilot-approved, app-owned WebRTC video sender backed by decoded FFmpeg frames. */
public final class ManagedVideoMediaPeer implements AutoCloseable {
    private static final String TAG = "ManagedVideoMedia";
    private static final long PARTIAL_ICE_ANSWER_DELAY_MS = 2_500L;
    private static final long MEDIA_ANSWER_TIMEOUT_MS = 15_000L;

    static int minimumBitrateForTarget(long targetBitrateBps) {
        return (int) Math.min(Math.max(1L, targetBitrateBps), 100_000L);
    }

    static int startupBitrateForTarget(long targetBitrateBps) {
        int target = (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(1L, targetBitrateBps));
        return Math.min(
                target,
                Math.max(200_000, Math.min(750_000, target / 5)));
    }
    public interface Sink {
        void sendAnswer(@NonNull String requestId, @NonNull String sdp);
        void onMetrics(@NonNull Metrics metrics);
        void onFailure(@NonNull String requestId, @NonNull String reason);
        void onMicrophoneState(@NonNull String requestId, boolean enabled, @Nullable String error);
        void onReady(@NonNull String requestId);
    }

    public static final class Metrics {
        @NonNull public final String requestId;
        @NonNull public final String routeKind;
        public final long bytesSent;
        public final int width;
        public final int height;
        public final double framesPerSecond;
        public final long bitrateBps;

        Metrics(String requestId, String routeKind, long bytesSent, int width, int height,
                double framesPerSecond, long bitrateBps) {
            this.requestId = requestId;
            this.routeKind = routeKind;
            this.bytesSent = bytesSent;
            this.width = width;
            this.height = height;
            this.framesPerSecond = framesPerSecond;
            this.bitrateBps = bitrateBps;
        }
    }

    @NonNull private final Sink sink;
    @NonNull private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();
    @Nullable private EglBase eglBase;
    @Nullable private PeerConnectionFactory factory;
    @Nullable private PeerConnection peer;
    @Nullable private VideoSource videoSource;
    @Nullable private VideoTrack videoTrack;
    @Nullable private RtpSender videoSender;
    @Nullable private RtpSender audioSender;
    @Nullable private AudioSource audioSource;
    @Nullable private AudioTrack audioTrack;
    @Nullable private AudioManager audioManager;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private boolean communicationAudioConfigured;
    @Nullable private ScheduledFuture<?> statsTask;
    @NonNull private String requestId = "";
    private volatile long ffmpegSessionId;
    @Nullable private FfmpegBridge.RemoteVideoFrameLease videoFrameLease;
    private int selectedWidth;
    private int selectedHeight;
    private double selectedFps;
    private long selectedBitrateBps;
    private boolean answerSent;
    @NonNull private final List<IceCandidate> localIceCandidates = new ArrayList<>();
    private long previousBytes;
    private long previousFrames;
    private long previousStatsAtMs;
    private long lastDiagnosticAtMs;
    private final AtomicBoolean closed = new AtomicBoolean(true);
    private Context applicationContext;
    private volatile boolean microphoneRequested;
    private volatile boolean microphoneRecording;
    private volatile String microphoneFailure;
    private ManagedVideoPlaybackStartGate playbackStartGate = new ManagedVideoPlaybackStartGate();

    public ManagedVideoMediaPeer(@NonNull Sink sink) {
        this.sink = sink;
    }

    public synchronized boolean start(
            @NonNull Context context,
            @NonNull VideoMediaOffer offer,
            long sessionId,
            int width,
            int height,
            double fps,
            long bitrateBps,
            boolean deferredRecording) {
        closePeer();
        playbackStartGate = new ManagedVideoPlaybackStartGate();
        requestId = offer.requestId;
        ffmpegSessionId = sessionId;
        selectedWidth = width & ~1;
        selectedHeight = height & ~1;
        selectedFps = Math.max(1.0, Math.min(30.0, fps));
        selectedBitrateBps = Math.max(100_000L, bitrateBps);
        answerSent = false;
        localIceCandidates.clear();
        previousBytes = 0L;
        previousFrames = 0L;
        previousStatsAtMs = 0L;
        lastDiagnosticAtMs = 0L;
        closed.set(false);
        CaltopoClient.CTDebug(TAG, "Starting media peer request=" + requestId
                + " thread=" + Thread.currentThread().getName());
        if ((!deferredRecording && sessionId <= 0) || selectedWidth < 2 || selectedHeight < 2) {
            fail("The selected drone video source is not available.");
            return false;
        }
        try {
            applicationContext = context.getApplicationContext();
            microphoneRequested = false;
            microphoneRecording = false;
            microphoneFailure = null;
            configureCommunicationAudio(applicationContext);
            initializeFactory(context.getApplicationContext());
            PeerConnection.RTCConfiguration configuration =
                    new PeerConnection.RTCConfiguration(parseIceServers(offer.iceServers));
            configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            // The browser is already constrained to TURN/relay. Requiring a
            // second relay allocation on the tablet can leave Android with no
            // candidates even though the measured routed path is healthy.
            // Any selected pair still includes the browser relay and is routed.
            configuration.iceTransportsType = PeerConnection.IceTransportsType.ALL;
            // Android can finish its first gathering pass before NetworkMonitor
            // reports the already-connected Wi-Fi network. Keep gathering so a
            // candidate can still be produced when that callback arrives.
            configuration.continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
            peer = factory.createPeerConnection(configuration, new PeerObserver());
            if (peer == null) {
                fail("Unable to create WebRTC media peer.");
                return false;
            }
            // A disabled track still allows WebRTC to open AudioRecord. Keep
            // capture off until the operator enables it after granting permission.
            peer.setAudioRecording(false);
            CaltopoClient.CTDebug(TAG, "Media peer created request=" + requestId);
            videoSource = factory.createVideoSource(false);
            videoSource.adaptOutputFormat(selectedWidth, selectedHeight, (int) Math.round(selectedFps));
            videoTrack = factory.createVideoTrack("r2c-video", videoSource);
            videoSender = peer.addTrack(videoTrack, java.util.Collections.singletonList("r2c-managed"));
            applySenderLimits();
            String normalizedOfferSdp = ManagedVideoSdp.normalizeRemoteOffer(offer.sdp);
            peer.setRemoteDescription(
                    new DescriptionObserver("apply the browser offer") {
                        @Override void onSuccessValue() {
                            CaltopoClient.CTDebug(
                                    TAG,
                                    "Media remote offer applied request=" + requestId);
                            configureAudioTransceiver();
                            createAnswer();
                        }
                    },
                    new SessionDescription(SessionDescription.Type.OFFER, normalizedOfferSdp));
            if (!deferredRecording) {
                FfmpegBridge.RemoteVideoFrameLease lease = FfmpegBridge.INSTANCE.startRemoteVideoFrames(
                        sessionId,
                        selectedWidth,
                        selectedHeight,
                        selectedFps,
                        FfmpegBridge.RemoteVideoFramePurpose.LIVE_SHARE,
                        this::onDecodedFrame);
                if (lease == null) {
                    fail("Unable to attach the approved drone video source.");
                    return false;
                }
                videoFrameLease = lease;
            }
            statsTask = executor.scheduleAtFixedRate(
                    this::collectStats,
                    2L,
                    2L,
                    TimeUnit.SECONDS);
            executor.schedule(
                    this::failIfAnswerWasNotSent,
                    MEDIA_ANSWER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception exception) {
            CaltopoClient.CTError(TAG, "Unable to start approved WebRTC video.", exception);
            fail("Unable to start approved WebRTC video.");
            return false;
        }
    }

    /**
     * Move an active WebRTC sender to a replacement FFmpeg decoder session.
     * Local RTMP recovery is expected to replace decoder sessions; it must not
     * tear down the already-approved browser/WebRTC session.
     */
    public synchronized boolean rebindVideoSource(long replacementSessionId) {
        if (closed.get() || replacementSessionId <= 0L) return false;
        long previousSessionId = ffmpegSessionId;
        if (replacementSessionId == previousSessionId) return true;
        FfmpegBridge.RemoteVideoFrameLease replacementLease =
                FfmpegBridge.INSTANCE.startRemoteVideoFrames(
                replacementSessionId,
                selectedWidth,
                selectedHeight,
                selectedFps,
                FfmpegBridge.RemoteVideoFramePurpose.LIVE_SHARE,
                this::onDecodedFrame);
        if (replacementLease == null) {
            CaltopoClient.CTWarn(TAG, "Unable to rebind media peer request=" + requestId
                    + " replacementSession=" + replacementSessionId);
            return false;
        }
        FfmpegBridge.RemoteVideoFrameLease previousLease = videoFrameLease;
        videoFrameLease = replacementLease;
        ffmpegSessionId = replacementSessionId;
        if (previousLease != null) {
            FfmpegBridge.INSTANCE.stopRemoteVideoFrames(previousLease);
        }
        CaltopoClient.CTDebug(TAG, "Rebound media peer request=" + requestId
                + " decoderSession=" + previousSessionId + "->" + replacementSessionId);
        return true;
    }

    private void initializeFactory(@NonNull Context context) {
        ManagedWebRtcRuntime.initialize(context);
        eglBase = EglBase.create();
        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(
                eglBase.getEglBaseContext());
        JavaAudioDeviceModule audioModule = JavaAudioDeviceModule.builder(context)
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    @Override public void onWebRtcAudioRecordInitError(String error) {
                        reportMicrophoneFailure(error);
                    }
                    @Override public void onWebRtcAudioRecordStartError(
                            JavaAudioDeviceModule.AudioRecordStartErrorCode code, String error) {
                        reportMicrophoneFailure(error);
                    }
                    @Override public void onWebRtcAudioRecordError(String error) {
                        reportMicrophoneFailure(error);
                    }
                })
                .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback() {
                    @Override public void onWebRtcAudioRecordStart() {
                        microphoneRecording = true;
                        if (!closed.get() && microphoneRequested && microphoneFailure == null) {
                            sink.onMicrophoneState(requestId, true, null);
                        }
                    }
                    @Override public void onWebRtcAudioRecordStop() {
                        microphoneRecording = false;
                        if (!closed.get()) sink.onMicrophoneState(requestId, false, microphoneFailure);
                    }
                })
                .createAudioDeviceModule();
        try {
            factory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(audioModule)
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory();
        } finally {
            audioModule.release();
        }
    }

    private void reportMicrophoneFailure(String detail) {
        microphoneFailure = "Microphone could not start. End remote viewing and reconnect to retry.";
        microphoneRecording = false;
        CaltopoClient.CTWarn(TAG, "Microphone capture failed request=" + requestId + " " + detail);
        // Audio callbacks run on a native worker. Never call back into WebRTC
        // there: its signaling thread may be waiting for that worker.
        try {
            executor.execute(() -> {
                if (closed.get()) return;
                setMicrophoneEnabled(false);
                sink.onMicrophoneState(requestId, false, microphoneFailure);
            });
        } catch (RejectedExecutionException ignored) { }
    }

    private void applySenderLimits() {
        RtpSender sender = videoSender;
        if (sender == null) return;
        RtpParameters parameters = sender.getParameters();
        int target = (int) Math.min(Integer.MAX_VALUE, selectedBitrateBps);
        int minimum = minimumBitrateForTarget(target);
        int startup = startupBitrateForTarget(target);
        for (RtpParameters.Encoding encoding : parameters.encodings) {
            encoding.minBitrateBps = minimum;
            encoding.maxBitrateBps = target;
            encoding.maxFramerate = (int) Math.round(selectedFps);
            encoding.scaleResolutionDownBy = 1.0;
        }
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION;
        boolean senderApplied = sender.setParameters(parameters);
        PeerConnection activePeer = peer;
        boolean bweApplied = activePeer != null && activePeer.setBitrate(
                minimum, startup, target);
        CaltopoClient.CTDebug(
                TAG,
                "Sender target request=" + requestId + " " + selectedWidth + "x"
                        + selectedHeight + "@" + selectedFps
                        + " bitrate min/current/max=" + minimum + "/" + startup + "/" + target
                        + " degradation=maintain-resolution scale=1.0"
                        + " senderApplied=" + senderApplied + " bweApplied=" + bweApplied);
    }

    private void configureAudioTransceiver() {
        PeerConnection activePeer = peer;
        if (activePeer == null) return;
        for (RtpTransceiver transceiver : activePeer.getTransceivers()) {
            if (transceiver.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                audioSender = transceiver.getSender();
                // Negotiate the tablet-to-browser track in the initial answer.
                // Adding it only when the operator unmutes can require another
                // negotiation, which this media session does not perform.
                org.webrtc.MediaConstraints constraints = new org.webrtc.MediaConstraints();
                constraints.optional.add(new org.webrtc.MediaConstraints.KeyValuePair(
                        "googEchoCancellation", "true"));
                constraints.optional.add(new org.webrtc.MediaConstraints.KeyValuePair(
                        "googAutoGainControl", "true"));
                constraints.optional.add(new org.webrtc.MediaConstraints.KeyValuePair(
                        "googNoiseSuppression", "true"));
                audioSource = factory.createAudioSource(constraints);
                audioTrack = factory.createAudioTrack("r2c-audio", audioSource);
                audioTrack.setEnabled(false);
                if (!audioSender.setTrack(audioTrack, false)) {
                    deferFailure("Unable to negotiate the tablet microphone.", null);
                    return;
                }
                setAudioSenderActive(false);
                CaltopoClient.CTDebug(TAG, "Negotiated muted tablet audio track request=" + requestId);
                return;
            }
        }
    }

    private void configureCommunicationAudio(@NonNull Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;
        audioManager = manager;
        previousAudioMode = manager.getMode();
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        boolean speakerSelected = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo current = manager.getCommunicationDevice();
            if (current == null || current.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                for (AudioDeviceInfo device : manager.getAvailableCommunicationDevices()) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        speakerSelected = manager.setCommunicationDevice(device);
                        break;
                    }
                }
            }
        } else {
            manager.setSpeakerphoneOn(true);
            speakerSelected = true;
        }
        communicationAudioConfigured = true;
        CaltopoClient.CTDebug(
                TAG,
                "Configured remote voice audio mode=" + manager.getMode()
                        + " speakerSelected=" + speakerSelected);
    }

    private void restoreCommunicationAudio() {
        AudioManager manager = audioManager;
        audioManager = null;
        if (manager == null || !communicationAudioConfigured) return;
        communicationAudioConfigured = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.clearCommunicationDevice();
        } else {
            manager.setSpeakerphoneOn(false);
        }
        manager.setMode(previousAudioMode);
    }

    private boolean setAudioSenderActive(boolean active) {
        RtpSender sender = audioSender;
        if (sender == null) return false;
        RtpParameters parameters = sender.getParameters();
        for (RtpParameters.Encoding encoding : parameters.encodings) {
            encoding.active = active;
        }
        boolean applied = sender.setParameters(parameters);
        CaltopoClient.CTDebug(
                TAG,
                "Media microphone RTP request=" + requestId
                        + " active=" + active + " applied=" + applied);
        return applied;
    }

    public synchronized void setMicrophoneEnabled(boolean enabled) {
        RtpSender sender = audioSender;
        PeerConnectionFactory activeFactory = factory;
        if (sender == null || activeFactory == null || audioTrack == null || closed.get()) {
            sink.onMicrophoneState(requestId, false, "VoIP audio path unavailable");
            return;
        }
        if (enabled && (applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED || microphoneFailure != null)) {
            sink.onMicrophoneState(requestId, false, microphoneFailure != null
                    ? microphoneFailure : "Microphone permission denied");
            return;
        }
        microphoneRequested = enabled;
        if (!enabled) peer.setAudioRecording(false);
        // Keep the negotiated track attached across mute/unmute cycles.
        // Mute locally first; a failed RTP update must never leave a live mic.
        boolean applied = ManagedVideoMicrophoneControl.setEnabled(enabled,
                value -> audioTrack.setEnabled(value), this::setAudioSenderActive);
        if (!applied) {
            microphoneRequested = false;
            sink.onMicrophoneState(requestId, false, "Unable to activate VoIP microphone");
            return;
        }
        if (enabled) peer.setAudioRecording(true);
        sink.onMicrophoneState(requestId, enabled && microphoneRecording && microphoneFailure == null,
                microphoneFailure);
    }

    private void onDecodedFrame(long sessionId, int width, int height, long timestampUs,
                                @NonNull byte[] packedI420) {
        VideoSource source = videoSource;
        if (closed.get() || source == null || sessionId != ffmpegSessionId) return;
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        int yBytes = width * height;
        int chromaBytes = chromaWidth * chromaHeight;
        if (packedI420.length < yBytes + chromaBytes * 2) return;
        JavaI420Buffer buffer = JavaI420Buffer.allocate(width, height);
        copyPlane(packedI420, 0, width, buffer.getDataY(), buffer.getStrideY(), width, height);
        copyPlane(packedI420, yBytes, chromaWidth, buffer.getDataU(), buffer.getStrideU(),
                chromaWidth, chromaHeight);
        copyPlane(packedI420, yBytes + chromaBytes, chromaWidth, buffer.getDataV(),
                buffer.getStrideV(), chromaWidth, chromaHeight);
        VideoFrame frame = new VideoFrame(buffer, 0, Math.max(1L, timestampUs) * 1_000L);
        source.getCapturerObserver().onFrameCaptured(frame);
        frame.release();
    }

    private static void copyPlane(byte[] source, int sourceOffset, int sourceStride,
                                  ByteBuffer destination, int destinationStride,
                                  int width, int height) {
        for (int row = 0; row < height; row++) {
            destination.position(row * destinationStride);
            destination.put(source, sourceOffset + row * sourceStride, width);
        }
    }

    private void createAnswer() {
        PeerConnection activePeer = peer;
        if (activePeer == null) return;
        activePeer.createAnswer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription description) {
                CaltopoClient.CTDebug(TAG, "Media answer created request=" + requestId);
                PeerConnection current = peer;
                if (current == null) return;
                current.setLocalDescription(new DescriptionObserver("set the tablet answer") {
                    @Override void onSuccessValue() {
                        CaltopoClient.CTDebug(
                                TAG,
                                "Media local answer applied request=" + requestId);
                        setAudioSenderActive(false);
                        maybeSendAnswer(false);
                        try {
                            executor.schedule(
                                    () -> maybeSendAnswer(true),
                                    PARTIAL_ICE_ANSWER_DELAY_MS,
                                    TimeUnit.MILLISECONDS);
                        } catch (RejectedExecutionException ignored) {
                            // The pilot retired this peer while ICE was gathering.
                        }
                    }
                }, description);
            }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String error) {
                deferFailure("Unable to create WebRTC answer.", error);
            }
            @Override public void onSetFailure(String error) {
                deferFailure("Unable to create WebRTC answer.", error);
            }
        }, new org.webrtc.MediaConstraints());
    }

    private synchronized void maybeSendAnswer(boolean allowPartialGathering) {
        PeerConnection activePeer = peer;
        if (answerSent || activePeer == null ||
                (!allowPartialGathering && activePeer.iceGatheringState()
                        != PeerConnection.IceGatheringState.COMPLETE)) return;
        SessionDescription description = activePeer.getLocalDescription();
        if (description == null || description.description.trim().isEmpty()) {
            fail("WebRTC media answer was empty.");
            return;
        }
        String completeDescription = ManagedVideoSdp.withIceCandidates(
                description.description,
                new ArrayList<>(localIceCandidates));
        boolean hasCandidate = completeDescription.contains("a=candidate:");
        boolean hasRoutableCandidate =
                ManagedVideoSdp.hasRoutableIceCandidate(completeDescription);
        if (!hasCandidate || !hasRoutableCandidate) {
            CaltopoClient.CTDebug(TAG, "Media answer awaiting routed ICE candidate request="
                    + requestId + " ice=" + activePeer.iceGatheringState()
                    + " gathered=" + localIceCandidates.size()
                    + " anyCandidate=" + hasCandidate);
            // Do not close on the first COMPLETE-without-candidates callback.
            // On Android it can race just ahead of NetworkMonitor announcing
            // Wi-Fi. Continual gathering will then deliver the late candidate;
            // the overall answer timeout remains the bounded failure path.
            return;
        }
        answerSent = true;
        CaltopoClient.CTDebug(TAG, "Sending media answer request=" + requestId
                + " ice=" + activePeer.iceGatheringState()
                + " routableCandidate=" + hasRoutableCandidate
                + " gathered=" + localIceCandidates.size());
        sink.sendAnswer(requestId, completeDescription);
    }

    private synchronized void failIfAnswerWasNotSent() {
        if (closed.get() || answerSent) return;
        CaltopoClient.CTWarn(
                TAG,
                "Timed out waiting 15 seconds for a routed media ICE candidate request="
                        + requestId + " gathered=" + localIceCandidates.size());
        fail("A network connection could not be established.");
    }

    private void collectStats() {
        PeerConnection activePeer = peer;
        if (activePeer != null && !closed.get()) activePeer.getStats(this::consumeStats);
    }

    private void consumeStats(@NonNull RTCStatsReport report) {
        long bytes = 0L;
        long frames = 0L;
        long audioBytesSent = 0L;
        long audioBytesReceived = 0L;
        int width = selectedWidth;
        int height = selectedHeight;
        long packetsLost = 0L;
        long retransmittedBytes = 0L;
        long nackCount = 0L;
        long pliCount = 0L;
        double roundTripTimeSeconds = -1.0;
        String qualityLimitationReason = "unknown";
        for (RTCStats stat : report.getStatsMap().values()) {
            Object kind = stat.getMembers().get("kind");
            Object mediaType = stat.getMembers().get("mediaType");
            if ("audio".equals(String.valueOf(kind)) || "audio".equals(String.valueOf(mediaType))) {
                if ("outbound-rtp".equals(stat.getType())) audioBytesSent += longValue(stat, "bytesSent", 0L);
                if ("inbound-rtp".equals(stat.getType())) audioBytesReceived += longValue(stat, "bytesReceived", 0L);
            }
            if (!"video".equals(String.valueOf(kind)) && !"video".equals(String.valueOf(mediaType))) continue;
            if ("outbound-rtp".equals(stat.getType())) {
                bytes = longValue(stat, "bytesSent", bytes);
                frames = longValue(stat, "framesEncoded", frames);
                width = (int) longValue(stat, "frameWidth", width);
                height = (int) longValue(stat, "frameHeight", height);
                retransmittedBytes += longValue(stat, "retransmittedBytesSent", 0L);
                nackCount += longValue(stat, "nackCount", 0L);
                pliCount += longValue(stat, "pliCount", 0L);
                Object reason = stat.getMembers().get("qualityLimitationReason");
                if (reason != null) qualityLimitationReason = String.valueOf(reason);
            } else if ("remote-inbound-rtp".equals(stat.getType())) {
                packetsLost += longValue(stat, "packetsLost", 0L);
                roundTripTimeSeconds = doubleValue(
                        stat, "roundTripTime", roundTripTimeSeconds);
            }
        }
        long now = System.currentTimeMillis();
        long elapsed = previousStatsAtMs == 0L ? 0L : Math.max(1L, now - previousStatsAtMs);
        double actualFps = elapsed == 0L ? 0.0 : (frames - previousFrames) * 1_000.0 / elapsed;
        long actualBitrate = elapsed == 0L ? 0L : Math.max(0L, bytes - previousBytes) * 8_000L / elapsed;
        previousBytes = bytes;
        previousFrames = frames;
        previousStatsAtMs = now;
        if (elapsed > 0L && now - lastDiagnosticAtMs >= 10_000L) {
            lastDiagnosticAtMs = now;
            String message = "Media stats request=" + requestId
                    + " route=" + selectedRouteKind(report)
                    + " actual=" + width + "x" + height + "@"
                    + String.format(java.util.Locale.US, "%.1f", Math.max(0.0, actualFps))
                    + " bitrate=" + actualBitrate + " target=" + selectedBitrateBps
                    + " bytes=" + bytes + " frames=" + frames
                    + " audioSent=" + audioBytesSent + " audioReceived=" + audioBytesReceived
                    + " qualityLimit=" + qualityLimitationReason
                    + " rttMs=" + (roundTripTimeSeconds < 0.0
                        ? -1L : Math.round(roundTripTimeSeconds * 1_000.0))
                    + " packetsLost=" + packetsLost
                    + " retransmitBytes=" + retransmittedBytes
                    + " nack=" + nackCount + " pli=" + pliCount;
            if (actualBitrate > 0L && actualBitrate * 2L < selectedBitrateBps) {
                CaltopoClient.CTWarn(TAG, message + " underTarget=true");
            } else {
                CaltopoClient.CTDebug(TAG, message);
            }
        }
        sink.onMetrics(new Metrics(requestId, selectedRouteKind(report), bytes, width, height,
                Math.max(0.0, actualFps), actualBitrate));
    }

    private static long longValue(RTCStats stat, String key, long fallback) {
        Object value = stat.getMembers().get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static double doubleValue(RTCStats stat, String key, double fallback) {
        Object value = stat.getMembers().get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    @NonNull private static String selectedRouteKind(@NonNull RTCStatsReport report) {
        Map<String, RTCStats> stats = report.getStatsMap();
        String selectedPairId = "";
        for (RTCStats stat : stats.values()) {
            if ("transport".equals(stat.getType())) {
                Object value = stat.getMembers().get("selectedCandidatePairId");
                if (value != null) selectedPairId = String.valueOf(value);
            }
        }
        RTCStats pair = stats.get(selectedPairId);
        if (pair == null) return "connecting";
        RTCStats local = stats.get(String.valueOf(pair.getMembers().get("localCandidateId")));
        RTCStats remote = stats.get(String.valueOf(pair.getMembers().get("remoteCandidateId")));
        if (local == null || remote == null) return "connecting";
        return "relay".equals(String.valueOf(local.getMembers().get("candidateType"))) ||
                "relay".equals(String.valueOf(remote.getMembers().get("candidateType")))
                ? "routed" : "direct";
    }

    @NonNull private static List<PeerConnection.IceServer> parseIceServers(@Nullable JSONArray values) {
        List<PeerConnection.IceServer> result = new ArrayList<>();
        if (values == null) return result;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            List<String> urls = new ArrayList<>();
            Object rawUrls = value.opt("urls");
            if (rawUrls instanceof String) urls.add((String) rawUrls);
            else if (rawUrls instanceof JSONArray) {
                JSONArray array = (JSONArray) rawUrls;
                for (int i = 0; i < array.length(); i++) {
                    String url = array.optString(i).trim();
                    if (!url.isEmpty()) urls.add(url);
                }
            }
            if (urls.isEmpty()) continue;
            PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urls);
            String username = value.optString("username").trim();
            String credential = value.optString("credential").trim();
            if (!username.isEmpty() && !credential.isEmpty()) {
                builder.setUsername(username).setPassword(credential);
            }
            result.add(builder.createIceServer());
        }
        return result;
    }

    private void fail(@NonNull String reason) {
        String failedRequest = requestId;
        closePeer();
        if (!failedRequest.isEmpty()) sink.onFailure(failedRequest, reason);
    }

    private void deferFailure(@NonNull String reason, @Nullable String detail) {
        CaltopoClient.CTWarn(TAG, reason + (detail == null ? "" : " " + detail));
        try {
            executor.execute(() -> fail(reason));
        } catch (RejectedExecutionException ignored) {
            // The app-owned media peer has already been closed.
        }
    }

    private synchronized void closePeer() {
        playbackStartGate.cancel();
        if (closed.getAndSet(true) && peer == null && factory == null) return;
        FfmpegBridge.RemoteVideoFrameLease activeVideoFrameLease = videoFrameLease;
        videoFrameLease = null;
        if (activeVideoFrameLease != null) {
            FfmpegBridge.INSTANCE.stopRemoteVideoFrames(activeVideoFrameLease);
        }
        ffmpegSessionId = 0L;
        ScheduledFuture<?> activeStatsTask = statsTask;
        statsTask = null;
        if (activeStatsTask != null) activeStatsTask.cancel(false);
        videoSender = null;
        audioSender = null;
        AudioTrack activeAudioTrack = audioTrack;
        audioTrack = null;
        AudioSource activeAudioSource = audioSource;
        audioSource = null;
        VideoTrack track = videoTrack;
        videoTrack = null;
        VideoSource source = videoSource;
        videoSource = null;
        PeerConnection activePeer = peer;
        peer = null;
        microphoneRequested = false;
        microphoneRecording = false;
        // Native close dispatches signaling callbacks. Release this monitor
        // before closing so those callbacks cannot deadlock session cleanup.
        restoreCommunicationAudio();
        PeerConnectionFactory activeFactory = factory;
        factory = null;
        EglBase activeEgl = eglBase;
        eglBase = null;
        if (activePeer != null || activeFactory != null || activeEgl != null) {
            try {
                executor.schedule(() -> {
                    if (activePeer != null) {
                        activePeer.close();
                        activePeer.dispose();
                    }
                    if (activeAudioTrack != null) activeAudioTrack.dispose();
                    if (activeAudioSource != null) activeAudioSource.dispose();
                    if (track != null) track.dispose();
                    if (source != null) source.dispose();
                    if (activeFactory != null) activeFactory.dispose();
                    if (activeEgl != null) activeEgl.release();
                }, 500L, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // Shutdown occurs only after the final peer has been retired.
            }
        }
    }

    @Override public void close() {
        closePeer();
        executor.shutdown();
    }

    private abstract class DescriptionObserver implements SdpObserver {
        @NonNull private final String operation;
        DescriptionObserver(@NonNull String operation) { this.operation = operation; }
        abstract void onSuccessValue();
        @Override public void onSetSuccess() { onSuccessValue(); }
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onCreateFailure(String error) {
            deferFailure("WebRTC failed to " + operation + ".", error);
        }
        @Override public void onSetFailure(String error) {
            deferFailure("WebRTC failed to " + operation + ".", error);
        }
    }

    private final class PeerObserver implements PeerConnection.Observer {
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            if (closed.get() || state != PeerConnection.PeerConnectionState.CONNECTED) return;
            synchronized (ManagedVideoMediaPeer.this) {
                if (closed.get() || !playbackStartGate.shouldStart(
                        state == PeerConnection.PeerConnectionState.CONNECTED)) return;
                CaltopoClient.CTDebug(TAG, "Media transport ready request=" + requestId);
                sink.onReady(requestId);
            }
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            if (!closed.get() && state == PeerConnection.IceConnectionState.FAILED) {
                deferFailure("The remote video connection failed.", null);
            }
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            CaltopoClient.CTDebug(
                    TAG,
                    "Media ICE gathering request=" + requestId + " state=" + state);
            if (state == PeerConnection.IceGatheringState.COMPLETE) maybeSendAnswer(false);
        }
        @Override public void onIceCandidate(IceCandidate candidate) {
            synchronized (ManagedVideoMediaPeer.this) {
                if (closed.get()) return;
                localIceCandidates.add(candidate);
                String candidateType = candidate.sdp.contains(" typ relay ")
                        ? "relay"
                        : candidate.sdp.contains(" typ srflx ") ? "srflx" : "host";
                CaltopoClient.CTDebug(
                        TAG,
                        "Media " + candidateType + " candidate gathered request=" + requestId
                                + " mline=" + candidate.sdpMLineIndex
                                + " count=" + localIceCandidates.size());
            }
            if (!ManagedVideoSdp.hasRoutableIceCandidate(localIceCandidates)) return;
            try {
                executor.schedule(
                        () -> maybeSendAnswer(true),
                        50L,
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // The pilot retired this peer while ICE was gathering.
            }
        }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(MediaStream stream) { }
        @Override public void onRemoveStream(MediaStream stream) { }
        @Override public void onDataChannel(DataChannel dataChannel) { }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) { }
    }
}
