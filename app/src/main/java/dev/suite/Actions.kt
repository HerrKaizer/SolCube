package dev.suite

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared action catalog for both Routines (which support "Back to before" restore values) and
 * per-app Profiles (which restore modified stateful settings automatically on exit).
 */
object Actions {

    val GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Connectivity" to listOf(
            "wifi" to "Wi-Fi",
            "data" to "Mobile data",
            "bluetooth" to "Bluetooth",
            "nfc" to "NFC",
            "airplane" to "Airplane mode",
            "location" to "Location",
            "auto_sync" to "Auto-sync",
        ),
        "Sound & Media" to listOf(
            "ringer" to "Sound mode",
            "dnd" to "Do Not Disturb",
            "media" to "Media volume",
            "ring_vol" to "Ring volume",
            "notif_vol" to "Notification volume",
            "alarm_vol" to "Alarm volume",
            "media_control" to "Media playback",
        ),
        "Display & Power" to listOf(
            "brightness" to "Screen brightness",
            "auto_brightness" to "Auto-brightness",
            "dark_mode" to "Dark mode",
            "rotation" to "Screen rotation",
            "screen_timeout" to "Screen timeout",
            "keep_awake" to "Keep screen on",
            "extra_dim" to "Extra dim",
            "grayscale" to "Grayscale",
            "saver" to "Battery saver",
            "boost" to "Boost mode",
            "lock" to "Lock screen",
            "wake_screen" to "Wake screen",
        ),
        "Apps & Navigation" to listOf(
            "open_app" to "Open app",
            "launch_bg" to "Launch in background",
            "close_app" to "Close specific apps",
            "close_apps" to "Close all apps",
            "revive" to "Revive apps",
            "go_home" to "Go to Home screen",
            "open_url" to "Open link / URL",
        ),
        "SolCube" to listOf(
            "temp_pill" to "Temperature pill",
            "fps_counter" to "FPS counter",
            "frame_interp" to "Frame interpolation",
            "super_res" to "Super resolution",
            "play_together" to "Play together",
            "stream_laptop" to "Stream to laptop",
            "gestures" to "Gestures",
            "circle_to_search" to "Circle to Search",
            "gemini_cts" to "Gemini CTS",
            "gemini_live" to "Gemini Live",
            "cts_touch" to "CTS: two-finger hold",
            "cts_button" to "CTS: power-button hold",
            "cts_nav" to "CTS: nav bar hold",
            "power_hold" to "Power button hold",
        ),
        "Utilities" to listOf(
            "notice" to "Show status pill",
            "vibrate" to "Vibrate (done)",
            "screenshot" to "Take screenshot",
            "run_routine" to "Run another routine",
            "wait" to "Wait",
            "shell_cmd" to "Run shell command",
        ),
    )

    val ALL: LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        for ((_, entries) in GROUPS) {
            for ((k, v) in entries) put(k, v)
        }
    }

    val STATEFUL: Set<String> = setOf(
        "wifi", "data", "bluetooth", "nfc", "airplane", "location", "auto_sync",
        "ringer", "dnd", "media", "ring_vol", "notif_vol", "alarm_vol",
        "brightness", "auto_brightness", "dark_mode", "rotation", "screen_timeout",
        "keep_awake", "extra_dim", "grayscale", "saver", "boost",
        "temp_pill", "fps_counter", "frame_interp", "super_res", "play_together",
        "stream_laptop", "gestures", "cts_touch", "cts_button", "cts_nav", "power_hold",
    )

    fun values(type: String, allowRestore: Boolean, allowToggle: Boolean = false): List<String> {
        val base = when (type) {
            "rotation" -> listOf("portrait", "auto", "landscape")
            "dark_mode" -> listOf("on", "off", "auto")
            "ringer" -> if (allowRestore) listOf("vibrate", "silent", "normal") else listOf("silent", "vibrate", "normal")
            "media", "ring_vol", "notif_vol", "alarm_vol" -> listOf("0", "25", "50", "75", "100")
            "brightness" -> listOf("10", "25", "50", "75", "100")
            "screen_timeout" -> listOf("15000", "30000", "60000", "120000", "300000", "600000", "1800000")
            "stream_laptop" -> listOf("on", "wide", "off")
            "media_control" -> return listOf("play_pause", "play", "pause", "next", "prev", "stop")
            "wait" -> return listOf("1", "3", "5", "10", "30")
            "open_app", "launch_bg", "close_app", "close_apps", "revive",
            "lock", "wake_screen", "go_home", "open_url", "circle_to_search",
            "gemini_cts", "gemini_live", "notice",
            "vibrate", "screenshot", "run_routine", "shell_cmd" -> return emptyList()
            "data", "wifi", "airplane", "saver", "dnd" -> listOf("off", "on")
            else -> listOf("on", "off")
        }
        val withToggle = if (allowToggle && type in STATEFUL && "on" in base && "off" in base) {
            listOf("toggle") + base
        } else {
            base
        }
        return if (allowRestore && type in STATEFUL) withToggle + "restore" else withToggle
    }

    fun valueText(type: String, v: String): String {
        val raw = when {
            v == "toggle" -> "Toggle"
            v == "restore" -> "Back to before"
            type == "stream_laptop" && v == "wide" -> "16:10 screen"
            type == "rotation" && v == "portrait" -> "Portrait lock"
            type == "rotation" && v == "auto" -> "Auto-rotate"
            type == "rotation" && v == "landscape" -> "Landscape"
            type == "dark_mode" && v == "auto" -> "Auto"
            type == "screen_timeout" -> when (v) {
                "15000" -> if (I18n.current() == I18n.LANG_RU) "15 с" else "15 s"
                "30000" -> if (I18n.current() == I18n.LANG_RU) "30 с" else "30 s"
                "60000" -> if (I18n.current() == I18n.LANG_RU) "1 мин" else "1 min"
                "120000" -> if (I18n.current() == I18n.LANG_RU) "2 мин" else "2 min"
                "300000" -> if (I18n.current() == I18n.LANG_RU) "5 мин" else "5 min"
                "600000" -> if (I18n.current() == I18n.LANG_RU) "10 мин" else "10 min"
                "1800000" -> if (I18n.current() == I18n.LANG_RU) "30 мин" else "30 min"
                else -> "${(v.toLongOrNull() ?: 60_000L) / 1000} " + if (I18n.current() == I18n.LANG_RU) "с" else "s"
            }
            type == "media_control" -> when (v) {
                "play_pause" -> "Play / Pause"
                "play" -> "Play"
                "pause" -> "Pause"
                "next" -> "Next"
                "prev" -> "Previous"
                "stop" -> "Stop"
                else -> v.replaceFirstChar { it.uppercase() }
            }
            type in setOf("media", "ring_vol", "notif_vol", "alarm_vol", "brightness") -> return "$v%"
            type == "wait" -> return "$v " + if (I18n.current() == I18n.LANG_RU) "с" else "s"
            else -> v.replaceFirstChar { it.uppercase() }
        }
        return I18n.t(raw)
    }

    fun defaultAction(type: String, allowRestore: Boolean, allowToggle: Boolean = false): JSONObject = JSONObject().put("type", type).apply {
        values(type, allowRestore, allowToggle).firstOrNull()?.let { put("value", it) }
        when (type) {
            "revive" -> put("apps", JSONArray(Routines.REVIVE_DEFAULT))
            "launch_bg", "close_app" -> put("apps", JSONArray())
            "close_apps" -> put("except", JSONArray())
            "open_app" -> put("pkg", "")
            "notice" -> put("text", "")
            "open_url" -> put("url", "")
            "shell_cmd" -> put("cmd", "")
            "run_routine" -> put("routineId", "")
        }
    }

    fun describe(
        o: JSONObject,
        appName: (String) -> String = { it },
        routineName: (String) -> String = { it },
    ): String {
        val type = o.optString("type")
        val name = I18n.t(ALL[type] ?: type)
        val ru = I18n.current() == I18n.LANG_RU
        val pt = I18n.current() == I18n.LANG_PT
        return when {
            type == "open_app" -> {
                val pkg = o.optString("pkg")
                if (pkg.isEmpty()) I18n.t("Open app")
                else when {
                    ru -> "Открыть ${appName(pkg)}"
                    pt -> "Abrir ${appName(pkg)}"
                    else -> "Open ${appName(pkg)}"
                }
            }
            type == "close_app" -> {
                val apps = o.optJSONArray("apps")
                when (val n = apps?.length() ?: 0) {
                    0 -> I18n.t("Close specific apps")
                    1 -> when {
                        ru -> "Закрыть ${appName(apps!!.getString(0))}"
                        pt -> "Fechar ${appName(apps!!.getString(0))}"
                        else -> "Close ${appName(apps!!.getString(0))}"
                    }
                    else -> I18n.t("Close $n apps")
                }
            }
            type == "close_apps" -> {
                val ex = o.optJSONArray("except")?.length() ?: 0
                if (ex > 0) I18n.t("Close all apps (keep $ex)") else I18n.t("Close all apps")
            }
            type == "revive" -> I18n.t("Revive ${o.optJSONArray("apps")?.length() ?: 0} apps")
            type == "launch_bg" -> {
                val targets = o.optJSONArray("apps")
                when (val n = targets?.length() ?: 0) {
                    0 -> I18n.t("Launch in background")
                    1 -> when {
                        ru -> "Запустить ${appName(targets!!.getString(0))} в фоне"
                        pt -> "Abrir ${appName(targets!!.getString(0))} em 2º plano"
                        else -> "Launch ${appName(targets!!.getString(0))} in bg"
                    }
                    else -> I18n.t("Launch $n apps in bg")
                }
            }
            type == "notice" -> {
                val t = o.optString("text").trim()
                if (t.isEmpty()) I18n.t("Show status pill")
                else when {
                    ru -> "Островок «${t.take(24)}»"
                    pt -> "Pílula “${t.take(24)}”"
                    else -> "Pill “${t.take(24)}”"
                }
            }
            type == "open_url" -> {
                val u = o.optString("url").trim()
                if (u.isEmpty()) I18n.t("Open link")
                else {
                    val shortU = u.removePrefix("https://").removePrefix("http://").take(28)
                    when {
                        ru -> "Открыть $shortU"
                        pt -> "Abrir $shortU"
                        else -> "Open $shortU"
                    }
                }
            }
            type == "shell_cmd" -> {
                val c = o.optString("cmd").trim()
                if (c.isEmpty()) I18n.t("Run shell command") else "Shell: ${c.take(24)}"
            }
            type == "run_routine" -> {
                val id = o.optString("routineId")
                if (id.isEmpty()) I18n.t("Run routine")
                else when {
                    ru -> "Запустить ${routineName(id)}"
                    pt -> "Executar ${routineName(id)}"
                    else -> "Run ${routineName(id)}"
                }
            }
            o.optString("value") == "toggle" -> when {
                ru -> "Переключить: $name"
                pt -> "Alternar $name"
                else -> "Toggle $name"
            }
            type == "media_control" -> when {
                ru -> "Медиа: ${valueText(type, o.optString("value", "play_pause"))}"
                pt -> "Mídia: ${valueText(type, o.optString("value", "play_pause"))}"
                else -> "Media: ${valueText(type, o.optString("value", "play_pause"))}"
            }
            type == "stream_laptop" -> I18n.t(when (o.optString("value")) {
                "wide" -> "Laptop 16:10 screen"
                "on" -> "Stream to laptop on"
                "off" -> "Stream to laptop off"
                else -> "Stream to laptop back to before"
            })
            type == "rotation" && o.optString("value") != "restore" -> valueText(type, o.optString("value"))
            type == "keep_awake" -> I18n.t(when (o.optString("value")) {
                "on" -> "Keep screen on"
                "off" -> "Normal screen timeout"
                else -> "Screen timeout back to before"
            })
            values(type, allowRestore = false).isEmpty() -> name
            else -> if (ru || pt) "$name: ${valueText(type, o.optString("value")).lowercase()}" else "$name ${valueText(type, o.optString("value")).lowercase()}"
        }
    }
}
