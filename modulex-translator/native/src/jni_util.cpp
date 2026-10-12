#include "jni_util.h"

#include <cstdint>
#include <cstdio>

namespace mx {

std::string to_utf8(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const jsize len = env->GetStringLength(s);
    const jchar * chars = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(static_cast<size_t>(len) * 3);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD; // lone surrogate
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

jstring to_jstring(JNIEnv * env, const std::string & s) {
    std::vector<jchar> u16;
    u16.reserve(s.size());
    size_t i = 0;
    const auto * b = reinterpret_cast<const unsigned char *>(s.data());
    const size_t n = s.size();
    while (i < n) {
        uint32_t cp;
        int extra;
        const unsigned char c = b[i];
        if (c < 0x80) { cp = c; extra = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; extra = 3; }
        else { u16.push_back(0xFFFD); ++i; continue; }
        bool ok = true;
        for (int k = 1; k <= extra; ++k) {
            if (i + k >= n || (b[i + k] & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (b[i + k] & 0x3F);
        }
        if (!ok) { u16.push_back(0xFFFD); ++i; continue; }
        i += extra + 1;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            u16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            u16.push_back(static_cast<jchar>(cp));
        }
    }
    return env->NewString(u16.data(), static_cast<jsize>(u16.size()));
}

void throw_engine_error(JNIEnv * env, const char * code, const std::string & message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("com/modulex/translator/engines/NativeEngineException");
    if (cls == nullptr) return; // ClassNotFound already pending
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
    jobject ex = env->NewObject(cls, ctor, env->NewStringUTF(code), to_jstring(env, message));
    env->Throw(static_cast<jthrowable>(ex));
}

std::string json_escape(const std::string & s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (const unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof buf, "\\u%04x", c);
                    o += buf;
                } else {
                    o += static_cast<char>(c);
                }
        }
    }
    return o;
}

size_t utf8_complete_prefix(const std::string & s) {
    size_t n = s.size();
    // Walk back over at most 3 continuation bytes to find the last lead byte.
    size_t i = n;
    int cont = 0;
    while (i > 0 && cont < 4) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { --i; ++cont; continue; }
        int need = (c < 0x80) ? 0 : ((c & 0xE0) == 0xC0) ? 1 : ((c & 0xF0) == 0xE0) ? 2 : ((c & 0xF8) == 0xF0) ? 3 : 0;
        return (cont >= need) ? n : i - 1;
    }
    return n;
}

} // namespace mx
