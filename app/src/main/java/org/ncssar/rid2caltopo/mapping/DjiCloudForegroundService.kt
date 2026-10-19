package org.ncssar.rid2caltopo.mapping

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder

class DjiCloudForegroundService:Service() {
    override fun onCreate(){super.onCreate(); val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Mapping mission connection",NotificationManager.IMPORTANCE_LOW))
        startForeground(ID,notification(this,DjiCloudManager.session.value))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onTaskRemoved(rootIntent:Intent?){DjiCloudManager.stop();super.onTaskRemoved(rootIntent)}
    override fun onTimeout(startId:Int,fgsType:Int){DjiCloudManager.stop();stopSelf()}
    companion object {
        private const val CHANNEL="r2c-native-mapping";private const val ID=8766
        private fun notification(context:Context,session:DjiCloudSession?):Notification {
            val open=PendingIntent.getActivity(context,0,Intent(context,org.ncssar.rid2caltopo.app.R2CActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("R2C Mapping · ${session?.title ?: "Connecting"}")
                .setContentText("${session?.photos ?: 0} originals stored · ${session?.status ?: "Waiting for Pilot 2"}").setContentIntent(open).setOngoing(true).build()
        }
        fun update(context:Context,session:DjiCloudSession?){ context.getSystemService(NotificationManager::class.java).notify(ID,notification(context,session)) }
    }
}
