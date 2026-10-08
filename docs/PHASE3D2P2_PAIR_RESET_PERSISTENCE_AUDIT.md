# Phase 3D.2P.2 - Pair SUCCESS, TCP reset and local record inspection

Later E01 local inspection confirms one readable/decryptable PAIRED record
with matching local association and available wrapping alias. During P.3
the user confirmed the old NOT_FOUND was the uninstall-related run before
the latest Pair; P.1 was not rerun afterward. See
[the subsequent comparison and independent reopen verification](PHASE3D2P3_EXISTING_PAIR_LOOKUP_FIX.md).
This resolves the historical persistence uncertainty at the reported
inspection time, but not live-phone association or the peer TCP-reset cause.
The verdicts below preserve the evidence available when P.2 was written.

## Scope and evidence

Static audit plus a separate local-only metadata action. Development does not
contact USB, USBMUX, Lockdown, an iPhone or a network. No new Pair/ValidatePair
transport test, reconnect, session, TLS or service behavior is implemented.
Existing transport diagnostics are unchanged. minSdk22 and
CONNECTIONS_ENABLED=false remain.

Latest supplied E01 evidence: the combined Pair action, not the no-Pair action,
reported Pair SUCCESS, protected PAIRED save verified, a full TCP_OUT4972
completion, then peer RST during VALIDATE_PAIR. USB device/descriptors, local
handle ownership, pipe and USBMUX host remained available; stream TCP_OPEN
became false with RST=true. FIN/EOF/timeout/short-write/failed-write were false.
Cleanup succeeded and all forbidden later-stage counts were zero.

The earlier P.1 NOT_FOUND occurred after an explicitly confirmed **uninstall**.
It is explained by removal of app-private/KeyStore-backed state, not evidence
of a persistence bug. The no-Pair action has not been reported running after
this latest accepted save. Current E01 record state is not observable from
source; the newly requested local inspection is justified by that uncertainty,
not a claim that an accepted record disappeared across an in-place update.

## 1. Exact Pair -> ValidatePair call graph and connection lifetime

Sources:

- [manual runner](../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt)
- [diagnostic orchestration](../common/src/main/java/com/shilapi/xcertplay/ControlledPairDiagnostic.kt)
- [Android Session/pipe](../common/src/main/java/com/shilapi/xcertplay/AndroidReadOnlyLockdownAccess.kt)
- [controlled state machine](../shared/src/main/java/com/shilapi/xcertplay/transport/ControlledLockdownPairing.kt)
- [plist framing](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPlistChannel.kt)
- [USBMUX host/TCP](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt)

```text
manual confirmPairDiagnostic(false)
  -> AndroidDiagnosticPairStore(applicationContext)
  -> ControlledPairDiagnostic.run
     -> access.open -> Session
     -> same-handle GET_CONFIGURATION5 -> claim(false)
     -> Session.initialize -> one strict Iap2UsbMuxHost
     -> Session.connectLockdown
        -> host.connect(62078) -> SYN/SYN+ACK/ACK -> stream
        -> channel = LockdownPlistChannel(stream)
     -> QueryType
     -> Session.prepareAndPair -> pairOperation(existingOnly=false)
        -> capture existing channel in opened and existing host in host
        -> ControlledLockdownPairing.run
           -> GetValue(UniqueDeviceID) -> record lookup
           -> preparation/Pair branch only for PREPARED or absent record
           -> exchange(Pair)
              -> active/unchanged checks
              -> same opened.request -> send framed plist -> receive reply
              -> host.verifyReadOnlyDiagnostic -> correlated reply validation
           -> construct PAIRED candidate -> store.save -> verified log
           -> exchange(ValidatePair)
              -> same closure, same opened, same TCP stream
              -> framed send -> receive
           -> only successful validation saves VALIDATED
     -> finally releaseUsbMux -> stopTransport -> close channel/host/pipe
     -> close UsbDeviceConnection -> STOP
```

The Pair and ValidatePair requests **use the same TCP object, local port,
channel and USBMUX host**. connectLockdown is called once and guarded by
connectAttempted. pairOperation captures its channel once; no factory/new
connection is called between exchanges. Storage has no transport reference.

Between Pair SUCCESS and ValidatePair there is no local close/shutdown,
channel.detach, FIN generation, host restart, receiver teardown, USB release
or reconnect. Only candidate construction, synchronous storage, reporting
and the next active/unchanged checks occur.

The reader continues concurrently:

- SYN+ACK advances connection state and sends ACK during initial connect.
- Payload is queued and automatically ACKed; it does not close the stream.
- Peer FIN latches FIN, ACKs, marks closed and removes the connection. recv
  on a drained closed stream marks EOF; framing treats EOF as protocol failure.
- Peer RST latches RST/failure/closed, wakes waiters and removes the stream.
- Reader exception invokes host.fail, closes the host/connections/pipe. This
  differs from stream-only RST; the latest host-active/pipe-open evidence fits
  stream-only RST, not that global reader-failure path.
- Local abort can send RST for connection establishment timeout; it is not
  invoked between successful Pair and validation.
- Normal close sends FIN only for an open connected stream. After RST,
  TCP.close is a no-op because closed is already true. Host/pipe/USB cleanup
  happens after the exception, not as a precursor to validation in this flow.

## 2. Exact RST classification and what it does not establish

ReaderLoop -> takeFrame -> dispatchTcp -> connections[destination local port]
-> `Iap2UsbMuxTcpConnection.onPacket`. When flags contain TCP_RST (0x04):

```text
rstObserved = true
failure = DeviceUnavailable("USBMUX TCP connection was reset by the peer")
closed = true
notifyAll
removeConnection(this)
```

The pending recv or next send raises the retained failure.
[safe classification](../shared/src/main/java/com/shilapi/xcertplay/transport/PairDiagnosticFailureDetails.kt)
maps this exact audited origin to USBMUX_TCP_RESET;
ControlledPairFailure now retains its original cause.

This is a received USBMUX TCP reset flag, not an Android detach inference,
USB short write, response timeout, plist Error response or Pair rejection.
Pair was already accepted. USB_HANDLE_OPEN is local ownership, not an
electrical liveness probe.

dispatchTcp validates strict remote port62078 and the registered local port,
but does not validate the RST's sequence/acknowledgement against an
outstanding request. There is no application request identifier on TCP RST.
The evidence therefore identifies stream failure, **not its iOS policy or
which application request caused teardown**. Reader/write concurrency also
prevents proving the peer emitted RST after receipt/parsing of ValidatePair
merely from log ordering.

## 3. The 4972-byte completion

ValidatePair construction uses Label, Request, the persisted five-field
PairRecord, and ProtocolVersion text2. There are no PairingOptions,
private-key fields or session/service requests. No credential contents are
reproduced here.

LockdownPlistChannel.sendLocked serializes UTF-8 XML and prepends its four-byte
big-endian length. TCP.send splits at16KiB; this request fits one chunk.
host.sendTcp adds a20-byte TCP header; sendFrame adds a16-byte USBMUX header.
Pipe labels this entire buffer TCP_OUT and requires bulkTransfer completion
to equal its total length.

For the supplied single ValidatePair data completion:

| Structure | Bytes |
|---|---:|
| USBMUX header | 16 |
| TCP header | 20 |
| Lockdown length prefix | 4 |
| XML plist | 4932 |
| Total bulk OUT buffer | 4972 |

Thus4936bytes comprise the complete framed Lockdown request, and4972/4972
proves the Android USB bulk API returned full local completion for the whole
USBMUX packet. No additional application send/padding/ZLP is issued by this
code for this request. Normal TCP/mux acknowledgement fields remain part of
the protocol; inbound payloads are ACKed automatically.

send does **not** wait for or track a peer ACK covering these bytes. It advances
local sequence after pipe.write returns. A full completion is not proof of
remote TCP delivery, Lockdown plist parse, credential acceptance or a response.
RST can be processed concurrently before/after the local completion log.
The outcome is compatible with delayed Pair-related teardown or rejection
of the subsequent validation, but neither explanation is proved.

## 4. Is post-Pair reconnect required?

Existing source/reference evidence:

- [normal DiPlay Pair client](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairingClient.kt)
  opens a channel inside use; on Pair success it returns and use closes it.
  It has no ValidatePair call. Subsequent normal CarKit/session code opens a
  new connection. This is a local ownership choice, not a demonstrated iOS
  rule requiring reconnect specifically before ValidatePair.
- [prior QDrive structural audit](PHASE3D2N_LOCKDOWN_PAIR_TRUST_AUDIT.md),
  section7: wrappers Pair0xA5F24 and ValidatePair0xA5F78 share generic helper
 0xA61E0. One preflight path is Pair -> StartSession/TLS -> ValidatePair;
  a different client_new_with_handshake path is Pair/ValidatePair ->
  StartSession. These references do not establish a mandatory teardown
  after every successful Pair or a device-version-specific rule.
- The earlier controlled design permits Pair then standalone ValidatePair,
  but static separability from sessions does not prove modern iOS accepts
  same-connection immediate validation in this hardware situation.

There is insufficient source evidence to certify either same-connection
correctness for this iPhone or a mandatory fresh connection. No reconnect
was implemented. Do not add QueryType/retry/delay/new connection by guessing,
and do not start a session to work around validation.

## 5. Persistence, discoverability and cleanup

Sources: [Android store/codec](../common/src/main/java/com/shilapi/xcertplay/AndroidDiagnosticPairStore.kt)
and [detailed persistence audit](PHASE3D2P2_PAIR_RECORD_PERSISTENCE_AUDIT.md).

- Production store is AndroidDiagnosticPairStore, not a test memory store.
  App-private preferences `diplay_controlled_pair`, conceptually
  `<applicationInfo.dataDir>/shared_prefs/diplay_controlled_pair.xml`.
  Debug installed package remains `com.shihab.diplay.legacytest`.
- Key is `device_` plus lower-case SHA-256 of validated UniqueDeviceID UTF-8
  bytes, never a USB device name or provisional identifier. Actual association
  and identity/material/state are inside the encrypted codec v1 record.
- Successful Pair saves PAIRED before attempting validation. PREPARED was
  saved before Pair; VALIDATED is written only after successful validation.
- save refuses downgrade and identity/material replacement, serializes the
  whole record, encrypts AES256-GCM with association-name AAD, wraps its AES
  key with the retained AndroidKeyStore RSA alias, and requires commit=true.
- `stored=verified` is synchronous SharedPreferences disk-commit success plus
  same-object preference readback, fresh decrypt and serialized-byte equality.
  It is not just a RAM candidate check. It does not close/reopen the file or
  prove an independent process/head-unit restart. A new store wrapper in the
  same process can still use Android's cached preferences object.
- Host private key/certificates remain in the protected record with its
  identity; wrapping key is separate. No credentials are logged/exported.
- P.1 preflight scans all `device_` entries, without UDID or USB selection.
  Empty inventory yields NOT_FOUND; only PREPARED yields PREPARED; recognized
  decryption/association/material failure yields INVALID. After discovery,
  the actual UniqueDeviceID supplies deterministic exact load; no Pair fallback.
- With an intact matching PAIRED/VALIDATED candidate, even the combined action
  skips Pair. A log containing Pair means its lookup selected the absent or
  PREPARED branch at that time; repeated button presses alone do not explain
  repeated Pair when a matching accepted record is intact. No supplied
  chronological local inventory establishes that additional scenario.
- The earlier NOT_FOUND is explained by the user's confirmed uninstall. It
  does not establish physical loss in the current installation. Current
  accepted state cannot be established without the local inspection result.
- Failure catches do not save/delete. After RST, SAVE_VALIDATION is not
  reached. Finally releases/closes only transport resources; observer cleanup
  only unregisters observation. No downgrade, association clear, preference
  clear, alias deletion, material replacement, migration or rollback exists
  in this diagnostic path. Local cleanup cannot undo accepted iPhone Trust.

Preserve this installation. Future APKs must be same-package/same-signer
in-place updates, not uninstall/reinstall. Signing config is unchanged;
compare public signer fingerprints before installing the new build. Neither
versionName changes nor normal in-place updates require record regeneration.

## 6. Implemented separate LOCAL-ONLY action

Settings/Diagnostics button: **Inspect local pairing records (NO USB)**.
Nothing runs automatically. Save diagnostic report includes the result.

Implementation:

- [local-only boundary/formatter](../common/src/main/java/com/shilapi/xcertplay/LocalPairMetadataDiagnostic.kt)
- AndroidDiagnosticPairStore.inspect, which reads prefs.all and read-only
  AndroidKeyStore alias presence, then decrypts entries independently.
- Separate activity worker; no USB adapter, USB observer or inventory call.
  Shared exclusion prevents starting Pair while this inspection runs.

Only safe counts/booleans/fixed enums are returned:

- Recognized record-entry and unknown-entry counts; PREPARED/PAIRED/VALIDATED
  and UNKNOWN counts. State may be readable from a header even if the rest
  fails validation; status and accepted-readable flag must also be checked.
- Wrapping-alias-present boolean or UNKNOWN, alias-check-failed boolean.
- Preferences-file and backup-file presence, identity-metadata presence.
- Per scan-local row: state, association present/matching booleans, decryptable
  boolean and READABLE / WRONG_VALUE_TYPE / ENVELOPE_OR_DECRYPT_FAILED /
  CODEC_INVALID / ASSOCIATION_INVALID / ASSOCIATION_MISMATCH / MATERIAL_INVALID.
- Accepted **readable** record exists, incomplete inspection flag, orphaned
  alias/identity metadata flags when recognized inventory is empty.

Entry failures remain explicit rows, not silently omitted. A store-wide
failure reports counts UNKNOWN, not zero. Unknown exception messages are
never exported. No record key/hash, device identifier, identity, plist,
key/certificate or escrow is returned to the formatter.

Inspection uses current Android preferences snapshots and fresh decryption,
not an independent disk/process reopen. The report explicitly states that
limit. Readability is not proof of iPhone validation.

No identity/systemBuid/save/encrypt/repair/migration/delete call occurs in
inspection. Missing alias is only observed; decrypt uses keyStore(false),
which refuses creation. Local certificate verification verifies existing
material, not new key generation, networking, TLS or phone requests.
Software wrapping keys exist only as test injection.

Do not use the old Pair button again to investigate this reset. Next E01
action is local metadata inspection only, save report and STOP.
If accepted/readable state is present, retain it while investigating protocol
semantics; its existence does not authorize a reconnect validation test.

## 7. Validation

Focused software tests cover accepted PAIRED/VALIDATED inventory after store
wrapper reopen; accepted-record local Pair-success persistence before a
mocked validation RST and retention after actual diagnostic cleanup; bad
association distinct from no entries; mixed corrupt/valid entries; safe
failure text; manual UI with forbidden USB access; no Pair fallback/identity
regeneration; saved-report export. Existing gate/one-shot tests also run.

Tests use JVM/Robolectric SDK28 with software fixture keys and mocked USB
transfers. They are not real USB tests, API22 runtime execution, independently
restarted processes or E01 persistence proof. No hardware operation occurred.
The tooling has no IDE-discovered Gradle tests, so the repository Gradle unit
runner is used. Results and APK evidence are recorded below after validation.

Final results: **60 tests passed**, common44/shared16, zero failures/errors/
skips. Final common selectors: LocalPairMetadataDiagnosticTest,
AndroidDiagnosticPairStoreTest, AndroidControlledPairAccessTest,
ControlledPairDiagnosticTest, ReadOnlyLockdownUiTest, DiagnosticExportUiTest.
Shared selectors: ControlledLockdownPairingTest, PairDiagnosticFailureDetailsTest,
ReadOnlyUsbMuxInitTest. The local metadata class includes8tests; shared tests
were run before the final common-only rerun, with no shared source changes.
Editor diagnostics and diff whitespace checks passed.

`.\gradlew.bat :mobile:assembleDebug`: **BUILD SUCCESSFUL** (21seconds).
APK: `mobile\build\outputs\apk\debug\mobile-debug.apk`.
Version `0.2.12-api22-phase3d2p2-local-records`, code31, minSdk22,
package `com.shihab.diplay.legacytest`, v1/v2 signatures verified.
Size8,434,863bytes; SHA256
`B64365F650FE245FE6C479030A1B61C4666E919FA23290E7733EEA12B17450E3`.
Public signer SHA256
`659c3de738cfbf38c71ad7c03b350eb98593600c5647bb52fd077f06c1730b5b`
matches the P.1 APK fingerprint inspected earlier in this session.
This comparison does not independently inspect the currently installed E01
package. Update in place only; if install is rejected, STOP, never uninstall.

## Verdicts

Primary transport:

PAIR TCP RESET ROOT CAUSE STILL UNKNOWN — NO VALIDATEPAIR HARDWARE TEST YET

Persistence:

PAIR RECORD STATE UNKNOWN — LOCAL METADATA DIAGNOSTIC REQUIRED
