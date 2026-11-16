package org.ncssar.rid2caltopo.btrx

import java.util.Locale
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkKind
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.PairStep
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * The Desk Pod screen's wording, shared logic so Android and iPad say the same
 * thing (the iPad copy is R2CCore BtrxDeskPodText.swift; keep them matching).
 */
object BtrxDeskPodText {
    /** Developer Tools entry and screen title (the btrx receiver system; the pods keep their names inside). */
    const val TITLE = "BTRX"
    /** Top of the BTRX pane (Settings > Developer Tools > BTRX): opens the BTRX page in the system browser. */
    const val LEARN_MORE = "Learn more about BTRX"
    const val LEARN_MORE_URL = "https://rid2caltopo.com/btrx"
    const val USB_PAUSED = "Desk Pod is attached by USB; Bluetooth paused"
    const val BLUETOOTH_TOGGLE = "Connect over Bluetooth"
    const val POD_STALE_HINT = "Not asking any more: nod the drone pod twice again."
    const val PAIRED_POD_QUIET = "No HEALTH from the paired Drone Pod for over 2 min; re-pair?"
    /** Android only: USB is a debug link (an iPad cannot open a USB serial device). */
    const val USB_DEBUG_TOGGLE = "Use USB link (debug)"
    const val USB_DEBUG_HINT = "Off: a Desk Pod plugged into this tablet only draws power and Bluetooth stays the link. On: a plugged-in Desk Pod is opened over USB and Bluetooth pauses."
    val PIN_HINT = "Bluetooth PIN: printed on the Desk Pod (${MeshtasticBle.DEFAULT_PIN} by default for now)."

    enum class StepStatus { PENDING, ACTIVE, DONE, FAILED }

    data class Step(val title: String, val status: StepStatus, val detail: String)

    fun linkLine(s: BtrxDeskPodModel.Snapshot): String {
        val kind = when (s.link.kind) {
            LinkKind.BLUETOOTH -> "Bluetooth"
            LinkKind.USB -> "USB (debug)"
            LinkKind.NONE -> "None"
        }
        return "$kind: ${phaseName(s.link.phase)}" + reconnectSuffix(s.link)
    }

    /** Bluetooth connects failing in a row: "; reconnecting, attempt N (last failure: ...)". */
    fun reconnectSuffix(link: BtrxDeskPodModel.LinkStatus): String {
        if (link.failedAttempts <= 0 || link.phase == LinkPhase.READY || link.phase == LinkPhase.OFF || link.phase == LinkPhase.PAUSED_FOR_USB) return ""
        val last = link.lastFailure?.let { " (last failure: $it)" } ?: ""
        return "; reconnecting, attempt ${link.failedAttempts + 1}$last"
    }

    fun phaseName(p: LinkPhase): String = when (p) {
        LinkPhase.OFF -> "off"
        LinkPhase.UNAVAILABLE -> "unavailable"
        LinkPhase.NO_PERMISSION -> "permission needed"
        LinkPhase.SCANNING -> "looking for the Desk Pod"
        LinkPhase.BONDING -> "pairing with the Desk Pod (enter the PIN)"
        LinkPhase.CONNECTING -> "connecting"
        LinkPhase.CONFIGURING -> "reading configuration"
        LinkPhase.READY -> "connected"
        LinkPhase.RETRY_WAIT -> "waiting to retry"
        LinkPhase.PAUSED_FOR_USB -> "paused (USB attached)"
    }

    fun deviceLine(s: BtrxDeskPodModel.Snapshot): String? {
        val name = s.link.deviceName
        val node = s.localNode?.let { BtrxClient.nodeName(it) }
        return when {
            name != null && node != null -> "$name (node $node)"
            name != null -> name
            node != null -> "Node $node"
            else -> null
        }
    }

    fun roleLine(s: BtrxDeskPodModel.Snapshot): String {
        val r = s.role ?: return "Role: not reported yet"
        return if (r.role == P.ROLE_DESK_POD) "Role: Desk Pod" else "Role: ${P.roleName(r.role)} (not a Desk Pod)"
    }

    fun firmwareLine(s: BtrxDeskPodModel.Snapshot): String {
        val r = s.role ?: return "Firmware: not reported yet"
        return "Firmware build: %s (%s)".format(Locale.US, hex4(r.buildHash), P.buildSupportText(r.buildHash))
    }

    /** Link-health lines, in display order. */
    fun healthLines(s: BtrxDeskPodModel.Snapshot): List<String> = buildList {
        val h = s.dronePodHealth
        if (h == null) {
            add("Drone Pod: no HEALTH report yet (one arrives about every 60 s)")
        } else {
            val hh = h.health
            add("Drone Pod ${BtrxClient.nodeName(h.node)}: ${if (hh.confirmed == 1) "confirmed" else "not confirmed"}, ${if (hh.scanning == 1) "listening" else "not listening"}")
            add("Last HEALTH: ${ago(s.nowMs - h.arrivalMs)} (pod uptime ${hh.uptimeS} s, flags ${P.statusFlagNames(hh.flags)})")
            add("Desk Pod hears Drone Pod: RSSI ${h.rxRssi} dBm, SNR ${"%.1f".format(Locale.US, h.rxSnr)} dB")
            add("Drone Pod hears Desk Pod: RSSI ${hh.loraRssi} dBm, SNR ${hh.loraSnr} dB")
            hh.ext?.let { addAll(dronePodExtLines(it)) }
        }
        val last = s.lastDetectMs?.let { ", last ${ago(s.nowMs - it)}" } ?: ""
        add("Detections: ${s.detectsLastMinute} in the last minute$last")
        if (s.otherPodNodes > 0) add("Other Drone Pods heard on the channel: ${s.otherPodNodes}")
        s.deskPhoneLost?.let { add(deskPhoneLostLine(it)) }
        // Desk Pod HEALTH from byte 30: only phone_lost and reset_reason mean anything (38-43 are always 0).
        s.deskHealthExt?.let { add("Desk Pod last reset: ${resetReasonText(it.resetReason)}") }
        add(deskWindowLine(s))
    }

    /** The Desk Pod's pairing state, from DESK_PAIR (not HEALTH). */
    fun deskWindowLine(s: BtrxDeskPodModel.Snapshot): String =
        if (s.windowOpen) "Desk Pod pairing window: open, ${s.windowSecondsLeft} s left (${deskPairSourceText(s.windowSource)})"
        else "Desk Pod pairing window: closed"

    /** DESK_PAIR source: 1 button, 2 phone/USB-opened (DESK_PAIR), 3 app-opened (PAIR_OPEN, build 0x0107). */
    fun deskPairSourceText(src: Int): String = when (src) {
        P.DESK_SRC_BUTTON -> "button"
        P.DESK_SRC_LINK -> "phone/USB-opened"
        P.DESK_SRC_PAIR_OPEN -> "app-opened"
        else -> "source $src"
    }

    /** Drone Pod HEALTH fields from build 0x0106 (offsets 34-43). */
    fun dronePodExtLines(e: P.HealthExt): List<String> = listOf(
        "Drone Pod last reset: ${resetReasonText(e.resetReason)}",
        "Drone Pod pairing window last opened by: ${pairSourceText(e.pairSource)}; nods ignored while paired: ${e.nodsIgnored}",
        "Drone Pod leaves out: ${excludeText(e.excludeFlags)}; ${e.excludedPerS} ad(s) left out per second",
    )

    /** reset_reason (nRF52 RESETREAS) in words; the hex value too unless it is 0. */
    fun resetReasonText(v: Long): String {
        if (v == 0L) return "power on or brown-out"
        val words = mapOf(
            "reset-pin" to "reset pin", "watchdog" to "watchdog", "soft-reset" to "software reset", "lockup" to "CPU lockup",
            "wake-pin" to "wake from pin", "wake-lpcomp" to "wake from LPCOMP", "wake-debug" to "debug interface",
            "wake-nfc" to "wake from NFC", "wake-usb" to "wake from USB",
        )
        val names = P.resetReasonNames(v).split(",").joinToString(", ") { words[it] ?: it }
        return "$names (0x%x)".format(Locale.US, v)
    }

    fun pairSourceText(src: Int): String = when (src) {
        P.PAIR_SRC_NONE -> "nothing since it started"
        P.PAIR_SRC_NOD -> "a nod (not paired yet)"
        P.PAIR_SRC_APP -> "this app (Re-pair)"
        else -> "unknown ($src)"
    }

    /** exclude_flags: which of its own boards, the Desk Pod and DJI the pod leaves out of STEER and DETECT. */
    fun excludeText(flags: Int): String {
        val items = listOf(
            P.EXCL_OWN to "its own board", P.EXCL_OTHER_BOARD to "the other board",
            P.EXCL_DESK_POD to "the Desk Pod", P.EXCL_DJI to "DJI",
        )
        val on = items.filter { flags and it.first != 0 }.map { it.second }
        val off = items.filter { flags and it.first == 0 }.map { it.second }
        return when {
            on.isEmpty() -> "nothing yet (${off.joinToString(", ")} not known)"
            off.isEmpty() -> on.joinToString(", ")
            else -> on.joinToString(", ") + " (not yet: " + off.joinToString(", ") + ")"
        }
    }

    /** Desk Pod HEALTH phone_lost (build 0x0104): messages dropped from its full queue to this app. */
    fun deskPhoneLostLine(lost: Long): String =
        "Desk Pod to app: ${if (lost >= 0xFFFFFFFFL) "4294967295+" else lost.toString()} message(s) dropped since the Desk Pod started (queue full)"

    /** "Live frames: ..." plus the packets dropped before btrx decoding, when there are any. */
    fun liveFramesLine(s: BtrxDeskPodModel.Snapshot): String {
        val dropped = if (s.droppedEncrypted + s.droppedOtherPort == 0) ""
            else "; dropped: encrypted=${s.droppedEncrypted} other-port=${s.droppedOtherPort}"
        return countsLine("Live frames", s.liveCounts) + dropped
    }

    fun countsLine(label: String, counts: Map<String, Int>): String =
        "$label: " + if (counts.isEmpty()) "none" else counts.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }

    fun pairedLine(s: BtrxDeskPodModel.Snapshot): String? = s.pairedPod?.let {
        "Paired Drone Pod: ${BtrxClient.nodeName(it.node)}, pod_id %08x, dev_a %08x, dev_b %08x".format(Locale.US, it.podId, it.devA, it.devB)
    }

    /** Warnings under the paired pod: an unexpected pod_id from it, or no HEALTH for a while. */
    fun pairedPodWarnings(s: BtrxDeskPodModel.Snapshot): List<String> = buildList {
        if (s.pairedPod == null) return@buildList
        s.lastMismatchPodId?.let {
            if (s.pairedPodIdMismatches > 0) {
                add("Paired Drone Pod is sending pod_id %08x (%d frame(s) ignored); re-pair it.".format(Locale.US, it, s.pairedPodIdMismatches))
            }
        }
        if (s.pairedPodQuiet) add(PAIRED_POD_QUIET)
    }

    // ---- Desk Pod button confirmation (firmware 0x0108) ----

    const val CONFIRM_PAIR_TITLE = "Change Drone Pod pairing?"
    const val CONFIRM_PAIR_BODY = "The Desk Pod button was tapped. Confirm opens the Desk Pod's pairing window so a Drone Pod can pair. Cancel leaves the pairing as it is."
    const val CONFIRM_RESET_TITLE = "Factory reset the Desk Pod?"
    const val CONFIRM_RESET_BODY = "The Desk Pod button was held 10 s. A factory reset erases the Desk Pod's saved settings and its pairing, and cannot be undone. The Drone Pod must be paired again afterwards."
    const val CONFIRM_OK = "Confirm"
    const val CONFIRM_RESET_OK = "Factory reset"
    const val CONFIRM_CANCEL = "Cancel"
    const val CONFIRM_WAITING = "Confirmed. Waiting for the Desk Pod…"
    const val DESK_RESET_WARNING = "The Desk Pod was factory reset from its button and is no longer paired. Re-pair the Drone Pod (or Pair drone pod)."

    fun confirmTitle(v: BtrxButtonConfirm.View): String = when (v.action) {
        P.CFM_ACTION_DESK_PAIR -> CONFIRM_PAIR_TITLE
        P.CFM_ACTION_FACTORY_RESET -> CONFIRM_RESET_TITLE
        else -> "Desk Pod button action ${v.action}?"
    }

    fun confirmBody(v: BtrxButtonConfirm.View): String = when (v.action) {
        P.CFM_ACTION_DESK_PAIR -> CONFIRM_PAIR_BODY
        P.CFM_ACTION_FACTORY_RESET -> CONFIRM_RESET_BODY
        else -> "The Desk Pod asks to confirm a button action this app does not know."
    }

    fun confirmCountdown(v: BtrxButtonConfirm.View): String =
        if (v.confirmSent) CONFIRM_WAITING else "Nothing happens unless you confirm (${v.secondsLeft} s left)."

    /** Desk Pod pane: whether the Desk Pod asks this app before its button acts. */
    fun buttonConfirmLine(s: BtrxDeskPodModel.Snapshot): String = "Desk Pod button: " + when (s.buttonConfirmLink) {
        BtrxDeskPodModel.ButtonConfirmLink.ON -> "asks this app to confirm pairing or a factory reset"
        BtrxDeskPodModel.ButtonConfirmLink.NO_LINK -> "acts at once (no link to the Desk Pod)"
        BtrxDeskPodModel.ButtonConfirmLink.OLD_FIRMWARE -> "acts at once (confirmation needs Desk Pod firmware 0x0108)"
        BtrxDeskPodModel.ButtonConfirmLink.NOT_IN_FRONT -> "acts at once (this app is not in front)"
    }

    const val MUTE = "Mute"
    const val CLEAR_MUTES = "Clear mutes"
    // One Desk Pod and one Drone Pod per app: replace, never add another.
    const val PAIR_POD = "Pair drone pod"
    const val REPLACE_POD = "Replace Drone Pod"
    const val REPLACE_POD_TITLE = "Replace Drone Pod?"
    const val REPLACE_POD_OK = "Unpair and pair new"
    const val REPLACE_DESK = "Replace Desk Pod"
    const val REPLACE_DESK_TITLE = "Replace Desk Pod?"
    const val REPLACE_DESK_OK = "Forget and scan"
    const val REPLACE_KEEP = "Keep current"
    // Re-pair (firmware 0x0106: a paired pod ignores nods; PAIR_OPEN opens its window).
    const val REPAIR_POD = "Re-pair Drone Pod"
    const val REPAIR_POD_TITLE = "Re-pair Drone Pod?"
    const val REPAIR_POD_OK = "Open pairing"

    /** Body of the "Re-pair Drone Pod?" confirmation. */
    fun repairPodMessage(s: BtrxDeskPodModel.Snapshot): String {
        val pod = s.pairedPod?.let { "Drone Pod ${BtrxClient.nodeName(it.node)}" } ?: "The Drone Pod"
        return "This opens the pairing window on the Desk Pod and on $pod for 60 s, then asks you to confirm the pod. " +
            "Do it on the ground, not in flight. Steering keeps working meanwhile; if you do not confirm, the pod stays paired as it was."
    }

    // Own aircraft (saved): STEER_MUTE op 3 / 4 (firmware 0x0106); the local toggle stays for older builds.
    const val OWN_SAVED = "Own aircraft (saved)"
    const val OWN_SAVED_DONE = "Saved"
    const val OWN_SAVED_REMOVE = "Remove"
    const val OWN_SAVED_HINT = "Saved on the Drone Pod: it leaves the address out of STEER and DETECT, also after a restart. Pick a source heard in the last 60 s."
    fun ownSavedTitle(count: Int): String = "Own aircraft saved on the Drone Pod: $count"
    fun srcIdText(id: Int): String = "source %04X".format(Locale.US, id)

    /** Body of the "Replace Drone Pod?" confirmation. */
    fun replacePodMessage(s: BtrxDeskPodModel.Snapshot): String {
        val pod = s.pairedPod?.let { "Drone Pod ${BtrxClient.nodeName(it.node)} (pod_id %08x)".format(Locale.US, it.podId) } ?: "The current Drone Pod"
        return "$pod will be unpaired from this app, then pairing starts for the new pod. " +
            "The old pod keeps its own pairing and keeps sending until it is paired again or factory reset; this app ignores it."
    }

    /** Body of the "Replace Desk Pod?" confirmation. */
    fun replaceDeskMessage(s: BtrxDeskPodModel.Snapshot): String {
        val desk = s.link.deviceName?.let { "Desk Pod $it" } ?: "The current Desk Pod"
        return "$desk will be forgotten and the app will connect to the next Desk Pod it finds. The paired Drone Pod stays paired."
    }

    // Steering overlay hide/show (only the card on the stream tile).
    const val OVERLAY_TOGGLE = "Show steering overlay on the stream"
    const val OVERLAY_TOGGLE_HINT = "Off hides only the card on the stream tile; the Drone Pod stays connected, mutes and this Steering section keep working. Tap \"BTRX hidden\" on the tile to show it again."
    const val OVERLAY_HIDE = "Hide"
    const val OVERLAY_HIDDEN = "BTRX hidden"

    const val STEER_PICK = "Drone Pod is on this drone"
    const val STEER_CHOOSE_AGAIN = "Choose another drone"
    const val STEER_ROLL = "Roll correction (debug; only after the bench roll check)"
    fun steerTileLine(chosen: String?, liveCount: Int): String = when {
        chosen != null -> "Steering overlay on: $chosen"
        liveCount > 1 -> "Several streams are live: tap \"$STEER_PICK\" on the drone carrying the pod."
        else -> "Steering overlay on the live stream (one live)."
    }
    const val STEER_NONE = "No STEER yet (firmware 0x0101 or later sends one about every second once the pod is paired)."

    // Drone Pod IMU calibration (design doc section 29.3).
    const val IMU_CAL_TITLE = "Drone Pod IMU"
    const val IMU_CAL_HINT = "Calibrate once after the lid goes on, and again whenever the IMU board is refitted. Set the Drone Pod level and keep it still, then tap Calibrate. Needs Drone Pod firmware 0x0102 or later."
    const val IMU_CAL_START = "Calibrate"
    const val IMU_CAL_START_AGAIN = "Start again"
    const val IMU_CAL_STBD = "Start starboard step"
    const val IMU_CAL_STBD_RETRY = "Retry starboard step"
    const val IMU_CAL_CANCEL = "Cancel calibration"
    const val IMU_CAL_CLOSE = "Close"
    const val IMU_CAL_CHECK = "Check"
    const val IMU_CAL_CLEAR = "Clear saved calibration"
    fun imuCalCountdown(seconds: Int): String = "Measuring in $seconds s"
    const val STEER_MUTED = "A steering source is muted on the Drone Pod."
    const val STEER_MUTE_HINT = "Tap the overlay on the stream view to mute its source (for example a search team); the next strongest takes its place. The pod holds up to 8 mutes; they clear when it restarts, and a muted source unheard for 60 s comes back under a new color."

    /** Bench line for the Desk Pod screen: src, hint word, kind, age (the swatch is drawn beside it). */
    fun steerLine(v: BtrxSteering.View): String =
        "Last STEER: src ${v.srcId} (${P.devClassName(v.devClass)}), ${v.hint.word}, ${ago(v.ageMs)} (steer_seq ${v.steerSeq})"

    /** The four pairing steps with their result, for the per-step display. */
    fun steps(p: BtrxDeskPodModel.Pairing): List<Step> {
        val order = listOf(PairStep.WAIT_BUTTON, PairStep.WAIT_NOD, PairStep.POD_FOUND, PairStep.CONFIRMING)
        val current = when (p.step) {
            PairStep.FAILED -> p.failedAt
            PairStep.CANCELLED, PairStep.IDLE -> null
            PairStep.PAIRED -> null
            else -> p.step
        }
        val currentIndex = current?.let { order.indexOf(it) } ?: if (p.step == PairStep.PAIRED) order.size else -1
        val c = p.candidate
        return order.mapIndexed { i, step ->
            val status = when {
                p.step == PairStep.IDLE -> StepStatus.PENDING
                p.step == PairStep.CANCELLED -> if (c != null && i <= 1) StepStatus.DONE else StepStatus.PENDING
                i < currentIndex -> StepStatus.DONE
                i == currentIndex -> if (p.step == PairStep.FAILED) StepStatus.FAILED else StepStatus.ACTIVE
                else -> StepStatus.PENDING
            }
            when (step) {
                PairStep.WAIT_BUTTON -> if (p.repair) Step("Desk Pod opens its pairing window", status,
                    if (status == StepStatus.ACTIVE) "Asked over the link. If it does not open, tap the Desk Pod button." else "")
                    else Step("Tap the Desk Pod button", status, if (status == StepStatus.ACTIVE) "Waiting for the tap." else "")
                PairStep.WAIT_NOD -> if (p.repair && !p.repairByNod) Step("Drone Pod asks to pair", status, when (status) {
                    StepStatus.ACTIVE -> "${p.windowSecondsLeft} s left in the pairing window. PAIR_OPEN sent; the paired pod answers with PAIR_REQ (no nod)."
                    else -> ""
                }) else Step("Nod the drone pod twice", status, when (status) {
                    StepStatus.ACTIVE -> "${p.windowSecondsLeft} s left in the pairing window. Hold the pod still for 3 s, then tip it at least 25° and back, twice."
                    else -> ""
                })
                PairStep.POD_FOUND -> Step(
                    "Confirm the Drone Pod", status,
                    (c?.let { candidateLine(it) } ?: "") + if (p.podStale && status == StepStatus.ACTIVE) "\n$POD_STALE_HINT" else "",
                )
                else -> Step("Drone Pod acknowledges", status, when {
                    p.aOk != null -> "a_ok=${p.aOk} b_ok=${p.bOk}" + (p.ackFlags?.let { " flags=${P.statusFlagNames(it)}" } ?: "")
                    status == StepStatus.ACTIVE -> "PAIR_CONFIRM sent (try ${p.confirmTries}); waiting for PAIR_ACK."
                    else -> ""
                })
            }
        }
    }

    fun candidateLine(c: BtrxDeskPodModel.Candidate): String =
        "%s  dev_a %08x  dev_b %08x  imu_ok %d  RSSI %d dBm  SNR %.1f dB".format(Locale.US, BtrxClient.nodeName(c.node), c.devA, c.devB, c.imuOk, c.rssi, c.snr)

    fun ago(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return if (s < 120) "$s s ago" else "${s / 60} min ago"
    }

    private fun hex4(v: Int) = "0x%04x".format(Locale.US, v)
}
