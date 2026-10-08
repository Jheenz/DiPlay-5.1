# Phase 3W.1 - Geely E01 wireless CarPlay hardware feasibility

## Verdict

**The E01's Wi-Fi AP and Classic Bluetooth hardware work individually, but
wireless CarPlay is not established and is not currently runnable from DiPlay.**
Phase 3A proved an OEM/manual hotspot and cross-device system NSD/mDNS on the
E01. Phase 3B proved the separate NForetek vehicle Bluetooth service can report
cached Bluetooth state. Neither proves the Bluetooth discovery/iAP2 transport
needed to initiate wireless CarPlay. The installed NForetek SPP implementation
is no-op, the standard Android Bluetooth adapter was separately observed
disabled, production startup remains gated off, and no authorized E01 MFi
provider is established.

The existing E01 work did not identify the Wi-Fi chipset/driver, supported
bands, SoftAP band, or simultaneous STA+AP capability. A manually triggered
snapshot has been added for the missing facts that public API22 framework calls
can report. It does not probe or change radio state and cannot answer those
hardware/OEM questions completely.

## Capability matrix

| Capability | Status | Evidence and exact limit |
|---|---|---|
| Platform identity | **CONFIRMED** | Real E01 is reported as `alps E01`, MT6735, Android 5.1/API22, firmware `SWVX11A0126H5173.00036`; see [Phase 3A evidence](COMPATIBILITY.md#geely-android-51-api-22-port-phase-3a). MT6735 identifies the platform SoC, not the Wi-Fi chipset or driver. |
| Wi-Fi interface and manual AP | **CONFIRMED** | Phase 3A hardware validation observed `ap0` owning `192.168.43.1/24`; manual-hotspot readiness and cross-device NSD/mDNS passed. That proves an OEM/user-started AP works in that observed configuration, not its frequency or concurrency. |
| Wi-Fi chipset and driver | **UNKNOWN** | Existing Phase 3A/3B records contain no Wi-Fi module/driver identity. Public Android APIs expose band/feature capabilities, not a reliable chipset/driver name. The new snapshot deliberately does not inspect `/sys`, `/proc`, hidden services, or identifying build properties. |
| 2.4 GHz radio support | **UNKNOWN** | No E01 band measurement is in prior reports. `WifiManager.is24GHzBandSupported()` was added in API31, so it is unavailable on API22. Generic Wi-Fi feature declaration and an active AP do not establish every supported band. |
| 5 GHz radio support | **UNKNOWN pending snapshot** | API22 can query `WifiManager.is5GHzBandSupported()` (API21+). The new one-shot snapshot records its framework result. This is radio/chipset support, not proof that OEM SoftAP can use 5 GHz or that a permitted channel is available. |
| Wi-Fi Direct framework feature | **UNKNOWN pending snapshot** | Snapshot reads `PackageManager.FEATURE_WIFI_DIRECT` without starting P2P discovery. A declared feature is not proof the E01 driver can form the required group. DiPlay's implemented P2P credential path requires API29; API22 cannot use that path. |
| SoftAP availability through OEM Settings | **CONFIRMED** | A manually enabled E01 hotspot was observed through Phase 3A's read-only network/interface diagnostics. The OEM Settings UI can provide an AP without DiPlay changing Wi-Fi state. |
| App-created LocalOnlyHotspot | **UNSUPPORTED on API22** | Android's public `WifiManager.startLocalOnlyHotspot()` was added in API26. The Reddit APK's API26+ fallback is therefore unavailable on the E01. DiPlay's API22 wireless mode must use an already enabled OEM hotspot or existing Wi-Fi. |
| App hotspot configuration/control | **UNKNOWN / OEM restricted** | Phase 3A proved observation/readiness only. It did not grant DiPlay a documented E01 permission or API to enable/configure the saved car hotspot. Do not infer the BYD authorized-ADB hotspot path works on Geely. No app-side control is attempted by the new snapshot. |
| SoftAP frequency support | **UNKNOWN** | Neither the prior test nor the new public API22 snapshot reports the current AP's operating frequency. A 5 GHz radio-support result is not SoftAP-mode support. The safe diagnostic does not inspect hidden AP configuration or start/change a hotspot. |
| Simultaneous AP + STA | **UNKNOWN** | Phase 3A explicitly left station/AP concurrency unverified. Android's public `WifiManager.isStaApConcurrencySupported()` was added in API30. Testing by joining a station while changing AP state would alter hardware state and is prohibited here. |
| Classic Bluetooth radio/profile stack | **CONFIRMED, vehicle-side** | NForetek/Geely exposes Bluetooth, HFP, A2DP, AVRCP and PBAP components. Phase 3B.3 cache-only hardware test returned `GEELY_BT` enabled / state 302. This confirms the vehicle's Bluetooth backend/basic profiles, not a third-party app's iAP2 path. See [Phase 3B findings](COMPATIBILITY.md#phase-3b3-real-okavango-result-pass). |
| Standard Android Bluetooth adapter | **UNAVAILABLE in recorded observation** | Phase 3B.3 separately observed Android `CAR_BT` disabled while `GEELY_BT` was active. Android `BluetoothSocket`/RFCOMM APIs exist at API22, but the recorded system adapter is not a usable route in that state. No attempt to enable it is authorized. |
| SDP / iPhone iAP2 service discovery | **UNKNOWN** | The vendor `getBtRemoteUuids(String)` implementation returns constant profile mask 39 rather than measured remote SDP data. No E01 iPhone SDP result was supplied. The existing diagnostic's cached UUID observation is not service discovery and must not initiate a scan. |
| NForetek SPP / RFCOMM byte transport | **UNSUPPORTED through audited Geely interfaces** | The actual `CommandSppImp` methods return false or do no work; `BtManagerService.getSpp()` returns null. Phase 3B.4 found no usable NForetek SPP transport and a shared-backend lifecycle risk. Do not bind/start SPP or send bytes. This does not prove no other undocumented firmware path exists. |
| CarPlay-specific Bluetooth discovery/advertising | **UNKNOWN** | Apple's public session says Bluetooth discovers and reconnects CarPlay devices. The audited E01 artifacts provide generic profile control and a no-op SPP, not a proven CarPlay discovery/advertisement implementation. No Bluetooth discovery or advertising was run. |
| API22 Bonjour/NSD | **CONFIRMED for diagnostic service only** | Phase 3A's system NSD registration/discovery was confirmed across devices over the manual AP. Production `CarPlayBonjour` selects platform NSD on API22 because the legacy JmDNS multicast socket path conflicts with Android's mDNS daemon. The Phase 3A service was not a CarPlay advertisement or handshake. |
| Location data for wireless CarPlay | **UNKNOWN** | WWDC17 lists location data among wireless CarPlay system requirements. The new snapshot reports only the PackageManager GPS hardware feature declaration; it does not read a location, check permission grants, or prove a usable location provider. |
| MFi authorization | **UNKNOWN / blocking prerequisite** | The existing MFi audit found no authorized E01 identity/provider. No certificate/key is inspected or used in this phase. Wireless transport capability cannot replace MFi authorization. |
| DiPlay production wireless session | **UNSUPPORTED in current build** | `LegacyLaunchBuild.CONNECTIONS_ENABLED=false`; `CarPlayController.start()` fails closed. No E01 wireless startup, connection, or projection is enabled by this phase. |

## Apple requirements

Apple's [WWDC17 “Developing Wireless CarPlay Systems”](https://developer.apple.com/videos/play/wwdc2017/717/)
transcript identifies Bluetooth, a Wi-Fi access point, and location data as
wireless-system requirements, and says Bluetooth is used to discover and
reconnect CarPlay devices. This is consistent with DiPlay's proposed Bluetooth
bootstrap followed by Wi-Fi, but generic HFP/A2DP or an Android NSD test alone
does not satisfy the CarPlay-specific Bluetooth discovery/iAP2 requirement.
The public excerpt used here does not establish a specific E01 band, AP+STA
concurrency mode, or authorized MFi provider.

## Current DiPlay path and blockers

1. `CarPlayController.start()` is blocked by `LegacyLaunchBuild.CONNECTIONS_ENABLED=false`.
2. If enabled in a future, separately authorized build, the controller runs
   `startMfi()` before `startPhone()`. Missing an authorized MFi provider blocks
   both wired and wireless authentication.
3. Wireless startup selects an AP backend, attaches the AirPlay listener/VPN,
   starts Bonjour discovery, then selects a bonded iPhone from the Android
   Bluetooth adapter and opens secure RFCOMM using the iAP2 service UUID.
   `Iap2Session.openWireless()` runs iAP2 bootstrap; accepted control then sends
   Wi-Fi configuration/start-session information and expects a tunneled iAP2
   connection. Sources: [`CarPlayController.kt`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt),
   [`BluetoothCompatibility.kt`](../shared/src/main/java/com/shilapi/xcertplay/transport/BluetoothCompatibility.kt),
   [`Iap2WirelessControlClient.kt`](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2WirelessControlClient.kt).
4. API22 blocks the app-created LocalOnlyHotspot path (API26+) and DiPlay's
   implemented credentialed Wi-Fi Direct path (API29). A manually enabled OEM
   hotspot is a possible AP backend, but DiPlay cannot safely assume it can
   enable/configure the E01 hotspot itself.
5. The standard Android Bluetooth adapter was observed disabled. The confirmed
   GEELY_BT/NForetek path has no working SPP byte transport. This is an
   OEM-access/implementation blocker, not absence of Classic Bluetooth hardware.
6. No location-data capability/runtime result or authorized MFi identity is
   established. No end-to-end wireless CarPlay claim follows from these facts.

## Blocker categories

- **Hardware:** Wi-Fi AP and Classic Bluetooth operate individually. WLAN
  chipset/driver, 2.4 GHz support, 5 GHz SoftAP support, and AP+STA concurrency
  remain unknown; one-on-one radio coexistence was not tested.
- **Android API:** API22 lacks public LocalOnlyHotspot and the public STA+AP
  capability query. The snapshot uses API21+ passive band/frequency APIs and
  API14+ feature metadata only. This does not make the iAP2 stack interoperable.
- **OEM access:** stock Geely Settings owns the demonstrated AP and GEELY_BT
  state. No authorized app-facing hotspot controller or functional NForetek SPP
  route was found. The existing Bluetooth cache snapshot is the only vendor
  call retained; this phase adds no vendor bind/query.
- **MFi authorization:** a legitimate certificate/signing provider is still
  required. A hotspot or working Bluetooth radio cannot substitute for it.

## Snapshot and next action

The new **Phase 3W.1 wireless capability snapshot** is manually triggered once
per Settings Activity instance. It reads only PackageManager Wi-Fi Direct/GPS
feature flags, `WifiManager.is5GHzBandSupported()`, Wi-Fi enabled state, and the
frequency of an already-connected Wi-Fi link. It emits no SSID, BSSID, MAC,
device name, chipset string, or coordinates. It does not scan, toggle Wi-Fi,
enable a hotspot, initiate Bluetooth discovery, connect, or advertise. The
Android API level 2.4 GHz capability query and STA+AP concurrency query are not
available on API22; SoftAP-mode support is not inferred from radio-band support.

**One next action:** On a parked E01, leave Wi-Fi/Bluetooth and the existing
hotspot exactly as they are; open Settings diagnostics and tap **Capture
wireless capability snapshot** once. Save/export the report and provide only
that report. Do not run another Phase 3A network test or any Bluetooth/vendor
action as part of this step. This can fill the 5-GHz/GPS/feature unknowns only;
chipset/driver, SoftAP band, AP+STA concurrency, iPhone SDP/iAP2, and MFi
authorization remain unresolved.

Focused validation: `WirelessCapabilitySnapshotTest` and
`Phase3ADeviceSettingsTest` passed; `:mobile:assembleDebug` succeeded. The
debug build's configured minSdk remains 22. No device test was run in this
phase.