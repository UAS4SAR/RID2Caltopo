# PR #7 integration — October 29, 2026

Integrated source from [PR #7](https://github.com/UAS4SAR/RID2Caltopo/pull/7),
commit `07aefc1581768a3303939bdfbef0182ddcb2ac89`, into the existing local changes.
The previous source base was `0fef257a56fc0d6a4ec906188d552cb8ebd0b57e`.

## Preserved local work

The source merge retains the track-label/recording-link fixes, required fresh
terrain preparation for clue saves, worker-prepared map history and geometry,
and prepared osmdroid startup configuration. Apple retains its expired Teams
credential safeguard. A SHA-256 inventory of 858 pre-existing modified/untracked
files verified that only the four expected overlapping source/project files
changed; no previous file disappeared. Nothing was staged, committed, or pushed.

## Integration repairs

- Registered the new Apple credential Keychain/browser source in the app target.
- Fixed Swift exclusive access to the loopback request buffer and supplied the
  catalog request timestamp required by the existing client API.
- Prepared Android account catalogs and persisted login-mode changes on workers;
  only prepared catalog/state results return to the UI.
- Kept Apple Keychain access on workers. UI configuration reads use a bounded
  in-memory snapshot and never wait for the lock that serializes storage access.
  Storage revisions reject obsolete loads; a regression test holds a storage
  read while UI status remains available.
- Apple reuses an existing account credential on Sign in and reads its Keychain
  store only in Credentials mode.
- Blocked conflicting login-mode changes during account work and rejected stale
  summaries/catalog results. Credentials stay separate from Teams and cookies.

## Validation

- Android complete unit suite: 1,811 cases, zero failures/errors, one existing
  skip (1,810 passed), including all 11 new personal-account tests.
- Android debug APK assembly passed.
- Apple CalTopo regression selection: 60 XCTest cases and 16 Swift Testing cases
  passed, including the new delayed-storage regression. An initial sandbox run
  could not compile a WebKit rule list; the normal service-enabled run passed.
- Unsigned iOS Debug app build passed, including the new app source.
- Xcode project plist validation and `git diff --check` passed.

Debug APK SHA-256:
`99e8214134cc06351982033ceb8595f5fdd626186bdf3ffb989f4cac9666b166`.
Artifacts and build/test logs are in `build/releases/2.5.5-432/pr7-integration`.
Version remains 2.5.5 (432). No installation, device launch, physical test,
credential enrollment, or live CalTopo publication was performed for this integration.

## Operator verification

In CalTopo settings, select **Personal Login Support > Credentials** and choose
**Sign in**. Confirm the system-browser activation, then select a personal map.
An existing cookie login keeps Session Cookies until explicitly changed.
Actual account activation and map publication still require operator verification.

## Git history

At completion of the integration step, the owner's request was to leave Git
history/index unchanged at `0fef257a56fc0d6a4ec906188d552cb8ebd0b57e`; PR #7
was integrated as working-source changes. Automatic permission review had timed out before
the metadata update started; no update was performed. No remote PR merge
was requested.

## Installation follow-up

Both tablets were subsequently updated in place at the owner's request.
See [release notes and installation record](2026-10-29-pr7-device-update.md).

## Source release follow-up

The owner subsequently requested committing the combined source, tagging it
`v2.5.5`, and pushing to main. See [release source record](2026-10-29-release-source-v2.5.5.md).
