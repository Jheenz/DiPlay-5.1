# Phase 3D.3B - offline wired CarPlay protocol validation

## Scope and verdict

Offline source audit and synthetic/mock testing only. No iPhone, USBMUX, CarKit application data, real MFi credentials, or real signing was used. Production CarPlay remains disabled; existing Lockdown pairing/TLS behavior was not changed.

**Verdict:** The implemented wired iAP2 path is internally testable and the reviewed framing/state logic has focused synthetic coverage. Two source-confirmed state/parser defects were corrected. This does **not** establish Apple CarKit application-protocol compatibility, MFi authorization, or E01 interoperability. Private Apple behavior not established by an authorized reference remains explicitly unverified.

## Complete path after CarKit TLS

1. `LockdownCarKitClient.openService()` (used by the normal controller; Phase 3D.2W used an isolated equivalent) runs Lockdown StartSession/TLS and encrypted StartService, opens the returned service TCP port, and performs service TLS when requested.
2. `CarPlayController.runStack()` wraps the resulting service byte stream in `Iap2Session.open()`, which owns an `Iap2CsmChannel`, `Iap2LinkChannel`, link worker, and underlying service stream.
3. `Iap2LinkEngine` emits the iAP2 marker, performs wired synchronization, validates frames/checksums, negotiates session-10 payload limits, and transports session data. `Iap2LinkChannel` serializes stream I/O on one worker and bounds queues. `Iap2CsmChannel` frames complete CSM messages and splits them to the peer's negotiated session-10 limit.
4. `Iap2WiredControlClient.run()` performs Identification; runs `Iap2MfiAuthenticationClient`; sends power/subscription messages; then receives CarPlay availability and may emit CarPlayStartSession. The actual controller separately attaches NCM/VPN/AirPlay infrastructure around this control stage. None of those production operations were invoked by these tests.
5. The controller owns cleanup. `Iap2Session.close()` closes CSM, which closes the link; the link closes the owned service stream and joins its worker with a bounded wait. Close failures are propagated rather than silently reported as success.

## Protocol audit

### Framing and checksums

- iAP2 link frames use the `FF 5A` header, big-endian length, control/sequence/ack/session fields, a header checksum, payload, and payload checksum. The receiver accepts arbitrary transport fragmentation, validates checksums, and bounds the total frame to the 16-bit wire limit.
- The link engine synchronizes fixed-width fields and three-byte session descriptors. During review, `SynchronizationPayload.decode()` was found to silently ignore a one- or two-byte trailing partial descriptor. Encoding can only produce complete three-byte descriptors, so the decoder now rejects a synchronization payload whose descriptor tail is incomplete. A focused regression verifies rejection and the link's resulting failed negotiation.
- CSM framing is `0x4040 | u16 total length | u16 message ID | body`, with no CSM checksum. Its framer handles fragmented/concatenated frames, waits for truncated data, resynchronizes after an invalid short length, and has a bounded receive buffer. CSM TLV parameter parsing rejects truncated headers and lengths extending beyond the body. `splitForLink()` validates one complete CSM frame before producing bounded defensive-copy chunks.

### Link state, sequence/ACK, retransmission and deadlines

- Wired mode initiates marker/synchronization and uses session 10, control-session version 2, max outgoing 4, and `zeroAcknowledgements=true`. The offline test inspects the emitted synchronization advertisement. This profile is present in source and attributed there to the LIVI-compatible wired implementation; it is not independently proven against Apple's private specification.
- The engine tracks modulo-256 send/receive sequence state, ACK windows, queued/unacknowledged packets, bounded out-of-order packets, extended ACK requests, peer retransmission deadlines, and retry limits. Synthetic tests exercise successful negotiation, sequence-ordered release of out-of-order packets, ACK clearing, retransmission, retry-limit termination, checksum rejection/recovery, EOF, and malformed synchronization descriptors.
- One ambiguous implementation detail remains unverified against a normative Apple reference: retransmission counter boundaries and peer ACK-window edge semantics. Tests describe current behavior; they do not certify interoperability.

### Identification and MFi state machines

- `Iap2IdentificationClient` waits for negotiated session readiness, sends IdentificationInformation only after StartIdentification, accepts IdentificationAccepted, maps IdentificationRejected parameter IDs, and times out/fails on unexpected messages. Review found that it previously accepted IdentificationAccepted even if it had not sent IdentificationInformation. It now fails closed in that state. It does not attempt to trim optional fields or retry after rejection.
- `Iap2MfiAuthenticationClient` uses an injected `MfiAuthenticator`: it obtains certificate bytes, responds to AA00 with AA01, signs the AA02 challenge and sends AA03, requires AA05, and fails on AA04, malformed challenges, unexpected messages, or timeout. Review found it previously treated AA05 as success even before any AA03 response. It now rejects success until a challenge response send completes.
- These guards enforce local state consistency, not an undocumented full Apple ordering policy. Other request orderings such as repeated AA00 or AA02 are not newly restricted without authoritative protocol documentation. Tests use only deterministic mock certificate/signature values.

### Unsupported messages and shutdown

Identification and MFi phases fail closed on unexpected message IDs. After authentication, `Iap2WiredControlClient` explicitly routes non-availability/non-location/non-vehicle frames to its `onIncoming` callback rather than terminating the link. The controller's current route handler forwards frames to component handlers and otherwise leaves them unhandled; that policy is implementation behavior, not an Apple guarantee. No unsupported CarKit payload was synthesized or transmitted beyond offline fake sessions.

Clean channel/EOF and bounded timeout outcomes are tested. `Iap2LinkChannel.close()` closes the owned byte stream, stops the worker, and propagates close/worker failures. There is no graceful application-level CarKit shutdown protocol verified offline; service TLS/Lockdown cleanup is covered in prior 3D.2W phases.

## References and limits

- Repository attribution credits [LIVI](https://github.com/f-io/LIVI) and Showcase for protocol research ([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)). LIVI-derived comments and vectors are useful implementation references, not a normative Apple compatibility statement.
- Apple's public [MFi program](https://mfi.apple.com/en/how-it-works.html) describes licensed technologies and access to technical specifications/resources. Those private specifications were not available in this audit.
- The existing source and synthetic tests provide implementation-level facts. They cannot establish that an iPhone accepts DiPlay's Identification fields, AA00-AA05 behavior, wired zero-ACK profile, session descriptions, CarPlayAvailability response assumptions, or CarPlayStartSession payloads.
- 3D.2W verified the transport/TLS/StopSession boundary, not application-protocol acceptance. No real MFi provider is established for the E01.

## Changes and tests

Source changes:
- `Iap2Session.kt` now implements a minimal `Iap2MessageSession` contract to permit deterministic offline state-machine tests; the live implementation is unchanged.
- `Iap2IdentificationClient` rejects IdentificationAccepted before IdentificationInformation was sent.
- `Iap2MfiAuthenticationClient` rejects AA05 before an AA03 challenge response was sent.
- `Iap2LinkEngine.SynchronizationPayload.decode()` rejects incomplete trailing session descriptors.

Added/extended offline coverage in `Iap2LinkEngineProtocolTest`, `Iap2WiredStateMachineTest`, and `Iap2ProtocolTest`. The focused selection also ran existing `Iap2WiredControlClientTest`, `Iap2ControlDeadlineTest`, `Iap2PreAuthProbeTest`, link/file-transfer, wireless-link-role, and mocked/local/remote MFi provider tests. **55 tests passed, 0 failed.** No real credential was used.

`./gradlew :mobile` is not a task in this repository; `./gradlew :mobile:assembleDebug --max-workers=1` succeeded. The APK is `0.2.12-api22-phase3d2z-mfi-provider-snapshot`, `minSdk=22`. Robolectric tests run under SDK 28; API22 package compatibility is verified by build metadata, not by an Android 5.1 runtime test. Production connection startup remains disabled.

## Remaining uncertainty and next phase

**Recommended next phase: 3D.3C - authorized protocol-reference and provider handoff review.** Obtain Geely/Neusoft or authorized MFi-licensee documentation for the E01's legitimate MFi provider and the supported wired iAP2/CarKit sequence, especially the wired synchronization/zero-ACK profile, session descriptors, Identification rejection/acceptance requirements, and MFi AA exchange ordering. Success criteria: an identified authorized provider/access contract and an authoritative sequence/test-vector source against which DiPlay's offline fixtures can be compared. Do not send application traffic until those prerequisites and a legitimate provider are established; a further E01 test is not justified by this offline phase alone.
