package org.ncssar.rid2caltopo.mapping;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Runs the actual Kotlin declarations and JNI adapter on the host, without Android or a device. */
public final class JNITests {
 static double[] doubles(Path p)throws Exception {var b=ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer();var out=new double[b.remaining()];b.get(out);return out;}
 public static void main(String[] args)throws Exception {
  var nativeEngine=MappingSurfaceNative.INSTANCE;nativeEngine.requireAvailable();var root=Path.of(args[0]);
  var cameras=doubles(root.resolve("cameras.f64"));var lasers=doubles(root.resolve("lasers.f64"));var pitch=new double[9];Arrays.fill(pitch,-90);
  var paths=new String[9];for(int i=0;i<9;i++)paths[i]=root.resolve(i+".gray").toString();
  int[] calls={0,0};var progress=new MappingSurfaceNative.StageProgress(){
   public boolean stage(String label,double fraction){if(label.isEmpty()||!Double.isFinite(fraction))throw new AssertionError();calls[0]++;return true;}
   public void connectivity(int round,int[] groups,int[] support,int[] exclusions){if(groups.length!=9||support.length!=9||exclusions.length!=9)throw new AssertionError();calls[1]++;}
  };
  var aligned=nativeEngine.reconstructStage(cameras,lasers,pitch,1008,756,700,paths,new double[0],progress);
  if(!Arrays.equals(aligned.getAdjustment(),doubles(root.resolve("adjustment.f64"))))throw new AssertionError("JNI camera solution differs from C API");
  var dense=nativeEngine.reconstructStage(cameras,lasers,pitch,1008,756,700,paths,aligned.getAdjustment(),progress);
  var buffer=ByteBuffer.wrap(Files.readAllBytes(root.resolve("heights.f32"))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();var expected=new float[buffer.remaining()];buffer.get(expected);
  if(!Arrays.equals(dense.getHeights(),expected))throw new AssertionError("JNI depth grid differs from C API");
  if(calls[0]<100||calls[1]==0)throw new AssertionError("Missing progress or connectivity");
  var rgba=new byte[8*8*4];for(int y=0;y<8;y++)for(int x=0;x<8;x++)rgba[4*(y*8+x)+(y>=4?2:(x<4?0:1))]=(byte)255;
  if(!Arrays.equals(nativeEngine.sampleRGBA(rgba,8,8,4),new byte[]{76,(byte)150,29,29}))throw new AssertionError("JNI sample differs");
  try{nativeEngine.sampleRGBA(new byte[1],8,8,4);throw new AssertionError("Accepted short raster");}catch(IllegalStateException expectedError){}
  var cancelled=new MappingSurfaceNative.StageProgress(){
   public boolean stage(String s,double f){throw new IllegalArgumentException("cancel-sentinel");}
   public void connectivity(int r,int[] g,int[] s,int[] e){}
  };
  try{nativeEngine.reconstructStage(cameras,lasers,pitch,1008,756,700,paths,new double[0],cancelled);throw new AssertionError("Ignored cancellation");}
  catch(IllegalArgumentException e){if(!"cancel-sentinel".equals(e.getMessage()))throw e;}
  var blankRoot=Files.createTempDirectory("r2c-jni-approximate-");
  try {
   var blankPaths=new String[9];var blank=new byte[1008*756];Arrays.fill(blank,(byte)127);
   for(int i=0;i<9;i++){var file=blankRoot.resolve(i+".gray");Files.write(file,blank);blankPaths[i]=file.toString();}
   var fallback=nativeEngine.reconstructStage(cameras,lasers,pitch,1008,756,700,blankPaths,new double[0],progress);
   var solution=fallback.getAdjustment();
   if(solution.length!=13+9*12||solution[solution.length-1]!=-1||fallback.getStatistics()[2]!=0)throw new AssertionError("Missing approximate classification");
   var projected=nativeEngine.reconstructStage(cameras,lasers,pitch,1008,756,700,blankPaths,solution,progress);
   if(projected.getStatistics()[5]!=0||projected.getHeights().length==0)throw new AssertionError("Approximate projection invented depth measurements");
  }finally{try(var files=Files.list(blankRoot)){for(var file:files.toList())Files.delete(file);}Files.delete(blankRoot);}
  System.out.println("Actual Kotlin/JNI adapter matches C API camera/depth arrays; callbacks, sampler, bad input and cancellation passed");
 }
}
