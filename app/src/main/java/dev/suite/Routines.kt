package dev.suite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routines: stored as JSON in Suite's prefs, run by the shell-side engine (RoutinesUserService),
 * which gets the whole list via configure(). The engine runs while at least one routine is on.
 */
object Routines {
    const val KEY = "routines_on"          // engine switch = any routine on
    private const val PREF = "routines"

    // ------------------------------------------------------------------ vocab (engine ids → words)

    val TRIGGERS = linkedMapOf(
        "wifi_connect" to "Wi-Fi connects",
        "wifi_gone" to "Wi-Fi out of range",
        "wifi_off" to "Wi-Fi is off",
        "dnd_on" to "Do Not Disturb on",
        "dnd_off" to "Do Not Disturb off",
        "unlock" to "Phone unlocked",
        "screen_on" to "Screen turns on",
        "time" to "At a time",
    )

    val ACTIONS = linkedMapOf(
        "data" to "Mobile data",
        "wifi" to "Wi-Fi",
        "airplane" to "Airplane mode",
        "saver" to "Battery saver",
        "dnd" to "Do Not Disturb",
        "ringer" to "Sound mode",
        "media" to "Media volume",
        "close_apps" to "Close all apps",
        "revive" to "Revive apps",
        "lock" to "Lock screen",
        "vibrate" to "Vibrate (done)",
        "wait" to "Wait",
    )

    /** The values an action's chip cycles through (empty = no value). */
    fun values(type: String): List<String> = when (type) {
        "ringer" -> listOf("vibrate", "silent", "normal", "restore")
        "media" -> listOf("0", "25", "50", "75", "100", "restore")
        "wait" -> listOf("1", "3", "5", "10")
        "close_apps", "lock", "vibrate", "revive" -> emptyList()
        else -> listOf("off", "on", "restore")
    }

    fun valueText(type: String, v: String): String = when {
        v == "restore" -> "Back to before"
        type == "media" -> "$v%"
        type == "wait" -> "$v s"
        else -> v.replaceFirstChar { it.uppercase() }
    }

    // ------------------------------------------------------------------ storage

    fun load(ctx: Context): MutableList<JSONObject> {
        val prefs = Components.prefs(ctx)
        val raw = prefs.getString(PREF, null)
            ?: return defaults().also { save(ctx, it, push = false); prefs.edit().putInt(VERSION, 4).commit() }
        val list = runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() } }
            .getOrElse { defaults() }
        if (prefs.getInt(VERSION, 1) < 2) {
            // 2026-11-15: user asked for the "done" vibration on every routine, and a Wi-Fi check before Work
            list.forEach { r ->
                val a = r.optJSONArray("actions") ?: JSONArray().also { r.put("actions", it) }
                if ((0 until a.length()).none { a.getJSONObject(it).optString("type") == "vibrate" }) a.put(act("vibrate"))
            }
            if (list.none { it.optString("id") == "wificheck" }) {
                val i = list.indexOfFirst { it.optString("id") == "work" }
                list.add(if (i >= 0) i + 1 else list.size, wifiCheck())
            }
            save(ctx, list, push = false)
            prefs.edit().putInt(VERSION, 2).commit()
        }
        if (prefs.getInt(VERSION, 1) < 3) {
            // the user wanted the Wi-Fi check folded into Work (less clutter): it becomes Work's "wifiGuard"
            val check = list.firstOrNull { it.optString("id") == "wificheck" }
            val work = list.firstOrNull { it.optString("id") == "work" }
            if (check != null && work != null && !work.has("wifiGuard")) {
                work.put("wifiGuard", JSONObject().put("on", check.optBoolean("on", true))
                    .put("from", check.optString("from", "08:30")).put("to", check.optString("to", "09:20"))
                    .put("minutes", check.optJSONObject("trigger")?.optInt("minutes", 3) ?: 3))
            }
            if (check != null) list.remove(check)
            save(ctx, list, push = false)
            prefs.edit().putInt(VERSION, 3).commit()
        }
        if (prefs.getInt(VERSION, 1) < 4) {
            // Sleep now force-stops background apps too; Morning revives the user's chosen ones
            list.firstOrNull { it.optString("id") == "morning" }?.let { r ->
                val a = r.optJSONArray("actions") ?: JSONArray().also { r.put("actions", it) }
                if ((0 until a.length()).none { a.getJSONObject(it).optString("type") == "revive" }) {
                    val v = (0 until a.length()).firstOrNull { a.getJSONObject(it).optString("type") == "vibrate" }
                    val items = (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
                    items.add(v ?: items.size, reviveAction())
                    r.put("actions", JSONArray(items))
                }
            }
            save(ctx, list, push = false)
            prefs.edit().putInt(VERSION, 4).commit()
        }
        return list
    }

    private const val VERSION = "routines_version"

    /** Saves, switches the engine on/off to match, and sends it the new list. Call off the main thread. */
    fun save(ctx: Context, list: List<JSONObject>, push: Boolean = true) {
        val any = list.any { it.optBoolean("on", true) }
        Components.prefs(ctx).edit().putString(PREF, JSONArray(list).toString()).putBoolean(KEY, any).commit()
        if (!push) return
        SuiteService.sync(ctx)
        if (!any) { Shell.stop(ctx, Shell.Kind.ROUTINES); return }
        val s = Shell.service(Shell.Kind.ROUTINES)
        if (s == null) Shell.start(ctx, Shell.Kind.ROUTINES)   // configured once attached
        else runCatching { s.configure(config(ctx)) }
    }

    fun config(ctx: Context): String = JSONObject().put("routines", JSONArray(load(ctx))).toString()

    fun newId() = "r" + System.currentTimeMillis().toString(36)

    // ------------------------------------------------------------------ text

    private val DAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun summary(r: JSONObject, all: List<JSONObject>): String {
        val t = r.optJSONObject("trigger") ?: JSONObject()
        val ssids = t.optJSONArray("ssids")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val nets = if (ssids.isEmpty()) "(no network)" else ssids.joinToString(" or ")
        val head = when (t.optString("type")) {
            "wifi_connect" -> "Connects to $nets"
            "wifi_gone" -> "$nets out of range for ${t.optInt("minutes", 10)} min"
            "time" -> "At ${t.optString("at", "?")}"
            "wifi_off" -> "Wi-Fi is off (checked every ${t.optInt("minutes", 3)} min)"
            else -> TRIGGERS[t.optString("type")] ?: "?"
        }
        val parts = mutableListOf(head)
        days(r).takeIf { it.isNotEmpty() && it.size < 7 }?.let { parts += dayText(it) }
        val from = r.optString("from"); val to = r.optString("to")
        if (from.isNotEmpty() && to.isNotEmpty()) parts += "$from–$to"
        r.optString("after").takeIf { it.isNotEmpty() }?.let { id ->
            parts += "after " + (all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?")
        }
        r.optString("unless").takeIf { it.isNotEmpty() }?.let { id ->
            parts += "until " + (all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?") + " runs"
        }
        return parts.joinToString(" · ")
    }

    fun days(r: JSONObject): List<Int> = r.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()

    fun dayText(d: List<Int>): String {
        val s = d.sorted()
        if (s == listOf(1, 2, 3, 4, 5)) return "Mon–Fri"
        if (s == listOf(6, 7)) return "Weekends"
        return s.joinToString(", ") { DAY[it - 1] }
    }

    fun actionsText(r: JSONObject): String {
        val a = r.optJSONArray("actions") ?: return ""
        return (0 until a.length()).joinToString(", ") { i ->
            val o = a.getJSONObject(i)
            val type = o.optString("type")
            val name = ACTIONS[type] ?: type
            if (type == "revive") "Revive ${o.optJSONArray("apps")?.length() ?: 0} apps"
            else if (values(type).isEmpty()) name else "$name ${valueText(type, o.optString("value")).lowercase()}"
        }
    }

    // ------------------------------------------------------------------ the user's routines (2026-11-15)

    private fun act(type: String, value: String = "") = JSONObject().put("type", type).apply { if (value.isNotEmpty()) put("value", value) }

    /** The user's pick (2026-11-16): Ayugram, Max, KWGT, Google, VK Messenger. */
    val REVIVE_DEFAULT = emptyList<String>()

    private fun reviveAction() = act("revive").put("apps", JSONArray(REVIVE_DEFAULT))

    /** (v2 only, migrated into Work's wifiGuard in v3) */
    private fun wifiCheck() = JSONObject().put("id", "wificheck").put("name", "Wi-Fi check").put("on", true)
        .put("trigger", JSONObject().put("type", "wifi_off").put("minutes", 3))
        .put("days", JSONArray(listOf(1, 2, 3, 4, 5))).put("from", "08:30").put("to", "09:20").put("unless", "work")
        .put("actions", JSONArray(listOf(act("wifi", "on"), act("vibrate"))))

    /** "Keep Wi-Fi on before it runs": the routine's days, this window, every N min, until it ran today. */
    fun guardText(r: JSONObject): String? {
        val g = r.optJSONObject("wifiGuard") ?: return null
        if (!g.optBoolean("on", true)) return null
        return "Keeps Wi-Fi on ${g.optString("from")}–${g.optString("to")} (checks every ${g.optInt("minutes", 3)} min) until it runs"
    }

    private fun defaults(): MutableList<JSONObject> {
        val office = JSONArray()
        val weekdays = JSONArray(listOf(1, 2, 3, 4, 5))
        return mutableListOf(
            JSONObject().put("id", "work").put("name", "Work").put("on", true)
                .put("trigger", JSONObject().put("type", "wifi_connect").put("ssids", office))
                .put("days", weekdays).put("from", "08:40").put("to", "09:15").put("once", true)
                .put("wifiGuard", JSONObject().put("on", true).put("from", "08:30").put("to", "09:20").put("minutes", 3))
                .put("actions", JSONArray(listOf(act("data", "off"), act("ringer", "vibrate"), act("media", "0"), act("vibrate")))),
            JSONObject().put("id", "leave").put("name", "Leave work").put("on", true)
                .put("trigger", JSONObject().put("type", "wifi_gone").put("ssids", office).put("minutes", 10))
                .put("days", weekdays).put("from", "14:00").put("to", "16:00").put("after", "work")
                .put("actions", JSONArray(listOf(act("data", "restore"), act("ringer", "restore"), act("media", "restore"), act("vibrate")))),
            JSONObject().put("id", "sleep").put("name", "Sleep").put("on", true)
                .put("trigger", JSONObject().put("type", "dnd_on"))
                .put("actions", JSONArray(listOf(act("close_apps"), act("airplane", "on"), act("wifi", "off"),
                    act("saver", "on"), act("lock"), act("vibrate")))),
            JSONObject().put("id", "morning").put("name", "Morning").put("on", true)
                .put("trigger", JSONObject().put("type", "unlock"))
                .put("from", "05:00").put("to", "12:00").put("after", "sleep")
                .put("actions", JSONArray(listOf(act("airplane", "off"), act("wifi", "on"), act("saver", "off"), act("dnd", "off"), reviveAction(), act("vibrate")))),
        )
    }
}
