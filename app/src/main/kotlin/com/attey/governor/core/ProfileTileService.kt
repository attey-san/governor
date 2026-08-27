package com.attey.governor.core

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.attey.governor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A quick-settings tile that cycles through saved profiles.
 *
 * The label is the profile currently applied; tapping applies the next one. No
 * countdown here, and none is needed: a profile cannot offline a core, so the
 * worst a stray tap can do is change some frequencies, and the next tap moves on.
 *
 * Deliberately not a shortcut that opens the app. The point of the tile is to
 * change something from the pull-down without unlocking anything.
 */
class ProfileTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val store = ProfileStore(this)
        val profiles = store.loadProfiles()
        if (profiles.isEmpty()) {
            render()
            return
        }
        val next = profiles[(profiles.indexOfFirst { it.name == store.loadActiveProfile() } + 1)
            .mod(profiles.size)]

        // Optimistic label: the tile has a couple of hundred milliseconds before
        // the panel redraws, and a root probe does not fit in that.
        store.saveActiveProfile(next.name)
        render()

        scope.launch {
            val shell = RootShell.get() ?: return@launch
            val model = DeviceProbe.probe(shell)
            ProfileEngine.apply(shell, model, next)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val store = ProfileStore(this)
        val active = store.loadActiveProfile()
        val hasProfiles = store.loadProfiles().isNotEmpty()

        tile.label = getString(R.string.app_name)
        tile.subtitle = when {
            !hasProfiles -> "no profiles"
            active.isNullOrEmpty() -> "tap to apply"
            else -> active
        }
        tile.state = if (hasProfiles) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
