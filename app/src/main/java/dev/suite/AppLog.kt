package dev.suite

import android.content.Context
import android.util.Log

/**
 * Small persistent event log for the app process, shown in Diagnostics → Recent events. On some
 * vivo builds the app's own Log.i lines never reach logcat, so without this nobody (user or
 * developer) can see why e.g. a SolEngine start failed. Keeps the last [MAX] lines in prefs.
 */
object AppLog {
    private const val MAX = 40
    private const val PREF = "app_log"
    private val lines = ArrayDeque<String>()
    @Volatile private var loaded = false
    private var ctx: Context? = null
    private val lastSeen = HashMap<String, Long>()   // text -> when it was last logged

    private fun prefs(c: Context) = c.getSharedPreferences("diag", Context.MODE_PRIVATE)

    @Synchronized
    private fun ensure(c: Context) {
        ctx = c.applicationContext
        if (loaded) return
        loaded = true
        prefs(c).getString(PREF, null)?.split('\n')?.filter { it.isNotBlank() }?.let { lines.addAll(it.takeLast(MAX)) }
    }

    /** Records one event ("HH:mm:ss text"). Any thread. */
    @Synchronized
    fun add(c: Context, text: String) {
        ensure(c)
        val now = System.currentTimeMillis()
        if (now - (lastSeen[text] ?: 0L) < 10_000L) return   // same event reported by two paths
        lastSeen[text] = now
        val t = java.time.LocalTime.now().withNano(0)
        lines.addLast("${java.time.LocalDate.now().let { "%02d-%02d".format(it.monthValue, it.dayOfMonth) }} $t $text")
        while (lines.size > MAX) lines.removeFirst()
        prefs(c).edit().putString(PREF, lines.joinToString("\n")).apply()
        Log.i("SolCube", text)
    }

    /** Last [n] lines, oldest first. */
    @Synchronized
    fun recent(c: Context, n: Int): List<String> {
        ensure(c)
        return lines.toList().takeLast(n)
    }
}
