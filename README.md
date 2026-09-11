# Voice IME

A voice-ONLY Android keyboard (InputMethodService). No keys — you speak, the IME
records 16 kHz mono PCM, sends it as WAV to an OpenAI-compatible
`/chat/completions` endpoint as base64 `input_audio`, and pastes the transcript
via clipboard + `commitText`.

## Setup

1. Install the debug APK (built by CI) and open the **Voice IME** app.
2. Enter **Base URL** (default `https://api.openai.com/v1`), **API key**, and
   **Model** (an OpenAI audio model, e.g. `gpt-4o-mini-audio-preview`), then Save.
3. Tap **Enable keyboard**, turn on Voice IME in system input settings, and
   select it as the current keyboard.
4. Grant microphone permission when prompted.

## How it works

Voice → `AudioRecorder` (AudioRecord, VOICE_RECOGNITION, 16 kHz mono 16-bit PCM)
→ WAV in memory → `LlmClient` POSTs `{baseUrl}/chat/completions` with
`{model, modalities:[text], messages:[{role:user, content:[{type:text,…},
{type:input_audio,input_audio:{data:base64,format:wav}}]}]}` → transcript from
`choices[0].message.content` → copied to clipboard and committed with
`commitText` (toast: "Copied — tap to paste"). Recording stops via Stop button
or a 60 s auto-stop. Password fields require a second mic tap to confirm.

## Limits

- Keep clips under ~20 seconds for best results; hard cap is 60 s.
- OpenAI audio-capable chat models only (e.g. `gpt-4o-mini-audio-preview`,
  `gpt-4o-audio-preview`); plain text models return 404/405-style errors.
- Debug APK built by CI — not Play-ready, no release signing.

## Privacy

- Audio lives in memory only and is nulled after each request; nothing is
  written to disk.
- The API key is stored in EncryptedSharedPreferences and is never logged.
- `android:allowBackup=false`; the settings screen sets `FLAG_SECURE`.

## Origin

IME/audio patterns studied from futo-org/android-keyboard
(FUTO Source First License 1.1-kb); all code reimplemented, no verbatim copy.
