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

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# How the CPU backend gets its instruction set: one baseline, or several
# variants chosen on the device.
#
# On for release builds and off for debug; -PllmRuntimeDispatch=0 cuts a release
# back to a single baseline, =1 turns it on for a debug build. See the comment
# on runtimeDispatch in app/build.gradle for why the two differ.
#
# The problem this solves is that the useful ARM instructions are optional. A
# binary built for one baseline either excludes the phones below it -- with
# SIGILL, at the first matmul, not with a clean error -- or gives up the
# instructions that make quantised inference fast on the phones above it.
#
# With dispatch on, ggml compiles the CPU backend once per feature set as a
# CMake MODULE (libggml-cpu-android_*.so), each exporting ggml_backend_score().
# The loader dlopen()s all of them, asks each for its score, and keeps the best:
# a variant returns 0 when the CPU is missing any feature it was built for, so
# the i8mm build is simply not selected on a CPU without i8mm. Scoring reads
# HWCAP and touches none of the kernels, so an unusable variant is loaded and
# rejected without executing an instruction the CPU does not have.
#
# Two things about this are Android-specific and were what made the earlier
# comment here declare it impossible:
#
#   * The default search path is `get_executable_path()`, which on Android is
#     the directory of /proc/self/exe -- /system/bin/app_process64, never the
#     APK's lib/<abi>. ggml_backend_load_all_from_path() takes the directory
#     explicitly, so the app passes its own nativeLibraryDir down through JNI.
#   * GGML_BACKEND_DL requires BUILD_SHARED_LIBS, which AGP leaves off. It is
#     forced on here; llama and ggml are already shared libraries in the APK,
#     so nothing else changes shape.
#
# Without dispatch the CPU backend is a single library beside libggml.so, and
# LLM_CPU_ARCH below picks its one baseline.
if (LLM_RUNTIME_DISPATCH)
    set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
    message(STATUS "llama_wrapper: runtime CPU dispatch, one backend per feature set")
else()
    set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
endif()

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
#
# Ignored under LLM_RUNTIME_DISPATCH: ggml refuses the two together, and a
# single baseline is the thing dispatch exists to avoid.
if (LLM_CPU_ARCH AND NOT LLM_RUNTIME_DISPATCH)
    set(GGML_CPU_ARM_ARCH "${LLM_CPU_ARCH}" CACHE STRING "" FORCE)
    message(STATUS "llama_wrapper: using CPU arch baseline ${LLM_CPU_ARCH}")
elseif (LLM_CPU_ARCH AND LLM_RUNTIME_DISPATCH)
    message(WARNING "llama_wrapper: ignoring LLM_CPU_ARCH=${LLM_CPU_ARCH}; "
                    "runtime dispatch builds every baseline itself")
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
