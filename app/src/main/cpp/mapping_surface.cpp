// Android adapter for the shared offline surface solver and renderer.
#include <jni.h>
#include <memory>
#include <stdexcept>
#include "../../../../native/mapping/SurfaceRefinement.hpp"
#include "../../../../native/mapping/SurfaceCore.hpp"
namespace {
std::vector<double> doubles(JNIEnv* e,jdoubleArray a){std::vector<double> v(e->GetArrayLength(a));e->GetDoubleArrayRegion(a,0,int(v.size()),v.data());return v;}
void fail(JNIEnv* e,const char* message){if(!e->ExceptionCheck())e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),message);}
struct Progress: r2c::mapping::Progress {
 JNIEnv* e; jobject object;
 Progress(JNIEnv* env,jobject obj):e(env),object(obj){}
 bool iteration(int i,double cost)override {
  auto c=e->GetObjectClass(object);auto m=e->GetMethodID(c,"iteration","(ID)Z");e->DeleteLocalRef(c);
  bool ok=e->CallBooleanMethod(object,m,i,cost);return !e->ExceptionCheck()&&ok;
 }
 void connectivity(int round,const std::vector<int>& group,const std::vector<int>& support,const std::vector<int>& exclusion)override {
  auto c=e->GetObjectClass(object);auto m=e->GetMethodID(c,"connectivity","(I[I[I[I)V");e->DeleteLocalRef(c);
  int n=int(group.size());auto a=e->NewIntArray(n),b=e->NewIntArray(n),d=e->NewIntArray(n);
  e->SetIntArrayRegion(a,0,n,group.data());e->SetIntArrayRegion(b,0,n,support.data());e->SetIntArrayRegion(d,0,n,exclusion.data());
  e->CallVoidMethod(object,m,round,a,b,d);e->DeleteLocalRef(a);e->DeleteLocalRef(b);e->DeleteLocalRef(d);
  if(e->ExceptionCheck())throw std::runtime_error("Processing interrupted.");
 }
};
jint render(JNIEnv* e,jfloatArray surface,jint gw,jint gh,jdouble gx,jdouble gy,jdouble cell,jdoubleArray c,jdoubleArray k,jint iw,jint ih,jobjectArray paths,jdouble tx,jdouble ty,jdouble step,jintArray output,bool ordered){
 try {
  if(e->GetArrayLength(output)!=65536)throw std::runtime_error("Invalid surface tile.");
  std::vector<float> z(e->GetArrayLength(surface));e->GetFloatArrayRegion(surface,0,int(z.size()),z.data());
  std::vector<std::string> files;for(int i=0;i<e->GetArrayLength(paths);++i){auto s=(jstring)e->GetObjectArrayElement(paths,i);auto p=e->GetStringUTFChars(s,nullptr);files.emplace_back(p);e->ReleaseStringUTFChars(s,p);e->DeleteLocalRef(s);}
  std::vector<int32_t> out;int count=r2c::mapping::render(z,gw,gh,gx,gy,cell,doubles(e,c),doubles(e,k),iw,ih,files,tx,ty,step,out,ordered);
  e->SetIntArrayRegion(output,0,65536,out.data());return count;
 }catch(const std::exception& ex){fail(e,ex.what());return -1;}
}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_adjustAttitude(JNIEnv* e,jobject,jdoubleArray c,jdoubleArray o,jint count,jint w,jint h,jdouble f,jdoubleArray laser,jobject object){
 try{Progress progress(e,object);auto v=r2c::mapping::adjust(progress,doubles(e,c),doubles(e,o),count,w,h,f,doubles(e,laser));auto a=e->NewDoubleArray(int(v.size()));e->SetDoubleArrayRegion(a,0,int(v.size()),v.data());return a;}catch(const std::exception& ex){fail(e,ex.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_releasePixels(JNIEnv*,jobject){r2c::mapping::releasePixels();}
extern "C" JNIEXPORT jint JNICALL Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_render(JNIEnv* e,jobject,jfloatArray s,jint w,jint h,jdouble x,jdouble y,jdouble cell,jdoubleArray c,jdoubleArray k,jint iw,jint ih,jobjectArray p,jdouble tx,jdouble ty,jdouble step,jintArray out){return render(e,s,w,h,x,y,cell,c,k,iw,ih,p,tx,ty,step,out,true);}
extern "C" JNIEXPORT jint JNICALL Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_renderReference(JNIEnv* e,jobject,jfloatArray s,jint w,jint h,jdouble x,jdouble y,jdouble cell,jdoubleArray c,jdoubleArray k,jint iw,jint ih,jobjectArray p,jdouble tx,jdouble ty,jdouble step,jintArray out){return render(e,s,w,h,x,y,cell,c,k,iw,ih,p,tx,ty,step,out,false);}

extern "C" JNIEXPORT void JNICALL Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_refine(JNIEnv* e,jobject,jfloatArray s,jint w,jint h,jdouble cell){
 try {if(e->GetArrayLength(s)!=int64_t(w)*h)throw std::runtime_error("Invalid surface size.");
 std::vector<float> z(e->GetArrayLength(s));e->GetFloatArrayRegion(s,0,int(z.size()),z.data());r2c::mapping::refineSurface(z.data(),w,h,cell);e->SetFloatArrayRegion(s,0,int(z.size()),z.data());
 }catch(const std::exception& ex){fail(e,ex.what());}
}
extern "C" JNIEXPORT jintArray JNICALL Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_faces(JNIEnv* e,jobject,jfloatArray s,jint w,jint h,jint stride,jintArray ids){
 try {if(e->GetArrayLength(s)!=int64_t(w)*h)throw std::runtime_error("Invalid surface size.");
 std::vector<float> z(e->GetArrayLength(s));e->GetFloatArrayRegion(s,0,int(z.size()),z.data());std::vector<int32_t> v(e->GetArrayLength(ids));e->GetIntArrayRegion(ids,0,int(v.size()),v.data());
 auto out=r2c::mapping::surfaceFaces(z.data(),w,h,stride,v.data(),v.size());auto result=e->NewIntArray(int(out.size()));e->SetIntArrayRegion(result,0,int(out.size()),out.data());return result;
 }catch(const std::exception& ex){fail(e,ex.what());return nullptr;}
}

extern "C" JNIEXPORT void JNICALL Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_prepareProjection(JNIEnv* e,jobject,jfloatArray surface,jint w,jint h,jdouble x,jdouble y,jdouble cell,jdoubleArray c,jdoubleArray k,jint iw,jint ih,jobjectArray paths,jobject object){
 try {std::vector<float> z(e->GetArrayLength(surface));e->GetFloatArrayRegion(surface,0,int(z.size()),z.data());
 std::vector<std::string> files;for(int i=0;i<e->GetArrayLength(paths);++i){auto s=(jstring)e->GetObjectArrayElement(paths,i);auto p=e->GetStringUTFChars(s,nullptr);files.emplace_back(p);e->ReleaseStringUTFChars(s,p);e->DeleteLocalRef(s);}
 Progress progress(e,object);r2c::mapping::prepareProjection(z,w,h,x,y,cell,doubles(e,c),doubles(e,k),iw,ih,files,[&](double fraction){if(!progress.iteration(0,fraction))throw std::runtime_error("Processing cancelled; originals retained.");});
 }catch(const std::exception& ex){fail(e,ex.what());}
}

#include "../../../../native/mapping/include/R2CMapping.h"
namespace {
struct StageCallback {
 JNIEnv* env; jobject object;
 static void connectivity(void* context,int round,const int* groups,const int* support,const int* exclusions,int count){
  auto& c=*static_cast<StageCallback*>(context);auto e=c.env;if(e->ExceptionCheck())return;
  auto cls=e->GetObjectClass(c.object);auto method=e->GetMethodID(cls,"connectivity","(I[I[I[I)V");e->DeleteLocalRef(cls);if(!method)return;
  auto g=e->NewIntArray(count),s=e->NewIntArray(count),x=e->NewIntArray(count);
  e->SetIntArrayRegion(g,0,count,groups);e->SetIntArrayRegion(s,0,count,support);e->SetIntArrayRegion(x,0,count,exclusions);
  e->CallVoidMethod(c.object,method,round,g,s,x);e->DeleteLocalRef(g);e->DeleteLocalRef(s);e->DeleteLocalRef(x);
 }
 static int update(void* context,const char* label,double fraction){
  auto& c=*static_cast<StageCallback*>(context);auto e=c.env;
  if(e->ExceptionCheck())return 0;
  auto cls=e->GetObjectClass(c.object);auto method=e->GetMethodID(cls,"stage","(Ljava/lang/String;D)Z");e->DeleteLocalRef(cls);
  if(!method)return 0;auto text=e->NewStringUTF(label);bool ok=e->CallBooleanMethod(c.object,method,text,fraction);e->DeleteLocalRef(text);
  return !e->ExceptionCheck()&&ok;
 }
};
}
extern "C" JNIEXPORT jobject JNICALL Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_reconstructStage(JNIEnv* e,jobject,jdoubleArray cameras,jdoubleArray lasers,jdoubleArray pitch,jint w,jint h,jdouble focal,jobjectArray paths,jdoubleArray adjustment,jobject progress){
 try{
  auto c=doubles(e,cameras),l=doubles(e,lasers),p=doubles(e,pitch),a=doubles(e,adjustment);int n=e->GetArrayLength(paths);
  if(n<3||n>192||c.size()!=size_t(n*15)||l.size()!=size_t(n*3)||p.size()!=size_t(n))throw std::runtime_error("Invalid reconstruction arrays.");
  std::vector<std::string> files;for(int i=0;i<n;++i){auto s=(jstring)e->GetObjectArrayElement(paths,i);if(!s)throw std::runtime_error("Missing original sample.");auto text=e->GetStringUTFChars(s,nullptr);if(!text)return nullptr;files.emplace_back(text);e->ReleaseStringUTFChars(s,text);e->DeleteLocalRef(s);}
  std::vector<const char*> names;for(auto& f:files)names.push_back(f.c_str());
  R2CMappingInput input{n,w,h,focal,c.data(),l.data(),p.data(),names.data()};StageCallback cb{e,progress};input.connectivity=StageCallback::connectivity;input.connectivityContext=&cb;R2CMappingResult* raw=nullptr;char error[1024]{};
  int ok=a.empty()?R2CMappingAlign(&input,StageCallback::update,&cb,&raw,error,sizeof(error)):R2CMappingDense(&input,a.data(),a.size(),StageCallback::update,&cb,&raw,error,sizeof(error));
  std::unique_ptr<R2CMappingResult,decltype(&R2CMappingFree)> result(raw,R2CMappingFree);
  if(e->ExceptionCheck())return nullptr;if(!ok||!result)throw std::runtime_error(error);
  auto adjusted=e->NewDoubleArray(result->adjustmentCount);if(result->adjustmentCount)e->SetDoubleArrayRegion(adjusted,0,result->adjustmentCount,result->adjustment);
  int cells=result->width*result->height;auto heights=e->NewFloatArray(cells);if(cells)e->SetFloatArrayRegion(heights,0,cells,result->heights);
  double gridValues[]={double(result->width),double(result->height),result->x,result->y,result->cell};auto grid=e->NewDoubleArray(5);e->SetDoubleArrayRegion(grid,0,5,gridValues);
  int stats[]={result->selectedPhotos,result->registeredPhotos,result->featureTracks,result->matchedPairs,result->stereoPairs,result->measuredCells};auto statistics=e->NewIntArray(6);e->SetIntArrayRegion(statistics,0,6,stats);
  auto cls=e->FindClass("org/ncssar/rid2caltopo/mapping/MappingSurfaceNative$Reconstruction");auto constructor=e->GetMethodID(cls,"<init>","([D[F[D[I)V");
  return e->NewObject(cls,constructor,adjusted,heights,grid,statistics);
 }catch(const std::exception& ex){fail(e,ex.what());return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_sampleRGBA(JNIEnv* e,jobject,jbyteArray rgba,jint w,jint h,jint factor){
 try{
  if(w<=0||h<=0||w>12000||h>12000||factor<1||factor>16||w%factor||h%factor||e->GetArrayLength(rgba)!=int64_t(w)*h*4)throw std::runtime_error("Invalid original raster.");
  std::vector<uint8_t> input(size_t(w)*h*4),output(size_t(w/factor)*(h/factor));e->GetByteArrayRegion(rgba,0,input.size(),reinterpret_cast<jbyte*>(input.data()));char error[512]{};
  if(!R2CMappingSampleRGBA(input.data(),w,h,factor,output.data(),output.size(),error,sizeof(error)))throw std::runtime_error(error);
  auto result=e->NewByteArray(output.size());e->SetByteArrayRegion(result,0,output.size(),reinterpret_cast<jbyte*>(output.data()));return result;
 }catch(const std::exception& ex){fail(e,ex.what());return nullptr;}
}
