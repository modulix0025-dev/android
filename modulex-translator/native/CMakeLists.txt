# ModuleX Translator native engines.
#
# Produces up to four JNI libraries, each statically linking its dependencies and exporting only
# JNI symbols:
#   libmx_ai.so   translation (llama.cpp) + speech recognition (whisper.cpp), sharing one ggml
#   libmx_ocr.so  text recognition (Tesseract + Leptonica)
#   libmx_tts.so  offline voices (Piper + espeak-ng, GPL-3.0; OFF by default, see docs/LICENSING.md)
#
# The same file builds for Android (via the Gradle externalNativeBuild) and for the host JVM, where
# the JNI libraries are exercised by the :engines JVM tests.

cmake_minimum_required(VERSION 3.22)
project(modulex_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

option(MX_WITH_AI  "Build translation + speech engines" ON)
option(MX_WITH_OCR "Build the text recognition engine" ON)
option(MX_WITH_TTS "Build the Piper voice engine (GPL-3.0)" OFF)
set(MX_THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../third_party" CACHE PATH "Location of pinned dependency sources")
set(MX_ONNXRUNTIME_DIR "" CACHE PATH "onnxruntime headers+library (required when MX_WITH_TTS)")

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

if (ANDROID)
    # Release-quality code even in debug app builds: inference is unusable at -O0.
    set(CMAKE_C_FLAGS_DEBUG "-O2 -g")
    set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")
else()
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
endif()

# Export only JNI entry points; everything else (ggml, llama, whisper, tesseract...) stays private
# so that two libraries can never clash on symbols.
set(MX_EXPORTS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
function(mx_jni_library name)
    add_library(${name} SHARED ${ARGN})
    target_include_directories(${name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
    set_target_properties(${name} PROPERTIES CXX_VISIBILITY_PRESET hidden C_VISIBILITY_PRESET hidden)
    target_link_options(${name} PRIVATE "-Wl,--version-script=${MX_EXPORTS}" "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
    if (ANDROID)
        target_link_libraries(${name} PRIVATE log android)
        # 16 KB page size compatibility (Android 15+ devices).
        target_link_options(${name} PRIVATE "-Wl,-z,max-page-size=16384")
    endif()
endfunction()

# ---------------------------------------------------------------- translation + speech
if (MX_WITH_AI)
    set(LLAMA_DIR "${MX_THIRD_PARTY}/llama.cpp")
    set(WHISPER_DIR "${MX_THIRD_PARTY}/whisper.cpp")
    if (NOT EXISTS "${LLAMA_DIR}/include/llama.h" OR NOT EXISTS "${WHISPER_DIR}/include/whisper.h")
        message(FATAL_ERROR "llama.cpp / whisper.cpp sources missing. Run tools/fetch-native-deps.sh")
    endif()

    set(LLAMA_BUILD_COMMON ON  CACHE BOOL "" FORCE) # chat templates (Jinja) from the model file
    set(LLAMA_BUILD_TESTS  OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS  OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP    OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL      OFF CACHE BOOL "" FORCE) # no network code paths in the app
    set(LLAMA_SUBPROCESS   OFF CACHE BOOL "" FORCE)
    set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP        OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE     ON  CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL    OFF CACHE BOOL "" FORCE)
    if (ANDROID)
        set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
        if (ANDROID_ABI STREQUAL "arm64-v8a")
            # ARMv8.2 dot-product + fp16. The app checks /proc/cpuinfo before loading (see CpuSupport.kt).
            set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
        endif()
    endif()
    add_subdirectory(${LLAMA_DIR} llama.cpp EXCLUDE_FROM_ALL)

    # whisper.cpp built against llama.cpp's ggml so the app carries a single copy.
    add_library(mx_whisper STATIC
        ${WHISPER_DIR}/src/whisper.cpp
        ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp)
    target_include_directories(mx_whisper PUBLIC ${WHISPER_DIR}/include ${WHISPER_DIR}/src)
    target_compile_definitions(mx_whisper PRIVATE WHISPER_VERSION="mx")
    target_link_libraries(mx_whisper PUBLIC ggml)

    mx_jni_library(mx_ai
        src/jni_util.cpp
        src/translator.cpp
        src/jni_translator.cpp
        src/speech.cpp
        src/jni_speech.cpp)
    target_link_libraries(mx_ai PRIVATE llama llama-common mx_whisper ggml)
endif()

# ---------------------------------------------------------------- OCR
if (MX_WITH_OCR)
    set(LEPT_DIR "${MX_THIRD_PARTY}/leptonica")
    set(TESS_DIR "${MX_THIRD_PARTY}/tesseract")
    if (NOT EXISTS "${TESS_DIR}/include/tesseract/baseapi.h")
        message(FATAL_ERROR "tesseract sources missing. Run tools/fetch-native-deps.sh")
    endif()
    # Leptonica without image codecs: the app hands over decoded pixels.
    set(SW_BUILD OFF CACHE BOOL "" FORCE)
    set(BUILD_PROG OFF CACHE BOOL "" FORCE)
    set(ENABLE_ZLIB OFF CACHE BOOL "" FORCE)
    set(ENABLE_PNG OFF CACHE BOOL "" FORCE)
    set(ENABLE_GIF OFF CACHE BOOL "" FORCE)
    set(ENABLE_JPEG OFF CACHE BOOL "" FORCE)
    set(ENABLE_TIFF OFF CACHE BOOL "" FORCE)
    set(ENABLE_WEBP OFF CACHE BOOL "" FORCE)
    set(ENABLE_OPENJPEG OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LEPT_DIR} leptonica EXCLUDE_FROM_ALL)
    # Expose only Leptonica's public generated header. Its build dir also holds a config_auto.h
    # that would shadow Tesseract's own (and silently re-enable the ScrollView debug viewer).
    configure_file("${CMAKE_CURRENT_BINARY_DIR}/leptonica/src/endianness.h"
                   "${CMAKE_CURRENT_BINARY_DIR}/leptonica-public/endianness.h" COPYONLY)
    file(READ "${LEPT_DIR}/CMakeLists.txt" _lept_cmake)
    string(REGEX MATCH "project\\([^)]*VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)" _m "${_lept_cmake}")
    set(MX_LEPTONICA_VERSION "${CMAKE_MATCH_1}")
    set(Leptonica_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cmake/leptonica" CACHE PATH "" FORCE)

    set(BUILD_TRAINING_TOOLS OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(DISABLE_ARCHIVE ON CACHE BOOL "" FORCE)
    set(DISABLE_CURL ON CACHE BOOL "" FORCE) # no network code
    set(DISABLE_TIFF ON CACHE BOOL "" FORCE)
    set(GRAPHICS_DISABLED ON CACHE BOOL "" FORCE)
    set(OPENMP_BUILD OFF CACHE BOOL "" FORCE)
    set(ENABLE_NATIVE OFF CACHE BOOL "" FORCE)
    set(INSTALL_CONFIGS OFF CACHE BOOL "" FORCE)
    if (ANDROID)
        set(CpuFeaturesNdkCompat_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cmake/cpufeatures" CACHE PATH "" FORCE)
    endif()
    add_subdirectory(${TESS_DIR} tesseract EXCLUDE_FROM_ALL)

    mx_jni_library(mx_ocr src/jni_util.cpp src/ocr.cpp src/jni_ocr.cpp)
    target_link_libraries(mx_ocr PRIVATE libtesseract leptonica)
    # tesseract/version.h is generated into the build tree.
    target_include_directories(mx_ocr PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/tesseract/include ${TESS_DIR}/include)
endif()

# ---------------------------------------------------------------- TTS (GPL-3.0)
if (MX_WITH_TTS)
    include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/piper.cmake)
    mx_jni_library(mx_tts src/jni_util.cpp src/tts.cpp src/jni_tts.cpp)
    target_link_libraries(mx_tts PRIVATE mx_piper)
endif()
