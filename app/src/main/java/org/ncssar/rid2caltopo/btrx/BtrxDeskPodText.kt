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
    const val TITLE = "Desk Pod"
    const val USB_PAUSED = "Desk Pod is attached by USB; Bluetooth paused"
    const val BLUETOOTH_TOGGLE = "Connect over Bluetooth"
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
                PairStep.POD_FOUND -> Step("Confirm the Drone Pod", status, c?.let { candidateLine(it) } ?: "")
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
