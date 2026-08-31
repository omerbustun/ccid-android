// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reader discovery and access.
 *
 * A USB device is unreachable until the user grants access to it, per device,
 * through a system dialog. Attaching a reader while the application is in the
 * foreground grants access implicitly; a reader already attached at launch must
 * be requested with [requestPermission].
 *
 * Discovery opens no card session, so it cannot contend with an operation
 * holding one.
 */
class CcidReaders(private val context: Context) {

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Readers currently attached, whether or not access has been granted. */
    fun attached(): List<UsbDevice> = usb.deviceList.values.filter { it.isCcid() }

    fun hasPermission(device: UsbDevice): Boolean = usb.hasPermission(device)

    /**
     * Attach and detach events, plus one emission on collection so a collector
     * starting after a reader was plugged in still sees it.
     */
    fun events(): Flow<List<UsbDevice>> = channelFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(attached())
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        trySend(attached())
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * Request access, suspending until the user answers. Returns false if they
     * decline or the dialog times out.
     *
     * The grant is broadcast before the device accepts transfers; [open] waits
     * for the reader to respond before returning.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usb.hasPermission(device)) return true
        return withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_PERMISSION) return
                        // Another reader's answer, from a request running alongside.
                        if (answeredDevice(intent)?.deviceName != device.deviceName) return
                        runCatching { context?.unregisterReceiver(this) }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (cont.isActive) cont.resume(granted)
                    }
                }
                val filter = IntentFilter(ACTION_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

                // MUTABLE is required: the system writes the device and the grant
                // into this intent before broadcasting it back.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                // One PendingIntent per device: a shared one would have its extras
                // overwritten by a request for another reader.
                usb.requestPermission(
                    device,
                    PendingIntent.getBroadcast(
                        context,
                        device.deviceId,
                        Intent(ACTION_PERMISSION).setPackage(context.packageName),
                        flags,
                    ),
                )
            }
        } ?: false
    }

    private fun answeredDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    /**
     * Open a session on a reader that access has been granted for.
     *
     * The caller owns the result and must [UsbCcidTransport.close] it.
     *
     * One transport per reader; several readers may be driven concurrently.
     * [slot] selects among a multi-slot reader's slots.
     *
     * @throws CcidException if access has not been granted, the device is not a
     * CCID reader, the reader is already open, or [slot] does not exist.
     */
    @JvmOverloads
    fun open(device: UsbDevice, slot: Int = 0): UsbCcidTransport {
        if (!usb.hasPermission(device)) {
            throw CcidException("No permission for this reader", CcidException.Reason.NO_PERMISSION)
        }
        val connection = usb.openDevice(device)
            ?: throw CcidException("Could not open the reader", CcidException.Reason.NO_READER)

        val found = CcidInterface.find(device, connection)
        if (found == null) {
            connection.close()
            throw CcidException("Not a CCID reader", CcidException.Reason.UNSUPPORTED_READER)
        }
        if (found.level == Ccid.ExchangeLevel.CHARACTER) {
            connection.close()
            throw CcidException(
                "Character-level readers are not supported",
                CcidException.Reason.UNSUPPORTED_READER,
            )
        }
        if (slot !in 0 until found.slotCount) {
            connection.close()
            throw CcidException(
                "Slot $slot does not exist; this reader has ${found.slotCount}",
                CcidException.Reason.NO_READER,
            )
        }
        val transport = UsbCcidTransport(
            device = device,
            connection = connection,
            iface = found.iface,
            bulkIn = found.bulkIn,
            bulkOut = found.bulkOut,
            exchangeLevel = found.level,
            features = found.features,
            mechanicalFunctions = found.mechanical,
            clockCount = found.clockCount,
            dataRateCount = found.dataRateCount,
            maxMessageLength = found.maxMessageLength,
            interruptIn = found.interruptIn,
            slotCount = found.slotCount,
            slot = slot,
        )
        if (!OpenReaders.claim(device.deviceName, transport)) {
            connection.close()
            throw CcidException("This reader is already in use", CcidException.Reason.READER_BUSY)
        }
        return transport
    }

    private companion object {
        /** Package-scoped so only this application's receiver is woken. */
        const val ACTION_PERMISSION = "io.ustun.ccid.USB_PERMISSION"

        /** How long to wait for the permission dialog before giving up. */
        const val PERMISSION_TIMEOUT_MS = 60_000L
    }
}

/**
 * The transport holding each reader, keyed by device name.
 *
 * Process-wide, so the guard spans every part of an application that opens one:
 * two connections to a reader interleave on its bulk endpoints and each
 * consumes the other's replies.
 */
internal object OpenReaders {

    private val live = ConcurrentHashMap<String, UsbCcidTransport>()

    /** Register [transport] for [key] unless a live transport already holds it. */
    fun claim(key: String, transport: UsbCcidTransport): Boolean {
        while (true) {
            val held = live.putIfAbsent(key, transport) ?: return true
            if (!held.closed) return false
            // Held by a transport that has since been closed without release.
            if (live.replace(key, held, transport)) return true
        }
    }

    fun release(key: String, transport: UsbCcidTransport) {
        live.remove(key, transport)
    }
}

/**
 * Whether this device exposes the CCID class. Some readers declare it only on
 * the interface and leave the device class at zero, so both are checked.
 */
fun UsbDevice.isCcid(): Boolean {
    if (deviceClass == CcidInterface.CLASS_SMART_CARD) return true
    for (i in 0 until interfaceCount) {
        if (getInterface(i).interfaceClass == CcidInterface.CLASS_SMART_CARD) return true
    }
    return false
}
