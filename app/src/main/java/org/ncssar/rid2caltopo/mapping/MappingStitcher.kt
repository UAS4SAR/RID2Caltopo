package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.graphics.*
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.core.Point
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import org.osmdroid.util.MapTileIndex
import java.io.File
import kotlin.math.*

/** Feature-aligned, georeferenced flat-plane 2D mosaic. No dense 3D/terrain model is inferred. */
internal object MappingStitcher {
    private data class Frame(val file:File,val pose:MappingPose,val width:Int,val height:Int,val cx:Double,val cy:Double,
        val basis:DoubleArray,var adjustment:DoubleArray=doubleArrayOf(1.0,0.0,0.0,0.0)) {
        fun local(x:Double,y:Double):Point {
            val u=x-(width-1)/2.0;val v=y-(height-1)/2.0
            return Point(basis[0]*u+basis[1]*v,basis[2]*u+basis[3]*v)
        }
        fun world(x:Double,y:Double):Point {
            val p=local(x,y);val a=adjustment
            return Point(cx+a[0]*p.x-a[1]*p.y+a[2],cy+a[1]*p.x+a[0]*p.y+a[3])
        }
        fun corners()=listOf(world(0.0,0.0),world(width.toDouble(),0.0),world(width.toDouble(),height.toDouble()),world(0.0,height.toDouble()))
    }
    private data class Features(val points:Array<KeyPoint>,val descriptor:Mat,val scaleX:Double,val scaleY:Double)
    private data class Tie(val i:Int,val j:Int,val left:Point,val right:Point)

    fun run(context:Context,mission:JSONObject,digests:List<String>,root:File,out:File,
        progress:(String,Double)->Unit,check:()->Unit):JSONObject {
        require(OpenCVLoader.initLocal()){ "The bundled image-alignment engine could not start." }
        Core.setNumThreads(2)
        require(mission.getString("profile")=="daylight-rgb"){"Local stitching currently supports visible-wide photos. Thermal alignment needs separate qualification."}
        require(digests.size in 3..200){"Choose a visible flight with 3–200 verified originals."}
        val fov=mission.getJSONObject("cameraSpecifications").getDouble("diagonalFOVDegrees")
        val features=mutableListOf<Features>();val sift=SIFT.create(1800);val matcher=DescriptorMatcher.create(DescriptorMatcher.FLANNBASED)
        try {
            val frames=digests.mapIndexed{index,digest->
                check();progress("Reading photo ${index+1}/${digests.size}",0.05*index/digests.size)
                val file=File(root,"originals/$digest.jpg")
                require(DjiOriginalCamera.matches(file,mission)){"Use originals matching the mission camera and focal length."}
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
                require(bounds.outWidth in 256..12000&&bounds.outHeight in 256..12000){"A returned photo cannot be decoded."}
                val pose=MappingPose.read(file,fov)
                Frame(file,pose,bounds.outWidth,bounds.outHeight,MappingGeometry.east(pose.longitude),MappingGeometry.north(pose.latitude),MappingGeometry.basis(pose,bounds.outWidth,bounds.outHeight))
            }
            val originX=frames.map{it.cx}.average();val originY=frames.map{it.cy}.average()
            val localFrames=frames.map{it.copy(cx=it.cx-originX,cy=it.cy-originY)}
            localFrames.forEachIndexed{index,frame->
                check();progress("Finding image detail ${index+1}/${frames.size}",0.05+0.18*index/frames.size)
                val options=BitmapFactory.Options().apply{inSampleSize=generateSequence(1){it*2}.first{frame.width/it<=1200&&frame.height/it<=1200};inPreferredConfig=Bitmap.Config.ARGB_8888}
                val bitmap=BitmapFactory.decodeFile(frame.file.path,options)?:error("Cannot read a photo for alignment.")
                val rgba=Mat();val gray=Mat();val points=MatOfKeyPoint();val descriptors=Mat();val mask=Mat()
                try {
                    Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
                    sift.detectAndCompute(gray,mask,points,descriptors)
                    require(!descriptors.empty()){ "Photo ${index+1} has too little image detail for alignment." }
                    features.add(Features(points.toArray(),descriptors,frame.width.toDouble()/bitmap.width,frame.height.toDouble()/bitmap.height))
                } catch(e:Exception){descriptors.release();throw e}
                finally{bitmap.recycle();rgba.release();gray.release();points.release();mask.release()}
            }
            val pairs=linkedSetOf<Pair<Int,Int>>()
            localFrames.forEachIndexed{i,f->localFrames.indices.filter{it!=i}.sortedBy{j->hypot(f.cx-localFrames[j].cx,f.cy-localFrames[j].cy)}.take(8).forEach{j->
                val radius=MappingGeometry.gsd(f.pose,f.width,f.height)*hypot(f.width.toDouble(),f.height.toDouble())/cos(Math.toRadians(f.pose.latitude))
                if(hypot(f.cx-localFrames[j].cx,f.cy-localFrames[j].cy)<radius)pairs.add(min(i,j) to max(i,j))
            }}
            val ties=mutableListOf<Tie>();val edges=mutableListOf<Pair<Int,Int>>()
            pairs.forEachIndexed{index,(i,j)->
                check();progress("Aligning overlaps ${index+1}/${pairs.size}",0.23+0.24*index/pairs.size.coerceAtLeast(1))
                val a=features[i];val b=features[j];val matches=mutableListOf<MatOfDMatch>()
                try {
                    matcher.knnMatch(a.descriptor,b.descriptor,matches,2)
                    val good=matches.mapNotNull{m->val found=m.toArray();if(found.size==2&&found[0].distance<0.7*found[1].distance)found[0] else null}
                    if(good.size<24)return@forEachIndexed
                    val left=good.map{m->val p=a.points[m.queryIdx].pt;val v=localFrames[i].local(p.x*a.scaleX,p.y*a.scaleY);Point(v.x+localFrames[i].cx,v.y+localFrames[i].cy)}
                    val right=good.map{m->val p=b.points[m.trainIdx].pt;val v=localFrames[j].local(p.x*b.scaleX,p.y*b.scaleY);Point(v.x+localFrames[j].cx,v.y+localFrames[j].cy)}
                    val l=MatOfPoint2f(*left.toTypedArray());val r=MatOfPoint2f(*right.toTypedArray());val inliers=Mat()
                    val transform=Calib3d.estimateAffinePartial2D(l,r,inliers,Calib3d.RANSAC,0.5,2500,0.99,10)
                    try {
                        if(transform.empty())return@forEachIndexed
                        val aa=transform.get(0,0)[0];val bb=transform.get(1,0)[0]
                        if(hypot(aa,bb) !in 0.85..1.15||abs(atan2(bb,aa))>Math.toRadians(15.0))return@forEachIndexed
                        val accepted=good.indices.filter{inliers.get(it,0)[0]>0}
                        if(accepted.size<24||accepted.size<good.size*0.25)return@forEachIndexed
                        edges.add(i to j)
                        accepted.filterIndexed{n,_->n%max(1,accepted.size/32)==0}.take(40).forEach{k->
                            ties.add(Tie(i,j,Point(left[k].x-localFrames[i].cx,left[k].y-localFrames[i].cy),Point(right[k].x-localFrames[j].cx,right[k].y-localFrames[j].cy)))
                        }
                    }finally{l.release();r.release();inliers.release();transform.release()}
                }finally{matches.forEach{it.release()};matcher.clear()}
            }
            val reachable=mutableSetOf(0);repeat(frames.size){edges.forEach{(i,j)->if(i in reachable||j in reachable){reachable.add(i);reachable.add(j)}}}
            require(reachable.size==frames.size){"Image alignment connected ${reachable.size}/${frames.size} photos. No partial layer was published. Check overlap or divide the flight into smaller areas."}
            check();progress("Refining the map alignment",0.48)
            val n=frames.size*4;val normal=DoubleArray(n*n);val rhs=DoubleArray(n)
            fun equation(values:List<Pair<Int,Double>>,target:Double,weight:Double=1.0){
                values.forEach{(i,a)->rhs[i]+=weight*a*target;values.forEach{(j,b)->normal[i*n+j]+=weight*a*b}}
            }
            ties.forEach{t->val i=t.i*4;val j=t.j*4;val a=t.left;val b=t.right
                equation(listOf(i to a.x,i+1 to -a.y,i+2 to 1.0,j to -b.x,j+1 to b.y,j+2 to -1.0),localFrames[t.j].cx+b.x-localFrames[t.i].cx-a.x)
                equation(listOf(i to a.y,i+1 to a.x,i+3 to 1.0,j to -b.y,j+1 to -b.x,j+3 to -1.0),localFrames[t.j].cy+b.y-localFrames[t.i].cy-a.y)
            }
            // Soft scale/rotation and GPS priors prevent collapse or uncontrolled absolute drift.
            repeat(frames.size){i->equation(listOf(i*4 to 1.0),0.0,2500.0);equation(listOf(i*4+1 to 1.0),0.0,2500.0)
                equation(listOf(i*4+2 to 1.0),0.0,0.04);equation(listOf(i*4+3 to 1.0),0.0,0.04)}
            val m=Mat(n,n,CvType.CV_64F);val b=Mat(n,1,CvType.CV_64F);val answer=Mat()
            try{m.put(0,0,*normal);b.put(0,0,*rhs);require(Core.solve(m,b,answer,Core.DECOMP_CHOLESKY)){"Alignment refinement failed."}
                localFrames.forEachIndexed{i,f->val a=DoubleArray(4){answer.get(i*4+it,0)[0]};a[0]+=1
                    f.adjustment=a}
            }finally{m.release();b.release();answer.release()}
            val adjustments=localFrames.map{it.adjustment}
            MappingPhotoStore.writeJson(File(root,"alignment-diagnostic.json"),JSONObject().put("matchedPairs",edges.size).put("tiePoints",ties.size)
                .put("minScale",adjustments.minOf{hypot(it[0],it[1])}).put("maxScale",adjustments.maxOf{hypot(it[0],it[1])})
                .put("maxShiftMeters",adjustments.maxOf{hypot(it[2],it[3])}).put("adjustments",JSONArray(adjustments.map{JSONArray(it.toList())})))
            require(adjustments.all{hypot(it[0],it[1]) in 0.8..1.2&&hypot(it[2],it[3])<30}){"Alignment moved too far from the drone's position. No layer was published."}
            val rms=sqrt(ties.map{t->val a=localFrames[t.i].adjustment;val b=localFrames[t.j].adjustment;val l=t.left;val r=t.right
                val x=localFrames[t.i].cx+a[0]*l.x-a[1]*l.y+a[2]-localFrames[t.j].cx-b[0]*r.x+b[1]*r.y-b[2]
                val y=localFrames[t.i].cy+a[1]*l.x+a[0]*l.y+a[3]-localFrames[t.j].cy-b[1]*r.x-b[0]*r.y-b[3];x*x+y*y}.average())
            require(rms.isFinite()&&rms<1.5){"Image alignment residual is too large. No layer was published."}
            features.forEach{it.descriptor.release()};features.clear()
            val gsds=frames.map{MappingGeometry.gsd(it.pose,it.width,it.height)}.sorted();val gsd=gsds[gsds.size/2]
            val z=MappingGeometry.zoom(gsd,frames.map{it.pose.latitude}.average());val factor=256.0*(1 shl z)/MappingGeometry.WORLD
            val footprints=localFrames.map{f->f.corners().map{Point((it.x+originX+MappingGeometry.WORLD/2)*factor,(MappingGeometry.WORLD/2-it.y-originY)*factor)}}
            val tileSets=footprints.map{p->
                val x0=floor(p.minOf{it.x}/256).toInt();val x1=floor(p.maxOf{it.x}/256).toInt();val y0=floor(p.minOf{it.y}/256).toInt();val y1=floor(p.maxOf{it.y}/256).toInt()
                require((x1.toLong()-x0+1)*(y1-y0+1)<16000){"A photo footprint is too large for this local job."}
                (x0..x1).flatMap{x->(y0..y1).map{y->MapTileIndex.getTileIndex(z,x,y)}}
            }
            val highest=tileSets.flatten().distinct();require(highest.size<=16000){"Split this flight into smaller mapping areas."}
            require(out.usableSpace>highest.size*700000L+MappingPhotoStore.FREE_SPACE_RESERVE){"Not enough local storage for the map and share package."}
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            // Decode each original once. Read-only mapped working pixels let each output tile
            // be composited/encoded once without retaining all full-size photos in RAM.
            val rawFiles=localFrames.mapIndexed{i,f->
                check();progress("Preparing image pixels ${i+1}/${frames.size}",0.5+0.08*i/frames.size)
                require(f.width.toLong()*f.height*8<Runtime.getRuntime().maxMemory()*0.7){"An individual photo is too large for this tablet's processing memory."}
                val source=BitmapFactory.decodeFile(f.file.path,BitmapFactory.Options().apply{inMutable=true;inPreferredConfig=Bitmap.Config.ARGB_8888})?:error("Cannot decode an original for stitching.")
                val file=File(out,"working-$i.raw")
                try{feather(source);val bytes=java.nio.ByteBuffer.allocate(source.byteCount);source.copyPixelsToBuffer(bytes)
                    file.outputStream().buffered().use{it.write(bytes.array())}
                }finally{source.recycle()}
                file
            }
            val mapped=rawFiles.mapIndexed{i,file->java.io.FileInputStream(file).channel.use{it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,file.length())}}.toMutableList()
            try {
                val byTile=mutableMapOf<Long,MutableList<Int>>()
                tileSets.forEachIndexed{i,indices->indices.forEach{index->byTile.getOrPut(index){mutableListOf()}.add(i)}}
                val ordered=highest.sortedWith(compareBy<Long>{MapTileIndex.getX(it)}.thenBy{MapTileIndex.getY(it)})
                ordered.forEachIndexed{number,index->
                    check();progress("Stitching map tile ${number+1}/${ordered.size}",0.58+0.27*number/ordered.size)
                    val x=MapTileIndex.getX(index);val y=MapTileIndex.getY(index);val target=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
                    try {
                        val canvas=Canvas(target)
                        byTile[index].orEmpty().forEach{i->
                            val f=localFrames[i];val p0=f.world(0.0,0.0);val px=f.world(1.0,0.0);val py=f.world(0.0,1.0)
                            val matrix=Matrix().apply{setValues(floatArrayOf(((px.x-p0.x)*factor).toFloat(),((py.x-p0.x)*factor).toFloat(),((p0.x+originX+MappingGeometry.WORLD/2)*factor-x*256).toFloat(),
                                (-(px.y-p0.y)*factor).toFloat(),(-(py.y-p0.y)*factor).toFloat(),((MappingGeometry.WORLD/2-p0.y-originY)*factor-y*256).toFloat(),0f,0f,1f))}
                            val inverse=Matrix();require(matrix.invert(inverse)){"Invalid image placement."}
                            val box=floatArrayOf(0f,0f,256f,0f,256f,256f,0f,256f);inverse.mapPoints(box)
                            val rx=(floor(listOf(box[0],box[2],box[4],box[6]).min()).toInt()-2).coerceIn(0,f.width)
                            val ry=(floor(listOf(box[1],box[3],box[5],box[7]).min()).toInt()-2).coerceIn(0,f.height)
                            val right=(ceil(listOf(box[0],box[2],box[4],box[6]).max()).toInt()+2).coerceIn(0,f.width)
                            val bottom=(ceil(listOf(box[1],box[3],box[5],box[7]).max()).toInt()+2).coerceIn(0,f.height)
                            if(right<=rx||bottom<=ry)return@forEach
                            val w=right-rx;val h=bottom-ry;val bytes=ByteArray(w*h*4);val pixels=mapped[i]
                            repeat(h){row->pixels.position(((ry+row)*f.width+rx)*4);pixels.get(bytes,row*w*4,w*4)}
                            val region=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                            try{region.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes));matrix.preTranslate(rx.toFloat(),ry.toFloat());canvas.drawBitmap(region,matrix,paint)}finally{region.recycle()}
                        }
                        val file=File(out,"$z/$x/$y.png");file.parentFile!!.mkdirs();file.outputStream().buffered().use{require(target.compress(Bitmap.CompressFormat.PNG,100,it))}
                    }finally{target.recycle()}
                }
            }finally{mapped.clear();rawFiles.forEach{it.delete()}}
            var children=highest
            for(level in z-1 downTo 14){
                check();progress("Building map zoom levels",0.86+0.08*(z-level)/(z-14).coerceAtLeast(1))
                val parents=children.map{MapTileIndex.getTileIndex(level,MapTileIndex.getX(it)/2,MapTileIndex.getY(it)/2)}.distinct()
                parents.forEach{index->check();val x=MapTileIndex.getX(index);val y=MapTileIndex.getY(index);val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
                    try{val canvas=Canvas(bitmap);for(dx in 0..1)for(dy in 0..1){val file=File(out,"${level+1}/${x*2+dx}/${y*2+dy}.png")
                        if(file.isFile){val child=BitmapFactory.decodeFile(file.path)?:error("Cannot decode a stitched tile.");try{canvas.drawBitmap(child,null,Rect(dx*128,dy*128,dx*128+128,dy*128+128),paint)}finally{child.recycle()}}}
                        val file=File(out,"$level/$x/$y.png");file.parentFile!!.mkdirs();file.outputStream().buffered().use{require(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                    }finally{bitmap.recycle()}}
                children=parents
            }
            val corners=localFrames.flatMap{it.corners()};val captured=frames.map{it.pose.capturedAt}.sorted()
            return JSONObject().put("photoCount",frames.size).put("profile","daylight-rgb").put("captureTimeLocal",captured.first()).put("captureEndTimeLocal",captured.last()).put("captureTimeZone","camera local time; zone unknown")
                .put("groundResolutionCmPerPixel",gsd*100).put("minZoom",14).put("maxZoom",z)
                .put("bounds",JSONArray(listOf(MappingGeometry.longitude(corners.minOf{it.x}+originX),MappingGeometry.latitude(corners.minOf{it.y}+originY),MappingGeometry.longitude(corners.maxOf{it.x}+originX),MappingGeometry.latitude(corners.maxOf{it.y}+originY))))
                .put("engine","OpenCV ${Core.VERSION} · SIFT + global 2D alignment").put("registeredPhotos",frames.size).put("matchedPairs",edges.size).put("alignmentRmsMeters",rms)
                .put("quality","Flat-ground 2D mosaic. Review seams, trees and buildings; terrain relief is not corrected. Absolute placement follows drone GPS.")
                .put("photogrammetric3D",false)
        }finally{features.forEach{it.descriptor.release()};sift.clear();matcher.clear()}
    }
    private fun feather(bitmap:Bitmap){
        bitmap.setHasAlpha(true);val canvas=Canvas(bitmap);val paint=Paint().apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
        val w=bitmap.width.toFloat();val h=bitmap.height.toFloat();val edge=min(w,h)*0.04f
        listOf(floatArrayOf(0f,0f,edge,0f,0f,0f,edge,h),floatArrayOf(w,0f,w-edge,0f,w-edge,0f,w,h),
            floatArrayOf(0f,0f,0f,edge,0f,0f,w,edge),floatArrayOf(0f,h,0f,h-edge,0f,h-edge,w,h)).forEach{p->
            paint.shader=LinearGradient(p[0],p[1],p[2],p[3],Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP);canvas.drawRect(p[4],p[5],p[6],p[7],paint)}
    }
}
