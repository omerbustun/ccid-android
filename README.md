# ccid-android

A USB CCID smart-card transport for Android, in Kotlin.

Android has no smart-card stack, so an application that needs a card in a reader
has to speak CCID itself. This library is that layer: the equivalent of
CryptoTokenKit on Apple platforms and pcsc-lite on Linux.

- **CCID framing**, all fourteen bulk-OUT commands, every exchange level
- **T=1 and T=0** on the host, for readers that pass blocks through rather than
  assembling APDUs themselves
- **Reader discovery**, multi-slot readers, and the USB permission flow Android
  requires
- **Slot-change and hardware-error notification** from the interrupt endpoint
- **PIN-pad readers**, so the PIN never enters the calling process
- **No dependencies** beyond kotlinx-coroutines
- **Apache-2.0**

## Why it exists

Android ships no smart-card API for a USB reader. The CCID code that exists is
copyleft, or a JNI port of a C driver, or tied to one vendor's hardware, and
every permissively licensed one of them sends whole APDUs in
`PC_to_RDR_XfrBlock` and needs a reader that assembles them.

| Project | License | Shape |
|---|---|---|
| [OpenKeychain][ok] | GPL-3.0 | Runs the block protocols on the host, and unusable in a closed-source application |
| [pcsc-lite CCID][mk] | LGPL-2.1 | The reference C driver ported to Android, over JNI, and built around pcscd |
| [YubiKit][yk] | Apache-2.0 | Shaped around YubiKeys |
| [Multipaz][mp] | Apache-2.0 | A CCID driver inside a digital-credentials SDK |
| [nfcim/ccid][nc] | MIT | The Android half of a Flutter plugin |
| [android-pcsclike][sc] | SpringCard only | Kotlin and reader-agnostic in shape, but usable with SpringCard hardware alone |
| [RIA DigiDoc][rd] | LGPL-2.1 | Reaches readers through the ACS and Identiv SDKs |

A reader reporting TPDU level hands the T=1 block layer back to the host. That
layer, with the ATR parsing and the error recovery it needs, is most of what is
here.

[ok]: https://github.com/open-keychain/open-keychain
[mk]: https://github.com/mikma/ccid-android
[yk]: https://github.com/Yubico/yubikit-android
[mp]: https://github.com/openwallet-foundation/multipaz
[nc]: https://github.com/nfcim/ccid
[sc]: https://github.com/springcard/android-pcsclike
[rd]: https://github.com/open-eid/MOPP-Android

## Install

Nothing is published to a repository yet. Include the build and depend on the
coordinate it publishes; Gradle substitutes the local project for it.

```kotlin
// settings.gradle.kts
includeBuild("../ccid-android")
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.ustun:ccid:0.1.0")
}
```

Runs on API 21 and up, on a device with USB host mode. Consuming projects
compile against SDK 37 or later.

## Use

```kotlin
suspend fun read(context: Context): ByteArray? {
    val readers = CcidReaders(context)

    val device = readers.attached().firstOrNull() ?: return null
    if (!readers.requestPermission(device)) return null   // the user declined

    val transport = readers.open(device)
    return try {
        if (!transport.cardPresent()) return null
        transport.use { card ->
            card.transmit(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x00))
        }
    } finally {
        transport.close()
    }
}
```

`requestPermission` suspends. Everything else blocks, for as long as the card
takes to answer, so none of it belongs on the main thread.

`use` brackets one complete operation rather than a single APDU. A signature is
SELECT, VERIFY PIN, then the key operation, and the verified-PIN state survives
only while the card stays powered and undisturbed. The card is powered up on
entry and down on exit, so a verified PIN cannot outlive the operation that
established it.

`readers.events()` reports readers arriving and leaving. It emits once on
collection and then on every attach, detach and permission grant.

One transport per reader. Several readers may be driven concurrently, but two
connections to the same reader interleave on its bulk endpoints and each
consumes the other's replies; `open` refuses the second.

Multi-slot readers take a slot index: `readers.open(device, slot = 1)`.
`transport.slotCount` reports how many there are, and one transport drives one
of them at a time.

Where a reader has an interrupt endpoint, `transport.awaitSlotEvent(timeoutMs)`
reports insertion and removal as the reader observes them, in place of polling
`cardPresent()`. The same endpoint carries hardware errors, so the result is a
`SlotEvent.Changed` or a `SlotEvent.HardwareError`.

`transport.clockFrequencies()` and `transport.dataRates()` list what a reader
will accept, which is what makes `setDataRateAndClockFrequency` usable.

## Tests

```
./gradlew :ccid:testDebugUnitTest
```

The protocol layers carry no Android types and are tested on the JVM. Each
expected value is one a standard states outright, because a test that recomputes
an answer the way the implementation does cannot tell a correct implementation
from a consistent misreading of the specification. The suite is checked by
re-introducing known defects and confirming it goes red for each.

## Specifications

| Document | Covers |
|---|---|
| [USB CCID 1.1][ccid] | message framing, `bStatus`/`bError`, `dwFeatures`, chaining, the control-pipe requests |
| ISO/IEC 7816-3:2006 | the ATR (cl. 8), T=0 (cl. 10) and T=1 (cl. 11) |
| ISO/IEC 13239 §4.2.5.2 | the CRC epilogue 7816-3 defers to |
| [USB 2.0][usb] | bulk transfers, standard requests, descriptor layouts |

[ccid]: https://www.usb.org/documents
[usb]: https://www.usb.org/documents

## Implemented but not yet exercised in hardware

The following is written against the standards, but no device available to me
for testing reaches it. Treat it as untried rather than proven. Reports from
hardware are the most useful contribution anyone can make.

- T=0
- CRC epilogue
- Multi-slot readers
- PIN-pad entry
- Slot-change notification
- Parameters, Escape and Abort
- Clock stop, T=0 class bytes, motorised functions and the data rate
- The control-pipe requests: ABORT, GET_CLOCK_FREQUENCIES, GET_DATA_RATES

## Not implemented

- **Recovery level three.** 7816-3 §11.6.3.1 escalates retransmission to
  S(RESYNCH) to warm reset. The first two are implemented. A warm reset
  discards a verified PIN mid-operation, so the decision is left to the caller.
- **Character-level readers.** Refused at `open`. They place the entire
  character layer on the host and no reader in production uses the level.
- **Extended-length APDUs longer than one CCID message.** The reader's
  `dwMaxCCIDMessageLength` is enforced; over-long commands are refused rather
  than truncated.
- **A definitive FCS byte order for CRC.** 7816-3 §11.3.4 defines the epilogue
  as two bytes and defers the value to 13239; neither states the order on the
  wire. High byte first is used here.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). You may use this in a
closed-source application; redistribution in source or binary form must retain
the copyright, license and attribution notices and reproduce the contents of
NOTICE.
