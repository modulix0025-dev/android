# Piper (GPL-3.0) + espeak-ng (GPL-3.0) for offline voices.
# Shipping this library makes the distributed application subject to GPL-3.0 terms;
# it is disabled unless MX_WITH_TTS=ON (see docs/LICENSING.md).

set(ESPEAK_DIR "${MX_THIRD_PARTY}/espeak-ng")
set(PIPER_DIR "${MX_THIRD_PARTY}/piper1-gpl/libpiper")
if (NOT EXISTS "${PIPER_DIR}/include/piper.h" OR NOT EXISTS "${ESPEAK_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "piper1-gpl / espeak-ng sources missing. Run tools/fetch-native-deps.sh")
endif()
if (NOT MX_ONNXRUNTIME_DIR)
    message(FATAL_ERROR "MX_ONNXRUNTIME_DIR must point at onnxruntime headers and library")
endif()

# espeak-ng library only: phoneme data is compiled once on a host (tools/build-espeak-data.sh)
# and installed as a verified asset pack, so cross-compilation never runs target binaries.
set(COMPILE_INTONATIONS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(USE_ASYNC OFF CACHE BOOL "" FORCE)
set(USE_MBROLA OFF CACHE BOOL "" FORCE)
set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
set(USE_KLATT OFF CACHE BOOL "" FORCE)
set(USE_SPEECHPLAYER OFF CACHE BOOL "" FORCE)
set(ESPEAK_BUILD_MANPAGES OFF CACHE BOOL "" FORCE)
add_subdirectory(${ESPEAK_DIR} espeak-ng EXCLUDE_FROM_ALL)

if (ANDROID)
    set(ORT_LIB "${MX_ONNXRUNTIME_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so")
    set(ORT_INC "${MX_ONNXRUNTIME_DIR}/headers")
else()
    find_library(ORT_LIB NAMES onnxruntime PATHS "${MX_ONNXRUNTIME_DIR}/lib" NO_DEFAULT_PATH REQUIRED)
    set(ORT_INC "${MX_ONNXRUNTIME_DIR}/include")
endif()
add_library(mx_onnxruntime SHARED IMPORTED)
set_target_properties(mx_onnxruntime PROPERTIES IMPORTED_LOCATION "${ORT_LIB}" INTERFACE_INCLUDE_DIRECTORIES "${ORT_INC}")

add_library(mx_piper STATIC ${PIPER_DIR}/src/piper.cpp ${PIPER_DIR}/src/chinese_phonemizer.cpp)
target_include_directories(mx_piper PUBLIC ${PIPER_DIR}/include ${ESPEAK_DIR}/src/include)
target_compile_definitions(mx_piper PRIVATE PIPER_VERSION="1.x-mx")
target_link_libraries(mx_piper PUBLIC espeak-ng mx_onnxruntime)
