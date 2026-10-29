/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.data.CaltopoPersonalAccountConnection

/**
 * Keeps the process able to accept CalTopo's loopback redirect while the auth tab is in front.
 * Android can freeze a backgrounded app, and a frozen process cannot accept that connection.
 */
class CaltopoPersonalAuthService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
        } catch (error: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "CalTopo sign-in", NotificationManager.IMPORTANCE_LOW)
        )
        val cancel = PendingIntent.getActivity(
            this,
            1,
            CaltopoPersonalAuthActivity.closeIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this,
            2,
            Intent(this, R2CActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_r2c)
            .setContentTitle("CalTopo sign-in")
            .setContentText(CaltopoPersonalAccountConnection.ACTIVATION_PROMPT)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, "Cancel", cancel)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        private const val CHANNEL = "caltopo-personal-auth"
        private const val NOTIFICATION_ID = 4417
    }
}
