package com.screenly.app

import android.content.Context

/**
 * In-app affirmative consent is separate from Android's accessibility service toggle.
 * Until both are granted, Screenly does not observe third-party interfaces.
 */
internal object AccessibilityConsent {
    private const val PREFERENCES = "screenly_accessibility_consent"
    private const val GRANTED = "granted"

    fun isGranted(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(GRANTED, false)

    fun setGranted(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(GRANTED, granted).apply()
    }
}
