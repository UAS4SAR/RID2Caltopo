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

The pre-login check is "redirect is present and non-empty." It is not an allowlist. After a real sign-in on 29 October 2026, CalTopo kept the redirect and showed it on the activation page. See the signed-in results below.

## Signed-in results (29 October 2026)

A desktop browser, already able to sign in to a real CalTopo account, requested:

`GET /app/activate/offline?redirect=` followed by a loopback URL that itself contained a query (`http://127.0.0.1:<port>/r2c-probe/callback?state=...`).

After login, the browser landed on `https://caltopo.com/app/activate/finish`, titled Offline Activation. That page showed the account email, an editable device name defaulting to `Unnamed Device`, a checkbox quoting the redirect URL verbatim, and a Sync Account button.

Sync Account sent the browser to the redirect string with this suffix concatenated onto it:

`/client/finish-activate?code=<8 alphanumeric characters>&name=<device name, form-encoded>`

The device name chosen on the page was the `name` value. With a `?` already in the redirect, that suffix landed inside the query and the state value was no longer a separate parameter. The return prefix must contain no query and no fragment. The nonce belongs in the path:

`http://127.0.0.1:<port>/<nonce>`

CalTopo then requests:

`http://127.0.0.1:<port>/<nonce>/client/finish-activate?code=<8 characters>&name=<device name>`

The same concatenation applies to a custom-scheme prefix that also keeps the nonce in the path and has no query, for example `caltopoapp://return/<nonce>`. That form is covered by the matcher and is not registered as an app link. The signed-in check used loopback only.

`GET /api/v1/activate?code=<that code>&name=<that device name>` with no cookies returned HTTP 200 JSON:

- `code`: 12-character credential id
- `key`: 44-character base64 signing secret (32 bytes)
- `account.id`: 6 characters
- `account.type`: `account`
- `account.properties`: `email`, `firstName`, `lastName`, `title`, `subscriptionType`, `subscriptionStatus`, `subscriptionExpires` (milliseconds), `subscriptionRenew`, `guest`, `featuresId`, `class` (`UserAccount` on this account), `provider`, `updated`

No secret from that response is recorded here. The device name typed on the activation page is not the credentials-table Description. See below.

## Credential Description column

The Your Account credentials table is rendered by the CalTopo web bundle (`main.js`, Last-Modified 27 October 2026). Its columns are the `UserCredential` id, `properties.title` labeled **Description**, `properties.lastUsed` labeled **Last Used**, and `properties.updated`. The table is filled from `pt.account.credentials` after:

`GET /sideload/account/{accountId}.json` with query `json={full: true}`

That bundle does not contain the strings `Generated on User Request` or `Last synced by CalTopo App`. The server writes `properties.title`. A signed `GET /api/v1/acct/{accountId}/since/0` updates Last Used and does not change Description. Redeeming `/api/v1/activate?code=…&name=R2C probe` produced Description `Generated on User Request`, so the activation `name` is not that column. Desktop and mobile credentials show the fixed phrase `Last synced by CalTopo App`, not `Last synced by <their device name>`.

The web client’s generic object DAO can create, save, and delete a `UserCredential`:

- `POST /api/v1/acct/{accountId}/UserCredential` with a JSON body
- `POST /api/v1/acct/{accountId}/UserCredential/{credentialId}` with a JSON body
- `DELETE` the same object URL

Unauthenticated probes on 29 October 2026, using only the fake account `AAAAAA`: the collection POST returned HTTP 401 `Authentication required`; the object POST returned HTTP 500 `unknown server error`; GET of the object returned HTTP 401 `Not authorized or object not found`; PUT returned HTTP 405 with `Allow: GET, HEAD, POST, OPTIONS`. `GET /sideload/account/AAAAAA.json` returned HTTP 401 `login required`. There is no public `/api/v1/credential` or `/api/v1/sync` route (HTTP 404). The desktop installer at `/offline/app` redirects to login, so the Desktop program’s own JavaScript was not available.

A signed form POST sets Description. On 29 October 2026 that request returned HTTP 200, and the credentials table showed the submitted `properties.title` as Description. Sign it the same way as any other CalTopo POST: HMAC-SHA256 over `POST <path>\n<expires>\n<json>`, with the key base64-decoded. Send `id`, `expires`, `signature`, and `json` as form fields.

`POST /api/v1/acct/{accountId}/UserCredential/{credentialId}`

The signed JSON is exactly `{"id":"{credentialId}","properties":{"class":"UserCredential","title":"Last synced by RID2Caltopo app"}}`. After a successful activation exchange, Android and Apple send that request. A non-200 response or a transport error is logged and the stored credential is kept. The tracker device name is not used. Apostrophes in names such as `Mike D.'s iPad Pro` are still deferred. The activation `name` remains the Sync Account label, not this Description.

These other probes were not required once that POST stuck, and they have not been run against a real credential: a browser-session POST with body `{"properties":{"title":"…"}}` (the web DAO shape), and extra `title`, `description`, `client`, or `source` query parameters on `GET /api/v1/activate`. A fake activation code still returns HTTP 500 when those parameters are present.

## Mobile return path

Bind `127.0.0.1` on an ephemeral port before opening the browser. The redirect is `http://127.0.0.1:<port>/<32 hex characters>` and nothing else. Open `https://caltopo.com/app/activate/offline?redirect=<that url>&name=<suggested device name>` in the system browser. `name` on that page is still the value the operator confirms; the signed-in page defaulted to `Unnamed Device` when the query was omitted.

Accept a callback only when the path is `/<nonce>/client/finish-activate` and the query has an 8-character `code` plus `name`. A second `?`, an `http` or `https` URL, or the old query-bearing redirect is rejected. Exchange with `GET https://<host>/api/v1/activate?code=<code>&name=<name>` and do not follow redirects. No browser session cookie is required. Store the signing key in the iOS Keychain or the Android Keystore. Teams credentials are not read or written.

Custom schemes such as `rid2caltopo://activate` passed the pre-login non-empty check and failed nothing else, because that check does not look at the value. That is not evidence CalTopo will redirect to a custom scheme after login. `ASWebAuthenticationSession` and Chrome Custom Tabs are the right return path if a logged-in test shows a custom scheme or an HTTPS app link is preserved. They are a poor fit for `http://127.0.0.1`, which is why this prototype opens the system browser and listens on loopback. Google sign-in stays in that browser. It does not run in the embedded web view that holds the cookie experiment. The embedded-webview Google block stays as it is.

The Android session hook only signs personal requests for `caltopo.com`, because those requests are already fixed to that host. The module will also store a `sartopo.com` binding, and the account-catalog helper uses the stored host.

## Credential lifetime, revocation, reconnect

The signed-in account object includes `subscriptionExpires` in milliseconds. That is the subscription, not an expiry of the signing key. The credential itself is the device entry created by Sync Account. Treat the key as long-lived until the operator deletes that credential in CalTopo, or until a live call shows otherwise.

No revoke call was found. `DELETE` was not attempted against a real credential. The prototype can mark its local copy revoked and then refuses to sign, without falling back to cookies for that same account. Reconnect of a still-valid local copy does not call CalTopo. A revoked or missing local copy has to enroll again, which creates another credential on the account. The previous one remains in CalTopo until someone deletes it there. Confirm that in the account UI during the live test.

The signing key is not written into Teams fields, configuration backup, or logs. Android stores ciphertext in `noBackupFilesDir` under a Keystore key that requires the device to have been unlocked. iOS uses `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` on service `org.ncssar.RID2CaltopoApple.caltopo-personal-account`, which is not the Teams service. Both survive an ordinary app update and are removed with the app's private data. A full in-app Keychain wipe that deletes every generic password also deletes this item, which matches the existing reset.

The local record is bound to host plus account id. A credential enrolled for one account is not used for another account's personal maps, and it is not used for Teams publishing.

## How personal login is selected

Settings has **Personal Login Support** with **Credentials** (the default) and **Session Cookies**.

- Credentials enrolls through the system browser and signs personal map, track, clue, and photo requests with the stored account credential. It does not attach the browser session, and it does not use the Teams secret.
- Session Cookies is the existing embedded-browser login, unchanged. The account credential, if one is stored, is not read.
- Teams and service-account publishing is unchanged in either mode.

A device that already had personal browser sign-in selected, and has never stored this setting, stays on Session Cookies so that login keeps working. Choosing the other mode clears the active personal map and account selection and cancels in-flight personal requests. It does not delete the other mode's stored secret. Sign in again for the mode you selected.

Log out, in Credentials mode, deletes the Keychain or Keystore entry and that selection. CalTopo still lists the device on the account until you delete it there. The Log out control says so.

While Credentials is selected and a personal map is connected:

- Android signs that personal request with the account credential and does not attach the cookie. A missing, revoked, or different-account credential fails the personal request. Session Cookies is not used as a fallback.
- Apple builds a `CaltopoLiveConfiguration` with `personalSignedAccount` set and no cookie session, so the existing signer runs and the personal media-owner rules still apply. A Teams map stays on the Teams credential.

Enrollment, the code exchange, and Keystore reads run off the main thread. The Android listener and exchange are called from `enroll`, which blocks and must be started on a worker. The Apple `enroll` hops off the main actor before it listens. After the first successful read, later signing uses an in-memory copy the way the Teams secret already does.

## Open questions

1. Is the activation code single-use, and how quickly does the unused code expire? The local listener still gives up after five minutes. The signed-in exchange was performed once, immediately, without cookies.
2. Does the signing key expire, and does deleting it under Your Account make the next signed call fail? Which HTTP status is that? The credential list in Your Account was not opened during this pass.
3. Does a signed `GET /api/v1/acct/<account id>/since/0` return the same catalog the cookie login normalizes, including a restricted Workspace the pilot was invited to and not a Workspace they were not?
4. Can that credential read a restricted map and publish a clue and a photo through the existing client, with the same full-map read spacing?
5. Does the Keychain or Keystore item survive an in-place app update, and is it gone after the credential is deleted in CalTopo and the app is told to disconnect?
6. Does CalTopo pre-fill "Your device will be synced as" from the activation URL's `name` query? The signed-in page showed `Unnamed Device` when that query was absent. The name that matters is the one submitted with Sync Account.
7. On a phone, can Safari and Chrome complete the redirect to `127.0.0.1`? The signed-in check used a desktop browser. A custom-scheme return that uses the same path suffix has not been tried against CalTopo.

## Live test plan

Use a disposable personal CalTopo account and a disposable map. Do not use a production incident map. Leave the cookie login in place so a failure of this prototype does not remove the current path.

On the test device, open Settings and set Personal Login Support to Credentials. Use Session Cookies only for the comparison pass.

Then:

1. **Enrollment on a phone.** From a worker, call `enroll` with the system-browser opener (`CaltopoPersonalAccountBrowser.systemBrowserIntent` on Android, `AppleCaltopoPersonalAccountBrowser` on Apple). Sign in with Google in that browser, not in the embedded web view. Confirm the browser reaches `http://127.0.0.1:<port>/<nonce>/client/finish-activate` and the app stores the account id and credential id. In Your Account, that credential's Description is `Last synced by RID2Caltopo app`. If that title request fails, the stored credential is still used. Teams credential fields stay unchanged.
2. **Catalog.** `fetchAccountCatalog` (Android) or `CaltopoTeamMapClient` fed by `teamMapConfiguration()` (Apple). Pass the body through the existing personal catalog normalizer. Confirm the invited Workspace appears and an unrelated Workspace does not. Confirm the request was signed and carried no `Cookie` header.
3. **Map read.** Open a restricted map with the signed configuration. Confirm HTTP 200 and that anonymous access to the same map is denied. Confirm a second immediate full read is still held by the existing 90-second gate.
4. **Publish.** Post one clue and one photo clue through the existing client. Confirm the media owner is the personal or workspace owner the catalog already chooses, and that the Teams credential id does not appear on the request.
5. **Update.** Install the next build in place (`adb install -r` on Android; do not uninstall). Relaunch with the flag on. Confirm the same credential still signs a map read without repeating activation.
6. **Revocation.** In CalTopo, delete that credential under Your Account. Confirm the next signed call fails, the app does not attach the old session cookie for that account, and Teams publishing still works. Disconnect locally and confirm the Keystore or Keychain item is gone. Enroll again and confirm CalTopo now lists a new credential.
7. **Flag off.** Turn the flag off and relaunch. Confirm personal maps use the existing cookie login again and a stored signing key is not attached to those requests.

The desktop browser already confirmed the string-append return. On a phone, stop if the browser does not request `/<nonce>/client/finish-activate`. Do not point the redirect at anything except `127.0.0.1` with a nonce this app generated.
