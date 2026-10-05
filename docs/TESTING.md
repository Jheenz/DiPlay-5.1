# Test checklist

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
`0.2.12-api22-phase3b-device-test` and projection/vehicle controls remain disabled.
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

Development validation: `:mobile:assembleDebug` and instrumentation APK compilation passed.
The focused selection passed 271 shared tests and 43 common tests with no failures/skips.
Coverage includes API 22 permission policy, API 28 adapter/bonded/scan lifecycle, API 31
permission denial, RFCOMM stream I/O, mocked hardware-mode connection success/failure,
pre-auth identification/authentication boundaries and Phase 1/2/3A regressions.
Connected API 22 instrumentation was attempted but blocked by `No connected devices!`;
API 22 Bluetooth runtime and real RFCOMM radio behavior remain hardware checks.

On the parked Okavango:

1. Install the debug APK and confirm `0.2.12-api22-phase3b-device-test`.
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

If RFCOMM fails, record the exact SDP/connect/timeout reason and cached service UUIDs.
No hidden channel-number or insecure fallback is attempted. The next milestone is real
RFCOMM plus pre-auth framing success. Only after that evidence and explicit approval should
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
