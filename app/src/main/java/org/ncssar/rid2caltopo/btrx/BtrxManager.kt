package org.ncssar.rid2caltopo.btrx

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import org.ncssar.rid2caltopo.data.CaltopoClient

/**
 * Finds a btrx Desk Pod on USB and keeps one [BtrxUsbLink] open to it. Inactive
 * unless a board with the Desk Pod's USB IDs is attached: install() only
 * registers for attach/detach/permission broadcasts and checks the attached
 * devices once on a worker. Android only (iPad support needs the Bluetooth link,
 * not in btrx version 1).
 */
object BtrxManager {
    private const val TAG = "Btrx"
    const val VENDOR_ID = 0x239A
    const val PRODUCT_ID = 0x8029
    private const val ACTION_USB_PERMISSION = "org.ncssar.rid2caltopo.btrx.USB_PERMISSION"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "btrx-usb").apply { isDaemon = true } }
    private lateinit var appContext: Context
    @Volatile private var installed = false
    @Volatile private var link: BtrxUsbLink? = null
    /** Boards that answered with a role other than Desk Pod, ignored until unplugged. */
    private val rejected = mutableSetOf<String>()
    private val requested = mutableSetOf<String>()

    val logger = object : BtrxClient.Logger {
        override fun info(message: String) = CaltopoClient.CTInfo(TAG, message)
        override fun warn(message: String) = CaltopoClient.CTWarn(TAG, message)
    }

    fun isDeskPodCandidate(d: UsbDevice?): Boolean = d != null && d.vendorId == VENDOR_ID && d.productId == PRODUCT_ID

    /** Current link, for later milestones (status card, pairing). */
    fun currentLink(): BtrxUsbLink? = link

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        appContext = context.applicationContext
        if (!appContext.packageManager.hasSystemFeature("android.hardware.usb.host")) return
        installed = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        worker.execute { scanAttached() }
    }

    /** Called by the USB attach activity, which Android opens when a Desk Pod is plugged in. */
    fun onDeviceAttached(context: Context, device: UsbDevice?) {
        install(context)
        if (installed && isDeskPodCandidate(device)) worker.execute { connect(device!!) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (!isDeskPodCandidate(device)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> worker.execute { connect(device) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> worker.execute { detached(device) }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    worker.execute {
                        synchronized(this@BtrxManager) { requested.remove(device.deviceName) }
                        if (granted) connect(device) else logger.warn("USB permission denied for ${BtrxUsbLink.describe(device)}")
                    }
                }
            }
        }
    }

    private fun usbManager(): UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun scanAttached() {
        usbManager().deviceList.values.filter(::isDeskPodCandidate).forEach(::connect)
    }

    private fun connect(device: UsbDevice) {
        synchronized(this) {
            if (link != null || device.deviceName in rejected) return
        }
        val usb = usbManager()
        if (!usb.hasPermission(device)) {
            synchronized(this) { if (!requested.add(device.deviceName)) return }
            logger.info("requesting USB permission for ${BtrxUsbLink.describe(device)}")
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(device, PendingIntent.getBroadcast(appContext, 0, intent, flags))
            return
        }
        val newLink = BtrxUsbLink(usb, device, logger) { closedLink, reason ->
            synchronized(this) {
                if (link === closedLink) link = null
                if (reason.startsWith("not a Desk Pod")) rejected += closedLink.device.deviceName
            }
        }
        synchronized(this) { link = newLink }
        try {
            newLink.open()
        } catch (e: Exception) {
            logger.warn("USB open failed for ${BtrxUsbLink.describe(device)}: ${e.message}")
            newLink.close("open failed")
        }
    }

    private fun detached(device: UsbDevice) {
        synchronized(this) {
            rejected.remove(device.deviceName)
            requested.remove(device.deviceName)
        }
        val current = link
        if (current != null && current.device.deviceName == device.deviceName) current.close("detached")
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    internal fun deviceFrom(intent: Intent): UsbDevice? = intent.usbDevice()
}
