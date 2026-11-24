package dev.suite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routines: stored as JSON in SolCube's prefs, run by the shell-side engine (RoutinesUserService),
 * which gets the whole list via configure(). The engine runs while at least one routine is on.
 */
object Routines {
    const val KEY = "routines_on"          // engine switch = any routine on
    private const val PREF = "routines"

    // ------------------------------------------------------------------ vocab (engine ids → words)

    val TRIGGER_GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Wi-Fi & Network" to listOf(
            "wifi_connect" to "Wi-Fi connects",
            "wifi_disconnect" to "Wi-Fi disconnects",
            "wifi_gone" to "Wi-Fi out of range",
            "wifi_off" to "Wi-Fi is off",
            "airplane_on" to "Airplane mode on",
            "airplane_off" to "Airplane mode off",
            "wifi_on" to "Wi-Fi turned on",
            "wifi_switched_off" to "Wi-Fi turned off",
            "data_on" to "Mobile data on",
            "data_off" to "Mobile data off",
        ),
        "Bluetooth & Audio" to listOf(
            "bt_connect" to "Bluetooth connects",
            "bt_disconnect" to "Bluetooth disconnects",
            "bt_on" to "Bluetooth turned on",
            "bt_off" to "Bluetooth turned off",
            "headphones_connect" to "Headphones connected",
            "headphones_disconnect" to "Headphones disconnected",
            "media_start" to "Media starts playing",
            "media_stop" to "Media stops playing",
            "call_start" to "Call starts or rings",
            "call_end" to "Call ends",
        ),
        "Battery & Power" to listOf(
            "charging_on" to "Charger connected",
            "charging_off" to "Charger disconnected",
            "battery_below" to "Battery drops below",
            "battery_above" to "Battery rises above",
            "saver_on" to "Battery saver on",
            "saver_off" to "Battery saver off",
            "boost_on" to "Boost mode on",
            "boost_off" to "Boost mode off",
            "temp_above" to "Battery temperature high",
            "temp_below" to "Battery cooled down",
        ),
        "Device & Screen" to listOf(
            "unlock" to "Phone unlocked",
            "lock" to "Phone locked",
            "screen_on" to "Screen turns on",
            "screen_off" to "Screen turns off",
            "screen_off_for" to "Screen off for a while",
            "landscape" to "Turned to landscape",
            "portrait" to "Turned to portrait",
            "dnd_on" to "Do Not Disturb on",
            "dnd_off" to "Do Not Disturb off",
            "ringer_normal" to "Sound mode: ring",
            "ringer_vibrate" to "Sound mode: vibrate",
            "ringer_silent" to "Sound mode: silent",
            "dark_on" to "Dark mode on",
            "dark_off" to "Dark mode off",
        ),
        "Apps & Games" to listOf(
            "app_open" to "App opened",
            "app_close" to "App closed",
            "game_open" to "Any game opened",
            "game_close" to "Game closed",
        ),
        "Schedule" to listOf(
            "time" to "At a time",
        ),
        "Shortcuts" to listOf(
            "two_finger_hold" to "Two-finger hold",
            "power_hold" to "Power button hold",
            "nav_hold" to "Navigation bar hold",
            "double_press" to "Double power press",
        ),
        "Gestures" to listOf(
            "gesture1" to "One-finger gesture",   // picker only: stored as type "gesture", fingers 1
            "gesture" to "Two-finger gesture",
        ),
    )

    val TRIGGERS = linkedMapOf<String, String>().apply {
        for ((_, entries) in TRIGGER_GROUPS) {
            for ((k, v) in entries) put(k, v)
        }
    }

    val CONDITION_GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Battery & Power" to listOf(
            "battery" to "Battery level",
            "charging" to "Charging",
            "saver" to "Battery saver",
            "boost" to "Boost mode",
            "temp" to "Battery temperature",
        ),
        "Connectivity" to listOf(
            "wifi_state" to "Wi-Fi state",
            "wifi_network" to "Wi-Fi network",
            "bt_state" to "Bluetooth state",
            "bt_device" to "Bluetooth device",
            "headphones" to "Headphones / audio",
            "data" to "Mobile data",
            "airplane" to "Airplane mode",
        ),
        "Device & State" to listOf(
            "screen" to "Screen / lock",
            "dnd" to "Do Not Disturb",
            "ringer" to "Sound mode",
            "dark_mode" to "Dark mode",
            "orientation" to "Orientation",
            "media_playing" to "Media playback",
            "in_call" to "Phone call",
            "app" to "Current app",
        ),
    )

    val CONDITIONS = linkedMapOf<String, String>().apply {
        for ((_, entries) in CONDITION_GROUPS) {
            for ((k, v) in entries) put(k, v)
        }
    }

    val ACTIONS = Actions.ALL

    /** The values an action's chip cycles through (empty = no value). */
    fun values(type: String): List<String> = Actions.values(type, allowRestore = true)

    fun valueText(type: String, v: String): String = Actions.valueText(type, v)

    fun defaultCondition(type: String): JSONObject = JSONObject().put("type", type).apply {
        when (type) {
            "battery" -> put("op", "below").put("value", 25)
            "charging" -> put("value", "charging").put("mode", "any")
            "wifi_state" -> put("value", "on")
            "wifi_network" -> put("op", "connected").put("ssids", JSONArray())
            "bt_state" -> put("value", "on")
            "bt_device" -> put("op", "connected").put("devices", JSONArray())
            "headphones" -> put("value", "connected")
            "data", "airplane", "dnd", "saver", "boost", "dark_mode" -> put("value", "on")
            "ringer" -> put("value", "normal")
            "screen" -> put("value", "unlocked")
            "orientation" -> put("value", "landscape")
            "media_playing" -> put("value", "playing")
            "in_call" -> put("value", "in_call")
            "app" -> put("op", "in").put("apps", JSONArray())
            "temp" -> put("op", "above").put("value", 40)
        }
    }

    fun conditionValues(type: String): List<String> = when (type) {
        "charging" -> listOf("charging", "not_charging")
        "wifi_state", "bt_state", "data", "airplane", "dnd", "saver", "boost", "dark_mode" -> listOf("on", "off")
        "headphones" -> listOf("connected", "disconnected")
        "ringer" -> listOf("normal", "vibrate", "silent")
        "screen" -> listOf("unlocked", "locked", "screen_on", "screen_off")
        "orientation" -> listOf("landscape", "portrait")
        "media_playing" -> listOf("playing", "not_playing")
        "in_call" -> listOf("in_call", "not_in_call")
        else -> emptyList()
    }

    fun conditionValueText(type: String, v: String): String = when (v) {
        "charging" -> "Charging"
        "not_charging" -> "Not charging"
        "connected" -> "Connected"
        "disconnected" -> "Disconnected"
        "unlocked" -> "Unlocked"
        "locked" -> "Locked"
        "screen_on" -> "Screen on"
        "screen_off" -> "Screen off"
        "playing" -> "Playing"
        "not_playing" -> "Not playing"
        "in_call" -> "In call"
        "not_in_call" -> "Not in call"
        else -> v.replaceFirstChar { it.uppercase() }
    }

    fun conditionText(c: JSONObject, appName: (String) -> String = { it }): String {
        val type = c.optString("type")
        return when (type) {
            "battery" -> {
                val op = if (c.optString("op", "below") == "above") "≥" else "≤"
                "Battery $op ${c.optInt("value", 25)}%"
            }
            "charging" -> {
                val base = conditionValueText(type, c.optString("value", "charging"))
                val mode = c.optString("mode", "any")
                if (c.optString("value", "charging") == "charging" && mode != "any" && mode.isNotEmpty()) "$base (${mode.uppercase()})" else base
            }
            "wifi_network" -> {
                val ssids = strList(c.optJSONArray("ssids"))
                val not = c.optString("op") == "not_connected"
                when {
                    ssids.isEmpty() -> if (not) "Not on any Wi-Fi" else "On any Wi-Fi"
                    not -> "Not on ${ssids.joinToString("/")}"
                    else -> "On ${ssids.joinToString("/")}"
                }
            }
            "bt_device" -> {
                val devs = strList(c.optJSONArray("devices"))
                val not = c.optString("op") == "not_connected"
                when {
                    devs.isEmpty() -> if (not) "No BT connected" else "Any BT connected"
                    not -> "${devs.joinToString("/")} disconnected"
                    else -> "${devs.joinToString("/")} connected"
                }
            }
            "app" -> {
                val apps = strList(c.optJSONArray("apps")).map(appName)
                val not = c.optString("op") == "not_in"
                if (apps.isEmpty()) "App condition"
                else if (not) "Not in ${apps.joinToString("/")}"
                else "In ${apps.joinToString("/")}"
            }
            "temp" -> {
                val op = if (c.optString("op", "above") == "below") "≤" else "≥"
                "Temp $op ${c.optInt("value", 40)} °C"
            }
            else -> {
                val name = CONDITIONS[type] ?: type
                val v = c.optString("value")
                if (v.isEmpty()) name else "$name: ${conditionValueText(type, v)}"
            }
        }
    }

    private fun strList(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) }

    // ------------------------------------------------------------------ storage

    fun load(ctx: Context): MutableList<JSONObject> {
        val prefs = Components.prefs(ctx)
        val raw = prefs.getString(PREF, null)
            ?: return defaults().also { save(ctx, it, push = false); prefs.edit().putInt(VERSION, 5).commit() }
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
        if (prefs.getInt(VERSION, 1) < 5) {
            // 2026-11-19: the user wants Outside only between Morning and Sleep (it fired at night
            // after Sleep turned Wi-Fi off)
            val morning = list.firstOrNull { it.optString("id") == "morning" }
            val sleep = list.firstOrNull { it.optString("id") == "sleep" }
            if (morning != null && sleep != null) list.filter { it.optString("name").trim().equals("Outside", ignoreCase = true) }
                .forEach { if (!it.has("between")) it.put("between", JSONObject().put("start", "morning").put("end", "sleep")) }
            save(ctx, list, push = false)
            prefs.edit().putInt(VERSION, 5).commit()
        }
        return list
    }

    private const val VERSION = "routines_version"

    /** Saves, switches the engine on/off to match, and sends it the new list. Call off the main thread. */
    fun save(ctx: Context, list: List<JSONObject>, push: Boolean = true) {
        val any = list.any { it.optBoolean("on", true) }
        Components.prefs(ctx).edit().putString(PREF, JSONArray(list).toString()).putBoolean(KEY, any).commit()
        if (!push) return
        SolCubeService.sync(ctx)
        if (!any) {
            Shell.stop(ctx, Shell.Kind.ROUTINES)
        } else {
            val s = Shell.service(Shell.Kind.ROUTINES)
            if (s == null) Shell.start(ctx, Shell.Kind.ROUTINES)   // configured once attached
            else runCatching { s.configure(config(ctx)) }
        }
        Components.syncCts(ctx)
    }

    fun hasEnabledTrigger(ctx: Context, type: String): Boolean = load(ctx).any { r ->
        r.optBoolean("on", true) && r.optJSONObject("trigger")?.optString("type") == type
    }

    fun needsCtsListener(ctx: Context): Boolean = load(ctx).any { r ->
        r.optBoolean("on", true) && r.optJSONObject("trigger")?.optString("type") in
            setOf("two_finger_hold", "power_hold", "nav_hold")
    }

    /** Fires any matching enabled routine(s) for a shortcut trigger. Call off the main thread. */
    fun trigger(ctx: Context, type: String): Boolean {
        if (!hasEnabledTrigger(ctx, type) || Shell.state() != Shell.State.READY) return false
        var s = Shell.service(Shell.Kind.ROUTINES)
        var newlyStarted = false
        if (s == null) {
            newlyStarted = true
            Shell.start(ctx, Shell.Kind.ROUTINES)
            val until = System.currentTimeMillis() + 3000
            while (s == null && System.currentTimeMillis() < until) {
                Thread.sleep(100)
                s = Shell.service(Shell.Kind.ROUTINES)
            }
        }
        if (s == null) return false
        if (newlyStarted) runCatching { s.configure(config(ctx)) }
        return runCatching { s.command("trigger $type") == "matched" }.getOrDefault(false)
    }

    fun config(ctx: Context): String = JSONObject().put("routines", JSONArray(load(ctx)))
        .put("gestures", GestureStore.config(ctx))
        .put("secret", Ipc.secret(ctx)).toString()

    fun newId() = "r" + System.currentTimeMillis().toString(36)

    // ------------------------------------------------------------------ text

    private val DAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun wifiGoneSeconds(t: JSONObject): Int =
        if (t.has("seconds")) t.optInt("seconds", 600).coerceAtLeast(30)
        else t.optInt("minutes", 10).coerceAtLeast(1) * 60

    fun wifiGoneDurationText(sec: Int): String =
        if (sec < 60) "$sec sec" else "${sec / 60} min"

    fun summary(r: JSONObject, all: List<JSONObject>, appName: (String) -> String = { it },
                gestureName: (String) -> String = { dev.suite.shell.Gestures.builtinName(it) ?: "custom gesture" }): String {
        val t = r.optJSONObject("trigger") ?: JSONObject()
        val ssids = strList(t.optJSONArray("ssids"))
        val nets = if (ssids.isEmpty()) "(no network)" else ssids.joinToString(" or ")
        val devs = strList(t.optJSONArray("devices"))
        val devText = if (devs.isEmpty()) "any Bluetooth" else devs.joinToString(" or ")
        val apps = strList(t.optJSONArray("apps")).map(appName)
        val appText = if (apps.isEmpty()) "(no app)" else apps.joinToString(" or ")
        val head = when (t.optString("type")) {
            "wifi_connect" -> if (t.optBoolean("any")) "Connects to any Wi-Fi" else "Connects to $nets"
            "wifi_disconnect" -> if (ssids.isEmpty()) "Wi-Fi disconnects" else "Disconnects from $nets"
            "wifi_gone" -> "$nets out of range for ${wifiGoneDurationText(wifiGoneSeconds(t))}"
            "time" -> "At ${t.optString("at", "?")}"
            "wifi_off" -> "Wi-Fi is off (checked every ${t.optInt("minutes", 3)} min)"
            "bt_connect" -> "$devText connects"
            "bt_disconnect" -> "$devText disconnects"
            "charging_on" -> {
                val m = t.optString("mode", "any")
                if (m == "any" || m.isEmpty()) "Charger connected" else "Charger (${m.uppercase()}) connected"
            }
            "charging_off" -> {
                val m = t.optString("mode", "any")
                if (m == "any" || m.isEmpty()) "Charger disconnected" else "Charger (${m.uppercase()}) disconnected"
            }
            "battery_below" -> "Battery ≤ ${t.optInt("percent", 20)}%"
            "battery_above" -> "Battery ≥ ${t.optInt("percent", 80)}%"
            "temp_above" -> "Battery temp ≥ ${t.optInt("temp", 42)} °C"
            "temp_below" -> "Battery temp ≤ ${t.optInt("temp", 38)} °C"
            "screen_off_for" -> "Screen off for ${t.optInt("minutes", 10)} min"
            "app_open" -> "When $appText opens"
            "app_close" -> "When $appText closes"
            "gesture" -> t.optString("gesture").takeIf { it.isNotEmpty() }?.let { gestureName(it) }
                ?.let { "Gesture: $it (${if (t.optInt("fingers", 2) == 1) "1 finger" else "2 fingers"})" } ?: "Gesture (none picked)"
            else -> TRIGGERS[t.optString("type")] ?: "?"
        }
        val parts = mutableListOf(head)
        days(r).takeIf { it.isNotEmpty() && it.size < 7 }?.let { parts += dayText(it) }
        val from = r.optString("from"); val to = r.optString("to")
        if (from.isNotEmpty() && to.isNotEmpty()) parts += "$from–$to"
        r.optJSONArray("conditions")?.takeIf { it.length() > 0 }?.let { cs ->
            val joiner = if (r.optString("conditionsMode") == "any") " OR " else ", "
            parts += "if " + (0 until cs.length()).joinToString(joiner) { conditionText(cs.getJSONObject(it), appName) }
        }
        r.optString("after").takeIf { it.isNotEmpty() }?.let { id ->
            parts += "after " + (all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?")
        }
        r.optString("unless").takeIf { it.isNotEmpty() }?.let { id ->
            parts += "until " + (all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?") + " runs"
        }
        r.optJSONObject("between")?.let { b ->
            fun name(id: String) = all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?"
            val s = b.optString("start"); val e = b.optString("end")
            if (s.isNotEmpty() || e.isNotEmpty())
                parts += listOfNotNull(s.takeIf { it.isNotEmpty() }?.let { "after ${name(it)}" },
                    e.takeIf { it.isNotEmpty() }?.let { "before ${name(it)}" }).joinToString(" ")
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

    fun actionsText(
        r: JSONObject,
        appName: (String) -> String = { it },
        routineName: (String) -> String = { it },
    ): String {
        val a = r.optJSONArray("actions") ?: return ""
        return (0 until a.length()).joinToString(", ") { i ->
            Actions.describe(a.getJSONObject(i), appName, routineName)
        }
    }

    /** Human-readable (whenClause, doClause) pair for the routine/gesture editor banner. */
    fun plainSummaryParts(
        r: JSONObject,
        all: List<JSONObject>,
        appName: (String) -> String = { it },
        gestureName: (String) -> String = { dev.suite.shell.Gestures.builtinName(it) ?: "custom gesture" },
        routineName: (String) -> String = { rid -> all.firstOrNull { it.optString("id") == rid }?.optString("name") ?: "?" },
    ): Pair<String, String> {
        val t = r.optJSONObject("trigger") ?: JSONObject()
        val ssids = strList(t.optJSONArray("ssids"))
        val nets = if (ssids.isEmpty()) "(no network)" else ssids.joinToString(" or ")
        val devs = strList(t.optJSONArray("devices"))
        val devText = if (devs.isEmpty()) "any Bluetooth device" else devs.joinToString(" or ")
        val apps = strList(t.optJSONArray("apps")).map(appName)
        val appText = if (apps.isEmpty()) "(no app)" else apps.joinToString(" or ")
        val type = t.optString("type")
        val head = when (type) {
            "wifi_connect" -> if (t.optBoolean("any")) "connecting to any Wi-Fi" else "connecting to $nets"
            "wifi_disconnect" -> if (ssids.isEmpty()) "Wi-Fi disconnects" else "disconnecting from $nets"
            "wifi_gone" -> "$nets is out of range for ${wifiGoneDurationText(wifiGoneSeconds(t))}"
            "time" -> "it's ${t.optString("at", "?")}"
            "wifi_off" -> "Wi-Fi is off (checked every ${t.optInt("minutes", 3)} min)"
            "bt_connect" -> "$devText connects"
            "bt_disconnect" -> "$devText disconnects"
            "charging_on" -> {
                val m = t.optString("mode", "any")
                if (m == "any" || m.isEmpty()) "charger connects" else "charger (${m.uppercase()}) connects"
            }
            "charging_off" -> {
                val m = t.optString("mode", "any")
                if (m == "any" || m.isEmpty()) "charger disconnects" else "charger (${m.uppercase()}) disconnects"
            }
            "battery_below" -> "battery drops to ${t.optInt("percent", 20)}% or below"
            "battery_above" -> "battery rises to ${t.optInt("percent", 80)}% or above"
            "temp_above" -> "battery temp reaches ${t.optInt("temp", 42)} °C"
            "temp_below" -> "battery temp cools to ${t.optInt("temp", 38)} °C"
            "screen_off_for" -> "screen is off for ${t.optInt("minutes", 10)} min"
            "app_open" -> "$appText opens"
            "app_close" -> "$appText closes"
            "gesture" -> t.optString("gesture").takeIf { it.isNotEmpty() }?.let { gid ->
                "you draw ${gestureName(gid)} (${if (t.optInt("fingers", 2) == 1) "1 finger" else "2 fingers"})"
            } ?: "a gesture is drawn (pick one below)"
            else -> TRIGGERS[type]?.let { s ->
                if (s.startsWith("Wi-Fi") || s.startsWith("Bluetooth") || s.startsWith("Do Not Disturb")) s
                else s.replaceFirstChar { it.lowercase() }
            } ?: "trigger fires"
        }

        val qualifiers = mutableListOf<String>()
        days(r).takeIf { it.isNotEmpty() && it.size < 7 }?.let { qualifiers += "on ${dayText(it)}" }
        val from = r.optString("from"); val to = r.optString("to")
        if (from.isNotEmpty() && to.isNotEmpty()) qualifiers += "between $from and $to"
        r.optJSONArray("conditions")?.takeIf { it.length() > 0 }?.let { cs ->
            val joiner = if (r.optString("conditionsMode") == "any") " or " else " and "
            qualifiers += "if " + (0 until cs.length()).joinToString(joiner) { conditionText(cs.getJSONObject(it), appName) }
        }
        r.optString("after").takeIf { it.isNotEmpty() }?.let { rid -> qualifiers += "after ${routineName(rid)}" }
        r.optString("unless").takeIf { it.isNotEmpty() }?.let { rid -> qualifiers += "until ${routineName(rid)} runs" }
        r.optJSONObject("between")?.let { b ->
            val s = b.optString("start"); val e = b.optString("end")
            if (s.isNotEmpty()) qualifiers += "after ${routineName(s)}"
            if (e.isNotEmpty()) qualifiers += "before ${routineName(e)}"
        }
        if (r.optBoolean("once")) qualifiers += "once a day"
        val whenClause = if (qualifiers.isEmpty()) head else "$head, ${qualifiers.joinToString(", ")}"

        val acts = r.optJSONArray("actions")
        val items = if (acts == null) emptyList() else (0 until acts.length()).map { i ->
            Actions.describe(acts.getJSONObject(i), appName, routineName)
        }
        val doClause = when (items.size) {
            0 -> "nothing yet (add an action below)"
            1 -> items[0]
            2 -> "${items[0]} and ${items[1]}"
            else -> "${items.dropLast(1).joinToString(", ")}, and ${items.last()}"
        }
        return whenClause to doClause
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
