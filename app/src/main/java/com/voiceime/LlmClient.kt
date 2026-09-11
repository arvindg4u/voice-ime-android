package com.voiceime

import android.util.Base64
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
 * Sends WAV audio to {baseUrl}/chat/completions as base64 input_audio
 * and returns the transcript from choices[0].
 * The API key is never logged.
 */
class LlmClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var inFlight: Call? = null

    @Throws(TranscribeException::class)
    fun transcribe(wav: ByteArray, baseUrl: String, apiKey: String, model: String): String {
        if (isGeminiBaseUrl(baseUrl)) return transcribeGemini(wav, baseUrl, apiKey, model)
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val audioB64 = Base64.encodeToString(wav, Base64.NO_WRAP)

        val body = buildRequestJson(model, audioB64)
        val host = try { java.net.URL(baseUrl).host } catch (e: Exception) { baseUrl }
        debugLog("transcribe", "POST url=$host model=$model wavBytes=${wav.size} b64Len=${audioB64.length}")

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        inFlight = call
        try {
            val response = call.execute()
            response.use { res ->
                val payload = res.body?.string().orEmpty()
                debugLog("transcribe", "HTTP ${res.code} payload=${payload.take(2000)}")
                if (!res.isSuccessful) {
                    throw TranscribeException(mapHttpError(res.code, payload))
                }
                return parseTranscript(payload)
            }
        } catch (e: TranscribeException) {
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

    /** Cancels the in-flight transcription request, if any. */
    fun cancel() {
        inFlight?.cancel()
    }

    /** Diagnostic log only: never pass apiKey or audio data here. */
    private fun debugLog(tag: String, msg: String) {
        android.util.Log.d("VoiceIme", "$tag: $msg")
    }

    /** Builds the exact POST body for a chat-completions audio transcription request. */
    internal fun buildRequestJson(model: String, audioB64: String): String =
        JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put(
                                            "text",
                                            "Transcribe the speech exactly. " +
                                                "Output only the transcript, no commentary.",
                                        ),
                                )
                                .put(
                                    JSONObject()
                                        .put("type", "input_audio")
                                        .put(
                                            "input_audio",
                                            JSONObject()
                                                .put("data", audioB64)
                                                .put("format", "wav"),
                                        ),
                                ),
                        ),
                ),
            )
            .toString()

    /**
     * Extracts the transcript from a chat-completions response payload.
     * Reads message.content first; falls back to message.audio.transcript.
     */
    @Throws(TranscribeException::class)
    internal fun parseTranscript(payload: String): String {
        val message = JSONObject(payload)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
        val text = message?.optString("content").orEmpty().trim()
        if (text.isNotEmpty()) return text
        val transcript = message
            ?.optJSONObject("audio")
            ?.optString("transcript")
            .orEmpty()
            .trim()
        if (transcript.isEmpty()) throw TranscribeException("empty")
        return transcript
    }

    /** True when the base URL points at the Google Generative Language API (case-insensitive). */
    internal fun isGeminiBaseUrl(baseUrl: String): Boolean {
        val host = try {
            java.net.URL(baseUrl).host
        } catch (e: Exception) {
            baseUrl
        }
        return host.contains("generativelanguage.googleapis.com", ignoreCase = true)
    }

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
     * Sends WAV audio to the Gemini generateContent endpoint with an x-goog-api-key
     * header and returns the transcript joined from all candidates' parts.
     * The API key and audio are never logged.
     */
    @Throws(TranscribeException::class)
    internal fun transcribeGemini(wav: ByteArray, baseUrl: String, apiKey: String, model: String): String {
        val url = geminiUrl(baseUrl, model)
        val audioB64 = Base64.encodeToString(wav, Base64.NO_WRAP)

        val body = buildGeminiRequestJson(audioB64)
        val host = try { java.net.URL(baseUrl).host } catch (e: Exception) { baseUrl }
        debugLog("transcribeGemini", "POST url=$host model=$model wavBytes=${wav.size} b64Len=${audioB64.length}")

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        inFlight = call
        try {
            val response = call.execute()
            response.use { res ->
                val payload = res.body?.string().orEmpty()
                debugLog("transcribeGemini", "HTTP ${res.code} payload=${payload.take(2000)}")
                if (!res.isSuccessful) {
                    throw TranscribeException(mapHttpError(res.code, payload))
                }
                return parseGeminiTranscript(payload)
            }
        } catch (e: TranscribeException) {
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

    private fun mapHttpError(code: Int): String = mapHttpError(code, "")

    /** Maps an HTTP status to a user-safe message, appending server detail when present. */
    internal fun mapHttpError(code: Int, body: String): String {
        val base = when (code) {
            401 -> "Check API key in settings"
            404, 405 -> "Server does not support audio input — check base URL/model"
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
