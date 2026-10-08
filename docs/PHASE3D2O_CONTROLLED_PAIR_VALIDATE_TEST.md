# Phase 3D.2O — Controlled Lockdown Pair + ValidatePair

This is a separately confirmed, state-changing diagnostic. It is not part of
startup or normal connection. It stops after Pair/ValidatePair and cleanup;
StartSession, TLS, StartService, CarKit, iAP2, MFi and projection are not
started.

## Persistent effects and confirmation

### Phase 3D.2O.1 API22 linkage fix

The E01 reported `UI_DIAGNOSTIC / NoSuchMethodError` and STOP. No missing
method message or stack trace was retained. The user reports that Pair and
ValidatePair were not reached. The old outer catch discarded the in-progress
engine report; its `UI_DIAGNOSTIC` label was not the actual failing stage.

Static inspection of the source and compiled JVM calls identified an
unavailable method in `DiagnosticPairMaterial.verify`:
`java.security.cert.X509Certificate.verify(java.security.PublicKey, java.security.Provider)`.
Android SDK API metadata marks this X509Certificate overload as **API 24**.
The inherited Certificate provider overload is API 26; neither is available
on API 22. All three certificate-chain checks now use the **API 1**
`verify(java.security.PublicKey)` overload. Parsing still uses the explicitly
bundled provider; no provider is registered globally, no certificate profile
changes, and no validation is skipped.

This is a definite static API22 incompatibility, **not a proven attribution
of the observed E01 exception**. The verifier runs after QueryType, during
pairing preparation. No unavailable platform method was identified in the
pre-USB path: Settings card/label/button helpers and confirmation, lazy
store construction, USB event observer, diagnostic construction,
AtomicBoolean/StringBuilder preflight, passive device/configuration/interface/
endpoint metadata enumeration, config5 selection, and scoped open checks.
These use platform methods available by API 22 and bundled Kotlin/AndroidX
helpers. USB configuration/interface descriptor APIs are API 21; the
existing RippleDrawable/state-list animator UI APIs are also API 21.
No new Files/Path, Java collection factories, newer Objects/String/date-time
APIs, java.util.Base64 or certificate calls run before USB open.

Linkage errors now retain the precise engine/preparation stage, accumulated
report and cleanup handling. Initialization and outer UI failures use the
same safe reporter. Reports include the exception class, a normalized
missing-method message/signature when recognized, and up to eight application
stack frames. Arbitrary exception text and ART dex-file suffixes are redacted;
unrecognized messages are explicitly marked unavailable/redacted, never
exported as raw text. No credentials or pairing material are logged.

Pair/ValidatePair request order, timeouts, retry policy, Trust handling,
certificate generation, persistence, and all disabled runtime gates remain
unchanged. This patch does not implement StartSession, TLS or StartService.
Version: `0.2.12-api22-phase3d2o1-pair-api22-fix`.

The bundled Robolectric 4.17 runner does not support API 22 (a direct SDK22
test attempt returned `API level 22 is not available`). Tests therefore use
the existing SDK28 sandbox, emitted-bytecode checks for absence of the API24
overload, SDK API metadata, and injected linkage failures. This is not a
claim of a successful E01 rerun.

PC validation for 3D.2O.1: **159 tests passed** (shared 22, common 137),
zero failed/skipped. Coverage includes the exact compiled certificate-overload
descriptor, generated certificate/chain/key verification, ART/JVM message
sanitization, preflight UI linkage failure with no USB open, linkage-stage
retention and release/close, plus the existing USBMUX, Lockdown, Pair,
persistence, Base64, UI/export, config5 and disabled-startup regressions.
The successful shared selectors add `*PairDiagnosticFailureDetailsTest*`
and `*Base64CompatTest*` to the earlier shared command; common selectors
are unchanged.

`.\gradlew.bat :mobile:assembleDebug`: **BUILD SUCCESSFUL** (15 seconds).
APK: `mobile\build\outputs\apk\debug\mobile-debug.apk`, version
`0.2.12-api22-phase3d2o1-pair-api22-fix`, code 31, minSdk 22, v1/v2
signatures verified, 8,411,554 bytes.
SHA-256: `8E4364920911E01CE6E9D6EA9A6E448562D05CC7D121713A31CCA0132D0E1B6C`.
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false` remains unchanged.
No real USB/device operation or pairing diagnostic was executed during this fix.

Before confirming, understand that the action:

- writes `SetValue(UntrustedHostBUID)` to the iPhone;
- prepares and locally persists protected pairing credentials;
- may persist iPhone Trust, which is not undone by STOP;
- requires any Trust approval to be performed by the user on the iPhone.

The local store encrypts the complete pairing record, including EscrowBag, with
AES-GCM and wraps a per-record random AES key with an AndroidKeyStore RSA key.
The RSA key is generated with `KeyPairGeneratorSpec` (API 18+); the global
SystemBUID is stable. The store synchronously commits and decrypt/readbacks
the record. It does not replace or downgrade records; saving an already
VALIDATED, unchanged record is skipped, so its ciphertext is not rewritten. If legacy
`lockdown_host_id` state exists, the new store refuses to proceed: it does not
import, read legacy credentials, or clear them. Corrupt records or a missing
AndroidKeyStore key are STOP conditions.

Device association uses `GetValue(UniqueDeviceID)` before looking up the
per-device record. The identifier is not logged or exported.

The audited normal generator's certificate profile has an empty issuer name
that the explicitly bundled Bouncy Castle parser rejects. With the user's
explicit authorization, the diagnostic therefore uses a separate
diagnostic-only generator with valid nonempty subject/issuer names. It
otherwise retains the normal generator's RSA-2048/SHA-256 chain and
certificate lifetime. The bundled Bouncy Castle `CertificateFactory` parses
the generated certificates and verifies the signed chain and device-key /
private-key match before Pair. The normal generator is unchanged; this
diagnostic-only deviation makes no claim about iOS acceptance. No provider is
registered globally and no TLS is performed. Credential bytes, keys, device
identifiers and raw throwable messages are not logged or included in reports.

## Run boundary

1. Confirm **Pair / validate DiPlay host** only for the already-prepared active
   configuration-5 iPhone with USB permission already granted. Earlier phase
   reports are historical; this action does not run prior phases.
2. The diagnostic verifies the Lockdown service with QueryType, reads
   `GetValue(UniqueDeviceID)` (never reported), and uses it to look up the
   per-device record. A stable global SystemBUID is used for host identity.
3. If no new-store record exists, persist and verify the identity before the
   first device mutation. Prepare with `SetValue(UntrustedHostBUID)`,
   `GetValue(DevicePublicKey)` and `GetValue(WiFiAddress)`, generate and
   validate the credential material, then persist the complete PREPARED
   candidate and verify it by decrypt/readback before Pair.
4. State controls the next request:
   - **PREPARED:** Pair once with the saved candidate. If Pair is pending,
     cleanup and STOP. After the user separately approves Trust on the iPhone,
     a new manual confirmation sends Pair once again using exactly the same
     candidate and material; it does not regenerate, overwrite, or
     automatically retry. Only successful Pair is followed by one
     ValidatePair.
   - **PAIRED:** the Pair response was accepted, but validation failed or was
     cancelled. A later manual run is validation-only: ValidatePair once,
     with no SetValue or Pair.
   - **VALIDATED:** validation-only: ValidatePair once, with no SetValue or
     Pair.
5. Release/close the transport and USB resources. Save the report and STOP.

Pairing-dialog pending is not PASS. The attempt cleans up and stops while
retaining the PREPARED candidate. After separately approving Trust on the
iPhone, a new, manually confirmed run reuses that candidate for exactly one
Pair request. PAIRED and VALIDATED records take the validation-only path,
including when a prior validation attempt failed or was cancelled. There is
no automatic reconnect/retry, regeneration, candidate overwrite, Unpair or
trust reset.
Detach, cancellation, timeout, malformed response, persistence/readback
failure, validation failure, or cleanup failure is STOP. Pair can already
have persisted Trust even if a later stage fails.

PASS requires the correct explicit mode (PREPARED Pair plus ValidatePair, or
PAIRED/VALIDATED validation-only), verified record handling, successful
ValidatePair, and successful cleanup including observer cleanup. It states
that StartSession, TLS and StartService were not started. ValidatePair is not
evidence of TLS, service, CarKit, iAP2, MFi or projection compatibility.

## Focused validation

The UI test verifies that the action is manual, shows the SetValue/Trust and
validation-only disclosures, checks busy diagnostics before opening and again
at confirmation, blocks the existing USB diagnostic entry points while active,
and retains the report through both export branches. Pair/ValidatePair engine,
record-state, cryptographic-store, certificate-validation, cleanup and
protocol-order tests must pass before any hardware attempt. Run no hardware
test as part of PC validation. Do not claim API 22 provider/device
compatibility from Robolectric or a desktop test.

Certificate tests confirm that the diagnostic material parses with both the
bundled Bouncy Castle and the standard desktop `CertificateFactory`, that the
chain, signatures, private key and device key match, that malformed
certificates and wrong keys are rejected, and that invalid material prevents
Pair. The legacy generator's empty-DN profile is unchanged. A typed response
timeout is reported as the safe stage `RESPONSE_TIMEOUT` with no retry.

## PC validation results
```powershell
.\gradlew.bat :shared:testDebugUnitTest --tests '*ControlledLockdownPairingTest*' --tests '*DiagnosticPairMaterialTest*' --tests '*ReadOnlyUsbMuxInitTest*' --tests '*ReadOnlyLockdownQueriesTest*' --tests '*UsbMuxVersionPacketTest*' :common:testDebugUnitTest --tests '*ControlledPairDiagnosticTest*' --tests '*AndroidDiagnosticPairStoreTest*' --tests '*AndroidControlledPairAccessTest*' --tests '*AndroidReadOnlyLockdownAccessTest*' --tests '*ReadOnlyLockdownDiagnosticTest*' --tests '*ReadOnlyLockdownUiTest*' --tests '*DiagnosticExportUiTest*' --tests '*Lazy*' --tests '*UsbMuxVersion*' --tests '*ActiveConfig5*' --tests '*Phase3BDeviceSettingsTest*'
.\gradlew.bat :mobile:assembleDebug
```
Final combined focused run: PASS, 151 tests (shared 16, common 135), 0 failed,
0 skipped. It covers pairing, certificate material, store, adapter,
orchestrator, UI, export, Settings and lazy-startup tests plus read-only
Lockdown, K and configuration-5 regressions. `:mobile:assembleDebug` was run
once afterwards: BUILD SUCCESSFUL (10 s).
APK: `mobile\build\outputs\apk\debug\mobile-debug.apk`, version
`0.2.12-api22-phase3d2o-pair-validate`, versionCode 31, minSdk 22, v1/v2
signatures true, 8,410,028 bytes, SHA-256
`5C16FB8C56D023D2799E25F88832F75F33C12A0F699A1B58CA53736C73EC3D0A`.
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false` was verified in source.

No hardware, USB, QDrive or native runtime was exercised. The API 22 code
surface is compatible, but Robolectric (SDK 28, software keys) does not certify
the E01 AndroidKeyStore or iOS acceptance of the diagnostic certificate profile.

CONTROLLED PAIR/VALIDATEPAIR DIAGNOSTIC READY FOR HARDWARE TEST
