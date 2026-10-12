#include "speech.h"

#include "log.h"
#include "whisper.h"

#include <chrono>
#include <vector>

namespace mx {

SpeechRecognizer::~SpeechRecognizer() { unload(); }

void SpeechRecognizer::unload() {
    if (ctx_) { whisper_free(ctx_); ctx_ = nullptr; }
}

bool SpeechRecognizer::load(const std::string & path, std::string & error) {
    unload();
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = false;
    ctx_ = whisper_init_from_file_with_params(path.c_str(), cp);
    if (!ctx_) {
        error = "speech model could not be loaded";
        return false;
    }
    return true;
}

bool SpeechRecognizer::multilingual() const { return ctx_ && whisper_is_multilingual(ctx_); }

bool SpeechRecognizer::transcribe(const float * samples, int n, const std::string & language, int threads, bool single_segment,
                                  const std::string & initial_prompt, Transcript & out, std::string & error) {
    if (!ctx_) { error = "speech model not loaded"; return false; }
    cancel_.store(false);
    const auto t0 = std::chrono::steady_clock::now();

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.translate = false;              // translation is done by the translation engine
    p.no_context = true;              // utterances are independent; avoids repeating previous text
    p.single_segment = single_segment;
    p.suppress_blank = true;
    p.suppress_nst = true;            // no non-speech tokens like [music]
    p.temperature_inc = single_segment ? 0.0f : 0.2f;
    p.no_speech_thold = 0.6f;
    const bool autodetect = language.empty() || language == "auto";
    p.language = autodetect ? "auto" : language.c_str();
    p.detect_language = false;
    if (!initial_prompt.empty()) p.initial_prompt = initial_prompt.c_str();
    p.abort_callback = [](void * data) { return static_cast<std::atomic<bool> *>(data)->load(); };
    p.abort_callback_user_data = &cancel_;

    if (autodetect && !whisper_is_multilingual(ctx_)) {
        error = "this speech model cannot detect languages";
        return false;
    }

    const int rc = whisper_full(ctx_, p, samples, n);
    if (cancel_.load()) { error = "cancelled"; return false; }
    if (rc != 0) { error = "speech recognition failed"; return false; }

    out = {};
    const int lang_id = whisper_full_lang_id(ctx_);
    out.language = lang_id >= 0 ? whisper_lang_str(lang_id) : language;
    const int ns = whisper_full_n_segments(ctx_);
    for (int i = 0; i < ns; ++i) {
        Segment s;
        s.t0_ms = whisper_full_get_segment_t0(ctx_, i) * 10;
        s.t1_ms = whisper_full_get_segment_t1(ctx_, i) * 10;
        s.text = whisper_full_get_segment_text(ctx_, i);
        s.no_speech_prob = whisper_full_get_segment_no_speech_prob(ctx_, i);
        out.text += s.text;
        out.segments.push_back(std::move(s));
    }
    out.elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    return true;
}

} // namespace mx
