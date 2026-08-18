# Local LLM Chat for Android

A chat app that runs GGUF language models entirely on the device. No network
permission is declared, so conversations cannot leave the phone.

- llama.cpp inference through a thin JNI bridge
- Streaming replies with a working stop button
- Multi-turn conversations with KV-cache prefix reuse between turns
- The model's own chat template is applied, so instruction-tuned models behave
- A model library: import, switch, delete
- Material 3 UI, dark mode, dynamic colour on Android 12+

## Requirements

Install via the SDK Manager:

| Component | Version |
| --- | --- |
| SDK Platform | API 36 |
| NDK (side by side) | 27.2.12479018 |
| CMake | 3.22.1 |
| Build-Tools | 36.x |

Plus JDK 17 or newer (the JDK bundled with Android Studio is fine).

For running the app, a physical **arm64-v8a** device: the release APK ships that
ABI only. The debug variant additionally builds `x86_64` so the instrumented
tests can run on a desktop emulator — see Testing below.

The NDK version is pinned in `app/build.gradle`. If SDK Manager gave you a
different patch revision, change that one line to match.

## Building

llama.cpp is a git submodule, so clone with it:

```bash
git clone --recurse-submodules <repo>
# or, in an existing clone:
git submodule update --init
```

Then:

```bash
./gradlew assembleDebug
```

**Benchmark with `assembleRelease`, not `assembleDebug`.** The debug variant
compiles ggml without optimisation and is roughly an order of magnitude slower
per token.

### Signed release APK

Release builds are signed when `keystore.properties` exists at the repo root
(it is gitignored, as are `*.jks`/`*.keystore`). Without it the build still
succeeds and produces an unsigned APK.

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Create the keystore once:

```bash
keytool -genkeypair -v -keystore release.jks -alias llmchat         -keyalg RSA -keysize 4096 -validity 10000
```

**Back the keystore up outside the repo.** Losing it means never being able to
update an already-installed build; Android refuses upgrades signed by a
different key.

### Optional: newer CPU baseline

The default build targets the portable `armv8-a` baseline so it runs on every
64-bit device. On a 2018-or-newer SoC, opting in to dotprod and fp16 arithmetic
gives a large speedup on the quantised kernels:

```bash
./gradlew assembleRelease -PllmCpuArch=armv8.2-a+dotprod+fp16
```

Do not ship that build to older devices — they will crash with SIGILL.

## Getting a model

The app ships without a model. Download a `.gguf` file, then use **Add a model**
in the app to import it; the file is copied into app-private storage.

Good starting points are instruction-tuned models in the 1–3 B range at Q4
quantisation. Base (non-instruct) models will produce continuations rather than
answers, because there is no chat template to apply.

## Project layout

```
app/src/main/cpp/
  llama_wrapper.cpp        JNI bridge: load, format, generate, stop
  CMakeLists.txt           ggml/llama.cpp build configuration
  lib/llama.cpp/           vendored llama.cpp (see below)

app/src/main/java/org/zzssg/llmchatapp/
  llm/                     LlamaBridge (JNI) and LlamaEngine (coroutines)
  data/                    model library and settings persistence
  ui/                      ViewModel, theme, screens, components
```

### About llama.cpp

`app/src/main/cpp/lib/llama.cpp` is a git submodule pinned to a release tag, so
the pinned commit is the exact provenance. See
`app/src/main/cpp/lib/LLAMA_CPP_VERSION` for how to bump it and what to check
afterwards — the C API does change between releases.

The submodule is configured `ignore = dirty`: this project never patches
llama.cpp, so working-tree changes inside it are noise. Drop that setting if you
ever need to patch it.

## Testing

```bash
./gradlew test
```

Unit tests cover the pure-Kotlin logic (markdown block parsing, size
formatting).

### Instrumented tests

`ConversationTest` runs the real engine against a real GGUF file and is the
regression suite for the inference bugs — in particular, that the second and
third turns of a conversation stay coherent. It skips itself when no model is
present, so it never fails for the wrong reason.

```bash
adb push <some-model>.gguf /data/local/tmp/test-model.gguf
./gradlew connectedDebugAndroidTest
```

A ~0.5 B instruct model at Q4 is enough; the tests use greedy decoding with a
fixed seed so results are reproducible.

The debug variant builds `x86_64` in addition to `arm64-v8a` precisely so these
can run on a desktop emulator. Release stays `arm64-v8a` only. **Emulator
timings are meaningless** — that ABI is for correctness, never for benchmarking.

Lint runs with `warningsAsErrors`, so anything new fails the build:

```bash
./gradlew lintDebug
```

## GPU acceleration

Vulkan is disabled. Enabling it requires generating ggml's embedded shader
sources with a host-side tool before the Android build, and the shader sources
must then be committed or generated in CI. The previous build wired a Gradle
task for this that ran a host CMake build on every `preBuild` and copied files
from the wrong directory; it has been removed rather than left half-working.

## License

MIT. llama.cpp is MIT-licensed; see `app/src/main/cpp/lib/llama.cpp/LICENSE`.
