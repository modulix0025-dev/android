// Small JNI helpers shared by the ModuleX native libraries.
#pragma once

#include <jni.h>

#include <string>
#include <vector>

namespace mx {

// Converts a Java string to UTF-8 (handles supplementary characters correctly, unlike
// GetStringUTFChars which produces "modified UTF-8").
std::string to_utf8(JNIEnv * env, jstring s);

// Creates a Java string from UTF-8 bytes. Invalid sequences are replaced rather than crashing
// the VM (NewStringUTF aborts on invalid modified UTF-8, e.g. a token split inside a code point).
jstring to_jstring(JNIEnv * env, const std::string & utf8);

// Throws com.modulex.translator.engines.NativeEngineException(code, message).
void throw_engine_error(JNIEnv * env, const char * code, const std::string & message);

// Escapes a string for embedding in JSON.
std::string json_escape(const std::string & s);

// Length of the longest prefix of `s` that ends on a complete UTF-8 code point.
size_t utf8_complete_prefix(const std::string & s);

} // namespace mx
