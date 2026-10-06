package org.ncssar.rid2caltopo.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Application
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.data.AppConfigStore
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.FaaConfigManager
import org.ncssar.rid2caltopo.data.R2CMqttManager
import org.ncssar.rid2caltopo.data.TrackerEnrollmentClient
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter
import org.ncssar.rid2caltopo.video.mapcache.MapCacheStartupMaintenance
import org.ncssar.rid2caltopo.video.PersonRelevanceCoordinator

class R2CApplication : Application() {
    val TAG = "R2CApplication"

    override fun onCreate() {
        super.onCreate()
        UserInteractionTracker.install(this)
        // The personal-login experiment has a separate cookie store and must not
        // start a second Tracker, scanner, or operational application session.
        if (Application.getProcessName().endsWith(":caltopo_probe")) {
            android.webkit.WebView.setDataDirectorySuffix("caltopo_probe")
            android.webkit.WebView.setWebContentsDebuggingEnabled(false)
            return
        }
        instance = this;
        PersonRelevanceCoordinator.initialize(this)

        // Force IPv4 preference for older stack compatibility
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv6Addresses", "false");

        initializeCrashlyticsProbe()
        AppConfigStore.initialize(this)
        TrackerEnrollmentClient.retryManagedConfigurationBootstrap(this)
        FaaConfigManager.refreshIfNeededOnStartup(this)
        AirspaceCenter.initialize(this)
        NotamCenter.initialize(this)
        LandRestrictionCenter.initialize(this)
        NetworkCheckRecovery.start(this)
        R2CMqttManager.InitializeNetworkAddressMonitor(this)
        MapCacheStartupMaintenance.ensureStarted(this)
        // Safety net: raw AOL lidar work only lives while Download Map stays open; remove any left by a killed process.
        Thread({
            // Never delete under a running download (cannot normally start this early; checked anyway).
            if (org.ncssar.rid2caltopo.video.MapOfflinePrepRuntime.isActive()) return@Thread
            runCatching { org.ncssar.rid2caltopo.video.surface.SurfaceSourceReuse.deleteWorkDirectories(cacheDir) }
                .onSuccess { if (it > 0) CTDebug(TAG, "Removed $it leftover AOL work folder(s).") }
        }, "AOL work cleanup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        MainThreadStallMonitor.start()
        logHistoricalProcessExitReasons()
        CTDebug(TAG, "onCreate().")
        openSessionLogAtProcessStart()
    }

    /**
     * Every process start gets a Log_ file. Previously the file was only opened from
     * R2CActivity.initialize(), which runs after all runtime permissions are granted,
     * so a process started for a broadcast/service (e.g. AppIdleAlarmReceiver) or a
     * launch waiting on a permission prompt wrote nothing to disk. SAF I/O stays off
     * the main thread; persisted state is loaded here first so the worker doesn't race
     * the main thread's first state load.
     */
    private fun openSessionLogAtProcessStart() {
        runCatching { CaltopoClient.HasArchiveDirForCurrentSession() }
        Thread({
            runCatching { CaltopoClient.InitArchiveDir() }
                .onSuccess {
                    CaltopoClient.CTDebug(
                        TAG,
                        "Process started pid=${android.os.Process.myPid()}; session log opened at process start"
                    )
                }
                .onFailure { android.util.Log.e(TAG, "CTError: session log open at process start failed", it) }
        }, "Session log open").apply { isDaemon = true }.start()
    }

    private fun initializeCrashlyticsProbe() {
        runCatching {
            val firebaseApp = FirebaseApp.initializeApp(this) ?: FirebaseApp.getInstance()
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCrashlyticsCollectionEnabled(true)
            crashlytics.setCustomKey("r2c_build_version", BuildConfig.BUILD_VERSION)
            crashlytics.log("R2C Crashlytics startup probe version=${BuildConfig.BUILD_VERSION}")
            CTDebug(
                TAG,
                "Crashlytics startup probe initialized app=${firebaseApp.name} version=${BuildConfig.BUILD_VERSION}"
            )
        }.onFailure { error ->
            CTError(TAG, "Crashlytics startup probe failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun logHistoricalProcessExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val activityManager = getSystemService(ActivityManager::class.java) ?: return
            val exitReasons = activityManager.getHistoricalProcessExitReasons(packageName, 0, 5)
            if (exitReasons.isEmpty()) {
                CTDebug(TAG, "Historical process exit info unavailable.")
                return
            }
            exitReasons.forEachIndexed { index, info ->
                CTDebug(
                    TAG,
                    "Historical process exit #$index: " +
                        "pid=${info.pid} timestamp=${info.timestamp} " +
                        "reason=${reasonName(info.reason)}(${info.reason}) " +
                        "status=${info.status} importance=${info.importance} " +
                        "pss=${info.pss} rss=${info.rss} " +
                        "description='${info.description.orEmpty()}'"
                )
                if (info.reason == ApplicationExitInfo.REASON_ANR) {
                    AnrTraceStore.capture(this, info)?.let { captureSummary ->
                        CTDebug(TAG, captureSummary)
                    }
                }
            }
        }.onFailure { error ->
            CTError(TAG, "Historical process exit probe failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun reasonName(reason: Int): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_OTHER -> "OTHER"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                else -> "UNRECOGNIZED"
            }
        } else {
            "UNAVAILABLE"
        }

    companion object {
        private var instance: R2CApplication? = null;

        @JvmStatic
        fun getAppCtxt(): R2CApplication? {
            return instance;
        }
    }
}
