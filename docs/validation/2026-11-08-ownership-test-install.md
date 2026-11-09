# Ownership test install — November 8, 2026

Both updates were installed in place as version 2.5.8 (437). App data was not cleared and the apps were not launched for device testing.

| Device | Install evidence | Artifact SHA-256 |
| --- | --- | --- |
| A5 Pro, R52Y90C9XST | Android package manager reports last update at 2026-11-08 12:17:33 PST and version 2.5.8 (437). | APK: `156b1d06e4181332c7f2bfb80e5476cc95f4805d4bb85c70c0b9b5ed22cd9d7c` |
| Ken's iPad, 00008103-0010596E1AD3001E | Installation succeeded; device inventory verified version 2.5.8 (437) by 2026-11-08 12:18:11 PST. The device inventory did not expose an exact installation time. | Signed app bundle ZIP: `6ba93cb505ff7eb05ba7a873bcf4496d7caa98815efbee5f851a053f6ad8c43f` |

The Android APK was signed with the same certificate as the existing installation. The signed iPad app passed deep, strict signature verification. Its executable SHA-256 is `cc8f580dc47c6ee1174e1cb87e82970a79c9f1826404c8f95191a038f896d758`; its code-directory hash is `efea996abe3b4c185932634be9028433ddcf5f61`.

These are local test builds. The tracker changes have not been deployed, and the CalTopo hold and review action for an unverified ownership claim remain incomplete in this 2.5.8 (437) build.

The follow-up build is recorded in `docs/validation/2026-11-08-ownership-review-recovery.md`.
