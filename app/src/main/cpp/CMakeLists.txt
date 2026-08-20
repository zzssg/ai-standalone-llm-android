cmake_minimum_required(VERSION 3.22)

project(llama_android LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lib/llama.cpp)

# --- llama.cpp / ggml configuration -------------------------------------------
# Keep the build lean: no tools, tests or examples in the APK build.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
# LLAMA_CURL is deprecated upstream; networking is pulled in via these instead,
# and none of them belong in an app that declares no INTERNET permission.
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)

# Backends we do not ship on Android.
set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CUDA      OFF CACHE BOOL "" FORCE)
set(GGML_METAL     OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN    OFF CACHE BOOL "" FORCE)
set(GGML_BLAS      OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL    OFF CACHE BOOL "" FORCE)

# IMPORTANT: the CPU backend must be linked directly into libllama.so.
#
# GGML_BACKEND_DL builds each backend as a CMake MODULE (libggml-cpu-*.so) that is
# discovered at runtime with dlopen() from `get_executable_path()`. On Android that
# path resolves to the dir of /proc/self/exe, i.e. /system/bin/app_process64 --
# never the APK's lib/<abi> dir -- so ggml_backend_load_all() finds nothing and the
# app ends up with zero compute backends. GGML_BACKEND_DL also hard-requires
# BUILD_SHARED_LIBS, which AGP leaves OFF, so the configure step fails outright.
# GGML_CPU_ALL_VARIANTS in turn requires GGML_BACKEND_DL, so both must stay OFF.
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)

# Optional CPU feature baseline.
#
# `armv8.2-a+dotprod+fp16` roughly doubles throughput on Q4_K kernels, but those
# instructions are NOT universal on arm64 -- pre-2018 SoCs (Cortex-A53/A57, e.g.
# Snapdragon 835) will SIGILL. With minSdk 24 we keep the portable armv8-a
# baseline by default and let the build opt in explicitly:
#
#   ./gradlew assembleRelease -PllmCpuArch=armv8.2-a+dotprod+fp16
#
# (see android.defaultConfig.externalNativeBuild.cmake.arguments in app/build.gradle)
if (LLM_CPU_ARCH)
    set(GGML_CPU_ARM_ARCH "${LLM_CPU_ARCH}" CACHE STRING "" FORCE)
    message(STATUS "llama_wrapper: using CPU arch baseline ${LLM_CPU_ARCH}")
endif()

add_subdirectory(${LLAMA_CPP_DIR} llama_cpp_build)

add_library(llama_wrapper SHARED llama_wrapper.cpp)

target_include_directories(llama_wrapper PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
    # Multi-token prediction lives in src/llama-ext.h, a staging header that
    # upstream explicitly marks WIP ("breaking changes and C++ are allowed").
    # Its symbols are exported from libllama.so, but the header is not published
    # under include/, so the source tree has to be on the include path.
    # Re-check this after every llama.cpp bump.
    ${LLAMA_CPP_DIR}/src
)

target_link_libraries(llama_wrapper
    android
    ${log-lib}
    llama
)

set_target_properties(llama_wrapper PROPERTIES CXX_STANDARD 17)
