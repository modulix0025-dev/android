// Offline speech recognition on top of whisper.cpp.
#pragma once

#include <atomic>
#include <string>
#include <vector>

struct whisper_context;

namespace mx {

struct Segment {
    int64_t t0_ms;
    int64_t t1_ms;
    std::string text;
    float no_speech_prob;
};

struct Transcript {
    std::string text;
    std::string language;     // detected or forced language code
    float language_prob = -1; // only when auto-detected
    std::vector<Segment> segments;
    int64_t elapsed_ms = 0;
};

class SpeechRecognizer {
public:
    ~SpeechRecognizer();
    bool load(const std::string & path, std::string & error);
    void unload();

    // `samples`: 16 kHz mono float PCM. `language`: whisper code or "auto".
    // `single_segment` is used for fast partial results.
    bool transcribe(const float * samples, int n, const std::string & language, int threads, bool single_segment,
                    const std::string & initial_prompt, Transcript & out, std::string & error);

    void request_cancel() { cancel_.store(true); }
    bool multilingual() const;

private:
    whisper_context * ctx_ = nullptr;
    std::atomic<bool> cancel_{false};
};

} // namespace mx
