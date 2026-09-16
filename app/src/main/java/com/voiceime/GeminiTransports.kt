package com.voiceime

import org.json.JSONArray
import org.json.JSONObject

/**
 * App-critical transcription constraints. Sent as systemInstruction on the
 * generateContent path; the Interactions path inlines them into the text part
 * instead (transcribe models reject systemInstruction / developer instruction).
 */
internal const val BASE_SYSTEM =
    "You are a speech transcription assistant. Transcribe the user's speech exactly. " +
        "Output only the transcript, no commentary. If there is no speech, output nothing."

/** Overall bound for one Live session: stream + server turn + close. */
internal const val LIVE_TIMEOUT_MS = 75_000L

/** Grace after the last transcript fragment: close and commit instead of hanging. */
internal const val LIVE_GRACE_MS = 8_000L

/** Window after finish() before the socket self-closes without a server finalize. */
internal const val FINISH_GRACE_MS = 6_000L

/** ~100 ms of 16 kHz 16-bit mono PCM per realtimeInput message. */
internal const val LIVE_CHUNK_BYTES = 3200

/** MIME type of the PCM chunks sent over the Live socket. */
internal const val LIVE_PCM_MIME = "audio/pcm;rate=16000"

internal const val LIVE_RPC_PATH =
    "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
internal const val LIVE_DEFAULT_HOST = "generativelanguage.googleapis.com"

/** Matches the server's "this model is Live-only" 400 body. */
internal val LIVE_ONLY_ERROR =
    Regex("only supports.*bidiGenerateContent|Please use the .*Live API", RegexOption.IGNORE_CASE)

/**
 * Matches the server's "developer instruction unsupported" 400 body, seen on
 * transcribe-family models when systemInstruction is sent. Those models belong
 * on the Interactions API instead.
 */
internal val DEV_INSTRUCTION_ERROR =
    Regex("developer instruction.*not enabled|systemInstruction.*not (supported|enabled)", RegexOption.IGNORE_CASE)

/** Trims, drops blanks, and dedupes keys, keeping the original order. */
internal fun normalizeKeys(apiKeys: List<String>): List<String> =
    apiKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Opt-in Live-API routing: models whose name carries a live/native-audio
 * marker stream over the BidiGenerateContent WebSocket; everything else
 * keeps a REST path. Substring heuristic, so future live model names match
 * without an allowlist update.
 */
internal fun requiresLive(model: String): Boolean {
    val name = model.trim().lowercase(java.util.Locale.ROOT)
    return name.contains("-live") ||
        name.contains("live-preview") ||
        name.contains("native-audio") ||
        name.contains("-dialog")
}

/**
 * Dedicated batch-transcription models (per official docs) belong on the
 * Interactions API, not generateContent and not Live. Exact allowlist —
 * never guess a model onto this transport.
 */
internal fun isInteractionsModel(model: String): Boolean {
    return model.trim().lowercase(java.util.Locale.ROOT) == "gemini-3.5-transcribe"
}

/**
 * Authoritative runtime signal that a model is Live-only: HTTP 400 whose
 * body tells the caller to use the Live (bidiGenerateContent) API.
 */
internal fun isLiveOnlyError(httpCode: Int, body: String): Boolean {
    if (httpCode != 400) return false
    return LIVE_ONLY_ERROR.containsMatchIn(body)
}

/**
 * Runtime signal that the model rejects developer/system instruction: HTTP
 * 400 naming it. Caller retries the same model on the Interactions API,
 * which carries preferences inline instead.
 */
internal fun isDevInstructionError(httpCode: Int, body: String): Boolean {
    if (httpCode != 400) return false
    return DEV_INSTRUCTION_ERROR.containsMatchIn(body)
}

/**
 * Live session setup message for TRANSCRIPTION (official live-transcribe
 * docs): TEXT response modality (AUDIO is for conversational agents),
 * inputAudioTranscription with auto language detection and SMART/VERBATIM
 * mode inside it. Manual VAD (push-to-talk): automatic activity detection
 * is DISABLED because our mic tap / Stop button already marks turn
 * boundaries — with auto-VAD the server keeps waiting for silence and the
 * turn never finalizes. Never carries keys or audio.
 */
internal fun buildLiveSetupJson(model: String, smartMode: Boolean = false): String =
    JSONObject()
        .put(
            "setup",
            JSONObject()
                .put("model", "models/$model")
                .put(
                    "generationConfig",
                    JSONObject().put("responseModalities", JSONArray().put("TEXT")),
                )
                .put(
                    "realtimeInputConfig",
                    JSONObject().put(
                        "automaticActivityDetection",
                        JSONObject().put("disabled", true),
                    ),
                )
                // Raw-socket proven: inputAudioTranscription is a TOP-LEVEL
                // setup field. Nested inside generationConfig the server
                // rejects it outright (Unknown name at
                // setup.generationConfig) and the session stays silent.
                .put(
                    "inputAudioTranscription",
                    JSONObject()
                        .put("languageCodes", JSONArray())
                        .put("mode", if (smartMode) "SMART" else "VERBATIM"),
                ),
        )
        .toString()

/** Live turn-start marker (manual VAD): sent before the first PCM chunk. */
internal fun buildLiveActivityStartJson(): String =
    JSONObject()
        .put("realtimeInput", JSONObject().put("activityStart", JSONObject()))
        .toString()

/** Live turn-end marker (manual VAD): sent after the last PCM chunk. */
internal fun buildLiveActivityEndJson(): String =
    JSONObject()
        .put("realtimeInput", JSONObject().put("activityEnd", JSONObject()))
        .toString()

/** Live audio chunk message: base64 PCM with its 16 kHz mono mime type. */
internal fun buildLiveAudioMessage(pcmChunk: ByteArray): String {
    val data = java.util.Base64.getEncoder().encodeToString(pcmChunk)
    return JSONObject()
        .put(
            "realtimeInput",
            JSONObject()
                .put(
                    "audio",
                    JSONObject()
                        .put("mimeType", LIVE_PCM_MIME)
                        .put("data", data),
                ),
        )
        .toString()
}

/** Live end-of-stream marker sent after the last PCM chunk. */
internal fun buildLiveAudioEndJson(): String =
    JSONObject()
        .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
        .toString()

/**
 * Reads transcription text fragments from one Live server message: FINAL
 * input-transcription first, else interim hypotheses (docs: interim feeds
 * live preview; final is authoritative). Tolerates snake_case variants;
 * empty or invalid JSON yields an empty list and never throws.
 */
internal fun parseLiveInputTranscripts(serverJson: String): List<String> {
    val root = try {
        JSONObject(serverJson)
    } catch (e: Exception) {
        return emptyList()
    }
    val serverContent = root.optJSONObject("serverContent")
        ?: root.optJSONObject("server_content")
        ?: return emptyList()
    val transcription = serverContent.optJSONObject("inputTranscription")
        ?: serverContent.optJSONObject("input_transcription")
    val interim = serverContent.optJSONObject("interimInputTranscription")
        ?: serverContent.optJSONObject("interim_input_transcription")
    // Final wins when present; otherwise take the interim hypothesis so a
    // turn that never finalizes (short clips, VAD wait) still yields text.
    val text = transcription?.optString("text").orEmpty()
        .ifEmpty { interim?.optString("text").orEmpty() }
    if (text.isEmpty()) return emptyList()
    return listOf(text)
}

/**
 * True when a Live server message carries a FINAL input transcription
 * (authoritative). Interim-only messages must REPLACE, not append, the
 * running hypothesis — every interim repeats the full text so far.
 */
internal fun hasLiveFinalTranscript(serverJson: String): Boolean {
    val root = try {
        JSONObject(serverJson)
    } catch (e: Exception) {
        return false
    }
    val serverContent = root.optJSONObject("serverContent")
        ?: root.optJSONObject("server_content")
        ?: return false
    val transcription = serverContent.optJSONObject("inputTranscription")
        ?: serverContent.optJSONObject("input_transcription")
        ?: return false
    return transcription.optString("text").orEmpty().isNotEmpty()
}

/**
 * True when a Live server message acks our setup. The server sends
 * {"setupComplete":{}} before it accepts realtime input; audio sent
 * earlier is dropped. Empty or invalid JSON yields false, never throws.
 */
internal fun isLiveSetupComplete(serverJson: String): Boolean {
    val root = try {
        JSONObject(serverJson)
    } catch (e: Exception) {
        return false
    }
    return root.has("setupComplete") || root.has("setup_complete")
}

/**
 * True when a Live server message ends the turn. Empty or invalid JSON
 * yields false and never throws.
 */
internal fun isLiveTurnComplete(serverJson: String): Boolean {
    val root = try {
        JSONObject(serverJson)
    } catch (e: Exception) {
        return false
    }
    val serverContent = root.optJSONObject("serverContent")
        ?: root.optJSONObject("server_content")
        ?: return false
    if (serverContent.optBoolean("turnComplete", false)) return true
    return serverContent.optBoolean("generationComplete", false)
}

/**
 * Live WebSocket host: default Google host, or the custom baseUrl's host.
 * The raw [LiveSocket] speaks TLS on 443 only; plain-ws test servers are a
 * unit-test concern, not a production path. The key travels as a query
 * parameter and is never logged.
 */
internal fun liveHost(baseUrl: String): String {
    val parsedHost = try {
        java.net.URL(baseUrl).host.orEmpty()
    } catch (e: Exception) {
        ""
    }
    return parsedHost.ifEmpty { LIVE_DEFAULT_HOST }
}

/** Live RPC path with the key attached (key never logged). */
internal fun livePath(baseUrl: String, key: String): String =
    "$LIVE_RPC_PATH?key=${java.net.URLEncoder.encode(key, "UTF-8")}"

/**
 * Strips the WAV header, returning raw 16 kHz 16-bit mono PCM for the
 * Live socket. The app already records in that format; anything else
 * fails fast (no resampler in v1) with a user-safe message.
 */
@Throws(TranscribeException::class)
internal fun wavToMono16kPcm(wav: ByteArray): ByteArray {
    if (wav.size < 12 || tag(wav, 0) != "RIFF" || tag(wav, 8) != "WAVE") {
        throw TranscribeException("Invalid audio — try again")
    }
    var audioFormat = -1
    var channels = -1
    var sampleRate = -1
    var bitsPerSample = -1
    var dataOffset = -1
    var dataLength = -1
    var pos = 12
    while (pos + 8 <= wav.size) {
        val id = tag(wav, pos)
        val size = u32(wav, pos + 4)
        if (size < 0 || size > wav.size || pos + 8 + size > wav.size) {
            throw TranscribeException("Invalid audio — try again")
        }
        when (id) {
            "fmt " -> {
                if (size < 16) throw TranscribeException("Invalid audio — try again")
                audioFormat = u16(wav, pos + 8)
                channels = u16(wav, pos + 10)
                sampleRate = u32(wav, pos + 12)
                bitsPerSample = u16(wav, pos + 22)
            }
            "data" -> {
                dataOffset = pos + 8
                dataLength = size
            }
        }
        pos += 8 + size + (size and 1)
    }
    if (dataOffset < 0 || dataLength < 0) {
        throw TranscribeException("Invalid audio — try again")
    }
    if (audioFormat != 1 || channels != 1 || sampleRate != 16000 || bitsPerSample != 16) {
        throw TranscribeException("Audio format not supported — try again")
    }
    return wav.copyOfRange(dataOffset, dataOffset + dataLength)
}

private fun tag(buf: ByteArray, offset: Int): String =
    String(buf.copyOfRange(offset, offset + 4), Charsets.US_ASCII)

private fun u16(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

private fun u32(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        ((buf[offset + 3].toInt() and 0xFF) shl 24)

/** Builds the Gemini generateContent body: WAV audio as inline data, a text
 * instruction, plus a systemInstruction carrying the app-critical
 * constraints and (when non-blank) the user's custom preferences.
 * The custom prompt is never logged.
 */
internal fun buildGeminiRequestJson(audioB64: String, customPrompt: String = ""): String {
    val trimmed = customPrompt.trim()
    val systemText = if (trimmed.isEmpty()) {
        BASE_SYSTEM
    } else {
        "$BASE_SYSTEM\n\nUser preferences:\n$trimmed"
    }
    return JSONObject()
        .put(
            "systemInstruction",
            JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", systemText)),
            ),
        )
        .put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put(
                        "parts",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "audio/wav")
                                            .put("data", audioB64),
                                    ),
                            )
                            .put(
                                JSONObject()
                                    .put(
                                        "text",
                                        "Transcribe the speech exactly. " +
                                            "Output only the transcript, no commentary.",
                                    ),
                            ),
                    ),
            ),
        )
        .toString()
}

/** Gemini endpoint for a model: {base}/models/{model}:generateContent. */
internal fun geminiUrl(baseUrl: String, model: String): String =
    baseUrl.trimEnd('/') + "/models/${java.net.URLEncoder.encode(model, "UTF-8")}:generateContent"

/**
 * Joins the text of every part of every candidate in a generateContent response.
 * Throws TranscribeException("empty") when nothing was returned.
 */
@Throws(TranscribeException::class)
internal fun parseGeminiTranscript(payload: String): String {
    val sb = StringBuilder()
    val candidates = try {
        JSONObject(payload).optJSONArray("candidates")
    } catch (e: Exception) {
        null
    }
    if (candidates != null) {
        for (c in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(c)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: continue
            for (p in 0 until parts.length()) {
                sb.append(parts.optJSONObject(p)?.optString("text").orEmpty())
            }
        }
    }
    val text = sb.toString().trim()
    if (text.isEmpty()) throw TranscribeException("empty")
    return text
}

/**
 * Builds the Interactions API body for dedicated batch-transcription models
 * (official docs): model + text instruction part + inline WAV part, with
 * generation_config.transcription_config.mode smart/verbatim. No
 * systemInstruction and no timestamps/diarization — transcribe models reject
 * developer instruction (HTTP 400), smart is incompatible with timestamps,
 * and verbatim is the server default. Custom preferences ride inline in the
 * text part.
 */
internal fun buildInteractionsRequestJson(
    audioB64: String,
    model: String,
    customPrompt: String = "",
    smartMode: Boolean = false,
): String {
    val trimmed = customPrompt.trim()
    val instruction = if (trimmed.isEmpty()) {
        "Transcribe the speech exactly. Output only the transcript, no commentary."
    } else {
        "Transcribe the speech exactly. Output only the transcript, no commentary. " +
            "User preferences:\n$trimmed"
    }
    return JSONObject()
        .put("model", model)
        .put(
            "input",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", instruction),
                )
                .put(
                    JSONObject()
                        .put("type", "audio")
                        .put("data", audioB64)
                        .put("mime_type", "audio/wav"),
                ),
        )
        .put(
            "generation_config",
            JSONObject().put(
                "transcription_config",
                JSONObject().put("mode", if (smartMode) "smart" else "verbatim"),
            ),
        )
        .toString()
}

/** Interactions endpoint: {base}/interactions (model travels in the body). */
internal fun interactionsUrl(baseUrl: String): String =
    baseUrl.trimEnd('/') + "/interactions"

/**
 * Reads the transcript from an Interactions API response: `output_text`
 * first, then a walk over steps → model_output content text (with word_info
 * annotations ignored). Throws TranscribeException("empty") when blank.
 */
@Throws(TranscribeException::class)
internal fun parseInteractionsTranscript(payload: String): String {
    val root = try {
        JSONObject(payload)
    } catch (e: Exception) {
        throw TranscribeException("empty")
    }
    val direct = root.optString("output_text").orEmpty().trim()
    if (direct.isNotEmpty()) return direct
    val sb = StringBuilder()
    val steps = root.optJSONArray("steps") ?: JSONArray()
    for (s in 0 until steps.length()) {
        val step = steps.optJSONObject(s) ?: continue
        // Only model output: user_input steps echo our own instruction/audio.
        if (step.optString("type") != "model_output") continue
        val contents = step.optJSONArray("content") ?: continue
        for (c in 0 until contents.length()) {
            val content = contents.optJSONObject(c) ?: continue
            // Live shape omits "type" (bare {"text": ...}); accept text parts
            // unless explicitly typed otherwise.
            val type = content.optString("type", "text")
            if (type == "text" || type.isEmpty()) {
                sb.append(content.optString("text").orEmpty())
            }
        }
    }
    val text = sb.toString().trim()
    if (text.isEmpty()) throw TranscribeException("empty")
    return text
}

/** Maps an HTTP status to a user-safe message, appending server detail when present. */
internal fun mapHttpError(code: Int, body: String): String {
    val base = when (code) {
        401 -> "Check API key in settings"
        404, 405 -> "Model not found or no access — check model"
        429 -> "Rate limited — retry"
        else -> "Server error (HTTP $code)"
    }
    val detail = try {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().trim()
    } catch (e: Exception) {
        ""
    }
    if (detail.isEmpty()) return base
    return "$base — ${detail.take(200)}"
}
