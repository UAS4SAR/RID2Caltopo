# btrx bench test: Desk Pod over Bluetooth, link health and Pair drone pod (Android and iPad)

Bluetooth is the primary Desk Pod link on both platforms. USB
(`M1-bench-test.md`) stays as an Android debug link, used only when
**Use USB link (debug)** is on (off by default; see section 5). Main menu > **Desk Pod**
shows the link, the Desk Pod's role and firmware, link health and the
**Pair drone pod** flow. The panel looks and behaves the same on Android and
the iPad. **Dismiss** only hides the panel; the link keeps running.
**Cancel pairing** ends a pairing in progress and sends nothing to the pod.
Nothing on this screen ever posts a clue.

Bench facts: Desk Pod `Meshtastic_42ca` (node `!66c842ca`), Drone Pod
`!018968be`, Bluetooth PIN `123456` (FIXED_PIN). Expect a ROLE_REPORT within
1 s of ROLE_GET, a DETECT about every 3 s and a HEALTH about every 60 s.

## Setup

- 2.6.0 (445) debug builds installed in place on the A5 Pro (`adb install -r`;
  never uninstall) and on the iPad.
- Only one central can hold the Desk Pod's Bluetooth link. Stop r2c_sim
  `--ble`, and turn **Connect over Bluetooth** off on the other tablet, before
  each test. The Desk Pod's USB port must not be open in r2c_sim either.
- Reference run on the Mac mini (btrx `firmware/tools/r2c_sim`, commit
  340967f or later):

  ```
  python r2c_sim.py --ble Meshtastic_42ca listen --seconds 120
  python r2c_sim.py --ble Meshtastic_42ca role-get --dest ^local
  ```

  Note the live DETECT and HEALTH counts, then disconnect r2c_sim.

## 1. Connect (each tablet)

1. Main menu > **Desk Pod**. Turn on **Connect over Bluetooth**.
   - Android asks for the Nearby devices permission if it was not granted.
   - The iPad asks for Bluetooth permission the first time.
2. The link line goes through *looking for the Desk Pod (scan 1 of 3)*,
   *pairing with the Desk Pod (enter the PIN)* on first use, *connecting*,
   *reading configuration*, *connected*.
3. First use only: the system asks for the Bluetooth PIN. Enter `123456`.
   On Android the panel says so while bonding. On the iPad the system PIN sheet
   appears when the app enables notifications (an encrypted write); the panel
   shows *pairing with the Desk Pod (enter the PIN)* and the app waits up to
   90 s for the PIN to be typed before giving up and retrying.
4. Expect in the app log (tag/category `Btrx`):
   - `ble: found Meshtastic_42ca rssi=...`
   - `ble: connected to Meshtastic_42ca`
   - `handshake: want_config_id=... sent`, `handshake: config complete, Desk Pod node !66c842ca`
   - `rx backlog from=...` for each frame the Desk Pod queued while the app was away,
     then `connect backlog drained: N frame(s) queued while R2C was away`
     (everything read before the first empty FromRadio read is backlog)
   - `tx ROLE_GET to !66c842ca` (sent only after the backlog, so its answer is live)
   - `Desk Pod confirmed: role=3 build_hash=0x0100 (supported)`
5. The panel shows **Role: Desk Pod** and **Firmware build: 0x0100 (supported)**.

Pass: connected within about 30 s; role 3.

Record: whether the PIN prompt appeared, how long the connection took, and
how many frames were tagged backlog right after connecting.

## 2. Link health (120 s)

1. Leave the panel open for 120 s with the Drone Pod listening.
2. **Link health** shows the Drone Pod's HEALTH line (confirmed, listening),
   both directions of RSSI/SNR, and *Detections: N in the last minute*.
3. The live and backlog counts are listed separately. Compare the live DETECT
   count with the r2c_sim reference (about 40 in 120 s; 2 HEALTH).
4. The app log has `ble summary: frames: ...` every 60 s and no `bad btrx frame`,
   `refused`, `undecodable` or `got no answer` warnings.

## 3. Pair drone pod

1. Tap **Pair drone pod**. Step 1 is active: *Tap the Desk Pod button*.
2. Tap the Desk Pod button. Step 2 becomes active with a countdown.
3. Nod the drone pod twice (hold still 3 s, tip at least 25° and back, twice).
4. Step 3 shows the pod (`!018968be`, dev_a, dev_b, imu_ok, RSSI, SNR) and
   any warning (port board missing, motion sensor not OK). Nothing is sent yet.
5. Tap **Confirm pod** while the pod is still asking (its PAIR_REQ repeats every
   2 s inside its own 60 s nod window). Log:
   `pairing: PAIR_CONFIRM to !018968be nonce=... pod_id=<non-zero>`.
   If the pod stops asking for more than 5 s, step 3 says *Not asking any more:
   nod the drone pod twice again*, **Confirm pod** is hidden, and a new nod
   brings it back.
6. Expect step 4 ✓ with `a_ok=1 b_ok=1` and *Drone Pod paired.* The panel
   shows *Paired Drone Pod: !018968be, pod_id ...*. It survives an app restart.

Pass: PAIR_ACK `a_ok=1`; HEALTH continues with `confirmed=1`. From now on link
health, RSSI and detections come only from `!018968be`; other pods are only
counted (*Other Drone Pods heard on the channel*).

Also check, one at a time:
- **Tap before connecting.** Turn Bluetooth off, tap the Desk Pod button, turn
  Bluetooth on within about 30 s, then start pairing. The window opened by the
  backlog tap still counts (*The Desk Pod pairing window is open*), and the
  flow starts at step 2. Record whether the DESK_PAIR frame was tagged backlog.
  This works only while the Desk Pod's queue has room: with the Drone Pod
  running it fills about 90 s after the last disconnect and later frames,
  including the tap, are dropped. If the window does not show, tap the Desk Pod
  button again.
- **Cancel.** Start pairing, nod, then **Cancel pairing** at step 3. Nothing is
  sent (no `pairing: PAIR_CONFIRM`); confirm=0 would unpair, so it is never sent.
  The pod keeps sending PAIR_REQ until its own window ends, and holds its
  DETECTs back for up to 60 s after the nod.
- **Window runs out.** Tap the button and do not nod for 60 s: step 2 fails.
  The Desk Pod's window-closed frame arrives just before the app's own 60 s
  timer, so the message is usually *The Desk Pod closed its pairing window…*
  rather than *The pairing window ran out.*
- **Button not tapped.** Start pairing and wait 2 minutes: step 1 fails.

## 4. Reconnect

1. Power-cycle the Desk Pod. The link line shows *waiting to retry* with
   delays of 2, 4, 8, 16 then 30 s, then reconnects on its own (no PIN).
2. Lock the tablet for 2 minutes with the link up. Unlock: the link is still
   connected and the live DETECT count kept growing (Android: the scanning
   service keeps the process alive; iPad: `bluetooth-central` background mode).
3. **Forget this Desk Pod** disconnects and scans for any Desk Pod again.

## 5. USB debug link (Android only)

The Desk Pod has no battery. A tablet's USB-C port may be what powers it, so USB
is opt-in: **Use USB link (debug)** in the Desk Pod panel, off by default.

1. Switch off: plug the Desk Pod into the A5 Pro. Android does not offer to open
   RID2Caltopo, the log says `... left alone: USB debug link is off, so it is
   power only`, and Bluetooth stays the link (it connects once the Desk Pod has
   booted).
2. Switch on with the Desk Pod plugged in and the Bluetooth link up: the panel
   shows *Desk Pod is attached by USB; Bluetooth paused* and the link line reads
   *USB (debug)*. The log shows the Bluetooth session close before `USB open`.
3. Switch off again: the log shows `USB: sent disconnect so the Desk Pod
   re-enables Bluetooth`, and Bluetooth reconnects within seconds.
4. Switch on, then unplug. Unplugging powers the Desk Pod off (no battery), so
   Bluetooth reconnects only once it is powered again. With the disconnect sent
   on a clean USB close it comes back in seconds; without it (cable pulled while
   the USB link was open) the firmware may keep Bluetooth off for up to 15 min.

Pass: never both links open at once; with the switch off a plugged-in Desk Pod
never pauses Bluetooth.

## Notes

- If the PIN was entered wrongly or the Desk Pod was re-flashed, the panel says
  the link is *not paired*. Forget the Desk Pod in the system Bluetooth
  settings (Android or iPad), then connect again.
- `not a Desk Pod (role=...)`: a Drone Pod board answered; the app skips that
  device until the Desk Pod is forgotten.
