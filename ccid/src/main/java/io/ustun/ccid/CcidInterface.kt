// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * Finding the CCID interface on a USB device and reading what it can do.
 *
 * Everything a transport needs to know about a reader before it opens comes
 * from here: which endpoints to use, and the capabilities the class descriptor
 * declares.
 */
internal object CcidInterface {

    /** USB device class 0Bh, Smart Card. */
    const val CLASS_SMART_CARD = 11

    data class Found(
        val iface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
        /** Optional: CCID §6.3 makes slot-change notification optional. */
        val interruptIn: UsbEndpoint?,
        val level: Ccid.ExchangeLevel,
        val features: Int,
        val mechanical: Int,
        val clockCount: Int,
        val dataRateCount: Int,
        val maxMessageLength: Int,
        val slotCount: Int,
    )

    fun find(device: UsbDevice, connection: UsbDeviceConnection): Found? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != CLASS_SMART_CARD) continue

            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var interruptIn: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK ->
                        if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = bulkIn ?: ep
                        else bulkOut = bulkOut ?: ep

                    UsbConstants.USB_ENDPOINT_XFER_INT ->
                        if (ep.direction == UsbConstants.USB_DIR_IN) interruptIn = interruptIn ?: ep
                }
            }
            if (bulkIn == null || bulkOut == null) continue

            val caps = capabilities(connection, iface.id)
            return Found(
                iface, bulkIn, bulkOut, interruptIn,
                caps.level, caps.features, caps.mechanical,
                caps.clockCount, caps.dataRateCount,
                caps.maxMessageLength, caps.slotCount,
            )
        }
        return null
    }

    private data class Capabilities(
        val level: Ccid.ExchangeLevel,
        val features: Int,
        val mechanical: Int,
        val clockCount: Int,
        val dataRateCount: Int,
        val maxMessageLength: Int,
        val slotCount: Int,
    )

    /**
     * Walk the raw descriptors for the CCID class descriptor belonging to this
     * interface. Android exposes no typed accessor for a class-specific
     * descriptor.
     *
     * USB 2.0 Table 9-5: INTERFACE is descriptor type 4. Table 9-12: bLength at
     * offset 0, bDescriptorType at 1, bInterfaceNumber at 2. CCID Table 5.1-1:
     * the class descriptor is type 21h, dwFeatures at offset 40 and
     * dwMechanical at 36, dwMaxCCIDMessageLength at 44, all little-endian.
     */
    private fun capabilities(connection: UsbDeviceConnection, interfaceId: Int): Capabilities {
        val fallback = Capabilities(Ccid.ExchangeLevel.SHORT_APDU, 0, 0, 0, 0, 0, 1)
        val raw = connection.rawDescriptors ?: run {
            Log.w("ccid", "no raw descriptors for interface $interfaceId; using fallback $fallback")
            return fallback
        }
        var i = 0
        var inTarget = false
        while (i + 1 < raw.size) {
            val length = raw[i].toInt() and 0xFF
            val type = raw[i + 1].toInt() and 0xFF
            if (length == 0) break

            if (type == 0x04 && i + 2 < raw.size) {
                inTarget = (raw[i + 2].toInt() and 0xFF) == interfaceId
            }

            if (type == 0x21 && inTarget && length >= 44 && i + 43 < raw.size) {
                // Table 5.1-1: bNumClockSupported at 18, bNumDataRatesSupported
                // at 27, dwMechanical at 36, dwFeatures at 40.
                val clocks = raw[i + 18].toInt() and 0xFF
                val rates = raw[i + 27].toInt() and 0xFF
                val mechanical = Ccid.le32(raw, i + 36)
                val features = Ccid.le32(raw, i + 40)
                val maxLen = if (length >= 48 && i + 47 < raw.size) Ccid.le32(raw, i + 44) else 0
                // Table 5.1-1: bMaxSlotIndex at offset 4; slots are one more.
                val slots = (raw[i + 4].toInt() and 0xFF) + 1
                if (Integer.bitCount(features and 0x0007_0000) > 1) {
                    Log.w("ccid", "descriptor sets multiple exchange levels: dwFeatures=0x${"%08x".format(features)}")
                }
                Log.d(
                    "ccid",
                    "descriptor dwFeatures=0x${"%08x".format(features)} " +
                        "level=${Ccid.ExchangeLevel.fromFeatures(features)} maxMessage=$maxLen slots=$slots",
                )
                return Capabilities(
                    Ccid.ExchangeLevel.fromFeatures(features), features, mechanical,
                    clocks, rates, maxLen, slots,
                )
            }
            i += length
        }
        Log.w("ccid", "no CCID functional descriptor (0x21) for interface $interfaceId; using fallback $fallback")
        return fallback
    }
}
