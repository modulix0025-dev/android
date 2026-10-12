#include "tts.h"

#include "piper.h"

namespace mx {

Voice::~Voice() {
    if (synth_) piper_free(synth_);
}

bool Voice::load(const std::string & model, const std::string & config, const std::string & espeak_data, std::string & error) {
    synth_ = piper_create(model.c_str(), config.c_str(), espeak_data.c_str());
    if (!synth_) {
        error = "voice could not be loaded";
        return false;
    }
    return true;
}

bool Voice::synthesize(const std::string & text, const VoiceParams & p,
                       const std::function<bool(const float *, size_t, int, bool)> & on_chunk, std::string & error) {
    if (!synth_) { error = "voice not loaded"; return false; }
    cancel_.store(false);
    piper_synthesize_options o = piper_default_synthesize_options(synth_);
    if (p.speaker_id >= 0) o.speaker_id = p.speaker_id;
    if (p.length_scale > 0) o.length_scale = p.length_scale;
    if (p.noise_scale >= 0) o.noise_scale = p.noise_scale;
    if (p.noise_w_scale >= 0) o.noise_w_scale = p.noise_w_scale;
    if (piper_synthesize_start(synth_, text.c_str(), &o) != PIPER_OK) {
        error = "speech synthesis failed to start";
        return false;
    }
    piper_audio_chunk chunk;
    while (true) {
        if (cancel_.load()) { error = "cancelled"; return false; }
        const int rc = piper_synthesize_next(synth_, &chunk);
        if (rc == PIPER_DONE) break;
        if (rc != PIPER_OK) { error = "speech synthesis failed"; return false; }
        if (!on_chunk(chunk.samples, chunk.num_samples, chunk.sample_rate, chunk.is_last)) { error = "cancelled"; return false; }
        if (chunk.is_last) break;
    }
    return true;
}

} // namespace mx
