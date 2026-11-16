package dev.suite

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * All of Suite's settings (every SharedPreferences file: switches, routines, games, pill, …) as
 * one JSON file. Saved by itself to Download/Suite/suite-backup.json whenever the app is left,
 * so a reinstall can bring everything back with "Restore". (Installing an update over the old
 * app keeps the settings anyway; this is for uninstalls and new phones.)
 */
object Backup {
    const val NAME = "suite-backup.json"
    private val DIR = Environment.DIRECTORY_DOWNLOADS + "/Suite/"
    private val SKIP = setOf("updates")   // the update checker's cache

    private fun files(ctx: Context): List<String> =
        File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.filter { it.name.endsWith(".xml") }?.map { it.name.removeSuffix(".xml") }
            ?.filter { it !in SKIP } ?: emptyList()

    fun export(ctx: Context): String {
        val root = JSONObject()
        root.put("app", "dev.suite")
        root.put("version", Updates.current(ctx))
        root.put("saved", System.currentTimeMillis())
        val all = JSONObject()
        for (f in files(ctx)) {
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(f, Context.MODE_PRIVATE).all) {
                val e = JSONObject()
                when (v) {
                    is Boolean -> e.put("t", "b")
                    is Int -> e.put("t", "i")
                    is Long -> e.put("t", "l")
                    is Float -> e.put("t", "f")
                    is String -> e.put("t", "s")
                    is Set<*> -> e.put("t", "set")
                    else -> continue
                }
                e.put("v", if (v is Set<*>) JSONArray(v.toList()) else v)
                o.put(k, e)
            }
            all.put(f, o)
        }
        root.put("prefs", all)
        return root.toString(1)
    }

    /** Replaces the settings with the backup's. Returns false for a file that isn't a Suite backup. */
    fun import(ctx: Context, text: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (root.optString("app") != "dev.suite") return false
        val all = root.optJSONObject("prefs") ?: return false
        for (f in all.keys()) {
            if (f in SKIP) continue
            val o = all.getJSONObject(f)
            val ed = ctx.getSharedPreferences(f, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                when (e.optString("t")) {
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> ed.putString(k, e.getString("v"))
                    "set" -> e.getJSONArray("v").let { a -> ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            ed.commit()
        }
        return true
    }

    /** Writes the backup to Download/Suite/suite-backup.json (no permission needed). Off the main thread. */
    fun autoSave(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // only finds the copy this install made; after a reinstall a new copy gets written next to it
        val existing = cr.query(col, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(NAME, DIR), null)?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(col, c.getLong(0).toString()) else null }
        val uri = existing ?: cr.insert(col, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, DIR)
        })
        uri != null && (cr.openOutputStream(uri, "wt")?.use { it.write(export(ctx).toByteArray()); true } ?: false)
    } catch (e: Exception) {
        Log.w("Suite", "backup failed: $e")
        false
    }
}
