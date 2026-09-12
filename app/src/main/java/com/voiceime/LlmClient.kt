package com.voiceime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private var inFlightRaw: LiveSocket? = null

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
     * Cancels the in-flight transcription request, if any. Covers all
     * transports: the REST call, the one-shot Live socket, and any open
     * streaming LiveSession. Failing the Live result first guarantees
     * TranscribeException("cancelled") wins over any socket-teardown error
     * that arrives a moment later.
     */
    fun cancel() {
        inFlight?.cancel()
        liveResult?.completeExceptionally(TranscribeException("cancelled"))
        inFlightRaw?.close(1001, "cancel")
        inFlightRaw = null
        // Never kill a session the commit flow owns; cancelWork() handles it.
        val session = liveSession
        if (session != null && !session.isDetached()) session.close()
    }

    @Volatile
    private var liveSession: LiveSession? = null

    /**
     * Opens a continuous Live transcription session (cookbook pattern):
     * socket stays open while the user speaks; interim hypotheses stream
     * back and [onInterim] replaces the preview text; [onFinal] fires per
     * finalized turn; [onError] fires once on failure. Call [LiveSession.sendPcm]
     * as mic buffers arrive, [LiveSession.finish] on Stop. Caller owns the
     * session lifecycle and must call finish() (or close()) exactly once.
     */
    fun startLiveSession(
        key: String,
        baseUrl: String,
        model: String,
        smartMode: Boolean = false,
        onInterim: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ): LiveSession {
        // Drop any stale alias: the previous session already terminated
        // (its terminal path closed the socket); only the new one is live.
        liveSession?.close()
        val session = LiveSession(baseUrl, model, key, smartMode, onInterim, onFinal, onError, ::debugLog)
        liveSession = session
        session.open()
        return session
    }

    /**
     * Continuous Live transcription session. One instance per mic tap on
     * Live-marker models. Threading: OkHttp callbacks arrive on its threads;
     * listener lambdas are invoked on the calling thread — the service posts
     * to main. All sends are best-effort guarded; terminal callbacks fire
     * at most once.
     */
    class LiveSession internal constructor(
        baseUrl: String,
        private val model: String,
        private val key: String,
        private val smartMode: Boolean,
        private val onInterim: (String) -> Unit,
        private val onFinal: (String) -> Unit,
        private val onError: (String) -> Unit,
        private val log: (String, String) -> Unit,
    ) {
        private val wsHost = liveHost(baseUrl)
        private val wsPath = livePath(baseUrl, key)
        @Volatile private var socket: LiveSocket? = null
        @Volatile private var open = false
        @Volatile private var setupComplete = false
        private val pending = ArrayDeque<ByteArray>()
        @Volatile private var finished = false
        @Volatile private var failed = false
        private val finalParts = StringBuilder()
        /** Completes on first turnComplete or first final fragment. */
        private val finalSignal = CompletableDeferred<Unit>()
        /** Chars of finalParts already delivered via onFinal (delta base). */
        private var deliveredFinalChars = 0
        /** True once the owner handed the session to the commit flow. */
        @Volatile private var detached = false

        fun open() {
            val raw = LiveSocket(
                host = wsHost,
                path = wsPath,
                onText = { text -> onSocketText(text) },
                onClosed = { _, _ -> finishOk() },
                onFailure = { t -> onSocketFailure(t) },
                log = log,
            )
            socket = raw
            // Connect + setup MUST run off the main thread: connect() does
            // DNS + TCP + TLS synchronously and Android throws
            // NetworkOnMainThreadException for that (instant "network error"
            // on mic tap). Mic capture already runs, so early audio buffers
            // in [pending] until setupComplete flushes it.
            Thread({
                try {
                    raw.connect()
                } catch (e: LiveSocketException) {
                    failOnce(e.message ?: "Network error — check connection", e)
                    return@Thread
                } catch (e: Exception) {
                    failOnce("Network error — check connection", e)
                    return@Thread
                }
                try {
                    if (finished || failed) return@Thread
                    // Setup first; audio only after the server's
                    // setupComplete (early realtimeInput is dropped).
                    val setupJson = buildLiveSetupJson(model, smartMode)
                    log("live-wire", "setup bytes=" + setupJson.length)
                    val setupOk = raw.send(setupJson)
                    log("live-wire", "setup send=" + setupOk)
                    if (!setupOk) {
                        failOnce("Network error — check connection")
                        return@Thread
                    }
                    synchronized(pending) { open = true }
                } catch (e: Exception) {
                    failOnce("Network error — check connection", e)
                }
                log("live-session", "open model=$model")
            }, "VoiceImeLiveOpen").apply { isDaemon = true }.start()
        }

        private fun onSocketText(text: String) {
            // Full-reply forensics: server replies carry no secrets
            // (transcripts only), so log them whole while the
            // transcribe-live path is silent in production.
            if (shapeLogged < 6) {
                shapeLogged++
                log("live-rx", "msg#$shapeLogged len=" + text.length + " " + text.take(600))
            }
            logShapeOnce(text)
            if (!setupComplete && isLiveSetupComplete(text)) {
                setupComplete = true
                log("live-session", "setupComplete, starting turn")
                try {
                    if (finished || failed) return
                    val startOk = socket?.send(buildLiveActivityStartJson()) ?: false
                    log("live-wire", "activityStart send=" + startOk)
                    val drain: List<ByteArray>
                    synchronized(pending) {
                        drain = pending.toList()
                        pending.clear()
                    }
                    for (chunk in drain) {
                        if (finished || failed) return
                        val ok = socket?.send(buildLiveAudioMessage(chunk)) ?: false
                        if (!ok) {
                            failOnce("Network error — check connection")
                            return
                        }
                    }
                } catch (e: Exception) {
                    failOnce("Network error — check connection", e)
                }
                return
            }
            val fragments = parseLiveInputTranscripts(text)
            if (fragments.isNotEmpty()) {
                if (hasLiveFinalTranscript(text)) {
                    for (fragment in fragments) finalParts.append(fragment)
                    // Delta only: onFinal already sent the prefix.
                    val delta = finalParts.substring(deliveredFinalChars)
                    deliveredFinalChars = finalParts.length
                    try {
                        onFinal(delta)
                    } catch (e: Exception) {
                        // Listener must never throw into the socket reader.
                    }
                    finalSignal.complete(Unit)
                } else {
                    // Interim repeats the full hypothesis: replace preview.
                    val preview = fragments.joinToString("")
                    try {
                        onInterim(preview)
                    } catch (e: Exception) {
                        // Listener must never throw into the socket reader.
                    }
                }
            }
            if (isLiveTurnComplete(text)) {
                finalSignal.complete(Unit)
                finishOk()
            }
        }

        private fun onSocketFailure(t: Throwable) {
            val message = t.message.orEmpty()
            // Surface real handshake failures; transport drops stay generic
            // and key-free (the key lives in the handshake path, never logs).
            when {
                message.contains("Rate limited") -> failOnce(message)
                message.startsWith("Check API key") ||
                    message.startsWith("Server error (HTTP") -> failOnce(message)
                else -> {
                    val detail = message.substringBefore("?").take(120)
                    failOnce(
                        if (detail.isBlank() || detail == "Network error — check connection") {
                            "Network error — check connection"
                        } else {
                            "Network error — $detail"
                        },
                    )
                }
            }
        }

        /** Streams one raw PCM chunk (any length; 3200B ≈ 100 ms nominal). */
        fun sendPcm(pcmChunk: ByteArray) {
            if (finished || failed) return
            synchronized(pending) {
                if (!open || !setupComplete) {
                    // Handshake or server setup still in flight: buffer
                    // (cap ~30 s) for the setupComplete flush. Never fail
                    // the session for early audio.
                    if (pending.size < 300) pending.addLast(pcmChunk)
                    return
                }
            }
            try {
                val ok = socket?.send(buildLiveAudioMessage(pcmChunk)) ?: false
                if (!ok) failOnce("Network error — check connection")
            } catch (e: Exception) {
                failOnce("Network error — check connection")
            }
        }

        /**
         * Ends the turn (Stop): activityEnd + stream end, then close. The
         * server flushes the final transcript; onFinal fires on arrival and
         * the socket closes via turnComplete/onClosed. Safety net: if the
         * server never finalizes, close the socket ourselves after a grace
         * window so Stop never orphans an open connection.
         */
        /**
         * Sends the turn-end markers. Does NOT close: the owner awaits
         * [awaitFinal] and closes after reading the commit snapshot.
         * Markers go out before any state flip so a fast Stop that races
         * teardown still delivers turn boundaries to the server.
         */
        fun finish() {
            if (finished || failed) return
            try {
                socket?.send(buildLiveActivityEndJson())
                socket?.send(buildLiveAudioEndJson())
            } catch (e: Exception) {
                // Send path dead; close will settle via onFailure/onClosed.
            } finally {
                finished = true
            }
        }

        /**
         * Suspends until the server finalizes (turnComplete, first final, or
         * close-with-text) or [timeoutMs] elapses. Returns true when a signal
         * arrived; false on timeout — the caller then commits from whatever
         * interim/final text already arrived.
         */
        suspend fun awaitFinal(timeoutMs: Long): Boolean {
            return try {
                withTimeout(timeoutMs) { finalSignal.await() }
                true
            } catch (e: TimeoutCancellationException) {
                false
            }
        }

        /** Marks the session handed off to the commit flow (lifecycle must not kill it). */
        fun detach() {
            detached = true
        }

        /** True while the commit flow owns this session. */
        fun isDetached(): Boolean = detached

        /**
         * Abandons the session without a transcript (Cancel / hide / destroy).
         * Always attempts the socket close — even after finish(), which only
         * sends end markers and leaves the socket server-dependent.
         */
        fun close() {
            finished = true
            synchronized(pending) { pending.clear() }
            try {
                socket?.close(1000, "cancel")
            } catch (e: Exception) {
                // Already gone.
            }
            socket = null
        }

        /**
         * One-shot shape discovery (D10): logs the top-level serverContent
         * keys of the first few messages so a device run reveals which field
         * actually carries the transcript. Never logs audio, keys, or text.
         */
        private var shapeLogged = 0

        private fun logShapeOnce(serverJson: String) {
            if (shapeLogged >= 3) return
            shapeLogged++
            val keys = try {
                val sc = org.json.JSONObject(serverJson).optJSONObject("serverContent")
                    ?: org.json.JSONObject(serverJson).optJSONObject("server_content")
                    ?: return
                val names = mutableListOf<String>()
                val it = sc.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    names.add(if (k == "inputTranscription" || k == "input_transcription") "$k(len=${sc.optJSONObject(k)?.optString("text").orEmpty().length})" else k)
                }
                names.sorted().joinToString(",")
            } catch (e: Exception) {
                "unparseable"
            }
            log("live-shape", "msg#$shapeLogged serverContent=[$keys]")
        }

        private fun finishOk() {
            if (finished || failed) return
            finished = true
            synchronized(pending) { pending.clear() }
            try {
                socket?.close(1000, "")
            } catch (e: Exception) {
                // Already closed.
            }
            socket = null
        }

        private fun failOnce(message: String, cause: Throwable? = null) {
            if (finished || failed) return
            failed = true
            synchronized(pending) { pending.clear() }
            try {
                socket?.close(1001, "error")
            } catch (e: Exception) {
                // Already gone.
            }
            socket = null
            try {
                onError(message)
            } catch (e: Exception) {
                // Listener must never throw into OkHttp.
            }
        }
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
        val host = liveHost(baseUrl)
        debugLog(
            "live",
            "RAW host=$host model=$model pcmBytes=${pcm.size} keys=${keys.size} start=$startIndex",
        )

        val start = ((startIndex % keys.size) + keys.size) % keys.size
        for (i in keys.indices) {
            val idx = (start + i) % keys.size
            val outcome = liveAttempt(host, baseUrl, keys[idx], model, pcm, smartMode)
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
    /**
     * One Live session on a single key over the raw socket. Bridges socket
     * callbacks to a coroutine and bounds the whole attempt with
     * [LIVE_TIMEOUT_MS].
     */
    private fun liveAttempt(host: String, baseUrl: String, key: String, model: String, pcm: ByteArray, smartMode: Boolean = false): LiveOutcome {
        val deferred = CompletableDeferred<LiveOutcome>()
        liveResult = deferred
        val transcript = StringBuilder()
        val lastTranscriptMs = java.util.concurrent.atomic.AtomicLong(0L)
        fun completeDone() {
            val text = transcript.toString().trim()
            deferred.complete(
                if (text.isEmpty()) LiveOutcome.Fail("empty")
                else LiveOutcome.Done(text),
            )
        }
        val raw = LiveSocket(
            host = host,
            path = livePath(baseUrl, key),
            onText = { text ->
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
                    lastTranscriptMs.set(System.currentTimeMillis())
                }
                if (isLiveTurnComplete(text)) {
                    inFlightRaw?.close(1000, "")
                    completeDone()
                }
            },
            onClosed = { _, _ -> completeDone() },
            onFailure = { t ->
                val message = t.message.orEmpty()
                val outcome = when {
                    message.contains("Rate limited") -> LiveOutcome.Rotate
                    message.startsWith("Check API key") ||
                        message.startsWith("Server error (HTTP") -> LiveOutcome.Fail(message, t)
                    else -> LiveOutcome.Fail("Network error — check connection", t)
                }
                deferred.complete(outcome)
            },
            log = ::debugLog,
        )
        inFlightRaw = raw
        try {
            raw.connect()
        } catch (e: LiveSocketException) {
            val message = e.message.orEmpty()
            return when {
                message.contains("Rate limited") -> LiveOutcome.Rotate
                else -> LiveOutcome.Fail(if (message.isEmpty()) "Network error — check connection" else message, e)
            }
        } catch (e: Exception) {
            return LiveOutcome.Fail("Network error — check connection", e)
        }
        try {
            if (!raw.send(buildLiveSetupJson(model, smartMode))) {
                return LiveOutcome.Fail("Network error — check connection")
            }
            if (!raw.send(buildLiveActivityStartJson())) {
                return LiveOutcome.Fail("Network error — check connection")
            }
            var offset = 0
            while (offset < pcm.size) {
                if (deferred.isCompleted) break
                val end = minOf(offset + LIVE_CHUNK_BYTES, pcm.size)
                if (!raw.send(buildLiveAudioMessage(pcm.copyOfRange(offset, end)))) {
                    deferred.complete(LiveOutcome.Fail("Network error — check connection"))
                    break
                }
                offset = end
            }
            if (!deferred.isCompleted) raw.send(buildLiveActivityEndJson())
            if (!deferred.isCompleted) raw.send(buildLiveAudioEndJson())
        } catch (e: Exception) {
            deferred.complete(LiveOutcome.Fail("Network error — check connection", e))
        }
        // Grace closer: the server often keeps the turn open after the last
        // interim (no turnComplete). If transcript arrived but nothing new
        // comes for GRACE_MS, close the socket ourselves — onClosed commits
        // whatever was heard instead of hanging until LIVE_TIMEOUT_MS.
        val graceJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(LIVE_GRACE_MS)
                val last = lastTranscriptMs.get()
                val idleFor = System.currentTimeMillis() - last
                if (last > 0L && idleFor >= LIVE_GRACE_MS && !deferred.isCompleted) {
                    try {
                        raw.close(1000, "grace")
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
                raw.close(1000, "timeout")
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
                raw.close(1000, "")
            } catch (e: Exception) {
                // Already closed; nothing to do.
            }
            if (inFlightRaw === raw) inFlightRaw = null
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
