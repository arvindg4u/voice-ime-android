package com.voiceime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when transcription fails; message is safe to show to the user. */
class TranscribeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * App-critical transcription constraints. Always sent as systemInstruction;
 * user preferences are appended below it, never mixed into the user part.
 */
internal const val BASE_SYSTEM =
    "You are a speech transcription assistant. Transcribe the user's speech exactly. " +
        "Output only the transcript, no commentary. If there is no speech, output nothing."

/** Overall bound for one Live session: stream + server turn + close. */
private const val LIVE_TIMEOUT_MS = 75_000L

/** ~100 ms of 16 kHz 16-bit mono PCM per realtimeInput message. */
private const val LIVE_CHUNK_BYTES = 3200

/** MIME type of the PCM chunks sent over the Live socket. */
private const val LIVE_PCM_MIME = "audio/pcm;rate=16000"

private const val LIVE_RPC_PATH =
    "/ws/google.ai.generativelanguage.v1beta/GenerativeService.BidiGenerateContent"
private const val LIVE_DEFAULT_HOST = "generativelanguage.googleapis.com"

/** Matches the server's "this model is Live-only" 400 body. */
private val LIVE_ONLY_ERROR =
    Regex("only supports.*bidiGenerateContent|Please use the .*Live API", RegexOption.IGNORE_CASE)

/**
 * Sends WAV audio to the Gemini generateContent endpoint as base64 inline
 * audio and returns the transcript joined from all candidates' parts.
 * Key rotation lives here: keys are tried in round-robin order from
 * [startIndex]; on HTTP 429 the next key is tried. Non-429 errors fail
 * immediately. The API keys are never logged.
 *
 * Models whose name carries a live marker (see [requiresLive]) instead use
 * the BidiGenerateContent WebSocket transport; a REST 400 that reports
 * [isLiveOnlyError] switches to it at runtime.
 */
class LlmClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var inFlight: Call? = null

    @Volatile
    private var inFlightWs: WebSocket? = null

    @Volatile
    private var liveResult: CompletableDeferred<LiveOutcome>? = null

    /**
     * Transcribes [wav] with the first working key. Returns the transcript
     * plus the index of the key that worked, so the caller can advance its
     * cursor to (usedIndex + 1) % n for even spread.
     */
    @Throws(TranscribeException::class)
    fun transcribe(
        wav: ByteArray,
        apiKeys: List<String>,
        baseUrl: String,
        model: String,
        startIndex: Int = 0,
        customPrompt: String = "",
    ): Pair<String, Int> {
        val keys = normalizeKeys(apiKeys)
        if (keys.isEmpty()) throw TranscribeException("No API key — open Settings")
        if (requiresLive(model)) {
            return liveTranscribe(wav, keys, baseUrl, model, startIndex)
        }
        val audioB64 = java.util.Base64.getEncoder().encodeToString(wav)

        val body = buildGeminiRequestJson(audioB64, customPrompt)
        val url = geminiUrl(baseUrl, model)
        val host = try { java.net.URL(baseUrl).host } catch (e: Exception) { baseUrl }
        debugLog(
            "transcribe",
            "POST url=$host model=$model wavBytes=${wav.size} b64Len=${audioB64.length} " +
                "keys=${keys.size} start=$startIndex",
        )

        val start = ((startIndex % keys.size) + keys.size) % keys.size
        for (i in keys.indices) {
            val idx = (start + i) % keys.size
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", keys[idx])
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val call = http.newCall(request)
            inFlight = call
            val code: Int
            val payload: String
            try {
                val response = call.execute()
                response.use { res ->
                    code = res.code
                    payload = res.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                if (call.isCanceled()) throw TranscribeException("cancelled", e)
                throw TranscribeException("Network error — check connection", e)
            } catch (e: Exception) {
                throw TranscribeException("Transcription failed", e)
            } finally {
                if (inFlight === call) inFlight = null
            }
            debugLog("transcribe", "HTTP $code key=$idx payload=${payload.take(2000)}")
            if (code == 429) {
                if (i == keys.lastIndex) {
                    throw TranscribeException("Rate limited on all keys — retry later")
                }
                continue
            }
            if (code < 200 || code >= 300) {
                if (isLiveOnlyError(code, payload)) {
                    debugLog("transcribe", "HTTP 400 requires Live API; switching transport key=$idx")
                    return liveTranscribe(wav, keys, baseUrl, model, idx)
                }
                throw TranscribeException(mapHttpError(code, payload))
            }
            return Pair(parseGeminiTranscript(payload), idx)
        }
        throw TranscribeException("Rate limited on all keys — retry later")
    }

    /**
     * Cancels the in-flight transcription request, if any. Covers both
     * transports: the REST call and the Live socket. Failing the Live result
     * first guarantees TranscribeException("cancelled") wins over any
     * socket-teardown error that arrives a moment later.
     */
    fun cancel() {
        inFlight?.cancel()
        liveResult?.completeExceptionally(TranscribeException("cancelled"))
        inFlightWs?.close(1001, "cancel")
        inFlightWs = null
    }

    /** Diagnostic log only: never pass apiKey or audio data here. */
    private fun debugLog(tag: String, msg: String) {
        android.util.Log.d("VoiceIme", "$tag: $msg")
    }

    /** Trims, drops blanks, and dedupes keys, keeping the original order. */
    internal fun normalizeKeys(apiKeys: List<String>): List<String> =
        apiKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Opt-in Live-API routing: models whose name carries a live/native-audio
     * marker stream over the BidiGenerateContent WebSocket; everything else
     * keeps the generateContent REST path. Substring heuristic, so future
     * live model names match without an allowlist update.
     */
    internal fun requiresLive(model: String): Boolean {
        val name = model.trim().lowercase(java.util.Locale.ROOT)
        return name.contains("-live") ||
            name.contains("live-preview") ||
            name.contains("native-audio") ||
            name.contains("-dialog")
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
     * Live session setup message: model plus AUDIO response modality with
     * input transcription enabled. Never carries keys or audio.
     */
    internal fun buildLiveSetupJson(model: String): String =
        JSONObject()
            .put(
                "setup",
                JSONObject()
                    .put("model", "models/$model")
                    .put(
                        "generationConfig",
                        JSONObject().put("responseModalities", JSONArray().put("AUDIO")),
                    )
                    .put("inputAudioTranscription", JSONObject()),
            )
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
     * Reads input-transcription text fragments from one Live server message.
     * Tolerates snake_case variants; empty or invalid JSON yields an empty
     * list and never throws.
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
            ?: return emptyList()
        val text = transcription.optString("text").orEmpty()
        if (text.isEmpty()) return emptyList()
        return listOf(text)
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
     * Live WebSocket URL: default Google host, or the custom baseUrl's host
     * (http→ws, https→wss) for proxies. The key travels as a query parameter
     * and is never logged.
     */
    internal fun liveUrl(baseUrl: String): String {
        val parsedHost = try {
            java.net.URL(baseUrl).host.orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (parsedHost.isEmpty() || parsedHost == LIVE_DEFAULT_HOST) {
            return "wss://$LIVE_DEFAULT_HOST$LIVE_RPC_PATH"
        }
        val scheme = try {
            if (java.net.URL(baseUrl).protocol == "http") "ws" else "wss"
        } catch (e: Exception) {
            "wss"
        }
        return "$scheme://$parsedHost$LIVE_RPC_PATH"
    }

    /**
     * Strips the WAV header, returning raw 16 kHz 16-bit mono PCM for the
     * Live socket. The app already records in that format; anything else
     * fails fast (no resampler in v1) with a user-safe message.
     */
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
                    channels = u16(wav, pos + 12)
                    sampleRate = u32(wav, pos + 16)
                    bitsPerSample = u16(wav, pos + 24)
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

    /**
     * Live transport: streams 16 kHz mono PCM over the BidiGenerateContent
     * WebSocket and returns the accumulated input transcription with the
     * index of the key that worked. Same rotation contract as REST: 429
     * tries the next key, 401 fails fast via [mapHttpError].
     */
    private fun liveTranscribe(
        wav: ByteArray,
        keys: List<String>,
        baseUrl: String,
        model: String,
        startIndex: Int,
    ): Pair<String, Int> {
        val pcm = wavToMono16kPcm(wav)
        val url = liveUrl(baseUrl)
        val host = try { java.net.URL(baseUrl).host } catch (e: Exception) { baseUrl }
        debugLog(
            "live",
            "WSS url=$host model=$model pcmBytes=${pcm.size} keys=${keys.size} start=$startIndex",
        )

        val start = ((startIndex % keys.size) + keys.size) % keys.size
        for (i in keys.indices) {
            val idx = (start + i) % keys.size
            val outcome = liveAttempt(url, keys[idx], model, pcm)
            when (outcome) {
                is LiveOutcome.Done -> {
                    debugLog("live", "transcript key=$idx chars=${outcome.text.length}")
                    return Pair(outcome.text, idx)
                }
                LiveOutcome.Rotate -> {
                    debugLog("live", "HTTP 429 key=$idx rotating")
                    if (i == keys.lastIndex) {
                        throw TranscribeException("Rate limited on all keys — retry later")
                    }
                }
                is LiveOutcome.Fail -> throw TranscribeException(outcome.message, outcome.cause)
            }
        }
        throw TranscribeException("Rate limited on all keys — retry later")
    }

    /**
     * One Live session on a single key. Bridges the WebSocket callbacks to a
     * coroutine and bounds the whole attempt with [LIVE_TIMEOUT_MS].
     */
    private fun liveAttempt(wsUrl: String, key: String, model: String, pcm: ByteArray): LiveOutcome {
        val deferred = CompletableDeferred<LiveOutcome>()
        liveResult = deferred
        val transcript = StringBuilder()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    webSocket.send(buildLiveSetupJson(model))
                    var offset = 0
                    while (offset < pcm.size) {
                        val end = minOf(offset + LIVE_CHUNK_BYTES, pcm.size)
                        val sent = webSocket.send(buildLiveAudioMessage(pcm.copyOfRange(offset, end)))
                        if (!sent) {
                            deferred.complete(LiveOutcome.Fail("Network error — check connection"))
                            return
                        }
                        offset = end
                    }
                    webSocket.send(buildLiveAudioEndJson())
                } catch (e: Exception) {
                    deferred.complete(LiveOutcome.Fail("Network error — check connection", e))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (fragment in parseLiveInputTranscripts(text)) transcript.append(fragment)
                if (isLiveTurnComplete(text)) {
                    webSocket.close(1000, null)
                    completeDone()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                completeDone()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                val outcome = when {
                    code == 429 -> LiveOutcome.Rotate
                    code == 401 || code == 403 -> {
                        val body = try {
                            response?.body?.string().orEmpty()
                        } catch (e: Exception) {
                            ""
                        }
                        LiveOutcome.Fail(mapHttpError(code, body))
                    }
                    else -> LiveOutcome.Fail("Network error — check connection", t)
                }
                deferred.complete(outcome)
            }

            private fun completeDone() {
                val text = transcript.toString().trim()
                deferred.complete(
                    if (text.isEmpty()) LiveOutcome.Fail("empty")
                    else LiveOutcome.Done(text),
                )
            }
        }
        val ws = http.newWebSocket(Request.Builder().url("$wsUrl?key=$key").build(), listener)
        inFlightWs = ws
        try {
            return try {
                runBlocking(Dispatchers.IO) {
                    withTimeout(LIVE_TIMEOUT_MS) { deferred.await() }
                }
            } catch (e: TimeoutCancellationException) {
                ws.close(1000, "timeout")
                throw TranscribeException("Timed out — try again", e)
            } catch (e: TranscribeException) {
                throw e
            } catch (e: CancellationException) {
                throw TranscribeException("cancelled", e)
            }
        } finally {
            try {
                ws.close(1000, null)
            } catch (e: Exception) {
                // Already closed; nothing to do.
            }
            if (inFlightWs === ws) inFlightWs = null
            if (liveResult === deferred) liveResult = null
        }
    }

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
        baseUrl.trimEnd('/') + "/models/$model:generateContent"

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

    /** One Live key attempt: success, rotate on 429, or fail with a user-safe message. */
    private sealed interface LiveOutcome {
        data class Done(val text: String) : LiveOutcome
        data object Rotate : LiveOutcome
        data class Fail(val message: String, val cause: Throwable? = null) : LiveOutcome
    }

    override fun toString(): String = "LlmClient(<api key redacted>)"
}
