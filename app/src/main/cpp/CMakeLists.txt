cmake_minimum_required(VERSION 3.22)

project(llama_android LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Android NDK path (auto-detected by AS)
find_library(log-lib log)

# Set the path to the llama.cpp library
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lib/llama.cpp)

# Disable some features that might cause compilation issues
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile" FORCE)
set(GGML_CUDA OFF CACHE BOOL "Disable CUDA" FORCE)
set(GGML_METAL OFF CACHE BOOL "Disable Metal" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "Disable Vulkan" FORCE)
set(GGML_BLAS OFF CACHE BOOL "Disable BLAS" FORCE)

# Include directories
include_directories(
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/src
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include
    ${LLAMA_CPP_DIR}/ggml/src
)

# Add llama.cpp as a subdirectory with a different name to avoid conflicts
add_subdirectory(${LLAMA_CPP_DIR} llama_cpp_build)

# Create our wrapper library with a different name
add_library(
    llama_wrapper SHARED
    "llama_wrapper.c"
)

# Link libraries
target_link_libraries(
    llama_wrapper
    android
    ${log-lib}
    llama
)

# Ensure proper C++17 support
set_target_properties(llama_wrapper PROPERTIES CXX_STANDARD 17)