# DiPlay

**CarPlay for compatible BYD Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay`.

> **BYD release scope:** The public release targets compatible BYD head units. Other brands are unsupported. A separate Geely E01 research effort is documented below; it is not a supported release or a promise of future Geely support.

**Geely E01 research status (not supported):** Testing is on a Geely Okavango head unit identified as `alps E01`, MT6735, Android 5.1/API 22, firmware `SWVX11A0126H5173.00036`. Confirmed so far: the app installs and launches; the manually enabled car hotspot (`ap0`, `192.168.43.1/24`) passes readiness and cross-device mDNS/NSD checks; cached vehicle Bluetooth status is readable; and wired USBMUX/Lockdown TLS reaches the CarKit service and closes cleanly. This does **not** establish a working CarPlay session. The production connection path remains disabled, an authorized MFi provider is unresolved, and no usable E01 Bluetooth RFCOMM/iAP2 path or wireless CarPlay session is confirmed. See the [E01 compatibility findings](docs/COMPATIBILITY.md#geely-android-51--api-22-port-phase-3a), [wireless feasibility report](docs/PHASE3W1_E01_WIRELESS_FEASIBILITY.md), and [CarKit transport result](docs/PHASE3D2W_CARKIT_SERVICE_TCP_TLS.md).

[Download & website](https://shihabal3amri.github.io/DiPlay/) · [Release](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.12) · [Report a problem](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

![DiPlay home](site/assets/home.png)

## Development status

**Status as of 2026-10-09.** The public `v0.2.12` line targets compatible BYD head units. The Geely work below is a separate API22 engineering investigation; it is not a Geely release, supported configuration or claim of successful CarPlay.

### Geely E01 target

| Item | Value |
|---|---|
| Vehicle / head unit | Geely Okavango, `alps E01` |
| Platform | MediaTek MT6735; Android 5.1 / API 22 |
| Reported firmware | `SWVX11A0126H5173.00036` |
| Research objective | Establish whether the existing wired and wireless DiPlay transports can be supported without changing production safety gates |
| Current CarPlay result | No complete CarPlay session confirmed; `LegacyLaunchBuild.CONNECTIONS_ENABLED` remains `false` |

### Verified milestones

| Milestone | Evidence | What it does not prove |
|---|---|---|
| Phase 3A: API22 install, launch and network diagnostics | Real E01 runs the test app. The manually enabled hotspot exposed `ap0` at `192.168.43.1/24`; manual readiness and cross-device system NSD/mDNS passed. | No CarPlay advertisement, phone handshake, connection or projection. |
| Phase 3B: Bluetooth/OEM interface audit | A manual, zero-flag NForetek cache read returned vehicle Bluetooth `GEELY_BT` enabled/state 302. Static vendor APK review found the installed SPP implementation is no-op; the Geely SPP accessor returns null. | No iPhone SDP result, working RFCOMM/iAP2 byte path, or CarPlay-specific Bluetooth discovery. The separate Android `CAR_BT` adapter was observed disabled. |
| Phase 3D.2W: wired transport boundary | Real E01 completed USBMUX/Lockdown, CarKit service TCP/TLS peer validation, encrypted StopSession and cleanup. | No CarKit application bytes, iAP2, MFi authentication or CarPlay session were sent. |
| Phase 3D.3B: offline wired protocol review | The source-level CSM/iAP2 state machines have focused synthetic tests; API22-minimum debug assembly passes. | Synthetic tests do not demonstrate iPhone acceptance or protocol interoperability. |
| Phase 3D.3A: MFi provider audit | No authorized E01 MFi identity/provider or ordinary-app access path is established. | This is bounded by collected artifacts; it does not prove no uncollected firmware component exists. |
| Phase 3D.3C/D: third-party APK review | Static analysis found a DiPlay-derived API26+ HUD build with a local-provider code path. | Its identity was not inspected or reused; authorization, selection, iPhone acceptance and successful projection are unverified. It cannot run on API22. |
| Phase 3W.1: wireless feasibility | A manual, one-shot API22 capability snapshot is available in Settings diagnostics. | It has not yet been captured on the E01. It cannot reveal chipset/driver, SoftAP-specific bands or AP+STA concurrency. |

### Transport boundary

```text
Wired, verified through transport only:
iPhone USB -> USBMUX -> Lockdown pairing/session TLS -> StartService -> CarKit TCP/TLS -> clean StopSession
																	^ last real-E01 application boundary

Wireless, source path only:
Bluetooth RFCOMM/iAP2 bootstrap -> Wi-Fi endpoint handoff -> tunneled iAP2 -> AirPlay media
```

The Geely work has **not** crossed the CarKit service/TLS boundary with application data. The wireless path is not runnable in the current build and its required E01 Bluetooth transport is not established.

### Open engineering gates

- **MFi:** obtain an authorized identity/signing provider and its supported E01 access contract. Do not use the Reddit APK's identity or another accessory's credentials.
- **Bluetooth:** establish an authorized, functioning Bluetooth discovery/RFCOMM/iAP2 route. Generic HFP/A2DP and a cached NForetek status are not sufficient; the audited Geely SPP service cannot carry bytes.
- **Wi-Fi:** record the manual snapshot's API-visible Wi-Fi Direct feature, 5 GHz radio support, GPS feature declaration and current link frequency. E01 Wi-Fi chipset/driver, 2.4 GHz support, SoftAP band and STA+AP concurrency remain unknown.
- **API22/OEM:** Android 5.1 has no public LocalOnlyHotspot API; DiPlay's credentialed Wi-Fi Direct path requires API29. Current E01 evidence only supports observing a manually enabled OEM hotspot, not enabling/configuring it from DiPlay.
- **Safety gate:** keep production connections and vendor integration disabled until the provider, Bluetooth transport and authorized test sequence are established.

The snapshot is manual and one-shot per Settings Activity instance. It reads public framework metadata only: no scan, hotspot change, Bluetooth discovery, pairing, connection or advertisement. It does not read network identifiers, locations, vendor state or credential material. Follow the single parked-car capture procedure in the [Phase 3W.1 report](docs/PHASE3W1_E01_WIRELESS_FEASIBILITY.md).

## Developer workflow

Requirements and credential-safe packaging rules are in [Build from source](docs/BUILD.md). The core focused check is:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleDebug
```

For the Geely E01 track, start with [Compatibility](docs/COMPATIBILITY.md), [Testing](docs/TESTING.md), the [Phase 3W.1 feasibility report](docs/PHASE3W1_E01_WIRELESS_FEASIBILITY.md), and the milestone reports linked above. Do not interpret a passing build, mock test or transport-only hardware test as a CarPlay pass.

## Release and security notes

The `v0.2.12` release is a public preview for compatible BYD head units; see [release notes](docs/RELEASE-NOTES-0.2.12.md), [validation](docs/VALIDATION.md), and [BYD navigation scope](docs/BYD_NAVIGATION.md). Broader head-unit and iOS compatibility is not guaranteed. Reports are shared only when the user chooses; review them before posting and never include hotspot passwords.

This is **not an Apple-certified product**. The public APK includes an experimental accessory identity recovered from public Carlinkit firmware; it is extractable and is not established as an authorized DiPlay identity. The Git repository excludes runtime credential assets by default; local release packaging is described in [Build from source](docs/BUILD.md) and [third-party notices](docs/THIRD_PARTY_NOTICES.md). No Apple or BYD affiliation or endorsement is implied.

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; preserve those notices when distributing modifications. The website and app interface are available in English, Arabic, Russian, Ukrainian, Spanish and Simplified Chinese.
