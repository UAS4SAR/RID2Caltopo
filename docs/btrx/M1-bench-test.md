# btrx M1 bench test: Connect and decode (Android)

Milestone 1 of the btrx receiver integration (`firmware/R2C_INTEGRATION.md`
in UAS4SAR/btrx, section 13). Android only. The app finds the Desk Pod on
USB, runs the Meshtastic serial handshake, decodes every btrx frame on app
port 256 into the app log (tag `Btrx`), and sends `ROLE_GET` to the Desk Pod
(`^local`). USB is now the Android debug link; Bluetooth, the BTRX panel
and pairing on both platforms are covered in `BLE-bench-test.md`. **Turn on
"Use USB link (debug)" in Main menu > BTRX first**; with it off (the
default) a plugged-in Desk Pod is treated as power only. With Bluetooth on,
opening USB pauses Bluetooth first. Over USB (no empty-read signal on
serial), frames before config_complete, and the burst right after it up to
the first 300 ms with no bytes, are tagged backlog; ROLE_GET goes out after
that gap.

## Setup

- Mac mini bench: Desk Pod on `/dev/cu.usbmodem101`, starboard
  `/dev/cu.usbmodem31201`, port `/dev/cu.usbmodem31101`. The Drone Pod is
  already paired and listening (HEALTH `confirmed=1`, `scanning=1`).
- A5 Pro with the 2.6.0 (445) debug build installed in place
  (`adb install -r`; never uninstall).
- A USB-C OTG adapter or cable for the A5 Pro.

## 1. Reference run with r2c_sim (Mac mini)

```
cd ~/Projects/btrx/firmware/tools/r2c_sim && source ~/Projects/btrx-flash/venv/bin/activate
python r2c_sim.py --port /dev/cu.usbmodem101 listen --seconds 120
python r2c_sim.py --port /dev/cu.usbmodem101 role-get --dest ^local
```

Note the DETECT and HEALTH counts from the starboard node and the Desk Pod's
`ROLE_REPORT` (expect `role=tablet`, `build_hash=0x0100`). The log is under
`~/Projects/btrx-flash/logs/`.

## 2. Free the Desk Pod

Only one program can hold the Desk Pod's USB port. **Stop r2c_sim** (and any
serial monitor on `/dev/cu.usbmodem101`) before unplugging the Desk Pod from
the Mac mini. Leave the starboard and port boards powered.

## 3. Connect the Desk Pod to the A5 Pro

1. Plug the Desk Pod into the A5 Pro through the OTG adapter.
2. Android asks whether RID2Caltopo may open the device (it can offer to
   always open it). Accept. If RID2Caltopo was already running, it may instead
   show the standard USB permission prompt; accept that.
3. In the app log, expect within a few seconds:
   - `USB open: /dev/bus/usb/... 239a:8029 WisCore RAK4631 Board`
   - `handshake: want_config_id=... sent`
   - `handshake: my_info node=!xxxxxxxx`
   - `handshake: config complete, Desk Pod node !xxxxxxxx`
   - `connect backlog drained after a 300 ms quiet gap: N frame(s) queued while R2C was away`
   - `tx ROLE_GET to !xxxxxxxx`
   - `rx from=!xxxxxxxx ... ROLE_REPORT ... role=3(desk-pod) ... build_hash=0x0100`
   - `Desk Pod confirmed: role=3 build_hash=0x0100 (supported)`
4. Right after connecting, a burst of older DETECT/HEALTH frames the Desk
   Pod queued may appear, tagged `backlog` (see the note above). That is
   expected.

Pass: `ROLE_GET` returns `role=3`.

**Check this explicitly.** A ROLE_REPORT addressed to the Desk Pod itself
reaching USB has not been tested on hardware yet. If no ROLE_REPORT arrives,
the app asks again after 10 s and, 10 s later, logs
`no ROLE_REPORT from Desk Pod after a retry; ... Keeping the link open and decoding frames.`
The link stays open and the listen test below still applies. Record which of
the two happened, and report a missing ROLE_REPORT to the btrx firmware side.

Frames the Desk Pod had queued are delivered right after config_complete
(BT RX review, November 11, 2026). Over Bluetooth they are tagged `backlog`
up to the first empty FromRadio read; over USB up to the first 300 ms quiet
gap. If the burst has a longer pause inside it, its remainder is counted as
live; record it if you see that.

## 4. 120 s listen

1. Note the time, then leave the app running with the Drone Pod listening
   for 120 s. The app writes `summary: frames: ...` to the log every 60 s.
2. Expect about **40 DETECT** and **2 HEALTH** from the starboard node in
   120 s (one DETECT at most every 3 s, HEALTH every 60 s), matching the
   r2c_sim run from step 1. Count only live frames: the summary lists the
   connect-time backlog separately.
3. Each line reads like
   `rx from=!<starboard> rssi=-40 snr=9.5 DETECT seq=... pod=00000000 flags=none  window_end_ms=... n=...`.
   Bluetooth addresses show only their last 4 characters (`[..5566 ...]`).
4. There should be no `bad btrx frame`, `refused`, or `undecodable` warnings.

Pass: DETECT and HEALTH counts within a few of the r2c_sim run; no CRC or
version rejections.

## 5. Detach

Unplug the Desk Pod. Expect `USB close (detached); frames: ...` with the
session totals. Plug it back in to confirm reconnect works without
restarting the app.

## 6. Return the Desk Pod to the Mac mini

Plug the Desk Pod back into the Mac mini before running r2c_sim again.

## Notes

- All three btrx boards share USB IDs `0x239A:0x8029`. If a Drone Pod board
  is plugged into the tablet by mistake, the app logs
  `this board answered role=<n> ..., not a Desk Pod` and closes the link
  until that board is unplugged.
- `no Meshtastic config_complete after 10s` usually means another program
  is holding the board, or the board is still booting.
