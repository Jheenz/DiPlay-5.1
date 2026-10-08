# Phase 3D.2S - Controlled modern StartSession diagnostic

## Build and scope

Implemented a separate, explicitly confirmed Settings/Diagnostics action:

- **Phase 3D.2S - Modern Lockdown StartSession**
- **Test StartSession (NO TLS / NO SERVICES)**
- **STARTSESSION ONLY - NO VALIDATEPAIR / TLS / STARTSERVICE**

The UI displays the requested wording with em dashes. Nothing runs at
startup or merely opening Settings. Results are included in **Save diagnostic
report**, including STOP/failure reports.

Version: `0.2.12-api22-phase3d2s-startsession`.
Package: `com.shihab.diplay.legacytest`; versionCode31; minSdk22.
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false` remains unchanged.
No development-time USB/iPhone operations or hardware test were performed.

## Implementation boundaries

- [ControlledStartSession](../shared/src/main/java/com/shilapi/xcertplay/transport/ControlledStartSession.kt):
  isolated one-shot protocol logic. Its record capability has only
  acceptedRecords/load; no save, delete, systemBuid generation, repair or
  migration API. Production verification uses DiagnosticPairMaterial.verify.
- [ModernStartSessionDiagnostic](../common/src/main/java/com/shilapi/xcertplay/ModernStartSessionDiagnostic.kt):
  inventory/configuration/claim/init/QueryType gates, cancellation, report,
  unconditional release-if-claimed and close, no retries/reconnect.
- [AndroidReadOnlyLockdownAccess](../common/src/main/java/com/shilapi/xcertplay/AndroidReadOnlyLockdownAccess.kt):
  reuses the already-proven scoped configuration5 transport, not the normal
  CarPlay session wrapper. The added session capability admits only one
  association GetValue, one StartSession, and a conditionally authorized
  single StopSession. It prohibits mixing this path with controlled pairing.
- [LockdownPlistChannel](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPlistChannel.kt):
  optional framing-length callback reports the actual encoded frame length,
  not an estimated request size. Existing default request behavior is retained.
- [DiPlayActivity](../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt)
  and [strings_legacy](../common/src/main/res/values/strings_legacy.xml):
  separate manual confirmation, worker, saved report/export, mutual exclusion
  through the existing diagnostic busy gate, detach observation and
  cancellation on Activity pause/destroy.
- [mobile build configuration](../mobile/build.gradle.kts):
  requested debug version suffix; existing signing/minSdk/package retained.

Pairing-record persistence and lookup implementation are unchanged.
The legacy Pair and ValidatePair actions remain separate historical actions;
this diagnostic never calls them or the normal LockdownCarKitClient wrapper.

## Exact request order

1. Local-only preflight must find **exactly one** readable accepted PAIRED or
   VALIDATED record. Empty, PREPARED-only, ambiguous, undecryptable or invalid
   material stops before USB opens. No record is selected by list position.
2. Require the permitted Apple05AC:12A8, existing Android USB permission,
   five post-transition configurations and the proven configuration5
   Valeria/NCM/USBMUX descriptor guards.
3. Open once. Same-handle GET_CONFIGURATION must equal5.
4. Claim only the scoped configuration5 USBMUX interface with force=false.
5. Successful USBMUX version exchange and setup07; one fresh TCP62078.
6. QueryType must confirm `com.apple.mobile.lockdown`.
7. One GetValue(UniqueDeviceID), retained only locally; load the exact
   associated accepted record, verify its material and identities against
   the preflight candidate. Mismatch/unavailable association stops.
8. One StartSession with exactly Label, Request, HostID and SystemBUID.
   Identities come from the accepted associated record. No PairRecord,
   PairingOptions or ProtocolVersion; no regeneration or substitute identities.
9. Validate echoed Request, Error and optional Result. Require an explicit
   boolean EnableSessionSSL and a nonblank bounded textual SessionID for
   confirmation; no absent-field default to plaintext.
10. Conditional cleanup below, release/close and STOP.

The phase does not automatically execute QDrive, select a USB configuration,
request permission, or run earlier preparation actions. It requires their
already-proven state. Unlike the optional version-read design in the R audit,
this smallest implementation uses only the requested association GetValue;
it makes no claim to discover the connected iOS version.

## Response and cleanup

| Outcome | Behavior |
|---|---|
| SSL=false and valid SessionID | Exactly one bounded StopSession using the identifier held in RAM; check correlated response/error/result. Release/close. Confirmation is withheld if StopSession or transport cleanup fails. |
| SSL=true and valid SessionID | No TLS, services or plaintext StopSession. Close the connection, USBMUX host/pipe, release interface and close USB. Report `STARTSESSION SUCCEEDED - TLS REQUIRED - SESSION STOP NOT CONFIRMED` (em dashes in UI/report). |
| Error, malformed or mismatched response | Safe error/status; no guessed StopSession, retry or fallback. Release/close. |
| RST, EOF, timeout, write or other transport failure | Safe stage/classification and existing transport-state latches; release/close. No reconnect. |
| Cancellation/detach | Stop progression and clean up. No recovery or forced session termination over an unproven channel. |

Explicit StopSession is valid here only after an accepted SSL=false session.
There is no source proof that plaintext StopSession is valid when TLS is
requested. Closing before TLS is bounded abort cleanup, **not** proof of
immediate device-side session reaping or rollback.

Classification strings:

- `STARTSESSION CONFIRMED - SSL NOT REQUIRED`
- `STARTSESSION CONFIRMED - TLS REQUIRED`
- `STARTSESSION REJECTED`
- `STARTSESSION TRANSPORT FAILURE`
- `PAIR RECORD ASSOCIATION FAILURE`

The first two use em dashes in UI/report. Confirmation is only finalized
after transport cleanup. Observer-cleanup failure explicitly withholds it.
Association-query rejection is classified as association failure, not a
StartSession rejection when that request was never sent. A StopSession
failure is classified as a transport-path/cleanup failure at StopSession,
not rejection of the already-accepted StartSession.
StartSession acceptance does not prove an established TLS session, CarPlay
readiness, service availability, iAP2 or accessory authentication.

## Secret-safe report

Report only request attempts, actual XML/framed byte lengths, transport/
parsed-response presence, SessionID presence/validity, explicit SSL boolean,
allowlisted Lockdown errors (unknown remote values become UNKNOWN_ERROR),
fixed failure classes/stages, transport latches and cleanup results.

No HostID/SystemBUID values, UDID/device identifiers, SessionID value,
certificates, private keys, EscrowBag, raw XML/dictionaries, PairRecord or
arbitrary exception/remote error text are logged/exported.

The session path has no TLS factory, service client, normal controller,
identity generator or persistence mutation capability. Forbidden operation
counts are zero: Pair, ValidatePair, SetValue, identity generation, record
writes, TLS, StartService, CarKit, iAP2, MFi, NCM, AirPlay and CarPlay.
StartSession is at most1; conditional StopSession is at most1.

## Tests and verification

**47 focused tests passed; zero failures/errors.** Tests are PC-local injected
inventories/record boundaries and mocked Android USB packets, not hardware.

- [ModernStartSessionDiagnosticTest](../common/src/test/java/com/shilapi/xcertplay/ModernStartSessionDiagnosticTest.kt):
  8 tests covering PAIRED/VALIDATED acceptance, missing/PREPARED/multiple/
  unreadable/invalid records, exact identity association, no fallback or
  mutation capability, request field shape, one-shot reuse rejection,
  SSL branches, SessionID forwarding without logging, missing/type-invalid
  response fields, safe remote errors, failures at every transport/cleanup
  stage, cancellation, permission/configuration/device-count guards.
- [AndroidModernStartSessionAccessTest](../common/src/test/java/com/shilapi/xcertplay/AndroidModernStartSessionAccessTest.kt):
  3 tests verifying real scoped adapter/framing, exact XML/frame length,
  one version/setup/TCP connect, SSL-dependent StopSession, no permission
  request/configuration change/service/TLS traffic, cleanup, mismatch/
  rejection and local preflight without USB open.
- [ModernStartSessionUiTest](../common/src/test/java/com/shilapi/xcertplay/ModernStartSessionUiTest.kt):
  2 tests proving manual confirmation/cancel boundary, no automatic USB,
  and missing-record STOP report without USB.
- Existing UI/export, Android controlled Pair/discovery, shared controlled
  Pair and read-only query suites: 34 tests passed, preserving prior behavior.

Focused runner:

```powershell
.\gradlew.bat :common:testDebugUnitTest `
  --tests com.shilapi.xcertplay.ModernStartSessionDiagnosticTest `
  --tests com.shilapi.xcertplay.AndroidModernStartSessionAccessTest `
  --tests com.shilapi.xcertplay.ModernStartSessionUiTest `
  --tests com.shilapi.xcertplay.DiagnosticExportUiTest `
  --tests com.shilapi.xcertplay.Phase3BDeviceSettingsTest `
  --tests com.shilapi.xcertplay.AndroidControlledPairAccessTest `
  --tests com.shilapi.xcertplay.AndroidReadOnlyLockdownAccessTest `
  :shared:testDebugUnitTest `
  --tests com.shilapi.xcertplay.transport.ControlledLockdownPairingTest `
  --tests com.shilapi.xcertplay.transport.ReadOnlyLockdownQueriesTest
.\gradlew.bat :mobile:assembleDebug
```

Final targeted run succeeded; final assembleDebug succeeded in11s. APK badging
confirms the requested version/package and minSdk22. Existing debug signer
matches the prior installed-build signer; APK verifies with v1 and v2.
Existing dependency META-INF signature warnings remain; no signing error.
Editor reported no errors in the changed production boundaries.

APK: `C:\Projects\DiPlay51\mobile\build\outputs\apk\debug\mobile-debug.apk`

Size: 8,461,060 bytes.
SHA256: `515F7DB1AC4D466E70C04953BFD87FFDAC49F9074F76F7597EF5C0A849AD836C`.

## Deployment boundary

Preserve the installed application's data and KeyStore: same-signer
**in-place update only; do not uninstall, clear data or repeat Pair**.
No hardware operation was run automatically. This implementation/build
does not authorize later TLS, StartService or CarPlay stages. STOP.
