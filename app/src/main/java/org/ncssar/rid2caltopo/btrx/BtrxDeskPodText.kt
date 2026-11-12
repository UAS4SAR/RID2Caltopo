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
    /** Main menu item and screen title (the btrx receiver system; the pods keep their names inside). */
    const val TITLE = "BTRX"
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
        return "$kind: ${phaseName(s.link.phase)}"
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
        return "Firmware build: %s (%s)".format(Locale.US, hex4(r.buildHash), if (s.roleSupported) "supported" else "not in this app's supported list")
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
        }
        val last = s.lastDetectMs?.let { ", last ${ago(s.nowMs - it)}" } ?: ""
        add("Detections: ${s.detectsLastMinute} in the last minute$last")
        if (s.otherPodNodes > 0) add("Other Drone Pods heard on the channel: ${s.otherPodNodes}")
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
                PairStep.WAIT_BUTTON -> Step("Tap the Desk Pod button", status, if (status == StepStatus.ACTIVE) "Waiting for the tap." else "")
                PairStep.WAIT_NOD -> Step("Nod the drone pod twice", status, when (status) {
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
