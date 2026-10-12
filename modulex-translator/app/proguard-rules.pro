# JNI: native methods are bound by class and method name.
-keepclasseswithmembernames,includedescriptorclasses class com.modulex.translator.engines.** {
    native <methods>;
}
-keep class com.modulex.translator.engines.NativeTranslator { *; }
-keep class com.modulex.translator.engines.NativeSpeech { *; }
-keep class com.modulex.translator.engines.NativeOcr { *; }
-keep class com.modulex.translator.engines.NativeVoice { *; }
# Called from native code by name/signature.
-keep class com.modulex.translator.engines.NativeEngineException { <init>(java.lang.String, java.lang.String); }
-keep interface com.modulex.translator.engines.TextListener { boolean onText(java.lang.String); }
-keep interface com.modulex.translator.engines.AudioListener { boolean onAudio(float[], int, boolean); }
-keep class * implements com.modulex.translator.engines.TextListener { boolean onText(java.lang.String); }
-keep class * implements com.modulex.translator.engines.AudioListener { boolean onAudio(float[], int, boolean); }

# WebRTC uses JNI callbacks into its Java classes.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# kotlinx.serialization (generated serializers are kept by the library's own rules; keep companions).
-keepclassmembers @kotlinx.serialization.Serializable class com.modulex.translator.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
