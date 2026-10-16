/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2026 by Exclave contributors                                 *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package io.nekohasekai.sagernet.bg

import android.app.KeyguardManager
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import android.service.quicksettings.TileService as BaseTileService

const val QS_TILE_SLOTS = 6

@RequiresApi(24)
fun tileServiceClass(slot: Int): Class<*> = when (slot) {
    1 -> GroupTileService1::class.java
    2 -> GroupTileService2::class.java
    3 -> GroupTileService3::class.java
    4 -> GroupTileService4::class.java
    5 -> GroupTileService5::class.java
    6 -> GroupTileService6::class.java
    else -> error("bad slot $slot")
}

fun tileIconRes(slot: Int): Int = when (slot) {
    1 -> R.drawable.ic_tile_group_1
    2 -> R.drawable.ic_tile_group_2
    3 -> R.drawable.ic_tile_group_3
    4 -> R.drawable.ic_tile_group_4
    5 -> R.drawable.ic_tile_group_5
    else -> R.drawable.ic_tile_group_6
}

/**
 * One Quick Settings tile per assigned subscription group. Tap toggles the VPN using the
 * group's last used profile (or its best-ping profile); if another group is running, tap
 * switches to this group. Assign groups in the app: group menu -> Assign Quick Settings tile.
 */
@RequiresApi(24)
abstract class GroupTileService : BaseTileService(), SagerConnection.Callback {

    abstract val slot: Int

    private val iconIdle by lazy { Icon.createWithResource(this, tileIconRes(slot)) }
    private val keyguard by lazy { getSystemService<KeyguardManager>()!! }

    @Volatile
    private var tapPending = false

    private val connection = SagerConnection()

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        updateTile(state)
    }

    override fun onServiceConnected(service: ISagerNetService) {
        updateTile(BaseService.State.entries[service.state])
        if (tapPending) {
            tapPending = false
            runOnDefaultDispatcher { toggle() }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onTileRemoved() {
        // tile was removed from the panel: drop the binding
        runOnDefaultDispatcher {
            DataStore.putQsTileGroup(slot, null)
        }
        super.onTileRemoved()
    }

    override fun onClick() {
        if (isLocked) {
            unlockAndRun {
                runOnDefaultDispatcher { toggle() }
            }
        } else {
            runOnDefaultDispatcher { toggle() }
        }
    }

    private fun updateTile(serviceState: BaseService.State) {
        runOnDefaultDispatcher {
            val groupId = DataStore.qsTileGroup(slot)
            val group = groupId?.let { SagerDatabase.groupDao.getById(it) }
            val running =
                if (serviceState.canStop) SagerDatabase.proxyDao.getById(DataStore.startedProfile) else null
            var subtitle: String?
            val tileState: Int
            when (serviceState) {
                BaseService.State.Connecting -> {
                    tileState = Tile.STATE_ACTIVE
                    subtitle = null
                }
                BaseService.State.Connected -> if (running != null && groupId != null && running.groupId == groupId) {
                    tileState = Tile.STATE_ACTIVE
                    subtitle = running.displayName()
                } else {
                    tileState = Tile.STATE_INACTIVE
                    subtitle = GroupSwitcher.pickProfile(groupId)?.displayName()
                }
                BaseService.State.Stopping -> {
                    tileState = Tile.STATE_UNAVAILABLE
                    subtitle = null
                }
                else -> {
                    tileState = Tile.STATE_INACTIVE
                    subtitle = GroupSwitcher.pickProfile(groupId)?.displayName()
                }
            }
            runOnMainDispatcher {
                qsTile?.apply {
                    label = group?.name ?: getString(R.string.app_name)
                    this.subtitle = subtitle
                    icon = iconIdle
                    state = tileState
                    updateTile()
                }
            }
        }
    }

    private suspend fun toggle() {
        val groupId = DataStore.qsTileGroup(slot) ?: return
        val service = connection.service
        if (service == null) {
            // binder not ready yet; retry once connected
            tapPending = true
            return
        }
        val state = BaseService.State.entries[service.state]
        when {
            state.canStop -> {
                val running = SagerDatabase.proxyDao.getById(DataStore.startedProfile)
                if (running != null && running.groupId == groupId) {
                    SagerNet.stopService()
                } else {
                    GroupSwitcher.switchTo(groupId, state)
                }
            }
            state == BaseService.State.Stopped -> GroupSwitcher.switchTo(groupId, state)
        }
    }

}

@RequiresApi(24)
class GroupTileService1 : GroupTileService() {
    override val slot: Int = 1
}

@RequiresApi(24)
class GroupTileService2 : GroupTileService() {
    override val slot: Int = 2
}

@RequiresApi(24)
class GroupTileService3 : GroupTileService() {
    override val slot: Int = 3
}

@RequiresApi(24)
class GroupTileService4 : GroupTileService() {
    override val slot: Int = 4
}

@RequiresApi(24)
class GroupTileService5 : GroupTileService() {
    override val slot: Int = 5
}

@RequiresApi(24)
class GroupTileService6 : GroupTileService() {
    override val slot: Int = 6
}
