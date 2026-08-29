// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

/**
 * USB CCID message framing, per the USB Device Class Specification for
 * Integrated Circuit(s) Cards Interface Devices, revision 1.1.
 *
 * Pure encoding and decoding; the transport supplies the USB layer.
 */
internal object Ccid {

    /** Every CCID message begins with a 10-byte header (§4.1, §4.2). */
    const val HEADER = 10

    // Bulk-OUT, host to reader (Table 6.1-1).
    const val PC_TO_RDR_ICC_POWER_ON = 0x62
    const val PC_TO_RDR_ICC_POWER_OFF = 0x63
    const val PC_TO_RDR_GET_SLOT_STATUS = 0x65
    const val PC_TO_RDR_XFR_BLOCK = 0x6F
    const val PC_TO_RDR_GET_PARAMETERS = 0x6C
    const val PC_TO_RDR_RESET_PARAMETERS = 0x6D
    const val PC_TO_RDR_SET_PARAMETERS = 0x61
    const val PC_TO_RDR_ESCAPE = 0x6B
    const val PC_TO_RDR_ICC_CLOCK = 0x6E
    const val PC_TO_RDR_T0APDU = 0x6A
    const val PC_TO_RDR_SECURE = 0x69
    const val PC_TO_RDR_MECHANICAL = 0x71
    const val PC_TO_RDR_ABORT = 0x72
    const val PC_TO_RDR_SET_DATA_RATE_AND_CLOCK = 0x73

    // Bulk-IN, reader to host (Table 6.2-1).
    const val RDR_TO_PC_DATA_BLOCK = 0x80
    const val RDR_TO_PC_SLOT_STATUS = 0x81
    const val RDR_TO_PC_PARAMETERS = 0x82
    const val RDR_TO_PC_ESCAPE = 0x83
    const val RDR_TO_PC_DATA_RATE_AND_CLOCK = 0x84

    // Interrupt endpoint, Table 6.3-1.
    const val RDR_TO_PC_NOTIFY_SLOT_CHANGE = 0x50
    const val RDR_TO_PC_HARDWARE_ERROR = 0x51

    /**
     * The three class-specific requests on the control pipe, Table 5.3-1.
     *
     * These are not bulk messages. §4.1 pairs [ABORT] with the bulk
     * `PC_to_RDR_Abort`, and the two `GET_` requests are the only way to learn
     * which clock frequencies and data rates a reader will accept.
     */
    object ControlRequest {
        const val ABORT = 0x01
        const val GET_CLOCK_FREQUENCIES = 0x02
        const val GET_DATA_RATES = 0x03

        /** Table 5.3-1, `00100001B`: host to device, class request, interface recipient. */
        const val TYPE_OUT = 0x21

        /** Table 5.3-1, `10100001B`: device to host, class request, interface recipient. */
        const val TYPE_IN = 0xA1

        /** §5.3.1: `wValue` carries bSlot in the low byte and bSeq in the high. */
        fun abortValue(slot: Int, seq: Int): Int = ((seq and 0xFF) shl 8) or (slot and 0xFF)
    }

    /** `dwFeatures` bits, Table 5.1-1, in the order that table lists them. */
    object Feature {
        /** The CCID sets protocol parameters from the ATR itself. */
        const val AUTO_PARAM_FROM_ATR = 0x0000_0002
        const val AUTO_ACTIVATE_ON_INSERT = 0x0000_0004
        const val AUTO_VOLTAGE = 0x0000_0008

        /** The CCID negotiates protocol parameters; the host must not set them. */
        const val AUTO_PARAM_NEGOTIATION = 0x0000_0040

        /** The CCID performs PPS itself according to the active parameters. */
        const val AUTO_PPS = 0x0000_0080

        /** The CCID can put the card into clock stop mode. */
        const val CLOCK_STOP = 0x0000_0100

        /** The CCID performs the IFSD exchange itself, as the first exchange. */
        const val AUTO_IFSD = 0x0000_0400
    }

    /**
     * How much of an APDU the reader assembles, from `dwFeatures` bits 16 to 18
     * (Table 5.1-1). Decides whether [UsbCcidTransport] passes APDUs through or
     * runs the T=1 block layer itself.
     */
    enum class ExchangeLevel {
        SHORT_APDU,
        EXTENDED_APDU,
        TPDU,
        CHARACTER;

        companion object {
            fun fromFeatures(dwFeatures: Int): ExchangeLevel = when (dwFeatures and 0x0007_0000) {
                0x0002_0000 -> SHORT_APDU
                0x0004_0000 -> EXTENDED_APDU
                0x0001_0000 -> TPDU
                else -> CHARACTER
            }
        }
    }

    /** `dwMechanical` bits, Table 5.1-1: the motorised functions a reader has. */
    object Mechanical {
        const val ACCEPT = 0x0000_0001
        const val EJECT = 0x0000_0002
        const val CAPTURE = 0x0000_0004
        const val LOCK_UNLOCK = 0x0000_0008
    }

    /** `bClockCommand` on an IccClock message (§6.1.9). */
    object Clock {
        const val RESTART = 0x00
        const val STOP = 0x01
    }

    /** `bmChanges` on a T0APDU message (§6.1.10). */
    object T0Apdu {
        const val GET_RESPONSE_CLASS = 0x01
        const val ENVELOPE_CLASS = 0x02

        /** §6.1.10: this value makes the reader echo the APDU's own class byte. */
        const val ECHO_CLASS = 0xFF
    }

    /** `bChainParameter` on a data block, extended-APDU level only (§6.2.1). */
    object Chain {
        const val COMPLETE = 0x00
        const val BEGINS = 0x01
        const val CONTINUES_AND_ENDS = 0x02
        const val CONTINUES = 0x03
        const val EXPECTS_MORE_COMMAND = 0x10
    }

    /** `wLevelParameter` on an XfrBlock, extended-APDU level only (§6.1.4). */
    object Level {
        const val COMPLETE = 0x0000
        const val EXPECT_MORE_RESPONSE = 0x0010
    }

    /** Table 6.2-1: the bulk-IN message that answers each bulk-OUT command. */
    fun expectedReply(command: Int): Int = when (command) {
        PC_TO_RDR_ICC_POWER_ON, PC_TO_RDR_XFR_BLOCK, PC_TO_RDR_SECURE -> RDR_TO_PC_DATA_BLOCK
        PC_TO_RDR_GET_PARAMETERS,
        PC_TO_RDR_SET_PARAMETERS,
        PC_TO_RDR_RESET_PARAMETERS,
        -> RDR_TO_PC_PARAMETERS

        PC_TO_RDR_ESCAPE -> RDR_TO_PC_ESCAPE
        PC_TO_RDR_SET_DATA_RATE_AND_CLOCK -> RDR_TO_PC_DATA_RATE_AND_CLOCK
        else -> RDR_TO_PC_SLOT_STATUS
    }

    /**
     * Build a bulk-OUT message. `p0`/`p1`/`p2` are the three message-specific
     * bytes at offsets 7 to 9.
     */
    fun message(
        type: Int,
        slot: Int,
        seq: Int,
        p0: Int = 0,
        p1: Int = 0,
        p2: Int = 0,
        data: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArray(HEADER + data.size)
        out[0] = type.toByte()
        out[1] = (data.size and 0xFF).toByte()
        out[2] = (data.size ushr 8 and 0xFF).toByte()
        out[3] = (data.size ushr 16 and 0xFF).toByte()
        out[4] = (data.size ushr 24 and 0xFF).toByte()
        out[5] = slot.toByte()
        out[6] = seq.toByte()
        out[7] = p0.toByte()
        out[8] = p1.toByte()
        out[9] = p2.toByte()
        data.copyInto(out, HEADER)
        return out
    }

    /** A parsed bulk-IN message. */
    data class Response(
        val type: Int,
        val slot: Int,
        val seq: Int,
        val status: Int,
        val error: Int,
        /**
         * The message-specific byte at offset 9: `bChainParameter` on a data
         * block, `bProtocolNum` on parameters, `bClockStatus` on a slot status.
         */
        val messageSpecific: Int,
        val data: ByteArray,
    ) {
        /** `bmCommandStatus`, bits 6 and 7 of the slot status register (Table 6.2-3). */
        val commandStatus: Int get() = (status ushr 6) and 0x03

        /** `bmICCStatus`, bits 0 and 1: 0 present and active, 1 present and inactive, 2 absent. */
        val iccStatus: Int get() = status and 0x03

        val failed: Boolean get() = commandStatus == 1

        /**
         * A time extension, not an answer: another bulk-IN for the same `bSeq`
         * follows, and `bError` carries the BWT multiplier (§6.2.6).
         */
        val timeExtension: Boolean get() = commandStatus == 2

        val cardAbsent: Boolean get() = iccStatus == 2

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Response) return false
            return type == other.type && slot == other.slot && seq == other.seq &&
                status == other.status && error == other.error &&
                messageSpecific == other.messageSpecific && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = type
            result = 31 * result + slot
            result = 31 * result + seq
            result = 31 * result + status
            result = 31 * result + error
            result = 31 * result + messageSpecific
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /** Read a bulk-IN header. Null when the buffer is shorter than one. */
    fun parseHeader(buf: ByteArray, length: Int): Response? {
        if (length < HEADER) return null
        val declared = declaredLength(buf, length)
        val available = (length - HEADER).coerceAtLeast(0)
        val take = minOf(declared.coerceAtLeast(0), available)
        return Response(
            type = buf[0].toInt() and 0xFF,
            slot = buf[5].toInt() and 0xFF,
            seq = buf[6].toInt() and 0xFF,
            status = buf[7].toInt() and 0xFF,
            error = buf[8].toInt() and 0xFF,
            messageSpecific = buf[9].toInt() and 0xFF,
            data = buf.copyOfRange(HEADER, HEADER + take),
        )
    }

    /** Read a little-endian double word, the only integer width CCID uses. */
    fun le32(from: ByteArray, at: Int): Int =
        (from[at].toInt() and 0xFF) or
            ((from[at + 1].toInt() and 0xFF) shl 8) or
            ((from[at + 2].toInt() and 0xFF) shl 16) or
            ((from[at + 3].toInt() and 0xFF) shl 24)

    /** Write a little-endian double word. */
    fun putLe32(into: ByteArray, at: Int, value: Int) {
        into[at] = (value and 0xFF).toByte()
        into[at + 1] = (value ushr 8 and 0xFF).toByte()
        into[at + 2] = (value ushr 16 and 0xFF).toByte()
        into[at + 3] = (value ushr 24 and 0xFF).toByte()
    }

    /** `dwLength`, the payload size the header declares. */
    fun declaredLength(buf: ByteArray, length: Int): Int =
        if (length < HEADER) 0 else le32(buf, 1)

    /**
     * The slot error register, Table 6.2-2. A signed byte with three ranges:
     * named codes, `7Fh..01h` the index of an incorrect field in the command,
     * and `C0h..81h` vendor-defined. The last two are reported by number
     * because neither can be named from here.
     */
    fun errorText(bError: Int): String = when (bError) {
        0xFF -> "Command aborted"
        0xFE -> "Card is mute"
        0xFD -> "Parity error talking to the card"
        0xFC -> "Overrun talking to the card"
        0xFB -> "Reader hardware error"
        0xF8 -> "Bad ATR TS"
        0xF7 -> "Bad ATR TCK"
        0xF6 -> "Card protocol not supported"
        0xF5 -> "Card class not supported"
        0xF4 -> "Procedure byte conflict"
        0xF3 -> "Protocol deactivated"
        0xF2 -> "Busy with an automatic sequence"
        0xF0 -> "PIN entry timed out"
        0xEF -> "PIN entry cancelled"
        0xE0 -> "Slot busy"
        0x00 -> "Command not supported"
        in 0x01..0x7F -> "Incorrect field at offset $bError in the command"
        in 0x81..0xC0 -> "Vendor-defined reader error 0x${bError.toString(16).uppercase()}"
        else -> "Reader error 0x${bError.toString(16).uppercase()}"
    }
}
