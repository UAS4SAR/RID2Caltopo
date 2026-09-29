package org.ncssar.rid2caltopo.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter
import org.ncssar.rid2caltopo.data.CaltopoClient

internal class NetworkCheckRecoveryGate {
    private var available = false
    fun update(available: Boolean): Boolean {
        val restored = available && !this.available
        this.available = available
        return restored
    }
}

/** Application-lifetime observer; controller LAN alone is not Internet recovery. */
object NetworkCheckRecovery {
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Synchronized fun start(context: Context) {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val gate = NetworkCheckRecoveryGate()
        val observer = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) { current = network }
            override fun onLost(network: Network) {
                if (network == current) { current = null; gate.update(false) }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network != current) return
                val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!gate.update(available)) return
                CaltopoClient.CTDebug("NetworkChecks", "Internet available; refreshing airspace, NOTAMs, and land rules")
                AirspaceCenter.requestImmediateRefresh()
                NotamCenter.requestImmediateRefresh()
                LandRestrictionCenter.requestImmediateRefresh()
            }
        }
        manager.registerDefaultNetworkCallback(observer)
        callback = observer
    }
}
