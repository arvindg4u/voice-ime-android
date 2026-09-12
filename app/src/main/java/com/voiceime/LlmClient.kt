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
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when transcription fails; message is safe to show to the user. */
class TranscribeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Sends WAV audio to the right Gemini transport and returns the transcript
 * plus the index of the key that worked, so the caller can advance its
 * cursor to (usedIndex + 1) % n for even spread. Transport routing:
 * live-marker models → BidiGenerateContent WebSocket; dedicated
 * batch-transcription models → Interactions API; everything else →
 * generateContent REST (with runtime fallback when the server reports a
 * transport mismatch). Key rotation lives here: keys are tried in
 * round-robin order from [startIndex]; on HTTP 429 the next key is tried.
 * Non-429 errors fail immediately. The API keys are never logged.
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
        smartMode: Boolean = false,
    ): Pair<String, Int> {
        val keys = normalizeKeys(apiKeys)
        if (keys.isEmpty()) throw TranscribeException("No API key — open Settings")
        if (requiresLive(model)) {
            return liveTranscribe(wav, keys, baseUrl, model, startIndex, smartMode)
        }
        if (isInteractionsModel(model)) {
            return interactionsTranscribe(wav, keys, baseUrl, model, startIndex, customPrompt, smartMode)
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
                if (isDevInstructionError(code, payload)) {
                    debugLog("transcribe", "HTTP 400 rejects developer instruction; switching to Interactions key=$idx")
                    return interactionsTranscribe(wav, keys, baseUrl, model, idx, customPrompt)
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
        smartMode: Boolean = false,
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
            val outcome = liveAttempt(url, keys[idx], model, pcm, smartMode)
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
     * Interactions transport for dedicated batch-transcription models
     * (official docs): POST {base}/interactions with inline WAV. Same
     * rotation contract as the other transports: 429 tries the next key,
     * other errors fail fast. Returns transcript + working key index.
     */
    private fun interactionsTranscribe(
        wav: ByteArray,
        keys: List<String>,
        baseUrl: String,
        model: String,
        startIndex: Int,
        customPrompt: String,
        smartMode: Boolean = false,
    ): Pair<String, Int> {
        val audioB64 = java.util.Base64.getEncoder().encodeToString(wav)
        val body = buildInteractionsRequestJson(audioB64, model, customPrompt, smartMode)
        val url = interactionsUrl(baseUrl)
        val host = try { java.net.URL(baseUrl).host } catch (e: Exception) { baseUrl }
        debugLog(
            "interactions",
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
            debugLog("interactions", "HTTP $code key=$idx payload=${payload.take(2000)}")
            if (code == 429) {
                if (i == keys.lastIndex) {
                    throw TranscribeException("Rate limited on all keys — retry later")
                }
                continue
            }
            if (code < 200 || code >= 300) {
                throw TranscribeException(mapHttpError(code, payload))
            }
            return Pair(parseInteractionsTranscript(payload), idx)
        }
        throw TranscribeException("Rate limited on all keys — retry later")
    }

    /**
     * One Live session on a single key. Bridges the WebSocket callbacks to a
     * coroutine and bounds the whole attempt with [LIVE_TIMEOUT_MS].
     */
    private fun liveAttempt(wsUrl: String, key: String, model: String, pcm: ByteArray, smartMode: Boolean = false): LiveOutcome {
        val deferred = CompletableDeferred<LiveOutcome>()
        liveResult = deferred
        val transcript = StringBuilder()
        @Volatile
        var lastTranscriptMs = 0L
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    webSocket.send(buildLiveSetupJson(model, smartMode))
                    webSocket.send(buildLiveActivityStartJson())
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
                    webSocket.send(buildLiveActivityEndJson())
                    webSocket.send(buildLiveAudioEndJson())
                } catch (e: Exception) {
                    deferred.complete(LiveOutcome.Fail("Network error — check connection", e))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val fragments = parseLiveInputTranscripts(text)
                if (fragments.isNotEmpty()) {
                    if (hasLiveFinalTranscript(text)) {
                        // Final turn text: append (multiple turns accumulate).
                        for (fragment in fragments) transcript.append(fragment)
                    } else {
                        // Interim hypothesis repeats the full text so far:
                        // replace, never append, or one sentence becomes three.
                        transcript.setLength(0)
                        for (fragment in fragments) transcript.append(fragment)
                    }
                    lastTranscriptMs = System.currentTimeMillis()
                }
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
        // Grace closer: the server often keeps the turn open after the last
        // interim (no turnComplete). If transcript arrived but nothing new
        // comes for GRACE_MS, close the socket ourselves — onClosed commits
        // whatever was heard instead of hanging until LIVE_TIMEOUT_MS.
        val graceJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                kotlinx.coroutines.delay(LIVE_GRACE_MS)
                val idleFor = System.currentTimeMillis() - lastTranscriptMs
                if (lastTranscriptMs > 0L && idleFor >= LIVE_GRACE_MS && !deferred.isCompleted) {
                    try {
                        ws.close(1000, "grace")
                    } catch (e: Exception) {
                        // Socket already gone; onClosed/failure will settle it.
                    }
                    return@launch
                }
            }
        }
        try {
            return try {
                runBlocking(Dispatchers.IO) {
                    withTimeout(LIVE_TIMEOUT_MS) { deferred.await() }
                }
            } catch (e: TimeoutCancellationException) {
                // Server never finalized the turn: if interim text arrived,
                // commit it instead of failing — the words were heard.
                val interim = transcript.toString().trim()
                ws.close(1000, "timeout")
                if (interim.isNotEmpty()) {
                    return LiveOutcome.Done(interim)
                }
                throw TranscribeException("Timed out — try again", e)
            } catch (e: TranscribeException) {
                throw e
            } catch (e: CancellationException) {
                throw TranscribeException("cancelled", e)
            }
        } finally {
            try {
                graceJob.cancel()
            } catch (e: Exception) {
                // Watcher already done; nothing to do.
            }
            try {
                ws.close(1000, null)
            } catch (e: Exception) {
                // Already closed; nothing to do.
            }
            if (inFlightWs === ws) inFlightWs = null
            if (liveResult === deferred) liveResult = null
        }
    }

    /** One Live key attempt: success, rotate on 429, or fail with a user-safe message. */
    private sealed interface LiveOutcome {
        data class Done(val text: String) : LiveOutcome
        data object Rotate : LiveOutcome
        data class Fail(val message: String, val cause: Throwable? = null) : LiveOutcome
    }

    override fun toString(): String = "LlmClient(<api key redacted>)"
}
