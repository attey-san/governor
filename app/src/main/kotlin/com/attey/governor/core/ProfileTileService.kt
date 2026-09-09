package com.attey.governor.core

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.attey.governor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProfileTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        applyScope.launch {
            applyMutex.withLock {
                val store = ProfileStore(this@ProfileTileService)
                val profiles = store.loadProfiles()
                if (profiles.isEmpty()) {
                    withContext(Dispatchers.Main) { render() }
                    return@withLock
                }
                val next = profiles[
                    (profiles.indexOfFirst { it.name == store.loadActiveProfile() } + 1)
                        .mod(profiles.size)
                ]
                withContext(Dispatchers.Main) { render("applying ${next.name}") }
                val result = runCatching {
                    val shell = RootShell.get() ?: error("root unavailable")
                    val model = DeviceProbe.probe(shell)
                    ProfileEngine.apply(shell, model, next)
                }
                val status = result.fold(
                    onSuccess = { declined ->
                        if (declined.isEmpty()) {
                            if (store.saveActiveProfile(next.name)) next.name
                            else "applied; active marker not saved"
                        } else {
                            "${declined.size} setting(s) declined"
                        }
                    },
                    onFailure = { it.message ?: "apply failed" },
                )
                withContext(Dispatchers.Main) { render(status) }
            }
        }
    }

    private fun render(statusOverride: String? = null) {
        val tile = qsTile ?: return
        val store = ProfileStore(this)
        val active = store.loadActiveProfile()
        val hasProfiles = store.loadProfiles().isNotEmpty()

        val status = statusOverride ?: when {
            !hasProfiles -> "no profiles"
            active.isNullOrEmpty() -> "tap to apply"
            else -> active
        }
        // Tile subtitles start at API 29.
        if (Build.VERSION.SDK_INT >= 29) {
            tile.label = getString(R.string.app_name)
            tile.subtitle = status
        } else {
            tile.label = if (hasProfiles && !active.isNullOrEmpty()) status
                else getString(R.string.app_name)
        }
        tile.contentDescription = "${getString(R.string.app_name)}: $status"
        tile.state = if (hasProfiles) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        tile.updateTile()
    }

    private companion object {
        // TileService may be unbound as soon as the panel closes.
        val applyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val applyMutex = Mutex()
    }
}
