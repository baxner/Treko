#include "treko_aec3_core.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace treko::audio {

TrekoAecProcessor::TrekoAecProcessor() {
    std::lock_guard<std::mutex> lock(aec_mutex_);
    InitEchoControlLocked();
}

void TrekoAecProcessor::InitEchoControlLocked() {
    webrtc::EchoCanceller3Config config;
    config.delay.default_delay = 20; // ~80ms initial estimate (20 * 4ms blocks)
    config.filter.initial_state_seconds = 0.25f;
    config.filter.conservative_initial_phase = false;

    webrtc::EchoCanceller3Factory factory(config);
    echo_control_ = factory.Create(env_, kSampleRateHz, kChannels, kChannels);
    high_pass_ = std::make_unique<webrtc::HighPassFilter>(kSampleRateHz, kChannels);
    render_buffer_ = std::make_unique<webrtc::AudioBuffer>(
        kSampleRateHz, kChannels,
        kSampleRateHz, kChannels,
        kSampleRateHz, kChannels
    );
    capture_buffer_ = std::make_unique<webrtc::AudioBuffer>(
        kSampleRateHz, kChannels,
        kSampleRateHz, kChannels,
        kSampleRateHz, kChannels
    );
}

void TrekoAecProcessor::Reset() {
    {
        std::lock_guard<std::mutex> rlock(render_mutex_);
        render_fifo_.clear();
        latest_playout_delay_ms_ = 80;
    }
    {
        std::lock_guard<std::mutex> alock(aec_mutex_);
        InitEchoControlLocked();
    }
}

void TrekoAecProcessor::FeedRender16k(const int16_t* samples, size_t count, int playout_delay_ms) {
    if (samples == nullptr || count == 0) return;

    std::lock_guard<std::mutex> lock(render_mutex_);
    if (playout_delay_ms > 0) {
        latest_playout_delay_ms_ = std::clamp(playout_delay_ms, 10, 350);
    }

    if (count >= kMaxRenderFifoSamples) {
        render_fifo_.clear();
        const size_t start = count - kMaxRenderFifoSamples;
        render_fifo_.insert(render_fifo_.end(), samples + start, samples + count);
        return;
    }

    while (render_fifo_.size() + count > kMaxRenderFifoSamples) {
        size_t drop = (render_fifo_.size() + count) - kMaxRenderFifoSamples;
        drop = std::min(drop, render_fifo_.size());
        render_fifo_.erase(render_fifo_.begin(), render_fifo_.begin() + static_cast<ptrdiff_t>(drop));
    }
    render_fifo_.insert(render_fifo_.end(), samples, samples + count);
}

AecProcessMetrics TrekoAecProcessor::ProcessCapture16k(
    const int16_t* capture_in,
    size_t count,
    int playout_delay_ms,
    int16_t* clean_out
) {
    AecProcessMetrics result;
    if (capture_in == nullptr || clean_out == nullptr || count == 0) {
        return result;
    }

    int effective_delay_ms = playout_delay_ms;
    const size_t num_capture_subframes = count / kSubFrameSamples;
    const size_t wanted_render_samples = num_capture_subframes * kSubFrameSamples;
    drained_render_.clear();
    {
        std::lock_guard<std::mutex> rlock(render_mutex_);
        if (effective_delay_ms <= 0) {
            effective_delay_ms = latest_playout_delay_ms_;
        }
        // Drain at most 1 render 10ms sub-frame per capture 10ms sub-frame so
        // AudioTrack write bursts never trigger WebRTC's RenderOverrun /
        // DetectExcessRenderBlocks (>8 blocks) and reset the adaptive filter.
        const size_t available_full_frames = render_fifo_.size() / kSubFrameSamples;
        const size_t drain_samples = std::min(
            wanted_render_samples,
            available_full_frames * kSubFrameSamples
        );
        if (drain_samples > 0) {
            drained_render_.assign(
                render_fifo_.begin(),
                render_fifo_.begin() + static_cast<ptrdiff_t>(drain_samples)
            );
            render_fifo_.erase(
                render_fifo_.begin(),
                render_fifo_.begin() + static_cast<ptrdiff_t>(drain_samples)
            );
        }
    }

    effective_delay_ms = std::clamp(effective_delay_ms, 0, 350);

    std::lock_guard<std::mutex> alock(aec_mutex_);
    if (!echo_control_ || !render_buffer_ || !capture_buffer_ || !high_pass_) {
        std::memcpy(clean_out, capture_in, count * sizeof(int16_t));
        return result;
    }

    // Interleave 10ms render and capture sub-frames 1:1 in time order.
    size_t processed = 0;
    size_t render_offset = 0;
    while (processed + kSubFrameSamples <= count) {
        if (render_offset + kSubFrameSamples <= drained_render_.size()) {
            float* const* r_ch = render_buffer_->channels();
            for (int i = 0; i < kSubFrameSamples; ++i) {
                r_ch[0][i] = static_cast<float>(drained_render_[render_offset + i]);
            }
            echo_control_->AnalyzeRender(render_buffer_.get());
            render_offset += kSubFrameSamples;
        }

        float* const* c_ch = capture_buffer_->channels();
        for (int i = 0; i < kSubFrameSamples; ++i) {
            c_ch[0][i] = static_cast<float>(capture_in[processed + i]);
        }

        echo_control_->SetAudioBufferDelay(effective_delay_ms);
        high_pass_->Process(capture_buffer_.get(), /*use_split_band_data=*/false);
        echo_control_->AnalyzeCapture(capture_buffer_.get());
        echo_control_->ProcessCapture(capture_buffer_.get(), /*level_change=*/false);

        const float* const* out_ch = capture_buffer_->channels_const();
        for (int i = 0; i < kSubFrameSamples; ++i) {
            float v = out_ch[0][i];
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            clean_out[processed + i] = static_cast<int16_t>(std::lrintf(v));
        }
        processed += kSubFrameSamples;
    }

    if (processed < count) {
        std::memcpy(clean_out + processed, capture_in + processed, (count - processed) * sizeof(int16_t));
    }

    if (processed > 0) {
        const auto metrics = echo_control_->GetMetrics();
        result.aec_applied = true;
        result.erle_db = std::isfinite(metrics.echo_return_loss_enhancement)
            ? static_cast<float>(metrics.echo_return_loss_enhancement)
            : 0.0f;
        result.delay_ms = metrics.delay_ms;
    }
    return result;
}

} // namespace treko::audio
