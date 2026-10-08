# Phase 3D.3A - E01 MFi authorization path audit

**Scope:** Static DiPlay source, existing Geely/Neusoft artifact audits, and the reported 3D.2Z/Phase 3C.3C E01 observations. No device, credential, I2C node, or provider was accessed in this audit. No application source changed.

## Confirmed evidence

- The real 3D.2Z snapshot reported `MfiTarget=NOT_SET`, effective target `LOCAL`, `LOCAL=INACCESSIBLE`, `packagedPairPresent=false`, `privatePairPresent=false`, no matching CH341 descriptor, `/dev/i2c-1` present but `INACCESSIBLE`, REMOTE not configured, and `AUTHORIZATION_UNVERIFIED`.
- `AirPlayPersistence.loadMfiTarget()` resolves an absent/unrecognized stored target to LOCAL. `CarPlayHostActivity` passes that effective target into `CarPlayRuntimeConfig`; the runtime constructor's USB_CH341 default does not override the activity's explicit target.
- `docs/BUILD.md` states ordinary source/CI builds contain no accessory identity. Runtime identity assets are copied only when explicit `DIPLAY_AUTH_ASSETS_DIR` inputs are provided. This makes absent LOCAL credentials the expected outcome for an ordinary source-only APK, but the 3D.2Z snapshot does not prove how the installed E01 APK was packaged.
- The previous real-E01 passive I2C inventory observed `/dev/i2c-0` through `/dev/i2c-3`, all root-only mode `0600`, with no identifying Apple/MFi/authentication evidence among registered sysfs clients. `/dev/i2c-1` is one of those nodes, explaining why it can exist while remaining inaccessible to the ordinary app.

## LOCAL status root cause

`MfiProviderSnapshot.report()` calculates:

- `packagedPairPresent` from whether `AssetManager.list("offline-mfi")` returned both expected filenames. A false result does not distinguish an empty listing (resources absent) from a failed/null listing (metadata unavailable).
- `privatePairPresent` as true only if both `identity.pk8` and `certificate.p7b` have metadata `exists=true`, are regular files, and are readable. A false result means that pair condition failed; it does not report which file or which subcondition failed.
- `LOCAL=INACCESSIBLE` when the asset listing is null, either file metadata call has an error, or an existing file is not a readable regular file. Otherwise, if neither pair is complete, it reports `NOT_CONFIGURED`.

The concrete metadata behavior explains the ambiguity: `AndroidPassiveI2cInventoryFiles.stat()` maps `ENOENT` to `exists=false` **and still sets a non-null error string**. `AndroidMfiProviderSnapshotSource.credentialFile()` propagates that to `error=true`; the snapshot treats any such error as inaccessible. Therefore, genuinely missing private files can produce `LOCAL=INACCESSIBLE`, even though the underlying condition is absence rather than a filesystem permission denial. Separately, an `AssetManager.list()` exception becomes null and also produces `INACCESSIBLE`. The hardware report's two false pair booleans do not distinguish these cases.

Robolectric does not provide a reliable Android/API22 filesystem `Os.lstat` model for this app-private path: its metadata fixture returned unknown/error for locally created sandbox files. That test limitation cannot establish what happened on the E01. The device's own snapshot is evidence that both pairs were not recognized as present, but its combined booleans/status are insufficient to determine whether the private files were missing, metadata access failed, or one existing file was inaccessible. Do not infer that credentials were present and blocked, and do not read them to resolve this.

## Geely/Neusoft interface findings

Existing `docs/COMPATIBILITY.md` static audits of the collected Geely/Neusoft artifacts report:

- `AppleInterface`, `ApplePrivate`, and an embedded `AppleService` Java wrapper contain native-load, USB/UEvent, mode, and authentication-state callback hooks.
- The Java `OnAuthentication(int,int)` callback reports state; it is not itself an MFi certificate/challenge/signing provider.
- The inspected Settings/Wheeljack APKs do not contain native libraries; `AppleCore_jni` and `ApplePrivate_jni` are referenced but missing from the inspected APK/device search. The embedded service is not registered in the inspected manifests, and an earlier package query did not resolve `com.neusoft.appleservice`.
- The Java wrappers expose USB state/mode, USB support, and `usbncm0`/network-interface control hooks. No Java I2C register client, MFi coprocessor protocol, certificate source, or `signChallenge` interface was identified.
- QDrive's `libusbserver`/usbmuxd/Lockdown path is a USB/Lockdown precedent, not evidence of an MFi authentication provider. No collected artifact establishes a supported Binder service for an MFi signer.

These are bounded findings from collected artifacts and prior device inventory, not proof that every firmware partition or uncollected OEM package lacks an implementation. The interfaces are **potential OEM leads but unsupported as usable MFi providers by current evidence**: their implementation depends on missing/unregistered components, and no documented app-accessible contract or legitimate identity was found.

## I2C reconciliation

The passive inventory establishes node metadata, not chip identity or bus response:

- `/dev/i2c-1` exists as a character device but is root-only `0600`; the snapshot's app-context access checks therefore report `INACCESSIBLE`.
- No Apple/MFi/authentication identity was found in the registered sysfs client metadata. Generic bus/client addresses are explicitly not MFi evidence.
- A physical chip with no registered identifying client is not ruled out; the passive inventory does not open nodes or issue transactions. The active `MfiDeviceScanner` and `MfiSelfCheck` would perform register-select writes and reads and are not authorized by this phase.
- No OS permission, SELinux allowance, vendor Binder contract, board connection, or OEM method granting DiPlay access to an internal MFi coprocessor is established.

## Readiness and feasibility verdict

**No established legitimate E01 MFi authorization path.** DiPlay's LOCAL/I2C/CH341/REMOTE providers are generic software implementations and do not establish an authorized credential or E01-accessible coprocessor. The source-only build lacks local identity assets; the observed internal I2C nodes are inaccessible; no CH341 bridge was enumerated; no remote provider is configured. The OEM Apple wrappers are **potentially relevant but unverified**, not a usable or authorized provider.

Confirmed: provider code and AA00-AA05 consumer exist; W transport/TLS success does not authenticate MFi; the E01 reports no configured usable path. Unknown: whether uncollected OEM software/hardware contains a signer, whether the vendor supports an application-facing interface, which provider/identity is approved for this product, and any service/entitlement policy beyond public MFi program scope.

## One recommended next action

Send **one formal documentation request to Geely/Neusoft's authorized CarPlay/MFi integration contact** asking them to identify the E01's approved MFi authentication provider and its supported application access contract. Request the provider's product/package or component identity, supported API22 access method and OS permissions, how a legitimate accessory identity is provisioned/authorized, and the approved CarKit/iAP2 authentication sequence. Do not request or transmit private keys or certificate contents.

Success means the vendor supplies a specific supported provider/interface and confirms DiPlay is authorized to use it, including the permission/credential provisioning path. If they cannot identify such a provider or authorize the application path, record the E01 MFi route as unavailable and stop; do not compensate with probing, reverse-engineering guesses, or a payload test.
