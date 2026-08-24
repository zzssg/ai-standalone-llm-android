# Voice input: evaluation

Status: **proposal, nothing implemented.** This is the design note written
before any code, so that the three numbers that decide the feature get measured
before the UI gets built.

The goal is dictation into the composer: the user holds a button, speaks, and
the transcript lands in the text field for editing. Fully on-device, with no
weakening of the guarantee the app currently makes about the network.

## Summary

whisper.cpp is close to free here, because ggml is already in the tree. Every
other engine brings a second tensor runtime into an APK that is 12 MB precisely
because of ggml.

Provided the speech model is imported by the user the way GGUF models already
are -- rather than bundled -- the APK grows by roughly half a megabyte. The
real costs are memory and latency, and both are manageable but neither is free.

## Choosing an engine

| Option | Native code added | On-device | Russian | Note |
| --- | --- | --- | --- | --- |
| whisper.cpp on the existing ggml | ~0.3-0.7 MB | yes | small and up | Reuses the `libggml*.so` already shipped |
| whisper.cpp as an independent submodule | +6-8 MB | yes | -- | Duplicates ggml and collides on CMake target names. Not an option; see below |
| sherpa-onnx (Moonshine, Zipformer) | +15-20 MB | yes | weak | Faster on short utterances, but a second runtime |
| Vosk | +10 MB AAR | yes | 45 MB model | No punctuation or casing, noticeably lower accuracy |
| `SpeechRecognizer.createOnDeviceSpeechRecognizer()` | 0 | **not guaranteed** | device-dependent | Rejected; see below |

The platform recogniser deserves an explicit rejection rather than a silent
one, because on size alone it wins. It runs in another process -- one that does
hold `INTERNET`. `EXTRA_PREFER_OFFLINE` exists, but the app would be trading a
guarantee it can state in its manifest for a hint it cannot verify, and the
README currently claims the strong version of that guarantee. It is also absent
on devices without GMS, and its language packs are downloaded separately, so
the feature would be present or missing depending on the phone.

whisper.cpp keeps the manifest claim intact.

## Building it against the ggml already here

Do not `add_subdirectory()` whisper.cpp. It vendors its own ggml, and the
`ggml`, `ggml-base` and `ggml-cpu` targets would collide with the ones already
defined by `llama_cpp_build`. Compile its sources into a target of our own
instead:

```cmake
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lib/whisper.cpp)

add_library(whisper_wrapper SHARED
    whisper_wrapper.cpp
    ${WHISPER_DIR}/src/whisper.cpp
)
target_include_directories(whisper_wrapper PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${LLAMA_CPP_DIR}/ggml/include
)
target_link_libraries(whisper_wrapper android ${log-lib} ggml)
```

A separate `.so` rather than more sources in `llama_wrapper`, so that
`System.loadLibrary("whisper_wrapper")` can happen on the first tap of the
microphone. A phone whose owner never dictates never maps the code.

The runtime-dispatch work already done pays for this one too. The ggml backend
registry is process-global, so the `ggml_backend_load_all_from_path()` call
that `llama_wrapper` makes with the app's `nativeLibraryDir` also serves
whisper: the i8mm CPU variant gets selected for speech recognition without a
line of extra code. That holds only while both libraries link the *same*
`libggml-base.so`, which is exactly what the arrangement above guarantees and
what a second ggml would destroy.

## APK size

Baseline: 12.0 MB (`dist/LocalLLMChat-3.4.1-arm64-universal.apk`).

- whisper.cpp compiled for arm64: **+0.3-0.7 MB**, estimated. It is roughly ten
  thousand lines and ggml is not duplicated. This estimate is the first thing
  step 1 below must replace with a measurement.
- `AudioRecord`, the Kotlin layer and the UI: +20-50 KB.
- The model does **not** belong in the APK. `small-q5_1` alone is 181 MB.

The app is already built around bring-your-own-model through the Storage Access
Framework, and the speech model should arrive the same way. Bundling even
`tiny-q5_1` would mean a 43 MB APK in exchange for Russian transcription not
worth reading, and assets cannot be handed to `whisper_init_from_file` -- the
file would have to be copied into `filesDir` on first run, spending the disk
twice and the code once.

## Memory

Peak resident set during transcription -- weights plus cross-attention KV plus
mel plus graphs -- for quantised models:

| Model | File, q5 | Peak RSS |
| --- | --- | --- |
| tiny | 31 MB | ~180-250 MB |
| base | 57 MB | ~250-350 MB |
| small | 181 MB | ~500-700 MB |
| large-v3-turbo | 547 MB | ~1.0-1.3 GB |

This is the dangerous part of the plan. `ModelSizeAdvice` budgets
`APP_OVERHEAD_GB = 0.6` for everything the app holds outside the model weights.
Adding 600 MB on top of a loaded 4B model overruns that by a factor of six, and
on an 8 GB phone the likely outcome is the low-memory killer taking the process
-- after the user has already spoken the sentence.

Three rules follow:

1. **Free the whisper context as soon as the transcript is produced.** Loading
   `small-q5` from disk costs a fraction of a second, which is cheaper than
   being killed. Keeping it alive for a short while, to serve a run of
   consecutive utterances, is an optimisation to add later and measure.
2. Teach `ModelSizeAdvice` about it -- either raise `APP_OVERHEAD_GB` while a
   speech model is selected, or refuse to offer `small` on a phone whose LLM
   already consumes the budget.
3. Never hold whisper and a generating LLM in memory at the same time.

## Latency

The detail that decides the feature: **Whisper's encoder always processes a
30-second window**, padding with silence. By default a three-second utterance
costs exactly what a thirty-second one does.

Flagship arm64, four to six threads:

| Model | Full 30 s window | `audio_ctx` trimmed to a ~5 s utterance |
| --- | --- | --- |
| tiny q5 | 0.6-1.5 s | 0.15-0.4 s |
| base q5 | 1.2-3 s | 0.3-0.8 s |
| small q5 | 4-9 s | 1-2.5 s |
| large-v3-turbo q5 | 15-30 s | 4-8 s |

So two things are not optional:

- **Set `whisper_full_params.audio_ctx` from the length of the recording.**
  1500 is the full 30 seconds, so roughly 50 per second of audio plus margin.
  This is the only lever that turns "wait six seconds after a short phrase"
  into something usable. It costs a little accuracy at the window edge, which
  for push-to-talk does not matter.
- **Voice activity detection**, to trim the silence at both ends. whisper.cpp
  ships Silero VAD support and the ggml model is about 1 MB -- small enough to
  bundle, unlike the speech model itself.

The decoder's share is proportional to the number of tokens emitted and is
minor next to the encoder.

There is no contention with the LLM: transcription finishes before the user
sends the message. Thermal throttling is shared, though, so a long session of
back-to-back generation will make dictation slower than the table says.

`whisper_full_params.abort_callback` must be wired up, so that cancelling a
transcription is as honest as the existing `nativeStop`.

## Russian

This is where the size picture changes. `tiny` and `base` produce Russian that
has to be retyped, which makes the feature pointless for it. The practical
floor is **small (181 MB, q5_1)** and the comfortable choice is
**large-v3-turbo (547 MB, q5_0)**.

For a Russian-speaking user the feature therefore costs 181-547 MB on disk and
0.5-1.3 GB of RAM per utterance, not 31 MB and 250 MB. Another argument for
importing the model rather than shipping it, and for freeing the context after
every transcript.

## What has to be written

The layering mirrors what already exists.

| File | Mirrors | Size |
| --- | --- | --- |
| `cpp/whisper_wrapper.cpp` | `llama_wrapper.cpp` | ~300-400 lines |
| `llm/WhisperBridge.kt` | `LlamaBridge.kt`: top-level `external fun`, `@file:JvmName` | ~60 |
| `llm/AsrEngine.kt` | `LlamaEngine.kt` | ~150 |
| `audio/VoiceRecorder.kt` | nothing yet | ~120 |
| `data/AsrModelStore.kt` | `ModelStore` filters `.gguf`; whisper models are `.bin`, so the extension has to become a parameter or the store has to split | ~40, as a refactor |
| Microphone in `Composer`, states in `ChatUiState` | `ChatScreen.kt:315` | ~150 |
| `SettingsSheet`: model and language | | ~80 |
| `proguard-rules.pro` | the `TokenSink` block is the exact template | ~10 |
| Instrumented test: WAV asset in, expected transcript out | `ExtraModelTest.kt` | ~80 |

Capture is `AudioRecord` with `MediaRecorder.AudioSource.VOICE_RECOGNITION`, 16
kHz mono PCM16, normalised to float32. No resampling: every device provides 16
kHz.

## Manifest

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-feature android:name="android.hardware.microphone" android:required="false" />
```

`FOREGROUND_SERVICE_MICROPHONE` is not needed as long as recording only happens
in the foreground, and push-to-talk should be designed so that it stays that
way. `RECORD_AUDIO` is a dangerous permission with a runtime prompt; a refusal
needs handling as careful as a missing model gets.

The comment in the manifest that explains why the permission list is short will
need extending, and that is the good news: "there is a microphone and there is
still no INTERNET" is a stronger sentence than the one there now.

## Interaction

- Microphone in the `Composer`, beside `Send`, or in its place while `draft` is
  empty.
- States: idle, recording with a level meter, transcribing with progress,
  transcript inserted.
- **Never send automatically.** The transcript always lands in the
  `OutlinedTextField` to be edited. At the accuracy small models reach on
  Russian this is a requirement, not a preference.
- Insert at the cursor rather than replacing whatever draft is there.

## Risks

1. **ggml drift.** whisper.cpp is pinned to its own ggml sync; the one from
   llama.cpp is normally newer. They are usually compatible, but a renamed or
   re-specified op would break whisper silently, at runtime, not at compile
   time. Pin whisper.cpp to a commit verified against the current llama.cpp,
   and keep an instrumented transcription test that fails when the submodule
   moves. This is the price of sharing ggml and it should be paid knowingly.
2. `BUILD_SHARED_LIBS ON` is already forced in release for `GGML_BACKEND_DL`.
   `whisper_wrapper` has to link correctly both ways: debug without dispatch,
   release with it.
3. Lint runs with `warningsAsErrors`. The new permission and `AudioRecord` will
   raise something; it has to be fixed rather than suppressed.
4. Build time: one more large translation unit, not measurable in minutes.

## Order of work

1. Submodule and CMake glue. Confirm the `.so` builds and that ggml is not
   duplicated. **Measure the APK delta** -- this is the first checkpoint, and
   the work continues only if it lands near half a megabyte.
2. `whisper_wrapper.cpp`, `WhisperBridge`, and the instrumented test against a
   fixed WAV. Measure peak RSS and wall time for tiny, base and small on real
   hardware.
3. `VoiceRecorder`, the permission, `audio_ctx` from recording length, VAD.
4. UI: microphone, states, insertion.
5. Settings, model import through the existing SAF flow, `ModelSizeAdvice`.

Three numbers have to come out of steps 1 and 2 before any UI is written: the
APK delta, peak RSS for `small-q5`, and the time to transcribe a five-second
utterance with `audio_ctx` trimmed. If the third does not fit inside about two
seconds on target hardware, the feature ships limited to `base` for English,
and says plainly in the UI that Russian needs `small` and patience.
