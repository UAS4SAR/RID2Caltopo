package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import kotlin.math.*

/** Synthetic controls execute the delivered JNI/OpenCV implementation without touching flights/layers. */
@RunWith(AndroidJUnit4::class)
class MappingObliqueGeometryTest {
    @Test fun unsupportedPhotoIsExcludedWithoutMovingOrDiscardingSupportedGeometry() {
        val rotation=MappingCameraGeometry.rotation(0.0,-90.0,0.0)
        val centres=mutableListOf<DoubleArray>();val laser=mutableListOf<Double>()
        for(x in listOf(-8.0,0.0,8.0))for(y in listOf(-8.0,8.0)){
            centres.add(doubleArrayOf(x,y,60.0));laser.addAll(listOf(x,y,0.0))
        }
        centres.add(doubleArrayOf(500.0,500.0,60.0));laser.addAll(List(3){Double.NaN})
        val observations=mutableListOf<Double>();var points=0
        for(y in -24..24 step 2)for(x in -24..24 step 2){
            val point=doubleArrayOf(x.toDouble(),y.toDouble(),0.0)
            repeat(6){camera->val px=MappingCameraGeometry.project(rotation,centres[camera],point,600.0,503.5,377.5)!!
                observations.addAll(listOf(camera.toDouble(),points.toDouble(),px[0],px[1]))};points++
        }
        val packed=centres.flatMap{it.toList()+rotation.toList()+listOf(.02,.02,.03)}.toDoubleArray()
        val result=MappingSurfaceNative.adjustAttitude(packed,observations.toDoubleArray(),points,1008,756,600.0,laser.toDoubleArray(),object:MappingSurfaceNative.Progress{
            override fun iteration(iteration:Int,cost:Double)=true
        })
        assertEquals(6.0,result[0],0.0)
        assertTrue(result.copyOfRange(12,12+6*12).all(Double::isFinite))
        assertTrue(result.copyOfRange(12+6*12,12+7*12).all(Double::isNaN))
        assertTrue(result[7]<.02);assertTrue(result[9]<.01)
    }
    @Test fun repeatedExposuresAloneCannotEstablishSurfaceDepth() {
        val rotation=MappingCameraGeometry.rotation(0.0,-90.0,0.0)
        val centre=doubleArrayOf(0.0,0.0,60.0)
        val packed=(0..2).flatMap{centre.toList()+rotation.toList()+listOf(.02,.02,.03)}.toDoubleArray()
        val observations=mutableListOf<Double>()
        var points=0
        for(y in -24..24 step 2)for(x in -24..24 step 2){
            val projected=MappingCameraGeometry.project(rotation,centre,doubleArrayOf(x.toDouble(),y.toDouble(),0.0),600.0,503.5,377.5)!!
            repeat(3){camera->observations.addAll(listOf(camera.toDouble(),points.toDouble(),projected[0],projected[1]))};points++
        }
        val error=assertThrows(IllegalStateException::class.java){
            MappingSurfaceNative.adjustAttitude(packed,observations.toDoubleArray(),points,1008,756,600.0,DoubleArray(9){Double.NaN},object:MappingSurfaceNative.Progress{
                override fun iteration(iteration:Int,cost:Double)=true
            })
        }
        assertTrue(error.message!!.contains("Too few triangulated tracks"))
    }
    @Test fun mixedAttitudeNativeAdjustmentRetainsFixedRtkAndHeightScale() {
        MappingSurfaceNative.requireAvailable()
        for(pitch in listOf(-90.0,-70.0,-60.0,-45.0)) {
            val centres=mutableListOf<DoubleArray>();val rotations=mutableListOf<DoubleArray>();val lasers=mutableListOf<Double>()
            for(angle in listOf(-90.0,pitch))for(x in listOf(-8.0,0.0,8.0))for(y in listOf(-8.0,8.0)) {
                val r=MappingCameraGeometry.rotation(0.0,angle,0.0)
                centres.add(doubleArrayOf(x,y-60*tan(Math.toRadians(90+angle)),60.0));rotations.add(r);lasers.addAll(listOf(x,y,0.0))
            }
            val points=mutableListOf<DoubleArray>()
            for(y in -24..24 step 2)for(x in -24..24 step 2)points.add(doubleArrayOf(x.toDouble(),y.toDouble(),if(x>=14&&y>=10)6.0 else 0.0))
            val observations=mutableListOf<Double>()
            points.forEachIndexed { index,p -> centres.indices.forEach { camera ->
                val px=MappingCameraGeometry.project(rotations[camera],centres[camera],p,600.0,503.5,377.5)!!
                if(px[0] in 1.0..1006.0&&px[1] in 1.0..754.0)observations.addAll(listOf(camera.toDouble(),index.toDouble(),px[0],px[1]))
            } }
            val packed=centres.indices.flatMap{centres[it].toList()+rotations[it].toList()+listOf(.02,.02,.03)}.toDoubleArray()
            val result=MappingSurfaceNative.adjustAttitude(packed,observations.toDoubleArray(),points.size,1008,756,600.0,lasers.toDoubleArray(),object:MappingSurfaceNative.Progress{
                override fun iteration(iteration:Int,cost:Double)=true
            })
            assertEquals(13+centres.size*12+points.size*3,result.size)
            assertTrue("pitch $pitch reprojection ${result[7]}",result[7]<.02)
            assertTrue("pitch $pitch RTK shift ${result[9]}",result[9]<.01)
            assertTrue(result.last()>=6)
            val recovered=result.copyOfRange(12+centres.size*12,result.size-1)
            assertTrue(recovered.toList().chunked(3).count{it.all(Double::isFinite)}>=500)
            points.forEachIndexed { i,p -> if(recovered[i*3].isFinite())repeat(3){axis->assertEquals("pitch $pitch point $i",p[axis],recovered[i*3+axis],.03)} }
        }
    }
    @Test fun horizontalAndVerticalRectificationRecoverSlantDepthForMixedAttitudes() {
        assertTrue(OpenCVLoader.initLocal())
        for(pitch in listOf(-90.0,-70.0,-60.0,-45.0))for(eastBaseline in listOf(true,false)) {
            val owned=mutableListOf<Mat>();fun mat()=Mat().also{owned.add(it)}
            val k=Mat.eye(3,3,CvType.CV_64F).apply{put(0,0,600.0);put(1,1,600.0);put(0,2,503.5);put(1,2,377.5)}.also{owned.add(it)}
            val d=MatOfDouble(0.0,0.0,0.0,0.0,0.0).also{owned.add(it)}
            val ci=doubleArrayOf(0.0,0.0,60.0);val cj=if(eastBaseline)doubleArrayOf(10.0,0.0,60.0) else doubleArrayOf(0.0,10.0,60.0)
            val ri=Mat(3,3,CvType.CV_64F).apply{put(0,0,*MappingCameraGeometry.rotation(0.0,-90.0,0.0))}.also{owned.add(it)}
            val rj=Mat(3,3,CvType.CV_64F).apply{put(0,0,*MappingCameraGeometry.rotation(0.0,pitch,0.0))}.also{owned.add(it)}
            val rt=ri.t().also{owned.add(it)};val rel=mat();val t=mat();val delta=Mat(3,1,CvType.CV_64F).apply{put(0,0,*DoubleArray(3){ci[it]-cj[it]})}.also{owned.add(it)}
            val none=mat();val r1=mat();val r2=mat();val p1=mat();val p2=mat();val q=mat();val a=mat();val b=mat()
            try {
                Core.gemm(rj,rt,1.0,none,0.0,rel);Core.gemm(rj,delta,1.0,none,0.0,t)
                Calib3d.stereoRectify(k,d,k,d,Size(1008.0,756.0),rel,t,r1,r2,p1,p2,q,Calib3d.CALIB_ZERO_DISPARITY,1.0)
                Core.gemm(r1,ri,1.0,none,0.0,a);Core.gemm(r2,rj,1.0,none,0.0,b)
                val ar=DoubleArray(9);a.get(0,0,ar);val br=DoubleArray(9);b.get(0,0,br);val qq=DoubleArray(16);q.get(0,0,qq)
                val vertical=abs(p2.get(1,3)[0])>abs(p2.get(0,3)[0]);val axis=if(vertical)1 else 0
                val shared=mutableListOf<DoubleArray>()
                for(z in listOf(0.0,10.0))for(x in -10..10 step 5)for(y in 15..35 step 5) {
                    val point=doubleArrayOf(x.toDouble(),y.toDouble(),z)
                    val left=MappingCameraGeometry.project(ar,ci,point,p1.get(0,0)[0],p1.get(0,2)[0],p1.get(1,2)[0])!!
                    val right=MappingCameraGeometry.project(br,cj,point,p2.get(0,0)[0],p2.get(0,2)[0],p2.get(1,2)[0])!!
                    assertEquals(left[1-axis],right[1-axis],1e-7)
                    val disparity=left[axis]-right[axis]
                    val vec=doubleArrayOf(left[0],left[1],disparity,1.0)
                    val homogeneous=DoubleArray(4){row->(0..3).sumOf{col->qq[row*4+col]*vec[col]}}
                    val recovered=DoubleArray(3){homogeneous[it]/homogeneous[3]}
                    val local=MappingCameraGeometry.transform(ar,DoubleArray(3){point[it]-ci[it]})
                    assertArrayEquals(local,recovered,1e-7);shared.add(point)
                }
                val bounds=MappingCameraGeometry.depthBounds(ar,ci,shared)!!
                shared.forEach{point->val depth=MappingCameraGeometry.transform(ar,DoubleArray(3){point[it]-ci[it]})[2];assertTrue(depth in bounds.first..bounds.second)}
            } finally {owned.forEach{it.release()}}
        }
    }
    @Test fun obliqueTextureRejectsOccludedAndUnknownRayCells() {
        MappingSurfaceNative.requireAvailable()
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val raw=java.io.File.createTempFile("oblique-control-",".raw",context.cacheDir)
        try {
            val pixels=java.nio.ByteBuffer.allocate(64*64*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(64*64){pixels.putInt(0xff443322.toInt())};raw.writeBytes(pixels.array())
            val width=41;val height=81;val surface=FloatArray(width*height)
            val camera=MappingCameraGeometry.rotation(0.0,-45.0,0.0)+doubleArrayOf(0.0,-20.0,20.0)
            val output=IntArray(256*256)
            fun render() {
                MappingSurfaceNative.render(surface,width,height,-10.0,-25.0,.5,camera,doubleArrayOf(40.0,31.5,31.5,0.0,0.0),64,64,arrayOf(raw.path),-.5,.5,1.0,output)
            }
            render();assertTrue(output[0]!=0)
            for(y in 29..31)for(x in 0 until width)surface[y*width+x]=15f
            render();assertEquals(0,output[0])
            for(y in 29..31)for(x in 0 until width)surface[y*width+x]=Float.NaN
            render();assertEquals(0,output[0])
        }finally{MappingSurfaceNative.releasePixels();raw.delete()}
    }
    @Test fun mixedNadir60StereoReconstructsSyntheticTexturedPlaneOnTablet() {
        assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2)
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory=java.io.File(context.cacheDir,"oblique-stereo-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val frames=mutableListOf<SurfaceFrame>();val cameras=mutableListOf<Double>()
        val random=java.util.Random(60);val texture=IntArray(1024*1024){random.nextInt(256)}
        fun color(x:Double,y:Double):Int {
            val u=(x+100)/.2;val v=(y+100)/.2;val ix=floor(u).toInt();val iy=floor(v).toInt()
            if(ix !in 0..1022||iy !in 0..1022)return 0xff000000.toInt()
            val fx=u-ix;val fy=v-iy
            val a=texture[iy*1024+ix];val b=texture[iy*1024+ix+1];val c=texture[(iy+1)*1024+ix];val d=texture[(iy+1)*1024+ix+1]
            val value=((1-fy)*((1-fx)*a+fx*b)+fy*((1-fx)*c+fx*d)).roundToInt()
            return 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        }
        try {
            for(pitch in listOf(-90.0,-60.0))for(x in listOf(-12.0,0.0,12.0))for(y in listOf(-6.0,6.0)) {
                val centre=doubleArrayOf(x,y-40*tan(Math.toRadians(90+pitch)),40.0)
                val r=MappingCameraGeometry.rotation(0.0,pitch,0.0)
                val pixels=IntArray(1008*756)
                for(row in 0 until 756)for(col in 0 until 1008) {
                    val ray=doubleArrayOf((col-503.5)/600,(row-377.5)/600,1.0)
                    val enu=DoubleArray(3){i->(0..2).sumOf{j->r[j*3+i]*ray[j]}}
                    val t= -centre[2]/enu[2]
                    pixels[row*1008+col]=if(t>0)color(centre[0]+t*enu[0],centre[1]+t*enu[1]) else 0xff000000.toInt()
                }
                val file=java.io.File(directory,"${frames.size}.jpg")
                val bitmap=android.graphics.Bitmap.createBitmap(pixels,1008,756,android.graphics.Bitmap.Config.ARGB_8888)
                try{file.outputStream().use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,98,it))}}finally{bitmap.recycle()}
                frames.add(SurfaceFrame(file,MappingPose(39.0,-121.0,40.0,0.0,"synthetic",82.0,pitch=pitch),1008,756,centre))
                cameras.addAll(r.toList()+centre.toList())
            }
            val points=mutableListOf<Double>()
            for(y in -34..34 step 2)for(x in -34..34 step 2)points.addAll(listOf(x.toDouble(),y.toDouble(),0.0))
            val model=SurfaceAlignment(frames,cameras.toDoubleArray(),doubleArrayOf(600.0,503.5,377.5,0.0,0.0),points.toDoubleArray(),0.0,0.0,0.0,1.0,1008,756,org.json.JSONObject())
            val surface=MappingDenseSurface.run(model,{_,_->},{})
            val measured=surface.report.getInt("measuredStereoCells")
            assertTrue("Measured cells $measured",measured>surface.heights.size*.5)
            assertTrue(surface.report.getLong("consistentStereoPoints")>100000)
            assertTrue(surface.report.getInt("acceptedObliqueStereoPairs")>=3)
            val known=surface.heights.filter(Float::isFinite)
            assertTrue("Plane depth RMS",sqrt(known.sumOf{it.toDouble().pow(2)}/known.size)<.2)
            android.util.Log.i("MappingObliqueTest","Synthetic nadir/-60 stereo: ${surface.report}")
        }finally{directory.deleteRecursively()}
    }
    @Test fun retainedNadirOriginalsStillAdjustWithFullAttitude() {
        val args=androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val id=args.getString("obliqueRegressionMission")
        org.junit.Assume.assumeTrue(id!=null)
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val root=java.io.File(context.filesDir,"mapping-missions/$id")
        val manifest=java.io.File(root,"mission.json").readText()
        val input=org.json.JSONObject(java.io.File(root,"processing-input.json").readText()).getJSONArray("sha256")
        val digests=(0 until input.length()).map{input.getString(it)}
        val before=MappingPhotoStore(root).inventory().apply{remove("freeBytes")}.toString()
        assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2)
        val model=MappingSurfaceAlignment.run(org.json.JSONObject(manifest),digests,root,{_,_->},{})
        assertTrue(model.report.getDouble("rtkCentreMaxShiftMeters")<=.5)
        assertTrue(model.report.getDouble("reprojectionRmsSamplePixels")<=1.5)
        assertEquals(before,MappingPhotoStore(root).inventory().apply{remove("freeBytes")}.toString())
        assertEquals(manifest,java.io.File(root,"mission.json").readText())
        java.io.File(context.cacheDir,"oblique-nadir-regression.json").writeText(model.report.toString(2))
    }

}
