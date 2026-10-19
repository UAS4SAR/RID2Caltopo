package org.ncssar.rid2caltopo.mapping

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.*
import java.io.File
import kotlin.math.*

internal data class SurfaceFrame(val file:File,val pose:MappingPose,val width:Int,val height:Int,val centre:DoubleArray)
internal data class SurfaceAlignment(val frames:List<SurfaceFrame>,val cameras:DoubleArray,val intrinsic:DoubleArray,
    val points:DoubleArray,val originX:Double,val originY:Double,val originAltitude:Double,val mercatorScale:Double,
    val sampleWidth:Int,val sampleHeight:Int,val report:JSONObject) {
    fun rotation(i:Int)=Mat(3,3,CvType.CV_64F).apply{put(0,0,*cameras.copyOfRange(i*12,i*12+9))}
    fun centre(i:Int)=cameras.copyOfRange(i*12+9,i*12+12)
    fun calibration()=Mat.eye(3,3,CvType.CV_64F).apply{put(0,0,intrinsic[0]);put(1,1,intrinsic[0]);put(0,2,intrinsic[1]);put(1,2,intrinsic[2])}
    fun distortion()=MatOfDouble(intrinsic[3],intrinsic[4],0.0,0.0,0.0)
}

/** Tracks are multi-view image observations, not planar registration offsets. */
internal object MappingSurfaceAlignment {
    fun run(mission:JSONObject,digests:List<String>,root:File,progress:(String,Double)->Unit,check:()->Unit):SurfaceAlignment {
        MappingSurfaceNative.requireAvailable()
        val fov=mission.getJSONObject("cameraSpecifications").getDouble("diagonalFOVDegrees")
        val parsed=digests.map { digest ->
            val file=File(root,"originals/$digest.jpg");val pose=MappingPose.read(file,fov,allowOblique=true)
            require(pose.absoluteAltitude!=null){"A photo lacks recorded exposure altitude. Originals are retained."}
            val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,b)
            require(b.outWidth in 256..12000&&b.outHeight in 256..12000)
            if(mission.getJSONObject("cameraSpecifications").optString("captureResolutionMode") in listOf("observed-timed-12mp","planned-12mp"))
                require(b.outWidth==mission.getJSONObject("cameraSpecifications").getInt("widthPixels")&&b.outHeight==mission.getJSONObject("cameraSpecifications").getInt("heightPixels")){"Original resolution differs from the planned 12 MP capture mode. Retain originals and review the capture settings."}
            SurfaceFrame(file,pose,b.outWidth,b.outHeight,doubleArrayOf())
        }
        require(parsed.size in 3..192&&parsed.map{it.width to it.height}.distinct().size==1){"Surface correction requires one visible-wide camera and image size."}
        val originX=parsed.map{MappingGeometry.east(it.pose.longitude)}.average();val originY=parsed.map{MappingGeometry.north(it.pose.latitude)}.average()
        val cosLat=cos(Math.toRadians(parsed.map{it.pose.latitude}.average()))
        val altitude=parsed.map{it.pose.absoluteAltitude!!-it.pose.height}.sorted().let{it[it.size/2]}
        val frames=parsed.map{f->f.copy(centre=doubleArrayOf((MappingGeometry.east(f.pose.longitude)-originX)*cosLat,(MappingGeometry.north(f.pose.latitude)-originY)*cosLat,f.pose.absoluteAltitude!!-altitude))}
        val sample=generateSequence(1){it*2}.first{frames[0].width/it<=1400&&frames[0].height/it<=1400}
        val w=(frames[0].width+sample-1)/sample;val h=(frames[0].height+sample-1)/sample
        val focal=hypot(w.toDouble(),h.toDouble())/(2*tan(Math.toRadians(fov/2)))
        val cameras=frames.flatMap{f->f.centre.toList()+MappingCameraGeometry.rotation(f.pose.yaw,f.pose.pitch,f.pose.roll).toList()+
            MappingPositionQuality.fixed(f.pose.rtkFlag,f.pose.rtkSigmaEast,f.pose.rtkSigmaNorth,f.pose.rtkSigmaHeight).let{fixed->listOf(MappingPositionQuality.sigma(f.pose.rtkSigmaEast,fixed,false),MappingPositionQuality.sigma(f.pose.rtkSigmaNorth,fixed,false),MappingPositionQuality.sigma(f.pose.rtkSigmaHeight,fixed,true))}}.toDoubleArray()
        val laser=frames.flatMap{f->val p=f.pose.laserTarget;val target=p?.let{doubleArrayOf((MappingGeometry.east(it.longitude)-originX)*cosLat,(MappingGeometry.north(it.latitude)-originY)*cosLat,it.altitude-altitude)}
            val usable=target!=null&&p!!.latitude in -85.0..85.0&&p.longitude in -180.0..180.0&&p.distance in 5.0..500.0&&
                MappingCameraGeometry.usableLaser(MappingCameraGeometry.rotation(f.pose.yaw,f.pose.pitch,f.pose.roll),f.centre,target,p.distance)
            (if(usable)target!! else DoubleArray(3){Double.NaN}).toList()}.toDoubleArray()
        val rounds=JSONArray();var exclusionCodes=IntArray(frames.size)
        val diagnostics=JSONObject().put("alignmentRounds",rounds).put("connectedComponentPolicy","largest-supported-block")
        val diagnosticsFile=File(root,"surface-alignment-diagnostics.json")
        val callback=MappingSharedReconstruction.callback(progress,check){round,groups,support,exclusions ->
            exclusionCodes=exclusions.copyOf()
            val blocks=frames.indices.filter{groups[it]>=0}.groupBy{groups[it]}.values
            rounds.put(JSONObject().put("round",round).put("groups",JSONArray(blocks.map{block->JSONArray(block.map{it+1})}))
                .put("consistentObservationsByPhoto",JSONArray(support.toList())).put("exclusionCodesByPhoto",JSONArray(exclusions.toList())))
            MappingPhotoStore.writeJson(diagnosticsFile,diagnostics)
        }
        val native=MappingSharedReconstruction.withSamples(frames,w,h,progress,check){paths ->
            MappingSurfaceNative.reconstructStage(cameras,laser,frames.map{it.pose.pitch}.toDoubleArray(),w,h,focal,paths,DoubleArray(0),callback)
        }
        val result=native.adjustment
        val approximate=result.last()<0
        val trackCount=native.statistics[2];val acceptedPairs=native.statistics[3]
        require(result.size==13+frames.size*12+trackCount*3&&result.all{it.isFinite()||it.isNaN()}){"Invalid surface-engine result."}
        val used=frames.indices.filter{i->result.copyOfRange(12+i*12,24+i*12).all(Double::isFinite)}
        require(used.size>=3&&used.size==result[0].toInt()){"Invalid registered camera count."}
        val excluded=JSONArray(frames.indices.filter{it !in used}.map{i->
            require(result.copyOfRange(12+i*12,24+i*12).all(Double::isNaN)){"Invalid excluded camera."}
            JSONObject().put("photo",i+1).put("sha256",digests[i]).put("captureTime",frames[i].pose.capturedAt)
                .put("reason",if(exclusionCodes[i]==2)"Disconnected from the dominant image group" else "Fewer than 40 consistent observations from qualified multi-view tracks")})

        MappingPhotoStore.writeJson(diagnosticsFile,diagnostics.put("registeredPhotoCount",used.size).put("excludedPhotos",excluded)
            .put("featureTracks",trackCount).put("matchedPairs",acceptedPairs))
        val fixedCount=used.count{i->val p=frames[i].pose;MappingPositionQuality.fixed(p.rtkFlag,p.rtkSigmaEast,p.rtkSigmaNorth,p.rtkSigmaHeight)}
        val report=JSONObject().put("positioningMode",if(fixedCount==used.size)"fixed-rtk" else "gnss-assisted").put("positioningWarning",if(fixedCount==used.size)"" else MappingPositionQuality.WARNING).put("engine","R2C stereo surface · Ceres CPU + OpenCV").put("missionTitle",mission.optString("title","Mission")).put("fixedRtkPhotos",fixedCount)
            .put("selectedPhotoCount",frames.size).put("excludedPhotoCount",excluded.length()).put("excludedPhotos",excluded)
            .put("alignmentRounds",rounds).put("connectedComponentPolicy","largest-supported-block")
            .put("featureTracks",trackCount).put("matchedPairs",acceptedPairs)
            .put("reconstructionMode",if(approximate)"approximate-plane" else "measured-surface").put("approximatePlaneHeightMeters",if(approximate)result[7] else JSONObject.NULL).put("projectionReferenceCode",result.last().toInt())
            .put("reprojectionRmsSamplePixels",if(approximate)JSONObject.NULL else result[7]).put("rtkCentreRmsMeters",if(approximate)JSONObject.NULL else result[8]).put("rtkCentreMaxShiftMeters",if(approximate)JSONObject.NULL else result[9]).put("consistentObservations",result[10].toInt()).put("adjustmentIterations",result[11].toInt())
            .put("rtkSigmaFloorMeters",JSONArray(listOf(.02,.02,.03))).put("heightReference","DJI exposure altitude minus local takeoff estimate; not independently surveyed")
            .put("scaleMode",if(result.last()<6)"nominal-calibration" else "laser-anchored").put("scaleWarning",if(result.last()<6)MappingPositionQuality.SCALE_WARNING else "").put("laserHeightAnchors",max(0,result.last().toInt())).put("laserAnchorSigmaMeters",.6)
            .put("cameraAttitudeConvention","DJI absolute NED ZYX to ENU camera right/down/forward")
            .put("capturePitchDegrees",JSONArray(used.map{frames[it].pose.pitch}.distinct())).put("originalWidthPixels",frames[0].width).put("originalHeightPixels",frames[0].height)
        return SurfaceAlignment(used.map{frames[it]},used.flatMap{i->result.copyOfRange(12+i*12,24+i*12).toList()}.toDoubleArray(),result.copyOfRange(2,7),result.copyOfRange(12+frames.size*12,result.size-1),originX,originY,altitude,cosLat,w,h,report)

    }
}
