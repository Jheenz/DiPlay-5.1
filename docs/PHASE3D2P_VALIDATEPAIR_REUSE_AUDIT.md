# Phase 3D.2P - ValidatePair DEVICE_UNAVAILABLE and existing-pair reuse

## Scope and verdict

Static source audit only. No USB operation, Lockdown request, native execution,
record lookup on a device, credential extraction, test execution or build was
performed. No runtime source, storage behavior, version or gate was changed.
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false` and mobile `minSdk=22`.

Authoritative hardware input: on E01/API22, configuration5, scoped claim,
version, setup07, TCP62078 and QueryType succeeded. One Pair succeeded; the
accepted record was stored and verified. One ValidatePair was attempted and
reported DEVICE_UNAVAILABLE at ControlledLockdownPairing.run line130.
Cleanup succeeded and all session/service/projection counters remained zero.

**The conversion to DEVICE_UNAVAILABLE is fully located, but the underlying
transport event is not uniquely identified by this report.** The reported
line130 is the wrapping throw, not the original transport throw. Several
different source conditions produce the same exception class; the wrapper
discards their original message, cause and stack. Do not claim a proven USB
detach, TCP FIN/RST, or required reconnect from that information.

Record reuse is structurally supported: the successful save is PAIRED, and a
subsequent run with that record takes validation-only. However, the existing
combined button can Pair if its per-device record is absent or PREPARED.
**Do not use it as an unconditional no-Pair probe.** Phase 3D.2Q is not
implemented or authorized here; because the failure is not fully understood,
this document does not specify a ready-to-run hardware experiment.

## 1. Exact call graph and failure conversion

Sources:

- [ControlledLockdownPairing.run / exchange](../shared/src/main/java/com/shilapi/xcertplay/transport/ControlledLockdownPairing.kt)
- [AndroidReadOnlyLockdownAccess.Session / Pipe](../common/src/main/java/com/shilapi/xcertplay/AndroidReadOnlyLockdownAccess.kt)
- [ControlledPairDiagnostic.run](../common/src/main/java/com/shilapi/xcertplay/ControlledPairDiagnostic.kt)
- [LockdownPlistChannel.request / sendLocked / receiveLocked / readFully](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPlistChannel.kt)
- [Iap2UsbMuxHost / Iap2UsbMuxTcpConnection](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt)

After Pair's correlated, non-error response:

1. `exchange("Pair", record=record)` has returned, including the adapter's
   **post-response host health check**. Optional Result must be Success.
2. Optional EscrowBag is checked for Data type and copied into the candidate.
3. Construct `DiagnosticPairCandidate(..., record, PAIRED, escrow)`.
   This uses the same identity and record object; no new keys or UUIDs.
4. Set stage SAVE_PAIR_SUCCESS; `store.save(candidate)` completes commit and
   decrypt/readback comparison. Report successful storage.
5. Set stage VALIDATE_PAIR; report "ValidatePair attempted (once)".
6. `exchange("ValidatePair", record=record)` calls `active()` before building
   the plist. The adapter also checks open/release state, cancellation,
   inventory stability and the same session's `unchanged()` callback.
7. `opened.request(request, 5000)` on the **existing** channel:
   validate timeout -> check channel open -> XML encode -> four-byte BE length
   prefix -> TCP stream send -> framed read -> XML parse.
8. Only after a received dictionary does `host.verifyReadOnlyDiagnostic()`
   check reader/host failure. Then exchange checks Request/Error/Result.
9. Only successful validation triggers SAVE_VALIDATION and VALIDATED save.
10. Any failure returns to engine release/close; no reopening/re-Pair.

Current lines124-130 map exceptions:

```text
IphoneUsbException.TimedOut          -> RESPONSE_TIMEOUT
IphoneUsbException.Protocol         -> MALFORMED_PROTOCOL
IphoneUsbException.DeviceUnavailable -> DEVICE_UNAVAILABLE
IphoneUsbException.PermissionDenied -> PERMISSION_UNAVAILABLE
other Exception                    -> LOCAL_OR_TRANSPORT_FAILURE
throw ControlledPairFailure(stage, reason)
```

There is no causal exception supplied to this final throw. The generic safe
reporter subsequently sees ControlledPairFailure, not the original USB/TCP
exception. Linkage errors have separate details; that does not preserve
DeviceUnavailable provenance. "ValidatePair attempted" is logged **before**
active checks and the write: it does not prove the request reached bulk OUT,
the peer, or that a ValidatePair response was received.

## 2. Exact reachable DeviceUnavailable conditions

| Source / condition | Exact transport meaning | Limits |
|---|---|---|
| Pipe.write: `bulkTransfer(...) != data.size` | TCP bulk OUT failed or was short; throws DeviceUnavailable with counts. No retry. | Android negative result does not identify disconnect vs other USB error. A partial write is not a complete request. |
| Pipe.read: post-version negative result AND `closed.get()` | Pipe was closed during read. | Negative read while open returns null instead, not DeviceUnavailable directly. |
| Host.checkOpenLocked: existing `failure` | Earlier reader/write error is rethrown. | Failure may predate ValidatePair while record persistence was running. |
| Host.checkOpenLocked: no failure but host `closed` | Host was locally closed. | No intended local close exists between Pair and ValidatePair in this flow. |
| Host.readerLoop RuntimeException catch | Wraps **any RuntimeException** as DeviceUnavailable("USBMUX reader failed", cause). | Includes inventory-check exceptions in Pipe.read and other runtime failures; does not prove USB reset. |
| TCP.onPacket RST bit set | Stores DeviceUnavailable("USBMUX TCP connection was reset by the peer"), closes/removes this TCP connection. | Host need not be failed; later TCP send/recv rethrows connection failure. |
| TCP.checkConnectedLocked: `!connected || closed` | TCP stream is not open when send starts or between chunks. | A peer FIN before ValidatePair send sets closed with no failure, yielding this condition. |
| TCP.recv interrupted wait | Restores interrupt and throws DeviceUnavailable("Interrupted while waiting for USBMUX TCP data"). | Not proof of device removal. UI cancellation sets a flag, not worker interruption. |
| TCP/host failure propagated by closeFromHost | Host failure closes active streams with that exception. | If error is Protocol or TimedOut it retains those types; only DeviceUnavailable maps to this code. |
| Plist.checkOpen: channel `closed` | Local plist channel already closed. | No intended close/detach occurs in the interval. Plist.detach is not called. |

Not causal candidates in this interval: allocating a new TCP source port or
waiting for initial connect, because ValidatePair opens no new connection.

### What the reported code does NOT mean

- Synchronous observer detach and missing UsbManager device checks throw
  **UsbMuxClaimFailure(DEVICE_DISAPPEARED)**, not DeviceUnavailable.
- Changed descriptors throw DEVICE_STATE_CHANGED. Other precondition or
  permission changes retain their selector codes.
- However, the same inventory callback on the **reader thread** can throw
  those RuntimeExceptions into readerLoop's broad RuntimeException wrapper.
  That route can ultimately become DEVICE_UNAVAILABLE.
- Lockdown stream EOF **during framed receive** returns an empty TCP chunk;
  readFully throws Protocol, mapped to MALFORMED_PROTOCOL. A FIN **before
  sending** instead trips TCP.checkConnectedLocked and maps to DEVICE_UNAVAILABLE.
- No response by the receive deadline (5000ms) maps to RESPONSE_TIMEOUT.
- Malformed framing/XML normally throws Protocol. An unexpected runtime
  failure in reader logic can take the RuntimeException wrapping route.
- Lockdown Error replies are handled only after reception; InvalidHostID and
  InvalidPairRecord have their own codes. They are not DEVICE_UNAVAILABLE.
- Record lookup, decrypt, generation and save failures are not remote
  DeviceUnavailable in the production store; they normally map to
  LOCAL_OR_TRANSPORT_FAILURE at their own stages.

## 3. Known transport state versus unresolved event

Known at Pair completion: a correlated Pair reply was parsed, no Error was
present, optional Result passed, and host health check passed. At the reported
validation boundary, stage was VALIDATE_PAIR and the accepted local candidate
was already PAIRED and save-verified. The session intended to reuse the same
USB handle, mux host, TCP stream and plist channel.

Unknown: whether the stream was open at validation send, whether OUT was
complete/short, whether FIN/RST arrived, whether the worker was interrupted,
whether the device disappeared, whether descriptors changed, and whether an
asynchronous reader error occurred while persistence was running.

Cleanup PASS is useful but not a protocol trace:
Session.stopTransport first checks host health; a retained host failure is
re-thrown and should make cleanup fail in this strict path. Thus reported
clean cleanup is **less consistent** with a persistent host-reader/global
write failure and **compatible** with a connection-only FIN/RST whose host
remains healthy. TCP.close returns immediately if already closed.
This narrows candidates but cannot distinguish FIN, RST, interruption or
other state failures, and does not prove any of them.

Necessary missing evidence: safe original typed transport reason/throw site,
OUT completion count, same-run observer/inventory state, and TCP control
flags/stream health at the failure. No record contents or identifiers are
needed. Existing generic `usbmux TCP control` logs include flags and may help
if a same-run trace already exists: FIN bit1, RST bit4. There is no such
trace supplied with this result. Do not generate traffic to obtain one in P.

## 4. Pair, reset, detach and connection lifetime

The controlled Pair path calls no USB reset, setConfiguration, alternate
setting, role/mode setter, vendor request or permission request. Neither
successful record persistence nor certificate verification manipulates USB.
There is no intentional local TCP/channel close between Pair and validation.

The peer/OS can terminate a stream asynchronously; the code explicitly handles
FIN/RST and detach. **No inspected evidence proves that successful Pair
requires or causes USB reset/re-enumeration or TCP closure on this iPhone.**
It is a possible externally observed event, not a mandated transition.

The [normal LockdownPairingClient](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairingClient.kt)
creates a TCP62078 channel per attempt and closes it via `use` after **every**
outcome, including Pair success. It reconnects/re-Pairs on pending Trust;
it does not ValidatePair and cannot establish a same-connection requirement.
Its next session client opens a new TCP connection. This is application
ownership behavior, not proof the phone forcibly closed the old stream.

The authoritative [N audit, section8](PHASE3D2N_LOCKDOWN_PAIR_TRUST_AUDIT.md)
records QDrive's distinct native paths: generic handshake can Pair then
ValidatePair then StartSession; actual preflight can Pair then StartSession/
TLS then ValidatePair. A Trust-notification handler opens a separate client
and Pairs again. None of that proves a required USB reset or universally
required reconnect between accepted Pair and isolated ValidatePair.
No native code or vendor material was re-examined/executed in this phase.

The current diagnostic is intentionally fatal on detach or metadata change:
the detach flag remains latched for that run even if a phone comes back;
inventory checks refuse changed devices. Reader-thread failures can lose
their precise category as described above. There is **no transient-detach
recovery**, automatic re-open, delay/retry or re-claim.

## 5. ValidatePair request and identity association

`exchange` builds:

```text
Label: DiPlay-ControlledPair
Request: ValidatePair
ProtocolVersion: text "2"
PairRecord: DeviceCertificate, HostCertificate, HostID, RootCertificate, SystemBUID
```

It uses `record.toPairRequestDictionary()` on the exact in-memory record used
for successful Pair. Pair alone adds PairingOptions/ExtendedPairingErrors.
No private key, WiFi address, UDID or EscrowBag is transmitted in this nested
dictionary. ValidatePair does not transmit proof using the private key or
start TLS. Validation still has to be accepted by the phone; simply possessing
these fields is not evidence of success.

On later runs UniqueDeviceID supplies the per-device lookup key. Load checks
association, verifies chain/private-key/device-key material locally and
compares candidate/record identities before selecting validation-only.
No actual identity or credential value is included here.

Current implementation is option **A: immediate same-connection**. It does
not require A at a protocol level: pairing relationship is stored state,
and the already-paired branch sends ValidatePair on a newly constructed
diagnostic connection. Option **B: fresh Lockdown connection** is therefore
structurally supported without re-Pair, but not proven to fix this failure.
Option C (USB re-enumeration) is not required by any inspected builder/caller.
Option D (depends on externally observed device behavior) remains unresolved;
do not invent an automatic reconnect to hide that uncertainty.

## 6. Stored record, restart and next-run reuse

Source: [AndroidDiagnosticPairStore / DiagnosticPairCodec](../common/src/main/java/com/shilapi/xcertplay/AndroidDiagnosticPairStore.kt).

- App-private SharedPreferences `diplay_controlled_pair`; one SHA256-derived
  entry per device. Package is debug `.legacytest`, not normal production app.
  Conceptually stored under that package's private preferences directory.
- Complete candidate envelope contains identity, state, key/certificate
  association and optional escrow. AES256-GCM authenticates the payload and
  device-derived entry association; random AES key is RSA-wrapped by
  AndroidKeyStore alias `diplay-controlled-pair-storage-v1`.
- Synchronized save rejects identity/material replacement and state downgrade.
  Entire encrypted entry is replaced in one editor commit; commit must return
  true and decrypt/readback bytes must equal the serialized candidate.
  This is not a multi-resource transaction with iOS and cannot roll back Pair.
  Readback proves storage/key usability in-process, not independently observed
  power-loss durability. No crash/head-unit reboot test was supplied.
- Load decodes all six key/certificate/public-key buffers together and validates
  association and state. Keys stay with their corresponding identity; app or
  head-unit restart should reuse the same package data and KeyStore.
  Uninstall, data clearing, missing keys or corruption do not have regeneration
  fallbacks. Manifest disables backup.
- HostID generation happens only if per-device load returns null. SystemBUID
  has its own stable stored value and is created only when absent.
- PAIRED is saved **before** ValidatePair; failed validation does not save
  VALIDATED, downgrade, delete, regenerate or clear the accepted record.
  Release/close/observer cleanup do not modify preferences.
- Unchanged VALIDATED saves are skipped. Successful PAIRED validation advances
  state without changing material; legacy normal-store presence blocks reuse/
  migration rather than silently importing or overwriting it.

Expected next manual combined run, assuming unchanged installed package and
intact PAIRED record:

```text
preflight -> open -> GET_CONFIGURATION5 -> claim(false)
-> version -> setup07 -> fresh TCP62078 -> QueryType
-> GetValue(UniqueDeviceID) -> load PAIRED -> local material/identity checks
-> ValidatePair once -> save VALIDATED only if successful -> cleanup -> STOP
```

No SetValue, new UUID/key generation, Pair, session or service occurs in that
branch. Existing tests explicitly cover PAIRED/VALIDATED validation-only,
material retention, protected reload and identity/downgrade rejection; those
are pre-existing PC evidence, not a restart test of this hardware record.

**Critical limitation:** load null or PREPARED still takes the combined
diagnostic's Pair branch. That behavior must not be mistaken for an
existing-pair-only guard. Normal app startup does not invoke this diagnostic
or load this separate store. Normal AirPlayPersistence/global-record/session
behavior documented in N is a separate disabled route, not a consumer of
the controlled encrypted record.

## 7. Trust conclusions

| Observed state | Supported conclusion / behavior |
|---|---|
| Pair SUCCESS | Lockdown accepted this Pair dictionary without Error; optional Result checked. Device pairing/trust may persist; accepted local candidate was stored. Does not prove validated reuse, a current open stream, TLS, session, services, unlock state or who/when approved Trust. |
| Already trusted with matching PAIRED/VALIDATED candidate | Current diagnostic skips Pair and validates once; no promise that future validation/session will be accepted. |
| User accepted Trust | Only manual phone action. Correlated Pair success is still the acceptance boundary, not a notification assumption. |
| PairingDialogResponsePending | PREPARED retained; stop and cleanup. No automatic approval/retry. A manually repeated combined run can Pair again, hence unsuitable for an unconditional no-Pair test. |
| UserDeniedPairing | TRUST_DENIED; stop, retain candidate; no bypass/reset. |
| PasswordProtected | PHONE_LOCKED; stop; no passcode or unlock bypass. |
| InvalidHostID / InvalidPairRecord from validation | Stop with explicit rejection; do not delete credentials or automatically re-Pair. |

Pair SUCCESS is not authorization to advance to StartSession.

## 8. 3D.2Q decision and required observability

**No ready Phase 3D.2Q hardware design is issued:** the user conditions its
design on fully understanding the failure, and the original transport event
remains ambiguous. Do not implement Q, rerun the combined Pair button blindly,
automatically reconnect, restore configuration1 or manipulate Trust.

Constraints for any separately approved future existing-pair-only diagnostic
(requirements, not an authorized test):

- Require an intact PAIRED/VALIDATED candidate associated with this device;
  absence, PREPARED, corruption or mismatched identity must STOP, never Pair.
- Reuse normal config5/version/setup/scoped-claim guards. QueryType and
  UniqueDeviceID association reads would still be needed by the present
  architecture; do not call it literally ValidatePair-only traffic.
- A fresh **manually initiated** connection can be considered only after
  explaining the source error. Any USB re-enumeration must be visible as a
  separate event/preflight with a new handle, never hidden recovery.
- One ValidatePair, exact classified result, release/close, STOP.
- No Pair even after rejected validation without separate explicit approval;
  no StartSession/TLS/StartService/CarKit/iAP2/MFi/NCM/AirPlay/CarPlay.

Required future categories and evidence:

| Category | Evidence required, never guessed |
|---|---|
| DEVICE_PRESENT | Current inventory/permission/config5 match; no UDID or device path exported. |
| USB_DETACHED | Observed detach event, distinguish missing-event-metadata error. |
| USB_REENUMERATED | Observed attach after detach, descriptors checked afresh; association required before use. |
| USB_CONNECTION_LOST | Underlying transfer failure/short completion with direction/count and last inventory state. Negative Android return alone cannot prove physical removal. |
| USBMUX_TCP_CLOSED | FIN/RST/local close and per-stream state; distinguish reader-host failure. |
| LOCKDOWN_EOF | Empty TCP read while a plist frame is required, report header/body phase and counts only. |
| LOCKDOWN_TIMEOUT | Bounded framed-read deadline, not inferred from every negative USB poll. |
| LOCKDOWN_ERROR_RESPONSE | Correlated Request + allowlisted Error code; unknown text redacted. |
| PAIR_RECORD_NOT_FOUND | Associated lookup returns null; STOP without identity generation or Pair. |
| PAIR_RECORD_INVALID | Local association/material/decrypt/state failure; no contents logged or erased. |
| VALIDATEPAIR_REJECTED | Correlated ValidatePair Error/invalid Result; separate from transport failure. |

Retain a safe original typed reason at the throw site and stage
(pre-send/write/read/parse/post-response health), not just its final wrapper.
The original bulk/peer/reader error must not be collapsed into a universal
DEVICE_UNAVAILABLE. Do not export arbitrary exception causes: crypto/provider/
plist text may contain secrets. No keys, certificates, pairing-record contents,
EscrowBag, HostID, SystemBUID or device secrets belong in diagnostics.

## 9. Final safety and unresolved questions

Only this audit document was added. No protocol/runtime implementation,
storage mutation, build or hardware operation was performed.
Sources still show CONNECTIONS_ENABLED=false, minSdk22 and the controlled
hard stop after ValidatePair and cleanup. Normal startup remains disabled.

Unresolved: exact original DeviceUnavailable throw; whether ValidatePair
was written completely; TCP FIN vs RST vs interrupted wait vs other failure;
whether any physical USB event occurred; device-specific post-Pair connection
lifetime; protected record reload after an actual E01/app restart.

VALIDATEPAIR FAILURE PARTIALLY IDENTIFIED — MORE STATIC ANALYSIS REQUIRED
