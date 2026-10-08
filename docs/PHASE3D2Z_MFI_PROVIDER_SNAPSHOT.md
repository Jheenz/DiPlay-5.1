# Phase 3D.2Z - read-only MFi provider snapshot

Manual, local metadata diagnostic. No provider is initialized. It reads no credential contents, signs nothing, requests no USB permission, opens no USB/I2C device, performs no I2C transaction or network request, and makes no iPhone/USBMUX/Lockdown/CarKit/iAP2/MFi connection. `LegacyLaunchBuild.CONNECTIONS_ENABLED=false`; `minSdk=22`.

## Status meanings

- `CONFIGURED`: a valid selection or provider setting exists; this does not mean a usable provider is present. REMOTE `futureSecureConfigurationAcceptable=true` requires endpoint + HTTPS + token settings; it still does not establish backend legitimacy.
- `PATH_PRESENT`: passive metadata indicates the expected file/node/descriptor is present (USB also reports existing permission); it does not prove the device is an MFi coprocessor.
- `INACCESSIBLE`: metadata access failed or existing permissions do not allow the requested path.
- `NOT_CONFIGURED`: the target, required setting, expected file, or path is absent/invalid.
- `AUTHORIZATION_UNVERIFIED`: always reported. The snapshot never establishes Apple/MFi authorization and never reports `MFi READY`.

The report shows persisted `MfiTarget`, its effective `CarPlayHostActivity` selection, and the fallback source. Activity selection uses a valid persisted target; if missing or invalid, it falls back to LOCAL. `CarPlayRuntimeConfig` has a separate USB_CH341 constructor default, but the activity passes its resolved target explicitly.

Provider checks are metadata-only: LOCAL lists the expected asset names and uses `lstat`/access metadata for the two app-private credential paths, without following symlinks or opening files; USB_CH341 compares already-enumerated descriptors with configured `1A86:5512` and checks permission only for matching descriptors; I2C validates and stats only the configured `/dev/i2c-N` node; REMOTE reports endpoint/token presence, HTTPS, and the secure-configuration candidate boolean without emitting URL/token values. The snapshot emits `Version=0.2.12-api22-phase3d2z-mfi-provider-snapshot` and includes zero-operation counters.

## Single-run E01 procedure

1. Install the API22 debug APK in place, preserving current app data and settings. Do not uninstall or clear data. Build/version: `0.2.12-api22-phase3d2z-mfi-provider-snapshot`.
2. Park the vehicle and disconnect the iPhone. Do not attach a new MFi/CH341 device, grant USB permission, or change provider settings for this observation. Already-enumerated USB descriptors may be listed passively.
3. Open DiPlay Settings/Diagnostics and locate **Phase 3D.2Z — Read-Only MFi Provider Snapshot**.
4. Press **Inspect MFi provider configuration** once. Wait for the report and save/export it.
5. Record the persisted/effective target, fallback source, selected provider status, metadata path status, HTTPS/token booleans, and `AUTHORIZATION_UNVERIFIED`. Do not interpret `PATH_PRESENT` as MFi readiness.
6. Stop. Do not run the active I2C scanner/self-check, open a provider, connect the iPhone, Pair/ValidatePair, or proceed to CarKit/iAP2/authentication.

This single action establishes only configured/path metadata. It does not prove hardware identity, credential validity, Apple authorization, or iPhone acceptance. OEM/MFi-licensee documentation and an authorized provider remain prerequisites before any active authentication test.
