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

import android.net.Network
import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase

object NetworkSwitcher {

    const val ROLE_WIFI = "wifi"
    const val ROLE_MOBILE = "mobile"

    /**
     * Called on default network changes: when enabled and the running profile does not belong
     * to the group assigned to the current network type (Wi-Fi or mobile data), switch to it.
     */
    suspend fun onNetworkChanged(network: Network?) {
        if (!DataStore.autoSwitchNetwork) return
        if (!SagerNet.started) return
        val connectivityManager =
            SagerNet.application.getSystemService(android.net.ConnectivityManager::class.java)
                ?: return
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) } ?: return
        val role = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ROLE_WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ROLE_MOBILE
            else -> return
        }
        val target = SagerDatabase.groupDao.allGroups()
            .firstOrNull { DataStore.groupNetRole(it.id) == role } ?: return
        val running = SagerDatabase.proxyDao.getById(DataStore.startedProfile) ?: return
        if (running.groupId == target.id) return
        GroupSwitcher.switchTo(target.id, BaseService.State.Connected)
    }

}
