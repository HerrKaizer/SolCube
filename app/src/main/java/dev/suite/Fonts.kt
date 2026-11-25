package dev.suite

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily

/**
 * Shared variable-font loader combining Google Sans Flex (Latin/digits) with Onest (Cyrillic)
 * at matching variable weights.
 */
object Fonts {
    private val cache = HashMap<Int, Typeface>()

    @Synchronized
    fun get(ctx: Context, weight: Int): Typeface {
        cache[weight]?.let { return it }

        val tf = try {
            val primaryFont = Font.Builder(ctx.assets, "fonts/GoogleSansFlex.ttf")
                .setFontVariationSettings("'wght' $weight")
                .build()
            val primaryFamily = FontFamily.Builder(primaryFont).build()

            val cyrFont = Font.Builder(ctx.assets, "fonts/Onest.ttf")
                .setFontVariationSettings("'wght' $weight")
                .build()
            val cyrFamily = FontFamily.Builder(cyrFont).build()

            Typeface.CustomFallbackBuilder(primaryFamily)
                .addCustomFallback(cyrFamily)
                .build()
        } catch (_: Exception) {
            try {
                Typeface.Builder(ctx.assets, "fonts/GoogleSansFlex.ttf")
                    .setFontVariationSettings("'wght' $weight")
                    .build() ?: Typeface.DEFAULT
            } catch (_: Exception) {
                Typeface.DEFAULT
            }
        }
        cache[weight] = tf
        return tf
    }
}
