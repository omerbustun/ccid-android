// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

/**
 * Mapping APDUs onto T=0 TPDUs.
 *
 * At TPDU exchange level the reader handles procedure bytes and the character
 * layer itself (CCID 1.1 §3.2.1), leaving the host the conversion from an APDU
 * to one of the three command forms that clause lists, and the follow-up
 * exchanges T=0 requires because a single exchange cannot both send and receive
 * data.
 */
internal object T0 {

    /** ISO/IEC 7816-4 §5.1: the four shapes a command APDU can take. */
    enum class Case {
        /** CLA INS P1 P2. Nothing in, nothing out. */
        ONE,

        /** CLA INS P1 P2 Le. Nothing in, data out. */
        TWO,

        /** CLA INS P1 P2 Lc Data. Data in, nothing out. */
        THREE,

        /** CLA INS P1 P2 Lc Data Le. Data in and out; T=0 cannot do both at once. */
        FOUR,
    }

    /** GET RESPONSE, which collects the data a case 4 command or a 61XX left waiting. */
    fun getResponse(cla: Int, le: Int): ByteArray =
        byteArrayOf(cla.toByte(), 0xC0.toByte(), 0x00, 0x00, le.toByte())

    /** SW1 61h: the card holds SW2 more bytes, retrievable with GET RESPONSE. */
    fun moreDataAvailable(sw1: Int) = sw1 == 0x61

    /** SW1 6Ch: Le was wrong and SW2 is the correct value to retry with. */
    fun wrongLength(sw1: Int) = sw1 == 0x6C

    /**
     * Classify a command APDU.
     *
     * Null when the buffer is not a well-formed short APDU. Extended-length
     * APDUs are not representable in T=0 and are rejected rather than truncated.
     */
    fun classify(apdu: ByteArray): Case? = when {
        apdu.size < 4 -> null
        apdu.size == 4 -> Case.ONE
        apdu.size == 5 -> Case.TWO
        else -> {
            val lc = apdu[4].toInt() and 0xFF
            when {
                // Extended length: a zero Lc byte with more following.
                lc == 0 -> null
                apdu.size == 5 + lc -> Case.THREE
                apdu.size == 5 + lc + 1 -> Case.FOUR
                else -> null
            }
        }
    }

    /**
     * The command TPDU to send first.
     *
     * Case 1 gains the P3=00h the CCID expects. Case 4 sends only its incoming
     * half; the outgoing half arrives through [getResponse].
     */
    fun commandTpdu(apdu: ByteArray, case: Case): ByteArray = when (case) {
        Case.ONE -> apdu + 0x00
        Case.TWO, Case.THREE -> apdu
        Case.FOUR -> apdu.copyOfRange(0, apdu.size - 1)
    }

    /** The Le a case 4 command wants, once its data has been accepted. */
    fun expectedLength(apdu: ByteArray, case: Case): Int =
        if (case == Case.FOUR) apdu[apdu.size - 1].toInt() and 0xFF else 0
}
