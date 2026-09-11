package com.voiceime

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlmClientTest {

    private val client = LlmClient()

    private val geminiBody = """{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}"""

    @Test
    fun `mapHttpError includes server detail and keeps bare messages when body empty`() {
        val body = """{"error":{"message":"Incorrect API key provided"}}"""
        val withDetail = client.mapHttpError(401, body)
        assertTrue(withDetail.contains("Check API key in settings"))
        assertTrue(withDetail.contains("Incorrect API key provided"))

        assertEquals("Rate limited — retry", client.mapHttpError(429, ""))
    }

    @Test
    fun `mapHttpError 404 points at model`() {
        val msg = client.mapHttpError(404, "")
        assertTrue(msg.contains("Model not found or no access"))
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

    @Test
    fun `normalizeKeys trims drops blanks and dedupes keeping order`() {
        assertEquals(
            listOf("a", "b", "c"),
            client.normalizeKeys(listOf("  a ", "", "b", "a", "  ", "c", "b")),
        )
    }

    @Test
    fun `normalizeKeys empty input stays empty`() {
        assertEquals(emptyList<String>(), client.normalizeKeys(listOf("", "   ")))
    }

    @Test
    fun `transcribe success returns transcript and used index`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(geminiBody))
        server.start()
        try {
            val (text, used) = client.transcribe(
                byteArrayOf(1, 2, 3),
                listOf("key1", "key2"),
                server.url("/v1beta").toString(),
                "m",
            )
            assertEquals("hi", text)
            assertEquals(0, used)
            val req = server.takeRequest()
            assertEquals("key1", req.getHeader("x-goog-api-key"))
            assertTrue(req.path!!.contains(":generateContent"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `transcribe rotates to next key on 429`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody(geminiBody))
        server.start()
        try {
            val (text, used) = client.transcribe(
                byteArrayOf(1, 2, 3),
                listOf("key1", "key2"),
                server.url("/v1beta").toString(),
                "m",
            )
            assertEquals("hi", text)
            assertEquals(1, used)
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val second = server.takeRequest()
            assertEquals("key2", second.getHeader("x-goog-api-key"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `transcribe all 429 throws rate-all message`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.start()
        try {
            try {
                client.transcribe(
                    byteArrayOf(1, 2, 3),
                    listOf("key1", "key2"),
                    server.url("/v1beta").toString(),
                    "m",
                )
                fail("expected TranscribeException")
            } catch (e: TranscribeException) {
                assertTrue(e.message!!.contains("Rate limited on all keys"))
            }
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `transcribe 401 fails immediately without rotation`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"bad key"}}"""),
        )
        server.start()
        try {
            try {
                client.transcribe(
                    byteArrayOf(1, 2, 3),
                    listOf("key1", "key2"),
                    server.url("/v1beta").toString(),
                    "m",
                )
                fail("expected TranscribeException")
            } catch (e: TranscribeException) {
                assertTrue(e.message!!.contains("Check API key"))
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `transcribe empty keys throws no-key message`() {
        try {
            client.transcribe(byteArrayOf(1), listOf("  ", ""), "http://x/", "m")
            fail("expected TranscribeException")
        } catch (e: TranscribeException) {
            assertEquals("No API key — open Settings", e.message)
        }
    }

    @Test
    fun `transcribe startIndex wraps try order`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(geminiBody))
        server.start()
        try {
            val (_, used) = client.transcribe(
                byteArrayOf(1, 2, 3),
                listOf("key1", "key2"),
                server.url("/v1beta").toString(),
                "m",
                startIndex = 1,
            )
            assertEquals(1, used)
            val req = server.takeRequest()
            assertEquals("key2", req.getHeader("x-goog-api-key"))
        } finally {
            server.shutdown()
        }
    }
}
