"""Device-free test of production Kotlin native declarations and Android JNI C++ adapter on macOS.
Run the native pipeline fixture and :app:compileDebugKotlin first.
"""
import argparse,os,subprocess,tempfile,shutil
from pathlib import Path
root=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--fixture',type=Path,required=True)
p.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home')))
a=p.parse_args()
build=root/'apple/Build/Mapping/macos'
cache=dict(line.split('=',1) for line in (build/'CMakeCache.txt').read_text().splitlines() if '=' in line and not line.startswith(('#','//')))
source=Path(cache['OPENMVG_SOURCE:PATH'] if 'OPENMVG_SOURCE:PATH' in cache else cache['OPENMVG_SOURCE:UNINITIALIZED'])
ndk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))/'ndk/29.0.14206865'
stdlib=sorted((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.10').glob('*/*.jar'))[0]
with tempfile.TemporaryDirectory(prefix='r2c-jni-host-') as temporary:
 work=Path(temporary)
 shutil.copy2(ndk/'toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/include/jni.h',work/'jni.h')
 includes=[work,source/'src/third_party/eigen',source/'src/third_party/ceres-solver/include',source/'src/third_party/ceres-solver/internal/ceres/miniglog',build/'ceres/config']
 subprocess.run(['xcrun','clang++','-std=c++17','-O2','-dynamiclib',str(root/'app/src/main/cpp/mapping_surface.cpp'),str(build/'libr2c_mapping_runtime.a'),'-framework','Accelerate',*[f'-I{x}' for x in includes],'-o',str(work/'libr2c_mapping_surface.dylib')],check=True)
 classes=root/'app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
 cp=os.pathsep.join(map(str,[work,classes,stdlib]))
 subprocess.run([str(a.java_home/'bin/javac'),'-cp',cp,'-d',str(work),str(root/'native/mapping/JNITests.java')],check=True)
 subprocess.run([str(a.java_home/'bin/java'),f'-Djava.library.path={work}','-cp',cp,'org.ncssar.rid2caltopo.mapping.JNITests',str(a.fixture.resolve())],check=True)
