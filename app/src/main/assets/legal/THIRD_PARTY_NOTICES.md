# Third-Party Software Notices

RID2Caltopo's original source code and documentation are licensed under the
[Apache License 2.0](LICENSE). Third-party software is not relicensed by the
RID2Caltopo license and remains subject to its respective copyright and license
terms.

This document summarizes important third-party components used by or supported
by RID2Caltopo. It does not replace the license text and notices supplied with
those components. A distributor is responsible for reviewing the exact source,
dependencies, build configuration, and binaries included in its distribution.

## OpenDroneID receiver-android

- Project: <https://github.com/opendroneid/receiver-android>
- License: Apache License 2.0
- Copyright: Intel Corporation, Skydio, and other contributors as identified
  in the retained source headers

RID2Caltopo incorporates and modifies portions of the Remote ID scanning,
parsing, and data-model code. The original copyright and SPDX notices are
retained in those files.

## MediaMTX

- Project: <https://github.com/bluenviron/mediamtx>
- License: MIT License
- Copyright: Copyright (c) 2019 aler9
- License text: [MediaMTX-MIT.txt](third_party/licenses/MediaMTX-MIT.txt)

Some application distributions include a modified MediaMTX executable for
local video relay. MediaMTX and modifications derived from it remain subject to
the MediaMTX license; they are not covered by RID2Caltopo's Apache-2.0 grant.

## FFmpeg

- Project: <https://ffmpeg.org/>
- License information: <https://ffmpeg.org/legal.html>
- LGPL-2.1 text: [FFmpeg-LGPL-2.1.txt](third_party/licenses/FFmpeg-LGPL-2.1.txt)

The Apple runtime statically links FFmpeg; the Android runtime uses FFmpeg shared libraries. The current source build pins n7.0 at revision `083443d67cb159ce469e5d902346b8d0c2cd1c93`. FFmpeg is
generally licensed under LGPL-2.1-or-later, but builds that enable GPL
components are governed by the applicable GPL, and builds using nonfree
components may not be redistributable. The exact license depends on the
configuration and external libraries used to produce the included binaries.

Apple device Release builds include a written source/relinking offer under
LGPL 2.1 section 6(c). License & Notices links to the matching public GitHub
release containing `RID2Caltopo-relink.zip`. That ZIP provides the library
source, application objects, non-system link inputs, Eigen source, build/link
scripts and notices. Downloads require no account and are free of charge.

The offer remains valid for at least three years after our last distribution
of the corresponding build. If the download is unavailable, contact
kjt@uas4sar.com for a free copy. The full offer is in
`third_party/licenses/SOURCE_AND_RELINKING_OFFER.txt`. Original developer signing
credentials are not required to produce an unsigned replacement executable.

The FFmpeg binaries and headers retain their own license; RID2Caltopo's
Apache-2.0 grant does not relicense them. Android uses shared libraries and has
its own platform build/distribution requirements.

## Android and Java dependencies

The Gradle build resolves libraries from AndroidX, Google and Firebase,
Square, Eclipse Paho, Bouncy Castle, osmdroid, NGA, ZXing, and other transitive
dependencies. These libraries retain their own licenses and notices. The
authoritative dependency set for a particular build is the resolved Gradle
dependency graph, not this human-maintained summary.

Notable source projects include:

- AndroidX: <https://github.com/androidx/androidx>
- AppAuth for Android: <https://github.com/openid/AppAuth-Android>
- OkHttp: <https://github.com/square/okhttp>
- Eclipse Paho MQTT Java client: <https://github.com/eclipse-paho/paho.mqtt.java>
- Bouncy Castle: <https://www.bouncycastle.org/>
- osmdroid: <https://github.com/osmdroid/osmdroid>
- ZXing: <https://github.com/zxing/zxing>

## LiteRT and EfficientDet Lite0

- LiteRT project: <https://github.com/google-ai-edge/LiteRT>
- LiteRT version: 2.1.5
- Model project: <https://huggingface.co/litert-community/efficientdet>
- Model revision: `971d935f3679eabbcce7b4d3733f351d403ff2b9`
- Model SHA-256: `33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704`
- License: Apache License 2.0
- License text: [Apache License 2.0](LICENSE)

RID2Caltopo includes LiteRT for CPU-based on-device inference and an
EfficientDet Lite0 object-detection model trained on COCO. These components
retain their own license and provenance. The checked-in model identity manifest
records the exact source revision, tensor contract, class mapping, and digest.

Test-only tools and dependencies are also governed by their respective
licenses even when they are not included in the application package.

## Trademarks and services

Product, organization, and service names are the property of their respective
owners. Use of a name or interoperability with a service does not imply
affiliation or endorsement.

## laz-perf and GeographicLib

The shared Android/Apple AOL preparer includes laz-perf 3.4.0 for streaming LAZ
decoding and selected GeographicLib 2.7 components for coordinate conversion.
Upstream copyright notices are retained in the vendored source.

- laz-perf: https://github.com/hobuinc/laz-perf, Apache License 2.0.
  [License](third_party/licenses/LAZPERF-LICENSE).
- GeographicLib: https://github.com/geographiclib/geographiclib, MIT License.
  [License](third_party/licenses/GEOGRAPHICLIB-LICENSE).
- Pinned source provenance and build configuration:
  [CAOL provenance](third_party/licenses/CAOL-README.txt).


## WebRTC native framework

The Apple target builds the M125 source branch at revision
`db3ba7af86dd6d48174ed35387d11c0794f1a21b` from
https://github.com/webrtc-sdk/webrtc. The local XCFramework includes device and
Simulator slices. The pinned recipe and build-tool-only compatibility patch
are under `apple/Native/WebRTC` and `third_party/webrtc`.

- [WebRTC BSD license](third_party/licenses/WebRTC-BSD.txt).
- [Full GN-generated component notices](third_party/licenses/WebRTC-THIRD-PARTY-LICENSES.txt).
- Individual copyright, license, patent and author notices are retained under
  `third_party/licenses/WebRTC-dependencies` for all 23 components identified by
  the successful upstream license generator.
- `WebRTC-build-provenance.json` records source identity, slice hashes and notice
  inputs. The previous Specs wrapper's MIT notice is retained conservatively;
  the current Apple target does not depend on that prebuilt package.

## MediaMTX dependencies and Go runtime

The native MediaMTX build uses Go 1.25.6. Its vendored module versions and retained
license, copyright and patent notices are included under
`third_party/licenses/MediaMTX-dependencies`. This conservative collection may
include notices for upstream code that the final linker does not retain.
The [Go runtime license](third_party/licenses/Go-BSD.txt) applies separately.
The reviewed ios/arm64 package graph contains 559 packages (225 standard-library
packages). Copyright and license comment blocks from all 3,455 selected source
files are retained in `Go-component-copyright-notices.txt`, with Go patent
notices and source hashes. This preserves per-file attributions as well as the
module-level license texts.
The embedded HLS web player uses hls.js v1.6.15; its [notice](third_party/licenses/HLS.js-LICENSE.txt) retains Dailymotion and Brightcove attribution under Apache 2.0.
The actual player was matched to the SHA-verified release. All 89 bundled npm
source-map files match eventemitter3 5.0.1, url-toolkit 2.2.5 and SVTA Common
Media Library 0.17.1. Their license and inherited notices are under
`third_party/licenses/HLS-dependencies`. The release executable's 60 linked Go
module versions and retained notice paths are recorded in
`MediaMTX-dependencies/linked-modules.json`, including the separate BSD keywrap
copyright header.

## Local mapping dependencies

The native mapping build selects OpenCV 4.12.0 core, imgproc, features2d,
calib3d and flann, plus Ceres Solver and Eigen from OpenMVG revision
`01193a245ee3c36458e650b1cf4402caad8983ef`.

- [OpenCV license](third_party/licenses/OpenCV/LICENSE) and retained module
  copyright banners cover OpenCV and separately attributed portions such as FLANN.
- The build's dependency archives include ADE, libjpeg-turbo, libpng, libwebp and
  zlib; their retained texts are under `third_party/licenses/OpenCV`.
- [Ceres Solver BSD license](third_party/licenses/CERES-LICENSE.txt).
- [Eigen license overview](third_party/licenses/Eigen/COPYING.README) and
  [MPL 2.0](third_party/licenses/Eigen/COPYING.MPL2). The source tree's additional
  COPYING files are retained because some optional Eigen code has other licenses.
  The matching source is included in the linked Apple relink ZIP, together
  with the relevant COPYING texts. Upstream source:
  https://github.com/openMVG/openMVG/tree/01193a245ee3c36458e650b1cf4402caad8983ef/src/third_party/eigen.

This software is based in part on the work of the Independent JPEG Group.

## Rights and distribution materials

The Apache license for original RID2Caltopo source does not replace third-party
licenses. Their rights to modification and reverse engineering for debugging modifications
remain governed by those licenses. Apple releases retain the written offer,
full license texts and a small build-matched download reference. The large ZIP
is hosted on GitHub rather than included in the installed app. Release
verification checks the public asset checksums before distribution. Existing
material sets are reused when the corresponding source, compiled objects,
notices and build/link inputs are unchanged.

The in-app License & Notices view contains the bundled full texts. The generated
legal manifest verifies that those resources match the reviewed inputs. It does
not certify fulfillment of source distribution, relinking or other obligations.
