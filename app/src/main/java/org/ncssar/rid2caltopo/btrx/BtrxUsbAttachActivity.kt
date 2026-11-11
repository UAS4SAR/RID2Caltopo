package org.ncssar.rid2caltopo.btrx

import android.app.Activity
import android.os.Bundle

/**
 * Invisible target for USB_DEVICE_ATTACHED with the Desk Pod's USB IDs
 * (res/xml/btrx_usb_device_filter.xml). Opening the app this way gives it USB
 * permission for that board without a separate prompt. It hands the device to
 * [BtrxManager] and closes; it does not open the main screen.
 */
class BtrxUsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BtrxManager.onDeviceAttached(this, BtrxManager.deviceFrom(intent))
        finish()
    }
}
