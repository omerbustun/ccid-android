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
 * The APDU-to-TPDU mapping T=0 needs, against the four command cases of
 * ISO/IEC 7816-4 clause 5.1 and the status words of clause 5.1.3.
 */
class T0Test {

    /** §5.1: the four shapes a command APDU can take. */
    @Test
    fun `the four command cases are told apart by length`() {
        assertEquals(T0.Case.ONE, T0.classify(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)))
        assertEquals(T0.Case.TWO, T0.classify(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x10)))

        val three = byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x03, 0x11, 0x22, 0x33)
        assertEquals(T0.Case.THREE, T0.classify(three))

        val four = three + 0x08
        assertEquals(T0.Case.FOUR, T0.classify(four))
    }

    @Test
    fun `a malformed or extended-length APDU is refused rather than truncated`() {
        assertNull("shorter than a header", T0.classify(byteArrayOf(0x00, 0xA4.toByte(), 0x04)))
        // A zero Lc byte with data behind it introduces an extended-length
        // APDU, which T=0 cannot carry.
        assertNull(T0.classify(byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00)))
        // Lc that disagrees with the bytes actually present.
        assertNull(T0.classify(byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x05, 0x11)))
    }

    /**
     * A case 1 command gains the P3 = '00' that makes it a well-formed TPDU,
     * since the T=0 command header is always five bytes.
     */
    @Test
    fun `case 1 gains a P3 of zero`() {
        val apdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
        assertArrayEquals(apdu + 0x00, T0.commandTpdu(apdu, T0.Case.ONE))
    }

    /**
     * T=0 moves data in one direction per exchange, so a case 4 command sends
     * only its incoming half first. The outgoing half is fetched separately.
     */
    @Test
    fun `case 4 sends only its incoming half and remembers the Le`() {
        val apdu = byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x02, 0xAA.toByte(), 0xBB.toByte(), 0x08)
        assertArrayEquals(
            byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x02, 0xAA.toByte(), 0xBB.toByte()),
            T0.commandTpdu(apdu, T0.Case.FOUR),
        )
        assertEquals(0x08, T0.expectedLength(apdu, T0.Case.FOUR))
    }

    /** Cases 2 and 3 already fit a single exchange and pass through unchanged. */
    @Test
    fun `cases 2 and 3 are sent as they stand`() {
        val two = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x10)
        val three = byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x01, 0x7F)
        assertArrayEquals(two, T0.commandTpdu(two, T0.Case.TWO))
        assertArrayEquals(three, T0.commandTpdu(three, T0.Case.THREE))
    }

    /** GET RESPONSE is INS C0h, with the class byte carried over from the command. */
    @Test
    fun `GET RESPONSE keeps the class byte of the command it follows`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, 0x0E),
            T0.getResponse(cla = 0x00, le = 0x0E),
        )
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0xC0.toByte(), 0x00, 0x00, 0x05),
            T0.getResponse(cla = 0x80, le = 0x05),
        )
    }

    /**
     * §5.1.3: SW1 = '61' means the card holds SW2 further bytes, and SW1 = '6C'
     * means Le was wrong and SW2 is the value to retry with. Neither is an error
     * and neither may be confused with the other.
     */
    @Test
    fun `61 and 6C are told apart from each other and from success`() {
        assertTrue(T0.moreDataAvailable(0x61))
        assertFalse(T0.moreDataAvailable(0x6C))
        assertFalse(T0.moreDataAvailable(0x90))

        assertTrue(T0.wrongLength(0x6C))
        assertFalse(T0.wrongLength(0x61))
        assertFalse(T0.wrongLength(0x90))
    }
}
