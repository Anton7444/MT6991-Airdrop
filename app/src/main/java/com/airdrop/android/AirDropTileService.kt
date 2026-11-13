package com.airdrop.android

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.airdrop.android.daemon.Hub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AirDropTileService : TileService() {
    private var listening: Job? = null

    override fun onStartListening() {
        listening?.cancel()
        listening = CoroutineScope(Dispatchers.Main.immediate).launch {
            combine(Hub.airdrop, Hub.status, Hub.connected) { on, st, up -> Triple(on, st, up) }.collect { (on, st, up) ->
                val t = qsTile ?: return@collect
                t.icon = Icon.createWithResource(this@AirDropTileService, R.drawable.ic_airdrop)
                t.label = getString(R.string.tile_label)
                t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    t.subtitle = getString(
                        when {
                            !on -> R.string.tile_off
                            st.failed -> R.string.tile_error
                            up && st.on -> R.string.tile_on
                            else -> R.string.tile_starting
                        },
                    )
                }
                t.updateTile()
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onDestroy() {
        listening?.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        Hub.init(applicationContext)
        val enable = !Hub.airdrop.value
        if (enable && isLocked) unlockAndRun { Hub.setAirDrop(true) }
        else Hub.setAirDrop(enable)
    }
}
