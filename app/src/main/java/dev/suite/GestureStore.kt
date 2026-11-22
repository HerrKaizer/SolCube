package dev.suite

import android.content.Context
import dev.suite.shell.Gestures
import org.json.JSONArray
import org.json.JSONObject

/**
 * Two-finger gestures: the switch, settings, custom gestures and the user's own samples. The
 * routines engine (GestureListener) does the live recognising; routines use a gesture through
 * the "gesture" trigger ({type: "gesture", gesture: <id>}).
 */
object GestureStore {
    const val KEY = "gestures"                 // section switch
    private const val K_SENS = "gest_sens"     // 0 strict, 1 normal, 2 loose
    private const val K_VIBRATE = "gest_vibrate"
    private const val K_IGNORE = "gest_ignore"
    private const val K_CUSTOM = "gest_custom" // [{id, name}]
    private const val K_SAMPLES = "gest_samples" // [{id, s, p:[2N floats]}]

    const val SAMPLES_PER_CUSTOM = 3

    fun on(ctx: Context) = Components.enabled(ctx, KEY)
    fun sens(ctx: Context) = Components.prefs(ctx).getInt(K_SENS, 1)
    fun vibrate(ctx: Context) = Components.prefs(ctx).getBoolean(K_VIBRATE, true)

    /** Apps where gestures never fire. Defaults to the frame-interpolation games. */
    fun ignore(ctx: Context): JSONArray = Components.prefs(ctx).getString(K_IGNORE, null)
        ?.let { runCatching { JSONArray(it) }.getOrNull() }
        ?: JSONArray(Components.games(ctx).map { it.pkg })

    fun custom(ctx: Context): List<Pair<String, String>> = arr(ctx, K_CUSTOM).let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("id") to it.optString("name") }
    }

    private fun arr(ctx: Context, key: String): JSONArray =
        Components.prefs(ctx).getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    private fun samples(ctx: Context) = arr(ctx, K_SAMPLES)

    fun sampleCount(ctx: Context, id: String): Int = samples(ctx).let { a ->
        (0 until a.length()).count { a.optJSONObject(it)?.optString("id") == id }
    }

    fun name(ctx: Context, id: String): String =
        Gestures.builtinName(id) ?: custom(ctx).firstOrNull { it.first == id }?.second ?: "(deleted gesture)"

    /** Picker groups for one or two fingers: (header, [(id, name)]). One finger has no swipes. */
    fun groups(ctx: Context, fingers: Int = 2): List<Pair<String, List<Pair<String, String>>>> {
        fun g(a: Array<Array<String>>) = a.map { it[0] to it[1] }
        val out = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        if (fingers == 2) out += "Swipes" to g(Gestures.SWIPES)
        out += "Shapes" to g(Gestures.SHAPES)
        out += "Digits" to g(Gestures.DIGITS)
        val c = custom(ctx)
        if (c.isNotEmpty()) out += "Custom" to c
        return out
    }

    fun fingersText(f: Int) = if (f == 1) "one finger" else "two fingers"

    /** Why this gesture can't be drawn with one finger (null = it can). */
    fun oneFingerWhy(ctx: Context, id: String): String? {
        if (Gestures.builtinName(id) != null) return Gestures.oneFingerWhy(id)
        val a = samples(ctx)
        val mine = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id") == id }
        val ok = mine.isNotEmpty() && mine.all { o ->
            val p = o.optJSONArray("p") ?: return@all false
            Gestures.closedSample(FloatArray(p.length()) { p.optDouble(it).toFloat() }, o.optInt("s", 1))
        }
        return if (ok) null else "With one finger only closed shapes are allowed (one stroke that ends where it started), because ordinary scrolls and swipes never do that. This custom gesture isn't drawn that way."
    }

    /** Routines using this gesture with the given number of fingers. */
    fun routinesFor(ctx: Context, id: String, fingers: Int): List<JSONObject> = Routines.load(ctx).filter {
        it.optJSONObject("trigger")?.let { t ->
            t.optString("type") == "gesture" && t.optString("gesture") == id && t.optInt("fingers", 2) == fingers
        } == true
    }

    /** Everything to compare a drawing against: built-ins + the user's samples. */
    fun templates(ctx: Context): List<Gestures.Template> {
        val out = ArrayList(Gestures.builtins())
        val a = samples(ctx)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val p = o.optJSONArray("p") ?: continue
            if (p.length() != 2 * Gestures.N) continue
            out += Gestures.Template(o.optString("id"), FloatArray(p.length()) { p.optDouble(it).toFloat() }, o.optInt("s", 1).coerceAtLeast(1))
        }
        return out
    }

    private fun put(ctx: Context, key: String, v: Any) = Components.prefs(ctx).edit().putString(key, v.toString()).commit()

    fun setSens(ctx: Context, v: Int) { Components.prefs(ctx).edit().putInt(K_SENS, v.coerceIn(0, 2)).commit(); push(ctx) }
    fun setVibrate(ctx: Context, v: Boolean) { Components.prefs(ctx).edit().putBoolean(K_VIBRATE, v).commit(); push(ctx) }
    fun setIgnore(ctx: Context, a: JSONArray) { put(ctx, K_IGNORE, a); push(ctx) }
    fun setOn(ctx: Context, on: Boolean) { Components.prefs(ctx).edit().putBoolean(KEY, on).commit(); push(ctx) }

    fun addSamples(ctx: Context, id: String, drawings: List<List<FloatArray>>) {
        val a = samples(ctx)
        for (d in drawings) {
            val pts = Gestures.normalize(d) ?: continue
            a.put(JSONObject().put("id", id).put("s", d.size).put("p", JSONArray(pts.map { Math.round(it * 1000) / 1000.0 })))
        }
        put(ctx, K_SAMPLES, a)
        push(ctx)
    }

    fun clearSamples(ctx: Context, id: String) {
        val a = samples(ctx)
        val keep = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { keep.put(it) }
        put(ctx, K_SAMPLES, keep)
        push(ctx)
    }

    fun addCustom(ctx: Context, name: String, drawings: List<List<FloatArray>>): String {
        val id = "c" + System.currentTimeMillis().toString(36)
        put(ctx, K_CUSTOM, arr(ctx, K_CUSTOM).put(JSONObject().put("id", id).put("name", name)))
        addSamples(ctx, id, drawings)
        return id
    }

    fun deleteCustom(ctx: Context, id: String) {
        val a = arr(ctx, K_CUSTOM)
        val keep = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { keep.put(it) }
        put(ctx, K_CUSTOM, keep)
        clearSamples(ctx, id)
    }

    /** Routines (names) that use this gesture, either way. */
    fun usedBy(ctx: Context, id: String): List<String> = Routines.load(ctx).filter {
        it.optJSONObject("trigger")?.let { t -> t.optString("type") == "gesture" && t.optString("gesture") == id } == true
    }.map { it.optString("name") }

    /**
     * Recognises a drawing from the test pad / recorder. Swipes need a straight stroke at least
     * minSwipe long (in the drawing's units). One finger: only closed shapes, like the live listener.
     */
    fun recognize(ctx: Context, d: List<FloatArray>, minSwipe: Float, fingers: Int = 2): Gestures.Result {
        if (fingers == 1 && !Gestures.closed(d)) return Gestures.Result()
        if (fingers == 2) Gestures.swipe(d, minSwipe)?.let { sw ->
            return Gestures.Result().apply { id = sw; closest = sw; score = 1f }
        }
        if (Gestures.straight(d)) return Gestures.Result()
        return Gestures.recognize(d, templates(ctx), Gestures.minScore(sens(ctx)))
    }

    /** The existing gesture a new custom drawing is most like, and how alike (0..1). */
    fun closestOther(ctx: Context, drawings: List<List<FloatArray>>): Pair<String, Float>? {
        var best: Pair<String, Float>? = null
        val tpl = templates(ctx)
        for (d in drawings) {
            val r = Gestures.recognize(d, tpl, 2f)   // minScore > 1: only want the scores
            val c = r.closest ?: continue
            if (best == null || r.score > best.second) best = c to r.score
        }
        return best
    }

    /** Sent to the routines engine inside its config. */
    fun config(ctx: Context): JSONObject = JSONObject()
        .put("on", on(ctx))
        .put("sens", sens(ctx))
        .put("vibrate", vibrate(ctx))
        .put("ignore", ignore(ctx))
        .put("samples", samples(ctx))

    /** Re-sends the routines config (which carries the gesture settings). Off the main thread. */
    fun push(ctx: Context) {
        val s = Shell.service(Shell.Kind.ROUTINES)
        if (s != null && runCatching { s.command("gestures " + config(ctx)) }.getOrNull() == "ok") return
        Routines.save(ctx, Routines.load(ctx))
    }
}
