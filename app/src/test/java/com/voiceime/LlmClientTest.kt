package com.voiceime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmClientTest {

    private val client = LlmClient()

    @Test
    fun `buildRequestJson includes audio modalities and audio output param`() {
        val body = client.buildRequestJson("gpt-4o-audio-preview", "QUJDRA==")
        val json = JSONObject(body)

        val modalities = json.getJSONArray("modalities")
        assertEquals(2, modalities.length())
        assertEquals("text", modalities.getString(0))
        assertEquals("audio", modalities.getString(1))
        assertTrue(body.contains("\"modalities\":[\"text\",\"audio\"]"))

        val audio = json.getJSONObject("audio")
        assertEquals("alloy", audio.getString("voice"))
        assertEquals("wav", audio.getString("format"))

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
}
