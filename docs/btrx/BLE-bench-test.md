# btrx bench test: Desk Pod over Bluetooth, link health and Pair drone pod (Android and iPad)

Bluetooth is the primary Desk Pod link on both platforms. USB
(`M1-bench-test.md`) stays as an Android debug link. Main menu > **Desk Pod**
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
   On Android the panel says so while bonding; on the iPad the system sheet
   appears when the app first touches the encrypted characteristic.
4. Expect in the app log (tag/category `Btrx`):
   - `ble: found Meshtastic_42ca rssi=...`
   - `ble: connected to Meshtastic_42ca`
   - `handshake: want_config_id=... sent`, `handshake: config complete, Desk Pod node !66c842ca`
   - `tx ROLE_GET to !66c842ca`
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
5. Tap **Confirm pod**. Log: `tx PAIR_CONFIRM to !018968be ... pod_id=<non-zero>`.
6. Expect step 4 ✓ with `a_ok=1 b_ok=1` and *Drone Pod paired.* The panel
   shows *Paired Drone Pod: !018968be, pod_id ...*. It survives an app restart.

Pass: PAIR_ACK `a_ok=1`; HEALTH continues with `confirmed=1`.

Also check, one at a time:
- **Tap before connecting.** Turn Bluetooth off, tap the Desk Pod button, turn
  Bluetooth on within about 30 s, then start pairing. The window opened by the
  backlog tap still counts (*The Desk Pod pairing window is open*), and the
  flow starts at step 2. Record whether the DESK_PAIR frame was tagged backlog.
- **Cancel.** Start pairing, nod, then **Cancel pairing** at step 3. Nothing is
  sent (no `tx PAIR_CONFIRM`). The pod times out on its own.
- **Window runs out.** Tap the button and do not nod for 60 s: step 2 fails with
  *The pairing window ran out.*
- **Button not tapped.** Start pairing and wait 2 minutes: step 1 fails.

## 4. Reconnect

1. Power-cycle the Desk Pod. The link line shows *waiting to retry* with
   delays of 2, 4, 8, 16 then 30 s, then reconnects on its own (no PIN).
2. Lock the tablet for 2 minutes with the link up. Unlock: the link is still
   connected and the live DETECT count kept growing (Android: the scanning
   service keeps the process alive; iPad: `bluetooth-central` background mode).
3. **Forget this Desk Pod** disconnects and scans for any Desk Pod again.

## 5. USB and Bluetooth together (Android only)

1. With the Bluetooth link up, plug the Desk Pod into the A5 Pro by USB.
2. The panel shows *Desk Pod is attached by USB; Bluetooth paused* and the
   link line reads *USB (debug)*. The log shows the Bluetooth session close
   before `USB open`.
3. Unplug. About 3 s later Bluetooth reconnects.

Pass: never both links open at once.

## Notes

- If the PIN was entered wrongly or the Desk Pod was re-flashed, the panel says
  the link is *not paired*. Forget the Desk Pod in the system Bluetooth
  settings (Android or iPad), then connect again.
- `not a Desk Pod (role=...)`: a Drone Pod board answered; the app skips that
  device until the Desk Pod is forgotten.
