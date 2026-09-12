package com.voiceime

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LlmClientTest {

    private val client = LlmClient()

    private val geminiBody = """{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}"""

    /** Minimal 44-byte-header PCM WAV for wavToMono16kPcm tests. */
    private fun wavBytes(
        sampleRate: Int = 16000,
        channels: Int = 1,
        bits: Int = 16,
        audioFormat: Int = 1,
        pcm: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): ByteArray {
        val out = ByteArray(44 + pcm.size)
        fun tag(off: Int, s: String) {
            s.toByteArray(Charsets.US_ASCII).copyInto(out, off)
        }
        fun u16(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        fun u32(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
            out[off + 2] = ((v ushr 16) and 0xFF).toByte()
            out[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        tag(0, "RIFF"); u32(4, 36 + pcm.size); tag(8, "WAVE")
        tag(12, "fmt "); u32(16, 16)
        u16(20, audioFormat); u16(22, channels); u32(24, sampleRate)
        u32(28, sampleRate * channels * bits / 8)
        u16(32, channels * bits / 8); u16(34, bits)
        tag(36, "data"); u32(40, pcm.size)
        pcm.copyInto(out, 44)
        return out
    }

    @Test
    fun `mapHttpError includes server detail and keeps bare messages when body empty`() {
        val body = """{"error":{"message":"Incorrect API key provided"}}"""
        val withDetail = mapHttpError(401, body)
        assertTrue(withDetail.contains("Check API key in settings"))
        assertTrue(withDetail.contains("Incorrect API key provided"))

        assertEquals("Rate limited — retry", mapHttpError(429, ""))
    }

    @Test
    fun `mapHttpError 404 points at model`() {
        val msg = mapHttpError(404, "")
        assertTrue(msg.contains("Model not found or no access"))
    }

    @Test
    fun `buildGeminiRequestJson carries wav inlineData and instruction text`() {
        val body = buildGeminiRequestJson("QUJDRA==")
        val json = JSONObject(body)
        val parts = json.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val inline = parts.getJSONObject(0).getJSONObject("inlineData")
        assertEquals("audio/wav", inline.getString("mimeType"))
        assertEquals("QUJDRA==", inline.getString("data"))
        val text = parts.getJSONObject(1).getString("text")
        assertTrue(text.contains("Transcribe the speech exactly"))
    }

    @Test
    fun `buildGeminiRequestJson always carries systemInstruction with base role text`() {
        val json = JSONObject(buildGeminiRequestJson("QUJDRA=="))
        val systemText = json.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(systemText.contains("You are a speech transcription assistant"))
        assertTrue(systemText.contains("If there is no speech, output nothing"))
    }

    @Test
    fun `buildGeminiRequestJson appends custom prompt under User preferences`() {
        val json = JSONObject(buildGeminiRequestJson("QUJDRA==", "Keep Hinglish as-is"))
        val systemText = json.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(systemText.startsWith(BASE_SYSTEM))
        assertTrue(systemText.contains("User preferences:\nKeep Hinglish as-is"))
    }

    @Test
    fun `buildGeminiRequestJson blank custom omits User preferences marker`() {
        for (blank in listOf("", "   ")) {
            val json = JSONObject(buildGeminiRequestJson("QUJDRA==", blank))
            val systemText = json.getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text")
            assertEquals(BASE_SYSTEM, systemText)
            assertTrue(!systemText.contains("User preferences:"))
        }
    }

    @Test
    fun `buildGeminiRequestJson user text part ignores custom prompt`() {
        val expected = "Transcribe the speech exactly. " +
            "Output only the transcript, no commentary."
        for (custom in listOf("", "Add punctuation")) {
            val json = JSONObject(buildGeminiRequestJson("QUJDRA==", custom))
            val parts = json.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
            assertEquals(expected, parts.getJSONObject(1).getString("text"))
        }
    }

    @Test
    fun `geminiUrl appends model generateContent path`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            geminiUrl("https://generativelanguage.googleapis.com/v1beta/", "gemini-2.5-flash"),
        )
    }

    @Test
    fun `parseGeminiTranscript joins all candidates parts text`() {
        val payload = """{"candidates":[
            |{"content":{"parts":[{"text":"hello "},{"text":"world"}]}},
            |{"content":{"parts":[{"text":"!"}]}}]}""".trimMargin()
        assertEquals("hello world!", parseGeminiTranscript(payload))
    }

    @Test(expected = TranscribeException::class)
    fun `parseGeminiTranscript throws on empty or missing candidates`() {
        parseGeminiTranscript("""{"candidates":[]}""")
    }

    @Test
    fun `normalizeKeys trims drops blanks and dedupes keeping order`() {
        assertEquals(
            listOf("a", "b", "c"),
            normalizeKeys(listOf("  a ", "", "b", "a", "  ", "c", "b")),
        )
    }

    @Test
    fun `normalizeKeys empty input stays empty`() {
        assertEquals(emptyList<String>(), normalizeKeys(listOf("", "   ")))
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

    @Test
    fun `requiresLive true for live markers`() {
        assertTrue(requiresLive("gemini-3.5-transcribe-live"))
        assertTrue(requiresLive("gemini-2.5-flash-live-preview"))
        assertTrue(requiresLive("gemini-2.5-flash-preview-native-audio-dialog"))
        assertTrue(requiresLive("  Gemini-2.0-Flash-Live  "))
    }

    @Test
    fun `requiresLive false for rest models`() {
        assertFalse(requiresLive("gemini-2.5-flash"))
        assertFalse(requiresLive("gemini-2.0-flash"))
        assertFalse(requiresLive("gemini-2.5-flash-preview-tts"))
        assertFalse(requiresLive(""))
    }

    @Test
    fun `isLiveOnlyError true only on 400 with live wording`() {
        val body = """{"error":{"message":"This model only supports the bidiGenerateContent method. Please use the Live API."}}"""
        assertTrue(isLiveOnlyError(400, body))
        assertTrue(isLiveOnlyError(400, "please use the gemini live api"))
        assertFalse(isLiveOnlyError(400, """{"error":{"message":"bad request"}}"""))
        assertFalse(isLiveOnlyError(401, body))
        assertFalse(isLiveOnlyError(429, body))
        assertFalse(isLiveOnlyError(400, ""))
    }

    @Test
    fun `buildLiveSetupJson carries model text modality and transcription mode`() {
        val json = JSONObject(buildLiveSetupJson("gemini-3.5-transcribe-live"))
        val setup = json.getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
        val modalities = setup.getJSONObject("generationConfig").getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("TEXT", modalities.getString(0))
        val transcription = setup.getJSONObject("inputAudioTranscription")
        assertEquals("VERBATIM", transcription.getString("mode"))
        assertEquals(0, transcription.getJSONArray("languageCodes").length())
    }

    @Test
    fun `buildLiveSetupJson smart flag selects SMART mode`() {
        val json = JSONObject(buildLiveSetupJson("gemini-3.5-transcribe-live", smartMode = true))
        assertEquals(
            "SMART",
            json.getJSONObject("setup").getJSONObject("inputAudioTranscription").getString("mode"),
        )
    }

    @Test
    fun `buildLiveSetupJson disables automatic activity detection`() {
        // Push-to-talk: our mic tap / Stop marks turn boundaries (manual VAD).
        val setup = JSONObject(buildLiveSetupJson("gemini-3.5-transcribe-live")).getJSONObject("setup")
        assertTrue(
            setup.getJSONObject("realtimeInputConfig")
                .getJSONObject("automaticActivityDetection").getBoolean("disabled"),
        )
    }

    @Test
    fun `buildLiveActivity markers carry start and end signals`() {
        val start = JSONObject(buildLiveActivityStartJson()).getJSONObject("realtimeInput")
        assertTrue(start.has("activityStart"))
        val end = JSONObject(buildLiveActivityEndJson()).getJSONObject("realtimeInput")
        assertTrue(end.has("activityEnd"))
    }

    @Test
    fun `isInteractionsModel matches only the dedicated model`() {
        assertTrue(isInteractionsModel("gemini-3.5-transcribe"))
        assertTrue(isInteractionsModel("  GEMINI-3.5-TRANSCRIBE  "))
        assertFalse(isInteractionsModel("gemini-3.5-transcribe-live"))
        assertFalse(isInteractionsModel("gemini-2.5-flash"))
        assertFalse(isInteractionsModel(""))
    }

    @Test
    fun `isDevInstructionError matches only the 400 developer-instruction body`() {
        assertTrue(
            isDevInstructionError(
                400,
                """{"error":{"message":"Developer instruction is not enabled for this model"}}""",
            ),
        )
        assertFalse(isDevInstructionError(400, """{"error":{"message":"bad request"}}"""))
        assertFalse(isDevInstructionError(401, "Developer instruction is not enabled"))
        assertFalse(isDevInstructionError(400, ""))
    }

    @Test
    fun `buildInteractionsRequestJson carries model input and verbatim default`() {
        val json = JSONObject(
            buildInteractionsRequestJson("QUJD", "gemini-3.5-transcribe"),
        )
        assertEquals("gemini-3.5-transcribe", json.getString("model"))
        val input = json.getJSONArray("input")
        assertEquals("text", input.getJSONObject(0).getString("type"))
        val audio = input.getJSONObject(1)
        assertEquals("audio", audio.getString("type"))
        assertEquals("QUJD", audio.getString("data"))
        assertEquals("audio/wav", audio.getString("mime_type"))
        assertEquals(
            "verbatim",
            json.getJSONObject("generation_config")
                .getJSONObject("transcription_config").getString("mode"),
        )
    }

    @Test
    fun `buildInteractionsRequestJson smart flag and custom prompt`() {
        val json = JSONObject(
            buildInteractionsRequestJson("QUJD", "gemini-3.5-transcribe", "Keep Hinglish", smartMode = true),
        )
        assertEquals(
            "smart",
            json.getJSONObject("generation_config")
                .getJSONObject("transcription_config").getString("mode"),
        )
        assertTrue(json.getJSONArray("input").getJSONObject(0).getString("text").contains("Keep Hinglish"))
    }

    @Test
    fun `parseInteractionsTranscript reads output_text then steps`() {
        assertEquals("hello", parseInteractionsTranscript("""{"output_text":"hello"}"""))
        val steps = """{"steps":[{"type":"model_output","content":[{"type":"text","text":"hi "},{"type":"text","text":"there"}]}]}"""
        assertEquals("hi there", parseInteractionsTranscript(steps))
        try {
            parseInteractionsTranscript("""{"output_text":"  "}""")
            fail("expected TranscribeException")
        } catch (e: TranscribeException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun `parseInteractionsTranscript ignores user_input echo steps`() {
        val mixed = """{"steps":[
            {"type":"user_input","content":[{"type":"text","text":"Transcribe the speech"}]},
            {"type":"model_output","content":[{"text":"hello"}]}
        ]}"""
        assertEquals("hello", parseInteractionsTranscript(mixed))
    }

    @Test
    fun `parseInteractionsTranscript accepts bare text parts without type`() {
        // Live shape: steps[0].content[0] = {"text": "..."} with no "type".
        val live = """{"steps":[{"content":[{"text":"मेरा नाम"}],"type":"model_output"}]}"""
        assertEquals("मेरा नाम", parseInteractionsTranscript(live))
    }

    @Test
    fun `interactionsUrl appends interactions path`() {
        assertEquals(
            "https://example.com/v1/interactions",
            interactionsUrl("https://example.com/v1/"),
        )
    }

    @Test
    fun `buildLiveAudioMessage carries pcm mime and base64 data`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val json = JSONObject(buildLiveAudioMessage(chunk))
        val audio = json.getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        val decoded = java.util.Base64.getDecoder().decode(audio.getString("data"))
        assertTrue(decoded.contentEquals(chunk))
    }

    @Test
    fun `parseLiveInputTranscripts joins multi-message fragments`() {
        val first = """{"serverContent":{"inputTranscription":{"text":"hello "}}}"""
        val second = """{"serverContent":{"inputTranscription":{"text":"world"}}}"""
        val joined = (parseLiveInputTranscripts(first) +
            parseLiveInputTranscripts(second)).joinToString("")
        assertEquals("hello world", joined)
    }

    @Test
    fun `hasLiveFinalTranscript distinguishes final from interim`() {
        assertTrue(hasLiveFinalTranscript("""{"serverContent":{"inputTranscription":{"text":"hi"}}}"""))
        assertFalse(hasLiveFinalTranscript("""{"serverContent":{"interimInputTranscription":{"text":"hi"}}}"""))
        assertFalse(hasLiveFinalTranscript("""{"serverContent":{}}"""))
        assertFalse(hasLiveFinalTranscript("not json"))
    }

    @Test
    fun `parseLiveInputTranscripts falls back to interim hypothesis`() {
        val interim = """{"serverContent":{"interimInputTranscription":{"text":"partial"}}}"""
        assertEquals(listOf("partial"), parseLiveInputTranscripts(interim))
        val both = """{"serverContent":{"inputTranscription":{"text":"final"},"interimInputTranscription":{"text":"partial"}}}"""
        assertEquals(listOf("final"), parseLiveInputTranscripts(both))
    }

    @Test
    fun `parseLiveInputTranscripts tolerates snake_case and junk`() {
        val snake = """{"server_content":{"inputTranscription":{"text":"hi"}}}"""
        assertEquals(listOf("hi"), parseLiveInputTranscripts(snake))
        assertEquals(emptyList<String>(), parseLiveInputTranscripts(""))
        assertEquals(emptyList<String>(), parseLiveInputTranscripts("not json"))
        assertEquals(emptyList<String>(), parseLiveInputTranscripts("""{"serverContent":{}}"""))
        assertEquals(
            emptyList<String>(),
            parseLiveInputTranscripts("""{"serverContent":{"inputTranscription":{}}}"""),
        )
    }

    @Test
    fun `isLiveTurnComplete true on turn or generation complete only`() {
        assertTrue(isLiveTurnComplete("""{"serverContent":{"turnComplete":true}}"""))
        assertTrue(isLiveTurnComplete("""{"serverContent":{"generationComplete":true}}"""))
        assertFalse(isLiveTurnComplete("""{"serverContent":{"turnComplete":false}}"""))
        assertFalse(isLiveTurnComplete("""{"serverContent":{}}"""))
        assertFalse(isLiveTurnComplete("not json"))
        assertFalse(isLiveTurnComplete(""))
    }

    @Test
    fun `liveUrl defaults for google and wraps custom hosts`() {
        assertEquals(
            "wss://generativelanguage.googleapis.com" +
                "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            liveUrl("https://generativelanguage.googleapis.com/v1beta"),
        )
        val custom = liveUrl("https://proxy.example.com/v1beta")
        assertTrue(custom.startsWith("wss://proxy.example.com/ws/"))
        val http = liveUrl("http://proxy.example.com/v1beta")
        assertTrue(http.startsWith("ws://proxy.example.com/ws/"))
    }

    @Test
    fun `wavToMono16kPcm strips header and returns pcm`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        assertTrue(wavToMono16kPcm(wavBytes(pcm = pcm)).contentEquals(pcm))
    }

    @Test
    fun `wavToMono16kPcm rejects wrong rate and non-pcm`() {
        for (bad in listOf(
            wavBytes(sampleRate = 8000),
            wavBytes(sampleRate = 44100),
            wavBytes(channels = 2),
            wavBytes(bits = 8),
            wavBytes(audioFormat = 3),
            byteArrayOf(1, 2, 3),
            "NOT-A-WAV-FILE-PADDING-ENOUGH-BYTES!!".toByteArray(Charsets.US_ASCII),
        )) {
            try {
                wavToMono16kPcm(bad)
                fail("expected TranscribeException")
            } catch (e: TranscribeException) {
                // Expected; message is user-safe.
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `liveSession buffers pcm sent before handshake completes`() {
        // Mic starts instantly on tap while TLS+WS handshake is in flight:
        // early chunks must be flushed after setup, never dropped.
        val server = MockWebServer()
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
            }
        }))
        server.start()
        val session = client.startLiveSession(
            "k", server.url("/v1beta").toString(), "gemini-3.5-transcribe-live",
            onInterim = {}, onFinal = {}, onError = {},
        )
        try {
            val chunk = ByteArray(3200) { 7 }
            session.sendPcm(chunk)
            session.sendPcm(chunk)
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val audios = received.count { it.contains("\"audio\"") }
                if (received.any { it.contains("inputAudioTranscription") } && audios >= 2) break
                Thread.sleep(50)
            }
            assertTrue(received.any { it.contains("inputAudioTranscription") })
            assertEquals(2, received.count { it.contains("\"audio\"") })
            val setupIdx = received.indexOfFirst { it.contains("inputAudioTranscription") }
            val firstAudio = received.indexOfFirst { it.contains("\"audio\"") }
            assertTrue(setupIdx >= 0 && firstAudio > setupIdx)
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `liveSession surfaces handshake rejection detail`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"boom-detail\"}}"),
        )
        server.start()
        val latch = CountDownLatch(1)
        var err = ""
        val session = client.startLiveSession(
            "k", server.url("/v1beta").toString(), "m",
            onInterim = {}, onFinal = {},
            onError = { err = it; latch.countDown() },
        )
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertTrue(err.contains("boom-detail"))
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `liveSession streams interim then final transcripts`() {
        val server = MockWebServer()
        val serverWs = AtomicReference<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverWs.set(webSocket)
            }
        }))
        server.start()
        val interims = CopyOnWriteArrayList<String>()
        val finals = CopyOnWriteArrayList<String>()
        val session = client.startLiveSession(
            "k", server.url("/v1beta").toString(), "m",
            onInterim = { interims.add(it) },
            onFinal = { finals.add(it) },
            onError = {},
        )
        try {
            val openDeadline = System.currentTimeMillis() + 10_000
            while (serverWs.get() == null && System.currentTimeMillis() < openDeadline) {
                Thread.sleep(20)
            }
            val ws = serverWs.get() ?: fail("no server socket")
            ws.send("{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"hel\"}}}}")
            ws.send("{\"serverContent\":{\"inputTranscription\":{\"text\":\"hello\"}}}}")
            val msgDeadline = System.currentTimeMillis() + 10_000
            while ((interims.isEmpty() || finals.isEmpty()) && System.currentTimeMillis() < msgDeadline) {
                Thread.sleep(20)
            }
            assertEquals(listOf("hel"), interims.toList())
            assertEquals(listOf("hello"), finals.toList())
        } finally {
            session.close()
            server.shutdown()
        }
    }
}
