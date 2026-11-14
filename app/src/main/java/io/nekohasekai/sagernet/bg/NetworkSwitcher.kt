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
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object NetworkSwitcher {

    const val ROLE_WIFI = "wifi"
    const val ROLE_MOBILE = "mobile"

    private val switchLock = Mutex()
    private var lastSwitchElapsed = 0L
    private var lastSwitchKey: String? = null

    /**
     * Called on default network changes: when enabled and the running profile is not the one
     * assigned to the current network type (Wi-Fi or mobile data), switch to it. The target is
     * either an exact profile ("p:<id>") or a group ("g:<id>", switches to its last used
     * profile); when no explicit target is set, the legacy per-group role is used.
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
        val running = SagerDatabase.proxyDao.getById(DataStore.startedProfile) ?: return

        switchLock.withLock {
            // one network change fires a burst of events: reload only once
            val now = SystemClock.elapsedRealtime()
            if (lastSwitchKey != null && now - lastSwitchElapsed < 5000) {
                Logs.d("auto-switch: debounced, just switched")
                return
            }

            // explicit profile/group target wins over the legacy per-group role
            var targetProfile: ProxyEntity? = null
            var targetGroup: ProxyGroup? = null
            val explicit = DataStore.netTarget(role)
            when {
                explicit.isNullOrEmpty() -> {
                    val group = SagerDatabase.groupDao.allGroups()
                        .firstOrNull { DataStore.groupNetRole(it.id) == role }
                    if (group == null) {
                        Logs.d("auto-switch: $role has no assigned target")
                        return
                    }
                    targetGroup = group
                }
                explicit.startsWith("p:") -> {
                    targetProfile = SagerDatabase.proxyDao.getById(
                        explicit.removePrefix("p:").toLongOrNull() ?: return
                    ) ?: return
                }
                explicit.startsWith("g:") -> {
                    targetGroup = SagerDatabase.groupDao.getById(
                        explicit.removePrefix("g:").toLongOrNull() ?: return
                    ) ?: return
                }
                else -> return
            }

            val key: String
            val targetName: String
            if (targetProfile != null) {
                key = "p:${targetProfile.id}"
                targetName = targetProfile.displayName()
                if (running.id == targetProfile.id) {
                    Logs.d("auto-switch: already on $targetName")
                    lastSwitchKey = key
                    lastSwitchElapsed = now
                    return
                }
                Logs.i("auto-switch: network is $role, switching to $targetName")
                lastSwitchKey = key
                lastSwitchElapsed = now
                GroupSwitcher.switchToProfile(targetProfile.id, BaseService.State.Connected)
                return
            }
            val group = targetGroup ?: return
            key = "g:${group.id}"
            targetName = group.displayName()
            if (running.groupId == group.id) {
                Logs.d("auto-switch: already on $targetName")
                lastSwitchKey = key
                lastSwitchElapsed = now
                return
            }
            Logs.i("auto-switch: network is $role, switching to $targetName")
            lastSwitchKey = key
            lastSwitchElapsed = now
            GroupSwitcher.switchTo(group.id, BaseService.State.Connected)
        }
    }

}
