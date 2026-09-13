package com.voiceime

/**
 * Hands out the id of the live session currently allowed to write the
 * service's transcript buffers. Callbacks must capture the id returned by
 * [next] at session start and check [isCurrent] on every fire — never read
 * a mutable sequence counter at fire time (all live sessions would read the
 * same converged value and the guard would accept everything, including a
 * previous session's late callback). Pure, framework-free, JVM-testable.
 */
internal class LiveSessionGuard {
    private var current = 0L

    /** Starts a new session era; returns its id (monotonic, from 1). */
    fun next(): Long {
        current += 1
        return current
    }

    /** True only for the id of the latest started session. */
    fun isCurrent(id: Long): Boolean = id != 0L && id == current
}
