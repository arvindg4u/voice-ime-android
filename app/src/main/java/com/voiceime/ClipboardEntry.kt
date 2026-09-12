package com.voiceime

import java.util.UUID

/**
 * One saved clipboard item. Text-only; images are out of scope.
 */
data class ClipboardEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val createdAtMs: Long,
    val isPinned: Boolean = false,
    val isSensitive: Boolean = false,
    val mimeType: String = "text/plain",
)
