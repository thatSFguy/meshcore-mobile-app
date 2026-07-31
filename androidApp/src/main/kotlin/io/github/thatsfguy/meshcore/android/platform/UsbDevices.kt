package io.github.thatsfguy.meshcore.android.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.github.thatsfguy.meshcore.platform.usbserial.UsbSerialProber

/**
 * Attached-USB-device listing + the runtime permission handshake for
 * the Add-node picker. Only devices [UsbSerialProber] can drive
 * (CDC-ACM, CP210x) are listed.
 */
object UsbDevices {

    private const val ACTION_USB_PERMISSION = "io.github.thatsfguy.meshcore.USB_PERMISSION"

    data class AttachedDevice(
        val device: UsbDevice,
        val driver: String,
        val hasPermission: Boolean,
    )

    fun attached(context: Context): List<AttachedDevice> {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usb.deviceList.values.mapNotNull { device ->
            val driver = UsbSerialProber.driverName(device) ?: return@mapNotNull null
            AttachedDevice(device, driver, usb.hasPermission(device))
        }
    }

    /** Fire the system permission dialog; [onResult] runs once. */
    fun requestPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usb.hasPermission(device)) {
            onResult(true)
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                runCatching { ctx.unregisterReceiver(this) }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        usb.requestPermission(device, pi)
    }
}
