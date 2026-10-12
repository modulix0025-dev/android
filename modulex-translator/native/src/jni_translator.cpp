// JNI bridge for com.modulex.translator.engines.NativeTranslator.
#include "jni_util.h"
#include "translator.h"

#include <memory>

using mx::Translator;

namespace {
Translator * from(jlong h) { return reinterpret_cast<Translator *>(h); }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_modulex_translator_engines_NativeTranslator_nativeLoad(JNIEnv * env, jclass, jstring jpath, jint threads, jint n_ctx) {
    auto t = std::make_unique<Translator>();
    std::string err;
    if (!t->load(mx::to_utf8(env, jpath), threads, n_ctx, err)) {
        mx::throw_engine_error(env, "MODEL_LOAD_FAILED", err);
        return 0;
    }
    return reinterpret_cast<jlong>(t.release());
}

JNIEXPORT jstring JNICALL
Java_com_modulex_translator_engines_NativeTranslator_nativeDescribe(JNIEnv * env, jclass, jlong h) {
    const auto & d = from(h)->description();
    std::string json = "{\"architecture\":\"" + mx::json_escape(d.architecture) +
        "\",\"name\":\"" + mx::json_escape(d.name) +
        "\",\"description\":\"" + mx::json_escape(d.description) +
        "\",\"parameters\":" + std::to_string(d.n_params) +
        ",\"sizeBytes\":" + std::to_string(d.size_bytes) +
        ",\"contextTrain\":" + std::to_string(d.n_ctx_train) +
        ",\"hasChatTemplate\":" + (d.has_chat_template ? "true" : "false") + "}";
    return mx::to_jstring(env, json);
}

JNIEXPORT jstring JNICALL
Java_com_modulex_translator_engines_NativeTranslator_nativeFormatPrompt(JNIEnv * env, jclass, jlong h, jstring juser) {
    std::string out, err;
    if (!from(h)->format_prompt(mx::to_utf8(env, juser), out, err)) {
        mx::throw_engine_error(env, "TEMPLATE_FAILED", err);
        return nullptr;
    }
    return mx::to_jstring(env, out);
}

// Returns the generated text; metrics are written into `metricsOut` (long[4]):
// promptTokens, generatedTokens, prefillMicros, decodeMicros.
// `listener` (nullable) receives text increments via `boolean onText(String)`.
JNIEXPORT jstring JNICALL
Java_com_modulex_translator_engines_NativeTranslator_nativeGenerate(
        JNIEnv * env, jclass, jlong h, jstring jprompt, jfloat temperature, jfloat top_p, jint top_k,
        jfloat rep_penalty, jint max_tokens, jint seed, jobject listener, jlongArray metrics_out) {
    mx::SamplingParams sp;
    sp.temperature = temperature;
    sp.top_p = top_p;
    sp.top_k = top_k;
    sp.repetition_penalty = rep_penalty;
    sp.max_new_tokens = max_tokens;
    sp.seed = static_cast<uint32_t>(seed);

    jmethodID on_text = nullptr;
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        on_text = env->GetMethodID(cls, "onText", "(Ljava/lang/String;)Z");
        if (on_text == nullptr) return nullptr;
    }
    std::string out;
    mx::GenerationMetrics m;
    const auto status = from(h)->generate(mx::to_utf8(env, jprompt), sp, out, m, [&](const std::string & piece) {
        if (!on_text) return true;
        jstring js = mx::to_jstring(env, piece);
        const jboolean keep = env->CallBooleanMethod(listener, on_text, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) return false;
        return keep == JNI_TRUE;
    });
    if (env->ExceptionCheck()) return nullptr;

    jlong vals[4] = {m.prompt_tokens, m.generated_tokens, m.prefill_us, m.decode_us};
    if (metrics_out != nullptr && env->GetArrayLength(metrics_out) >= 4) env->SetLongArrayRegion(metrics_out, 0, 4, vals);

    switch (status) {
        case mx::Status::Ok: return mx::to_jstring(env, out);
        case mx::Status::Cancelled: mx::throw_engine_error(env, "CANCELLED", "generation cancelled"); return nullptr;
        case mx::Status::PromptTooLong: mx::throw_engine_error(env, "INPUT_TOO_LONG", "input does not fit the context window"); return nullptr;
        case mx::Status::NotLoaded: mx::throw_engine_error(env, "MODEL_NOT_LOADED", "model not loaded"); return nullptr;
        case mx::Status::TemplateFailed: mx::throw_engine_error(env, "TEMPLATE_FAILED", "tokenization failed"); return nullptr;
        default: mx::throw_engine_error(env, "INFERENCE_FAILED", "decode failed"); return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_modulex_translator_engines_NativeTranslator_nativeCancel(JNIEnv *, jclass, jlong h) {
    if (h) from(h)->request_cancel();
}

JNIEXPORT void JNICALL
Java_com_modulex_translator_engines_NativeTranslator_nativeFree(JNIEnv *, jclass, jlong h) {
    delete from(h);
}

} // extern "C"
