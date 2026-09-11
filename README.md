# Voice IME

A voice-ONLY Android keyboard (InputMethodService). No keys — you speak, the IME
records 16 kHz mono PCM, sends it as WAV to the Gemini `generateContent` API as
base64 inline audio, and pastes the transcript via clipboard + `commitText`.

## Setup

1. Install the debug APK (built by CI) and open the **Voice IME** app.
2. Enter **Base URL** (default
   `https://generativelanguage.googleapis.com/v1beta`), **API keys** (one per
   line), and **Model** (e.g. `gemini-2.5-flash`), then Save.
3. Tap **Enable keyboard**, turn on Voice IME in system input settings, and
   select it as the current keyboard.
4. Grant microphone permission when prompted.

## Key rotation

Put one API key per line in the keys field. On a request the client tries keys
in round-robin order starting from its cursor; on HTTP 429 it moves to the next
key automatically. After a success the cursor advances to
`(usedIndex + 1) % n`, so load spreads evenly instead of hammering the first
key. All keys 429'd → "Rate limited on all keys — retry later". Non-429 errors
(e.g. 401 bad key) fail immediately without burning the other keys.

## Custom prompt

The **Custom prompt** field (system instructions, max 2000 chars) is appended
to the request's `systemInstruction` under a "User preferences:" heading, so it
shapes every transcription including the Test button tone. Examples: "Add
punctuation and paragraph breaks" (formatting); "Keep Hinglish words as-is,
don't translate them" (Hinglish keep-as-is). Leave it blank to disable.


## Design (M3 Dynamic)

Material 3 dynamic color (wallpaper-derived on Android 12+, static M3 fallback
below) with a live waveform hero as the single memorable element. IME state is
shown by a status dot (outline idle / primary recording / tertiary uploading /
error red) plus a 48-bar `WaveformView` that invalidates only on amplitude
change > 0.02 (~30 fps max, zero per-frame allocation).

## How it works

Voice → `AudioRecorder` (AudioRecord, VOICE_RECOGNITION, 16 kHz mono 16-bit PCM)
→ WAV in memory → `LlmClient` POSTs
`{baseUrl}/models/{model}:generateContent` with
`{contents:[{parts:[{inlineData:{mimeType:audio/wav,data:base64}},{text:…}}]}]}`
→ transcript joined from all candidates' parts → copied to clipboard and
committed with `commitText` (toast: "Copied — tap to paste"). Recording stops
via Stop button or a 5 min auto-stop. Password fields require a second mic tap
to confirm. An Enter button performs the editor action named by
`imeOptions` (label: Go / Search / Send / Next / Done / Previous, else
"Enter"); with no action set it commits a newline. Visible in idle and
error states only.

## Limits

- Recording cap is 5 min. At 16 kHz mono 16-bit (32 KB/s ≈ 1.9 MB/min WAV,
  ×4/3 base64 ≈ 2.6 MB/min JSON) a full 5 min clip is ≈13 MB of request JSON —
  comfortably under the 20 MB inline-audio ceiling.
- Gemini transcription supports up to 1 hour of audio; we stay well inside that.
- Debug APK built by CI — not Play-ready, no release signing.

## Privacy

- Audio lives in memory only and is nulled after each request; nothing is
  written to disk.
- API keys are stored in EncryptedSharedPreferences and are never logged.
- `android:allowBackup=false`; the settings screen sets `FLAG_SECURE`.

## Origin

IME/audio patterns studied from futo-org/android-keyboard
(FUTO Source First License 1.1-kb); all code reimplemented, no verbatim copy.
