package dev.suite

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tiles for Frame interpolation, Super resolution and Play together. They flip the
 * same switch as the card in SolCube (and show its state); the shell side still needs Shizuku.
 */
abstract class FeatureTile : TileService() {
    abstract val key: String
    abstract fun set(ctx: Context, on: Boolean)

    override fun onStartListening() { refresh() }

    override fun onClick() {
        I18n.init(this)
        val on = !Components.enabled(this, key)
        if (on && Shell.state() != Shell.State.READY) {
            Island.show(this, I18n.t("Shizuku stopped · open it to start"), ok = false)
            refresh()
            return
        }
        // the pref first, so the tile flips at once; the shell side starts / stops behind it
        Components.prefs(this).edit().putBoolean(key, on).commit()
        refresh()
        val app = applicationContext
        Thread { runCatching { set(app, on) } }.start()
    }

    private fun refresh() {
        I18n.init(this)
        val t = qsTile ?: return
        val on = Components.enabled(this, key)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = I18n.t(if (on) "On" else "Off")
        t.updateTile()
    }

    companion object {
        val ALL = listOf(FiTile::class.java, SrTile::class.java, MixTile::class.java)

        /** Asks the tiles to redraw (after the switch changed in the app, a profile or a routine). */
        fun refreshAll(ctx: Context) {
            for (c in ALL) runCatching { requestListeningState(ctx, ComponentName(ctx, c)) }
        }

        /** The system's "Add tile to Quick Settings?" prompt. [done] gets a short result line. */
        fun requestAdd(ctx: Context, cls: Class<*>, label: String, icon: Int, done: (String) -> Unit) {
            I18n.init(ctx)
            val sbm = ctx.getSystemService(StatusBarManager::class.java)
            runCatching {
                sbm.requestAddTileService(ComponentName(ctx, cls), I18n.t(label), Icon.createWithResource(ctx, icon), ctx.mainExecutor) { r ->
                    done(I18n.t(when (r) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Already in Quick Settings"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> ""
                        else -> "Add it by editing Quick Settings (pencil icon)"
                    }))
                }
            }.onFailure { done(I18n.t("Add it by editing Quick Settings (pencil icon)")) }
        }
    }
}

class FiTile : FeatureTile() {
    override val key = Components.KEY_GAME
    override fun set(ctx: Context, on: Boolean) = Components.setGame(ctx, on)
}

class SrTile : FeatureTile() {
    override val key = Components.KEY_SR
    override fun set(ctx: Context, on: Boolean) = Components.setSr(ctx, on)
}

class MixTile : FeatureTile() {
    override val key = Components.KEY_MIX
    override fun set(ctx: Context, on: Boolean) = Components.setMix(ctx, on)
}
