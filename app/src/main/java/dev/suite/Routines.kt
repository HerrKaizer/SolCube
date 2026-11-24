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

    fun conditionValueText(type: String, v: String): String {
        val raw = when (v) {
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
        return I18n.t(raw)
    }

    fun conditionText(c: JSONObject, appName: (String) -> String = { it }): String {
        val type = c.optString("type")
        val ru = I18n.current() == I18n.LANG_RU
        val pt = I18n.current() == I18n.LANG_PT
        return when (type) {
            "battery" -> {
                val op = if (c.optString("op", "below") == "above") "≥" else "≤"
                val lbl = when { ru -> "Заряд"; pt -> "Bateria"; else -> "Battery" }
                "$lbl $op ${c.optInt("value", 25)}%"
            }
            "charging" -> {
                val base = conditionValueText(type, c.optString("value", "charging"))
                val mode = c.optString("mode", "any")
                if (c.optString("value", "charging") == "charging" && mode != "any" && mode.isNotEmpty()) "$base (${I18n.t(mode.uppercase())})" else base
            }
            "wifi_network" -> {
                val ssids = strList(c.optJSONArray("ssids"))
                val not = c.optString("op") == "not_connected"
                when {
                    ssids.isEmpty() -> when {
                        ru -> if (not) "Не подключён к Wi-Fi" else "В любой сети Wi-Fi"
                        pt -> if (not) "Sem Wi-Fi" else "Em qualquer Wi-Fi"
                        else -> if (not) "Not on any Wi-Fi" else "On any Wi-Fi"
                    }
                    not -> when {
                        ru -> "Не в ${ssids.joinToString("/")}"
                        pt -> "Fora de ${ssids.joinToString("/")}"
                        else -> "Not on ${ssids.joinToString("/")}"
                    }
                    else -> when {
                        ru -> "В ${ssids.joinToString("/")}"
                        pt -> "Em ${ssids.joinToString("/")}"
                        else -> "On ${ssids.joinToString("/")}"
                    }
                }
            }
            "bt_device" -> {
                val devs = strList(c.optJSONArray("devices"))
                val not = c.optString("op") == "not_connected"
                when {
                    devs.isEmpty() -> when {
                        ru -> if (not) "Bluetooth не подключён" else "Любое устройство Bluetooth"
                        pt -> if (not) "Nenhum Bluetooth" else "Qualquer Bluetooth"
                        else -> if (not) "No BT connected" else "Any BT connected"
                    }
                    not -> "${devs.joinToString("/")} " + when { ru -> "откл."; pt -> "desconectado"; else -> "disconnected" }
                    else -> "${devs.joinToString("/")} " + when { ru -> "подкл."; pt -> "conectado"; else -> "connected" }
                }
            }
            "app" -> {
                val apps = strList(c.optJSONArray("apps")).map(appName)
                val not = c.optString("op") == "not_in"
                if (apps.isEmpty()) when { ru -> "Текущее приложение"; pt -> "App atual"; else -> "App condition" }
                else if (not) when {
                    ru -> "Не в ${apps.joinToString("/")}"
                    pt -> "Fora de ${apps.joinToString("/")}"
                    else -> "Not in ${apps.joinToString("/")}"
                }
                else when {
                    ru -> "В ${apps.joinToString("/")}"
                    pt -> "Em ${apps.joinToString("/")}"
                    else -> "In ${apps.joinToString("/")}"
                }
            }
            "temp" -> {
                val op = if (c.optString("op", "above") == "below") "≤" else "≥"
                val lbl = when { ru -> "Темп."; pt -> "Temp."; else -> "Temp" }
                "$lbl $op ${c.optInt("value", 40)} °C"
            }
            else -> {
                val name = I18n.t(CONDITIONS[type] ?: type)
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

    fun wifiGoneDurationText(sec: Int): String {
        val ru = I18n.current() == I18n.LANG_RU
        return if (sec < 60) "$sec " + (if (ru) "с" else "sec").let { if (I18n.current() == I18n.LANG_PT) "s" else it }
        else "${sec / 60} " + if (ru) "мин" else "min"
    }

    fun summary(r: JSONObject, all: List<JSONObject>, appName: (String) -> String = { it },
                gestureName: (String) -> String = { dev.suite.shell.Gestures.builtinName(it)?.let { g -> I18n.t(g) } ?: I18n.t("custom gesture") }): String {
        val t = r.optJSONObject("trigger") ?: JSONObject()
        val ru = I18n.current() == I18n.LANG_RU
        val pt = I18n.current() == I18n.LANG_PT
        val orWord = when { ru -> " или "; pt -> " ou "; else -> " or " }
        val ssids = strList(t.optJSONArray("ssids"))
        val nets = if (ssids.isEmpty()) when { ru -> "(сеть не задана)"; pt -> "(sem rede)"; else -> "(no network)" } else ssids.joinToString(orWord)
        val devs = strList(t.optJSONArray("devices"))
        val devText = if (devs.isEmpty()) when { ru -> "любой Bluetooth"; pt -> "qualquer Bluetooth"; else -> "any Bluetooth" } else devs.joinToString(orWord)
        val apps = strList(t.optJSONArray("apps")).map(appName)
        val appText = if (apps.isEmpty()) when { ru -> "(не выбрано)"; pt -> "(sem app)"; else -> "(no app)" } else apps.joinToString(orWord)
        val head = when (t.optString("type")) {
            "wifi_connect" -> when {
                ru -> if (t.optBoolean("any")) "Подключение к любому Wi-Fi" else "Подключение к $nets"
                pt -> if (t.optBoolean("any")) "Conecta a qualquer Wi-Fi" else "Conecta a $nets"
                else -> if (t.optBoolean("any")) "Connects to any Wi-Fi" else "Connects to $nets"
            }
            "wifi_disconnect" -> when {
                ru -> if (ssids.isEmpty()) "Отключение от Wi-Fi" else "Отключение от $nets"
                pt -> if (ssids.isEmpty()) "Wi-Fi desconecta" else "Desconecta de $nets"
                else -> if (ssids.isEmpty()) "Wi-Fi disconnects" else "Disconnects from $nets"
            }
            "wifi_gone" -> when {
                ru -> "$nets вне зоны ${wifiGoneDurationText(wifiGoneSeconds(t))}"
                pt -> "$nets fora de alcance por ${wifiGoneDurationText(wifiGoneSeconds(t))}"
                else -> "$nets out of range for ${wifiGoneDurationText(wifiGoneSeconds(t))}"
            }
            "time" -> (when { ru -> "В "; pt -> "Às "; else -> "At " }) + t.optString("at", "?")
            "wifi_off" -> when {
                ru -> "Wi-Fi выкл. (каждые ${t.optInt("minutes", 3)} мин)"
                pt -> "Wi-Fi desl. (a cada ${t.optInt("minutes", 3)} min)"
                else -> "Wi-Fi is off (checked every ${t.optInt("minutes", 3)} min)"
            }
            "bt_connect" -> when { ru -> "$devText подключается"; pt -> "$devText conecta"; else -> "$devText connects" }
            "bt_disconnect" -> when { ru -> "$devText отключается"; pt -> "$devText desconecta"; else -> "$devText disconnects" }
            "charging_on" -> {
                val m = t.optString("mode", "any")
                val base = I18n.t("Charger connected")
                if (m == "any" || m.isEmpty()) base else "$base (${I18n.t(m.uppercase())})"
            }
            "charging_off" -> {
                val m = t.optString("mode", "any")
                val base = I18n.t("Charger disconnected")
                if (m == "any" || m.isEmpty()) base else "$base (${I18n.t(m.uppercase())})"
            }
            "battery_below" -> when {
                ru -> "Заряд ≤ ${t.optInt("percent", 20)}%"
                pt -> "Bateria ≤ ${t.optInt("percent", 20)}%"
                else -> "Battery ≤ ${t.optInt("percent", 20)}%"
            }
            "battery_above" -> when {
                ru -> "Заряд ≥ ${t.optInt("percent", 80)}%"
                pt -> "Bateria ≥ ${t.optInt("percent", 80)}%"
                else -> "Battery ≥ ${t.optInt("percent", 80)}%"
            }
            "temp_above" -> when {
                ru -> "Темп. батареи ≥ ${t.optInt("temp", 42)} °C"
                pt -> "Temp. bateria ≥ ${t.optInt("temp", 42)} °C"
                else -> "Battery temp ≥ ${t.optInt("temp", 42)} °C"
            }
            "temp_below" -> when {
                ru -> "Темп. батареи ≤ ${t.optInt("temp", 38)} °C"
                pt -> "Temp. bateria ≤ ${t.optInt("temp", 38)} °C"
                else -> "Battery temp ≤ ${t.optInt("temp", 38)} °C"
            }
            "screen_off_for" -> when {
                ru -> "Экран выкл. ${t.optInt("minutes", 10)} мин"
                pt -> "Tela desl. por ${t.optInt("minutes", 10)} min"
                else -> "Screen off for ${t.optInt("minutes", 10)} min"
            }
            "app_open" -> when {
                ru -> "При открытии $appText"
                pt -> "Quando $appText abrir"
                else -> "When $appText opens"
            }
            "app_close" -> when {
                ru -> "При закрытии $appText"
                pt -> "Quando $appText fechar"
                else -> "When $appText closes"
            }
            "gesture" -> t.optString("gesture").takeIf { it.isNotEmpty() }?.let { gestureName(it) }
                ?.let { g ->
                    val fStr = I18n.t(if (t.optInt("fingers", 2) == 1) "one finger" else "two fingers")
                    val lbl = when { ru -> "Жест"; pt -> "Gesto"; else -> "Gesture" }
                    "$lbl: $g ($fStr)"
                } ?: when { ru -> "Жест (не выбран)"; pt -> "Gesto (nenhum)"; else -> "Gesture (none picked)" }
            else -> TRIGGERS[t.optString("type")]?.let { I18n.t(it) } ?: "?"
        }
        val parts = mutableListOf(head)
        days(r).takeIf { it.isNotEmpty() && it.size < 7 }?.let { parts += dayText(it) }
        val from = r.optString("from"); val to = r.optString("to")
        if (from.isNotEmpty() && to.isNotEmpty()) parts += "$from–$to"
        r.optJSONArray("conditions")?.takeIf { it.length() > 0 }?.let { cs ->
            val joiner = if (r.optString("conditionsMode") == "any") when { ru -> " ИЛИ "; pt -> " OU "; else -> " OR " } else ", "
            val ifW = when { ru -> "если "; pt -> "se "; else -> "if " }
            parts += ifW + (0 until cs.length()).joinToString(joiner) { conditionText(cs.getJSONObject(it), appName) }
        }
        r.optString("after").takeIf { it.isNotEmpty() }?.let { id ->
            val pfx = when { ru -> "после "; pt -> "após "; else -> "after " }
            parts += pfx + (all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?")
        }
        r.optString("unless").takeIf { it.isNotEmpty() }?.let { id ->
            val nm = all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?"
            parts += when { ru -> "пока не сработает $nm"; pt -> "até $nm rodar"; else -> "until $nm runs" }
        }
        r.optJSONObject("between")?.let { b ->
            fun name(id: String) = all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?"
            val s = b.optString("start"); val e = b.optString("end")
            if (s.isNotEmpty() || e.isNotEmpty()) {
                val pA = when { ru -> "после "; pt -> "após "; else -> "after " }
                val pB = when { ru -> "до "; pt -> "antes de "; else -> "before " }
                parts += listOfNotNull(s.takeIf { it.isNotEmpty() }?.let { pA + name(it) },
                    e.takeIf { it.isNotEmpty() }?.let { pB + name(it) }).joinToString(" ")
            }
        }
        return parts.joinToString(" · ")
    }

    fun days(r: JSONObject): List<Int> = r.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()

    fun dayText(d: List<Int>): String {
        val s = d.sorted()
        if (s == listOf(1, 2, 3, 4, 5)) return I18n.t("Mon–Fri")
        if (s == listOf(6, 7)) return I18n.t("Weekends")
        return s.joinToString(", ") { I18n.t(DAY[it - 1]) }
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
        gestureName: (String) -> String = { dev.suite.shell.Gestures.builtinName(it)?.let { g -> I18n.t(g) } ?: I18n.t("custom gesture") },
        routineName: (String) -> String = { rid -> all.firstOrNull { it.optString("id") == rid }?.optString("name") ?: "?" },
    ): Pair<String, String> {
        val t = r.optJSONObject("trigger") ?: JSONObject()
        val ru = I18n.current() == I18n.LANG_RU
        val pt = I18n.current() == I18n.LANG_PT
        val orWord = when { ru -> " или "; pt -> " ou "; else -> " or " }
        val andWord = when { ru -> " и "; pt -> " e "; else -> " and " }
        val ssids = strList(t.optJSONArray("ssids"))
        val nets = if (ssids.isEmpty()) when { ru -> "(сеть не задана)"; pt -> "(sem rede)"; else -> "(no network)" } else ssids.joinToString(orWord)
        val devs = strList(t.optJSONArray("devices"))
        val devText = if (devs.isEmpty()) when { ru -> "любое устройство Bluetooth"; pt -> "qualquer dispositivo Bluetooth"; else -> "any Bluetooth device" } else devs.joinToString(orWord)
        val apps = strList(t.optJSONArray("apps")).map(appName)
        val appText = if (apps.isEmpty()) when { ru -> "(приложение не выбрано)"; pt -> "(nenhum app)"; else -> "(no app)" } else apps.joinToString(orWord)
        val type = t.optString("type")
        val head = when (type) {
            "wifi_connect" -> when {
                ru -> if (t.optBoolean("any")) "телефон подключается к любому Wi-Fi" else "телефон подключается к $nets"
                pt -> if (t.optBoolean("any")) "conectar a qualquer Wi-Fi" else "conectar a $nets"
                else -> if (t.optBoolean("any")) "connecting to any Wi-Fi" else "connecting to $nets"
            }
            "wifi_disconnect" -> when {
                ru -> if (ssids.isEmpty()) "Wi-Fi отключается" else "Wi-Fi отключается от $nets"
                pt -> if (ssids.isEmpty()) "o Wi-Fi desconectar" else "desconectar de $nets"
                else -> if (ssids.isEmpty()) "Wi-Fi disconnects" else "disconnecting from $nets"
            }
            "wifi_gone" -> when {
                ru -> "сеть $nets недоступна в течение ${wifiGoneDurationText(wifiGoneSeconds(t))}"
                pt -> "$nets ficar fora de alcance por ${wifiGoneDurationText(wifiGoneSeconds(t))}"
                else -> "$nets is out of range for ${wifiGoneDurationText(wifiGoneSeconds(t))}"
            }
            "time" -> when {
                ru -> "время достигает ${t.optString("at", "?")}"
                pt -> "for ${t.optString("at", "?")}"
                else -> "it's ${t.optString("at", "?")}"
            }
            "wifi_off" -> when {
                ru -> "Wi-Fi выключен (проверка каждые ${t.optInt("minutes", 3)} мин)"
                pt -> "o Wi-Fi estiver desligado (checando a cada ${t.optInt("minutes", 3)} min)"
                else -> "Wi-Fi is off (checked every ${t.optInt("minutes", 3)} min)"
            }
            "bt_connect" -> when { ru -> "подключается $devText"; pt -> "$devText conectar"; else -> "$devText connects" }
            "bt_disconnect" -> when { ru -> "отключается $devText"; pt -> "$devText desconectar"; else -> "$devText disconnects" }
            "charging_on" -> {
                val m = t.optString("mode", "any")
                val ms = if (m == "any" || m.isEmpty()) "" else " (${I18n.t(m.uppercase())})"
                when { ru -> "подключается зарядка$ms"; pt -> "o carregador$ms conectar"; else -> "charger$ms connects" }
            }
            "charging_off" -> {
                val m = t.optString("mode", "any")
                val ms = if (m == "any" || m.isEmpty()) "" else " (${I18n.t(m.uppercase())})"
                when { ru -> "отключается зарядка$ms"; pt -> "o carregador$ms desconectar"; else -> "charger$ms disconnects" }
            }
            "battery_below" -> when {
                ru -> "заряд опускается до ${t.optInt("percent", 20)}% или ниже"
                pt -> "a bateria cair para ${t.optInt("percent", 20)}% ou menos"
                else -> "battery drops to ${t.optInt("percent", 20)}% or below"
            }
            "battery_above" -> when {
                ru -> "заряд поднимается до ${t.optInt("percent", 80)}% или выше"
                pt -> "a bateria subir para ${t.optInt("percent", 80)}% ou mais"
                else -> "battery rises to ${t.optInt("percent", 80)}% or above"
            }
            "temp_above" -> when {
                ru -> "температура батареи достигает ${t.optInt("temp", 42)} °C"
                pt -> "a bateria atingir ${t.optInt("temp", 42)} °C"
                else -> "battery temp reaches ${t.optInt("temp", 42)} °C"
            }
            "temp_below" -> when {
                ru -> "температура батареи снижается до ${t.optInt("temp", 38)} °C"
                pt -> "a bateria esfriar para ${t.optInt("temp", 38)} °C"
                else -> "battery temp cools to ${t.optInt("temp", 38)} °C"
            }
            "screen_off_for" -> when {
                ru -> "экран выключен ${t.optInt("minutes", 10)} мин"
                pt -> "a tela ficar desligada por ${t.optInt("minutes", 10)} min"
                else -> "screen is off for ${t.optInt("minutes", 10)} min"
            }
            "app_open" -> when { ru -> "открывается $appText"; pt -> "$appText abrir"; else -> "$appText opens" }
            "app_close" -> when { ru -> "закрывается $appText"; pt -> "$appText fechar"; else -> "$appText closes" }
            "gesture" -> t.optString("gesture").takeIf { it.isNotEmpty() }?.let { gid ->
                val gName = gestureName(gid)
                val fStr = I18n.t(if (t.optInt("fingers", 2) == 1) "one finger" else "two fingers")
                when {
                    ru -> "вы рисуете «$gName» ($fStr)"
                    pt -> "você desenhar $gName ($fStr)"
                    else -> "you draw $gName ($fStr)"
                }
            } ?: when {
                ru -> "нарисован жест (выберите фигуру ниже)"
                pt -> "um gesto for desenhado (escolha abaixo)"
                else -> "a gesture is drawn (pick one below)"
            }
            else -> TRIGGERS[type]?.let { en ->
                val tr = I18n.t(en)
                if (tr.startsWith("Wi-Fi") || tr.startsWith("Bluetooth") || tr.startsWith("Do Not Disturb") || tr.startsWith("Shizuku")) tr
                else tr.replaceFirstChar { it.lowercase() }
            } ?: when { ru -> "срабатывает триггер"; pt -> "o gatilho disparar"; else -> "trigger fires" }
        }

        val qualifiers = mutableListOf<String>()
        days(r).takeIf { it.isNotEmpty() && it.size < 7 }?.let { d ->
            qualifiers += when { ru -> "по ${dayText(d)}"; pt -> "em ${dayText(d)}"; else -> "on ${dayText(d)}" }
        }
        val from = r.optString("from"); val to = r.optString("to")
        if (from.isNotEmpty() && to.isNotEmpty()) {
            qualifiers += when { ru -> "с $from до $to"; pt -> "entre $from e $to"; else -> "between $from and $to" }
        }
        r.optJSONArray("conditions")?.takeIf { it.length() > 0 }?.let { cs ->
            val joiner = if (r.optString("conditionsMode") == "any") orWord else andWord
            val ifW = when { ru -> "если "; pt -> "se "; else -> "if " }
            qualifiers += ifW + (0 until cs.length()).joinToString(joiner) { conditionText(cs.getJSONObject(it), appName) }
        }
        r.optString("after").takeIf { it.isNotEmpty() }?.let { rid ->
            val pfx = when { ru -> "после "; pt -> "após "; else -> "after " }
            qualifiers += "$pfx${routineName(rid)}"
        }
        r.optString("unless").takeIf { it.isNotEmpty() }?.let { rid ->
            val nm = routineName(rid)
            qualifiers += when { ru -> "пока не сработает $nm"; pt -> "até $nm rodar"; else -> "until $nm runs" }
        }
        r.optJSONObject("between")?.let { b ->
            val s = b.optString("start"); val e = b.optString("end")
            if (s.isNotEmpty()) qualifiers += (when { ru -> "после "; pt -> "após "; else -> "after " }) + routineName(s)
            if (e.isNotEmpty()) qualifiers += (when { ru -> "до "; pt -> "antes de "; else -> "before " }) + routineName(e)
        }
        if (r.optBoolean("once")) qualifiers += when {
            ru -> "не чаще раза в день"
            pt -> "no máximo uma vez ao dia"
            else -> "once a day"
        }
        val whenClause = if (qualifiers.isEmpty()) head else "$head, ${qualifiers.joinToString(", ")}"

        val acts = r.optJSONArray("actions")
        val items = if (acts == null) emptyList() else (0 until acts.length()).map { i ->
            Actions.describe(acts.getJSONObject(i), appName, routineName)
        }
        val doClause = when (items.size) {
            0 -> I18n.t("nothing yet (add an action below)")
            1 -> items[0]
            2 -> "${items[0]}$andWord${items[1]}"
            else -> "${items.dropLast(1).joinToString(", ")}$andWord${items.last()}"
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
        val from = g.optString("from"); val to = g.optString("to"); val mins = g.optInt("minutes", 3)
        return when (I18n.current()) {
            I18n.LANG_RU -> "Держит Wi-Fi вкл. $from–$to (проверка каждые $mins мин) до запуска"
            I18n.LANG_PT -> "Mantém o Wi-Fi ligado $from–$to (a cada $mins min) até rodar"
            else -> "Keeps Wi-Fi on $from–$to (checks every $mins min) until it runs"
        }
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
