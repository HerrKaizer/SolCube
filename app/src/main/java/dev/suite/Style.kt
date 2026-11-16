package dev.suite

import android.content.Context
import android.content.SharedPreferences

/** User-adjustable look of the pill, shared between the app screen and the overlay service. */
object Style {
    const val PREFS = "style"
    const val KEY_BG_OPACITY = "bgOpacity"   // 0..100 %
    const val KEY_SIZE = "size"              // 50..250 % of the base size
    const val KEY_GRAY = "grayNumbers"

    const val DEFAULT_BG_OPACITY = 60
    const val DEFAULT_SIZE = 100
    const val MIN_SIZE = 50
    const val MAX_SIZE = 250

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
