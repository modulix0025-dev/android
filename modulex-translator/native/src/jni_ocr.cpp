// JNI bridge for com.modulex.translator.engines.NativeOcr.
#include "jni_util.h"
#include "ocr.h"

#include <cmath>
#include <memory>

using mx::TextRecognizer;

namespace {
TextRecognizer * from(jlong h) { return reinterpret_cast<TextRecognizer *>(h); }
std::string num(float f) { return std::isfinite(f) ? std::to_string(f) : "0"; }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_modulex_translator_engines_NativeOcr_nativeInit(JNIEnv * env, jclass, jstring jdir, jstring jlangs) {
    auto r = std::make_unique<TextRecognizer>();
    std::string err;
    if (!r->init(mx::to_utf8(env, jdir), mx::to_utf8(env, jlangs), err)) {
        mx::throw_engine_error(env, "MODEL_LOAD_FAILED", err);
        return 0;
    }
    return reinterpret_cast<jlong>(r.release());
}

// Returns JSON: {"text":..,"meanConfidence":..,"elapsedMs":..,"lines":[{"text","conf","x","y","w","h","block","para"}]}
JNIEXPORT jstring JNICALL
Java_com_modulex_translator_engines_NativeOcr_nativeRecognize(JNIEnv * env, jclass, jlong h, jbyteArray jpix, jint width,
                                                              jint height, jint bpp, jint bpl, jint psm) {
    const jsize len = env->GetArrayLength(jpix);
    if (static_cast<int64_t>(bpl) * height > len) {
        mx::throw_engine_error(env, "INVALID_INPUT", "pixel buffer too small");
        return nullptr;
    }
    jbyte * px = env->GetByteArrayElements(jpix, nullptr);
    mx::OcrResult r;
    std::string err;
    const bool ok = from(h)->recognize(reinterpret_cast<unsigned char *>(px), width, height, bpp, bpl, psm, r, err);
    env->ReleaseByteArrayElements(jpix, px, JNI_ABORT);
    if (!ok) {
        mx::throw_engine_error(env, "INFERENCE_FAILED", err);
        return nullptr;
    }
    std::string json = "{\"text\":\"" + mx::json_escape(r.text) + "\",\"meanConfidence\":" + num(r.mean_confidence) +
        ",\"elapsedMs\":" + std::to_string(r.elapsed_ms) + ",\"lines\":[";
    for (size_t i = 0; i < r.lines.size(); ++i) {
        const auto & l = r.lines[i];
        if (i) json += ",";
        json += "{\"text\":\"" + mx::json_escape(l.text) + "\",\"conf\":" + num(l.confidence) +
            ",\"x\":" + std::to_string(l.x) + ",\"y\":" + std::to_string(l.y) + ",\"w\":" + std::to_string(l.w) +
            ",\"h\":" + std::to_string(l.h) + ",\"block\":" + std::to_string(l.block) + ",\"para\":" + std::to_string(l.paragraph) + "}";
    }
    json += "]}";
    return mx::to_jstring(env, json);
}

JNIEXPORT void JNICALL
Java_com_modulex_translator_engines_NativeOcr_nativeFree(JNIEnv *, jclass, jlong h) {
    delete from(h);
}

} // extern "C"
