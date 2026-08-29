// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answer-to-reset against ISO/IEC 7816-3:2006 clause 8, and the T=1
 * interface bytes against clause 11.4.
 *
 * The ATRs here are written byte by byte from the clause 8 bitmap rules rather
 * than captured from a card, so each one isolates a single rule.
 */
class AtrTest {

    /** §8.1: TS is '3B' for direct convention or '3F' for inverse. */
    @Test
    fun `TS is 3B or 3F and nothing else`() {
        assertTrue(Atr.looksValid(byteArrayOf(0x3B, 0x00)))
        assertTrue(Atr.looksValid(byteArrayOf(0x3F, 0x00)))
        assertFalse(Atr.looksValid(byteArrayOf(0x3C, 0x00)))
        assertFalse(Atr.looksValid(ByteArray(0)))
    }

    /** §8.2.3: a card that sends no TD1 offers T=0 only. */
    @Test
    fun `no TD1 means T=0`() {
        assertEquals(0, Atr.firstProtocol(byteArrayOf(0x3B, 0x00)))
    }

    /** §8.2.3: the type is the low nibble of TD1. */
    @Test
    fun `the first protocol is the low nibble of TD1`() {
        // T0 = 80h: only TD1 follows. TD1 = 81h: only TD2 follows, type T=1.
        assertEquals(1, Atr.firstProtocol(byteArrayOf(0x3B, 0x80.toByte(), 0x81.toByte())))
        assertEquals(0, Atr.firstProtocol(byteArrayOf(0x3B, 0x80.toByte(), 0x80.toByte())))
    }

    /**
     * §11.4.2 and §11.4.4 by way of §8.2.3: the interface bytes specific to T=1
     * are "the first TA, TB and TC for T=1 ... transmitted respectively as TA3,
     * TB3 and TC3 after TD2 indicating T=1".
     */
    @Test
    fun `IFSC and the epilogue come from TA3 and TC3`() {
        // T0 = 80h (TD1 only), TD1 = 81h (TD2 only, T=1),
        // TD2 = 51h (TA3 and TC3 present, T=1), TA3 = FEh, TC3 = 01h.
        val atr = byteArrayOf(0x3B, 0x80.toByte(), 0x81.toByte(), 0x51, 0xFE.toByte(), 0x01)
        assertEquals(254, Atr.ifsc(atr))
        assertEquals(T1.Edc.CRC, Atr.edc(atr))
    }

    /** §11.4.4: bit 1 of the first TC for T=1 is 0 for LRC, the default. */
    @Test
    fun `TC3 with bit 1 clear selects LRC`() {
        val atr = byteArrayOf(0x3B, 0x80.toByte(), 0x81.toByte(), 0x51, 0xFE.toByte(), 0x00)
        assertEquals(T1.Edc.LRC, Atr.edc(atr))
    }

    /**
     * §8.2.3: "TC2 is specific to T=0". It carries the waiting time integer of
     * clause 10.2, so it must never be read as the T=1 redundancy code even
     * when TD1 names T=1.
     *
     * WI = 11 has bit 1 set. Read as a TC for T=1 it selects CRC, and every
     * block afterwards carries an epilogue the card will not accept.
     */
    @Test
    fun `TC2 belongs to T=0 even when TD1 names T=1`() {
        // T0 = 80h (TD1 only), TD1 = 41h (TC2 present, T=1), TC2 = 0Bh (WI = 11).
        val atr = byteArrayOf(0x3B, 0x80.toByte(), 0x41, 0x0B)
        assertEquals(T1.Edc.LRC, Atr.edc(atr))
    }

    /**
     * §8.2.3: "TA1, TB1, TC1, TA2 and TB2 are global." TA2 is the specific mode
     * byte of §8.3, not an IFSC, so a card sending it keeps the §11.4.2 default.
     */
    @Test
    fun `TA2 is global and is not an IFSC`() {
        // T0 = 80h (TD1 only), TD1 = 11h (TA2 present, T=1), TA2 = 81h.
        val atr = byteArrayOf(0x3B, 0x80.toByte(), 0x11, 0x81.toByte())
        assertEquals(T1.DEFAULT_IFS, Atr.ifsc(atr))
    }

    /** §11.4.2: '00' and 'FF' are reserved, so a card sending one keeps the default. */
    @Test
    fun `a reserved IFSC falls back to the default`() {
        for (reserved in listOf(0x00, 0xFF)) {
            val atr = byteArrayOf(
                0x3B, 0x80.toByte(), 0x81.toByte(), 0x11, reserved.toByte(),
            )
            assertEquals(T1.DEFAULT_IFS, Atr.ifsc(atr))
        }
    }

    /**
     * §8.2.3: "After T=15, TA, TB, and TC are global." A TA and TC following a
     * TD that names T=15 are therefore not the T=1 parameters of §11.4, even
     * where an earlier TD named T=1.
     */
    @Test
    fun `bytes after T=15 are global, not protocol parameters`() {
        // TD1 = 81h (TD2 only, T=1), TD2 = 5Fh (TA3 and TC3 present, T=15).
        val atr = byteArrayOf(0x3B, 0x80.toByte(), 0x81.toByte(), 0x5F, 0xFE.toByte(), 0x01)
        assertEquals(T1.DEFAULT_IFS, Atr.ifsc(atr))
        assertEquals(T1.Edc.LRC, Atr.edc(atr))
        // T=15 marks global bytes; the type the card offers is still the T=1 of TD1.
        assertEquals(1, Atr.firstProtocol(atr))
    }

    /** A truncated ATR must not read past its end. */
    @Test
    fun `a truncated ATR yields the defaults`() {
        val truncated = byteArrayOf(0x3B, 0x80.toByte(), 0x51)
        assertEquals(T1.DEFAULT_IFS, Atr.ifsc(truncated))
        assertEquals(T1.Edc.LRC, Atr.edc(truncated))
    }

    /**
     * A real AKİS ATR, so the bitmap walk is exercised against a card in the
     * field and not only the constructed cases above. Its historical bytes
     * spell "UEKAE V1.0".
     *
     * T0 = BAh sets TA1, TB1 and TD1 present with ten historical bytes; TD1 and
     * TD2 both name T=1; TA3 = FEh carries the IFSC and no TC for T=1 appears,
     * so §11.4.4 leaves the epilogue at its LRC default.
     */
    @Test
    fun `a card in the field reports T=1, IFSC 254 and the default epilogue`() {
        val akis = "3BBA11008131FE4D55454B41452056312E30AE".hexToBytes()
        assertTrue(Atr.looksValid(akis))
        assertEquals(1, Atr.firstProtocol(akis))
        assertEquals(254, Atr.ifsc(akis))
        assertEquals(T1.Edc.LRC, Atr.edc(akis))
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
