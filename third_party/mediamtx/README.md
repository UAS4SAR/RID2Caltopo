# RID2Caltopo MediaMTX source patches

RID2Caltopo does not store generated MediaMTX or FFmpeg binaries in Git. The
Android and Apple builds use the official MediaMTX `v1.16.2` source plus the
patches in this directory. Exact upstream commits and tool versions are pinned
in `manifest.env`.

`0001-rid2caltopo-mediamtx-v1.16.2.patch` contains the RID2Caltopo RTMP,
low-latency, diagnostics, and configuration changes. The upstream MediaMTX
module depends on `github.com/wlynxg/anet`; `0002-anet-v0.0.5-android.patch`
removes two Android link-name declarations that are incompatible with the
pinned Go toolchain. `0003-gortmplib-v0.3.0-rid2caltopo.patch` contains the
RTMP parsing, video-only publishing, and connection-diagnostics changes used
by the MediaMTX patch. The preparation script recreates the vendor hierarchy
used by the known-good `mediamtx-1.16.2h` build before applying dependency
patches.
`0004-rid2caltopo-mediamtx-tests-tools.patch` preserves the focused idle-ping
tests and RTMP capture-analysis utility developed alongside those changes.
`0005-rid2caltopo-nonempty-rtsp-session-name.patch` restores the known-good
gortsplib behavior that serializes an empty publisher title as `s=MediaMTX`,
which Android Media3 can parse.
`0006-rid2caltopo-regression-fixes.patch` restores empty-description stream
compatibility and corrects the RID2Caltopo configuration and audio-replacement
regression tests uncovered during the source-reproducibility audit.
`0007-record-file-complete-event.patch` emits a structured recorder lifecycle
line only after the completed segment has been closed successfully. Android and
Apple use that event to publish the finished recording without a timing guess.

Prepare a fresh patched checkout:

`0009-rtmp-publish-timing.patch` adds bounded publisher timing diagnostics shared
by Android and Apple source builds. `RTMP timing` summarizes read calls/media
units and maximum read, pre-callback, post-callback, and inter-media times every
60 seconds, or at most once per five seconds when a read/media gap exceeds 500 ms
or forwarding exceeds 100 ms. A final line is emitted on read failure.
`beforeMediaMs` includes socket waiting and RTMP parsing; `afterMediaMs` measures
synchronous relay forwarding, not asynchronous RTSP socket writes. Compare
`wallMs` with the existing FFmpeg `av_read_frame wait` and `video packet gap`
lines. No payloads, credentials, or per-frame log lines are added.

`0010-socket-read-and-rtsp-queue-diagnostics.patch` separates time inside the
underlying RTMP socket `Read` from other work within the media-reader call.
The existing timing summary includes socket calls/bytes and maximum socket and
non-socket time. Socket timing includes OS scheduling and is not a TCP packet-loss
measurement. RTSP's existing once-per-second discarded-packet warning now
explicitly identifies `RTSP output queue full`; the session prefix identifies
the affected reader. These changes do not alter queue capacity or packet handling.

```sh
tools/prepare_mediamtx_source.sh
```

Build and install the Android arm64 executable:

```sh
tools/build_mediamtx_android_arm64.sh
```

The default build intentionally matches the known-good untrimmed build command.
Set `MEDIAMTX_TRIMPATH=1` only when explicitly evaluating a trimmed build.
The manifest records both the byte-identical historical untrimmed binary hash
and the path-independent corrected trimpath binary hash. Trimpath builds are
rejected when their SHA-256 differs from the pinned reference.

The build runs MediaMTX's standard generators. Those generators download
checksum-verified HLS and Raspberry Pi camera release assets into the ignored
working tree; neither those generated assets nor the compiled MediaMTX
executable is committed.

Apple's MediaMTX build scripts invoke the same preparation script unless
`MEDIAMTX_SOURCE_DIR` explicitly selects another verified checkout. Generated
source trees and binaries remain beneath ignored build/output paths.

MediaMTX and `gortmplib` retain their MIT licenses. `anet` retains its
BSD-3-Clause license.
