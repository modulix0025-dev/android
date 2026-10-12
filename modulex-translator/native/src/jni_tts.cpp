// JNI bridge for com.modulex.translator.engines.NativeVoice.
#include "jni_util.h"
#include "tts.h"

#include <memory>

using mx::Voice;

namespace {
Voice * from(jlong h) { return reinterpret_cast<Voice *>(h); }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_modulex_translator_engines_NativeVoice_nativeLoad(JNIEnv * env, jclass, jstring jmodel, jstring jconfig, jstring jdata) {
    auto v = std::make_unique<Voice>();
    std::string err;
    if (!v->load(mx::to_utf8(env, jmodel), mx::to_utf8(env, jconfig), mx::to_utf8(env, jdata), err)) {
        mx::throw_engine_error(env, "MODEL_LOAD_FAILED", err);
        return 0;
    }
    return reinterpret_cast<jlong>(v.release());
}

// `listener.onAudio(float[] samples, int sampleRate, boolean isLast): boolean` receives each sentence.
JNIEXPORT void JNICALL
Java_com_modulex_translator_engines_NativeVoice_nativeSynthesize(JNIEnv * env, jclass, jlong h, jstring jtext, jfloat rate,
                                                                 jobject listener) {
    jclass cls = env->GetObjectClass(listener);
    jmethodID on_audio = env->GetMethodID(cls, "onAudio", "([FIZ)Z");
    if (on_audio == nullptr) return;
    mx::VoiceParams p;
    if (rate > 0) p.length_scale = 1.0f / rate;
    std::string err;
    const bool ok = from(h)->synthesize(mx::to_utf8(env, jtext), p, [&](const float * s, size_t n, int sr, bool last) {
        jfloatArray arr = env->NewFloatArray(static_cast<jsize>(n));
        if (arr == nullptr) return false;
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(n), s);
        const jboolean keep = env->CallBooleanMethod(listener, on_audio, arr, sr, last ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(arr);
        return !env->ExceptionCheck() && keep == JNI_TRUE;
    }, err);
    if (!ok && !env->ExceptionCheck()) mx::throw_engine_error(env, err == "cancelled" ? "CANCELLED" : "INFERENCE_FAILED", err);
}

JNIEXPORT void JNICALL
Java_com_modulex_translator_engines_NativeVoice_nativeCancel(JNIEnv *, jclass, jlong h) {
    if (h) from(h)->request_cancel();
}

JNIEXPORT void JNICALL
Java_com_modulex_translator_engines_NativeVoice_nativeFree(JNIEnv *, jclass, jlong h) {
    delete from(h);
}

} // extern "C"
