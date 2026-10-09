#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY = 0,
    ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH = 1,
} anomaly_detector_processing_mode_t;

typedef struct {
    float render_backlog_seconds;
    float startup_elapsed_seconds;
    float startup_skip_seconds;
    float cursory_backlog_seconds;
    float thorough_backlog_seconds;
    float max_backlog_seconds;
    bool adapter_pressure;
} anomaly_detector_runtime_budget_t;

typedef struct {
    int64_t interval_ms;
    int confidence;
} anomaly_detector_runtime_budget_source_interval_estimate_t;

typedef struct {
    int64_t desired_interval_ms;
    int64_t render_interval_ms;
} anomaly_detector_runtime_budget_render_interval_t;

typedef struct {
    int64_t pts_us;
    bool repaired;
} anomaly_detector_runtime_budget_local_playback_pts_t;

typedef struct {
    int oldest_index;
    int newest_index;
    bool valid;
} anomaly_detector_runtime_budget_local_playback_timing_indices_t;

typedef struct {
    int slot_index;
    int history_offset;
    bool valid;
} anomaly_detector_runtime_budget_local_playback_history_slot_t;

typedef struct {
    int history_offset;
    int64_t step_budget;
    bool replay_active;
    bool render_from_history;
    bool reset_tracking;
} anomaly_detector_runtime_budget_local_playback_step_t;

typedef struct {
    int slot_index;
    int next_index;
    int count;
    bool valid;
} anomaly_detector_runtime_budget_local_playback_append_t;

typedef struct {
    int64_t step_budget;
    bool advance;
    bool consume_step;
} anomaly_detector_runtime_budget_local_playback_advance_t;

typedef struct {
    bool clear_render_queue;
    bool clear_ad_queue;
    bool reset_render_timing;
    bool clear_step_budget;
    bool clear_history_replay;
} anomaly_detector_runtime_budget_local_playback_pause_t;

typedef struct {
    int64_t elapsed_ms;
    bool observing;
    bool finalize;
} anomaly_detector_runtime_budget_startup_observation_t;

typedef struct {
    int64_t lag_ms;
    int64_t lag_budget_ms;
    bool severe_lag;
    bool periodic_log;
    bool update_log_timestamp;
} anomaly_detector_runtime_budget_render_lag_t;

typedef struct {
    int64_t elapsed_ms;
    bool wait;
    bool complete;
} anomaly_detector_runtime_budget_local_ad_startup_preroll_t;

typedef struct {
    bool analyze;
    bool prediction_only;
    bool full_scan_due;
    int frame_stride_override;
} anomaly_detector_runtime_budget_local_ad_cadence_t;

typedef enum {
    ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_OVERLAY_NONE = 0,
    ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_OVERLAY_ATTACHED = 1,
    ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_OVERLAY_FORWARD_LATE = 2,
} anomaly_detector_runtime_budget_local_ad_overlay_action_t;

typedef enum {
    ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_ROUTE_RENDER_FIRST = 0,
    ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_ROUTE_INLINE_REVIEW = 1,
} anomaly_detector_runtime_budget_local_ad_route_t;

typedef struct {
    int head;
    int depth;
    bool valid;
} anomaly_detector_runtime_budget_queue_pop_t;

typedef struct {
    int drop_count;
    int head;
    int depth;
    bool valid;
} anomaly_detector_runtime_budget_queue_trim_t;

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_make_default(
        float frame_rate_fps);

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_normalize(
        anomaly_detector_runtime_budget_t budget);

anomaly_detector_processing_mode_t anomaly_detector_runtime_budget_processing_mode(
        anomaly_detector_runtime_budget_t budget);

int anomaly_detector_runtime_budget_trim_keep_latest_frames(
        int64_t source_interval_ms,
        int64_t target_latency_ms,
        int64_t default_source_interval_ms,
        int64_t default_target_latency_ms,
        int min_keep_frames,
        int max_keep_frames);

int anomaly_detector_runtime_budget_render_queue_hard_cap(
        int keep_latest_frames,
        int fallback_keep_latest_frames,
        int min_keep_frames,
        int min_extra_frames,
        int min_hard_cap_frames,
        int max_hard_cap_frames);

bool anomaly_detector_runtime_budget_should_trim_render_queue(
        bool local_file_source,
        int64_t buffered_span_ms,
        int64_t target_latency_ms);

bool anomaly_detector_runtime_budget_should_wait_for_local_ad_buffer(
        bool local_file_source,
        bool ad_enabled,
        bool ad_thread_started,
        bool ad_sync_ready,
        bool render_thread_stop,
        bool local_playback_paused,
        int render_queue_depth,
        int64_t buffered_span_ms,
        int64_t target_latency_ms);

bool anomaly_detector_runtime_budget_should_wait_for_local_ad_processing(
        bool local_file_source,
        bool processing_enabled,
        bool render_thread_stop,
        bool target_color_selected,
        anomaly_detector_runtime_budget_t budget);

bool anomaly_detector_runtime_budget_should_start_ad_worker(
        bool render_session,
        bool ad_sync_ready,
        bool processing_enabled,
        bool ad_thread_started,
        bool ad_thread_starting);

int64_t anomaly_detector_runtime_budget_local_ad_annotation_ordinal(
        bool local_file_source,
        int64_t analyzed_frame_count,
        int64_t local_ad_cadence_ordinal);

int64_t anomaly_detector_runtime_budget_local_ad_render_latency_target_ms(
        bool local_file_source,
        bool ad_enabled,
        bool target_color_selected,
        int64_t target_latency_ms,
        int64_t target_color_cap_ms);

int64_t anomaly_detector_runtime_budget_local_ad_buffer_latency_target_ms(
        bool local_file_source,
        bool ad_enabled,
        bool target_color_selected,
        int64_t target_latency_ms,
        int64_t target_color_buffer_cap_ms);

int anomaly_detector_runtime_budget_queue_tail_index(
        int head,
        int depth,
        int capacity);

int anomaly_detector_runtime_budget_queue_offset_index(
        int head,
        int offset,
        int capacity);

anomaly_detector_runtime_budget_queue_pop_t anomaly_detector_runtime_budget_queue_pop_state(
        int head,
        int depth,
        int capacity);

anomaly_detector_runtime_budget_queue_trim_t anomaly_detector_runtime_budget_queue_trim_state(
        int head,
        int depth,
        int keep_latest,
        int capacity);

int anomaly_detector_runtime_budget_render_queue_storage_capacity(
        int current_capacity,
        int min_capacity,
        int initial_capacity,
        int growth_threshold,
        int growth_step);

int64_t anomaly_detector_runtime_budget_target_latency_ms(
        int64_t stall_estimate_ms,
        int64_t proven_gap_ms,
        int64_t stall_floor_ms,
        int64_t processing_margin_ms,
        int64_t min_target_latency_ms,
        int64_t max_target_latency_ms);

anomaly_detector_runtime_budget_source_interval_estimate_t
anomaly_detector_runtime_budget_update_source_interval_estimate(
        int64_t current_interval_ms,
        int current_confidence,
        int64_t decode_delta_ms,
        int64_t default_interval_ms,
        int ema_pct,
        int confidence_step,
        int64_t min_interval_ms,
        int64_t max_interval_ms);

anomaly_detector_runtime_budget_source_interval_estimate_t
anomaly_detector_runtime_budget_apply_pts_source_interval(
        int64_t current_interval_ms,
        int current_confidence,
        int64_t pts_interval_ms,
        bool force_direct,
        int64_t default_interval_ms,
        int direct_confidence_threshold,
        int confidence_floor,
        int64_t far_delta_threshold_ms,
        int near_blend_pct,
        int far_blend_pct,
        int64_t min_interval_ms,
        int64_t max_interval_ms);

int64_t anomaly_detector_runtime_budget_update_stall_estimate_ms(
        int64_t current_stall_ms,
        int64_t gap_ms,
        int64_t stall_floor_ms,
        int rise_ema_pct,
        int decay_ema_pct,
        int64_t max_stall_ms);

int64_t anomaly_detector_runtime_budget_update_proven_gap_ms(
        int64_t current_proven_gap_ms,
        int64_t gap_ms,
        int64_t stall_floor_ms,
        int blend_ema_pct,
        int64_t max_gap_ms);

int64_t anomaly_detector_runtime_budget_decay_toward_floor_ms(
        int64_t current_value_ms,
        int64_t floor_ms,
        int decay_ema_pct);

anomaly_detector_runtime_budget_render_interval_t
anomaly_detector_runtime_budget_desired_render_interval_ms(
        int64_t source_interval_ms,
        int64_t previous_interval_ms,
        int64_t buffered_span_ms,
        int64_t target_latency_ms,
        bool stall_active,
        int adjust_base_pct,
        int adjust_max_pct,
        int smoothing_pct);

int64_t anomaly_detector_runtime_budget_current_render_interval_ms(
        int64_t smoothed_interval_ms,
        int64_t source_interval_ms,
        int default_fps,
        int64_t min_interval_ms,
        int64_t max_interval_ms);

int64_t anomaly_detector_runtime_budget_interval_from_fps(
        double fps,
        int64_t min_interval_ms,
        int64_t max_interval_ms);

int64_t anomaly_detector_runtime_budget_local_playback_target_interval_ms(
        int64_t nominal_interval_ms,
        int64_t pts_interval_ms,
        int default_fps,
        int64_t min_interval_ms,
        int64_t max_interval_ms,
        int64_t max_reasonable_interval_ms);

int64_t anomaly_detector_runtime_budget_local_playback_pace_delay_ms(
        int64_t nominal_interval_ms,
        int64_t previous_pts_us,
        int64_t pts_us,
        int64_t previous_admit_at_ms,
        int64_t now_ms,
        int default_fps,
        int64_t min_interval_ms,
        int64_t max_interval_ms,
        int64_t max_reasonable_interval_ms);

anomaly_detector_runtime_budget_local_playback_pts_t
anomaly_detector_runtime_budget_normalize_local_playback_pts_us(
        int64_t pts_us,
        int64_t last_pts_us,
        int64_t nominal_interval_ms,
        int64_t source_interval_ms,
        int default_fps);

anomaly_detector_runtime_budget_local_playback_timing_indices_t
anomaly_detector_runtime_budget_local_playback_timing_indices(
        int timing_next,
        int timing_count,
        int capacity);

bool anomaly_detector_runtime_budget_local_playback_timing_span_is_valid(
        int64_t first_pts_us,
        int64_t last_pts_us,
        int64_t first_render_at_ms,
        int64_t last_render_at_ms);

anomaly_detector_runtime_budget_local_playback_history_slot_t
anomaly_detector_runtime_budget_local_playback_history_slot(
        int history_next,
        int history_count,
        int requested_offset,
        int capacity);

anomaly_detector_runtime_budget_local_playback_step_t
anomaly_detector_runtime_budget_local_playback_step_forward(
        int requested_frame_count,
        int current_history_offset,
        bool history_replay_active,
        int64_t current_step_budget,
        int64_t max_step_budget);

anomaly_detector_runtime_budget_local_playback_step_t
anomaly_detector_runtime_budget_local_playback_step_back(
        int current_history_offset,
        int history_count);

anomaly_detector_runtime_budget_local_playback_append_t
anomaly_detector_runtime_budget_local_playback_append(
        int current_next,
        int current_count,
        int capacity);

anomaly_detector_runtime_budget_local_playback_advance_t
anomaly_detector_runtime_budget_local_playback_advance(
        bool paused,
        int64_t current_step_budget);

anomaly_detector_runtime_budget_local_playback_pause_t
anomaly_detector_runtime_budget_local_playback_pause(
        bool paused);

anomaly_detector_runtime_budget_startup_observation_t
anomaly_detector_runtime_budget_startup_observation(
        bool active,
        int64_t now_ms,
        int64_t started_at_ms,
        int64_t observe_ms);

anomaly_detector_runtime_budget_render_lag_t
anomaly_detector_runtime_budget_render_lag(
        int64_t now_ms,
        int64_t scheduled_due_ms,
        int64_t interval_ms,
        int64_t base_interval_ms,
        int64_t last_lag_log_at_ms,
        int64_t lag_log_interval_ms);

anomaly_detector_runtime_budget_local_ad_startup_preroll_t
anomaly_detector_runtime_budget_local_ad_startup_preroll(
        bool local_file_source,
        bool preroll_complete,
        bool ad_enabled,
        bool ad_thread_started,
        bool ad_sync_ready,
        bool render_thread_stop,
        int render_queue_depth,
        int64_t now_ms,
        int64_t started_at_ms,
        int target_render_queue_depth,
        int64_t max_wait_ms);

anomaly_detector_runtime_budget_local_ad_cadence_t
anomaly_detector_runtime_budget_local_ad_cadence(
        bool local_file_source,
        bool processing_enabled,
        bool target_color_selected,
        int64_t decoded_frame_ordinal,
        int full_scan_stride_frames,
        int target_eval_interval_frames);

bool anomaly_detector_runtime_budget_local_ad_should_skip_target_eval(
        bool target_eval_frame,
        bool full_scan_due,
        int target_revisit_count,
        bool target_color_selected);

anomaly_detector_runtime_budget_local_ad_overlay_action_t
anomaly_detector_runtime_budget_local_ad_overlay_action(
        bool overlay_present,
        bool attached_to_pending_render);

bool anomaly_detector_runtime_budget_should_draw_held_local_overlay(
        bool local_file_source,
        bool frame_overlay_present,
        bool held_overlay_present);

anomaly_detector_runtime_budget_local_ad_route_t
anomaly_detector_runtime_budget_local_ad_route(
        bool ad_enabled,
        bool ad_thread_started,
        bool ad_sync_ready);

int64_t anomaly_detector_runtime_budget_advance_render_due_ms(
        int64_t scheduled_due_ms,
        int64_t interval_ms,
        int64_t now_ms);

int64_t anomaly_detector_runtime_budget_pts_interval_from_span_ms(
        int64_t first_pts_us,
        int64_t last_pts_us,
        int valid_count,
        int64_t min_interval_ms,
        int64_t max_interval_ms);

int64_t anomaly_detector_runtime_budget_buffered_span_ms(
        int64_t first_pts_us,
        int64_t last_pts_us);

bool anomaly_detector_runtime_budget_decode_delta_is_gap(
        int64_t delta_ms,
        int64_t source_interval_ms,
        int64_t default_source_interval_ms,
        int64_t gap_floor_ms);

bool anomaly_detector_runtime_budget_decode_delta_is_plausible_cadence(
        int64_t delta_ms,
        int64_t source_interval_ms,
        int64_t default_source_interval_ms,
        int64_t min_sample_ms,
        int64_t max_sample_ms,
        int min_source_pct,
        int max_source_pct);

bool anomaly_detector_runtime_budget_decode_stall_active(
        int64_t now_ms,
        int64_t last_decode_at_ms,
        int64_t source_interval_ms,
        int64_t gap_floor_ms,
        int interval_multiplier);

const char *anomaly_detector_processing_mode_name(
        anomaly_detector_processing_mode_t mode);

/** Faster valid PTS cadence must recover before a frame-count cap trims the queue. */
bool anomaly_detector_runtime_budget_should_relock_live_pts(
        int queue_depth, int64_t buffered_span_ms, int64_t target_latency_ms,
        int64_t since_last_relock_ms, int64_t current_interval_ms, int64_t pts_interval_ms);

#ifdef __cplusplus
}
#endif
