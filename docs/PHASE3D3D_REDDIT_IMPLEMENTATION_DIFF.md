# Phase 3D.3D - Reddit APK implementation difference audit

## Scope and method

Static, offline comparison of the non-sensitive DEX control flow in the APK
inventoried in [Phase 3D.3C](PHASE3D3C_THIRD_PARTY_CARKIT_COMPARISON.md) with
the current source. Android SDK `dexdump` was used to inspect selected methods
only. The APK was not installed or executed. Credential entries, signing
internals, authentication challenge/signature data, and private-key contents
were not examined. No source or APK was changed or built.

This is a method-level comparison of the requested paths, not proof that every
APK method is semantically equivalent to a source revision. Decompilation to
Kotlin/source form was not used; DEX call and branch evidence is identified
below. Runtime behavior remains unverified.

## Confirmed implementation differences

| Area | Reddit APK method evidence | Current source difference | API 22 assessment |
|---|---|---|---|
| Connection startup | `CarPlayHostActivity.onCreate()` proceeds to startup prerequisites; `startCarPlay()` constructs/stores `CarPlayController` and calls `start()`. `CarPlayController.start()` registers wired USB listeners when wired and calls `startMfi()`; its DEX has no disabled-launch throw. | [`CarPlayController.start()`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt) checks `LegacyLaunchBuild.CONNECTIONS_ENABLED`; [`LegacyLaunchBuild.kt`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/LegacyLaunchBuild.kt) sets it to `false`. | This is an enablement difference, not an API22 compatibility fix. The APK's minimum API 26 prevents installing it on API22. Removing the current launch gate alone would not prove the full stack works on API22. |
| Bootstrap and MFi selection | `CarPlayHostActivity.onCreate()` calls `DiPlayBootstrap.ensure(Context)` before loading settings. `CarPlayController.startMfi()` checks whether app-private `offline-mfi` exists first; if so it calls `openLocalMfi()` and returns before reading `config.mfiTarget`. Otherwise it switches to the configured provider. | Current [`DiPlayBootstrap.ensure(Context, MfiTarget)`](../common/src/main/java/com/shilapi/xcertplay/DiPlayBootstrap.kt) returns unless `LOCAL` is selected. Current [`CarPlayController.startMfi()`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt) dispatches on the explicit configured target and has no directory-existence override. | File staging and local selection use ordinary app-private file/asset APIs and are structurally portable. The APK's unconditional bootstrap and local-first override should not be ported: they defeat explicit provider selection. Local identity validity/authorization was not examined and is unknown. |
| Accessory identification profile | `CarPlayHostActivity.createRuntimeConfig()` embeds target-specific Identification constants including `xuv300` and `XUV300-`. | Current [`createRuntimeConfig()`](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt) uses the configured normalized model and manufacturer, and derives a DiPlay serial/host identity. | Vehicle profile is not an OS compatibility fix. The XUV300 values are target-specific and are not a safe E01/API22 port. |
| iAP2 Identification state | `Iap2IdentificationClient.identify()` dispatches the accepted message directly to its return block; its DEX method has no state check that IdentificationInformation was sent first. | Current [`Iap2IdentificationClient.identify()`](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2IdentificationClient.kt) tracks `identificationInformationSent` and rejects premature IdentificationAccepted. | The source guard is independently useful and API22-safe; keep it. It is already in current source and is not a reason to adopt the APK implementation. |

## Paths with no material difference found

| Path | APK method evidence and source comparison | API 22 portability |
|---|---|---|
| USBMUX / Lockdown / CarKit | `CarPlayController.runStack()` opens USBMUX, pairs or reuses a Lockdown record, and calls `LockdownCarKitClient.open()`. `LockdownCarKitClient.openService()` builds StartSession, requires/enables session TLS, sends StartService, opens the returned port, and applies service TLS when requested. The inspected DEX call sequence matches [`runStack()`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt) and [`LockdownCarKitClient.openService()`](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt); no distinct Android 8/9 workaround was identified. | The source transport has prior API22-specific validation, but this comparison did not establish that the APK has a more portable transport implementation. |
| Wired iAP2 sequence | `Iap2Session.open()` follows the CarKit stream. `Iap2WiredControlClient.run()` calls Identification, then the MFi client, then power/subscription messages and the availability loop. The inspected DEX call order matches [`Iap2WiredControlClient.run()`](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2WiredControlClient.kt) and the 3D.3B source audit. MFi signing internals were not inspected. | No newer-Android-only difference was identified in this sequence. This is not evidence of iPhone acceptance or API22 runtime success. |
| Wireless discovery and handoff | The inspected controller flow includes hotspot setup, Bluetooth RFCOMM iAP2 bootstrap, and tunneled iAP2 control; `CarPlayBonjour.start()` uses Android NSD and interface mDNS/JmDNS paths. These correspond to current `CarPlayController` wireless methods and [`CarPlayBonjour`](../shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt). No distinct discovery or connection sequence was established in the inspected methods. | Both implementations' LocalOnlyHotspot route requires API26 or newer. Neither provides evidence of a Reddit APK route usable on API22. |

## Android 8/9 compatibility finding

The APK's method-level wireless dispatch maps `WIFI_P2P` to
`LOCAL_ONLY_HOTSPOT` below API 29, and its `WifiP2pGroupManager.start()` rejects
API levels below 29. This is an Android 8/9-era wireless fallback: LocalOnly
Hotspot exists from API26, while this P2P credential path requires API29. The
current [`startWirelessHotspot()`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)
has the same API26-28 routing and explicitly rejects P2P/LocalOnlyHotspot below
API26. Thus it is not a Reddit-only improvement, and it cannot provide an
API22 path. The APK minimum of API26 also means no API22 compatibility claim can
be inferred from its own execution.

No Android 8/9-specific change was found in the inspected wired Lockdown or
iAP2 control sequence. The APK's enabled launch path and packaged local-provider
selection are behavior/configuration differences, not evidence of API22
framework compatibility.

## Dongle-free claim: evidence and unknowns

**Established statically:** after its bootstrap runs, the APK's controller
prefers the app-private local-provider directory before consulting the
configured MFi target. The archive entry names and bootstrap flow were recorded
in Phase 3D.3C; their contents remain untouched. This is a code path that could
avoid a separate MFi signing coprocessor if the provisioned identity is
legitimate, compatible, and accepted.

**Not established:** the identity's issuer, authorization, validity, matching
pair, runtime selection result, iPhone acceptance, or a successful CarPlay
session. No runtime log or test evidence was available. “Without a dongle” can
only mean without a separate MFi signing accessory; wired operation still
requires the iPhone's USB connection. The APK itself cannot be installed on
API22.

## Safe porting assessment

- Keep the current explicit MFi provider selection; do not copy the APK's
  unconditional local-first override or its identity assets.
- Keep the current premature-IdentificationAccepted guard; the inspected APK
  lacks it.
- The API26-28 wireless fallback is already present in current source. The
  API26 LocalOnlyHotspot API and API29 Wi-Fi Direct credential path do not
  extend to API22.
- No additional Reddit-specific transport or protocol change was established
  as a safe API22 improvement. Current launch remains deliberately disabled;
  this audit does not authorize enabling it.

## Recommended next action

Request one non-secret evidence bundle from the APK author: written provenance
that the local identity is authorized, plus a reproducible test record naming
the head-unit Android API, iPhone/iOS version, selected MFi provider, absence of
an external MFi accessory, and successful authentication/CarPlay projection.
Do not request or accept the private key or certificate contents. This is the
minimum evidence needed to evaluate the no-dongle claim before considering any
API22 port.