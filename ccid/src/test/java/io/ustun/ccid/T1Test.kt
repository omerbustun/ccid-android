// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T=1 against ISO/IEC 7816-3:2006 clause 11 and ISO/IEC 13239 clause 4.2.5.2.
 *
 * Every expected value here is one a standard states outright. A test that
 * recomputes an answer the way the implementation does cannot tell a correct
 * implementation from a consistent misreading of the specification.
 */
class T1Test {

    // ── Epilogue: §11.3.4 ───────────────────────────────────────────────────

    /**
     * §11.3.4: "exclusive-oring all the bytes of the block from NAD to LRC
     * inclusive shall give '00'."
     *
     * The information field deliberately does not end in '00'. XOR cannot see a
     * trailing zero byte, so a block ending in one verifies under an LRC that
     * never included it, and the check passes over a broken implementation.
     */
    @Test
    fun `xor from NAD to LRC inclusive gives 00`() {
        val block = T1.iBlock(sequence = 0, more = false, info = byteArrayOf(0x11, 0x22, 0x33))
        var acc = 0
        for (b in block) acc = acc xor (b.toInt() and 0xFF)
        assertEquals(0, acc)
    }

    /**
     * The same check over a block whose information field ends in '00'. An LRC
     * that stopped short of the last byte would still verify such a block, so
     * changing that byte has to invalidate it.
     */
    @Test
    fun `the LRC covers an information field ending in zero`() {
        val block = T1.iBlock(sequence = 0, more = false, info = byteArrayOf(0x11, 0x00))
        assertNotNull(T1.parse(block))
        block[block.size - 2] = 0x01
        assertNull("the last INF byte must be covered by the LRC", T1.parse(block))
    }

    /**
     * ISO/IEC 13239 §4.2.5.2: with the register preset to all ones, the
     * remainder over the protected bits and the FCS is
     * "0001 1101 0000 1111" when there are no transmission errors.
     */
    @Test
    fun `CRC leaves the residue 13239 states`() {
        val block = T1.iBlock(0, false, byteArrayOf(0x01, 0x02, 0x03), T1.Edc.CRC)
        assertEquals(0x1D0F, T1.crcRegister(block, 0, block.size))
    }

    /**
     * A control for the test above. §4.2.5.2 transmits "the ones complement of
     * the resulting remainder"; omitting that step yields a checksum that still
     * round-trips against itself, so only the residue catches it.
     */
    @Test
    fun `an FCS without the ones complement misses the residue`() {
        val body = byteArrayOf(0x00, 0x00, 0x03, 0x01, 0x02, 0x03)
        val uncomplemented = T1.crcRegister(body, 0, body.size)
        val wrong = body + byteArrayOf(
            ((uncomplemented ushr 8) and 0xFF).toByte(),
            (uncomplemented and 0xFF).toByte(),
        )
        assertTrue(T1.crcRegister(wrong, 0, wrong.size) != 0x1D0F)
    }

    @Test
    fun `parse rejects a corrupted epilogue`() {
        for (edc in T1.Edc.entries) {
            val block = T1.iBlock(0, false, byteArrayOf(0x41, 0x42), edc)
            assertNotNull(T1.parse(block, edc))
            block[3] = (block[3].toInt() xor 0xFF).toByte()
            assertNull("a flipped INF byte must invalidate the $edc block", T1.parse(block, edc))
        }
    }

    // ── PCB encodings: §11.3.2.2, Figures 18 to 20 ──────────────────────────

    /**
     * Figure 18: an I-block PCB is `0-N(S)-M-00000`, so N(S) is bit 7 (40h) and
     * the more-data bit is bit 6 (20h).
     */
    @Test
    fun `I-block PCB carries N(S) at bit 7 and more-data at bit 6`() {
        assertEquals(0x00, pcb(T1.iBlock(0, more = false, info = ByteArray(0))))
        assertEquals(0x40, pcb(T1.iBlock(1, more = false, info = ByteArray(0))))
        assertEquals(0x20, pcb(T1.iBlock(0, more = true, info = ByteArray(0))))
        assertEquals(0x60, pcb(T1.iBlock(1, more = true, info = ByteArray(0))))
    }

    /**
     * Figure 19: an R-block PCB is `10-0-N(R)-0000`, which puts N(R) at bit 5
     * (10h), not at bit 0. An N(R) written into the low bits reads as error
     * code 01, "EDC and/or parity error", and the card retransmits forever.
     */
    @Test
    fun `R-block PCB carries N(R) at bit 5`() {
        assertEquals(0x80, pcb(T1.rBlock(0)))
        assertEquals(0x90, pcb(T1.rBlock(1)))
    }

    /** Figure 19: bits 2 to 1 are 01 for an EDC or parity error, 10 for any other. */
    @Test
    fun `R-block error codes are 01 for EDC and 10 for other`() {
        assertEquals(0x81, pcb(T1.rBlockError(0, edcError = true)))
        assertEquals(0x82, pcb(T1.rBlockError(0, edcError = false)))
        assertEquals(0x91, pcb(T1.rBlockError(1, edcError = true)))
        assertEquals(0x92, pcb(T1.rBlockError(1, edcError = false)))
    }

    /** Figure 20, with bit 6 (20h) distinguishing a response from a request. */
    @Test
    fun `S-block PCBs match figure 20`() {
        assertEquals(0xC0, T1.S_RESYNCH_REQUEST)
        assertEquals(0xE0, T1.S_RESYNCH_RESPONSE)
        assertEquals(0xC1, T1.S_IFS_REQUEST)
        assertEquals(0xE1, T1.S_IFS_RESPONSE)
        assertEquals(0xC2, T1.S_ABORT_REQUEST)
        assertEquals(0xE2, T1.S_ABORT_RESPONSE)
        assertEquals(0xC3, T1.S_WTX_REQUEST)
        assertEquals(0xE3, T1.S_WTX_RESPONSE)
        // Bit 6 is what separates a response from the request it answers.
        assertEquals(T1.S_RESYNCH_RESPONSE, T1.S_RESYNCH_REQUEST or 0x20)
        assertEquals(T1.S_IFS_RESPONSE, T1.S_IFS_REQUEST or 0x20)
        assertEquals(T1.S_ABORT_RESPONSE, T1.S_ABORT_REQUEST or 0x20)
        assertEquals(T1.S_WTX_RESPONSE, T1.S_WTX_REQUEST or 0x20)
    }

    @Test
    fun `block type is read from the two high bits`() {
        assertTrue(T1.parse(T1.iBlock(0, false, ByteArray(0)))!!.isIBlock)
        assertTrue(T1.parse(T1.rBlock(0))!!.isRBlock)
        assertTrue(T1.parse(T1.resynchRequest())!!.isSBlock)
    }

    // ── Field sizes: §11.3.2.3 and §11.4.2 ──────────────────────────────────

    /** §11.3.2.3: LEN codes 0 to 254; the value 'FF' is reserved. */
    @Test
    fun `LEN FF is reserved`() {
        val block = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00)
        assertNull(T1.parse(block))
        assertEquals(254, T1.MAX_INFO)
    }

    /** §11.4.2: IFSC and IFSD both start at 32 until S(IFS) changes them. */
    @Test
    fun `the default information field size is 32`() {
        assertEquals(32, T1.DEFAULT_IFS)
    }

    @Test
    fun `an information field longer than 254 is refused`() {
        var refused = false
        try {
            T1.iBlock(0, false, ByteArray(T1.MAX_INFO + 1))
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue("LEN cannot encode more than ${T1.MAX_INFO} bytes", refused)
    }

    /** §11.6.2.3 Rule 4: an S(IFS) request carries the new size in one byte. */
    @Test
    fun `S(IFS) request carries the size and refuses a value LEN cannot hold`() {
        val request = T1.ifsRequest(254)
        assertEquals(T1.S_IFS_REQUEST, pcb(request))
        assertArrayEquals(byteArrayOf(254.toByte()), T1.parse(request)!!.info)
        for (bad in listOf(0, 255)) {
            var refused = false
            try {
                T1.ifsRequest(bad)
            } catch (e: IllegalArgumentException) {
                refused = true
            }
            assertTrue("IFS $bad is out of range", refused)
        }
    }

    /**
     * §11.6.2.3 Rules 3 and 4: the response to S(WTX request) and to
     * S(IFS request) repeats the information field of the request.
     */
    @Test
    fun `S-block responses echo the request information field`() {
        val wtx = T1.parse(T1.wtxResponse(T1.Block(0, T1.S_WTX_REQUEST, byteArrayOf(0x04))))!!
        assertEquals(T1.S_WTX_RESPONSE, wtx.pcb)
        assertArrayEquals(byteArrayOf(0x04), wtx.info)

        val ifs = T1.parse(T1.ifsResponse(T1.Block(0, T1.S_IFS_REQUEST, byteArrayOf(0xFE.toByte()))))!!
        assertEquals(T1.S_IFS_RESPONSE, ifs.pcb)
        assertArrayEquals(byteArrayOf(0xFE.toByte()), ifs.info)
    }

    // ── Round trip ──────────────────────────────────────────────────────────

    /** §11.3.2.1: with no addressing in use, SAD and DAD are both zero. */
    @Test
    fun `an I-block survives a round trip under either epilogue`() {
        val info = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x00)
        for (edc in T1.Edc.entries) {
            val parsed = T1.parse(T1.iBlock(1, more = true, info = info, edc = edc), edc)!!
            assertEquals(0x00, parsed.nad)
            assertEquals(1, parsed.sequence)
            assertTrue(parsed.chained)
            assertArrayEquals(info, parsed.info)
        }
    }

    /**
     * §11.6.3.2 Rule 7 branches on what was last transmitted, and treats an
     * S(... request) and an S(... response) differently: a request is
     * retransmitted, a response is answered with an R-block. Collapsing the two
     * sends a WTX response again where the card is waiting for an R-block.
     */
    @Test
    fun `rule 7 tells the four kinds of block apart`() {
        assertEquals(T1.Kind.I_BLOCK, T1.kindOf(pcb(T1.iBlock(0, false, ByteArray(0)))))
        assertEquals(T1.Kind.I_BLOCK, T1.kindOf(pcb(T1.iBlock(1, true, ByteArray(0)))))
        assertEquals(T1.Kind.R_BLOCK, T1.kindOf(pcb(T1.rBlock(0))))
        assertEquals(T1.Kind.R_BLOCK, T1.kindOf(pcb(T1.rBlockError(1, edcError = true))))

        for (request in listOf(T1.S_RESYNCH_REQUEST, T1.S_IFS_REQUEST, T1.S_ABORT_REQUEST, T1.S_WTX_REQUEST)) {
            assertEquals("0x%02X is a request".format(request), T1.Kind.S_REQUEST, T1.kindOf(request))
        }
        for (response in listOf(T1.S_RESYNCH_RESPONSE, T1.S_IFS_RESPONSE, T1.S_ABORT_RESPONSE, T1.S_WTX_RESPONSE)) {
            assertEquals("0x%02X is a response".format(response), T1.Kind.S_RESPONSE, T1.kindOf(response))
        }
    }

    private fun pcb(block: ByteArray) = block[1].toInt() and 0xFF
}
