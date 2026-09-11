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
