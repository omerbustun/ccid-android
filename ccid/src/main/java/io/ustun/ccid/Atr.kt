// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

/**
 * The answer-to-reset, per ISO/IEC 7816-3:2006 clause 8.
 *
 * TS, T0, then groups of interface bytes. The high nibble of T0 says which of
 * TA1/TB1/TC1/TD1 are present; each TDi's high nibble says the same for the
 * next group, and its low nibble names the protocol that group describes.
 */
internal object Atr {

    /** §8.1. TS is 3Bh for direct convention or 3Fh for inverse. */
    fun looksValid(atr: ByteArray): Boolean {
        val ts = atr.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        return ts == 0x3B || ts == 0x3F
    }

    /**
     * §8.2.3. The first offered protocol, from the low nibble of TD1. A card
     * offering no TD1 is T=0 by default.
     */
    fun firstProtocol(atr: ByteArray): Int =
        groups(atr).firstOrNull { it.protocol != GLOBAL }?.protocol ?: 0

    /**
     * §11.4.4. Bit 1 of the first TC for T=1 selects the epilogue: CRC when
     * set, LRC otherwise, which the same clause names as the default.
     */
    fun edc(atr: ByteArray): T1.Edc {
        val tc = firstFor(atr, 1) { it.tc } ?: return T1.Edc.LRC
        return if (tc and 0x01 != 0) T1.Edc.CRC else T1.Edc.LRC
    }

    /**
     * §11.4.2. The first TA for T=1 sets the card's initial IFSC, the longest
     * information field it accepts. The values '00' and 'FF' are reserved, and
     * a card offering no such byte keeps the default.
     */
    fun ifsc(atr: ByteArray): Int {
        val ta = firstFor(atr, 1) { it.ta } ?: return T1.DEFAULT_IFS
        return if (ta in 1..T1.MAX_INFO) ta else T1.DEFAULT_IFS
    }

    /**
     * TA and TC of one group, with the protocol the preceding TD named. TB is
     * skipped: for T=1 it carries BWI, which the reader negotiates itself.
     */
    private class Group(val protocol: Int, val ta: Int?, val tc: Int?)

    /** T0's own interface bytes are global parameters and describe no protocol. */
    private const val GLOBAL = -1

    /** The first of an interface byte present for [protocol], as §8.2.3 orders them. */
    private fun firstFor(atr: ByteArray, protocol: Int, byte: (Group) -> Int?): Int? =
        groups(atr).firstOrNull { it.protocol == protocol && byte(it) != null }?.let(byte)

    private fun groups(atr: ByteArray): List<Group> {
        if (atr.size < 2) return emptyList()
        val out = mutableListOf<Group>()
        var i = 1
        var y = (atr[i].toInt() and 0xF0) ushr 4     // Y1, carried by T0
        var protocol = GLOBAL
        i++
        while (true) {
            val ta = if (y and 0x1 != 0) byteAt(atr, i++) else null
            if (y and 0x2 != 0) i++
            val tc = if (y and 0x4 != 0) byteAt(atr, i++) else null
            out += Group(protocol, ta, tc)
            if (y and 0x8 == 0) return out
            val td = byteAt(atr, i++) ?: return out
            protocol = td and 0x0F
            y = (td and 0xF0) ushr 4
        }
    }

    private fun byteAt(atr: ByteArray, at: Int): Int? =
        if (at in atr.indices) atr[at].toInt() and 0xFF else null
}
