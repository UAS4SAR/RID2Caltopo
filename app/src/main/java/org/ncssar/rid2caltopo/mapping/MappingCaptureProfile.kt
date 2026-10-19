package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject

/** Versioned capture presets. Saved routes retain their exact pass definitions; new vegetation
 * surveys use canopy-aware overlap. Pilot 2 retains clearance review and flight start.
 */
object MappingCaptureProfile {
    data class Pass(val pitchDegrees:Double,val directionDegrees:Double,val forwardOverlapPercent:Double,val sideOverlapPercent:Double)
    data class Profile(val id:String,val label:String,val passes:List<Pass>,val exportQualified:Boolean) {
        val description:String get()=when(id) {
            "nadir" -> "Single downward-looking grid for open ground and low brush. 80% forward / 70% side overlap."
            "brush-trees-crossed-70-v1" -> "Nadir anchors plus crossing −70° views for brush and trees. 85% overlap at the estimated canopy surface. Ground hidden in every view remains unseen."
            "nadir-plus-60-80-70" -> "Nadir plus crossing −60° views. 80% forward / 70% side overlap; comparison trial."
            "nadir-plus-60" -> "Nadir plus crossing −60° views. 85% forward / 80% side overlap; more photos than the 80% / 70% trial."
            "nadir-plus-45" -> "Nadir plus crossing −45° views. Preview only; route export is unavailable."
            else -> label
        }
        fun json()=JSONObject().put("id",id).put("passes",JSONArray(passes.map{p->JSONObject()
            .put("gimbalPitchDegrees",p.pitchDegrees).put("directionDegrees",p.directionDegrees)
            .put("forwardOverlapPercent",p.forwardOverlapPercent).put("sideOverlapPercent",p.sideOverlapPercent)}))
    }
    val nadir=Profile("nadir","Open ground / low brush · nadir −90°",listOf(Pass(-90.0,0.0,80.0,70.0)),true)
    val nadir60=Profile("nadir-plus-60","Nadir + −60° crossing · 85% / 80% trial",listOf(Pass(-90.0,0.0,85.0,80.0),Pass(-60.0,90.0,85.0,80.0)),true)
    val nadir60Standard=Profile("nadir-plus-60-80-70","Brush / trees · nadir + −60° crossing trial",listOf(Pass(-90.0,0.0,80.0,70.0),Pass(-60.0,90.0,80.0,70.0)),true)
    val approved60Trials=listOf(nadir60Standard,nadir60)
    val nadir45=Profile("nadir-plus-45","Nadir + −45° crossing · later comparison preview",listOf(Pass(-90.0,0.0,85.0,80.0),Pass(-45.0,90.0,85.0,80.0)),false)
    // Prepared from a saved gap audit; absent from the generic chooser until a target plan exists.
    val gapTargeted60=Profile("gap-targeted-60","Crossed nadir + targeted −60°",listOf(Pass(-90.0,0.0,80.0,70.0),Pass(-90.0,90.0,80.0,70.0),Pass(-60.0,0.0,85.0,80.0)),true)
    val brushTrees=Profile("brush-trees-crossed-70-v1","Brush / trees · canopy-aware crossed survey",listOf(Pass(-90.0,0.0,85.0,85.0),Pass(-70.0,90.0,85.0,85.0)),true)
    val defaultVisible=brushTrees
    // Saved one-flight gap plans: canopy-aware east/west anchors and targeted north/south views.
    val canopyTargeted60=Profile("canopy-targeted-60","Canopy anchors + targeted −60°",listOf(Pass(-90.0,90.0,85.0,80.0),Pass(-60.0,0.0,85.0,80.0)),true)
    val profiles=listOf(defaultVisible,nadir,nadir60Standard,nadir60,nadir45)
    private val knownProfiles get()=profiles+gapTargeted60+canopyTargeted60
    fun requireExportQualified(mission:JSONObject) {
        val capture=mission.optJSONObject("captureProfile")?:return // legacy nadir routes
        val profile=knownProfiles.firstOrNull{it.id==capture.getString("id")}
        require(profile!=null&&profile.exportQualified&&capture.toString()==profile.json().toString()) {
            "This capture profile is preview-only pending qualification. No flight route was issued."
        }
    }
    fun passes(mission:JSONObject):List<Pass> {
        val capture=mission.optJSONObject("captureProfile")
        if(capture==null){val p=mission.getJSONObject("parameters");return listOf(Pass(-90.0,p.getDouble("directionDegrees"),p.getDouble("forwardOverlapPercent"),p.getDouble("sideOverlapPercent")))}
        val profile=knownProfiles.firstOrNull{it.id==capture.getString("id")}?:error("Unknown capture profile.")
        require(capture.toString()==profile.json().toString()){"Capture profile parameters do not match a controlled comparison."}
        require(mission.getString("cameraProfileId")=="m4td.visible.wide"){"Controlled capture profiles require visible-wide originals."}
        return profile.passes
    }
}
