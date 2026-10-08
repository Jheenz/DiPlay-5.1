# Phase 3D.2Y - MFi provider readiness audit

**Scope:** Static source, documentation, and previously collected E01 evidence only. No iPhone connection, I2C transaction, USB permission/open, MFi/iAP2/CarKit traffic, APK build, or hardware test was performed. Pairing and production CarPlay state are unchanged.

## Executive finding

DiPlay contains an `MfiAuthenticator` contract, an iAP2 AA00-AA05 consumer, and four provider routes (local files, Linux I2C coprocessor, USB CH341-to-I2C coprocessor, remote signer). This proves software plumbing only. No authorized provider, credential, or usable provider access path is established for the Geely E01. Do not send CarKit application data or run the active I2C scanner.

The successful 3D.2W test confirms USBMUX, Lockdown TLS, CarKit service TCP/TLS, and StopSession; it says nothing about MFi readiness because W sends no CarKit/iAP2 application payload. The E01's actual persisted `MfiTarget` is also unknown: it was not reported by W.

## Provider trace

- `CarPlayRuntimeConfig.MfiTarget` contains `LOCAL`, `USB_CH341`, `I2C`, and `REMOTE`. The runtime-config constructor defaults to `USB_CH341` and requires a configured CH341 device list, I2C path, or remote server for the corresponding target.
- The common `CarPlayHostActivity` keeps LOCAL as its in-memory default, loads a persisted target through `AirPlayPersistence.loadMfiTarget()`, and its visible choice row offers LOCAL and USB_CH341. I2C/REMOTE fields and enum branches exist, but are not offered in that choice row. `AirPlayPersistence.loadMfiTarget()` falls back to LOCAL when there is no recognized saved value. No E01 settings value was collected in 3D.2W.
- `CarPlayHostActivity.createRuntimeConfig()` explicitly passes that effective activity target, so its persisted-or-LOCAL choice takes precedence over the config constructor's USB_CH341 default. That constructor default applies only to other callers that omit the target. The E01's actual persisted value remains unknown.
- `CarPlayController.start()` calls `startMfi()` before `startPhone()`. LOCAL loads the app-private identity; CH341 discovers configured USB VID/PID devices, requests USB permission, opens a bridge and scans; I2C opens the configured `/dev/i2c-N` and scans; REMOTE constructs the HTTP client, sends reset, and obtains protocol metadata. A ready provider permits phone discovery to start.
- In wired operation, after Lockdown opens `com.apple.carkit.service` and iAP2 CSM is ready, `Iap2WiredControlClient.run()` performs Identification first, then `Iap2MfiAuthenticationClient.run()`. AA00 triggers AA01 certificate transmission; AA02 challenge triggers local/coproc/remote signing and AA03; AA05 is required for success and AA04 fails. Power/subscription and CarPlay availability/start-session messages follow authentication. This is DiPlay's implemented expectation, not proof of the iPhone's private service specification.

## Provider readiness matrix

| Provider | Requirements and initialization | E01 evidence | Authorization status | Feasibility |
|---|---|---|---|---|
| **LOCAL file-backed** | `offline-mfi/identity.pk8` (PKCS#8 EC/P-256) plus `certificate.p7b` (one X.509 cert), provisioned into app-private `noBackupFilesDir/offline-mfi`. `LocalMfiAuthenticationClient.load()` reads and validates the matching key/certificate and signs a random consistency challenge. `DiPlayBootstrap` installs assets only when explicitly supplied through `DIPLAY_AUTH_ASSETS_DIR`. | The ordinary source build has no accessory identity; no E01-provisioned files or authorization evidence were identified. W does not read or report provider settings. | **Not established.** Matching key/cert proves local consistency only, not Apple trust. | Technically plausible on API22 subject to target crypto-provider behavior, but unusable absent legitimately provisioned material. Never inspect or extract existing secrets for this audit. |
| **Linux I2C coprocessor** | Configured exact `/dev/i2c-N`, packaged JNI `xcertplay_i2c`, OS/SELinux permission, compatible MFi chip. `MfiRuntime.scan()` actively probes I2C addresses `0x10`, then `0x11`, using register writes/reads. | E01 passive Phase 3C.3C found `/dev/i2c-0` through `/dev/i2c-3`, all root-only `0600`, with no Apple/MFi/authentication evidence in registered sysfs clients. The default configured path is `/dev/i2c-1`; no E01 access grant, vendor Binder path, or responding coprocessor is established. | **Not established.** A generic I2C client/address is not MFi identification or authorization. | Native I2C/JNI is present for supported ABIs and the declared minimum is API22, but root-only nodes block ordinary-app access on the observed E01. Do not run the scanner. |
| **USB CH341-to-I2C** | Separately attached CH341 bridge, configured VID/PID, Android USB permission, compatible MFi coprocessor behind the bridge; optional reset GPIO. The bridge transports I2C but does not itself provide MFi identity. | `CarPlayHostActivity.createRuntimeConfig()` currently configures bridge VID/PID `1A86:5512` when USB_CH341 is selected. This is source configuration only: no matching attached E01 bridge or MFi coprocessor is established by collected audits. The production launch path is disabled. | **Not established.** A matching USB descriptor would identify only a bridge candidate, not MFi hardware or authorized credentials. | Android USB Host APIs are API22-compatible in principle; feasibility still requires an attached matching bridge, compatible coprocessor, and permission. |
| **REMOTE provider** | Configured server and optional Bearer token. Current `RemoteMfiAuthenticationClient` calls `/mfi/reset`, `/mfi/certificate`, and `/mfi/sign`; it accepts both `http://` and `https://`, and retries selected certificate/sign requests. | No authorized endpoint, reachable service, configuration, or backend identity is established for E01. W does not contact it. | **Not established.** A generic signing endpoint does not prove it owns an Apple-authorized identity. | APIs used are available on API22, but deployment is blocked by lack of authorized service evidence; plain HTTP support is not suitable for sending signing challenges/tokens. |

## Known versus unknown

**Confirmed:**

- Apple publicly states that MFi provides technical specifications/resources and includes CarPlay, iAP2, and authentication coprocessors among licensed technologies.
- Apple states that iAP2-over-USB and vehicles supporting CarPlay over USB are within the MFi Program.
- DiPlay implements AA00-AA05 and places that exchange after iAP2 Identification and before its wired power/subscription/availability steps.
- The E01 passive inventory saw four root-only I2C nodes and no identifying registered sysfs clients. This is evidence about those observed paths, not an exhaustive board/Binder/native audit.
- Production connection startup remains disabled.

**Unknown / not proven:**

- Which provider target, if any, is currently selected in the E01's persisted settings.
- Whether the physical board has an unobserved MFi chip, CH341 bridge, OEM service, or separately provisioned provider.
- Whether a specific MFi identity is authorized for this head unit/iPhone.
- Whether the private `com.apple.carkit.service` protocol mandates exactly DiPlay's iAP2 sequence, has OEM-specific extensions, or requires an additional authorization mechanism.
- Whether the iOS CarPlay app entitlement has any relevance to the Android accessory/head-unit implementation. The public entitlement page is for iOS app development; it does not establish a head-unit entitlement requirement.
- Whether local EC/`NONEwithECDSA` signing works on this particular API22 vendor crypto provider. No target-device provider test was run.

## Could a metadata-only readiness diagnostic be safe?

Yes, if it reports only configuration and filesystem/descriptor metadata and preserves the existing `PassiveMfiI2cInventory` boundary:

- Read the persisted `MfiTarget`; report whether a value is explicitly saved or using the fallback, without invoking `CarPlayController.startMfi()`.
- LOCAL: use stat/existence/size/mode only for the two expected filenames; never open/read them, parse certificates, load keys, or sign.
- I2C: report whether the configured value is a strict `/dev/i2c-N` path and stat/access booleans for that node only. Never open it, enumerate/probe I2C addresses, or call `MfiDeviceScanner`/`MfiSelfCheck`.
- CH341: report configured VID/PID count and passive `UsbManager.deviceList` descriptor match booleans only. Do not request permission, open/claim a device, or instantiate the bridge transport.
- REMOTE: report whether the endpoint and token preference are configured and whether the scheme is HTTPS; never display/read out the token value, issue HTTP requests, or contact the endpoint.

This can answer “is a provider path configured and superficially accessible?” It cannot prove the hardware is MFi, that the credential is authorized, or that the iPhone accepts it.

Keep selection, path, and trust states distinct: `CONFIGURED` means a valid provider selection/configuration exists; `PATH_PRESENT` means passive metadata shows the expected file, node, or descriptor; `INACCESSIBLE` means stat/access metadata denies access; `NOT_CONFIGURED` means a required setting or prerequisite is absent; `AUTHORIZATION_UNVERIFIED` remains the authentication state until the OEM/MFi provider confirms authorization. Do not aggregate these states into `MFi READY`.

The existing `PassiveMfiI2cInventory` uses `lstat`/`access` and bounded sysfs metadata reads; it does not open device nodes. A future provider snapshot can reuse that boundary and add SharedPreferences presence checks, `AssetManager.list()` for expected asset names, credential-file stat only, `UsbManager.deviceList` plus `hasPermission()` for an already-listed matching descriptor, and stat/access on only a validated `/dev/i2c-N` setting. It must not call `CarPlayController.startMfi()`, `DiPlayBootstrap.ensure()`, `LocalMfiAuthenticationClient.load()`, `Ch341UsbHost.requestPermission/openAsync()`, `LinuxI2cTransport.open()`, `MfiRuntime.scan()`, `MfiSelfCheck.run()`, or a `RemoteMfiAuthenticationClient` method. Those paths respectively copy/read identity files, request/open USB, actively probe I2C registers, or contact the remote provider. Do not enumerate I2C addresses, open nodes, read credential contents/token values, or connect to the iPhone.

## Smallest justified next phase

**Recommend Phase 3D.2Z — MFi provider metadata snapshot (manual, local-only, no iPhone).** Extend/reuse the existing passive inventory to report the target/configuration-presence booleans above, especially the saved target and the exact I2C node's permissions. Keep secrets unread, avoid I2C/USB opens and network requests, and leave production disabled.

Before any future authentication or CarKit payload, obtain from Geely/Neusoft or an authorized MFi licensee:

1. Written identification of the E01's intended MFi provider (chip/bridge, OEM service, or approved remote provider) and its supported OS/API/access mechanism.
2. Confirmation that the provider/identity is legitimately provisioned and authorized for this CarPlay accessory role; no keys or certificates need to be sent into this audit.
3. Authorized CarKit service/iAP2 sequence documentation, including whether AA00-AA05 is required and what valid failure/success responses are expected.
4. If remote signing is the approved route, the authenticated HTTPS endpoint contract, credential lifecycle, and confirmation that it holds an authorized MFi identity.

**Verdict: PROVIDER READINESS NOT ESTABLISHED; APPLICATION-PAYLOAD TEST BLOCKED.** The next justified action is metadata-only readiness, followed by OEM/MFi documentation—not an active scanner, iPhone connection, or speculative authentication.
