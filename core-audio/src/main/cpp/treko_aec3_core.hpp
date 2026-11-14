#ifndef TREKO_AEC3_CORE_HPP_
#define TREKO_AEC3_CORE_HPP_

#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <vector>

#include "api/echo_canceller3_config.h"
#include "api/echo_canceller3_factory.h"
#include "api/echo_control.h"
#include "api/environment.h"
#include "audio_processing/audio_buffer.h"
#include "audio_processing/high_pass_filter.h"

namespace treko::audio {

struct AecProcessMetrics {
    bool aec_applied = false;
    float erle_db = 0.0f;
    int delay_ms = 0;
};

/**
 * Thread-safe wrapper around WebRTC AEC3 (EchoCanceller3) for 16 kHz mono 10ms frames.
 * Playback thread pushes 16 kHz far-end render samples into a bounded FIFO under a short
 * mutex; the capture thread owns all calls into EchoControl and HighPassFilter.
 */
class TrekoAecProcessor {
public:
    static constexpr int kSampleRateHz = 16000;
    static constexpr int kChannels = 1;
    static constexpr int kSubFrameSamples = 160; // 10ms @ 16kHz
    static constexpr size_t kMaxRenderFifoSamples = kSampleRateHz / 2; // 500ms cap

    TrekoAecProcessor();
    ~TrekoAecProcessor() = default;

    TrekoAecProcessor(const TrekoAecProcessor&) = delete;
    TrekoAecProcessor& operator=(const TrekoAecProcessor&) = delete;

    void FeedRender16k(const int16_t* samples, size_t count, int playout_delay_ms);

    AecProcessMetrics ProcessCapture16k(
        const int16_t* capture_in,
        size_t count,
        int playout_delay_ms,
        int16_t* clean_out
    );

    void Reset();

private:
    void InitEchoControlLocked();

    std::mutex render_mutex_;
    std::deque<int16_t> render_fifo_;
    int latest_playout_delay_ms_ = 80;

    std::mutex aec_mutex_;
    webrtc::Environment env_;
    std::unique_ptr<webrtc::EchoControl> echo_control_;
    std::unique_ptr<webrtc::HighPassFilter> high_pass_;
    std::unique_ptr<webrtc::AudioBuffer> render_buffer_;
    std::unique_ptr<webrtc::AudioBuffer> capture_buffer_;
    std::vector<int16_t> drained_render_;
};

} // namespace treko::audio

#endif // TREKO_AEC3_CORE_HPP_
