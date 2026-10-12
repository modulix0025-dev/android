// Offline neural voices on top of Piper.
#pragma once

#include <atomic>
#include <functional>
#include <string>

struct piper_synthesizer;

namespace mx {

struct VoiceParams {
    int speaker_id = -1;      // -1: model default
    float length_scale = -1;  // < 0: model default (speaking rate)
    float noise_scale = -1;
    float noise_w_scale = -1;
};

class Voice {
public:
    ~Voice();
    bool load(const std::string & model, const std::string & config, const std::string & espeak_data, std::string & error);
    // Streams audio sentence by sentence. `on_chunk(samples, n, sample_rate, is_last)` returns false to stop.
    bool synthesize(const std::string & text, const VoiceParams & p,
                    const std::function<bool(const float *, size_t, int, bool)> & on_chunk, std::string & error);
    void request_cancel() { cancel_.store(true); }

private:
    piper_synthesizer * synth_ = nullptr;
    std::atomic<bool> cancel_{false};
};

} // namespace mx
