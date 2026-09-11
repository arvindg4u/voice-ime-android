package com.voiceime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmClientTest {

    private val client = LlmClient()

    @Test
    fun `buildRequestJson is input-only with no modalities or top-level audio`() {
        val body = client.buildRequestJson("gpt-audio-1.5", "QUJDRA==")
        val json = JSONObject(body)

        assertEquals("gpt-audio-1.5", json.getString("model"))
        assertFalse("request must not contain modalities", json.has("modalities"))
        assertFalse("request must not contain top-level audio", json.has("audio"))

        val content = json.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        var foundInputAudio = false
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            if (part.optString("type") == "input_audio") {
                val inputAudio = part.getJSONObject("input_audio")
                assertEquals("QUJDRA==", inputAudio.getString("data"))
                assertEquals("wav", inputAudio.getString("format"))
                foundInputAudio = true
            }
        }
        assertTrue("messages content must contain an input_audio part", foundInputAudio)
        assertTrue(body.contains("\"format\":\"wav\""))
    }

    @Test
    fun `parseTranscript returns content when present`() {
        val payload = """{"choices":[{"message":{"role":"assistant","content":"  hello world  "}}]}"""
        assertEquals("hello world", client.parseTranscript(payload))
    }

    @Test
    fun `parseTranscript falls back to audio transcript when content is null or blank`() {
        val nullContent = """{"choices":[{"message":{"role":"assistant","content":null,"audio":{"transcript":" hi there "}}}]}"""
        assertEquals("hi there", client.parseTranscript(nullContent))

        val blankContent = """{"choices":[{"message":{"role":"assistant","content":"   ","audio":{"transcript":"fallback works"}}}]}"""
        assertEquals("fallback works", client.parseTranscript(blankContent))
    }

    @Test(expected = TranscribeException::class)
    fun `parseTranscript throws on fully empty`() {
        client.parseTranscript("""{"choices":[{"message":{"role":"assistant","content":"","audio":{"transcript":"  "}}}]}""")
    }

    @Test
    fun `mapHttpError includes server detail and keeps bare messages when body empty`() {
        val body = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}"""
        val withDetail = client.mapHttpError(401, body)
        assertTrue(withDetail.contains("Check API key in settings"))
        assertTrue(withDetail.contains("Incorrect API key provided"))

        assertEquals("Rate limited — retry", client.mapHttpError(429, ""))
    }

    @Test
    fun `isGeminiBaseUrl detects Google host`() {
        assertTrue(client.isGeminiBaseUrl("https://generativelanguage.googleapis.com/v1beta"))
        assertTrue(client.isGeminiBaseUrl("https://GENERATIVELANGUAGE.GOOGLEAPIS.COM/v1beta/"))
    }

    @Test
    fun `isGeminiBaseUrl rejects OpenAI-compatible hosts`() {
        assertFalse(client.isGeminiBaseUrl("https://api.openai.com/v1"))
        assertFalse(client.isGeminiBaseUrl(""))
    }

    @Test
    fun `buildGeminiRequestJson carries wav inlineData and instruction text`() {
        val body = client.buildGeminiRequestJson("QUJDRA==")
        val json = JSONObject(body)
        val parts = json.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val inline = parts.getJSONObject(0).getJSONObject("inlineData")
        assertEquals("audio/wav", inline.getString("mimeType"))
        assertEquals("QUJDRA==", inline.getString("data"))
        val text = parts.getJSONObject(1).getString("text")
        assertTrue(text.contains("Transcribe the speech exactly"))
    }

    @Test
    fun `geminiUrl appends model generateContent path`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            client.geminiUrl("https://generativelanguage.googleapis.com/v1beta/", "gemini-2.5-flash"),
        )
    }

    @Test
    fun `parseGeminiTranscript joins all candidates parts text`() {
        val payload = """{"candidates":[
            |{"content":{"parts":[{"text":"hello "},{"text":"world"}]}},
            |{"content":{"parts":[{"text":"!"}]}}]}""".trimMargin()
        assertEquals("hello world!", client.parseGeminiTranscript(payload))
    }

    @Test(expected = TranscribeException::class)
    fun `parseGeminiTranscript throws on empty or missing candidates`() {
        client.parseGeminiTranscript("""{"candidates":[]}""")
    }
}
