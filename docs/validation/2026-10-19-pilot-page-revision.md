# Retained Pilot connection page — 2.5.3 (400)

The owner supplied the exact controller-side message:
“Pilot 2 has not confirmed download owner. Reconnect before starting another
mission.” That is the pre-build-398 text. The build-398/399 controller script adds
`(reported …)` to every failure from this readback check. This is evidence of an
older H5 page, possibly still loaded in Pilot's WebView or opened from another
older tablet endpoint. It does not establish an actual native owner getter value.

Both native servers already send `Cache-Control: no-store`, but those headers do
not replace JavaScript already running in a retained WebView. Clicking Connect
again invokes the script already loaded; it does not reload that document.

## Change

The current H5 client sends `X-R2C-Page-Revision: 2` on its API requests. Both
native servers require revision 2 for authenticated `/cloud/bootstrap` and
`/cloud/report`. A missing or incompatible revision returns HTTP 409 with a clear
instruction to close Open Platforms, reopen the tablet address and enter its
current code. The check happens before returning bootstrap credentials or
accepting a readiness report. Authorization still runs first. Other native DJI
mission/media protocol endpoints are unchanged.

The script URL includes its revision as a cache key. Existing no-store headers,
media readback validation, stale callback isolation and photo-return readiness
requirements are preserved. No aircraft/controller configuration is modified by
this change, and no speculative workaround accepts an unverified download owner.
The checks are constant-time header comparisons in existing network workers;
no UI-thread I/O is introduced.

## Verification

- Android mapping suite: 154 cases, no failures/errors, one optional skip.
  Real local HTTP tests check missing/old/future revisions on bootstrap and
  readiness reports, and ensure rejected reports do not mark photo return ready.
- Apple Pilot Cloud tests: five cases passed, including the same HTTP checks;
  preference persistence/stale-write test also passed.
- Shared H5 test checks its revision header, handling a server revision rejection
  before native modules load, existing connection recovery and all media checks.
- Android debug build and Apple development device compile/sign pass. Notes,
  metadata and copy checks pass. Both platforms are 2.5.3/build 400.

## Owner retest

Close the Pilot 2 Open Platforms page completely. On the iPad, reopen the mission
connection; use the address and access code currently shown there. Reopen that
address in Pilot 2 rather than pressing Connect inside the old page. A remaining
owner failure from the current page will contain its safe `(reported …)` value.
Physical owner-readback qualification remains pending that fresh-page test.

Build 400 was installed in place on the iPad; independent device query confirmed
2.5.3/build 400. Both packages contained identical revised controller assets and
canonical notes. No app data was cleared or operational session started.
