# AI Standalone Android

An Android application that runs large language models locally on-device without requiring cloud connectivity.

## Features

- Runs GGUF format LLMs directly on Android devices
- Native C++ implementation for maximum performance
- Chat interface with real-time response generation
- Support for various quantized models
- Optional Vulkan acceleration for compatible devices

## Prerequisites

- Android Studio with NDK support
- CMake 3.22+
- Android device or emulator (API level 24+)
- For Vulkan support: Vulkan SDK and a native compiler (GCC/Clang/MSVC)

## Building the Project

### Standard Build

```bash
./gradlew assembleDebug
```

### Build with Vulkan Support

Vulkan support requires generating shader files on the host machine before building the Android app.

#### Prerequisites for Vulkan Support

You need the following installed on your system:
- **Vulkan SDK** (https://vulkan.lunarg.com/sdk/home)
- **C/C++ compiler**: 
  - **Windows**: Visual Studio with C++ support, or MinGW-w64, or MSYS2
  - **Linux**: GCC or Clang (usually pre-installed)
  - **macOS**: Xcode command line tools

#### Generating Vulkan Shaders

1. Install the Vulkan SDK from https://vulkan.lunarg.com/sdk/home

2. Run the provided script to generate Vulkan shaders:
   ```bash
   # Windows
   generate_vulkan_shaders.bat
   
   # Linux/macOS
   ./generate_vulkan_shaders.sh
   ```

3. If the script fails due to missing compilers, install the appropriate compiler toolchain for your system.

4. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```

The build system will automatically detect the presence of the Vulkan shaders and enable Vulkan support if they're available.

## Testing

### Unit Tests

```bash
./gradlew test
```

### Instrumented Tests

Connect an Android device or start an emulator, then run:

```bash
./gradlew connectedDebugAndroidTest
```

See [TESTING.md](TESTING.md) for detailed testing instructions.

## Model Installation

The app requires a GGUF format model file to be placed in the `app/src/main/assets/models/` directory. By default, it uses the Gemma 270M model.

## License

This project is licensed under the MIT License - see the LICENSE file for details.