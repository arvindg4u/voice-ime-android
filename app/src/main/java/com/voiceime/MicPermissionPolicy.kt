package com.voiceime

/**
 * Outcome of a microphone runtime-permission result for the input surface that
 * requested it.
 *
 * The IME used to resume its pending tap from `onWindowShown`: a denied result
 * re-entered `onMicTapped()`, which relaunched the permission activity and set
 * the pending flag again. On Android 11+ a repeat denial is auto-denied with no
 * dialog, so that became an activity-launch loop. [Outcome.DENIED] is therefore
 * terminal for the tap - the request is never re-armed without a new tap.
 *
 * Keeping the rule here rather than inside [VoiceImeService] makes the
 * no-re-arm invariant JVM-testable without a device or a live permission
 * dialog.
 */
internal object MicPermissionPolicy {

    enum class Outcome {
        /**
         * No tap was waiting on this result - the other input surface
         * (floating mic) opened the request, so nothing to resume here.
         */
        IGNORE,

        /** Granted: resume the tap that opened the request. */
        RESUME,

        /**
         * Denied: report it and stop. Re-entering the tap here would loop,
         * because Android auto-denies a repeat request.
         */
        DENIED,
    }

    fun outcome(pendingTap: Boolean, granted: Boolean): Outcome = when {
        !pendingTap -> Outcome.IGNORE
        granted -> Outcome.RESUME
        else -> Outcome.DENIED
    }
}
