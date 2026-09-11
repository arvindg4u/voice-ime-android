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
 * and returns the transcript from choices[0].message.content.
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

        val body = JSONObject()
            .put("model", model)
            .put("modalities", JSONArray().put("text"))
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
                if (!res.isSuccessful) {
                    throw TranscribeException(mapHttpError(res.code))
                }
                val text = JSONObject(payload)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                    .trim()
                if (text.isEmpty()) throw TranscribeException("empty")
                return text
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

    private fun mapHttpError(code: Int): String = when (code) {
        401 -> "Check API key in settings"
        404, 405 -> "Server does not support audio input — check base URL/model"
        429 -> "Rate limited — retry"
        else -> "Server error (HTTP $code)"
    }

    override fun toString(): String = "LlmClient(<api key redacted>)"
}
