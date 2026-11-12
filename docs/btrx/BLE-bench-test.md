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
1 s of ROLE_GET, a DETECT about every 3 s (every 5 s from firmware 0x0101)
and a HEALTH about every 60 s. Firmware 0x0101 (btrx PR #18, branch
`steering-report`) adds STEER about every second once the pod is paired; see
section 6.

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
   If the pod stops asking for more than 7 s, step 3 says *Not asking any more:
   nod the drone pod twice again*, **Confirm pod** is hidden, and a new nod of
   the same pod brings it back. A different pod is ignored until the Desk Pod
   button is tapped again (back to step 2). A stale pod ends the flow (step 3
   fails: *... Tap the Desk Pod button and nod the drone pod twice again.*) once
   the Desk Pod window has closed, or 2 min after the pod was found.
6. Expect step 4 ✓ with `a_ok=1 b_ok=1` and *Drone Pod paired.* The panel
   shows *Paired Drone Pod: !018968be, pod_id ...*. It survives an app restart.

Pass: PAIR_ACK `a_ok=1`; HEALTH continues with `confirmed=1`. From now on link
health, RSSI and detections come only from `!018968be`; other pods are only
counted (*Other Drone Pods heard on the channel*). Under the paired line, red
hints appear if `!018968be` sends a pod_id other than its own (*Paired Drone
Pod is sending pod_id ... ; re-pair it.*, log `paired node ... sending pod_id
...; re-pair`), or if no HEALTH arrives from it for over 2 min while connected
(*No HEALTH from the paired Drone Pod for over 2 min; re-pair?*). Holding the
pod still for 10 s resets it to pod_id 0, unconfirmed, and it stops
transmitting, so the second hint is what to expect after that.

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
2. Switch on with the Desk Pod plugged in and the Bluetooth link up. The first
   time after turning the switch on, Android shows its USB permission dialog
   (*Allow RID2Caltopo to access ...?*); allow it. The panel then shows *Desk Pod
   is attached by USB; Bluetooth paused* and the link line reads *USB (debug)*.
   The log shows the Bluetooth session close before `USB open`, then
   `connect backlog drained after a 300 ms quiet gap: N frame(s) ...` and
   `tx ROLE_GET`.
3. Switch off again: the log shows `USB: sent disconnect so the Desk Pod
   re-enables Bluetooth`, and Bluetooth reconnects within seconds.
4. Switch on, then unplug. The app sees a detached close and sends no
   disconnect, but unplugging also powers the Desk Pod off (no battery), and it
   boots with Bluetooth on, so Bluetooth reconnects once the Desk Pod is powered
   again. The firmware's 15 min Bluetooth-off case applies only if the Desk Pod
   stays powered while the USB link disappears without a disconnect (for
   example, the app is killed while the USB link is open).

Pass: never both links open at once; with the switch off a plugged-in Desk Pod
never pauses Bluetooth.

## 6. Steering overlay (firmware 0x0101)

Needs both pods on firmware build 0x0101 (the Desk Pod panel's firmware line
reads *supported*), the Drone Pod paired, and the Desk Pod link up. **Pod B units
flashed with the earlier PR #18 UF2 must be reflashed with 4a68518 or later**
(roll sign fix). The map does not change in this build. Reference: `python
r2c_sim.py --ble Meshtastic_42ca steer` prints the same STEER lines (disconnect it
before testing the tablet).

**Which tile:** the overlay shows only on the stream of the drone carrying the
pod. With exactly one live stream (Android: LIVE, not local playback; iPad: live,
not the demo tile) it appears there by itself. With more than one live, each live
tile shows a **Drone Pod is on this drone** button at the top instead; tap it on
the right drone and the overlay appears on that tile only. The Desk Pod panel's
Steering section shows *Steering overlay on: ...* and **Choose another drone**
(clears the pick so the buttons come back). The pick clears by itself when that
stream ends.

What the pilot sees: at the top center of the video, one circle and a large
arrow in the source's color. **The arrow carries the meaning, not a word** (Ken,
Nov 12: left/right words need extra processing for some people). The word
(LEFT, RIGHT, STRAIGHT, CLOSER, FARTHER, LEVEL or LOST) is only a small label
under the arrow and the accessibility label. No numbers.

| Hint | Arrow |
| --- | --- |
| STRAIGHT | points straight up |
| LEFT / RIGHT | up and 45° to that side |
| hard left / right (8 dB+, or one patch with 2+ hits) | flat, pointing to that side |
| CLOSER | straight up with a second chevron behind the head |
| FARTHER | U-turn arrow (up, over to the right, back down): turn around |
| LEVEL | the held arrow turns gray; wings-level badge (ring with a level line) at bottom left |
| LOST | no arrow; the faded circle gets a slash and a dashed ring |
| a source muted on the pod | crossed-out speaker badge at bottom right |

**Arrow length = relative distance.** Long while the source is weak (far),
shorter as it gets stronger, as in the original combined target vector (btrx
design doc §17.2, Ken v0.6: "long while the signal is weak and shortens as the
signal strengthens"); §41.2's first build had a fixed length. The length follows
the stronger of the two patch means: -50 dBm or stronger is the shortest arrow,
-100 dBm or weaker the longest, linear in dB between (that is linear in log
range). Smoothed as §17.2 says: EMA 0.3 per report and at most 10% of full scale
per second, so a fade or a single odd report cannot make it jump; held while
faded, gated or LOST; a new source starts at its own length. Circle shape by device kind: solid phone, ringed wearable or
audio, dashed tracker, plain otherwise. Each new source takes the next of 8
colors (Okabe-Ito with white); gray is only for LEVEL. The Desk Pod panel's
**Steering** section shows the last STEER (swatch, src, kind, word, age),
**Clear mutes**, and the **Roll correction (debug)** switch (off by default). Each
STEER is written to the btrx log at debug level (`rx ... STEER ...`, address last
4 only).

1. **Left/right must be verified.** PR #18 fixed the firmware's port/starboard
   swap (DETECT's rssi_port/rssi_stbd were swapped); the app decodes DETECT and
   STEER as sent. Hold the Drone Pod level, nose away from you, and place a
   phone about 3 m off its **port** (left) side with Bluetooth on. Within a few
   seconds the arrow must point **left** (flat when the difference is 8 dB or
   more, or heard on the port patch only with 2+ hits). Move the phone to the
   starboard side: the arrow points right. Directly ahead or behind: straight
   up. If the sides come out reversed, stop and report it to BT RX (the firmware
   fix or the board roles, not the app).
   *Desk Pod as the source:* with the Desk Pod's Bluetooth antenna right in front
   of the Drone Pod's **port** patch and the starboard patch facing 90° away, the
   Desk Pod (Meshtastic_42ca) is usually the strongest source: `rssi_port` must
   be clearly above `rssi_stbd`, `hits_port` >= `hits_stbd`, and the arrow points
   left (flat) and short (strong signal).
2. **Roll sign** (r2c_sim `steer`, or the app's debug log): level, `roll_x2` and
   `pitch_x2` read about 0. Tilt pod B starboard side down by about 10°:
   `roll_x2` must go to about **+20**. If it goes negative, pod B's IMU is
   mounted another way than design doc §29.3 says, or pod B still runs the
   earlier UF2: fix that before turning on **Roll correction (debug)**. Only after
   this check passes may the switch be turned on (it is off by default and resets
   to off when the app restarts); with it on, 10° starboard down shifts the
   hint by about 2 dB toward LEFT.
3. Walk the phone steadily toward the pod from ahead for 10 s or more: the arrow
   shortens, and the CLOSER chevron appears once the peak rises at 0.5 dB/s or
   more, clearly above the noise (it needs 6 reports, so not before about 6 s);
   away: the arrow lengthens and turns into the U-turn arrow (FARTHER: turn
   around). Standing still clears the chevron or U-turn.
4. Tilt the pod past its attitude gate: at once the arrow turns gray and the
   wings-level badge shows (LEVEL); once level again the previous arrow returns in
   color.
5. **Mute**: tap the circle (or **Mute** under the arrow). The log shows
   `steering: STEER_MUTE mute src=N to !018968be`; the pod moves to the next
   strongest source (new color) and the panel shows *A steering source is muted
   on the Drone Pod.* The crossed-out speaker badge shows on the overlay. Check with r2c_sim `steer` that the muted flag is set.
   Mutes live only in the pod's memory: up to 8, cleared when the pod restarts,
   and a muted source unheard for 60 s is forgotten and comes back unmuted under
   a new src_id (new color).
6. **Clear mutes** in the Desk Pod panel: log `steering: STEER_MUTE clear to
   !018968be`; the muted line goes away and the first source can return.
7. **2 s fade**: with a target showing, unplug or power off the Desk Pod (or walk
   the tablet out of Bluetooth range). Within 2 s of the last STEER the circle and
   arrow fade (same look as a no-target report) while the arrow stays; at 5 s the
   arrow goes and the slashed circle (LOST) shows; after 12 s with no STEER the
   overlay goes away.
8. **LOST**: switch the phone's Bluetooth off (and nothing else nearby). A
   report with bit 0 clear means nothing was heard in that 1 s window: the
   circle fades at once and the arrow holds, then the slashed circle (LOST) with
   no arrow 5 s after the last report with a target. The pod repeats the no-target STEER at most
   every 5 s.
9. **Pod restart**: power-cycle the Drone Pod while the overlay shows. The log
   shows `steering: window_end_ms went back ...: the Drone Pod restarted`; src ids
   start again at 1, so colors restart from the first and the muted line clears.
10. Turn **Connect over Bluetooth** off (or forget the paired pod): the overlay
    disappears. It never shows before a pod is paired, from another pod's node,
    with another pod_id, or from the connect backlog.

Pass: the port-side phone (or the Desk Pod in front of the port patch) points
the arrow left on both tablets; the roll sign reads
positive starboard side down; the 2 s fade, Mute and Clear mutes behave as
above; the overlay is only on the pod drone's tile; nothing is ever posted as a
clue.

## Notes

- If the PIN was entered wrongly or the Desk Pod was re-flashed, the panel says
  the link is *not paired*. Forget the Desk Pod in the system Bluetooth
  settings (Android or iPad), then connect again.
- `not a Desk Pod (role=...)`: a Drone Pod board answered; the app skips that
  device until the Desk Pod is forgotten.
