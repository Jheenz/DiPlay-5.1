# Phase 3D.2R - Modern Lockdown StartSession reference audit

## Scope and outcome

Research and static audit only. No real USB/iPhone operations, Lockdown
requests, TLS, services, authentication, network-to-device or projection.
No pairing-record lookup/persistence changes, writes, migrations, repairs,
deletions or identity generation. No runtime diagnostic is implemented.
minSdk22 and CONNECTIONS_ENABLED=false remain unchanged.

**Maintained upstream implementations support modern StartSession without
the legacy ValidatePair request.** The failed E01 validation is not a
prerequisite that must be made successful before a modern session audit.
The references do not prove why that particular peer sent RST.

A future isolated one-StartSession/no-TLS response probe can be designed
using the verified accepted record. It must not call the existing normal
CarKit/session wrapper, which automatically starts TLS and services.
StartSession is state-changing, not read-only. Explicit StopSession is
appropriate only if a usable plaintext session is explicitly confirmed;
when SSL is requested the no-TLS boundary requires closing instead, with
session termination **not claimed as confirmed**.

## 1. Pinned current public reference sources

Upstream default-branch tips retrieved during this audit:

| Project | Commit / date | Files inspected |
|---|---|---|
| libimobiledevice/libimobiledevice | `fa0f79190142bc309307967c058f89c1b36eb6b8`, 2026-06-10 | src/lockdown.c, include/libimobiledevice/lockdown.h, src/property_list_service.c |
| libimobiledevice/usbmuxd | `3ded00c9985a5108cfc7591a309f9a23d57a8cba`, 2025-12-06 | src/preflight.c |
| doronz88/pymobiledevice3 | `e0ab4a52b7f506cd48a1655a5031fed43e01ac14`, 2026-10-07 | pymobiledevice3/lockdown.py, service_connection.py |

These are immutable source references, not statements about every iOS release
or vendor build. Source was read, never imported/executed. No private
repository code, credentials or device records were sent to external services.

## 2. Exact libimobiledevice modern sequence

Pinned [lockdownd_client_new / handshake](https://github.com/libimobiledevice/libimobiledevice/blob/fa0f79190142bc309307967c058f89c1b36eb6b8/src/lockdown.c#L615):

1. client_new creates a plaintext property-list connection to62078, calls
   QueryType, and obtains ProductVersion/DeviceClass when not already known.
2. client_new_with_handshake (lines699-793) creates that one client, loads
   the device-associated saved pair record and extracts its host identity.
3. If the record is absent, this **general-purpose** handshake may Pair.
   That fallback must not be adopted for the controlled existing-record test.
4. **The current condition at line739 is device version <7.0.0 and device
   class not Watch.** Only inside that block does it call ValidatePair;
   the accompanying older-device trusted-host comment matches actual code.
   The legacy InvalidHostID branch can Pair/revalidate on that same client.
5. On the modern branch, resolve/reload the accepted record's host identity
   if necessary, then call StartSession (line776) on the existing client.
   There is no unconditional post-Pair close/reconnect or ValidatePair.
6. Session setup may enable SSL; only a successful high-level handshake
   returns the initialized client. This full helper is not a no-TLS probe.

**Classification: implementation-specific, strongly corroborated modern
client policy**, not an Apple-published universal protocol mandate.
It proves the maintained client does not require modern ValidatePair; it
does not prove ValidatePair is unsupported on every modern device.
Actual ProductVersion was not supplied in the E01 evidence; a future test
may read it safely, reject missing/unparseable/legacy version, and never
derive version from ProductType alone or silently substitute a legacy default.

### Pair and ValidatePair API semantics

[lockdownd_do_pair / wrappers](https://github.com/libimobiledevice/libimobiledevice/blob/fa0f79190142bc309307967c058f89c1b36eb6b8/src/lockdown.c#L898):
Pair and ValidatePair are separate calls using the supplied client. Pair
adds ExtendedPairingErrors; ValidatePair supplies no pairing options.
They send a framed request, receive/check its response and return without
automatically replacing/closing the client. Only successful Pair enters its
pair-record save branch; returned escrow is retained if present.

The source retrieves WiFiAddress before Pair because a later retrieval can
fail on iOS7 and cause reconnect. That comment describes a particular later
GetValue issue, **not** a blanket reconnect-before-ValidatePair requirement.

Pair success means that operation was accepted according to the checked
response, not that a session, TLS or service is already established.
Pending Trust, user denial and locked-phone errors remain separate outcomes.
The later successful modern handshake goes to StartSession using the
accepted identity, without re-validating through the legacy verb.

## 3. StartSession / StopSession upstream behavior

[lockdownd_start_session](https://github.com/libimobiledevice/libimobiledevice/blob/fa0f79190142bc309307967c058f89c1b36eb6b8/src/lockdown.c#L1158):

- Stops an already-known running session before creating another; a fresh
  controlled client must have no existing session to stop.
- Builds Label, Request=StartSession, HostID from the resolved pair record,
  and SystemBUID read from user preferences. No PairRecord, certificates,
  private keys, escrow, PairingOptions or ProtocolVersion are added.
- Receives/checks the correlated result/error.
- On success reads EnableSessionSSL, retains SessionID and, if SSL is
  requested, enables SSL on the same property-list transport before returning.
  Missing SSL field defaults false in this implementation; the future probe
  should instead explicitly report missing/type-invalid as unknown and stop.

StartSession itself is not another ValidatePair wire operation. It presents
the host identity to establish a live session; TLS, when requested, then uses
saved certificate/key material. These references demonstrate operation without
an explicit modern validation verb, not the device's internal validation code.

[StopSession and client free](https://github.com/libimobiledevice/libimobiledevice/blob/fa0f79190142bc309307967c058f89c1b36eb6b8/src/lockdown.c#L232):
StopSession sends Label, Request and the retained session identifier, checks
the reply, clears local session state, and disables SSL **after** the request.
client_free calls StopSession when it has a session identifier, then frees
the underlying connection. Therefore a normal SSL-enabled session stops
over the already-enabled SSL channel, not proven plaintext.

**Meaningful state:** successful StartSession creates live device session
state and may move the connection into SSL-handshake-required state. It is
not a passive read, no-op or rollback of pairing. The sources expose no
new PairRecord write request inside StartSession, but cannot prove every
internal device-side effect or an exact device-state restoration on close.

## 4. Distinguish usbmuxd preflight and modern Python client

[usbmuxd preflight](https://github.com/libimobiledevice/usbmuxd/blob/3ded00c9985a5108cfc7591a309f9a23d57a8cba/src/preflight.c#L133)
is device-arrival/trust orchestration, not client_new_with_handshake:

- Saved records can be tried through StartSession to determine pairing.
- Its iOS7+ branch can trigger Pair/Trust notification handling and make the
  device visible; it does not run the old branch's ValidatePair sequence.
- The iOS6-and-earlier branch performs Pair -> StartSession -> ValidatePair.
- Its Trust notification callback creates/frees a separate Pair client.
  That is implementation-specific Trust completion, not a mandate to
  reconnect for modern validation.

Do not copy this preflight: it includes mutations, services and notifications
outside the requested isolated boundary.

[pymobiledevice3.validate_pairing](https://github.com/doronz88/pymobiledevice3/blob/e0ab4a52b7f506cd48a1655a5031fed43e01ac14/pymobiledevice3/lockdown.py#L579)
independently gates the **wire** ValidatePair request to version<7.0, excluding
Watch. On modern devices it loads the accepted record and sends StartSession
with its saved identities, retains SessionID and starts SSL if requested.
The function name "validate_pairing" must not be confused with sending the
legacy ValidatePair verb on modern devices.

The autopair helper then uses pair -> validate_pairing on the same client
without an unconditional success reconnect. Generic request code can recover
on reset/termination/invalid-connection by reconnecting and retrying, and
can re-establish a session. None of that recovery/fallback belongs in the
future one-shot probe.

[pymobiledevice3.close](https://github.com/doronz88/pymobiledevice3/blob/e0ab4a52b7f506cd48a1655a5031fed43e01ac14/pymobiledevice3/lockdown.py#L899)
closes the underlying service without automatically issuing StopSession;
its explicit stop_session method is separate. This is evidence that clients
use disconnect cleanup, but **not proof of immediate device-side session
reaping**, especially before SSL negotiation has completed.

## 5. DiPlay StartSession path: not safe to invoke as the probe

Local code:

- [CarPlayController.runStack](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt),
  lines1839-1840: loadPairRecord callback, otherwise pairNewRecord.
  Its normal error recovery can clear and re-pair (lines1881-1891).
  Constructor creates fresh identity strings, but a loaded record supplies
  the identities actually used for session requests.
- [AirPlayPersistence.loadLockdownRecord](../common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt),
  lines882-907: normal global legacy preference slot, restores saved material,
  no per-device association. This is **not** the protected diagnostic store
  holding the current accepted PAIRED record.
- [LockdownCarKitClient.openService](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt),
  lines25-58: fresh TCP62078, framed StartSession using the passed record's
  HostID/SystemBUID and Label. No PairRecord/ProtocolVersion or validation.
- It checks Error, requires boolean EnableSessionSSL=true, rejects false,
  detaches the plaintext stream and automatically starts TLS.
- It does **not parse or retain SessionID**, require echoed Request, or
  validate optional Result before the TLS transition.
- After TLS it immediately sends StartService and opens the service stream.
  Its lifetime owner closes streams but never constructs StopSession.
  A production source search found no StopSession/SessionID implementation.

| Structure / behavior | libimobiledevice | Existing DiPlay |
|---|---|---|
| StartSession fields | Label, Request, saved HostID, preference SystemBUID | Label, Request, both identities from passed record |
| PairRecord / ProtocolVersion / PairingOptions | Absent | Absent |
| SessionID | Retained for cleanup | Ignored |
| EnableSessionSSL | Read; supports false or true, enabling SSL when true | Boolean required; false rejected, true automatically starts TLS |
| Reply validation | Correlated result/error checker | Error-only check |
| StopSession | Implemented; client-free attempts it when session known | Absent |
| Next stage | Session returned to caller | Automatically StartService/CarKit or another selected service |

DiPlay's non-secret request structure broadly agrees with upstream; using
the protected record's own SystemBUID avoids substituting regenerated identity.
The existing wrapper is not sufficiently isolated and must **not** be reused
for a no-TLS/no-service probe. This is a future diagnostic design constraint,
not authorization to modify normal startup or fix unrelated legacy paths.

## 6. Designed future boundary - NOT implemented or run

Requires new explicit user authorization because StartSession changes live
state. Preserve accepted persistence; no promotion from PAIRED to another
record state and no credential mutation.

```text
manual mutation warning/confirmation, not normal startup
-> read-only protected accepted-record preflight (PAIRED/VALIDATED)
-> fail closed on empty/PREPARED/corrupt/key/association problems
-> exact proven USB/config5 permission/descriptor/same-handle guards
-> claim(false), version, setup07
-> ONE fresh TCP62078 connection, no throwaway connection or reconnect
-> QueryType == com.apple.mobile.lockdown
-> bounded GetValue(ProductVersion) to confirm modern version policy
-> GetValue(UniqueDeviceID), never log/export it
-> exact load/verify matching existing accepted record
-> exactly ONE StartSession using that record's identities
-> parse safe response metadata
-> conditional cleanup below -> release/close -> STOP
```

Before the request, use a separate capability/allowlist admitting only
QueryType, the bounded association/version GetValues, StartSession and
conditional StopSession. It must have no generator, Pair, ValidatePair,
SetValue, store.save/clear/migration, TLS factory, service or normal-controller
dependency. Any unmatched/unknown identity stops **before** session request.
Multiple-record ambiguity must not be silently resolved by list position.

Report only request attempted/count, correlated result, safe allowlisted
error code or UNKNOWN_ERROR, session-identifier-present/type-valid boolean,
SSL field present/type-valid/requested booleans, transport flags and cleanup.
Do not log response dictionaries, arbitrary ErrorString, session identifier
values, stored identities, device identifiers or credential material.

### Cleanup by response branch

| Response | Designed action without TLS/services |
|---|---|
| Correlated Error or failed Result | Safe status -> close -> STOP; no StopSession guessed, no retry/re-pair or record mutation. |
| Success, explicitly EnableSessionSSL=false, valid nonempty SessionID | Exactly one bounded StopSession using identifier retained only in RAM; require correlated safe result, then close. Report confirmed stop only on accepted reply. |
| Success, EnableSessionSSL=true | Report SESSION_ACCEPTED_SSL_REQUESTED, TLS starts0. Do **not** send plaintext StopSession or create TLS solely for cleanup. Close TCP, host/pipe/interface/USB and STOP; report SESSION_STOP_NOT_CONFIRMED / CONNECTION_CLOSED_BEFORE_TLS. |
| Missing/malformed SSL field or SessionID, mismatched reply, EOF/RST/timeout | Precise failure/unknown-session-state -> close; no speculative StopSession, reconnect or success fallback. |

StopSession is the reference's explicit live-session termination operation;
it is not Pair/Unpair or a local persistence write. The first probe may permit
it for an explicitly plaintext successful session as cleanup, at most once.

When SSL=true, the reference enables SSL before later session operations.
There is **no source proof** plaintext StopSession is valid while the device
expects TLS. Closing is bounded abort cleanup consistent with the user's
explicit no-TLS requirement; it does not confirm StopSession or immediate
server-state rollback. If a future test requires *confirmed session teardown
on every success branch*, that stronger requirement is incompatible with
this no-TLS design and needs separate authorization/design rather than a
hidden TLS exception.

Success wording must distinguish an accepted StartSession response from
SESSION/TLS_ESTABLISHED, which is not tested here. Pair/ValidatePair/SetValue,
TLS, StartService, CarKit/iAP2/MFi/NCM/AirPlay/CarPlay counts remain zero;
StartSession may be1 and plaintext cleanup StopSession0or1.

## 7. RST interpretation and validation

Modern source policy supplies a better next investigation than insisting on
legacy validation success: **skip ValidatePair and audit StartSession**.
It does not establish the precise reason for the observed RST, prove modern
ValidatePair always resets, or prove reconnect would fix it.

Only this audit document is created in this phase. No production/test code,
pair-record persistence/lookup, version or build changes. No hardware test
implemented/run, no native code execution or credential extraction.
Documentation references/whitespace and source minSdk22/disabled gate checked;
no unit tests/build required for documentation-only work.

MODERN REFERENCE SUPPORTS STARTSESSION WITHOUT VALIDATEPAIR — CONTROLLED STARTSESSION TEST CAN BE DESIGNED
