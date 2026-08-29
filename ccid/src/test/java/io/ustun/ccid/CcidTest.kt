// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message framing against the USB Device Class Specification for Integrated
 * Circuit(s) Cards Interface Devices, revision 1.1.
 */
class CcidTest {

    /**
     * §4.1: a ten-byte header of bMessageType, dwLength, bSlot, bSeq and three
     * message-specific bytes, with the payload behind it. dwLength is
     * little-endian and counts only the payload.
     */
    @Test
    fun `the header lays out as section 4 point 1 describes`() {
        val message = Ccid.message(
            type = Ccid.PC_TO_RDR_XFR_BLOCK,
            slot = 0x03, seq = 0x2A,
            p0 = 0x11, p1 = 0x22, p2 = 0x33,
            data = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        )
        assertEquals(Ccid.HEADER + 2, message.size)
        assertEquals(Ccid.PC_TO_RDR_XFR_BLOCK, message[0].toInt() and 0xFF)
        assertEquals(0x03, message[5].toInt() and 0xFF)
        assertEquals(0x2A, message[6].toInt() and 0xFF)
        assertEquals(0x11, message[7].toInt() and 0xFF)
        assertEquals(0x22, message[8].toInt() and 0xFF)
        assertEquals(0x33, message[9].toInt() and 0xFF)
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
            message.copyOfRange(Ccid.HEADER, message.size),
        )
    }

    /** dwLength spans four bytes, so a payload over 255 has to reach the second. */
    @Test
    fun `dwLength is little-endian across all four bytes`() {
        val message = Ccid.message(Ccid.PC_TO_RDR_XFR_BLOCK, 0, 0, data = ByteArray(300))
        assertArrayEquals(
            byteArrayOf(0x2C, 0x01, 0x00, 0x00),
            message.copyOfRange(1, 5),
        )
        assertEquals(300, Ccid.declaredLength(message, message.size))
    }

    /** Table 6.1-1, the bulk-OUT command codes. */
    @Test
    fun `bulk-OUT message types match table 6 point 1-1`() {
        assertEquals(0x62, Ccid.PC_TO_RDR_ICC_POWER_ON)
        assertEquals(0x63, Ccid.PC_TO_RDR_ICC_POWER_OFF)
        assertEquals(0x65, Ccid.PC_TO_RDR_GET_SLOT_STATUS)
        assertEquals(0x6F, Ccid.PC_TO_RDR_XFR_BLOCK)
        assertEquals(0x6C, Ccid.PC_TO_RDR_GET_PARAMETERS)
        assertEquals(0x6D, Ccid.PC_TO_RDR_RESET_PARAMETERS)
        assertEquals(0x61, Ccid.PC_TO_RDR_SET_PARAMETERS)
        assertEquals(0x6B, Ccid.PC_TO_RDR_ESCAPE)
        assertEquals(0x6E, Ccid.PC_TO_RDR_ICC_CLOCK)
        assertEquals(0x6A, Ccid.PC_TO_RDR_T0_APDU)
        assertEquals(0x69, Ccid.PC_TO_RDR_SECURE)
        assertEquals(0x71, Ccid.PC_TO_RDR_MECHANICAL)
        assertEquals(0x72, Ccid.PC_TO_RDR_ABORT)
        assertEquals(0x73, Ccid.PC_TO_RDR_SET_DATA_RATE_AND_CLOCK)
    }

    /** Table 6.2-1 and Table 6.3-1. */
    @Test
    fun `bulk-IN and interrupt message types match their tables`() {
        assertEquals(0x80, Ccid.RDR_TO_PC_DATA_BLOCK)
        assertEquals(0x81, Ccid.RDR_TO_PC_SLOT_STATUS)
        assertEquals(0x82, Ccid.RDR_TO_PC_PARAMETERS)
        assertEquals(0x83, Ccid.RDR_TO_PC_ESCAPE)
        assertEquals(0x84, Ccid.RDR_TO_PC_DATA_RATE_AND_CLOCK)
        assertEquals(0x50, Ccid.RDR_TO_PC_NOTIFY_SLOT_CHANGE)
    }

    /**
     * Table 6.2-3: bmICCStatus occupies bits 1 to 0 and bmCommandStatus bits 7
     * to 6, with four reserved bits between them. Reading either from the wrong
     * end turns a working card into a failure or the reverse.
     */
    @Test
    fun `the slot status register splits as table 6 point 2-3 defines`() {
        // Card present and active, command processed without error.
        assertStatus(0x00, icc = 0, command = 0)
        // Card present and inactive, command failed.
        assertStatus(0x41, icc = 1, command = 1)
        // No card, command failed.
        assertStatus(0x42, icc = 2, command = 1)
        // Card active, time extension requested.
        assertStatus(0x80, icc = 0, command = 2)
        // The four reserved bits between them must not disturb either field.
        assertStatus(0x3C, icc = 0, command = 0)
    }

    @Test
    fun `failure, time extension and card absence read off the status register`() {
        assertTrue(response(status = 0x41).failed)
        assertFalse(response(status = 0x41).timeExtension)

        assertTrue(response(status = 0x80).timeExtension)
        assertFalse(response(status = 0x80).failed)

        assertTrue(response(status = 0x02).cardAbsent)
        assertFalse(response(status = 0x01).cardAbsent)
    }

    /**
     * Table 6.2-2 gives the slot error register three ranges beyond its named
     * codes: 'C0' to '81' user defined, '7F' to '01' the index of an incorrect
     * parameter, and '00' command not supported.
     */
    @Test
    fun `the slot error register covers all three ranges of table 6 point 2-2`() {
        assertEquals("Command aborted", Ccid.errorText(0xFF))
        assertEquals("Card is mute", Ccid.errorText(0xFE))
        assertEquals("Slot busy", Ccid.errorText(0xE0))
        assertEquals("Command not supported", Ccid.errorText(0x00))

        assertTrue(Ccid.errorText(0x05).contains("offset 5"))
        assertTrue(Ccid.errorText(0x7F).contains("offset 127"))
        assertTrue(Ccid.errorText(0x81).contains("Vendor-defined"))
        assertTrue(Ccid.errorText(0xC0).contains("Vendor-defined"))
        // '80' and the gaps are reserved, so neither range may claim them.
        assertFalse(Ccid.errorText(0x80).contains("offset"))
        assertFalse(Ccid.errorText(0x80).contains("Vendor-defined"))
    }

    /**
     * Table 5.1-1: bits 18 to 16 of dwFeatures select the exchange level, and
     * where none is present the level is character. The level decides whether
     * the host runs the block protocol itself.
     */
    @Test
    fun `the exchange level comes from bits 18 to 16 of dwFeatures`() {
        assertEquals(Ccid.ExchangeLevel.TPDU, Ccid.ExchangeLevel.fromFeatures(0x0001_0000))
        assertEquals(Ccid.ExchangeLevel.SHORT_APDU, Ccid.ExchangeLevel.fromFeatures(0x0002_0000))
        assertEquals(Ccid.ExchangeLevel.EXTENDED_APDU, Ccid.ExchangeLevel.fromFeatures(0x0004_0000))
        assertEquals(Ccid.ExchangeLevel.CHARACTER, Ccid.ExchangeLevel.fromFeatures(0x0000_0000))
        // Other feature bits must not leak into the level.
        assertEquals(
            Ccid.ExchangeLevel.TPDU,
            Ccid.ExchangeLevel.fromFeatures(0x0001_0000 or Ccid.Feature.AUTO_IFSD or Ccid.Feature.AUTO_PPS),
        )
    }

    /** Table 5.1-1, the feature bits this library acts on. */
    @Test
    fun `feature bits match table 5 point 1-1`() {
        assertEquals(0x0000_0002, Ccid.Feature.AUTO_PARAM_FROM_ATR)
        assertEquals(0x0000_0004, Ccid.Feature.AUTO_ACTIVATE_ON_INSERT)
        assertEquals(0x0000_0008, Ccid.Feature.AUTO_VOLTAGE)
        assertEquals(0x0000_0040, Ccid.Feature.AUTO_PARAM_NEGOTIATION)
        assertEquals(0x0000_0080, Ccid.Feature.AUTO_PPS)
        assertEquals(0x0000_0100, Ccid.Feature.CLOCK_STOP)
        assertEquals(0x0000_0400, Ccid.Feature.AUTO_IFSD)
    }

    /** dwMechanical, Table 5.1-1: the motorised functions of §6.1.12. */
    @Test
    fun `mechanical capability bits match table 5 point 1-1`() {
        assertEquals(0x0000_0001, Ccid.Mechanical.ACCEPT)
        assertEquals(0x0000_0002, Ccid.Mechanical.EJECT)
        assertEquals(0x0000_0004, Ccid.Mechanical.CAPTURE)
        assertEquals(0x0000_0008, Ccid.Mechanical.LOCK_UNLOCK)
    }

    /** §6.1.12: bFunction 01h to 05h, each paired with the bit that permits it. */
    @Test
    fun `mechanical functions carry the code and capability of section 6 point 1 point 12`() {
        val expected = mapOf(
            UsbCcidTransport.MechanicalFunction.ACCEPT to (0x01 to Ccid.Mechanical.ACCEPT),
            UsbCcidTransport.MechanicalFunction.EJECT to (0x02 to Ccid.Mechanical.EJECT),
            UsbCcidTransport.MechanicalFunction.CAPTURE to (0x03 to Ccid.Mechanical.CAPTURE),
            UsbCcidTransport.MechanicalFunction.LOCK to (0x04 to Ccid.Mechanical.LOCK_UNLOCK),
            UsbCcidTransport.MechanicalFunction.UNLOCK to (0x05 to Ccid.Mechanical.LOCK_UNLOCK),
        )
        for ((function, spec) in expected) {
            assertEquals("$function code", spec.first, function.code)
            assertEquals("$function capability", spec.second, function.capability)
        }
    }

    /** §6.2.1: bChainParameter on a data block at extended-APDU level. */
    @Test
    fun `chaining values match section 6 point 2 point 1`() {
        assertEquals(0x00, Ccid.Chain.COMPLETE)
        assertEquals(0x01, Ccid.Chain.BEGINS)
        assertEquals(0x02, Ccid.Chain.CONTINUES_AND_ENDS)
        assertEquals(0x03, Ccid.Chain.CONTINUES)
        assertEquals(0x10, Ccid.Chain.EXPECTS_MORE_COMMAND)
    }

    @Test
    fun `a buffer shorter than a header parses to nothing`() {
        assertNull(Ccid.parseHeader(ByteArray(Ccid.HEADER - 1), Ccid.HEADER - 1))
        assertEquals(0, Ccid.declaredLength(ByteArray(4), 4))
    }

    /**
     * A reader may declare more payload than it delivered. Trusting dwLength
     * over the bytes actually read would copy past the end of the buffer.
     */
    @Test
    fun `a payload shorter than dwLength claims is not over-read`() {
        val buffer = ByteArray(Ccid.HEADER + 2)
        buffer[0] = Ccid.RDR_TO_PC_DATA_BLOCK.toByte()
        buffer[1] = 0xFF.toByte()               // dwLength says 255 bytes follow
        val parsed = Ccid.parseHeader(buffer, buffer.size)!!
        assertEquals(2, parsed.data.size)
    }

    private fun assertStatus(status: Int, icc: Int, command: Int) {
        val parsed = response(status)
        assertEquals("bmICCStatus of 0x%02X".format(status), icc, parsed.iccStatus)
        assertEquals("bmCommandStatus of 0x%02X".format(status), command, parsed.commandStatus)
    }

    private fun response(status: Int): Ccid.Response {
        val buffer = ByteArray(Ccid.HEADER)
        buffer[0] = Ccid.RDR_TO_PC_SLOT_STATUS.toByte()
        buffer[7] = status.toByte()
        return Ccid.parseHeader(buffer, buffer.size)!!
    }
}
