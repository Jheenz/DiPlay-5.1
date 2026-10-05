# Compatibility

This public preview is an independent receiver, not an Apple-certified CarPlay accessory. The experimental bundled accessory identity is extractable and its future acceptance is not guaranteed.

| Area | Current scope |
| --- | --- |
| Head unit | Android 9+ APK; wireless Wi-Fi Direct path needs Android 10+ |
| Phone | Standard, non-jailbroken iPhone with CarPlay enabled; device/iOS compatibility varies |
| Physical evidence | Previous private builds: wired and wireless picture, touch and audio confirmed on the development car with iPhone XS / iOS 18.7.10 |
| Other cars | Mixed community reports across DiLink generations; not a certified model support list |
| Current release | DiLink5.1: HUD/street names and Car hotspot confirmed; Wi-Fi Direct improved, occasional audio cutouts remain |
| Wi-Fi | Prefer 5 GHz without an established station connection; align to a supported existing station channel; explicit 2.4 GHz fallback for firmware that rejects 5 GHz or automatic channel selection |
| Video | Default H.264 / 30 fps; 60 fps and HEVC increase device-specific demands |

## Geely Android 5.1 / API 22 port (Phase 3A)

This branch supports installation/launch and legacy media initialization on API 22. This is
**not** evidence of a working CarPlay session. Connections and vendor integrations remain disabled
by `LegacyLaunchBuild`; Bluetooth RFCOMM/iAP2, authentication, USB projection, Wi-Fi Direct and
LocalOnlyHotspot are not enabled by this phase.

The Okavango has confirmed installation/launch on `alps E01`, `mt6735`, Android 5.1/API 22,
firmware `SWVX11A0126H5173.00036`. The debug build is now
`0.2.12-api22-phase3a-device-test`. Settings exposes read-only live interface/address,
prefix/route, station/AP evidence and stable Manual Hotspot readiness diagnostics.
**Start Phase 3A network test** performs readiness checks then a 20-second system NSD
registration/discovery test with `_diplay-phase3a._tcp.`. It does not advertise CarPlay,
load accessory credentials, accept socket traffic or send any handshake/control probe.
It retains a multicast lock only during the test and stops on app pause, timeout,
interface/address change or lost readiness. The diagnostic report includes the same
network/test data. `DiPlayPhase3ADevice` logs selection, readiness, NSD and failure outcomes.
On API 22, default connectivity is observable but Internet validation is unknown; no
external Internet probe is made. Missing firmware SSID/route information is reported,
not guessed. A self-discovered advertisement alone is not evidence of laptop reachability.

Existing-network managers use API 22-compatible service lookup. Manual hotspot sampling on
API 22 reads `activeNetworkInfo`, Wi-Fi `NetworkInfo`, `WifiInfo.ipAddress`, API 21
`Network`/`LinkProperties`, and local interfaces instead of `activeNetwork` (API 23).
Before/after legacy observations must agree. A missing default route is valid for a local AP;
a connected station whose interface cannot be identified remains unknown, not an eligible AP.
Three stable address/interface samples are still required. Existing Wi-Fi uses the already
connected station link; it never creates an AP, joins Wi-Fi, or changes the default route.
Before API 30, its callback request removes the legacy constructor's default capabilities
instead of calling the newer public `clearCapabilities()` method.
Station mode must use Existing Wi-Fi, not Manual Hotspot.

API 22 uses system NSD even when interface mDNS is requested: the legacy `MulticastSocket`
constructor conflicts with Android's mDNS daemon on UDP 5353. NSD registration/discovery/
resolution and TXT setters are available on API 22, and the Wi-Fi multicast lock is retained.
Android controls NSD's multicast/interface scope; `setHost` is not an interface-binding guarantee.
Android 5.1 may omit TXT attributes from resolved peers, so an endpoint can have no Bluetooth ID.
Phase 3A needs only its address/port, not pairing. API 23+ retains the existing JmDNS/NSD choices;
API 34+ multi-address resolution remains guarded. Discovery can be tested with
`probeControlService = false` to avoid sending the CarPlay connect request.

API 22 emulator checks cover enumeration, local same-port IPv4/scoped-IPv6 sockets, injected
AP readiness, and system NSD discovery/resolution. The emulator exposes Ethernet, not a real
Wi-Fi hotspot. Okavango firmware must still prove AP ownership, station/AP concurrency,
address changes, multicast delivery to another LAN device, and NSD operation with cellular/VPN
or multiple interfaces. Do not treat these checks as authorization to begin Phase 3B.

## BYD HUD and car hotspot

See [BYD navigation](BYD_NAVIGATION.md) for the exact verified firmware and lifecycle limits. Car hotspot now starts CarPlay on the development car using scoped IPv6. The phone must join the configured car hotspot. Neither result guarantees support on every firmware.

## Known limitations

- Some units stutter, particularly under higher video load. A 2.4 GHz link alone does not prove the cause: interference, firmware and decoder stalls can all contribute. Try Default icons, 30 fps and a lower resolution, then attach a report.
- A contributor reported periodic wireless CarPlay stutter on a 2023 Han with GCC DiLink 3 when the car's Wi-Fi client was disconnected: scans every 10 s took the radio off the CarPlay channel for 3-6 s. On Android 10, with network ADB already authorized and the framework transaction available, DiPlay pauses connectivity scans for hotspot/P2P sessions. Same LAN is excluded so station reconnect and roaming remain available. Controller leases share one serial worker; scans restore after the last lease closes. A durable marker precedes suppression, failed restores retry every 30 s while the app runs, and app startup recovers an interrupted restore. A force-stop can delay restoration until the app is opened again. Missing approval or transaction support leaves scans alone. The updated in-app lifecycle needs a parked vehicle retest; without ADB, joining the car to a hotspot also slowed scans in the contributor's test.
- Some iOS/head-unit combinations do not visibly apply icon and text size. Reconnection is implemented; that does not guarantee the iPhone chooses the requested layout.
- A radio that supports joining a 5 GHz network may still reject a 5 GHz Wi-Fi Direct group. The capability flag is diagnostic, not proof of group-owner support.
- Automatic startup depends on the car's firmware and startup permissions.
- USB requires a data port and correct host/device-role behavior.
- Calls, Siri, background reconnection, long journeys and future iOS releases need broader testing.

Reports record requested and actual frequencies, station association state, fallback failures and remembered-configuration events. Wi-Fi credentials and protocol payloads are excluded. A successful hotspot is not itself a successful CarPlay session.

Android references: [SupplicantState](https://developer.android.com/reference/android/net/wifi/SupplicantState), [explicit P2P operating frequency](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder#setGroupOperatingFrequency(int)).
