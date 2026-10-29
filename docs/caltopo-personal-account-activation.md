# Personal CalTopo account activation

RID2Caltopo needs a per-pilot CalTopo credential so a responder can use a restricted Workspace they were invited to. A team service account cannot. The current personal login borrows session cookies from an embedded browser. CalTopo tolerates that and may turn it off. On 28 October 2026 Matt said the Desktop account-activation flow is the right fit for now, with UAS4SAR owning investigation, maintenance, and support, and with no new CalTopo API, docs, or support.

This note records what was checked against the live service on 29 October 2026, how the prototype uses that flow, and what still has to be tested with a real CalTopo account. The cookie login is still the default and is not removed.

## What the public record says

[elliottshane/sme-sartopo-mapsrv](https://github.com/elliottshane/sme-sartopo-mapsrv) and the [caltopo_python credentials page](https://caltopo-python.readthedocs.io/en/latest/credentials.html) describe the same Desktop ceremony:

1. Sign in to CalTopo in a browser.
2. Open `https://caltopo.com/app/activate/offline?redirect=localhost` (older writeups use `sartopo.com`).
3. Enter a device name, confirm the address, and choose Sync Account.
4. The browser is sent to a local URL whose query contains `code` (an activation code, described as 8 characters) and `name`.
5. `GET https://caltopo.com/api/v1/activate?code=<code>` returns JSON shaped like:

```json
{
  "code": "credential id, described as 12 characters",
  "key": "base64 HMAC secret, described as 44 characters",
  "account": {
    "id": "6-character account id",
    "properties": { "class": "UserAccount", "title": "", "email": "" }
  }
}
```

The `code` field in that JSON is the credential id used as the `id` query parameter. The `key` is the HMAC secret and is not sent again. [CalTopo Training: Supported API for Teams](https://training.caltopo.com/all_users/team-accounts/teamapi) describes the same id-plus-secret model for every account kind, including individual accounts, which may have one credential per device. [CalTopo Desktop](https://training.caltopo.com/all_users/desktop) tells the operator to open `http://localhost:8080`, sign in with the same provider as the subscription, and sync the account. That sync does not upload maps.

Signed requests already used by this app are `HMAC-SHA256` over `METHOD PATH\nEXPIRES\nPAYLOAD`, with the key base64-decoded and the MAC base64-encoded. `expires` is milliseconds, two minutes ahead. The query or form fields are `id`, `expires`, and `signature`.

## What was confirmed on the live service

Probes below were unauthenticated. No CalTopo account, password, or activation code was used. No code was guessed.

| Request | Result |
| --- | --- |
| `GET /app/activate/offline` with no `redirect` | HTTP 400, body `Desktop activation requires desktop local url` |
| `GET /app/activate/offline?redirect=<non-empty>` | HTTP 302 to `/account/login` when logged out. Checked values included `localhost`, `http://127.0.0.1:<port>/<path>`, `http://[::1]:<port>/finish`, `rid2caltopo://activate`, `https://caltopo.com/app/activate/done`, a private-network URL, and `javascript:alert(1)` |
| Same path with `local`, `url`, `return`, `redirect_uri`, or `desktop` instead of `redirect` | HTTP 400, same "desktop local url" error |
| `GET /app/activate/localhost/client/finish-activate?code=TESTCODE&name=rid2caltopo` | HTTP 404. That path is not an entry point |
| `GET /api/v1/activate` and `GET /api/v1/activate?code=<synthetic>` | HTTP 500 `{"code":500,"message":"unknown server error","status":"error"}` for an empty code and for several short and 8-character synthetic codes. A session cookie from the activation redirect did not change the status. POST and PUT did the same |
| `sartopo.com` `/app/activate/offline?redirect=localhost` | HTTP 302 to `/account/login` |
| `sartopo.com` `/api/v1/activate?code=TESTCODE` | HTTP 500, same error shape |
| `GET /account/login` | HTTP 200. The page bootstrap lists providers `google`, `apple`, `facebook`, `msft`, and `yahoo`, and `featureFlags` includes `firstPartyLogin` |
| `main.js` fetched 29 October 2026 (Last-Modified 27 October 2026, 10,473,695 bytes) | No `finish-activate`, `/app/activate`, or `/api/v1/activate` string. The activation page is not in the map bundle; the server handles it before the SPA |

The pre-login check is "redirect is present and non-empty." It is not an allowlist. A logged-out session does not echo the redirect on `/account/login`, so the original URL is either stored in the `SESSION` cookie or dropped until after sign-in. Which of those is true was not visible without completing login.

## Mobile return path

A phone can use this flow without a CalTopo server change only if, after the operator signs in and chooses Sync Account, CalTopo sends the browser to the `redirect` value (or that value plus a query CalTopo appends) and that URL can reach a listener on the phone.

The prototype does that the way Desktop does:

- Bind `127.0.0.1` on an ephemeral port before opening the browser.
- Put an unguessable path and `state` in the redirect: `http://127.0.0.1:<port>/r2c/<token>?state=<state>`.
- Open `https://caltopo.com/app/activate/offline?redirect=<that url>&name=<device name>` in the system browser.
- Accept a callback only when the path and `state` match. Anything else, including a code that arrives on a different path, is rejected.
- Exchange the code once with `GET https://<host>/api/v1/activate?code=<code>`. Redirects are not followed, so the code is not sent to another host.
- Store the signing key in the iOS Keychain or the Android Keystore. Teams credentials are not read or written.

`name` on the activation URL is a hint. The Desktop instructions say the name is typed on the Sync Account page. Whether CalTopo pre-fills it from the query is unconfirmed. The callback still has to carry our `state`.

This is safe if CalTopo preserves the redirect URL and appends `code`. It fails closed if CalTopo rewrites the return to something like `http://127.0.0.1:8080/client/finish-activate?code&name` and drops `state`. That rewrite is what the older browser-devtools writeups describe for a Desktop listener, and it is the main live-test question. Do not accept a code from an unmatched request in order to make that rewrite work. A local app that can connect to our port during the enrollment window must still present the unguessable path and state.

Custom schemes such as `rid2caltopo://activate` passed the pre-login non-empty check and failed nothing else, because that check does not look at the value. That is not evidence CalTopo will redirect to a custom scheme after login. `ASWebAuthenticationSession` and Chrome Custom Tabs are the right return path if a logged-in test shows a custom scheme or an HTTPS app link is preserved. They are a poor fit for `http://127.0.0.1`, which is why this prototype opens the system browser and listens on loopback. Google sign-in stays in that browser. It does not run in the embedded web view that holds the cookie experiment. The embedded-webview Google block stays as it is.

The Android session hook only signs personal requests for `caltopo.com`, because those requests are already fixed to that host. The module will also store a `sartopo.com` binding, and the account-catalog helper uses the stored host.

## Credential lifetime, revocation, reconnect

Nothing in the public writeups or the unauthenticated probes gives the signing key an expiry. The account object carries subscription fields. The credential itself is described as a device entry under Your Account, and the secret is shown once at creation. Treat it as long-lived until the operator deletes that credential in CalTopo, or until a live test shows otherwise.

No revoke call was found. `DELETE` was not attempted against a real credential. The prototype can mark its local copy revoked and then refuses to sign, without falling back to cookies for that same account. Reconnect of a still-valid local copy does not call CalTopo. A revoked or missing local copy has to enroll again, which creates another credential on the account. The previous one remains in CalTopo until someone deletes it there. Confirm that in the account UI during the live test.

The signing key is not written into Teams fields, configuration backup, or logs. Android stores ciphertext in `noBackupFilesDir` under a Keystore key that requires the device to have been unlocked. iOS uses `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` on service `org.ncssar.RID2CaltopoApple.caltopo-personal-account`, which is not the Teams service. Both survive an ordinary app update and are removed with the app's private data. A full in-app Keychain wipe that deletes every generic password also deletes this item, which matches the existing reset.

The local record is bound to host plus account id. A credential enrolled for one account is not used for another account's personal maps, and it is not used for Teams publishing.

## How the prototype is wired

The flag defaults off.

- Android preference `caltopo-personal-account` / `feature_enabled`, read on the startup worker into `CaltopoPersonalAccountConnection.featureEnabled`.
- Apple `UserDefaults` key `caltopo.personalAccountActivation`, read when CalTopo settings load.

While the flag is off, personal requests keep using session cookies and the new store is not read. Teams requests keep using the service-account credential. Map, track, clue, and photo calls still go through `CaltopoSession` / `CaltopoLiveClient`, including the existing full-map read spacing.

While the flag is on and a non-revoked credential is stored for the selected personal account:

- Android `CaltopoSession` signs that personal request with the account credential and does not attach the cookie. A revoked credential fails that personal request and does not send the cookie. A different account, or no stored credential, still uses cookies.
- Apple builds a `CaltopoLiveConfiguration` with `personalSignedAccount` set and `personalSessionID` cleared, so the existing signer runs and the personal media-owner rules still apply. Teams configuration is returned unchanged when no personal session is selected.

There is no settings control in this draft. Turning the flag on does nothing visible until a credential has been enrolled through `enroll(...)`. Cookie sign-in stays on the existing button.

Enrollment, the code exchange, and Keystore reads run off the main thread. The Android listener and exchange are called from `enroll`, which blocks and must be started on a worker. The Apple `enroll` hops off the main actor before it listens. After the first successful read, later signing uses an in-memory copy the way the Teams secret already does.

## Open questions that need a real account

1. After login and Sync Account, what is the exact browser URL? Does it preserve `http://127.0.0.1:<port>/r2c/<token>?state=<state>` and append `code`, or does it replace the path and drop `state`?
2. Is `GET /api/v1/activate?code=` successful without the browser's session cookie? The public `urllib` examples omit cookies. Our probes only saw HTTP 500 for synthetic codes, with or without an anonymous session.
3. What is the real JSON for a 200 response? Field names, credential-id length, key length, whether it is wrapped in `result`, and whether `properties.class` is `UserAccount`. The parser accepts the documented shape and a `result` wrapper, and it rejects a non-user account. Lengths outside 8–32 for the credential id or 16–64 decoded key bytes are rejected until a live sample says otherwise. The activation code itself is accepted at 4–64 URL-safe characters because the "8 characters" figure is only from the writeups.
4. Is the code single-use, and how quickly does it expire? The local pending record expires after five minutes.
5. Does the signing key expire, and does deleting it under Your Account make the next signed call fail? Which HTTP status is that? Do not guess from the activation endpoint's HTTP 500.
6. Does a signed `GET /api/v1/acct/<account id>/since/0` return the same catalog the cookie login normalizes, including a restricted Workspace the pilot was invited to and not a Workspace they were not?
7. Can that credential read a restricted map and publish a clue and a photo through the existing client, with the same full-map read spacing?
8. Does the Keychain or Keystore item survive an in-place app update, and is it gone after the credential is deleted in CalTopo and the app is told to disconnect?
9. Does CalTopo pre-fill the device name from the `name` query? If the extra parameter changes the activation page, drop it. The required parameter is `redirect`.
10. On a phone, can Safari and Chrome complete the redirect to `127.0.0.1`? iOS local-network and cleartext prompts are untested. Android cleartext policy does not apply to Chrome connecting to the listener; the app's own exchange is HTTPS.

## Live test plan

Use a disposable personal CalTopo account and a disposable map. Do not use a production incident map. Leave the cookie login in place so a failure of this prototype does not remove the current path.

Enable the flag only on the test device:

- Android, after install, set `feature_enabled` in the `caltopo-personal-account` shared preferences, or set `CaltopoPersonalAccountConnection.featureEnabled` before enrollment. The preference is read on the next process start.
- Apple, set the UserDefaults bool `caltopo.personalAccountActivation`.

Then:

1. **Enrollment.** From a worker, call `enroll` with the system-browser opener (`CaltopoPersonalAccountBrowser.systemBrowserIntent` on Android, `AppleCaltopoPersonalAccountBrowser` on Apple). Sign in with Google in that browser, not in the embedded web view. Record the final browser URL and whether `state` survived. Confirm the local status shows the account id and credential id and that the Teams credential fields are unchanged.
2. **Catalog.** `fetchAccountCatalog` (Android) or `CaltopoTeamMapClient` fed by `teamMapConfiguration()` (Apple). Pass the body through the existing personal catalog normalizer. Confirm the invited Workspace appears and an unrelated Workspace does not. Confirm the request was signed and carried no `Cookie` header.
3. **Map read.** Open a restricted map with the signed configuration. Confirm HTTP 200 and that anonymous access to the same map is denied. Confirm a second immediate full read is still held by the existing 90-second gate.
4. **Publish.** Post one clue and one photo clue through the existing client. Confirm the media owner is the personal or workspace owner the catalog already chooses, and that the Teams credential id does not appear on the request.
5. **Update.** Install the next build in place (`adb install -r` on Android; do not uninstall). Relaunch with the flag on. Confirm the same credential still signs a map read without repeating activation.
6. **Revocation.** In CalTopo, delete that credential under Your Account. Confirm the next signed call fails, the app does not attach the old session cookie for that account, and Teams publishing still works. Disconnect locally and confirm the Keystore or Keychain item is gone. Enroll again and confirm CalTopo now lists a new credential.
7. **Flag off.** Turn the flag off and relaunch. Confirm personal maps use the existing cookie login again and a stored signing key is not attached to those requests.

Stop if the return URL drops `state`. That result belongs in this note before the matcher is loosened. Do not point the redirect at anything except `127.0.0.1` with a token this app generated.
