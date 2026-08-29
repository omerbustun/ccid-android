// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [CardSession] over a USB CCID reader.
 *
 * Every USB touch is behind one lock: callers may be on any thread, and two
 * transfers interleaved on the same endpoints corrupt each other's replies.
 */
class UsbCcidTransport internal constructor(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val exchangeLevel: Ccid.ExchangeLevel,
    private val features: Int,
    /** CCID Table 5.1-1: `dwMechanical`, the motorised functions this reader has. */
    private val mechanicalFunctions: Int,
    /** CCID Table 5.1-1: `bNumClockSupported`, which sizes GET_CLOCK_FREQUENCIES. */
    private val clockCount: Int,
    /** CCID Table 5.1-1: `bNumDataRatesSupported`, which sizes GET_DATA_RATES. */
    private val dataRateCount: Int,
    private val maxMessageLength: Int,
    private val interruptIn: UsbEndpoint?,
    /** CCID Table 5.1-1: `bMaxSlotIndex`, so slot count is one more than this. */
    val slotCount: Int,
    private val slot: Int,
) : CardSession {

    private val lock = ReentrantLock()

    private var sequence = 0
    private var atrBytes: ByteArray? = null
    private var powered = false
    private var claimed = false

    /** N(S) of the next I-block this side sends (7816-3 §11.6.2.2). */
    private var t1Sequence = 0

    /** N(S) expected of the card's next I-block, which counts independently. */
    private var t1CardSequence = 0

    /** The card's IFSC, from the ATR and then from any S(IFS request). */
    private var ifsc = T1.DEFAULT_IFS

    private var edc: T1.Edc = T1.Edc.LRC

    /** The protocol the card offers first (7816-3 §8.2.3): 0 for T=0, 1 for T=1. */
    private var protocol = 1

    /** True once [close] has run; the registry uses it to release the device. */
    @Volatile
    var closed = false
        private set

    /** The reader's product name, or its device name when it has none. */
    val readerName: String = device.productName?.trim()?.takeIf { it.isNotEmpty() }
        ?: device.deviceName

    /** Which slot of the reader this session drives. */
    val slotIndex: Int get() = slot

    // ── CardSession ─────────────────────────────────────────────────────────

    override fun begin() = lock.withLock {
        claim()
        if (!powered) {
            val atr = powerOn()
            atrBytes = atr
            powered = true
            t1Sequence = 0
            t1CardSequence = 0
            protocol = Atr.firstProtocol(atr)
            edc = Atr.edc(atr)
            ifsc = Atr.ifsc(atr)
            if (exchangeLevel == Ccid.ExchangeLevel.TPDU && protocol == 1) {
                Log.d(TAG, "T=1 with IFSC $ifsc and ${if (edc == T1.Edc.CRC) "CRC" else "LRC"}")
                crossCheckParameters()
                negotiateIfs()
            }
        }
    }

    override fun end() {
        lock.withLock {
            runCatching { if (powered) powerOff() }
            powered = false
            atrBytes = null
        }
    }

    override fun transmit(command: ByteArray): ByteArray = lock.withLock {
        if (!powered) throw failed("Card is not powered", CcidException.Reason.CARD_UNRESPONSIVE)
        when (exchangeLevel) {
            Ccid.ExchangeLevel.SHORT_APDU,
            Ccid.ExchangeLevel.EXTENDED_APDU,
            -> xfrApdu(command)

            // At TPDU level the transmission protocol is the host's to run.
            Ccid.ExchangeLevel.TPDU -> when (protocol) {
                0 -> xfrT0(command)
                1 -> xfrTpdu(command)
                else -> throw failed(
                    "Card protocol T=$protocol is not supported",
                    CcidException.Reason.UNSUPPORTED_READER,
                )
            }

            Ccid.ExchangeLevel.CHARACTER -> throw failed(
                "Character-level readers are not supported",
                CcidException.Reason.UNSUPPORTED_READER,
            )
        }
    }

    override fun atr(): ByteArray = lock.withLock {
        atrBytes ?: throw failed("No ATR; the card is not powered", CcidException.Reason.NO_CARD)
    }

    /** Whether a card is in the slot, without powering it up. */
    fun cardPresent(): Boolean = lock.withLock {
        claim()
        !exchange(Ccid.message(Ccid.PC_TO_RDR_GET_SLOT_STATUS, slot, nextSeq())).cardAbsent
    }

    /** Release the interface and the connection. The transport is dead after this. */
    fun close() {
        lock.withLock {
            if (closed) return
            runCatching { if (powered) powerOff() }
            powered = false
            atrBytes = null
            if (claimed) {
                connection.releaseInterface(iface)
                claimed = false
            }
            connection.close()
            closed = true
        }
        OpenReaders.release(device.deviceName, this)
    }

    // ── Session setup ───────────────────────────────────────────────────────

    private fun claim() {
        if (claimed) return
        if (!connection.claimInterface(iface, true)) {
            throw failed("Could not claim the reader", CcidException.Reason.NO_READER)
        }
        claimed = true
        clearHalt(bulkOut)
        clearHalt(bulkIn)
        drain()
        awaitReady()
    }

    /**
     * Poll `GetSlotStatus` until the reader responds.
     *
     * A newly attached device accepts the interface claim before it will accept
     * transfers. `GetSlotStatus` alters no card state, so it is safe to repeat.
     */
    private fun awaitReady() {
        for (attempt in 0 until READY_TRIES) {
            val ok = runCatching {
                exchange(Ccid.message(Ccid.PC_TO_RDR_GET_SLOT_STATUS, slot, nextSeq()))
            }.isSuccess
            if (ok) return
            Thread.sleep(READY_MS)
        }
        Log.d(TAG, "reader never answered GetSlotStatus; continuing")
    }

    /**
     * Discard any response left queued by a previous session.
     *
     * Sequence numbers do not identify these: a new transport restarts its
     * counter, so a stale reply may carry a number this session will reuse.
     */
    private fun drain() {
        val scratch = ByteArray(bulkIn.maxPacketSize)
        var dropped = 0
        while (true) {
            val n = connection.bulkTransfer(bulkIn, scratch, scratch.size, DRAIN_TIMEOUT_MS)
            if (n <= 0) break
            dropped += n
            if (dropped > MAX_DRAIN) break
        }
        if (dropped > 0) Log.d(TAG, "drained $dropped stale bytes")
    }

    private fun powerOn(): ByteArray {
        var response = exchange(Ccid.message(Ccid.PC_TO_RDR_ICC_POWER_ON, slot, nextSeq(), p0 = 0))

        // CCID §6.1.1 requires bPowerSelect 00h on the first power-on to an
        // inactive slot for readers featuring automatic activation.
        val mayChooseVoltage =
            features and Ccid.Feature.AUTO_ACTIVATE_ON_INSERT == 0 &&
                features and Ccid.Feature.AUTO_VOLTAGE == 0
        if (response.failed && mayChooseVoltage) {
            for (voltage in intArrayOf(1, 2, 3)) {
                response = exchange(
                    Ccid.message(Ccid.PC_TO_RDR_ICC_POWER_ON, slot, nextSeq(), p0 = voltage)
                )
                if (!response.failed) break
            }
        }
        if (response.failed) {
            if (response.cardAbsent) {
                throw failed("No card in the reader", CcidException.Reason.NO_CARD)
            }
            throw failed(Ccid.errorText(response.error), CcidException.Reason.CARD_UNRESPONSIVE)
        }
        // A payload that does not open like an ATR is not the power-on
        // response, indicating a stale reply left on the endpoint.
        if (!Atr.looksValid(response.data)) {
            drain()
            val again = exchange(
                Ccid.message(Ccid.PC_TO_RDR_ICC_POWER_ON, slot, nextSeq(), p0 = 0)
            )
            if (again.failed || !Atr.looksValid(again.data)) {
                throw failed("Card did not return a valid ATR", CcidException.Reason.CARD_UNRESPONSIVE)
            }
            return again.data
        }
        return response.data
    }

    private fun powerOff() {
        exchange(Ccid.message(Ccid.PC_TO_RDR_ICC_POWER_OFF, slot, nextSeq()))
    }

    /**
     * Announce this side's IFSD (7816-3 §11.6.2.3 Rule 4).
     *
     * Both sides default to 32 bytes (§11.4.2); raising it reduces the number
     * of chained blocks. Skipped when the reader declares `AUTO_IFSD`. A refusal
     * leaves the default in force and is not an error.
     */
    /**
     * Log where the reader's negotiated T=1 parameters disagree with the ATR.
     *
     * CCID §6.2.3 puts `bmTCCKST1` second in the T=1 structure, bit 0 selecting
     * CRC, and `bIFSC` sixth. The ATR stays authoritative because 7816-3
     * §11.4.4 makes the card's TC3 the source; a disagreement means one of the
     * two readings is wrong.
     */
    private fun crossCheckParameters() {
        val parameters = runCatching { getParametersLocked() }.getOrNull() ?: return
        if (parameters.protocol != 1 || parameters.data.size < 6) return

        val readerEdc = if (parameters.data[1].toInt() and 0x01 != 0) T1.Edc.CRC else T1.Edc.LRC
        if (readerEdc != edc) {
            Log.w(TAG, "reader negotiated $readerEdc but the ATR asks for $edc")
        }
        val readerIfsc = parameters.data[5].toInt() and 0xFF
        if (readerIfsc in 1..T1.MAX_INFO && readerIfsc != ifsc) {
            Log.w(TAG, "reader negotiated IFSC $readerIfsc but the ATR gives $ifsc")
        }
    }

    private fun negotiateIfs() {
        if (features and Ccid.Feature.AUTO_IFSD != 0) return
        runCatching { t1Exchange(T1.ifsRequest(T1.MAX_INFO, edc)) }
            .onSuccess { if (it.isIfsResponse) Log.d(TAG, "IFSD ${T1.MAX_INFO} accepted") }
    }

    // ── Exchanges ───────────────────────────────────────────────────────────

    /**
     * CCID §6.1.4, §6.2.1. At extended-APDU level a long response may be split
     * across data blocks, flagged in `bChainParameter`, and each next piece is
     * requested with an empty XfrBlock carrying `wLevelParameter = 0010h`.
     */
    private fun xfrApdu(command: ByteArray): ByteArray {
        checkBlockSize(command.size)
        var response = exchange(
            Ccid.message(
                Ccid.PC_TO_RDR_XFR_BLOCK, slot, nextSeq(),
                p0 = BWI, p1 = Ccid.Level.COMPLETE and 0xFF, p2 = Ccid.Level.COMPLETE ushr 8,
                data = command,
            )
        )
        if (response.failed) throw failed(Ccid.errorText(response.error))
        if (exchangeLevel != Ccid.ExchangeLevel.EXTENDED_APDU) return response.data

        val assembled = ByteArrayOutputStream()
        assembled.write(response.data)
        var guard = 0
        while (true) {
            when (response.messageSpecific) {
                Ccid.Chain.COMPLETE, Ccid.Chain.CONTINUES_AND_ENDS -> return assembled.toByteArray()
                Ccid.Chain.BEGINS, Ccid.Chain.CONTINUES -> Unit

                Ccid.Chain.EXPECTS_MORE_COMMAND -> throw failed(
                    "This reader wants the command in several blocks, which is not implemented",
                    CcidException.Reason.UNSUPPORTED_READER,
                )

                else -> throw failed(
                    "Reader chained a response with 0x${response.messageSpecific.toString(16)}",
                    CcidException.Reason.COMMUNICATION,
                )
            }
            if (++guard > MAX_CHAIN) {
                throw failed("Response never completed", CcidException.Reason.COMMUNICATION)
            }
            response = exchange(
                Ccid.message(
                    Ccid.PC_TO_RDR_XFR_BLOCK, slot, nextSeq(),
                    p0 = BWI,
                    p1 = Ccid.Level.EXPECT_MORE_RESPONSE and 0xFF,
                    p2 = Ccid.Level.EXPECT_MORE_RESPONSE ushr 8,
                )
            )
            if (response.failed) throw failed(Ccid.errorText(response.error))
            assembled.write(response.data)
        }
    }

    /**
     * Exchange an APDU over T=0.
     *
     * The reader owns procedure bytes and the character layer (CCID §3.2.1),
     * leaving the APDU-to-TPDU mapping to the host. A case 4 command sends its
     * data first and retrieves the response with GET RESPONSE. Per
     * ISO/IEC 7816-4, `61XX` means XX further bytes are available and `6CXX`
     * means Le was wrong and XX is the correct value.
     */
    private fun xfrT0(apdu: ByteArray): ByteArray {
        val case = T0.classify(apdu) ?: throw failed(
            "Not a short APDU; T=0 cannot carry extended lengths",
            CcidException.Reason.UNSUPPORTED_READER,
        )
        val cla = apdu[0].toInt() and 0xFF

        var sent = T0.commandTpdu(apdu, case)
        // Only a TPDU ending in P3 can be reissued with a corrected length.
        var sentEndsWithLe = case == T0.Case.ONE || case == T0.Case.TWO
        var response = xfrRaw(sent)
        var collected = ByteArray(0)
        var accepted: ByteArray? = null
        var guard = 0

        while (true) {
            if (++guard > MAX_T0_STEPS) {
                throw failed("Card kept deferring the response", CcidException.Reason.COMMUNICATION)
            }
            if (response.size < 2) {
                throw failed("Response carried no status word", CcidException.Reason.COMMUNICATION)
            }
            val sw1 = response[response.size - 2].toInt() and 0xFF
            val sw2 = response[response.size - 1].toInt() and 0xFF
            val body = response.copyOfRange(0, response.size - 2)

            when {
                T0.wrongLength(sw1) && sentEndsWithLe -> {
                    // Reissue the command just sent, not the original: 6CXX
                    // answers a GET RESPONSE as readily as the command itself.
                    sent = sent.copyOf().also { it[it.size - 1] = sw2.toByte() }
                    response = xfrRaw(sent)
                }

                T0.moreDataAvailable(sw1) -> {
                    collected += body
                    sent = T0.getResponse(cla, sw2)
                    sentEndsWithLe = true
                    response = xfrRaw(sent)
                }

                // A case 4 command sends its data first and its response half
                // is fetched separately. Asked once: a card with nothing to
                // give may refuse GET RESPONSE, and the command has already
                // succeeded, so its status word is kept.
                case == T0.Case.FOUR && accepted == null && collected.isEmpty() &&
                    sw1 == 0x90 && sw2 == 0x00 -> {
                    accepted = response
                    sent = T0.getResponse(cla, T0.expectedLength(apdu, case))
                    sentEndsWithLe = true
                    response = xfrRaw(sent)
                }

                else -> {
                    val succeeded = accepted
                    val emptyRefusal = collected.isEmpty() && body.isEmpty() && sw1 != 0x90
                    return if (succeeded != null && emptyRefusal) succeeded else collected + response
                }
            }
        }
    }

    /** One TPDU out, one response TPDU back, with no interpretation. */
    private fun xfrRaw(tpdu: ByteArray): ByteArray {
        checkBlockSize(tpdu.size)
        val response = exchange(
            Ccid.message(Ccid.PC_TO_RDR_XFR_BLOCK, slot, nextSeq(), p0 = BWI, data = tpdu)
        )
        if (response.failed) throw failed(Ccid.errorText(response.error))
        return response.data
    }

    /** 7816-3 §11.6.2.2. Chain the command out, reassemble the reply. */
    private fun xfrTpdu(command: ByteArray): ByteArray {
        val limit = maxInfo()
        var offset = 0
        var last: T1.Block? = null

        while (offset < command.size || offset == 0) {
            val remaining = command.size - offset
            val take = minOf(remaining, limit)
            val more = remaining > take
            val block = T1.iBlock(t1Sequence, more, command.copyOfRange(offset, offset + take), edc)
            offset += take

            last = t1Exchange(block)
            t1Sequence = t1Sequence xor 1
            if (!more) break
            if (!last.isRBlock) {
                throw failed("Card did not acknowledge a chained block", CcidException.Reason.COMMUNICATION)
            }
        }

        var reply = last
            ?: throw failed("No reply from the card", CcidException.Reason.CARD_UNRESPONSIVE)
        if (!reply.isIBlock) {
            throw failed("Card answered the command with no data", CcidException.Reason.COMMUNICATION)
        }
        val assembled = ByteArrayOutputStream()
        assembled.write(reply.info)

        var guard = 0
        while (reply.chained) {
            if (++guard > MAX_CHAIN) {
                throw failed("Response never completed", CcidException.Reason.COMMUNICATION)
            }
            // Rule 5: R(N(R)) asks for the next block of the chain.
            reply = t1Exchange(T1.rBlock(t1CardSequence, edc))
            if (!reply.isIBlock) {
                throw failed("Card broke off a chained response", CcidException.Reason.COMMUNICATION)
            }
            assembled.write(reply.info)
        }
        return assembled.toByteArray()
    }

    /**
     * The longest information field for one I-block: the card's IFSC
     * (7816-3 §11.4.2), capped by what one CCID message can carry.
     */
    private fun maxInfo(): Int {
        val epilogue = if (edc == T1.Edc.CRC) 2 else 1
        val room = if (maxMessageLength > 0) {
            maxMessageLength - Ccid.HEADER - T1.PROLOGUE - epilogue
        } else {
            T1.MAX_INFO
        }
        return minOf(ifsc, room, T1.MAX_INFO).coerceAtLeast(1)
    }

    /**
     * Exchange one T=1 block, resolving any S-blocks in between.
     *
     * Answers WTX and IFS requests (7816-3 §11.6.2.3 Rules 3 and 4) and recovers
     * per Rule 7 according to the block last sent, escalating to S(RESYNCH) once
     * retransmission is exhausted (§11.6.3.1).
     */
    private fun t1Exchange(block: ByteArray): T1.Block {
        var out = block
        var guard = 0
        var resends = 0
        while (true) {
            if (++guard > MAX_WTX) {
                throw failed("Card stopped responding", CcidException.Reason.CARD_UNRESPONSIVE)
            }
            val parsed = T1.parse(sendBlock(out).data, edc)
            if (parsed == null) {
                if (++resends > MAX_RESENDS) {
                    resynchronise()
                    throw failed("Card communication could not be recovered", CcidException.Reason.COMMUNICATION)
                }
                // §11.6.3.2 Rule 7 branches on what was last sent.
                out = when (T1.kindOf(out[1].toInt() and 0xFF)) {
                    // Rule 7.1, and the second half of 7.3: ask again for the
                    // I-block the card owes.
                    T1.Kind.I_BLOCK,
                    T1.Kind.S_RESPONSE,
                    -> T1.rBlockError(t1CardSequence, edcError = true, edc = edc)

                    // Rule 7.2 and the first half of 7.3: send the same again.
                    T1.Kind.R_BLOCK, T1.Kind.S_REQUEST -> out
                }
                continue
            }
            if (parsed.isWtxRequest) {
                out = T1.wtxResponse(parsed, edc)
                continue
            }
            if (parsed.isIfsRequest) {
                // Rule 4: the new IFSC holds until the card announces another.
                parsed.info.firstOrNull()?.toInt()?.and(0xFF)
                    ?.takeIf { it in 1..T1.MAX_INFO }
                    ?.let { ifsc = it }
                out = T1.ifsResponse(parsed, edc)
                continue
            }
            if (parsed.isAbortRequest) {
                // Rule 9. Acknowledge, then leave the operation to the caller:
                // the card has abandoned the chain, not just this block.
                runCatching { sendBlock(T1.abortResponse(edc)) }
                throw failed("Card abandoned the exchange", CcidException.Reason.COMMUNICATION)
            }
            if (parsed.isRetransmitRequest) {
                if (++resends > MAX_RESENDS) {
                    resynchronise()
                    throw failed("Card communication could not be recovered", CcidException.Reason.COMMUNICATION)
                }
                out = block
                continue
            }
            if (parsed.isIBlock) t1CardSequence = parsed.sequence xor 1
            return parsed
        }
    }

    /** One T=1 block onto the wire, with no interpretation of the answer. */
    private fun sendBlock(block: ByteArray): Ccid.Response {
        checkBlockSize(block.size)
        val response = exchange(
            Ccid.message(Ccid.PC_TO_RDR_XFR_BLOCK, slot, nextSeq(), p0 = BWI, data = block)
        )
        if (response.failed) throw failed(Ccid.errorText(response.error))
        return response
    }

    /**
     * Recovery level two (7816-3 §11.6.3.2 Rule 6).
     *
     * Level three, a warm reset or deactivation, is left to the caller: it
     * discards a verified PIN and the transport cannot know whether that is
     * acceptable.
     */
    private fun resynchronise() {
        val ok = runCatching {
            T1.parse(sendBlock(T1.resynchRequest(edc)).data, edc)?.isResynchResponse == true
        }.getOrDefault(false)
        if (ok) {
            t1Sequence = 0
            t1CardSequence = 0
            Log.d(TAG, "resynchronised")
        }
    }

    /** CCID §6.1.4: a block may not exceed `dwMaxCCIDMessageLength` minus the header. */
    private fun checkBlockSize(size: Int) {
        if (maxMessageLength <= 0) return
        val limit = maxMessageLength - Ccid.HEADER
        if (size > limit) {
            throw failed(
                "Command of $size bytes exceeds this reader's limit of $limit",
                CcidException.Reason.UNSUPPORTED_READER,
            )
        }
    }

    // ── USB ─────────────────────────────────────────────────────────────────

    private fun exchange(message: ByteArray): Ccid.Response {
        val seq = message[6].toInt() and 0xFF
        val expected = Ccid.expectedReply(message[0].toInt() and 0xFF)

        writeMessage(message)

        var waited = 0
        while (true) {
            val response = readMessage()
            // Left over from an earlier command, or meant for another slot.
            if (response.seq != seq || response.slot != slot) continue
            if (response.timeExtension) {
                if (++waited > MAX_WTX) {
                    throw failed("Card stopped responding", CcidException.Reason.CARD_UNRESPONSIVE)
                }
                continue
            }
            // Table 6.2-1 gives each command one reply, though a reader that
            // cannot carry the command out may report so with a slot status.
            if (response.type != expected && response.type != Ccid.RDR_TO_PC_SLOT_STATUS) {
                throw failed(
                    "Reader answered with message type 0x${response.type.toString(16)}",
                    CcidException.Reason.COMMUNICATION,
                )
            }
            return response
        }
    }

    /**
     * Read one CCID message, one maximum-size packet per transfer.
     *
     * USB 2.0 §5.8.3: a transfer completes on a packet shorter than
     * `wMaxPacketSize`, and a payload larger than expected retires every
     * pending transfer on the endpoint. Read buffers are therefore always a
     * whole packet.
     */
    /**
     * Put one whole message on the bulk-OUT endpoint.
     *
     * A first write can fail on an endpoint left halted by an earlier session,
     * which USB 2.0 §9.4.1 clears; one retry covers that without masking a
     * reader that is genuinely gone.
     */
    private fun writeMessage(message: ByteArray) {
        var sent = 0
        var retried = false
        while (sent < message.size) {
            val n = connection.bulkTransfer(
                bulkOut,
                message.copyOfRange(sent, message.size),
                message.size - sent,
                WRITE_TIMEOUT_MS,
            )
            if (n <= 0) {
                if (!retried) {
                    retried = true
                    clearHalt(bulkOut)
                    clearHalt(bulkIn)
                    continue
                }
                throw failed("Write to the reader failed", CcidException.Reason.COMMUNICATION)
            }
            sent += n
        }
    }

    private fun readMessage(): Ccid.Response {
        val packetSize = bulkIn.maxPacketSize
        val packet = ByteArray(packetSize)

        val first = connection.bulkTransfer(bulkIn, packet, packetSize, READ_TIMEOUT_MS)
        if (first < Ccid.HEADER) {
            throw failed("No response from the reader", CcidException.Reason.COMMUNICATION)
        }

        val declared = Ccid.declaredLength(packet, first)
        if (declared < 0 || declared > responseLimit()) {
            throw failed(
                "Reader declared a response of $declared bytes",
                CcidException.Reason.COMMUNICATION,
            )
        }
        val total = Ccid.HEADER + declared
        val body = ByteArray(total)
        var have = minOf(first, total)
        packet.copyInto(body, 0, 0, have)

        while (have < total) {
            val more = connection.bulkTransfer(bulkIn, packet, packetSize, READ_TIMEOUT_MS)
            if (more <= 0) {
                throw failed("Response was truncated", CcidException.Reason.COMMUNICATION)
            }
            packet.copyInto(body, have, 0, minOf(more, total - have))
            have += more
        }

        return Ccid.parseHeader(body, total)
            ?: throw failed("Malformed response from the reader", CcidException.Reason.COMMUNICATION)
    }

    /** The longest payload this reader can legitimately return. */
    private fun responseLimit(): Int =
        if (maxMessageLength > Ccid.HEADER) maxMessageLength - Ccid.HEADER else MAX_RESPONSE

    /**
     * Clear an endpoint halt (USB 2.0 §9.4.1, Tables 9-4 and 9-6).
     *
     * A negative result is expected on readers that do not implement the
     * request; §9.4.1 permits a Request Error where the feature cannot be
     * cleared.
     */
    private fun clearHalt(endpoint: UsbEndpoint): Int =
        connection.controlTransfer(0x02, 0x01, 0x00, endpoint.address, null, 0, CONTROL_TIMEOUT_MS)

    private fun nextSeq(): Int {
        sequence = (sequence + 1) and 0xFF
        return sequence
    }

    private fun failed(message: String, reason: CcidException.Reason = CcidException.Reason.COMMUNICATION) =
        CcidException(message, reason)

    // ── Optional reader operations ──────────────────────────────────────────

    /**
     * Stop the current transfer on this slot (CCID §6.1.13, §5.3.1).
     *
     * Three steps: the control-pipe ABORT, the bulk command carrying the same
     * slot and sequence, then discarding replies until the matching slot status
     * arrives. A reader fails every later command to a slot whose abort was
     * left half finished.
     */
    fun abort() = lock.withLock {
        val seq = nextSeq()
        // §5.3.1: control pipe first, then bulk, because the two run
        // asynchronously relative to each other. Both carry the same seq.
        connection.controlTransfer(
            Ccid.ControlRequest.TYPE_OUT,
            Ccid.ControlRequest.ABORT,
            Ccid.ControlRequest.abortValue(slot, seq),
            iface.id,
            null, 0, CONTROL_TIMEOUT_MS,
        )
        writeMessage(Ccid.message(Ccid.PC_TO_RDR_ABORT, slot, seq))

        // §5.3.1: discard replies for the aborted slot until the slot status
        // matching this abort arrives.
        var seen = 0
        while (seen++ < MAX_ABORT_REPLIES) {
            val response = runCatching { readMessage() }.getOrNull() ?: break
            if (response.slot == slot &&
                response.seq == seq &&
                response.type == Ccid.RDR_TO_PC_SLOT_STATUS
            ) {
                return@withLock
            }
        }
        Log.d(TAG, "abort was not acknowledged for slot $slot")
    }

    /**
     * The clock frequencies this reader accepts, in kHz (CCID §5.3.2).
     *
     * Empty when the descriptor reports `bNumClockSupported` as zero, which
     * §5.3.2 says excuses a reader from answering the request at all. Pair this
     * with [setDataRateAndClockFrequency], which otherwise has no way to know
     * what a reader will take.
     */
    fun clockFrequencies(): IntArray = lock.withLock {
        readDwordList(Ccid.ControlRequest.GET_CLOCK_FREQUENCIES, clockCount)
    }

    /** The data rates this reader accepts, in bits per second (CCID §5.3.3). */
    fun dataRates(): IntArray = lock.withLock {
        readDwordList(Ccid.ControlRequest.GET_DATA_RATES, dataRateCount)
    }

    /** §5.3.2 and §5.3.3 both answer with an array of little-endian double words. */
    private fun readDwordList(request: Int, count: Int): IntArray {
        if (count <= 0) return IntArray(0)
        val buffer = ByteArray(count * 4)
        val n = connection.controlTransfer(
            Ccid.ControlRequest.TYPE_IN, request, 0x0000, iface.id,
            buffer, buffer.size, CONTROL_TIMEOUT_MS,
        )
        if (n < 4) return IntArray(0)
        return IntArray(n / 4) { readLe32(buffer, it * 4) }
    }

    /**
     * Stop or restart the card's clock (CCID §6.1.9).
     *
     * A stopped clock holds the card's state while drawing almost no power.
     * `bClockCommand` 01h stops it in the state `bClockStop` selected through
     * [setParameters]; 00h restarts it.
     *
     * Returns the state the reader reports the clock reached.
     *
     * @throws CcidException if the reader does not declare clock stop mode.
     */
    fun clockStopped(stopped: Boolean): ClockStatus = lock.withLock {
        if (features and Ccid.Feature.CLOCK_STOP == 0) {
            throw failed(
                "This reader cannot stop the card clock",
                CcidException.Reason.UNSUPPORTED_READER,
            )
        }
        val command = if (stopped) Ccid.Clock.STOP else Ccid.Clock.RESTART
        val response = exchange(
            Ccid.message(Ccid.PC_TO_RDR_ICC_CLOCK, slot, nextSeq(), p0 = command)
        )
        if (response.failed) throw failed(Ccid.errorText(response.error))
        // §6.2.2 puts bClockStatus at offset 9, so the reader says what state
        // the clock actually reached rather than only that it accepted.
        ClockStatus.of(response.messageSpecific)
    }

    /**
     * Choose the class byte the reader puts on the GET RESPONSE and ENVELOPE
     * commands it issues on the host's behalf under T=0 (CCID §6.1.10).
     *
     * A null argument leaves that command at the reader's default;
     * [Ccid.T0Apdu.ECHO_CLASS] makes the reader echo the APDU's own class byte.
     * The setting is slot-specific and lapses when the slot loses power.
     *
     * @throws CcidException if the reader exchanges at TPDU level, where
     * §6.1.10 does not apply because [T0] does this work here instead.
     */
    fun setT0ApduClasses(getResponse: Int? = null, envelope: Int? = null) = lock.withLock {
        if (exchangeLevel != Ccid.ExchangeLevel.SHORT_APDU &&
            exchangeLevel != Ccid.ExchangeLevel.EXTENDED_APDU
        ) {
            throw failed(
                "Only an APDU-level reader issues GET RESPONSE itself",
                CcidException.Reason.UNSUPPORTED_READER,
            )
        }
        var changes = 0
        if (getResponse != null) changes = changes or Ccid.T0Apdu.GET_RESPONSE_CLASS
        if (envelope != null) changes = changes or Ccid.T0Apdu.ENVELOPE_CLASS
        val response = exchange(
            Ccid.message(
                Ccid.PC_TO_RDR_T0_APDU, slot, nextSeq(),
                p0 = changes, p1 = getResponse ?: 0, p2 = envelope ?: 0,
            )
        )
        if (response.failed) throw failed(Ccid.errorText(response.error))
    }

    /**
     * Drive a motorised reader (CCID §6.1.12).
     *
     * Refused unless the reader's `dwMechanical` declares the function asked
     * for. §6.1.12 leaves accept, eject and capture outside the revision it
     * defines, so a reader may decline them even while declaring them.
     *
     * @throws CcidException if the reader has no such mechanism.
     */
    fun mechanical(function: MechanicalFunction) = lock.withLock {
        if (mechanicalFunctions and function.capability == 0) {
            throw failed(
                "This reader has no ${function.name.lowercase()} mechanism",
                CcidException.Reason.UNSUPPORTED_READER,
            )
        }
        val response = exchange(
            Ccid.message(Ccid.PC_TO_RDR_MECHANICAL, slot, nextSeq(), p0 = function.code)
        )
        if (response.failed) throw failed(Ccid.errorText(response.error))
    }

    /**
     * Set the clock frequency and data rate of this slot (CCID §6.1.14).
     *
     * Returns what the reader settled on, which need not be what was asked
     * for: §6.1.14 has a reader running its own automatic selection report the
     * active values and discard the forced ones.
     */
    fun setDataRateAndClockFrequency(clockKHz: Int, dataRateBps: Int): ClockAndDataRate =
        lock.withLock {
            val payload = ByteArray(8)
            le32(payload, 0, clockKHz)
            le32(payload, 4, dataRateBps)
            val response = exchange(
                Ccid.message(
                    Ccid.PC_TO_RDR_SET_DATA_RATE_AND_CLOCK, slot, nextSeq(), data = payload,
                )
            )
            if (response.failed) throw failed(Ccid.errorText(response.error))
            if (response.data.size < 8) {
                throw failed("Reader returned a short clock and data rate")
            }
            // §6.2.5: dwClockFrequency at offset 10 and dwDataRate at 14, which
            // are the first and second words of the message-specific data.
            ClockAndDataRate(readLe32(response.data, 0), readLe32(response.data, 4))
        }

    /** The motorised functions CCID §6.1.12 defines, with their `dwMechanical` bit. */
    enum class MechanicalFunction(val code: Int, internal val capability: Int) {
        ACCEPT(0x01, Ccid.Mechanical.ACCEPT),
        EJECT(0x02, Ccid.Mechanical.EJECT),
        CAPTURE(0x03, Ccid.Mechanical.CAPTURE),
        LOCK(0x04, Ccid.Mechanical.LOCK_UNLOCK),
        UNLOCK(0x05, Ccid.Mechanical.LOCK_UNLOCK),
    }

    /** A slot's clock frequency in kHz and data rate in bits per second. */
    data class ClockAndDataRate(val clockKHz: Int, val dataRateBps: Int)

    /** What the interrupt endpoint reports, per CCID §6.3. */
    sealed interface SlotEvent {

        /** §6.3.1. One entry per slot, true where a card is present. */
        class Changed(val present: BooleanArray) : SlotEvent

        /**
         * §6.3.2. `code` is `bHardwareErrorCode`, of which only 01h,
         * overcurrent, is defined; the rest are reserved.
         */
        data class HardwareError(val slot: Int, val code: Int) : SlotEvent
    }

    /** `bClockStatus` on a slot status reply (CCID §6.2.2). */
    enum class ClockStatus {
        RUNNING, STOPPED_LOW, STOPPED_HIGH, STOPPED_UNKNOWN;

        internal companion object {
            fun of(value: Int): ClockStatus = entries.getOrElse(value) { STOPPED_UNKNOWN }
        }
    }

    private fun le32(into: ByteArray, at: Int, value: Int) {
        into[at] = (value and 0xFF).toByte()
        into[at + 1] = (value ushr 8 and 0xFF).toByte()
        into[at + 2] = (value ushr 16 and 0xFF).toByte()
        into[at + 3] = (value ushr 24 and 0xFF).toByte()
    }

    private fun readLe32(from: ByteArray, at: Int): Int =
        (from[at].toInt() and 0xFF) or
            ((from[at + 1].toInt() and 0xFF) shl 8) or
            ((from[at + 2].toInt() and 0xFF) shl 16) or
            ((from[at + 3].toInt() and 0xFF) shl 24)

    /** CCID §6.1.5. The protocol parameters currently in force. */
    fun getParameters(): Parameters = lock.withLock { getParametersLocked() }

    private fun getParametersLocked(): Parameters {
        val response = exchange(Ccid.message(Ccid.PC_TO_RDR_GET_PARAMETERS, slot, nextSeq()))
        if (response.failed) throw failed(Ccid.errorText(response.error))
        return Parameters(response.messageSpecific, response.data)
    }

    /** CCID §6.1.6. Return the slot to its default parameters. */
    fun resetParameters(): Parameters = lock.withLock {
        val response = exchange(Ccid.message(Ccid.PC_TO_RDR_RESET_PARAMETERS, slot, nextSeq()))
        if (response.failed) throw failed(Ccid.errorText(response.error))
        Parameters(response.messageSpecific, response.data)
    }

    /**
     * Set protocol parameters (CCID §6.1.7).
     *
     * Table 5.1-1 forbids the host changing FI, DI and the protocol on a reader
     * that sets them itself, from the ATR or by negotiation; this throws in
     * that case.
     */
    fun setParameters(parameters: Parameters): Parameters = lock.withLock {
        val automatic = Ccid.Feature.AUTO_PARAM_FROM_ATR or
            Ccid.Feature.AUTO_PARAM_NEGOTIATION or
            Ccid.Feature.AUTO_PPS
        if (features and automatic != 0) {
            throw failed(
                "This reader sets protocol parameters itself",
                CcidException.Reason.UNSUPPORTED_READER,
            )
        }
        val response = exchange(
            Ccid.message(
                Ccid.PC_TO_RDR_SET_PARAMETERS, slot, nextSeq(),
                p0 = parameters.protocol, data = parameters.data,
            )
        )
        if (response.failed) throw failed(Ccid.errorText(response.error))
        Parameters(response.messageSpecific, response.data)
    }

    /** CCID §6.1.8. A vendor-defined passthrough; the payload means nothing here. */
    fun escape(payload: ByteArray): ByteArray = lock.withLock {
        val response = exchange(
            Ccid.message(Ccid.PC_TO_RDR_ESCAPE, slot, nextSeq(), data = payload)
        )
        if (response.failed) throw failed(Ccid.errorText(response.error))
        response.data
    }

    /**
     * Have the reader collect the PIN on its own keypad and insert it into
     * [apdu] before sending it to the card (CCID §6.1.11).
     *
     * The PIN does not enter the calling process. `dwFeatures` carries no
     * capability bit for this; a reader without a keypad answers with an error.
     *
     * [apdu] is the verification command with a placeholder where the PIN block
     * is inserted, per §6.1.11.5.
     */
    fun verifyPinOnReader(
        apdu: ByteArray,
        minDigits: Int = 4,
        maxDigits: Int = 16,
        timeoutSeconds: Int = 30,
        formatString: Int = 0x82,
        pinBlockString: Int = 0x08,
        pinLengthFormat: Int = 0x00,
    ): ByteArray = lock.withLock {
        // §6.1.11.1 and §6.1.11.2, from offset 10 of the message.
        val structure = ByteArrayOutputStream().apply {
            write(0x00)                                   // bPINOperation: verification
            write(timeoutSeconds and 0xFF)                // bTimeOut
            write(formatString and 0xFF)                  // bmFormatString
            write(pinBlockString and 0xFF)                // bmPINBlockString
            write(pinLengthFormat and 0xFF)               // bmPINLengthFormat
            write(maxDigits and 0xFF)                     // wPINMaxExtraDigit, max
            write(minDigits and 0xFF)                     //                    min
            write(0x02)                                   // bEntryValidationCondition
            write(0x01)                                   // bNumberMessage
            write(0x09); write(0x04)                      // wLangId, en-US (0409h)
            write(0x00)                                   // bMsgIndex
            write(0x00); write(0x00); write(0x00)         // bTeoPrologue
            write(apdu)                                   // abPINApdu
        }.toByteArray()

        val response = exchange(
            Ccid.message(Ccid.PC_TO_RDR_SECURE, slot, nextSeq(), p0 = BWI, data = structure)
        )
        if (response.failed) throw failed(Ccid.errorText(response.error))
        response.data
    }

    /** Protocol parameters as CCID §6.2.3 returns them. */
    data class Parameters(
        /** `bProtocolNum`: 0 for T=0, 1 for T=1. */
        val protocol: Int,
        /** `abProtocolDataStructure`, whose shape depends on [protocol]. */
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parameters) return false
            return protocol == other.protocol && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * protocol + data.contentHashCode()
    }

    /**
     * Await a slot-change notification on the interrupt endpoint
     * (CCID §6.3.1, `RDR_to_PC_NotifySlotChange`).
     *
     * Returns one entry per slot, true where a card is present. Null when the
     * reader has no interrupt endpoint, which §6.3 permits, when the transport
     * is closed while waiting, or when no notification arrives before
     * [timeoutMs]; poll [cardPresent] instead.
     *
     * The interrupt endpoint is independent of the bulk pair, so this waits
     * without the transport lock and leaves the card usable meanwhile.
     */
    fun awaitSlotEvent(timeoutMs: Int): SlotEvent? {
        val endpoint = interruptIn ?: return null
        val buffer = ByteArray(endpoint.maxPacketSize)
        val n = runCatching {
            connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        }.getOrDefault(-1)
        if (n < 2) return null
        return when (buffer[0].toInt() and 0xFF) {
            // §6.3.1: two bits per slot, the lower saying a card is present and
            // the upper that it changed. Only presence is surfaced.
            Ccid.RDR_TO_PC_NOTIFY_SLOT_CHANGE -> SlotEvent.Changed(
                BooleanArray(slotCount) { i ->
                    val byteIndex = 1 + (i / 4)
                    if (byteIndex >= n) false
                    else (buffer[byteIndex].toInt() ushr ((i % 4) * 2)) and 0x01 != 0
                }
            )

            // §6.3.2: bSlot, bSeq, then bHardwareErrorCode. Dropping this reads
            // an overcurrent as though nothing had happened.
            Ccid.RDR_TO_PC_HARDWARE_ERROR -> if (n < 4) null else SlotEvent.HardwareError(
                slot = buffer[1].toInt() and 0xFF,
                code = buffer[3].toInt() and 0xFF,
            )

            else -> null
        }
    }

    private companion object {
        const val TAG = "ccid"

        /**
         * CCID §6.1.4: bBWI extends the block waiting time and applies only at
         * character and TPDU level. Zero leaves the reader's negotiated value
         * alone, which is what it already agreed with the card.
         */
        const val BWI = 0

        const val WRITE_TIMEOUT_MS = 3_000
        const val CONTROL_TIMEOUT_MS = 1_000

        /** An on-card RSA operation regularly takes seconds. */
        const val READ_TIMEOUT_MS = 20_000

        const val DRAIN_TIMEOUT_MS = 50
        const val MAX_DRAIN = 4096

        /**
         * An extended-length response and its status word, for a reader that
         * declares no `dwMaxCCIDMessageLength` of its own.
         */
        const val MAX_RESPONSE = 65_538

        const val READY_MS = 60L
        const val READY_TRIES = 10

        const val MAX_WTX = 60
        const val MAX_CHAIN = 64

        /** 7816-3 §11.6.3.2 Rule 7.4.2: two further attempts before S(RESYNCH). */
        const val MAX_RESENDS = 2

        /** How many stray replies to discard while waiting for an abort to land. */
        const val MAX_ABORT_REPLIES = 8

        /** A T=0 command deferring more often than this is not going to finish. */
        const val MAX_T0_STEPS = 64
    }
}

/** Locates the CCID interface on a device and reads its class descriptor. */
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
        val raw = connection.rawDescriptors ?: return fallback
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
                val mechanical = le32(raw, i + 36)
                val features = le32(raw, i + 40)
                val maxLen = if (length >= 48 && i + 47 < raw.size) le32(raw, i + 44) else 0
                // Table 5.1-1: bMaxSlotIndex at offset 4; slots are one more.
                val slots = (raw[i + 4].toInt() and 0xFF) + 1
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
        return fallback
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}
