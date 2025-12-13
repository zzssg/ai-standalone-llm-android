#!/bin/bash

echo "Generating Vulkan shaders for Android LLM app..."
echo

# Check if cmake is available
if ! command -v cmake &> /dev/null; then
    echo "Error: CMake not found. Please install CMake and make sure it's in your PATH."
    echo "You can install CMake with your package manager or download it from https://cmake.org/download/"
    exit 1
fi

# Check if compiler is available (try gcc first, then clang)
if command -v gcc &> /dev/null; then
    COMPILER=gcc
elif command -v clang &> /dev/null; then
    COMPILER=clang
else
    echo "Error: No C compiler found. Please install gcc or clang."
    exit 1
fi

# Set paths
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VULKAN_SHADERS_DIR="$PROJECT_DIR/app/src/main/cpp/lib/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders"
HOST_BUILD_DIR="$VULKAN_SHADERS_DIR/build-host"

# Add Vulkan SDK to PATH if it exists (Linux/macOS)
if [ -d "/usr/local/vulkan" ]; then
    export PATH="/usr/local/vulkan/bin:$PATH"
elif [ -d "$HOME/vulkan" ]; then
    export PATH="$HOME/vulkan/bin:$PATH"
fi

echo "Creating build directory..."
mkdir -p "$HOST_BUILD_DIR"

echo "Configuring with CMake..."
cmake -S "$VULKAN_SHADERS_DIR" -B "$HOST_BUILD_DIR" -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=$COMPILER
if [ $? -ne 0 ]; then
    echo "Error: CMake configuration failed."
    exit 1
fi

echo "Building Vulkan shader generator..."
cmake --build "$HOST_BUILD_DIR" --config Release
if [ $? -ne 0 ]; then
    echo "Error: Build failed."
    exit 1
fi

echo "Running shader generator..."
SHADER_GEN_EXE="$HOST_BUILD_DIR/vulkan-shaders-gen"
if [ ! -f "$SHADER_GEN_EXE" ]; then
    echo "Error: Could not find vulkan-shaders-gen executable."
    exit 1
fi

# Run the shader generator from the vulkan-shaders directory
cd "$VULKAN_SHADERS_DIR"
"./$SHADER_GEN_EXE" --out-dir "$HOST_BUILD_DIR"
if [ $? -ne 0 ]; then
    echo "Error: Shader generation failed."
    exit 1
fi

echo "Copying generated shaders..."
cp "$HOST_BUILD_DIR"/ggml-vulkan-shaders.* "$VULKAN_SHADERS_DIR/../" 2>/dev/null
if [ $? -ne 0 ]; then
    echo "Error: Failed to copy shader files."
    exit 1
fi

echo
echo "Vulkan shaders generated successfully!"
echo "You can now build the Android project with Vulkan support enabled."