# Phase 3D.2N - Lockdown Pair / Trust / StartSession static audit

## Scope, evidence and decision

**STATIC AUDIT ONLY.** No USB operation, device request, native-library
execution, credential generation, pairing-record read/write, TLS handshake,
service startup or hardware test was performed. Only this document was added.
Existing uncommitted work was preserved. The normal runtime gate remains
[`LegacyLaunchBuild.CONNECTIONS_ENABLED=false`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/LegacyLaunchBuild.kt).
The APK and runtime behavior are unchanged.

Read first: [J](PHASE3D2J_FIRST_USBMUX_EXCHANGE_AUDIT.md),
[K](PHASE3D2K_USBMUX_VERSION_TEST.md),
[L](PHASE3D2L_SETUP07_PRE_LOCKDOWN_AUDIT.md) and the
[combined read-only diagnostic](READ_ONLY_LOCKDOWN_DISCOVERY_TEST.md).

The user now confirms real E01 success with no Carlinkit: active config5,
scoped USBMUX claim, version reply, setup07 full write, TCP62078,
`QueryType=com.apple.mobile.lockdown`, `GetValue(ProductType)=iPhone15,4`
and cleanup. No Pair, ValidatePair, StartSession, pairing-record access,
Trust approval, TLS, StartService, CarKit, iAP2, MFi, NCM, AirPlay or CarPlay
occurred. This supersedes the earlier guide's historical "hardware untested"
status for discovery; it does **not** establish pairing or TLS compatibility.

**Pair and ValidatePair are separable from StartSession/TLS/StartService.**
The existing pairing client already ends after Pair. QDrive has a standalone
ValidatePair wrapper that sends no StartSession. An independently allow-listed
future Pair/ValidatePair diagnostic can therefore stop before session creation.
However, Pair is deliberately state-changing, not reversible enumeration.
It must not reuse the full controller, service opener or automatic retry policy.
DiPlay currently implements **neither ValidatePair nor StopSession**.
This audit identifies their protocol boundaries, not an existing working
Pair/ValidatePair diagnostic and not authorization to run one.

## 1. Call graph: proven discovery versus dormant full startup

Source references describe the current working tree, not only HEAD `de306d1`.

### Proven isolated prefix

[`ReadOnlyLockdownDiagnostic`](../common/src/main/java/com/shilapi/xcertplay/ReadOnlyLockdownDiagnostic.kt)
uses
[`AndroidReadOnlyLockdownAccess`](../common/src/main/java/com/shilapi/xcertplay/AndroidReadOnlyLockdownAccess.kt):

```text
preflight / same-handle GET_CONFIGURATION=5 / scoped claim(false)
  -> Iap2UsbMuxHost.openReadOnlyDiagnostic
     -> strict version exchange -> setup07 once -> TCP reader
  -> host.connect(62078, 5000ms)
  -> ReadOnlyLockdownQueries.queryType
  -> ReadOnlyLockdownQueries.productType
  -> TCP/reader cleanup -> release -> connection close -> STOP
```

The query helper is explicitly restricted to those two read-only requests.
It does not expose Pair or continue into the controller.

### Existing normal wired path: not a safe next-phase entry point

[`CarPlayHostActivity`](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt)
lines3824-3826 supplies load/save/clear callbacks to
[`CarPlayController`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt).
`start` line408 checks the false gate. If enabled in another build:

```text
controller device/configuration preparation
  -> openDataPaths (1790)
  -> IphoneUsbHost.openIap2UsbSessionAsync
     -> openIap2UsbSession (232): setConfiguration + claim(force=true)
  -> openNcm (1809)                         [already outside pair-only scope]
  -> runStack (1829)
     -> Iap2UsbMuxHost.open -> version -> setup -> reader
     -> LockdownPairingClient(mux)
     -> loadPairRecord()
        record absent: pairNewRecord -> pair -> savePairRecord
        record present: use saved record without ValidatePair
     -> LockdownCarKitClient.openService("com.apple.syslog_relay")
        -> StartSession -> TLS -> StartService -> service TCP/TLS
     -> LockdownCarKitClient.open("com.apple.carkit.service")
        -> another StartSession -> TLS -> StartService -> service TCP/TLS
        rejected saved record: clear local record -> Pair -> save -> retry service
     -> wired iAP2/control/projection continuation
```

The temporary syslog service attempt is present in current source, before
CarKit, even though its errors are caught. Do not mistake it for read-only
discovery or route a pair-only test through `runStack`. Normal USB opening
also differs from the proven config-scoped `force=false` diagnostic.
No NCM, syslog or CarKit operation is needed to pair.

Current host locations:
[`Iap2UsbMuxHost`](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt)
`connect` line36, normal `begin`116, strict `beginReadOnlyDiagnostic`156,
`openReadOnlyDiagnostic`321 and normal `open`333.
USB packets/endpoints were established by J/K/L and are not redefined here.

## 2. Exact DiPlay plaintext Pair sequence

[`LockdownPairingClient.pair`](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairingClient.kt)
lines26-83 takes caller-supplied nonblank label, HostID and SystemBUID.
It creates a new TCP62078 connection for each attempt. It does **not** call
QueryType first. After the proven discovery one would be entering a separate
pairing connection, not an implicit continuation inside the query helper.

All dictionaries below are structural shapes only. No certificate, key,
identifier value, device public-key content or EscrowBag is reproduced.
[`LockdownPlistChannel`](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPlistChannel.kt)
uses four-byte big-endian XML byte length followed by UTF-8 XML plist,
over USBMUX TCP, default maximum64KiB. Its response deadline begins after
the bounded underlying send; TCP fragmentation is reassembled, not a
second application request.

| Order | Exact request keys/values | Response handling |
|---|---|---|
| 1 | `Label`, `Request="SetValue"`, `Key="UntrustedHostBUID"`, `Value=<SystemBUID>`; no Domain | An `Error` throws. **First device-state mutation, before Pair.** |
| 2 | `Label`, `Request="GetValue"`, `Key="DevicePublicKey"`; no Domain | Requires `Value` of plist data type, no Error. |
| 3 | `Label`, `Request="GetValue"`, `Key="WiFiAddress"`; no Domain | Requires `Value` of text type, no Error. |
| 4 | Local RSA/certificate generation using those inputs | RAM only in the client; no USB operation in the generator. |
| 5 | `Label`, `Request="Pair"`, `ProtocolVersion="2"` **text**, `PairingOptions={ExtendedPairingErrors=true}`, `PairRecord=<five-field dictionary below>` | Absence of Error is considered success; optional data EscrowBag captured. |
| 6 | Close this plaintext plist/TCP connection | Return `PairedRecord`; no ValidatePair, session, TLS or service. |

Nested `PairRecord` contains exactly:

```text
DeviceCertificate: data
HostCertificate: data
HostID: text
RootCertificate: data
SystemBUID: text
```

Neither private key, DevicePublicKey, WiFiAddress nor EscrowBag is sent in
this dictionary. Certificates are generated locally and are still
credential-bearing material; never log/hex-dump the Pair plist.

### Responses and retry behavior

- `Pair` response with no Error returns `PairedRecord(record, EscrowBag?)`.
  Missing EscrowBag is accepted; a present non-data EscrowBag fails.
- `PairingDialogResponsePending`: close connection, wait1000ms with
  cancellation checks every100ms, reconnect TCP62078 and repeat.
- Every retry sends SetValue again. DevicePublicKey/WiFiAddress and
  certificate generation are done only once per invocation; the same RAM
  record/identifiers are reused for subsequent Pair attempts.
- `UserDeniedPairing` and `PasswordProtected` from **Pair** become distinct
  typed failures, with no pending-dialog retry.
- Other Pair errors become `RemoteError`. Errors in preceding SetValue or
  GetValue also become `RemoteError`, even if their code is PasswordProtected.
- Each connect/response budget is at most5000ms, reduced to remaining total
  time. Allowed total1..300000ms; controller uses300000ms.
  Local certificate generation is synchronous, not interruptible by that
  timer, and can overrun it before the next deadline check.
- Cancellation is checked between operations and after the Pair reply;
  it cannot interrupt the current bounded receive or undo device pairing.
  Cancellation after successful Pair can discard the result before saving.

**Validation gap:** this client does not require echoed `Request="Pair"`
or `Result="Success"`. An otherwise valid dictionary lacking Error is enough.
Do not use that alone as a future controlled pairing PASS. Pending/error
timeouts likewise do not prove that device state stayed unchanged.
These are existing behaviors, not fixes made by N.

## 3. Host identities, certificates and EscrowBag

### HostID and SystemBUID

Controller lines196-197 creates two independent uppercase
`UUID.randomUUID()` strings **per controller instance**, not a machine-wide
SystemBUID stored at creation. Pair uses them only for a new record.
A restored record supplies its own saved identities to StartSession.
Generating a fresh controller therefore does not invalidate a usable saved
record by itself, but missing/corrupt records or clearing them leads to new
identities and new certificate material. The generator/client do not verify
UUID syntax beyond nonblank input.

### Certificate generation/loading

[`LockdownPairRecord`](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairRecord.kt):
generator lines123-144; material generation169 onward; restore64 onward.

- Parse the returned PKCS#1 RSA DevicePublicKey PEM; reject a different
  PEM form, malformed DER or trailing data.
- Generate independent2048-bit RSA root and host key pairs using JCA.
- Root certificate is self-signed; host and device certificates are signed
  by the root key. Device certificate embeds the **phone's public key**;
  no device private key is generated or fetched.
- All three are X.509v3, SHA256withRSA, serial0, empty issuer/subject names,
  validity from current head-unit wall clock to ten365-day years later.
  Root has critical CA basicConstraints. Leaves have non-CA
  basicConstraints and digitalSignature/keyEncipherment key usage;
  device also has a SHA-1-derived subject key identifier.
- Keys are serialized as PKCS#8 private-key PEM; certificates as X.509 PEM.
  Generated signatures are checked with JCA over the signed TBS, not by
  parsing each certificate through CertificateFactory.
- `restore` copies supplied arrays and requires nonempty buffers/nonblank
  identifiers/WiFiMAC. It does **not** check certificate/key consistency,
  expiration, original device identity or cryptographic validity.

No bundled QDrive certificate/key was used or copied. Local generator
objects retain defensive copies; the client has no general wipe/destroy API.
The TLS factory wipes its temporary copies, not the stored originals.

### EscrowBag handling

`PairedRecord` lines230-239 holds optional reply EscrowBag in RAM only, with
defensive copies and a redacted toString. The controller's `pairNewRecord`
line2000 immediately selects `.pairRecord`, **dropping the EscrowBag**.
The persistence schema has no EscrowBag entry. CarKit's overload taking
PairedRecord also selects `.pairRecord`; StartService does not send EscrowBag.
Thus current DiPlay cannot restore or supply escrow material after restart.
No claim is made that every service requires it; pairing validation and
service-specific escrow requirements are different questions.

## 4. Persistent-state boundary and restart behavior

[`AirPlayPersistence`](../common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt)
lines881-946 is wired by the activity callbacks, not called by the standalone
pairing client.

| Stage | Local state | Device state / persistence |
|---|---|---|
| Controller construction | Random HostID/SystemBUID in RAM | None from identifier generation. |
| `loadLockdownRecord` | Reads existing private preferences | No phone request. Persistence may predate this run. |
| Certificate generation | Keys/certificates in RAM | No device mutation from generation alone. |
| Successful SetValue(UntrustedHostBUID) | Still no record saved by client | First explicit write to Lockdown state. Its internal durability is not observable here. |
| Pair / user Trust decision | Prepared RAM record exists | May establish/update persistent iOS host trust/pairing. Exact internal commit timing is not exposed; no atomic rollback contract. |
| Successful Pair return | Record plus optional RAM EscrowBag | Device may already be paired. |
| `.pairRecord.also(savePairRecord)` | Prefs values changed and disk write scheduled by `apply()` | Local durable write is asynchronous, not acknowledged. |
| StartSession/TLS | Live session/transport state | Starts authenticated session; not the first pairing-persistence boundary. |

Preferences name is `xcertplay_airplay`, opened with `MODE_PRIVATE`.
It stores one global record (not a per-device/UDID map): HostID, SystemBUID,
WiFiMAC, device public key/certificate, host private key/certificate and
root private key/certificate. Arrays are stored as **hex text**, not
encrypted using Android Keystore. Hex is encoding, not protection.
This is distinct from the same file's AirPlay pairing entries.

Once the asynchronous write completes, material is intended to survive
process/app/head-unit restart under the same installed app package and data
directory. Ordinary app update preserves data; clearing data/uninstalling
does not. Debug and other package identities need not share records.
[`mobile manifest`](../mobile/src/main/AndroidManifest.xml) disables backup.
No crash/power-loss durability or physical E01 storage result was tested.

Missing fields return null; any restore/hex exception is caught and also
returns null without a distinct corruption outcome. There is no UDID/device
match check before reuse. A different phone may therefore receive a
StartSession attempt using the single saved record.

Already-paired path:

1. Load record; skip Pair and all pairing-input GetValues.
2. Attempt syslog session/service, then CarKit session/service, with saved
   identifiers/material. No preliminary ValidatePair.
3. On the CarKit attempt only, an exception message/cause containing
   InvalidPairRecord or InvalidHostID causes local clear, new Pair, local save
   and a second CarKit attempt. Syslog errors are caught separately.
4. Other errors fail the stack. Clearing local prefs does not Unpair the
   iPhone or revoke an existing Trust relationship.

If the phone is paired to QDrive or another computer but DiPlay has no
matching record/private identity, DiPlay cannot infer or reuse that pairing.
The normal implementation generates its own identity and requests Pair.
Do not import QDrive records or assume prior QDrive Trust applies to DiPlay.

## 5. ValidatePair, StartSession, TLS, StartService and StopSession

### ValidatePair: absent in DiPlay, independently identifiable

No transport source implements or sends ValidatePair. The native QDrive
wrapper at0xA5F78 calls the common pair-operation builder at0xA61E0 with
`Request="ValidatePair"`, no PairingOptions. It uses the available pair record,
removes RootPrivateKey/HostPrivateKey from the wire copy, adds ProtocolVersion
text"2" and Label when configured. Other stored entries (for example escrow
or WiFi metadata) can remain in that native copy; it is not proven to contain
only DiPlay's five Pair fields. It has **no call to StartSession**.
Successful ValidatePair does not enter its Pair-specific record-save branch.

Structural minimal future request (not an implemented DiPlay request or a
byte-for-byte reconstruction of every possible native stored record):

```text
Label: chosen diagnostic label
Request: ValidatePair
ProtocolVersion: "2"
PairRecord: same certificate/HostID/SystemBUID dictionary used for Pair
```

This checks a credential-bearing pairing relationship; it is not harmless
ProductType metadata. The examined implementation does not create pairing
or start TLS in this operation. Require a correctly correlated response,
no Error and, when present, Result=Success; treat unsupported/invalid/denied/
locked responses as STOP, not permission to call StartSession.
Do not substitute StartSession as a validation probe.

### Exact existing DiPlay session/service sequence

[`LockdownCarKitClient.openService`](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt)
lines24-115:

1. New TCP62078 connection, connect budget5000ms.
2. Plaintext request:
   `{Label, Request="StartSession", HostID=<saved/generated>, SystemBUID=<same record>}`.
3. Reject Error; require boolean `EnableSessionSSL=true`. False/missing
   is an explicit failure, not plaintext fallback.
4. Detach the existing TCP stream from plaintext framing and pass it to
   `TlsDuplexChannel.open(..., handshakeTimeoutMillis=5000)`.
   **TLS starts on the same Lockdown TCP stream**, not a different port.
5. After completed handshake send, over TLS:
   `{Request="StartService", Service=<caller-selected service>}`.
   This dictionary omits Label and EscrowBag.
6. Reject Error; require integer Port in1..65535.
   `EnableServiceSSL` boolean controls service TLS; missing/wrong type is
   treated as false by current code.
7. Connect one new USBMUX TCP stream to the returned port, budget5000ms.
   If requested, perform a second5000ms TLS handshake on that service
   stream. Return service plus ownership of secure Lockdown connection.
8. Close service and secure Lockdown when the returned wrapper closes.

All plist receives have5000ms budgets; writes retain underlying transport
bounds. There is no explicit session-request retry in this client.
The controller's rejected-record fallback is a separate, broader retry.

**SessionID gap:** DiPlay does not read, validate, retain or use SessionID
from StartSession. No echoed-request or Result success check is performed
by `rejectError`; it only rejects Error. A proper session audit/test must
not regard EnableSessionSSL alone as a complete StartSession contract.

### TLS implementation and portability

[`LockdownTlsEngineFactory`](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownTlsEngineFactory.kt)
`create` line25 loads **RootPrivateKey + RootCertificate**, not host key/cert:
PKCS#8 -> RSA KeyFactory, X.509 CertificateFactory -> temporary KeyStore
key entry -> `KeyManagerFactory("PKIX")` -> `SSLContext("TLS")` -> client
SSLEngine. A custom trust manager accepts all peer certificates.
Endpoint identity checking is unset; this is not ordinary authenticated
internet TLS and must not be reused as such. Successful TLS would not
independently prove the device certificate matched the pairing record.

[`TlsDuplexChannel`](../shared/src/main/java/com/shilapi/xcertplay/transport/TlsDuplexChannel.kt)
lines393-427 filters supported protocols to TLSv1.2/TLSv1.3 only, then drives
beginHandshake, delegated tasks, wrap/unwrap over the mux TCP stream.
No TLSv1.0/1.1 fallback exists. Handshake deadline5000ms, receive chunk16KiB,
buffer cap1MiB; failure closes the owned stream. Close_notify and underlying
close errors are swallowed by existing TLS close behavior.

**Cleanup gap:** after secureLockdown is created, `openService` closes it
in its failure `finally` only when `serviceStream != null`. A StartService
error, malformed returned port or service-connect/TLS failure before that
assignment can leave the secure Lockdown connection unclosed. This and
the missing StopSession are reasons not to reuse this opener for diagnostics.

### StopSession

DiPlay has **no StopSession request**. Closing TCP/TLS is its current cleanup,
not evidence that a StopSession reply succeeded.

QDrive's0xA5224 builder sends `{Label if set, Request="StopSession",
SessionID=<retained session>}`, receives/validates a reply, clears its session
ID and disables session SSL. Client-free0xA55A4 invokes it only if a session
ID exists, then frees the connection. Starting a new QDrive session also
stops an existing one first. No StopSession is needed in a pair-only test
that never creates a session; do not send it as speculative cleanup.

## 6. Read-only versus mutating requests

| Request/operation | Classification | Boundary |
|---|---|---|
| QueryType | Read-only service identification | Proven on E01. |
| GetValue(ProductType) | Read-only benign model metadata | Proven on E01; not a Trust request. |
| GetValue(DevicePublicKey), GetValue(WiFiAddress) | Reads, not pairing writes | Sensitive/device-specific inputs; permissions/lock state may limit access. Do not dump arbitrary GetValue data. |
| SetValue(UntrustedHostBUID) | **Device-state write** | Happens before Pair in the existing client. |
| Pair and approving iOS Trust | **Pairing/trust mutation** | Can create/update persistent relationship and return credential material. |
| ValidatePair | Credential-bearing verification | No Pair save/session startup in examined native implementation; not authorized by a read-only diagnostic. |
| StartSession | Authenticated live-session creation | May enable TLS; not read-only discovery. |
| TLS handshake | Session transport establishment | Uses private identity; not a Lockdown plist request. |
| StopSession | Ends live session | Does not revoke pairing/trust. |
| StartService | Device service startup / new endpoint | Outside pair-only scope, including syslog and CarKit. |
| Unpair / RemoveValue / generic SetValue | State mutation | Not a cleanup shortcut; prohibited in the proposed test. |
| Local record load/save/clear | Credential persistence access/mutation | Separate authorization from USB read-only discovery. |

Read-only means the inspected request does not ask to mutate pairing, not
a guarantee that iOS cannot show an unsolicited prompt or alter internal
housekeeping. USB permission, iOS Trust, paired identity, session TLS and
MFi authentication are separate gates.

## 7. Trust, declined Trust, locked phone and existing pairing

DiPlay does not programmatically approve Trust. Pair is the point that asks
for a host pairing relationship; its pending-dialog response is explicitly
handled. The preceding UntrustedHostBUID write primes the identity context;
the source cannot prove the exact instant iOS displays its dialog.
Only the user can approve on the phone, potentially with device passcode.

| State/outcome | Existing behavior / limits |
|---|---|
| Valid DiPlay record already stored and accepted | Skip Pair; directly try StartSession and services. No preliminary validation. A new prompt is not guaranteed absent. |
| Phone paired elsewhere, no matching DiPlay record | Generate independent identity and Pair; do not borrow another app's records. |
| PairingDialogResponsePending | Automatic reconnect/Pair loop until deadline/cancel. It does not approve the dialog. |
| Trust declined -> UserDeniedPairing | Typed failure; no client retry of that error. Earlier SetValue still happened. |
| Locked -> PasswordProtected | Typed failure when returned by Pair, generic RemoteError when returned by earlier reads/write. No unlock/passcode bypass. |
| InvalidHostID / InvalidPairRecord | Pair client stops; full controller may clear/re-pair when a saved record's CarKit attempt fails. |
| Timeout/detach/cancellation | Fail/close; cannot assert phone remains unpaired. Late approval or lost successful reply can leave device state without a saved usable local record. |

The exact iPhone15,4/iOS response when locked or already paired is not supplied
by discovery evidence. Do not infer Pair acceptance, trust notification timing,
EscrowBag availability or session TLS behavior from ProductType alone.

## 8. QDrive structural comparison (native static evidence only)

Examined local
[`libusbserver.so`](../vendor-apks/QDrive_Global/lib/arm/libusbserver.so),
SHA256 `87748337AB3B0BB70AFB037E412454C74B1BB419CA01E270B666EB225C8AEA54`.
Internal functions are stripped; names below are inferred from associated
function-name literals, protocol builders and calls, not claimed exported
symbols. Thumb code, PC-relative string references and ARM PLT/relocations
were inspected. No key/certificate/record contents were extracted or displayed;
only protocol field names, generic log templates and storage structure.

| Address / evidence | Recovered structural behavior |
|---|---|
| 0x96974 preflight;0x969D6 ->0xA5C28 | Create plaintext Lockdown client; QueryType at0x96A46. It does not use the all-in-one session handshake at initial client construction. |
| 0x96A66..0x96A80 | Check per-device record existence, get saved HostID, try StartSession if present. |
| 0x96AC6..0x96AEA | Certain saved-session failures remove local device record and recreate the client; not a read-only fallback. |
| 0x96B6A..0x96C2E | Inspect device ProductVersion and branch for older/newer iOS behavior; the older branch also starts a notification service. Do not copy this whole preflight into a pair-only test. |
| 0x96C52..0x96C84 | Initial Pair via0xA5F24; on success reload saved HostID and **StartSession**. |
| 0x96D9A ->0xA5F78 | Following successful StartSession, this preflight then ValidatePairs. Its ordering is **Pair -> StartSession/TLS -> ValidatePair**, not the requested isolated ordering. |
| 0x96CFC..0x96D2C | Log waiting for user Trust, register notifications and wait with1-second sleeps/state checks. No finite overall deadline is established in this loop. |
| 0x96EB4..0x96EF0 | Set UntrustedHostBUID using the persisted system BUID. |
| 0x96F7C..0x96FD8 | On the paired/trusted notification, log trusted, open a separate plaintext client and Pair again; free client afterward. This is application-level completion/retry, not a one-Pair contract. |
| 0xA5D30..0xA5ED0 | Generic `client_new_with_handshake` takes another path: QueryType, read saved record/HostID, Pair when necessary, ValidatePair, invalid-host re-Pair + revalidate, then StartSession. Do not confuse it with the preflight order above. |
| 0xA5F24 /0xA5F78 ->0xA61E0 | Separate Pair and ValidatePair wrappers; Pair adds ExtendedPairingErrors; ValidatePair does not start a session itself. |
| 0xA62D4..0xA63C6 | Pair can generate missing record from DevicePublicKey, certificates, system BUID and new HostID; ValidatePair loads available record instead and fails if absent. |
| 0xA63F8..0xA6466 | Copy record, remove RootPrivateKey/HostPrivateKey from wire dictionary, set PairRecord/Request/ProtocolVersion text"2"/optional PairingOptions. |
| 0xA65CC..0xA6650 | Only successful Pair's local-record branch copies returned EscrowBag, stores WiFiMACAddress, and saves the record. ValidatePair does not enter it. |
| 0xA5414..0xA555E | Response checker requires string Request (matches operation when supplied), handles legacy Result=Success/Failure, otherwise maps Error. |
| 0xA5F94..0xA6182 | StartSession sends HostID and persisted SystemBUID, retains SessionID, reads EnableSessionSSL and starts TLS when enabled. Unlike DiPlay, this function permits the non-SSL response branch. |
| 0xA5224 /0xA55A4 | Explicit StopSession and session-aware client free. |

### QDrive persistence and identity

Native userpref wrappers0xA7E10/0xA8044/0xA7FE4 use local usbmux control
ReadBUID/ReadPairRecord/SavePairRecord; these are **not** TCP Lockdown requests.
Record parsing accepts binary or XML plist. Server-side handlers include
the field names at0x9243E..0x92462 and configuration functions0x949CC onward.

- Configuration directory is a configured base directory plus `lockdown`
  (0x949DC..0x949FC), not proven to be a desktop `/var/lib/lockdown` path.
  The actual Android/E01 parent directory was not established here.
- `SystemConfiguration.plist` contains a reused SystemBUID
  (0x94B20 onward). If missing, a36-character hyphenated hex identifier
  is generated and written into configuration state.
- Per-device records use `<device identifier>.plist`; HostID is read from
  that record (0x94F20 onward). QDrive therefore has per-device lookup,
  unlike DiPlay's single preference slot.
- New HostID generation0xA8C74 produces a36-character hyphenated hex string.
  ARM PLT relocations identify `time`, `srand48` and `lrand48` in these
  identifier-generation paths. Do not reproduce this as a credential RNG.
- The Pair builder's0xA80F0 helper generates certificate/key material
  locally for a new identity. Private keys remain in the local record, not
  the wire PairRecord. Exact certificate-profile equivalence to DiPlay was
  not established and is not necessary to identify the stopping boundary.
- EscrowBag is retained in the saved native Pair record; StartService's
  builder0xA69E0..0xA6A1A has an escrow-bearing branch.

These are persistence mechanisms, not a claim that files were inspected,
that the configured directory is writable on E01 or that every vendor
restart restores them. No live pairing material was accessed.

### QDrive TLS: a concrete mismatch, not a reason to downgrade DiPlay

Session SSL continuation0xA6168 calls property-list SSL enable0xA9B60,
ultimately the native enable function0xA4F60.
It loads saved **RootCertificate / RootPrivateKey**, creates an OpenSSL
context, installs root certificate/private key, sets client mode and uses
the existing TCP transport BIO:

- 0xA4FDC -> ARM PLT0x791FC -> relocation0x2CE8AC:
  **`TLSv1_method`**, not a demonstrated TLSv1.2/1.3 negotiated context.
- 0xA4FE0 ->0x79208 /0x2CE8B0: SSL_CTX_new.
- 0xA500E /0xA5058: SSL_CTX_use_certificate / SSL_CTX_use_RSAPrivateKey.
- 0xA509A ->0x7928C /0x2CE8DC: SSL_set_verify with mode0.
- 0xA50AA ->0x792A4 /0x2CE8E4: SSL_do_handshake; require return1.

No explicit bounded handshake/retry loop was identified in that caller.
It uses bundled native OpenSSL, not API22 Android SSLEngine. Its TLSv1
construction and absent peer verification must not be copied as a workaround.
The user-proven DiPlay result is plaintext discovery, not evidence that the
same phone accepts DiPlay's TLSv1.2-only-on-API22 session policy.
Likewise "QDrive works on E01" does not prove which native branch/session/TLS
version ran for this exact phone; no live QDrive session trace was supplied.

## 9. API22 compatibility and pre-runtime blockers

No new unguarded API-above22 call was identified in the inspected pairing/
record/TLS helper code. This is narrower than certifying the whole controller.
Prior [compatibility work](COMPATIBILITY.md) already replaced java.util.Base64
with Android Base64 and guarded newer USB/SSL APIs.

| Area | Static conclusion / remaining requirement |
|---|---|
| Base64 / PEM | `Base64Compat` uses Android util.Base64, API22-compatible; no java.util.Base64 in these helpers. |
| UUID, RSA2048, SHA256withRSA, PKCS#8, X.509, prefs | Available APIs; provider behavior, randomness, clock and certificate acceptance still require isolated verification. |
| KeyManagerFactory("PKIX") | Android's official table says API1+. **Not an identified API22 incompatibility**; do not mistake host-JDK provider differences for Android missing support. |
| SSLContext("TLS"), TLSv1.2 | Official table says API1+ and API16+ respectively. API22 can offer TLSv1.2; TLSv1.3 is API29+ and is filtered out when unsupported. |
| Endpoint-identification setter | Guarded by SDK>=24 in TLS factory; not called on API22. |
| SSLEngine handshake states | Uses existing enum states; NEED_UNWRAP_AGAIN is tested by name, not a newer enum-field access. |
| Generated certificate profile | Source explicitly notes host JBR CertificateFactory rejects the empty issuer DN. Generation verifies only TBS signatures. Android provider parsing and iOS acceptance of this exact profile are **unproven**, a pre-session portability blocker, not a diagnosed API22 NoSuchMethodError. |
| TLS policy versus QDrive | Concrete policy difference: DiPlay excludes TLS1.0/1.1; recovered QDrive caller uses TLSv1_method. Do not enable weaker TLS speculatively. Audit phone/provider compatibility separately before any StartSession phase. |
| Full normal USB/NCM/controller | Setter/force=true/NCM/service side effects are outside the proven diagnostic boundary. minSdk22 and compiling alone do not certify them. |
| Persistence and validation | Missing ValidatePair/StopSession, non-device-indexed records, dropped EscrowBag, async persistence, permissive Pair response checks and cleanup gaps must not be inherited by a controlled test. |

Official algorithm references:
[Android KeyManagerFactory](https://developer.android.com/reference/javax/net/ssl/KeyManagerFactory),
[Android SSLContext](https://developer.android.com/reference/javax/net/ssl/SSLContext).
No provider/engine was initialized, generated record created or TLS connection
started to make these static conclusions.

## 10. Smallest future controlled Pair/ValidatePair design (NOT implemented)

Isolation is possible because the low-level Pair client has no session/service
continuation and the native ValidatePair wrapper is independent. It is **not
safe to run the present full startup path**. Pair may persist iPhone Trust even
if validation/cleanup later fails; STOP does not mean rollback.

Before a separately authorized implementation/test:

1. Retain all proven config5 identity/Valeria/NCM-descriptor prerequisites,
   existing Android permission, same-handle GET5 and scoped claim(false).
   Reuse only the strict version/setup/TCP prefix, not normal USB/controller
   startup. NCM descriptors are metadata, not networking.
2. Define an isolated API22-compatible identity/record policy: stable HostID/
   SystemBUID, explicit per-device association, protected private storage,
   save-result verification and recovery for lost replies/cancel/power loss.
   Preserve HostID/SystemBUID before the first device write, and the complete
   generated candidate before sending Pair; distinguish a pending/unvalidated
   candidate from a confirmed record. Do not overwrite, clear, import or
   silently regenerate an existing record.
3. Verify certificate generation/parsing offline with representative test
   public-key fixtures and the intended Android provider; do not treat a
   signature-only check or a desktop build as API22 certification.
4. Add a narrow ValidatePair operation and correlated Pair/ValidatePair
   response validation, plus injected tests forbidding StartSession/TLS/
   StopSession/StartService/records export/projection dependencies.
   These are missing next-phase implementation prerequisites, not code added
   here. No runtime TLS work is needed for Pair/ValidatePair.
5. Confirmation must explicitly disclose SetValue(UntrustedHostBUID), local
   credential persistence, iOS Pair/Trust and their durable side effects.
   Trust approval is a new user-authorized step on the phone, never automatic.

Proposed isolated protocol boundary:

```text
verify config5 -> scoped claim(false) -> version -> setup07
 -> one TCP62078
 -> explicit SetValue(UntrustedHostBUID)
 -> GetValue(DevicePublicKey) + GetValue(WiFiAddress) only as needed
 -> prepared matching identity / certificates, verified candidate persistence
 -> Pair
 -> only after an unambiguous successful Pair: ValidatePair once
 -> close plaintext TCP / stop reader / release / close -> STOP
```

For an already-associated local record, a separately confirmed **validation-only**
branch can send ValidatePair and STOP. It must not auto-Pair on rejection.
It proves record validation, not a new Trust decision or TLS readiness.

Do not promise one Pair will complete first-time Trust:

- If the bounded Pair returns PairingDialogResponsePending, report PENDING
  (not PASS), retain the same prepared candidate, cleanup and STOP.
- Completing Pair after separately authorized user Trust may require a
  second explicitly confirmed attempt with the **same** identity/material.
  QDrive's notification callback and DiPlay's retry loop demonstrate this
  distinction. No automatic reconnect/re-Pair loop belongs in the smallest
  experiment; the retry policy must be specified in the next authorization.
- Denied Trust, locked phone, malformed/unsupported response, record mismatch,
  persistence error, detach or timeout: precise stage/error, cleanup and STOP.
  Never fallback to StartSession to "see whether it worked".
- Pair succeeds but ValidatePair or cleanup fails: report pairing may already
  persist; withhold final PASS, preserve recovery material and STOP.
  Do not send Unpair, reset trust, restore configuration1 or delete credentials
  as automatic recovery.

PASS would require correlated Pair success (or explicitly validation-only
mode), ValidatePair success, verified record handling and successful cleanup.
It must state `STARTSESSION / TLS / STARTSERVICE NOT STARTED`.
ValidatePair success is not proof of TLS, service, CarKit, iAP2 or MFi readiness.
No proposed step includes syslog, CarKit, NCM, AirPlay or CarPlay.

## 11. Unresolved hardware/provider facts and audit verification

- Exact iOS version, Trust timing, locked-state response, notification
  behavior and record/escrow response shape on this iPhone15,4.
- iOS internal persistence timing/atomicity, especially after timeout,
  cancellation, late Trust approval or a lost successful Pair reply.
- Android API22 provider and phone acceptance of DiPlay's certificate profile;
  saved record/device matching and durable protected-storage policy.
- TLSv1.2 interoperability versus the recovered QDrive TLSv1 caller.
  No TLS negotiation or downgrade is authorized by this phase.
- QDrive's configured storage parent, actual restart durability, exact
  certificate profile and which preflight/session branch ran on real E01.
  These are not reasons to copy vendor credentials or execute its library.

These uncertainties limit hardware/compatibility claims, not the identified
Pair-versus-session call boundary. Static checks found no ValidatePair,
StopSession or SessionID handling in DiPlay's transport/persistence sources.
The native evidence establishes separate Pair/ValidatePair wrappers,
Pair-specific record saving and explicit subsequent StartSession/TLS calls.
Only generic native protocol names/instructions were examined; no records,
certificate bodies or private-key values were read or printed.

Documentation-only validation: relative link targets and `git diff --check`.
No build or tests were run for N; previous discovery test/build results remain
historical, and user-reported real discovery PASS is recorded separately.
No runtime diagnostic for Pair/ValidatePair was implemented.

PAIR/TRUST FLOW FULLY IDENTIFIED — CONTROLLED PAIR TEST CAN BE DESIGNED
