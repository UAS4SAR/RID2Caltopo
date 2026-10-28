RID2Caltopo FFmpeg replacement and app relinking materials

This ZIP supplies the source/relink materials for the app identified by manifest.json. It contains that
build's app object files, its non-system link inputs, the matching FFmpeg source
and bridge, Eigen source, complete bundled notices and two portable build/link
scripts. It is supplied through the LGPL 2.1 section 6(c) written offer included in the
app. License & Notices links to this build-matched public GitHub release.

Extract with the macOS unzip utility, which preserves source script permissions:

    unzip RID2Caltopo-relink.zip -d recipient-materials
    cd recipient-materials

Requirements: an Apple Silicon Mac, Python 3, make and a compatible Xcode iOS SDK.
The preparation was tested with Xcode's iOS 27.1 SDK and an iOS 17 minimum target.
Apple tools, SDKs and signing credentials are not redistributed.

1. Produce an unsigned executable using the original supplied FFmpeg library:

    python3 relink_apple.py --output ../RID2CaltopoApple-original

2. Edit the FFmpeg source under source/ffmpeg. Rebuild the library and native
   bridge using the same enabled features and a fresh build directory:

    python3 rebuild_ffmpeg.py --build ../ffmpeg-modified-build

3. Relink with the replacement library:

    python3 relink_apple.py --ffmpeg ../ffmpeg-modified-build/libR2CFFmpegMobile.a \
        --output ../RID2CaltopoApple-modified

No application checkout or original developer credential is required for these
steps. The relinker checks the supplied objects and other inputs but permits
a replacement FFmpeg archive. Preserve the public bridge ABI when modifying it.
Use --allow-modified-inputs when intentionally modifying other app/link inputs.
The recorded original build configurations remain under build-evidence/ffmpeg.

To install a modified executable, copy your app bundle, replace the executable
named in Info.plist, and sign the embedded WebRTC framework and app with your
own compatible signing identity, entitlements and provisioning profile. If your
identity cannot provision the original bundle ID, select your own app ID and
update the bundle identifier and relevant entitlements. Follow Apple's current
signing/provisioning instructions. This process does not grant access to another
operator's private service accounts or original signing keys.

The LGPL, Apache, BSD, MIT, MPL and other relevant notices are in LICENSES.txt.
RELINKING_PERMISSIONS.txt preserves the application's modification/debugging
permissions. The manifest's app/framework UUIDs identify the matching build;
its hashes protect the supplied files. A modified executable will have a new
UUID and is intentionally different from the original release.
