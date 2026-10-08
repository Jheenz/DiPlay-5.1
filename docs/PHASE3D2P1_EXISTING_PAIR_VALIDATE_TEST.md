# Phase 3D.2P.1 - Existing-record ValidatePair, no Pair

Current investigation: the latest combined Pair test saw a peer TCP reset
after validation write. Do not run another phone test while its cause is
unresolved. Use only the separate local metadata inspection described in
[the reset/persistence audit](PHASE3D2P2_PAIR_RESET_PERSISTENCE_AUDIT.md).
The corrected procedure below records earlier authorization, not a new
authorization to Pair or ValidatePair during the current audit.

This implementation follows the [P audit](PHASE3D2P_VALIDATEPAIR_REUSE_AUDIT.md).
It improves provenance for the unresolved E01 failure; it does not claim that
the old DEVICE_UNAVAILABLE event has now been explained.

## Separate manual action

Settings/Diagnostics: **Phase 3D.2P.1 - Existing-record ValidatePair (NO PAIR)**.
Button: **Validate existing DiPlay pair (NO PAIR)**. Nothing runs on startup.
It shares the existing USB diagnostic exclusion/lifecycle/detach guards but
has its own display and saved-report section. Both report export paths include it.

1. Before USB open, load the protected persisted-record inventory. Require at
   least one PAIRED/VALIDATED candidate with valid associated material.
   Empty inventory, only PREPARED, corruption, missing storage key or failed
   material checks STOP. No identity/key generation or Pair.
2. Reuse proven exact-device/permission/five-config/Valeria/NCM/config5-scoped
   USBMUX descriptor guards. One open, same-handle GET_CONFIGURATION5,
   claim(false), version and setup07, one TCP62078 connection.
3. QueryType and GetValue(UniqueDeviceID) establish service/device association.
   The device identifier is never reported. Reload the associated record and
   verify material and identity. Missing/PREPARED/mismatched state STOP, even
   if another device has a valid stored candidate.
4. Exactly one ValidatePair. The same stored five-field PairRecord and
   ProtocolVersion text2 are used; no PairingOptions. No SetValue or Pair.
   A request allowlist also blocks Pair/SetValue in existing-only mode.
5. Correlated response without Error and optional Result=Success required.
   Successful PAIRED validation saves VALIDATED with unchanged material;
   unchanged VALIDATED save is skipped. Failure never deletes/replaces records.
6. Release/close and STOP. PASS is withheld on failure, detach, cancellation
   or cleanup failure. No retry, re-open, automatic recovery or re-pair.

USB descriptors do not expose the UDID. Thus pre-open inventory establishes
that accepted records exist; exact device association still requires the
bounded discovery reads after opening. It never grants permission to Pair.

## Original failure and classification

ControlledPairFailure retains the original Exception as its cause instead
of replacing DeviceUnavailable with an originless code. Reports include
original exception class, allowlisted safe message, original application
frames and bounded cause-chain provenance. Unknown provider/plist/storage
messages remain redacted; cause preservation in memory is not raw export.

Known classifications:

- USBMUX_TCP_RESET: actual peer RST.
- USBMUX_TCP_CLOSED: send on non-open TCP; FIN flag separately indicates
  whether a peer FIN was observed. Do not equate every closure with FIN.
- LOCKDOWN_EOF: stream ended while a framed plist was needed.
- LOCKDOWN_TIMEOUT: bounded receive deadline, not every negative USB poll.
- USB_SHORT_WRITE / USB_WRITE_FAILURE: short nonnegative / negative OUT
  completion; safe original counts retained. Neither proves physical detach.
- USBMUX_READER_FAILED: preserves original reader exception and nested cause.
- USBMUX_HOST_CLOSED / LOCKDOWN_CHANNEL_CLOSED / TCP_WAIT_INTERRUPTED.
- TRANSPORT_UNAVAILABLE_UNCLASSIFIED: explicit unknown, retaining provenance.
- Pair-record missing, PREPARED or invalid are separate STOP outcomes.
- Correlated remote rejection logs LOCKDOWN_ERROR_RESPONSE and
  VALIDATEPAIR_REJECTED with an allowlisted code; arbitrary remote text redacted.

Snapshots taken at failure, success and after cleanup report:

- DEVICE_PRESENT and DEVICE_STATE_UNCHANGED from passive inventory.
- USB_DETACHED, USB_REENUMERATED (observed attach after detach; latch still
  stops the attempt rather than recovering).
- USB_HANDLE_OPEN: **local ownership flag**, not a guarantee Android's
  underlying native USB handle remains electrically usable. A physical
  liveness check would require additional traffic and is deliberately absent.
- USBMUX_HOST_ACTIVE and TCP_OPEN; actual peer FIN/RST, read EOF, TCP receive
  timeout, short/failed OUT and pipe-open flags.

Snapshots are observations at reporting time, not an atomic historical proof.
No false physical-disconnect conclusions are drawn from a generic Android
bulk failure. Original inventory/cancellation reason is retained when nested
inside a reader failure. No HostID/SystemBUID values, device secrets, keys,
certificates, records or EscrowBag are exported.

## Safety and storage

No Pair, StartSession, TLS, StartService, CarKit, iAP2, MFi, NCM networking,
AirPlay or CarPlay. Existing-only reports explicitly state Pair count0.
Normal CONNECTIONS_ENABLED remains false; minSdk remains22.
The existing combined Pair action remains separate and is not invoked by
this action. No legacy record import or security downgrade.

Storage additions are read-only inventory/verification of the same encrypted
per-device format and key alias. Existing save/commit/readback/replacement/
downgrade protections remain unchanged. Do not clear app data, uninstall or
switch package identity to work around a record failure.

## Corrected procedure after confirmed uninstall

The user confirmed uninstalling DiPlay between O.1 and P.1. Uninstall can
remove protected local state; that NOT_FOUND run is not evidence of a
persistence bug. No local metadata diagnostic is needed on this evidence.

1. Keep the current DiPlay installation: do not uninstall, clear app data or
   clear KeyStore. Do not reset iPhone Trust.
2. With the direct iPhone connection prepared using the existing manual
   configuration diagnostics, open the existing **Pair / validate DiPlay
   host** action and review/confirm its mutation warning. This is separate
   from the NO PAIR action.
3. Run that controlled action once. Any required Trust approval is manual on
   the iPhone; never bypass it. Confirm the report contains
   `Pair response=SUCCESS` and `Pair success record stored=verified`.
   Pending, denial or storage failure means STOP; do not blindly repeat Pair.
4. Let the existing action complete cleanup and STOP. It already attempts
   one ValidatePair after successful Pair persistence. It does not stop
   between the save and validation, and no new Pair-only behavior is added.
   Failed validation does not authorize re-pair and does not delete PAIRED
   state. Save the report even if validation fails.
5. Future APK installations must be same-package/same-signer **in-place
   updates** over this app. If using adb, `adb install -r <apk>` requests an
   update preserving data. If installation fails, STOP; do not uninstall as
   a workaround. Keep the debug package/variant and signing identity.
6. Separately confirm **Validate existing DiPlay pair (NO PAIR)**. This loads
   the existing accepted identity, checks phone association, sends at most
   one ValidatePair, then cleans up and STOPs. There is no Pair fallback.
7. Save the report and stop before any session, TLS, service or projection.
   If an accepted record disappears after a confirmed same-package/same-signer
   in-place update, stop and reconsider the local metadata inspection design.

minSdk22 and CONNECTIONS_ENABLED=false remain unchanged. This procedure
does not add retries, StartSession, TLS, StartService or CarPlay behavior.
No hardware operation was performed while documenting this clarification.

## Development validation

Focused tests cover missing/PREPARED pre-open guards, PAIRED/VALIDATED modes,
wrong-device association, one ValidatePair maximum, no Pair/SetValue/session/
services, cause identity retention and secret-safe formatting. Mocked actual
USBMUX/plist framing exercises peer RST, FIN/EOF, timeout and short write with
resource cleanup. UI tests cover separate confirmation and no USB open when
records are absent; exports retain the separate report.

The SDK28 Robolectric/software-key tests and compiled API22-compatible
surface checks are PC validation, not E01 hardware execution or restart proof.
No real USB/device operations are performed during development.

Version: `0.2.12-api22-phase3d2p1-existing-pair`.
Do not run the hardware test as part of development; stop after tests/build.

Final PC results: **201 tests passed**, shared58/common143, zero failed/skipped.
Shared selectors: ControlledLockdownPairingTest, PairDiagnosticFailureDetailsTest,
DiagnosticPairMaterialTest, all `*UsbMux*`, ReadOnlyLockdownQueriesTest,
Base64CompatTest. Common selectors: ControlledPairDiagnosticTest,
AndroidDiagnosticPairStoreTest, AndroidControlledPairAccessTest,
AndroidReadOnlyLockdownAccessTest, ReadOnlyLockdownDiagnosticTest,
ReadOnlyLockdownUiTest, DiagnosticExportUiTest, `*Lazy*`, `*UsbMuxVersion*`,
`*ActiveConfig5*`, Phase3BDeviceSettingsTest.

`.\gradlew.bat :mobile:assembleDebug`: **BUILD SUCCESSFUL** (10 seconds).
APK: `mobile\build\outputs\apk\debug\mobile-debug.apk`, version code31,
minSdk22, v1/v2 signatures verified, size8,422,783 bytes.
SHA256: `FCFC28138AE7A653940D9A0DF990901EE58FA4125A11F5CFB2992C8F8ACB3288`.
No device/USB/hardware test was executed.
