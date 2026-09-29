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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import java.net.Inet4Address
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.opendroneid.android.bluetooth.WiFiScanner

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
        .plus(endpoints.map { "${it.label}: rtmp://${it.address}/droneDesig" })
        .joinToString("\n")

@Composable
internal fun ControllerEndpointInstructions(
    endpoints: List<ControllerEndpoint>,
    onDesignatorsClick: () -> Unit,
) {
    val visibleEndpoints = endpoints
    Column {
        if (visibleEndpoints.none { !it.wired }) {
            Text("Wi-Fi: Not connected")
        }
        visibleEndpoints.forEach { endpoint ->
            Row {
                Text("${endpoint.label}: rtmp://${endpoint.address}/")
                Text(
                    text = "droneDesig",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onDesignatorsClick),
                )
            }
        }
    }
}

internal data class ControllerNetworkState(
    val endpoints: List<ControllerEndpoint> = emptyList(),
    val ssid: String = "Not connected",
)

internal fun controllerNetworkState(candidates: List<ControllerEndpoint>, rawSSID: String?): ControllerNetworkState {
    val endpoints = controllerEndpoints(candidates)
    val name = rawSSID?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    return ControllerNetworkState(endpoints,
        if (endpoints.none { !it.wired }) "Not connected" else name ?: "Wi-Fi name unavailable")
}

@Composable
internal fun rememberControllerEndpoints(): List<ControllerEndpoint> = rememberControllerNetwork().endpoints

/** Observe local links and recheck identity while visible, including same-subnet Wi-Fi switches. */
@Composable
internal fun rememberControllerNetwork(): ControllerNetworkState {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(ControllerNetworkState()) }
    DisposableEffect(context, lifecycle) {
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
                    state = controllerNetworkState(candidates, runCatching { WiFiScanner.WiFiSSID(context) }.getOrNull())
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
        val identityRefresh = object : Runnable {
            override fun run() {
                if (!active || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
                refresh()
                handler.postDelayed(this, 3_000)
            }
        }
        val observer = LifecycleEventObserver { _, _ ->
            handler.removeCallbacks(identityRefresh)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) handler.post(identityRefresh)
        }
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            active = false
            lifecycle.removeObserver(observer)
            handler.removeCallbacks(identityRefresh)
            manager.unregisterNetworkCallback(callback)
        }
    }
    return state
}
