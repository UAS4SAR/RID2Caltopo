BTRX bearing layer: the continuous map-layer model of docs/btrx/BEARING-LAYER-spec.md
(r3, section 6), shared by Android JNI (app/src/main/cpp/btrx_bearing_jni.c, built
as libbtrx_bearing.so) and Swift Package Manager (target CBtrxBearing, used by R2CCore).

Rules (spec 6.1): C11, no compiler extensions, no global mutable state; every
allocation happens in btrx_bearing_create (none after); all time is int64 host
microseconds supplied by the caller; the library never reads a clock except in the
optional own worker (C11 timespec_get). Headers used: <stdint.h>, <stdbool.h>,
<stddef.h>, <string.h>, <stdlib.h>, <math.h>, <pthread.h>, plus <stdio.h> for
vsnprintf only (BBLAG/BBRND log lines) and <time.h> for timespec_get only.
Floating-point contraction is off in every file (#pragma STDC FP_CONTRACT OFF in
bb_internal.h; Android also passes -ffp-contract=off) so Android, iPad and the host
tests produce the same numbers.

No addresses leave the library: sources are named by a SipHash-2-4 key under the
caller's incident key and a 6-character short ID.

Host tests and replay: tools/btrx_bearing_test (run by apple/release-check.sh).
Nothing here goes to CalTopo; the layer is a Developer Tools feature.
