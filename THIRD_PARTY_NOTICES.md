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

RID2Caltopo supports optional drop-in FFmpeg shared libraries. FFmpeg is
generally licensed under LGPL-2.1-or-later, but builds that enable GPL
components are governed by the applicable GPL, and builds using nonfree
components may not be redistributable. The exact license depends on the
configuration and external libraries used to produce the included binaries.

Before distributing a build that includes FFmpeg, verify its configure flags
and linked libraries and satisfy the corresponding source, relinking,
attribution, notice, and other distribution requirements. The FFmpeg binaries
and headers are not covered by RID2Caltopo's Apache-2.0 grant.

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
  [License](apple/Sources/CAOL/LAZPERF-LICENSE).
- GeographicLib: https://github.com/geographiclib/geographiclib, MIT License.
  [License](apple/Sources/CAOL/GEOGRAPHICLIB-LICENSE).
- Pinned source provenance and build configuration:
  [CAOL README](apple/Sources/CAOL/README.md).
