#include "translator.h"

#include "jni_util.h"
#include "log.h"

#include "chat.h"
#include "llama.h"

#include <chrono>
#include <mutex>
#include <vector>

namespace mx {

namespace {

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

void llama_log_cb(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) mx_log_warn("llama", "%s", text);
}

std::string meta_str(const llama_model * m, const char * key) {
    char buf[512];
    const int32_t n = llama_model_meta_val_str(m, key, buf, sizeof buf);
    return n >= 0 ? std::string(buf) : std::string();
}

} // namespace

void translator_global_init() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(llama_log_cb, nullptr);
        llama_backend_init();
    });
}

Translator::~Translator() { unload(); }

void Translator::unload() {
    templates_.reset();
    if (ctx_) { llama_free(ctx_); ctx_ = nullptr; }
    if (model_) { llama_model_free(model_); model_ = nullptr; }
    desc_ = {};
}

bool Translator::load(const std::string & path, int n_threads, int n_ctx, std::string & error) {
    translator_global_init();
    unload();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // CPU inference; GPU backends are not validated on the target devices
    mp.load_mode = LLAMA_LOAD_MODE_MMAP; // weights are paged from the file, keeping resident memory low
    model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!model_) {
        error = "model file could not be loaded (unsupported format, architecture or quantization)";
        return false;
    }

    desc_.architecture = meta_str(model_, "general.architecture");
    desc_.name = meta_str(model_, "general.name");
    char buf[256];
    llama_model_desc(model_, buf, sizeof buf);
    desc_.description = buf;
    desc_.n_params = llama_model_n_params(model_);
    desc_.size_bytes = llama_model_size(model_);
    desc_.n_ctx_train = llama_model_n_ctx_train(model_);
    desc_.has_chat_template = llama_model_chat_template(model_, nullptr) != nullptr;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    cp.abort_callback = [](void * data) { return static_cast<std::atomic<bool> *>(data)->load(); };
    cp.abort_callback_data = &cancel_;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) {
        error = "could not allocate the inference context (insufficient memory?)";
        unload();
        return false;
    }
    n_threads_ = n_threads;

    try {
        templates_ = common_chat_templates_init(model_, "");
    } catch (const std::exception & e) {
        mx_log_warn("translator", "chat template init failed: %s", e.what());
        templates_.reset();
    }
    return true;
}

bool Translator::format_prompt(const std::string & user_message, std::string & out, std::string & error) const {
    if (!model_) { error = "model not loaded"; return false; }
    if (templates_) {
        try {
            common_chat_templates_inputs in;
            common_chat_msg msg;
            msg.role = "user";
            msg.content = user_message;
            in.messages.push_back(msg);
            in.add_generation_prompt = true;
            in.use_jinja = true;
            in.enable_thinking = false; // translation needs the direct answer only
            out = common_chat_templates_apply(templates_.get(), in).prompt;
            if (!out.empty()) return true;
        } catch (const std::exception & e) {
            mx_log_warn("translator", "jinja template failed, using built-in: %s", e.what());
        }
    }
    const char * tmpl = llama_model_chat_template(model_, nullptr);
    llama_chat_message m{"user", user_message.c_str()};
    std::vector<char> buf(user_message.size() * 2 + 512);
    int32_t n = llama_chat_apply_template(tmpl, &m, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, &m, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n < 0) { error = "the model's chat template is not supported"; return false; }
    out.assign(buf.data(), n);
    return true;
}

Status Translator::generate(const std::string & prompt, const SamplingParams & sp, std::string & out,
                            GenerationMetrics & metrics, const std::function<bool(const std::string &)> & on_piece) {
    out.clear();
    metrics = {};
    if (!ctx_) return Status::NotLoaded;
    cancel_.store(false);

    const llama_vocab * vocab = llama_model_get_vocab(model_);
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(ctx_));

    // Tokenize (special tokens in the template are parsed as tokens).
    int32_t n = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n > 0 ? n : 1);
    n = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n < 0) return Status::TemplateFailed;
    tokens.resize(n);
    // Templates often contain BOS already; avoid a duplicated BOS.
    if (tokens.size() >= 2 && tokens[0] == llama_vocab_bos(vocab) && tokens[1] == tokens[0]) tokens.erase(tokens.begin());
    metrics.prompt_tokens = static_cast<int32_t>(tokens.size());
    if (metrics.prompt_tokens + 8 >= n_ctx) return Status::PromptTooLong;

    llama_memory_clear(llama_get_memory(ctx_), true);

    // Prefill in batches.
    const int64_t t0 = now_us();
    const int32_t n_batch = 512;
    for (int32_t i = 0; i < metrics.prompt_tokens; i += n_batch) {
        const int32_t cnt = std::min(n_batch, metrics.prompt_tokens - i);
        const int rc = llama_decode(ctx_, llama_batch_get_one(tokens.data() + i, cnt));
        if (cancel_.load()) return Status::Cancelled;
        if (rc != 0) return rc == 2 ? Status::Cancelled : Status::DecodeFailed;
    }
    metrics.prefill_us = now_us() - t0;

    // Sampler chain per the published recommendations.
    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    scp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(scp);
    if (sp.repetition_penalty != 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, sp.repetition_penalty, 0.0f, 0.0f));
    }
    if (sp.temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (sp.top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sp.top_k));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sp.top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(sp.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(sp.seed));
    }

    const int64_t t1 = now_us();
    std::string pending; // bytes not yet forming complete UTF-8
    Status status = Status::Ok;
    int32_t pos = metrics.prompt_tokens;
    const int32_t limit = std::min(sp.max_new_tokens, n_ctx - pos - 1);
    for (int32_t i = 0; i < limit; ++i) {
        if (cancel_.load()) { status = Status::Cancelled; break; }
        llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char piece[256];
        const int32_t pn = llama_token_to_piece(vocab, tok, piece, sizeof piece, 0, false);
        if (pn > 0) {
            pending.append(piece, pn);
            const size_t complete = utf8_complete_prefix(pending);
            if (complete > 0) {
                std::string chunk = pending.substr(0, complete);
                pending.erase(0, complete);
                out += chunk;
                if (on_piece && !on_piece(chunk)) { status = Status::Cancelled; break; }
            }
        }
        metrics.generated_tokens++;
        const int rc = llama_decode(ctx_, llama_batch_get_one(&tok, 1));
        if (rc != 0) { status = (rc == 2 || cancel_.load()) ? Status::Cancelled : Status::DecodeFailed; break; }
        ++pos;
    }
    out += pending;
    metrics.decode_us = now_us() - t1;
    llama_sampler_free(smpl);
    return status;
}

} // namespace mx
