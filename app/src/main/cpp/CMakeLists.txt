cmake_minimum_required(VERSION 3.22.1)

project("liteai" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# 16 KB page size alignment (required for Android 15+ / Google Play from Nov 2025)
add_link_options(-Wl,-z,max-page-size=16384)

# --------------------------------------------------------------------------
# Per-ABI ggml configuration.
#   M0 goal: a clean, reproducible build. KleidiAI is OFF (it FetchContent-
#   downloads ARM kernels at configure time).
# --------------------------------------------------------------------------
if(DEFINED ANDROID_ABI)
    message(STATUS "liteai: Android ABI = ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        # Pixel Watch 4 / Wear OS runs a 32-bit userspace. NEON only; no KleidiAI.
        # 32-bit ARM NEON lacks the fp16 vector intrinsics (vld1_f16, ...) used by
        # the llamafile sgemm path, so disable it here.
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP ON)
        set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "liteai: unsupported ABI ${ANDROID_ABI}")
    endif()
endif()

# Build llama.cpp as a subproject. Tests/examples/tools/server stay off by
# default (LLAMA_STANDALONE is false when added via add_subdirectory).
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        llama-android.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)