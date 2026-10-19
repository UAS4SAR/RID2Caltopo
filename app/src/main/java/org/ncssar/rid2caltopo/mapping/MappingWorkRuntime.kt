package org.ncssar.rid2caltopo.mapping

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.util.UUID

data class MappingWorkState(val active:Boolean=false,val lastActivityAtMsec:Long=0L) {
    fun preventsMapDisconnect(nowMsec:Long,quietMsec:Long):Boolean = active ||
        lastActivityAtMsec>0L && nowMsec-lastActivityAtMsec<quietMsec
}

/** A resource owns its lease until it closes; dismissing a connection panel does not release it. */
internal class MappingWorkTracker(private val now:()->Long=System::currentTimeMillis,private val changed:()->Unit={}) {
    private data class Lease(val onCancel:()->Unit,val survivesTaskRemoval:Boolean)
    private val leases=mutableMapOf<String,Lease>()
    private val current=MutableStateFlow(MappingWorkState())
    val state=current.asStateFlow()
    fun begin(onCancel:()->Unit):String=begin(onCancel,false)
    fun begin(onCancel:()->Unit,survivesTaskRemoval:Boolean):String {
        val token=UUID.randomUUID().toString()
        synchronized(this){leases[token]=Lease(onCancel,survivesTaskRemoval);current.value=MappingWorkState(true,now())}
        runCatching{changed()}
        return token
    }
    fun finish(token:String) {
        val removed=synchronized(this){
            if(leases.remove(token)==null)false else {current.value=MappingWorkState(leases.isNotEmpty(),now());true}
        }
        if(removed)runCatching{changed()}
    }
    fun cancelActive(explicitExit:Boolean=true) {
        val actions=synchronized(this){leases.values.filter{explicitExit||!it.survivesTaskRemoval}.map{it.onCancel}}
        actions.forEach{runCatching{it()}}
    }
    internal fun resetForTesting(){synchronized(this){leases.clear();current.value=MappingWorkState()}}
}

/** Shared by idle shutdown, incident-map retention and the foreground screen-awake policy. */
object MappingWorkRuntime {
    private val tracker=MappingWorkTracker(changed=CaltopoClient::CheckIdle)
    val state=tracker.state
    @JvmStatic fun snapshot():MappingWorkState=state.value
    @JvmStatic fun isActive():Boolean=state.value.active
    @JvmStatic fun preventsMapDisconnect(nowMsec:Long,quietMsec:Long):Boolean=state.value.preventsMapDisconnect(nowMsec,quietMsec)
    internal fun begin(onCancel:()->Unit):String=tracker.begin(onCancel)
    internal fun begin(onCancel:()->Unit,survivesTaskRemoval:Boolean):String=tracker.begin(onCancel,survivesTaskRemoval)
    internal fun finish(token:String)=tracker.finish(token)
    @JvmStatic fun cancelActive()=tracker.cancelActive()
    fun onActivityFinished(explicitExit:Boolean)=tracker.cancelActive(explicitExit)
    internal fun resetForTesting()=tracker.resetForTesting()
}
