# Phase 3C.3B — MFi authentication provider feasibility audit

## Scope and safety

This is a source and previously collected artifact audit only. No iPhone was
connected; no iAP2 session or authentication was started; no USB, I2C, Bluetooth,
or vendor service operation was performed. No vendor credential, certificate,
private key, or authentication payload was extracted, copied, or reproduced.
`LegacyLaunchBuild.CONNECTIONS_ENABLED` remains `false`.

The evidence base is DiPlay source, the previously extracted Geely/Neusoft and
QDrive APK/native artifacts, and the static findings recorded in
[`COMPATIBILITY.md`](./COMPATIBILITY.md). The collected E01 set does not include
a board schematic/BOM, complete `/dev` or sysfs inventory, SELinux policy,
vendor Binder service implementation inventory for I2C, or the missing Apple JNI
libraries. A negative finding below is bounded by those limits.

## 1. DiPlay authentication architecture

`MfiAuthenticator` is the common provider contract: report a protocol major,
return the accessory certificate bytes, sign a challenge, and optionally provide
BAA certificate material. `Iap2MfiAuthenticationClient` consumes this contract
inside the already-implemented iAP2 CSM exchange:

1. It loads the configured certificate and formats it as an MFI certificate or
   BAA certificate body.
2. On AA00, it sends the certificate as AA01.
3. On AA02, it extracts the challenge, calls `signChallenge`, and sends the
   returned signature as AA03.
4. AA04 is an authentication failure; AA05 is success. Unexpected messages and
   timeouts fail closed.

This is the protocol client/provider seam. No CarPlay protocol redesign is
needed. A provider must supply an identity accepted by Apple's authentication
system; successful local signing or a self-consistent certificate/key pair does
not prove that acceptance.

## 2. Provider inventory and requirements

| Provider/path | Required hardware or files | Permissions, paths, addressing | Certificate/signing contract | API 22 and E01 status |
|---|---|---|---|---|
| `LocalMfiAuthenticationClient` (`MfiTarget.LOCAL`) | Deployment-provided `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`, each nonempty and no larger than 16 KiB. The key is PKCS#8 EC; the certificate file must parse as exactly one X.509 certificate with a P-256 EC public key. The implementation verifies that the key matches that certificate by signing and verifying a random challenge at load. | Reads from the app's private `noBackupFilesDir/offline-mfi`; no external-storage or special Android permission. Build input is explicitly selected using `DIPLAY_AUTH_ASSETS_DIR`; ordinary source/CI builds have no identity. | MFI certificate type only. Signs the supplied 32-byte digest using `NONEwithECDSA` and returns 64-byte raw `r || s`; it does not double-hash. Its source explicitly warns that consistency is not iPhone trust. | No post-22 Android API is apparent in this Java path. API 22 runtime support for the device's EC/`NONEwithECDSA` crypto provider is not demonstrated on the E01. Not presently usable there: the build has no authorized local identity. |
| `MfiAuthenticationClient` + `MfiRuntime` over `LinuxI2cTransport` (`MfiTarget.I2C`) | A compatible MFi authentication coprocessor physically connected to a Linux I2C controller, plus the native `xcertplay_i2c` library built for the device ABI. | Configuration must provide an exact `/dev/i2c-N` path. JNI opens it `O_RDWR` and uses `I2C_RDWR`; the app must already have OS device-node access and SELinux authorization. Android declares no permission that grants arbitrary I2C access, and the app does not change device permissions or policy. The scanner tries 7-bit addresses `0x10`, then `0x11`. | Register-based coprocessor supplies the certificate and performs the challenge signature. See register details below. | The Linux I2C syscalls/JNI design can run on API 22 if the kernel driver, ABI library and access policy allow it. NDK ABI filters are `arm64-v8a`, `armeabi-v7a`, and `x86_64`; no I2C bus/access path is confirmed on this E01. No vendor I2C Binder API was established by the collected artifacts. |
| `Ch341I2cTransport` + `Ch341UsbHost` (`MfiTarget.USB_CH341`) | A CH341 USB-to-I2C bridge and a compatible MFi coprocessor wired to it. No product identity is built in: deployment must configure the bridge VID/PID. Optional reset wiring uses a configured CH341 GPIO D0–D5. | Android USB Host APIs discover/open/claim the bridge after the system USB permission request. There is no special manifest permission for USB Host. Only devices matching configured VID/PID are accepted. I2C defaults to 100 kHz. | Uses the same register coprocessor client as direct I2C. The bridge transports I2C commands; it does not itself supply an Apple identity or sign challenges. | USB Host APIs predate API 22; the compatibility path handles PendingIntent flag differences. The source build has no configured deployed CH341 VID/PID or known attached bridge on the E01. |
| `RemoteMfiAuthenticationClient` (`MfiTarget.REMOTE`) | A reachable remote provider that owns or securely brokers a legitimately authorized MFi identity/signing coprocessor. | Requires `INTERNET` and a configured `http://` or `https://` base URL. It calls `POST /mfi/reset`, `GET /mfi/certificate`, and `POST /mfi/sign`. Although runtime configuration has a token field, the current wire implementation sends the literal `Authorization: ******` (also asserted by its test); this is a placeholder convention, not Bearer-token authentication. The code supports HTTP as well as HTTPS; deployment should require authenticated TLS and a properly designed credential mechanism before real use. | Certificate response includes protocol major, type (`mfi` or `baa`), base64 certificate/package and SHA-256. Signing sends base64 challenge plus request ID; response contains base64 signature. BAA material is split into leaf/intermediate certificates for the iAP2 payload. | Uses `HttpURLConnection` and Android-compatible Base64 code; no API 22-only blocker is apparent. No authorized endpoint, network configuration, or remote identity is established for the E01. A generic signing endpoint is not sufficient evidence of MFi authorization. |

### Provider lifecycle and diagnostic utilities

- `MfiRuntime.scan(I2cTransport)` runs `MfiDeviceScanner` with a 15-second
  startup window and returns `MfiAuthenticationClient` for the first responding
  candidate. It discovers/selects a hardware provider; it does not supply an
  identity itself. The caller owns and closes the transport.
- `MfiDeviceScanner` performs active I2C transactions: it selects register
  `0x00`, separately reads a device-version byte, retries addresses `0x10` then
  `0x11`, and rejects `0x00`/`0xff` values. Its standalone default window is
  two seconds; `MfiRuntime` overrides it to 15 seconds. This is not a passive
  device-node or USB inventory.
- `MfiSelfCheck` wraps the same scanner and then reads protocol-major register
  `0x02`. It does not read a certificate or sign a challenge, but its scanner
  and register access still perform live I2C transactions.
- `LocalMfiProbe` is a standalone `app_process`/ADB diagnostic taking one
  private-identity directory argument. It loads the local files, signs random
  challenges, and verifies them against the supplied certificate, including
  rejection of a changed challenge. It reports local consistency only; it
  neither contacts an iPhone nor proves MFi acceptance. It has no ordinary
  application permission that can grant access to protected identity files.

### Coprocessor register details

The documented software protocol reads device version register `0x00`, protocol
major register `0x02`, certificate length at `0x30`, and certificate data from
`0x31` in windows of at most 128 bytes. Challenge length/data are written at
`0x20`/`0x21`; authentication is started through register `0x10`, status is
polled there, and response length/data are read from `0x11`/`0x12`. Register
`0x05` is used for a best-effort error code.

The I2C device addresses `0x10` and `0x11` are separate from those register
numbers. Seeing `0x10` or `0x11` in source/binary strings alone is not evidence
of a chip or a responding bus device. The scanner selects register `0x00` and
issues a separate read; it is active I2C traffic, not passive enumeration. The
normal runtime `MfiRuntime.scan` allows a 15-second probe window. No scanner,
self-check, register read, challenge, or authentication was executed for this
audit.

## 3. E01 hardware and access evidence

**Evidence against an established E01 coprocessor:** the previously inspected
Geely DEX/APK evidence found no MFi literal/engine, Apple authentication payload,
or I2C coprocessor interface. Its Apple Java wrappers have authentication/status
callbacks and USB/UEvent/NCM hooks, but those do not identify an I2C device or
an MFi signing operation. The collected vendor APK/native-library set contains
no proven MFi coprocessor client connected to a head-unit I2C API.

**Evidence still missing:** no collected source establishes an E01 `/dev/i2c-N`
node, its owner/mode, an app-accessible SELinux rule, a vendor I2C Binder
service, board-level I2C wiring, or an MFi chip at `0x10`/`0x11`. Previous
read-only Apple stack inventory intentionally did not read `/dev`, `/proc`, or
`/sys`; its lack of I2C evidence is not a negative hardware probe.

**Potential app access mechanisms in DiPlay:** direct Linux I2C is possible in
the implementation only when a device node is accessible to the app and the
native library is present; a sideloaded app cannot grant itself those rights.
Android USB Host can access a separately attached CH341 bridge only after
USB permission and explicit VID/PID configuration. No vendor Binder access
path is established. There is no evidence that the stock head unit exposes an
internal authentication coprocessor through any of these paths.

## 4. OEM Apple stack and QDrive

### Geely/Neusoft Apple wrappers

The setting-widget and Wheeljack DEX copies define `AppleInterface`,
`ApplePrivate`, and embedded `AppleService`. The wrappers request the missing
`AppleCore_jni` and `ApplePrivate_jni` libraries by bare library name.
`AppleService` registers an `AppleInterface.OnAuthenticationListener` and
service-death listener, observes USB UEvents, derives `/dev/` paths from USB
device events, queries USB support, and controls device/host mode and `usbncm0`.

The prior static audit found no `.so` inside the inspected Apple APKs and no
Apple JNI binaries in the collected locations. Consequently native imports,
native device paths/configuration, and any native-side coprocessor access are
unresolved. The Java listener is an authentication-state callback, not proof
that it accesses an Apple authentication chip. No Java `/dev/i2c-*`, I2C
Binder, I2C register, `AppleAuth`, or `IAP_AUTH` path was established in the
inspected APKs. It remains possible that unavailable native libraries or
uncollected firmware components contain additional behavior; their contents
cannot be inferred from JNI method names.

### QDrive distinction

The prior QDrive audit established USB Host → file descriptor → JNI →
`libSSPAirPlayUSB.so`/`libusbserver.so` → usbmuxd/Lockdown. That is an iPhone
USB/Lockdown path, not evidence of Apple's MFi accessory certificate/challenge
authentication. QDrive's own activation/signature helper and its AirPlay/QDLink
stack must not be treated as an MFi provider. No QDrive MFi coprocessor
register client, Apple certificate/signature provider, or implemented
CarPlay-authentication path was established. This audit did not extract or
inspect any private-key material from those binaries.

## 5. Legitimate solution options

| Option | Classification | Evidence and what is still required |
|---|---|---|
| **A. Existing E01 MFi authentication hardware** | **NOT ESTABLISHED** | No board, node, service, or responder evidence in the collected artifacts. This is not proof that the physical board lacks a chip. To establish it safely requires authoritative hardware/service documentation or a separately authorized, narrowly scoped detection plan after read-only confirmation of the bus and app access. |
| **B. Authorized external MFi authentication coprocessor** | **POSSIBLY VIABLE** | DiPlay already has Linux-I2C and CH341-to-I2C transports and a register client. It requires a currently authorized coprocessor whose certificate/signing protocol is compatible, plus either permitted `/dev/i2c-N` access or an explicitly configured CH341 bridge. Hardware authorization and E01 attachment are not established. |
| **C. Authorized remote MFi signing/provider service** | **POSSIBLY VIABLE** | The remote client implements the certificate/sign/reset API and challenge signing. It requires an authorized provider that controls the legitimate identity, a reachable endpoint, authenticated TLS, and protected service credentials. No such service is configured or verified. |
| **D. Properly provisioned local accessory identity** | **POSSIBLY VIABLE** | Local provider code accepts a matching P-256 PKCS#8 key and X.509 certificate in the documented app-private asset location. It becomes viable only if those materials are issued/provisioned for this accessory by the authorized MFi owner. No such assets are present in the ordinary build or established for this project. Do not substitute an identity from another accessory. |

No option is currently shown to be usable on the E01. Local software signing
does not create MFi authorization; only an authorized identity/provider can
complete that boundary.

## 6. Minimum legitimate component and next-step decision

The minimum missing component is **one authorized MFi identity/signing provider**
that supplies the certificate expected by the iAP2 exchange and signs its
challenge: either a compatible MFi coprocessor reachable through an already
authorized I2C/CH341 path, a properly provisioned local identity, or a
legitimate remote provider. The DiPlay AA00–AA05 exchange is already the
consumer of that component.

**Phase 3C.3C hardware authentication detection is not justified yet.** The
available evidence does not identify an E01 bus/node/service, and DiPlay's
existing scanner performs I2C register-selection writes and reads. First obtain
read-only authoritative information about any board I2C bus, device-node
ownership/SELinux policy, or vendor service contract. Only if that establishes
a candidate path should a separately approved, narrowly scoped detection test
be considered. Do not use the app scanner as an exploratory all-bus probe.

## 7. Required conclusions

1. **DiPlay authentication architecture:** an implemented AA00–AA05 iAP2 client consumes the `MfiAuthenticator` certificate and challenge-signing contract.
2. **Available providers:** local identity, direct Linux I2C coprocessor, USB CH341-to-I2C coprocessor, and remote certificate/signing service.
3. **Provider requirements:** local PKCS#8 P-256 key plus one X.509 certificate; I2C needs an accessible `/dev/i2c-N` and compatible chip at a supported address; CH341 needs a configured bridge and compatible chip; remote requires the documented API and an authorized backend.
4. **API 22:** no obvious post-22 framework dependency blocks these provider implementations. CH341 uses API-22-capable USB Host APIs and flags; local EC/`NONEwithECDSA` crypto-provider behavior and packaged native I2C operation still require target-firmware validation. No such runtime validation was performed.
5. **E01 coprocessor evidence:** none found in collected APK/native metadata; absence is unproven because board, device-node, Binder, and missing Apple native-library evidence is incomplete.
6. **Possible E01 access:** an app-accessible Linux I2C node or a vendor service could work if documented/authorized; USB Host could reach an added CH341 bridge. None is established on this unit.
7. **OEM Apple authentication:** Java exposes authentication/status hooks, but no coprocessor access or MFi signer is proven. Missing Apple JNI libraries prevent a complete native audit.
8. **Legitimate alternatives:** authorized external coprocessor, properly provisioned local identity, or authorized remote provider; each must own a legitimate Apple identity/signing capability.
9. **Minimum remaining component:** the authorized certificate-and-signing provider itself, plus its supported access path.
10. **Phase 3C.3C:** do not start active hardware detection yet; gather read-only bus/service/access evidence first.

**Final verdict: INSUFFICIENT EVIDENCE**
