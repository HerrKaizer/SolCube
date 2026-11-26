package dev.suite

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** The three sections: their switches, the FI game table, and the combined status. */
object Components {
    const val KEY_CTS = "cts"
    const val KEY_GAME = "game"   // frame interpolation
    const val KEY_SR = "sr"       // super resolution
    const val KEY_TEMP = "temp"
    const val KEY_FPS = "fps_counter"
    const val KEY_MIX = "mix"     // play together (audio focus policy)

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("suite", Context.MODE_PRIVATE)
    fun enabled(ctx: Context, key: String) = prefs(ctx).getBoolean(key, false)

    // ------------------------------------------------------------------ FI game table

    data class Game(val pkg: String, val src: Int, val dst: Int)

    /** From fi.sh (the brief's reference): Genshin 45->90, Supercell games 60->120. */
    private val DEFAULT_GAMES = listOf(
        Game("com.miHoYo.GenshinImpact", 45, 90),
        Game("com.supercell.brawlstars", 60, 120),
        Game("com.supercell.clashroyale", 60, 120),
    )

    fun games(ctx: Context): List<Game> {
        val raw = prefs(ctx).getString("games", null) ?: return DEFAULT_GAMES
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Game(o.getString("pkg"), o.getInt("src"), o.getInt("dst"))
            }
        }.getOrDefault(DEFAULT_GAMES)
    }

    fun hot(ctx: Context) = prefs(ctx).getInt("hot", 450)     // tenths of a degree
    fun cool(ctx: Context) = prefs(ctx).getInt("cool", 420)

    fun saveGames(ctx: Context, games: List<Game>, hot: Int = hot(ctx), cool: Int = cool(ctx)) {
        val a = JSONArray()
        games.forEach { a.put(JSONObject().put("pkg", it.pkg).put("src", it.src).put("dst", it.dst)) }
        prefs(ctx).edit().putString("games", a.toString()).putInt("hot", hot).putInt("cool", cool).apply()
        Thread { runCatching { Shell.service(Shell.Kind.FI)?.configure(gameConfig(ctx)) } }.start()
    }

    fun gameConfig(ctx: Context): String {
        val a = JSONArray()
        games(ctx).forEach { a.put(JSONObject().put("pkg", it.pkg).put("src", it.src).put("dst", it.dst)) }
        return JSONObject().put("games", a).put("hot", hot(ctx)).put("cool", cool(ctx))
            .put("secret", Ipc.secret(ctx)).toString()
    }

    // ------------------------------------------------------------------ status

    const val KEY_SB_LOCK = "sb_hide_lock"

    data class Status(
        val shell: Shell.State,
        val cts: JSONObject? = null,
        val fi: JSONObject? = null,
        val sr: JSONObject? = null,
        val stream: JSONObject? = null,
        val mix: JSONObject? = null,
        val routines: JSONObject? = null,
        val profiles: JSONObject? = null,
        val statusbar: JSONObject? = null,
    )

    /** Call off the main thread (binder calls into the shell-side services). */
    fun status(): Status {
        val st = Shell.state()
        return Status(st, read(Shell.Kind.CTS), read(Shell.Kind.FI), read(Shell.Kind.SR), read(Shell.Kind.STREAM), read(Shell.Kind.MIX), read(Shell.Kind.ROUTINES), read(Shell.Kind.PROFILES), read(Shell.Kind.STATUSBAR))
    }

    fun read(k: Shell.Kind) = runCatching { Shell.service(k)?.status()?.let { JSONObject(it) } }.getOrNull()

    // ------------------------------------------------------------------ stream to laptop

    /** On while the phone is in the laptop's shape ("16:10 screen"); the shell service runs meanwhile. */
    const val KEY_STREAM = "stream"
    const val KEY_DIM = "stream_dim"          // phone brightness in stream mode, percent
    const val DEFAULT_DIM = 5
    fun dim(ctx: Context) = prefs(ctx).getInt(KEY_DIM, DEFAULT_DIM)

    /** Apps with a launch button on the stream bubble (navigating is awkward in stream mode). */
    fun streamApps(ctx: Context): List<String> {
        val raw = prefs(ctx).getString("stream_apps", null) ?: return listOf("com.miHoYo.GenshinImpact")
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrDefault(listOf("com.miHoYo.GenshinImpact"))
    }

    fun saveStreamApps(ctx: Context, pkgs: List<String>) {
        prefs(ctx).edit().putString("stream_apps", JSONArray(pkgs).toString()).apply()
    }

    /** Sends a command to the stream service, starting it first if needed. Off the main thread. */
    /** For the stream service: the laptop helper's token (sent with every request). */
    fun streamConfig(ctx: Context): String = JSONObject().put("token", Ipc.helperToken(ctx)).toString()

    fun streamCmd(ctx: Context, cmd: String): String {
        val readyUntil = System.currentTimeMillis() + 1500
        while (Shell.state() != Shell.State.READY && System.currentTimeMillis() < readyUntil) {
            Thread.sleep(100)
        }
        if (Shell.state() != Shell.State.READY) return "no-shell"
        var s = Shell.service(Shell.Kind.STREAM)
        if (s == null) {
            Shell.start(ctx, Shell.Kind.STREAM)
            val until = System.currentTimeMillis() + 5000
            while (s == null && System.currentTimeMillis() < until) {
                Thread.sleep(150)
                s = Shell.service(Shell.Kind.STREAM)
            }
        }
        return s?.let { runCatching { it.command(cmd) }.getOrNull() } ?: "no-shell"
    }

    /** "16:10 screen" on/off. Returns the service's answer ("ok", "no-helper", "no-shell"). Off the main thread. */
    fun setStreamMode(ctx: Context, on: Boolean): String {
        if (on) {
            prefs(ctx).edit().putBoolean(KEY_STREAM, true).commit()
            val r = streamCmd(ctx, "wide on")
            if (r != "ok") { prefs(ctx).edit().putBoolean(KEY_STREAM, false).commit(); Shell.stop(ctx, Shell.Kind.STREAM) }
            streamModeChanged(ctx)
            return r
        }
        prefs(ctx).edit().putBoolean(KEY_STREAM, false).commit()
        Shell.service(Shell.Kind.STREAM)?.let { runCatching { it.command("exit") } }
        Shell.stop(ctx, Shell.Kind.STREAM)   // its destroy() also restores the screen
        streamModeChanged(ctx)
        return "ok"
    }

    /** Overlay + Circle to Search's two-finger hold (paused while the phone is the game pad). */
    private fun streamModeChanged(ctx: Context) {
        SolCubeService.sync(ctx)
        runCatching { Shell.service(Shell.Kind.CTS)?.configure(ctsConfig(ctx)) }
        // Frame interpolation's extra frames are made after the screen is captured, so they never
        // reach the laptop, while FI holds Genshin at its 45 fps source rate. Pause it in stream mode.
        if (enabled(ctx, KEY_GAME)) {
            if (enabled(ctx, KEY_STREAM)) Shell.stop(ctx, Shell.Kind.FI) else Shell.start(ctx, Shell.Kind.FI)
        }
    }

    // ------------------------------------------------------------------ toggles (call off the main thread)

    // ------------------------------------------------------------------ Circle to Search triggers

    const val KEY_CTS_TOUCH = "cts_touch"
    const val KEY_CTS_BUTTON = "cts_button"
    const val KEY_TOUCH_HOLD = "cts_touch_hold"
    const val KEY_BUTTON_HOLD = "cts_button_hold"
    const val KEY_CTS_NAV = "cts_nav"
    const val KEY_NAV_HOLD = "cts_nav_hold"
    const val KEY_CTS_ANIMATE = "cts_animate"
    /** "Power button hold" card: the hold opens an app / switches sound instead of Circle to Search. */
    const val KEY_HOLD_ACT = "hold_action"
    const val HOLD_MIN = 200
    const val HOLD_MAX = 800

    /** Older versions had one switch for both triggers: carry it over once. */
    private fun migrateCts(ctx: Context) {
        val p = prefs(ctx)
        if (!p.contains(KEY_CTS_TOUCH)) {
            val on = p.getBoolean(KEY_CTS, false)
            p.edit().putBoolean(KEY_CTS_TOUCH, on).putBoolean(KEY_CTS_BUTTON, on).commit()
        }
    }

    fun ctsTouch(ctx: Context): Boolean { migrateCts(ctx); return prefs(ctx).getBoolean(KEY_CTS_TOUCH, false) }
    fun ctsButton(ctx: Context): Boolean { migrateCts(ctx); return prefs(ctx).getBoolean(KEY_CTS_BUTTON, false) }
    fun touchHold(ctx: Context) = prefs(ctx).getInt(KEY_TOUCH_HOLD, 400)
    fun buttonHold(ctx: Context) = prefs(ctx).getInt(KEY_BUTTON_HOLD, 350)
    fun ctsNav(ctx: Context) = prefs(ctx).getBoolean(KEY_CTS_NAV, false)
    fun navHold(ctx: Context) = prefs(ctx).getInt(KEY_NAV_HOLD, 350)
    fun ctsAnimate(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CTS_ANIMATE, true)
    fun ctsRippleWanted(ctx: Context): Boolean =
        ctsNav(ctx) || Routines.hasEnabledTrigger(ctx, "nav_hold") ||
            (ctsAnimate(ctx) && (ctsTouch(ctx) || ctsButton(ctx)))
    fun holdAction(ctx: Context) = prefs(ctx).getBoolean(KEY_HOLD_ACT, false)

    fun ctsServiceWanted(ctx: Context): Boolean =
        ctsTouch(ctx) || ctsButton(ctx) || ctsNav(ctx) || holdAction(ctx) || Routines.needsCtsListener(ctx)

    const val KEY_FREEZE = "cts_freeze"
    // Tested 2026-11-15: with the ripple in the accessibility overlay, Circle to Search's screenshot
    // stays clean even at 0 ms, so the ripple starts right away (RippleHost keeps 250 ms for the
    // app-overlay fallback, which CTS's screenshot does capture).
    const val DEFAULT_FREEZE = 0
    fun freeze(ctx: Context) = prefs(ctx).getInt(KEY_FREEZE, DEFAULT_FREEZE)

    fun ctsConfig(ctx: Context): String {
        val streaming = enabled(ctx, KEY_STREAM)
        val touchCts = ctsTouch(ctx) && !streaming
        val touchRt = Routines.hasEnabledTrigger(ctx, "two_finger_hold") && !streaming
        val navCts = ctsNav(ctx) && !streaming
        val navRt = Routines.hasEnabledTrigger(ctx, "nav_hold") && !streaming
        val btnCts = ctsButton(ctx)
        val btnAct = holdAction(ctx)
        val btnRt = Routines.hasEnabledTrigger(ctx, "power_hold")
        return JSONObject()
            // two-finger / nav holds pause while the phone is the game pad
            .put("touch", touchCts || touchRt)
            .put("touchCts", touchCts)
            .put("touchRoutine", touchRt)
            // one physical gesture: routine (when matched), else Circle to Search OR the hold card's action
            .put("button", btnCts || btnAct || btnRt)
            .put("buttonCts", btnCts)
            .put("buttonAct", btnAct)
            .put("buttonRoutine", btnRt)
            .put("nav", navCts || navRt)
            .put("navCts", navCts)
            .put("navRoutine", navRt)
            .put("navHold", navHold(ctx))
            .put("touchHold", touchHold(ctx))
            .put("buttonHold", buttonHold(ctx))
            .put("animate", ctsAnimate(ctx))
            .put("freeze", freeze(ctx))
            .put("haptics", HapticPrefs.toJson(ctx))
            .put("secret", Ipc.secret(ctx)).toString()   // ripple-port handshake (see Ipc)
    }

    /** Starts, reconfigures, or stops the CTS/hold listener service to match active triggers. Call off the main thread. */
    fun syncCts(ctx: Context) {
        val any = ctsServiceWanted(ctx)
        prefs(ctx).edit().putBoolean(KEY_CTS, any).commit()
        SolCubeService.sync(ctx)                    // the ripple overlay lives in the app
        if (!any) { Shell.stop(ctx, Shell.Kind.CTS); return }
        val s = Shell.service(Shell.Kind.CTS)
        if (s == null) Shell.start(ctx, Shell.Kind.CTS)
        else runCatching { s.configure(ctsConfig(ctx)) }   // button off -> it restarts itself
    }

    /** Turns one trigger on/off. The listener runs while either is on. Call off the main thread. */
    fun setCtsTrigger(ctx: Context, key: String, on: Boolean) {
        migrateCts(ctx)
        prefs(ctx).edit().putBoolean(key, on).also { e ->
            // the power-button hold does one thing: turning one on turns the other off
            if (on && key == KEY_CTS_BUTTON) e.putBoolean(KEY_HOLD_ACT, false)
            if (on && key == KEY_HOLD_ACT) e.putBoolean(KEY_CTS_BUTTON, false)
        }.commit()
        syncCts(ctx)
    }

    fun setCtsAnimate(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CTS_ANIMATE, on).commit()
        SolCubeService.sync(ctx)
        Thread { runCatching { Shell.service(Shell.Kind.CTS)?.configure(ctsConfig(ctx)) } }.start()
    }

    fun setHold(ctx: Context, key: String, ms: Int) {
        prefs(ctx).edit().putInt(key, ms.coerceIn(HOLD_MIN, HOLD_MAX)).commit()
        Thread { runCatching { Shell.service(Shell.Kind.CTS)?.configure(ctsConfig(ctx)) } }.start()
    }

    fun setGame(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_GAME, on).commit()
        SolCubeService.sync(ctx)                    // the service re-attaches after reboots
        if (on) Shell.start(ctx, Shell.Kind.FI) else Shell.stop(ctx, Shell.Kind.FI)
        FeatureTile.refreshAll(ctx)
    }

    fun setSr(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SR, on).commit()
        SolCubeService.sync(ctx)
        if (on) Shell.start(ctx, Shell.Kind.SR) else Shell.stop(ctx, Shell.Kind.SR)
        FeatureTile.refreshAll(ctx)
    }

    fun setMix(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MIX, on).commit()
        SolCubeService.sync(ctx)
        if (on) Shell.start(ctx, Shell.Kind.MIX) else Shell.stop(ctx, Shell.Kind.MIX)
        FeatureTile.refreshAll(ctx)
    }

    fun setTemp(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TEMP, on).commit()
        SolCubeService.sync(ctx)
        if (on || enabled(ctx, KEY_FPS)) Shell.start(ctx, Shell.Kind.TEMP) else Shell.stop(ctx, Shell.Kind.TEMP)
    }

    /** The FPS counter: its own pill on the temperature overlay (the overlay runs for either). */
    fun setFps(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FPS, on).commit()
        SolCubeService.sync(ctx)
        if (on || enabled(ctx, KEY_TEMP)) Shell.start(ctx, Shell.Kind.TEMP) else Shell.stop(ctx, Shell.Kind.TEMP)
    }

    fun statusBarDaemonWanted(ctx: Context): Boolean = enabled(ctx, KEY_SB_LOCK)

    fun statusBarConfig(ctx: Context): String = JSONObject()
        .put("hideLock", enabled(ctx, KEY_SB_LOCK))
        .toString()

    fun setLockStatusBarHidden(ctx: Context, hide: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SB_LOCK, hide).commit()
        SolCubeService.sync(ctx)
        if (hide) {
            Shell.start(ctx, Shell.Kind.STATUSBAR)
        } else {
            Thread { runCatching { Shell.stop(ctx, Shell.Kind.STATUSBAR) } }.start()
        }
    }

    /** Starts whatever is enabled but not running (after a reboot, when SolEngine comes back, after a crash). */
    fun reconcile(ctx: Context) {
        if (Shell.state() != Shell.State.READY) return
        if (ctsServiceWanted(ctx)) {
            prefs(ctx).edit().putBoolean(KEY_CTS, true).commit()
            Shell.start(ctx, Shell.Kind.CTS)
        }
        if (enabled(ctx, KEY_GAME) && !enabled(ctx, KEY_STREAM)) Shell.start(ctx, Shell.Kind.FI)   // paused while streaming
        if (enabled(ctx, KEY_SR)) Shell.start(ctx, Shell.Kind.SR)
        if (enabled(ctx, KEY_MIX)) Shell.start(ctx, Shell.Kind.MIX)
        if (enabled(ctx, Routines.KEY)) Shell.start(ctx, Shell.Kind.ROUTINES)
        Profiles.load(ctx)   // one-time migration from DoublePress NFC if not initialized yet
        if (enabled(ctx, Profiles.KEY)) Shell.start(ctx, Shell.Kind.PROFILES)
        if (enabled(ctx, KEY_TEMP) || enabled(ctx, KEY_FPS)) Shell.start(ctx, Shell.Kind.TEMP)
        if (enabled(ctx, KEY_STREAM)) Shell.start(ctx, Shell.Kind.STREAM)
        if (enabled(ctx, KEY_SB_LOCK)) Shell.start(ctx, Shell.Kind.STATUSBAR)
    }
}
