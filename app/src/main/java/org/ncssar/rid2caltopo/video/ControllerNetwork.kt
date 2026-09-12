package org.ncssar.rid2caltopo.video

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.net.Inet4Address

internal data class ControllerEndpoint(val address: String, val wired: Boolean) {
    val label: String get() = if (wired) "Ethernet" else "Wi-Fi"
}

internal fun controllerEndpoints(candidates: List<ControllerEndpoint>): List<ControllerEndpoint> =
    candidates.filter { candidate ->
        val parts = candidate.address.split('.')
        val octets = parts.mapNotNull { it.toIntOrNull() }
        parts.size == 4 && octets.size == 4 && octets.all { it in 0..255 } &&
            octets[0] in 1..223 && octets[0] != 127
    }.distinct().sortedWith(compareBy<ControllerEndpoint> { it.wired }.thenBy { it.address })

internal fun controllerEndpointInstructions(endpoints: List<ControllerEndpoint>): String =
    (if (endpoints.none { !it.wired }) listOf("Wi-Fi: Not connected") else emptyList())
        .plus(endpoints.map { "${it.label}: rtmp://${it.address}/<droneDesig>" })
        .joinToString("\n")

/** Observe all local networks, including Ethernet without an Internet route. */
@Composable
internal fun rememberControllerEndpoints(): List<ControllerEndpoint> {
    val context = LocalContext.current.applicationContext
    var endpoints by remember { mutableStateOf(emptyList<ControllerEndpoint>()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var active = true
        val lost = mutableSetOf<Network>()
        fun refresh() {
            handler.post {
                if (active) {
                    val candidates = manager.allNetworks.filter { it !in lost }.flatMap { network ->
                        val caps = manager.getNetworkCapabilities(network)
                        val wired = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                        if ((!wired && !wifi) || caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                            emptyList()
                        } else {
                            manager.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull {
                                val address = it.address
                                if (address is Inet4Address && !address.isLoopbackAddress &&
                                    !address.isAnyLocalAddress && !address.isMulticastAddress) {
                                    address.hostAddress?.let { host -> ControllerEndpoint(host, wired) }
                                } else null
                            }
                        }
                    }
                    endpoints = controllerEndpoints(candidates)
                }
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { lost.remove(network); refresh() }
            override fun onLost(network: Network) { lost.add(network); refresh() }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = refresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        }
        val request = NetworkRequest.Builder().clearCapabilities()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        manager.registerNetworkCallback(request, callback, handler)
        refresh()
        onDispose { active = false; manager.unregisterNetworkCallback(callback) }
    }
    return endpoints
}
