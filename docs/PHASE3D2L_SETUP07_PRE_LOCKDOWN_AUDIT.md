# Phase 3D.2L - Setup07 pre-Lockdown audit

## Scope and confirmed baseline

**STATIC AUDIT ONLY. No hardware operations or QDrive native execution.**
No transport, pairing, authentication, network or projection code was executed.
No runtime behavior, tests, build configuration or APK was changed for L.
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false` remains unchanged.
The existing uncommitted J/K implementation and documentation were preserved.
Base HEAD is `de306d1`; source references below describe the current working
tree including K's byte-identical version-packet extraction.

Read first: [J audit](PHASE3D2J_FIRST_USBMUX_EXCHANGE_AUDIT.md),
[K guide](PHASE3D2K_USBMUX_VERSION_TEST.md) and current transport implementation.

The user now confirms real **K PASS**: direct E01 iPhone, active configuration
5, scoped USBMUX claim successful, one version request/reply successful.
**No setup07 or Lockdown was started.** Treat this as confirmed user-reported
hardware evidence, not a developer-run test. No raw K response/minor bytes
were supplied with this result, so its setup acknowledgment cannot be inferred.
Historical active5 PASS is not proof of the state of a future connection.

## 1. Result: independent mux setup, with no defined setup reply

Setup07 is a **USBMUX protocol-2 bulk frame**, not a standard USB control
SETUP transaction, configuration setter, vendor request or Lockdown message.
Its one-byte payload is `07`; it contains no TCP header, service port, plist,
HostID, pairing record, certificate or Trust decision.

The exact send and stopping boundary are identified. **No dedicated reply
shape, ACK wait or setup-response validator is implemented** in DiPlay or
the recovered QDrive version-to-setup continuation. Do not invent a
17-byte echo, protocol-2 IN reply or requirement for one.
This establishes an isolated **submission** experiment, not a claim that
the iPhone has semantically accepted setup or that TCP is ready.

The internal meaning of the bits in `07` is not documented by the examined
implementations. They establish its role in v2 initialization, not an Apple
protocol specification or guaranteed reset/restore semantics.

## 2. Exact DiPlay call path and packet

[Iap2UsbMuxHost](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt):

1. `open`, line286, constructs a host and invokes `begin`, line108.
2. `begin` writes [UsbMuxVersionPacket.request](../shared/src/main/java/com/shilapi/xcertplay/transport/UsbMuxVersionPacket.kt):
   the audited 20-byte initial version request.
3. It accepts protocol0/length20/major2 through `takeFrame`, line153.
   That function copies `frame.sequence` into `nextMuxAcknowledgement`.
4. `begin`, line144, invokes `sendFrame(PROTOCOL_SETUP, byteArrayOf(7))`.
5. `sendFrame`, line180, builds a 16-byte header plus the one-byte body,
   writes it, then increments the next transmit sequence.
6. Immediately afterward `begin` starts the daemon reader and returns.
   It does **not** wait for a setup-specific response.
7. A caller must separately invoke `connect` to send a TCP SYN.

All multibyte setup fields are **big-endian**:

| Offset | Bytes | Value / meaning |
|---|---|---|
| 0 | 4 | `00000002`: mux protocol SETUP |
| 4 | 4 | `00000011`: total length17 |
| 8 | 4 | `FEEDFACE`: negotiated outbound v2 magic |
| 12 | 2 | `0000`: first post-version transmit sequence |
| 14 | 2 | `AA AA`: DiPlay's reply-derived mux acknowledgment |
| 16 | 1 | `07`: setup payload |

```text
00 00 00 02  00 00 00 11  FE ED FA CE  00 00  AA AA  07
```

**The initial version wire header is only eight bytes.** Offset12..15 of
its reply is the version minor word, not a genuine v2 sequence field.
[UsbMuxFrameBuffer](../shared/src/main/java/com/shilapi/xcertplay/transport/UsbMuxFrameBuffer.kt)
uses a generic 16-byte representation and calls bytes12..13 `sequence`.
Consequently DiPlay's `AA AA` equals the two high bytes of the accepted
version reply's minor word. With minor0 the exact setup packet is:

```text
00 00 00 02 00 00 00 11 FE ED FA CE 00 00 00 00 07
```

Do not silently describe that as the QDrive packet or assume K required
minor0: K accepts other minor/reserved values.
There is no separate transaction tag or nonce in setup.
Successful write moves DiPlay's next transmit sequence from0 to1.

### Endpoints, timeouts and retries

- Same claimed configuration5 interface1/alt0/255.254.2.
- Setup OUT: **bulk0x04**, exactly17 logical bytes.
- `sendFrame` uses **2000ms** write budget.
- [Iap2UsbSession.write](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt),
  line340, calls Android bulkTransfer through
  [UsbTransferCompatibility.writeFully](../shared/src/main/java/com/shilapi/xcertplay/transport/UsbTransferCompatibility.kt).
  A positive short write can cause more writes for the unsent suffix within
  the deadline. This is not a whole-frame retransmission, but is incompatible
  with an exactly-one-OUT diagnostic unless explicitly replaced by a
  one-shot transfer that stops on short count.
- Failed/incomplete write throws and closes the full host's pipe through
  `fail`; there is no setup retransmission loop.
- Subsequent general IN uses **bulk0x85** through UsbRequest, not interrupt
  transfer. API22 capacity is16384 and scheduled cancellation implements
  its timeout; blocking requestWait cancellation is driver-dependent.
- Reader poll default1000ms, direct diagnostic500ms. It keeps polling while
  open; there is **no overall setup-reply deadline**.
- `readerLoop`, line203, dispatches only protocol6. Non-TCP frames are
  consumed without a setup-success validator; `takeFrame` still updates ACK.
  Absence of a reader error is not setup acceptance.

**Existing full `open()` is unsuitable for M:** it starts persistent IN,
has stale-TCP discard/fragment/padding behavior, and callers may immediately
connect Lockdown. M must be an isolated prefix, not a call followed by a
hope that closing happens before the caller continues.

## 3. Exact response contract and limits of validation

| Question | Established answer |
|---|---|
| Required setup reply bytes/length/protocol | None identified or required by the examined startup paths. |
| Does the host wait for setup ACK? | No. DiPlay starts its reader; QDrive marks active and notifies higher code. |
| Is no response guaranteed? | No. Unsolicited mux control or stale TCP data may arrive. |
| Does USB full-write count prove device protocol acceptance? | No; it proves transfer completion, not interpretation or readiness. |
| Can a setup-specific response PASS be defined from this evidence? | No. Do not invent one or treat timeout as successful acknowledgment. |

Pinned [upstream usbmuxd device.c](https://github.com/libimobiledevice/usbmuxd/blob/3ded00c9985a5108cfc7591a309f9a23d57a8cba/src/device.c)
corroborates: `device_version_input` sends setup07 for v2, immediately marks
active and invokes preflight. `device_data_input` dispatches VERSION0,
CONTROL1 and TCP6, not a dedicated setup-response state.
`device_control_input` logs control payload types3/error,5/warning,7/info;
those are not a correlated setup ACK. Payload `07` in protocol2 is not
interchangeable with an incoming protocol1 informational message.
These are implementation facts, not guaranteed iOS response behavior.

No setup IN capture was supplied for E01. K only tested the preceding version
exchange. An optional future observation read would be a separately specified
additional operation with no established success response; it is not necessary
for the smallest submission test and is not included below.

## 4. QDrive comparison: static native continuation

Local [libusbserver.so](../vendor-apks/QDrive_Global/lib/arm/libusbserver.so),
SHA256 `87748337AB3B0BB70AFB037E412454C74B1BB419CA01E270B666EB225C8AEA54`.
Read-only Thumb disassembly rechecked setup construction and immediate continuation.
No library was loaded or executed.

| Address | Evidence |
|---|---|
| 0x939DA..0x939FC | Decode version major/minor; accept1 or2, store negotiated major. |
| 0x93A00..0x93A0E | For major2 call sender0x93F34 with protocol2, one-byte body; literal resolves to0x26D6EE containing07. |
| 0x93FDE..0x93FE4 | Special protocol2 path resets receive sequence toFFFF and transmit sequence to0. |
| 0x94004..0x94012 | Encode ACKFFFF/tx0 into header; increment tx. |
| 0x94036 ->0x95348 | Submit bulk packet on same registered device. |
| 0x95366/0x95370/0x95378/0x95384 | Stored OUT endpoint04; bulk type2; async timeout0; submit. |
| 0x93A12..0x93A44 | No setup-reply wait or setup-send return check here; log connection, mark active, initialize connection collection. |
| 0x93A72 ->0x968D8 | Notify higher device code. That continuation must not be reproduced by an isolated test. |
| 0x9374E..0x93758 | Incoming dispatch distinguishes TCP6, CONTROL1 and VERSION0, not a setup-ACK branch. |

Exact QDrive/upstream setup:

```text
00 00 00 02 00 00 00 11 FE ED FA CE 00 00 FF FF 07
```

Compared with DiPlay: same protocol, length, magic, tx0, body07 and OUT04;
**ACK differs**, even after minor0. QDrive explicitly resets it; DiPlay
retains the reply-derived value. Neither K PASS nor static code proves
which ACK behavior this E01 iPhone accepts after setup.
No runtime fix or claim of equivalent packets is made in L.

Native OUT uses timeout0 (no finite transfer timeout); error completion
logs/marks inactive through its callback. No setup resend loop is shown.
The sender's optional ZLP applies only when length is divisible by endpoint
maxPacketSize; M's inherited maxPacketSize>20 excludes it for17 bytes.
Normal async IN85/16384/timeout0 was submitted after version OUT and remains
active, with successful reads resubmitted. It is not a single setup reply read.
The earlier repeated4096/200ms pre-version drain remains unsuitable for M.

The native setup send itself has no port/TCP/Pair fields. Notification can
activate other higher-layer code; this audit does **not** certify full QDrive
startup or running usbmuxd as pre-Lockdown-safe.
Upstream explicitly launches preflight after setup; do not reuse that daemon.

## 5. Boundary and operations that must remain outside M

```text
USB setup / active config5 / scoped claim
    -> USBMUX VERSION0 request/reply
    -> USBMUX SETUP2 body07                 [proposed M STOP before TCP]
    -> USBMUX TCP6 SYN(destination port)
    -> Lockdown:62078
    -> new-host Pair / user Trust
    -> StartSession / TLS / StartService
```

Important qualifications:

- "USB setup" above describes enumeration/configuration/ownership; it is
  not the setup07 bulk frame. M verifies active5, never selects it.
- TCP6 can address ports other than62078. Setup has **no port at all**.
- First explicit Lockdown wire operation is `connect(62078)` ->
  `Iap2UsbMuxTcpConnection.beginConnect`, line432 -> TCP_SYN ->
  `sendTcp`, line77, destination `0xF27E`, wrapped in mux protocol6.
- [DirectUsbMuxDiagnostic](../shared/src/main/java/com/shilapi/xcertplay/transport/DirectUsbMuxDiagnostic.kt)
  calls full open, then62078, then GetValue(ProductType). It is not M-safe.
- [CarPlayController.runStack](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt),
  line1829, calls full mux.open, constructs a pairing client and **loads
  pairing records at line1839**, before deciding whether to pair. Record
  access is a local side-effect boundary, not implied by setup wire traffic.
  The normal caller also initializes NCM earlier. Do not run it.
- [LockdownPairingClient.pair](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairingClient.kt)
  connects62078, sends SetValue(UntrustedHostBUID), GetValue(DevicePublicKey/
  WiFiAddress), constructs a record, then Pair. Pair handles Trust-pending,
  denial and retry. The preparatory BUID write is already stateful Lockdown.
- [LockdownCarKitClient.openService](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt)
  uses a record to connect62078, sends StartSession, enables TLS, then
  StartService. Saved trusted records can skip new Pair/Trust: the diagram
  is a new-host conceptual progression, **not an obligatory linear call graph**.

Setup07 neither reads/writes records nor calls any of these APIs.
It contains no Pair/Trust request. That does not guarantee iOS cannot display
an independent prompt in response to USB state changes. Leave existing/new
Trust UI untouched; a new prompt/state change is a STOP, not an invitation
to approve or reject it.

## 6. Proposed M: minimum isolated setup submission experiment

**DESIGN ONLY. M is not implemented or authorized for execution by L.**

Recommended first profile: **preserve current DiPlay setup bytes**, including
reply-derived ACK. Do not silently introduce QDrive'sFFFF reset while calling
it the same implementation. If a QDrive-equivalent profile is later desired,
authorize/name it separately; do not try both or fall back on failure.

### Exact operation budget

1. Separate manually confirmed action, never auto E/G/I/K/full host/daemon.
   Keep normal connection gate false and exclude every other active USB action.
2. Reuse K/I preconditions: exactly one permitted05AC:12A8, exactly five
   configurations, unique config5, exact Valeria-name/descriptor evidence,
   NCM control/data metadata, unique scoped ID1/alt0/255.254.2 with bulk04/85.
   Retain K packet-size constraints (>20, IN<=1024). No flattened index.
3. Open once; same-handle GET_CONFIGURATION80/08/0/0/length1/1000ms must
   return count1,value5. Claim exactly once with force=false.
4. In this **same claim and connection**, repeat K's one20-byte version OUT04,
   timeout1000ms, require count20; one1024-capacity IN85, timeout1000ms,
   require count20/protocol0/declared20/major2. Prior K PASS cannot replace
   version negotiation on this handle. No drain, padding skip or continuation.
5. Derive ACK from actual accepted reply bytes12..13, exactly as DiPlay does.
   Construct protocol2/length17/FEEDFACE/tx0/that ACK/body07 and report it.
   Validate locally that all17 bytes equal the selected profile **before send**.
6. **One setup bulk OUT04**, length17, proposed timeout2000ms to match
   DiPlay's write budget. Require count17; any short/negative result STOP.
   No suffix completion, resend, optional ZLP, reset or alternate profile.
7. **No post-setup IN request** in the minimum experiment. No setup-response
   validator exists to justify one. Record "setup response not awaited or
   tested", not "ACK received" or "no response exists".
8. Recheck passive identity/inventory/events; cancel/detach suppresses PASS.
   Release exactly once only if claimed; nested finally-close regardless.
   No FIN/RST, configuration restoration, drain or recovery during cleanup.
9. Save report and STOP.

Successful operation budget: **one GET_CONFIGURATION, one claim, two bulk
OUT calls (version20 + setup17), one bulk IN call (version), one release,
one close; zero TCP/Lockdown/pairing calls, zero retries.**
The setup send may change iPhone mux state; closing does not prove reversal.
Do not auto-repeat an experiment or reconnect as a harmless reset.

### Validation and honest outcome

Require all preconditions/readback/version checks, exact selected setup packet,
full17-byte setup transfer, stable inventory/no detach/cancellation, successful
release/close and USB observer cleanup.
Suggested narrow PASS:

```text
SETUP07 OUT TRANSFER CONFIRMED
SETUP RESPONSE / TCP / LOCKDOWN / PAIRING NOT TESTED
```

This validates **host packet construction and completed submission**, not
the meaning of07, device acceptance, full mux initialization, TCP readiness,
authentication or CarPlay. If the requirement instead becomes "prove setup
protocol acceptance by exact response", this evidence is insufficient:
more response evidence/analysis is required, not an invented timeout PASS
or a probe to62078.

Report device/configuration inventory, same-handle active5, exact interface/
endpoints, profile name and ACK derivation, version fields, outbound20/17 hex,
counts/timeouts/timestamps/timings, state events, release/close, zero post-setup
reads/TCP/Lockdown/record access. Only retain valid version bytes and generated
setup bytes; do not dump unexpected pending TCP/plist/authentication payloads.
Version lacks a nonce: freshness limitation remains as documented in K.

### STOP cases and future test requirements

- Wrong permission/identity/config5/Valeria/NCM/interface/endpoints: no open/
  claim/protocol progression; standard K classifications.
- GET failed/short/not5 or claimfalse: no bulk; no setter/force/retry.
- Version OUT/IN timeout/failure/short/extra/malformed/wrong fields: no setup.
  Do not discard unexpected data or assemble another completion.
- Wrong constructed setup/profile/ACK: STOP before setup OUT.
- Setup OUT exception/negative/short/impossible count: explicit transfer
  failure, release/close; never assume setup was not partially delivered.
- Detach, re-enumeration, cancellation, changed inventory, new Trust UI:
  STOP cleanup only; no answer/reopen/reclaim.
- Release/close/observer failure: retain transfer evidence, no overall PASS.

Future tests must prove the exact17-byte packet for zero and nonzero minor
bytes; profile choice/ACK derivation; success counts2 OUT/1 IN; setup prevented
by every K failure; one setup attempt maximum, no suffix retry; no post-setup
read; no full-host/TCP/record/Lockdown dependency; cleanup/cancellation/detach
on every exception path and manual-only confirmation/export/exclusion.
No M code or tests were created or executed in L.

## 7. Remaining uncertainties and static verification

Unresolved:07 bit semantics; device-side setup acceptance without TCP;
E01 unsolicited post-setup data; ACK0000/reply-derived versus QDriveFFFF
compatibility; Trust UI causality; exact current K response bytes; stability
of state after prefix-only close. None makes setup itself a Lockdown packet.
The isolated test above deliberately does not claim to resolve these.

Source/native fingerprints at L start and completion match:

- Current host: `D777068C1F97B54F8526B1347F2E744D2589E5AD33AF0A15FB68D4668A00E6CC`
- K engine: `5B12C2EAE3DDD2462934998CE849F4711CFD99809F6999ADA81E61A52F38A8B8`
- K adapter: `77FAD50E8F3320C65CCFBE794A748C71CD66A10C5DF1AA348E0C448F591050FE`
- QDrive native hash as listed above.

Evidence consists of current source, J/K reports, static Thumb disassembly,
literal data and pinned public upstream implementation. No secrets/records
were inspected; no binaries executed. Documentation links/whitespace checked.
No tests/build are necessary for this documentation-only phase.
The verdict below refers to the exact host-side packet, lack of a required
reply in these implementations, safety boundary and submission-only test.
It does not certify undocumented payload bits or a device acceptance response.

SETUP07 FULLY IDENTIFIED — ISOLATED HARDWARE TEST CAN BE DESIGNED
