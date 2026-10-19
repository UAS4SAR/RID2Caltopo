"""Reproducible, CPU-only mobile OpenMVG qualification build; no incident upload."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

PIN = "01193a245ee3c36458e650b1cf4402caad8983ef"  # official v2.1
HERE = Path(__file__).resolve().parent


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=("android", "ios", "macos"))
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    cmake = shutil.which("cmake") or str(sdk / "cmake/3.22.1/bin/cmake")
    ninja = shutil.which("ninja") or str(sdk / "cmake/3.22.1/bin/ninja")
    source, build = args.source.resolve(), args.build.resolve()
    if not source.exists():
        run("git", "clone", "--depth", "1", "--branch", "v2.1", "--recursive",
            "--shallow-submodules", "https://github.com/openMVG/openMVG.git", source)
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if revision != PIN:
        raise SystemExit("Source must be the pinned official OpenMVG v2.1 revision.")
    # Fix an upstream nonliteral printf rather than disabling format-security.
    coin = source / "src/dependencies/osi_clp/CoinUtils/src/CoinMessageHandler.cpp"
    text = coin.read_text()
    old, new = 'sprintf(messageOut_,format_+2);', 'sprintf(messageOut_,"%s",format_+2);'
    if old not in text and new not in text:
        raise SystemExit("Unexpected CoinUtils source; patch needs review.")
    coin.write_text(text.replace(old, new))
    project = source / "src/CMakeLists.txt"
    hook = '\nif(DEFINED R2C_PROBE_DIR)\n  include("${R2C_PROBE_DIR}/probe.cmake")\nendif()\n'
    if hook not in project.read_text():
        project.write_text(project.read_text() + hook)
    options = ["-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}", "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH", "-DOpenMVG_USE_OPENMP=OFF",
        "-DOpenMVG_BUILD_TESTS=OFF", "-DOpenMVG_BUILD_EXAMPLES=OFF",
        "-DOpenMVG_BUILD_GUI_SOFTWARES=OFF", "-DOpenMVG_BUILD_DOC=OFF",
        f"-DR2C_PROBE_DIR={HERE}"]
    targets = ["r2c_openmvg_probe"]
    if args.platform == "android":
        ndk = sdk / "ndk/29.0.14206865"
        options += [f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
            "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-29", "-DANDROID_STL=c++_static"]
        targets += ["openMVG_main_" + name for name in ("SfMInit_ImageListing", "ComputeFeatures",
            "ComputeMatches", "GeometricFilter", "SfM", "ConvertSfM_DataFormat")]
    elif args.platform == "ios":
        options += ["-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_SYSROOT=iphoneos",
            "-DCMAKE_OSX_ARCHITECTURES=arm64", "-DCMAKE_OSX_DEPLOYMENT_TARGET=17.0",
            "-DIOS_DEPLOYMENT_TARGET=17.0", "-DIOS_PLATFORM=OS", "-DOpenMVG_BUILD_SOFTWARES=OFF"]
    run(cmake, "-S", source / "src", "-B", build, *options)
    run(cmake, "--build", build, "--target", *targets, "-j", max(1, min(args.jobs, 8)))
    if args.platform == "ios":
        archives = sorted(build.rglob("*.a"))
        run("xcrun", "libtool", "-static", "-o", build / "libR2COpenMVG.a", *archives)


if __name__ == "__main__":
    main()
