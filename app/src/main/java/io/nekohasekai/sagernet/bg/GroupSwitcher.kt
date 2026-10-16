/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026 by Exclave contributors                                 *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase

/**
 * Shared "connect to this group" logic for group tiles and the network auto-switcher.
 */
object GroupSwitcher {

    /**
     * Last used profile of the group, falling back to its best-ping profile, then the first one.
     */
    fun pickProfile(groupId: Long?): ProxyEntity? {
        if (groupId == null) return null
        val list = SagerDatabase.proxyDao.getByGroup(groupId)
        if (list.isEmpty()) return null
        DataStore.groupLastProfile(groupId)?.let { last ->
            list.firstOrNull { it.id == last }?.let { return it }
        }
        return list.filter { it.status == 1 }.minByOrNull { it.ping } ?: list.first()
    }

    /**
     * Connect [groupId] using its last used profile. [state] is the current service state:
     * a running service is reloaded, a stopped one is started.
     */
    suspend fun switchTo(groupId: Long, state: BaseService.State) {
        val profile = pickProfile(groupId) ?: return
        DataStore.selectedProxy = profile.id
        DataStore.putGroupLastProfile(groupId, profile.id)
        if (state.canStop) {
            SagerNet.reloadService()
        } else {
            SagerNet.startService()
        }
    }

}
