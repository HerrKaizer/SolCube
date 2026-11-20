package dev.suite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-app Profiles: stored as JSON in Suite's prefs and watched by ProfilesUserService.
 * When any listed app is in the foreground, its actions apply; on leaving, modified settings
 * restore automatically (unless restoreOnExit is turned off).
 */
object Profiles {
    const val KEY = "profiles_on"
    private const val PREF = "profiles"
    private const val VERSION = "profiles_v"

    val ACTIONS = Actions.ALL

    fun values(type: String): List<String> = Actions.values(type, allowRestore = false)

    fun valueText(type: String, v: String): String = Actions.valueText(type, v)

    fun load(ctx: Context): MutableList<JSONObject> {
        val prefs = Components.prefs(ctx)
        val raw = prefs.getString(PREF, null) ?: return migrateDefaults(ctx)
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun migrateDefaults(ctx: Context): MutableList<JSONObject> {
        val list = mutableListOf<JSONObject>()
        val target = DoublePress.action.target(ctx)
        if (DoublePress.nfc(ctx) && target.isNotEmpty()) {
            val pm = ctx.packageManager
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(target, 0)).toString()
            }.getOrDefault(target)
            list += JSONObject()
                .put("id", "p_nfc")
                .put("name", label)
                .put("on", true)
                .put("apps", JSONArray(listOf(target)))
                .put("restoreOnExit", true)
                .put("actions", JSONArray(listOf(JSONObject().put("type", "nfc").put("value", "on"))))
        }
        save(ctx, list, push = false)
        Components.prefs(ctx).edit().putInt(VERSION, 1).commit()
        return list
    }

    fun save(ctx: Context, list: List<JSONObject>, push: Boolean = true) {
        val any = list.any { it.optBoolean("on", true) && (it.optJSONArray("apps")?.length() ?: 0) > 0 }
        Components.prefs(ctx).edit()
            .putString(PREF, JSONArray(list).toString())
            .putBoolean(KEY, any)
            .commit()
        if (!push) return
        SuiteService.sync(ctx)
        if (!any) {
            Shell.stop(ctx, Shell.Kind.PROFILES)
            return
        }
        val s = Shell.service(Shell.Kind.PROFILES)
        if (s == null) Shell.start(ctx, Shell.Kind.PROFILES)
        else runCatching { s.configure(config(ctx)) }
    }

    fun config(ctx: Context): String = JSONObject().put("profiles", JSONArray(load(ctx))).toString()

    fun newId(): String = "p_" + System.currentTimeMillis()

    fun apps(p: JSONObject): List<String> = p.optJSONArray("apps")?.let { a ->
        (0 until a.length()).map { a.getString(it) }
    } ?: emptyList()

    fun actionsText(
        p: JSONObject,
        appName: (String) -> String,
        routineName: (String) -> String = { it },
    ): String {
        val a = p.optJSONArray("actions") ?: return ""
        return (0 until a.length()).joinToString(", ") { i ->
            Actions.describe(a.getJSONObject(i), appName, routineName)
        }
    }
}
