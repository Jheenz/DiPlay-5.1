# Phase 3D.2Q - Post-Pair Lockdown TCP connection lifetime

## Scope and decision

Static audit only. No USB/iPhone/network/native execution, Pair, SetValue,
ValidatePair, session, TLS, service, authentication or projection. No source,
pairing-record persistence/lookup, tests, APK or version changes. minSdk22 and
CONNECTIONS_ENABLED=false remain unchanged.

Accepted facts: real Pair SUCCESS; protected PAIRED save; later E01 inspection
and independent PC file/store reopen show accepted-state discoverability.
The subsequent ValidatePair bulk OUT completed4972/4972, then the peer reset
the TCP stream. Device/descriptors and host remained available; RST=true,
FIN/EOF/timeout/short-write=false.

**Same-connection use is proven. A mandatory post-Pair reconnect is not.**
Normal DiPlay closes after Pair, but does not ValidatePair in that client;
that alone is not a protocol requirement. Previously recovered QDrive paths
have differing request orders, not a proven universal reconnect boundary.
Neither "same connection is correct" nor "fresh connection is required"
is established for this E01/iPhone by the repository evidence.

## 1. Exact controlled connection lifecycle

Sources and line anchors:

- [ControlledPairDiagnostic](../common/src/main/java/com/shilapi/xcertplay/ControlledPairDiagnostic.kt),
  run: lines112-137 connect/discover/dispatch; finally lines144-167 cleanup.
- [AndroidReadOnlyLockdownAccess.Session](../common/src/main/java/com/shilapi/xcertplay/AndroidReadOnlyLockdownAccess.kt),
  connectLockdown66, queryType86, prepareAndPair91, pairOperation105,
  stopTransport125.
- [ControlledLockdownPairing](../shared/src/main/java/com/shilapi/xcertplay/transport/ControlledLockdownPairing.kt),
  run48, device association52, load57, Pair100, PAIRED save114,
  ValidatePair120, exchange141.

```text
one scoped USB connection/config5 claim
-> one USBMUX host
-> Session.connectLockdown()
   -> host.connect(62078)
   -> one Iap2UsbMuxTcpConnection stream
   -> one LockdownPlistChannel(stream), retained in Session.channel
-> QueryType through that channel
-> prepareAndPair -> pairOperation(existingOnly=false)
   -> capture channel as opened and mux as host
   -> request closure always uses opened.request(...,5000)
-> association / preparation requests
-> Pair through the same closure/channel/stream
-> receive and validate Pair SUCCESS
-> construct PAIRED candidate and synchronously save
-> ValidatePair through the same closure/channel/stream
-> success or exception
-> finally transport/interface/USB cleanup -> STOP
```

connectAttempted prevents another Session.connectLockdown call. No new stream
or channel factory, detach, shutdown, close, FIN, reconnect, USB release or
host reinitialization is invoked after Pair SUCCESS and before validation.
Storage is local and has no stream/channel reference.

The channel's own closed flag remains false after Pair success:
[LockdownPlistChannel.request](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPlistChannel.kt),
lines63-71, sends/receives under ioLock without closing. Only close91 or
detach80 changes ownership/closed state. Neither is called in the transition.
The underlying TCP stream can still be independently closed by the reader;
an open plist wrapper is not proof of a live peer connection.

## 2. Reader events, peer reset and cleanup

[Iap2UsbMuxHost](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt):
readerLoop238, dispatchTcp251, TCP.send407, recv436, close466, onPacket542.

While Pair replies, storage and validation execute, the reader continues:

- Inbound TCP data is queued and ACKed; it does not close the channel.
- FIN is separately latched, ACKed, and closes/removes the stream. Drained
  recv returns empty and latches EOF; plist framing treats that as a failure.
- RST flag0x04 is handled first: latch RST, store
  DeviceUnavailable with the fixed peer-reset message, close the stream,
  notify waiters and remove its registered local port.
- recv raises that retained failure rather than EOF or timeout. A later send
  also raises it. The shared host need not close for a stream-only RST.
- A reader/global host failure instead closes all streams and the pipe;
  this is different from the latest host-active/pipe-open state.
- abort sends a local RST only on connection-establishment failure; no such
  abort is called between Pair and successful validation OUT here.
- Cleanup follows the failure. A TCP stream already closed by RST does not
  send a new FIN in close; channel/host/pipe/interface/USB teardown then
  finishes. No cleanup request is the precursor to validation in this flow.

The observed classification is exact received-RST evidence, not a guessed
USB detach or plist rejection. dispatchTcp matches the local registered port
and strict remote62078, but does not validate RST sequence/ACK against an
outstanding request. TCP RST carries no Lockdown request correlation.

## 3. Meaning of the complete write and competing explanations

The4972bytes are one USBMUX frame:

| Component | Bytes |
|---|---:|
| USBMUX header | 16 |
| TCP header | 20 |
| Lockdown length prefix | 4 |
| UTF-8 XML | 4932 |
| Total | 4972 |

The4936-byte Lockdown frame is below TCP.send's16KiB split threshold. One
complete bulk completion covers the whole serialized request locally.
sendFrame advances mux sequence after write; TCP.send advances local byte
sequence. It does not await an ACK covering these bytes or confirmation
from the Lockdown application. Normal inbound payload ACK handling remains.

The reader can observe RST concurrently with the successful OUT completion.
Log order alone does not prove when the peer emitted it, which prior request
caused it, or that validation was parsed before it.

| Interpretation | What this evidence supports |
|---|---|
| Post-Pair teardown | Compatible with delayed peer closure while local code attempts its next request; not proven normal/required behavior. |
| ValidatePair rejection | Possible stream-level response, but no correlated Lockdown Error reply proves rejection or reason. |
| Stale/invalidated connection reuse | Same object is reused; whether Pair invalidated it before validation is unknown. |
| Invalid request ordering | Possible device-specific behavior; no repository rule proves this ordering forbidden. |
| Malformed ValidatePair contents | No structural builder/framing defect found; actual iOS acceptance is not proved by local checks. |
| Physical USB loss / short write / timeout / FIN | Not indicated by the supplied state and completion; received RST is the identified event. |

Pair was already accepted. RST must not be relabeled Pair rejection or used
to regenerate/migrate/delete accepted credentials.

## 4. Normal DiPlay and bundled QDrive/reference evidence

### Normal DiPlay

[LockdownPairingClient](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairingClient.kt),
lines47-85: each attempt connects62078, creates a plist channel inside use,
and returns the accepted record from inside use. Resource unwinding closes
the channel/stream even on successful return. Pending Trust also exits use,
then loops into a fresh connection; that is retry behavior, not a single
successful-Pair validation sequence.

[LockdownCarKitClient.openService](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt),
lines25-35: creates another62078 connection and starts a session. This is a
fresh connection after normal Pair at the application level, but it does
**not** send ValidatePair. This audit does not execute or adopt that session
path. Closing due to function ownership is not proof iOS mandates reconnect.

### Already-audited bundled QDrive

Use the existing native static evidence in
[Phase3D.2N section8](PHASE3D2N_LOCKDOWN_PAIR_TRUST_AUDIT.md), not generic
external assumptions. The bundled libusbserver.so was rechecked by file hash:
`87748337AB3B0BB70AFB037E412454C74B1BB419CA01E270B666EB225C8AEA54`,
matching that audit. It was not loaded/executed, and no credentials extracted.

- Pair0xA5F24 and ValidatePair0xA5F78 call the generic operation helper0xA61E0.
  Separate wrappers show separation from session startup, not automatic
  post-Pair connection replacement.
- Application preflight0x96974 has a Pair -> StartSession/TLS -> ValidatePair
  route. Generic client_new_with_handshake0xA5D30..0xA5ED0 has a different
  QueryType/record/Pair-if-needed -> ValidatePair -> StartSession route.
  The prior structural trace does not establish a mandatory new client
  between successful Pair and ValidatePair in either route.
- Trusted-notification path0x96F7C..0x96FD8 does open a separate plaintext
  client and Pair again, then frees it. This is Trust-completion/retry and
  repeats Pair; it is not proof of a no-Pair validation reconnect rule.
- Saved-session failure paths can recreate clients/remove records; they
  are not safe precedents for this boundary.

No native C/C++ reference source was found in the inspected vendor/sample
locations. Current Kotlin ValidatePair implementation is the controlled
state machine, not an independent reference establishing peer policy.
The recovered paths are not an iOS-version-independent protocol guarantee.
No external protocol source was consulted or substituted for missing proof.

## 5. ValidatePair structure and accepted-record reuse

ControlledLockdownPairing.exchange lines149-162 builds a dictionary with
Label, Request=ValidatePair, PairRecord and ProtocolVersion text2.
PairingOptions is added **only** for Pair, not validation.

[LockdownPairRecord.toPairRequestDictionary](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairRecord.kt),
line50: five identity/certificate fields as typed text/data; no private keys,
device identifier, public-key input buffer, WiFi address or escrow field.
Only field categories are described here; no credential values or plist
contents are printed.

In the just-Paired flow, `record` is the same material used in successful Pair
and encoded into the PAIRED candidate before validation. It is not reopened
from storage between the save and this validation. No identity/material
replacement occurs at that boundary.

In existing-only mode, GetValue(UniqueDeviceID) supplies exact association,
store.load retrieves the accepted candidate, local material/identity checks
run, PREPARED/missing records stop, and no Pair/SetValue/generation branch is
permitted. That branch loads the persisted material directly.

Plist encoding (LockdownPlistChannel225-268) creates a root XML dictionary,
escapes text and encodes data using Base64Compat. sendLocked101-111 prefixes
the UTF-8 byte length correctly; no private material is added during framing.
Reply checking requires the operation's Request, no Error, and optional
Result=Success. No reply was received/accepted in the observed reset.

No structural defect or different local identity is demonstrated. This
does not certify that the particular iOS version accepts ValidatePair here,
this minimal field set, or same-connection ordering. Those distinctions
cannot be resolved from a full OUT completion and uncorrelated RST.

## 6. Hardware-design gate

**No new hardware test design is issued**, because the requested prerequisite
that source evidence prove reconnect is required is not met.

The already-existing P.1 action itself starts a fresh TCP connection in a
new invocation and does not Pair. That distinguishes it structurally from
immediate validation on the just-Paired stream, but is not evidence of a
mandatory reconnect rule or authorization to run it during this audit.

If later independent static protocol evidence establishes the rule, a
future approved test must use the accepted record and one fresh62078 stream,
never repeat Pair/SetValue, and verify device association before validation.
Opening an unnecessary throwaway connection and then another is not
justified. No reconnect, retry, validation test or later session/service
behavior was implemented here.

## Verification

Documentation-only work: references and whitespace checked. No unit tests
or APK build needed/run for this phase. Source checks confirm minSdk22 and
[CONNECTIONS_ENABLED=false](../shared/src/main/java/com/shilapi/xcertplay/orchestration/LegacyLaunchBuild.kt).
Lookup/persistence are left unchanged; the P.3 corrected chronology and
independent reopen evidence remain authoritative.

POST-PAIR CONNECTION LIFETIME STILL UNRESOLVED — NO HARDWARE TEST
