package com.voiceime

/**
 * Accumulates finalized live-transcript fragments into one authoritative
 * string and returns only the new delta per call. Joins with longest
 * suffix/prefix overlap so a cumulative server resend collapses instead of
 * duplicating, disjoint fragments append, and exact echoes yield "" (the
 * caller skips onFinal but still completes its signal). Pure,
 * framework-free, JVM-testable.
 */
internal class FinalAccumulator {
    private val text = StringBuilder()

    /** Appends [incoming] final text; returns only the not-yet-seen delta. */
    fun append(incoming: String): String {
        if (incoming.isEmpty()) return ""
        val delta = incoming.substring(maxOverlap(text.toString(), incoming))
        text.append(delta)
        return delta
    }

    /** Full accumulated final text. */
    fun snapshot(): String = text.toString()
}

/** Longest k such that [current] ends with the first k chars of [incoming]. */
private fun maxOverlap(current: String, incoming: String): Int {
    val max = minOf(current.length, incoming.length)
    for (k in max downTo 1) {
        if (current.endsWith(incoming.substring(0, k))) return k
    }
    return 0
}
