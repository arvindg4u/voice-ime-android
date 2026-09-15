package com.voiceime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
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
    suspend fun transcribe(
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

        val start = rotationStart(startIndex, keys.size)
        for (i in keys.indices) {
            val idx = (start + i) % keys.size
            val (code, payload) = executeRestCall(url, keys[idx], body)
            debugLog("transcribe", "HTTP $code key=$idx bytes=${payload.length}")
            if (rotateOn429(code, i, keys.size)) continue
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

    /** Normalizes [startIndex] into a valid rotation offset for [keyCount] keys. */
    private fun rotationStart(startIndex: Int, keyCount: Int): Int =
        ((startIndex % keyCount) + keyCount) % keyCount

    /**
     * True when the caller should try the next key (HTTP 429 with keys left);
     * throws when every key is rate-limited. False for non-429 codes.
     */
    private fun rotateOn429(code: Int, attempt: Int, keyCount: Int): Boolean {
        if (code != 429) return false
        if (attempt == keyCount - 1) {
            throw TranscribeException("Rate limited on all keys — retry later")
        }
        return true
    }

    /**
     * Executes one REST attempt on [key], enforcing Dispatchers.IO inside the
     * client so callers can invoke from any dispatcher (including Main).
     * Returns the HTTP code plus body; never converts cancellation.
     */
    private suspend fun executeRestCall(url: String, key: String, body: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(request)
        inFlight = call
        try {
            return withContext(Dispatchers.IO) {
                val response = call.execute()
                response.use { res ->
                    Pair(res.code, res.body?.string().orEmpty())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (call.isCanceled()) throw TranscribeException("cancelled", e)
            throw TranscribeException("Network error — check connection", e)
        } catch (e: Exception) {
            throw TranscribeException("Transcription failed", e)
        } finally {
            if (inFlight === call) inFlight = null
        }
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
        session.openAsync()
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
        private val finals = FinalAccumulator()
        /** Completes on first turnComplete or first final fragment. */
        private val finalSignal = CompletableDeferred<Unit>()
        /** True once the owner handed the session to the commit flow. */
        @Volatile private var detached = false
        /**
         * Supervisor-owned scope for the one-shot connect task: a connect
         * failure must never cancel sibling client work, and the scope is
         * cancelled on session teardown so a late connect can't resurrect it.
         */
        private val openScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Connects the socket in the background: connect() does DNS + TCP +
         * TLS synchronously and Android throws NetworkOnMainThreadException
         * for that on mic tap. Mic capture already runs, so early audio
         * buffers in [pending] until setupComplete flushes it.
         */
        fun openAsync() {
            val raw = LiveSocket(
                host = wsHost,
                path = wsPath,
                onText = { text -> onSocketText(text) },
                onClosed = { _, _ -> finishOk() },
                onFailure = { t -> onSocketFailure(t) },
                log = log,
            )
            socket = raw
            openScope.launch {
                try {
                    openSocket(raw)
                } catch (e: CancellationException) {
                    // Scope cancelled during teardown: propagate, never convert.
                    throw e
                } catch (e: Exception) {
                    val message = e.message.orEmpty()
                    failOnce(if (message.isBlank()) "Network error — check connection" else message, e)
                }
            }
        }

        /**
         * Blocking connect + setup on Dispatchers.IO. Throws on failure so
         * the caller's catch reports it via [failOnce].
         */
        private suspend fun openSocket(raw: LiveSocket) {
            withContext(Dispatchers.IO) {
                try {
                    raw.connect()
                } catch (e: LiveSocketException) {
                    throw IOException(e.message ?: "Network error — check connection", e)
                }
            }
            if (finished || failed) return
            // Setup first; audio only after the server's
            // setupComplete (early realtimeInput is dropped).
            val setupJson = buildLiveSetupJson(model, smartMode)
            log("live-wire", "setup bytes=" + setupJson.length)
            val setupOk = raw.send(setupJson)
            log("live-wire", "setup send=" + setupOk)
            if (!setupOk) {
                failOnce("Network error — check connection")
                return
            }
            synchronized(pending) { open = true }
            log("live-session", "open model=$model")
        }

        private fun onSocketText(text: String) {
            // Length/shape only: server replies carry transcripts, which never hit logcat.
            if (shapeLogged < 6) {
                shapeLogged++
                log("live-rx", "msg#$shapeLogged len=" + text.length)
            }
            logShapeOnce(text)
            if (!setupComplete && isLiveSetupComplete(text)) {
                handleSetupComplete()
                return
            }
            val fragments = parseLiveInputTranscripts(text)
            if (fragments.isNotEmpty()) {
                if (hasLiveFinalTranscript(text)) {
                    // Overlap-aware: a cumulative server resend collapses
                    // instead of duplicating; empty deltas never fire.
                    val delta = finals.append(fragments.joinToString(""))
                    if (delta.isNotEmpty()) {
                        try {
                            onFinal(delta)
                        } catch (e: Exception) {
                            // Listener must never throw into the socket reader.
                        }
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

        /**
         * First setup ack: starts the turn and flushes audio buffered during
         * the handshake. Runs on the socket-reader thread.
         */
        private fun handleSetupComplete() {
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
            openScope.cancel()
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
            openScope.cancel()
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
            openScope.cancel()
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

    /** Diagnostic log only: never pass apiKey or audio data here. Release builds stay silent. */
    private fun debugLog(tag: String, msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceIme", "$tag: $msg")
    }

    /**
     * Live transport: streams 16 kHz mono PCM over the BidiGenerateContent
     * WebSocket and returns the accumulated input transcription with the
     * index of the key that worked. Same rotation contract as REST: 429
     * tries the next key, 401 fails fast via [mapHttpError].
     */
    private suspend fun liveTranscribe(
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

        val start = rotationStart(startIndex, keys.size)
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
    private suspend fun interactionsTranscribe(
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

        val start = rotationStart(startIndex, keys.size)
        for (i in keys.indices) {
            val idx = (start + i) % keys.size
            val (code, payload) = executeRestCall(url, keys[idx], body)
            debugLog("interactions", "HTTP $code key=$idx bytes=${payload.length}")
            if (rotateOn429(code, i, keys.size)) continue
            if (code < 200 || code >= 300) {
                throw TranscribeException(mapHttpError(code, payload))
            }
            return Pair(parseInteractionsTranscript(payload), idx)
        }
        throw TranscribeException("Rate limited on all keys — retry later")
    }

    /**
     * One Live session on a single key over the raw socket. Bridges socket
     * callbacks to a coroutine and bounds the whole attempt with
     * [LIVE_TIMEOUT_MS].
     */
    private suspend fun liveAttempt(
        host: String,
        baseUrl: String,
        key: String,
        model: String,
        pcm: ByteArray,
        smartMode: Boolean = false,
    ): LiveOutcome = coroutineScope {
        val deferred = CompletableDeferred<LiveOutcome>()
        liveResult = deferred
        val transcript = StringBuilder()
        val lastTranscriptMs = AtomicLong(0L)
        val raw = buildLiveAttemptSocket(host, baseUrl, key, deferred, transcript, lastTranscriptMs)
        inFlightRaw = raw
        connectLiveSocket(raw)?.let { return@coroutineScope it }
        try {
            sendLiveAudio(raw, pcm, model, smartMode, deferred)
        } catch (e: Exception) {
            deferred.complete(LiveOutcome.Fail("Network error — check connection", e))
        }
        try {
            awaitLiveOutcome(raw, deferred, transcript, lastTranscriptMs)
        } finally {
            if (inFlightRaw === raw) inFlightRaw = null
            if (liveResult === deferred) liveResult = null
        }
    }

    /**
     * Builds the raw socket for one attempt, wiring server callbacks to
     * [deferred]: interim hypotheses replace the running text, finals append,
     * turn-complete and close commit, failures map to rotate/fail outcomes.
     */
    private fun buildLiveAttemptSocket(
        host: String,
        baseUrl: String,
        key: String,
        deferred: CompletableDeferred<LiveOutcome>,
        transcript: StringBuilder,
        lastTranscriptMs: AtomicLong,
    ): LiveSocket {
        val attempt = LiveAttemptCallbacks(deferred, transcript, lastTranscriptMs)
        return LiveSocket(
            host = host,
            path = livePath(baseUrl, key),
            onText = { text -> attempt.onText(text) },
            onClosed = { _, _ -> attempt.completeDone(closeSocket = false) },
            onFailure = { t -> deferred.complete(attempt.mapFailure(t)) },
            log = ::debugLog,
        )
    }

    /**
     * Attempt-scoped callback state: accumulates interim/final transcript
     * text and maps socket events to attempt outcomes. Lambdas stay thin so
     * the builder keeps its shape.
     */
    private inner class LiveAttemptCallbacks(
        private val deferred: CompletableDeferred<LiveOutcome>,
        private val transcript: StringBuilder,
        private val lastTranscriptMs: AtomicLong,
    ) {
        fun onText(text: String) {
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
            if (isLiveTurnComplete(text)) completeDone(closeSocket = true)
        }

        fun completeDone(closeSocket: Boolean) {
            if (closeSocket) inFlightRaw?.close(1000, "")
            val text = transcript.toString().trim()
            deferred.complete(
                if (text.isEmpty()) LiveOutcome.Fail("empty")
                else LiveOutcome.Done(text),
            )
        }

        fun mapFailure(t: Throwable): LiveOutcome {
            val message = t.message.orEmpty()
            return when {
                message.contains("Rate limited") -> LiveOutcome.Rotate
                message.startsWith("Check API key") ||
                    message.startsWith("Server error (HTTP") -> LiveOutcome.Fail(message, t)
                else -> LiveOutcome.Fail("Network error — check connection", t)
            }
        }
    }

    /**
     * Blocking connect on Dispatchers.IO (client enforces its own dispatcher).
     * Returns null when connected; otherwise the terminal outcome so the
     * caller can return early. Cancellation is never converted.
     */
    private suspend fun connectLiveSocket(raw: LiveSocket): LiveOutcome? {
        try {
            withContext(Dispatchers.IO) { raw.connect() }
        } catch (e: LiveSocketException) {
            val message = e.message.orEmpty()
            return when {
                message.contains("Rate limited") -> LiveOutcome.Rotate
                else -> LiveOutcome.Fail(if (message.isEmpty()) "Network error — check connection" else message, e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LiveOutcome.Fail("Network error — check connection", e)
        }
        return null
    }

    /**
     * Awaits the attempt outcome bounded by [LIVE_TIMEOUT_MS]: starts the
     * grace-closer child (which closes the socket when interim text goes
     * quiet instead of hanging), then awaits the callback-settled deferred.
     * A timed-out attempt with interim text still commits the words heard.
     * Closes the socket on cancellation and rethrows — never converts.
     */
    private suspend fun CoroutineScope.awaitLiveOutcome(
        raw: LiveSocket,
        deferred: CompletableDeferred<LiveOutcome>,
        transcript: StringBuilder,
        lastTranscriptMs: AtomicLong,
    ): LiveOutcome {
        val graceJob = launchGraceCloser(raw, deferred, lastTranscriptMs)
        try {
            return try {
                withTimeout(LIVE_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                // Server never finalized the turn: if interim text arrived,
                // commit it instead of failing — the words were heard.
                val interim = transcript.toString().trim()
                raw.close(1000, "timeout")
                if (interim.isNotEmpty()) {
                    LiveOutcome.Done(interim)
                } else {
                    throw TranscribeException("Timed out — try again", e)
                }
            } catch (e: TranscribeException) {
                throw e
            } catch (e: CancellationException) {
                // Close the socket so no late callback settles the deferred,
                // then rethrow — cancellation must propagate, never convert.
                try {
                    raw.close(1000, "cancel")
                } catch (_: Exception) {
                    // Already closed; nothing to do.
                }
                throw e
            }
        } finally {
            graceJob.cancel()
            try {
                raw.close(1000, "")
            } catch (e: Exception) {
                // Already closed; nothing to do.
            }
        }
    }

    /**
     * Grace closer child: the server often keeps the turn open after the last
     * interim (no turnComplete). If transcript arrived but nothing new comes
     * for GRACE_MS, closes the socket — onClosed commits whatever was heard
     * instead of hanging until LIVE_TIMEOUT_MS.
     */
    private fun CoroutineScope.launchGraceCloser(
        raw: LiveSocket,
        deferred: CompletableDeferred<LiveOutcome>,
        lastTranscriptMs: AtomicLong,
    ): Job = launch {
        while (true) {
            delay(LIVE_GRACE_MS)
            val last = lastTranscriptMs.get()
            if (last > 0L && System.currentTimeMillis() - last >= LIVE_GRACE_MS &&
                !deferred.isCompleted
            ) {
                try {
                    raw.close(1000, "grace")
                } catch (e: Exception) {
                    // Socket already gone; onClosed/failure will settle it.
                }
                return@launch
            }
        }
    }

    /**
     * Streams setup + activity-start + PCM chunks + end markers over [raw].
     * Settles [deferred] on transport failure; the caller's outcome logic
     * decides what the result means.
     */
    private fun sendLiveAudio(
        raw: LiveSocket,
        pcm: ByteArray,
        model: String,
        smartMode: Boolean,
        deferred: CompletableDeferred<LiveOutcome>,
    ) {
        if (!raw.send(buildLiveSetupJson(model, smartMode))) {
            deferred.complete(LiveOutcome.Fail("Network error — check connection"))
            return
        }
        if (!raw.send(buildLiveActivityStartJson())) {
            deferred.complete(LiveOutcome.Fail("Network error — check connection"))
            return
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
    }

    /** One Live key attempt: success, rotate on 429, or fail with a user-safe message. */
    private sealed interface LiveOutcome {
        data class Done(val text: String) : LiveOutcome
        data object Rotate : LiveOutcome
        data class Fail(val message: String, val cause: Throwable? = null) : LiveOutcome
    }

    override fun toString(): String = "LlmClient(<api key redacted>)"
}
