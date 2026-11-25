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
            else -> if (type == "ringer" && v == "normal") "Ring" else v.replaceFirstChar { it.uppercase() }
        }
        return I18n.t(raw)
    }

    fun conditionText(c: JSONObject, appName: (String) -> String = { it }): String {
        val type = c.optString("type")
        val gr = I18n.g()
        return when (type) {
            "battery" -> {
                val op = if (c.optString("op", "below") == "above") "≥" else "≤"
                "${gr.batteryLabel} $op ${c.optInt("value", 25)}%"
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
                    ssids.isEmpty() -> if (not) gr.wifiNotAnyLabel else gr.wifiAnyLabel
                    not -> "${gr.wifiNotOnPrefix}${ssids.joinToString("/")}"
                    else -> "${gr.wifiOnPrefix}${ssids.joinToString("/")}"
                }
            }
            "bt_device" -> {
                val devs = strList(c.optJSONArray("devices"))
                val not = c.optString("op") == "not_connected"
                when {
                    devs.isEmpty() -> if (not) gr.btNoneLabel else gr.btAnyLabel
                    not -> "${devs.joinToString("/")} ${gr.btDiscSuffix}"
                    else -> "${devs.joinToString("/")} ${gr.btConnSuffix}"
                }
            }
            "app" -> {
                val apps = strList(c.optJSONArray("apps")).map(appName)
                val not = c.optString("op") == "not_in"
                when {
                    apps.isEmpty() -> gr.appEmptyCond
                    not -> "${gr.appNotInPrefix}${apps.joinToString("/")}"
                    else -> "${gr.appInPrefix}${apps.joinToString("/")}"
                }
            }
            "temp" -> {
                val op = if (c.optString("op", "above") == "below") "≤" else "≥"
                "${gr.tempLabel} $op ${c.optInt("value", 40)} °C"
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
        .put("haptics", HapticPrefs.toJson(ctx))
        .put("secret", Ipc.secret(ctx)).toString()

    fun newId() = "r" + System.currentTimeMillis().toString(36)

    // ------------------------------------------------------------------ text

    private val DAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun wifiGoneSeconds(t: JSONObject): Int =
        if (t.has("seconds")) t.optInt("seconds", 600).coerceAtLeast(30)
        else t.optInt("minutes", 10).coerceAtLeast(1) * 60

    fun wifiGoneDurationText(sec: Int): String {
        val gr = I18n.g()
        return if (sec < 60) "$sec ${gr.secWord}" else "${sec / 60} ${gr.minUnit}"
    }

    fun wifiGoneForText(sec: Int): String {
        val dur = wifiGoneDurationText(sec)
        return when (I18n.current()) {
            I18n.LANG_RU -> "В течение $dur"
            I18n.LANG_PT -> "Por $dur"
            I18n.LANG_PL -> "Przez $dur"
            I18n.LANG_ES -> "Durante $dur"
            I18n.LANG_DE -> "Für $dur"
            I18n.LANG_FR -> "Pendant $dur"
            I18n.LANG_IT -> "Per $dur"
            I18n.LANG_ZH -> "持续 $dur"
            else -> "For $dur"
        }
    }

    fun summary(r: JSONObject, all: List<JSONObject>, appName: (String) -> String = { it },
                gestureName: (String) -> String = { dev.suite.shell.Gestures.builtinName(it)?.let { g -> I18n.t(g) } ?: I18n.g().customGestureName }): String {
        val t = r.optJSONObject("trigger") ?: JSONObject()
        val gr = I18n.g()
        val c = gr.code
        val ssids = strList(t.optJSONArray("ssids"))
        val nets = if (ssids.isEmpty()) when (c) {
            I18n.LANG_RU -> "(сеть не задана)"
            I18n.LANG_PT -> "(sem rede)"
            I18n.LANG_PL -> "(brak sieci)"
            I18n.LANG_ES -> "(sin red)"
            I18n.LANG_DE -> "(kein Netzwerk)"
            I18n.LANG_FR -> "(aucun réseau)"
            I18n.LANG_IT -> "(nessuna rete)"
            I18n.LANG_ZH -> "(未指定网络)"
            else -> "(no network)"
        } else ssids.joinToString(gr.orWord)
        val devs = strList(t.optJSONArray("devices"))
        val devText = if (devs.isEmpty()) when (c) {
            I18n.LANG_RU -> "любой Bluetooth"
            I18n.LANG_PT -> "qualquer Bluetooth"
            I18n.LANG_PL -> "dowolny Bluetooth"
            I18n.LANG_ES -> "cualquier Bluetooth"
            I18n.LANG_DE -> "beliebiges Bluetooth"
            I18n.LANG_FR -> "tout Bluetooth"
            I18n.LANG_IT -> "qualsiasi Bluetooth"
            I18n.LANG_ZH -> "任意蓝牙"
            else -> "any Bluetooth"
        } else devs.joinToString(gr.orWord)
        val apps = strList(t.optJSONArray("apps")).map(appName)
        val appText = if (apps.isEmpty()) when (c) {
            I18n.LANG_RU -> "(не выбрано)"
            I18n.LANG_PT -> "(sem app)"
            I18n.LANG_PL -> "(brak aplikacji)"
            I18n.LANG_ES -> "(sin app)"
            I18n.LANG_DE -> "(keine App)"
            I18n.LANG_FR -> "(aucune app)"
            I18n.LANG_IT -> "(nessuna app)"
            I18n.LANG_ZH -> "(未选应用)"
            else -> "(no app)"
        } else apps.joinToString(gr.orWord)
        val head = when (t.optString("type")) {
            "wifi_connect" -> if (t.optBoolean("any")) when (c) {
                I18n.LANG_RU -> "Подключается к любому Wi-Fi"
                I18n.LANG_PT -> "Conecta a qualquer Wi-Fi"
                I18n.LANG_PL -> "Łączy się z dowolnym Wi-Fi"
                I18n.LANG_ES -> "Se conecta a cualquier Wi-Fi"
                I18n.LANG_DE -> "Verbindet mit beliebigem WLAN"
                I18n.LANG_FR -> "Se connecte à tout Wi-Fi"
                I18n.LANG_IT -> "Si connette a qualsiasi Wi-Fi"
                I18n.LANG_ZH -> "连接到任意 Wi-Fi"
                else -> "Connects to any Wi-Fi"
            } else when (c) {
                I18n.LANG_RU -> "Подключается к $nets"
                I18n.LANG_PT -> "Conecta a $nets"
                I18n.LANG_PL -> "Łączy się z $nets"
                I18n.LANG_ES -> "Se conecta a $nets"
                I18n.LANG_DE -> "Verbindet mit $nets"
                I18n.LANG_FR -> "Se connecte à $nets"
                I18n.LANG_IT -> "Si connette a $nets"
                I18n.LANG_ZH -> "连接到 $nets"
                else -> "Connects to $nets"
            }
            "wifi_disconnect" -> if (ssids.isEmpty()) when (c) {
                I18n.LANG_RU -> "Wi-Fi отключается"
                I18n.LANG_PT -> "Wi-Fi desconecta"
                I18n.LANG_PL -> "Wi-Fi rozłącza się"
                I18n.LANG_ES -> "El Wi-Fi se desconecta"
                I18n.LANG_DE -> "WLAN trennt sich"
                I18n.LANG_FR -> "Le Wi-Fi se déconnecte"
                I18n.LANG_IT -> "Il Wi-Fi si disconnette"
                I18n.LANG_ZH -> "Wi-Fi 断开"
                else -> "Wi-Fi disconnects"
            } else when (c) {
                I18n.LANG_RU -> "Отключается от $nets"
                I18n.LANG_PT -> "Desconecta de $nets"
                I18n.LANG_PL -> "Rozłącza się z $nets"
                I18n.LANG_ES -> "Se desconecta de $nets"
                I18n.LANG_DE -> "Trennt sich von $nets"
                I18n.LANG_FR -> "Se déconnecte de $nets"
                I18n.LANG_IT -> "Si disconnette da $nets"
                I18n.LANG_ZH -> "从 $nets 断开"
                else -> "Disconnects from $nets"
            }
            "wifi_gone" -> {
                val dur = wifiGoneDurationText(wifiGoneSeconds(t))
                when (c) {
                    I18n.LANG_RU -> "$nets вне зоны в течение $dur"
                    I18n.LANG_PT -> "$nets fora de alcance por $dur"
                    I18n.LANG_PL -> "$nets poza zasięgiem przez $dur"
                    I18n.LANG_ES -> "$nets fuera de alcance durante $dur"
                    I18n.LANG_DE -> "$nets außer Reichweite für $dur"
                    I18n.LANG_FR -> "$nets hors de portée pendant $dur"
                    I18n.LANG_IT -> "$nets fuori portata per $dur"
                    I18n.LANG_ZH -> "$nets 离开范围 $dur"
                    else -> "$nets out of range for $dur"
                }
            }
            "time" -> {
                val at = t.optString("at", "?")
                when (c) {
                    I18n.LANG_RU -> "В $at"
                    I18n.LANG_PT -> "Às $at"
                    I18n.LANG_PL -> "O $at"
                    I18n.LANG_ES -> "A las $at"
                    I18n.LANG_DE -> "Um $at"
                    I18n.LANG_FR -> "À $at"
                    I18n.LANG_IT -> "Alle $at"
                    I18n.LANG_ZH -> "在 $at"
                    else -> "At $at"
                }
            }
            "wifi_off" -> {
                val mins = t.optInt("minutes", 3)
                when (c) {
                    I18n.LANG_RU -> "Wi-Fi выключен (проверка каждые $mins мин)"
                    I18n.LANG_PT -> "Wi-Fi desativado (a cada $mins min)"
                    I18n.LANG_PL -> "Wi-Fi wyłączone (co $mins min)"
                    I18n.LANG_ES -> "Wi-Fi desactivado (cada $mins min)"
                    I18n.LANG_DE -> "WLAN ist aus (alle $mins Min.)"
                    I18n.LANG_FR -> "Wi-Fi désactivé (toutes les $mins min)"
                    I18n.LANG_IT -> "Wi-Fi disattivo (ogni $mins min)"
                    I18n.LANG_ZH -> "Wi-Fi 已关闭（每 $mins 分钟检查）"
                    else -> "Wi-Fi is off (checked every $mins min)"
                }
            }
            "bt_connect" -> when (c) {
                I18n.LANG_RU -> "$devText подключается"
                I18n.LANG_PT -> "$devText conecta"
                I18n.LANG_PL -> "$devText łączy się"
                I18n.LANG_ES -> "$devText se conecta"
                I18n.LANG_DE -> "$devText verbindet sich"
                I18n.LANG_FR -> "$devText se connecte"
                I18n.LANG_IT -> "$devText si connette"
                I18n.LANG_ZH -> "$devText 连接"
                else -> "$devText connects"
            }
            "bt_disconnect" -> when (c) {
                I18n.LANG_RU -> "$devText отключается"
                I18n.LANG_PT -> "$devText desconecta"
                I18n.LANG_PL -> "$devText rozłącza się"
                I18n.LANG_ES -> "$devText se desconecta"
                I18n.LANG_DE -> "$devText trennt sich"
                I18n.LANG_FR -> "$devText se déconnecte"
                I18n.LANG_IT -> "$devText si disconnette"
                I18n.LANG_ZH -> "$devText 断开"
                else -> "$devText disconnects"
            }
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
            "battery_below" -> "${gr.batteryLabel} ≤ ${t.optInt("percent", 20)}%"
            "battery_above" -> "${gr.batteryLabel} ≥ ${t.optInt("percent", 80)}%"
            "temp_above" -> {
                val tv = t.optInt("temp", 42)
                when (c) {
                    I18n.LANG_RU -> "Темп. батареи ≥ $tv °C"
                    I18n.LANG_PT -> "Temp. da bateria ≥ $tv °C"
                    I18n.LANG_PL -> "Temp. baterii ≥ $tv °C"
                    I18n.LANG_ES -> "Temp. de batería ≥ $tv °C"
                    I18n.LANG_DE -> "Akkutemp. ≥ $tv °C"
                    I18n.LANG_FR -> "Temp. batterie ≥ $tv °C"
                    I18n.LANG_IT -> "Temp. batteria ≥ $tv °C"
                    I18n.LANG_ZH -> "电池温度 ≥ $tv °C"
                    else -> "Battery temp ≥ $tv °C"
                }
            }
            "temp_below" -> {
                val tv = t.optInt("temp", 38)
                when (c) {
                    I18n.LANG_RU -> "Темп. батареи ≤ $tv °C"
                    I18n.LANG_PT -> "Temp. da bateria ≤ $tv °C"
                    I18n.LANG_PL -> "Temp. baterii ≤ $tv °C"
                    I18n.LANG_ES -> "Temp. de batería ≤ $tv °C"
                    I18n.LANG_DE -> "Akkutemp. ≤ $tv °C"
                    I18n.LANG_FR -> "Temp. batterie ≤ $tv °C"
                    I18n.LANG_IT -> "Temp. batteria ≤ $tv °C"
                    I18n.LANG_ZH -> "电池温度 ≤ $tv °C"
                    else -> "Battery temp ≤ $tv °C"
                }
            }
            "screen_off_for" -> {
                val mins = t.optInt("minutes", 10)
                when (c) {
                    I18n.LANG_RU -> "Экран выключен $mins мин"
                    I18n.LANG_PT -> "Tela desligada por $mins min"
                    I18n.LANG_PL -> "Ekran wyłączony przez $mins min"
                    I18n.LANG_ES -> "Pantalla apagada durante $mins min"
                    I18n.LANG_DE -> "Bildschirm aus seit $mins Min."
                    I18n.LANG_FR -> "Écran éteint pendant $mins min"
                    I18n.LANG_IT -> "Schermo spento per $mins min"
                    I18n.LANG_ZH -> "熄屏已达 $mins 分钟"
                    else -> "Screen off for $mins min"
                }
            }
            "app_open" -> when (c) {
                I18n.LANG_RU -> "При открытии $appText"
                I18n.LANG_PT -> "Quando $appText abre"
                I18n.LANG_PL -> "Gdy otwiera się $appText"
                I18n.LANG_ES -> "Cuando se abre $appText"
                I18n.LANG_DE -> "Wenn $appText öffnet"
                I18n.LANG_FR -> "À l'ouverture de $appText"
                I18n.LANG_IT -> "Quando si apre $appText"
                I18n.LANG_ZH -> "打开 $appText 时"
                else -> "When $appText opens"
            }
            "app_close" -> when (c) {
                I18n.LANG_RU -> "При закрытии $appText"
                I18n.LANG_PT -> "Quando $appText fecha"
                I18n.LANG_PL -> "Gdy zamyka się $appText"
                I18n.LANG_ES -> "Cuando se cierra $appText"
                I18n.LANG_DE -> "Wenn $appText schließt"
                I18n.LANG_FR -> "À la fermeture de $appText"
                I18n.LANG_IT -> "Quando si chiude $appText"
                I18n.LANG_ZH -> "关闭 $appText 时"
                else -> "When $appText closes"
            }
            "gesture" -> t.optString("gesture").takeIf { it.isNotEmpty() }?.let { gestureName(it) }
                ?.let { g ->
                    val fStr = I18n.t(if (t.optInt("fingers", 2) == 1) "one finger" else "two fingers")
                    "${gr.gesturePrefix}: $g ($fStr)"
                } ?: gr.gestureNone
            else -> TRIGGERS[t.optString("type")]?.let { I18n.t(it) } ?: "?"
        }
        val parts = mutableListOf(head)
        days(r).takeIf { it.isNotEmpty() && it.size < 7 }?.let { parts += dayText(it) }
        val from = r.optString("from"); val to = r.optString("to")
        if (from.isNotEmpty() && to.isNotEmpty()) parts += "$from–$to"
        r.optJSONArray("conditions")?.takeIf { it.length() > 0 }?.let { cs ->
            val joiner = if (r.optString("conditionsMode") == "any") " ${gr.orWord.trim().uppercase()} " else ", "
            parts += gr.ifWord + (0 until cs.length()).joinToString(joiner) { conditionText(cs.getJSONObject(it), appName) }
        }
        r.optString("after").takeIf { it.isNotEmpty() }?.let { id ->
            val nm = all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?"
            parts += if (c == I18n.LANG_ZH) "$nm 之后" else gr.afterWord + nm
        }
        r.optString("unless").takeIf { it.isNotEmpty() }?.let { id ->
            val nm = all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?"
            parts += when (c) {
                I18n.LANG_RU -> "пока не сработает $nm"
                I18n.LANG_PT -> "até $nm executar"
                I18n.LANG_PL -> "dopóki nie uruchomi się $nm"
                I18n.LANG_ES -> "hasta que se ejecute $nm"
                I18n.LANG_DE -> "bis $nm läuft"
                I18n.LANG_FR -> "jusqu'à ce que $nm s'exécute"
                I18n.LANG_IT -> "finché $nm non si avvia"
                I18n.LANG_ZH -> "直到 $nm 运行"
                else -> "until $nm runs"
            }
        }
        r.optJSONObject("between")?.let { b ->
            fun name(id: String) = all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?"
            val s = b.optString("start"); val e = b.optString("end")
            if (s.isNotEmpty() || e.isNotEmpty()) {
                val pA = s.takeIf { it.isNotEmpty() }?.let { if (c == I18n.LANG_ZH) "${name(it)} 之后" else gr.afterWord + name(it) }
                val pB = e.takeIf { it.isNotEmpty() }?.let { if (c == I18n.LANG_ZH) "${name(it)} 之前" else gr.beforeWord + name(it) }
                parts += listOfNotNull(pA, pB).joinToString(" ")
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
        gestureName: (String) -> String = { dev.suite.shell.Gestures.builtinName(it)?.let { g -> I18n.t(g) } ?: I18n.g().customGestureName },
        routineName: (String) -> String = { rid -> all.firstOrNull { it.optString("id") == rid }?.optString("name") ?: "?" },
    ): Pair<String, String> {
        val t = r.optJSONObject("trigger") ?: JSONObject()
        val gr = I18n.g()
        val c = gr.code
        val ssids = strList(t.optJSONArray("ssids"))
        val nets = if (ssids.isEmpty()) when (c) {
            I18n.LANG_RU -> "(сеть не задана)"
            I18n.LANG_PT -> "(sem rede)"
            I18n.LANG_PL -> "(brak sieci)"
            I18n.LANG_ES -> "(sin red)"
            I18n.LANG_DE -> "(kein Netzwerk)"
            I18n.LANG_FR -> "(aucun réseau)"
            I18n.LANG_IT -> "(nessuna rete)"
            I18n.LANG_ZH -> "(未指定网络)"
            else -> "(no network)"
        } else ssids.joinToString(gr.orWord)
        val devs = strList(t.optJSONArray("devices"))
        val devText = if (devs.isEmpty()) when (c) {
            I18n.LANG_RU -> "любое устройство Bluetooth"
            I18n.LANG_PT -> "qualquer dispositivo Bluetooth"
            I18n.LANG_PL -> "dowolne urządzenie Bluetooth"
            I18n.LANG_ES -> "cualquier dispositivo Bluetooth"
            I18n.LANG_DE -> "ein beliebiges Bluetooth-Gerät"
            I18n.LANG_FR -> "n'importe quel appareil Bluetooth"
            I18n.LANG_IT -> "qualsiasi dispositivo Bluetooth"
            I18n.LANG_ZH -> "任意蓝牙设备"
            else -> "any Bluetooth device"
        } else devs.joinToString(gr.orWord)
        val apps = strList(t.optJSONArray("apps")).map(appName)
        val appText = if (apps.isEmpty()) when (c) {
            I18n.LANG_RU -> "(приложение не выбрано)"
            I18n.LANG_PT -> "(nenhum app)"
            I18n.LANG_PL -> "(brak aplikacji)"
            I18n.LANG_ES -> "(ninguna app)"
            I18n.LANG_DE -> "(keine App)"
            I18n.LANG_FR -> "(aucune app)"
            I18n.LANG_IT -> "(nessuna app)"
            I18n.LANG_ZH -> "(未选应用)"
            else -> "(no app)"
        } else apps.joinToString(gr.orWord)
        val type = t.optString("type")
        val head = when (type) {
            "wifi_connect" -> if (t.optBoolean("any")) when (c) {
                I18n.LANG_RU -> "телефон подключается к любому Wi-Fi"
                I18n.LANG_PT -> "o celular conecta a qualquer Wi-Fi"
                I18n.LANG_PL -> "telefon łączy się z dowolnym Wi-Fi"
                I18n.LANG_ES -> "el teléfono se conecta a cualquier Wi-Fi"
                I18n.LANG_DE -> "WLAN sich mit beliebigem Netzwerk verbindet"
                I18n.LANG_FR -> "le téléphone se connecte à tout Wi-Fi"
                I18n.LANG_IT -> "il telefono si connette a qualsiasi Wi-Fi"
                I18n.LANG_ZH -> "连接到任意 Wi-Fi"
                else -> "connecting to any Wi-Fi"
            } else when (c) {
                I18n.LANG_RU -> "телефон подключается к $nets"
                I18n.LANG_PT -> "o celular conecta a $nets"
                I18n.LANG_PL -> "telefon łączy się z $nets"
                I18n.LANG_ES -> "el teléfono se conecta a $nets"
                I18n.LANG_DE -> "WLAN sich mit $nets verbindet"
                I18n.LANG_FR -> "le téléphone se connecte à $nets"
                I18n.LANG_IT -> "il telefono si connette a $nets"
                I18n.LANG_ZH -> "连接到 $nets"
                else -> "connecting to $nets"
            }
            "wifi_disconnect" -> if (ssids.isEmpty()) when (c) {
                I18n.LANG_RU -> "Wi-Fi отключается"
                I18n.LANG_PT -> "o Wi-Fi desconecta"
                I18n.LANG_PL -> "Wi-Fi rozłącza się"
                I18n.LANG_ES -> "el Wi-Fi se desconecta"
                I18n.LANG_DE -> "WLAN sich trennt"
                I18n.LANG_FR -> "le Wi-Fi se déconnecte"
                I18n.LANG_IT -> "il Wi-Fi si disconnette"
                I18n.LANG_ZH -> "Wi-Fi 断开连接"
                else -> "Wi-Fi disconnects"
            } else when (c) {
                I18n.LANG_RU -> "Wi-Fi отключается от $nets"
                I18n.LANG_PT -> "desconecta de $nets"
                I18n.LANG_PL -> "Wi-Fi rozłącza się z $nets"
                I18n.LANG_ES -> "se desconecta de $nets"
                I18n.LANG_DE -> "WLAN sich von $nets trennt"
                I18n.LANG_FR -> "le Wi-Fi se déconnecte de $nets"
                I18n.LANG_IT -> "il Wi-Fi si disconnette da $nets"
                I18n.LANG_ZH -> "从 $nets 断开"
                else -> "disconnecting from $nets"
            }
            "wifi_gone" -> {
                val dur = wifiGoneDurationText(wifiGoneSeconds(t))
                when (c) {
                    I18n.LANG_RU -> "сеть $nets недоступна в течение $dur"
                    I18n.LANG_PT -> "$nets fica fora de alcance por $dur"
                    I18n.LANG_PL -> "sieć $nets jest poza zasięgiem przez $dur"
                    I18n.LANG_ES -> "$nets está fuera de alcance durante $dur"
                    I18n.LANG_DE -> "$nets seit $dur außer Reichweite ist"
                    I18n.LANG_FR -> "$nets est hors de portée pendant $dur"
                    I18n.LANG_IT -> "$nets è fuori portata per $dur"
                    I18n.LANG_ZH -> "$nets 离开范围已达 $dur"
                    else -> "$nets is out of range for $dur"
                }
            }
            "time" -> {
                val at = t.optString("at", "?")
                when (c) {
                    I18n.LANG_RU -> "наступает $at"
                    I18n.LANG_PT -> "são $at"
                    I18n.LANG_PL -> "jest godzina $at"
                    I18n.LANG_ES -> "son las $at"
                    I18n.LANG_DE -> "es $at Uhr ist"
                    I18n.LANG_FR -> "il est $at"
                    I18n.LANG_IT -> "sono le $at"
                    I18n.LANG_ZH -> "到达 $at"
                    else -> "it's $at"
                }
            }
            "wifi_off" -> {
                val mins = t.optInt("minutes", 3)
                when (c) {
                    I18n.LANG_RU -> "Wi-Fi выключен (проверка каждые $mins мин)"
                    I18n.LANG_PT -> "o Wi-Fi está desligado (verifica a cada $mins min)"
                    I18n.LANG_PL -> "Wi-Fi jest wyłączone (co $mins min)"
                    I18n.LANG_ES -> "el Wi-Fi está apagado (cada $mins min)"
                    I18n.LANG_DE -> "WLAN aus ist (Prüfung alle $mins Min.)"
                    I18n.LANG_FR -> "le Wi-Fi est désactivé (vérifié toutes les $mins min)"
                    I18n.LANG_IT -> "il Wi-Fi è disattivo (controllo ogni $mins min)"
                    I18n.LANG_ZH -> "Wi-Fi 已关闭（每 $mins 分钟检查）"
                    else -> "Wi-Fi is off (checked every $mins min)"
                }
            }
            "bt_connect" -> when (c) {
                I18n.LANG_RU -> "подключается $devText"
                I18n.LANG_PT -> "$devText conecta"
                I18n.LANG_PL -> "łączy się $devText"
                I18n.LANG_ES -> "se conecta $devText"
                I18n.LANG_DE -> "$devText sich verbindet"
                I18n.LANG_FR -> "$devText se connecte"
                I18n.LANG_IT -> "si connette $devText"
                I18n.LANG_ZH -> "$devText 连接"
                else -> "$devText connects"
            }
            "bt_disconnect" -> when (c) {
                I18n.LANG_RU -> "отключается $devText"
                I18n.LANG_PT -> "$devText desconecta"
                I18n.LANG_PL -> "rozłącza się $devText"
                I18n.LANG_ES -> "se desconecta $devText"
                I18n.LANG_DE -> "$devText sich trennt"
                I18n.LANG_FR -> "$devText se déconnecte"
                I18n.LANG_IT -> "si disconnette $devText"
                I18n.LANG_ZH -> "$devText 断开"
                else -> "$devText disconnects"
            }
            "charging_on" -> {
                val m = t.optString("mode", "any")
                val ms = if (m == "any" || m.isEmpty()) "" else " (${I18n.t(m.uppercase())})"
                when (c) {
                    I18n.LANG_RU -> "подключается зарядка$ms"
                    I18n.LANG_PT -> "o carregador$ms conecta"
                    I18n.LANG_PL -> "podłączona jest ładowarka$ms"
                    I18n.LANG_ES -> "se conecta el cargador$ms"
                    I18n.LANG_DE -> "ein Ladegerät$ms angeschlossen wird"
                    I18n.LANG_FR -> "le chargeur$ms est branché"
                    I18n.LANG_IT -> "il caricatore$ms è collegato"
                    I18n.LANG_ZH -> "接入充电器$ms"
                    else -> "charger$ms connects"
                }
            }
            "charging_off" -> {
                val m = t.optString("mode", "any")
                val ms = if (m == "any" || m.isEmpty()) "" else " (${I18n.t(m.uppercase())})"
                when (c) {
                    I18n.LANG_RU -> "отключается зарядка$ms"
                    I18n.LANG_PT -> "o carregador$ms desconecta"
                    I18n.LANG_PL -> "odłączona jest ładowarka$ms"
                    I18n.LANG_ES -> "se desconecta el cargador$ms"
                    I18n.LANG_DE -> "das Ladegerät$ms getrennt wird"
                    I18n.LANG_FR -> "le chargeur$ms est débranché"
                    I18n.LANG_IT -> "il caricatore$ms viene scollegato"
                    I18n.LANG_ZH -> "拔下充电器$ms"
                    else -> "charger$ms disconnects"
                }
            }
            "battery_below" -> {
                val pct = t.optInt("percent", 20)
                when (c) {
                    I18n.LANG_RU -> "заряд опускается до $pct% или ниже"
                    I18n.LANG_PT -> "a bateria cai para $pct% ou menos"
                    I18n.LANG_PL -> "bateria spada do $pct% lub niżej"
                    I18n.LANG_ES -> "la batería baja al $pct% o menos"
                    I18n.LANG_DE -> "der Akku auf $pct% oder weniger fällt"
                    I18n.LANG_FR -> "la batterie descend à $pct% ou moins"
                    I18n.LANG_IT -> "la batteria scende al $pct% o meno"
                    I18n.LANG_ZH -> "电量降至 $pct% 或更低"
                    else -> "battery drops to $pct% or below"
                }
            }
            "battery_above" -> {
                val pct = t.optInt("percent", 80)
                when (c) {
                    I18n.LANG_RU -> "заряд поднимается до $pct% или выше"
                    I18n.LANG_PT -> "a bateria sobe para $pct% ou mais"
                    I18n.LANG_PL -> "bateria rośnie do $pct% lub wyżej"
                    I18n.LANG_ES -> "la batería sube al $pct% o más"
                    I18n.LANG_DE -> "der Akku auf $pct% oder mehr steigt"
                    I18n.LANG_FR -> "la batterie monte à $pct% ou plus"
                    I18n.LANG_IT -> "la batteria sale al $pct% o più"
                    I18n.LANG_ZH -> "电量升至 $pct% 或更高"
                    else -> "battery rises to $pct% or above"
                }
            }
            "temp_above" -> {
                val tv = t.optInt("temp", 42)
                when (c) {
                    I18n.LANG_RU -> "температура батареи достигает $tv °C"
                    I18n.LANG_PT -> "a bateria atinge $tv °C"
                    I18n.LANG_PL -> "temperatura baterii osiąga $tv °C"
                    I18n.LANG_ES -> "la batería alcanza $tv °C"
                    I18n.LANG_DE -> "die Akkutemperatur $tv °C erreicht"
                    I18n.LANG_FR -> "la batterie atteint $tv °C"
                    I18n.LANG_IT -> "la batteria raggiunge $tv °C"
                    I18n.LANG_ZH -> "电池温度升至 $tv °C"
                    else -> "battery temp reaches $tv °C"
                }
            }
            "temp_below" -> {
                val tv = t.optInt("temp", 38)
                when (c) {
                    I18n.LANG_RU -> "температура батареи снижается до $tv °C"
                    I18n.LANG_PT -> "a bateria esfria para $tv °C"
                    I18n.LANG_PL -> "temperatura baterii spada do $tv °C"
                    I18n.LANG_ES -> "la batería baja a $tv °C"
                    I18n.LANG_DE -> "die Akkutemperatur auf $tv °C sinkt"
                    I18n.LANG_FR -> "la batterie descend à $tv °C"
                    I18n.LANG_IT -> "la batteria scende a $tv °C"
                    I18n.LANG_ZH -> "电池温度降至 $tv °C"
                    else -> "battery temp cools to $tv °C"
                }
            }
            "screen_off_for" -> {
                val mins = t.optInt("minutes", 10)
                when (c) {
                    I18n.LANG_RU -> "экран выключен $mins мин"
                    I18n.LANG_PT -> "a tela fica desligada por $mins min"
                    I18n.LANG_PL -> "ekran jest wyłączony przez $mins min"
                    I18n.LANG_ES -> "la pantalla está apagada durante $mins min"
                    I18n.LANG_DE -> "der Bildschirm seit $mins Min. aus ist"
                    I18n.LANG_FR -> "l'écran est éteint pendant $mins min"
                    I18n.LANG_IT -> "lo schermo resta spento per $mins min"
                    I18n.LANG_ZH -> "熄屏已达 $mins 分钟"
                    else -> "screen is off for $mins min"
                }
            }
            "app_open" -> when (c) {
                I18n.LANG_RU -> "открывается $appText"
                I18n.LANG_PT -> "$appText abre"
                I18n.LANG_PL -> "otwiera się $appText"
                I18n.LANG_ES -> "se abre $appText"
                I18n.LANG_DE -> "$appText geöffnet wird"
                I18n.LANG_FR -> "$appText s'ouvre"
                I18n.LANG_IT -> "si apre $appText"
                I18n.LANG_ZH -> "打开 $appText"
                else -> "$appText opens"
            }
            "app_close" -> when (c) {
                I18n.LANG_RU -> "закрывается $appText"
                I18n.LANG_PT -> "$appText fecha"
                I18n.LANG_PL -> "zamyka się $appText"
                I18n.LANG_ES -> "se cierra $appText"
                I18n.LANG_DE -> "$appText geschlossen wird"
                I18n.LANG_FR -> "$appText se ferme"
                I18n.LANG_IT -> "si chiude $appText"
                I18n.LANG_ZH -> "关闭 $appText"
                else -> "$appText closes"
            }
            "gesture" -> t.optString("gesture").takeIf { it.isNotEmpty() }?.let { gid ->
                val gName = gestureName(gid)
                val fStr = I18n.t(if (t.optInt("fingers", 2) == 1) "one finger" else "two fingers")
                when (c) {
                    I18n.LANG_RU -> "вы рисуете «$gName» ($fStr)"
                    I18n.LANG_PT -> "você desenha “$gName” ($fStr)"
                    I18n.LANG_PL -> "narysujesz „$gName” ($fStr)"
                    I18n.LANG_ES -> "dibujas “$gName” ($fStr)"
                    I18n.LANG_DE -> "du „$gName“ zeichnest ($fStr)"
                    I18n.LANG_FR -> "vous tracez « $gName » ($fStr)"
                    I18n.LANG_IT -> "disegni “$gName” ($fStr)"
                    I18n.LANG_ZH -> "你绘制“$gName”（$fStr）"
                    else -> "you draw $gName ($fStr)"
                }
            } ?: when (c) {
                I18n.LANG_RU -> "нарисован жест (выберите фигуру ниже)"
                I18n.LANG_PT -> "um gesto é desenhado (escolha abaixo)"
                I18n.LANG_PL -> "zostaje narysowany gest (wybierz kształt poniżej)"
                I18n.LANG_ES -> "dibujas un gesto (elige uno abajo)"
                I18n.LANG_DE -> "eine Geste gezeichnet wird (unten wählen)"
                I18n.LANG_FR -> "un geste est tracé (choisissez ci-dessous)"
                I18n.LANG_IT -> "disegni un gesto (scegli sotto)"
                I18n.LANG_ZH -> "绘制手势（在下方选择形状）"
                else -> "a gesture is drawn (pick one below)"
            }
            else -> TRIGGERS[type]?.let { en ->
                val tr = I18n.t(en)
                if (c == I18n.LANG_DE || c == I18n.LANG_ZH ||
                    tr.startsWith("Wi-Fi") || tr.startsWith("WLAN") ||
                    tr.startsWith("Bluetooth") || tr.startsWith("Shizuku") ||
                    tr.startsWith("Do Not Disturb") || tr.startsWith("Не беспокоить") ||
                    tr.startsWith("«Не беспокоить»") || tr.startsWith("Nie przeszkadzać") ||
                    tr.startsWith("Não perturbe") || tr.startsWith("No molestar") ||
                    tr.startsWith("Ne pas déranger") || tr.startsWith("Non disturbare")
                ) tr else tr.replaceFirstChar { it.lowercase() }
            } ?: when (c) {
                I18n.LANG_RU -> "срабатывает триггер"
                I18n.LANG_PT -> "o gatilho dispara"
                I18n.LANG_PL -> "działa wyzwalacz"
                I18n.LANG_ES -> "se activa el activador"
                I18n.LANG_DE -> "der Auslöser greift"
                I18n.LANG_FR -> "le déclencheur s'active"
                I18n.LANG_IT -> "scatta l'attivatore"
                I18n.LANG_ZH -> "触发条件满足"
                else -> "trigger fires"
            }
        }

        val qualifiers = mutableListOf<String>()
        days(r).takeIf { it.isNotEmpty() && it.size < 7 }?.let { d ->
            val dt = dayText(d)
            qualifiers += when (c) {
                I18n.LANG_RU -> "по $dt"
                I18n.LANG_PT -> "em $dt"
                I18n.LANG_PL -> "w $dt"
                I18n.LANG_ES -> "los $dt"
                I18n.LANG_DE -> "an $dt"
                I18n.LANG_FR -> "les $dt"
                I18n.LANG_IT -> "di $dt"
                I18n.LANG_ZH -> "在 $dt"
                else -> "on $dt"
            }
        }
        val from = r.optString("from"); val to = r.optString("to")
        if (from.isNotEmpty() && to.isNotEmpty()) {
            qualifiers += when (c) {
                I18n.LANG_RU -> "с $from до $to"
                I18n.LANG_PT -> "entre $from e $to"
                I18n.LANG_PL -> "między $from a $to"
                I18n.LANG_ES -> "entre las $from y $to"
                I18n.LANG_DE -> "zwischen $from und $to"
                I18n.LANG_FR -> "entre $from et $to"
                I18n.LANG_IT -> "tra le $from e le $to"
                I18n.LANG_ZH -> "在 $from 至 $to 之间"
                else -> "between $from and $to"
            }
        }
        r.optJSONArray("conditions")?.takeIf { it.length() > 0 }?.let { cs ->
            val joiner = if (r.optString("conditionsMode") == "any") gr.orWord else gr.andWord
            qualifiers += gr.ifWord + (0 until cs.length()).joinToString(joiner) { conditionText(cs.getJSONObject(it), appName) }
        }
        r.optString("after").takeIf { it.isNotEmpty() }?.let { rid ->
            val nm = routineName(rid)
            qualifiers += if (c == I18n.LANG_ZH) "$nm 之后" else "${gr.afterWord}$nm"
        }
        r.optString("unless").takeIf { it.isNotEmpty() }?.let { rid ->
            val nm = routineName(rid)
            qualifiers += when (c) {
                I18n.LANG_RU -> "пока не сработает $nm"
                I18n.LANG_PT -> "até $nm executar"
                I18n.LANG_PL -> "dopóki nie uruchomi się $nm"
                I18n.LANG_ES -> "hasta que se ejecute $nm"
                I18n.LANG_DE -> "bis $nm läuft"
                I18n.LANG_FR -> "jusqu'à ce que $nm s'exécute"
                I18n.LANG_IT -> "finché $nm non si avvia"
                I18n.LANG_ZH -> "直到 $nm 运行"
                else -> "until $nm runs"
            }
        }
        r.optJSONObject("between")?.let { b ->
            val s = b.optString("start"); val e = b.optString("end")
            if (s.isNotEmpty()) qualifiers += if (c == I18n.LANG_ZH) "${routineName(s)} 之后" else gr.afterWord + routineName(s)
            if (e.isNotEmpty()) qualifiers += if (c == I18n.LANG_ZH) "${routineName(e)} 之前" else gr.beforeWord + routineName(e)
        }
        if (r.optBoolean("once")) qualifiers += when (c) {
            I18n.LANG_RU -> "не чаще раза в день"
            I18n.LANG_PT -> "no máximo uma vez ao dia"
            I18n.LANG_PL -> "raz dziennie"
            I18n.LANG_ES -> "una vez al día"
            I18n.LANG_DE -> "einmal täglich"
            I18n.LANG_FR -> "une fois par jour"
            I18n.LANG_IT -> "una volta al giorno"
            I18n.LANG_ZH -> "每天仅一次"
            else -> "once a day"
        }
        val sep = if (c == I18n.LANG_ZH) "，" else ", "
        val whenClause = if (qualifiers.isEmpty()) head else "$head$sep${qualifiers.joinToString(sep)}"

        val acts = r.optJSONArray("actions")
        val items = if (acts == null) emptyList() else (0 until acts.length()).map { i ->
            Actions.describe(acts.getJSONObject(i), appName, routineName)
        }
        val listSep = if (c == I18n.LANG_ZH) "、" else ", "
        val doClause = when (items.size) {
            0 -> I18n.t("nothing yet (add an action below)")
            1 -> items[0]
            2 -> "${items[0]}${gr.andWord}${items[1]}"
            else -> "${items.dropLast(1).joinToString(listSep)}${gr.andWord}${items.last()}"
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
            I18n.LANG_RU -> "Держит Wi-Fi включённым $from–$to (проверка каждые $mins мин) до запуска"
            I18n.LANG_PT -> "Mantém o Wi-Fi ativado $from–$to (a cada $mins min) até executar"
            I18n.LANG_PL -> "Utrzymuje Wi-Fi włączone $from–$to (co $mins min) do uruchomienia"
            I18n.LANG_ES -> "Mantiene el Wi-Fi activo $from–$to (cada $mins min) hasta ejecutarse"
            I18n.LANG_DE -> "Hält WLAN an $from–$to (alle $mins Min.) bis zum Start"
            I18n.LANG_FR -> "Garde le Wi-Fi activé $from–$to (toutes les $mins min) jusqu'au lancement"
            I18n.LANG_IT -> "Mantiene il Wi-Fi attivo $from–$to (ogni $mins min) fino all'avvio"
            I18n.LANG_ZH -> "在 $from–$to 保持 Wi-Fi 开启（每 $mins 分钟检查）直到运行"
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
