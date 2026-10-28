#!/usr/bin/env python3
"""Build the kit's FFmpeg source and bridge as an arm64 iOS static library.

Edit source/ffmpeg as desired, then run this script. Apple SDKs are obtained
from the recipient's own Xcode installation and are not redistributed here.
"""
from pathlib import Path
import argparse
import os
import subprocess


def main():
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=root / 'source/ffmpeg')
    parser.add_argument('--build', type=Path, required=True, help='new or reusable build directory outside source')
    parser.add_argument('--jobs', type=int, default=min(8, os.cpu_count() or 1))
    args = parser.parse_args()
    build = args.build.resolve()
    build.mkdir(parents=True, exist_ok=True)
    prefix = build / 'prefix'
    sdk = subprocess.check_output(['xcrun', '--sdk', 'iphoneos', '--show-sdk-path'], text=True).strip()
    tool = lambda name: subprocess.check_output(['xcrun', '--sdk', 'iphoneos', '--find', name], text=True).strip()
    target = 'arm64-apple-ios17.0'
    flags = '-target ' + target + ' -isysroot ' + sdk
    configure = [str(args.source.resolve() / 'configure'), '--prefix=' + str(prefix),
                 '--target-os=darwin', '--arch=arm64', '--enable-cross-compile',
                 '--cc=' + tool('clang') + ' ' + flags, '--ar=' + tool('ar'),
                 '--ranlib=' + tool('ranlib'), '--strip=' + tool('strip'),
                 '--sysroot=' + sdk, '--extra-cflags=' + flags + ' -fPIC',
                 '--extra-ldflags=' + flags, '--pkg-config=/bin/false',
                 '--enable-static', '--disable-shared', '--disable-programs',
                 '--disable-doc', '--disable-debug', '--disable-autodetect',
                 '--disable-everything', '--enable-avformat', '--enable-avcodec',
                 '--enable-avutil', '--enable-network', '--enable-protocol=file,tcp,udp,rtp',
                 '--enable-demuxer=mov,rtsp,rtp,sdp,h264', '--enable-muxer=mp4',
                 '--enable-parser=h264', '--enable-decoder=h264',
                 '--enable-hwaccel=h264_videotoolbox', '--enable-videotoolbox']
    subprocess.run(configure, cwd=build, check=True)
    subprocess.run(['make', '-j' + str(args.jobs)], cwd=build, check=True)
    subprocess.run(['make', 'install'], cwd=build, check=True)
    bridge = root / 'source/application/apple/Native/FFmpeg'
    subprocess.run([tool('clang'), '-target', target, '-isysroot', sdk, '-std=c11', '-O2', '-fPIC',
                    '-I' + str(prefix / 'include'), '-I' + str(bridge), '-c',
                    str(bridge / 'R2CFFmpegMobile.c'), '-o', str(build / 'R2CFFmpegMobile.o')], check=True)
    output = build / 'libR2CFFmpegMobile.a'
    subprocess.run(['xcrun', 'libtool', '-static', '-o', str(output),
                    str(build / 'R2CFFmpegMobile.o')] +
                   [str(prefix / 'lib' / name) for name in ['libavformat.a', 'libavcodec.a', 'libavutil.a']], check=True)
    print('Replacement FFmpeg library:', output)


if __name__ == '__main__':
    main()
