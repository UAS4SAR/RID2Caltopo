# Known untracked local files

Inventory recorded on October 8, 2026, after updating to `v2.5.0rc1`
(`10d48b9`). The migration archive was deleted with the owner's authorization.
The other four files were restored with verified SHA-256 checksums and added
for tracking with the note **required for release process**. The temporary copy
and build logs remain at `/private/tmp/r2c-untracked-20261008-tiui8a4z`.

The entries below record the disposition of files that were previously
untracked; they are not a list of currently untracked files.

| Repository relative path | Approximate size | Purpose or context |
| --- | ---: | --- |
| `r2c-native-artifacts.tgz` | 55 MiB | Deleted. Owner identified it as the MBA migration archive. |
| `test-fixtures/aol/partial-set/index.json` | 1.2 KiB | Index in a local AOL fixture directory named `partial-set`. |
| `test-fixtures/aol/partial-set/tile-0-1000.aol` | 1.0 KiB | AOL tile in the same fixture directory. |
| `test-fixtures/video-track/ipad-20260912-1617.json` | 14 KiB | Video track JSON named for an iPad capture; whether it contains original location or time data has not been verified. |
| `tools/anomaly_test/reviews/Red2.review.json` | 2.6 KiB | Red2 review annotations referenced by `tools/anomaly_test/README.md` for visible-color qualification. |

This inventory excludes ignored files, including generated builds, credentials,
and real-world capture files covered by `.gitignore`. The four restored files are retained as required for release process. The AOL
and Red2 dependencies were demonstrated by the checks below; the iPad trace is
retained at the owner's request alongside those fixtures.

## Checks with files absent

- Android release gate and bundle build failed in `colorRealtimeQualification`
  because `tools/anomaly_test/reviews/Red2.review.json` was missing. Crashlytics
  uploads were excluded from this local build attempt.
- Apple focused surface preparation tests ran eight tests: seven passed and
  `preparedAOLPartialTransferDoesNotClaimCompleteCoverage` failed because
  `test-fixtures/aol/partial-set/index.json` was missing.
- Android focused `SurfacePreparedSetTest` ran four tests: three passed and
  `partialTransferDoesNotClaimCompletePreparedCoverage` failed because the same
  partial-set index was missing.
- The Apple release gate verified existing native frameworks without rebuilding
  them. Its first attempt stopped because `cmake` was absent from PATH; a retry
  with SDK CMake passed all 5,209 portable anomaly checks, then failed in the
  shared `colorRealtimeQualification` because `Red2.review.json` was missing.
  It stopped before producing a fresh device archive or signed IPA.

The AOL partial-set pair and Red2 review annotations are dependencies of the
current tests and release gates. The iPad track JSON is referenced by
`docs/iPad_Track_Return_Segment_2026-09-12.md`; these blocked builds do not
establish whether it can be permanently removed.

Logs are retained in the holding directory. These failures establish that the
current release process depends on some of the held files; successful release
images without them have not been established.

To refresh the list from the repository root:

```sh
git ls-files --others --exclude-standard
```

The four restored files and this inventory are included in the repository addition.
