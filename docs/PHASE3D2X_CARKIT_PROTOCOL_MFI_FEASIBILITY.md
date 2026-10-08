# Phase 3D.2X - CarKit protocol and MFi feasibility

**Scope:** Static/read-only investigation. No iPhone connection, CarKit payload, pairing change, APK build, or hardware test was performed. Production CarPlay remains disabled.

## Executive finding

The repository proves that Phase 3D.2W can discover `com.apple.carkit.service`, establish a second TCP connection, authenticate TLS with the stored paired-device identity, and cleanly StopSession. It does **not** reveal the service's private application protocol or establish that a legitimate MFi provider is available on the E01. Do not send CarKit application data yet.

## Evidence

### Confirmed in DiPlay

- [LockdownCarKitClient](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt) sends encrypted Lockdown `StartService` for `com.apple.carkit.service`, opens the returned port, and uses TLS when `EnableServiceSSL=true`. W hardware testing independently confirmed the TCP/TLS and paired-device certificate validation.
- [CarPlayController](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt) wraps the service stream in `Iap2Session`/CSM and invokes [Iap2WiredControlClient](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2WiredControlClient.kt).
- DiPlay's wired sequence is Identification, MFi AA00-AA05 authentication, power announcement/subscriptions, then wait for CarPlay availability before sending CarPlayStartSession. The MFi client sends the accessory certificate on AA00, signs the AA02 challenge, sends AA03, and requires AA05 success. This establishes DiPlay's intended protocol path, not Apple's normative specification or iPhone acceptance.
- Generic legitimate provider contracts exist for provisioned local credentials, an I2C MFi coprocessor, USB CH341-to-I2C, and remote signing. These are implementation options, not proof a working/authorized provider is installed on the E01.
- The production launch guard remains disabled (`LegacyLaunchBuild.CONNECTIONS_ENABLED=false`).

### E01 provider evidence

The documented real-E01 Phase 3C.3C passive inventory found `/dev/i2c-0` through `/dev/i2c-3`, all root-only mode `0600`, and no Apple/MFi/authentication evidence in registered sysfs clients. The [MFi provider audit](PHASE3C3B_MFI_PROVIDER_AUDIT.md) found no established vendor I2C Binder path, deployed CH341 bridge, authorized local identity, or remote provider. It also notes that inspected Geely/Neusoft Java artifacts expose Apple authentication-state wrappers, but missing Apple JNI implementations prevent attributing actual authentication behavior to them. These findings are bounded by the collected artifacts and do not prove the physical board lacks uninspected hardware or services.

### Legitimate references

- Apple's [MFi Program overview](https://mfi.apple.com/en/how-it-works.html) says the program provides technical specifications/resources and lists CarPlay, iAP2, and authentication coprocessors among licensed technologies.
- Apple's [MFi eligibility guidance](https://mfi.apple.com/en/who-should-join.html) says iAP2 over USB and vehicles supporting CarPlay over USB are within the MFi Program. This is program/certification evidence, not a packet-level statement that every CarKit request triggers MFi authentication.
- Apple's [CarPlay developer page](https://developer.apple.com/carplay/) describes app categories and requesting a CarPlay app entitlement. That app entitlement is not evidence of an Android head unit's accessory entitlement or of the CarKit service protocol.
- The MFi program pages do not publish the `com.apple.carkit.service` application protocol. The repository's iAP2/CSM implementation is an implementation reference, not a substitute for authorized Apple/OEM protocol documentation.

## Known, unknown, and assumptions

| Topic | Status |
|---|---|
| StartService endpoint and service TLS | Confirmed by 3D.2V/2W hardware results. |
| Post-TLS application protocol used by DiPlay | Source shows iAP2 CSM and wired iAP2 control. |
| Whether Apple's CarKit service requires exactly this iAP2 sequence, other service messages, or OEM-specific framing | Unknown; not observed in W because W sent no application data. |
| MFi authentication in DiPlay's full wired CarPlay path | The implemented path requires AA00-AA05 success before power/subscriptions/availability. |
| Whether StartService or service TLS themselves require MFi | Not established; W succeeded through TLS without sending an iAP2/MFi payload. |
| Runtime MFi requirement for a compatible production CarPlay accessory | Apple places CarPlay-over-USB and iAP2-over-USB in the MFi program; exact target/OEM authorization details are not public here. |
| Additional app entitlement / head-unit authorization | No head-unit entitlement requirement can be inferred from the iOS CarPlay app entitlement page. OEM/private service authorization requirements remain unknown. |
| Legitimate E01 provider | None established in collected evidence. Generic provider interfaces exist, but there is no confirmed authorized identity or usable E01 path. |

## Prerequisites before any CarKit application payload

1. Obtain authorized Geely/Neusoft or MFi-licensee documentation identifying the CarKit service protocol and the required authorization/certification model. Do not infer it from a service port or TLS success.
2. Establish a legitimate MFi identity/signing provider and an authorized access path for this E01. A locally consistent key/certificate, generic I2C address, or remote signing endpoint is not proof of Apple authorization.
3. Confirm the protocol sequence expected by the service against an authorized reference. For the DiPlay implementation, the CSM must be ready, identification accepted, and MFi AA00-AA05 successful before power/subscription/availability or CarPlayStartSession messages.
4. Preserve the existing paired-record association and strict Lockdown/service TLS peer pinning. Keep all production CarPlay paths disabled and require separate explicit approval for any future payload test.

## Recommended next phase

Do not transmit a CarKit application payload. First obtain OEM/MFi-provider confirmation of the CarKit protocol, authorization requirement, and a legitimate E01 provider/interface. The smallest safe diagnostic after that is a **no-iPhone, no-I2C-transaction provider-readiness snapshot**: report only the selected `MfiTarget`, whether configured local credential files exist (metadata only; never read/log/sign), whether an I2C path is configured and its stat/access booleans, whether a CH341 VID/PID is configured and matching USB descriptors are visible without opening/requesting permission, and whether a remote endpoint is configured (scheme/host redacted, no network request). This can resolve whether a provider path is configured; it cannot prove Apple authorization or protocol interoperability.

**Verdict: BLOCKED pending authorized protocol/provider evidence.** No speculative authentication, entitlement bypass, certificate relaxation, credential handling, APK build, or hardware operation is appropriate in 3D.2X.
