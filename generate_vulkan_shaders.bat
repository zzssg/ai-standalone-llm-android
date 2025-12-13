@echo off
echo Generating Vulkan shaders for Android LLM app...
echo.

REM Set paths
set PROJECT_DIR=%~dp0
set VULKAN_SHADERS_DIR=%PROJECT_DIR%app\src\main\cpp\lib\llama.cpp\ggml\src\ggml-vulkan\vulkan-shaders
set HOST_BUILD_DIR=%VULKAN_SHADERS_DIR%\build-host
set VULKAN_SDK_PATH=C:\VulkanSDK\1.4.321.1

REM Add Vulkan SDK to PATH if it exists
if exist "%VULKAN_SDK_PATH%\bin" (
    echo Adding Vulkan SDK to PATH...
    set PATH=%VULKAN_SDK_PATH%\bin;%PATH%
)

echo Creating build directory...
mkdir "%HOST_BUILD_DIR%" 2>nul

echo Configuring with CMake...
cmake -S "%VULKAN_SHADERS_DIR%" -B "%HOST_BUILD_DIR%" -DCMAKE_BUILD_TYPE=Release
if %errorlevel% neq 0 (
    echo Error: CMake configuration failed.
    echo This might be because you don't have a C/C++ compiler installed.
    echo.
    echo To fix this issue:
    echo 1. Install Visual Studio with C++ support, or
    echo 2. Install MinGW-w64, or
    echo 3. Install MSYS2 and use its compiler
    echo.
    echo Alternatively, you can skip Vulkan support by building the project normally:
    echo   .\gradlew assembleDebug
    pause
    exit /b 1
)

echo Building Vulkan shader generator...
cmake --build "%HOST_BUILD_DIR%" --config Release
if %errorlevel% neq 0 (
    echo Error: Build failed.
    pause
    exit /b 1
)

echo Running shader generator...
REM Try to find the executable
set SHADER_GEN_EXE=%HOST_BUILD_DIR%\vulkan-shaders-gen.exe
if not exist "%SHADER_GEN_EXE%" (
    set SHADER_GEN_EXE=%HOST_BUILD_DIR%\Release\vulkan-shaders-gen.exe
)

if not exist "%SHADER_GEN_EXE%" (
    echo Error: Could not find vulkan-shaders-gen executable.
    pause
    exit /b 1
)

REM Change to the vulkan-shaders directory and run the generator
pushd "%VULKAN_SHADERS_DIR%"
"%SHADER_GEN_EXE%" --out-dir "%HOST_BUILD_DIR%"
popd
if %errorlevel% neq 0 (
    echo Error: Shader generation failed.
    pause
    exit /b 1
)

echo Copying generated shaders...
copy "%HOST_BUILD_DIR%\ggml-vulkan-shaders.*" "%VULKAN_SHADERS_DIR%\..\" >nul
if %errorlevel% neq 0 (
    echo Error: Failed to copy shader files.
    pause
    exit /b 1
)

echo.
echo Vulkan shaders generated successfully!
echo You can now build the Android project with Vulkan support enabled.
echo.
pause