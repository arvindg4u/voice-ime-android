package com.voiceime

import android.text.InputType

/**
 * Is this editor a password field?
 *
 * This is the single source of truth for a security-critical question, because
 * three call sites guard on it - the IME (commit + clipboard history), the
 * clipboard capture gate, and the floating accessibility overlay (focus veto
 * and insertion). They used to carry three independent copies of the rule, so
 * adding a new variation to one of them would silently leave the others
 * unguarded, which is a leak. Add variations here, in exactly one place.
 *
 * Covers all four framework password variations, including "visible password"
 * (after the show-password eye is toggled, where [android.view.accessibility.AccessibilityNodeInfo.isPassword]
 * often flips to false) and the web-password variation used by Chromium inputs.
 */
internal object PasswordFieldPolicy {

    fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT && cls != InputType.TYPE_CLASS_NUMBER) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}
