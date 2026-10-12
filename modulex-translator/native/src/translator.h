// Local translation runtime on top of llama.cpp.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

struct llama_model;
struct llama_context;
struct common_chat_templates;

namespace mx {

struct ModelDescription {
    std::string architecture;
    std::string name;
    std::string description;
    uint64_t n_params = 0;
    uint64_t size_bytes = 0;
    int32_t n_ctx_train = 0;
    bool has_chat_template = false;
};

struct SamplingParams {
    float temperature = 0.7f;
    float top_p = 0.6f;
    int32_t top_k = 20;
    float repetition_penalty = 1.05f;
    int32_t max_new_tokens = 1024;
    uint32_t seed = 42;
};

struct GenerationMetrics {
    int32_t prompt_tokens = 0;
    int32_t generated_tokens = 0;
    int64_t prefill_us = 0;
    int64_t decode_us = 0;
};

enum class Status {
    Ok = 0,
    NotLoaded,
    PromptTooLong,
    TemplateFailed,
    DecodeFailed,
    Cancelled,
};

class Translator {
public:
    Translator() = default;
    ~Translator();
    Translator(const Translator &) = delete;
    Translator & operator=(const Translator &) = delete;

    // Loads a GGUF model. Returns false and fills `error` on failure.
    bool load(const std::string & path, int n_threads, int n_ctx, std::string & error);
    void unload();
    bool loaded() const { return ctx_ != nullptr; }

    const ModelDescription & description() const { return desc_; }

    // Formats a single user turn with the model's own chat template (Jinja first, then the
    // built-in template table), adding the assistant prefix.
    bool format_prompt(const std::string & user_message, std::string & out, std::string & error) const;

    // Runs generation for an already formatted prompt. `on_piece` receives UTF-8 complete text
    // increments; returning false from it stops generation.
    Status generate(const std::string & formatted_prompt, const SamplingParams & params, std::string & out,
                    GenerationMetrics & metrics, const std::function<bool(const std::string &)> & on_piece);

    void request_cancel() { cancel_.store(true); }

private:
    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    std::shared_ptr<common_chat_templates> templates_;
    ModelDescription desc_;
    int n_threads_ = 4;
    std::atomic<bool> cancel_{false};
};

// One-time process initialisation (backend + log routing).
void translator_global_init();

} // namespace mx
