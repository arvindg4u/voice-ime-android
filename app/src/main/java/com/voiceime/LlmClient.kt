package com.voiceime

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when transcription fails; message is safe to show to the user. */
class TranscribeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Sends WAV audio to the Gemini generateContent endpoint as base64 inline
 * audio and returns the transcript joined from all candidates' parts.
 * Key rotation lives here: keys are tried in round-robin order from
 * [startIndex]; on HTTP 429 the next key is tried. Non-429 errors fail
 * immediately. The API keys are never logged.
 */
class LlmClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var inFlight: Call? = null

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
    ): Pair<String, Int> {
        val keys = normalizeKeys(apiKeys)
        if (keys.isEmpty()) throw TranscribeException("No API key — open Settings")
        val audioB64 = java.util.Base64.getEncoder().encodeToString(wav)

        val body = buildGeminiRequestJson(audioB64)
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
                throw TranscribeException(mapHttpError(code, payload))
            }
            return Pair(parseGeminiTranscript(payload), idx)
        }
        throw TranscribeException("Rate limited on all keys — retry later")
    }

    /** Cancels the in-flight transcription request, if any. */
    fun cancel() {
        inFlight?.cancel()
    }

    /** Diagnostic log only: never pass apiKey or audio data here. */
    private fun debugLog(tag: String, msg: String) {
        android.util.Log.d("VoiceIme", "$tag: $msg")
    }

    /** Trims, drops blanks, and dedupes keys, keeping the original order. */
    internal fun normalizeKeys(apiKeys: List<String>): List<String> =
        apiKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Builds the Gemini generateContent body: WAV audio as inline data plus a text instruction. */
    internal fun buildGeminiRequestJson(audioB64: String): String =
        JSONObject()
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

    override fun toString(): String = "LlmClient(<api key redacted>)"
}
