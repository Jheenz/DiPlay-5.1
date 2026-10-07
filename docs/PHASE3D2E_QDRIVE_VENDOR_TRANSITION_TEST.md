# Phase 3D.2E - Controlled QDrive USB vendor transition test

## Status and scope

Manual, confirmed diagnostic implementation only. **No request has been sent
to the real E01/iPhone during development.** No USBMUX or Phase 3D.2F is
implemented. Normal startup remains gated by
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false`.

The user supplied real-E01 baseline is `05AC:12A8`, existing Android USB
permission, active configuration **1 / PTP**, and a complete **FALSE** Valeria
predicate. The [Phase 3D.2D static audit](PHASE3D2D_QDRIVE_VENDOR_REQUEST_AUDIT.md)
establishes the allowed single QDrive FALSE-branch request. It does not prove
which PID, descriptor set or active configuration results on this iPhone.

## Subsequent real-E01 result (user reported)

The controlled test returned0, observed the original device detach and Apple
reattach approximately0.9 seconds later with the same `05AC:12A8`, and found
a descriptor change from four configurations/12 flattened interfaces to five
configurations/18 flattened interfaces. Config5 advertises PTP + Apple Mobile
Device + Valeria and CDC-NCM. The exact checked string `"Valeria"` was
retrieved; predicate TRUE. **GET_CONFIGURATION remained1/PTP**.

This establishes the vendor transition, not activation of config5 or any
transport. The [Phase3D.2F static audit](PHASE3D2F_QDRIVE_POST_VALERIA_CONFIGURATION_AUDIT.md)
rechecks QDrive's resulting TRUE branch. No additional hardware operation is
authorized or performed by that audit.

## Implementation

- [QDriveVendorTransitionDiagnostic](../common/src/main/java/com/shilapi/xcertplay/QDriveVendorTransitionDiagnostic.kt):
  injectable inventory/open/event/clock boundaries; preflight, single attempt,
  cleanup, 10-second observation and permission-gated post-readback.
- [AndroidQDriveTransitionAccess](../common/src/main/java/com/shilapi/xcertplay/AndroidQDriveTransitionAccess.kt):
  Android API22-compatible adapter and connection wrapper exposing only
  standard configuration read, the verified read-only discriminator, a
  **parameterless one-shot audited request**, and close.
- [AndroidQDriveDescriptorAccess](../common/src/main/java/com/shilapi/xcertplay/AndroidQDriveDescriptorAccess.kt):
  reused C2 first-alternate/string semantics on an already opened connection;
  optional cancellation/deadline checks; raw descriptor SHA256 for comparison.
  Existing standalone read-only diagnostic behavior is preserved.
- [AndroidPassiveUsbInventory](../common/src/main/java/com/shilapi/xcertplay/AndroidPassiveUsbInventory.kt):
  opt-in configuration metadata for all observed devices; prior diagnostics'
  Apple-only configuration mapping default is unchanged.
- [DiPlayActivity](../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt) and
  [strings_legacy.xml](../common/src/main/res/values/strings_legacy.xml):
  separate warning-colored diagnostics section, read-only preflight button,
  disabled-until-ready test button, confirmation dialog, background execution,
  USB-diagnostic mutual exclusion, pause/destroy cancellation, UI/report export.
- [mobile build configuration](../mobile/build.gradle.kts):
  debug version suffix `-api22-phase3d2e-qdrive-vendor-transition`.

Nothing runs at startup, during Settings rendering, or merely on opening
the confirmation dialog. Cancelling the dialog sends nothing. A separate
**Check QDrive transition preflight (read-only)** action is necessary to
enable **Test QDrive USB mode transition**.

## Preflight and revalidation

Require exactly one Apple VID `05AC` device, specifically initial PID `12A8`,
and permission already granted. Open without claims, perform standard
GET_CONFIGURATION (`80/08/0/0`, length 1, 1000ms), require value 1, run the
verified read-only first-alternate string scan, and require its complete
result FALSE. Missing/uncertain predicate, TRUE, open/read failure,
configuration mismatch, identity change or cleanup failure prevents readiness.
GET_CONFIGURATION is rechecked after the string reads.

The preparation connection closes in finally. Fresh device metadata is checked
again before enabling the action. After confirmation, a **new** connection
repeats these requirements and revalidates the prepared inventory before the
state-changing request. No permission request or stale prepared-state shortcut.

Failed preflight reports:

`PREFLIGHT FAILED — REQUEST NOT SENT`

The complete C2 native-equivalent string behavior is retained: individual
missing index/native-negative string reads can yield known empty/no-match;
unresolved malformed/Android outcomes cannot prove FALSE. Cached Android names
are supporting evidence, not substitutes for the predicate's requested strings.

## Single allowed mutation and safety boundary

Exactly:

```text
controlTransfer(0x40, 0x52, 0, 2, null, 0, 1000)
```

OUT / vendor / device; no payload. **1000ms is the one intentional request
parameter difference** from native QDrive timeout0 (no deadline). This avoids
an indefinitely blocked Android diagnostic. QDrive's ignored return/-1
device-add exit is not copied as a diagnostic success or failure criterion.

The report records wall-clock timestamps (timezone/milliseconds), exact
parameters, return/exception, monotonic elapsed milliseconds and cleanup.
Negative return or exception still leads to observation: disconnect can
invalidate the original connection. There is **no retry**.

The wrapper consumes its attempt before entering controlTransfer. It forbids
another vendor attempt or any further read on that old connection. It closes
in finally immediately after the attempt. No request is sent on the old
connection afterward. If close fails, passive observation continues, but
post-transition opens are skipped and the result is inconclusive.

The interfaces do not expose requestPermission, configuration setters,
interface claims, alternate setters, bulk endpoints, or transport startup.
No other vendor request, USB reset, USB mode/role manipulation, broadcast
mutation, QDrive JNI, Apple vendor integration, USBMUX, Lockdown, Pair/Trust,
StartSession, CarKit/iAP2, MFi, Apple Ethernet/NCM, AirPlay or CarPlay is used.

## Observation and post-readback

- Attach/detach receiver is registered **before** the request; inventory
  polling complements broadcasts that might not be observable on E01.
- Observe for **10,000ms after original cleanup**, sampling every **250ms**.
  Inventory reports retain every changed sample and final sample.
- Record original disappearance, event timestamps and attach/detach identity.
  Events during preflight are reported but do not establish post-request change.
- Observe all attached USB devices; classify Apple primarily by VID `05AC`,
  **not old PID**. Report actual PID/path, configuration/interface counts and
  cached configuration/interface names, IDs, alternates, class/subclass/protocol,
  endpoint types/directions/address/size/interval, and permission status.
- Permission-only changes are not claimed as a USB mode transition.
- At the end require one unambiguous Apple device for active inspection.
  No assumption that PID remains `12A8` or that config3/4 becomes active.
- If permission is absent:
  `POST-TRANSITION DEVICE DETECTED — PERMISSION NOT GRANTED`.
  Retain passive metadata; **never request permission**.
- If permission already exists and original cleanup succeeded, reopen using
  freshly enumerated identity and a new connection. Use only standard
  GET_CONFIGURATION/raw descriptors/necessary STRING GET_DESCRIPTOR reads,
  then finally-close. Correlate actual active value against fresh configuration
  metadata; report scoped USBMUX/Apple Ethernet descriptor counts.
- Reuse the C2 discriminator to report post-transition TRUE/FALSE/UNAVAILABLE.
  Neither branch is executed, even on TRUE.
- Compare raw-descriptor fingerprint and checked interface-string evidence
  as well as inventory, active value and predicate; a same-path/same-PID
  descriptor change can be observed without assuming detach is mandatory.
  Only strings successfully retrieved for the same checked descriptor entry
  in both scans are compared; a missing/failed read is not evidence of a change.
- Recheck fresh Apple inventory after readback; changes during inspection
  invalidate an apparently complete result.

Each string transfer is bounded at 1000ms. The discriminator has a 15,000ms
budget checked before reads and after completion; an in-flight transfer may
take up to its own 1000ms beyond that budget before cleanup. The 10-second
observation window is separate from preflight and post-inspection duration.
All work is off the UI thread. Leaving the Activity cancels before the next
operation; an already issued bounded request is not retried or reversed.

## Classification

| Final output | Meaning |
|---|---|
| `PREFLIGHT FAILED — REQUEST NOT SENT` | No vendor attempt; a prerequisite/observer setup failed or confirmation state was stale. |
| `QDRIVE VENDOR TRANSITION OBSERVED` | Post-request Apple attach/detach/disappearance, identity/descriptor change, changed active value or newly TRUE predicate, with adequate final permitted inspection and cleanup. Not proof of USBMUX interoperability or CarPlay. |
| `QDRIVE VENDOR REQUEST SENT — NO TRANSITION OBSERVED` | One attempt occurred; the complete bounded observation/readback establishes no USB state change. Return0 alone is never PASS. |
| `QDRIVE VENDOR TRANSITION INCONCLUSIVE` | Observation/readback/cleanup/cancellation failed, ambiguity/disappearance persists, or a changed device cannot be adequately inspected (including permission loss). |

Return0 confirms only zero-length request completion; negative/error does not
prove no transition. All final outputs include actual evidence and STOP.
The state-changing button disables after each attempt. Another attempt
requires a new manual preflight and new confirmation; none is scheduled.

## Tests and build

Focused test files:

- [QDriveVendorTransitionDiagnosticTest](../common/src/test/java/com/shilapi/xcertplay/QDriveVendorTransitionDiagnosticTest.kt):
  wrong VID/PID, missing permission, ambiguous device, wrong active config,
  TRUE/unknown predicate, open/read/observer failure, read-only preflight,
  single attempt/no retry, finally-close ordering, negative/exception still
  observes, changed PID, lost permission/no post open, transient events,
  in-place config/string change, incomplete post-state, cleanup failures,
  cancellation and discriminator budget.
- [AndroidQDriveTransitionAccessTest](../common/src/test/java/com/shilapi/xcertplay/AndroidQDriveTransitionAccessTest.kt):
  exact `40/52/0/2/null/0/1000` arguments; consumed attempt on failure; old
  connection reads/retries prohibited; permission/identity/open guards;
  standard readback; mock no-more-interactions and compiled forbidden-operation
  boundary checks.
- [QDriveTransitionUiTest](../common/src/test/java/com/shilapi/xcertplay/QDriveTransitionUiTest.kt):
  no startup/dialog-opening request, cancelled confirmation sends nothing,
  positive action starts one attempt, consumes readiness, preserves disabled gate.
- Existing C2, active configuration, Settings and saved-export regressions.

Host Android mock/UI tests use Robolectric API28: this Robolectric version
does not provide API22. This is **not** a claim of real-E01 runtime validation.
API22 compatibility is checked by API usage/minSdk and the built APK manifest;
the controlled real-E01 test remains pending.

```powershell
.\gradlew.bat :common:testDebugUnitTest `
  --tests 'com.shilapi.xcertplay.QDriveVendorTransitionDiagnosticTest' `
  --tests 'com.shilapi.xcertplay.AndroidQDriveTransitionAccessTest' `
  --tests 'com.shilapi.xcertplay.QDriveTransitionUiTest' `
  --tests 'com.shilapi.xcertplay.QDriveBranchDiagnosticTest' `
  --tests 'com.shilapi.xcertplay.ActiveUsbConfigurationDiagnosticTest' `
  --tests 'com.shilapi.xcertplay.Phase3BDeviceSettingsTest' `
  --tests 'com.shilapi.xcertplay.DiagnosticExportUiTest' `
  --tests 'com.shilapi.xcertplay.PassiveUsbInventoryTest' `
  --tests 'com.shilapi.xcertplay.PassiveUsbConfigurationMappingTest' `
  :mobile:assembleDebug
```

Final PC validation: **66 focused tests passed**, zero failures/errors.
`:mobile:assembleDebug` succeeded. Editor diagnostics reported no errors in
the changed runtime files; `git diff --check` and documentation links passed.
No QDrive JNI/native code or real USB operation was executed during validation.

APK manifest verification:

- Package: `com.shihab.diplay.legacytest`; versionCode31.
- Version: `0.2.12-api22-phase3d2e-qdrive-vendor-transition`.
- minSdk **22**, targetSdk37.
- APK signature verification passed for minSdk22, including v1/JAR signing.
- `LegacyLaunchBuild.CONNECTIONS_ENABLED=false` reverified from source and UI test.
- Exact APK: `C:\Projects\DiPlay51\mobile\build\outputs\apk\debug\mobile-debug.apk`.
- Size: **9,767,020 bytes**.
- SHA256: `D3649E276875225E87E245DE1765745E787FDBC1F832D2D849FF96E0E5BDA206`.

This is the ordinary source-only diagnostic debug build, not an authenticated
CarPlay/standalone projection build. No authentication provisioning is needed
or used for this controlled diagnostic.

## Exact controlled real-car procedure

1. Remove the Carlinkit dongle completely.
2. Start Geely normally.
3. Unlock the iPhone.
4. Connect it directly to USB port1 with a known-good data cable.
5. Open DiPlay, then Settings/Diagnostics.
6. In Phase3D.2E run **Check QDrive transition preflight (read-only)**.
   If it fails, save the report and STOP; do not try a different USB mutation.
7. Only after PREFLIGHT PASS, tap **Test QDrive USB mode transition**.
   Read the warning and confirm **Send ONE request**.
8. Stay in DiPlay while the request, bounded observation and readback finish.
   Do not approve Trust or other iPhone prompts; do not run another USB test.
9. **Save diagnostic report**, preserving baseline, request result, events,
   actual resulting PID/configuration/descriptors/Valeria and cleanup.
10. **STOP.** No TRUE branch, configuration selection, USBMUX, Lockdown, iAP2,
    MFi, Ethernet/NCM, AirPlay or CarPlay. No Phase3D.2F.

PHASE 3D.2E READY FOR CONTROLLED REAL-E01 TEST
