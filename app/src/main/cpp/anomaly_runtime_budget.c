#include "anomaly_runtime_budget.h"

#include <math.h>

static float anomaly_detector_at_least(float value, float minimum) {
    return value >= minimum ? value : minimum;
}

static int64_t anomaly_detector_budget_clamp_i64(
        int64_t value,
        int64_t min_value,
        int64_t max_value) {
    if (value < min_value) {
        return min_value;
    }
    if (value > max_value) {
        return max_value;
    }
    return value;
}

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_make_default(
        float frame_rate_fps) {
    (void)frame_rate_fps;
    anomaly_detector_runtime_budget_t budget = {
        .render_backlog_seconds = 0.0f,
        .startup_elapsed_seconds = 0.0f,
        .startup_skip_seconds = 0.25f,
        .cursory_backlog_seconds = 0.25f,
        .thorough_backlog_seconds = 0.5f,
        .max_backlog_seconds = 0.5f,
        .adapter_pressure = false,
    };
    return budget;
}

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_normalize(
        anomaly_detector_runtime_budget_t budget) {
    if (budget.render_backlog_seconds < 0.0f) {
        budget.render_backlog_seconds = 0.0f;
    }
    if (budget.startup_elapsed_seconds < 0.0f) {
        budget.startup_elapsed_seconds = 0.0f;
    }

    budget.startup_skip_seconds =
        anomaly_detector_at_least(budget.startup_skip_seconds, 0.25f);
    budget.cursory_backlog_seconds =
        anomaly_detector_at_least(budget.cursory_backlog_seconds, 0.25f);
    budget.thorough_backlog_seconds =
        anomaly_detector_at_least(budget.thorough_backlog_seconds, 0.5f);
    if (budget.thorough_backlog_seconds < budget.cursory_backlog_seconds) {
        budget.thorough_backlog_seconds = budget.cursory_backlog_seconds;
    }

    budget.max_backlog_seconds =
        anomaly_detector_at_least(budget.max_backlog_seconds, 0.5f);
    if (budget.max_backlog_seconds < budget.thorough_backlog_seconds) {
        budget.max_backlog_seconds = budget.thorough_backlog_seconds;
    }
    return budget;
}

anomaly_detector_processing_mode_t anomaly_detector_runtime_budget_processing_mode(
        anomaly_detector_runtime_budget_t budget) {
    budget = anomaly_detector_runtime_budget_normalize(budget);
    if (budget.adapter_pressure) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
    }
    if (budget.startup_elapsed_seconds < budget.startup_skip_seconds) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
    }
    if (budget.render_backlog_seconds <= budget.cursory_backlog_seconds) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
    }
    if (budget.render_backlog_seconds >= budget.thorough_backlog_seconds) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH;
    }
    return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
}

int anomaly_detector_runtime_budget_trim_keep_latest_frames(
        int64_t source_interval_ms,
        int64_t target_latency_ms,
        int64_t default_source_interval_ms,
        int64_t default_target_latency_ms,
        int min_keep_frames,
        int max_keep_frames) {
    if (source_interval_ms <= 0) {
        source_interval_ms = default_source_interval_ms;
    }
    if (source_interval_ms <= 0) {
        source_interval_ms = 1;
    }
    if (target_latency_ms <= 0) {
        target_latency_ms = default_target_latency_ms;
    }
    if (target_latency_ms <= 0) {
        target_latency_ms = 0;
    }
    if (min_keep_frames < 0) {
        min_keep_frames = 0;
    }
    if (max_keep_frames < min_keep_frames) {
        max_keep_frames = min_keep_frames;
    }

    int keep_latest = (int) ((target_latency_ms + source_interval_ms - 1) /
                             source_interval_ms);
    if (keep_latest < min_keep_frames) {
        keep_latest = min_keep_frames;
    }
    if (keep_latest > max_keep_frames) {
        keep_latest = max_keep_frames;
    }
    return keep_latest;
}

int anomaly_detector_runtime_budget_render_queue_hard_cap(
        int keep_latest_frames,
        int fallback_keep_latest_frames,
        int min_keep_frames,
        int min_extra_frames,
        int min_hard_cap_frames,
        int max_hard_cap_frames) {
    if (min_keep_frames < 0) {
        min_keep_frames = 0;
    }
    if (keep_latest_frames < min_keep_frames) {
        keep_latest_frames = fallback_keep_latest_frames;
    }
    if (keep_latest_frames < min_keep_frames) {
        keep_latest_frames = min_keep_frames;
    }
    if (min_extra_frames < 0) {
        min_extra_frames = 0;
    }
    if (max_hard_cap_frames < min_hard_cap_frames) {
        max_hard_cap_frames = min_hard_cap_frames;
    }

    int hard_cap = keep_latest_frames * 2;
    int extra_cap = keep_latest_frames + min_extra_frames;
    if (hard_cap < extra_cap) {
        hard_cap = extra_cap;
    }
    if (hard_cap < min_hard_cap_frames) {
        hard_cap = min_hard_cap_frames;
    }
    if (hard_cap > max_hard_cap_frames) {
        hard_cap = max_hard_cap_frames;
    }
    return hard_cap;
}

bool anomaly_detector_runtime_budget_should_trim_render_queue(
        bool local_file_source,
        int64_t buffered_span_ms,
        int64_t target_latency_ms) {
    if (buffered_span_ms <= 0 || target_latency_ms <= 0) {
        return false;
    }
    int64_t threshold_ms = target_latency_ms;
    if (!local_file_source) {
        threshold_ms = target_latency_ms > INT64_MAX / 2
                ? INT64_MAX
                : target_latency_ms * 2;
    }
    return buffered_span_ms >= threshold_ms;
}

bool anomaly_detector_runtime_budget_should_wait_for_local_ad_buffer(
        bool local_file_source,
        bool ad_enabled,
        bool ad_thread_started,
        bool ad_sync_ready,
        bool render_thread_stop,
        bool local_playback_paused,
        int render_queue_depth,
        int64_t buffered_span_ms,
        int64_t target_latency_ms) {
    if (!local_file_source ||
        !ad_enabled ||
        !ad_thread_started ||
        !ad_sync_ready ||
        render_thread_stop ||
        local_playback_paused ||
        render_queue_depth <= 0 ||
        target_latency_ms <= 0) {
        return false;
    }
    if (buffered_span_ms < 0) {
        buffered_span_ms = 0;
    }
    return buffered_span_ms < target_latency_ms;
}

bool anomaly_detector_runtime_budget_should_wait_for_local_ad_processing(
        bool local_file_source,
        bool processing_enabled,
        bool render_thread_stop,
        bool target_color_selected,
        anomaly_detector_runtime_budget_t budget) {
    if (!local_file_source ||
        !processing_enabled ||
        render_thread_stop ||
        target_color_selected) {
        return false;
    }
    budget = anomaly_detector_runtime_budget_normalize(budget);
    return budget.render_backlog_seconds < budget.cursory_backlog_seconds;
}

bool anomaly_detector_runtime_budget_should_start_ad_worker(
        bool render_session,
        bool ad_sync_ready,
        bool processing_enabled,
        bool ad_thread_started,
        bool ad_thread_starting) {
    return render_session &&
           ad_sync_ready &&
           processing_enabled &&
           !ad_thread_started &&
           !ad_thread_starting;
}

int64_t anomaly_detector_runtime_budget_local_ad_annotation_ordinal(
        bool local_file_source,
        int64_t analyzed_frame_count,
        int64_t local_ad_cadence_ordinal) {
    if (local_file_source && local_ad_cadence_ordinal > 0) {
        return local_ad_cadence_ordinal - 1;
    }
    return analyzed_frame_count < 0 ? 0 : analyzed_frame_count;
}

int64_t anomaly_detector_runtime_budget_local_ad_render_latency_target_ms(
        bool local_file_source,
        bool ad_enabled,
        bool target_color_selected,
        int64_t target_latency_ms,
        int64_t target_color_cap_ms) {
    if (!local_file_source ||
        !ad_enabled ||
        !target_color_selected ||
        target_latency_ms <= 0 ||
        target_color_cap_ms <= 0) {
        return target_latency_ms;
    }
    return target_latency_ms < target_color_cap_ms
           ? target_latency_ms
           : target_color_cap_ms;
}

int64_t anomaly_detector_runtime_budget_local_ad_buffer_latency_target_ms(
        bool local_file_source,
        bool ad_enabled,
        bool target_color_selected,
        int64_t target_latency_ms,
        int64_t target_color_buffer_cap_ms) {
    if (!local_file_source ||
        !ad_enabled ||
        !target_color_selected ||
        target_latency_ms <= 0 ||
        target_color_buffer_cap_ms <= 0) {
        return target_latency_ms;
    }
    return target_latency_ms < target_color_buffer_cap_ms
           ? target_latency_ms
           : target_color_buffer_cap_ms;
}

int anomaly_detector_runtime_budget_queue_tail_index(
        int head,
        int depth,
        int capacity) {
    if (capacity <= 0) {
        return 0;
    }
    int normalized_head = head % capacity;
    if (normalized_head < 0) {
        normalized_head += capacity;
    }
    if (depth < 0) {
        depth = 0;
    }
    return (normalized_head + depth) % capacity;
}

int anomaly_detector_runtime_budget_queue_offset_index(
        int head,
        int offset,
        int capacity) {
    if (capacity <= 0) {
        return 0;
    }
    int normalized_head = head % capacity;
    if (normalized_head < 0) {
        normalized_head += capacity;
    }
    if (offset < 0) {
        offset = 0;
    }
    return (normalized_head + offset) % capacity;
}

anomaly_detector_runtime_budget_queue_pop_t anomaly_detector_runtime_budget_queue_pop_state(
        int head,
        int depth,
        int capacity) {
    anomaly_detector_runtime_budget_queue_pop_t pop = {
        .head = 0,
        .depth = 0,
        .valid = false,
    };
    if (capacity <= 0 || depth <= 0) {
        return pop;
    }
    int normalized_head = head % capacity;
    if (normalized_head < 0) {
        normalized_head += capacity;
    }
    pop.depth = depth - 1;
    if (pop.depth > 0) {
        pop.head = (normalized_head + 1) % capacity;
    }
    pop.valid = true;
    return pop;
}

anomaly_detector_runtime_budget_queue_trim_t anomaly_detector_runtime_budget_queue_trim_state(
        int head,
        int depth,
        int keep_latest,
        int capacity) {
    anomaly_detector_runtime_budget_queue_trim_t trim = {
        .drop_count = 0,
        .head = 0,
        .depth = 0,
        .valid = false,
    };
    if (capacity <= 0 || depth < 0) {
        return trim;
    }
    int normalized_head = head % capacity;
    if (normalized_head < 0) {
        normalized_head += capacity;
    }
    trim.head = normalized_head;
    trim.depth = depth;
    if (keep_latest < 1 || depth <= keep_latest) {
        return trim;
    }
    trim.drop_count = depth - keep_latest;
    trim.head = (normalized_head + trim.drop_count) % capacity;
    trim.depth = keep_latest;
    trim.valid = true;
    return trim;
}

int anomaly_detector_runtime_budget_render_queue_storage_capacity(
        int current_capacity,
        int min_capacity,
        int initial_capacity,
        int growth_threshold,
        int growth_step) {
    if (min_capacity < 1) {
        min_capacity = 1;
    }
    if (current_capacity >= min_capacity) {
        return current_capacity;
    }
    if (initial_capacity < 1) {
        initial_capacity = 1;
    }
    if (growth_threshold < 1) {
        growth_threshold = 1;
    }
    if (growth_step < 1) {
        growth_step = 1;
    }

    int new_capacity = current_capacity;
    if (new_capacity < initial_capacity) {
        new_capacity = initial_capacity;
    }
    while (new_capacity < min_capacity) {
        if (new_capacity < growth_threshold) {
            new_capacity *= 2;
        } else {
            new_capacity += growth_step;
        }
    }
    return new_capacity;
}

int64_t anomaly_detector_runtime_budget_target_latency_ms(
        int64_t stall_estimate_ms,
        int64_t proven_gap_ms,
        int64_t stall_floor_ms,
        int64_t processing_margin_ms,
        int64_t min_target_latency_ms,
        int64_t max_target_latency_ms) {
    if (stall_floor_ms < 0) {
        stall_floor_ms = 0;
    }
    if (stall_estimate_ms <= 0) {
        stall_estimate_ms = stall_floor_ms;
    }
    if (stall_estimate_ms < stall_floor_ms) {
        stall_estimate_ms = stall_floor_ms;
    }
    if (proven_gap_ms > stall_estimate_ms) {
        stall_estimate_ms = proven_gap_ms;
    }
    if (processing_margin_ms < 0) {
        processing_margin_ms = 0;
    }
    if (max_target_latency_ms < min_target_latency_ms) {
        max_target_latency_ms = min_target_latency_ms;
    }

    int64_t target_ms = (stall_estimate_ms * 2) + processing_margin_ms;
    if (target_ms < min_target_latency_ms) {
        target_ms = min_target_latency_ms;
    }
    if (target_ms > max_target_latency_ms) {
        target_ms = max_target_latency_ms;
    }
    return target_ms;
}

anomaly_detector_runtime_budget_source_interval_estimate_t
anomaly_detector_runtime_budget_update_source_interval_estimate(
        int64_t current_interval_ms,
        int current_confidence,
        int64_t decode_delta_ms,
        int64_t default_interval_ms,
        int ema_pct,
        int confidence_step,
        int64_t min_interval_ms,
        int64_t max_interval_ms) {
    anomaly_detector_runtime_budget_source_interval_estimate_t estimate = {
        .interval_ms = current_interval_ms,
        .confidence = current_confidence,
    };
    if (estimate.confidence < 0) {
        estimate.confidence = 0;
    }
    if (default_interval_ms <= 0) {
        default_interval_ms = min_interval_ms > 0 ? min_interval_ms : 1;
    }
    if (current_interval_ms <= 0) {
        current_interval_ms = default_interval_ms;
    }
    if (min_interval_ms < 0) {
        min_interval_ms = 0;
    }
    if (max_interval_ms < min_interval_ms) {
        max_interval_ms = min_interval_ms;
    }
    if (ema_pct < 0) {
        ema_pct = 0;
    }
    if (ema_pct > 100) {
        ema_pct = 100;
    }
    if (confidence_step < 0) {
        confidence_step = 0;
    }

    int64_t new_interval_ms = current_interval_ms;
    if (decode_delta_ms > 0) {
        if (current_confidence <= 0) {
            new_interval_ms = decode_delta_ms;
        } else {
            new_interval_ms =
                    ((current_interval_ms * (100 - ema_pct)) +
                     (decode_delta_ms * ema_pct) + 50) / 100;
        }
    }
    if (new_interval_ms < min_interval_ms) {
        new_interval_ms = min_interval_ms;
    }
    if (new_interval_ms > max_interval_ms) {
        new_interval_ms = max_interval_ms;
    }
    estimate.interval_ms = new_interval_ms;

    if (estimate.confidence < 100) {
        estimate.confidence += confidence_step;
        if (estimate.confidence > 100) {
            estimate.confidence = 100;
        }
    }
    return estimate;
}

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
        int64_t max_interval_ms) {
    anomaly_detector_runtime_budget_source_interval_estimate_t estimate = {
        .interval_ms = current_interval_ms,
        .confidence = current_confidence,
    };
    if (estimate.confidence < 0) {
        estimate.confidence = 0;
    }
    if (default_interval_ms <= 0) {
        default_interval_ms = min_interval_ms > 0 ? min_interval_ms : 1;
    }
    if (current_interval_ms <= 0) {
        current_interval_ms = default_interval_ms;
    }
    if (min_interval_ms < 0) {
        min_interval_ms = 0;
    }
    if (max_interval_ms < min_interval_ms) {
        max_interval_ms = min_interval_ms;
    }
    if (far_delta_threshold_ms < 0) {
        far_delta_threshold_ms = 0;
    }
    if (near_blend_pct < 0) {
        near_blend_pct = 0;
    }
    if (near_blend_pct > 100) {
        near_blend_pct = 100;
    }
    if (far_blend_pct < 0) {
        far_blend_pct = 0;
    }
    if (far_blend_pct > 100) {
        far_blend_pct = 100;
    }

    int64_t new_interval_ms = current_interval_ms;
    if (pts_interval_ms > 0) {
        if (force_direct || current_confidence < direct_confidence_threshold) {
            new_interval_ms = pts_interval_ms;
        } else {
            int64_t delta_ms = pts_interval_ms - current_interval_ms;
            if (delta_ms < 0) {
                delta_ms = -delta_ms;
            }
            int blend_pct = delta_ms >= far_delta_threshold_ms
                    ? far_blend_pct
                    : near_blend_pct;
            new_interval_ms =
                    ((current_interval_ms * (100 - blend_pct)) +
                     (pts_interval_ms * blend_pct) + 50) / 100;
            if (new_interval_ms == current_interval_ms &&
                pts_interval_ms != current_interval_ms) {
                new_interval_ms += pts_interval_ms > current_interval_ms ? 1 : -1;
            }
        }
    }
    if (new_interval_ms < min_interval_ms) {
        new_interval_ms = min_interval_ms;
    }
    if (new_interval_ms > max_interval_ms) {
        new_interval_ms = max_interval_ms;
    }
    estimate.interval_ms = new_interval_ms;
    if (estimate.confidence < confidence_floor) {
        estimate.confidence = confidence_floor;
    }
    if (estimate.confidence > 100) {
        estimate.confidence = 100;
    }
    return estimate;
}

int64_t anomaly_detector_runtime_budget_update_stall_estimate_ms(
        int64_t current_stall_ms,
        int64_t gap_ms,
        int64_t stall_floor_ms,
        int rise_ema_pct,
        int decay_ema_pct,
        int64_t max_stall_ms) {
    if (stall_floor_ms < 0) {
        stall_floor_ms = 0;
    }
    if (max_stall_ms < stall_floor_ms) {
        max_stall_ms = stall_floor_ms;
    }
    if (current_stall_ms <= 0) {
        current_stall_ms = stall_floor_ms;
    }
    if (current_stall_ms < stall_floor_ms) {
        current_stall_ms = stall_floor_ms;
    }
    if (rise_ema_pct < 0) {
        rise_ema_pct = 0;
    }
    if (rise_ema_pct > 100) {
        rise_ema_pct = 100;
    }
    if (decay_ema_pct < 0) {
        decay_ema_pct = 0;
    }
    if (decay_ema_pct > 100) {
        decay_ema_pct = 100;
    }

    int ema_pct = gap_ms >= current_stall_ms ? rise_ema_pct : decay_ema_pct;
    int64_t new_stall_ms =
            ((current_stall_ms * (100 - ema_pct)) +
             (gap_ms * ema_pct) + 50) / 100;
    if (new_stall_ms < stall_floor_ms) {
        new_stall_ms = stall_floor_ms;
    }
    if (new_stall_ms > max_stall_ms) {
        new_stall_ms = max_stall_ms;
    }
    return new_stall_ms;
}

int64_t anomaly_detector_runtime_budget_update_proven_gap_ms(
        int64_t current_proven_gap_ms,
        int64_t gap_ms,
        int64_t stall_floor_ms,
        int blend_ema_pct,
        int64_t max_gap_ms) {
    if (stall_floor_ms < 0) {
        stall_floor_ms = 0;
    }
    if (max_gap_ms < stall_floor_ms) {
        max_gap_ms = stall_floor_ms;
    }
    if (current_proven_gap_ms <= 0) {
        current_proven_gap_ms = stall_floor_ms;
    }
    if (current_proven_gap_ms < stall_floor_ms) {
        current_proven_gap_ms = stall_floor_ms;
    }
    if (blend_ema_pct < 0) {
        blend_ema_pct = 0;
    }
    if (blend_ema_pct > 100) {
        blend_ema_pct = 100;
    }

    int64_t new_gap_ms = current_proven_gap_ms;
    if (gap_ms > current_proven_gap_ms) {
        new_gap_ms = gap_ms;
    } else {
        new_gap_ms =
                ((current_proven_gap_ms * (100 - blend_ema_pct)) +
                 (gap_ms * blend_ema_pct) + 50) / 100;
    }
    if (new_gap_ms < stall_floor_ms) {
        new_gap_ms = stall_floor_ms;
    }
    if (new_gap_ms > max_gap_ms) {
        new_gap_ms = max_gap_ms;
    }
    return new_gap_ms;
}

int64_t anomaly_detector_runtime_budget_decay_toward_floor_ms(
        int64_t current_value_ms,
        int64_t floor_ms,
        int decay_ema_pct) {
    if (floor_ms < 0) {
        floor_ms = 0;
    }
    if (current_value_ms < floor_ms) {
        current_value_ms = floor_ms;
    }
    if (decay_ema_pct < 0) {
        decay_ema_pct = 0;
    }
    if (decay_ema_pct > 100) {
        decay_ema_pct = 100;
    }

    int64_t new_value_ms =
            ((current_value_ms * (100 - decay_ema_pct)) +
             (floor_ms * decay_ema_pct) + 50) / 100;
    if (new_value_ms < floor_ms) {
        new_value_ms = floor_ms;
    }
    return new_value_ms;
}

anomaly_detector_runtime_budget_render_interval_t
anomaly_detector_runtime_budget_desired_render_interval_ms(
        int64_t source_interval_ms,
        int64_t previous_interval_ms,
        int64_t buffered_span_ms,
        int64_t target_latency_ms,
        bool stall_active,
        int adjust_base_pct,
        int adjust_max_pct,
        int smoothing_pct) {
    if (source_interval_ms <= 0) {
        source_interval_ms = 1;
    }
    if (previous_interval_ms <= 0) {
        previous_interval_ms = source_interval_ms;
    }
    if (adjust_base_pct < 0) {
        adjust_base_pct = 0;
    }
    if (adjust_base_pct > 100) {
        adjust_base_pct = 100;
    }
    if (adjust_max_pct < adjust_base_pct) {
        adjust_max_pct = adjust_base_pct;
    }
    if (adjust_max_pct > 100) {
        adjust_max_pct = 100;
    }
    if (smoothing_pct < 0) {
        smoothing_pct = 0;
    }
    if (smoothing_pct > 100) {
        smoothing_pct = 100;
    }

    double error_ratio = 0.0;
    if (target_latency_ms > 0) {
        error_ratio = (double) (buffered_span_ms - target_latency_ms) /
                      (double) target_latency_ms;
    }
    if (error_ratio < -0.5) error_ratio = -0.5;
    if (error_ratio > 4.0) error_ratio = 4.0;

    double adjust_pct = error_ratio * (double) adjust_base_pct;
    double backlog_ratio = target_latency_ms > 0
            ? (double) buffered_span_ms / (double) target_latency_ms
            : 1.0;
    double max_adjust_pct = (double) adjust_base_pct;
    if (backlog_ratio >= 1.5) max_adjust_pct = 20.0;
    if (backlog_ratio >= 2.0) max_adjust_pct = 28.0;
    if (backlog_ratio >= 3.0) max_adjust_pct = 35.0;
    if (backlog_ratio >= 5.0) max_adjust_pct = 40.0;
    if (max_adjust_pct > (double) adjust_max_pct) {
        max_adjust_pct = (double) adjust_max_pct;
    }
    if (adjust_pct < -(double) adjust_base_pct) {
        adjust_pct = -(double) adjust_base_pct;
    }
    if (adjust_pct > max_adjust_pct) {
        adjust_pct = max_adjust_pct;
    }

    int64_t desired_interval_ms =
            (int64_t) llround((double) source_interval_ms *
                              (100.0 - adjust_pct) / 100.0);
    bool preserve_during_stall =
            stall_active &&
            target_latency_ms > 0 &&
            buffered_span_ms <= ((target_latency_ms * 5) / 4);
    if (preserve_during_stall) {
        int64_t preserve_interval_ms = (source_interval_ms * 108 + 99) / 100;
        if (desired_interval_ms < preserve_interval_ms) {
            desired_interval_ms = preserve_interval_ms;
        }
    }

    int64_t min_interval_ms =
            (source_interval_ms * (100 - adjust_max_pct) + 99) / 100;
    int64_t max_interval_ms =
            (source_interval_ms * (100 + adjust_base_pct) + 99) / 100;
    if (max_interval_ms < min_interval_ms) {
        max_interval_ms = min_interval_ms;
    }
    desired_interval_ms = anomaly_detector_budget_clamp_i64(
            desired_interval_ms,
            min_interval_ms,
            max_interval_ms);

    int64_t smoothed_interval_ms =
            ((previous_interval_ms * (100 - smoothing_pct)) +
             (desired_interval_ms * smoothing_pct) + 50) / 100;
    // Integer EMA rounding must not strand the controller a few milliseconds
    // away from its target (e.g. 44 -> 41 at 15 percent rounds back to 44).
    if (smoothing_pct > 0 && smoothed_interval_ms == previous_interval_ms &&
        desired_interval_ms != previous_interval_ms) {
        smoothed_interval_ms += desired_interval_ms > previous_interval_ms ? 1 : -1;
    }
    smoothed_interval_ms = anomaly_detector_budget_clamp_i64(
            smoothed_interval_ms,
            min_interval_ms,
            max_interval_ms);

    anomaly_detector_runtime_budget_render_interval_t interval = {
        .desired_interval_ms = desired_interval_ms,
        .render_interval_ms = smoothed_interval_ms,
    };
    return interval;
}

int64_t anomaly_detector_runtime_budget_current_render_interval_ms(
        int64_t smoothed_interval_ms,
        int64_t source_interval_ms,
        int default_fps,
        int64_t min_interval_ms,
        int64_t max_interval_ms) {
    if (min_interval_ms < 0) {
        min_interval_ms = 0;
    }
    if (max_interval_ms < min_interval_ms) {
        max_interval_ms = min_interval_ms;
    }
    int64_t interval_ms = smoothed_interval_ms;
    if (interval_ms <= 0) {
        interval_ms = source_interval_ms;
    }
    if (interval_ms <= 0) {
        interval_ms = default_fps > 0
                ? (1000 + (default_fps / 2)) / default_fps
                : 1;
    }
    return anomaly_detector_budget_clamp_i64(
            interval_ms,
            min_interval_ms,
            max_interval_ms);
}

int64_t anomaly_detector_runtime_budget_interval_from_fps(
        double fps,
        int64_t min_interval_ms,
        int64_t max_interval_ms) {
    if (!(fps > 1.0)) {
        return 0;
    }
    if (min_interval_ms < 0) {
        min_interval_ms = 0;
    }
    if (max_interval_ms < min_interval_ms) {
        max_interval_ms = min_interval_ms;
    }
    int64_t interval_ms = (int64_t) llround(1000.0 / fps);
    return anomaly_detector_budget_clamp_i64(
            interval_ms,
            min_interval_ms,
            max_interval_ms);
}

int64_t anomaly_detector_runtime_budget_local_playback_target_interval_ms(
        int64_t nominal_interval_ms,
        int64_t pts_interval_ms,
        int default_fps,
        int64_t min_interval_ms,
        int64_t max_interval_ms,
        int64_t max_reasonable_interval_ms) {
    int64_t target_interval_ms = nominal_interval_ms;
    if (pts_interval_ms > 0) {
        // Local recordings can contain timestamped metadata-only H.264 access
        // units. Some MP4 demuxers count those toward avg_frame_rate, making a
        // 30 fps picture stream look like 60 fps. Decoded-frame PTS is the
        // authoritative playback clock; accept any bounded positive interval
        // instead of comparing it to that potentially doubled nominal rate.
        if (max_reasonable_interval_ms <= 0 ||
            pts_interval_ms <= max_reasonable_interval_ms) {
            target_interval_ms = pts_interval_ms;
        }
    }
    if (target_interval_ms <= 0) {
        target_interval_ms =
                anomaly_detector_runtime_budget_current_render_interval_ms(
                        0,
                        0,
                        default_fps,
                        min_interval_ms,
                        max_interval_ms);
    }
    return anomaly_detector_budget_clamp_i64(
            target_interval_ms,
            min_interval_ms,
            max_interval_ms);
}

int64_t anomaly_detector_runtime_budget_local_playback_pace_delay_ms(
        int64_t nominal_interval_ms,
        int64_t previous_pts_us,
        int64_t pts_us,
        int64_t previous_admit_at_ms,
        int64_t now_ms,
        int default_fps,
        int64_t min_interval_ms,
        int64_t max_interval_ms,
        int64_t max_reasonable_interval_ms) {
    if (previous_admit_at_ms <= 0 || now_ms <= 0) {
        return 0;
    }
    int64_t pts_interval_ms = 0;
    if (previous_pts_us > 0 && pts_us > previous_pts_us) {
        pts_interval_ms = (pts_us - previous_pts_us) / 1000;
    }
    int64_t target_interval_ms =
            anomaly_detector_runtime_budget_local_playback_target_interval_ms(
                    nominal_interval_ms,
                    pts_interval_ms,
                    default_fps,
                    min_interval_ms,
                    max_interval_ms,
                    max_reasonable_interval_ms);
    int64_t target_at_ms = previous_admit_at_ms + target_interval_ms;
    if (target_at_ms <= now_ms) {
        return 0;
    }
    return target_at_ms - now_ms;
}

anomaly_detector_runtime_budget_local_playback_pts_t
anomaly_detector_runtime_budget_normalize_local_playback_pts_us(
        int64_t pts_us,
        int64_t last_pts_us,
        int64_t nominal_interval_ms,
        int64_t source_interval_ms,
        int default_fps) {
    int64_t interval_us = nominal_interval_ms > 0
            ? nominal_interval_ms * 1000
            : source_interval_ms * 1000;
    if (interval_us <= 0) {
        interval_us = default_fps > 0
                ? (1000000 + (default_fps / 2)) / default_fps
                : 1;
    }

    anomaly_detector_runtime_budget_local_playback_pts_t normalized = {
        .pts_us = pts_us,
        .repaired = false,
    };
    if (pts_us <= 0) {
        if (last_pts_us > 0) {
            normalized.pts_us = last_pts_us + interval_us;
        }
        return normalized;
    }
    if (last_pts_us > 0 && pts_us <= last_pts_us) {
        normalized.pts_us = last_pts_us + interval_us;
        normalized.repaired = true;
    }
    return normalized;
}

anomaly_detector_runtime_budget_local_playback_timing_indices_t
anomaly_detector_runtime_budget_local_playback_timing_indices(
        int timing_next,
        int timing_count,
        int capacity) {
    anomaly_detector_runtime_budget_local_playback_timing_indices_t indices = {
        .oldest_index = 0,
        .newest_index = 0,
        .valid = false,
    };
    if (capacity <= 0 || timing_count < 2) {
        return indices;
    }
    if (timing_count > capacity) {
        timing_count = capacity;
    }
    int normalized_next = timing_next % capacity;
    if (normalized_next < 0) {
        normalized_next += capacity;
    }
    indices.oldest_index = (normalized_next - timing_count + capacity) % capacity;
    indices.newest_index = (normalized_next - 1 + capacity) % capacity;
    indices.valid = true;
    return indices;
}

bool anomaly_detector_runtime_budget_local_playback_timing_span_is_valid(
        int64_t first_pts_us,
        int64_t last_pts_us,
        int64_t first_render_at_ms,
        int64_t last_render_at_ms) {
    return first_pts_us > 0 &&
           last_pts_us > first_pts_us &&
           first_render_at_ms > 0 &&
           last_render_at_ms > first_render_at_ms;
}

anomaly_detector_runtime_budget_local_playback_history_slot_t
anomaly_detector_runtime_budget_local_playback_history_slot(
        int history_next,
        int history_count,
        int requested_offset,
        int capacity) {
    anomaly_detector_runtime_budget_local_playback_history_slot_t slot = {
        .slot_index = 0,
        .history_offset = 0,
        .valid = false,
    };
    if (capacity <= 0 || history_count <= 0) {
        return slot;
    }
    if (history_count > capacity) {
        history_count = capacity;
    }
    int history_offset = requested_offset;
    if (history_offset < 0) {
        history_offset = 0;
    }
    if (history_offset >= history_count) {
        history_offset = history_count - 1;
    }

    int normalized_next = history_next % capacity;
    if (normalized_next < 0) {
        normalized_next += capacity;
    }
    int newest_index = (normalized_next - 1 + capacity) % capacity;
    slot.slot_index = (newest_index - history_offset + capacity) % capacity;
    slot.history_offset = history_offset;
    slot.valid = true;
    return slot;
}

anomaly_detector_runtime_budget_local_playback_step_t
anomaly_detector_runtime_budget_local_playback_step_forward(
        int requested_frame_count,
        int current_history_offset,
        bool history_replay_active,
        int64_t current_step_budget,
        int64_t max_step_budget) {
    anomaly_detector_runtime_budget_local_playback_step_t step = {
        .history_offset = current_history_offset > 0 ? current_history_offset : 0,
        .step_budget = current_step_budget > 0 ? current_step_budget : 0,
        .replay_active = history_replay_active,
        .render_from_history = false,
        .reset_tracking = false,
    };
    int64_t step_count = requested_frame_count > 0
            ? (int64_t) requested_frame_count
            : 1;
    if (step_count == 1 && step.history_offset > 0) {
        step.history_offset -= 1;
        step.replay_active = true;
        step.render_from_history = true;
        return step;
    }

    step.reset_tracking = history_replay_active;
    step.history_offset = 0;
    step.replay_active = false;
    if (max_step_budget < 0) {
        max_step_budget = 0;
    }
    if (step.step_budget > max_step_budget - step_count) {
        step.step_budget = max_step_budget;
    } else {
        step.step_budget += step_count;
    }
    return step;
}

anomaly_detector_runtime_budget_local_playback_step_t
anomaly_detector_runtime_budget_local_playback_step_back(
        int current_history_offset,
        int history_count) {
    anomaly_detector_runtime_budget_local_playback_step_t step = {
        .history_offset = current_history_offset > 0 ? current_history_offset : 0,
        .step_budget = 0,
        .replay_active = false,
        .render_from_history = false,
        .reset_tracking = false,
    };
    if (history_count <= 0) {
        step.history_offset = 0;
        return step;
    }
    int max_offset = history_count - 1;
    if (step.history_offset < max_offset) {
        step.history_offset += 1;
    } else {
        step.history_offset = max_offset;
    }
    step.replay_active = true;
    step.render_from_history = true;
    return step;
}

anomaly_detector_runtime_budget_local_playback_append_t
anomaly_detector_runtime_budget_local_playback_append(
        int current_next,
        int current_count,
        int capacity) {
    anomaly_detector_runtime_budget_local_playback_append_t append = {
        .slot_index = 0,
        .next_index = 0,
        .count = 0,
        .valid = false,
    };
    if (capacity <= 0) {
        return append;
    }
    int slot_index = current_next % capacity;
    if (slot_index < 0) {
        slot_index += capacity;
    }
    int count = current_count;
    if (count < 0) {
        count = 0;
    }
    if (count < capacity) {
        count += 1;
    } else {
        count = capacity;
    }
    append.slot_index = slot_index;
    append.next_index = (slot_index + 1) % capacity;
    append.count = count;
    append.valid = true;
    return append;
}

anomaly_detector_runtime_budget_local_playback_advance_t
anomaly_detector_runtime_budget_local_playback_advance(
        bool paused,
        int64_t current_step_budget) {
    anomaly_detector_runtime_budget_local_playback_advance_t advance = {
        .step_budget = current_step_budget > 0 ? current_step_budget : 0,
        .advance = false,
        .consume_step = false,
    };
    if (!paused) {
        advance.advance = true;
        return advance;
    }
    if (advance.step_budget > 0) {
        advance.step_budget -= 1;
        advance.advance = true;
        advance.consume_step = true;
    }
    return advance;
}

anomaly_detector_runtime_budget_local_playback_pause_t
anomaly_detector_runtime_budget_local_playback_pause(
        bool paused) {
    anomaly_detector_runtime_budget_local_playback_pause_t state = {
        .clear_render_queue = false,
        .clear_ad_queue = false,
        .reset_render_timing = false,
        .clear_step_budget = false,
        .clear_history_replay = false,
    };
    if (paused) {
        state.clear_render_queue = true;
        state.clear_ad_queue = true;
        return state;
    }
    state.reset_render_timing = true;
    state.clear_step_budget = true;
    state.clear_history_replay = true;
    return state;
}

anomaly_detector_runtime_budget_startup_observation_t
anomaly_detector_runtime_budget_startup_observation(
        bool active,
        int64_t now_ms,
        int64_t started_at_ms,
        int64_t observe_ms) {
    anomaly_detector_runtime_budget_startup_observation_t observation = {
        .elapsed_ms = 0,
        .observing = false,
        .finalize = false,
    };
    if (!active) {
        return observation;
    }
    observation.elapsed_ms = now_ms - started_at_ms;
    if (observation.elapsed_ms < 0) {
        observation.elapsed_ms = 0;
    }
    if (observe_ms > 0 && observation.elapsed_ms < observe_ms) {
        observation.observing = true;
    } else {
        observation.finalize = true;
    }
    return observation;
}

anomaly_detector_runtime_budget_render_lag_t
anomaly_detector_runtime_budget_render_lag(
        int64_t now_ms,
        int64_t scheduled_due_ms,
        int64_t interval_ms,
        int64_t base_interval_ms,
        int64_t last_lag_log_at_ms,
        int64_t lag_log_interval_ms) {
    anomaly_detector_runtime_budget_render_lag_t lag = {
        .lag_ms = now_ms - scheduled_due_ms,
        .lag_budget_ms = 0,
        .severe_lag = false,
        .periodic_log = false,
        .update_log_timestamp = false,
    };
    if (interval_ms < 0) {
        interval_ms = 0;
    }
    if (base_interval_ms < 0) {
        base_interval_ms = 0;
    }
    if (lag_log_interval_ms < 0) {
        lag_log_interval_ms = 0;
    }
    lag.lag_budget_ms = interval_ms - lag.lag_ms;
    lag.periodic_log = (now_ms - last_lag_log_at_ms) >= lag_log_interval_ms;
    lag.severe_lag = lag.lag_ms >= (base_interval_ms * 2);
    lag.update_log_timestamp = lag.severe_lag || lag.periodic_log;
    return lag;
}

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
        int64_t max_wait_ms) {
    anomaly_detector_runtime_budget_local_ad_startup_preroll_t preroll = {
        .elapsed_ms = now_ms - started_at_ms,
        .wait = false,
        .complete = preroll_complete,
    };
    if (preroll.elapsed_ms < 0) {
        preroll.elapsed_ms = 0;
    }
    if (preroll.complete) {
        return preroll;
    }
    if (!local_file_source) {
        preroll.complete = true;
        return preroll;
    }
    if (!ad_enabled || !ad_thread_started || !ad_sync_ready || render_thread_stop) {
        return preroll;
    }
    if (target_render_queue_depth <= 0 && max_wait_ms <= 0) {
        preroll.complete = true;
        return preroll;
    }
    if (target_render_queue_depth > 0 && render_queue_depth >= target_render_queue_depth) {
        preroll.complete = true;
        return preroll;
    }
    if (max_wait_ms > 0 && preroll.elapsed_ms >= max_wait_ms) {
        preroll.complete = true;
        return preroll;
    }
    preroll.wait = true;
    return preroll;
}

anomaly_detector_runtime_budget_local_ad_cadence_t
anomaly_detector_runtime_budget_local_ad_cadence(
        bool local_file_source,
        bool processing_enabled,
        bool target_color_selected,
        int64_t decoded_frame_ordinal,
        int full_scan_stride_frames,
        int target_eval_interval_frames) {
    const int suppress_implicit_full_refresh_stride = 1000000;
    anomaly_detector_runtime_budget_local_ad_cadence_t cadence = {
        .analyze = true,
        .prediction_only = false,
        .full_scan_due = false,
        .frame_stride_override = 0,
    };
    if (!local_file_source || !processing_enabled) {
        return cadence;
    }
    // The uniqueness detector already owns its adaptive scan cadence. Applying a
    // second sparse gate here can leave short-lived targets with only one discovery
    // observation, which can never satisfy min_hits. Explicit target-color scans
    // retain the lightweight outer cadence below.
    if (!target_color_selected) {
        return cadence;
    }
    if (decoded_frame_ordinal <= 0) {
        decoded_frame_ordinal = 1;
    }
    if (target_eval_interval_frames < 1) {
        target_eval_interval_frames = 1;
    }
    if (full_scan_stride_frames < target_eval_interval_frames) {
        full_scan_stride_frames = target_eval_interval_frames;
    }
    if (decoded_frame_ordinal == 1) {
        cadence.full_scan_due = true;
        cadence.frame_stride_override = 1;
        return cadence;
    }
    cadence.full_scan_due =
            (decoded_frame_ordinal % full_scan_stride_frames) == 0;
    cadence.frame_stride_override = cadence.full_scan_due
            ? 1
            : suppress_implicit_full_refresh_stride;
    return cadence;
}

bool anomaly_detector_runtime_budget_local_ad_should_skip_target_eval(
        bool target_eval_frame,
        bool full_scan_due,
        int target_revisit_count,
        bool target_color_selected) {
    return target_eval_frame &&
           !full_scan_due &&
           target_revisit_count <= 0 &&
           !target_color_selected;
}

anomaly_detector_runtime_budget_local_ad_overlay_action_t
anomaly_detector_runtime_budget_local_ad_overlay_action(
        bool overlay_present,
        bool attached_to_pending_render) {
    if (!overlay_present) {
        return ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_OVERLAY_NONE;
    }
    if (attached_to_pending_render) {
        return ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_OVERLAY_ATTACHED;
    }
    return ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_OVERLAY_NONE;
}

bool anomaly_detector_runtime_budget_should_draw_held_local_overlay(
        bool local_file_source,
        bool frame_overlay_present,
        bool held_overlay_present) {
    return local_file_source && !frame_overlay_present && held_overlay_present;
}

anomaly_detector_runtime_budget_local_ad_route_t
anomaly_detector_runtime_budget_local_ad_route(
        bool ad_enabled,
        bool ad_thread_started,
        bool ad_sync_ready) {
    (void) ad_enabled;
    (void) ad_thread_started;
    (void) ad_sync_ready;
    return ANOMALY_DETECTOR_RUNTIME_BUDGET_LOCAL_AD_ROUTE_RENDER_FIRST;
}

int64_t anomaly_detector_runtime_budget_advance_render_due_ms(
        int64_t scheduled_due_ms,
        int64_t interval_ms,
        int64_t now_ms) {
    if (interval_ms <= 0) {
        return now_ms;
    }
    if (scheduled_due_ms <= 0) {
        scheduled_due_ms = now_ms;
    }
    int64_t next_due_ms = scheduled_due_ms + interval_ms;
    if (next_due_ms <= now_ms) {
        int64_t skipped_ticks = ((now_ms - next_due_ms) / interval_ms) + 1;
        next_due_ms += skipped_ticks * interval_ms;
    }
    return next_due_ms;
}

int64_t anomaly_detector_runtime_budget_pts_interval_from_span_ms(
        int64_t first_pts_us,
        int64_t last_pts_us,
        int valid_count,
        int64_t min_interval_ms,
        int64_t max_interval_ms) {
    if (valid_count < 2 || last_pts_us <= first_pts_us) {
        return 0;
    }
    if (min_interval_ms < 0) {
        min_interval_ms = 0;
    }
    if (max_interval_ms < min_interval_ms) {
        max_interval_ms = min_interval_ms;
    }
    int64_t frame_gaps = (int64_t) valid_count - 1;
    int64_t span_us = last_pts_us - first_pts_us;
    int64_t interval_ms =
            (span_us + (frame_gaps * 500LL)) /
            (frame_gaps * 1000LL);
    return anomaly_detector_budget_clamp_i64(
            interval_ms,
            min_interval_ms,
            max_interval_ms);
}

int64_t anomaly_detector_runtime_budget_buffered_span_ms(
        int64_t first_pts_us,
        int64_t last_pts_us) {
    if (first_pts_us <= 0 || last_pts_us <= first_pts_us) {
        return 0;
    }
    return (last_pts_us - first_pts_us) / 1000;
}

bool anomaly_detector_runtime_budget_decode_delta_is_gap(
        int64_t delta_ms,
        int64_t source_interval_ms,
        int64_t default_source_interval_ms,
        int64_t gap_floor_ms) {
    if (gap_floor_ms < 0) {
        gap_floor_ms = 0;
    }
    if (delta_ms < gap_floor_ms) {
        return false;
    }
    if (default_source_interval_ms <= 0) {
        default_source_interval_ms = 1;
    }
    int64_t reference_ms = source_interval_ms > 0
            ? source_interval_ms
            : default_source_interval_ms;
    int64_t threshold_ms = (reference_ms * 7 + 3) / 4;
    if (threshold_ms < gap_floor_ms) {
        threshold_ms = gap_floor_ms;
    }
    return delta_ms >= threshold_ms;
}

bool anomaly_detector_runtime_budget_decode_delta_is_plausible_cadence(
        int64_t delta_ms,
        int64_t source_interval_ms,
        int64_t default_source_interval_ms,
        int64_t min_sample_ms,
        int64_t max_sample_ms,
        int min_source_pct,
        int max_source_pct) {
    if (min_sample_ms < 0) {
        min_sample_ms = 0;
    }
    if (max_sample_ms < min_sample_ms) {
        max_sample_ms = min_sample_ms;
    }
    if (delta_ms < min_sample_ms || delta_ms > max_sample_ms) {
        return false;
    }
    if (default_source_interval_ms <= 0) {
        default_source_interval_ms = 1;
    }
    if (min_source_pct < 0) {
        min_source_pct = 0;
    }
    if (max_source_pct < min_source_pct) {
        max_source_pct = min_source_pct;
    }
    int64_t reference_ms = source_interval_ms > 0
            ? source_interval_ms
            : default_source_interval_ms;
    int64_t min_ms = (reference_ms * min_source_pct + 99) / 100;
    int64_t max_ms = (reference_ms * max_source_pct + 99) / 100;
    if (min_ms < min_sample_ms) {
        min_ms = min_sample_ms;
    }
    if (max_ms > max_sample_ms) {
        max_ms = max_sample_ms;
    }
    return delta_ms >= min_ms && delta_ms <= max_ms;
}

bool anomaly_detector_runtime_budget_decode_stall_active(
        int64_t now_ms,
        int64_t last_decode_at_ms,
        int64_t source_interval_ms,
        int64_t gap_floor_ms,
        int interval_multiplier) {
    if (last_decode_at_ms <= 0 || now_ms < last_decode_at_ms) {
        return false;
    }
    if (source_interval_ms <= 0) {
        source_interval_ms = 1;
    }
    if (gap_floor_ms < 0) {
        gap_floor_ms = 0;
    }
    if (interval_multiplier < 0) {
        interval_multiplier = 0;
    }
    int64_t stall_threshold_ms = source_interval_ms * interval_multiplier;
    if (stall_threshold_ms < gap_floor_ms) {
        stall_threshold_ms = gap_floor_ms;
    }
    return (now_ms - last_decode_at_ms) >= stall_threshold_ms;
}

const char *anomaly_detector_processing_mode_name(
        anomaly_detector_processing_mode_t mode) {
    switch (mode) {
        case ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY:
            return "cursory";
        case ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH:
            return "thorough";
        default:
            return "unknown";
    }
}
