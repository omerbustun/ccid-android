// Copyright 2026 Ömer Üstün
// SPDX-License-Identifier: Apache-2.0

package io.ustun.ccid

/**
 * An exclusive session with a card.
 *
 * [begin] and [end] bracket one complete operation rather than a single APDU.
 * Card state established mid-operation, such as a verified PIN, persists only
 * while the card remains powered and undisturbed; implementations power the
 * card up on [begin] and down on [end].
 *
 * Callers must invoke [end] exactly once for every [begin] that returns
 * normally, including where the intervening operation failed. [use] does this.
 *
 * Implementations are safe to call from any thread, and every call blocks for
 * as long as the card takes to answer, so none of them belongs on the main one.
 */
interface CardSession {

    /** Take the card and power it up. */
    @Throws(CcidException::class)
    fun begin()

    /** Release it. Never throws; the operation is already over. */
    fun end()

    /** One command APDU in, the response and its status word out. */
    @Throws(CcidException::class)
    fun transmit(command: ByteArray): ByteArray

    /** The answer-to-reset from power-up. Valid between [begin] and [end]. */
    @Throws(CcidException::class)
    fun atr(): ByteArray
}

/** Run [body] within a session, ending it however [body] exits. */
inline fun <T> CardSession.use(body: (CardSession) -> T): T {
    begin()
    try {
        return body(this)
    } finally {
        end()
    }
}

/**
 * A card or reader failure.
 *
 * Messages are English and intended for logs. Applications presenting failures
 * to end users should branch on [reason] and supply their own text.
 */
class CcidException(
    message: String,
    /** What went wrong, for callers that branch rather than display. */
    val reason: Reason = Reason.OTHER,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Reason {
        /** No reader is attached, or it was detached. */
        NO_READER,

        /** USB access to the device has not been granted. */
        NO_PERMISSION,

        /** A reader is attached but holds no card. */
        NO_CARD,

        /** The card did not respond, or its response was not intelligible. */
        CARD_UNRESPONSIVE,

        /** The reader requires a capability this library does not implement. */
        UNSUPPORTED_READER,

        /** Another transport already holds this reader. */
        READER_BUSY,

        /** Transfers failed, or protocol recovery was exhausted. */
        COMMUNICATION,

        OTHER,
    }
}
