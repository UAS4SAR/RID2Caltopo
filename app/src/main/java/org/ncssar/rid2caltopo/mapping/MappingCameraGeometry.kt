package org.ncssar.rid2caltopo.mapping

import kotlin.math.*

/** DJI absolute NED ZYX Euler attitude -> ENU world to camera (right, down, forward).
 * FlightYawDegree is deliberately not composed with the absolute gimbal attitude.
 * Row-major matrices use the same convention as OpenCV and the Ceres JNI boundary.
 */
internal object MappingCameraGeometry {
    fun rotation(yawDegrees:Double,pitchDegrees:Double,rollDegrees:Double):DoubleArray {
        require(listOf(yawDegrees,pitchDegrees,rollDegrees).all(Double::isFinite))
        val y=Math.toRadians(yawDegrees);val p=Math.toRadians(pitchDegrees);val r=Math.toRadians(rollDegrees)
        val cy=cos(y);val sy=sin(y);val cp=cos(p);val sp=sin(p);val cr=cos(r);val sr=sin(r)
        // Columns of Rz(yaw) Ry(pitch) Rx(roll), expressed in ENU.
        val forward=doubleArrayOf(sy*cp,cy*cp,sp)
        val right=doubleArrayOf(sy*sp*sr+cy*cr,cy*sp*sr-sy*cr,-cp*sr)
        val down=doubleArrayOf(sy*sp*cr-cy*sr,cy*sp*cr+sy*sr,-cp*cr)
        return right+down+forward
    }
    fun transform(rotation:DoubleArray,v:DoubleArray)=DoubleArray(3){i->(0..2).sumOf{j->rotation[i*3+j]*v[j]}}
    fun project(rotation:DoubleArray,centre:DoubleArray,point:DoubleArray,focal:Double,cx:Double,cy:Double):DoubleArray? {
        val v=transform(rotation,DoubleArray(3){point[it]-centre[it]})
        if(v[2]<=0)return null
        return doubleArrayOf(focal*v[0]/v[2]+cx,focal*v[1]/v[2]+cy,v[2])
    }
    /** Rectified optical depth of actual shared structure, not camera altitude.
     * Full extrema plus padding retain high canopy; no percentile clipping.
     */
    fun depthBounds(rotation:DoubleArray,centre:DoubleArray,points:List<DoubleArray>):Pair<Double,Double>? {
        if(points.size<32)return null
        val depths=points.map{transform(rotation,DoubleArray(3){j->it[j]-centre[j]})[2]}
        if(depths.any{!it.isFinite()||it<=5})return null
        val padding=max(3.0,(depths.max()-depths.min())*.1)
        return max(5.0,depths.min()-padding) to depths.max()+padding
    }
    data class DisparityWindow(val minimum:Int,val range:Int)
    fun disparityWindow(focalBaseline:Double,depthBounds:Pair<Double,Double>,positive:Boolean,imageWidth:Int):DisparityWindow? {
        if(!focalBaseline.isFinite()||focalBaseline<=0||!depthBounds.first.isFinite()||!depthBounds.second.isFinite()||depthBounds.first<=0||depthBounds.second<depthBounds.first)return null
        val near=floor(focalBaseline/depthBounds.second-12).coerceAtLeast(0.0)
        val far=ceil(focalBaseline/depthBounds.first+12)
        if(!far.isFinite()||far-near>512||far>=imageWidth-16)return null
        val range=(ceil((far-near)/16).toInt()*16).coerceAtLeast(16)
        if(near+range>=imageWidth-16||range>512)return null
        return DisparityWindow(if(positive)near.toInt() else -near.toInt()-range,range)
    }
    /** Laser must agree with the exposure optical ray as well as reported slant distance. */
    fun usableLaser(rotation:DoubleArray,centre:DoubleArray,target:DoubleArray,distance:Double):Boolean {
        if(!distance.isFinite()||distance !in 5.0..500.0||!target.all(Double::isFinite))return false
        val delta=DoubleArray(3){target[it]-centre[it]};val length=sqrt(delta.sumOf{it*it})
        if(abs(length-distance)>=2)return false
        val optical=transform(rotation,delta)
        return optical[2]>0 && hypot(optical[0],optical[1]) <= max(.8,length*tan(Math.toRadians(2.0)))
    }
}
