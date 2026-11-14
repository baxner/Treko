cmake_minimum_required(VERSION 3.22.1)
project("treko_audio" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WEBRTC_AEC3_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/webrtc_aec3/src)

file(GLOB_RECURSE WEBRTC_AEC3_SOURCES
    ${WEBRTC_AEC3_SRC_DIR}/*.cc
    ${WEBRTC_AEC3_SRC_DIR}/*.c
    ${WEBRTC_AEC3_SRC_DIR}/*.cpp
)

if(ANDROID)
    set(EFFECTIVE_ARCH ${CMAKE_SYSTEM_PROCESSOR})
else()
    set(EFFECTIVE_ARCH ${CMAKE_SYSTEM_PROCESSOR})
endif()

# Exclude x86 SIMD sources on non-x86 architectures
if(NOT (EFFECTIVE_ARCH MATCHES "x86_64|AMD64|amd64|i686|x86"))
    list(FILTER WEBRTC_AEC3_SOURCES EXCLUDE REGEX ".*_avx2\\.cc$")
    list(FILTER WEBRTC_AEC3_SOURCES EXCLUDE REGEX ".*_sse2\\.cc$")
    list(FILTER WEBRTC_AEC3_SOURCES EXCLUDE REGEX ".*_sse\\.cc$")
else()
    file(GLOB_RECURSE WEBRTC_AVX2_SOURCES ${WEBRTC_AEC3_SRC_DIR}/*_avx2.cc)
    if(WEBRTC_AVX2_SOURCES)
        set_source_files_properties(${WEBRTC_AVX2_SOURCES} PROPERTIES COMPILE_FLAGS "-mavx2 -mfma")
    endif()
endif()

# Exclude ARM64 NEON intrinsics on non-ARM64 targets (armeabi-v7a / x86 / x86_64)
if(NOT (EFFECTIVE_ARCH MATCHES "aarch64|arm64|ARM64"))
    list(FILTER WEBRTC_AEC3_SOURCES EXCLUDE REGEX ".*_neon\\.cc$")
endif()

if(ANDROID)
    list(FILTER WEBRTC_AEC3_SOURCES EXCLUDE REGEX "cpu_features_linux\\.c$")
endif()

# Exclude WAV file helpers unless building host test
list(FILTER WEBRTC_AEC3_SOURCES EXCLUDE REGEX ".*/wav_file\\.cc$")
list(FILTER WEBRTC_AEC3_SOURCES EXCLUDE REGEX ".*/wav_header\\.cc$")

add_library(webrtc_aec3 STATIC ${WEBRTC_AEC3_SOURCES})

target_compile_options(webrtc_aec3 PRIVATE
    -O3
    -fPIC
    -fexceptions
    -frtti
    -Wno-deprecated
    -Wno-unused-parameter
    -Wno-sign-compare
)

if(ANDROID)
    target_compile_definitions(webrtc_aec3 PRIVATE WEBRTC_ANDROID WEBRTC_POSIX)
    if(ANDROID_ABI STREQUAL "x86")
        target_compile_options(webrtc_aec3 PRIVATE -msse2)
    endif()
    if(DEFINED ANDROID_NDK)
        target_include_directories(webrtc_aec3 PRIVATE ${ANDROID_NDK}/sources/android/cpufeatures)
    endif()
elseif(UNIX AND NOT APPLE)
    target_compile_definitions(webrtc_aec3 PRIVATE WEBRTC_LINUX WEBRTC_POSIX)
endif()

target_include_directories(webrtc_aec3 PUBLIC
    ${WEBRTC_AEC3_SRC_DIR}
    ${WEBRTC_AEC3_SRC_DIR}/api
    ${WEBRTC_AEC3_SRC_DIR}/base
    ${WEBRTC_AEC3_SRC_DIR}/base/rtc_base
    ${WEBRTC_AEC3_SRC_DIR}/base/system_wrappers/include
    ${WEBRTC_AEC3_SRC_DIR}/base/jsoncpp/include
    ${WEBRTC_AEC3_SRC_DIR}/absl
    ${WEBRTC_AEC3_SRC_DIR}/audio_processing
    ${WEBRTC_AEC3_SRC_DIR}/audio_processing/aec3
    ${WEBRTC_AEC3_SRC_DIR}/audio_processing/include
    ${WEBRTC_AEC3_SRC_DIR}/audio_processing/logging
    ${WEBRTC_AEC3_SRC_DIR}/audio_processing/resampler
    ${WEBRTC_AEC3_SRC_DIR}/audio_processing/utility
    ${WEBRTC_AEC3_SRC_DIR}/rtc_base/experiments
    ${WEBRTC_AEC3_SRC_DIR}/common_audio
    ${WEBRTC_AEC3_SRC_DIR}/common_audio/third_party/ooura/fft_size_128
)

add_library(
    treko_audio
    SHARED
    oboe_audio_engine.cpp
    treko_aec3_core.cpp
    treko_aec3_jni.cpp
)

target_compile_options(treko_audio PRIVATE
    -O3
    -fexceptions
    -frtti
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)

target_link_libraries(
    treko_audio
    PRIVATE
    webrtc_aec3
    ${log-lib}
    ${aaudio-lib}
)

if(ANDROID)
    # Required for Android 15+ 16KB page-size compatibility
    target_link_options(treko_audio PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
endif()
