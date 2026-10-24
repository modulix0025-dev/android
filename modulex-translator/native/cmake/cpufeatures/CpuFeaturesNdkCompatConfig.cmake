# Stand-in for the NDK cpufeatures package that Tesseract's CMakeLists requires on Android.
# Tesseract only calls android_getCpuFeatures() for 32-bit ARM NEON detection, guarded by
# HAVE_ANDROID_GETCPUFAMILY, which this build never defines. The app ships arm64-v8a (NEON is
# mandatory) and x86_64, so no cpufeatures code is needed; the target is an empty interface.
if (NOT TARGET CpuFeatures::ndk_compat)
    add_library(CpuFeatures::ndk_compat INTERFACE IMPORTED)
endif()
set(CpuFeaturesNdkCompat_FOUND TRUE)
