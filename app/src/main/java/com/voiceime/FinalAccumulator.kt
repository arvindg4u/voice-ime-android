package com.voiceime

/**
 * Accumulates finalized live-transcript fragments into one authoritative
 * string and returns only the new delta per call. Joins with longest
 * suffix/prefix overlap so a cumulative server resend collapses instead of
 * duplicating, disjoint fragments append, and exact echoes yield "" (the
 * caller skips onFinal but still completes its signal). Pure,
 * framework-free, JVM-testable.
 *
 * Known limitation: matching is text-only, so a word genuinely repeated across
 * a boundary is absorbed - current "hello" plus incoming "hello again" yields
 * "hello again" rather than "hello hello again". A cumulative resend is far
 * more common than a repeated boundary word, so the trade favours not
 * duplicating; it does mean this can silently drop a repeated word.
 *
 * Not thread-safe; call only from the socket-callback thread. Appends are
 * O(n^2) worst-case (suffix scan per append) — fine for short transcripts.
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
