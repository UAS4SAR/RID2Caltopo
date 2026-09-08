package org.ncssar.rid2caltopo.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo
import org.ncssar.rid2caltopo.video.AndroidMapOfflinePrepCoordinator
import org.ncssar.rid2caltopo.video.MapOfflinePrepRuntime

/** Keeps a user-requested offline-map download running while the display is asleep. */
class MapOfflineDownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val stallWatchdog = object : Runnable {
        override fun run() {
            if (!MapOfflinePrepRuntime.isActive()) {
                stopSelf()
                return
            }
            if (
                MapOfflinePrepRuntime.claimStallRecovery(
                    System.currentTimeMillis(),
                    STALL_RECOVERY_THRESHOLD_MSEC,
                )
            ) {
                val cancelledCalls = AndroidMapOfflinePrepCoordinator.recoverStalledNetworkCalls()
                if (cancelledCalls > 0) {
                    CTInfo(TAG, "Offline map download stalled; cancelled $cancelledCalls tile request(s) for retry")
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MSEC)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationIntent = Intent(this, R2CActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_r2c)
            .setContentTitle("Downloading offline map")
            .setContentText("RID2Caltopo is keeping the map download active")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireWakeLock()
        handler.removeCallbacks(stallWatchdog)
        handler.postDelayed(stallWatchdog, WATCHDOG_INTERVAL_MSEC)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stallWatchdog)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        CTError(TAG, "Android data-sync time limit ended the offline map download")
        MapOfflinePrepRuntime.cancelActive()
        stopSelf(startId)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:offline-map-download",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Offline map downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps active offline map downloads running when the screen is off"
            }
        )
    }

    companion object {
        private const val TAG = "MapOfflineDownload"
        private const val CHANNEL_ID = "offline_map_download"
        private const val NOTIFICATION_ID = 4
        private const val WATCHDOG_INTERVAL_MSEC = 15_000L
        private const val STALL_RECOVERY_THRESHOLD_MSEC = 45_000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, MapOfflineDownloadService::class.java),
                )
            }.onFailure { error ->
                CTError(
                    TAG,
                    "Unable to start offline-map foreground service: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }

        fun stop() {
            val context = R2CApplication.getAppCtxt() ?: return
            context.stopService(Intent(context, MapOfflineDownloadService::class.java))
        }
    }
}
