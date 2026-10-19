package org.ncssar.rid2caltopo.mapping

import kotlin.math.ceil

/** Suggest a coarser capture only when the operator's plan exceeds the local photo limit.
 * The operator applies it explicitly; no mission, route or connection is stored here.
 */
internal object MappingMissionPlanning {
    data class Recommendation(val gsdCmPerPixel:Double,val route:DjiMappingRoute)
    fun withinPhotoLimit(mission:MappingMission,camera:MappingResolution.Profile,requestedGsd:Double,
        capture:MappingCaptureProfile.Profile):Recommendation? {
        if(camera.id!="m4td.visible.wide"||!capture.exportQualified||!requestedGsd.isFinite()||requestedGsd !in .2..100.0)return null
        fun candidate(gsd:Double):DjiMappingRoute?=runCatching{DjiMappingRoute(mission.json(camera,gsd,capture))}.getOrNull()
        val current=candidate(requestedGsd)?:return null
        if(current.photoEstimate<=200)return null
        var lower=requestedGsd;var upper=requestedGsd
        var fit:DjiMappingRoute?=null
        repeat(32){
            if(fit==null){
                upper=ceil(upper*1.25*10)/10
                val height=MappingResolution.heightMetersForGsd(upper,camera.sensor)
                if(upper>100||height==null||height>500)return null
                val route=candidate(upper)
                if(route!=null&&route.photoEstimate<=200)fit=route else lower=upper
            }
        }
        if(fit==null)return null
        // A verified fit is the goal; irregular polygon grids need not vary monotonically.
        repeat(10){
            val middle=(lower+upper)/2;val route=candidate(middle)
            if(route!=null&&route.photoEstimate<=200){upper=middle;fit=route}else lower=middle
        }
        val rounded=ceil(upper*10)/10
        val route=candidate(rounded)?:return null
        return if(route.photoEstimate<=200)Recommendation(rounded,route) else null
    }
}
