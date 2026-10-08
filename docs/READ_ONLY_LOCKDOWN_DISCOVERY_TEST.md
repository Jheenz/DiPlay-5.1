# USBMUX init + read-only Lockdown discovery

Build suffix: `-api22-readonly-lockdown`; minSdk22 and the false normal-connection
gate remain unchanged. This is a separate manual action beside K, not automatic
continuation. References: [J exchange audit](PHASE3D2J_FIRST_USBMUX_EXCHANGE_AUDIT.md),
[K version test](PHASE3D2K_USBMUX_VERSION_TEST.md),
[L pre-Lockdown audit](PHASE3D2L_SETUP07_PRE_LOCKDOWN_AUDIT.md).
The reported hardware K PASS remains historical version-exchange evidence only;
it is not proof of current configuration or read-only Lockdown compatibility.

## Parked-car procedure

1. Keep the intended iPhone connected, already in active configuration5 with
   existing permission. Do not automatically rerun earlier phases or reprepare
   the phone. If prerequisites are absent, STOP.
2. Open Settings, **USBMUX init + read-only Lockdown discovery**, press
   **Test read-only Lockdown discovery**, review the warning and confirm
   **Run read-only discovery**. Cancel performs no USB work.
3. Wait for cleanup. Save the diagnostic report (document picker or in-app
   fallback). Record configuration/claim, init, Lockdown connection, QueryType,
   ProductType and release/close/observer cleanup. On failure or Trust prompt,
   leave Trust untouched, pause the app to cancel, save and STOP; no retry or
   workaround. Read-only requests do not guarantee iOS will show no prompt.

## Boundary and failure handling

Same-handle config5 check and force=false claim; USBMUX initialization and
Lockdown discovery use only QueryType and GetValue(ProductType). Initialization
reuses K's strict initial version policy: one20-byte OUT and one20-byte IN,
1s timeout, no retries or fragment continuation. A valid version reply supplies
the ACK used by one17-byte setup write (2s timeout); there is no dedicated setup
ACK. Only then does the TCP reader start. One TCP connection to Lockdown port
62078 is allowed. The init milestone means version accepted and setup submitted,
not a setup acknowledgement or Lockdown readiness; the separate TCP milestone
tests connection readiness, and QueryType/ProductType establish discovery.
QueryType and ProductType each have a 5s deadline; general
TCP reads use bounded 500ms polling until their deadlines, not discovery retries.
FIN/ACK traffic is expected cleanup, not an additional discovery request.
The session halts the reader before interface release and always closes in
finally. Cleanup errors, including FIN failure, STOP; discovery PASS is emitted
only after successful release, close and final inventory checks. No Pair,
ValidatePair, StartSession, pairing records, Trust approval, session/service
startup, authentication, NCM activation or projection. All USB diagnostics
exclude this action in both directions. Pause/destroy cancels; Apple detach or
observer metadata errors latch disappearance. Observer registration failure
cancels before USB work; cleanup failure withholds PASS. Unexpected worker
errors are retained as STOP reports. No hardware PASS is claimed for this action.

## Focused PC selectors

Parent validation should include `com.shilapi.xcertplay.ReadOnlyLockdownUiTest`,
`com.shilapi.xcertplay.DiagnosticExportUiTest`,
`com.shilapi.xcertplay.UsbMuxVersionUiTest`,
`com.shilapi.xcertplay.Phase3BDeviceSettingsTest` and the new diagnostic/adapter/transport
unit selectors. UI tests inject the access/observer boundaries, not hardware,
and cover confirmation, bidirectional exclusion, cancellation, observer errors,
cleanup and report export. Robolectric runs on API28; that is not an API22
hardware result. Run one final assemble only after all sources are frozen.

## Focused validation

Final focused tests passed: **157 total (common116, shared41), zero failures or
errors**. Common: engine4, adapter6, UI11, export2, Settings5, startup26,
K engine16/adapter8/UI9 and I engine20/adapter9. Shared: queries4, strict init3,
packet1, framing27 and regression6.

Injected UI tests verify observer registration/error handling, bidirectional
exclusion with all USB diagnostics, both export destinations, and pause/destroy
cancellation. Observer cleanup failure withholds discovery confirmation and
the final success outcome while retaining achieved initialization/TCP evidence.
These API28 Robolectric checks cover the API22-compatible UI wiring; they are
not API22 device or new Lockdown hardware validation. No hardware operations
were performed for this validation.

After tests, the **one** `:mobile:assembleDebug` succeeded in 16s. APK checks:
package `com.shihab.diplay.legacytest`, minSdk22, versionCode31, version
`0.2.12-api22-readonly-lockdown`; signing verification exit0, v1/v2 true;
`CONNECTIONS_ENABLED=false`, no editor errors. APK size **8,382,451 bytes**;
SHA-256 **`29D9106E9FD4DB966944D8D4021096BFC5A6E433312A10BBC072B58651DCF5D3`**.
These are build/PC results, not init, TCP, discovery or CarPlay hardware PASS.
