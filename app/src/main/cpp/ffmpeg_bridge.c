#include <jni.h>
#include <android/log.h>
#include <android/trace.h>
#include <android/native_window_jni.h>
#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <stdarg.h>
#include <time.h>
#include <unistd.h>

#include "../../../../native/R2CDJICameraTelemetry.h"
#include "../../../../native/R2CSEIDiscovery.h"
#include "../../../../native/R2CLocalPlaybackCadence.h"

#if !defined(HAVE_FFMPEG)
#define HAVE_FFMPEG 0
#endif
#if !defined(HAVE_SWSCALE)
#define HAVE_SWSCALE 0
#endif

#if HAVE_FFMPEG
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/mem.h>
#include <libavutil/pixfmt.h>
#include <libavutil/dict.h>
#if HAVE_SWSCALE
#include <libswscale/swscale.h>
#endif
#endif

#define TAG "ffmpeg_bridge"
#define MAX_SESSIONS 32
#include "anomaly_analysis.h"
#include "anomaly_debug_helpers.h"
#include "anomaly_detector.h"
#include "anomaly_person_relevance_scheduler.h"
#include "anomaly_runtime_handoff.h"
#include "anomaly_runtime_pressure.h"
#include "anomaly_target_revisit.h"
#define RENDER_MIN_INTERVAL_MS 5
#define RENDER_MAX_INTERVAL_MS 1000
#define RENDER_DEFAULT_FPS 30
// ═══════════════════════════════════════════════════════════════════════════
// Bursty drone video and the adaptive render-control loop
// ═══════════════════════════════════════════════════════════════════════════
//
// Drone video (DJI over RTMP → MediaMTX → RTSP) is highly bursty and varies
// significantly across drone models and controller firmware:
//
//   • The drone controller batches H.264 NAL units and sends them in tight
//     bursts — several frames may arrive within a few ms of each other.
//   • Gaps of 200 – 800 ms between bursts are completely normal, and are
//     observed during manoeuvres, auto-exposure events, or controller-side
//     recording flushes.  Some models produce gaps > 900 ms routinely.
//   • A single IDR "keyframe" may carry a disproportionately large payload and
//     arrive well ahead of the next P-frame run.
//   • Different controller/drone combinations produce wildly different cadences.
//     Hardcoded thresholds tuned for one model will break others.
//
// Because of this burstiness the decode thread fills the render queue faster
// than the render thread drains it during a burst, then the queue sits empty
// during the gap.  A naïve approach (render as fast as possible) would drain
// the queue before the next burst and cause display stutter.
//
// ── Adaptive control overview ───────────────────────────────────────────────
// Rather than capping the queue or hard-coding a backpressure depth, the render
// thread uses a closed-loop controller that adapts to each stream's observed
// behaviour:
//
//  1. source_render_interval_ms  — EMA of inter-decode deltas and PTS-derived
//     cadence.  Represents the underlying "native" frame rate of this stream.
//
//  2. stall_estimate_ms  — asymmetric EMA of observed inter-burst gaps.
//     Rises quickly when a new gap is seen (RENDER_STALL_RISE_EMA_PCT),
//     decays very slowly after a long quiet period (RENDER_STALL_DECAY_EMA_PCT,
//     with a 5-second grace window before decay starts).
//
//  3. proven_gap_ms  — tracks the largest confirmed gap (> RENDER_PROVEN_GAP_TRIGGER_MS).
//     Only decays after 30 seconds of silence, and very slowly (2 % EMA).
//     This prevents the renderer from becoming over-eager after a drone that
//     normally has large gaps happens to transmit smoothly for a while.
//
//  4. target_latency_ms  — computed as max(stall_ms, proven_gap_ms) × 2 + margin,
//     clamped to [RENDER_TARGET_LATENCY_MIN_MS, RENDER_TARGET_LATENCY_MAX_MS].
//     This is the desired amount of buffered content the render thread aims to
//     maintain so it can bridge inter-burst gaps without stalling.
//
//  5. compute_desired_render_interval_ms_locked()  — PID-like controller.
//     Compares the current buffered_span_ms to target_latency_ms and adjusts the
//     render interval proportionally (±RENDER_INTERVAL_ADJUST_BASE_PCT to
//     ±RENDER_INTERVAL_ADJUST_MAX_PCT).  A smoothing EMA prevents abrupt steps.
//     During an active stall the interval is gently extended to conserve buffer.
//
// RENDER_QUEUE_INITIAL_CAPACITY is the queue's starting size; it grows
// automatically via ensure_render_queue_capacity() if needed.
//
// RENDER_QUEUE_BACKPRESSURE_DEPTH is reserved for a potential future
// decode-side backpressure mechanism.  It is NOT currently used in the decode
// loop — the adaptive render-control loop above is the sole mechanism for
// matching render rate to source rate.
#define RENDER_QUEUE_INITIAL_CAPACITY 32
#define RENDER_QUEUE_BACKPRESSURE_DEPTH 16  // reserved; not active — see notes above
#define RENDER_QUEUE_LOG_INTERVAL_MS 1000
#define RENDER_QUEUE_WARN_INTERVAL_MS 2000
#define RENDER_QUEUE_WARN_DEPTH 100
#define RENDER_LAG_LOG_INTERVAL_MS 1000
#define RENDER_STARTUP_OBSERVE_MS 2000
#define RENDER_SAMPLE_WINDOW_CAPACITY 16
#define RENDER_SOURCE_INTERVAL_DEFAULT_MS 33
#define RENDER_STALL_ESTIMATE_FLOOR_MS 300
#define RENDER_TARGET_LATENCY_MIN_MS 700
#define RENDER_TARGET_LATENCY_MAX_MS 1800
#define RENDER_PROCESSING_MARGIN_MS 100
#define RENDER_CADENCE_SAMPLE_MIN_MS 10
#define RENDER_CADENCE_SAMPLE_MAX_MS 120
#define RENDER_GAP_FLOOR_MS 150
#define RENDER_INTERVAL_ADJUST_MAX_PCT 40
#define RENDER_INTERVAL_ADJUST_BASE_PCT 12
#define RENDER_INTERVAL_SMOOTHING_PCT 15
#define RENDER_SOURCE_ESTIMATE_EMA_PCT 6
#define RENDER_SOURCE_ESTIMATE_UPDATE_INTERVAL_MS 1000
#define RENDER_SOURCE_ESTIMATE_MIN_PCT 65
#define RENDER_SOURCE_ESTIMATE_MAX_PCT 150
#define RENDER_STALL_RISE_EMA_PCT 30
#define RENDER_STALL_DECAY_EMA_PCT 4
#define RENDER_STALL_DECAY_GRACE_MS 5000
#define RENDER_STALL_DECAY_INTERVAL_MS 1000
#define RENDER_PROVEN_GAP_TRIGGER_MS 900
#define RENDER_PROVEN_GAP_DECAY_EMA_PCT 2
#define RENDER_PROVEN_GAP_DECAY_GRACE_MS 30000
#define RENDER_PROVEN_GAP_DECAY_INTERVAL_MS 5000
#define RENDER_CONTROL_LOG_INTERVAL_MS 1000
#define RENDER_NO_SURFACE_LOG_INTERVAL_MS 2000
#define IO_STARTUP_INTERRUPT_MS 4000
// Set to 1 to enable telemetry extraction from packet side-data and frame
// metadata (drone flight data embedded in the stream).  This is expensive on
// CPU-constrained devices and has not yet yielded actionable insights, so it
// is disabled by default.  probe_event calls for frame_decoded /
// decoded_frame_gap are NOT gated by this flag — they remain active.
#define FFMPEG_TELEMETRY_ENABLED 0
#define LOCAL_PLAYBACK_HISTORY_CAPACITY 24
// Live AD queue is intentionally sized above the nominal 750 ms budget so we
// can absorb bursty RTMP/TCP delivery and occasional runs of "cheap" decodes
// without tripping straight into full bypass.  At ~30 fps, 24 frames is ~800 ms.
#define AD_INPUT_QUEUE_INITIAL_CAPACITY 24
#define AD_INPUT_QUEUE_HARD_CAPACITY 24
#define AD_INPUT_QUEUE_DEFAULT_BACKLOG_MS 750
#define LOCAL_AD_INPUT_QUEUE_BACKLOG_MS 150
#define LOCAL_AD_STARTUP_PREROLL_RENDER_DEPTH 30
#define LOCAL_AD_STARTUP_PREROLL_MAX_WAIT_MS 1000
#define LOCAL_AD_FULL_SCAN_STRIDE_FRAMES 30
#define LOCAL_AD_TARGET_EVAL_INTERVAL_FRAMES 5
#define LOCAL_AD_TARGET_COLOR_RENDER_LATENCY_CAP_MS 350
#define LOCAL_AD_TARGET_COLOR_BUFFER_LATENCY_CAP_MS 100
#define AD_PRESSURE_ANALYZE_ALTERNATE_PCT 50
#define AD_PRESSURE_BYPASS_ALTERNATE_PCT 66
#define AD_PRESSURE_BYPASS_ALL_PCT 80
#define AD_PRESSURE_RECOVER_DEPTH 2

typedef enum {
    AD_PAUSE_REASON_NONE = 0,
    AD_PAUSE_REASON_OVERLOAD = 1,
    AD_PAUSE_REASON_THERMAL = 2,
} ad_pause_reason_t;

typedef enum {
    AD_RUNTIME_MODE_BYPASSED = 0,
    AD_RUNTIME_MODE_INLINE = 1,
    AD_RUNTIME_MODE_THREADED = 2,
} ad_runtime_mode_t;

typedef anomaly_runtime_pressure_mode_t ad_pressure_mode_t;
#define AD_PRESSURE_MODE_NORMAL ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL
#define AD_PRESSURE_MODE_ANALYZE_ALTERNATE ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE
#define AD_PRESSURE_MODE_BYPASS_ALTERNATE ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE
#define AD_PRESSURE_MODE_BYPASS_ALL ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL

#if HAVE_FFMPEG && HAVE_SWSCALE
typedef struct {
    AVFrame *frame;
    AVFrame *history_frame;
    AVFrame *overlay_frame;
    int64_t frame_id;
    int64_t local_ad_cadence_ordinal;
    int64_t generation_id;
    int64_t source_ts_us;
    int64_t enqueued_at_ms;
    int width;
    int height;
    enum AVPixelFormat pixel_format;
    bool analyzed;
} render_queue_slot_t;
#endif

typedef struct ffmpeg_session_t {
    jlong session_id;
    bool active;
    bool running;
    bool is_render;
    char designator[96];
    char url[256];
    pthread_t thread;

    jobject surface_global_ref;
    ANativeWindow *window;
    anomaly_config_t anomaly_cfg;    // protected by g_lock
    anomaly_state_t  anomaly_state;  // decode-owned, synchronized via anomaly_lock for resets
    // Person relevance is a bounded sidecar. It is initialized OFF until a
    // model backend and analyzer candidate-identity hook are configured.
    anomaly_person_relevance_scheduler_t person_relevance_scheduler;
    pthread_mutex_t person_relevance_config_lock;
    bool person_relevance_config_lock_ready;
    atomic_int person_relevance_mode;
    pthread_mutex_t anomaly_lock;
    bool anomaly_lock_ready;
    bool anomaly_thermal_paused;
    bool anomaly_runtime_disabled;
    bool anomaly_troubleshooting_debug;
    ad_pause_reason_t anomaly_pause_reason;
    int64_t anomaly_generation_id;
    int64_t anomaly_next_frame_id;
    // Manual render stride — set via nativeSetRenderStride().
    // When render_stride > 1, every (render_stride - 1) out of render_stride
    // non-keyframe packets are dropped *before* decode.  Keyframe (IDR) packets
    // always pass through so the decoder's reference-frame state is never broken.
    // This is an operator-controlled tool for extreme CPU-reduction scenarios
    // (e.g., one focused FFmpeg stream + several background streams on a
    // CPU-constrained device).  It operates entirely in the decode thread and is
    // independent of the adaptive render-control loop, which continues to run
    // normally on whatever frames do reach the render queue.
    // Default: 1 (no skip).
    int render_stride;
    int64_t render_stride_counter; // non-keyframe packet counter; reset on stride change
    // Surface-absent decode gate: set true when no render surface is attached.
    // The decode thread skips avcodec_send_packet while this flag is set, keeping
    // the RTSP connection alive for instant resume.  The render queue is flushed
    // immediately on detach via render_queue_flush_requested.
    volatile bool surface_paused;              // true until render surface is attached
    volatile bool render_queue_flush_requested; // render thread flushes on seeing this; clears after flush
    int64_t last_render_post_at_ms;
    int64_t last_no_surface_log_at_ms;
    int64_t source_render_interval_ms;
    int64_t render_interval_smoothed_ms;
    int64_t next_render_due_ms;
    int64_t render_drop_count;
    int last_logged_render_queue_depth;
    int64_t last_logged_render_drop_count;
    int64_t last_render_queue_log_at_ms;
    int64_t last_render_queue_warn_at_ms;
    int64_t last_render_lag_log_at_ms;
    int64_t last_render_control_log_at_ms;
    bool startup_observation_active;
    int64_t startup_started_at_ms;
    int source_interval_confidence;
    int64_t stall_estimate_ms;
    int64_t target_latency_ms;
    bool stall_active;
    int64_t last_decode_at_ms;
    int64_t last_valid_pts_us;
    int64_t last_source_estimate_update_at_ms;
    int64_t last_source_pts_relock_at_ms;
    int64_t last_gap_at_ms;
    int64_t last_stall_decay_at_ms;
    int64_t proven_gap_ms;
    int64_t last_proven_gap_decay_at_ms;
    int64_t cadence_samples_ms[RENDER_SAMPLE_WINDOW_CAPACITY];
    int cadence_sample_count;
    int cadence_sample_head;
    int64_t gap_samples_ms[RENDER_SAMPLE_WINDOW_CAPACITY];
    int gap_sample_count;
    int gap_sample_head;
    int64_t local_playback_last_pts_us;
    int64_t local_playback_last_render_at_ms;
    int64_t local_playback_first_pts_us;
    int64_t local_playback_first_render_at_ms;
    int64_t local_playback_display_pts_us;
    int64_t local_playback_last_admit_pts_us;
    int64_t local_playback_last_admit_at_ms;
    int64_t local_playback_nominal_interval_ms;
    R2CLocalPlaybackCadence local_playback_render_cadence;
    R2CLocalPlaybackCadence local_playback_admit_cadence;
    int64_t local_playback_pts_repair_count;
    int64_t local_playback_timing_pts_us[LOCAL_PLAYBACK_HISTORY_CAPACITY];
    int64_t local_playback_timing_render_at_ms[LOCAL_PLAYBACK_HISTORY_CAPACITY];
    int local_playback_timing_count;
    int local_playback_timing_next;
    bool local_playback_paused;
    bool local_playback_history_replay_active;
    int64_t local_playback_step_budget;
    int64_t anomaly_process_frame_count;
    int64_t anomaly_annotated_frame_count;
    anomaly_detector_annotation_cadence_snapshot_state_t anomaly_annotation_cadence;
    anomaly_detector_annotation_cadence_snapshot_state_t anomaly_overlay_publication_cadence;
    int64_t anomaly_annotation_last_source_ts_us;
    int64_t anomaly_process_total_us;
    int64_t anomaly_process_max_us;
    int64_t anomaly_process_last_us;
    int64_t anomaly_reg_health_healthy_count;
    int64_t anomaly_reg_health_soft_count;
    int64_t anomaly_reg_health_hard_count;
    int64_t anomaly_reg_health_invalid_count;
    int64_t anomaly_rescan_full_count;
    int64_t anomaly_rescan_partial_count;
    int64_t anomaly_rescan_target_only_count;
    int64_t anomaly_rescan_stride_skip_count;
    int anomaly_last_registration_health;
    int anomaly_last_rescan_mode;
    char latest_anomaly_debug_summary[2048];
    char latest_ad_bridge_debug_summary[256];
    char latest_local_playback_ad_decision[256];
    int64_t reader_stall_started_at_ms;
    int64_t last_reader_stall_log_at_ms;
    int64_t reader_stall_timeout_events;
    int64_t reader_stall_error_events;
    int64_t reader_waiting_since_ms;      // nonzero while decode thread is blocked in av_read_frame
    int64_t reader_reconnecting_since_ms; // nonzero while decode thread is between close and re-open
    int64_t last_reader_wait_event_ms;    // render thread: timestamp of last "reader_wait_long" probe
    bool packet_metadata_keys_logged;
    bool frame_metadata_keys_logged;
    int64_t io_interrupt_deadline_ms;

#if HAVE_FFMPEG
    AVFormatContext *fmt;
    AVCodecContext *codec;
    int video_stream_index;
    AVRational video_time_base;
#if HAVE_SWSCALE
    // Render conversion resources are used only by the render thread.
    pthread_t render_thread;
    pthread_t ad_thread;
    bool render_thread_started;
    bool ad_thread_started;
    bool ad_thread_starting;
    bool render_thread_stop;
    bool ad_thread_stop;
    bool render_sync_ready;
    bool ad_sync_ready;
    pthread_mutex_t render_lock;
    pthread_mutex_t ad_lock;
    pthread_cond_t render_cond;
    pthread_cond_t ad_cond;
    render_queue_slot_t *render_queue;
    render_queue_slot_t *ad_input_queue;
    int render_queue_capacity;
    int render_queue_head;
    int render_queue_depth;
    int ad_input_queue_capacity;
    int ad_input_queue_head;
    int ad_input_queue_depth;
    render_queue_slot_t local_playback_history[LOCAL_PLAYBACK_HISTORY_CAPACITY];
    int local_playback_history_count;
    int local_playback_history_next;
    int local_playback_history_offset;
    struct SwsContext *sws;
    AVFrame *rgba_frame;
    AVFrame *capture_overlay_frame;
    bool capture_frame_valid;
    int64_t capture_source_ts_us;
    uint8_t *rgba_buffer;
    int rgba_buffer_size;
    int rgba_width;
    int rgba_height;
    // Anomaly conversion resources are used by the decode thread.
    struct SwsContext *anomaly_sws;
    struct SwsContext *anomaly_back_sws;
    AVFrame *anomaly_rgba_frame;
    uint8_t *anomaly_rgba_buffer;
    int anomaly_rgba_buffer_size;
    uint8_t *anomaly_clean_rgba;
    size_t anomaly_clean_rgba_capacity;
    int anomaly_rgba_width;
    int anomaly_rgba_height;
    enum AVPixelFormat anomaly_src_fmt;
    // Pilot-approved remote video export. Disabled by default and converted
    // on the decode thread only at the selected cadence.
    volatile bool remote_video_enabled;
    int remote_video_width;
    int remote_video_height;
    double remote_video_fps;
    // Keep the fractional cadence deadline. Rounding 30 fps up to a 34 ms
    // integer interval makes a normal 33/34 ms source miss every other frame.
    double remote_video_next_due_ms;
    struct SwsContext *remote_video_sws;
    uint8_t *remote_video_i420_buffer;
    int remote_video_i420_buffer_size;
    int remote_video_buffer_width;
    int remote_video_buffer_height;
    enum AVPixelFormat remote_video_src_fmt;
    int64_t ad_forwarded_without_analysis_count;
    int64_t ad_full_queue_disable_count;
    int64_t ad_analyzed_rendered_frame_count;
    int64_t ad_bypassed_rendered_frame_count;
    int64_t ad_input_queue_depth_max;
    int64_t ad_input_enqueued_count;
    int64_t ad_worker_dequeued_frame_count;
    int64_t ad_worker_processed_frame_count;
    int64_t ad_worker_skipped_frame_count;
    int64_t ad_worker_annotated_frame_count;
    int64_t ad_worker_overlay_enqueued_count;
    int64_t local_ad_sidecar_offered_count;
    int64_t local_ad_sidecar_enqueued_count;
    int64_t local_ad_sidecar_dropped_count;
    int64_t local_ad_sidecar_completed_count;
    int64_t local_ad_sidecar_late_count;
    int64_t local_ad_startup_preroll_started_at_ms;
    int64_t local_ad_startup_preroll_bypassed_count;
    bool local_ad_startup_preroll_complete;
    int64_t local_ad_cadence_frame_counter;
    int64_t ad_pressure_frame_counter;
    ad_pressure_mode_t ad_pressure_mode;
#endif
#endif
} ffmpeg_session_t;

#if HAVE_FFMPEG && HAVE_SWSCALE
static void append_local_playback_history_locked(ffmpeg_session_t *session,
                                                 AVFrame *decoded,
                                                 int64_t source_ts_us,
                                                 int64_t rendered_at_ms);
static AVFrame *clone_local_playback_history_frame_locked(ffmpeg_session_t *session,
                                                          int history_offset,
                                                          int64_t *out_source_ts_us);
#endif
static void record_local_playback_timing_sample(ffmpeg_session_t *session,
                                                int64_t pts_us,
                                                int64_t rendered_at_ms);
static bool recent_local_playback_timing_span(const ffmpeg_session_t *session,
                                              int64_t *out_first_pts_us,
                                              int64_t *out_last_pts_us,
                                              int64_t *out_first_render_at_ms,
                                              int64_t *out_last_render_at_ms);

static JavaVM *g_vm = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static jlong g_next_session_id = 1;
static ffmpeg_session_t g_sessions[MAX_SESSIONS];
static jclass g_bridge_class = NULL;
static jclass g_person_relevance_coordinator_class = NULL;
static jmethodID g_person_relevance_infer_mid = NULL;
static jclass g_caltopo_client_class = NULL;
static jmethodID g_dispatch_probe_event_mid = NULL;
static jmethodID g_dispatch_remote_video_frame_mid = NULL;
static jmethodID g_ctdebug_mid = NULL;
static jmethodID g_research_log_mid = NULL;
static jmethodID g_ctwarn_mid = NULL;
static jmethodID g_cterror_mid = NULL;
static jmethodID g_register_debug_tag_mid = NULL;
static atomic_uint_fast64_t g_person_relevance_evidence_id = 1u;
static atomic_bool g_dji_sei_hex_dump_enabled = false;
static pthread_mutex_t g_sei_capture_lock = PTHREAD_MUTEX_INITIALIZER;
static R2CSEIDiscovery g_sei_capture;

#if HAVE_FFMPEG && HAVE_SWSCALE
static bool person_relevance_native_backend(
        void *context,
        void *retained_frame,
        const anomaly_person_relevance_model_identity_t *model,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        anomaly_person_relevance_decision_batch_t *decisions_out);
#endif

static inline void trace_begin_section(const char *name) {
    if (name == NULL || name[0] == '\0') return;
    ATrace_beginSection(name);
}

static inline void trace_end_section(void) {
    ATrace_endSection();
}

static inline void trace_set_counter(const char *name, int64_t value) {
    if (name == NULL || name[0] == '\0') return;
    ATrace_setCounter(name, value);
}

static JNIEnv *get_env(bool *did_attach) {
    *did_attach = false;
    if (g_vm == NULL) return NULL;

    JNIEnv *env = NULL;
    int rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if (rc != JNI_EDETACHED) return NULL;

    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return NULL;
    *did_attach = true;
    return env;
}

static void release_env(bool did_attach) {
    if (did_attach && g_vm != NULL) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static void dispatch_probe_event_ex(const char *designator,
                                 const char *event_type,
                                 int64_t session_id,
                                 const char *source_tag,
                                 double confidence,
                                 const char *remote_id,
                                 int64_t source_ts_us,
                                 int64_t render_latency_ms,
                                 double lat,
                                 double lng,
                                 double alt,
                                 double gimbal_pitch,
                                 double camera_yaw,
                                 double heading,
                                 double fov_width,
                                 double fov_height,
                                 const char *dji_attitude_angles) {
    bool did_attach = false;
    JNIEnv *env = get_env(&did_attach);
    if (env == NULL) return;
    if (g_bridge_class == NULL || g_dispatch_probe_event_mid == NULL) {
        release_env(did_attach);
        return;
    }

    jstring j_designator = (*env)->NewStringUTF(env, designator);
    jstring j_event_type = (*env)->NewStringUTF(env, event_type);
    jstring j_source_tag = (*env)->NewStringUTF(env, source_tag != NULL ? source_tag : "");
    jstring j_remote_id = (*env)->NewStringUTF(env, remote_id != NULL ? remote_id : "");
    jstring j_dji_attitude_angles = (*env)->NewStringUTF(
            env,
            dji_attitude_angles != NULL ? dji_attitude_angles : "");
    (*env)->CallStaticVoidMethod(
            env,
            g_bridge_class,
            g_dispatch_probe_event_mid,
            j_designator,
            j_event_type,
            (jlong) session_id,
            j_source_tag,
            (jdouble) confidence,
            j_remote_id,
            (jlong) source_ts_us,
            (jlong) render_latency_ms,
            (jdouble) lat,
            (jdouble) lng,
            (jdouble) alt,
            (jdouble) gimbal_pitch,
            (jdouble) camera_yaw,
            (jdouble) heading,
            (jdouble) fov_width,
            (jdouble) fov_height,
            j_dji_attitude_angles);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, j_designator);
    (*env)->DeleteLocalRef(env, j_event_type);
    (*env)->DeleteLocalRef(env, j_source_tag);
    (*env)->DeleteLocalRef(env, j_remote_id);
    (*env)->DeleteLocalRef(env, j_dji_attitude_angles);

    release_env(did_attach);
}

static void dispatch_probe_event(const char *designator,
                                 const char *event_type,
                                 int64_t session_id,
                                 int64_t source_ts_us,
                                 double lat,
                                 double lng,
                                 double alt,
                                 double gimbal_pitch,
                                 double camera_yaw,
                                 double heading) {
    dispatch_probe_event_ex(
            designator,
            event_type,
            session_id,
            "runtime",
            0.50,
            "",
            source_ts_us,
            0,
            lat,
            lng,
            alt,
            gimbal_pitch,
            camera_yaw,
            heading,
            NAN,
            NAN,
            "");
}

static void dispatch_probe_event_with_latency(const char *designator,
                                              const char *event_type,
                                              int64_t session_id,
                                              int64_t source_ts_us,
                                              int64_t render_latency_ms,
                                              double lat,
                                              double lng,
                                              double alt,
                                              double gimbal_pitch,
                                              double camera_yaw,
                                              double heading) {
    dispatch_probe_event_ex(
            designator,
            event_type,
            session_id,
            "runtime",
            0.50,
            "",
            source_ts_us,
            render_latency_ms,
            lat,
            lng,
            alt,
            gimbal_pitch,
            camera_yaw,
            heading,
            NAN,
            NAN,
            "");
}

#if HAVE_FFMPEG && HAVE_SWSCALE
static bool ensure_remote_video_resources(ffmpeg_session_t *session,
                                          AVFrame *decoded,
                                          int width,
                                          int height) {
    if (session->remote_video_sws != NULL &&
        session->remote_video_i420_buffer != NULL &&
        session->remote_video_buffer_width == width &&
        session->remote_video_buffer_height == height &&
        session->remote_video_src_fmt == decoded->format) {
        return true;
    }
    if (session->remote_video_sws != NULL) {
        sws_freeContext(session->remote_video_sws);
        session->remote_video_sws = NULL;
    }
    if (session->remote_video_i420_buffer != NULL) {
        av_free(session->remote_video_i420_buffer);
        session->remote_video_i420_buffer = NULL;
    }
    session->remote_video_i420_buffer_size = av_image_get_buffer_size(
            AV_PIX_FMT_YUV420P, width, height, 1);
    if (session->remote_video_i420_buffer_size <= 0) return false;
    session->remote_video_i420_buffer = av_malloc(
            (size_t) session->remote_video_i420_buffer_size);
    if (session->remote_video_i420_buffer == NULL) return false;
    session->remote_video_sws = sws_getContext(
            decoded->width,
            decoded->height,
            decoded->format,
            width,
            height,
            AV_PIX_FMT_YUV420P,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);
    if (session->remote_video_sws == NULL) {
        av_free(session->remote_video_i420_buffer);
        session->remote_video_i420_buffer = NULL;
        return false;
    }
    session->remote_video_buffer_width = width;
    session->remote_video_buffer_height = height;
    session->remote_video_src_fmt = decoded->format;
    return true;
}

static void dispatch_remote_video_frame(ffmpeg_session_t *session,
                                        AVFrame *decoded,
                                        int64_t source_ts_us,
                                        int64_t decoded_at_ms) {
    int width = 0;
    int height = 0;
    double fps = 0.0;
    pthread_mutex_lock(&g_lock);
    if (session->remote_video_enabled) {
        fps = session->remote_video_fps;
        double interval_ms = 1000.0 / fmax(1.0, fps);
        double deadline_ms = session->remote_video_next_due_ms;
        // Select the first decoded frame within half an output interval of the
        // accumulated deadline. Advancing the prior deadline instead of
        // scheduling from decoded_at_ms prevents rounding and jitter from
        // reducing 30 fps to 15 fps (and 15 fps to 10 fps).
        if (deadline_ms <= 0.0 ||
            (double) decoded_at_ms + interval_ms * 0.5 >= deadline_ms) {
            width = session->remote_video_width;
            height = session->remote_video_height;
            if (deadline_ms <= 0.0 ||
                (double) decoded_at_ms - deadline_ms > interval_ms * 4.0) {
                deadline_ms = (double) decoded_at_ms + interval_ms;
            } else {
                do {
                    deadline_ms += interval_ms;
                } while (deadline_ms <=
                         (double) decoded_at_ms + interval_ms * 0.5);
            }
            session->remote_video_next_due_ms = deadline_ms;
        }
    }
    pthread_mutex_unlock(&g_lock);
    if (width <= 0 || height <= 0 || fps <= 0.0 ||
        g_bridge_class == NULL || g_dispatch_remote_video_frame_mid == NULL) {
        return;
    }
    if (!ensure_remote_video_resources(session, decoded, width, height)) return;

    uint8_t *planes[4] = {NULL, NULL, NULL, NULL};
    int strides[4] = {0, 0, 0, 0};
    if (av_image_fill_arrays(
            planes,
            strides,
            session->remote_video_i420_buffer,
            AV_PIX_FMT_YUV420P,
            width,
            height,
            1) < 0) {
        return;
    }
    int rows = sws_scale(
            session->remote_video_sws,
            (const uint8_t *const *) decoded->data,
            decoded->linesize,
            0,
            decoded->height,
            planes,
            strides);
    if (rows != height) return;

    bool did_attach = false;
    JNIEnv *env = get_env(&did_attach);
    if (env == NULL) return;
    jbyteArray payload = (*env)->NewByteArray(
            env, session->remote_video_i420_buffer_size);
    if (payload != NULL) {
        (*env)->SetByteArrayRegion(
                env,
                payload,
                0,
                session->remote_video_i420_buffer_size,
                (const jbyte *) session->remote_video_i420_buffer);
        (*env)->CallStaticVoidMethod(
                env,
                g_bridge_class,
                g_dispatch_remote_video_frame_mid,
                (jlong) session->session_id,
                (jint) width,
                (jint) height,
                (jlong) source_ts_us,
                payload);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, payload);
    }
    release_env(did_attach);
}
#endif

static void ct_log_call(jmethodID method_id,
                        int android_prio,
                        const char *tag,
                        const char *fmt,
                        ...) {
    char buffer[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);

    const char *resolved_tag = (tag != NULL) ? tag : TAG;
    bool did_attach = false;
    JNIEnv *env = get_env(&did_attach);
    if (env == NULL || g_caltopo_client_class == NULL || method_id == NULL) {
        __android_log_print(android_prio, resolved_tag, "%s", buffer);
        release_env(did_attach);
        return;
    }

    jstring j_tag = (*env)->NewStringUTF(env, resolved_tag);
    jstring j_msg = (*env)->NewStringUTF(env, buffer);
    (*env)->CallStaticVoidMethod(env, g_caltopo_client_class, method_id, j_tag, j_msg);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        __android_log_print(android_prio, resolved_tag, "%s", buffer);
    }
    (*env)->DeleteLocalRef(env, j_tag);
    (*env)->DeleteLocalRef(env, j_msg);
    release_env(did_attach);
}

static void ct_debug(const char *tag, const char *fmt, ...) {
    char buffer[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    ct_log_call(g_ctdebug_mid, ANDROID_LOG_DEBUG, tag, "%s", buffer);
}

static void sei_capture_log(const char *line) {
    // Explicit research opt-in is independent of ordinary debug filters.
    bool attached=false; JNIEnv *env=get_env(&attached);
    if (env && g_caltopo_client_class && g_research_log_mid) {
        jstring level=(*env)->NewStringUTF(env,"RESEARCH");
        jstring tag=(*env)->NewStringUTF(env,"DjiSeiPayload");
        jstring message=(*env)->NewStringUTF(env,line);
        if (level && tag && message) (*env)->CallStaticVoidMethod(env,g_caltopo_client_class,g_research_log_mid,level,tag,message);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
        if(level) (*env)->DeleteLocalRef(env,level);
        if(tag) (*env)->DeleteLocalRef(env,tag);
        if(message) (*env)->DeleteLocalRef(env,message);
    }
    release_env(attached);
}

static void ct_warn(const char *tag, const char *fmt, ...) {
    char buffer[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    ct_log_call(g_ctwarn_mid, ANDROID_LOG_WARN, tag, "%s", buffer);
}

static void ct_error(const char *tag, const char *fmt, ...) {
    char buffer[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    ct_log_call(g_cterror_mid, ANDROID_LOG_ERROR, tag, "%s", buffer);
}

static int64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000LL + (int64_t) ts.tv_nsec / 1000000LL;
}

static int64_t monotonic_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000LL + (int64_t) ts.tv_nsec / 1000LL;
}

static ffmpeg_session_t *find_session_locked(jlong session_id) {
    for (int i = 0; i < MAX_SESSIONS; i++) {
        if (g_sessions[i].active && g_sessions[i].session_id == session_id) {
            return &g_sessions[i];
        }
    }
    return NULL;
}

static bool session_running(ffmpeg_session_t *session) {
    bool running;
    pthread_mutex_lock(&g_lock);
    running = session->running;
    pthread_mutex_unlock(&g_lock);
    return running;
}

static bool session_stopping(ffmpeg_session_t *session) {
    if (session == NULL) return true;
    bool stopping;
    pthread_mutex_lock(&g_lock);
    stopping = !session->running;
    pthread_mutex_unlock(&g_lock);
    return stopping;
}

static bool frame_looks_queueable(const AVFrame *frame) {
    if (frame == NULL) return false;
    if (frame->width <= 0 || frame->height <= 0) return false;
    if (frame->format == AV_PIX_FMT_NONE) return false;
    if (frame->data[0] == NULL) return false;
    return true;
}

static bool frame_looks_scalable(const AVFrame *frame) {
    if (!frame_looks_queueable(frame)) return false;
    if (frame->linesize[0] == 0) return false;
    return true;
}

static void *ad_thread_main(void *arg);
static void clear_ad_input_queue(ffmpeg_session_t *session);
static const char *color_blob_reject_reason_name(int reason);

static void set_io_interrupt_timeout(ffmpeg_session_t *session, int64_t timeout_ms) {
    if (session == NULL) return;
    pthread_mutex_lock(&g_lock);
    session->io_interrupt_deadline_ms =
            timeout_ms > 0 ? monotonic_ms() + timeout_ms : 0;
    pthread_mutex_unlock(&g_lock);
}

static void clear_io_interrupt_timeout(ffmpeg_session_t *session) {
    if (session == NULL) return;
    pthread_mutex_lock(&g_lock);
    session->io_interrupt_deadline_ms = 0;
    pthread_mutex_unlock(&g_lock);
}

static int ffmpeg_interrupt_cb(void *opaque) {
    ffmpeg_session_t *session = (ffmpeg_session_t *) opaque;
    if (session == NULL) return 1;
    int interrupt = 0;
    pthread_mutex_lock(&g_lock);
    if (!session->running) {
        interrupt = 1;
    } else if (session->io_interrupt_deadline_ms > 0 &&
               monotonic_ms() >= session->io_interrupt_deadline_ms) {
        interrupt = 1;
    }
    pthread_mutex_unlock(&g_lock);
    return interrupt;
}

static ANativeWindow *acquire_window(ffmpeg_session_t *session) {
    ANativeWindow *window = NULL;
    pthread_mutex_lock(&g_lock);
    if (session->window != NULL) {
        window = session->window;
        ANativeWindow_acquire(window);
    }
    pthread_mutex_unlock(&g_lock);
    return window;
}

static bool is_local_file_source(const ffmpeg_session_t *session) {
    if (session == NULL) return false;
    // java.io.File.toURI() can produce file:/path while Android Uri.fromFile()
    // produces file:///path. Treat every file URI as finite playback so it is
    // paced by media timestamps and reaches EOF once instead of reconnecting.
    return strncmp(session->url, "file:", 5) == 0;
}

#if HAVE_FFMPEG && HAVE_SWSCALE
static void ensure_rgba_resources(ffmpeg_session_t *session, int width, int height, enum AVPixelFormat src_fmt) {
    if (session->sws != NULL && session->rgba_width == width && session->rgba_height == height) {
        return;
    }

    session->capture_frame_valid = false;
    av_frame_free(&session->capture_overlay_frame);
    if (session->sws != NULL) {
        sws_freeContext(session->sws);
        session->sws = NULL;
    }
    if (session->rgba_frame != NULL) {
        av_frame_free(&session->rgba_frame);
    }
    if (session->rgba_buffer != NULL) {
        av_free(session->rgba_buffer);
        session->rgba_buffer = NULL;
    }
    session->rgba_frame = av_frame_alloc();
    if (session->rgba_frame == NULL) return;
    session->rgba_frame->width = width;
    session->rgba_frame->height = height;
    session->rgba_frame->format = AV_PIX_FMT_RGBA;

    session->rgba_buffer_size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, width, height, 1);
    if (session->rgba_buffer_size <= 0) return;
    session->rgba_buffer = (uint8_t *) av_malloc((size_t) session->rgba_buffer_size);
    if (session->rgba_buffer == NULL) return;

    av_image_fill_arrays(
            session->rgba_frame->data,
            session->rgba_frame->linesize,
            session->rgba_buffer,
            AV_PIX_FMT_RGBA,
            width,
            height,
            1);

    session->sws = sws_getContext(
            width,
            height,
            src_fmt,
            width,
            height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);

    session->rgba_width = width;
    session->rgba_height = height;
}

static void ensure_anomaly_rgba_resources(ffmpeg_session_t *session,
                                          int width,
                                          int height,
                                          enum AVPixelFormat src_fmt) {
    if (session->anomaly_sws != NULL &&
        session->anomaly_back_sws != NULL &&
        session->anomaly_rgba_width == width &&
        session->anomaly_rgba_height == height &&
        session->anomaly_src_fmt == src_fmt) {
        return;
    }

    if (session->anomaly_sws != NULL) {
        sws_freeContext(session->anomaly_sws);
        session->anomaly_sws = NULL;
    }
    if (session->anomaly_back_sws != NULL) {
        sws_freeContext(session->anomaly_back_sws);
        session->anomaly_back_sws = NULL;
    }
    if (session->anomaly_rgba_frame != NULL) {
        av_frame_free(&session->anomaly_rgba_frame);
    }
    if (session->anomaly_rgba_buffer != NULL) {
        av_free(session->anomaly_rgba_buffer);
        session->anomaly_rgba_buffer = NULL;
    }
    if (session->anomaly_clean_rgba != NULL) {
        free(session->anomaly_clean_rgba);
        session->anomaly_clean_rgba = NULL;
    }

    session->anomaly_rgba_frame = av_frame_alloc();
    if (session->anomaly_rgba_frame == NULL) return;
    session->anomaly_rgba_frame->format = AV_PIX_FMT_RGBA;
    session->anomaly_rgba_frame->width = width;
    session->anomaly_rgba_frame->height = height;

    session->anomaly_rgba_buffer_size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, width, height, 1);
    if (session->anomaly_rgba_buffer_size <= 0) return;
    session->anomaly_rgba_buffer = (uint8_t *) av_malloc((size_t) session->anomaly_rgba_buffer_size);
    if (session->anomaly_rgba_buffer == NULL) return;

    av_image_fill_arrays(
            session->anomaly_rgba_frame->data,
            session->anomaly_rgba_frame->linesize,
            session->anomaly_rgba_buffer,
            AV_PIX_FMT_RGBA,
            width,
            height,
            1);

    session->anomaly_sws = sws_getContext(
            width,
            height,
            src_fmt,
            width,
            height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);
    session->anomaly_back_sws = sws_getContext(
            width,
            height,
            AV_PIX_FMT_RGBA,
            width,
            height,
            src_fmt,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);

    session->anomaly_rgba_width = width;
    session->anomaly_rgba_height = height;
    session->anomaly_src_fmt = src_fmt;

    anomaly_state_reset(&session->anomaly_state);
    anomaly_detector_annotation_cadence_snapshot_state_init(
            &session->anomaly_annotation_cadence);
    anomaly_detector_annotation_cadence_snapshot_state_init(
            &session->anomaly_overlay_publication_cadence);
}

static void cleanup_anomaly_resources(ffmpeg_session_t *session) {
    if (session == NULL) return;

    if (session->anomaly_sws != NULL) {
        sws_freeContext(session->anomaly_sws);
        session->anomaly_sws = NULL;
    }
    if (session->anomaly_back_sws != NULL) {
        sws_freeContext(session->anomaly_back_sws);
        session->anomaly_back_sws = NULL;
    }
    if (session->anomaly_rgba_frame != NULL) {
        av_frame_free(&session->anomaly_rgba_frame);
    }
    if (session->anomaly_rgba_buffer != NULL) {
        av_free(session->anomaly_rgba_buffer);
        session->anomaly_rgba_buffer = NULL;
    }
    if (session->anomaly_lock_ready) {
        pthread_mutex_lock(&session->anomaly_lock);
        anomaly_state_cleanup(&session->anomaly_state);
        pthread_mutex_unlock(&session->anomaly_lock);
    } else {
        anomaly_state_cleanup(&session->anomaly_state);
    }
    session->anomaly_rgba_buffer_size = 0;
    session->anomaly_clean_rgba_capacity = 0;
    session->anomaly_rgba_width = 0;
    session->anomaly_rgba_height = 0;
    session->anomaly_src_fmt = AV_PIX_FMT_NONE;
}

static bool ensure_anomaly_clean_rgba_capacity(ffmpeg_session_t *session,
                                               int width,
                                               int height,
                                               int rgba_stride) {
    if (session == NULL || width <= 0 || height <= 0 || rgba_stride <= 0) {
        return false;
    }
    size_t required = (size_t) rgba_stride * (size_t) height;
    if (required == 0) {
        return false;
    }
    if (session->anomaly_clean_rgba_capacity >= required &&
        session->anomaly_clean_rgba != NULL) {
        return true;
    }
    uint8_t *buffer = (uint8_t *) realloc(session->anomaly_clean_rgba, required);
    if (buffer == NULL) {
        return false;
    }
    session->anomaly_clean_rgba = buffer;
    session->anomaly_clean_rgba_capacity = required;
    return true;
}

static void copy_anomaly_rgba_rows(uint8_t       *dst,
                                   const uint8_t *src,
                                   int            width,
                                   int            height,
                                   int            dst_stride,
                                   int            src_stride) {
    if (dst == NULL || src == NULL || width <= 0 || height <= 0 ||
        dst_stride <= 0 || src_stride <= 0) {
        return;
    }
    size_t row_bytes = (size_t) width * 4u;
    for (int y = 0; y < height; y++) {
        memcpy(dst + ((size_t) y * (size_t) dst_stride),
               src + ((size_t) y * (size_t) src_stride),
               row_bytes);
    }
}

static int64_t anomaly_abs_i64(int64_t value) {
    return value < 0 ? -value : value;
}

static int64_t anomaly_annotation_center_milli(float start_norm, float end_norm) {
    float center = (start_norm + end_norm) * 0.5f;
    if (!isfinite(center)) {
        return -1;
    }
    if (center < 0.0f) {
        center = 0.0f;
    } else if (center > 1.0f) {
        center = 1.0f;
    }
    return (int64_t) (center * 10000.0f + 0.5f);
}

static int64_t anomaly_annotation_center_x_milli(
        const anomaly_detector_annotation_view_t *view) {
    if (view == NULL || view->boxes == NULL || view->box_count <= 0) {
        return -1;
    }
    const anomaly_box_t *box = &view->boxes[0];
    return anomaly_annotation_center_milli(box->left_norm, box->right_norm);
}

static int64_t anomaly_annotation_center_y_milli(
        const anomaly_detector_annotation_view_t *view) {
    if (view == NULL || view->boxes == NULL || view->box_count <= 0) {
        return -1;
    }
    const anomaly_box_t *box = &view->boxes[0];
    return anomaly_annotation_center_milli(box->top_norm, box->bottom_norm);
}

static AVFrame *clone_rgba_frame(const AVFrame *src) {
    if (src == NULL || src->width <= 0 || src->height <= 0) {
        ct_warn(TAG, "clone_rgba_frame invalid source");
        return NULL;
    }
    if (src->format != AV_PIX_FMT_RGBA) {
        ct_warn(TAG,
                "clone_rgba_frame unexpected format=%d width=%d height=%d",
                src->format,
                src->width,
                src->height);
        return NULL;
    }
    if (src->data[0] == NULL || src->linesize[0] <= 0) {
        ct_warn(TAG,
                "clone_rgba_frame missing pixels width=%d height=%d linesize=%d",
                src->width,
                src->height,
                src->linesize[0]);
        return NULL;
    }

    AVFrame *copy = av_frame_alloc();
    if (copy == NULL) {
        ct_warn(TAG, "clone_rgba_frame alloc failed");
        return NULL;
    }
    copy->format = src->format;
    copy->width = src->width;
    copy->height = src->height;
    if (av_frame_get_buffer(copy, 1) < 0) {
        ct_warn(TAG,
                "clone_rgba_frame get_buffer failed width=%d height=%d",
                src->width,
                src->height);
        av_frame_free(&copy);
        return NULL;
    }
    if (av_frame_make_writable(copy) < 0) {
        ct_warn(TAG, "clone_rgba_frame make_writable failed");
        av_frame_free(&copy);
        return NULL;
    }
    av_image_copy(copy->data,
                  copy->linesize,
                  (const uint8_t * const *) src->data,
                  src->linesize,
                  AV_PIX_FMT_RGBA,
                  src->width,
                  src->height);

    copy->pts = src->pts;
    copy->pkt_dts = src->pkt_dts;
    copy->best_effort_timestamp = src->best_effort_timestamp;
    copy->sample_aspect_ratio = src->sample_aspect_ratio;
    copy->color_range = src->color_range;
    copy->color_primaries = src->color_primaries;
    copy->color_trc = src->color_trc;
    copy->colorspace = src->colorspace;

    if (copy->data[0] == NULL || copy->linesize[0] <= 0) {
        ct_warn(TAG, "clone_rgba_frame copied frame missing output pixels");
        av_frame_free(&copy);
        return NULL;
    }
    return copy;
}

static const char *registration_health_name(int value) {
    switch (value) {
        case ANOMALY_REG_HEALTH_INVALID:
            return "invalid";
        case ANOMALY_REG_HEALTH_HARD_DEGRADED:
            return "hard-degraded";
        case ANOMALY_REG_HEALTH_SOFT_DEGRADED:
            return "soft-degraded";
        case ANOMALY_REG_HEALTH_HEALTHY:
            return "healthy";
        case ANOMALY_REG_HEALTH_UNKNOWN:
        default:
            return "unknown";
    }
}

static const char *timing_stage_short_name(anomaly_timing_stage_t stage) {
    switch (stage) {
        case ANOMALY_TIMING_STAGE_REGISTRATION_PREP: return "prep";
        case ANOMALY_TIMING_STAGE_REGISTRATION_SOLVE: return "solve";
        case ANOMALY_TIMING_STAGE_SCAN_PLANNING: return "plan";
        case ANOMALY_TIMING_STAGE_REFRESH_MASK_BUILD: return "mask";
        case ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP: return "sample";
        case ANOMALY_TIMING_STAGE_THERMAL_SCORING: return "thermal";
        case ANOMALY_TIMING_STAGE_COLOR_SCORING: return "color";
        case ANOMALY_TIMING_STAGE_COLOR_SEED_SCORING: return "cseed";
        case ANOMALY_TIMING_STAGE_COLOR_BLOB_EXTRACTION: return "cblob";
        case ANOMALY_TIMING_STAGE_COLOR_CANDIDATE_RANKING: return "crank";
        case ANOMALY_TIMING_STAGE_MOTION_SCORING: return "motion";
        case ANOMALY_TIMING_STAGE_SALIENCY_SCORING: return "persist";
        case ANOMALY_TIMING_STAGE_TARGET_TRACKING: return "track";
        case ANOMALY_TIMING_STAGE_OVERLAY_DRAW: return "draw";
        case ANOMALY_TIMING_STAGE_COUNT:
        default:
            return "unknown";
    }
}

static void append_timing_summary(
        char                         *buffer,
        size_t                        buffer_size,
        const anomaly_debug_timing_t *timing) {
    if (buffer == NULL || buffer_size == 0 || timing == NULL || !timing->compiled) return;
    size_t used = strlen(buffer);
    if (used >= buffer_size) return;
    int written = snprintf(buffer + used,
                           buffer_size - used,
                           " timing[total=%.2fms",
                           (double)timing->total_us / 1000.0);
    if (written < 0 || (size_t)written >= buffer_size - used) return;
    used += (size_t)written;
    for (int stage = 0; stage < ANOMALY_TIMING_STAGE_COUNT && used < buffer_size; stage++) {
        written = snprintf(buffer + used,
                           buffer_size - used,
                           " %s=%.2f",
                           timing_stage_short_name((anomaly_timing_stage_t)stage),
                           (double)timing->stage_us[stage] / 1000.0);
        if (written < 0 || (size_t)written >= buffer_size - used) return;
        used += (size_t)written;
    }
    if (used + 1 < buffer_size) {
        buffer[used++] = ']';
        buffer[used] = '\0';
    }
}

static void trace_set_anomaly_stage_timing_counters(
        const anomaly_debug_timing_t *timing) {
    if (timing == NULL || !timing->compiled) return;

    trace_set_counter("RID2C ad_stage_total_us", timing->total_us);
    for (int stage = 0; stage < ANOMALY_TIMING_STAGE_COUNT; stage++) {
        char counter_name[64];
        int written = snprintf(counter_name,
                               sizeof(counter_name),
                               "RID2C ad_stage_%s_us",
                               timing_stage_short_name((anomaly_timing_stage_t)stage));
        if (written <= 0 || (size_t)written >= sizeof(counter_name)) continue;
        trace_set_counter(counter_name, timing->stage_us[stage]);
    }
}

static const char *rescan_mode_name(int value) {
    switch (value) {
        case ANOMALY_RESCAN_MODE_FULL:
            return "full";
        case ANOMALY_RESCAN_MODE_PARTIAL:
            return "partial";
        case ANOMALY_RESCAN_MODE_TARGET_ONLY:
            return "target-only";
        case ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP:
            return "appearance-stride-skip";
        case ANOMALY_RESCAN_MODE_UNSET:
        default:
            return "unset";
    }
}

static void format_scan_reason_flags(uint32_t flags, char *buffer, size_t buffer_size) {
    if (buffer == NULL || buffer_size == 0) return;
    buffer[0] = '\0';
    if (flags == 0u) {
        snprintf(buffer, buffer_size, "none");
        return;
    }
    const struct {
        uint32_t flag;
        const char *name;
    } entries[] = {
        { ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH, "no-appearance-refresh" },
        { ANOMALY_SCAN_REASON_NO_SAMPLES, "no-samples" },
        { ANOMALY_SCAN_REASON_PREV_STATE_INVALID, "prev-state-invalid" },
        { ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY, "scene-discontinuity" },
        { ANOMALY_SCAN_REASON_REG_INVALID, "reg-invalid" },
        { ANOMALY_SCAN_REASON_REG_HARD_DEGRADED, "reg-hard-degraded" },
        { ANOMALY_SCAN_REASON_WARP_LOW, "warp-low" },
        { ANOMALY_SCAN_REASON_NEW_EXPOSED_HIGH, "new-exposed-high" },
        { ANOMALY_SCAN_REASON_STALE_HIGH, "stale-high" },
        { ANOMALY_SCAN_REASON_SAMPLE_STEP_MISMATCH, "sample-step-mismatch" },
        { ANOMALY_SCAN_REASON_TARGET_ONLY_ELIGIBLE, "target-only-eligible" },
        { ANOMALY_SCAN_REASON_PARTIAL_ELIGIBLE, "partial-eligible" },
        { ANOMALY_SCAN_REASON_MASK_BUILD_FAILED, "mask-build-failed" },
        { ANOMALY_SCAN_REASON_MASK_EMPTY, "mask-empty" },
        { ANOMALY_SCAN_REASON_MASK_TOO_BROAD, "mask-too-broad" },
        { ANOMALY_SCAN_REASON_PERIODIC_FULL_REFRESH, "periodic-full-refresh" },
        { ANOMALY_SCAN_REASON_TARGET_COLOR_ACQUIRE, "target-color-acquisition" },
    };
    size_t offset = 0;
    for (size_t i = 0; i < sizeof(entries) / sizeof(entries[0]); i++) {
        if ((flags & entries[i].flag) == 0u) continue;
        int written = snprintf(buffer + offset,
                               buffer_size - offset,
                               "%s%s",
                               offset > 0 ? "|" : "",
                               entries[i].name);
        if (written < 0) break;
        if ((size_t)written >= buffer_size - offset) {
            offset = buffer_size - 1;
            break;
        }
        offset += (size_t)written;
    }
}

static const char *anomaly_algorithm_name(int value) {
    switch (value) {
        case ANOMALY_ALGO_COLOR:
            return "color";
        case ANOMALY_ALGO_THERMAL:
            return "thermal";
        case ANOMALY_ALGO_MOTION:
            return "motion";
        case ANOMALY_ALGO_PERSIST:
            return "persist";
        case ANOMALY_ALGO_MOTION_TOLERANCE:
            return "motion-tolerance";
        default:
            return "unknown";
    }
}

static void format_target_track_summary(
        const anomaly_state_t *state,
        char                  *buffer,
        size_t                 buffer_size) {
    if (buffer == NULL || buffer_size == 0) return;
    buffer[0] = '\0';
    if (state == NULL) return;

    const anomaly_target_track_t *best = NULL;
    float best_score = -1000.0f;
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        const anomaly_target_track_t *track = &state->target_tracks[i];
        if (!track->active) continue;
        float score = track->confidence +
                      (track->publish_confirmed ? 10.0f : 0.0f) +
                      (track->algorithm == ANOMALY_ALGO_COLOR ? 5.0f : 0.0f) +
                      0.01f * (float)track->hit_count -
                      0.01f * (float)track->miss_count;
        if (best == NULL || score > best_score) {
            best = track;
            best_score = score;
        }
    }

    if (best == NULL) {
        snprintf(buffer, buffer_size, " track[none]");
        return;
    }

    const float mean_movement_confidence =
            best->movement_valid_frames > 0
                    ? best->movement_confidence_sum / (float)best->movement_valid_frames
                    : 0.0f;
    snprintf(buffer,
             buffer_size,
             " track[id=%d alg=%s pub=%d fresh=%d hits=%d miss=%d hold=%d conf=%.2f center=%.3f,%.3f pred[v=%d invFail=%d local=%d before=%.3f,%.3f reg=%.3f,%.3f after=%.3f,%.3f move[win=%d valid=%d par=%d ind=%d conf=%.2f dx=%.1f dy=%.1f resid=%.1f indScore=%.2f]]",
             best->id,
             anomaly_algorithm_name(best->algorithm),
             best->publish_confirmed ? 1 : 0,
             best->fresh_observation ? 1 : 0,
             best->hit_count,
             best->miss_count,
             best->hold_count,
             best->confidence,
             best->center_x_norm,
             best->center_y_norm,
             best->last_prediction_valid ? 1 : 0,
             best->last_prediction_inverse_failed ? 1 : 0,
             best->last_prediction_local_residual_applied ? 1 : 0,
             best->last_prediction_before_x_norm,
             best->last_prediction_before_y_norm,
             best->last_prediction_registration_x_norm,
             best->last_prediction_registration_y_norm,
             best->last_prediction_after_x_norm,
             best->last_prediction_after_y_norm,
             best->movement_window_frames,
             best->movement_valid_frames,
             best->movement_parallax_frames,
             best->movement_independent_frames,
             mean_movement_confidence,
             best->last_movement_dx_px,
             best->last_movement_dy_px,
             best->last_movement_residual_px,
             best->last_movement_independent_score);
}

static const char *registration_invalid_reason_name(int value) {
    switch (value) {
        case ANOMALY_REG_INVALID_REASON_NONE:
            return "none";
        case ANOMALY_REG_INVALID_REASON_DEBUG_INPUT_UNAVAILABLE:
            return "debug-input-unavailable";
        case ANOMALY_REG_INVALID_REASON_GMV_TOO_FEW_ANCHORS:
            return "gmv-too-few-anchors";
        case ANOMALY_REG_INVALID_REASON_GMV_FIT_INVALID:
            return "gmv-fit-invalid";
        case ANOMALY_REG_INVALID_REASON_GMV_RESIDUAL_TOO_HIGH:
            return "gmv-residual-too-high";
        case ANOMALY_REG_INVALID_REASON_GMV_MOTION_TOO_LARGE:
            return "gmv-motion-too-large";
        case ANOMALY_REG_INVALID_REASON_GMV_SCALE_OUT_OF_RANGE:
            return "gmv-scale-out-of-range";
        case ANOMALY_REG_INVALID_REASON_AFFINE_ROI_DEGENERATE:
            return "affine-roi-degenerate";
        case ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_CORNERS:
            return "affine-too-few-corners";
        case ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_MATCHES:
            return "affine-too-few-matches";
        case ANOMALY_REG_INVALID_REASON_AFFINE_FIT_FAILED:
            return "affine-fit-failed";
        case ANOMALY_REG_INVALID_REASON_AFFINE_RESIDUAL_TOO_HIGH:
            return "affine-residual-too-high";
        case ANOMALY_REG_INVALID_REASON_AFFINE_MOTION_TOO_LARGE:
            return "affine-motion-too-large";
        case ANOMALY_REG_INVALID_REASON_AFFINE_SCALE_OUT_OF_RANGE:
            return "affine-scale-out-of-range";
        case ANOMALY_REG_INVALID_REASON_AFFINE_NEGATIVE_DET:
            return "affine-negative-det";
        default:
            return "unknown";
    }
}

static bool analyze_rgba_frame_locked(ffmpeg_session_t *session,
                                      const anomaly_config_t *cfg_override,
                                      int width,
                                      int height,
                                      uint8_t *rgba,
                                      int rgba_stride,
                                      int64_t source_ts_us,
                                      int64_t annotation_frame_ordinal,
                                      void *person_source_frame,
                                      uint64_t person_generation,
                                      uint64_t person_frame_sequence,
                                      anomaly_person_scheduler_pressure_t person_pressure,
                                      bool allow_person_offer) {
    anomaly_result_t result;
    memset(&result, 0, sizeof(result));
    // Callers snapshot configuration before acquiring anomaly_lock.  Falling
    // back to g_lock here would invert that hierarchy.
    anomaly_config_t cfg = cfg_override != NULL
            ? *cfg_override
            : (anomaly_config_t) {0};
    int64_t started_at_us = monotonic_us();
    trace_begin_section("RID2C anomaly_process_frame");
    bool use_raw_overlay_draw =
            cfg.show_hot_overlay ||
            cfg.show_candidate_blobs;
    bool clean_rgba_available =
            !use_raw_overlay_draw &&
            ensure_anomaly_clean_rgba_capacity(session, width, height, rgba_stride);
    bool use_stable_overlay_draw = !use_raw_overlay_draw && clean_rgba_available;
    if (clean_rgba_available) {
        copy_anomaly_rgba_rows(
                session->anomaly_clean_rgba,
                rgba,
                width,
                height,
                rgba_stride,
                rgba_stride);
    }
    int raw_box_count = anomaly_process_frame(&session->anomaly_state, &cfg,
                                              rgba, rgba_stride,
                                              width, height,
                                              source_ts_us, &result);
    trace_end_section();
    if (allow_person_offer &&
        atomic_load_explicit(
                &session->person_relevance_mode, memory_order_relaxed) !=
                ANOMALY_PERSON_RELEVANCE_OFF &&
        session->person_relevance_config_lock_ready &&
        pthread_mutex_trylock(&session->person_relevance_config_lock) == 0) {
        anomaly_person_scheduler_snapshot_t person_snapshot;
        size_t person_candidate_count = anomaly_person_scheduler_snapshot_from_boxes(
                person_generation,
                person_frame_sequence,
                source_ts_us,
                &session->anomaly_state,
                &cfg,
                result.boxes,
                (size_t)(raw_box_count > 0 ? raw_box_count : 0),
                &person_snapshot);
        if (person_candidate_count > 0u) {
            (void)anomaly_person_scheduler_consume_latest_boxes(
                    &session->person_relevance_scheduler,
                    &person_snapshot,
                    1000000,
                    result.boxes,
                    (size_t)raw_box_count);
            (void)anomaly_person_scheduler_offer(
                    &session->person_relevance_scheduler,
                    person_pressure,
                    person_source_frame,
                    &person_snapshot);
        }
        pthread_mutex_unlock(&session->person_relevance_config_lock);
    }
    anomaly_detector_annotation_view_t raw_annotations =
            anomaly_detector_result_annotations(&result);
    const int stable_overlay_window_frames =
        anomaly_detector_sparse_overlay_window_frames(LOCAL_AD_TARGET_EVAL_INTERVAL_FRAMES);
    int64_t stable_annotation_ordinal =
            annotation_frame_ordinal >= 0
                    ? annotation_frame_ordinal
                    : session->anomaly_process_frame_count;
    anomaly_detector_annotation_view_t stable_tracking_annotations =
            !use_stable_overlay_draw
                    ? raw_annotations
                    : anomaly_detector_result_apply_annotation_stability(
                            &result,
                            &session->anomaly_annotation_cadence,
                            stable_annotation_ordinal,
                            stable_overlay_window_frames);
    anomaly_detector_annotation_view_t stable_annotations =
            !use_stable_overlay_draw
                    ? stable_tracking_annotations
                    : anomaly_detector_annotation_publish_on_elapsed_cadence(
                            stable_tracking_annotations,
                            &session->anomaly_overlay_publication_cadence,
                            stable_annotation_ordinal,
                            stable_overlay_window_frames);
    if (source_ts_us > 0) {
        session->anomaly_annotation_last_source_ts_us = source_ts_us;
    }
    if (use_stable_overlay_draw) {
        copy_anomaly_rgba_rows(
                rgba,
                session->anomaly_clean_rgba,
                width,
                height,
                rgba_stride,
                rgba_stride);
    }
    if (use_stable_overlay_draw && stable_annotations.box_count > 0) {
        anomaly_debug_draw_boxes_rgba(
                rgba,
                rgba_stride,
                width,
                height,
                stable_annotations.boxes,
                stable_annotations.box_count);
    }
    bool annotated = stable_annotations.box_count > 0;
    const int64_t raw_center_x_milli =
            anomaly_annotation_center_x_milli(&raw_annotations);
    const int64_t raw_center_y_milli =
            anomaly_annotation_center_y_milli(&raw_annotations);
    const int64_t stable_center_x_milli =
            anomaly_annotation_center_x_milli(&stable_annotations);
    const int64_t stable_center_y_milli =
            anomaly_annotation_center_y_milli(&stable_annotations);
    const int64_t center_delta_milli =
            raw_center_x_milli >= 0 &&
            raw_center_y_milli >= 0 &&
            stable_center_x_milli >= 0 &&
            stable_center_y_milli >= 0
                    ? anomaly_abs_i64(raw_center_x_milli - stable_center_x_milli) +
                      anomaly_abs_i64(raw_center_y_milli - stable_center_y_milli)
                    : -1;
    trace_set_counter("RID2C ad_raw_box_count", raw_box_count);
    trace_set_counter("RID2C ad_stable_box_count", stable_annotations.box_count);
    trace_set_counter("RID2C ad_annotated", annotated ? 1 : 0);
    trace_set_counter("RID2C ad_stability_window_frames", stable_overlay_window_frames);
    trace_set_counter("RID2C ad_stability_ordinal", stable_annotation_ordinal);
    trace_set_counter("RID2C ad_raw_center_x_milli", raw_center_x_milli);
    trace_set_counter("RID2C ad_raw_center_y_milli", raw_center_y_milli);
    trace_set_counter("RID2C ad_stable_center_x_milli", stable_center_x_milli);
    trace_set_counter("RID2C ad_stable_center_y_milli", stable_center_y_milli);
    trace_set_counter("RID2C ad_center_delta_milli", center_delta_milli);
    int64_t elapsed_us = monotonic_us() - started_at_us;
    session->anomaly_process_frame_count += 1;
    session->anomaly_process_total_us += elapsed_us;
    session->anomaly_process_last_us = elapsed_us;
    if (elapsed_us > session->anomaly_process_max_us) {
        session->anomaly_process_max_us = elapsed_us;
    }
    if (annotated) {
        if (session->anomaly_annotated_frame_count == 0) {
            const anomaly_box_t *first_box = &stable_annotations.boxes[0];
            ct_debug(
                    "FfmpegProbeService",
                    "First anomaly ROI designator=%s sessionId=%lld mediaTs=%.3fs analyzedFrame=%lld "
                    "algorithm=%d box=[%.3f,%.3f,%.3f,%.3f] weight=%.2f",
                    session->designator,
                    (long long) session->session_id,
                    source_ts_us > 0 ? ((double) source_ts_us / 1000000.0) : 0.0,
                    (long long) session->anomaly_process_frame_count + 1,
                    first_box->algorithm,
                    first_box->left_norm,
                    first_box->top_norm,
                    first_box->right_norm,
                    first_box->bottom_norm,
                    first_box->weight);
        }
        session->anomaly_annotated_frame_count += 1;
    }
    session->anomaly_last_registration_health = result.registration_health;
    session->anomaly_last_rescan_mode = result.rescan_mode;
    switch (result.registration_health) {
        case ANOMALY_REG_HEALTH_HEALTHY:
            session->anomaly_reg_health_healthy_count += 1;
            break;
        case ANOMALY_REG_HEALTH_SOFT_DEGRADED:
            session->anomaly_reg_health_soft_count += 1;
            break;
        case ANOMALY_REG_HEALTH_HARD_DEGRADED:
            session->anomaly_reg_health_hard_count += 1;
            break;
        case ANOMALY_REG_HEALTH_INVALID:
            session->anomaly_reg_health_invalid_count += 1;
            break;
        default:
            break;
    }
    switch (result.rescan_mode) {
        case ANOMALY_RESCAN_MODE_FULL:
            session->anomaly_rescan_full_count += 1;
            break;
        case ANOMALY_RESCAN_MODE_PARTIAL:
            session->anomaly_rescan_partial_count += 1;
            break;
        case ANOMALY_RESCAN_MODE_TARGET_ONLY:
            session->anomaly_rescan_target_only_count += 1;
            break;
        case ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP:
            session->anomaly_rescan_stride_skip_count += 1;
            break;
        default:
            break;
    }
    trace_set_counter("RID2C anomaly_us", elapsed_us);
    trace_set_anomaly_stage_timing_counters(&result.timing);
    trace_set_counter("RID2C reg_health", result.registration_health);
    trace_set_counter("RID2C rescan_mode", result.rescan_mode);
    char scan_reason_summary[192];
    format_scan_reason_flags(result.scan_plan.reason_flags,
                             scan_reason_summary,
                             sizeof(scan_reason_summary));
    char target_track_summary[768];
    format_target_track_summary(&session->anomaly_state,
                                target_track_summary,
                                sizeof(target_track_summary));
    if (result.motion_debug.valid) {
        snprintf(
                session->latest_anomaly_debug_summary,
                sizeof(session->latest_anomaly_debug_summary),
                "reg=%s mode=%s plan[w=%.2f new=%.2f stale=%.2f mask=%.2f reasons=%s] regdbg[why=%s anchors=%d matches=%d resid=%.4f rstd=%.4f rmax=%.4f dxstd=%.4f dystd=%.4f qspread=%.4f det=%.3f scale=%.3f..%.3f] motion raw=%.2f load=%.2f broad=%.2f zoom=%.2f area=%.3f span=%.3f fill=%.2f support=%.2f tex=%.2f struct=%.2f persist=%.2f cand=(%.2f,%.2f)",
                registration_health_name(result.registration_health),
                rescan_mode_name(result.rescan_mode),
                result.scan_plan.warped_valid_fraction,
                result.scan_plan.newly_exposed_fraction,
                result.scan_plan.stale_fraction,
                result.scan_plan.refresh_mask_selected_fraction,
                scan_reason_summary,
                registration_invalid_reason_name(result.gmv_debug.invalid_reason),
                result.gmv_debug.anchor_count,
                result.gmv_debug.tracked_match_count,
                result.gmv_debug.fit_mean_residual,
                result.gmv_debug.fit_anchor_residual_std,
                result.gmv_debug.fit_anchor_residual_max,
                result.gmv_debug.fit_motion_dx_std,
                result.gmv_debug.fit_motion_dy_std,
                result.gmv_debug.fit_quadrant_residual_spread,
                result.gmv_debug.fit_det,
                result.gmv_debug.fit_min_scale,
                result.gmv_debug.fit_max_scale,
                result.motion_debug.raw_score,
                result.motion_debug.global_motion_load,
                result.motion_debug.broad_motion_scale,
                result.motion_debug.zoom_motion_scale,
                result.motion_debug.winner_component_area_frac,
                result.motion_debug.winner_component_span_frac,
                result.motion_debug.winner_component_fill_ratio,
                result.motion_debug.winner_support_scale,
                result.motion_debug.winner_texture_scale,
                result.motion_debug.winner_structure_scale,
                result.motion_debug.winner_persistence_scale,
                result.motion_debug.raw_x_norm,
                result.motion_debug.raw_y_norm);
    } else if (result.saliency_debug.raw_candidate_valid) {
        snprintf(
                session->latest_anomaly_debug_summary,
                sizeof(session->latest_anomaly_debug_summary),
                "reg=%s mode=%s plan[w=%.2f new=%.2f stale=%.2f mask=%.2f reasons=%s] regdbg[why=%s anchors=%d matches=%d resid=%.4f rstd=%.4f rmax=%.4f dxstd=%.4f dystd=%.4f qspread=%.4f det=%.3f scale=%.3f..%.3f] saliency raw=%.2f bg=%d tracked=%.2f hits=%d switch=%d cand=(%.2f,%.2f)",
                registration_health_name(result.registration_health),
                rescan_mode_name(result.rescan_mode),
                result.scan_plan.warped_valid_fraction,
                result.scan_plan.newly_exposed_fraction,
                result.scan_plan.stale_fraction,
                result.scan_plan.refresh_mask_selected_fraction,
                scan_reason_summary,
                registration_invalid_reason_name(result.gmv_debug.invalid_reason),
                result.gmv_debug.anchor_count,
                result.gmv_debug.tracked_match_count,
                result.gmv_debug.fit_mean_residual,
                result.gmv_debug.fit_anchor_residual_std,
                result.gmv_debug.fit_anchor_residual_max,
                result.gmv_debug.fit_motion_dx_std,
                result.gmv_debug.fit_motion_dy_std,
                result.gmv_debug.fit_quadrant_residual_spread,
                result.gmv_debug.fit_det,
                result.gmv_debug.fit_min_scale,
                result.gmv_debug.fit_max_scale,
                result.saliency_debug.raw_score,
                result.saliency_debug.bg_ready ? 1 : 0,
                result.saliency_debug.tracked_score_pre,
                result.saliency_debug.acc_post_hits,
                result.saliency_debug.switch_suppressed ? 1 : 0,
                result.saliency_debug.raw_x_norm,
                result.saliency_debug.raw_y_norm);
    } else if ((cfg.algorithm_mask & ANOMALY_ALGO_COLOR) != 0 ||
               result.color_debug.raw_candidate_valid ||
               result.color_debug.target.enabled ||
               result.box_count > 0) {
        snprintf(
                session->latest_anomaly_debug_summary,
                sizeof(session->latest_anomaly_debug_summary),
                "reg=%s mode=%s plan[w=%.2f new=%.2f stale=%.2f mask=%.2f reasons=%s] regdbg[scale=%.4f theta=%.2f tx=%.3f ty=%.3f resid=%.4f min=%.4f max=%.4f] color raw=%.2f cand=%d winner=%d fresh=%d carried=%d hist[v=%d nz=%d curMax=%.0f recMax=%.0f reset=%d recovery=%d scale=%.2f] seeds[rare=%d support=%d peak=%.2f top=%.2f@%d,%d lk=%d cur=%.0f rec=%.0f rar=%.4f ratio=%.2f src=%d coarse=%d over=%d dense=%d] reject[a=%d r=%d m=%d q=%d] target[en=%d valid=%d inside=%d refreshSkip=%d sampled=%d carried=%d pre=%.2f support=%.2f eligible=%d stage=%d ring=%.2f/%.2f n=%d coh=%d/%d multi=%d] boxes=%d%s",
                registration_health_name(result.registration_health),
                rescan_mode_name(result.rescan_mode),
                result.scan_plan.warped_valid_fraction,
                result.scan_plan.newly_exposed_fraction,
                result.scan_plan.stale_fraction,
                result.scan_plan.refresh_mask_selected_fraction,
                scan_reason_summary,
                result.gmv_debug.fit_scale,
                result.gmv_debug.fit_theta_deg,
                result.gmv_debug.fit_tx,
                result.gmv_debug.fit_ty,
                result.gmv_debug.fit_mean_residual,
                result.gmv_debug.fit_min_scale,
                result.gmv_debug.fit_max_scale,
                result.color_debug.raw_score,
                result.color_debug.candidate_count,
                result.color_debug.winning_candidate_index,
                result.color_debug.fresh_sample_count,
                result.color_debug.carried_sample_count,
                result.color_debug.histogram_valid_sample_count,
                result.color_debug.nonzero_histogram_bins,
                result.color_debug.max_histogram_current_count,
                result.color_debug.max_histogram_recent_count,
                result.color_debug.history_reset_applied ? 1 : 0,
                result.color_debug.history_recovery_frames_remaining,
                result.color_debug.history_recent_scale,
                result.color_debug.rarity_seed_count,
                result.color_debug.support_seed_count,
                result.color_debug.support_peak_score,
                result.color_debug.strongest_seed.score,
                result.color_debug.strongest_seed.sample_x,
                result.color_debug.strongest_seed.sample_y,
                result.color_debug.strongest_seed.local_support_count,
                result.color_debug.strongest_seed.hist_current_count,
                result.color_debug.strongest_seed.hist_recent_count,
                result.color_debug.strongest_seed.hist_rarity_score,
                result.color_debug.fresh_distinctness_ratio,
                result.color_debug.adaptive_source_coarse_count,
                result.color_debug.coarse_component_count,
                result.color_debug.coarse_oversized_count,
                result.color_debug.dense_verify_component_count,
                result.color_debug.blob_reject_area_count,
                result.color_debug.blob_reject_ring_count,
                result.color_debug.blob_reject_support_mass_count,
                result.color_debug.blob_reject_quality_count,
                result.color_debug.target.enabled ? 1 : 0,
                result.color_debug.target.valid ? 1 : 0,
                result.color_debug.target.inside_scan_zone ? 1 : 0,
                result.color_debug.target.refresh_skipped ? 1 : 0,
                result.color_debug.target.sampled_this_frame ? 1 : 0,
                result.color_debug.target.carried_from_history ? 1 : 0,
                result.color_debug.target.pre_support_score,
                result.color_debug.target.support_score,
                result.color_debug.target.support_seed_eligible ? 1 : 0,
                result.color_debug.target.stage,
                result.color_debug.target.ring_chroma_contrast,
                result.color_debug.target.ring_luma_contrast,
                result.color_debug.target.ring_neighbor_count,
                result.color_debug.target.coherent_patch_cell_count,
                result.color_debug.target.coherent_patch_fresh_cell_count,
                result.color_debug.target.coherent_patch_multicell ? 1 : 0,
                result.box_count,
                target_track_summary);
    } else if (result.gmv_debug.valid) {
        snprintf(
                session->latest_anomaly_debug_summary,
                sizeof(session->latest_anomaly_debug_summary),
                "reg=%s mode=%s plan[w=%.2f new=%.2f stale=%.2f mask=%.2f reasons=%s] regdbg[why=%s anchors=%d matches=%d resid=%.4f rstd=%.4f rmax=%.4f dxstd=%.4f dystd=%.4f qspread=%.4f det=%.3f scale=%.3f..%.3f] gmv scale=%.3f theta=%.1f resid=%.3f anchors=%d discontinuity=%d refresh=%d",
                registration_health_name(result.registration_health),
                rescan_mode_name(result.rescan_mode),
                result.scan_plan.warped_valid_fraction,
                result.scan_plan.newly_exposed_fraction,
                result.scan_plan.stale_fraction,
                result.scan_plan.refresh_mask_selected_fraction,
                scan_reason_summary,
                registration_invalid_reason_name(result.gmv_debug.invalid_reason),
                result.gmv_debug.anchor_count,
                result.gmv_debug.tracked_match_count,
                result.gmv_debug.fit_mean_residual,
                result.gmv_debug.fit_anchor_residual_std,
                result.gmv_debug.fit_anchor_residual_max,
                result.gmv_debug.fit_motion_dx_std,
                result.gmv_debug.fit_motion_dy_std,
                result.gmv_debug.fit_quadrant_residual_spread,
                result.gmv_debug.fit_det,
                result.gmv_debug.fit_min_scale,
                result.gmv_debug.fit_max_scale,
                result.gmv_debug.fit_scale,
                result.gmv_debug.fit_theta_deg,
                result.gmv_debug.fit_mean_residual,
                result.gmv_debug.anchor_count,
                result.gmv_debug.scene_discontinuity ? 1 : 0,
                result.appearance_refresh_ran_this_frame ? 1 : 0);
    } else if (session->latest_anomaly_debug_summary[0] == '\0') {
        snprintf(session->latest_anomaly_debug_summary, sizeof(session->latest_anomaly_debug_summary), "debug unavailable");
    }
#if ANOMALY_DEBUG_TIMING
    append_timing_summary(
            session->latest_anomaly_debug_summary,
            sizeof(session->latest_anomaly_debug_summary),
            &result.timing);
    if (session->anomaly_process_frame_count > 0 &&
        (session->anomaly_process_frame_count % 30) == 0) {
        ct_debug(TAG,
                 "anomaly timing id=%lld designator=%s frame=%lld %s",
                 (long long) session->session_id,
                 session->designator,
                 (long long) session->anomaly_process_frame_count,
                 session->latest_anomaly_debug_summary);
        if (target_track_summary[0] != '\0') {
            ct_debug(TAG,
                     "anomaly target track id=%lld designator=%s frame=%lld%s",
                     (long long) session->session_id,
                     session->designator,
                     (long long) session->anomaly_process_frame_count,
                     target_track_summary);
        }
    }
#endif
    bool log_local_playback_summary =
            is_local_file_source(session) &&
            cfg.enabled &&
            cfg.algorithm_mask != 0 &&
            (session->anomaly_process_frame_count <= 3 ||
             annotated ||
             (session->anomaly_process_frame_count % 120) == 0);
    if ((session->anomaly_troubleshooting_debug || log_local_playback_summary) &&
        (annotated ||
         session->anomaly_process_frame_count <= 3 ||
         (session->anomaly_process_frame_count % 60) == 0 ||
         log_local_playback_summary)) {
        const anomaly_box_t *first_box =
                stable_annotations.box_count > 0 ? &stable_annotations.boxes[0] : NULL;
        ct_debug(TAG,
                 "anomaly frame result id=%lld designator=%s frame=%lld ts=%.3fs annotated=%d boxCount=%d stableBoxCount=%d rawBoxCount=%d bestColor=%.2f bestThermal=%.2f bestMotion=%.2f overlay0=[algo=%d l=%.3f t=%.3f r=%.3f b=%.3f weight=%.2f] summary=%s",
                 (long long) session->session_id,
                 session->designator,
                 (long long) session->anomaly_process_frame_count,
                 source_ts_us > 0 ? ((double) source_ts_us / 1000000.0) : 0.0,
                 annotated ? 1 : 0,
                 result.box_count,
                 stable_annotations.box_count,
                 raw_box_count,
                 result.color_debug.raw_score,
                 result.thermal_debug.raw_score,
                 result.motion_debug.raw_score,
                 first_box != NULL ? first_box->algorithm : 0,
                 first_box != NULL ? first_box->left_norm : 0.0f,
                 first_box != NULL ? first_box->top_norm : 0.0f,
                 first_box != NULL ? first_box->right_norm : 0.0f,
                 first_box != NULL ? first_box->bottom_norm : 0.0f,
                 first_box != NULL ? first_box->weight : 0.0f,
                 session->latest_anomaly_debug_summary[0] != '\0'
                         ? session->latest_anomaly_debug_summary
                         : "debug unavailable");
    }
    if ((session->anomaly_troubleshooting_debug || log_local_playback_summary) &&
        cfg.enabled &&
        (cfg.algorithm_mask & ANOMALY_ALGO_COLOR) != 0 &&
        result.color_debug.candidate_count == 0 &&
        (result.color_debug.rarity_seed_count > 0 || result.color_debug.support_seed_count > 0)) {
        ct_debug(TAG,
                 "color dropout id=%lld designator=%s frame=%lld ts=%.3fs mode=%s plan[w=%.2f new=%.2f stale=%.2f mask=%.2f reasons=%s] regdbg[scale=%.4f theta=%.2f tx=%.3f ty=%.3f resid=%.4f] seeds[rare=%d support=%d peak=%.2f top=%.2f@%d,%d cur=%.0f rec=%.0f rar=%.4f lk=%d] blobs[targetSpanPx=%.1f targetCells=%d maxArea=%d examined=%d strongestReject=%s peak=%.2f area=%.0f span=%.1f ring=%.2f mass=%.2f quality=%.2f] reject[a=%d r=%d m=%d q=%d] hist[nz=%d curMax=%.0f recMax=%.0f recovery=%d scale=%.2f]",
                 (long long) session->session_id,
                 session->designator,
                 (long long) session->anomaly_process_frame_count,
                 source_ts_us > 0 ? ((double) source_ts_us / 1000000.0) : 0.0,
                 rescan_mode_name(result.rescan_mode),
                 result.scan_plan.warped_valid_fraction,
                 result.scan_plan.newly_exposed_fraction,
                 result.scan_plan.stale_fraction,
                 result.scan_plan.refresh_mask_selected_fraction,
                 scan_reason_summary,
                 result.gmv_debug.fit_scale,
                 result.gmv_debug.fit_theta_deg,
                 result.gmv_debug.fit_tx,
                 result.gmv_debug.fit_ty,
                 result.gmv_debug.fit_mean_residual,
                 result.color_debug.rarity_seed_count,
                 result.color_debug.support_seed_count,
                 result.color_debug.support_peak_score,
                 result.color_debug.strongest_seed.score,
                 result.color_debug.strongest_seed.sample_x,
                 result.color_debug.strongest_seed.sample_y,
                 result.color_debug.strongest_seed.hist_current_count,
                 result.color_debug.strongest_seed.hist_recent_count,
                 result.color_debug.strongest_seed.hist_rarity_score,
                 result.color_debug.strongest_seed.local_support_count,
                 result.color_debug.target_span_px,
                 result.color_debug.target_span_cells,
                 result.color_debug.max_blob_area_budget,
                 result.color_debug.blob_examined_count,
                 color_blob_reject_reason_name(result.color_debug.strongest_reject_reason),
                 result.color_debug.strongest_reject_peak_support,
                 result.color_debug.strongest_reject_area,
                 result.color_debug.strongest_reject_span,
                 result.color_debug.strongest_reject_ring_fraction,
                 result.color_debug.strongest_reject_support_mass,
                 result.color_debug.strongest_reject_quality,
                 result.color_debug.blob_reject_area_count,
                 result.color_debug.blob_reject_ring_count,
                 result.color_debug.blob_reject_support_mass_count,
                 result.color_debug.blob_reject_quality_count,
                 result.color_debug.nonzero_histogram_bins,
                 result.color_debug.max_histogram_current_count,
                 result.color_debug.max_histogram_recent_count,
                 result.color_debug.history_recovery_frames_remaining,
                 result.color_debug.history_recent_scale);
    }
    return annotated;
}

static bool analyze_rgba_frame(ffmpeg_session_t *session,
                               const anomaly_config_t *cfg_override,
                               int width,
                               int height,
                               uint8_t *rgba,
                               int rgba_stride,
                               int64_t source_ts_us) {
    anomaly_config_t cfg_snapshot;
    if (cfg_override == NULL) {
        pthread_mutex_lock(&g_lock);
        cfg_snapshot = session->anomaly_cfg;
        pthread_mutex_unlock(&g_lock);
        cfg_override = &cfg_snapshot;
    }
    if (session->anomaly_lock_ready) {
        pthread_mutex_lock(&session->anomaly_lock);
    }
    bool annotated = analyze_rgba_frame_locked(
            session,
            cfg_override,
            width,
            height,
            rgba,
            rgba_stride,
            source_ts_us,
            -1,
            NULL,
            0u,
            0u,
            ANOMALY_PERSON_PRESSURE_ELEVATED,
            false);
    if (session->anomaly_lock_ready) {
        pthread_mutex_unlock(&session->anomaly_lock);
    }
    return annotated;
}


static bool anomaly_processing_enabled(ffmpeg_session_t *session) {
    if (session == NULL) return false;
    bool enabled = false;
    pthread_mutex_lock(&g_lock);
    enabled = session->anomaly_cfg.enabled &&
              !session->anomaly_thermal_paused &&
              !session->anomaly_runtime_disabled &&
              (session->anomaly_cfg.algorithm_mask != 0);
    pthread_mutex_unlock(&g_lock);
    return enabled;
}

static bool anomaly_processing_enabled_locked(ffmpeg_session_t *session) {
    if (session == NULL) return false;
    return session->anomaly_cfg.enabled &&
           !session->anomaly_thermal_paused &&
           !session->anomaly_runtime_disabled &&
           (session->anomaly_cfg.algorithm_mask != 0);
}

static ad_runtime_mode_t current_ad_runtime_mode(ffmpeg_session_t *session) {
    if (session == NULL || !session->is_render) {
        return AD_RUNTIME_MODE_BYPASSED;
    }
    bool enabled = session->anomaly_cfg.enabled &&
                   !session->anomaly_thermal_paused &&
                   !session->anomaly_runtime_disabled &&
                   (session->anomaly_cfg.algorithm_mask != 0);
    if (!enabled) {
        return AD_RUNTIME_MODE_BYPASSED;
    }
    if (session->ad_thread_started && session->render_thread_started) {
        return AD_RUNTIME_MODE_THREADED;
    }
    return AD_RUNTIME_MODE_INLINE;
}

static const char *color_blob_reject_reason_name(int reason) {
    switch (reason) {
        case ANOMALY_COLOR_BLOB_REJECT_AREA:
            return "area";
        case ANOMALY_COLOR_BLOB_REJECT_RING:
            return "ring";
        case ANOMALY_COLOR_BLOB_REJECT_SUPPORT_MASS:
            return "support-mass";
        case ANOMALY_COLOR_BLOB_REJECT_QUALITY:
            return "quality";
        case ANOMALY_COLOR_BLOB_REJECT_COMMONNESS:
            return "commonness";
        default:
            return "none";
    }
}

static void update_local_playback_ad_decision_summary(
        ffmpeg_session_t *session,
        int64_t frame_id,
        int64_t generation_id,
        int64_t source_ts_us,
        bool ad_enabled,
        bool ad_thread_started,
        bool ad_sync_ready,
        ad_runtime_mode_t runtime_mode,
        const char *decision,
        const char *reason) {
    if (session == NULL) return;
    snprintf(session->latest_local_playback_ad_decision,
             sizeof(session->latest_local_playback_ad_decision),
             "frame=%lld gen=%lld ts=%.3fs decision=%s reason=%s adEnabled=%d adThread=%d adSync=%d runtimeMode=%d q=%d/%lld disabled=%d thermalPause=%d",
             (long long) frame_id,
             (long long) generation_id,
             source_ts_us > 0 ? ((double) source_ts_us / 1000000.0) : 0.0,
             decision != NULL ? decision : "unknown",
             reason != NULL ? reason : "unknown",
             ad_enabled ? 1 : 0,
             ad_thread_started ? 1 : 0,
             ad_sync_ready ? 1 : 0,
             (int) runtime_mode,
             session->ad_input_queue_depth,
             (long long) session->ad_input_queue_depth_max,
             session->anomaly_runtime_disabled ? 1 : 0,
             session->anomaly_thermal_paused ? 1 : 0);
}

static bool start_ad_thread_if_needed_locked(ffmpeg_session_t *session,
                                             const char *reason) {
    if (session == NULL) {
        return false;
    }
    bool processing_enabled = anomaly_processing_enabled_locked(session);
    if (session->ad_thread_started || session->ad_thread_starting) {
        return true;
    }
    if (!anomaly_detector_runtime_budget_should_start_ad_worker(
                session->is_render,
                session->ad_sync_ready,
                processing_enabled,
                session->ad_thread_started,
                session->ad_thread_starting)) {
        return false;
    }

    session->ad_thread_starting = true;
    session->ad_thread_stop = false;
    clear_ad_input_queue(session);
    int ad_thread_rc = pthread_create(&session->ad_thread, NULL, ad_thread_main, session);
    if (ad_thread_rc == 0) {
        session->ad_thread_started = true;
        session->ad_thread_starting = false;
        ct_debug(TAG,
                 "ad thread started id=%lld designator=%s reason=%s",
                 (long long) session->session_id,
                 session->designator,
                 reason != NULL ? reason : "unknown");
        return true;
    }

    session->ad_thread_started = false;
    session->ad_thread_starting = false;
    ct_warn(TAG,
            "ad thread start failed id=%lld designator=%s reason=%s rc=%d",
            (long long) session->session_id,
            session->designator,
            reason != NULL ? reason : "unknown",
            ad_thread_rc);
    return false;
}

static const char *ad_pressure_mode_name(ad_pressure_mode_t mode) {
    const char *name = anomaly_runtime_pressure_mode_name(mode);
    return strcmp(name, "unknown") == 0 ? "normal" : name;
}

static anomaly_runtime_pressure_policy_t ad_pressure_policy_for_session(
        const ffmpeg_session_t *session) {
    int capacity = session != NULL ? session->ad_input_queue_capacity : 0;
    return anomaly_runtime_pressure_policy_make(
            capacity,
            AD_PRESSURE_RECOVER_DEPTH,
            AD_PRESSURE_ANALYZE_ALTERNATE_PCT,
            AD_PRESSURE_BYPASS_ALTERNATE_PCT,
            AD_PRESSURE_BYPASS_ALL_PCT);
}

static int ad_queue_depth_threshold(const ffmpeg_session_t *session, int pct) {
    if (session == NULL) return 0;
    return anomaly_runtime_pressure_depth_threshold(session->ad_input_queue_capacity, pct);
}

static ad_pressure_mode_t select_ad_pressure_mode_locked(ffmpeg_session_t *session,
                                                         int queue_depth_before_dequeue) {
    if (session == NULL) return AD_PRESSURE_MODE_NORMAL;
    return anomaly_runtime_pressure_select_mode(ad_pressure_policy_for_session(session),
                                                session->ad_pressure_mode,
                                                queue_depth_before_dequeue);
}

static bool local_ad_target_eval_has_no_revisit_work(ffmpeg_session_t *session,
                                                     int64_t local_ad_cadence_ordinal) {
    if (session == NULL ||
        local_ad_cadence_ordinal <= 0 ||
        !is_local_file_source(session) ||
        (local_ad_cadence_ordinal % LOCAL_AD_TARGET_EVAL_INTERVAL_FRAMES) != 0 ||
        (local_ad_cadence_ordinal % LOCAL_AD_FULL_SCAN_STRIDE_FRAMES) == 0 ||
        !session->anomaly_lock_ready) {
        return false;
    }
    if (pthread_mutex_trylock(&session->anomaly_lock) != 0) {
        return false;
    }
    int target_revisit_count =
            anomaly_target_revisit_track_count(&session->anomaly_state);
    bool target_color_selected =
            session->anomaly_cfg.target_color_family_mask != 0u;
    pthread_mutex_unlock(&session->anomaly_lock);
    return anomaly_detector_runtime_budget_local_ad_should_skip_target_eval(
            true,
            false,
            target_revisit_count,
            target_color_selected);
}

static anomaly_runtime_handoff_frame_t ad_handoff_frame_from_packet(
        const render_queue_slot_t *packet) {
    return anomaly_runtime_handoff_frame_make(
            packet != NULL ? packet->frame_id : 0,
            packet != NULL ? packet->generation_id : 0,
            packet != NULL ? packet->source_ts_us : 0,
            packet != NULL ? packet->enqueued_at_ms : 0,
            packet != NULL ? packet->width : 0,
            packet != NULL ? packet->height : 0,
            packet != NULL && packet->frame != NULL);
}

static AVFrame *build_overlay_frame(ffmpeg_session_t *session,
                                    AVFrame *decoded,
                                    int64_t source_ts_us,
                                    bool *out_analyzed,
                                    int frame_stride_override,
                                    int64_t annotation_frame_ordinal,
                                    uint64_t generation,
                                    uint64_t frame_sequence,
                                    anomaly_person_scheduler_pressure_t person_pressure,
                                    bool allow_person_offer) {
    if (out_analyzed != NULL) *out_analyzed = false;
    if (session == NULL || session_stopping(session) || !frame_looks_scalable(decoded)) return NULL;
    if (!anomaly_processing_enabled(session)) return NULL;

    // Snapshot global configuration before taking anomaly_lock.  The global lock is
    // the outermost FFmpeg session lock; acquiring it while holding a per-session
    // lock can deadlock surface/control operations that use the opposite order.
    anomaly_config_t cfg_override;
    pthread_mutex_lock(&g_lock);
    cfg_override = session->anomaly_cfg;
    pthread_mutex_unlock(&g_lock);
    if (frame_stride_override > 0) {
        cfg_override.stride_mode = ANOMALY_STRIDE_MODE_FIXED;
        cfg_override.frame_stride = frame_stride_override;
        cfg_override.adaptive_min_stride_frames = frame_stride_override;
        cfg_override.adaptive_max_stride_frames = frame_stride_override;
    }

    // The AD worker and render thread share these conversion resources. Keep
    // reconfiguration, RGBA analysis, and overlay cloning in one critical section.
    bool locked = session->anomaly_lock_ready;
    if (locked) {
        pthread_mutex_lock(&session->anomaly_lock);
    }
    ensure_anomaly_rgba_resources(session, decoded->width, decoded->height, decoded->format);
    if (session->anomaly_sws == NULL || session->anomaly_rgba_frame == NULL ||
        session->anomaly_rgba_frame->data[0] == NULL || session->anomaly_rgba_frame->linesize[0] == 0) {
        if (locked) {
            pthread_mutex_unlock(&session->anomaly_lock);
        }
        return NULL;
    }

    trace_begin_section("RID2C anomaly_rgba_convert");
    sws_scale(
            session->anomaly_sws,
            (const uint8_t *const *) decoded->data,
            decoded->linesize,
            0,
            decoded->height,
            session->anomaly_rgba_frame->data,
            session->anomaly_rgba_frame->linesize);
    trace_end_section();

    bool frame_annotated = analyze_rgba_frame_locked(
            session,
            &cfg_override,
            decoded->width,
            decoded->height,
            session->anomaly_rgba_frame->data[0],
            session->anomaly_rgba_frame->linesize[0],
            source_ts_us,
            annotation_frame_ordinal,
            decoded,
            generation,
            frame_sequence,
            person_pressure,
            allow_person_offer);
    if (out_analyzed != NULL) *out_analyzed = true;
    AVFrame *overlay = frame_annotated ? clone_rgba_frame(session->anomaly_rgba_frame) : NULL;
    if (locked) {
        pthread_mutex_unlock(&session->anomaly_lock);
    }
    return overlay;
}

static bool apply_overlay_to_decoded_frame(ffmpeg_session_t *session,
                                           AVFrame *decoded,
                                           const AVFrame *overlay_frame) {
    if (session == NULL || session_stopping(session) ||
        !frame_looks_scalable(decoded) || !frame_looks_scalable(overlay_frame)) {
        return false;
    }
    if (overlay_frame->format != AV_PIX_FMT_RGBA) return false;
    if (decoded->width != overlay_frame->width || decoded->height != overlay_frame->height) {
        if (session->anomaly_troubleshooting_debug) {
            ct_warn(TAG,
                    "overlay size mismatch id=%lld designator=%s decoded=%dx%d overlay=%dx%d",
                    (long long) session->session_id,
                    session->designator,
                    decoded->width,
                    decoded->height,
                    overlay_frame->width,
                    overlay_frame->height);
        }
        return false;
    }

    bool locked = session->anomaly_lock_ready;
    if (locked) {
        pthread_mutex_lock(&session->anomaly_lock);
    }
    ensure_anomaly_rgba_resources(session, decoded->width, decoded->height, decoded->format);
    if (session->anomaly_back_sws == NULL) {
        ct_warn(TAG,
                "overlay back-convert unavailable id=%lld designator=%s",
                (long long) session->session_id,
                session->designator);
        if (locked) {
            pthread_mutex_unlock(&session->anomaly_lock);
        }
        return false;
    }
    if (!frame_looks_scalable(decoded)) {
        if (locked) {
            pthread_mutex_unlock(&session->anomaly_lock);
        }
        return false;
    }
    if (av_frame_make_writable(decoded) < 0) {
        ct_warn(TAG,
                "decoded frame not writable for overlay id=%lld designator=%s",
                (long long) session->session_id,
                session->designator);
        if (locked) {
            pthread_mutex_unlock(&session->anomaly_lock);
        }
        return false;
    }
    if (!frame_looks_scalable(decoded)) {
        if (locked) {
            pthread_mutex_unlock(&session->anomaly_lock);
        }
        return false;
    }

    trace_begin_section("RID2C anomaly_overlay_convert");
    sws_scale(
            session->anomaly_back_sws,
            (const uint8_t *const *) overlay_frame->data,
            overlay_frame->linesize,
            0,
            decoded->height,
            decoded->data,
            decoded->linesize);
    trace_end_section();
    if (locked) {
        pthread_mutex_unlock(&session->anomaly_lock);
    }
    return true;
}

static int copy_published_overlay_boxes(ffmpeg_session_t *session,
                                        anomaly_box_t *out_boxes,
                                        int out_capacity) {
    if (session == NULL || out_boxes == NULL || out_capacity <= 0) {
        return 0;
    }
    bool locked = session->anomaly_lock_ready;
    if (locked) {
        pthread_mutex_lock(&session->anomaly_lock);
    }
    anomaly_detector_annotation_view_t view =
            anomaly_detector_annotation_cadence_snapshot_view(
                    &session->anomaly_overlay_publication_cadence);
    int count = view.box_count;
    if (count < 0 || view.boxes == NULL) {
        count = 0;
    }
    if (count > out_capacity) {
        count = out_capacity;
    }
    for (int i = 0; i < count; i++) {
        out_boxes[i] = view.boxes[i];
    }
    if (locked) {
        pthread_mutex_unlock(&session->anomaly_lock);
    }
    return count;
}

static void render_frame_to_surface(ffmpeg_session_t *session,
                                    AVFrame *decoded,
                                    AVFrame *history_frame,
                                    AVFrame *overlay_frame,
                                    bool analyzed,
                                    int64_t source_ts_us,
                                    int64_t render_latency_ms,
                                    bool record_local_history) {
    ANativeWindow *window = acquire_window(session);
    if (window == NULL) {
        int64_t now_ms = monotonic_ms();
        if (now_ms - session->last_no_surface_log_at_ms >= RENDER_NO_SURFACE_LOG_INTERVAL_MS) {
            session->last_no_surface_log_at_ms = now_ms;
            ct_debug(TAG,
                     "render waiting for surface id=%lld designator=%s",
                     (long long) session->session_id,
                     session->designator);
        }
        return;
    }

    anomaly_box_t held_overlay_boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    int held_overlay_box_count = 0;
    if (overlay_frame == NULL && is_local_file_source(session)) {
        held_overlay_box_count = copy_published_overlay_boxes(
                session,
                held_overlay_boxes,
                ANOMALY_MAX_BOXES_PER_FRAME);
    }

    bool use_render_lock = session->render_sync_ready;
    if (use_render_lock) {
        pthread_mutex_lock(&session->render_lock);
    }
    session->capture_frame_valid = false;
    av_frame_free(&session->capture_overlay_frame);
    if (overlay_frame == NULL) {
        ensure_rgba_resources(session, decoded->width, decoded->height, decoded->format);
    }
    if ((overlay_frame == NULL && (session->sws == NULL || session->rgba_frame == NULL)) ||
        (overlay_frame != NULL && overlay_frame->data[0] == NULL)) {
        if (use_render_lock) {
            pthread_mutex_unlock(&session->render_lock);
        }
        ANativeWindow_release(window);
        dispatch_probe_event(session->designator, "render_skipped_no_rgba", session->session_id, source_ts_us,
                             NAN, NAN, NAN, NAN, NAN, NAN);
        return;
    }

    AVFrame *display_rgba = overlay_frame;
    if (display_rgba == NULL) {
        trace_begin_section("RID2C render_rgba_convert");
        sws_scale(
                session->sws,
                (const uint8_t *const *) decoded->data,
                decoded->linesize,
                0,
                decoded->height,
                session->rgba_frame->data,
                session->rgba_frame->linesize);
        trace_end_section();
        display_rgba = session->rgba_frame;
    }
    if (anomaly_detector_runtime_budget_should_draw_held_local_overlay(
                is_local_file_source(session),
                overlay_frame != NULL,
                held_overlay_box_count > 0)) {
        anomaly_debug_draw_boxes_rgba(
                display_rgba->data[0],
                display_rgba->linesize[0],
                decoded->width,
                decoded->height,
                held_overlay_boxes,
                held_overlay_box_count);
    }
    ANativeWindow_setBuffersGeometry(window, decoded->width, decoded->height, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer buffer;
    trace_begin_section("RID2C render_surface_post");
    int lock_rc = ANativeWindow_lock(window, &buffer, NULL);
    if (lock_rc == 0) {
        uint8_t *dst = (uint8_t *) buffer.bits;
        const int dst_stride = buffer.stride * 4;
        uint8_t *src = display_rgba->data[0];
        const int src_stride = display_rgba->linesize[0];
        const int copy_width = decoded->width * 4;
        for (int y = 0; y < decoded->height; y++) {
            memcpy(dst + (y * dst_stride), src + (y * src_stride), (size_t) copy_width);
        }
        ANativeWindow_unlockAndPost(window);
        session->last_render_post_at_ms = monotonic_ms();
        // Normal RGBA remains protected by render_lock until the next surface post.
        // Retain overlays separately because their queue slot is released after rendering.
        session->capture_overlay_frame = overlay_frame != NULL ? av_frame_clone(display_rgba) : NULL;
        session->capture_source_ts_us = source_ts_us;
        session->capture_frame_valid = overlay_frame == NULL || session->capture_overlay_frame != NULL;
        if (overlay_frame != NULL) {
            ct_debug(TAG,
                     "render posted overlay id=%lld designator=%s ts=%.3fs analyzed=%d latencyMs=%lld",
                     (long long) session->session_id,
                     session->designator,
                     source_ts_us > 0 ? ((double) source_ts_us / 1000000.0) : 0.0,
                     analyzed ? 1 : 0,
                     (long long) render_latency_ms);
        }
        if (is_local_file_source(session) && source_ts_us > 0) {
            // Track both the full-session and recent playback spans from actual
            // surface posts so the UI can distinguish steady-state speed from a
            // startup-dragged cumulative average.
            record_local_playback_timing_sample(
                    session,
                    source_ts_us,
                    session->last_render_post_at_ms);
        }
        if (record_local_history && use_render_lock && is_local_file_source(session)) {
            append_local_playback_history_locked(
                    session,
                    history_frame != NULL ? history_frame : decoded,
                    source_ts_us,
                    session->last_render_post_at_ms);
        }
        dispatch_probe_event_with_latency(session->designator,
                                          "frame_rendered",
                                          session->session_id,
                                          source_ts_us,
                                          render_latency_ms,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN);
        if (analyzed) {
            session->ad_analyzed_rendered_frame_count += 1;
        } else {
            session->ad_bypassed_rendered_frame_count += 1;
        }
    } else {
        ct_warn(TAG,
                "ANativeWindow_lock failed id=%lld designator=%s rc=%d width=%d height=%d",
                (long long) session->session_id,
                session->designator,
                lock_rc,
                decoded->width,
                decoded->height);
        dispatch_probe_event_with_latency(session->designator,
                                          "render_lock_failed",
                                          session->session_id,
                                          source_ts_us,
                                          render_latency_ms,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN);
    }
    trace_end_section();

    ANativeWindow_release(window);
    if (use_render_lock) {
        pthread_mutex_unlock(&session->render_lock);
    }
}

static void *retain_person_relevance_frame(void *frame) {
    return frame != NULL ? av_frame_clone((const AVFrame *)frame) : NULL;
}

static void release_person_relevance_frame(void *frame) {
    AVFrame *owned = frame;
    av_frame_free(&owned);
}

static const anomaly_person_relevance_model_identity_t PERSON_RELEVANCE_MODEL = {
    .model_name = "EfficientDet Lite0 Detection",
    .model_version = "971d935f3679eabbcce7b4d3733f351d403ff2b9",
    .model_sha256 = "33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704",
    .input_tensor = "serving_default_images:0",
    .quantization = "uint8 scale=0.0078125 zeroPoint=127",
    .runtime_name = "LiteRT",
    .runtime_version = "2.1.5",
    .accelerator = "CPU/XNNPACK 1-thread",
};

static bool person_relevance_crop_rgb320(
        const AVFrame *source,
        const anomaly_person_relevance_candidate_t *candidate,
        uint8_t **rgb_out) {
    if (source == NULL || candidate == NULL || rgb_out == NULL ||
        source->width <= 0 || source->height <= 0 ||
        !isfinite(candidate->left_norm) || !isfinite(candidate->top_norm) ||
        !isfinite(candidate->right_norm) || !isfinite(candidate->bottom_norm) ||
        candidate->right_norm <= candidate->left_norm ||
        candidate->bottom_norm <= candidate->top_norm) {
        return false;
    }
    *rgb_out = NULL;
    AVFrame *crop = av_frame_clone(source);
    if (crop == NULL) return false;

    float center_x = 0.5f * (candidate->left_norm + candidate->right_norm);
    float center_y = 0.5f * (candidate->top_norm + candidate->bottom_norm);
    int candidate_w = (int)ceilf(
            (candidate->right_norm - candidate->left_norm) * (float)source->width);
    int candidate_h = (int)ceilf(
            (candidate->bottom_norm - candidate->top_norm) * (float)source->height);
    int side = candidate_w > candidate_h ? candidate_w : candidate_h;
    side *= 2;
    if (side < 64) side = 64;
    if (side > source->width) side = source->width;
    if (side > source->height) side = source->height;
    if (side <= 0) {
        av_frame_free(&crop);
        return false;
    }
    int left = (int)lroundf(center_x * (float)source->width - 0.5f * (float)side);
    int top = (int)lroundf(center_y * (float)source->height - 0.5f * (float)side);
    if (left < 0) left = 0;
    if (top < 0) top = 0;
    if (left + side > source->width) left = source->width - side;
    if (top + side > source->height) top = source->height - side;
    crop->crop_left = (size_t)left;
    crop->crop_top = (size_t)top;
    crop->crop_right = (size_t)(source->width - left - side);
    crop->crop_bottom = (size_t)(source->height - top - side);
    if (av_frame_apply_cropping(crop, AV_FRAME_CROP_UNALIGNED) < 0 ||
        crop->width <= 0 || crop->height <= 0) {
        av_frame_free(&crop);
        return false;
    }

    struct SwsContext *sws = sws_getContext(
            crop->width,
            crop->height,
            (enum AVPixelFormat)crop->format,
            320,
            320,
            AV_PIX_FMT_RGB24,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);
    uint8_t *rgb = av_malloc(320u * 320u * 3u);
    if (sws == NULL || rgb == NULL) {
        sws_freeContext(sws);
        av_free(rgb);
        av_frame_free(&crop);
        return false;
    }
    uint8_t *dest_data[4] = {rgb, NULL, NULL, NULL};
    int dest_linesize[4] = {320 * 3, 0, 0, 0};
    int rows = sws_scale(
            sws,
            (const uint8_t *const *)crop->data,
            crop->linesize,
            0,
            crop->height,
            dest_data,
            dest_linesize);
    sws_freeContext(sws);
    av_frame_free(&crop);
    if (rows != 320) {
        av_free(rgb);
        return false;
    }
    *rgb_out = rgb;
    return true;
}

static bool person_relevance_native_backend(
        void *context,
        void *retained_frame,
        const anomaly_person_relevance_model_identity_t *model,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        anomaly_person_relevance_decision_batch_t *decisions_out) {
    (void)context;
    (void)model;
    if (retained_frame == NULL || candidates == NULL || decisions_out == NULL) return false;
    memset(decisions_out, 0, sizeof(*decisions_out));
    bool did_attach = false;
    JNIEnv *env = get_env(&did_attach);
    if (env == NULL || g_person_relevance_coordinator_class == NULL ||
        g_person_relevance_infer_mid == NULL) {
        release_env(did_attach);
        return false;
    }

    decisions_out->count = candidates->count;
    for (size_t i = 0; i < candidates->count; i++) {
        const anomaly_person_relevance_candidate_t *candidate =
                &candidates->candidates[i];
        anomaly_person_relevance_decision_t *decision =
                &decisions_out->decisions[i];
        decision->candidate_id = candidate->candidate_id;
        decision->observation_index = candidate->observation_index;
        decision->provenance_mask = candidate->provenance_mask;
        decision->kind = ANOMALY_PERSON_RELEVANCE_DECISION_INVALID;

        uint8_t *rgb = NULL;
        if (!person_relevance_crop_rgb320(
                    (const AVFrame *)retained_frame, candidate, &rgb)) {
            continue;
        }
        jobject buffer = (*env)->NewDirectByteBuffer(env, rgb, 320 * 320 * 3);
        jfloatArray result = buffer == NULL ? NULL : (jfloatArray)(*env)->CallStaticObjectMethod(
                env,
                g_person_relevance_coordinator_class,
                g_person_relevance_infer_mid,
                buffer);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            result = NULL;
        }
        if (result != NULL && (*env)->GetArrayLength(env, result) >= 2) {
            jfloat values[2] = {-1.0f, 0.0f};
            (*env)->GetFloatArrayRegion(env, result, 0, 2, values);
            if (values[0] == 1.0f && isfinite(values[1]) &&
                values[1] >= 0.0f && values[1] <= 1.0f) {
                decision->person_confidence = values[1];
                decision->evidence_id = atomic_fetch_add_explicit(
                        &g_person_relevance_evidence_id, 1u, memory_order_relaxed);
                decision->kind = values[1] >= 0.50f
                        ? ANOMALY_PERSON_RELEVANCE_DECISION_POSITIVE
                        : ANOMALY_PERSON_RELEVANCE_DECISION_LOW_CONFIDENCE;
            } else if (values[0] == 0.0f) {
                decision->kind = ANOMALY_PERSON_RELEVANCE_DECISION_LOW_CONFIDENCE;
            }
        }
        if (result != NULL) (*env)->DeleteLocalRef(env, result);
        if (buffer != NULL) (*env)->DeleteLocalRef(env, buffer);
        av_free(rgb);
    }
    release_env(did_attach);
    return true;
}

static bool configure_person_relevance_scheduler(
        ffmpeg_session_t *session,
        anomaly_person_relevance_mode_t mode) {
    if (session == NULL) return false;
    anomaly_person_scheduler_destroy(&session->person_relevance_scheduler);
    anomaly_person_relevance_config_t config = {
        .mode = mode,
        .minimum_person_confidence = 0.50f,
        .maximum_confidence_bonus = 0.20f,
    };
    bool configured = anomaly_person_scheduler_init(
            &session->person_relevance_scheduler,
            &config,
            mode == ANOMALY_PERSON_RELEVANCE_OFF ? NULL : &PERSON_RELEVANCE_MODEL,
            mode == ANOMALY_PERSON_RELEVANCE_OFF ? NULL : person_relevance_native_backend,
            session,
            retain_person_relevance_frame,
            release_person_relevance_frame);
    atomic_store_explicit(
            &session->person_relevance_mode,
            configured ? mode : ANOMALY_PERSON_RELEVANCE_OFF,
            memory_order_relaxed);
    return configured;
}
#endif

#if HAVE_FFMPEG
static int64_t pts_to_us(int64_t pts, AVRational tb) {
    if (pts == AV_NOPTS_VALUE) return 0;
    return av_rescale_q(pts, tb, (AVRational) {1, 1000000});
}

static int64_t normalize_local_playback_pts_us(ffmpeg_session_t *session,
                                               int64_t pts_us) {
    if (session == NULL || !is_local_file_source(session)) return pts_us;

    int64_t last_pts_us = session->last_valid_pts_us;
    anomaly_detector_runtime_budget_local_playback_pts_t normalized =
            anomaly_detector_runtime_budget_normalize_local_playback_pts_us(
                    pts_us,
                    last_pts_us,
                    session->local_playback_nominal_interval_ms,
                    session->source_render_interval_ms,
                    RENDER_DEFAULT_FPS);
    if (normalized.repaired) {
        session->local_playback_pts_repair_count += 1;
        int64_t repair_count = session->local_playback_pts_repair_count;
        if (repair_count <= 5 || (repair_count % 120) == 0) {
            ct_debug(TAG,
                     "local playback pts repaired id=%lld designator=%s raw=%.3fs last=%.3fs repaired=%.3fs count=%lld",
                     (long long) session->session_id,
                     session->designator,
                     (double) pts_us / 1000000.0,
                     (double) last_pts_us / 1000000.0,
                     (double) normalized.pts_us / 1000000.0,
                     (long long) repair_count);
        }
    }
    return normalized.pts_us;
}

static int64_t clamp_i64(int64_t value, int64_t min_value, int64_t max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static int64_t current_render_interval_ms(ffmpeg_session_t *session) {
    if (session == NULL) return 0;
    return anomaly_detector_runtime_budget_current_render_interval_ms(
            session->render_interval_smoothed_ms,
            session->source_render_interval_ms,
            RENDER_DEFAULT_FPS,
            RENDER_MIN_INTERVAL_MS,
            RENDER_MAX_INTERVAL_MS);
}

static void push_sample_i64(int64_t *buf,
                            int *head,
                            int *count,
                            int capacity,
                            int64_t value) {
    if (buf == NULL || head == NULL || count == NULL || capacity <= 0) return;
    buf[*head] = value;
    *head = (*head + 1) % capacity;
    if (*count < capacity) {
        *count += 1;
    }
}

static int cmp_i64_asc(const void *a, const void *b) {
    int64_t av = *(const int64_t *) a;
    int64_t bv = *(const int64_t *) b;
    if (av < bv) return -1;
    if (av > bv) return 1;
    return 0;
}

static int64_t median_i64_copy(const int64_t *buf, int count) {
    if (buf == NULL || count <= 0) return 0;
    int64_t tmp[RENDER_SAMPLE_WINDOW_CAPACITY];
    if (count > RENDER_SAMPLE_WINDOW_CAPACITY) {
        count = RENDER_SAMPLE_WINDOW_CAPACITY;
    }
    memcpy(tmp, buf, (size_t) count * sizeof(int64_t));
    qsort(tmp, (size_t) count, sizeof(int64_t), cmp_i64_asc);
    if ((count & 1) != 0) {
        return tmp[count / 2];
    }
    return (tmp[(count / 2) - 1] + tmp[count / 2] + 1) / 2;
}

static bool decode_delta_is_gap(int64_t delta_ms, int64_t source_interval_ms) {
    return anomaly_detector_runtime_budget_decode_delta_is_gap(
            delta_ms,
            source_interval_ms,
            RENDER_SOURCE_INTERVAL_DEFAULT_MS,
            RENDER_GAP_FLOOR_MS);
}

static bool decode_delta_is_plausible_cadence(int64_t delta_ms, int64_t source_interval_ms) {
    return anomaly_detector_runtime_budget_decode_delta_is_plausible_cadence(
            delta_ms,
            source_interval_ms,
            RENDER_SOURCE_INTERVAL_DEFAULT_MS,
            RENDER_CADENCE_SAMPLE_MIN_MS,
            RENDER_CADENCE_SAMPLE_MAX_MS,
            RENDER_SOURCE_ESTIMATE_MIN_PCT,
            RENDER_SOURCE_ESTIMATE_MAX_PCT);
}

static int64_t buffered_span_ms_locked(const ffmpeg_session_t *session) {
    if (session == NULL ||
        session->render_queue == NULL ||
        session->render_queue_capacity <= 0 ||
        session->render_queue_depth <= 1) {
        return 0;
    }

    int64_t first_pts_us = 0;
    int64_t last_pts_us = 0;
    bool found = false;
    for (int i = 0; i < session->render_queue_depth; i++) {
        int idx = anomaly_detector_runtime_budget_queue_offset_index(
                session->render_queue_head,
                i,
                session->render_queue_capacity);
        int64_t pts_us = session->render_queue[idx].source_ts_us;
        if (pts_us <= 0) continue;
        if (!found) {
            first_pts_us = pts_us;
            last_pts_us = pts_us;
            found = true;
            continue;
        }
        if (pts_us < last_pts_us) {
            return 0;
        }
        last_pts_us = pts_us;
    }
    if (!found) return 0;
    return anomaly_detector_runtime_budget_buffered_span_ms(
            first_pts_us,
            last_pts_us);
}

static anomaly_detector_runtime_budget_t local_ad_runtime_budget_from_backlog_ms(
        int64_t buffered_span_ms) {
    anomaly_detector_runtime_budget_t budget =
            anomaly_detector_runtime_budget_make_default((float) RENDER_DEFAULT_FPS);
    if (buffered_span_ms < 0) {
        buffered_span_ms = 0;
    }
    budget.render_backlog_seconds = (float) buffered_span_ms / 1000.0f;
    budget.startup_elapsed_seconds = budget.startup_skip_seconds;
    return budget;
}

static int64_t local_ad_render_backlog_target_ms(void) {
    anomaly_detector_runtime_budget_t budget =
            anomaly_detector_runtime_budget_make_default((float) RENDER_DEFAULT_FPS);
    budget = anomaly_detector_runtime_budget_normalize(budget);
    return (int64_t) llroundf(budget.max_backlog_seconds * 1000.0f);
}

static int64_t effective_local_ad_render_latency_target_ms_locked(
        ffmpeg_session_t *session,
        int64_t           target_latency_ms) {
    if (session == NULL) return target_latency_ms;
    bool local_file_source = is_local_file_source(session);
    bool local_ad_ready = false;
    bool target_color_selected = false;
    if (local_file_source) {
        pthread_mutex_lock(&g_lock);
        local_ad_ready =
                anomaly_processing_enabled_locked(session) &&
                session->ad_thread_started &&
                session->ad_sync_ready;
        target_color_selected =
                session->anomaly_cfg.target_color_family_mask != 0u;
        pthread_mutex_unlock(&g_lock);
    }
    return anomaly_detector_runtime_budget_local_ad_render_latency_target_ms(
            local_file_source,
            local_ad_ready,
            target_color_selected,
            target_latency_ms,
            LOCAL_AD_TARGET_COLOR_RENDER_LATENCY_CAP_MS);
}

static int64_t local_render_backlog_ms(ffmpeg_session_t *session) {
    if (session == NULL || !session->render_sync_ready) {
        return 0;
    }
    pthread_mutex_lock(&session->render_lock);
    int64_t buffered_span_ms = buffered_span_ms_locked(session);
    pthread_mutex_unlock(&session->render_lock);
    return buffered_span_ms;
}

static int64_t queue_pts_interval_ms_locked(const ffmpeg_session_t *session,
                                            int max_samples) {
    if (session == NULL ||
        session->render_queue == NULL ||
        session->render_queue_capacity <= 0 ||
        session->render_queue_depth <= 1) {
        return 0;
    }

    if (max_samples < 2 || max_samples > session->render_queue_depth) {
        max_samples = session->render_queue_depth;
    }

    int start_offset = session->render_queue_depth - max_samples;
    int64_t first_pts_us = 0;
    int64_t last_pts_us = 0;
    int valid_count = 0;
    for (int i = start_offset; i < session->render_queue_depth; i++) {
        int idx = anomaly_detector_runtime_budget_queue_offset_index(
                session->render_queue_head,
                i,
                session->render_queue_capacity);
        int64_t pts_us = session->render_queue[idx].source_ts_us;
        if (pts_us <= 0) continue;
        if (valid_count == 0) {
            first_pts_us = pts_us;
            last_pts_us = pts_us;
            valid_count = 1;
            continue;
        }
        if (pts_us < last_pts_us) {
            return 0;
        }
        last_pts_us = pts_us;
        valid_count += 1;
    }
    if (valid_count < 2 || last_pts_us <= first_pts_us) {
        return 0;
    }
    return anomaly_detector_runtime_budget_pts_interval_from_span_ms(
            first_pts_us,
            last_pts_us,
            valid_count,
            RENDER_MIN_INTERVAL_MS,
            RENDER_MAX_INTERVAL_MS);
}

static int64_t compute_target_latency_ms_locked(ffmpeg_session_t *session) {
    if (session == NULL) return 1000;
    return anomaly_detector_runtime_budget_target_latency_ms(
            session->stall_estimate_ms,
            session->proven_gap_ms,
            RENDER_STALL_ESTIMATE_FLOOR_MS,
            RENDER_PROCESSING_MARGIN_MS,
            RENDER_TARGET_LATENCY_MIN_MS,
            RENDER_TARGET_LATENCY_MAX_MS);
}

static void update_source_interval_estimate_locked(ffmpeg_session_t *session,
                                                   int64_t decode_delta_ms) {
    if (session == NULL || decode_delta_ms <= 0) return;
    anomaly_detector_runtime_budget_source_interval_estimate_t estimate =
            anomaly_detector_runtime_budget_update_source_interval_estimate(
                    session->source_render_interval_ms,
                    session->source_interval_confidence,
                    decode_delta_ms,
                    RENDER_SOURCE_INTERVAL_DEFAULT_MS,
                    RENDER_SOURCE_ESTIMATE_EMA_PCT,
                    5,
                    RENDER_MIN_INTERVAL_MS,
                    RENDER_MAX_INTERVAL_MS);
    session->source_render_interval_ms = estimate.interval_ms;
    session->source_interval_confidence = estimate.confidence;
}

static void apply_pts_source_interval_locked(ffmpeg_session_t *session,
                                             int64_t pts_interval_ms,
                                             bool force_direct) {
    if (session == NULL || pts_interval_ms <= 0) return;

    anomaly_detector_runtime_budget_source_interval_estimate_t estimate =
            anomaly_detector_runtime_budget_apply_pts_source_interval(
                    session->source_render_interval_ms,
                    session->source_interval_confidence,
                    pts_interval_ms,
                    force_direct,
                    RENDER_SOURCE_INTERVAL_DEFAULT_MS,
                    70,
                    80,
                    4,
                    20,
                    35,
                    RENDER_MIN_INTERVAL_MS,
                    RENDER_MAX_INTERVAL_MS);
    session->source_render_interval_ms = estimate.interval_ms;
    session->source_interval_confidence = estimate.confidence;
}

static bool maybe_fast_relock_to_pts_locked(ffmpeg_session_t *session,
                                            int64_t buffered_span_ms,
                                            int64_t target_latency_ms,
                                            int64_t now_ms) {
    if (session == NULL || session->render_queue_depth < 8) return false;
    if (target_latency_ms <= 0) return false;
    if (buffered_span_ms < target_latency_ms) return false;
    if ((now_ms - session->last_source_pts_relock_at_ms) < 250) return false;

    int64_t current_interval_ms = session->source_render_interval_ms > 0
            ? session->source_render_interval_ms
            : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    int64_t pts_interval_ms = queue_pts_interval_ms_locked(session, 24);
    if (pts_interval_ms <= 0) return false;
    if (pts_interval_ms >= current_interval_ms - 3) return false;

    int64_t previous_interval_ms = session->source_render_interval_ms;
    apply_pts_source_interval_locked(session, pts_interval_ms, true);
    session->last_source_pts_relock_at_ms = now_ms;
    if (llabs(session->source_render_interval_ms - previous_interval_ms) >= 2) {
        ct_debug(TAG,
                 "render fast relock id=%lld designator=%s oldIntervalMs=%lld newIntervalMs=%lld ptsIntervalMs=%lld bufferedSpanMs=%lld targetLatencyMs=%lld queueDepth=%d",
                 (long long) session->session_id,
                 session->designator,
                 (long long) previous_interval_ms,
                 (long long) session->source_render_interval_ms,
                 (long long) pts_interval_ms,
                 (long long) buffered_span_ms,
                 (long long) target_latency_ms,
                 session->render_queue_depth);
    }
    return true;
}

static void update_stall_estimate_locked(ffmpeg_session_t *session, int64_t gap_ms) {
    if (session == NULL || gap_ms < RENDER_GAP_FLOOR_MS) return;
    session->stall_estimate_ms =
            anomaly_detector_runtime_budget_update_stall_estimate_ms(
                    session->stall_estimate_ms,
                    gap_ms,
                    RENDER_STALL_ESTIMATE_FLOOR_MS,
                    RENDER_STALL_RISE_EMA_PCT,
                    RENDER_STALL_DECAY_EMA_PCT,
                    RENDER_TARGET_LATENCY_MAX_MS);
    session->last_gap_at_ms = session->last_decode_at_ms;
    session->last_stall_decay_at_ms = session->last_decode_at_ms;
}

static void update_proven_gap_locked(ffmpeg_session_t *session, int64_t gap_ms) {
    if (session == NULL || gap_ms < RENDER_PROVEN_GAP_TRIGGER_MS) return;

    session->proven_gap_ms =
            anomaly_detector_runtime_budget_update_proven_gap_ms(
                    session->proven_gap_ms,
                    gap_ms,
                    RENDER_STALL_ESTIMATE_FLOOR_MS,
                    15,
                    RENDER_TARGET_LATENCY_MAX_MS);
    session->last_proven_gap_decay_at_ms = session->last_decode_at_ms;
}

static void maybe_decay_stall_estimate_locked(ffmpeg_session_t *session, int64_t now_ms) {
    if (session == NULL) return;
    if (session->stall_estimate_ms <= RENDER_STALL_ESTIMATE_FLOOR_MS) return;
    if (session->last_gap_at_ms <= 0) return;
    if ((now_ms - session->last_gap_at_ms) < RENDER_STALL_DECAY_GRACE_MS) return;
    if ((now_ms - session->last_stall_decay_at_ms) < RENDER_STALL_DECAY_INTERVAL_MS) return;

    int64_t old_stall_ms = session->stall_estimate_ms;
    session->stall_estimate_ms =
            anomaly_detector_runtime_budget_decay_toward_floor_ms(
                    session->stall_estimate_ms,
                    RENDER_STALL_ESTIMATE_FLOOR_MS,
                    RENDER_STALL_DECAY_EMA_PCT);
    session->last_stall_decay_at_ms = now_ms;
    if (llabs(session->stall_estimate_ms - old_stall_ms) >= 20) {
        ct_debug(TAG,
                 "render stall estimate decayed id=%lld designator=%s oldMs=%lld newMs=%lld",
                 (long long) session->session_id,
                 session->designator,
                 (long long) old_stall_ms,
                 (long long) session->stall_estimate_ms);
    }
}

static void maybe_decay_proven_gap_locked(ffmpeg_session_t *session, int64_t now_ms) {
    if (session == NULL) return;
    if (session->proven_gap_ms <= RENDER_STALL_ESTIMATE_FLOOR_MS) return;
    if (session->last_gap_at_ms <= 0) return;
    if ((now_ms - session->last_gap_at_ms) < RENDER_PROVEN_GAP_DECAY_GRACE_MS) return;
    if ((now_ms - session->last_proven_gap_decay_at_ms) < RENDER_PROVEN_GAP_DECAY_INTERVAL_MS) return;

    int64_t old_proven_gap_ms = session->proven_gap_ms;
    session->proven_gap_ms =
            anomaly_detector_runtime_budget_decay_toward_floor_ms(
                    session->proven_gap_ms,
                    RENDER_STALL_ESTIMATE_FLOOR_MS,
                    RENDER_PROVEN_GAP_DECAY_EMA_PCT);
    session->last_proven_gap_decay_at_ms = now_ms;
    if (llabs(session->proven_gap_ms - old_proven_gap_ms) >= 20) {
        ct_debug(TAG,
                 "render proven gap decayed id=%lld designator=%s oldMs=%lld newMs=%lld",
                 (long long) session->session_id,
                 session->designator,
                 (long long) old_proven_gap_ms,
                 (long long) session->proven_gap_ms);
    }
}

static void log_startup_estimates(ffmpeg_session_t *session, const char *reason) {
    if (session == NULL) return;
    ct_debug(TAG,
             "render startup complete id=%lld designator=%s reason=%s sourceIntervalMs=%lld stallEstimateMs=%lld provenGapMs=%lld targetLatencyMs=%lld cadenceSamples=%d gapSamples=%d",
             (long long) session->session_id,
             session->designator,
             reason != NULL ? reason : "startup",
             (long long) session->source_render_interval_ms,
             (long long) session->stall_estimate_ms,
             (long long) session->proven_gap_ms,
             (long long) session->target_latency_ms,
             session->cadence_sample_count,
             session->gap_sample_count);
}

static void finalize_startup_estimates_locked(ffmpeg_session_t *session) {
    if (session == NULL) return;
    int64_t pts_interval_ms = queue_pts_interval_ms_locked(session, 24);
    if (session->cadence_sample_count > 0) {
        session->source_render_interval_ms = clamp_i64(
                median_i64_copy(session->cadence_samples_ms, session->cadence_sample_count),
                RENDER_MIN_INTERVAL_MS,
                RENDER_MAX_INTERVAL_MS);
        session->source_interval_confidence = 60;
    } else if (session->source_render_interval_ms <= 0) {
        session->source_render_interval_ms = RENDER_SOURCE_INTERVAL_DEFAULT_MS;
        session->source_interval_confidence = 10;
    }
    if (pts_interval_ms > 0) {
        apply_pts_source_interval_locked(session, pts_interval_ms, true);
        session->last_source_pts_relock_at_ms = monotonic_ms();
    }
    if (session->gap_sample_count > 0) {
        int64_t startup_gap_ms = median_i64_copy(session->gap_samples_ms, session->gap_sample_count);
        if (startup_gap_ms < RENDER_STALL_ESTIMATE_FLOOR_MS) {
            startup_gap_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
        }
        session->stall_estimate_ms = startup_gap_ms;
        if (startup_gap_ms >= RENDER_PROVEN_GAP_TRIGGER_MS) {
            session->proven_gap_ms = startup_gap_ms;
        }
    } else if (session->stall_estimate_ms <= 0) {
        session->stall_estimate_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    }
    if (session->proven_gap_ms <= 0) {
        session->proven_gap_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    }
    session->target_latency_ms = compute_target_latency_ms_locked(session);
    session->render_interval_smoothed_ms = session->source_render_interval_ms;
    log_startup_estimates(session, "observe-window");
}

static void record_decode_timing_sample_locked(ffmpeg_session_t *session,
                                               int64_t decoded_at_ms,
                                               int64_t source_ts_us) {
    if (session == NULL || decoded_at_ms <= 0) return;

    int64_t previous_decode_at_ms = session->last_decode_at_ms;
    session->last_decode_at_ms = decoded_at_ms;
    if (source_ts_us > 0) {
        session->last_valid_pts_us = source_ts_us;
    }
    if (previous_decode_at_ms <= 0 || decoded_at_ms <= previous_decode_at_ms) {
        return;
    }

    int64_t decode_delta_ms = decoded_at_ms - previous_decode_at_ms;
    bool gap_sample = decode_delta_is_gap(decode_delta_ms, session->source_render_interval_ms);
    if (session->startup_observation_active) {
        if (gap_sample) {
            push_sample_i64(
                    session->gap_samples_ms,
                    &session->gap_sample_head,
                    &session->gap_sample_count,
                    RENDER_SAMPLE_WINDOW_CAPACITY,
                    decode_delta_ms);
        } else if (decode_delta_ms >= RENDER_CADENCE_SAMPLE_MIN_MS &&
                   decode_delta_ms <= RENDER_CADENCE_SAMPLE_MAX_MS) {
            push_sample_i64(
                    session->cadence_samples_ms,
                    &session->cadence_sample_head,
                    &session->cadence_sample_count,
                    RENDER_SAMPLE_WINDOW_CAPACITY,
                    decode_delta_ms);
        }
        return;
    }

    if (gap_sample) {
        push_sample_i64(
                session->gap_samples_ms,
                &session->gap_sample_head,
                &session->gap_sample_count,
                RENDER_SAMPLE_WINDOW_CAPACITY,
                decode_delta_ms);
        int64_t old_stall_ms = session->stall_estimate_ms;
        int64_t old_proven_gap_ms = session->proven_gap_ms;
        update_stall_estimate_locked(session, decode_delta_ms);
        update_proven_gap_locked(session, decode_delta_ms);
        session->target_latency_ms = compute_target_latency_ms_locked(session);
        if (llabs(session->stall_estimate_ms - old_stall_ms) >= 50) {
            ct_debug(TAG,
                     "render stall estimate updated id=%lld designator=%s oldMs=%lld newMs=%lld provenGapMs=%lld targetLatencyMs=%lld gapMs=%lld",
                     (long long) session->session_id,
                     session->designator,
                     (long long) old_stall_ms,
                     (long long) session->stall_estimate_ms,
                     (long long) session->proven_gap_ms,
                     (long long) session->target_latency_ms,
                     (long long) decode_delta_ms);
        } else if (llabs(session->proven_gap_ms - old_proven_gap_ms) >= 50) {
            ct_debug(TAG,
                     "render proven gap updated id=%lld designator=%s oldMs=%lld newMs=%lld targetLatencyMs=%lld gapMs=%lld",
                     (long long) session->session_id,
                     session->designator,
                     (long long) old_proven_gap_ms,
                     (long long) session->proven_gap_ms,
                     (long long) session->target_latency_ms,
                     (long long) decode_delta_ms);
        }
        return;
    }

    if (!decode_delta_is_plausible_cadence(decode_delta_ms, session->source_render_interval_ms)) {
        return;
    }
    push_sample_i64(
            session->cadence_samples_ms,
            &session->cadence_sample_head,
            &session->cadence_sample_count,
            RENDER_SAMPLE_WINDOW_CAPACITY,
            decode_delta_ms);

    if ((decoded_at_ms - session->last_source_estimate_update_at_ms) <
        RENDER_SOURCE_ESTIMATE_UPDATE_INTERVAL_MS) {
        return;
    }

    int sample_count = session->cadence_sample_count;
    if (sample_count < 4) {
        return;
    }
    int64_t robust_interval_ms = median_i64_copy(session->cadence_samples_ms, sample_count);
    if (!decode_delta_is_plausible_cadence(robust_interval_ms, session->source_render_interval_ms)) {
        return;
    }

    int64_t old_interval_ms = session->source_render_interval_ms;
    update_source_interval_estimate_locked(session, robust_interval_ms);
    session->target_latency_ms = compute_target_latency_ms_locked(session);
    session->last_source_estimate_update_at_ms = decoded_at_ms;
    if (llabs(session->source_render_interval_ms - old_interval_ms) >= 2) {
        ct_debug(TAG,
                 "render source estimate updated id=%lld designator=%s oldIntervalMs=%lld newIntervalMs=%lld targetLatencyMs=%lld robustSampleMs=%lld confidence=%d samples=%d",
                 (long long) session->session_id,
                 session->designator,
                 (long long) old_interval_ms,
                 (long long) session->source_render_interval_ms,
                 (long long) session->target_latency_ms,
                 (long long) robust_interval_ms,
                 session->source_interval_confidence,
                 sample_count);
    }
}

static int64_t compute_desired_render_interval_ms_locked(ffmpeg_session_t *session,
                                                         int64_t buffered_span_ms,
                                                         int64_t now_ms) {
    if (session == NULL) {
        return RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    }

    int64_t source_interval_ms = session->source_render_interval_ms > 0
            ? session->source_render_interval_ms
            : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    if (is_local_file_source(session)) {
        maybe_decay_stall_estimate_locked(session, now_ms);
        maybe_decay_proven_gap_locked(session, now_ms);
        session->target_latency_ms = compute_target_latency_ms_locked(session);
        if ((now_ms - session->last_source_pts_relock_at_ms) >=
            RENDER_SOURCE_ESTIMATE_UPDATE_INTERVAL_MS) {
            int64_t pts_interval_ms = queue_pts_interval_ms_locked(session, 24);
            if (pts_interval_ms > 0 &&
                decode_delta_is_plausible_cadence(pts_interval_ms, source_interval_ms)) {
                apply_pts_source_interval_locked(session, pts_interval_ms, false);
                source_interval_ms = session->source_render_interval_ms;
                session->last_source_pts_relock_at_ms = now_ms;
            }
        }
        int64_t previous_interval_ms = session->render_interval_smoothed_ms > 0
                ? session->render_interval_smoothed_ms
                : source_interval_ms;
        int64_t target_latency_ms =
                effective_local_ad_render_latency_target_ms_locked(
                        session,
                        session->target_latency_ms);
        anomaly_detector_runtime_budget_render_interval_t render_interval =
                anomaly_detector_runtime_budget_desired_render_interval_ms(
                        source_interval_ms,
                        previous_interval_ms,
                        buffered_span_ms,
                        target_latency_ms,
                        false,
                        RENDER_INTERVAL_ADJUST_BASE_PCT,
                        RENDER_INTERVAL_ADJUST_MAX_PCT,
                        RENDER_INTERVAL_SMOOTHING_PCT);
        int64_t desired_interval_ms = render_interval.desired_interval_ms;
        int64_t smoothed_interval_ms = render_interval.render_interval_ms;
        session->render_interval_smoothed_ms = smoothed_interval_ms;
        bool periodic_log =
                (now_ms - session->last_render_control_log_at_ms) >= RENDER_CONTROL_LOG_INTERVAL_MS;
        if (periodic_log) {
            session->last_render_control_log_at_ms = now_ms;
            ct_debug(TAG,
                     "render control id=%lld designator=%s bufferedSpanMs=%lld targetLatencyMs=%lld stallEstimateMs=%lld provenGapMs=%lld sourceIntervalMs=%lld renderIntervalMs=%lld desiredIntervalMs=%lld stallActive=%d queueDepth=%d localFile=1",
                     (long long) session->session_id,
                     session->designator,
                     (long long) buffered_span_ms,
                     (long long) target_latency_ms,
                     (long long) session->stall_estimate_ms,
                     (long long) session->proven_gap_ms,
                     (long long) source_interval_ms,
                     (long long) smoothed_interval_ms,
                     (long long) desired_interval_ms,
                     session->stall_active ? 1 : 0,
                     session->render_queue_depth);
        }
        return smoothed_interval_ms;
    }
    maybe_decay_stall_estimate_locked(session, now_ms);
    maybe_decay_proven_gap_locked(session, now_ms);
    session->target_latency_ms = compute_target_latency_ms_locked(session);
    int64_t target_latency_ms = session->target_latency_ms > 0
            ? session->target_latency_ms
            : compute_target_latency_ms_locked(session);
    if (maybe_fast_relock_to_pts_locked(session, buffered_span_ms, target_latency_ms, now_ms)) {
        source_interval_ms = session->source_render_interval_ms;
        session->target_latency_ms = compute_target_latency_ms_locked(session);
        target_latency_ms = session->target_latency_ms > 0
                ? session->target_latency_ms
                : compute_target_latency_ms_locked(session);
    }
    session->stall_active =
            anomaly_detector_runtime_budget_decode_stall_active(
                    now_ms,
                    session->last_decode_at_ms,
                    source_interval_ms,
                    RENDER_GAP_FLOOR_MS,
                    3);
    if (!session->stall_active &&
        (now_ms - session->last_source_pts_relock_at_ms) >= RENDER_SOURCE_ESTIMATE_UPDATE_INTERVAL_MS) {
        int64_t pts_interval_ms = queue_pts_interval_ms_locked(session, 24);
        if (pts_interval_ms > 0 &&
            decode_delta_is_plausible_cadence(pts_interval_ms, source_interval_ms)) {
            int64_t old_interval_ms = session->source_render_interval_ms;
            apply_pts_source_interval_locked(session, pts_interval_ms, false);
            source_interval_ms = session->source_render_interval_ms;
            session->last_source_pts_relock_at_ms = now_ms;
            if (llabs(session->source_render_interval_ms - old_interval_ms) >= 2) {
                ct_debug(TAG,
                         "render source estimate relocked id=%lld designator=%s oldIntervalMs=%lld newIntervalMs=%lld ptsIntervalMs=%lld targetLatencyMs=%lld",
                         (long long) session->session_id,
                         session->designator,
                         (long long) old_interval_ms,
                         (long long) session->source_render_interval_ms,
                         (long long) pts_interval_ms,
                         (long long) session->target_latency_ms);
            }
        }
    }

    int64_t previous_interval_ms = session->render_interval_smoothed_ms > 0
            ? session->render_interval_smoothed_ms
            : source_interval_ms;
    anomaly_detector_runtime_budget_render_interval_t render_interval =
            anomaly_detector_runtime_budget_desired_render_interval_ms(
                    source_interval_ms,
                    previous_interval_ms,
                    buffered_span_ms,
                    target_latency_ms,
                    session->stall_active,
                    RENDER_INTERVAL_ADJUST_BASE_PCT,
                    RENDER_INTERVAL_ADJUST_MAX_PCT,
                    RENDER_INTERVAL_SMOOTHING_PCT);
    int64_t desired_interval_ms = render_interval.desired_interval_ms;
    int64_t smoothed_interval_ms = render_interval.render_interval_ms;
    session->render_interval_smoothed_ms = smoothed_interval_ms;

    bool periodic_log =
            (now_ms - session->last_render_control_log_at_ms) >= RENDER_CONTROL_LOG_INTERVAL_MS;
    if (periodic_log) {
        session->last_render_control_log_at_ms = now_ms;
        ct_debug(TAG,
                 "render control id=%lld designator=%s bufferedSpanMs=%lld targetLatencyMs=%lld stallEstimateMs=%lld provenGapMs=%lld sourceIntervalMs=%lld renderIntervalMs=%lld desiredIntervalMs=%lld stallActive=%d queueDepth=%d ptsIntervalMs=%lld",
                 (long long) session->session_id,
                 session->designator,
                 (long long) buffered_span_ms,
                 (long long) target_latency_ms,
                 (long long) session->stall_estimate_ms,
                 (long long) session->proven_gap_ms,
                 (long long) source_interval_ms,
                 (long long) smoothed_interval_ms,
                 (long long) desired_interval_ms,
                 session->stall_active ? 1 : 0,
                 session->render_queue_depth,
                 (long long) queue_pts_interval_ms_locked(session, 24));
    }

    return smoothed_interval_ms;
}

#if HAVE_SWSCALE
static void log_render_queue_state(ffmpeg_session_t *session, int queue_depth, bool force) {
    if (session == NULL || !session->is_render) return;
    int64_t now_ms = monotonic_ms();
    if (queue_depth >= RENDER_QUEUE_WARN_DEPTH) {
        bool first_warn = session->last_render_queue_warn_at_ms <= 0;
        bool warn_interval_elapsed =
                (now_ms - session->last_render_queue_warn_at_ms) >= RENDER_QUEUE_WARN_INTERVAL_MS;
        if (first_warn || warn_interval_elapsed) {
            session->last_render_queue_warn_at_ms = now_ms;
            int64_t interval_ms = current_render_interval_ms(session);
            int64_t approx_backlog_ms = (int64_t) queue_depth * interval_ms;
            ct_warn(TAG,
                    "render queue backlog high id=%lld designator=%s depth=%d approxBacklogMs=%lld intervalMs=%lld",
                    (long long) session->session_id,
                    session->designator,
                    queue_depth,
                    (long long) approx_backlog_ms,
                    (long long) interval_ms);
        }
    }
    int depth_delta = queue_depth - session->last_logged_render_queue_depth;
    if (depth_delta < 0) depth_delta = -depth_delta;
    int last_depth = session->last_logged_render_queue_depth;
    bool crossed_backlog_band =
            (last_depth < 8 && queue_depth >= 8) ||
            (last_depth >= 8 && queue_depth < 8);
    bool significant_depth_change =
            depth_delta >= 8 || crossed_backlog_band;
    int64_t drop_delta = session->render_drop_count -
                         session->last_logged_render_drop_count;
    if (session->last_logged_render_drop_count < 0) {
        drop_delta = session->render_drop_count;
    }
    bool significant_drop = drop_delta >= 8;
    bool periodic_log = (now_ms - session->last_render_queue_log_at_ms) >= RENDER_QUEUE_LOG_INTERVAL_MS;
    bool first_log = session->last_render_queue_log_at_ms <= 0;
    if (!force && !first_log && !periodic_log && !significant_depth_change && !significant_drop) return;

    session->last_logged_render_queue_depth = queue_depth;
    session->last_logged_render_drop_count = session->render_drop_count;
    session->last_render_queue_log_at_ms = now_ms;
}

static void clear_render_queue_slot(render_queue_slot_t *slot) {
    if (slot == NULL) return;
    av_frame_free(&slot->frame);
    av_frame_free(&slot->history_frame);
    av_frame_free(&slot->overlay_frame);
    slot->frame_id = 0;
    slot->local_ad_cadence_ordinal = 0;
    slot->generation_id = 0;
    slot->source_ts_us = 0;
    slot->enqueued_at_ms = 0;
    slot->width = 0;
    slot->height = 0;
    slot->pixel_format = AV_PIX_FMT_NONE;
    slot->analyzed = false;
}

static void clear_local_playback_history(ffmpeg_session_t *session) {
    if (session == NULL) return;
    for (int i = 0; i < LOCAL_PLAYBACK_HISTORY_CAPACITY; i++) {
        clear_render_queue_slot(&session->local_playback_history[i]);
    }
    session->local_playback_history_count = 0;
    session->local_playback_history_next = 0;
    session->local_playback_history_offset = 0;
    session->local_playback_history_replay_active = false;
}

static void clear_render_queue(ffmpeg_session_t *session) {
    if (session == NULL) return;
    if (session->render_queue != NULL && session->render_queue_capacity > 0) {
        for (int i = 0; i < session->render_queue_depth; i++) {
            int idx = anomaly_detector_runtime_budget_queue_offset_index(
                    session->render_queue_head,
                    i,
                    session->render_queue_capacity);
            clear_render_queue_slot(&session->render_queue[idx]);
        }
    }
    session->render_queue_head = 0;
    session->render_queue_depth = 0;
}

static void reset_render_timing_state_locked(ffmpeg_session_t *session,
                                             bool observe_startup,
                                             bool clear_playback_metrics) {
    if (session == NULL) return;
    session->source_render_interval_ms = RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    session->render_interval_smoothed_ms = RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    session->next_render_due_ms = 0;
    session->last_render_control_log_at_ms = 0;
    session->startup_observation_active = observe_startup;
    session->startup_started_at_ms = monotonic_ms();
    session->source_interval_confidence = 0;
    session->stall_estimate_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    session->target_latency_ms = 1000;
    session->stall_active = false;
    session->last_decode_at_ms = 0;
    session->last_valid_pts_us = 0;
    session->last_source_estimate_update_at_ms = 0;
    session->last_gap_at_ms = 0;
    session->last_stall_decay_at_ms = 0;
    session->proven_gap_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    session->last_proven_gap_decay_at_ms = 0;
    memset(session->cadence_samples_ms, 0, sizeof(session->cadence_samples_ms));
    session->cadence_sample_count = 0;
    session->cadence_sample_head = 0;
    memset(session->gap_samples_ms, 0, sizeof(session->gap_samples_ms));
    session->gap_sample_count = 0;
    session->gap_sample_head = 0;
    if (clear_playback_metrics) {
        session->local_playback_last_pts_us = 0;
        session->local_playback_last_render_at_ms = 0;
        session->local_playback_first_pts_us = 0;
        session->local_playback_first_render_at_ms = 0;
        session->local_playback_display_pts_us = 0;
        session->local_playback_last_admit_pts_us = 0;
        session->local_playback_last_admit_at_ms = 0;
        session->local_playback_nominal_interval_ms = 0;
        R2CLocalPlaybackCadenceInit(&session->local_playback_render_cadence);
        R2CLocalPlaybackCadenceInit(&session->local_playback_admit_cadence);
        session->local_playback_pts_repair_count = 0;
        memset(session->local_playback_timing_pts_us, 0, sizeof(session->local_playback_timing_pts_us));
        memset(session->local_playback_timing_render_at_ms, 0, sizeof(session->local_playback_timing_render_at_ms));
        session->local_playback_timing_count = 0;
        session->local_playback_timing_next = 0;
        session->local_playback_history_replay_active = false;
        session->anomaly_process_frame_count = 0;
        session->anomaly_annotated_frame_count = 0;
        anomaly_detector_annotation_cadence_snapshot_state_init(
                &session->anomaly_annotation_cadence);
        anomaly_detector_annotation_cadence_snapshot_state_init(
                &session->anomaly_overlay_publication_cadence);
        session->anomaly_annotation_last_source_ts_us = 0;
        session->anomaly_process_total_us = 0;
        session->anomaly_process_max_us = 0;
        session->anomaly_process_last_us = 0;
        session->ad_forwarded_without_analysis_count = 0;
        session->ad_full_queue_disable_count = 0;
        session->ad_analyzed_rendered_frame_count = 0;
        session->ad_bypassed_rendered_frame_count = 0;
        session->ad_input_queue_depth_max = 0;
        session->ad_input_enqueued_count = 0;
        session->ad_worker_dequeued_frame_count = 0;
        session->anomaly_reg_health_healthy_count = 0;
        session->anomaly_reg_health_soft_count = 0;
        session->anomaly_reg_health_hard_count = 0;
        session->anomaly_reg_health_invalid_count = 0;
        session->anomaly_rescan_full_count = 0;
        session->anomaly_rescan_partial_count = 0;
        session->anomaly_rescan_target_only_count = 0;
        session->anomaly_rescan_stride_skip_count = 0;
        session->anomaly_last_registration_health = ANOMALY_REG_HEALTH_UNKNOWN;
        session->anomaly_last_rescan_mode = ANOMALY_RESCAN_MODE_UNSET;
        session->latest_anomaly_debug_summary[0] = '\0';
        session->latest_ad_bridge_debug_summary[0] = '\0';
        session->latest_local_playback_ad_decision[0] = '\0';
        session->ad_worker_processed_frame_count = 0;
        session->ad_worker_skipped_frame_count = 0;
        session->ad_worker_annotated_frame_count = 0;
        session->ad_worker_overlay_enqueued_count = 0;
        session->local_ad_sidecar_offered_count = 0;
        session->local_ad_sidecar_enqueued_count = 0;
        session->local_ad_sidecar_dropped_count = 0;
        session->local_ad_sidecar_completed_count = 0;
        session->local_ad_sidecar_late_count = 0;
        session->local_ad_startup_preroll_started_at_ms = monotonic_ms();
        session->local_ad_startup_preroll_bypassed_count = 0;
        session->local_ad_startup_preroll_complete = false;
        session->local_ad_cadence_frame_counter = 0;
        session->ad_pressure_frame_counter = 0;
        session->ad_pressure_mode = AD_PRESSURE_MODE_NORMAL;
    }
}

static void reset_anomaly_tracking_state(ffmpeg_session_t *session) {
    if (session == NULL) return;
    if (session->anomaly_lock_ready) {
        pthread_mutex_lock(&session->anomaly_lock);
        anomaly_state_reset(&session->anomaly_state);
        pthread_mutex_unlock(&session->anomaly_lock);
    } else {
        anomaly_state_reset(&session->anomaly_state);
    }
    session->anomaly_process_frame_count = 0;
    session->anomaly_annotated_frame_count = 0;
    anomaly_detector_annotation_cadence_snapshot_state_init(
            &session->anomaly_annotation_cadence);
    anomaly_detector_annotation_cadence_snapshot_state_init(
            &session->anomaly_overlay_publication_cadence);
    session->anomaly_annotation_last_source_ts_us = 0;
    session->anomaly_process_total_us = 0;
    session->anomaly_process_max_us = 0;
    session->anomaly_process_last_us = 0;
    session->ad_forwarded_without_analysis_count = 0;
    session->ad_full_queue_disable_count = 0;
    session->ad_analyzed_rendered_frame_count = 0;
    session->ad_bypassed_rendered_frame_count = 0;
    session->ad_input_queue_depth_max = 0;
    session->ad_input_enqueued_count = 0;
    session->ad_worker_dequeued_frame_count = 0;
    session->anomaly_reg_health_healthy_count = 0;
    session->anomaly_reg_health_soft_count = 0;
    session->anomaly_reg_health_hard_count = 0;
    session->anomaly_reg_health_invalid_count = 0;
    session->anomaly_rescan_full_count = 0;
    session->anomaly_rescan_partial_count = 0;
    session->anomaly_rescan_target_only_count = 0;
    session->anomaly_rescan_stride_skip_count = 0;
    session->anomaly_last_registration_health = ANOMALY_REG_HEALTH_UNKNOWN;
    session->anomaly_last_rescan_mode = ANOMALY_RESCAN_MODE_UNSET;
    session->latest_anomaly_debug_summary[0] = '\0';
    session->latest_ad_bridge_debug_summary[0] = '\0';
    session->latest_local_playback_ad_decision[0] = '\0';
    session->ad_worker_processed_frame_count = 0;
    session->ad_worker_skipped_frame_count = 0;
    session->ad_worker_annotated_frame_count = 0;
    session->ad_worker_overlay_enqueued_count = 0;
    session->local_ad_sidecar_offered_count = 0;
    session->local_ad_sidecar_enqueued_count = 0;
    session->local_ad_sidecar_dropped_count = 0;
    session->local_ad_sidecar_completed_count = 0;
    session->local_ad_sidecar_late_count = 0;
    session->local_ad_startup_preroll_started_at_ms = monotonic_ms();
    session->local_ad_startup_preroll_bypassed_count = 0;
    session->local_ad_startup_preroll_complete = false;
    session->local_ad_cadence_frame_counter = 0;
    session->ad_pressure_frame_counter = 0;
    session->ad_pressure_mode = AD_PRESSURE_MODE_NORMAL;
}

static void pace_local_file_playback(ffmpeg_session_t *session, int64_t pts_us) {
    if (session == NULL) return;
    int64_t target_interval_us = R2CLocalPlaybackCadenceNextIntervalUs(
            &session->local_playback_render_cadence,
            pts_us);
    if (session->local_playback_last_render_at_ms > 0) {
        int64_t target_interval_ms = (target_interval_us + 500) / 1000;
        int64_t target_ms = session->local_playback_last_render_at_ms + target_interval_ms;
        while (session_running(session)) {
            int64_t now_ms = monotonic_ms();
            if (now_ms >= target_ms) break;
            int64_t sleep_us = (target_ms - now_ms) * 1000;
            if (sleep_us > 5000) sleep_us = 5000;
            if (sleep_us < 1000) sleep_us = 1000;
            usleep((useconds_t) sleep_us);
        }
    }
    if (pts_us > 0) {
        session->local_playback_last_pts_us = pts_us;
        session->local_playback_display_pts_us = pts_us;
    }
}

static int64_t pace_local_file_decode_admission(ffmpeg_session_t *session,
                                                int64_t pts_us,
                                                int64_t decoded_at_ms) {
    if (session == NULL || !is_local_file_source(session)) {
        return decoded_at_ms;
    }
    int64_t target_interval_us = R2CLocalPlaybackCadenceNextIntervalUs(
            &session->local_playback_admit_cadence,
            pts_us);
    int64_t target_interval_ms = (target_interval_us + 500) / 1000;
    while (session_running(session)) {
        int64_t now_ms = monotonic_ms();
        int64_t target_at_ms = session->local_playback_last_admit_at_ms + target_interval_ms;
        int64_t delay_ms = target_at_ms > now_ms ? target_at_ms - now_ms : 0;
        if (delay_ms <= 0) {
            break;
        }
        int64_t sleep_us = delay_ms * 1000;
        if (sleep_us > 5000) sleep_us = 5000;
        if (sleep_us < 1000) sleep_us = 1000;
        usleep((useconds_t) sleep_us);
    }
    int64_t admitted_at_ms = monotonic_ms();
    if (pts_us > 0) {
        session->local_playback_last_admit_pts_us = pts_us;
    }
    session->local_playback_last_admit_at_ms = admitted_at_ms;
    return admitted_at_ms;
}

static void record_local_playback_timing_sample(ffmpeg_session_t *session,
                                                int64_t pts_us,
                                                int64_t rendered_at_ms) {
    if (session == NULL || pts_us <= 0 || rendered_at_ms <= 0) return;
    if (session->local_playback_first_pts_us <= 0) {
        session->local_playback_first_pts_us = pts_us;
    }
    if (session->local_playback_first_render_at_ms <= 0) {
        session->local_playback_first_render_at_ms = rendered_at_ms;
    }
    session->local_playback_last_pts_us = pts_us;
    session->local_playback_last_render_at_ms = rendered_at_ms;
    session->local_playback_display_pts_us = pts_us;

    anomaly_detector_runtime_budget_local_playback_append_t append =
            anomaly_detector_runtime_budget_local_playback_append(
                    session->local_playback_timing_next,
                    session->local_playback_timing_count,
                    LOCAL_PLAYBACK_HISTORY_CAPACITY);
    if (!append.valid) return;
    session->local_playback_timing_pts_us[append.slot_index] = pts_us;
    session->local_playback_timing_render_at_ms[append.slot_index] = rendered_at_ms;
    session->local_playback_timing_next = append.next_index;
    session->local_playback_timing_count = append.count;
}

static bool recent_local_playback_timing_span(const ffmpeg_session_t *session,
                                              int64_t *out_first_pts_us,
                                              int64_t *out_last_pts_us,
                                              int64_t *out_first_render_at_ms,
                                              int64_t *out_last_render_at_ms) {
    if (session == NULL ||
        session->local_playback_timing_count < 2 ||
        out_first_pts_us == NULL ||
        out_last_pts_us == NULL ||
        out_first_render_at_ms == NULL ||
        out_last_render_at_ms == NULL) {
        return false;
    }

    anomaly_detector_runtime_budget_local_playback_timing_indices_t indices =
            anomaly_detector_runtime_budget_local_playback_timing_indices(
                    session->local_playback_timing_next,
                    session->local_playback_timing_count,
                    LOCAL_PLAYBACK_HISTORY_CAPACITY);
    if (!indices.valid) {
        return false;
    }
    int64_t first_pts_us = session->local_playback_timing_pts_us[indices.oldest_index];
    int64_t last_pts_us = session->local_playback_timing_pts_us[indices.newest_index];
    int64_t first_render_at_ms = session->local_playback_timing_render_at_ms[indices.oldest_index];
    int64_t last_render_at_ms = session->local_playback_timing_render_at_ms[indices.newest_index];
    if (!anomaly_detector_runtime_budget_local_playback_timing_span_is_valid(
                first_pts_us,
                last_pts_us,
                first_render_at_ms,
                last_render_at_ms)) {
        return false;
    }

    *out_first_pts_us = first_pts_us;
    *out_last_pts_us = last_pts_us;
    *out_first_render_at_ms = first_render_at_ms;
    *out_last_render_at_ms = last_render_at_ms;
    return true;
}

static bool wait_for_local_playback_advance(ffmpeg_session_t *session) {
    if (session == NULL || !is_local_file_source(session)) return true;
    while (session_running(session)) {
        anomaly_detector_runtime_budget_local_playback_advance_t advance;
        pthread_mutex_lock(&g_lock);
        advance = anomaly_detector_runtime_budget_local_playback_advance(
                session->local_playback_paused,
                session->local_playback_step_budget);
        if (advance.consume_step) {
            session->local_playback_step_budget = advance.step_budget;
        }
        pthread_mutex_unlock(&g_lock);
        if (advance.advance) {
            return true;
        }
        usleep(5000);
    }
    return false;
}

static int local_ad_input_capacity_for_source_interval_ms(int64_t source_interval_ms) {
    return anomaly_runtime_pressure_backlog_frame_capacity(
            LOCAL_AD_INPUT_QUEUE_BACKLOG_MS,
            source_interval_ms,
            RENDER_SOURCE_INTERVAL_DEFAULT_MS,
            2,
            AD_INPUT_QUEUE_HARD_CAPACITY);
}

static int live_ad_input_capacity_for_source_interval_ms(int64_t source_interval_ms) {
    return anomaly_runtime_pressure_live_ad_backlog_frame_capacity(
            source_interval_ms,
            RENDER_SOURCE_INTERVAL_DEFAULT_MS,
            AD_INPUT_QUEUE_HARD_CAPACITY);
}

static void trim_render_queue_to_latest(ffmpeg_session_t *session, int keep_latest) {
    if (session == NULL ||
        session->render_queue == NULL ||
        session->render_queue_capacity <= 0 ||
        session->render_queue_depth <= keep_latest ||
        keep_latest < 1) {
        return;
    }

    anomaly_detector_runtime_budget_queue_trim_t trim =
            anomaly_detector_runtime_budget_queue_trim_state(
                    session->render_queue_head,
                    session->render_queue_depth,
                    keep_latest,
                    session->render_queue_capacity);
    if (!trim.valid) {
        return;
    }
    int drop_count = trim.drop_count;
    for (int i = 0; i < drop_count; i++) {
        int idx = anomaly_detector_runtime_budget_queue_offset_index(
                session->render_queue_head,
                i,
                session->render_queue_capacity);
        clear_render_queue_slot(&session->render_queue[idx]);
    }

    session->render_queue_head = trim.head;
    session->render_queue_depth = trim.depth;
    session->render_drop_count += drop_count;

    ct_warn(TAG,
            "render queue trimmed to live edge id=%lld designator=%s dropped=%d keep=%d",
            (long long) session->session_id,
            session->designator,
            drop_count,
            keep_latest);
}

static bool attach_local_ad_overlay_to_pending_render_locked(ffmpeg_session_t *session,
                                                            render_queue_slot_t *packet) {
    if (session == NULL || packet == NULL || packet->overlay_frame == NULL ||
        session->render_queue == NULL || session->render_queue_capacity <= 0 ||
        session->render_queue_depth <= 0) {
        return false;
    }
    for (int i = 0; i < session->render_queue_depth; i++) {
        int idx = anomaly_detector_runtime_budget_queue_offset_index(
                session->render_queue_head,
                i,
                session->render_queue_capacity);
        render_queue_slot_t *slot = &session->render_queue[idx];
        if (slot->frame_id != packet->frame_id ||
            slot->generation_id != packet->generation_id) {
            continue;
        }
        if (slot->overlay_frame != NULL) {
            return false;
        }
        slot->overlay_frame = packet->overlay_frame;
        packet->overlay_frame = NULL;
        slot->analyzed = packet->analyzed;
        session->ad_worker_overlay_enqueued_count += 1;
        return true;
    }
    return false;
}

static int compute_trim_keep_latest_locked(const ffmpeg_session_t *session,
                                           int64_t source_interval_ms,
                                           int64_t target_latency_ms) {
    if (session == NULL) return 8;
    int64_t default_source_interval_ms = session->source_render_interval_ms > 0
            ? session->source_render_interval_ms
            : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    int64_t default_target_latency_ms = session->target_latency_ms > 0
            ? session->target_latency_ms
            : RENDER_TARGET_LATENCY_MIN_MS;
    return anomaly_detector_runtime_budget_trim_keep_latest_frames(
            source_interval_ms,
            target_latency_ms,
            default_source_interval_ms,
            default_target_latency_ms,
            8,
            36);
}

static int compute_render_queue_hard_cap_locked(const ffmpeg_session_t *session,
                                                int keep_latest) {
    if (session == NULL) return 24;
    int fallback_keep_latest = compute_trim_keep_latest_locked(
            session,
            session->source_render_interval_ms,
            session->target_latency_ms);
    return anomaly_detector_runtime_budget_render_queue_hard_cap(
            keep_latest,
            fallback_keep_latest,
            8,
            12,
            24,
            72);
}

static void clear_ad_input_queue(ffmpeg_session_t *session);

static void destroy_render_queue_storage(ffmpeg_session_t *session) {
    if (session == NULL) return;
    clear_render_queue(session);
    clear_local_playback_history(session);
    if (session->render_queue != NULL) {
        free(session->render_queue);
        session->render_queue = NULL;
    }
    session->render_queue_capacity = 0;
}

static void destroy_ad_input_queue_storage(ffmpeg_session_t *session) {
    if (session == NULL) return;
    clear_ad_input_queue(session);
    if (session->ad_input_queue != NULL) {
        free(session->ad_input_queue);
        session->ad_input_queue = NULL;
    }
    session->ad_input_queue_capacity = 0;
}

static bool ensure_render_queue_capacity(ffmpeg_session_t *session, int min_capacity) {
    if (session == NULL) return false;
    if (min_capacity < 1) min_capacity = 1;
    if (session->render_queue != NULL && session->render_queue_capacity >= min_capacity) {
        return true;
    }

    int new_capacity = anomaly_detector_runtime_budget_render_queue_storage_capacity(
            session->render_queue_capacity,
            min_capacity,
            RENDER_QUEUE_INITIAL_CAPACITY,
            4096,
            1024);

    render_queue_slot_t *new_queue =
            (render_queue_slot_t *) calloc((size_t) new_capacity, sizeof(render_queue_slot_t));
    if (new_queue == NULL) return false;

    if (session->render_queue != NULL && session->render_queue_depth > 0 && session->render_queue_capacity > 0) {
        for (int i = 0; i < session->render_queue_depth; i++) {
            int src_idx = anomaly_detector_runtime_budget_queue_offset_index(
                    session->render_queue_head,
                    i,
                    session->render_queue_capacity);
            new_queue[i] = session->render_queue[src_idx];
        }
    }

    free(session->render_queue);
    session->render_queue = new_queue;
    session->render_queue_capacity = new_capacity;
    session->render_queue_head = 0;
    return true;
}

static bool ensure_ad_input_queue_capacity(ffmpeg_session_t *session, int min_capacity) {
    if (session == NULL) return false;
    if (session->ad_input_queue != NULL && session->ad_input_queue_capacity >= min_capacity) {
        return true;
    }

    int new_capacity = anomaly_runtime_pressure_queue_storage_capacity(
            session->ad_input_queue_capacity,
            min_capacity,
            AD_INPUT_QUEUE_INITIAL_CAPACITY,
            AD_INPUT_QUEUE_HARD_CAPACITY);
    if (new_capacity <= 0) {
        return false;
    }

    render_queue_slot_t *new_queue =
            (render_queue_slot_t *) calloc((size_t) new_capacity, sizeof(render_queue_slot_t));
    if (new_queue == NULL) return false;

    if (session->ad_input_queue != NULL &&
        session->ad_input_queue_depth > 0 &&
        session->ad_input_queue_capacity > 0) {
        for (int i = 0; i < session->ad_input_queue_depth; i++) {
            int src_idx = anomaly_detector_runtime_budget_queue_offset_index(
                    session->ad_input_queue_head,
                    i,
                    session->ad_input_queue_capacity);
            new_queue[i] = session->ad_input_queue[src_idx];
        }
    }

    free(session->ad_input_queue);
    session->ad_input_queue = new_queue;
    session->ad_input_queue_capacity = new_capacity;
    session->ad_input_queue_head = 0;
    return true;
}

static int render_queue_tail_index(const ffmpeg_session_t *session) {
    if (session == NULL || session->render_queue_capacity <= 0) return 0;
    return anomaly_detector_runtime_budget_queue_tail_index(
            session->render_queue_head,
            session->render_queue_depth,
            session->render_queue_capacity);
}

static bool enqueue_render_packet_locked(ffmpeg_session_t *session,
                                         render_queue_slot_t *packet) {
    if (session == NULL || packet == NULL || packet->frame == NULL) return false;
    // render_thread_stop is protected by render_lock.  Calling
    // session_stopping() here would acquire g_lock in the reverse order.
    if (session->render_thread_stop) return false;
    if (!ensure_render_queue_capacity(session, session->render_queue_depth + 1)) return false;
    int tail_idx = render_queue_tail_index(session);
    session->render_queue[tail_idx] = *packet;
    memset(packet, 0, sizeof(*packet));
    session->render_queue[tail_idx].pixel_format =
            session->render_queue[tail_idx].pixel_format == AV_PIX_FMT_NONE
            ? session->render_queue[tail_idx].frame->format
            : session->render_queue[tail_idx].pixel_format;
    session->render_queue_depth += 1;
    trace_set_counter("RID2C render_queue_depth", session->render_queue_depth);
    if (!is_local_file_source(session)) {
        int keep_latest = compute_trim_keep_latest_locked(
                session,
                session->source_render_interval_ms,
                session->target_latency_ms);
        int hard_cap = compute_render_queue_hard_cap_locked(session, keep_latest);
        if (session->render_queue_depth > hard_cap) {
            trim_render_queue_to_latest(session, keep_latest);
        }
    }
    log_render_queue_state(session, session->render_queue_depth, false);
    return true;
}

static bool enqueue_render_frame(ffmpeg_session_t *session,
                                 AVFrame *decoded,
                                 AVFrame *history_frame,
                                 AVFrame *overlay_frame,
                                 bool analyzed,
                                 int64_t frame_id,
                                 int64_t generation_id,
                                 int64_t source_ts_us,
                                 int64_t enqueued_at_ms) {
    if (session == NULL || session->render_thread_stop || !frame_looks_queueable(decoded)) {
        if (session != NULL && session->anomaly_troubleshooting_debug) {
            ct_warn(TAG,
                    "enqueue_render_frame dropped during shutdown/invalid frame id=%lld designator=%s decoded=%p w=%d h=%d fmt=%d data0=%p",
                    (long long) session->session_id,
                    session->designator,
                    (void *) decoded,
                    decoded != NULL ? decoded->width : 0,
                    decoded != NULL ? decoded->height : 0,
                    decoded != NULL ? decoded->format : -1,
                    decoded != NULL ? (void *) decoded->data[0] : NULL);
        }
        av_frame_free(&overlay_frame);
        return false;
    }
    render_queue_slot_t packet;
    memset(&packet, 0, sizeof(packet));
    packet.frame = av_frame_clone(decoded);
    if (packet.frame == NULL) {
        av_frame_free(&overlay_frame);
        return false;
    }
    if (history_frame != NULL) {
        if (!frame_looks_queueable(history_frame)) {
            av_frame_free(&overlay_frame);
            clear_render_queue_slot(&packet);
            return false;
        }
        packet.history_frame = av_frame_clone(history_frame);
        if (packet.history_frame == NULL) {
            av_frame_free(&overlay_frame);
            clear_render_queue_slot(&packet);
            return false;
        }
    }
    if (overlay_frame != NULL && !frame_looks_queueable(overlay_frame)) {
        av_frame_free(&overlay_frame);
        clear_render_queue_slot(&packet);
        return false;
    }
    packet.overlay_frame = overlay_frame;
    packet.frame_id = frame_id;
    packet.generation_id = generation_id;
    packet.source_ts_us = source_ts_us;
    packet.enqueued_at_ms = enqueued_at_ms;
    packet.width = decoded->width;
    packet.height = decoded->height;
    packet.pixel_format = decoded->format;
    packet.analyzed = analyzed;
    bool ok = enqueue_render_packet_locked(session, &packet);
    if (!ok) {
        clear_render_queue_slot(&packet);
    }
    return ok;
}

static int ad_input_queue_tail_index(const ffmpeg_session_t *session) {
    if (session == NULL || session->ad_input_queue_capacity <= 0) return 0;
    return anomaly_detector_runtime_budget_queue_tail_index(
            session->ad_input_queue_head,
            session->ad_input_queue_depth,
            session->ad_input_queue_capacity);
}

static void update_ad_bridge_debug_summary(ffmpeg_session_t *session,
                                           const char *stage,
                                           const render_queue_slot_t *packet,
                                           bool processed,
                                           bool analyzed,
                                           bool overlay_present,
                                           bool skipped) {
    if (session == NULL || !session->anomaly_troubleshooting_debug) return;
    snprintf(session->latest_ad_bridge_debug_summary,
             sizeof(session->latest_ad_bridge_debug_summary),
             "bridge stage=%s enq=%lld deq=%lld proc=%lld skip=%lld ann=%lld overlay=%lld q=%d/%lld last[id=%lld gen=%lld processed=%d analyzed=%d overlay=%d skipped=%d ts=%.3fs]",
             stage != NULL ? stage : "unknown",
             (long long) session->ad_input_enqueued_count,
             (long long) session->ad_worker_dequeued_frame_count,
             (long long) session->ad_worker_processed_frame_count,
             (long long) session->ad_worker_skipped_frame_count,
             (long long) session->ad_worker_annotated_frame_count,
             (long long) session->ad_worker_overlay_enqueued_count,
             session->ad_input_queue_depth,
             (long long) session->ad_input_queue_depth_max,
             packet != NULL ? (long long) packet->frame_id : 0LL,
             packet != NULL ? (long long) packet->generation_id : 0LL,
             processed ? 1 : 0,
             analyzed ? 1 : 0,
             overlay_present ? 1 : 0,
             skipped ? 1 : 0,
             (packet != NULL && packet->source_ts_us > 0)
                     ? ((double) packet->source_ts_us / 1000000.0)
                     : 0.0);
}

static void clear_ad_input_queue(ffmpeg_session_t *session) {
    if (session == NULL || session->ad_input_queue == NULL) return;
    for (int i = 0; i < session->ad_input_queue_depth; i++) {
        int idx = anomaly_detector_runtime_budget_queue_offset_index(
                session->ad_input_queue_head,
                i,
                session->ad_input_queue_capacity);
        clear_render_queue_slot(&session->ad_input_queue[idx]);
    }
    session->ad_input_queue_head = 0;
    session->ad_input_queue_depth = 0;
    trace_set_counter("RID2C ad_queue_depth", 0);
    update_ad_bridge_debug_summary(session, "queue-cleared", NULL, false, false, false, false);
}

static int drop_oldest_ad_input_frames_locked(ffmpeg_session_t *session, int drop_count) {
    if (session == NULL || session->ad_input_queue == NULL || drop_count <= 0) return 0;
    int dropped = 0;
    for (int i = 0; i < drop_count && session->ad_input_queue_depth > 0; i++) {
        int drop_idx = session->ad_input_queue_head;
        clear_render_queue_slot(&session->ad_input_queue[drop_idx]);
        anomaly_detector_runtime_budget_queue_pop_t pop =
                anomaly_detector_runtime_budget_queue_pop_state(
                        session->ad_input_queue_head,
                        session->ad_input_queue_depth,
                        session->ad_input_queue_capacity);
        session->ad_input_queue_head = pop.head;
        session->ad_input_queue_depth = pop.depth;
        dropped++;
    }
    if (dropped > 0) {
        trace_set_counter("RID2C ad_queue_depth", session->ad_input_queue_depth);
    }
    return dropped;
}

static bool enqueue_ad_input_frame_locked(ffmpeg_session_t *session,
                                          AVFrame *decoded,
                                          AVFrame *history_frame,
                                          int64_t frame_id,
                                          int64_t local_ad_cadence_ordinal,
                                          int64_t generation_id,
                                          int64_t source_ts_us,
                                          int64_t enqueued_at_ms) {
    // ad_thread_stop is protected by ad_lock.  Do not call session_stopping()
    // here: it takes g_lock and would invert the global -> per-session hierarchy.
    if (session == NULL || session->ad_thread_stop ||
        !frame_looks_queueable(decoded)) {
        return false;
    }
    if (!is_local_file_source(session)) {
        int desired_depth =
                live_ad_input_capacity_for_source_interval_ms(session->source_render_interval_ms);
        int drop_count = anomaly_runtime_pressure_oldest_drop_count_for_admission(
                session->ad_input_queue_depth,
                desired_depth);
        int dropped = drop_oldest_ad_input_frames_locked(session, drop_count);
        if (dropped > 0 && session->anomaly_troubleshooting_debug) {
            ct_debug(TAG,
                     "live AD queue trimmed to fresh edge id=%lld designator=%s dropped=%d desiredDepth=%d qDepth=%d",
                     (long long) session->session_id,
                     session->designator,
                     dropped,
                     desired_depth,
                     session->ad_input_queue_depth);
        }
    }
    if (!ensure_ad_input_queue_capacity(session, session->ad_input_queue_depth + 1)) return false;
    int tail_idx = ad_input_queue_tail_index(session);
    render_queue_slot_t *slot = &session->ad_input_queue[tail_idx];
    memset(slot, 0, sizeof(*slot));
    slot->frame = av_frame_clone(decoded);
    if (slot->frame == NULL) return false;
    if (history_frame != NULL) {
        if (!frame_looks_queueable(history_frame)) {
            clear_render_queue_slot(slot);
            return false;
        }
        slot->history_frame = av_frame_clone(history_frame);
        if (slot->history_frame == NULL) {
            clear_render_queue_slot(slot);
            return false;
        }
    }
    slot->frame_id = frame_id;
    slot->local_ad_cadence_ordinal = local_ad_cadence_ordinal;
    slot->generation_id = generation_id;
    slot->source_ts_us = source_ts_us;
    slot->enqueued_at_ms = enqueued_at_ms;
    slot->width = decoded->width;
    slot->height = decoded->height;
    slot->pixel_format = decoded->format;
    session->ad_input_queue_depth += 1;
    session->ad_input_enqueued_count += 1;
    if (session->ad_input_queue_depth > session->ad_input_queue_depth_max) {
        session->ad_input_queue_depth_max = session->ad_input_queue_depth;
    }
    trace_set_counter("RID2C ad_queue_depth", session->ad_input_queue_depth);
    update_ad_bridge_debug_summary(session, "enqueued", slot, false, false, false, false);
    if (session->anomaly_troubleshooting_debug &&
        (session->ad_input_enqueued_count <= 3 ||
         (session->ad_input_enqueued_count % 60) == 0 ||
         session->ad_input_queue_depth >= session->ad_input_queue_capacity)) {
        ct_debug(TAG,
                 "ad input enqueued id=%lld designator=%s enq=%lld qDepth=%d frame=%lld gen=%lld ts=%.3fs",
                 (long long) session->session_id,
                 session->designator,
                 (long long) session->ad_input_enqueued_count,
                 session->ad_input_queue_depth,
                 (long long) slot->frame_id,
                 (long long) slot->generation_id,
                 slot->source_ts_us > 0 ? ((double) slot->source_ts_us / 1000000.0) : 0.0);
    }
    return true;
}

static bool dequeue_ad_input_frame_locked(ffmpeg_session_t *session,
                                          render_queue_slot_t *out_packet) {
    if (session == NULL || out_packet == NULL || session->ad_input_queue_depth <= 0) return false;
    int idx = session->ad_input_queue_head;
    *out_packet = session->ad_input_queue[idx];
    memset(&session->ad_input_queue[idx], 0, sizeof(session->ad_input_queue[idx]));
    anomaly_detector_runtime_budget_queue_pop_t pop =
            anomaly_detector_runtime_budget_queue_pop_state(
                    session->ad_input_queue_head,
                    session->ad_input_queue_depth,
                    session->ad_input_queue_capacity);
    session->ad_input_queue_head = pop.head;
    session->ad_input_queue_depth = pop.depth;
    trace_set_counter("RID2C ad_queue_depth", session->ad_input_queue_depth);
    session->ad_worker_dequeued_frame_count += 1;
    update_ad_bridge_debug_summary(session, "dequeued", out_packet, false, false, false, false);
    return true;
}

static bool enqueue_local_ad_input_best_effort_locked(ffmpeg_session_t *session,
                                                      AVFrame *decoded,
                                                      AVFrame *history_frame,
                                                      int64_t frame_id,
                                                      int64_t local_ad_cadence_ordinal,
                                                      int64_t generation_id,
                                                      int64_t source_ts_us,
                                                      int64_t enqueued_at_ms) {
    if (session == NULL) return false;
    session->local_ad_sidecar_offered_count += 1;
    trace_set_counter("RID2C local_ad_offered", session->local_ad_sidecar_offered_count);

    int desired_depth =
            local_ad_input_capacity_for_source_interval_ms(session->source_render_interval_ms);
    int drop_count = anomaly_runtime_pressure_oldest_drop_count_for_admission(
            session->ad_input_queue_depth,
            desired_depth);
    int dropped = drop_oldest_ad_input_frames_locked(session, drop_count);
    if (dropped > 0) {
        session->local_ad_sidecar_dropped_count += dropped;
        trace_set_counter("RID2C local_ad_dropped", session->local_ad_sidecar_dropped_count);
    }

    bool enqueued = enqueue_ad_input_frame_locked(
            session,
            decoded,
            history_frame,
            frame_id,
            local_ad_cadence_ordinal,
            generation_id,
            source_ts_us,
            enqueued_at_ms);
    if (enqueued) {
        session->local_ad_sidecar_enqueued_count += 1;
        trace_set_counter("RID2C local_ad_enqueued", session->local_ad_sidecar_enqueued_count);
    } else {
        session->local_ad_sidecar_dropped_count += 1;
        trace_set_counter("RID2C local_ad_dropped", session->local_ad_sidecar_dropped_count);
    }
    return enqueued;
}

static void append_local_playback_history_locked(ffmpeg_session_t *session,
                                                 AVFrame *decoded,
                                                 int64_t source_ts_us,
                                                 int64_t rendered_at_ms) {
    if (session == NULL || decoded == NULL) return;
    AVFrame *cloned = av_frame_clone(decoded);
    if (cloned == NULL) return;

    anomaly_detector_runtime_budget_local_playback_append_t append =
            anomaly_detector_runtime_budget_local_playback_append(
                    session->local_playback_history_next,
                    session->local_playback_history_count,
                    LOCAL_PLAYBACK_HISTORY_CAPACITY);
    if (!append.valid) {
        av_frame_free(&cloned);
        return;
    }
    int slot_idx = append.slot_index;
    clear_render_queue_slot(&session->local_playback_history[slot_idx]);
    session->local_playback_history[slot_idx].frame = cloned;
    session->local_playback_history[slot_idx].source_ts_us = source_ts_us;
    session->local_playback_history[slot_idx].enqueued_at_ms = rendered_at_ms;
    session->local_playback_history_next = append.next_index;
    session->local_playback_history_count = append.count;
    session->local_playback_history_offset = 0;
}

static AVFrame *clone_local_playback_history_frame_locked(ffmpeg_session_t *session,
                                                          int history_offset,
                                                          int64_t *out_source_ts_us) {
    if (session == NULL || session->local_playback_history_count <= 0) return NULL;
    anomaly_detector_runtime_budget_local_playback_history_slot_t history_slot =
            anomaly_detector_runtime_budget_local_playback_history_slot(
                    session->local_playback_history_next,
                    session->local_playback_history_count,
                    history_offset,
                    LOCAL_PLAYBACK_HISTORY_CAPACITY);
    if (!history_slot.valid) return NULL;

    render_queue_slot_t *slot = &session->local_playback_history[history_slot.slot_index];
    if (slot->frame == NULL) return NULL;
    AVFrame *cloned = av_frame_clone(slot->frame);
    if (cloned == NULL) return NULL;
    if (out_source_ts_us != NULL) {
        *out_source_ts_us = slot->source_ts_us;
    }
    return cloned;
}

static bool dequeue_due_render_frame_locked(ffmpeg_session_t *session,
                                            AVFrame **out_frame,
                                            AVFrame **out_history_frame,
                                            AVFrame **out_overlay_frame,
                                            bool *out_analyzed,
                                            int64_t *out_source_ts_us,
                                            int64_t *out_render_latency_ms) {
    if (session == NULL ||
        out_frame == NULL ||
        out_history_frame == NULL ||
        out_overlay_frame == NULL ||
        out_analyzed == NULL ||
        out_source_ts_us == NULL ||
        out_render_latency_ms == NULL ||
        session->render_queue == NULL ||
        session->render_queue_capacity <= 0 ||
        session->render_queue_depth <= 0) {
        return false;
    }

    while (session->render_queue_depth > 0) {
        int stale_index = session->render_queue_head;
        if (session->render_queue[stale_index].generation_id == session->anomaly_generation_id ||
            session->render_queue[stale_index].generation_id == 0) {
            break;
        }
        clear_render_queue_slot(&session->render_queue[stale_index]);
        anomaly_detector_runtime_budget_queue_pop_t pop =
                anomaly_detector_runtime_budget_queue_pop_state(
                        session->render_queue_head,
                        session->render_queue_depth,
                        session->render_queue_capacity);
        session->render_queue_head = pop.head;
        session->render_queue_depth = pop.depth;
    }
    if (session->render_queue_depth <= 0) {
        session->render_queue_depth = 0;
        session->render_queue_head = 0;
        trace_set_counter("RID2C render_queue_depth", 0);
        return false;
    }

    int64_t now_ms = monotonic_ms();
    int oldest_index = session->render_queue_head;
    int64_t queue_age_ms = now_ms - session->render_queue[oldest_index].enqueued_at_ms;
    int64_t buffered_span_ms = buffered_span_ms_locked(session);

    if (session->startup_observation_active) {
        anomaly_detector_runtime_budget_startup_observation_t observation =
                anomaly_detector_runtime_budget_startup_observation(
                        session->startup_observation_active,
                        now_ms,
                        session->startup_started_at_ms,
                        RENDER_STARTUP_OBSERVE_MS);
        if (observation.observing) {
            bool periodic_log =
                    (now_ms - session->last_render_control_log_at_ms) >= RENDER_CONTROL_LOG_INTERVAL_MS;
            if (periodic_log) {
                session->last_render_control_log_at_ms = now_ms;
                ct_debug(TAG,
                         "render startup observing id=%lld designator=%s elapsedMs=%lld queueDepth=%d bufferedSpanMs=%lld queueAgeMs=%lld",
                         (long long) session->session_id,
                         session->designator,
                         (long long) observation.elapsed_ms,
                         session->render_queue_depth,
                         (long long) buffered_span_ms,
                         (long long) queue_age_ms);
            }
            return false;
        }
        if (observation.finalize) {
            finalize_startup_estimates_locked(session);
            session->startup_observation_active = false;
            session->next_render_due_ms = now_ms;
        }
    }

    int64_t interval_ms = compute_desired_render_interval_ms_locked(session, buffered_span_ms, now_ms);
    int64_t base_interval_ms = session->source_render_interval_ms > 0
            ? session->source_render_interval_ms
            : RENDER_SOURCE_INTERVAL_DEFAULT_MS;

    bool local_file_source = is_local_file_source(session);
    bool local_ad_enabled = false;
    bool local_ad_thread_started = false;
    bool local_ad_sync_ready = false;
    bool local_playback_paused = false;
    bool target_color_selected = false;
    if (local_file_source && pthread_mutex_trylock(&g_lock) == 0) {
        local_ad_enabled = anomaly_processing_enabled_locked(session);
        local_ad_thread_started = session->ad_thread_started;
        local_ad_sync_ready = session->ad_sync_ready;
        local_playback_paused = session->local_playback_paused;
        target_color_selected = session->anomaly_cfg.target_color_family_mask != 0u;
        pthread_mutex_unlock(&g_lock);
    }
    bool local_ad_ready =
            local_ad_enabled &&
            local_ad_thread_started &&
            local_ad_sync_ready;
    int64_t local_ad_buffer_target_ms =
            anomaly_detector_runtime_budget_local_ad_buffer_latency_target_ms(
                    local_file_source,
                    local_ad_ready,
                    target_color_selected,
                    local_ad_render_backlog_target_ms(),
                    LOCAL_AD_TARGET_COLOR_BUFFER_LATENCY_CAP_MS);
    int64_t render_latency_target_ms =
            anomaly_detector_runtime_budget_local_ad_render_latency_target_ms(
                    local_file_source,
                    local_ad_ready,
                    target_color_selected,
                    session->target_latency_ms,
                    LOCAL_AD_TARGET_COLOR_RENDER_LATENCY_CAP_MS);
    if (anomaly_detector_runtime_budget_should_wait_for_local_ad_buffer(
                local_file_source,
                local_ad_enabled,
                local_ad_thread_started,
                local_ad_sync_ready,
                session->render_thread_stop,
                local_playback_paused,
                session->render_queue_depth,
                buffered_span_ms,
                local_ad_buffer_target_ms)) {
        return false;
    }
    if (anomaly_detector_runtime_budget_should_trim_render_queue(
                local_file_source,
                buffered_span_ms,
                render_latency_target_ms)) {
        int keep_latest =
                compute_trim_keep_latest_locked(session, base_interval_ms, render_latency_target_ms);
        trim_render_queue_to_latest(session, keep_latest);
        oldest_index = session->render_queue_head;
        buffered_span_ms = buffered_span_ms_locked(session);
    }

    if (session->next_render_due_ms <= 0) {
        session->next_render_due_ms = now_ms;
    }
    if (now_ms < session->next_render_due_ms) {
        return false;
    }

    int64_t scheduled_due_ms = session->next_render_due_ms;
    anomaly_detector_runtime_budget_render_lag_t lag =
            anomaly_detector_runtime_budget_render_lag(
                    now_ms,
                    scheduled_due_ms,
                    interval_ms,
                    base_interval_ms,
                    session->last_render_lag_log_at_ms,
                    RENDER_LAG_LOG_INTERVAL_MS);
    int64_t lag_ms = lag.lag_ms;
    int64_t lag_budget_ms = lag.lag_budget_ms;
    if (lag.update_log_timestamp) {
        session->last_render_lag_log_at_ms = now_ms;
    }

    *out_frame = session->render_queue[oldest_index].frame;
    *out_history_frame = session->render_queue[oldest_index].history_frame;
    *out_overlay_frame = session->render_queue[oldest_index].overlay_frame;
    *out_analyzed = session->render_queue[oldest_index].analyzed;
    *out_source_ts_us = session->render_queue[oldest_index].source_ts_us;
    session->render_queue[oldest_index].frame = NULL;
    session->render_queue[oldest_index].history_frame = NULL;
    session->render_queue[oldest_index].overlay_frame = NULL;
    session->render_queue[oldest_index].source_ts_us = 0;
    session->render_queue[oldest_index].enqueued_at_ms = 0;
    anomaly_detector_runtime_budget_queue_pop_t pop =
            anomaly_detector_runtime_budget_queue_pop_state(
                    session->render_queue_head,
                    session->render_queue_depth,
                    session->render_queue_capacity);
    session->render_queue_head = pop.head;
    session->render_queue_depth = pop.depth;
    int64_t remaining_buffered_span_ms = buffered_span_ms_locked(session);
    *out_render_latency_ms = remaining_buffered_span_ms;
    trace_set_counter("RID2C render_queue_depth", session->render_queue_depth);
    trace_set_counter("RID2C render_latency_ms", remaining_buffered_span_ms);
    if (session->render_queue_depth <= 0) {
        session->render_queue_depth = 0;
        session->render_queue_head = 0;
        *out_render_latency_ms = 0;
        session->next_render_due_ms = 0;
        trace_set_counter("RID2C render_queue_depth", 0);
        trace_set_counter("RID2C render_latency_ms", 0);
    }
    log_render_queue_state(session, session->render_queue_depth, false);

    session->next_render_due_ms =
            anomaly_detector_runtime_budget_advance_render_due_ms(
                    scheduled_due_ms,
                    interval_ms,
                    now_ms);
    return *out_frame != NULL;
}

static void render_cond_timed_wait_ms(pthread_cond_t *cond,
                                      pthread_mutex_t *mutex,
                                      int64_t wait_ms) {
    if (cond == NULL || mutex == NULL || wait_ms <= 0) return;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += (time_t) (wait_ms / 1000);
    long add_ns = (long) ((wait_ms % 1000) * 1000000LL);
    ts.tv_nsec += add_ns;
    if (ts.tv_nsec >= 1000000000L) {
        ts.tv_sec += 1;
        ts.tv_nsec -= 1000000000L;
    }
    pthread_cond_timedwait(cond, mutex, &ts);
}

static void *render_thread_main(void *arg) {
    ffmpeg_session_t *session = (ffmpeg_session_t *) arg;
    if (session == NULL) return NULL;

    while (session_running(session)) {
        AVFrame *to_render = NULL;
        AVFrame *history_frame = NULL;
        AVFrame *overlay_frame = NULL;
        int64_t source_ts_us = 0;
        int64_t render_latency_ms = 0;
        bool analyzed = false;
        int64_t wait_ms = 20;
        bool should_stop = false;

        pthread_mutex_lock(&session->render_lock);
        // Surface-detach flush: discard all queued frames when the surface has gone
        // away.  This prevents unbounded render-queue growth during the interval
        // between detach and the next surface attachment.
        if (session->render_queue_flush_requested) {
            clear_render_queue(session);
            session->render_queue_flush_requested = false;
        }
        if (session->render_queue_depth > 0 &&
            dequeue_due_render_frame_locked(session,
                                            &to_render,
                                            &history_frame,
                                            &overlay_frame,
                                            &analyzed,
                                            &source_ts_us,
                                            &render_latency_ms)) {
            pthread_mutex_unlock(&session->render_lock);
            render_frame_to_surface(session, to_render, history_frame, overlay_frame, analyzed,
                                    source_ts_us, render_latency_ms, true);
            av_frame_free(&to_render);
            av_frame_free(&history_frame);
            av_frame_free(&overlay_frame);
            continue;
        }

        should_stop = session->render_thread_stop;
        if (session->render_queue_depth > 0) {
            int64_t now_ms = monotonic_ms();
            if (session->next_render_due_ms <= now_ms) {
                wait_ms = 1;
            } else {
                wait_ms = session->next_render_due_ms - now_ms;
                if (wait_ms > 20) wait_ms = 20;
            }
        }
        if (!should_stop) {
            render_cond_timed_wait_ms(&session->render_cond, &session->render_lock, wait_ms);
            should_stop = session->render_thread_stop;
        }
        pthread_mutex_unlock(&session->render_lock);

        // Monitor the decode thread for reader stalls and notify the Java side periodically.
        // With no socket timeout the decode thread blocks silently in av_read_frame() while
        // mediamtx is alive but the RTMP publisher is idle.  The render thread can see
        // reader_waiting_since_ms (written by the decode thread without the render lock; a
        // stale read is harmless here) and fires "reader_wait_long" events so the Java health-
        // classifier knows to suppress unnecessary session restarts.
        {
            int64_t now_ms = monotonic_ms();
            int64_t waiting_since = session->reader_waiting_since_ms; // lock-free read: acceptable
            if (waiting_since > 0) {
                int64_t wait_duration_ms = now_ms - waiting_since;
                int64_t last_event = session->last_reader_wait_event_ms;
                // First event after the stall has lasted 1 s; then every ~2.5 s thereafter.
                bool first_event  = (last_event == 0 && wait_duration_ms >= 1000);
                bool repeat_event = (last_event > 0  && (now_ms - last_event) >= 2500);
                if (first_event || repeat_event) {
                    session->last_reader_wait_event_ms = now_ms;
                    dispatch_probe_event(session->designator, "reader_wait_long",
                                         session->session_id, 0,
                                         NAN, NAN, NAN, NAN, NAN, NAN);
                }
            } else {
                // Stall ended – reset so the next stall gets its own first-event at 1 s.
                session->last_reader_wait_event_ms = 0;
            }
        }

        if (should_stop) break;
    }
    return NULL;
}

static void reset_anomaly_tracking_state(ffmpeg_session_t *session);

static void reconfigure_anomaly_mode(ffmpeg_session_t *session,
                                     bool enable,
                                     ad_pause_reason_t pause_reason,
                                     bool reset_runtime_disable) {
    if (session == NULL) return;
    bool was_runtime_disabled = session->anomaly_runtime_disabled;
    bool current_enabled = session->anomaly_cfg.enabled &&
                           !session->anomaly_thermal_paused &&
                           !was_runtime_disabled &&
                           (session->anomaly_cfg.algorithm_mask != 0);
    if (enable && reset_runtime_disable) {
        session->anomaly_runtime_disabled = false;
    } else if (!enable && pause_reason == AD_PAUSE_REASON_OVERLOAD) {
        session->anomaly_runtime_disabled = true;
    }
    bool runtime_disabled_changed =
            session->anomaly_runtime_disabled != was_runtime_disabled;
    bool needs_ad_thread_start =
            enable &&
            session->is_render &&
            session->ad_sync_ready &&
            !session->ad_thread_started &&
            !session->ad_thread_starting;
    bool changed = current_enabled != enable ||
                   session->anomaly_pause_reason != pause_reason ||
                   runtime_disabled_changed ||
                   needs_ad_thread_start;
    session->anomaly_pause_reason = pause_reason;
    if (!changed) {
        return;
    }
    if (enable) {
        start_ad_thread_if_needed_locked(session, "config_enable");
    }
    session->anomaly_generation_id += 1;
    if (session->ad_sync_ready) {
        pthread_mutex_lock(&session->ad_lock);
        clear_ad_input_queue(session);
        pthread_cond_signal(&session->ad_cond);
        pthread_mutex_unlock(&session->ad_lock);
    }
    if (session->render_sync_ready) {
        pthread_mutex_lock(&session->render_lock);
        pthread_cond_signal(&session->render_cond);
        pthread_mutex_unlock(&session->render_lock);
    }
    reset_anomaly_tracking_state(session);
    if (enable) {
        dispatch_probe_event(session->designator, "anomaly_resumed", session->session_id, 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
    } else if (pause_reason == AD_PAUSE_REASON_OVERLOAD) {
        dispatch_probe_event(session->designator, "anomaly_paused_overload", session->session_id, 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
    } else if (pause_reason == AD_PAUSE_REASON_THERMAL) {
        dispatch_probe_event(session->designator, "anomaly_paused_thermal", session->session_id, 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
    }
}

static void disable_anomaly_runtime(ffmpeg_session_t *session,
                                    ad_pause_reason_t reason) {
    if (session == NULL) return;
    pthread_mutex_lock(&g_lock);
    if (reason == AD_PAUSE_REASON_OVERLOAD) {
        session->ad_full_queue_disable_count += 1;
    }
    reconfigure_anomaly_mode(session, false, reason, false);
    pthread_mutex_unlock(&g_lock);
}

static void *ad_thread_main(void *arg) {
    ffmpeg_session_t *session = (ffmpeg_session_t *) arg;
    if (session == NULL) return NULL;
    update_ad_bridge_debug_summary(session, "thread-start", NULL, false, false, false, false);
    if (session->anomaly_troubleshooting_debug) {
        ct_debug(TAG,
                 "ad thread started id=%lld designator=%s",
                 (long long) session->session_id,
                 session->designator);
    }

    while (session_running(session)) {
        render_queue_slot_t packet;
        memset(&packet, 0, sizeof(packet));
        ad_pressure_mode_t pressure_mode = AD_PRESSURE_MODE_NORMAL;
        int queue_depth_before_dequeue = 0;

        pthread_mutex_lock(&session->ad_lock);
        // The stop flag is protected by ad_lock and is signalled by nativeStop.
        // Never call session_running() while holding ad_lock: surface detach holds
        // g_lock before clearing this queue, so that reverse order deadlocks both
        // workers and eventually the Android main thread.
        while (!session->ad_thread_stop &&
               session->ad_input_queue_depth <= 0) {
            render_cond_timed_wait_ms(&session->ad_cond, &session->ad_lock, 20);
        }
        if (session->ad_thread_stop) {
            pthread_mutex_unlock(&session->ad_lock);
            break;
        }
        queue_depth_before_dequeue = session->ad_input_queue_depth;
        pressure_mode = select_ad_pressure_mode_locked(session, queue_depth_before_dequeue);
        if (pressure_mode != session->ad_pressure_mode) {
            session->ad_pressure_mode = pressure_mode;
            if (session->anomaly_troubleshooting_debug) {
                ct_debug(TAG,
                         "ad pressure mode id=%lld designator=%s mode=%s qDepth=%d capacity=%d thresholds=%d/%d/%d recover=%d",
                         (long long) session->session_id,
                         session->designator,
                         ad_pressure_mode_name(pressure_mode),
                         queue_depth_before_dequeue,
                         session->ad_input_queue_capacity,
                         ad_queue_depth_threshold(session, AD_PRESSURE_ANALYZE_ALTERNATE_PCT),
                         ad_queue_depth_threshold(session, AD_PRESSURE_BYPASS_ALTERNATE_PCT),
                         ad_queue_depth_threshold(session, AD_PRESSURE_BYPASS_ALL_PCT),
                         AD_PRESSURE_RECOVER_DEPTH);
            }
        }
        if (!dequeue_ad_input_frame_locked(session, &packet)) {
            pthread_mutex_unlock(&session->ad_lock);
            continue;
        }
        session->ad_pressure_frame_counter += 1;
        pthread_mutex_unlock(&session->ad_lock);

        pthread_mutex_lock(&g_lock);
        int64_t generation_id = session->anomaly_generation_id;
        bool process_enabled = anomaly_processing_enabled_locked(session);
        bool target_color_selected = session->anomaly_cfg.target_color_family_mask != 0u;
        pthread_mutex_unlock(&g_lock);

        int frame_stride_override = 0;
        bool bypass_for_local_cadence = false;
        if (is_local_file_source(session) && process_enabled) {
            anomaly_detector_runtime_budget_t budget =
                    local_ad_runtime_budget_from_backlog_ms(local_render_backlog_ms(session));
            while (session_running(session) &&
                   !session->ad_thread_stop &&
                   anomaly_detector_runtime_budget_should_wait_for_local_ad_processing(
                           true,
                           process_enabled,
                           session->render_thread_stop,
                           target_color_selected,
                           budget)) {
                usleep(5000);
                budget = local_ad_runtime_budget_from_backlog_ms(
                        local_render_backlog_ms(session));
            }
            if (!session_running(session) || session->ad_thread_stop) {
                clear_render_queue_slot(&packet);
                break;
            }
            anomaly_detector_processing_mode_t processing_mode =
                    anomaly_detector_runtime_budget_processing_mode(budget);
            if (processing_mode == ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY ||
                processing_mode == ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH) {
                int64_t local_ad_cadence_ordinal =
                        packet.local_ad_cadence_ordinal > 0
                        ? packet.local_ad_cadence_ordinal
                        : packet.frame_id;
                anomaly_detector_runtime_budget_local_ad_cadence_t cadence =
                        anomaly_detector_runtime_budget_local_ad_cadence(
                                true,
                                process_enabled,
                                target_color_selected,
                                local_ad_cadence_ordinal,
                                LOCAL_AD_FULL_SCAN_STRIDE_FRAMES,
                                LOCAL_AD_TARGET_EVAL_INTERVAL_FRAMES);
                bypass_for_local_cadence = !cadence.analyze;
                frame_stride_override = cadence.frame_stride_override;
                if (cadence.prediction_only) {
                    trace_set_counter("RID2C local_ad_prediction_only",
                                      local_ad_cadence_ordinal);
                } else if (cadence.full_scan_due) {
                    trace_set_counter("RID2C local_ad_full_scan_due",
                                      local_ad_cadence_ordinal);
                } else if (cadence.frame_stride_override > 0 &&
                           local_ad_target_eval_has_no_revisit_work(
                                   session,
                                   local_ad_cadence_ordinal)) {
                    bypass_for_local_cadence = true;
                    trace_set_counter("RID2C local_ad_no_target_prediction_only",
                                      local_ad_cadence_ordinal);
                    trace_set_counter("RID2C local_ad_prediction_only",
                                      local_ad_cadence_ordinal);
                }
            }
        }

        bool skipped = false;

        bool bypass_for_pressure = false;
        if (process_enabled && packet.generation_id == generation_id) {
            bypass_for_pressure = bypass_for_local_cadence ||
                    anomaly_runtime_pressure_should_bypass_analysis_for_source(
                    pressure_mode,
                    session->ad_pressure_frame_counter,
                    is_local_file_source(session));
        }
        anomaly_runtime_handoff_decision_t handoff_decision =
                anomaly_runtime_handoff_decide(ad_handoff_frame_from_packet(&packet),
                                               process_enabled,
                                               generation_id,
                                               bypass_for_pressure);

        if (handoff_decision.action ==
            ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS) {
            packet.analyzed = false;
            skipped = true;
        } else {
            bool analyzed = false;
            int64_t annotation_frame_ordinal =
                    anomaly_detector_runtime_budget_local_ad_annotation_ordinal(
                            is_local_file_source(session),
                            session->anomaly_process_frame_count,
                            packet.local_ad_cadence_ordinal);
            packet.overlay_frame = build_overlay_frame(session,
                                                       packet.frame,
                                                       packet.source_ts_us,
                                                       &analyzed,
                                                       frame_stride_override,
                                                       annotation_frame_ordinal,
                                                       (uint64_t)packet.generation_id,
                                                       (uint64_t)packet.frame_id,
                                                       pressure_mode == AD_PRESSURE_MODE_NORMAL
                                                               ? ANOMALY_PERSON_PRESSURE_NORMAL
                                                               : ANOMALY_PERSON_PRESSURE_ELEVATED,
                                                       true);
            packet.analyzed = analyzed;
        }
        if (session_stopping(session)) {
            clear_render_queue_slot(&packet);
            break;
        }
        bool overlay_present = packet.overlay_frame != NULL;
        trace_set_counter("RID2C local_ad_overlay_present", overlay_present ? 1 : 0);
        anomaly_runtime_handoff_outcome_t handoff_outcome =
                anomaly_runtime_handoff_outcome_for_decision(handoff_decision,
                                                             overlay_present);
        session->ad_worker_processed_frame_count += handoff_outcome.processed_delta;
        session->ad_worker_skipped_frame_count += handoff_outcome.skipped_delta;
        session->ad_forwarded_without_analysis_count +=
                handoff_outcome.forwarded_without_analysis_delta;
        session->ad_worker_annotated_frame_count += handoff_outcome.annotated_delta;
        update_ad_bridge_debug_summary(
                session,
                skipped ? "skipped" : "processed",
                &packet,
                true,
                packet.analyzed,
                overlay_present,
                skipped);
        if (session->anomaly_troubleshooting_debug &&
            (session->ad_worker_processed_frame_count <= 3 ||
             (session->ad_worker_processed_frame_count % 60) == 0 ||
             skipped ||
             overlay_present)) {
            ct_debug(TAG,
                     "ad worker progress id=%lld designator=%s enq=%lld deq=%lld proc=%lld skip=%lld analyzed=%d overlay=%d mode=%s cadenceSkip=%d qDepth=%d ts=%.3fs",
                     (long long) session->session_id,
                     session->designator,
                     (long long) session->ad_input_enqueued_count,
                     (long long) session->ad_worker_dequeued_frame_count,
                     (long long) session->ad_worker_processed_frame_count,
                     (long long) session->ad_worker_skipped_frame_count,
                    packet.analyzed ? 1 : 0,
                    overlay_present ? 1 : 0,
                     ad_pressure_mode_name(pressure_mode),
                     bypass_for_local_cadence ? 1 : 0,
                     session->ad_input_queue_depth,
                     packet.source_ts_us > 0 ? ((double) packet.source_ts_us / 1000000.0) : 0.0);
        }

        if (is_local_file_source(session)) {
            bool attached = false;
            bool forwarded_late = false;
            anomaly_detector_runtime_budget_local_ad_overlay_action_t overlay_action =
                    anomaly_detector_runtime_budget_local_ad_overlay_action(
                            overlay_present,
                            false);
            if (overlay_present) {
                pthread_mutex_lock(&session->render_lock);
                attached = attach_local_ad_overlay_to_pending_render_locked(session, &packet);
                overlay_action =
                        anomaly_detector_runtime_budget_local_ad_overlay_action(
                                overlay_present,
                                attached);
                if (overlay_action ==
                    ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_OVERLAY_FORWARD_LATE) {
                    forwarded_late = enqueue_render_packet_locked(session, &packet);
                    if (forwarded_late) {
                        pthread_cond_signal(&session->render_cond);
                    }
                }
                pthread_mutex_unlock(&session->render_lock);
            }
            session->local_ad_sidecar_completed_count += 1;
            trace_set_counter("RID2C local_ad_completed",
                              session->local_ad_sidecar_completed_count);
            trace_set_counter("RID2C local_ad_overlay_attached", attached ? 1 : 0);
            trace_set_counter("RID2C local_ad_overlay_forwarded_late", forwarded_late ? 1 : 0);
            trace_set_counter("RID2C local_ad_overlay_missed",
                              (overlay_present && !attached && !forwarded_late) ? 1 : 0);
            if (overlay_action ==
                ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_OVERLAY_FORWARD_LATE) {
                session->local_ad_sidecar_late_count += 1;
                trace_set_counter("RID2C local_ad_late",
                                  session->local_ad_sidecar_late_count);
            }
            update_ad_bridge_debug_summary(
                    session,
                    attached ? "local-attached"
                             : (forwarded_late ? "local-forwarded-late" : "local-completed"),
                    &packet,
                    true,
                    packet.analyzed,
                    attached || forwarded_late,
                    skipped);
            clear_render_queue_slot(&packet);
            continue;
        }

        if (overlay_present) {
            apply_overlay_to_decoded_frame(session, packet.frame, packet.overlay_frame);
            session->ad_worker_overlay_enqueued_count += 1;
        }

        if (session_stopping(session) || session->render_thread_stop) {
            if (session->anomaly_troubleshooting_debug) {
                ct_debug(TAG,
                         "ad worker dropping packet during shutdown id=%lld designator=%s frame=%lld gen=%lld overlay=%d",
                         (long long) session->session_id,
                         session->designator,
                         (long long) packet.frame_id,
                         (long long) packet.generation_id,
                         overlay_present ? 1 : 0);
            }
            clear_render_queue_slot(&packet);
            break;
        }

        pthread_mutex_lock(&session->render_lock);
        bool enqueued = enqueue_render_packet_locked(session, &packet);
        if (enqueued) {
            pthread_cond_signal(&session->render_cond);
        } else {
            clear_render_queue_slot(&packet);
            session->render_drop_count += 1;
        }
        pthread_mutex_unlock(&session->render_lock);
    }
    update_ad_bridge_debug_summary(session, "thread-stop", NULL, false, false, false, false);
    return NULL;
}
#endif

typedef struct {
    bool has_any;
    bool has_explicit_ts;
    int64_t ts_us;
    char remote_id[128];
    double lat;
    double lng;
    double alt;
    double gimbal_pitch;
    double camera_yaw;
    double heading;
    double fov_width;
    double fov_height;
} telemetry_values_t;

static telemetry_values_t telemetry_values_init(void) {
    telemetry_values_t out;
    out.has_any = false;
    out.has_explicit_ts = false;
    out.ts_us = 0;
    out.remote_id[0] = '\0';
    out.lat = NAN;
    out.lng = NAN;
    out.alt = NAN;
    out.gimbal_pitch = NAN;
    out.camera_yaw = NAN;
    out.heading = NAN;
    out.fov_width = NAN;
    out.fov_height = NAN;
    return out;
}

static bool key_contains(const char *haystack, const char *needle) {
    return strstr(haystack, needle) != NULL;
}

static void lowercase_copy(char *dst, size_t dst_size, const char *src) {
    if (dst_size == 0) return;
    size_t i = 0;
    for (; src[i] != '\0' && i + 1 < dst_size; i++) {
        dst[i] = (char) tolower((unsigned char) src[i]);
    }
    dst[i] = '\0';
}

static bool parse_double_value(const char *val, double *out) {
    if (val == NULL || out == NULL) return false;
    char *end = NULL;
    double v = strtod(val, &end);
    if (end == val) return false;
    *out = v;
    return true;
}

static bool parse_int64_value(const char *val, int64_t *out) {
    if (val == NULL || out == NULL) return false;
    char *end = NULL;
    long long v = strtoll(val, &end, 10);
    if (end == val) return false;
    *out = (int64_t) v;
    return true;
}

static bool parse_string_value(const char *val, char *out, size_t out_size) {
    if (val == NULL || out == NULL || out_size == 0) return false;
    while (*val == ' ' || *val == '\t' || *val == '\n' || *val == '\r') val++;
    if (*val == '\0') return false;
    snprintf(out, out_size, "%s", val);
    size_t n = strlen(out);
    while (n > 0 &&
           (out[n - 1] == ' ' || out[n - 1] == '\t' || out[n - 1] == '\n' || out[n - 1] == '\r')) {
        out[n - 1] = '\0';
        n--;
    }
    return n > 0;
}

static void map_telemetry_key_value(telemetry_values_t *tv, const char *key, const char *val) {
    if (tv == NULL || key == NULL || val == NULL) return;

    char k[128];
    lowercase_copy(k, sizeof(k), key);

    double d = 0.0;
    int64_t i64 = 0;

    if ((strcmp(k, "lat") == 0 || key_contains(k, "latitude")) && parse_double_value(val, &d)) {
        tv->lat = d;
        tv->has_any = true;
        return;
    }
    if ((strcmp(k, "lon") == 0 || key_contains(k, "longitude") || key_contains(k, "long")) &&
        parse_double_value(val, &d)) {
        tv->lng = d;
        tv->has_any = true;
        return;
    }
    if (key_contains(k, "alt") && parse_double_value(val, &d)) {
        tv->alt = d;
        tv->has_any = true;
        return;
    }
    if (key_contains(k, "gimbal") && key_contains(k, "pitch") && parse_double_value(val, &d)) {
        tv->gimbal_pitch = d;
        tv->has_any = true;
        return;
    }
    if ((key_contains(k, "camera") || key_contains(k, "gimbal")) && key_contains(k, "yaw") &&
        parse_double_value(val, &d)) {
        tv->camera_yaw = d;
        tv->has_any = true;
        return;
    }
    if ((key_contains(k, "heading") || key_contains(k, "course")) && parse_double_value(val, &d)) {
        tv->heading = d;
        tv->has_any = true;
        return;
    }
    if (key_contains(k, "fov") && key_contains(k, "width") && parse_double_value(val, &d)) {
        tv->fov_width = d;
        tv->has_any = true;
        return;
    }
    if (key_contains(k, "fov") && key_contains(k, "height") && parse_double_value(val, &d)) {
        tv->fov_height = d;
        tv->has_any = true;
        return;
    }
    if ((key_contains(k, "timestamp") || key_contains(k, "time_us") || key_contains(k, "pts")) &&
        parse_int64_value(val, &i64)) {
        tv->ts_us = i64;
        tv->has_explicit_ts = true;
        tv->has_any = true;
        return;
    }
    if ((key_contains(k, "remoteid") || key_contains(k, "remote_id") ||
         key_contains(k, "serial") || key_contains(k, "uasid")) &&
        parse_string_value(val, tv->remote_id, sizeof(tv->remote_id))) {
        tv->has_any = true;
    }
}

static telemetry_values_t collect_dict_telemetry_values(AVDictionary *dict, int64_t fallback_ts_us) {
    telemetry_values_t tv = telemetry_values_init();
    tv.ts_us = fallback_ts_us;
    if (dict == NULL) return tv;

    AVDictionaryEntry *entry = NULL;
    while ((entry = av_dict_get(dict, "", entry, AV_DICT_IGNORE_SUFFIX)) != NULL) {
        map_telemetry_key_value(&tv, entry->key, entry->value);
    }

    return tv;
}

static void emit_telemetry_values(ffmpeg_session_t *session,
                                  const char *source_tag,
                                  double confidence,
                                  const telemetry_values_t *tv) {
    if (session == NULL || tv == NULL || !tv->has_any) return;
    dispatch_probe_event_ex(
            session->designator,
            "telemetry",
            session->session_id,
            source_tag,
            confidence,
            tv->remote_id,
            tv->ts_us,
            0,
            tv->lat,
            tv->lng,
            tv->alt,
            tv->gimbal_pitch,
            tv->camera_yaw,
            tv->heading,
            tv->fov_width,
            tv->fov_height,
            "");
}

typedef struct {
    ffmpeg_session_t *session;
    int64_t packet_ts_us;
    size_t message_index;
} dji_sei_payload_dump_context_t;

static void dump_dji_sei_payload(size_t type, const uint8_t *payload, size_t size, void *opaque) {
    dji_sei_payload_dump_context_t *c = opaque;
    char identity[96];
    snprintf(identity,sizeof(identity),"%.60s/%lld",c->session->designator,(long long)c->session->session_id);
    R2CSEICapture(&g_sei_capture,type,payload,size,identity,c->packet_ts_us,sei_capture_log);
}

static void emit_dji_camera_telemetry(
        ffmpeg_session_t *session,
        const AVPacket *packet) {
    if (session == NULL || packet == NULL || packet->data == NULL || packet->size <= 0) return;
    int64_t packet_ts_us = pts_to_us(packet->pts, session->video_time_base);
    if (!session->is_render && atomic_load_explicit(&g_dji_sei_hex_dump_enabled, memory_order_relaxed)) {
        pthread_mutex_lock(&g_sei_capture_lock);
        if (atomic_load_explicit(&g_dji_sei_hex_dump_enabled, memory_order_relaxed)) {
            dji_sei_payload_dump_context_t context = {.session=session,.packet_ts_us=packet_ts_us};
            R2CDJIVisitH264SEIPayloads(packet->data,(size_t)packet->size,4,dump_dji_sei_payload,&context);
        }
        pthread_mutex_unlock(&g_sei_capture_lock);
    }
    R2CDJICameraTelemetry camera = {0};
    if (!R2CDJIDecodeH264Packet(
            packet->data,
            (size_t) packet->size,
            4,
            &camera)) {
        return;
    }
    char attitude_angles_csv[256];
    snprintf(
            attitude_angles_csv,
            sizeof(attitude_angles_csv),
            "%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d",
            camera.attitudeAnglesDegrees[0],
            camera.attitudeAnglesDegrees[1],
            camera.attitudeAnglesDegrees[2],
            camera.attitudeAnglesDegrees[3],
            camera.attitudeAnglesDegrees[4],
            camera.attitudeAnglesDegrees[5],
            camera.attitudeAnglesDegrees[6],
            camera.attitudeAnglesDegrees[7],
            camera.attitudeAnglesDegrees[8],
            camera.relativeNorthMillimeters,
            camera.relativeEastMillimeters,
            camera.downMillimeters);
    dispatch_probe_event_ex(
            session->designator,
            "telemetry",
            session->session_id,
            "dji-sei-245",
            0.95,
            "",
            packet_ts_us,
            0,
            camera.positionValid ? camera.latitudeDegrees : NAN,
            camera.positionValid ? camera.longitudeDegrees : NAN,
            camera.positionValid ? camera.altitudeMeters : NAN,
            camera.tiltDegrees,
            camera.azimuthDegrees,
            NAN,
            camera.horizontalFovDegrees,
            camera.verticalFovDegrees,
            attitude_angles_csv);
}

static void log_dict_keys_once(ffmpeg_session_t *session,
                               AVDictionary *dict,
                               const char *source_tag,
                               bool *already_logged) {
    if (session == NULL || dict == NULL || source_tag == NULL || already_logged == NULL || *already_logged) {
        return;
    }

    char keys[512];
    keys[0] = '\0';
    size_t used = 0;
    bool first = true;

    AVDictionaryEntry *entry = NULL;
    while ((entry = av_dict_get(dict, "", entry, AV_DICT_IGNORE_SUFFIX)) != NULL) {
        const char *sep = first ? "" : ",";
        int written = snprintf(keys + used, sizeof(keys) - used, "%s%s", sep, entry->key);
        if (written < 0) break;
        if ((size_t) written >= sizeof(keys) - used) {
            used = sizeof(keys) - 1;
            break;
        }
        used += (size_t) written;
        first = false;
    }

    if (first) {
        snprintf(keys, sizeof(keys), "<empty>");
    } else if (used >= sizeof(keys) - 1 && sizeof(keys) >= 5) {
        memcpy(keys + sizeof(keys) - 5, "...", 4);
        keys[sizeof(keys) - 1] = '\0';
    }

    *already_logged = true;
}

static void emit_dict_telemetry(ffmpeg_session_t *session,
                                AVDictionary *dict,
                                const char *source_tag,
                                double confidence,
                                int64_t fallback_ts_us) {
    if (session == NULL || dict == NULL) return;
    telemetry_values_t tv = collect_dict_telemetry_values(dict, fallback_ts_us);
    emit_telemetry_values(session, source_tag, confidence, &tv);
}

static bool fps_from_rate(AVRational rate, double *out_fps) {
    if (out_fps == NULL) return false;
    if (rate.num <= 0 || rate.den <= 0) return false;
    double fps = av_q2d(rate);
    if (!(fps > 1.0 && fps <= 240.0)) return false;
    *out_fps = fps;
    return true;
}

static int64_t interval_from_fps(double fps) {
    return anomaly_detector_runtime_budget_interval_from_fps(
            fps,
            RENDER_MIN_INTERVAL_MS,
            RENDER_MAX_INTERVAL_MS);
}

static int64_t provisional_interval_from_stream(const AVStream *stream,
                                                double *out_fps,
                                                const char **out_source) {
    if (out_fps != NULL) *out_fps = 0.0;
    if (out_source != NULL) *out_source = "none";
    if (stream == NULL) return 0;

    double fps = 0.0;
    if (fps_from_rate(stream->avg_frame_rate, &fps)) {
        if (out_fps != NULL) *out_fps = fps;
        if (out_source != NULL) *out_source = "avg_frame_rate";
        return interval_from_fps(fps);
    }
    if (fps_from_rate(stream->r_frame_rate, &fps)) {
        if (out_fps != NULL) *out_fps = fps;
        if (out_source != NULL) *out_source = "r_frame_rate";
        return interval_from_fps(fps);
    }
    return 0;
}

static int open_input_with_profile(ffmpeg_session_t *session, bool low_latency) {
    AVDictionary *opts = NULL;
    if (session == NULL) return AVERROR(EINVAL);

    if (session->fmt == NULL) {
        session->fmt = avformat_alloc_context();
        if (session->fmt == NULL) {
            return AVERROR(ENOMEM);
        }
    }
    session->fmt->interrupt_callback.callback = ffmpeg_interrupt_cb;
    session->fmt->interrupt_callback.opaque = session;

    // Keep transport deterministic for loopback stability.
    av_dict_set(&opts, "rtsp_transport", "tcp", 0);
    // No rw_timeout/stimeout/timeout: we connect to localhost mediamtx which keeps the RTSP
    // TCP session alive even when the RTMP publisher is idle (via RTSP keepalives).
    // av_read_frame() will block until data arrives, the connection closes (EOF), or the
    // interrupt_callback fires (session->running = false).  ffmpeg's network loop polls every
    // ~100 ms and checks the interrupt_callback, so stop() is still responsive without a
    // per-socket timeout.  The render thread monitors reader_waiting_since_ms and emits
    // "reader_wait_long" probe events so the Java health-classifier stays informed.
    // The render path only needs video; subscribing to audio as well can add interleave stalls.
    if (session != NULL && session->is_render) {
        av_dict_set(&opts, "allowed_media_types", "video", 0);
    }

    if (low_latency) {
        av_dict_set(&opts, "fflags", "nobuffer", 0);
        av_dict_set(&opts, "flags", "low_delay", 0);
        av_dict_set(&opts, "flush_packets", "1", 0);
        av_dict_set(&opts, "reorder_queue_size", "0", 0);
        av_dict_set(&opts, "max_delay", "100000", 0);
        av_dict_set(&opts, "probesize", "32768", 0);
        av_dict_set(&opts, "analyzeduration", "0", 0);
        av_dict_set(&opts, "fpsprobesize", "0", 0);
    } else {
        // Compatibility fallback for streams that dislike aggressive probing.
        // Render sessions only need H264 video, so keep probing modest to shorten startup.
        if (session != NULL && session->is_render) {
            av_dict_set(&opts, "max_delay", "250000", 0);
            av_dict_set(&opts, "probesize", "65536", 0);
            av_dict_set(&opts, "analyzeduration", "250000", 0);
            av_dict_set(&opts, "fpsprobesize", "2", 0);
        } else {
            av_dict_set(&opts, "max_delay", "500000", 0);
            av_dict_set(&opts, "probesize", "262144", 0);
            av_dict_set(&opts, "analyzeduration", "1000000", 0);
        }
    }

    set_io_interrupt_timeout(session, IO_STARTUP_INTERRUPT_MS);
    int rc = avformat_open_input(&session->fmt, session->url, NULL, &opts);
    clear_io_interrupt_timeout(session);
    av_dict_free(&opts);
    return rc;
}

static int open_decoder(ffmpeg_session_t *session) {
    bool prefer_low_latency = !is_local_file_source(session);
    int64_t phase_started_at_ms = monotonic_ms();
    ct_debug(TAG,
             "open_decoder begin id=%lld designator=%s preferLowLatency=%d render=%d",
             (long long) session->session_id,
             session->designator,
             prefer_low_latency ? 1 : 0,
             session->is_render ? 1 : 0);

    int rc = open_input_with_profile(session, prefer_low_latency);
    int64_t open_input_elapsed_ms = monotonic_ms() - phase_started_at_ms;
    ct_debug(TAG,
             "open_decoder open_input id=%lld designator=%s profile=%s elapsedMs=%lld rc=%d",
             (long long) session->session_id,
             session->designator,
             prefer_low_latency ? "low-latency" : "compatibility",
             (long long) open_input_elapsed_ms,
             rc);
    if (rc < 0 && prefer_low_latency) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        memset(errbuf, 0, sizeof(errbuf));
        av_strerror(rc, errbuf, sizeof(errbuf));
        ct_warn(TAG,
                "low-latency open failed id=%lld designator=%s rc=%d err=%s; retrying with compatibility profile",
                (long long) session->session_id,
                session->designator,
                rc,
                errbuf);
        if (session->fmt != NULL) {
            avformat_close_input(&session->fmt);
        }
        phase_started_at_ms = monotonic_ms();
        rc = open_input_with_profile(session, false);
        open_input_elapsed_ms = monotonic_ms() - phase_started_at_ms;
        ct_debug(TAG,
                 "open_decoder open_input id=%lld designator=%s profile=compatibility elapsedMs=%lld rc=%d",
                 (long long) session->session_id,
                 session->designator,
                 (long long) open_input_elapsed_ms,
                 rc);
    }
    if (rc < 0) {
        return rc;
    }

    phase_started_at_ms = monotonic_ms();
    set_io_interrupt_timeout(session, IO_STARTUP_INTERRUPT_MS);
    rc = avformat_find_stream_info(session->fmt, NULL);
    clear_io_interrupt_timeout(session);
    int64_t find_stream_info_elapsed_ms = monotonic_ms() - phase_started_at_ms;
    ct_debug(TAG,
             "open_decoder find_stream_info id=%lld designator=%s elapsedMs=%lld rc=%d",
             (long long) session->session_id,
             session->designator,
             (long long) find_stream_info_elapsed_ms,
             rc);
    if (rc < 0) {
        return rc;
    }

    phase_started_at_ms = monotonic_ms();
    int video_index = av_find_best_stream(session->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    int64_t find_best_stream_elapsed_ms = monotonic_ms() - phase_started_at_ms;
    ct_debug(TAG,
             "open_decoder find_best_stream id=%lld designator=%s elapsedMs=%lld videoIndex=%d",
             (long long) session->session_id,
             session->designator,
             (long long) find_best_stream_elapsed_ms,
             video_index);
    if (video_index < 0) {
        return video_index;
    }

    AVStream *stream = session->fmt->streams[video_index];
    double provisional_fps = 0.0;
    const char *provisional_source = "none";
    int64_t provisional_interval_ms =
            provisional_interval_from_stream(stream, &provisional_fps, &provisional_source);
    if (provisional_interval_ms > 0) {
        session->source_render_interval_ms = provisional_interval_ms;
        session->render_interval_smoothed_ms = provisional_interval_ms;
        if (is_local_file_source(session)) {
            session->local_playback_nominal_interval_ms = provisional_interval_ms;
        }
    }
    if (is_local_file_source(session)) {
        ct_debug(TAG,
                 "local playback cadence id=%lld designator=%s fps=%.3f source=%s intervalMs=%lld",
                 (long long) session->session_id,
                 session->designator,
                 provisional_fps,
                 provisional_source != NULL ? provisional_source : "none",
                 (long long) provisional_interval_ms);
    }

    const AVCodec *decoder = avcodec_find_decoder(stream->codecpar->codec_id);
    if (decoder == NULL) {
        return AVERROR_DECODER_NOT_FOUND;
    }

    session->codec = avcodec_alloc_context3(decoder);
    if (session->codec == NULL) {
        return AVERROR(ENOMEM);
    }

    rc = avcodec_parameters_to_context(session->codec, stream->codecpar);
    if (rc < 0) return rc;

    session->codec->thread_count = 1;
    session->codec->flags |= AV_CODEC_FLAG_LOW_DELAY;
    session->codec->flags2 |= AV_CODEC_FLAG2_FAST;

    phase_started_at_ms = monotonic_ms();
    rc = avcodec_open2(session->codec, decoder, NULL);
    int64_t codec_open_elapsed_ms = monotonic_ms() - phase_started_at_ms;
    ct_debug(TAG,
             "open_decoder codec_open id=%lld designator=%s elapsedMs=%lld rc=%d codec=%s",
             (long long) session->session_id,
             session->designator,
             (long long) codec_open_elapsed_ms,
             rc,
             decoder->name != NULL ? decoder->name : "unknown");
    if (rc < 0) {
        return rc;
    }

    session->video_stream_index = video_index;
    session->video_time_base = stream->time_base;

    emit_dict_telemetry(session, session->fmt->metadata, "format-metadata", 0.40, 0);
    emit_dict_telemetry(session, stream->metadata, "stream-metadata", 0.50, 0);
    return 0;
}

static void close_decoder(ffmpeg_session_t *session) {
#if HAVE_SWSCALE
    if (session->render_sync_ready) {
        pthread_mutex_lock(&session->render_lock);
    }
    session->capture_frame_valid = false;
    av_frame_free(&session->capture_overlay_frame);
    if (session->sws != NULL) {
        sws_freeContext(session->sws);
        session->sws = NULL;
    }
    if (session->rgba_frame != NULL) {
        av_frame_free(&session->rgba_frame);
    }
    if (session->rgba_buffer != NULL) {
        av_free(session->rgba_buffer);
        session->rgba_buffer = NULL;
    }
    if (session->render_sync_ready) {
        pthread_mutex_unlock(&session->render_lock);
    }
    if (session->remote_video_sws != NULL) {
        sws_freeContext(session->remote_video_sws);
        session->remote_video_sws = NULL;
    }
    if (session->remote_video_i420_buffer != NULL) {
        av_free(session->remote_video_i420_buffer);
        session->remote_video_i420_buffer = NULL;
    }
    session->remote_video_i420_buffer_size = 0;
    session->remote_video_buffer_width = 0;
    session->remote_video_buffer_height = 0;
    session->remote_video_src_fmt = AV_PIX_FMT_NONE;
#endif
    if (session->codec != NULL) {
        avcodec_free_context(&session->codec);
    }
    if (session->fmt != NULL) {
        avformat_close_input(&session->fmt);
    }
    session->video_stream_index = -1;
}

static void run_decode_loop(ffmpeg_session_t *session) {
    char errbuf[AV_ERROR_MAX_STRING_SIZE];
    memset(errbuf, 0, sizeof(errbuf));

    avformat_network_init();

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    if (pkt == NULL || frame == NULL) {
        dispatch_probe_event(session->designator, "decoder_alloc_error", session->session_id, 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
        if (pkt != NULL) av_packet_free(&pkt);
        if (frame != NULL) av_frame_free(&frame);
        return;
    }

    // Full render-state reset and render-thread startup happen only once, before
    // the first connection attempt.  Subsequent reconnects skip the EMA/buffer
    // fields so the tuned gap history survives the 20-second DJI session rotation.
    if (session->is_render) {
        session->render_drop_count = 0;
        session->last_logged_render_queue_depth = -1;
        session->last_logged_render_drop_count = -1;
        session->last_render_queue_log_at_ms = 0;
        session->last_render_queue_warn_at_ms = 0;
        session->last_render_lag_log_at_ms = 0;
        reset_render_timing_state_locked(session, !is_local_file_source(session), true);
        session->gap_sample_head = 0;
        session->reader_stall_started_at_ms = 0;
        session->last_reader_stall_log_at_ms = 0;
        session->reader_stall_timeout_events = 0;
        session->reader_stall_error_events = 0;
        session->reader_waiting_since_ms = 0;
        session->reader_reconnecting_since_ms = 0;
        session->last_reader_wait_event_ms = 0;
        ct_debug(TAG,
                 "render startup observe begin id=%lld designator=%s observeMs=%d",
                 (long long) session->session_id,
                 session->designator,
                 RENDER_STARTUP_OBSERVE_MS);
#if HAVE_SWSCALE
        if (session->render_sync_ready) {
            pthread_mutex_lock(&session->render_lock);
            session->render_thread_stop = false;
            clear_render_queue(session);
            if (!ensure_render_queue_capacity(session, RENDER_QUEUE_INITIAL_CAPACITY)) {
                ct_warn(TAG,
                        "render queue prealloc failed id=%lld designator=%s",
                        (long long) session->session_id,
                        session->designator);
            }
            pthread_mutex_unlock(&session->render_lock);

            int render_thread_rc = pthread_create(&session->render_thread, NULL, render_thread_main, session);
            if (render_thread_rc == 0) {
                session->render_thread_started = true;
            } else {
                session->render_thread_started = false;
                ct_warn(TAG,
                        "render thread start failed id=%lld designator=%s rc=%d",
                        (long long) session->session_id,
                        session->designator,
                        render_thread_rc);
            }
        }
        pthread_mutex_lock(&g_lock);
        start_ad_thread_if_needed_locked(session, "session_start");
        pthread_mutex_unlock(&g_lock);
#endif
    }

    // ── Outer reconnect loop ────────────────────────────────────────────────
    // When mediamtx kicks an old RTMP publisher and accepts a new one (which
    // the DJI controller does every ~20 s), the RTSP stream signals EOF.
    // Rather than waiting for the Java layer to call stop/start, we close the
    // decoder, wait briefly for mediamtx to be ready, and reopen it ourselves.
    // The render thread stays alive throughout; reader_reconnecting_since_ms
    // tells it to hold the current frame instead of draining the queue.
    bool first_open = true;
    bool local_file_source = is_local_file_source(session);
    bool local_file_eof = false;
    int64_t reconnect_delay_ms = 150; // head-start: DJI media arrives ~220 ms after connect
    int reconnect_failures = 0;

    while (session_running(session)) {

        if (!first_open) {
            // Signal the render thread to hold the last good frame.
            session->reader_reconnecting_since_ms = monotonic_ms();

            // Brief delay so mediamtx can accept the new DJI publisher before
            // we hammer it with avformat_open_input.
            usleep((useconds_t)(reconnect_delay_ms * 1000));
            if (!session_running(session)) break;
        }

        int rc = open_decoder(session);
        session->reader_reconnecting_since_ms = 0; // connected (or failed)
        session->reader_stall_started_at_ms = 0;
        session->last_reader_stall_log_at_ms = 0;
        session->reader_stall_timeout_events = 0;
        session->reader_stall_error_events = 0;
        session->reader_waiting_since_ms = 0;
        session->last_reader_wait_event_ms = 0;

        if (rc < 0) {
            av_strerror(rc, errbuf, sizeof(errbuf));
            if (first_open) {
                // Initial open failed: bad URL or server not running.
                // Emit error and stop — there is nothing useful to retry here.
                ct_error(TAG,
                         "open_decoder failed id=%lld designator=%s rc=%d err=%s",
                         (long long) session->session_id,
                         session->designator,
                         rc,
                         errbuf);
                dispatch_probe_event(session->designator, "decoder_open_error", session->session_id, 0,
                                     NAN, NAN, NAN, NAN, NAN, NAN);
                break;
            }
            // Reconnect failed — mediamtx may not have a publisher yet.
            // Back off slightly and try again, up to a limit.
            reconnect_failures++;
            reconnect_delay_ms = (reconnect_delay_ms < 1000) ? reconnect_delay_ms + 100 : 1000;
            ct_debug(TAG,
                     "reconnect open failed id=%lld designator=%s attempt=%d rc=%d err=%s",
                     (long long) session->session_id,
                     session->designator,
                     reconnect_failures,
                     rc,
                     errbuf);
            if (reconnect_failures > 20) {
                ct_error(TAG,
                         "reconnect giving up id=%lld designator=%s after %d attempts",
                         (long long) session->session_id,
                         session->designator,
                         reconnect_failures);
                dispatch_probe_event(session->designator, "decoder_open_error", session->session_id, 0,
                                     NAN, NAN, NAN, NAN, NAN, NAN);
                break;
            }
            continue;
        }

        // Successful (re)connect.
        reconnect_failures = 0;
        reconnect_delay_ms = 150;

        dispatch_probe_event(session->designator,
                             first_open ? "decoder_opened" : "decoder_reconnected",
                             session->session_id, 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);

        if (!first_open) {
            if (session->is_render) {
                reset_render_timing_state_locked(session, true, !local_file_source);
                ct_debug(TAG,
                         "render startup observe begin id=%lld designator=%s observeMs=%d reconnect=1",
                         (long long) session->session_id,
                         session->designator,
                         RENDER_STARTUP_OBSERVE_MS);
            }
        }
        first_open = false;

    // ── Inner decode loop ───────────────────────────────────────────────────
    int64_t last_video_packet_at_ms = 0;
    int64_t last_decoded_frame_at_ms = 0;
    int startup_packet_log_count = 0;
    int64_t processing_started_ms = 0, telemetry_work_ms = 0, codec_work_ms = 0;
    int64_t timing_log_ms = monotonic_ms();
    int64_t max_processing_ms = 0, max_telemetry_ms = 0, max_codec_ms = 0, max_other_ms = 0;
    unsigned int timing_packets = 0;

    while (session_running(session)) {
        int64_t read_started_at_ms = monotonic_ms();
        if (processing_started_ms > 0) {
            int64_t processing_ms = read_started_at_ms - processing_started_ms;
            int64_t other_ms = processing_ms - telemetry_work_ms - codec_work_ms;
            if (other_ms < 0) other_ms = 0;
            timing_packets++;
            if (processing_ms > max_processing_ms) max_processing_ms = processing_ms;
            if (telemetry_work_ms > max_telemetry_ms) max_telemetry_ms = telemetry_work_ms;
            if (codec_work_ms > max_codec_ms) max_codec_ms = codec_work_ms;
            if (other_ms > max_other_ms) max_other_ms = other_ms;
            int64_t window_ms = read_started_at_ms - timing_log_ms;
            if (!local_file_source && (window_ms >= 60000 || (processing_ms >= 100 && window_ms >= 5000))) {
                ct_debug(TAG, "packet processing id=%lld designator=%s render=%d windowMs=%lld packets=%u maxProcessingMs=%lld maxTelemetryMs=%lld maxCodecMs=%lld maxOtherMs=%lld",
                         (long long)session->session_id, session->designator, session->is_render,
                         (long long)window_ms, timing_packets, (long long)max_processing_ms,
                         (long long)max_telemetry_ms, (long long)max_codec_ms, (long long)max_other_ms);
                timing_log_ms = read_started_at_ms;
                timing_packets = 0;
                max_processing_ms = max_telemetry_ms = max_codec_ms = max_other_ms = 0;
            }
        }
        processing_started_ms = 0;
        telemetry_work_ms = codec_work_ms = 0;
        read_started_at_ms = monotonic_ms();
        // Expose blocking start time so the render thread can detect an implicit stall
        // before ETIMEDOUT fires on the socket timeout.
        session->reader_waiting_since_ms = read_started_at_ms;
        // Let ordinary source stalls block here until packets resume.
        // Explicit stop() still interrupts via ffmpeg_interrupt_cb when running=false.
        rc = av_read_frame(session->fmt, pkt);
        if (rc >= 0) processing_started_ms = monotonic_ms();
        session->reader_waiting_since_ms = 0;
        int64_t read_elapsed_ms = monotonic_ms() - read_started_at_ms;
            if (read_elapsed_ms >= 500) {
                const char *err_suffix = "";
                if (rc < 0) {
                    av_strerror(rc, errbuf, sizeof(errbuf));
                    err_suffix = errbuf;
            }
            ct_debug(TAG,
                     "av_read_frame wait id=%lld designator=%s elapsedMs=%lld rc=%d%s%s",
                     (long long) session->session_id,
                     session->designator,
                     (long long) read_elapsed_ms,
                     rc,
                     rc < 0 ? " err=" : "",
                     rc < 0 ? err_suffix : "");
                if (read_elapsed_ms >= 1000) {
                    dispatch_probe_event(session->designator, "reader_wait_long", session->session_id, 0,
                                         NAN, NAN, NAN, NAN, NAN, NAN);
                }
            }
        if (rc == AVERROR_EOF) {
            // Local-file playback should stop cleanly at EOF instead of reopening
            // the file from the beginning. Live/RTSP sources still use EOF as the
            // reconnect signal because publisher churn appears as end-of-stream.
            av_packet_unref(pkt);
            if (local_file_source) {
                dispatch_probe_event(session->designator, "local_playback_eof", session->session_id, 0,
                                     NAN, NAN, NAN, NAN, NAN, NAN);
                local_file_eof = true;
                break;
            }
            break;
        }
        if (rc < 0) {
            int64_t now_ms = monotonic_ms();
            bool timeout = (rc == AVERROR(ETIMEDOUT));
            if (session->reader_stall_started_at_ms <= 0) {
                session->reader_stall_started_at_ms = now_ms;
            }
            if (timeout) {
                session->reader_stall_timeout_events += 1;
            } else {
                session->reader_stall_error_events += 1;
            }
            bool first_stall_log = session->last_reader_stall_log_at_ms <= 0;
            bool periodic_stall_log = (now_ms - session->last_reader_stall_log_at_ms) >= 1000;
            if (first_stall_log || periodic_stall_log) {
                session->last_reader_stall_log_at_ms = now_ms;
                av_strerror(rc, errbuf, sizeof(errbuf));
                ct_debug(TAG,
                         "reader stall id=%lld designator=%s stallMs=%lld timeoutEvents=%lld errorEvents=%lld rc=%d err=%s",
                         (long long) session->session_id,
                         session->designator,
                         (long long) (now_ms - session->reader_stall_started_at_ms),
                         (long long) session->reader_stall_timeout_events,
                         (long long) session->reader_stall_error_events,
                         rc,
                         errbuf);
            }
            av_packet_unref(pkt);
            continue;
        }
        if (session->reader_stall_started_at_ms > 0) {
            int64_t now_ms = monotonic_ms();
            int64_t stall_ms = now_ms - session->reader_stall_started_at_ms;
            ct_debug(TAG,
                     "reader stall recovered id=%lld designator=%s stallMs=%lld timeoutEvents=%lld errorEvents=%lld streamIndex=%d",
                     (long long) session->session_id,
                     session->designator,
                     (long long) stall_ms,
                     (long long) session->reader_stall_timeout_events,
                     (long long) session->reader_stall_error_events,
                     pkt->stream_index);
            session->reader_stall_started_at_ms = 0;
            session->last_reader_stall_log_at_ms = 0;
            session->reader_stall_timeout_events = 0;
            session->reader_stall_error_events = 0;
        }

        if (pkt->stream_index != session->video_stream_index) {
            av_packet_unref(pkt);
            continue;
        }

        int64_t now_ms = monotonic_ms();
        if (last_video_packet_at_ms != 0) {
            int64_t packet_gap_ms = now_ms - last_video_packet_at_ms;
            if (packet_gap_ms >= 500) {
                int64_t pkt_pts_us = pts_to_us(pkt->pts, session->video_time_base);
                ct_debug(TAG,
                         "video packet gap id=%lld designator=%s gapMs=%lld ptsUs=%lld",
                         (long long) session->session_id,
                         session->designator,
                         (long long) packet_gap_ms,
                         (long long) pkt_pts_us);
                dispatch_probe_event(session->designator, "video_packet_gap", session->session_id, pkt_pts_us,
                                     NAN, NAN, NAN, NAN, NAN, NAN);
            }
        }
        last_video_packet_at_ms = now_ms;

        if (last_decoded_frame_at_ms == 0 && startup_packet_log_count < 8) {
            bool is_keyframe = (pkt->flags & AV_PKT_FLAG_KEY) != 0;
            int64_t pkt_pts_us = pts_to_us(pkt->pts, session->video_time_base);
            startup_packet_log_count++;
            ct_debug(TAG,
                     "startup packet id=%lld designator=%s count=%d key=%d size=%d ptsUs=%lld dts=%lld",
                     (long long) session->session_id,
                     session->designator,
                     startup_packet_log_count,
                     is_keyframe ? 1 : 0,
                     pkt->size,
                     (long long) pkt_pts_us,
                     (long long) pkt->dts);
        }

        int64_t telemetry_started_ms = monotonic_ms();
        emit_dji_camera_telemetry(session, pkt);
#if FFMPEG_TELEMETRY_ENABLED
        telemetry_values_t packet_tv = telemetry_values_init();
#if defined(AV_PKT_DATA_STRINGS_METADATA)
        size_t sd_size = 0;
        uint8_t *sd = av_packet_get_side_data(pkt, AV_PKT_DATA_STRINGS_METADATA, &sd_size);
        if (sd != NULL && sd_size > 0) {
            AVDictionary *packet_dict = NULL;
            if (av_packet_unpack_dictionary(sd, (int) sd_size, &packet_dict) >= 0) {
                int64_t pkt_ts_us = pts_to_us(pkt->pts, session->video_time_base);
                log_dict_keys_once(
                        session,
                        packet_dict,
                        "packet-side-data",
                        &session->packet_metadata_keys_logged);
                packet_tv = collect_dict_telemetry_values(packet_dict, pkt_ts_us);
                emit_telemetry_values(session, "packet-side-data", 0.70, &packet_tv);
            }
            av_dict_free(&packet_dict);
        }
#endif  // AV_PKT_DATA_STRINGS_METADATA
#endif  // FFMPEG_TELEMETRY_ENABLED

        telemetry_work_ms += monotonic_ms() - telemetry_started_ms;
        // Probe sessions exist to inspect packet-carried metadata such as DJI
        // SEI. Keep the RTSP reader current without paying to decode video
        // frames when there is no visual consumer.
        if (!session->is_render) {
            av_packet_unref(pkt);
            continue;
        }

        // Manual render stride (optional, set via nativeSetRenderStride):
        // When render_stride > 1, every N-1 out of N non-keyframe packets are
        // dropped before decode.  Keyframe (IDR) packets always pass through so
        // the decoder's reference-frame state is never broken.
        if (session->render_stride > 1) {
            bool is_keyframe = (pkt->flags & AV_PKT_FLAG_KEY) != 0;
            if (!is_keyframe) {
                session->render_stride_counter++;
                if ((session->render_stride_counter % session->render_stride) != 0) {
                    av_packet_unref(pkt);
                    continue;
                }
            }
        }

        // Surface-absent gate: discard packets without decoding when no render
        // surface is present.  The RTSP connection is maintained so that when the
        // surface returns (e.g. after a dialog dismissal) decode resumes from the
        // live edge with zero latency from accumulated backlog.
        if (session->is_render && session->surface_paused &&
            !session->remote_video_enabled) {
            if (!local_file_source) {
                av_packet_unref(pkt);
                continue;
            }
            while (session_running(session)) {
                bool surface_paused = false;
                bool remote_video_enabled = false;
                pthread_mutex_lock(&g_lock);
                surface_paused = session->surface_paused;
                remote_video_enabled = session->remote_video_enabled;
                pthread_mutex_unlock(&g_lock);
                if (!surface_paused || remote_video_enabled) break;
                usleep(5000);
            }
            if (!session_running(session)) {
                av_packet_unref(pkt);
                break;
            }
        }

        trace_begin_section("RID2C avcodec_send_packet");
        int64_t codec_started_ms = monotonic_ms();
        rc = avcodec_send_packet(session->codec, pkt);
        codec_work_ms += monotonic_ms() - codec_started_ms;
        trace_end_section();
        av_packet_unref(pkt);
        if (rc < 0) {
            continue;
        }

        while (session_running(session)) {
            trace_begin_section("RID2C avcodec_receive_frame");
            codec_started_ms = monotonic_ms();
            rc = avcodec_receive_frame(session->codec, frame);
            codec_work_ms += monotonic_ms() - codec_started_ms;
            trace_end_section();
            if (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) {
                break;
            }
            if (rc < 0) {
                break;
            }
            int64_t pts_us = pts_to_us(frame->best_effort_timestamp, session->video_time_base);
            pts_us = normalize_local_playback_pts_us(session, pts_us);
            AVFrame *clean_history_frame = local_file_source ? av_frame_clone(frame) : NULL;
#if FFMPEG_TELEMETRY_ENABLED
            telemetry_started_ms = monotonic_ms();
            log_dict_keys_once(
                    session,
                    frame->metadata,
                    "frame-metadata",
                    &session->frame_metadata_keys_logged);
            telemetry_values_t frame_tv = collect_dict_telemetry_values(frame->metadata, pts_us);
            telemetry_work_ms += monotonic_ms() - telemetry_started_ms;
#endif  // FFMPEG_TELEMETRY_ENABLED
            int64_t decoded_at_ms = monotonic_ms();
            if (last_decoded_frame_at_ms != 0) {
                int64_t frame_gap_ms = decoded_at_ms - last_decoded_frame_at_ms;
                if (frame_gap_ms >= 500) {
                    ct_debug(TAG,
                             "decoded frame gap id=%lld designator=%s gapMs=%lld ptsUs=%lld",
                             (long long) session->session_id,
                             session->designator,
                             (long long) frame_gap_ms,
                             (long long) pts_us);
                    dispatch_probe_event(session->designator, "decoded_frame_gap", session->session_id, pts_us,
                                         NAN, NAN, NAN, NAN, NAN, NAN);
                }
            }
            last_decoded_frame_at_ms = decoded_at_ms;
            dispatch_probe_event(session->designator, "frame_decoded", session->session_id, pts_us,
                                 NAN, NAN, NAN, NAN, NAN, NAN);
#if HAVE_SWSCALE
            dispatch_remote_video_frame(session, frame, pts_us, decoded_at_ms);
#endif
#if FFMPEG_TELEMETRY_ENABLED
            telemetry_started_ms = monotonic_ms();
            emit_telemetry_values(session, "frame-metadata", 0.60, &frame_tv);
            telemetry_work_ms += monotonic_ms() - telemetry_started_ms;
#endif  // FFMPEG_TELEMETRY_ENABLED

#if HAVE_SWSCALE
            if (session->is_render) {
                if (local_file_source && !wait_for_local_playback_advance(session)) {
                    av_frame_unref(frame);
                    break;
                }
                if (session->render_thread_started && session->render_sync_ready) {
                    bool ad_enabled = false;
                    bool enqueued = false;
                    int64_t frame_id = 0;
                    int64_t generation_id = 0;
                    bool ad_thread_started = false;
                    bool ad_sync_ready = false;
                    bool target_color_selected = false;
                    ad_runtime_mode_t runtime_mode = AD_RUNTIME_MODE_BYPASSED;
                    pthread_mutex_lock(&g_lock);
                    ad_enabled = anomaly_processing_enabled_locked(session);
                    frame_id = ++session->anomaly_next_frame_id;
                    generation_id = session->anomaly_generation_id;
                    ad_thread_started = session->ad_thread_started;
                    ad_sync_ready = session->ad_sync_ready;
                    target_color_selected =
                            session->anomaly_cfg.target_color_family_mask != 0u;
                    runtime_mode = current_ad_runtime_mode(session);
                    pthread_mutex_unlock(&g_lock);

                    if (local_file_source) {
                        decoded_at_ms = pace_local_file_decode_admission(
                                session,
                                pts_us,
                                decoded_at_ms);
                        anomaly_detector_runtime_budget_local_ad_route_t local_ad_route =
                                anomaly_detector_runtime_budget_local_ad_route(
                                        ad_enabled,
                                        ad_thread_started,
                                        ad_sync_ready);
                        int render_queue_depth_after_enqueue = 0;
                        pthread_mutex_lock(&session->render_lock);
                        record_decode_timing_sample_locked(session, decoded_at_ms, pts_us);
                        enqueued = enqueue_render_frame(
                                session,
                                frame,
                                clean_history_frame,
                                NULL,
                                false,
                                frame_id,
                                generation_id,
                                pts_us,
                                decoded_at_ms);
                        if (enqueued) {
                            render_queue_depth_after_enqueue = session->render_queue_depth;
                            pthread_cond_signal(&session->render_cond);
                        }
                        pthread_mutex_unlock(&session->render_lock);

                        bool sidecar_enqueued = false;
                        const char *decision = enqueued ? "render-first" : "render-drop";
                        const char *reason = enqueued ? "local-render-owned" : "render-enqueue-failed";
                        if (local_ad_route ==
                                    ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_ROUTE_RENDER_FIRST &&
                                enqueued && ad_enabled && ad_thread_started && ad_sync_ready) {
                            if (session->local_ad_startup_preroll_started_at_ms <= 0) {
                                session->local_ad_startup_preroll_started_at_ms = decoded_at_ms;
                            }
                            anomaly_detector_runtime_budget_local_ad_startup_preroll_t preroll =
                                    anomaly_detector_runtime_budget_local_ad_startup_preroll(
                                            local_file_source,
                                            session->local_ad_startup_preroll_complete,
                                            ad_enabled,
                                            ad_thread_started,
                                            ad_sync_ready,
                                            session->render_thread_stop,
                                            render_queue_depth_after_enqueue,
                                            decoded_at_ms,
                                            session->local_ad_startup_preroll_started_at_ms,
                                            target_color_selected
                                                    ? LOCAL_AD_STARTUP_PREROLL_RENDER_DEPTH
                                                    : 0,
                                            target_color_selected
                                                    ? LOCAL_AD_STARTUP_PREROLL_MAX_WAIT_MS
                                                    : 0);
                            if (preroll.complete && !session->local_ad_startup_preroll_complete) {
                                session->local_ad_startup_preroll_complete = true;
                                trace_set_counter("RID2C local_ad_preroll_complete", 1);
                                if (session->anomaly_troubleshooting_debug) {
                                    ct_debug(TAG,
                                             "local AD startup preroll complete id=%lld designator=%s elapsedMs=%lld renderQ=%d bypassed=%lld",
                                             (long long) session->session_id,
                                             session->designator,
                                             (long long) preroll.elapsed_ms,
                                             render_queue_depth_after_enqueue,
                                             (long long) session->local_ad_startup_preroll_bypassed_count);
                                }
                            }
                            if (preroll.wait) {
                                session->local_ad_startup_preroll_bypassed_count += 1;
                                trace_set_counter("RID2C local_ad_preroll_bypass",
                                                  session->local_ad_startup_preroll_bypassed_count);
                                decision = "render-first-ad-preroll";
                                reason = "local-ad-startup-preroll";
                            } else {
                                session->local_ad_cadence_frame_counter += 1;
                                anomaly_detector_runtime_budget_local_ad_cadence_t local_ad_prequeue_cadence =
                                        anomaly_detector_runtime_budget_local_ad_cadence(
                                                true,
                                                ad_enabled,
                                                target_color_selected,
                                                session->local_ad_cadence_frame_counter,
                                                LOCAL_AD_FULL_SCAN_STRIDE_FRAMES,
                                                LOCAL_AD_TARGET_EVAL_INTERVAL_FRAMES);
                                if (local_ad_prequeue_cadence.prediction_only) {
                                    trace_set_counter("RID2C local_ad_prequeue_prediction_only",
                                                      session->local_ad_cadence_frame_counter);
                                    trace_set_counter("RID2C local_ad_prediction_only",
                                                      session->local_ad_cadence_frame_counter);
                                    decision = "render-first-ad-prediction-only";
                                    reason = "local-ad-prequeue-cadence";
                                } else {
                                    pthread_mutex_lock(&session->ad_lock);
                                    sidecar_enqueued = enqueue_local_ad_input_best_effort_locked(
                                            session,
                                            frame,
                                            clean_history_frame,
                                            frame_id,
                                            session->local_ad_cadence_frame_counter,
                                            generation_id,
                                            pts_us,
                                            decoded_at_ms);
                                    if (sidecar_enqueued) {
                                        pthread_cond_signal(&session->ad_cond);
                                    }
                                    pthread_mutex_unlock(&session->ad_lock);
                                    decision = sidecar_enqueued ? "render-first-ad-sidecar"
                                                                : "render-first-ad-dropped";
                                    reason = sidecar_enqueued ? "local-ad-best-effort"
                                                              : "local-ad-pressure";
                                }
                            }
                        } else if (enqueued) {
                            if (!ad_enabled) {
                                reason = "ad-disabled";
                            } else if (!ad_thread_started) {
                                reason = "ad-thread-not-started";
                            } else if (!ad_sync_ready) {
                                reason = "ad-sync-not-ready";
                            }
                        }
                        update_local_playback_ad_decision_summary(
                                session,
                                frame_id,
                                generation_id,
                                pts_us,
                                ad_enabled,
                                ad_thread_started,
                                ad_sync_ready,
                                runtime_mode,
                                decision,
                                reason);
                    } else {
                        pthread_mutex_lock(ad_enabled ? &session->ad_lock : &session->render_lock);
                        record_decode_timing_sample_locked(session, decoded_at_ms, pts_us);
                        if (ad_enabled && session->ad_thread_started && session->ad_sync_ready) {
                            update_local_playback_ad_decision_summary(
                                    session,
                                    frame_id,
                                    generation_id,
                                    pts_us,
                                    ad_enabled,
                                    ad_thread_started,
                                    ad_sync_ready,
                                    runtime_mode,
                                    "enqueue-ad",
                                    "threaded-ready");
                            enqueued = enqueue_ad_input_frame_locked(
                                    session,
                                    frame,
                                    clean_history_frame,
                                    frame_id,
                                    0,
                                    generation_id,
                                    pts_us,
                                    decoded_at_ms);
                            if (enqueued) {
                                pthread_cond_signal(&session->ad_cond);
                            }
                        } else {
                            const char *bypass_reason = "inline-render";
                            if (!ad_enabled) {
                                bypass_reason = "ad-disabled";
                            } else if (!ad_thread_started) {
                                bypass_reason = "ad-thread-not-started";
                            } else if (!ad_sync_ready) {
                                bypass_reason = "ad-sync-not-ready";
                            }
                            update_local_playback_ad_decision_summary(
                                    session,
                                    frame_id,
                                    generation_id,
                                    pts_us,
                                    ad_enabled,
                                    ad_thread_started,
                                    ad_sync_ready,
                                    runtime_mode,
                                    "bypass-render",
                                    bypass_reason);
                            enqueued = enqueue_render_frame(
                                    session,
                                    frame,
                                    clean_history_frame,
                                    NULL,
                                    false,
                                    frame_id,
                                    generation_id,
                                    pts_us,
                                    decoded_at_ms);
                            if (enqueued) {
                                pthread_cond_signal(&session->render_cond);
                            }
                        }
                        pthread_mutex_unlock(ad_enabled ? &session->ad_lock : &session->render_lock);
                    }
                    if (local_file_source &&
                        session->anomaly_cfg.enabled &&
                        session->anomaly_cfg.algorithm_mask != 0 &&
                        (session->anomaly_troubleshooting_debug ||
                         !enqueued ||
                         session->anomaly_process_frame_count == 0 ||
                         frame_id <= 3 ||
                         (frame_id % 120) == 0)) {
                        ct_debug(TAG,
                                 "local playback AD branch id=%lld designator=%s %s",
                                 (long long) session->session_id,
                                 session->designator,
                                 session->latest_local_playback_ad_decision);
                    }
                    if (!enqueued) {
                        if (!local_file_source && ad_enabled && !enqueued) {
                            ct_warn(TAG,
                                    "ad input queue full id=%lld designator=%s; forwarding frame without AD",
                                    (long long) session->session_id,
                                    session->designator);
                            pthread_mutex_lock(&session->render_lock);
                            enqueued = enqueue_render_frame(
                                    session,
                                    frame,
                                    clean_history_frame,
                                    NULL,
                                    false,
                                    frame_id,
                                    session->anomaly_generation_id,
                                    pts_us,
                                    decoded_at_ms);
                            if (enqueued) {
                                update_local_playback_ad_decision_summary(
                                        session,
                                        frame_id,
                                        session->anomaly_generation_id,
                                        pts_us,
                                        false,
                                        ad_thread_started,
                                        ad_sync_ready,
                                        runtime_mode,
                                        "fallback-render",
                                        "live-ad-pressure");
                                pthread_cond_signal(&session->render_cond);
                            }
                            pthread_mutex_unlock(&session->render_lock);
                        }
                        if (!enqueued) {
                            session->render_drop_count += 1;
                            ct_warn(TAG,
                                    "render queue enqueue failed id=%lld designator=%s; dropping frame",
                                    (long long) session->session_id,
                                    session->designator);
                        }
                    }
                } else {
                    bool analyzed = false;
                    AVFrame *overlay_frame = build_overlay_frame(
                            session,
                            frame,
                            pts_us,
                            &analyzed,
                            0,
                            -1,
                            0u,
                            0u,
                            ANOMALY_PERSON_PRESSURE_ELEVATED,
                            false);
                    if (is_local_file_source(session)) {
                        pace_local_file_playback(session, pts_us);
                    }
                    render_frame_to_surface(session, frame, clean_history_frame, overlay_frame, analyzed,
                                            pts_us, 0, true);
                    av_frame_free(&overlay_frame);
                    session->next_render_due_ms = monotonic_ms() + current_render_interval_ms(session);
                }
                av_frame_free(&clean_history_frame);
            }
#endif
            av_frame_unref(frame);
        }
        if (local_file_eof) {
            break;
        }
    } // ── end inner decode loop ───────────────────────────────────────────

    // Close the decoder so we can reopen it cleanly on the next iteration.
    // The render thread stays alive between reconnects.
    close_decoder(session);

    if (local_file_eof) {
        break;
    }

    } // ── end outer reconnect loop ─────────────────────────────────────────

    // Ensure reconnecting flag is cleared on any exit path.
    session->reader_reconnecting_since_ms = 0;

#if HAVE_SWSCALE
    if (session->is_render && session->ad_thread_started && session->ad_sync_ready) {
        ct_debug(TAG,
                 "teardown begin join ad_thread id=%lld designator=%s",
                 (long long) session->session_id,
                 session->designator);
        pthread_mutex_lock(&session->ad_lock);
        session->ad_thread_stop = true;
        pthread_cond_signal(&session->ad_cond);
        pthread_mutex_unlock(&session->ad_lock);
        pthread_join(session->ad_thread, NULL);
        session->ad_thread_started = false;
        session->ad_thread_starting = false;
        ct_debug(TAG,
                 "teardown joined ad_thread id=%lld designator=%s",
                 (long long) session->session_id,
                 session->designator);
    }
    if (session->is_render && session->render_thread_started && session->render_sync_ready) {
        ct_debug(TAG,
                 "teardown begin join render_thread id=%lld designator=%s",
                 (long long) session->session_id,
                 session->designator);
        pthread_mutex_lock(&session->render_lock);
        session->render_thread_stop = true;
        pthread_cond_signal(&session->render_cond);
        pthread_mutex_unlock(&session->render_lock);
        pthread_join(session->render_thread, NULL);
        session->render_thread_started = false;
        ct_debug(TAG,
                 "teardown joined render_thread id=%lld designator=%s",
                 (long long) session->session_id,
                 session->designator);
    }
    if (session->is_render && session->render_sync_ready) {
        pthread_mutex_lock(&session->render_lock);
        clear_render_queue(session);
        pthread_mutex_unlock(&session->render_lock);
    }
    if (session->is_render && session->ad_sync_ready) {
        pthread_mutex_lock(&session->ad_lock);
        clear_ad_input_queue(session);
        pthread_mutex_unlock(&session->ad_lock);
    }
    if (session->is_render &&
        is_local_file_source(session) &&
        session->anomaly_cfg.enabled &&
        session->anomaly_cfg.algorithm_mask != 0 &&
        session->anomaly_process_frame_count == 0 &&
        (session->ad_analyzed_rendered_frame_count > 0 ||
         session->ad_bypassed_rendered_frame_count > 0)) {
        ct_warn(TAG,
                "local playback anomaly never ran id=%lld designator=%s runtimeMode=%d renderedAnalyzed=%lld renderedBypassed=%lld forwardedWithoutAnalysis=%lld adProcessed=%lld adAnnotated=%lld adOverlay=%lld qMax=%d thermalPause=%d runtimeDisabled=%d lastDecision=%s",
                (long long) session->session_id,
                session->designator,
                (int) current_ad_runtime_mode(session),
                (long long) session->ad_analyzed_rendered_frame_count,
                (long long) session->ad_bypassed_rendered_frame_count,
                (long long) session->ad_forwarded_without_analysis_count,
                (long long) session->ad_worker_processed_frame_count,
                (long long) session->ad_worker_annotated_frame_count,
                (long long) session->ad_worker_overlay_enqueued_count,
                session->ad_input_queue_depth_max,
                session->anomaly_thermal_paused ? 1 : 0,
                session->anomaly_runtime_disabled ? 1 : 0,
                session->latest_local_playback_ad_decision[0] != '\0'
                        ? session->latest_local_playback_ad_decision
                        : "unavailable");
    }
    if (session->is_render) {
        if (session->person_relevance_config_lock_ready) {
            pthread_mutex_lock(&session->person_relevance_config_lock);
        }
        anomaly_person_scheduler_destroy(&session->person_relevance_scheduler);
        if (session->person_relevance_config_lock_ready) {
            pthread_mutex_unlock(&session->person_relevance_config_lock);
        }
        cleanup_anomaly_resources(session);
    }
#endif
    av_frame_free(&frame);
    av_packet_free(&pkt);
    // Note: close_decoder is called inside the outer loop on each iteration,
    // so we do NOT call it again here.
}
#endif

static void *session_thread_main(void *arg) {
    ffmpeg_session_t *session = (ffmpeg_session_t *) arg;

    dispatch_probe_event(session->designator, "session_started", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);

#if HAVE_FFMPEG
    dispatch_probe_event(session->designator, "decoder_backend_ffmpeg_linked", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    run_decode_loop(session);
#else
    dispatch_probe_event(session->designator, "decoder_backend_stub", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    while (session_running(session)) {
        int64_t now_us = ((int64_t) time(NULL)) * 1000000LL;
        const char *event = session->is_render ? "frame_render" : "frame_probe";
        dispatch_probe_event(session->designator, event, session->session_id, now_us,
                             NAN, NAN, NAN, NAN, NAN, NAN);
        usleep(300000);
    }
#endif

    dispatch_probe_event(session->designator, "session_stopped", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    return NULL;
}

static jlong start_session(JNIEnv *env, jstring designator, jstring url, bool is_render) {
    const char *d = (*env)->GetStringUTFChars(env, designator, 0);
    const char *u = (*env)->GetStringUTFChars(env, url, 0);

    pthread_mutex_lock(&g_lock);

    ffmpeg_session_t *slot = NULL;
    for (int i = 0; i < MAX_SESSIONS; i++) {
        if (!g_sessions[i].active) {
            slot = &g_sessions[i];
            break;
        }
    }

    if (slot == NULL) {
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, designator, d);
        (*env)->ReleaseStringUTFChars(env, url, u);
        ct_error(TAG, "No free ffmpeg session slots");
        return 0;
    }

    memset(slot, 0, sizeof(*slot));
    slot->session_id = g_next_session_id++;
    slot->active = true;
    slot->running = true;
    slot->is_render = is_render;
#if HAVE_FFMPEG && HAVE_SWSCALE
    slot->remote_video_src_fmt = AV_PIX_FMT_NONE;
#endif
    slot->anomaly_cfg.enabled           = false;
    slot->anomaly_cfg.show_hot_overlay  = false;
    slot->anomaly_cfg.show_candidate_blobs = false;
    slot->anomaly_cfg.algorithm_mask    = ANOMALY_ALGO_THERMAL;
    slot->anomaly_cfg.registration_mode = ANOMALY_REGISTRATION_AFFINE;
    slot->anomaly_cfg.movement_estimator_mode = ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
    slot->anomaly_cfg.stride_mode       = ANOMALY_STRIDE_MODE_FIXED;
    slot->anomaly_cfg.frame_stride      = ANOMALY_DEFAULT_FRAME_STRIDE;
    slot->anomaly_cfg.adaptive_min_stride_frames = 2;
    slot->anomaly_cfg.adaptive_max_stride_frames = 33;
    slot->anomaly_cfg.adaptive_max_stride_seconds = 1.0f;
    slot->anomaly_cfg.pixel_step        = 0;
    slot->anomaly_cfg.score_threshold   = ANOMALY_DEFAULT_SCORE_THRESHOLD;
    slot->anomaly_cfg.motion_evidence_scale = 1.0f;
    slot->anomaly_cfg.min_area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION;
    slot->anomaly_cfg.thermal_polarity  = ANOMALY_THERMAL_WHITE_HOT;
    slot->anomaly_cfg.scan_zone         = ANOMALY_SCAN_ZONE_DEFAULT;
    slot->anomaly_cfg.min_hits          = ANOMALY_DEFAULT_MIN_HITS;
    slot->anomaly_cfg.thermal_min_delta = ANOMALY_THERMAL_MIN_DELTA;
    slot->anomaly_cfg.color_frontend_mode = ANOMALY_COLOR_FRONTEND_LEGACY;
    slot->anomaly_cfg.color_target_candidate_limit = 1;
    slot->anomaly_cfg.target_color_family_mask = 0u;
    slot->anomaly_thermal_paused = false;
    slot->anomaly_runtime_disabled = false;
    slot->anomaly_troubleshooting_debug = false;
    slot->anomaly_pause_reason = AD_PAUSE_REASON_NONE;
    slot->anomaly_generation_id = 1;
    slot->anomaly_next_frame_id = 0;
    slot->ad_pressure_frame_counter = 0;
    slot->ad_pressure_mode = AD_PRESSURE_MODE_NORMAL;
    anomaly_state_init(&slot->anomaly_state);
    atomic_init(&slot->person_relevance_mode, ANOMALY_PERSON_RELEVANCE_OFF);
    if (pthread_mutex_init(&slot->person_relevance_config_lock, NULL) != 0) {
        ct_error(TAG, "person relevance config mutex initialization failed");
        memset(slot, 0, sizeof(*slot));
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, designator, d);
        (*env)->ReleaseStringUTFChars(env, url, u);
        return 0;
    }
    slot->person_relevance_config_lock_ready = true;
#if HAVE_FFMPEG && HAVE_SWSCALE
    if (!configure_person_relevance_scheduler(
            slot, ANOMALY_PERSON_RELEVANCE_OFF)) {
#else
    const anomaly_person_relevance_config_t person_relevance_off = {
        .mode = ANOMALY_PERSON_RELEVANCE_OFF,
        .minimum_person_confidence = 0.50f,
        .maximum_confidence_bonus = 0.20f,
    };
    if (!anomaly_person_scheduler_init(
            &slot->person_relevance_scheduler,
            &person_relevance_off,
            NULL,
            NULL,
            NULL,
            NULL,
            NULL)) {
#endif
        ct_error(TAG, "person relevance scheduler OFF initialization failed");
        pthread_mutex_destroy(&slot->person_relevance_config_lock);
        memset(slot, 0, sizeof(*slot));
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, designator, d);
        (*env)->ReleaseStringUTFChars(env, url, u);
        return 0;
    }
    anomaly_detector_annotation_cadence_snapshot_state_init(
            &slot->anomaly_annotation_cadence);
    anomaly_detector_annotation_cadence_snapshot_state_init(
            &slot->anomaly_overlay_publication_cadence);
    if (pthread_mutex_init(&slot->anomaly_lock, NULL) != 0) {
        ct_error(TAG, "pthread_mutex_init failed for anomaly lock");
        anomaly_person_scheduler_destroy(&slot->person_relevance_scheduler);
        pthread_mutex_destroy(&slot->person_relevance_config_lock);
        memset(slot, 0, sizeof(*slot));
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, designator, d);
        (*env)->ReleaseStringUTFChars(env, url, u);
        return 0;
    }
    slot->anomaly_lock_ready = true;
    slot->render_stride = 1;
    slot->render_stride_counter = 0;
    slot->local_playback_paused = false;
    slot->local_playback_step_budget = 0;
    // Render sessions start paused: no frames decoded until the TextureView surface
    // is attached.  Non-render sessions (probe/audio) are never paused this way.
    slot->surface_paused = slot->is_render;
    slot->render_queue_flush_requested = false;
#if HAVE_FFMPEG
    slot->video_stream_index = -1;
#if HAVE_SWSCALE
    slot->anomaly_src_fmt = AV_PIX_FMT_NONE;
    if (slot->is_render) {
        if (pthread_mutex_init(&slot->render_lock, NULL) != 0) {
            ct_error(TAG, "pthread_mutex_init failed for render_lock");
            pthread_mutex_destroy(&slot->anomaly_lock);
            anomaly_person_scheduler_destroy(&slot->person_relevance_scheduler);
            pthread_mutex_destroy(&slot->person_relevance_config_lock);
            memset(slot, 0, sizeof(*slot));
            pthread_mutex_unlock(&g_lock);
            (*env)->ReleaseStringUTFChars(env, designator, d);
            (*env)->ReleaseStringUTFChars(env, url, u);
            return 0;
        }
        if (pthread_cond_init(&slot->render_cond, NULL) != 0) {
            ct_error(TAG, "pthread_cond_init failed for render_cond");
            pthread_mutex_destroy(&slot->render_lock);
            pthread_mutex_destroy(&slot->anomaly_lock);
            anomaly_person_scheduler_destroy(&slot->person_relevance_scheduler);
            pthread_mutex_destroy(&slot->person_relevance_config_lock);
            memset(slot, 0, sizeof(*slot));
            pthread_mutex_unlock(&g_lock);
            (*env)->ReleaseStringUTFChars(env, designator, d);
            (*env)->ReleaseStringUTFChars(env, url, u);
            return 0;
        }
        if (pthread_mutex_init(&slot->ad_lock, NULL) != 0) {
            ct_error(TAG, "pthread_mutex_init failed for ad_lock");
            pthread_cond_destroy(&slot->render_cond);
            pthread_mutex_destroy(&slot->render_lock);
            pthread_mutex_destroy(&slot->anomaly_lock);
            anomaly_person_scheduler_destroy(&slot->person_relevance_scheduler);
            pthread_mutex_destroy(&slot->person_relevance_config_lock);
            memset(slot, 0, sizeof(*slot));
            pthread_mutex_unlock(&g_lock);
            (*env)->ReleaseStringUTFChars(env, designator, d);
            (*env)->ReleaseStringUTFChars(env, url, u);
            return 0;
        }
        if (pthread_cond_init(&slot->ad_cond, NULL) != 0) {
            ct_error(TAG, "pthread_cond_init failed for ad_cond");
            pthread_mutex_destroy(&slot->ad_lock);
            pthread_cond_destroy(&slot->render_cond);
            pthread_mutex_destroy(&slot->render_lock);
            pthread_mutex_destroy(&slot->anomaly_lock);
            anomaly_person_scheduler_destroy(&slot->person_relevance_scheduler);
            pthread_mutex_destroy(&slot->person_relevance_config_lock);
            memset(slot, 0, sizeof(*slot));
            pthread_mutex_unlock(&g_lock);
            (*env)->ReleaseStringUTFChars(env, designator, d);
            (*env)->ReleaseStringUTFChars(env, url, u);
            return 0;
        }
        slot->render_sync_ready = true;
        slot->ad_sync_ready = true;
        slot->render_thread_started = false;
        slot->ad_thread_started = false;
        slot->ad_thread_starting = false;
        slot->render_thread_stop = false;
        slot->ad_thread_stop = false;
        slot->render_queue = NULL;
        slot->ad_input_queue = NULL;
        slot->render_queue_capacity = 0;
        slot->render_queue_head = 0;
        slot->render_queue_depth = 0;
        slot->ad_input_queue_capacity = 0;
        slot->ad_input_queue_head = 0;
        slot->ad_input_queue_depth = 0;
    }
#endif
#endif
    snprintf(slot->designator, sizeof(slot->designator), "%s", d);
    snprintf(slot->url, sizeof(slot->url), "%s", u);
#if HAVE_FFMPEG && HAVE_SWSCALE
    if (slot->is_render) {
        if (!ensure_render_queue_capacity(slot, RENDER_QUEUE_INITIAL_CAPACITY)) {
            ct_warn(TAG,
                    "render queue prealloc failed id=%lld designator=%s",
                    (long long) slot->session_id,
                    slot->designator);
        }
        if (!ensure_ad_input_queue_capacity(slot, AD_INPUT_QUEUE_INITIAL_CAPACITY)) {
            ct_warn(TAG,
                    "ad input queue prealloc failed id=%lld designator=%s",
                    (long long) slot->session_id,
                    slot->designator);
        }
    }
#endif

    int pthread_rc = pthread_create(&slot->thread, NULL, session_thread_main, slot);
    if (pthread_rc != 0) {
        ct_error(TAG, "pthread_create failed rc=%d", pthread_rc);
#if HAVE_FFMPEG && HAVE_SWSCALE
        if (slot->render_sync_ready) {
            destroy_render_queue_storage(slot);
            destroy_ad_input_queue_storage(slot);
            pthread_cond_destroy(&slot->ad_cond);
            pthread_mutex_destroy(&slot->ad_lock);
            pthread_cond_destroy(&slot->render_cond);
            pthread_mutex_destroy(&slot->render_lock);
            slot->render_sync_ready = false;
            slot->ad_sync_ready = false;
        }
#endif
        anomaly_person_scheduler_destroy(&slot->person_relevance_scheduler);
        if (slot->person_relevance_config_lock_ready) {
            pthread_mutex_destroy(&slot->person_relevance_config_lock);
            slot->person_relevance_config_lock_ready = false;
        }
        if (slot->anomaly_lock_ready) {
            pthread_mutex_destroy(&slot->anomaly_lock);
            slot->anomaly_lock_ready = false;
        }
        memset(slot, 0, sizeof(*slot));
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, designator, d);
        (*env)->ReleaseStringUTFChars(env, url, u);
        return 0;
    }

    jlong session_id = slot->session_id;
    pthread_mutex_unlock(&g_lock);

    (*env)->ReleaseStringUTFChars(env, designator, d);
    (*env)->ReleaseStringUTFChars(env, url, u);
    return session_id;
}

JNIEXPORT jboolean JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeIsAvailable(
        JNIEnv *env,
        jobject thiz
) {
    (void) env;
    (void) thiz;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeInitBridge(
        JNIEnv *env,
        jobject thiz
) {
    jclass local_cls = (*env)->GetObjectClass(env, thiz);
    if (local_cls == NULL) return;

    if (g_bridge_class != NULL) {
        (*env)->DeleteGlobalRef(env, g_bridge_class);
        g_bridge_class = NULL;
    }
    g_bridge_class = (*env)->NewGlobalRef(env, local_cls);
    (*env)->DeleteLocalRef(env, local_cls);

    if (g_bridge_class == NULL) return;

    g_dispatch_probe_event_mid = (*env)->GetStaticMethodID(
            env,
            g_bridge_class,
            "dispatchNativeProbeEvent",
        "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;DLjava/lang/String;JJDDDDDDDDLjava/lang/String;)V");
    g_dispatch_remote_video_frame_mid = (*env)->GetStaticMethodID(
            env,
            g_bridge_class,
            "dispatchNativeRemoteVideoFrame",
            "(JIIJ[B)V");

    jclass person_local_cls = (*env)->FindClass(
            env,
            "org/ncssar/rid2caltopo/video/PersonRelevanceCoordinator");
    if (person_local_cls != NULL) {
        if (g_person_relevance_coordinator_class != NULL) {
            (*env)->DeleteGlobalRef(env, g_person_relevance_coordinator_class);
        }
        g_person_relevance_coordinator_class =
                (*env)->NewGlobalRef(env, person_local_cls);
        (*env)->DeleteLocalRef(env, person_local_cls);
        if (g_person_relevance_coordinator_class != NULL) {
            g_person_relevance_infer_mid = (*env)->GetStaticMethodID(
                    env,
                    g_person_relevance_coordinator_class,
                    "inferRgb320",
                    "(Ljava/nio/ByteBuffer;)[F");
        }
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        g_person_relevance_infer_mid = NULL;
    }

    jclass caltopo_local_cls = (*env)->FindClass(env, "org/ncssar/rid2caltopo/data/CaltopoClient");
    if (caltopo_local_cls == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return;
    }

    if (g_caltopo_client_class != NULL) {
        (*env)->DeleteGlobalRef(env, g_caltopo_client_class);
        g_caltopo_client_class = NULL;
    }
    g_caltopo_client_class = (*env)->NewGlobalRef(env, caltopo_local_cls);
    (*env)->DeleteLocalRef(env, caltopo_local_cls);
    if (g_caltopo_client_class == NULL) return;

    g_research_log_mid = (*env)->GetStaticMethodID(env,g_caltopo_client_class,
            "CTLog", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    g_ctdebug_mid = (*env)->GetStaticMethodID(
            env,
            g_caltopo_client_class,
            "CTDebug",
            "(Ljava/lang/String;Ljava/lang/String;)V");
    g_ctwarn_mid = (*env)->GetStaticMethodID(
            env,
            g_caltopo_client_class,
            "CTWarn",
            "(Ljava/lang/String;Ljava/lang/String;)V");
    g_cterror_mid = (*env)->GetStaticMethodID(
            env,
            g_caltopo_client_class,
            "CTError",
            "(Ljava/lang/String;Ljava/lang/String;)V");
    g_register_debug_tag_mid = (*env)->GetStaticMethodID(
            env,
            g_caltopo_client_class,
            "RegisterDebugTag",
            "(Ljava/lang/String;)V");

    if (g_register_debug_tag_mid != NULL) {
        jstring j_tag = (*env)->NewStringUTF(env, TAG);
        (*env)->CallStaticVoidMethod(env, g_caltopo_client_class, g_register_debug_tag_mid, j_tag);
        (*env)->DeleteLocalRef(env, j_tag);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
    }
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeSetDjiSeiHexDumpEnabled(
        JNIEnv *env,
        jobject thiz,
        jboolean enabled
) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&g_sei_capture_lock);
    if (atomic_load(&g_dji_sei_hex_dump_enabled)) R2CSEISummary(&g_sei_capture,sei_capture_log);
    if (enabled == JNI_TRUE) R2CSEIReset(&g_sei_capture);
    atomic_store(&g_dji_sei_hex_dump_enabled, enabled == JNI_TRUE);
    pthread_mutex_unlock(&g_sei_capture_lock);
}

JNIEXPORT jstring JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeDecoderBackend(
        JNIEnv *env,
        jobject thiz
) {
    (void) thiz;
#if HAVE_FFMPEG
    return (*env)->NewStringUTF(env, "ffmpeg-linked");
#else
    return (*env)->NewStringUTF(env, "stub");
#endif
}

JNIEXPORT jlong JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeStartProbe(
        JNIEnv *env,
        jobject thiz,
        jstring designator,
        jstring rtsp_url
) {
    (void) thiz;
    return start_session(env, designator, rtsp_url, false);
}

JNIEXPORT jlong JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeStartRender(
        JNIEnv *env,
        jobject thiz,
        jstring designator,
        jstring rtsp_url
) {
    (void) thiz;
    return start_session(env, designator, rtsp_url, true);
}

JNIEXPORT jboolean JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeAttachSurface(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jobject surface
) {
    (void) thiz;

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) return JNI_FALSE;

    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session == NULL || !session->active) {
        pthread_mutex_unlock(&g_lock);
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    if (session->surface_global_ref != NULL) {
        (*env)->DeleteGlobalRef(env, session->surface_global_ref);
        session->surface_global_ref = NULL;
    }
    if (session->window != NULL) {
        ANativeWindow_release(session->window);
    }

    bool was_surface_paused = session->surface_paused;
    session->surface_global_ref = (*env)->NewGlobalRef(env, surface);
    session->window = window;
    session->surface_paused = false;  // surface ready — resume decode
    if (was_surface_paused && session->is_render) {
        clear_render_queue(session);
        session->render_queue_flush_requested = false;
        if (session->ad_sync_ready) {
            pthread_mutex_lock(&session->ad_lock);
            clear_ad_input_queue(session);
            pthread_mutex_unlock(&session->ad_lock);
        }
        reset_render_timing_state_locked(session, false, false);
        ct_debug(TAG,
                 "render resume reset id=%lld designator=%s targetLatencyMs=%lld",
                 (long long) session->session_id,
                 session->designator,
                 (long long) session->target_latency_ms);
    }
    pthread_mutex_unlock(&g_lock);

    dispatch_probe_event(session->designator, "surface_attached", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    return JNI_TRUE;
}

// One immutable snapshot: header (PTS, width, height) followed by packed RGBA.
JNIEXPORT jbyteArray JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeCaptureRenderedFrame(
        JNIEnv *env, jobject thiz, jlong session_id) {
    (void) thiz;
    jbyteArray result = NULL;
#if HAVE_FFMPEG && HAVE_SWSCALE
    ffmpeg_session_t *session = NULL;
    // Never wait for render_lock while holding g_lock: the renderer calls back into Java.
    // Retry briefly between posts; g_lock then pins the session for the pixel copy.
    bool locked = false;
    for (int attempt = 0; attempt < 50; attempt++) {
        pthread_mutex_lock(&g_lock);
        session = find_session_locked(session_id);
        if (session == NULL || !session->active || !session->render_sync_ready) {
            pthread_mutex_unlock(&g_lock);
            return NULL;
        }
        if (pthread_mutex_trylock(&session->render_lock) == 0) {
            locked = true;
            break;
        }
        pthread_mutex_unlock(&g_lock);
        usleep(1000);
    }
    if (!locked) return NULL;
    {
        AVFrame *frame = session->capture_overlay_frame != NULL
                ? session->capture_overlay_frame : session->rgba_frame;
        if (session->capture_frame_valid && frame != NULL && frame->data[0] != NULL &&
            frame->width > 0 && frame->height > 0 &&
            (is_local_file_source(session) || monotonic_ms() - session->last_render_post_at_ms <= 3000)) {
            int64_t byte_count = (int64_t) frame->width * frame->height * 4;
            if (byte_count > 0 && byte_count < INT32_MAX - 16) {
                result = (*env)->NewByteArray(env, (jsize) byte_count + 16);
                if (result != NULL) {
                    int64_t pts = session->capture_source_ts_us;
                    int32_t width = frame->width, height = frame->height;
                    (*env)->SetByteArrayRegion(env, result, 0, 8, (const jbyte *) &pts);
                    (*env)->SetByteArrayRegion(env, result, 8, 4, (const jbyte *) &width);
                    (*env)->SetByteArrayRegion(env, result, 12, 4, (const jbyte *) &height);
                    for (int y = 0; y < height && !(*env)->ExceptionCheck(env); y++) {
                        (*env)->SetByteArrayRegion(env, result, 16 + y * width * 4, width * 4,
                                (const jbyte *) (frame->data[0] + y * frame->linesize[0]));
                    }
                }
            }
        }
        pthread_mutex_unlock(&session->render_lock);
    }
    pthread_mutex_unlock(&g_lock);
#endif
    return result;
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeDetachSurface(
        JNIEnv *env,
        jobject thiz,
        jlong session_id
) {
    (void) thiz;

    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active) {
        if (session->surface_global_ref != NULL) {
            (*env)->DeleteGlobalRef(env, session->surface_global_ref);
            session->surface_global_ref = NULL;
        }
        if (session->window != NULL) {
            ANativeWindow_release(session->window);
            session->window = NULL;
        }
        // Pause decode and request a render-queue flush.  The decode thread will
        // drop incoming packets until the surface is reattached; the render thread
        // will drain and discard any already-queued frames on its next iteration.
        session->surface_paused = true;
        session->render_queue_flush_requested = true;
        if (session->ad_sync_ready) {
            pthread_mutex_lock(&session->ad_lock);
            clear_ad_input_queue(session);
            pthread_mutex_unlock(&session->ad_lock);
        }
        dispatch_probe_event(session->designator, "surface_detached", session->session_id, 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
    }
    pthread_mutex_unlock(&g_lock);

}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeUpdateAnomalyConfig(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jboolean enabled,
        jboolean show_hot_overlay,
        jboolean show_candidate_blobs,
        jboolean troubleshooting_debug,
        jint algorithm_mask,
        jint registration_mode,
        jint movement_estimator_mode,
        jint stride_mode,
        jint frame_stride,
        jint adaptive_min_stride_frames,
        jint adaptive_max_stride_frames,
        jfloat adaptive_max_stride_seconds,
        jint pixel_step,
        jfloat score_threshold,
        jfloat motion_evidence_scale,
        jfloat min_area_fraction,
        jint thermal_polarity,
        jfloat scan_zone,
        jint min_hits,
        jfloat thermal_min_delta,
        jfloat small_target_screen_fraction,
        jint color_frontend_mode,
        jint color_target_candidate_limit,
        jint target_color_family_mask
) {
    (void) env;
    (void) thiz;
    bool should_reconfigure = false;
    bool should_enable = false;
    bool force_config_reset = false;
    ad_pause_reason_t pause_reason = AD_PAUSE_REASON_NONE;
    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active) {
        anomaly_config_t previous_cfg = session->anomaly_cfg;
        bool previous_effective_enabled =
                session->anomaly_cfg.enabled &&
                !session->anomaly_thermal_paused &&
                !session->anomaly_runtime_disabled &&
                (session->anomaly_cfg.algorithm_mask != 0);
        ad_pause_reason_t previous_pause_reason = session->anomaly_pause_reason;
        float sz = (float) scan_zone;
        int   mh = (int)   min_hits;
        int adaptive_min_frames = (int) adaptive_min_stride_frames;
        int adaptive_max_frames = (int) adaptive_max_stride_frames;
        if (adaptive_min_frames < 2) adaptive_min_frames = 2;
        if (adaptive_min_frames > 33) adaptive_min_frames = 33;
        if (adaptive_max_frames < adaptive_min_frames) adaptive_max_frames = adaptive_min_frames;
        if (adaptive_max_frames > 120) adaptive_max_frames = 120;
        session->anomaly_cfg.enabled           = (enabled == JNI_TRUE);
        session->anomaly_cfg.show_hot_overlay  = (show_hot_overlay == JNI_TRUE);
        session->anomaly_cfg.show_candidate_blobs = (show_candidate_blobs == JNI_TRUE);
        session->anomaly_troubleshooting_debug = (troubleshooting_debug == JNI_TRUE);
        session->anomaly_cfg.algorithm_mask    = (int) algorithm_mask;
        session->anomaly_cfg.registration_mode = ((int) registration_mode == ANOMALY_REGISTRATION_AFFINE)
                                                 ? ANOMALY_REGISTRATION_AFFINE
                                                 : ANOMALY_REGISTRATION_GMV;
        session->anomaly_cfg.movement_estimator_mode =
                (movement_estimator_mode == ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE)
                ? ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE
                : ((movement_estimator_mode == ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW)
                    ? ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW
                    : ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE);
        session->anomaly_cfg.stride_mode       =
                (stride_mode == ANOMALY_STRIDE_MODE_ADAPTIVE)
                ? ANOMALY_STRIDE_MODE_ADAPTIVE
                : ANOMALY_STRIDE_MODE_FIXED;
        session->anomaly_cfg.frame_stride      = ((int) frame_stride < 1) ? 1 : (((int) frame_stride > 120) ? 120 : (int) frame_stride);
        session->anomaly_cfg.adaptive_min_stride_frames = adaptive_min_frames;
        session->anomaly_cfg.adaptive_max_stride_frames = adaptive_max_frames;
        session->anomaly_cfg.adaptive_max_stride_seconds =
                adaptive_max_stride_seconds < 0.1f ? 0.1f
                : (adaptive_max_stride_seconds > 10.0f ? 10.0f : adaptive_max_stride_seconds);
        session->anomaly_cfg.pixel_step        = ((int) pixel_step < 0) ? 0 : (int) pixel_step;
        session->anomaly_cfg.score_threshold   = fmaxf(0.1f, score_threshold);
        session->anomaly_cfg.motion_evidence_scale = fminf(fmaxf(motion_evidence_scale, 0.1f), 4.0f);
        session->anomaly_cfg.min_area_fraction = fminf(fmaxf(min_area_fraction, 0.0001f), 0.20f);
        session->anomaly_cfg.thermal_polarity  = ((int) thermal_polarity == ANOMALY_THERMAL_BLACK_HOT)
                                                 ? ANOMALY_THERMAL_BLACK_HOT : ANOMALY_THERMAL_WHITE_HOT;
        session->anomaly_cfg.scan_zone         = sz < 0.5f ? 0.5f : (sz > 1.0f ? 1.0f : sz);
        session->anomaly_cfg.min_hits          = mh < 1 ? 1 : (mh > 10 ? 10 : mh);
        session->anomaly_cfg.thermal_min_delta = thermal_min_delta > 0.0f
                                                 ? thermal_min_delta : ANOMALY_THERMAL_MIN_DELTA;
        session->anomaly_cfg.small_target_screen_fraction =
                small_target_screen_fraction > 0.0f ? small_target_screen_fraction : (1.0f / 250.0f);
        session->anomaly_cfg.color_frontend_mode =
                (color_frontend_mode == ANOMALY_COLOR_FRONTEND_FRESH_YUV)
                ? ANOMALY_COLOR_FRONTEND_FRESH_YUV
                : ((color_frontend_mode == ANOMALY_COLOR_FRONTEND_FRESH_RGBA)
                    ? ANOMALY_COLOR_FRONTEND_FRESH_RGBA
                    : ANOMALY_COLOR_FRONTEND_LEGACY);
        int color_candidate_limit = (int) color_target_candidate_limit;
        session->anomaly_cfg.color_target_candidate_limit =
                color_candidate_limit < 1 ? 1 : (color_candidate_limit > 4 ? 4 : color_candidate_limit);
        session->anomaly_cfg.target_color_family_mask =
                ((uint32_t) target_color_family_mask) & ANOMALY_TARGET_COLOR_ALL;
        anomaly_config_transition_t config_transition =
                anomaly_config_transition_classify(&previous_cfg, &session->anomaly_cfg);
        bool log_local_config =
                is_local_file_source(session) &&
                session->anomaly_cfg.enabled &&
                session->anomaly_cfg.algorithm_mask != 0;
        if (session->anomaly_troubleshooting_debug || log_local_config) {
            ct_debug(TAG,
                     "anomaly config applied id=%lld designator=%s local=%d enabled=%d mask=%d reg=%d movement=%d strideMode=%d stride=%d adaptiveMin=%d adaptiveMaxFrames=%d adaptiveMaxSec=%.1f pixelStep=%d threshold=%.2f minHits=%d scanZone=%.2f colorFrontend=%d colorCandidates=%d targetColors=%u thermalPause=%d runtimeDisabled=%d",
                     (long long) session->session_id,
                     session->designator,
                     is_local_file_source(session) ? 1 : 0,
                     session->anomaly_cfg.enabled ? 1 : 0,
                     session->anomaly_cfg.algorithm_mask,
                     session->anomaly_cfg.registration_mode,
                     session->anomaly_cfg.movement_estimator_mode,
                     session->anomaly_cfg.stride_mode,
                     session->anomaly_cfg.frame_stride,
                     session->anomaly_cfg.adaptive_min_stride_frames,
                     session->anomaly_cfg.adaptive_max_stride_frames,
                     session->anomaly_cfg.adaptive_max_stride_seconds,
                     session->anomaly_cfg.pixel_step,
                     session->anomaly_cfg.score_threshold,
                     session->anomaly_cfg.min_hits,
                     session->anomaly_cfg.scan_zone,
                     session->anomaly_cfg.color_frontend_mode,
                     session->anomaly_cfg.color_target_candidate_limit,
                     session->anomaly_cfg.target_color_family_mask,
                     session->anomaly_thermal_paused ? 1 : 0,
                     session->anomaly_runtime_disabled ? 1 : 0);
        }
        should_enable = session->anomaly_cfg.enabled &&
                        !session->anomaly_thermal_paused &&
                        (session->anomaly_cfg.algorithm_mask != 0);
        pause_reason = session->anomaly_thermal_paused ? AD_PAUSE_REASON_THERMAL : AD_PAUSE_REASON_NONE;
        bool mode_change_expected =
                previous_effective_enabled != should_enable ||
                previous_pause_reason != pause_reason ||
                (should_enable && session->anomaly_runtime_disabled);
        force_config_reset =
                config_transition == ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE &&
                should_enable &&
                !mode_change_expected;
        should_reconfigure = true;
    }
    if (should_reconfigure) {
        reconfigure_anomaly_mode(session, should_enable, pause_reason, should_enable);
        if (force_config_reset) {
            session->anomaly_generation_id += 1;
            if (session->ad_sync_ready) {
                pthread_mutex_lock(&session->ad_lock);
                clear_ad_input_queue(session);
                pthread_cond_signal(&session->ad_cond);
                pthread_mutex_unlock(&session->ad_lock);
            }
            if (session->render_sync_ready) {
                pthread_mutex_lock(&session->render_lock);
                pthread_cond_signal(&session->render_cond);
                pthread_mutex_unlock(&session->render_lock);
            }
            reset_anomaly_tracking_state(session);
        }
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeSetAnomalyThermalPaused(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jboolean paused
) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active) {
        session->anomaly_thermal_paused = paused == JNI_TRUE;
        bool should_enable = session->anomaly_cfg.enabled &&
                             !session->anomaly_thermal_paused &&
                             (session->anomaly_cfg.algorithm_mask != 0);
        ad_pause_reason_t pause_reason =
                session->anomaly_thermal_paused ? AD_PAUSE_REASON_THERMAL : AD_PAUSE_REASON_NONE;
        reconfigure_anomaly_mode(session, should_enable, pause_reason, should_enable);
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jboolean JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeSetPersonRelevanceMode(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jint mode
) {
    (void)env;
    (void)thiz;
    if (mode < ANOMALY_PERSON_RELEVANCE_OFF ||
        mode > ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY) {
        return JNI_FALSE;
    }
    bool configured = false;
    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active && session->running && session->is_render &&
        session->person_relevance_config_lock_ready) {
#if HAVE_FFMPEG && HAVE_SWSCALE
        pthread_mutex_lock(&session->person_relevance_config_lock);
        configured = configure_person_relevance_scheduler(
                session, (anomaly_person_relevance_mode_t)mode);
        pthread_mutex_unlock(&session->person_relevance_config_lock);
#else
        configured = mode == ANOMALY_PERSON_RELEVANCE_OFF;
#endif
    }
    pthread_mutex_unlock(&g_lock);
    return configured ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeGetSessionPerfStats(
        JNIEnv *env,
        jobject thiz,
        jlong session_id
) {
    (void) thiz;
    jlong values[46];
    memset(values, 0, sizeof(values));

    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session == NULL || !session->active) {
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }
    values[0] = (jlong) session->anomaly_process_frame_count;
    values[1] = (jlong) session->anomaly_annotated_frame_count;
    values[2] = (jlong) session->anomaly_process_total_us;
    values[3] = (jlong) session->anomaly_process_max_us;
    values[4] = (jlong) session->anomaly_process_last_us;
    values[5] = (jlong) session->local_playback_first_pts_us;
    values[6] = (jlong) session->local_playback_last_pts_us;
    values[7] = (jlong) session->local_playback_first_render_at_ms;
    values[8] = (jlong) session->local_playback_last_render_at_ms;
    values[9] = (jlong) session->local_playback_display_pts_us;
    values[10] = (jlong) session->anomaly_reg_health_healthy_count;
    values[11] = (jlong) session->anomaly_reg_health_soft_count;
    values[12] = (jlong) session->anomaly_reg_health_hard_count;
    values[13] = (jlong) session->anomaly_reg_health_invalid_count;
    values[14] = (jlong) session->anomaly_rescan_full_count;
    values[15] = (jlong) session->anomaly_rescan_partial_count;
    values[16] = (jlong) session->anomaly_rescan_target_only_count;
    values[17] = (jlong) session->anomaly_rescan_stride_skip_count;
    values[18] = (jlong) session->anomaly_last_registration_health;
    values[19] = (jlong) session->anomaly_last_rescan_mode;
    recent_local_playback_timing_span(
            session,
            &values[20],
            &values[21],
            &values[22],
            &values[23]);
    values[24] = (jlong) session->ad_input_queue_depth;
    values[25] = (jlong) session->ad_input_queue_depth_max;
    values[26] = (jlong) session->ad_forwarded_without_analysis_count;
    values[27] = (jlong) session->ad_full_queue_disable_count;
    values[28] = (jlong) session->ad_analyzed_rendered_frame_count;
    values[29] = (jlong) session->ad_bypassed_rendered_frame_count;
    values[30] = (jlong) current_ad_runtime_mode(session);
    values[31] = (jlong) session->ad_worker_processed_frame_count;
    values[32] = (jlong) session->ad_worker_annotated_frame_count;
    values[33] = (jlong) session->ad_worker_overlay_enqueued_count;
    anomaly_person_scheduler_metrics_t person_metrics;
    anomaly_person_scheduler_metrics(
            &session->person_relevance_scheduler, &person_metrics);
    values[34] = (jlong)atomic_load_explicit(
            &session->person_relevance_mode, memory_order_relaxed);
    values[35] = (jlong)person_metrics.offered;
    values[36] = (jlong)person_metrics.admitted;
    values[37] = (jlong)person_metrics.contention_drops;
    values[38] = (jlong)person_metrics.pressure_drops;
    values[39] = (jlong)person_metrics.replacements;
    values[40] = (jlong)person_metrics.completed;
    values[41] = (jlong)person_metrics.stale_discarded;
    values[42] = (jlong)person_metrics.backend_failures;
    values[43] = (jlong)person_metrics.positive_decisions;
    values[44] = (jlong)person_metrics.bonuses_applied;
    values[45] = (jlong)person_metrics.inference_wall_time_us;
    pthread_mutex_unlock(&g_lock);

    jlongArray array = (*env)->NewLongArray(env, 46);
    if (array == NULL) return NULL;
    (*env)->SetLongArrayRegion(env, array, 0, 46, values);
    return array;
}

JNIEXPORT jstring JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeGetSessionDebugSummary(
        JNIEnv *env,
        jobject thiz,
        jlong session_id
) {
    (void) thiz;
    char summary[1024];
    summary[0] = '\0';
    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active && session->anomaly_troubleshooting_debug) {
        const char *bridge = session->latest_ad_bridge_debug_summary;
        const char *detector = session->latest_anomaly_debug_summary;
        if (bridge[0] != '\0' && detector[0] != '\0') {
            snprintf(summary, sizeof(summary), "%s | %s", bridge, detector);
        } else if (bridge[0] != '\0') {
            snprintf(summary, sizeof(summary), "%s", bridge);
        } else if (detector[0] != '\0') {
            snprintf(summary, sizeof(summary), "%s", detector);
        }
        anomaly_person_scheduler_metrics_t metrics;
        anomaly_person_scheduler_metrics(
                &session->person_relevance_scheduler, &metrics);
        size_t used = strlen(summary);
        snprintf(summary + used,
                 sizeof(summary) - used,
                 "%sperson{mode=%d offered=%llu admitted=%llu pressureDrop=%llu "
                 "contentionDrop=%llu replaced=%llu completed=%llu stale=%llu "
                 "failure=%llu positive=%llu applied=%llu inferUs=%llu}",
                 used > 0 ? " | " : "",
                 atomic_load_explicit(
                         &session->person_relevance_mode, memory_order_relaxed),
                 (unsigned long long)metrics.offered,
                 (unsigned long long)metrics.admitted,
                 (unsigned long long)metrics.pressure_drops,
                 (unsigned long long)metrics.contention_drops,
                 (unsigned long long)metrics.replacements,
                 (unsigned long long)metrics.completed,
                 (unsigned long long)metrics.stale_discarded,
                 (unsigned long long)metrics.backend_failures,
                 (unsigned long long)metrics.positive_decisions,
                 (unsigned long long)metrics.bonuses_applied,
                 (unsigned long long)metrics.inference_wall_time_us);
    }
    pthread_mutex_unlock(&g_lock);
    if (summary[0] == '\0') return NULL;
    return (*env)->NewStringUTF(env, summary);
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeSetLocalPlaybackPaused(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jboolean paused
) {
    (void) env;
    (void) thiz;
    ffmpeg_session_t *session = NULL;
    anomaly_detector_runtime_budget_local_playback_pause_t pause_state = {0};
    bool should_update_ad = false;
    pthread_mutex_lock(&g_lock);
    session = find_session_locked(session_id);
    if (session != NULL && session->active && is_local_file_source(session)) {
        bool pause_enabled = paused == JNI_TRUE;
        pause_state = anomaly_detector_runtime_budget_local_playback_pause(pause_enabled);
        session->local_playback_paused = pause_enabled;
        if (pause_state.clear_step_budget) {
            session->local_playback_step_budget = 0;
        }
        if (pause_state.clear_history_replay) {
            session->local_playback_history_replay_active = false;
        }
        should_update_ad = session->ad_sync_ready;
    }
    pthread_mutex_unlock(&g_lock);
    if (session != NULL && should_update_ad && pause_state.clear_ad_queue) {
        pthread_mutex_lock(&session->ad_lock);
        clear_ad_input_queue(session);
        pthread_cond_signal(&session->ad_cond);
        pthread_mutex_unlock(&session->ad_lock);
    }
    if (session != NULL && session->render_sync_ready) {
        pthread_mutex_lock(&session->render_lock);
        if (pause_state.clear_render_queue) {
            clear_render_queue(session);
        }
        if (pause_state.reset_render_timing) {
            reset_render_timing_state_locked(session, false, false);
        }
        session->next_render_due_ms = 0;
        pthread_cond_signal(&session->render_cond);
        pthread_mutex_unlock(&session->render_lock);
    }
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeStepLocalPlayback(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jint frame_count
) {
    (void) env;
    (void) thiz;
    ffmpeg_session_t *session = NULL;
    bool render_from_history = false;
    int history_offset = 0;
    pthread_mutex_lock(&g_lock);
    session = find_session_locked(session_id);
    if (session != NULL && session->active && is_local_file_source(session)) {
        session->local_playback_paused = true;
        anomaly_detector_runtime_budget_local_playback_step_t step =
                anomaly_detector_runtime_budget_local_playback_step_forward(
                        frame_count,
                        session->local_playback_history_offset,
                        session->local_playback_history_replay_active,
                        session->local_playback_step_budget,
                        INT64_MAX);
        session->local_playback_history_offset = step.history_offset;
        session->local_playback_history_replay_active = step.replay_active;
        if (step.render_from_history) {
            history_offset = session->local_playback_history_offset;
            render_from_history = true;
        } else {
            if (step.reset_tracking) {
                reset_anomaly_tracking_state(session);
            }
            session->local_playback_step_budget = step.step_budget;
            if (session->render_sync_ready) {
                pthread_mutex_lock(&session->render_lock);
                clear_render_queue(session);
                session->next_render_due_ms = 0;
                pthread_cond_signal(&session->render_cond);
                pthread_mutex_unlock(&session->render_lock);
            }
        }
    }
    pthread_mutex_unlock(&g_lock);

    if (render_from_history && session != NULL && session->render_sync_ready) {
        // History playback shows older decoded frames without rewinding the decoder,
        // so clear detector state before drawing them to avoid future-frame ROI latches.
        reset_anomaly_tracking_state(session);
        pthread_mutex_lock(&session->render_lock);
        int64_t history_pts_us = 0;
        AVFrame *history_frame =
                clone_local_playback_history_frame_locked(session, history_offset, &history_pts_us);
        pthread_mutex_unlock(&session->render_lock);
        if (history_frame != NULL) {
            render_frame_to_surface(session, history_frame, NULL, NULL, false, history_pts_us, 0, false);
            av_frame_free(&history_frame);
        }
    }
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeStepLocalPlaybackBack(
        JNIEnv *env,
        jobject thiz,
        jlong session_id
) {
    (void) env;
    (void) thiz;
    ffmpeg_session_t *session = NULL;
    bool render_from_history = false;
    int history_offset = 0;
    pthread_mutex_lock(&g_lock);
    session = find_session_locked(session_id);
    if (session != NULL && session->active && is_local_file_source(session)) {
        session->local_playback_paused = true;
        if (session->render_sync_ready) {
            pthread_mutex_lock(&session->render_lock);
            anomaly_detector_runtime_budget_local_playback_step_t step =
                    anomaly_detector_runtime_budget_local_playback_step_back(
                            session->local_playback_history_offset,
                            session->local_playback_history_count);
            if (step.render_from_history) {
                session->local_playback_history_offset = step.history_offset;
                session->local_playback_history_replay_active = step.replay_active;
                history_offset = session->local_playback_history_offset;
                render_from_history = true;
            }
            pthread_mutex_unlock(&session->render_lock);
        }
    }
    pthread_mutex_unlock(&g_lock);

    if (render_from_history && session != NULL && session->render_sync_ready) {
        // History playback shows older decoded frames without rewinding the decoder,
        // so clear detector state before drawing them to avoid future-frame ROI latches.
        reset_anomaly_tracking_state(session);
        pthread_mutex_lock(&session->render_lock);
        int64_t history_pts_us = 0;
        AVFrame *history_frame =
                clone_local_playback_history_frame_locked(session, history_offset, &history_pts_us);
        pthread_mutex_unlock(&session->render_lock);
        if (history_frame != NULL) {
            render_frame_to_surface(session, history_frame, NULL, NULL, false, history_pts_us, 0, false);
            av_frame_free(&history_frame);
        }
    }
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeSetRenderStride(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jint stride
) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active) {
        int new_stride = ((int) stride < 1) ? 1 : (int) stride;
        if (new_stride != session->render_stride) {
            ct_debug(TAG,
                     "render stride id=%lld designator=%s: %d -> %d",
                     (long long) session->session_id,
                     session->designator,
                     session->render_stride,
                     new_stride);
            session->render_stride = new_stride;
            session->render_stride_counter = 0;
        }
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jboolean JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeConfigureRemoteVideoFrames(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jint width,
        jint height,
        jdouble fps,
        jboolean enabled
) {
    (void) env;
    (void) thiz;
#if HAVE_FFMPEG && HAVE_SWSCALE
    bool configured = false;
    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active && session->is_render) {
        if (enabled == JNI_TRUE) {
            int configured_width = ((int) width) & ~1;
            int configured_height = ((int) height) & ~1;
            double configured_fps = fmax(1.0, fmin(30.0, (double) fps));
            if (configured_width >= 2 && configured_height >= 2) {
                session->remote_video_width = configured_width;
                session->remote_video_height = configured_height;
                session->remote_video_fps = configured_fps;
                session->remote_video_next_due_ms = 0;
                session->remote_video_enabled = true;
                configured = true;
            }
        } else {
            session->remote_video_enabled = false;
            session->remote_video_width = 0;
            session->remote_video_height = 0;
            session->remote_video_fps = 0.0;
            session->remote_video_next_due_ms = 0;
            configured = true;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return configured ? JNI_TRUE : JNI_FALSE;
#else
    (void) session_id;
    (void) width;
    (void) height;
    (void) fps;
    (void) enabled;
    return JNI_FALSE;
#endif
}

JNIEXPORT jlongArray JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeGetVideoSourceInfo(
        JNIEnv *env,
        jobject thiz,
        jlong session_id
) {
    (void) thiz;
#if HAVE_FFMPEG
    jlong values[5] = {0, 0, 0, 0, 0};
    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active && session->codec != NULL) {
        values[0] = (jlong) session->codec->width;
        values[1] = (jlong) session->codec->height;
        values[3] = (jlong) fmax(0, session->codec->bit_rate);
        values[4] = (jlong) session->codec->codec_id;
        if (session->fmt != NULL && session->video_stream_index >= 0 &&
            session->video_stream_index < (int) session->fmt->nb_streams) {
            AVStream *stream = session->fmt->streams[session->video_stream_index];
            AVRational rate = stream->avg_frame_rate.num > 0
                    ? stream->avg_frame_rate : stream->r_frame_rate;
            if (rate.num > 0 && rate.den > 0) {
                values[2] = (jlong) llround(av_q2d(rate) * 1000.0);
            }
            if (values[3] <= 0 && stream->codecpar != NULL) {
                values[3] = (jlong) fmax(0, stream->codecpar->bit_rate);
            }
        }
    }
    pthread_mutex_unlock(&g_lock);
    if (values[0] <= 0 || values[1] <= 0) return NULL;
    jlongArray result = (*env)->NewLongArray(env, 5);
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 5, values);
    return result;
#else
    (void) env;
    (void) session_id;
    return NULL;
#endif
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeStop(
        JNIEnv *env,
        jobject thiz,
        jlong session_id
) {
    (void) thiz;

    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session == NULL || !session->active) {
        pthread_mutex_unlock(&g_lock);
        return;
    }

    session->running = false;
    pthread_t thread = session->thread;
    pthread_mutex_unlock(&g_lock);

    ct_debug(TAG,
             "nativeStop begin id=%lld designator=%s",
             (long long) session_id,
             session->designator);

#if HAVE_FFMPEG && HAVE_SWSCALE
    if (session->is_render && session->ad_sync_ready) {
        pthread_mutex_lock(&session->ad_lock);
        session->ad_thread_stop = true;
        pthread_cond_signal(&session->ad_cond);
        pthread_mutex_unlock(&session->ad_lock);
    }
    if (session->is_render && session->render_sync_ready) {
        pthread_mutex_lock(&session->render_lock);
        session->render_thread_stop = true;
        pthread_cond_signal(&session->render_cond);
        pthread_mutex_unlock(&session->render_lock);
    }
#endif

    pthread_join(thread, NULL);

    pthread_mutex_lock(&g_lock);
    if (session->surface_global_ref != NULL) {
        (*env)->DeleteGlobalRef(env, session->surface_global_ref);
        session->surface_global_ref = NULL;
    }
    if (session->window != NULL) {
        ANativeWindow_release(session->window);
        session->window = NULL;
    }
#if HAVE_FFMPEG && HAVE_SWSCALE
    if (session->render_sync_ready) {
        destroy_render_queue_storage(session);
        destroy_ad_input_queue_storage(session);
        pthread_cond_destroy(&session->ad_cond);
        pthread_mutex_destroy(&session->ad_lock);
        pthread_cond_destroy(&session->render_cond);
        pthread_mutex_destroy(&session->render_lock);
        session->render_sync_ready = false;
        session->ad_sync_ready = false;
    }
#endif
    if (session->anomaly_lock_ready) {
        pthread_mutex_destroy(&session->anomaly_lock);
        session->anomaly_lock_ready = false;
    }
    anomaly_person_scheduler_destroy(&session->person_relevance_scheduler);
    if (session->person_relevance_config_lock_ready) {
        pthread_mutex_destroy(&session->person_relevance_config_lock);
        session->person_relevance_config_lock_ready = false;
    }
    memset(session, 0, sizeof(*session));
    pthread_mutex_unlock(&g_lock);

}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}
