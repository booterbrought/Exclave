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
import io.nekohasekai.sagernet.ktx.Logs

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
        var caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
        if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        ) {
            // some devices/ROMs report the VPN or a transport-less network: find the
            // underlying physical one ourselves
            caps = connectivityManager.allNetworks.asSequence()
                .mapNotNull { n -> connectivityManager.getNetworkCapabilities(n) }
                .firstOrNull {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            && (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                }
        }
        val role = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> ROLE_WIFI
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> ROLE_MOBILE
            else -> {
                Logs.d("auto-switch: no wifi/cellular network (caps=$caps)")
                return
            }
        }
        val target = SagerDatabase.groupDao.allGroups()
            .firstOrNull { DataStore.groupNetRole(it.id) == role }
        if (target == null) {
            Logs.d("auto-switch: $role has no assigned group")
            return
        }
        val running = SagerDatabase.proxyDao.getById(DataStore.startedProfile)
        if (running == null) {
            Logs.d("auto-switch: nothing is running")
            return
        }
        if (running.groupId == target.id) {
            Logs.d("auto-switch: already on ${target.displayName()}")
            return
        }
        Logs.i("auto-switch: network is $role, switching to ${target.displayName()}")
        GroupSwitcher.switchTo(target.id, BaseService.State.Connected)
    }

}
