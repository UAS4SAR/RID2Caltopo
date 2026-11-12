package org.ncssar.rid2caltopo.btrx

/**
 * Meshtastic Bluetooth phone API (public Meshtastic client-API docs,
 * meshtastic.org/docs/development/device/client-api). Each ToRadio write and each
 * FromRadio read carries one bare protobuf, with no 0x94 0xC3 serial framing.
 * ToRadio is written with response (the firmware does not offer write-without-response).
 * After a FromNum notification, FromRadio is read until a read comes back empty.
 */
object MeshtasticBle {
    const val SERVICE_UUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"
    const val TO_RADIO_UUID = "f75c76d2-129e-4dad-a1dd-7866124401e7"
    const val FROM_RADIO_UUID = "2c55e69e-4993-11ed-b878-0242ac120002"
    const val FROM_NUM_UUID = "ed9da18c-a800-4f66-a670-aa7547e34453"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val MTU = 512
    /** Desk Pods advertise as Meshtastic_xxxx (the last 4 hex digits of the node number). */
    const val NAME_PREFIX = "Meshtastic"
    /** Printed-PIN placeholder until each unit gets its own (btrx provisioning, planned). */
    const val DEFAULT_PIN = "123456"
}

/**
 * One GATT operation at a time. Android GATT (and CoreBluetooth on the iPad) allow
 * only one outstanding read or write; on the bench two overlapping writes (a
 * heartbeat plus a command) left one stuck forever and stopped all reads while
 * FromNum notifications kept arriving. Every write, read, descriptor write, MTU
 * request and service discovery goes through this queue and the next one starts
 * only when the platform callback for the current one has arrived ([complete]).
 *
 * Not thread-safe: the link confines it to its own worker thread. Pure Kotlin so
 * ordering is unit tested on the JVM; the iPad has the same queue in Swift.
 */
class BtrxGattQueue<Op : Any>(
    /** Starts [op] on the platform; false if the platform refused to start it. */
    private val start: (Op) -> Boolean,
    /** The platform refused to start [op]; the queue stops and the link ends the session. */
    private val onStartFailed: (Op) -> Unit,
) {
    private val pending = ArrayDeque<Op>()
    private var closed = false

    /** The operation waiting for its platform callback, or null when idle. */
    var inFlight: Op? = null
        private set

    val size: Int get() = pending.size + (if (inFlight != null) 1 else 0)

    fun enqueue(op: Op) {
        if (closed) return
        pending.addLast(op)
        pump()
    }

    /**
     * The platform callback for the in-flight operation arrived. Returns that
     * operation (null if nothing was in flight, e.g. a late callback after [clear]).
     */
    fun complete(): Op? {
        val done = inFlight ?: return null
        inFlight = null
        pump()
        return done
    }

    /** Drops everything (session ended). Later [enqueue] calls are ignored. */
    fun clear() {
        closed = true
        pending.clear()
        inFlight = null
    }

    private fun pump() {
        while (!closed && inFlight == null && pending.isNotEmpty()) {
            val op = pending.removeFirst()
            inFlight = op
            if (!start(op)) {
                // The session is broken: stall until the link clears it, never run later ops out of order.
                inFlight = null
                closed = true
                pending.clear()
                onStartFailed(op)
                return
            }
        }
    }
}

/**
 * FromRadio drain: after want_config_id and after each FromNum notification, read
 * FromRadio until it comes back empty. A notification that arrives while a drain is
 * running schedules one more pass after the empty read, so nothing queued between
 * the last read and the notification is missed. Only one read is ever requested at
 * a time; [requestRead] enqueues it on the [BtrxGattQueue].
 */
class BtrxFromRadioDrain(private val requestRead: () -> Unit) {
    var draining = false
        private set
    private var again = false
    var reads = 0L
        private set

    /** FromNum notified (or the handshake was just written): make sure a drain runs. */
    fun kick() {
        if (draining) { again = true; return }
        draining = true
        requestRead()
    }

    /** A FromRadio read finished. Returns true when [value] is a message to deliver. */
    fun onRead(value: ByteArray): Boolean {
        reads++
        if (value.isNotEmpty()) {
            requestRead()
            return true
        }
        if (again) {
            again = false
            requestRead()
        } else {
            draining = false
        }
        return false
    }

    fun reset() {
        draining = false
        again = false
    }
}
