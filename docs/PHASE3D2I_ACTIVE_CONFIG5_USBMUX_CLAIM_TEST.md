# Phase 3D.2I - Controlled active-configuration5 USBMUX claim/release

## Boundary

**CLAIM/RELEASE ONLY — NO USBMUX PROTOCOL TRAFFIC**

Separate, manually confirmed Settings/Diagnostics action:

- **Phase 3D.2I — Active configuration 5 USBMUX claim**
- **Claim/release configuration-5 USBMUX**
- Confirmation: **Claim/release ONCE**

Nothing runs on startup, when opening Settings or when another USB diagnostic
finishes. Phase3D.2E and G are never called by I. Normal connection gates remain
disabled (`LegacyLaunchBuild.CONNECTIONS_ENABLED=false`).

The hardware experiment has **not** been executed during development.
The prerequisite G hardware PASS is user reported; see
[H's static mapping/claim audit](PHASE3D2H_ACTIVE_CONFIG5_USBMUX_AUDIT.md).
No subsequent USBMUX protocol test is implemented.

## Implementation

- [Engine, scoped selector and injectable boundary](../common/src/main/java/com/shilapi/xcertplay/ActiveConfig5UsbMuxClaimDiagnostic.kt)
- [Android API22 adapter](../common/src/main/java/com/shilapi/xcertplay/AndroidActiveConfig5UsbMuxClaimAccess.kt)
- [Passive inventory](../common/src/main/java/com/shilapi/xcertplay/AndroidPassiveUsbInventory.kt):
  new optional omission of flattened interfaces; defaults preserve old diagnostics.
- [Settings/confirmation/cancellation/export](../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt)
- [UI text](../common/src/main/res/values/strings_legacy.xml)
- [APK version](../mobile/build.gradle.kts)

### Before open

Require exactly one Apple device (extra Apple devices cause STOP, even if
unpermitted), exact05AC:12A8, existing permission and exactly five
configurations with unique ID5.

Within **configuration5**, require:

- One USBMUX descriptor, unique interface number1, alternate0,255/254/2.
- Exactly two endpoints: bulk OUT04/number4/direction0, and bulk
  IN85/number5/direction128; positive maxPacketSize.
- Valeria descriptor255/42/255 with **case-sensitive `Valeria` in its
  `UsbInterface.getName()` inventory evidence**.
- Genuine CDC-NCM Control2/13/0 and Data10/0/1 descriptors.

Cached Android interface names are established by Android host enumeration,
not by executing native QDrive code. A null/unavailable/lowercase-only name,
or a matching configuration name alone, **fails closed before opening**.
There is no additional GET_DESCRIPTOR/string request in I. Run the earlier
read-only inspection separately if evidence is unavailable; I does not guess,
fall back or automatically initiate it.

The inventory traverses configurations only. It does not call flattened
`UsbDevice.getInterface()` or use historical index13. It retains the actual
Android interface object from the actual `UsbConfiguration` whose ID is5.
Descriptor-identical USBMUX in configurations3/4 does not create scoped
ambiguity; duplicates inside5 do.

### Single-connection experiment

1. Revalidate permission and fresh inventory/object descriptors before open.
2. Call `UsbManager.openDevice()` once; no permission request.
3. Immediately on that connection issue exactly one standard
   `GET_CONFIGURATION`: requestType80,request08,value0,index0,length1,
   timeout1000ms. Transfer result must be exactly1; value must be5.
4. Record selected config5 ID1/alt0/triple/endpoints, active readback,
   timestamp and **force=false**; check inventory stability/cancellation.
5. One `claimInterface(actualConfig5UsbMuxInterface, false)`.
   Record boolean/exception and elapsed monotonic milliseconds.
6. In finally, **only if claim returnedtrue**, one
   `releaseInterface(theSameObject)`, with boolean/exception and elapsed time.
   Cancellation or disappearance after claimtrue does not skip release.
7. In nested finally close the connection regardless of release outcome.
   No retry, reopen, configuration restore or state-changing cleanup workaround.
8. Passively check inventory stability; save report and STOP.

Adapter guards independently prohibit claim before verified active5, a second
claim, release before claimtrue, a second release/read and use after close.
A false/throwing claim consumes the attempt. A false/throwing release is not
retried. Connection close failure is explicit and suppresses PASS.

Android native claim takes only interface number, not configuration/alternate;
same-handle active5 and scoped stable inventory are essential. External
configuration changes are not atomically excluded by the public API. Do not
run other USB apps/services or concurrent DiPlay diagnostics.

The public claim/release calls have no timeout argument. Work runs off the
UI thread and measures duration; it does not pretend a watchdog can cancel
kernel ownership acquisition. Activity pause cancels subsequent work while
preserving release/close cleanup. Android's normal successful public release
may permit kernel-driver reconnection; no explicit disconnect/force-detach
operation is added.

### PASS and failures

PASS requires all of:

- Same-connection GET_CONFIGURATION5.
- Exact configuration5 scoped USBMUX selected.
- Claimtrue with forcefalse.
- Releasetrue.
- Close succeeded, no cancellation, inventory stable.

Report:

```text
ACTIVE CONFIGURATION 5 USBMUX INTERFACE CLAIM CONFIRMED
USBMUX PROTOCOL NOT TESTED — NO BULK TRAFFIC SENT
```

The extra stability/cancellation checks can make results inconclusive; claimtrue
alone is never PASS. Outcome codes separately identify precondition/permission/
open/GET_CONFIGURATION failure, active value not5, missing/ambiguous scoped
interface/endpoints, claimfalse/exception, releasefalse/exception,
device disappearance/state change, cleanup failure, cancellation and attempted
reuse of the one-shot engine. Secondary failures are retained in report lines,
even when the primary outcome is an earlier failure.

Saved report includes identity, full configuration-scoped inventory including
interface names, static preflight, read request/count/value, actual selected
identity/endpoints, forcefalse, claim/release timestamps and timings, cleanup,
final passive stability and explicit **bulk=0,interrupt=0**.
The existing Save diagnostic report action exports the I result.

## Excluded operations

No vendor52, setter/SET_CONFIGURATION, alternate changes, forcetrue, driver
detach, hidden APIs/JNI/libusb/ioctl bypass, bulk/interrupt/UsbRequest,
USBMUX open/version/setup07, usbmuxd, Lockdown/BUID/Pair/Trust, iAP2, MFi,
NCM networking, AirPlay, CarPlay or QDrive native execution.
The adapter exposes no bulk pipe/session and imports no transport package.
Trust UI is not a prerequisite, and the diagnostic cannot answer it.

Other earlier manual diagnostics remain separate; **do not press the older
Test USBMUX + Lockdown button for this phase**.

## Tests and build validation

New focused tests:

- [Engine tests](../common/src/test/java/com/shilapi/xcertplay/ActiveConfig5UsbMuxClaimDiagnosticTest.kt):
  active-not5/read failure; VID/PID/permission/device/config count/ID failures;
  missing/case-sensitive Valeria and NCM; absent/ambiguous/wrong ID/alternate
  USBMUX; wrong endpoints; reordered configurations/duplicate flattened
  candidates; claim/release false/exception; one-shot behavior; cancellation;
  disappearance before claim/after claim/during release; cleanup/open failure.
- [Android adapter tests](../common/src/test/java/com/shilapi/xcertplay/AndroidActiveConfig5UsbMuxClaimAccessTest.kt):
  actual configuration5 object rather than identical3/4 candidates; forcefalse;
  exact standard GET arguments and short/failed/malformed counts; one open;
  no flattened getter; one-shot claim/release including exceptions;
  stale inventory/permission; no extra connection interactions; compiled
  boundary excludes setter/vendor/bulk/interrupt-request/transport entry points.
- [UI tests](../common/src/test/java/com/shilapi/xcertplay/ActiveConfig5UsbMuxClaimUiTest.kt):
  startup/cancel do nothing, only confirmation runs, no E/G/transport invoked;
  mutual exclusion with other active USB diagnostics.
- Existing Settings/export/passive-inventory/configuration-mapping/G-confirmation
  tests verify integration and unchanged inventory defaults.

The VS Code test tool did not discover Gradle tests, so the existing Gradle
runner was used. Initial run found a queued Robolectric confirmation callback
in the new UI test; adding the normal main-looper drain fixed the test.
Final command:

```powershell
.\gradlew.bat :common:testDebugUnitTest `
  --tests com.shilapi.xcertplay.ActiveConfig5UsbMuxClaimDiagnosticTest `
  --tests com.shilapi.xcertplay.AndroidActiveConfig5UsbMuxClaimAccessTest `
  --tests com.shilapi.xcertplay.ActiveConfig5UsbMuxClaimUiTest `
  --tests com.shilapi.xcertplay.DiagnosticExportUiTest `
  --tests com.shilapi.xcertplay.Phase3BDeviceSettingsTest `
  --tests com.shilapi.xcertplay.PassiveUsbInventoryTest `
  --tests com.shilapi.xcertplay.PassiveUsbConfigurationMappingTest `
  --tests com.shilapi.xcertplay.QDriveConfigurationUiTest `
  :mobile:assembleDebug
```

**47 tests passed**, zero failures/errors:
engine20, adapter9, UI2, export1, Settings5, passive inventory4,
mapping5, G confirmation1. Robolectric tests use API28, not real API22
hardware certification.

`:mobile:assembleDebug` succeeded. Built manifest:

- Package `com.shihab.diplay.legacytest`; versionCode31.
- Version **0.2.12-api22-phase3d2i-usbmux-claim**.
- **minSdk22**; signing verifies with v1 and v2 (checked for minSdk22).
- `LegacyLaunchBuild.CONNECTIONS_ENABLED=false` reverified.
- APK `C:\Projects\DiPlay51\mobile\build\outputs\apk\debug\mobile-debug.apk`.
- Size **9,850,368 bytes**.
- SHA256 `88BA3575AA59719EB7A3473E0076DC52D0BFEA95A8C16773CCD497BA507C09B0`.

Editor diagnostics found no errors in changed/new runtime/test files.
No dependencies were installed. No hardware operations were executed.

## Real-E01 procedure (not executed during development)

1. Keep the Carlinkit dongle completely removed. Start Geely normally.
2. Install/open this I APK; keep normal connections disabled.
3. Connect unlocked iPhone05AC:12A8 directly to USB port1 using a known-good
   data cable, in the **already-transitioned, already-active5** state proven
   by E/G. If absent/not5, stop I; any E/G preparation is a separate phase/action,
   never automatic. Reconnection/reboot must not be assumed to preserve5.
4. Leave **Trust This Computer?** untouched. Do not approve or reject it.
5. Close other USB apps and do not run concurrent USB diagnostics.
6. In Settings/Diagnostics select
   **Phase 3D.2I — Active configuration 5 USBMUX claim**.
7. Tap **Claim/release configuration-5 USBMUX**, read the warning, then
   **Claim/release ONCE**. Stay in the activity until report completion.
8. If any precondition/open/read/claim/release/cleanup fails, do not retry or
   work around it. Preserve the report and stop.
9. On PASS, confirm both exact lines above. This establishes ownership only,
   **not USBMUX protocol, Lockdown, Trust or CarPlay success**.
10. Use **Save diagnostic report**. STOP; do not press any transport diagnostic,
    change configuration, approve Trust or implement/run the next protocol phase.

## Subsequent real-E01 result (user reported)

The user reports **ACTIVE CONFIGURATION 5 USBMUX INTERFACE CLAIM CONFIRMED**:
direct 05AC:12A8, five configurations, active 5, exact config5 USBMUX ID1/alt0/
255.254.2 with OUT04/IN85, same-connection GET_CONFIGURATION=5, force=false,
claim=true, release=true, cleanup success and zero bulk/interrupt transfers.
This confirms ownership/cleanup, **not USBMUX protocol success**.
The [Phase3D.2J static first-exchange audit](PHASE3D2J_FIRST_USBMUX_EXCHANGE_AUDIT.md)
designs a version-only next experiment without implementing or running it.

STOP AFTER PHASE 3D.2I — NO SUBSEQUENT PROTOCOL OPERATION AUTHORIZED BY THIS GUIDE.
