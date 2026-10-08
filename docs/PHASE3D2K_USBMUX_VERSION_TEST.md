# Phase 3D.2K — USBMUX version exchange only

## Boundary and prior evidence

**ONE USBMUX VERSION REQUEST/REPLY ONLY — NO LOCKDOWN**

This is a separate, manually confirmed diagnostic, not full USBMUX initialization.
The user reported real-E01 [I claim/release PASS](PHASE3D2I_ACTIVE_CONFIG5_USBMUX_CLAIM_TEST.md).
[J's first-exchange audit](PHASE3D2J_FIRST_USBMUX_EXCHANGE_AUDIT.md) established the
20-byte version packet and the stopping point before SETUP07. PC tests and the
debug build passed (below); no hardware operations were run during development.
The user subsequently confirmed **real-E01 K PASS**: direct USB, active config5,
scoped USBMUX claim and one version request/reply succeeded. No setup07 or
Lockdown was started. See the subsequent
[L setup07 pre-Lockdown static audit](PHASE3D2L_SETUP07_PRE_LOCKDOWN_AUDIT.md).
This does not authorize any further hardware operation.

Settings exposes **Phase 3D.2K — USBMUX version exchange**, the button
**Test USBMUX version exchange**, and confirmation **Send ONE version exchange**.
Startup, opening Settings, completing another diagnostic and resume never start K.
K never automatically invokes E, G or I. All active USB diagnostics exclude one
another in both directions while K runs, including confirmation-time rechecks.
Normal connection gates remain disabled.

## Implementation

- [Engine and injectable boundary](../common/src/main/java/com/shilapi/xcertplay/UsbMuxVersionDiagnostic.kt)
- [Android API22 adapter](../common/src/main/java/com/shilapi/xcertplay/AndroidUsbMuxVersionAccess.kt)
- [Settings, confirmation, exclusion, detach, pause and export](../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt)
- [UI text](../common/src/main/res/values/strings_legacy.xml) and [APK version](../mobile/build.gradle.kts)
- [Shared request bytes](../shared/src/main/java/com/shilapi/xcertplay/transport/UsbMuxVersionPacket.kt):
  `UsbMuxVersionPacket.request()` was extracted byte-identically. The existing
  [`Iap2UsbMuxHost`](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt)
  uses the same bytes with no behavior change. K neither constructs nor opens
  that host; it depends only on the independent packet helper.

## Exact controlled experiment

- Before open: exactly one Apple device, permitted `05AC:12A8`, exactly five
  configurations and unique ID5. Use I's configuration-scoped identity:
  unique USBMUX ID1/alt0/255.254.2; exactly bulk OUT04/number4/direction0 and
  IN85/number5/direction128; config5 case-sensitive interface-name `Valeria`
  with descriptor255/42/255; genuine CDC-NCM Control2/13/0 and Data10/0/1
  descriptors (metadata only). No flattened-interface fallback or index13.
- Both endpoint maxPacketSizes must exceed20. IN maxPacketSize must be at most1024.
  Revalidate permission and scoped device/interface stability.
- Open once and require same-handle standard GET_CONFIGURATION=5:
  `80/08/value0/index0/length1/timeout1000ms`, return count exactly1.
  No configuration setter, alternate setting, force detach or vendor request.
- Claim the actual configuration5 USBMUX interface once with `force=false`.
- Exactly one bulk OUT04 request of20 bytes, timeout1000ms:

  ```text
  00 00 00 00  00 00 00 14  00 00 00 02  00 00 00 00  00 00 00 00
  ```

- Only after full OUT20: exactly one bulk IN85 read into a1024-byte buffer,
  timeout1000ms. Require count exactly20. This captures extra bytes instead of
  silently truncating the receive buffer to20.
- Validate big-endian protocol0, total length20 and major2. Reply minor/reserved
  words are reported but not constrained. No drain, fragment continuation,
  padding discard, short-write continuation, retry, reopen or persistent reader.
  The initial version packet has no nonce/tag, so freshness of an identical
  pending reply cannot be proved; K performs no pre-read/drain to try.
- Reports do not save raw unexpected IN payload. Reply hex is included only
  for a valid version packet; unexpected replies retain parsed header/counts.
  A negative Android IN result is labelled `BULK_IN_TIMEOUT_OR_FAILURE`:
  the public bulk-transfer API cannot distinguish timeout from other failure.
- Release once in finally only after claimtrue; nested finally-close regardless
  of outcome. Pause cancels subsequent work while preserving release/close.
  Public claim/release calls have no timeout parameter; the transfer timeout is
  not a guarantee that a blocked kernel ownership call can be interrupted.
- During K, reuse the existing Android USB attach/detach observer. An Apple
  detach suppresses PASS even if reattachment leaves matching inventory; no
  recovery or wait is added. Missing event metadata fails closed. Filtering is
  deliberately broad: any Apple (VID 05AC) detach counts, not only the
  selected device; non-Apple detaches are ignored because preflight and final
  inventory already require exactly one Apple device. The receiver uses only
  API22-available calls (application-context dynamic registration through
  `ContextCompat.registerReceiver`, `getParcelableExtra`,
  `unregisterReceiver`). Registration failure cancels before any USB access
  and the report states `USB detach observation=FAILED ...`. The observer
  closes after diagnostic cleanup, without delaying release/close; the report
  states `USB detach observer cleanup=PASS`, `NOT NEEDED` or `FAILED`. A
  cleanup failure replaces PASS with
  `Outcome=USB_EVENT_OBSERVER_CLEANUP_FAILURE`. An unexpected engine exception
  yields `Outcome=UNEXPECTED_DIAGNOSTIC_FAILURE`; no exception produces PASS.

PASS is **USBMUX VERSION EXCHANGE CONFIRMED**, followed by
**SETUP07 / LOCKDOWN / PAIRING NOT STARTED**. Cleanup failure suppresses PASS.
Unexpected, short, extra or timed-out data is a STOP result, not justification
to drain or retry. PASS proves only this version exchange, not TCP readiness,
Lockdown, authentication or CarPlay.

There is no existing USBMUX host opener, SETUP07, TCP connect62078, plist,
pairing-record access, Trust change, session/TLS, iAP2, MFi hardware, NCM
activation, Bluetooth, AirPlay or projection in this experiment.

## Parked-car procedure

1. Install `0.2.12-api22-phase3d2k-usbmux-version` from
   `mobile\build\outputs\apk\debug\mobile-debug.apk` (package
   `com.shihab.diplay.legacytest`, versionCode31, minSdk22, 9,856,963 bytes,
   SHA-256 `268F1EE844490A5F78877512705C305090437ADC6BD90F79B572B0878606FD54`).
   Compare the hash and the visible version before testing.
2. Use the iPhone that is **currently** directly connected in the active5
   state prepared by earlier separately authorized E/G, with USB permission
   already granted. Prior E/G/I PASS reports are historical only; they do not
   prove the present device/handle is active5. Do not disconnect, replug,
   reboot or re-run E/G/I to create that state; detach/reattach is not
   harmless and may change it. If the current state or permission is not
   already present, **STOP**.
3. Remove Carlinkit and leave other USB apps/services inactive. Keep the
   iPhone unlocked. Do not run any other USB diagnostic first or concurrently.
   K never transitions or selects a configuration and never runs E/G/I.
4. Open Settings/Diagnostics. Verify K shows
   `Phase 3D.2K — USBMUX version exchange: not run`. Leave any iPhone Trust
   prompt untouched; do not approve or reject it.
5. Press only **Test USBMUX version exchange**. Read the boundary warning and
   confirm **Send ONE version exchange** once. Do not press it again.
6. Do not touch the cable during the test. Wait for the outcome, release/close
   and transfer-count lines. If Trust appears, leave it untouched and pause
   DiPlay to cancel subsequent work; do not run another test.
7. **Save diagnostic report** (the final report, whatever the outcome).
   Confirm the K section contains preflight inventory/selection,
   `Same-connection GET_CONFIGURATION`, claim with `force=false`, OUT request
   hex/result, IN capacity/result, parsed fields, release/close, final
   inventory, outcome, `SETUP07 / LOCKDOWN / PAIRING NOT STARTED`, the
   bulk-call line (zero retries/setup07/TCP/Lockdown) and
   `USB detach observation=REGISTERED` / `USB detach observer cleanup=PASS`.
   PASS requires OUT1/IN1 and **USBMUX VERSION EXCHANGE CONFIRMED**.
8. **STOP**, whether PASS or failure. Any preflight, identity, permission,
   GET_CONFIGURATION readback, claim, transfer, validation, detach or cleanup
   failure is final for this session: no retry, rerun, replug, E/G/I
   re-preparation, recovery, alternative command or other manual/automatic
   workaround, and no setup07/Lockdown without separate authorization.

Do not press the older **Test USBMUX + Lockdown** action or treat an I PASS as
permission to continue beyond K's single reply. This procedure has not been
executed on hardware by the developer; only the saved real-E01 report can
establish a K result.

## Focused PC validation

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.UsbMuxVersion*Test' --tests 'com.shilapi.xcertplay.AndroidUsbMuxVersionAccessTest' --tests 'com.shilapi.xcertplay.ActiveConfig5UsbMuxClaimUiTest' --tests 'com.shilapi.xcertplay.Phase3BDeviceSettingsTest' --tests 'com.shilapi.xcertplay.DiagnosticExportUiTest'
.\gradlew.bat :mobile:assembleDebug
```

Injected-access UI tests cover cancelled and confirmed manual actions,
precondition failure, successful one-exchange reporting, all active USB
diagnostics' mutual exclusion in both directions, confirmation-time recheck,
pause cancellation with release/close and no subsequent traffic, and explicit
observer registration/cleanup failure without PASS.
An actual injected Android detach/reattach broadcast with unchanged fake
inventory verifies transient disappearance suppresses PASS without recovery.
Settings/export tests cover labels, not-run state and retained report text.
These tests require no USB hardware; Robolectric SDK28 is not an API22
hardware validation. Run combined validation serially, not concurrent builds.

### Validation result

Final serial validation of the corrected source passed with zero
failures/errors (includes adapter construction-cleanup suppressed-error
reporting, open-layer construction-error cleanup wording, and explicit
observer registration/cleanup failure reporting):

| Scope | Tests |
| --- | ---: |
| Common: K engine16, adapter8, UI9; I engine20, adapter9, UI2; export1; Settings5 | 70 |
| Shared: packet1, host regression6, frame27 (packet extraction) | 34 |
| **Total** | **104** |

`:mobile:assembleDebug`: BUILD SUCCESSFUL. aapt verified minSdk22,
`0.2.12-api22-phase3d2k-usbmux-version`, `com.shihab.diplay.legacytest`
and versionCode31. apksigner exited 0 with v1/v2 true (standard META-INF
warning only). `CONNECTIONS_ENABLED=false` was verified. Final APK:
9,856,963 bytes, SHA-256
`268F1EE844490A5F78877512705C305090437ADC6BD90F79B572B0878606FD54`.
No hardware run has been performed by the developer.
