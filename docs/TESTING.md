# Test checklist

## Phase 3D.2W - CarKit service TCP/TLS handshake only

See [the one-run 3D.2W hardware procedure](PHASE3D2W_CARKIT_SERVICE_TCP_TLS.md).
Focused coverage validates simultaneous USBMUX sockets, independent routing/close, the
authenticated service TLS path, and cleanup before Lockdown StopSession. No CarKit application
traffic is sent. Hardware confirmed service TLS, certificate validation, encrypted StopSession, and cleanup. A retired-Lockdown RST-with-payload diagnostic was logged after StopSession response while the host was already closed; see the runbook for its timing analysis. No further hardware run is required for that report. Debug version: `0.2.12-api22-phase3d2w-retired-rst-payload-fix`; minSdk22; production connections remain disabled.

## Phase 3D.2V - controlled CarKit StartService discovery

See [implementation and single-run hardware procedure](PHASE3D2V_CARKIT_STARTSERVICE_DISCOVERY.md).
Focused unit tests cover success, service error/unavailable, malformed and missing response
fields, paired-record/TLS gates, StopSession outcomes, and cleanup. No hardware test is run
during implementation. The debug build remains minSdk22 and connections stay disabled.

## Phase 3D.2T.1 - Lockdown TLS peer validation + StopSession (3D.2U manual)

See [TLS validation and cleanup](PHASE3D2T1_TLS_VALIDATION_CLEANUP.md). The trust-all
manager was replaced by paired-device key pinning. A real in-JVM TLS server tests:
- accept, mismatch, empty/malformed chains and missing material;
- same-stream fragmented/coalesced boundaries and RST/EOF;
- the encrypted QueryType, and StopSession(SessionID) inside TLS;
- cleanup on every outcome.

Lint shows no NewApi findings in the TLS path. No hardware was run. On E01, press "Test Lockdown
TLS round trip (NO SERVICES)" only manually with an already-transitioned config5 iPhone,
save the report, and STOP.

## Phase 3D.2T - Lockdown TLS and session static audit

See [same-stream trace, certificate-validation blocker and lifecycle audit](PHASE3D2T_LOCKDOWN_TLS_SESSION_AUDIT.md).
Five local-only TLS boundary tests pass on Robolectric28. This is not API22
provider certification. The current Lockdown TrustManager accepts empty or
untrusted peer certificate chains, so do not attempt TLS on E01 until a
paired-device validation policy is resolved. No USB/hardware or TLS
operation was run. StopSession must be inside TLS after a successful SSL
session; a transport close does not confirm logical session cleanup.

## Phase 3D.2S - controlled modern StartSession (no TLS/services)

See [implementation, request/cleanup boundaries and verification](PHASE3D2S_CONTROLLED_STARTSESSION_DIAGNOSTIC.md).
47 focused PC tests passed; assembleDebug succeeded. Version
`0.2.12-api22-phase3d2s-startsession`, minSdk22, CONNECTIONS_ENABLED=false.
The separate manually confirmed action requires one accepted record and
proven active config5, exact live association and one fresh Lockdown connection.
No Pair/ValidatePair/SetValue, identity generation, record writes, TLS/services
or projection. SSL=false plus valid SessionID allows one StopSession;
SSL=true closes without TLS/plaintext StopSession and reports session stop
not confirmed. Preserve installation/data/KeyStore; in-place update only.
No hardware test was run. Earlier instructions below are historical phase
boundaries, not instructions to repeat Pair or ValidatePair in Phase S.

## Phase 3D.2P.3 - existing-record lookup/reopen verification

See [lookup comparison, clarified chronology and disk reopen tests](PHASE3D2P3_EXISTING_PAIR_LOOKUP_FIX.md).
The user confirmed the old NOT_FOUND preceded the latest Pair and followed
uninstall; no same-state false lookup is proven. No production fix, version
change or new APK. 42 focused tests passed (common32/shared10), including a
fresh preferences implementation loading the committed XML file.
That PC independent store reopen is not an E01 process/KeyStore restart.
No USB validation test is authorized; peer RST remains unresolved.

## Phase 3D.2P.2 - local pair-record inspection

See [audit and validation](PHASE3D2P2_PAIR_RESET_PERSISTENCE_AUDIT.md).
Use only **Inspect local pairing records (NO USB)** for the next observation;
do not repeat Pair or run a validation/reconnect hardware test.
Preserve installation/data/KeyStore and install same-signer in-place updates.
Tests mock the previous Pair/Validate reset; they never contact hardware.

## Phase 3D.2P.1 - no-Pair existing-record ValidatePair

See [the separate action and failure classifications](PHASE3D2P1_EXISTING_PAIR_VALIDATE_TEST.md).
Build version `0.2.12-api22-phase3d2p1-existing-pair`, minSdk22,
CONNECTIONS_ENABLED=false. PC-only focused validation: 201 tests passed
(shared58/common143), no failed/skipped; assembleDebug successful.
Tests use injected inventories/storage and mocked USB frames, not real USB.
Missing/PREPARED records block before open, associated PAIRED/VALIDATED
records send at most one ValidatePair, never Pair/SetValue/session/services.
RST, FIN/EOF, timeout, short-write, wrong-device and cleanup outcomes retain
safe original provenance. Do not run the hardware diagnostic during development.

## Phase 3D.2O — controlled Lockdown Pair + ValidatePair

See [the manual confirmation, candidate handling, protocol order and STOP
criteria](PHASE3D2O_CONTROLLED_PAIR_VALIDATE_TEST.md). Debug suffix:
`-api22-phase3d2o-pair-validate`. The action is separate from read-only
discovery and never starts automatically. Confirm only after reviewing the
disclosure that `SetValue(UntrustedHostBUID)`, local protected credential
storage and iPhone Trust are persistent effects. Trust approval happens only
on the iPhone.

For a new device record, the order is QueryType, GetValue(UniqueDeviceID) for
per-device lookup (never logged/exported), preserve stable identity, then
SetValue(UntrustedHostBUID), GetValue(DevicePublicKey), and
GetValue(WiFiAddress). Because the normal generator's empty issuer is rejected
by the bundled Bouncy Castle parser, the explicitly authorized diagnostic-only
generator uses valid nonempty names while retaining RSA-2048, SHA-256, chain
structure and lifetime; the normal generator is unchanged. The bundled parser
checks the signed chain and device-key/private-key match before Pair. This is
not an iOS acceptance claim. Save the complete candidate including EscrowBag
in AES-GCM, with its random AES key
RSA-wrapped by AndroidKeyStore; synchronously commit and decrypt/readback
verify before Pair. A PREPARED candidate gets one Pair per manual run; pending
ends that run after cleanup, and the next separately confirmed run reuses the
same material. PAIRED (Pair accepted, validation failed/cancelled) and
VALIDATED records each get ValidatePair only, with no SetValue or Pair.
Existing legacy `lockdown_host_id`, corrupt data or a lost keystore key is
STOP; never import, read legacy credentials, clear, replace or downgrade.
A repeated VALIDATED save of an unchanged record must leave its ciphertext unchanged.
Any ambiguous result or cleanup failure is STOP. No automatic retry,
StartSession, TLS, StartService or projection.

The focused UI tests cover manual confirmation/disclosure, exclusion in both
directions and retention in both report-export branches. Run them together with
the core diagnostic/store tests:

```powershell
.\gradlew.bat :shared:testDebugUnitTest --tests '*ControlledLockdownPairingTest*' --tests '*DiagnosticPairMaterialTest*' --tests '*ReadOnlyUsbMuxInitTest*' --tests '*ReadOnlyLockdownQueriesTest*' --tests '*UsbMuxVersionPacketTest*' :common:testDebugUnitTest --tests '*ControlledPairDiagnosticTest*' --tests '*AndroidDiagnosticPairStoreTest*' --tests '*AndroidControlledPairAccessTest*' --tests '*AndroidReadOnlyLockdownAccessTest*' --tests '*ReadOnlyLockdownDiagnosticTest*' --tests '*ReadOnlyLockdownUiTest*' --tests '*DiagnosticExportUiTest*' --tests '*Lazy*' --tests '*UsbMuxVersion*' --tests '*ActiveConfig5*' --tests '*Phase3BDeviceSettingsTest*'
.\gradlew.bat :mobile:assembleDebug
```
Final combined focused run: PASS, 151 tests (shared 16, common 135), 0 failed,
0 skipped. It covers pairing, certificate material, store, adapter,
orchestrator, UI, export, Settings and lazy-startup tests plus read-only
Lockdown, K and configuration-5 regressions. `:mobile:assembleDebug` was run
once afterwards: BUILD SUCCESSFUL (10 s).
APK `0.2.12-api22-phase3d2o-pair-validate` (code 31, minSdk 22), SHA-256
`5C16FB8C56D023D2799E25F88832F75F33C12A0F699A1B58CA53736C73EC3D0A`. No
hardware was exercised; SDK 28 Robolectric with software keys does not certify
E01 AndroidKeyStore or iOS certificate acceptance.

## USBMUX init + read-only Lockdown discovery

See [the brief manual procedure and boundaries](READ_ONLY_LOCKDOWN_DISCOVERY_TEST.md).
Suffix `-api22-readonly-lockdown`; separate confirmation beside K, ProductType
only after QueryType, no automatic prior phases, pairing or session. Save the
report and STOP after this diagnostic. The user-reported K hardware PASS is
historical version-exchange evidence, not a PASS for this new action. Earlier
K build/test statements below describe their own snapshot.
Final focused validation passed **157 tests (common116/shared41), failures/errors0**,
including injected observer, all-USB exclusion, export and pause/destroy checks.
Robolectric uses API28, not API22 hardware. One final `:mobile:assembleDebug`
succeeded (16s), minSdk22/code31/version `0.2.12-api22-readonly-lockdown`,
v1/v2 signing verified and connection gate false. APK: 8,382,451 bytes,
SHA-256 `29D9106E9FD4DB966944D8D4021096BFC5A6E433312A10BBC072B58651DCF5D3`.
No hardware operations were performed. Read-only requests do not guarantee no
iOS prompt: leave Trust untouched, pause to cancel, save and STOP. Init confirms
accepted version/submitted setup only (no setup ACK); TCP readiness and
QueryType/ProductType discovery are separate milestones. See the procedure for
the selector breakdown.

## Phase 3D.2K — manual USBMUX version exchange only

See [the exact boundary, focused PC command and parked-car procedure](PHASE3D2K_USBMUX_VERSION_TEST.md).
Build suffix: `-api22-phase3d2k-usbmux-version`. Manually confirm
**Test USBMUX version exchange** / **Send ONE version exchange** only on the
currently connected iPhone already in active5 (prepared by earlier separately
authorized E/G) with permission already granted; prior PASS reports are
historical, not proof of current state. Do not replug or re-run E/G/I to create
it; otherwise STOP. No automatic E/G/I. Any K preflight/readback failure is
final: STOP, no retry or workaround.
One same-handle GET5, forcefalse claim, OUT20/IN1024 (1000ms each, reply count20),
release/finally-close; no fragment continuation, padding discard or retry.
PASS stops at **USBMUX VERSION EXCHANGE CONFIRMED** and
**SETUP07 / LOCKDOWN / PAIRING NOT STARTED**. Leave Trust untouched, save the
report and STOP. Injected UI tests cover manual confirmation, bidirectional USB
exclusion, transient-detach suppression, pause cleanup and export.
Final combined validation passed 104 tests with zero failures/errors (70 common
including K UI9, 34 shared including the byte-identical shared
`UsbMuxVersionPacket.request()` extraction) and `:mobile:assembleDebug`;
minSdk22, versionCode31, apksigner v1/v2 and `CONNECTIONS_ENABLED=false` were
verified. Final APK (9,856,963 bytes) SHA-256
`268F1EE844490A5F78877512705C305090437ADC6BD90F79B572B0878606FD54`.
Real-E01 K has not been run; no hardware PASS is claimed.

Use the [installation guide](INSTALL.md). With the car parked, verify wired and wireless connection, picture, touch and music. Test disconnect/reconnect, then settings Apply/Cancel. Save a diagnostic report after reproducing an issue.

## Phase 3A API 22 existing-LAN checks

Run from the project root with a supported JDK:

```powershell
.\gradlew.bat :mobile:assembleDebug
.\gradlew.bat :shared:testDebugUnitTest --tests 'com.shilapi.xcertplay.network.*' --tests 'com.shilapi.xcertplay.orchestration.ManualHotspot*Test' :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.ExistingWifi*Test' --tests 'com.shilapi.xcertplay.CarPlayBonjourDualStackTest' --tests 'com.shilapi.xcertplay.CarHotspotSetupTest' --tests 'com.shilapi.xcertplay.Phase3ADeviceSettingsTest'
.\gradlew.bat :shared:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.shilapi.xcertplay.network.LegacyNetworkApi22DeviceTest'
```

Select only an API 22 emulator/device for the last command. The instrumentation suite is API 22-only
and creates test-only local discovery advertisements; it never initializes authentication,
Bluetooth, USB or a CarPlay session, and disables Bonjour control probes. It exercises the real
network sampler/service lookup, manager construction, legacy network callback registration,
local IPv4/scoped-IPv6 listener round trips, stable injected AP readiness, observable system NSD
advertisement, and local peer discovery/resolution. It does
not create a hotspot. A non-Wi-Fi emulator can verify sockets/discovery, but cannot verify
real Wi-Fi attachment or AP ownership. Robolectric 4.17 does not support API 22; legacy
selection tests use pure snapshots and runtime checks use instrumentation.

Validation on the API 22 Android 5.1.1 emulator: 188 shared network unit tests, 32 focused
common-module unit tests, and all 5 network instrumentation tests passed. The debug APK
installed/launched; `eth0` exposed `10.0.2.15` and scoped link-local IPv6, both served a byte
round trip on the same port. The emulator's own DNS-SD advertisement was observed and a local
test peer resolved with no control probes. Full shared-module lint remains blocked by 84
errors in other surfaces; no `NewApi` error remains in the audited Phase 3A files. No lint
baseline or broad API suppression was added.

For the hardware-validation changes, the debug build and instrumentation APK compilation
pass, as do 197 shared and 36 common focused unit tests (including safety gates, address-change
signatures and Settings controls). The connected instrumentation command was attempted but
blocked by `No connected devices!`; only an API 37 AVD is installed on this development machine.
The API 22 class now contains six tests including the new device-diagnostics report/cleanup
check. These six tests must be rerun on API 22; the earlier five-test emulator result above is
historical, not validation of this build.

Install/launch the mobile debug APK separately and confirm the visible version is
`0.2.12-api22-phase3b-safe-startup` and projection/vehicle controls remain disabled.
Open **Settings -> Phase 3A network diagnostics**. It samples about every three seconds;
**Refresh network diagnostics** reruns the sampler and stable readiness check.
**Start Phase 3A network test** checks readiness before publishing a diagnostic-only
`_diplay-phase3a._tcp.` service and discovering that same type for 20 seconds. No CarPlay
advertisement, authentication, handshake or Bluetooth/USB/vehicle work occurs.
Registration success, service observation, self-observation, resolved peer address/port,
multicast lock state and test result appear in the screen and exported report.
To exercise peer resolution, have the laptop advertise `_diplay-phase3a._tcp.` with a
non-DiPlay name and a known port. Verify the head-unit advertisement separately on the
laptop; self-observation is not a bidirectional multicast test. API 22 NSD is platform-scoped.
Pause the app or toggle the hotspot during a test and verify cleanup and visible failure.
Capture `adb logcat -s DiPlayPhase3ADevice` if ADB is available.

The laptop previously received `192.168.43.157` with gateway `192.168.43.1`. Confirm the
app's interface list shows which actual interface owns `192.168.43.1`, and compare it to the
selected interface, local IPv4, prefix, route and AP/station evidence. Do not hardcode it.
On a parked Okavango with its hotspot manually enabled, confirm the firmware's AP status and
interface ownership agree, a stable private IPv4/scoped-IPv6 address is selected, and
readiness still succeeds without an Internet/default network. Check off/on and IP changes;
disabled, disappearing or station-only interfaces must not pass Manual Hotspot readiness.
For Existing Wi-Fi, verify the head unit's already connected station is selected, saved SSID
mismatches are rejected, and disconnect/address changes invalidate the attachment.

Use another non-iPhone LAN device advertising the diagnostic DNS-SD service to verify bidirectional
multicast and peer resolution. API 22 NSD is platform-scoped and can omit TXT IDs; validate
the selected address/port and the advertised receiver on the intended LAN, particularly with
cellular/VPN enabled or concurrent station/AP interfaces. Do not initiate a phone handshake.

## Phase 3B API 22 Bluetooth / pre-auth iAP2 checks

Phase 3A is hardware-confirmed on the Okavango: `ap0`, `192.168.43.1/24`, stable manual
readiness and cross-device NSD/mDNS all pass. The Phase 3B build retains those diagnostics.

```powershell
.\gradlew.bat :mobile:assembleDebug
.\gradlew.bat :shared:testDebugUnitTest --tests 'com.shilapi.xcertplay.transport.BluetoothCompatibilityTest' --tests 'com.shilapi.xcertplay.transport.Iap2*Test' --tests 'com.shilapi.xcertplay.iap2.*' --tests 'com.shilapi.xcertplay.network.*' --tests 'com.shilapi.xcertplay.orchestration.ManualHotspot*Test' --tests 'com.shilapi.xcertplay.media.Legacy*Test' --tests 'com.shilapi.xcertplay.media.MediaCodec*Test' :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.Phase3*Test' --tests 'com.shilapi.xcertplay.ExistingWifi*Test' --tests 'com.shilapi.xcertplay.CarPlayBonjourDualStackTest' --tests 'com.shilapi.xcertplay.CarHotspotSetupTest'
.\gradlew.bat :shared:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.shilapi.xcertplay.transport.LegacyBluetoothApi22DeviceTest,com.shilapi.xcertplay.network.LegacyNetworkApi22DeviceTest,com.shilapi.xcertplay.media.LegacyMediaApi22DeviceTest'
```

Use only an API 22 target for instrumentation. The Bluetooth test does read-only service lookup,
permission-policy and report/cleanup checks; it does not scan or connect without operator action.
Robolectric uses supported API 23+ environments, while pure permission-policy tests cover API 22.
Do not treat mocked RFCOMM or framing tests as real radio validation.

Original Phase 3B device-test validation: `:mobile:assembleDebug` and instrumentation APK compilation passed.
The focused selection passed 271 shared tests and 43 common tests with no failures/skips.
Coverage includes API 22 permission policy, API 28 adapter/bonded/scan lifecycle, API 31
permission denial, RFCOMM stream I/O, mocked hardware-mode connection success/failure,
pre-auth identification/authentication boundaries and Phase 1/2/3A regressions.
Connected API 22 instrumentation was attempted but blocked by `No connected devices!`;
API 22 Bluetooth runtime and real RFCOMM radio behavior remain hardware checks.

### Phase 3B.3 cache-only status hardware procedure

1. Install `0.2.12-api22-phase3b3-nforetek-cache-status`. Verify launch and opening
   Settings do not initiate a vendor test; the new section should say not sampled.
2. Turn vehicle Bluetooth ON using Geely Settings and confirm `GEELY_BT`; keep its
   UI/service active. Do not use stock Android Bluetooth enable or the older
   Phase 3B.2 component bind selector.
3. Press **Read cached NForetek Bluetooth status** in the Phase 3B.3 section.
   Confirm the exact NfServiceBluetooth component, flags=0, matching descriptor and
   genuine interface resolution. Capture all five raw values, interpreted enabled/state,
   version, failures if any, and **Unbind SUCCESS**. Do not treat bind success as
   transport success or cached readiness.
4. Save the diagnostic report. Confirm Geely Bluetooth remains usable after unbind.
   If vehicle Bluetooth is disturbed, stop testing and supply the report/logcat.
5. Optionally change Bluetooth using Geely Settings, then manually repeat to compare
   cached ON/OFF values (302/300). No automatic polling/change detection is implemented;
   stale, null or unknown results must remain explicit.
6. Missing service/bind=false/hash mismatch/timeouts do not trigger startup or fallback.
   A hanging vendor IPC blocks repeat tests until it returns; a timeout does not imply
   vendor code was terminated. Do not proceed to SPP/iAP2/authentication/Phase 3C.

Focused Phase 3B.3 tests use synthetic cache interfaces, not redistributed vendor code,
on Robolectric SDK 23/28 (Robolectric cannot execute API 22). They verify exactly five
getter calls, no forbidden calls, inert construction/launcher, zero binding flags,
descriptor/hash rejection, null/unknown values, vendor exceptions/linkage errors,
missing/disabled service, disconnect, timeout/blocked IPC and unbind failures/cleanup.
Real installed DexClassLoader/Stub resolution and cached GEELY_BT values were
subsequently confirmed by the operator on the Okavango: flags=0 bind, descriptor,
genuine interface resolution and clean unbind all passed; Bluetooth ON returned
`GEELY_BT`, `00:0D:86:2F:20:23`, enabled=true and raw state=302/ON.
Standard Android still reported the separate disabled CAR_BT adapter.
This is a real-car result, separate from unit tests; OFF/ON cache freshness and
the service-version value were not supplied in that result.

Validation for `0.2.12-api22-phase3b3-nforetek-cache-status`: debug assembly passed;
focused Phase 1/2/3A/Bluetooth/iAP2 shared regressions passed (271 cases); common
phase/network/export regressions passed (146 cases, including 28 new Phase 3B.3 cases
on SDK 23/28). API 22 instrumentation APK compilation passed, but no device was attached
for execution. Packaged minSdk=22, armeabi-v7a included, and API-22 v1 signing verified.
These are the focused regression suites, not a claim that the unrelated full suites
with existing disabled-surface expectations are green.

### Phase 3B.4 static audit and next-test recommendation

See [COMPATIBILITY.md](COMPATIBILITY.md#phase-3b4-static-transport-path-audit) for
the candidate-path matrix, exact methods/call sites and controller/lifecycle risks.
This milestone changes documentation only, not the APK or runtime transport.
No new build/test execution is required for this static audit; the preceding
build and regression results remain historical validation of the cache build.

The safest next hardware test uses the same five approved cache getters:
on a parked vehicle, keep Geely Settings active, capture ON, OFF, then ON-again
reports after changing Bluetooth only through the vehicle UI. Record the service
version, raw values, interpreted states, exceptions, bind and unbind outcomes.
Expect ON=302/true and OFF=300/false, without hiding stale or unavailable results.
If flags=0 binding fails because the service stopped, do not start it.
Verify the stock phone/audio functions still work after each test.

Do not add a callback-only test: `registerBtCallback()` can trigger vendor
initialization/controller queries and `setAutoConnect()` when internal readiness
is false. Do not request paired devices, bind/start SPP, use serial/JNI/socket
access, connect/pair, send payloads or answer Apple requests. Obtain the installed
Geely Settings/control APK for the next local static audit instead of treating
placeholder ECARX methods as a complete vehicle-UI trace. Phase 3C remains blocked.

### Phase 3B.4 stock control APK collection procedure

1. Install `0.2.12-api22-phase3b4-control-apk-export` on the Okavango. Launch and
   open Settings; no control-package search or export runs automatically.
2. In **Phase 3B.2 NForetek service diagnostics**, press
   **Find and export stock Geely Bluetooth control APK** and wait for completion.
   Do not press the vendor service-bind or investigation/callback buttons.
3. For an exact match, record package, service/application/component or action
   match, installed source path and every base/split export SUCCESS/FAIL.
   Each successful copy reports destination, byte count and SHA-256.
4. Copy all reported successful APK files from the existing `DiPlayVendorDump`
   directory to the PC's ignored `vendor-apks/` directory. If public Downloads
   failed, use the exact app external-files destination shown in the report.
   Save the diagnostic report too. Partial split failures mean the package export
   is incomplete; preserve that fact for static analysis.
5. If there is no exact match, save the candidate package/component/source-path
   report instead. Candidates are not launched, bound or automatically exported.
   A failed/restricted inventory is not evidence that the implementation is absent.
6. STOP after collection. Statically inspect the missing stock Settings/control
   implementation before approving another interaction. Do not test a transport.

Validation: `:mobile:assembleDebug` passed; focused shared Phase 1/2/3A/Bluetooth/iAP2
regressions passed (271 tests); common phase/network/export regressions passed
(164 tests), including 28 APK-export cases on SDK 23/28 (18 new control-collection
cases). Tests cover exact class/namespace/action ownership in differently named
packages, relative/disabled components, boundary lookalikes, base/split copies and
hashes, candidate-only no-copy behavior, PM security/runtime/linkage failures,
disappearing packages, partial split failure and absence of service/broadcast/
receiver/system-service operations. Existing launcher-laziness/cache/network tests
remain green. These are focused suites, not a full-suite claim.

Instrumentation APK compilation passed; the new real-car export remains untested
here. Packaged minSdk=22, armeabi-v7a inclusion and API-22 v1 signing were verified.
The operator has now confirmed successful stock control APK extraction on the
Okavango: package `com.neusoft.optimus.wheeljack.setting`, reported original
source `/system/app/Setting/Setting.apk`. The local base APK has been statically
audited together with the two earlier vendor APKs; this is not a live service test.

### Phase 3B.4 combined Settings audit: hardware boundary

See [the combined audit](COMPATIBILITY.md#phase-3b4-combined-audit-with-the-extracted-stock-settings-apk)
for exact UI/Binder/controller call chains, candidate matrix, callback/broadcast
effects, manifest-only BLE components and the external Apple JNI/USB boundary.
The stock control service returns a null Binder and performs policy/vehicle work
on creation. The real stock `UiCommand` bridge auto-creates NForetek profiles and
registers callbacks. Neither is a safe replacement for the existing direct
five-getter `NfServiceBluetooth` diagnostic. SPP remains prohibited/no-op.

Recommended next test on a parked unit:

1. Use the existing cache-status action only. With stock Geely Settings active,
   capture ON, OFF and ON-again; change Bluetooth only through the stock UI.
2. Record name/address, enabled, raw/interpreted state, service version,
   flags=0 bind, descriptor, genuine interface resolution, errors and clean unbind.
   Expected ON=302/true, OFF=300/false; preserve stale or failed results.
   A stopped service is a bind failure, not permission to auto-create it.
3. Confirm no new DiPlay Bluetooth/vehicle activity and that stock phone/audio
   still works. Retain Phase 3A behavior and normal launch/pause/resume checks.
4. Before further transport work, collect read-only installed metadata and source
   APK(s) for the explicitly referenced package `com.neusoft.appleservice`.
   Obtain readable `libAppleCore_jni.so` / `libApplePrivate_jni.so` plus native
   dependencies/configuration from metadata-resolved or read-only inventoried
   locations, not an assumed installation path. If authorized ADB is already
   available, `adb shell pm path com.neusoft.appleservice` and
   `adb shell dumpsys package com.neusoft.appleservice` are metadata-only leads;
   copy only the reported readable files. Record source, ABI, bytes and SHA-256.
   Do not load code, alter permissions, root/bypass denials, or start a service.
5. STOP after collection for static inspection. Do not connect an iPhone through
   DiPlay, register callbacks, bind stock control/bridge/BLE/SPP services, send
   broadcasts/controller commands, access serial/USB nodes, initialize Apple JNI,
   authenticate, send iAP2 or begin Phase 3C.

This follow-up changes documentation only. No new APK, runtime transport,
build identity or hardware capability was introduced; build/test results above
remain the previous exporter validation, not new execution for this audit.

### Phase 3B.5 collection-only hardware procedure

1. Install `0.2.12-api22-phase3b5-apple-stack-export` on the parked API 22 unit.
   Verify launch/Settings remains usable; no collection runs automatically.
   Do not attach an iPhone or press any vendor service-bind/transport buttons.
2. Open **Phase 3B.5 Apple/USB stack collection only**, then press
   **Collect and export Apple/USB stack files** once. Wait for completion in the
   existing vendor-export report above. No USB role or Bluetooth state change
   should occur.
3. Save the diagnostic report. Record package version, source/splits/library
   directories, components/actions and every SUCCESS/FAIL, including missing
   libraries/dependencies/config candidates, parser failures and extraction limits.
   A copied APK/root plus unresolved dependencies is partial collection.
4. Copy **exactly the files listed on SUCCESS lines** from the reported
   `DiPlayVendorDump` destination to the PC's ignored `vendor-apks/` folder.
   Base/split APKs and libraries/config candidates have source-disambiguating
   filenames; every successful file has destination, bytes and SHA-256.
   Use the reported app-external directory if public Downloads failed; do not
   assume a destination or delete an earlier export to hide a failure.
5. STOP after collection. Supply the report/files for static inspection.
   Do not start/bind Apple services, load libraries, access `/dev/*`, send
   broadcasts, change USB roles, connect an iPhone or authenticate.
   Phase 3C and runtime USB/iAP2 remain disabled.

Search/copy scope and filename patterns are documented in
[COMPATIBILITY.md](COMPATIBILITY.md#phase-3b5-appleusb-stack-collection-build).
Real-car collection has not run on the development PC, so exact installed file
availability and destination paths require the operator's report.

Phase 3B.5 development validation:

- `:mobile:assembleDebug` passed; identity
  `0.2.12-api22-phase3b5-apple-stack-export`, minSdk 22, packaged
  `armeabi-v7a`, `arm64-v8a`, `x86_64`; API 22 v1 and v2 signatures verify.
- Focused Phase 1/2/3A/3B/network/export regressions passed:
  **271 shared + 190 common tests**, zero failures/errors/skips.
  Includes **26 new collector cases** on Robolectric SDK 23/28:
  exact base/splits, metadata and public manifest actions, missing/restricted/vendor
  API failures, directory/APK libraries, direct-only dependencies, architecture
  mismatch, absolute/scoped dependency paths, 32/64-bit both-endian ELF mapping,
  malformed offsets/unterminated strings, traversal/device-path rejection,
  unresolved configs, hashes/bytes, no config-content logging, temporary cleanup,
  and no service/system-service/receiver/broadcast operations.
  Existing lazy-launch/cache/network/export suites remain green.
  The Phase 3A Settings test permits only the specifically named collection button
  containing "USB"; USB connection controls remain forbidden.
- `:shared:assembleDebugAndroidTest` passed. Instrumentation execution and actual
  API 22 file readability/manifest behavior remain real-device checks, not executed
  here. This is a focused-suite result, not a full-suite claim.
- APK: `mobile/build/outputs/apk/debug/mobile-debug.apk`, 9,477,649 bytes;
  SHA-256 `a82976377d9a81abf19da80bdd1fdfe92d9c8b152ee727a1efe0ed2d7945d08e`.
  Editor checks and `git diff --check` passed. No real Apple-stack export files
  have been created by running the collector on this PC.

### Phase 3B.5b Apple implementation discovery procedure

Phase 3B.5 real-car results: `com.neusoft.appleservice` returned
NameNotFoundException, both named Apple JNI libraries were not found,
`/system/vendor/lib64` / `/system/lib64` were readable, and no files were collected.
Do not treat the old names as the active implementation, or a 64-bit-only search
as complete discovery. The Settings DEX contains Java wrappers with unconditional
class-initializer native loading, not a self-contained/manifest-registered Apple
stack; see [the static findings](COMPATIBILITY.md#phase-3b5b-read-only-apple-implementation-discovery).

1. Install `0.2.12-api22-phase3b5b-apple-discovery` on the parked Okavango.
   Check launch/general Settings/pause/resume and Phase 3A as before.
   No inventory/export should run until explicitly requested.
2. Press **Phase 3B.5b Apple implementation discovery** once. Wait for the
   existing vendor export report above to finish. Broad candidate APKs may be large;
   this is not the older exact-name collector or a USB/Bluetooth test.
3. Save the diagnostic report, preserving candidate packages/version/components/
   actions, installed and readable APK sources/splits, every 32/64-bit and
   package-native directory, matching native filenames, aliases, failures and
   zero/partial results. Filename matches are not confirmed implementations.
4. Copy only the **SUCCESS** files from their exact reported `DiPlayVendorDump`
   paths to the PC's ignored `vendor-apks/` folder. Preserve report source,
   destination, size and SHA-256. Read-denied/directory/non-file/symlink failures
   are not instructions to change permissions or root/bypass the unit.
5. **STOP after inventory/export**. Do not open the stock CarPlay dialog to test
   missing libraries, bind/start services, send broadcasts, access `/dev/*`,
   change USB roles, send Bluetooth commands, connect an iPhone or authenticate.
   Supply the collected report/files for PC static inspection before any
   further hardware interaction. No Phase 3C.

Phase 3B.5b development validation:

- `:mobile:assembleDebug` passed: packaged identity
  `0.2.12-api22-phase3b5b-apple-discovery`, minSdk 22 and native ABIs
  `armeabi-v7a`, `arm64-v8a`, `x86_64`. API 22 v1 and v2 signing verified.
- Focused existing Phase 1/2/3A/3B/network/export regressions passed:
  **271 shared + 218 common tests**, zero failures/errors/skips.
  Includes **28 new discovery cases** on SDK 23/28 covering every requested
  package/native term case-insensitively, differently named implementations,
  APK-name/disabled-component/action-only/system-package/archive-component matches,
  manifest tracking, parser event limit, exact six 32/64-bit locations,
  package-native directories, filename-only nonrecursive matching, ordinary-file
  copying/hash verification, no class/ELF interpretation, duplicate-source handling,
  device/unavailable directory rejection and forbidden APK-source rejection before
  resource/archive access, partial/denied/vendor framework failures,
  and no service/Bluetooth/system-service/receiver/broadcast/intent-resolution work.
  Existing lazy-launch/cache/network collectors remain green.
- `:shared:assembleDebugAndroidTest` passed; no device instrumentation or
  actual Phase 3B.5b head-unit inventory/export was executed here.
  These are focused regressions, not a full-suite result.
- APK: `mobile/build/outputs/apk/debug/mobile-debug.apk`, 9,527,524 bytes,
SHA-256 `fea66a1fe635d6061e2a3f8845bad7a6888e8eb7f475a143e09107eeea03ca8e`.
  Editor checks and `git diff --check` passed; vendor APK inputs remain local,
  ignored/untracked. Exact car exports await the operator's report.

### Phase 3B.2 APK export and earlier service procedure

Manual APK export utility (historical APK collection step): press
**Export vendor Bluetooth APKs** in the Phase 3B.2 section. It resolves
`com.neusoft.geely.btphone.nf` and `com.nforetek.bt` through ApplicationInfo.sourceDir,
copies base APKs only, verifies size and SHA-256, and displays separate success/failure.
On API 22 the install-time storage permission permits an attempt at
`Download/DiPlayVendorDump/btphoneNF.apk` and `Bluetooth-GocBtAPI.apk`; if public writing
fails, the error is visible and the app external-files directory is tried. No runtime
permission prompt/root/service/system modification is used. Save the report and copy
both files to the PC using the exact displayed paths. Local APKs have now been audited:
SPP is a no-op implementation and its destroy hook affects the shared vendor singleton.
The operator chose to keep live SPP binding disabled; see COMPATIBILITY.md for the
verified mappings and cached Bluetooth getter alternative. Partial copies
are removed and verified copies are published only after verification.

Export utility validation: `:mobile:assembleDebug` passed; focused shared Phase 1/2/3A
and Bluetooth/iAP2 regressions passed (271 cases); common phase/network regressions
including APK export passed (116 cases, including 10 export cases on SDK 23/28).
API 22 instrumentation APK compilation passed; no connected car/device execution.
Packaged minSdk remains 22 and `armeabi-v7a` remains present.

Development validation: `:mobile:assembleDebug` passed. Final focused Phase 1/2/3A/3B/
3B.1/3B.2 selection passed **271 shared + 106 common tests**, zero failures/errors/skips.
The 22 SDK-parameterized NForetek tests cover permissions/export restrictions, zero-flag
explicit bind/descriptor/unbind, false/security/null/disconnect results, timeout/late
callbacks, stalled IPC isolation, pause cleanup, non-initializing/non-invoking class
metadata and class-loading failures. API 22 instrumentation (manual inspect only, no
automatic bind) compiled successfully. Packaged APK is minSdk 22 with `armeabi-v7a`,
`arm64-v8a`, `x86_64`. No real vendor APK or device is available here, so actual service
permissions/descriptors/methods/bindability are not yet known. Complete full-feature
suites were not rerun in this step; the documented Phase 3B.1 gate-related failures
remain outside this focused validation.

Install `0.2.12-api22-phase3b2-nforetek-discovery`; confirm normal safe launch and Phase 3A.
Keep Geely Bluetooth in its normal vehicle-UI state; never try to enable Android `CAR_BT`.

1. Open **Phase 3B.2 NForetek service diagnostics -> Inspect vendor Bluetooth services**.
   Wait for **Inspection complete**. Save the diagnostic report before testing any bind.
2. Record exact components/process names, exported/enabled flags, required permissions,
   protection levels/granted state, APK paths, DEX interface/Stub/Proxy names, loadability,
   public method signatures and all class/permission failures. No automatic bind occurs.
3. Press **Test read-only service bind** and select **one** eligible service. Only inspected,
   exported, enabled, permitted known components appear. The explicit bind uses flags=0;
   it does not auto-create a stopped service. If none qualify, do not bypass permission
   or export restrictions. Reinspect after returning from another app because pause
   cancels the model/bind.
4. Wait up to five seconds. Save the report with `bindService` return, connected component,
   Binder class, exact descriptor, local-interface metadata, disconnect/error/timeout and
   unbind result. Capture `DiPlayPhase3B2Device` logcat if available. Test another service
   separately if appropriate; prioritize `NfServiceBluetooth`, then `NfServiceSpp`, then
   the Geely manager. Do not pair, scan or open SPP/RFCOMM.
5. If binding returns false or times out, record whether the vehicle UI was open and
   Bluetooth ON/OFF; do not start the vendor service or guess required intent extras.
   A stopped service cannot be established with the chosen no-auto-create test.
6. Report any unexpected vehicle-UI effects and stop further binds. `onBind`/`onUnbind`
   executes vendor lifecycle code even though DiPlay sends no Bluetooth controls.

Use collected method/type names only as evidence. `Spp` naming is not proof of raw-byte
access, supported UUIDs, iAP2 compatibility or ordinary-app permission. `BtManagerService`
delegation cannot be inferred from naming; a supplied readable vendor APK may be required
for further interface/bytecode investigation. No Phase 3C or control commands are authorized.

### Phase 3B.1 vendor-discovery hardware procedure

Safe startup is now confirmed on the Okavango. Standard Android `CAR_BT` remains disabled
while vehicle `GEELY_BT` is active; do not attempt RFCOMM/iAP2 using it.
Install `0.2.12-api22-phase3b1-vendor-discovery` and verify normal launch/Settings and
Phase 3A still work with no automatic Bluetooth/vendor inventory.

1. Optionally manually Refresh the existing Phase 3B diagnostics to retain the Android
   service/adapter comparison in the exported report.
2. Open **Phase 3B.1 Vehicle Bluetooth investigation** and press **Start read-only vehicle
   Bluetooth investigation**. Wait until it says **Observing for 30 seconds**.
3. Within that window, open the Geely vehicle UI and toggle Bluetooth OFF then ON.
   Listening continues during activity pause; it ends automatically. Do not scan, connect,
   change Android settings, pair through DiPlay or invoke unknown components.
4. Return to DiPlay. Check the completion line; save the existing diagnostic report.
   If the activity was destroyed, the report will show cancellation (or be lost after
   process death); rerun manually. Duplicate Start presses during a run do not restart it.
5. Capture exact package/application names, APK/library locations, exported component
   class/process names, permissions/provider authorities, matching running services,
   readable allowlisted properties, subscribed action names, observed actions/timing/state
   numbers/extra keys, and all access failures/truncation notices. Note separately which
   UI toggle occurred at each time and whether GEELY_BT remained discoverable.
6. Repeat with vehicle Bluetooth OFF to compare service inventory, then ON if needed.
   Reports contain component/package metadata; review before sharing.

No public wildcard-broadcast receiver or complete Binder/property enumeration exists.
Protected/private/non-manifest vendor events may be invisible; exported components may
still require signature permissions. An empty report is not evidence of no vendor layer.
Logs use `DiPlayPhase3B1Device` for inventory/receiver lifecycle and failures.
The next milestone is identifying the stock integration from these names, not invoking it.
No Phase 3C work is authorized.

Phase 3B.1 development results:

- `:mobile:assembleDebug` passed; packaged label is
  `0.2.12-api22-phase3b1-vendor-discovery`, minSdk 22, ABIs `armeabi-v7a`,
  `arm64-v8a`, `x86_64`.
- Final focused Phase 1/2/3A/3B/3B.1 regressions: **271 shared + 84 common tests**
  passed, zero failures/errors/skips. Includes 12 vendor-inventory cases across API 23/28:
  inert constructor, exported metadata, receiver-action parsing/relative class names,
  secret-value omission, timeout/destruction cleanup, duplicate Start, failure handling
  and property/name filtering. All 26 lazy-launch cases remain passing.
- Complete suites were also run: shared **685 tests / 1 failure**, common
  **566 tests / 42 failures**. These expect intentionally disabled legacy-build surfaces:
  BYD startup recovery (1), BYD Settings reconnection (3), BYD vehicle Settings (24),
  hotspot boot controls (2), full location-reporting Settings (5), system-bar Settings (5)
  and the disabled USB host activity (3). For example, USB tests get
  `NameNotFoundException: Disabled component: CarPlayHostActivity`; the BYD recovery test
  expects a command from `onAppOpened`, which already returns when the vendor gate is
  false. Those gates/manifests predate this investigation and were not enabled to make
  full-feature tests pass. This is not a green complete-suite claim.
- API 22 instrumentation, including the new manual-inventory smoke test, compiled.
  No device is connected here, so actual API 22 enumeration/broadcast delivery remains
  a real-car check. Robolectric cannot run API 22.

### Historical safe-startup milestone (now hardware confirmed)

Install `0.2.12-api22-phase3b-safe-startup` on the parked Okavango. Launch with vehicle
Bluetooth off, then on in the Geely vehicle Settings UI. Do not use normal Android
Bluetooth Settings to infer vehicle state. Home, general Settings, About, export and
pause/resume must work before any Phase 3B action. The Bluetooth section stays "not sampled";
scan/RFCOMM buttons and actions are disabled pending confirmed real-hardware launch.
Phase 3A networking must behave as before.

Only then, optionally press **Refresh Bluetooth diagnostics**. It reads the Android
BluetoothManager once (no default-adapter fallback), distinguishes service availability,
adapter presence/enabled state and bonded-device visibility, and reports a possible
vehicle/Android mismatch without claiming to observe the MCU. Null/restricted/throwing
Android APIs must show diagnostic failures without stopping the activity. No scan
receiver is registered and no transport or adapter-enable work is performed.
Save a report; for a crash collect `adb logcat -b crash` or the AndroidRuntime exception
from regular logcat if available. The exact original exception is not yet confirmed.
Do not run RFCOMM or proceed to Phase 3C before successful launch is reported.

Safe-startup development validation: `:mobile:assembleDebug` passed. The full focused
Phase 1/2/3A plus Bluetooth/iAP2 regression selection passed **271 shared + 72 common
tests**, with zero failures, errors or skips. This includes 26 SDK-parameterized lazy
startup/manual-failure tests, nine Bluetooth transport/receiver tests and the Settings
safety-gate test. Transport tests use mocks only; no radio test was performed.
`:shared:assembleDebugAndroidTest` compiled successfully. No Android device was connected,
so API 22 instrumentation execution and real Okavango launch remain unverified.
Packaged APK metadata confirms `0.2.12-api22-phase3b-safe-startup`, minSdk 22 and
`armeabi-v7a`, `arm64-v8a`, `x86_64`. Phase 3A implementation is unchanged.

### Historical device-test steps (not enabled in the safe-startup APK)

These steps document the prior transport build only. Do not perform them during the
current launch milestone; the unit tests exercise mocked transports without contacting
an iPhone or the head unit.

On the parked Okavango, after transport testing is separately authorized:

1. Install the debug APK and confirm `0.2.12-api22-phase3b-device-test`.
   For the launch-regression retest, first leave Bluetooth off/unavailable and open the
   app, general Settings, About and diagnostic export without pressing a Phase 3B action.
   All must launch without accessing Bluetooth. The Phase 3B section remains "not sampled".
   Background/resume the app and repeat; Phase 3A diagnostics should behave as before.
2. Enable Bluetooth using the car settings. On the iPhone, open **Settings -> Bluetooth**
   and pair with the car using its normal pairing UI. Do not initiate CarPlay.
3. Open **DiPlay Settings -> Phase 3B Bluetooth diagnostics -> Refresh Bluetooth diagnostics**.
   Confirm adapter presence/enabled state and the correct bonded iPhone name/MAC.
   Local MAC may be a placeholder; cached UUIDs can be unknown without indicating failure.
4. Optionally tap **Scan for devices** while iPhone Bluetooth settings remains open.
   Scan is bounded to 15 seconds. Check discovered names/MACs; no automatic pairing occurs.
   On API 22 no runtime CONNECT/SCAN prompt is expected. API 23-30 scan requests location;
   API 31+ scan requests CONNECT/SCAN while RFCOMM needs CONNECT only.
5. Tap **Test RFCOMM connection** and explicitly select the bonded iPhone.
   Confirm secure RFCOMM uses `00000000-deca-fade-deca-deafdecacafe`. Connect is limited to
   12 seconds. A successful connection runs pre-auth framing for at most 10 seconds
   (a 10.5-second socket-close watchdog also unblocks stalled I/O).
6. Record RFCOMM result, sent/received bytes, link state and first control-message ID.
   `linkReady=true` proves synchronization, not authentication. Sending a marker with no
   response proves only a write. The test stops at `0x1d00` without `0x1d01`, or an auth
   request such as `0xaa00`/`0xaa02` without certificate/challenge responses.
7. Pause the app during a scan/connect/probe and confirm cleanup. A completed test has
   **Socket connected: no**, while retaining its result and byte counters.
8. Save the diagnostic report; capture `adb logcat -s DiPlayPhase3BDevice` where available.
   Reports deliberately contain Bluetooth names/MACs; review before sharing publicly.

Bluetooth diagnostics are now fully lazy: Refresh performs one read; there is no
periodic adapter/bonded-device sampling. Merely rendering Settings does not initialize
Bluetooth. Receivers are registered only for a manual scan and unregistered on
completion, failure, timeout or pause. A vendor runtime/linkage error must be shown as
FAIL without terminating the activity. API 23/28 Robolectric launcher tests substitute
missing-service, null-adapter, disabled, throwing adapter-state and throwing bonded-device
stacks and verify zero Bluetooth service/adapter access through create/start/resume,
Settings and pause/resume. Manual Refresh tests cover security, runtime and linkage
failures, missing service, preserved partial observations and vehicle-state uncertainty.
API 22 itself still needs a device retest.

If RFCOMM fails, record the exact SDP/connect/timeout reason and cached service UUIDs.
No hidden channel-number or insecure fallback is attempted. The current next milestone is
successful safe startup on the real head unit. RFCOMM/pre-auth testing remains withheld.
Only after safe launch, separately authorized transport evidence and explicit approval should
identification/authentication or Phase 3C be considered.

## Custom stream resolution

In the home settings and the in-session menu, confirm the accepted range is **30–160%**. Try 160%, cancel an edit, save an unrelated setting, and reconnect; the exact saved percentage must survive. Enter 161% in the numeric dialog and confirm it stays open with an error. Reset must only change the draft to 100% until Save/Apply is selected.

On a parked head unit, test a supported setting above 100% with Default, Smaller and Large icon/text sizes. Confirm the display diagnostics show the requested and effective resolution, actual decoder size/rate/alignment support, negotiated canvas and video output. An unsupported enlarged canvas must fall back before advertising it to the phone, with a notice; resolution falls back to 100% if removing the Smaller-size enlargement is insufficient, and 100% is saved for later connections. Compare picture sharpness, touch mapping, audio and sustained video smoothness. Decoder metadata and automated tests cannot establish performance on real hardware, so the contributor's supersampling result needs a signed vehicle retest.

## Android 10 Wi-Fi scan recovery

On a parked DiLink 3 head unit with network ADB already authorized, compare hotspot/P2P wireless CarPlay with the car's Wi-Fi client disconnected. Confirm that a supported framework reports `Wi-Fi connectivity scans paused=true` and check whether the contributor's periodic stutter is resolved. Close the session and confirm station scanning/reconnect returns. Trigger a full controller retry or replace the controller while the prior restore is delayed: an old cleanup must never enable scans after the replacement reports its pause.

Temporarily make the authorized ADB connection unavailable during teardown, then restore access. Confirm cleanup retries while the app remains open, and confirm a subsequent session's pause survives any pending old retry. Interrupt the app after suppression, reopen it, and verify the recorded restore is recovered; a force-stop cannot restore until the app next runs. With **Same LAN / Existing Wi-Fi**, confirm no pause is reported and normal station reconnect/roaming still works. On Android versions other than 10, or without existing ADB approval, there must be no suppression or approval prompt. These hardware checks remain necessary after the automated ownership/recovery tests pass.

## Diagnostic export without a picker

On an Android 9 emulator or head unit without a document picker, open **Settings → Diagnostics → Save diagnostic report**. Confirm that no picker is required and that the success dialog shows a TXT file under `Android/data/<package>/files/diagnostic-reports/`. Read that file and verify the app/device information and UTF-8 text. Use **View** and **Share** from the confirmation. Export twice and confirm that the reports have distinct file names and the earlier file is not overwritten. On Android 10+, normal exports should still use `Downloads/DiPlay`; **Choose save location** should still open a working picker, and cancelling it should not export anything. If external storage is unavailable, confirm that the private in-app fallback can still be viewed and shared. Do not disable system components on a car to simulate the missing-picker case; use an emulator for that simulation.

For channel memory, connect until authenticated CarPlay renders, disconnect and reconnect without changing the car's Wi-Fi association. Look for `remembered saved` followed by `remembered first`. Report absent events; creating a hotspot alone is insufficient.

Include head-unit model, DiLink/Android, iPhone/iOS, wired/wireless, app version and exact steps. Do not post credentials or unreviewed personal information. See [compatibility](COMPATIBILITY.md) for remaining limitations.

## Preferred Wi-Fi Direct channel

- In **Settings → Connection setup → Wi-Fi Direct**, confirm **Preferred channel: Auto** on a fresh install. Select channel 149 and Cancel; Auto must remain selected. Select 149 and Save, reopen the chooser and restart the app to confirm it stays saved.
- Disconnect/reconnect after saving. Check `channel preference=149 frequencyMHz=5745`, `create mode=PREFERRED_CHANNEL`, and `requestedMHz=5745 actualMHz=5745 matched=true`. An unsupported channel or a different actual channel must report an error instead of silently falling back. Select Auto to restore automatic startup.
- Compare Auto and manual choices with the car already joined to Wi-Fi. A manual choice must override station alignment and any remembered automatic channel. Successful manual sessions must not replace the remembered automatic configuration.
- Switch to built-in hotspot and USB. The channel chooser must be hidden for built-in hotspot, and neither connection may apply the Wi-Fi Direct preference. Returning to Wi-Fi Direct must restore the saved choice. Saving a channel during a connection must leave that session running and apply the change to the next connection.

## Wireless and USB car data

- On wireless, with **Report location to iPhone** on, confirm the Bluetooth bootstrap identifies with `location=false vehicleStatus=false`, then the Wi-Fi tunnel receives its own `start-location-information` before its first `location-information`. There must be no location output on Bluetooth and no unsolicited continuation from it.
- On USB, start a fresh wired session rather than plugging into an already active wireless session. Confirm the USB iAP2 link receives StartLocationInformation and carries all location updates itself.
- With **Car battery for the iPhone** on, confirm on wireless that the Bluetooth bootstrap does not advertise Vehicle Status and that the Wi-Fi tunnel receives its own `0xa100 start-vehicle-status` before sending `0xa101 vehicle-status`. On USB, the single wired iAP2 link must receive `0xa100` and send `0xa101`. A missing or stale battery reading must leave Vehicle Status undeclared rather than sending invented values.

## Video while parked

- Use a plain HTTPS MP4 or HLS item that supports AirPlay, such as one sent from Safari. DRM-protected services and apps that disable AirPlay are not acceptance tests.
- Test fresh wireless and fresh USB sessions separately. Confirm `/info videoInCar=true`, SETUP negotiates `videoPlayback`, the event channel becomes ready, and the latest P-state reports `delivery=SENT` even if it was first `QUEUED`.
- Confirm the video settings stream and remote-control stream are accepted, `requestUI videoplayback:` opens the player, and the player log reports a validated internet network before loading the URL.
- Shift out of P and confirm availability becomes false and the car player closes immediately. Disable ADB or make the gear unreadable and confirm the same fail-closed behavior.

## Rotation during reconnect

On an Android device that supports screen rotation, connect until CarPlay renders, then rotate from landscape to portrait and back while the connection is rebuilding. Repeat in both directions, including several quick rotations and a 180-degree turn. Let the device settle after the last rotation and check that the CarPlay picture has the correct aspect ratio and that touch targets match the displayed controls.

In the diagnostic report, the next `Starting CarPlay controller at` and `Display request` must use the latest settled dimensions, including a `Display updated while handshake is reset` event that arrived during teardown. A queued size change must settle before startup; cancelling it by returning to the accepted size must still resume the connection. On a BYD head unit, also open and close the camera window to confirm that a shrink/restore within the original window keeps the existing CarPlay session.

## Location reporting

With the car parked, open **Settings → Location → Report location to iPhone**.

- On a fresh installation, the switch is off. Enabling it requests precise location if needed; denying the request or granting only approximate location leaves it off.
- Grant precise location, enable the switch, then reopen Settings to confirm the saved state. With no connection running, the setting applies to the next connection.
- During wired and wireless CarPlay, enabling or disabling the switch reconnects the session. When enabled and requested by the iPhone, check for `start-location-information` and `location-information` in the DiPlay diagnostics; on wireless, also verify that reporting continues after the Bluetooth-to-Wi-Fi handoff.
- Disable the switch and confirm the next session does not advertise location reporting. These checks verify the accessory reporting path; they do not establish which inputs iOS uses in each fused location result.

## Advanced vehicle data

- Expand **Settings → Location → Advanced vehicle data**. Confirm a fresh install uses **Default mode · verified on DiLink 5.0 head units** and shows the battery, wheel-speed and parked-video switches without a field probe.
- In Default mode, tap **Check ADB access** and record the battery, speed and gear it shows. With CarPlay connected, turn on a switch whose data cannot be read: CarPlay must stay connected and the page must show what cannot be read.
- Select **Legacy head-unit detection · tested on controller 13 / DiLink 3.0**. Approve the key if the car asks; the same action must continue into the read-only field probe. With “Always allow” ticked, the page must not say the car allowed DiPlay only once. A failed probe must leave Default mode selected.
- Reopen Settings, restart DiPlay, change gear and reconnect CarPlay. The successful probe, resolved fields and enabled battery/wheel-speed/video switches must remain saved without another tap, even when the current firmware metadata differs.
- Switch back to Default mode and confirm the saved legacy probe remains available when Legacy mode is selected again.
- Turn ADB off temporarily. Saved functions and switches must remain visible; turning ADB back on allows automatic validation. Two READY-but-unreadable validations trigger one automatic re-probe, while an incomplete re-probe preserves the previous snapshot and shows manual retry.
- Press the first probe, authorization and retry controls after scrolling down the page. Progress and results must remain at the same scroll position rather than jumping to the top.
- Scroll down Settings, open CarPlay, then return to Settings (Back to DiPlay or the three-finger gesture). The page must keep its scroll position.
- When the BYD navigation card is available, confirm **Dashboard song** exists there exactly once and does not appear in Advanced vehicle data. Without that card, it must appear once under Advanced vehicle data, and turning it on must show the CarPlay song on the dashboard. With **Song only when it changes** on, a new song must show for about 5 seconds and the card must then empty; pause and play alone must not show it again.

## Hotspot and vehicle-settings interaction

- On a supported BYD unit, choose the built-in car hotspot. Automatic hotspot startup stays off on a fresh installation. Enable it explicitly and approve the ADB prompt; the setting saves only after DiPlay confirms its own required permissions. Denial must leave it off. Choosing Wi-Fi Direct hides the hotspot card and preserves its saved preference.
- Expand Advanced vehicle data. Battery, wheel-speed and parked-video switches must appear only in that section, with unavailable legacy fields hidden. The hotspot card must not provide duplicate switches that bypass the selected mode.
- Start a user vehicle check or probe, then try the hotspot switch before it finishes. A second authorization flow must not start. After the vehicle operation finishes, the hotspot switch becomes usable again.
- Start hotspot authorization while Advanced vehicle data is expanded. Mode and vehicle choices must stay disabled until it completes; an automatic saved-field validation must resume afterwards without another authorization prompt.
- During a pending battery preflight, let the hotspot eligibility check finish and redraw Settings. A valid vehicle result must still apply the requested reconnect once; an unreadable result or ADB failure must keep the existing connection.


## Dashboard song only when it changes

- Enable Dashboard song and Song only when it changes. A new title/artist appears for five seconds, then the card becomes blank/stopped. Pause/play and duplicate metadata must not reopen the card or extend its window.
- During that window, turn off Song only when it changes. The song must remain visible after the old five-second deadline. Turn off Dashboard song instead; the old timer must not recreate a blank card. Disable/re-enable and change tracks quickly to confirm earlier timers cannot dismiss a newer song.
- Show a wheel zoom/volume note while a song changes. The note must stay visible for its own duration, then restore the latest song if its five-second window is still active, or a blank card otherwise. A note from a disconnected session must not affect a new session.
- On supported HUD firmware, confirm HUD title/lyrics continue to follow the actual phone metadata while the dashboard card is blank or showing a wheel note. The updated upstream blank-card behavior still needs a vehicle retest.

## Steering-wheel dashboard map zoom

- On a fresh installation, wheel zoom is off. Enable the key service explicitly and configure the mode/zoom keys with the car parked. Confirm leaving settings, disabling wheel zoom, or waiting ten seconds cancels a pending key assignment. Subsequent hardware keys must keep their ordinary action.
- With the dashboard map visible, test both toggle and five-second modes. The mode key selects zoom, the zoom keys change the map, and pressing the mode key again restores volume. With the map hidden, stopped, or configured as a turn card, keys must keep their ordinary car action.
- Disconnect/reconnect CarPlay and disable/re-enable wheel zoom while zoom is active. Zoom must stay off until another mode-key press. Repeat while the CarPlay screen moves to the background and while the instrument map/card closes.
- Hold a volume key while a call starts or ends, the zoom timer expires, the map disappears, or the feature is disabled. Confirm every press has its matching release, with no stuck volume action or stray car key action. Test CarPlay and BYD Bluetooth calls; audio-mode call detection still needs firmware-specific vehicle confirmation.
- Recheck the current cluster/HUD controls, Same LAN connection, and diagnostic-report export after the update.

## Live dashboard content switching

- With the car parked, switch among Map, Turn card, and Map with the iPhone's turn card. Confirm the live switch keeps the phone connected and wheel zoom is available only for a delivered, visible map.
- Hide/pause the cluster map, choose different content, then resume it. The pause must remain in effect until resume, which shows the latest selection. Recreate the cluster stream to confirm the selection is reapplied after SETUP, with no stale wheel eligibility before delivery. Replace/reconnect the phone after a successful live change and confirm it keeps the selection; failed or stale switches must not overwrite the last accepted choice.
- Interrupt the event channel during a switch. The settings caller must fall back to reconnecting for the latest selection; rapid changes or a replacement controller must not trigger an old reconnect. Recheck the custom overlay and DiLink 5.1 reconnect paths.

## Steering-wheel CarPlay joystick

- On a fresh installation the joystick is off. Turn it on (the key service as for zoom) and check the joystick, previous/next, select and mode keys with the car parked.
- With CarPlay connected, the media key turns the joystick on (toast with the keys, "Joystick on" on the dashboard where the song shows). Previous/next and the volume roller move CarPlay's focus on the main screen (lists, the Maps side panel), play/pause selects and the custom key goes back. The media key turns it off and every key has its usual action again; the custom key switches the map zoom again.
- With "Joystick turns off by itself" on, the joystick ends 15 seconds after the last press and when a route starts; with it off, it stays on until the media key. Disconnect CarPlay or turn the setting off while it is on: it must be off afterwards.
- Without a CarPlay session the media key opens BYD media. During a CarPlay or Bluetooth call every key keeps its usual action, and no press loses its release.

## Phase 3B.6 — Native Geely CarPlay static audit

The audit is PC-side manifest/DEX inspection only. It found that the setting
widget's `CarPlaySwitch` dispatches to `SettingWidgetService.turnToCarPlay`,
updates `Settings.System["CarplayMode"]`, and calls the Apple native wrapper.
That wrapper unconditionally requests `AppleCore_jni`; its Apple-private
counterpart requests `ApplePrivate_jni`. Neither library is in the inspected
APK payloads, and the prior on-car search did not find the expected
`com.neusoft.appleservice` package. The stock Java code contains USB/UEvent and
`usbncm0` hooks, but no complete native wireless transport or working
NForetek/GEELY_BT bridge was established. The launcher receiver handles
CarPlay state/UI broadcasts; it is not the transport. Full findings, scope
limits and the hard-key/AutoKit caveats are in
[COMPATIBILITY.md](COMPATIBILITY.md).

### Safe next step on the Okavango

1. With the vehicle parked, run the existing manual **Phase 3B.5b Apple
   implementation discovery** collector once. Save its report and preserve
   the exact candidate package/component metadata, APK export paths and
   hashes, every package `nativeLibraryDir`, both 32/64-bit library roots,
   aliases, failures and zero/partial results.
2. Copy only its verified **SUCCESS** exports from the reported paths into
   ignored local `vendor-apks/` and continue static inspection on the PC.
   Treat package names and library filenames as candidates, not proof of a
   working transport. This read-only inventory is to resolve the remaining
   package/ABI/path uncertainty, not to activate CarPlay.
3. Stop after collection. Do not open the stock CarPlay dialog or click the
   `CarPlaySwitch`: the Java wrapper's class initialization loads the missing
   native library without an availability guard. Do not toggle `CarplayMode`
   or other flags, bind/start Apple or NForetek services, send broadcasts,
   load vendor libraries, use `NfServiceSpp`, change USB roles, connect or
   authenticate an iPhone, or begin Phase 3C.

No runtime APK or device test was produced for this static-only milestone.

## Phase 3B.7 — Complete native Geely CarPlay stack discovery

This was a PC-side, static-only follow-up. No ADB device was attached, no
package/library was executed, and no mode, service, broadcast, USB role or
iPhone state was changed. The examined local APKs still show Java Apple
wrappers and embedded service code, but no Apple JNI libraries or complete
native transport. The previous installed-package query did not resolve
`com.neusoft.appleservice`.

The Phase 3B.5b collector is not a complete raw-partition inventory: it does
not recursively scan all `/system/app`, `/system/priv-app`, `/vendor/app`,
framework JAR or vendor framework contents for DEX strings. It can therefore
not close the remaining "renamed/optional payload elsewhere" question by
itself.

### Safest read-only collection on the actual head unit

When the parked head unit is available, first record the model, product/region
metadata and exact build fingerprint using read-only means. Then perform a
read-only inventory of ordinary files under:

- `/system/lib`, `/system/lib64`, `/vendor/lib`, `/vendor/lib64`,
  `/system/vendor/lib` and `/system/vendor/lib64`;
- `/system/app`, `/system/priv-app`, `/vendor/app`;
- `/system/framework` and `/vendor/framework`;
- installed packages' `nativeLibraryDir` and their APK/split APK paths.

Do not recurse into `/dev`, `/proc` or `/sys`; do not execute or load candidate
files. For each ordinary readable candidate, record its full source path,
export destination, byte size and SHA-256. Preserve unreadable/not-found
results instead of treating them as evidence of absence. Search APK/JAR DEX,
manifest and resource strings as well as native filenames/ELF strings for the
Phase 3B.7 terms. The current collector's installed-package and selected
library-root results may be retained as a subset, but do not call them a
complete partition scan.

On the PC, compare a verified build known to include native wired CarPlay from
Geely/ECARX E01, preferably VX11/Okavango-family, against the collected current
build. Require build/model/region metadata and the readable system/vendor
app, framework and native payload; do not download an unverified image
automatically. This offline comparison is the safest next step toward finding
the missing OEM implementation.

Stop after read-only collection and offline inspection. Do not activate
`CarPlaySwitch`, change `Settings.System["CarplayMode"]`, call
`AppleInterface.setDefaultMode()`, start/bind Apple or CarPlay services, send
broadcasts, load JNI/vendor libraries, change USB roles, connect/authenticate
an iPhone, use `NfServiceSpp`, or begin Phase 3C.

## Phase 3C.3C — Passive MFi/I2C inventory

Run the desktop checks and build:

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.PassiveMfiI2cInventoryTest' --tests 'com.shilapi.xcertplay.Phase3BDeviceSettingsTest'
.\gradlew.bat :mobile:assembleDebug
```

Install the resulting debug APK on the E01, open **Settings**, and scroll to
**Phase 3C.3C — Passive MFi/I2C inventory**. Confirm it initially says
`not run`; press only **Run passive MFi/I2C inventory**. Review the displayed
`/dev/i2c-*` node/access metadata, the two I2C sysfs inventories, and any
permission/read errors, then use the existing **Save diagnostic report** action
to export it. Do not press the Phase 3B.8 scanner or run `MfiDeviceScanner`;
do not connect a phone or proceed to active probing/authentication. The inventory
is specifically read-only and manual, and generic I2C addresses are not an MFi
identification.

## Phase 3D.1 — Passive direct iPhone USB enumeration

Phase 3C.3C real-E01 results: `/dev/i2c-0` through `/dev/i2c-3` are root-only
`0600`; registered sysfs clients contain no Apple/MFi/authentication evidence.
The onboard-I2C route is deferred. Do not run `MfiDeviceScanner` or active probes.

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.PassiveUsbInventoryTest' --tests 'com.shilapi.xcertplay.Phase3BDeviceSettingsTest' --tests 'com.shilapi.xcertplay.DiagnosticExportUiTest'
.\gradlew.bat :mobile:assembleDebug
```

The debug APK is `mobile\build\outputs\apk\debug\mobile-debug.apk`.
minSdk stays 22 and `LegacyLaunchBuild.CONNECTIONS_ENABLED` stays false.
The manual action reads only `UsbManager.getDeviceList()`, existing permission
status, and cached device/interface/endpoint descriptor metadata. It does not
request permission, open a device, claim an interface, perform transfers, change
USB mode/role, invoke vendor services/JNI, or instantiate any transport or
authentication implementation. An Apple VID (`0x05AC`) is only an
**Apple USB device candidate**, not proof of CarPlay.

Real-car procedure (with the built APK installed):

1. Remove the Carlinkit dongle completely.
2. Start the Geely normally.
3. Open DiPlay.
4. In Settings/Diagnostics, first run **Scan connected USB devices** with nothing
   connected to USB port 1 and save/observe the baseline using **Save diagnostic report**.
5. Connect the iPhone **directly** to USB port 1 using a known-good data cable.
6. Unlock the iPhone.
7. Do not approve Trust or other prompts unless instructed in a later phase.
8. Run **Scan connected USB devices** again.
9. Save the diagnostic report.

Stop there. Do not proceed to Lockdown, usbmuxd, iAP2, MFi, NCM, Bluetooth or
CarPlay. The displayed/exported report includes
`PASSIVE ENUMERATION ONLY — no USB device opened or interface claimed`.
An empty list describes only this Android host-list sample; it does not prove
that the physical port supports or does not support direct iPhone connectivity.

## Phase 3D.2 — Direct USBMUX/Lockdown validation

Real-E01 Phase 3D.1 passed with one direct Apple `05AC:12A8` device, 12 interfaces,
existing permission, and a `255/254/2` multiplexor with BULK `0x04/0x85` (512-byte
packets). See [COMPATIBILITY.md](COMPATIBILITY.md#phase-3d2--direct-usbmuxlockdown-transport-test)
for the existing implementation audit and the descriptor-selection rationale.
Other vendor-specific bulk interfaces (`255/253/1`) are not multiplexors.

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.DirectUsbMuxDiagnosticTest' --tests 'com.shilapi.xcertplay.PassiveUsbInventoryTest' --tests 'com.shilapi.xcertplay.Phase3BDeviceSettingsTest' --tests 'com.shilapi.xcertplay.DiagnosticExportUiTest' :shared:testDebugUnitTest --tests 'com.shilapi.xcertplay.transport.UsbTransferCompatibilityTest' --tests 'com.shilapi.xcertplay.transport.UsbMuxFrameBufferTest' --tests 'com.shilapi.xcertplay.transport.UsbMuxIssue100RegressionTest'
.\gradlew.bat :mobile:assembleDebug
```

The injectable transport tests cover the twelve-interface layout at different
indices, incorrect/ambiguous descriptor rejection, missing permission, open/claim
failure, partial USB writes, fragmented real USBMUX/plist reads, version/TCP/query
timeouts, disconnects, valid non-mutating model query, cleanup, cancellation and
Trust-related remote errors. Android mock interaction allow-lists and compiled
dependency checks guard the prohibited operations. Existing API22 compatibility
and captured-frame regression tests remain in the targeted run. Robolectric runs
on supported SDK28/33; API22 legacy behavior is exercised through the existing
injectable compatibility backend, not an unsupported SDK22 sandbox.

The APK stays at `mobile\build\outputs\apk\debug\mobile-debug.apk`, minSdk 22.
`LegacyLaunchBuild.CONNECTIONS_ENABLED` remains false. Normal startup and opening
Settings do not open USB. Only the manual button enables this narrow test.
Permission absent reports `USB permission required — STOP`, never a permission
request. The only Lockdown request is plaintext `GetValue(ProductType)`, with no
pairing, record access, Trust mutation, session, TLS or service start.

After installing the APK:

1. Remove Carlinkit completely.
2. Start Geely normally.
3. Unlock iPhone.
4. Connect iPhone directly to driver-side USB port.
5. Open DiPlay.
6. Open **Phase 3D.2 — Direct USBMUX/Lockdown test** in Settings/Diagnostics.
7. Press **Test USBMUX + Lockdown**.
8. Do not approve a Trust prompt if one appears. Press **Trust prompt appeared — STOP**
   in DiPlay to record the observation and cancel. **Cancel transport test** also
   closes access; activity pause/destroy cancels active tests.
9. Save diagnostic report (wait for test completion/cleanup first).
10. **STOP and send report for analysis.**

The report displays
`PHASE 3D.2 TRANSPORT TEST ONLY — pairing, iAP2, MFi and CarPlay disabled`,
all attempted stages and cleanup results, plus one of the approved verdicts.
No hardware USBMUX/Lockdown success may be claimed solely from PC fake tests.
Do not begin pairing, Phase 3D.3, CarKit, iAP2, MFi, NCM, Bluetooth, AirPlay,
CarPlay, USB mode changes or vendor/AutoKit/Carlinkit work.

## Phase 3D.2A — Passive configuration/interface mapping

The real 3D.2 test safely rejected two matching flattened USBMUX candidates before
opening the device. Do not hard-code indices 6/8 or rerun the active test in this
phase. The new diagnostic reads descriptor metadata only.

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.PassiveUsbConfigurationMappingTest' --tests 'com.shilapi.xcertplay.PassiveUsbInventoryTest' --tests 'com.shilapi.xcertplay.Phase3BDeviceSettingsTest' --tests 'com.shilapi.xcertplay.DiagnosticExportUiTest' --tests 'com.shilapi.xcertplay.DirectUsbMuxDiagnosticTest'
.\gradlew.bat :mobile:assembleDebug
```

APK: `mobile\build\outputs\apk\debug\mobile-debug.apk`; debug version suffix
`-api22-phase3d2a-passive-usb-mapping`. minSdk stays 22 and
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false`.

After installation, remove Carlinkit completely, start Geely normally, connect
the unlocked iPhone directly to the driver-side USB port and open DiPlay.
In Settings/Diagnostics open **Phase 3D.2A — iPhone USB configuration mapping**,
press **Map iPhone USB configurations**, wait for the descriptor report and use
**Save diagnostic report**. Stop and supply that report for analysis.
Do not press **Test USBMUX + Lockdown**, approve Trust, change USB configuration,
or begin pairing/projection.

Compare configuration array indices/IDs, per-configuration interface indices/IDs,
alt settings and endpoint properties with the retained flattened view. The
structured USBMUX section reports all descriptor-value matches; identical
flattened entries may match several configurations. Active/current configuration
is explicitly UNKNOWN. Public API22 exposes self-powered/remote-wakeup flags but
not the complete raw configuration attributes byte; the report labels this
limitation. No current configuration is inferred from ordering or permission.
See [COMPATIBILITY.md](COMPATIBILITY.md#phase-3d2a--passive-iphone-usb-configuration-mapping)
for the static selection audit. The Phase 3D.2 selector remains unchanged pending
the real E01 mapping report.

## Phase 3D.2B — Read active USB configuration

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.ActiveUsbConfigurationDiagnosticTest' --tests 'com.shilapi.xcertplay.PassiveUsbConfigurationMappingTest' --tests 'com.shilapi.xcertplay.PassiveUsbInventoryTest' --tests 'com.shilapi.xcertplay.Phase3BDeviceSettingsTest' --tests 'com.shilapi.xcertplay.DiagnosticExportUiTest'
.\gradlew.bat :mobile:assembleDebug
```

APK: `mobile\build\outputs\apk\debug\mobile-debug.apk`, version suffix
`-api22-phase3d2b-get-usb-configuration`. minSdk 22 and
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false`.

Tests verify the exact one-byte standard GET_CONFIGURATION request and bounded
1000ms timeout, response values 1/2/3/4, permission-denied and open-failure stops,
malformed/zero-length/negative results, exceptions, detached/changed identities,
unconfigured/unknown/ambiguous configuration values, close even on failure,
getter/request/close-only interactions, prohibited dependency exclusion, manual
UI behavior and report export. Robolectric uses supported SDK28; the used Android
USB APIs are available on API22.

Real-car procedure after installing the APK:

1. Carlinkit completely removed.
2. Start Geely normally.
3. Unlock iPhone.
4. Connect iPhone directly to driver-side USB port.
5. Open DiPlay.
6. In Settings/Diagnostics > **Phase 3D.2B — Active iPhone USB configuration**,
   press **Read active USB configuration**.
7. Wait for the read/cleanup result, then **Save diagnostic report**.
8. **STOP.**

Do not run Phase 3D.2 USBMUX again yet. Do not approve Trust or start pairing,
session, authentication, NCM or projection. Report the GET_CONFIGURATION status,
returned value, matched descriptor name, scoped USBMUX candidate, Apple USB
Ethernet presence and cleanup. PC fixtures establish behavior, not the currently
active configuration on the E01.

## Phase 3D.2C1 - read-only QDrive interface-string discriminator

See the [exact native condition and read-only boundary](PHASE3D2C1_QDRIVE_VALERIA_DISCRIMINATOR.md).
QDrive uses case-sensitive **strstr** on the first alternate's iInterface
string for each interface in all configurations. A substring match returns
helper TRUE and enables its configuration-selection branch; no match enables
the separate vendor branch. This diagnostic executes neither branch.

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.QDriveBranchDiagnosticTest' --tests 'com.shilapi.xcertplay.PassiveUsbInventoryTest' --tests 'com.shilapi.xcertplay.PassiveUsbConfigurationMappingTest' --tests 'com.shilapi.xcertplay.ActiveUsbConfigurationDiagnosticTest' --tests 'com.shilapi.xcertplay.Phase3BDeviceSettingsTest' --tests 'com.shilapi.xcertplay.DiagnosticExportUiTest'
.\gradlew.bat :mobile:assembleDebug
```

APK: `mobile\build\outputs\apk\debug\mobile-debug.apk`.
Corrected C2 version suffix: `-api22-phase3d2c2-complete-interface-strings`. minSdk22, global
connections/vendor/transport gates disabled. Robolectric fixtures use SDK28;
used Android USB getters and requests are API22-compatible.

1. Remove Carlinkit.
2. Start Geely normally.
3. Unlock iPhone.
4. Connect iPhone directly.
5. Open DiPlay.
6. In Settings/Diagnostics, run only **Inspect iPhone interface strings**.
7. Wait for cleanup and **Save diagnostic report**.
8. **STOP.**

Do not approve Trust, run USBMUX or attempt either QDrive branch.
Permission absent, malformed raw descriptors, unresolved native malformed-string
behavior or cleanup failure produces an explicit UNAVAILABLE result.
PC test strings are not E01 evidence.
Phase 3D.2D is not implemented.

### Phase 3D.2C2 regression and completion semantics

The real C1 run stopped after `"PTP"` when config 2/interface 0 had
iInterface=0. Native instruction/PLT reinspection proves the ASCII output
is **zeroed** per entry: index zero and native-rejected string reads leave
empty output and allow the loop to continue. C2 fixes that stop without any
new USB operation category.

The focused command above now tests PTP -> missing index -> configuration-3
USBMUX strings -> configuration-4 USBMUX/Ethernet strings, skipping later
alternates as QDrive does. A later successfully retrieved Valeria substring
terminates immediately; complete known-empty/no-match scans prove FALSE.
Individual errors are reported, not hidden. Unresolved native malformed
memory cases continue collection but do not permit a final FALSE verdict.
The real-car procedure and **Inspect iPhone interface strings** button are
unchanged. No configuration selection, vendor request or transport follows.

## Phase 3D.2E - controlled single QDrive vendor request

See [the implementation, focused test command and controlled car procedure](PHASE3D2E_QDRIVE_VENDOR_TRANSITION_TEST.md).
This is a separate **state-changing** diagnostic, not a continuation of the
read-only string button. Nothing starts automatically. Run its read-only
preflight first, then manually confirm **Test QDrive USB mode transition**.
One `40/52/value0/index2/null/length0` request with a bounded 1000ms timeout
is allowed; no retry. Finally-close the old connection, observe for 10 seconds,
inspect fresh permitted Apple state read-only, save the report, and STOP.
No configuration setter, interface claim, bulk, USBMUX, Lockdown, iAP2, MFi,
Ethernet/NCM, AirPlay, CarPlay or Phase 3D.2F.

## Phase 3D.2G - controlled configuration5 selection

For the subsequent isolated claim/release experiment, see
[Phase3D.2I procedure and focused tests](PHASE3D2I_ACTIVE_CONFIG5_USBMUX_CLAIM_TEST.md).
It requires already-active5, one forcefalse claim/release, no bulk traffic,
and never invokes E/G automatically.

See [the configuration-only test and real-car procedure](PHASE3D2G_QDRIVE_CONFIGURATION5_TEST.md).
Separate confirmed **Select post-Valeria configuration** action: require the
already-transitioned five-configuration device, exact config5 Valeria, existing
permission and active1; select actual ID5 once, GET_CONFIGURATION, observe,
read back fresh permitted state and STOP. False setter is a STOP outcome;
true alone is not PASS. No vendor request/retry/driver detach/claim/alternate
change/bulk/USBMUX/Lockdown/NCM/projection follows.
