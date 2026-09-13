#include <jni.h>
#include "aol_grid.h"
#define JNI(name) Java_org_ncssar_rid2caltopo_video_surface_SurfaceNative_##name
extern "C" {
JNIEXPORT jlong JNICALL JNI(create)(JNIEnv*,jobject,jdouble lat,jdouble lon,jdouble west,jdouble south,jint w,jint h){return (jlong)aol_grid_create(lat,lon,west,south,w,h);}
JNIEXPORT void JNICALL JNI(free)(JNIEnv*,jobject,jlong g){aol_grid_free((void*)g);}
JNIEXPORT jint JNICALL JNI(open)(JNIEnv*e,jobject,jlong g,jstring path){const char*p=e->GetStringUTFChars(path,nullptr);int r=aol_grid_open((void*)g,p);e->ReleaseStringUTFChars(path,p);return r;}
JNIEXPORT jint JNICALL JNI(step)(JNIEnv*,jobject,jlong g,jint batch){return aol_grid_step((void*)g,batch);}
JNIEXPORT jint JNICALL JNI(write)(JNIEnv*e,jobject,jlong g,jstring surface,jstring ground){const char*s=e->GetStringUTFChars(surface,nullptr),*t=e->GetStringUTFChars(ground,nullptr);int r=aol_grid_write((void*)g,s,t);e->ReleaseStringUTFChars(surface,s);e->ReleaseStringUTFChars(ground,t);return r;}
JNIEXPORT jstring JNICALL JNI(error)(JNIEnv*e,jobject,jlong g){return e->NewStringUTF(aol_grid_error((void*)g));}
JNIEXPORT jstring JNICALL JNI(crs)(JNIEnv*e,jobject,jlong g){return e->NewStringUTF(aol_grid_crs((void*)g));}
JNIEXPORT jlong JNICALL JNI(read)(JNIEnv*,jobject,jlong g){return aol_grid_read((void*)g);}
JNIEXPORT jlong JNICALL JNI(count)(JNIEnv*,jobject,jlong g){return aol_grid_count((void*)g);}
JNIEXPORT jlong JNICALL JNI(missing)(JNIEnv*,jobject,jlong g){return aol_grid_missing((void*)g);}
}
