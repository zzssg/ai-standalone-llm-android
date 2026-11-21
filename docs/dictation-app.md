# Standalone dictation app: design

Status: **proposal for a separate application**, not a feature of this one.

It lives in this repository because it stands on the same groundwork — whisper.cpp
compiled against ggml, and the CPU-dispatch arrangement worked out for the chat
app. Everything measured in [`voice-input.md`](voice-input.md) about model sizes,
memory and encoder latency applies here unchanged and is restated below so this
note reads on its own.

## What it is

A dictation utility. The user speaks, the phone transcribes locally, and the
text arrives somewhere it can be used:

- **Clipboard.** The transcript is copied, so it can be pasted into any app that
  accepts text.
- **A transcript buffer.** Everything recognised is kept in a list the user can
  scroll, search, select fragments from, and copy out of, so a long dictation
  does not have to be pasted in one piece or lost.
- **Background operation.** Capture continues with the app off-screen, with
  start and stop bound to triggers the user configures.

No network. The manifest declares no `INTERNET` permission, which is the same
guarantee the chat app makes and the reason to build this rather than install
one of the dictation apps that already exist.

## Why a separate app rather than a feature

1. **Different resident set.** The chat app holds an LLM; this one holds only
   whisper, so it can afford `small` or `large-v3-turbo` where the chat app has
   to fit dictation into `APP_OVERHEAD_GB = 0.6` beside a 4B model.
2. **Different permission shape.** An always-available microphone service with
   a persistent notification is a different proposition from a chat app that
   happens to have a mic button, and bundling them means every chat user
   installs the always-listening one.
3. **Different lifetime.** This app is useful while another app is on screen.
   That is the whole point of it, and it is the opposite of how the chat app is
   used.

## The clipboard, and what Android permits

The rules changed twice and both changes matter.

| Android | Rule | Effect here |
| --- | --- | --- |
| 10 (API 29) | Clipboard **reads** are restricted to the focused app and the default IME | Does not block us: we write, we do not read |
| 13 (API 33) | The system shows a preview panel on every clipboard write | Every copy is a visible system event |
| 13 (API 33) | Clipboard is auto-cleared after a period | The buffer, not the clipboard, is the durable store |
| 13 (API 33) | `ClipDescription.EXTRA_IS_SENSITIVE` suppresses the preview contents | Use it for anything the user flags |

Writing with `setPrimaryClip()` from a foreground service is not covered by the
Android 10 read restriction and is expected to work. **Verify it on real
hardware across OEM skins before building anything on top of it** — MIUI,
ColorOS and One UI have each shipped clipboard behaviour of their own, and this
is a single point of failure for the entire product. It belongs in step 1 of
the order of work, not in QA.

The API 33 preview panel forces one design decision immediately:

> **Never copy every segment automatically.** In hands-free mode, a copy per
> utterance means a system panel per utterance — a stream of them across
> whatever the user is actually doing. Automatic copying belongs to
> push-to-talk, where the user asked for exactly one transcript. In background
> mode, segments land in the buffer silently and are copied when tapped.

## The keyboard, and why it is worth adding

The clipboard route always ends with the user pressing paste. There is one
Android mechanism that skips that step: an `InputMethodService` can call
`InputConnection.commitText()` and put text directly into whatever field has
focus, in any app. That is what a keyboard is, and it is the only sanctioned way
to insert text into another app's input.

This is not a replacement for the clipboard design above — the buffer, the
history and the background service are all still wanted, and a keyboard cannot
deliver text to an app that has no text field focused. It is a second delivery
route over the same machinery.

What it costs:

| Piece | Note |
| --- | --- |
| `VoiceInputMethodService` | ~250 lines. Owns an input view, calls the same `AsrEngine` |
| Input view | A mic button, a level meter, a backspace, and a key to leave. Not a full keyboard |
| `method.xml` + manifest entry | `BIND_INPUT_METHOD` permission on the service |
| Enrolment flow | The user has to enable it in system settings and then pick it; this needs walking through in-app |

Two details decide whether it feels good:

- **Return to the previous keyboard after committing.**
  `switchToPreviousInputMethod()` sends the user back to the keyboard they were
  using, so dictation is a detour rather than a mode they have to escape from.
  A dictation-only IME that stays selected leaves the user with no letter keys,
  which reads as the app having broken the phone.
- **An IME is exempt from the clipboard read restriction**, and it is the one
  component that can both read the field it is editing and write into it — so
  "replace the selected text with what I just said" becomes possible, which the
  clipboard route cannot express at all.

Recommended as a second milestone, after the clipboard app works end to end.
It is additive: nothing in the design below has to change to accommodate it.

## Background capture: the rules that actually bind

This is where a naive implementation fails, and one of the failures is silent.

**1. While-in-use microphone access (Android 11+).** A foreground service
started while the app is *not* visible does not get the microphone. `AudioRecord`
does not throw — it **returns buffers of zeros**. A recorder that trusts its own
API contract will happily transcribe silence forever and file empty segments.

The app must detect this itself: compute RMS over the first second of every
session, and if it is exactly zero, stop and tell the user that the system
denied the microphone to a service started in the background. Without this, the
bug reports are unanswerable.

The consequence for design: **the recording session has to be started from a
surface the system considers user-visible.** See the trigger table below for
which ones qualify.

**2. Foreground service type (Android 14+).**

```xml
<service
    android:name=".capture.CaptureService"
    android:exported="false"
    android:foregroundServiceType="microphone" />
```

with `FOREGROUND_SERVICE_MICROPHONE` in the manifest. Missing either is a
crash on start, not a downgrade.

**3. Starting a service from the background (Android 12+).** Independently of
the microphone rule, most background starts throw
`ForegroundServiceStartNotAllowedException`. The exemptions are narrow and
version-dependent.

**4. Doze.** The service needs a `PARTIAL_WAKE_LOCK` around the capture and
inference threads. Do not assume an active `AudioRecord` keeps the CPU awake for
the processing thread — it does not.

**5. OEM battery managers.** Xiaomi, Huawei, Samsung and others kill foreground
services with ongoing notifications anyway. The app should detect that it was
killed mid-session (a session record with no clean end) and offer to send the
user to `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. There is no code fix for
this; there is only telling the user the truth.

**6. Preemption.** A phone call, or another app taking the microphone, silences
the recorder. Register an `AudioManager.AudioRecordingCallback` and treat losing
the mic as a first-class state — pause, mark the gap in the buffer, resume when
it comes back if the user asked for that.

**7. The microphone indicator (Android 12+).** The green dot is always shown
while recording and cannot be suppressed. Design for it being there; an
always-listening app that looked like it was not listening would be the wrong
product.

## Start and stop, configurable

| Trigger | Reach | Gets the mic in background? | Note |
| --- | --- | --- | --- |
| Button in the app | App only | Yes | The baseline. Always works |
| Actions on the ongoing notification | Anywhere | Yes, while the service exists | Needs a service already running — a standby service with no microphone held |
| Quick Settings tile (`TileService`) | One swipe, anywhere | **Verify** | The best reach-to-effort ratio if it qualifies. Test before designing around it |
| Home-screen widget or shortcut | Home screen | Yes — widget interaction is a documented exemption | Reliable fallback if the tile does not qualify |
| Bluetooth headset button (`MediaSession`) | Headset | Yes, session is user-initiated | Grabs media keys globally; will fight with music apps. Opt-in only |
| Headset or SCO connect broadcast | Automatic | **No** — a broadcast receiver is not user-visible | Can arm a notification, cannot start the mic |
| Schedule, charging state, screen off | Automatic | **No** | Same limitation. Useful for *stopping*, not starting |
| Assistant role (`VoiceInteractionService`) | Long-press power | Yes | Heavy; requires being set as the default assistant |
| Accessibility service | Anywhere | — | **Do not.** It is an abuse of the API, it is a store policy violation, and it asks the user for a permission that can read every screen |

The asymmetry is the thing to internalise: **automatic triggers can reliably
stop a session and cannot reliably start one.** Anything that starts the
microphone has to trace back to a user action the system saw.

Stop conditions, all of which are safe to automate and all of which should be
configurable:

- silence for *N* seconds, measured by the VAD rather than by amplitude
- maximum session length
- battery below a threshold
- screen turning on, or off, whichever the user prefers
- another app taking the microphone
- the buffer reaching a size cap

The settings surface is therefore two lists: which triggers may start a session,
and which conditions end one. Defaults should be conservative — tile or widget
to start, five minutes of silence and thirty minutes total to stop.

## The pipeline

```
AudioRecord (16 kHz mono PCM16, VOICE_RECOGNITION)
    |
    v
ring buffer  ~2 s of pre-roll, so the first syllable survives the VAD decision
    |
    v
Silero VAD   per 32 ms frame, ~1 MB ggml model, sub-millisecond
    |
    v
segmenter    min speech 300 ms, trailing silence 700 ms, hard cap 25 s
    |
    v
whisper_full  audio_ctx set from segment length, abort_callback wired
    |
    v
segment      -> buffer store -> { clipboard on demand, notification, history UI }
```

Three points carry the design:

- **The VAD is what makes background operation affordable.** Continuous 16 kHz
  capture costs milliwatts; continuous whisper inference costs a battery. Whisper
  must only ever see audio the VAD has marked as speech.
- **`audio_ctx` from segment length.** Whisper's encoder always processes a
  thirty-second window, padding with silence, so a three-second utterance costs
  what a thirty-second one does unless `whisper_full_params.audio_ctx` is scaled
  — roughly 50 per second of audio, against 1500 for the full window. On `small`
  that is the difference between four to nine seconds and one to two.
- **The pre-roll buffer.** A VAD that triggers on the first voiced frame has
  already lost the consonant before it. Keep a couple of seconds behind the
  decision point and hand the segment to whisper starting before the trigger.

## Model choice and cost

Unlike the chat app, this one has the memory to itself.

| Model | File, q5 | Peak RSS | 30 s window | ~5 s utterance, `audio_ctx` trimmed |
| --- | --- | --- | --- | --- |
| tiny | 31 MB | ~180-250 MB | 0.6-1.5 s | 0.15-0.4 s |
| base | 57 MB | ~250-350 MB | 1.2-3 s | 0.3-0.8 s |
| small | 181 MB | ~500-700 MB | 4-9 s | 1-2.5 s |
| large-v3-turbo | 547 MB | ~1.0-1.3 GB | 15-30 s | 4-8 s |

Latencies are for flagship arm64 with four to six threads and are estimates
until step 2 of the order of work replaces them.

For Russian, `tiny` and `base` produce text that has to be retyped. The floor is
`small`; `large-v3-turbo` is the comfortable choice and is realistic here in a
way it is not in the chat app.

**Residency.** In push-to-talk, free the context after each transcript. In a
background session, keep it resident for as long as the session runs — reloading
`small-q5` per utterance costs a few hundred milliseconds of disk and init every
time, which is the whole latency budget. Holding 700 MB resident for hours on a
6 GB phone is a genuine eviction risk even behind a foreground notification, so
implement `onTrimMemory()`: drop the context on `TRIM_MEMORY_RUNNING_CRITICAL`
and reload lazily rather than being killed outright.

## The transcript buffer

The "special place" is the durable half of the product; the clipboard is only a
transport.

A segment:

```
id            stable, for pinning and dedup
text          the transcript, editable
spokenAt      absolute timestamp
durationMs
source        pushToTalk | background
sessionId     groups a background run, so a session can be deleted as a unit
pinned        survives retention
```

Storage as newline-delimited JSON per day, in `filesDir` — the chat app's
`ChatStore` is the precedent and this needs no more machinery than that. Room
would be justified only once search over months of history matters.

**Selecting fragments.** The cheapest correct answer is Compose's
`SelectionContainer` around the whole list: it gives free-form selection across
segment boundaries and the system's own floating toolbar with Copy, which is the
gesture users already know. Build the bespoke selection UI only if that proves
inadequate — it usually does not.

Around it:

- tap a segment to copy it whole
- long-press to enter multi-select, then copy the selection joined by spaces or
  newlines (configurable — it matters whether the user is dictating prose or a
  list)
- edit in place, because a wrong word is faster to fix here than after pasting
- search, and filter by session or day
- the ongoing notification carries the most recent segment and a Copy action, so
  the common case never requires opening the app

**Retention.** This is a rolling record of everything said near the phone.

- default retention of a few days, with pinned segments exempt
- one-tap purge, reachable without scrolling
- `FLAG_SECURE` on the history screen, so it stays out of screenshots and the
  recents thumbnail
- encrypt the store at rest, keyed through the Android keystore
- an explicit, plain-language explanation on first run of what is being kept and
  for how long

Recording other people has rules that vary by jurisdiction. The app should say
so once, plainly, and not pretend to give legal advice.

## APK size

Unlike the chat app, ggml is not already present and has to be shipped.

| Component | Size |
| --- | --- |
| ggml core plus CPU backend, single baseline | ~2-3 MB |
| ggml with all CPU variants and runtime dispatch | ~6-9 MB |
| whisper.cpp | ~0.3-0.7 MB |
| Silero VAD model | ~1 MB, bundled |
| Compose and the rest of the Kotlin side | ~2-3 MB |

So roughly **5 MB with a single baseline, 10-13 MB with runtime dispatch**, and
no speech model inside either. Runtime dispatch is worth it for the same reason
it was in the chat app: a single baseline either excludes older phones with
`SIGILL` or gives up the instructions that make quantised inference fast on
newer ones. The CMake arrangement carries over directly; see the chat app's
`app/src/main/cpp/CMakeLists.txt` and the `feat(build)` commit behind it.

The speech model is imported by the user through the Storage Access Framework,
the way the chat app's GGUF library works. That keeps `INTERNET` out of the
manifest, which is the strongest form of the privacy claim and the reason this
app exists at all. The cost is a rough first run — the user has to fetch a
`.bin` from Hugging Face on some other machine — and the onboarding screen has
to own that honestly rather than bury it.

## Permissions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<uses-feature android:name="android.hardware.microphone" android:required="true" />
```

No `INTERNET`. No `RECEIVE_BOOT_COMPLETED`: it would let the service start at
boot, but a service started that way cannot hold the microphone, so it would buy
nothing but a permission on the list.

`SYSTEM_ALERT_WINDOW` is worth considering later for a floating mic bubble, and
it also carries a while-in-use exemption — but it is an intrusive permission and
should not be requested until there is a feature that needs it.

## Project layout

```
app/src/main/cpp/
  whisper_wrapper.cpp      JNI: load, transcribe, abort
  CMakeLists.txt           ggml + whisper.cpp, runtime CPU dispatch
  lib/whisper.cpp/         submodule, pinned

app/src/main/java/.../
  asr/                     WhisperBridge (JNI), AsrEngine (coroutines)
  audio/                   AudioRecorder, ring buffer, Vad, Segmenter
  capture/                 CaptureService (FGS), session state, triggers
  clipboard/               the one place setPrimaryClip is called
  data/                    SegmentStore, SettingsStore, ModelStore
  ime/                     VoiceInputMethodService (milestone 2)
  ui/                      history, settings, onboarding
```

`clipboard/` being a single file with a single call site is deliberate: it is
the piece most likely to need per-OEM workarounds, and they should have one
place to live.

## Testing

- **Transcription.** A WAV fixture in `androidTest` assets, greedy decoding,
  fixed expected text. Skips itself when no model is present, the way the chat
  app's `ConversationTest` does.
- **The silent-microphone trap.** Start a session from the background and assert
  the app *detects* zero-amplitude input and reports it, rather than filing empty
  segments. This is the regression test that matters most, because the bug it
  guards produces no error of its own.
- **The segmenter**, as plain unit tests over synthetic audio: speech shorter
  than the minimum, silence shorter than the gap, an utterance past the hard cap.
- **Clipboard writes from the service**, as a manual matrix across at least one
  Pixel, one Samsung and one Xiaomi device. It cannot be automated and it cannot
  be skipped.

## Order of work

1. **Prove the two risky assumptions before writing a UI.** A throwaway build
   that (a) writes to the clipboard from a foreground service on each target OEM
   and (b) starts a `microphone`-type FGS from each candidate trigger and reports
   whether the audio is real or zeros. Everything below depends on the answers.
2. whisper.cpp plus ggml with dispatch, `WhisperBridge`, the WAV fixture test.
   Measure APK size, peak RSS and utterance latency on real hardware.
3. `AudioRecorder`, ring buffer, VAD, segmenter — push-to-talk only, in the
   foreground, transcript to the clipboard. This is a usable app already.
4. `SegmentStore` and the history screen with `SelectionContainer`, the
   notification with its Copy action, retention and purge.
5. `CaptureService`, the trigger and stop-condition settings, preemption and
   kill detection.
6. The IME, as its own milestone over the same engine.

Steps 1 and 3 are the go/no-go points. If clipboard writes from a service turn
out to be unreliable on the OEMs that matter, the IME moves from milestone 6 to
milestone 1 and this becomes a keyboard with a history screen — which is a
perfectly good product, but a different one, and better discovered in week one.

## Open questions

- Does a Quick Settings tile tap satisfy the while-in-use rule? It is the best
  trigger if it does. Answered by step 1.
- Punctuation. Whisper produces it, but dictating into a search field wants it
  suppressed and dictating prose wants it kept. A per-target setting is
  impossible without knowing the target, which is another argument for the IME,
  since an IME does know the field's `inputType`.
- Whether background sessions should transcribe live or defer. Deferring until
  the session ends means one batched run at full throughput; transcribing live
  means the buffer is useful while the session is still going. Live is the
  better product and the worse battery, and the choice should probably be the
  user's.
