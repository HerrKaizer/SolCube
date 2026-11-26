package dev.suite

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily

/**
 * Shared variable-font loader combining Google Sans Flex (Latin/digits) with Onest (Cyrillic)
 * at matching variable weights.
 *
 * The fonts are stored compressed in the APK (saves ~2 MB) and copied once per install to app
 * storage, so every weight maps the same file instead of each holding its own 4 MB copy in memory.
 */
object Fonts {
    private val cache = HashMap<Int, Typeface>()

    private fun file(ctx: Context, name: String): java.io.File {
        val stamp = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val f = java.io.File(ctx.noBackupFilesDir, "fonts/$stamp-$name")
        if (!f.exists()) {
            f.parentFile?.let { dir -> dir.mkdirs(); dir.listFiles()?.filter { it.name.endsWith("-$name") }?.forEach { it.delete() } }
            val tmp = java.io.File(f.path + ".tmp")
            ctx.assets.open("fonts/$name").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(f)
        }
        return f
    }

    @Synchronized
    fun get(ctx: Context, weight: Int): Typeface {
        cache[weight]?.let { return it }

        val tf = try {
            val primaryFont = Font.Builder(file(ctx, "GoogleSansFlex.ttf"))
                .setFontVariationSettings("'wght' $weight")
                .build()
            val primaryFamily = FontFamily.Builder(primaryFont).build()

            val cyrFont = Font.Builder(file(ctx, "Onest.ttf"))
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
