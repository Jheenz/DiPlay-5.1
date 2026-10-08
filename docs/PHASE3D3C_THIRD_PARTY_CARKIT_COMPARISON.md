# Phase 3D.3C - Third-party APK and CarKit integration comparison

## Scope and verdict

Static inspection only of `vendor-apks/reddit-mobile-debug.apk`, compared with
the current DiPlay source and the offline findings in
[`PHASE3D3B_OFFLINE_WIRED_PROTOCOL_VALIDATION.md`](PHASE3D3B_OFFLINE_WIRED_PROTOCOL_VALIDATION.md).
The APK was not installed, launched, or connected to a device or network. No
credential file contents were read, decoded, copied, or reported. No application
source or credential assets were changed.

**Verdict:** This is a DiPlay-derived HUD-test build containing the same broad
wired/wireless CarKit and MFi provider architecture as the current project. It
also packages filenames for a local MFi identity pair. This is evidence of an
intended file-backed authentication option, not evidence that the pair is
authorized, valid, selected at runtime, or accepted by an iPhone. The artifact
is not deployable on the E01's Android 5.1/API 22 environment: its manifest
requires API 26 or later.

## Artifact identity

| Metadata | Static finding |
|---|---|
| File | `vendor-apks/reddit-mobile-debug.apk`, approximately 16 MiB |
| SHA-256 | `e6be1701252cf86975f604839ad6ecaa14f2924445d635da3443e3c8f0791dc4` |
| Package and version | `com.shihab.diplay.hudtest`, version code 27, version name `0.2.8-hud-test` |
| Android SDK | Minimum 26; target 37 |
| APK signer | Debug-style subject with unknown fields; signer certificate SHA-256 fingerprint `f67430285c020c72fbeaa81870b42c79bcc159b08be2ddd83789d0eae68215fd` |
| Device-specific manifest items | `android.permission.BYDAUTO_INSTRUMENT_COMMON`, USB Host feature, and HUD demo components protected by `android.permission.DUMP` |

The package/version and HUD/BYD manifest items distinguish this artifact from
the current project build (`com.shihab.diplay.legacytest`, version 0.2.12,
minimum API 22). APK signing identity is separate from MFi accessory identity;
the signer fingerprint does not establish Apple authorization.

## Integration comparison

DEX class descriptors include `LockdownCarKitClient`, `Iap2LinkEngine`,
`Iap2WiredControlClient`, `Iap2WirelessControlClient`, `CarPlayController`,
`CarPlayBonjour`, `CarPlayVpnService`, `Iap2MfiAuthenticationClient`,
`LocalMfiAuthenticationClient`, `RemoteMfiAuthenticationClient`, and
`DiPlayBootstrap`. The manifest also declares USB Host, Bluetooth, Wi-Fi,
network, and VPN-related permissions/components.

These names align with the current source architecture:

- Wired: USBMUX and Lockdown TLS open `com.apple.carkit.service`, then the
  service stream feeds iAP2 CSM/link, Identification, MFi authentication, and
  wired control. See [`LockdownCarKitClient.kt`](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt),
  [`Iap2MfiAuthenticationClient.kt`](../shared/src/main/java/com/shilapi/xcertplay/mfi/Iap2MfiAuthenticationClient.kt),
  and [`CarPlayController.kt`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt).
- Wireless: the current source has wireless iAP2, Bonjour discovery, and a VPN
  media path; corresponding classes are present in the APK inventory.
- MFi providers: both local-file and remote provider implementations are
  present in the APK. The source also supports USB-CH341 and Linux-I2C provider
  paths; the APK includes `libxcertplay_i2c.so` for arm64-v8a, armeabi-v7a,
  x86, and x86_64.

Class presence establishes compiled components, not that a given route is
enabled or works. This static comparison found no evidence that the APK
implements a distinct replacement for the current iAP2/CarKit design, nor does
it establish successful CarKit application-protocol interoperability.

## Local identity and dongle-free claim

The APK archive contains the entry names
`assets/offline-mfi/identity.pk8` and
`assets/offline-mfi/certificate.p7b`; `DiPlayBootstrap` and
`LocalMfiAuthenticationClient` classes are also present. In the current source,
the bootstrap stages these files into app-private storage only when
`MfiTarget.LOCAL` is selected. The current build documentation describes
explicit local-identity provisioning and prevents accidental credential
inclusion in ordinary source/CI builds; see [`BUILD.md`](BUILD.md) and
[`mobile/build.gradle.kts`](../mobile/build.gradle.kts).

This is bounded evidence for a software, file-backed MFi provider that could
avoid a separate MFi signing coprocessor if a legitimate, compatible identity
were provisioned and the local target were selected. It does **not** prove
either condition for this APK. The identity files were intentionally not
opened, so their matching, integrity, authorization, validity, and provenance
are unknown. Treat them as untrusted and do not copy or reuse them. Wired
operation would still require the iPhone's USB connection; “dongle-free” here
can only refer to avoiding a separate MFi signing accessory.

## E01 applicability and limits

- **Not API 22 compatible:** `minSdkVersion=26` excludes Android 5.1/API 22,
  irrespective of whether its protocol code resembles the current source.
- **Different target context:** the `hudtest` package, HUD demo components, and
  BYD-specific permission do not establish compatibility with the Geely E01.
- **No runtime selection proof:** manifest, asset names, and class descriptors
  do not show which `MfiTarget` was selected in a particular run.
- **No acceptance or hardware proof:** no APK execution, iPhone exchange,
  network activity, or E01 test was performed. Static contents cannot prove
  Apple trust or successful authentication.
- **Credentials remain out of scope:** do not import these assets into the
  repository, print their contents, or use them as a substitute for an
  authorized identity.

## Recommended next action

Obtain the APK owner's build configuration and written provenance/authorization
for its local MFi identity before considering any local-provider integration.
If that authorization cannot be established, continue with the current
credential-free API 22 source build and do not use the APK's identity files.