cmake_minimum_required(VERSION 3.22.1)
project(sublab_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp di-clone ke folder ini oleh workflow (lihat .github/workflows/build-apk.yml)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp tidak ditemukan di ${WHISPER_DIR}. Jalankan: git clone --depth 1 --branch <tag> https://github.com/ggml-org/whisper.cpp ${WHISPER_DIR}")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

# Sama seperti contoh resmi whisper.android: aktifkan fp16 di arm64 untuk kecepatan.
# Matikan dengan -DSUBLAB_ARM_FP16=OFF bila perlu mendukung CPU lama.
option(SUBLAB_ARM_FP16 "Pakai -march=armv8.2-a+fp16 di arm64-v8a" ON)
if(ANDROID_ABI STREQUAL "arm64-v8a" AND SUBLAB_ARM_FP16)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+fp16")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16")
endif()

add_subdirectory(${WHISPER_DIR} whisper_build)

# Deteksi fitur API whisper.cpp pada tag yang dipakai
include(CheckCXXSourceCompiles)
set(CMAKE_REQUIRED_INCLUDES ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
set(CMAKE_REQUIRED_FLAGS "-std=c++17")
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
check_cxx_source_compiles([=[
#include "whisper.h"
int main() { whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  p.vad = true; p.vad_model_path = "x"; p.vad_params.threshold = 0.5f;
  p.vad_params.min_speech_duration_ms = 1; p.vad_params.min_silence_duration_ms = 1; p.vad_params.speech_pad_ms = 1;
  (void)p; return 0; }
]=] SUBLAB_HAS_VAD)
check_cxx_source_compiles([=[
#include "whisper.h"
int main() { whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  p.suppress_nst = true; (void)p; return 0; }
]=] SUBLAB_HAS_SUPPRESS_NST)
unset(CMAKE_REQUIRED_INCLUDES)
unset(CMAKE_REQUIRED_FLAGS)
unset(CMAKE_TRY_COMPILE_TARGET_TYPE)

add_library(sublab_whisper SHARED sublab_whisper.cpp)
target_include_directories(sublab_whisper PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
target_link_libraries(sublab_whisper PRIVATE whisper log android)
if(SUBLAB_HAS_VAD)
    target_compile_definitions(sublab_whisper PRIVATE SUBLAB_HAS_VAD=1)
else()
    message(WARNING "whisper.cpp tag ini belum mendukung VAD: opsi VAD diabaikan.")
endif()
if(SUBLAB_HAS_SUPPRESS_NST)
    target_compile_definitions(sublab_whisper PRIVATE SUBLAB_HAS_SUPPRESS_NST=1)
endif()
# Dukungan perangkat dengan ukuran halaman memori 16 KB (Android 15+)
target_link_options(sublab_whisper PRIVATE -Wl,-z,max-page-size=16384)
