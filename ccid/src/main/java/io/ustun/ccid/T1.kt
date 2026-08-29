// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

/**
 * The T=1 half-duplex block protocol, per ISO/IEC 7816-3:2006 clause 11.
 *
 * Needed only for readers reporting [Ccid.ExchangeLevel.TPDU], which pass
 * blocks through to the card unchanged. Readers at APDU level run this
 * themselves.
 *
 * Pure block encoding and decoding; [UsbCcidTransport] drives the exchange.
 */
internal object T1 {

    /** §11.3.2.1. SAD and DAD both zero when addressing is unused. */
    private const val NAD: Byte = 0x00

    /** §11.3.2.2 Figure 18. I-block: bit 8 clear, N(S) at bit 7, M-bit at bit 6. */
    private const val PCB_I_BLOCK = 0x00
    private const val PCB_SEQ_BIT = 0x40
    private const val PCB_CHAIN_BIT = 0x20

    /** §11.3.2.2 Figure 19. R-block PCB is `10` then `0-N(R)-0000`; N(R) is bit 5. */
    private const val PCB_R_SEQ_SHIFT = 4

    /** §11.3.2.2 Figure 20. S-block PCBs; bit 6 is the response bit. */
    const val S_RESYNCH_REQUEST = 0xC0
    const val S_RESYNCH_RESPONSE = 0xE0
    const val S_IFS_REQUEST = 0xC1
    const val S_IFS_RESPONSE = 0xE1
    const val S_ABORT_REQUEST = 0xC2
    const val S_ABORT_RESPONSE = 0xE2
    const val S_WTX_REQUEST = 0xC3
    const val S_WTX_RESPONSE = 0xE3

    /** §11.3.2.1. NAD, PCB and LEN precede the information field. */
    const val PROLOGUE = 3

    /** §11.3.2.3. LEN encodes 0..254; 'FF' is reserved. */
    const val MAX_INFO = 254

    /** §11.4.2. IFSC and IFSD both start here until S(IFS) adjusts them. */
    const val DEFAULT_IFS = 32

    /** §11.3.4. The epilogue is one byte of LRC or two of CRC. */
    enum class Edc { LRC, CRC }

    /**
     * The kind of block a PCB encodes (§11.3.2.2), which is what §11.6.3.2
     * Rule 7 branches on when a reply is invalid. An S-block is split by bit 6,
     * because Rule 7.3 answers a request and a response differently.
     */
    enum class Kind { I_BLOCK, R_BLOCK, S_REQUEST, S_RESPONSE }

    fun kindOf(pcb: Int): Kind = when {
        pcb and 0x80 == 0 -> Kind.I_BLOCK
        pcb and 0xC0 == 0x80 -> Kind.R_BLOCK
        pcb and 0x20 != 0 -> Kind.S_RESPONSE
        else -> Kind.S_REQUEST
    }

    /**
     * §4.2.5.2 of ISO/IEC 13239: the register run over the protected bytes and
     * the FCS together leaves this value when there were no errors.
     */
    const val CRC_RESIDUE = 0x1D0F

    data class Block(val nad: Int, val pcb: Int, val info: ByteArray) {
        val isIBlock: Boolean get() = pcb and 0x80 == 0
        val isRBlock: Boolean get() = pcb and 0xC0 == 0x80
        val isSBlock: Boolean get() = pcb and 0xC0 == 0xC0

        /** §11.6.2.2. More blocks follow this one. */
        val chained: Boolean get() = isIBlock && (pcb and PCB_CHAIN_BIT) != 0

        val isWtxRequest: Boolean get() = pcb == S_WTX_REQUEST
        val isIfsRequest: Boolean get() = pcb == S_IFS_REQUEST
        val isIfsResponse: Boolean get() = pcb == S_IFS_RESPONSE
        val isAbortRequest: Boolean get() = pcb == S_ABORT_REQUEST
        val isResynchResponse: Boolean get() = pcb == S_RESYNCH_RESPONSE

        /** §11.3.2.2. A non-zero error code means the block was not received intact. */
        val isRetransmitRequest: Boolean get() = isRBlock && (pcb and 0x03) != 0

        /** I-block N(S), bit 7. */
        val sequence: Int get() = (pcb ushr 6) and 1

        /** R-block N(R), bit 5. */
        val expectedSequence: Int get() = (pcb ushr 4) and 1

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Block) return false
            return nad == other.nad && pcb == other.pcb && info.contentEquals(other.info)
        }

        override fun hashCode(): Int = (31 * (31 * nad + pcb)) + info.contentHashCode()
    }

    fun iBlock(sequence: Int, more: Boolean, info: ByteArray, edc: Edc = Edc.LRC): ByteArray {
        require(info.size <= MAX_INFO) { "T=1 information field is at most $MAX_INFO bytes" }
        var pcb = PCB_I_BLOCK
        if (sequence and 1 == 1) pcb = pcb or PCB_SEQ_BIT
        if (more) pcb = pcb or PCB_CHAIN_BIT
        return frame(pcb, info, edc)
    }

    /**
     * §11.6.2.3 Rule 5. Acknowledges a chained I-block and requests the next,
     * with N(R) set to the N(S) expected next.
     */
    fun rBlock(nextSequence: Int, edc: Edc = Edc.LRC): ByteArray =
        frame(0x80 or ((nextSequence and 1) shl PCB_R_SEQ_SHIFT), ByteArray(0), edc)

    /** §11.3.2.2. Error code 01 is a redundancy or parity error, 10 any other. */
    fun rBlockError(nextSequence: Int, edcError: Boolean, edc: Edc = Edc.LRC): ByteArray {
        val code = if (edcError) 0x01 else 0x02
        return frame(0x80 or ((nextSequence and 1) shl PCB_R_SEQ_SHIFT) or code, ByteArray(0), edc)
    }

    /** §11.6.2.3 Rule 4. Announces the IFSD this side can receive. */
    fun ifsRequest(ifsd: Int, edc: Edc = Edc.LRC): ByteArray {
        require(ifsd in 1..MAX_INFO) { "IFS must be 1..$MAX_INFO" }
        return frame(S_IFS_REQUEST, byteArrayOf(ifsd.toByte()), edc)
    }

    /** §11.6.2.3 Rule 4. Owed to a card announcing a new IFSC, with the same INF. */
    fun ifsResponse(request: Block, edc: Edc = Edc.LRC): ByteArray =
        frame(S_IFS_RESPONSE, request.info, edc)

    /** §11.6.2.3 Rule 3. Owed to a card asking for more time, with the same INF. */
    fun wtxResponse(request: Block, edc: Edc = Edc.LRC): ByteArray =
        frame(S_WTX_RESPONSE, request.info, edc)

    /** §11.6.3.2 Rule 6, recovery level two. */
    fun resynchRequest(edc: Edc = Edc.LRC): ByteArray =
        frame(S_RESYNCH_REQUEST, ByteArray(0), edc)

    /** §11.6.3.2 Rule 9. Owed to a card abandoning a chain. */
    fun abortResponse(edc: Edc = Edc.LRC): ByteArray =
        frame(S_ABORT_RESPONSE, ByteArray(0), edc)

    /**
     * Parse a block and verify its epilogue. Null when the frame is malformed,
     * which §11.6.3.1 treats as an invalid block.
     */
    fun parse(raw: ByteArray, edc: Edc = Edc.LRC): Block? {
        val epilogue = if (edc == Edc.CRC) 2 else 1
        if (raw.size < PROLOGUE + epilogue) return null
        val len = raw[2].toInt() and 0xFF
        if (len == 0xFF) return null
        val body = PROLOGUE + len
        if (raw.size < body + epilogue) return null
        if (edc == Edc.CRC) {
            if (crcRegister(raw, 0, body + 2) != CRC_RESIDUE) return null
        } else {
            // §11.3.4: exclusive-oring NAD to LRC inclusive gives '00'.
            if (lrc(raw, 0, body + 1) != 0.toByte()) return null
        }
        return Block(
            nad = raw[0].toInt() and 0xFF,
            pcb = raw[1].toInt() and 0xFF,
            info = raw.copyOfRange(PROLOGUE, body),
        )
    }

    /**
     * The 16-bit FCS, ISO/IEC 13239 §4.2.5.2: the ones complement of the
     * remainder, generator x16 + x12 + x5 + 1, register preset to all ones.
     */
    fun crcFcs(data: ByteArray, from: Int, to: Int): Int =
        crcRegister(data, from, to).inv() and 0xFFFF

    /** The remainder before complementing, as used by the receiver's check. */
    fun crcRegister(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private fun frame(pcb: Int, info: ByteArray, edc: Edc): ByteArray {
        val epilogue = if (edc == Edc.CRC) 2 else 1
        val block = ByteArray(PROLOGUE + info.size + epilogue)
        block[0] = NAD
        block[1] = pcb.toByte()
        block[2] = info.size.toByte()
        info.copyInto(block, PROLOGUE)
        val body = PROLOGUE + info.size
        if (edc == Edc.CRC) {
            val fcs = crcFcs(block, 0, body)
            block[body] = ((fcs ushr 8) and 0xFF).toByte()
            block[body + 1] = (fcs and 0xFF).toByte()
        } else {
            block[body] = lrc(block, 0, body)
        }
        return block
    }

    private fun lrc(data: ByteArray, from: Int, to: Int): Byte {
        var acc = 0
        for (i in from until to) acc = acc xor (data[i].toInt() and 0xFF)
        return acc.toByte()
    }
}
