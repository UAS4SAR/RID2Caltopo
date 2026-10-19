"""Build the offline ARM64 R2C surface runtime from pinned CPU-only dependencies."""
import argparse
from pathlib import Path
import shutil
import subprocess
import os

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PIN = "01193a245ee3c36458e650b1cf4402caad8983ef"

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,required=True)
    p.add_argument('--dependencies',type=Path,required=True)
    p.add_argument('--build',type=Path,required=True)
    p.add_argument('--opencv',type=Path,required=True,help='Pinned OpenCV 4.12.0 AAR prefab/modules/opencv_java4 directory')
    a=p.parse_args()
    source=a.source.resolve();deps=a.dependencies.resolve();build=a.build.resolve()
    assert subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()==PIN
    sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))
    ndk=sdk/'ndk/29.0.14206865';cmake=sdk/'cmake/3.22.1/bin/cmake';ninja=sdk/'cmake/3.22.1/bin/ninja'
    build.mkdir(parents=True,exist_ok=True)
    project=build/'project';project.mkdir(exist_ok=True)
    (project/'CMakeLists.txt').write_text((HERE/'surface-engine.cmake').read_text())
    subprocess.run([str(cmake),'-S',str(project),'-B',str(build/'build'),'-G','Ninja',
        '-DCMAKE_BUILD_TYPE=Release',f'-DCMAKE_MAKE_PROGRAM={ninja}',
        f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
        '-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-29','-DANDROID_STL=c++_shared',
        f'-DOPENMVG_SOURCE={source}',f'-DOPENMVG_BUILD={deps}',f'-DR2C_SOURCE={ROOT}',f'-DOPENCV_PREFAB={a.opencv.resolve()}'],check=True)
    subprocess.run([str(cmake),'--build',str(build/'build'),'-j','2'],check=True)
    out=ROOT/'tools/mapping/dist/native/android/arm64-v8a';out.mkdir(parents=True,exist_ok=True)
    shutil.copy2(build/'build/libr2c_mapping_surface.so',out/'libr2c_mapping_surface.so')
    subprocess.run([str(ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip'),str(out/'libr2c_mapping_surface.so')],check=True)
    print('Bundled local runtime:',out/'libr2c_mapping_surface.so')

if __name__=='__main__':main()
