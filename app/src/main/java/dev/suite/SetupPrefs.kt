package dev.suite

import android.content.Context

/** Persists onboarding state (basic-mode skip and whether full setup has ever succeeded). */
object SetupPrefs {
    private const val PREF_UI = "ui"
    private const val KEY_SKIPPED_BASIC = "setup_skipped_basic"
    private const val KEY_EVER_CONNECTED = "has_ever_connected"
    private const val KEY_SEEN_INTRO = "setup_seen_intro"
    private const val KEY_BASICS = "setup_basics_granted"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)

    fun skippedBasic(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SKIPPED_BASIC, false)

    fun setSkippedBasic(ctx: Context, skipped: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SKIPPED_BASIC, skipped).apply()
    }

    fun hasEverConnected(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_EVER_CONNECTED, false)

    fun hasSeenIntro(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SEEN_INTRO, false)

    fun setSeenIntro(ctx: Context, seen: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SEEN_INTRO, seen).apply()
    }

    /** Once per setup: grant notifications, overlay and DND access through the engine. Off the main thread. */
    fun grantBasicsOnce(ctx: Context) {
        if (prefs(ctx).getBoolean(KEY_BASICS, false) || Shell.state() != Shell.State.READY) return
        if (Shell.oneShot(ctx, "grant-basics") == "ok") prefs(ctx).edit().putBoolean(KEY_BASICS, true).apply()
    }

    fun markConnected(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean(KEY_EVER_CONNECTED, true)
            .putBoolean(KEY_SKIPPED_BASIC, false)
            .apply()
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean(KEY_SKIPPED_BASIC, false)
            .putBoolean(KEY_EVER_CONNECTED, false)
            .putBoolean(KEY_SEEN_INTRO, false)
            .putBoolean(KEY_BASICS, false)
            .commit()
    }
}
