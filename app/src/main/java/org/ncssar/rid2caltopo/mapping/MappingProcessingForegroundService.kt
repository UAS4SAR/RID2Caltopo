package org.ncssar.rid2caltopo.mapping

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder

class MappingProcessingForegroundService:Service(){
    override fun onCreate(){super.onCreate();getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Local mapping processing",NotificationManager.IMPORTANCE_LOW));startForeground(ID,notification(this,MappingProcessingManager.state.value))}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onBind(intent:Intent?):IBinder?=null
    // Leaving the Android task does not mean the operator cancelled reconstruction.
    override fun onTimeout(startId:Int,fgsType:Int){MappingProcessingManager.interrupt();stopSelf()}
    companion object{
        private const val CHANNEL="r2c-local-mapping";private const val ID=8767
        private fun notification(context:Context,state:MappingProcessingState?):Notification {
            val open=PendingIntent.getActivity(context,0,Intent(context,org.ncssar.rid2caltopo.app.R2CActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("R2C Mapping · making 2D map")
                .setContentText(state?.stage?:"Preparing").setContentIntent(open).setOngoing(true).build()
        }
        fun update(context:Context,state:MappingProcessingState){context.getSystemService(NotificationManager::class.java).notify(ID,notification(context,state))}
    }
}
