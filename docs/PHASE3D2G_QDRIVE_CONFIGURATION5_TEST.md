# Phase 3D.2G - Controlled post-Valeria configuration 5 selection

## Scope

Implements only the separately confirmed manual **Select post-Valeria
configuration** diagnostic in Settings/Diagnostics. No automatic run,
Phase3D.2E invocation, vendor52 request, driver detach, claim, alternate change,
bulk/interrupt traffic, USBMUX/usbmuxd, Lockdown, Pair/Trust, iAP2, MFi,
NCM networking, AirPlay, CarPlay or QDrive JNI execution is reachable through
the new connection boundary. Normal connections remain disabled:
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false`; minSdk22.

The [Phase3D.2F audit](PHASE3D2F_QDRIVE_POST_VALERIA_CONFIGURATION_AUDIT.md)
proves QDrive targets value5 for this five-configuration device. Config5 is
advertised after the separately run [Phase3D.2E vendor test](PHASE3D2E_QDRIVE_VENDOR_TRANSITION_TEST.md);
it is not activated automatically by the new diagnostic or by the string scan.

**No real-E01 configuration setter was executed during development.**

## Implementation and passive-safety boundaries

- [QDriveConfigurationDiagnostic](../common/src/main/java/com/shilapi/xcertplay/QDriveConfigurationDiagnostic.kt):
  injectable inventory/connection/events/clock, preflight, one selection,
  readback, 10-second observation, final read-only correlation, classifications.
- [AndroidQDriveConfigurationAccess](../common/src/main/java/com/shilapi/xcertplay/AndroidQDriveConfigurationAccess.kt):
  exposes only configuration read, configuration5 string inspection, parameterless
  one-shot `select5`, and close. Its read-only post-inspection session refuses
  selection. The setter is exactly public `UsbDeviceConnection.setConfiguration`
  with the actual unique configuration object's **ID5**, never array index5.
- [AndroidQDriveDescriptorAccess](../common/src/main/java/com/shilapi/xcertplay/AndroidQDriveDescriptorAccess.kt):
  existing standard GET_DESCRIPTOR implementation reused with optional config5
  scope and raw device-count verification. Existing C2/E defaults unchanged.
  Cached names do not authorize selection. Valeria in config1/3/4 cannot
  substitute for an exact successfully retrieved config5 string.
- [AndroidQDriveTransitionAccess](../common/src/main/java/com/shilapi/xcertplay/AndroidQDriveTransitionAccess.kt):
  unchanged E behavior; passive attach/detach receiver extracted for reuse.
  G does not instantiate E's adapter, engine or vendor-capable session.
- [DiPlayActivity](../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt):
  warning-colored separate action and confirmation, off-UI-thread work,
  mutual exclusion with the existing active USB diagnostics, pause/destroy
  cancellation, selectable output and both saved-report paths.

## Mandatory preflight

Before the only setter:

1. Exactly one Apple VID05AC device, PID12A8, existing permission.
2. Exactly five cached configurations; unique ID/value5.
3. Within config5, exact triples USBMUX `255/254/2`, CDC-NCM control `2/13/0`,
   CDC data `10/0/1`.
4. Open without claims; exact standard GET_CONFIGURATION must return1.
5. Parse the complete raw configuration layout, require raw count5, and retrieve
   checked first-alternate strings **within configuration5** using existing
   standard read-only descriptor operations. A valid case-sensitive substring
   `"Valeria"` is required. Missing, false, malformed or unavailable result
   does not authorize selection.
6. Recheck fresh inventory equality and GET_CONFIGURATION1 after string reads.
   Cancellation/deadline failure prevents the setter.

Already-active5 also stops before the setter. No permission dialog, driver
detach, hidden API, root/ioctl/JNI fallback or automatic vendor transition.

## Single selection and verification

Register the passive USB observer before the operation. Consume the one-shot
guard **before** invoking the setter, even if it throws or returnsfalse.
Record timestamp, actual target ID5, Android boolean result/exception and
elapsed milliseconds. No raw SET_CONFIGURATION is additionally constructed.

Immediately attempt:

```text
controlTransfer(0x80, 0x08, 0, 0, oneByteBuffer, 1, 1000)
```

Then close the original connection **in finally**. Observe all USB devices
for10 seconds, polling250ms, and report attach/detach/disappearance and descriptor
inventory. No repeat setter or restore to1. False/exception is an explicit
STOP outcome; any subsequent activity is read-only observation/readback,
not a workaround or transport continuation.

At the observation endpoint require one appropriate existing-permitted Apple
device, five-configuration descriptor state unchanged from preflight. Fresh
path changes are allowed. Changed PID/layout, missing permission, absent or
ambiguous device cannot produce PASS. Open a **read-only** session, perform
GET_CONFIGURATION and config5 exact string readback, close in finally, and
recheck inventory stability. Failure to close the original prevents this
new open. Never request permission after re-enumeration.

The public setter has **no timeout parameter**. It runs off the UI thread;
the report measures elapsed time but does not claim a forced deadline on an
in-flight setter. Pause/destroy cancels subsequent work, not a setter already
inside Android. Do not race a close/reopen/retry against it. String inspection
has a15-second budget with individual reads bounded1000ms; a current read may
finish after the overall budget, but then fails the next check.

## Classification

- **PRECONDITION FAILED - REQUEST NOT SENT:** identity, permission, layout,
  active1, exact string, open/read, cancellation or stability requirement failed.
- **ANDROID setConfiguration RETURNED FALSE - STOP:** false setter, irrespective
  of readback. Record readback separately; never retry.
- **CONFIGURATION SELECTION EXCEPTION - STOP:** setter threw; never retry.
- **QDRIVE CONFIGURATION 5 SELECTION INCONCLUSIVE - STOP:** reliable final
  post-state unavailable due to disappearance, permission loss, failed reads,
  changed descriptors, cancellation, observer error or cleanup failure.
- **GET_CONFIGURATION=<actual> (expected5) - SELECTION NOT CONFIRMED:** final
  permitted/stable read establishes another active value.
- **QDRIVE CONFIGURATION 5 SELECTION CONFIRMED:** setter true, reliable final
  hardware GET_CONFIGURATION5, correlated unchanged post-Valeria descriptors
  and exact config5 string, stable identity/inventory and successful cleanup.
  A true return alone, an immediate value5 followed by final failure, advertised
  config5, or an attach/detach event is not PASS.

All outcomes include explicit no-claim/no-transport statements and STOP.
The test does not establish endpoint usability, NCM networking, authentication
or projection readiness.

## Tests and build

Focused new tests:

- [QDriveConfigurationDiagnosticTest](../common/src/test/java/com/shilapi/xcertplay/QDriveConfigurationDiagnosticTest.kt):
  identity/count/value/interface/permission/active/string preconditions,
  one-shot/no-retry, false/exception/non5/read failure, changed path,
  disappearance, permission/layout/string loss, cancellation and cleanup.
- [AndroidQDriveConfigurationAccessTest](../common/src/test/java/com/shilapi/xcertplay/AndroidQDriveConfigurationAccessTest.kt):
  actual ID5 object at a non-assumed index, exact GET_CONFIGURATION, one-shot
  exception consumption, read-only session, exact config5-only case-sensitive
  strings/raw-count validation, forbidden bytecode references, Mockito
  no-extra-connection-interaction/no-permission-request checks.
- [QDriveConfigurationUiTest](../common/src/test/java/com/shilapi/xcertplay/QDriveConfigurationUiTest.kt):
  no startup/open-dialog/cancel activity; only confirmed action runs preflight.
- Settings/manual-state and saved-report tests updated. Existing C2/E and active
  configuration diagnostics included in the regression run.

The focused command uses `:common:testDebugUnitTest` with those three new
classes plus QDriveBranchDiagnosticTest, AndroidQDriveTransitionAccessTest,
QDriveVendorTransitionDiagnosticTest, QDriveTransitionUiTest,
Phase3BDeviceSettingsTest, DiagnosticExportUiTest and
ActiveUsbConfigurationDiagnosticTest, then `:mobile:assembleDebug`.
Robolectric adapter/UI tests run API28; this is **not API22 hardware certification**.
The built APK manifest, not a mock, verifies minSdk22.

Final validation: **78 focused tests passed**, zero failures/errors.
`:mobile:assembleDebug` succeeded; editor diagnostics found no errors in the
new engine/adapter/tests or changed runtime files. Documentation links and
`git diff --check` passed.

- Package: `com.shihab.diplay.legacytest`; versionCode31.
- Version: `0.2.12-api22-phase3d2g-config5-selection`.
- Built APK minSdk **22**; API22-compatible v1 signing verification passed.
- `LegacyLaunchBuild.CONNECTIONS_ENABLED=false` reverified from source.
- APK: `C:\Projects\DiPlay51\mobile\build\outputs\apk\debug\mobile-debug.apk`.
- Size: **8,330,900 bytes**.
- SHA256: `CD23B27D30B9DB8299D2ED31B5277266C022211C92151F360911F387802FDA73`.

No new dependencies were installed. No actual USB operation or QDrive
JNI/native code was executed during PC validation. The real-E01 result was
pending at build time; the subsequent user-reported PASS is recorded below.

## Controlled real-E01 procedure

### Subsequent real-E01 result (user reported)

The user reports **QDRIVE CONFIGURATION 5 SELECTION CONFIRMED**:
one setter returnedtrue, immediate and final GET_CONFIGURATION5, same
post-transition enumeration remained present, and no interface claim/bulk/
USBMUX/Lockdown/pairing/authentication/NCM/projection followed.
The [Phase3D.2H static integration audit](PHASE3D2H_ACTIVE_CONFIG5_USBMUX_AUDIT.md)
documents configuration-scoped mapping and a proposed claim-only experiment.
It performs no additional hardware operation.

1. Remove Carlinkit completely; start Geely normally.
2. Unlock iPhone and connect directly to USB port1 with a known-good data cable.
3. Open DiPlay Settings/Diagnostics. If not already in the verified transitioned
   five-configuration/Valeria state, run the separately confirmed Phase3D.2E
   procedure first and save that report. G will never do this automatically.
4. Tap only **Select post-Valeria configuration** and read the confirmation.
5. Confirm **Select 5 ONCE**. Preflight failures send no setter; save and STOP.
6. Remain in DiPlay through readback/observation. Do not approve Trust, run
   another USB test, or assume the configuration changed from the button result.
7. **Save diagnostic report**. Expected success requires the exact confirmed
   classification backed by GET_CONFIGURATION5.
8. STOP. No USBMUX, claims, NCM, pairing/authentication or projection phase.
   Do not rerun the setter or automatically restore1 after failure.

PHASE 3D.2G READY FOR CONTROLLED REAL-E01 TEST
