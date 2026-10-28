/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.ncssar.rid2caltopo.data.CaltopoClient;

public final class AppIdleAlarmReceiver extends BroadcastReceiver {
    private static final int REQUEST_CODE = 120;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (SettingsStartup.isReady()) CaltopoClient.CheckIdle();
        else {
            // Reschedule without holding a broadcast alive or inspecting unloaded settings.
            schedule(context, 30_000L);
        }
    }

    public static void schedule(@NonNull Context context, long delayMsec) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(1L, delayMsec);
        PendingIntent pendingIntent = pendingIntent(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        } else {
            manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        }
    }

    public static void cancel(@NonNull Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) manager.cancel(pendingIntent(context));
    }

    private static PendingIntent pendingIntent(@NonNull Context context) {
        Intent intent = new Intent(context, AppIdleAlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
