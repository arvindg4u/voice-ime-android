package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCommitPolicyTest {

    @Test
    fun `final text wins over the interim hypothesis`() {
        assertEquals(
            "hello world",
            LiveCommitPolicy.resolveCommitText(
                finalText = "hello world",
                interimText = "hello wor",
                stillUploading = true,
            ),
        )
    }

    @Test
    fun `interim is the fallback when no final arrived`() {
        assertEquals(
            "hello wor",
            LiveCommitPolicy.resolveCommitText(
                finalText = "",
                interimText = "hello wor",
                stillUploading = true,
            ),
        )
    }

    @Test
    fun `blank text commits nothing`() {
        assertEquals(
            "",
            LiveCommitPolicy.resolveCommitText(
                finalText = "   ",
                interimText = "",
                stillUploading = true,
            ),
        )
    }

    @Test
    fun `an error that took over during finalization blocks the commit`() {
        assertEquals(
            "",
            LiveCommitPolicy.resolveCommitText(
                finalText = "hello world",
                interimText = "hello",
                stillUploading = false,
            ),
        )
    }

    @Test
    fun `final text is trimmed`() {
        assertEquals(
            "hello",
            LiveCommitPolicy.resolveCommitText(
                finalText = "  hello\n",
                interimText = "",
                stillUploading = true,
            ),
        )
    }

    /**
     * Regression for the resolveLiveText() bug: the old code fell back to the
     * status label, so a surfaced error string ("Network error ...") could be
     * committed as the transcript. The seam has no statusText parameter, so a
     * label can only ever be committed when the service explicitly passes it
     * as transcript text — which it never does.
     */
    @Test
    fun `no status label channel exists in the seam`() {
        assertEquals(
            "",
            LiveCommitPolicy.resolveCommitText(
                finalText = "",
                interimText = "",
                stillUploading = false,
            ),
        )
        assertEquals(
            "",
            LiveCommitPolicy.resolveCommitText(
                finalText = "",
                interimText = "",
                stillUploading = true,
            ),
        )
    }
}
