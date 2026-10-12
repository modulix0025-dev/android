// JNI bridge for com.modulex.translator.engines.NativeSpeech.
#include "jni_util.h"
#include "speech.h"

#include <cmath>
#include <memory>

using mx::SpeechRecognizer;

namespace {
SpeechRecognizer * from(jlong h) { return reinterpret_cast<SpeechRecognizer *>(h); }
std::string num(float f) { return std::isfinite(f) ? std::to_string(f) : "0"; }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_modulex_translator_engines_NativeSpeech_nativeLoad(JNIEnv * env, jclass, jstring jpath) {
    auto r = std::make_unique<SpeechRecognizer>();
    std::string err;
    if (!r->load(mx::to_utf8(env, jpath), err)) {
        mx::throw_engine_error(env, "MODEL_LOAD_FAILED", err);
        return 0;
    }
    return reinterpret_cast<jlong>(r.release());
}

JNIEXPORT jboolean JNICALL
Java_com_modulex_translator_engines_NativeSpeech_nativeIsMultilingual(JNIEnv *, jclass, jlong h) {
    return from(h)->multilingual() ? JNI_TRUE : JNI_FALSE;
}

// Returns JSON: {"text":..., "language":..., "elapsedMs":..., "segments":[{"t0":..,"t1":..,"text":..,"noSpeech":..}]}
JNIEXPORT jstring JNICALL
Java_com_modulex_translator_engines_NativeSpeech_nativeTranscribe(JNIEnv * env, jclass, jlong h, jfloatArray jpcm,
                                                                   jstring jlang, jint threads, jboolean partial, jstring jprompt) {
    const jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());
    mx::Transcript t;
    std::string err;
    if (!from(h)->transcribe(pcm.data(), n, mx::to_utf8(env, jlang), threads, partial == JNI_TRUE, mx::to_utf8(env, jprompt), t, err)) {
        mx::throw_engine_error(env, err == "cancelled" ? "CANCELLED" : "INFERENCE_FAILED", err);
        return nullptr;
    }
    std::string json = "{\"text\":\"" + mx::json_escape(t.text) + "\",\"language\":\"" + mx::json_escape(t.language) +
        "\",\"elapsedMs\":" + std::to_string(t.elapsed_ms) + ",\"segments\":[";
    for (size_t i = 0; i < t.segments.size(); ++i) {
        const auto & s = t.segments[i];
        if (i) json += ",";
        json += "{\"t0\":" + std::to_string(s.t0_ms) + ",\"t1\":" + std::to_string(s.t1_ms) +
            ",\"text\":\"" + mx::json_escape(s.text) + "\",\"noSpeech\":" + num(s.no_speech_prob) + "}";
    }
    json += "]}";
    return mx::to_jstring(env, json);
}

JNIEXPORT void JNICALL
Java_com_modulex_translator_engines_NativeSpeech_nativeCancel(JNIEnv *, jclass, jlong h) {
    if (h) from(h)->request_cancel();
}

JNIEXPORT void JNICALL
Java_com_modulex_translator_engines_NativeSpeech_nativeFree(JNIEnv *, jclass, jlong h) {
    delete from(h);
}

} // extern "C"
