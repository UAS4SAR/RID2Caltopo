package org.ncssar.rid2caltopo.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.ncssar.rid2caltopo.app.R2CApplication

/**
 * Validated Internet for CalTopo uploads. Lost or unvalidated links must not start
 * archive/clue/LiveTrack POSTs — queue durably and flush on restore instead.
 */
object CaltopoNetworkReachability {
    @Volatile private var testOverride: Boolean? = null

    @JvmStatic fun setAvailableForTesting(available: Boolean?) {
        testOverride = available
    }

    /**
     * True when the default network has INTERNET + VALIDATED.
     * When ConnectivityManager is unavailable (unit tests / pre-Application), assume
     * available so archives are not stuck deferred; only defer on known loss.
     */
    @JvmStatic fun hasValidatedInternet(): Boolean {
        testOverride?.let { return it }
        val context: Context = R2CApplication.getAppCtxt() ?: return true
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true
        }
    }
}
