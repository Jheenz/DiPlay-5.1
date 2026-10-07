# Phase 3D.2C - iPhone USB configuration transition static audit

## Verdict and scope

**Superseded hardware proposal:** completed real-E01 Phase 3D.2C2 scanning
found no case-sensitive `Valeria` substring (strings included `PTP`,
`Apple USB Multiplexor` and `AppleUSBEthernet`). The applicable branch is
FALSE/vendor-request, not the provisional TRUE/config4 test below.
Do not execute that historical configuration-selection proposal.
See the [Phase 3D.2D vendor-request audit](PHASE3D2D_QDRIVE_VENDOR_REQUEST_AUDIT.md)
for the recovered isolated operation and proposed, unimplemented 3D.2E test.

**Phase 3D.2C1 clarification:** the original description below of a `Valeria`
gate was incomplete. PLT/GOT relocation resolution establishes case-sensitive
`strstr`: the first checked interface string **containing** `Valeria` returns
helper TRUE and permits configuration selection. Exhaustion without any
substring match returns FALSE and takes the vendor branch.
See the [exact discriminator audit](PHASE3D2C1_QDRIVE_VALERIA_DISCRIMINATOR.md).
The read-only discriminator was subsequently completed in Phase 3D.2C2;
the historical proposed test below is superseded as stated above.

**CONFIGURATION TRANSITION PARTIALLY IDENTIFIED**

Standard configuration selection before USBMUX is present in both DiPlay and
QDrive. QDrive's audited device-add branch would select value **4** for a device
with four configurations, subject to its interface-string gate. The supplied
E01 descriptors independently establish that configuration 4 contains USBMUX.
This supports a proposed, isolated `1 -> 4` configuration/readback experiment,
not a claim that the transition has already succeeded on E01.

The complete CarPlay mode-transition path is not established for this phone.
DiPlay requires CDC-NCM descriptors absent from the reported layout; QDrive has
a separate conditional vendor request whose applicability to this E01 sample
has not been proven. Configuration selection, vendor mode selection, USBMUX
communication and CarPlay authentication are different operations.

This audit performed source inspection, local APK DEX extraction/disassembly,
ELF symbol/relocation/string inspection and instruction-level static analysis.
No vendor code or JNI was loaded or executed. No USB operation, configuration
change, claim, transfer, permission request or transport startup was performed.
No runtime code, selector, diagnostic button or build setting was changed.
No Phase 3D.2D implementation, tests, build or hardware execution was performed.

## 1. Proven current real-hardware state

These are the user's Phase 3D.2A/3D.2B E01 observations, not measurements made
during this audit:

| Property | Proven observation |
|---|---|
| Connection | Direct iPhone; Carlinkit completely removed |
| VID / PID | `0x05AC:0x12A8` |
| Android USB permission | Granted |
| Device open | Successful |
| Standard GET_CONFIGURATION | Successful, one-byte response |
| Active bConfigurationValue | **1** |
| Active descriptor name | **PTP** |
| USBMUX in active configuration | Absent |
| Apple USB Ethernet in active configuration | Absent |
| Connection close | Successful |

| Configuration value | Descriptor name | Relevance to this path |
|---|---|---|
| 1 | PTP | Current state; no USBMUX or Apple USB Ethernet |
| 2 | iPod USB Interface | Not established as a USBMUX configuration; its name alone proves neither USBMUX nor CarPlay |
| 3 | PTP + Apple Mobile Device | USBMUX interface present; no Apple USB Ethernet |
| 4 | PTP + Apple Mobile Device + Apple USB Ethernet | USBMUX plus proprietary Apple USB Ethernet descriptors |

Configurations 3 and 4 each contain interface ID 1, alternate 0,
class/subclass/protocol `255/254/2`, BULK OUT `0x04`, BULK IN `0x85`
(reported maximum packet size 512). They explain the descriptor-identical
flattened candidates at indexes 6 and 8. Neither flattened index identifies an
active configuration.

Configuration 4 additionally contains interface ID 2, `255/253/1`, alternate
settings 0/1/2; alternates 1/2 expose BULK IN `0x86` and OUT `0x05`.
These are Apple USB Ethernet candidates, **not USBMUX interfaces**. The supplied
layout does not establish CDC-NCM control `02/0D` and data `0A` interfaces.

## 2. Existing DiPlay wired behavior

### End-to-end order

The disabled normal wired path in
[CarPlayController](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)
is:

1. `start()` checks `LegacyLaunchBuild.CONNECTIONS_ENABLED`, registers wired
   receivers, then starts MFi discovery. This is not a configuration-only entry
   point and must not be invoked for the proposed experiment.
2. iPhone discovery uses
   [IphoneUsbMatcher](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt):
   exact configured identities or Apple VID matching. `0x12A8` has no special
   configuration policy.
3. Permission handling calls `IphoneCarPlayConfiguration.find(device)` over
   **all advertised configurations**, not just the active configuration.
4. If a complete configuration is found, it opens the data paths. Otherwise it
   sends the vendor transition described below, closes that connection and
   waits for an Apple attach event. At most two transition attempts are allowed.
5. It opens the USBMUX bulk connection, selects a configuration with the public
   Android API, then claims the configuration-scoped USBMUX interface.
6. It opens a separate NCM connection, discovers/claims NCM interfaces and
   selects the data alternate setting.
7. Only then does `runStack()` perform USBMUX handshake, Lockdown pairing,
   session/CarKit operations and iAP2 session creation.
8. It attaches the already-open NCM bridge to VPN/AirPlay after iAP2 session
   creation and before wired iAP2 control/authentication runs.

Relevant source locations: controller `start()` around line 408,
permission/configuration decision around 1682, transition/attach handling around
1750, data-path/NCM opening around 1789 and `runStack()` around 1829.

### Two different transitions

| Operation | Existing implementation | Meaning |
|---|---|---|
| Vendor mode request | `IphoneUsbHost.requestCarPlayReenumerationAsync()`: type `0xC0`, request `0x52`, value 0, index 4, IN length 1, timeout 1000 ms | Apple vendor-mode request; **not** standard SET_CONFIGURATION |
| Standard configuration selection | `IphoneUsbHost.openIap2UsbSession()`: `connection.setConfiguration(configuration)` | Selects the discovered configuration's ID/value before USBMUX claim |

The vendor request's **wIndex 4 is not proof of bConfigurationValue 4**.
The comments cite LIVI commit
`0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3` and a mode/configuration history,
but current executable Kotlin selection is descriptor-based. Comments mentioning
configuration 6 are not evidence that this phone exposes or should select 6.

### What the selector actually requires

[IphoneCarPlayConfiguration.find](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneCarPlayConfiguration.kt)
prefers the first advertised configuration containing:

- USBMUX `FF/FE/02`;
- CDC-NCM control `02/0D`;
- Apple USB Ethernet `FF/FD/01`.

Its fallback still requires **USBMUX plus CDC-NCM**. It does not fall back to
USBMUX alone or USBMUX plus proprietary Apple Ethernet alone. It uses
configuration objects/IDs, handles multiple configurations, and logs alternate
settings, but does not query GET_CONFIGURATION or prefer an already-active
configuration. Ordering resolves multiple qualifying configurations.

Within the selected configuration it finds `FF/FE/02` and prefers BULK
`0x04/0x85`, with a unique bulk pair fallback. No flattened index 6/8 is needed.

**Against the supplied E01 layout:** configuration 3 lacks Ethernet/NCM;
configuration 4 has Apple Ethernet but has no established CDC-NCM control
interface. Neither is shown to satisfy this normal CarPlay selector. It would
therefore enter the vendor-mode branch rather than simply select 3 or 4.

An existing caveat: if public `setConfiguration()` returns false, the normal
host logs failure and continues to claim anyway. This is not acceptable
evidence of a successful transition and must not be copied into a diagnostic.
No behavior was changed in this static phase.

### Re-enumeration and alternate settings

The vendor-transition path explicitly expects a new attach event, closes its
old connection and rechecks permission using the newly attached device.
Permission polling has a deadline and transition attempts are capped, but the
inspected `WaitingForReenumeration` path has no dedicated elapsed-time deadline.
The availability polling helper is not a bounded re-enumeration transaction.

[NcmFunctionDiscovery](../shared/src/main/java/com/shilapi/xcertplay/transport/NcmFunctionDiscovery.kt)
currently calls only `findCdcNcm()`: control `02/0D`, data `0A`, preferring a
bulk-bearing data alternate 1. Apple Ethernet constants do not implement
discovery of `FF/FD/01`.

[NcmUsbBridge.open](../shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt)
claims the discovered control/data interfaces and calls `setInterface(data)`.
It contains same-interface-ID alternate-setting handling, but that handling
does not make the current CDC-only finder recognize E01 interface 2.

The isolated
[Phase 3D.2 selector](../shared/src/main/java/com/shilapi/xcertplay/transport/DirectUsbMuxAccess.kt)
remains unchanged: exactly one matching **flattened** USBMUX candidate or STOP.
Activating configuration 4 must not be assumed to remove the duplicate
advertised descriptors or make that old selector safe to run.

## 3. Collected QDrive static evidence

### Audited artifacts

All native offsets below are ELF virtual addresses in the collected ARM
libraries, not source lines or runtime addresses. Static function names are
stripped in parts of the library; nearest-export disassembler labels are not
used as function identity. Thumb instructions and ARM interworking/PLT veneers
were decoded in their respective instruction modes.

| Artifact | SHA-256 |
|---|---|
| [QDrive_Global.apk](../vendor-apks/QDrive_Global/QDrive_Global.apk) | `C8907EDA5D29C7DD6F7ECD4C7FD56EC24EE87AA16D7787749449698154773732` |
| [libusbserver.so](../vendor-apks/QDrive_Global/lib/arm/libusbserver.so) | `87748337AB3B0BB70AFB037E412454C74B1BB419CA01E270B666EB225C8AEA54` |
| [libSSPAirPlayUSB.so](../vendor-apks/QDrive_Global/lib/arm/libSSPAirPlayUSB.so) | `355284ADC3B9E8ECCD1C9478859068AAE976A2F123749EA6BB7DE64A73682ED9` |
| [libSSP_Main.so](../vendor-apks/QDrive_Global/lib/arm/libSSP_Main.so) | `A426236D3919D5395A9E9F686CFB3D740FE8B272F452F8D4AB557944390CB67A` |

### Java/JNI handoff

APK DEX `PhoneMux.isIosDevice()` accepts VID `0x05AC` and
`0x1290 < PID < 0x12AF`; this includes `0x12A8`.
`PhoneMux.registDevice()` (DEX method code at `0x1FD428`) opens the Android
device and passes its file descriptor and device path to
`JNI_SSP_AddUsbDevice(int, String)`. The inspected DEX contains no Java
`setConfiguration`, `setInterface`, `controlTransfer` or `bulkTransfer` call.
This places the relevant transition below the Java handoff, not in Android
Java USB configuration logic.

`libSSPAirPlayUSB.so` exports `JNI_SSP_AddUsbDevice` at `0x5888`; its call at
`0x58EA` resolves to `UsbScreen_AddUsbDevice`.
`libusbserver.so` exports that entry at `0x84974`; its branch at `0x8497A`
enters the device-add routine at `0x95A60`. That routine uses the supplied
FD/path via `libusb_get_device2` and `libusb_open2` before inspecting descriptors.
This is an actual device-add call chain, not merely a generic libusb export.

### Configuration selection before USBMUX claim

| Native evidence in libusbserver.so | Interpretation |
|---|---|
| `0x95B2A`: device descriptor fetched into stack offset `0x24` | Establishes descriptor byte provenance |
| `0x95B44` / `0x95B50`: VID/PID read and Apple range checked | Includes the real `05AC:12A8` identity |
| `0x95B68`: helper `0x95574` called | Interface-string gate before configuration branch; TRUE on first substring match |
| Helper iterates configuration descriptors and first alternate per interface's `iInterface`; string at `0x26DD01` is `Valeria` | Case-sensitive strstr; non-null returns TRUE, null continues; actual strings remain unknown on E01 |
| `0x95B82` through PLT `0x78E24`: `libusb_get_configuration` | Reads current selection |
| `0x95BE0`: reads stack byte `0x35` | This is descriptor base `0x24` plus offset `0x11`: **bNumConfigurations** |
| `0x95BE6`: compares current value against that byte | No configuration-name or highest-interface-count policy |
| `0x95BEE` and driver-check/detach loop | Inspects currently active interfaces before changing configuration |
| `0x95CBC`: target argument loaded from stack byte `0x35`; `0x95CC2` through PLT `0x78E6C` calls `libusb_set_configuration` | Standard configuration-selection path uses **bNumConfigurations as the requested value** |
| `0x95CE0`: active descriptor fetched again | Same-handle verification/discovery after selection; no mandatory disconnect here |
| `0x95D90` to `0x95DA0`: `FF/FE/02` matching | USBMUX interface selection in the active descriptor |
| `0x95DD6` through PLT `0x78E78`: `libusb_claim_interface` | Claim happens **after** configuration selection |

The log string at `0x26E18B` describes changing configuration from one value
to another; the argument provenance and resolved call above prove the target
more strongly than strings or exported libusb capabilities alone.

For **four configurations**, this audited branch requests **value 4**. It does
not explicitly prefer value 3 or an Apple-Mobile-Device-only configuration.
On this phone, descriptor mapping happens to confirm a configuration whose ID
is 4. The algorithm's configuration-count/value equivalence is not a USB rule
and must not be copied for other descriptor layouts.

Upstream [usbmuxd 1.1.1 USB source](https://github.com/libimobiledevice/usbmuxd/blob/1.1.1/src/usb.c)
provides corroborating historical context: it selects a desired configuration
before finding/claiming USBMUX, deriving it from configuration count with a
ceiling of 4. The QDrive instructions above establish their own behavior;
upstream similarity does not prove binary identity. No equivalent count clamp
is established in the audited QDrive branch.

### Separate conditional vendor request

When no checked interface string contains `Valeria` (or there are no
interfaces), the helper returns false and the routine takes a **different**
branch at `0x95B9A` and calls `libusb_control_transfer` at `0x95BB8` with:

- request type `0x40` (vendor, device, HOST_TO_DEVICE);
- request `0x52`;
- value 0;
- index **2**;
- null data, length 0;
- timeout argument 0.

It then leaves this device-add attempt on its failure/return path rather than
immediately continuing through the standard configuration/USBMUX-claim path.
This is evidence of a mode-transition attempt, **not SET_CONFIGURATION(2)**.
It differs from DiPlay's IN/index-4/one-byte vendor request. Neither request
should be transplanted into the proposed standard-configuration experiment.
The audited branch does not prove an E01 detach/reattach event, changed
descriptor set, or successful subsequent USBMUX connection.

The real report supplied configuration names and descriptor tuples, not the
first-alternate interface strings. Therefore it does not establish which
QDrive branch would execute for this particular attached phone.

### Other libraries and Ethernet limits

`libSSP_Main.so` exposes USBMUX connection-state notifications.
Its `SSP_MainAPP_vNotSwitchUSBchannel` / `vSwitchUSBchannel` at
`0x193C8` / `0x193CC` resolve through interworking veneers/PLT to
`ssp_hc_vNotSwitchUSBchannel` / `vSwitchUSBchannel` at
`0x1B4EC` / `0x1B4EE`. Both platform hooks immediately return in this
collected library. Their names are not evidence of SET_CONFIGURATION.

`libusbserver.so` exports generic `libusb_set_interface_alt_setting`, but the
audited device-add/USBMUX path has no demonstrated call activating E01 Apple
Ethernet interface 2 alternate 1 or 2. Neither the wrapper nor the inspected
main-library handoff establishes such a transition. This bounded negative
finding is not proof that all proprietary networking code is absent.

Selecting value 4 makes the advertised Apple Ethernet function part of the
selected configuration, but does **not** establish a working NCM driver,
activate a bulk-bearing Ethernet alternate, or authenticate/start CarPlay.

## 4. Configuration to precede USBMUX

Descriptor-level answer: **either 3 or 4 has the expected USBMUX function;
1 does not, and 2 is not established as suitable.**

Evidence-ranked next experiment:

1. **Provisional standard-only `config 1 -> config 4 -> readback/STOP`.**
   The concrete QDrive configuration argument supplies evidence for 4 beyond
   simply choosing the configuration with the most interfaces. E01 descriptor
   mapping confirms USBMUX and Apple Ethernet membership in that value.
2. Configuration 3 is a valid descriptor-supported, USBMUX-only alternative,
   but this audit found no QDrive/DiPlay policy preferring it. Do not attempt it
   automatically if selecting 4 fails.
3. A future USBMUX diagnostic would use the **readback-confirmed active
   configuration's** interface ID 1/alt 0, not flattened index 6 or 8. That
   configuration-scoped diagnostic is a separate future change.

Neither a successful public selection on E01 nor a USBMUX response is yet
proven. The normal DiPlay CarPlay selector's CDC requirement remains unresolved
even if configuration 4 becomes active.

## 5. When Ethernet should appear

For the mapped descriptor set, proprietary Apple USB Ethernet belongs to
configuration 4 already; no CarKit/iAP2 event is needed to explain its
**descriptor presence**. Configuration 3 has no such function.

DiPlay expects an NCM-capable function **before USBMUX handshake, Lockdown,
CarKit and iAP2 session/authentication**, and retains its bridge through the
wired session. MFi provider discovery is earlier still. VPN/network use is
attached later, after iAP2 session creation. Descriptor presence, alternate
activation, network readiness and authorization must not be conflated.

No later `setConfiguration()` in this wired path enables Ethernet after iAP2
negotiation. A separate vendor-mode change can alter the advertised descriptor
set earlier, but this audit does not establish that outcome on E01.
Proprietary `FF/FD/01` Ethernet is not interchangeable with the current
CDC-NCM `02/0D` + `0A` discovery implementation.

## 6. Re-enumeration, connection lifetime and permissions

Standard configuration selection does not inherently require a new USB address
or Android detach/attach event. QDrive continues with its existing libusb
handle, retrieving the active descriptor and claiming USBMUX afterward.
DiPlay likewise selects configuration and claims on one connection.

That is distinct from DiPlay's vendor-mode branch, which explicitly expects
re-enumeration. Device-specific behavior on E01 after a standard selection is
still unknown; handle either same-device completion or real detach/reattach.

For a future diagnostic:

- Hold no interface claims during selection.
- Do not carry endpoint/session assumptions across the change.
- Use GET_CONFIGURATION, not list ordering or `setConfiguration()`'s boolean
  alone, to establish the result.
- Close the transition connection after its operation completes; reopen from a
  fresh UsbManager device list for independent confirmation.
- After a real detach, discard the old UsbDevice/UsbConfiguration/UsbInterface
  objects and reacquire descriptors and permission for the new device.
- Do not infer that the old permission grant survives re-enumeration. If the
  fresh device lacks permission, STOP without requesting it.
- If multiple Apple devices appear, or the device cannot be correlated
  unambiguously, STOP; do not use unique identifiers merely to force selection.
- Put an elapsed-time bound on observing reappearance; no endless retry loop.

## 7. Safest Android API22 mechanism

Prefer **`UsbDeviceConnection.setConfiguration(UsbConfiguration)`**, using a
validated configuration object from the currently selected device.

Android 5.1.1/API22 implementation evidence:

- [UsbDeviceConnection.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-5.1.1_r38/core/java/android/hardware/usb/UsbDeviceConnection.java):
  public setter passes `configuration.getId()` to native code.
- [USB JNI implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-5.1.1_r38/core/jni/android_hardware_UsbDeviceConnection.cpp):
  passes that ID to `usb_device_set_configuration`, returning true for result 0.
- [libusbhost usbhost.c](https://github.com/aosp-mirror/platform_system_core/blob/android-5.1.1_r38/libusbhost/usbhost.c):
  uses `USBDEVFS_SETCONFIGURATION` on the existing file descriptor.

This is the public API22 path already used by DiPlay. It lets the USB host
backend perform configuration selection through its configuration operation.
A raw standard control transfer (`0x00`, request `0x09`, value=configuration
value, index 0, no data) describes the wire request but is not the preferred
replacement for the host-stack configuration API or an automatic fallback
when it fails.

**Timeout limitation:** the public setter has no timeout/cancellation argument.
A future diagnostic must run it off the UI thread and distinguish a watchdog
deadline from actual cancellation of the native operation. It must not claim
that `Future.cancel()` or a 1000 ms GET_CONFIGURATION timeout bounds this setter.
A worker owns its connection until the native call returns and cleanup can
complete. On watchdog expiry report unresolved/in-progress state, prohibit
further operations/retries, and do not race another thread's close or rollback
against the call. Late completion must still be recorded and cleaned up.
No hard wall-clock bound for this vendor kernel is established by this audit.

If strict cancellable, hard-bounded mutation becomes a requirement, that
limitation must be resolved explicitly before implementation; do not silently
substitute a raw transfer solely to obtain a timeout field.

## 8. Exact proposed Phase 3D.2D hardware test

**Proposal only. Requires a separate approval/implementation phase. Nothing
below exists as a new live diagnostic in this audit.**

Scope: one manual, standard configuration transition to descriptor-validated
value 4, readback and cleanup. This is a mutating test and must not be labeled
passive. It is not a USBMUX, Ethernet or CarPlay test.

Proposed implementation sequence:

1. Keep all global runtime/vendor/transport gates disabled. Provide a separate
   manual button with an explicit configuration-change warning.
2. Require exactly one Apple device, record VID/PID, acquire its fresh
   configuration snapshot and check existing permission. Permission absent,
   unexpected identity/layout or ambiguity -> STOP.
3. Require an actual configuration with **ID/value 4**, scoped USBMUX ID 1,
   alt 0, `FF/FE/02`, BULK `04/85`, and the mapped Apple Ethernet ID 2
   `FF/FD/01` alternate descriptors. Do not select by array index/count/name
   alone. Names are descriptive evidence, not the transport discriminator.
4. Open without any claim. Read baseline GET_CONFIGURATION using the existing
   Phase 3D.2B request: `0x80/0x08`, value/index 0, length 1, timeout 1000 ms.
   Require value 1 for this specific `1 -> 4` experiment; otherwise report
   changed baseline and STOP.
5. Invoke the public `setConfiguration(validatedConfiguration4)` **once** on
   the background owner thread. Record boolean/exception and elapsed time.
   Use a 10-second UI watchdog as an observation deadline, not a promise of
   native cancellation. Block retries while unresolved.
6. If selection returns false/throws, record exact failure and close when safe.
   Do not claim anyway, select 3, issue vendor `0x52`, reset the device or
   automatically restore configuration 1.
7. If true and still attached, perform one bounded GET_CONFIGURATION readback,
   then close in `finally`. Record returned value; anything except 4 is
   unconfirmed, regardless of the setter's boolean.
8. Reacquire the device list, check permission, reopen without claims and
   perform a second GET_CONFIGURATION verification; close immediately.
   If a detach occurred, instead observe fresh enumeration for at most
   10 seconds with 250 ms polling and/or attach events, then use only the fresh
   device/descriptor objects. No permission request or configuration retry.
9. Report baseline/target, setter result/duration, watchdog state, observed
   detach/reattach, fresh permission, both readbacks where possible, active
   descriptor name/ID, scoped USBMUX and Ethernet descriptors, failure stage
   and cleanup. Config 4 + successful fresh readback confirms configuration
   selection **only**, not transport or authentication.
10. Save the report and STOP.

Before any future hardware build, add injected tests for descriptor-scoped
selection, ambiguous devices/configurations, changed baseline, absent
permission, open/setter failures, mismatched/malformed readback, disconnect,
permission loss after reattach, observation timeout, watchdog/late completion
and connection ownership/cleanup. Assert no permission request, claim,
alternate change, raw SET_CONFIGURATION fallback, bulk transfer or
transport/authentication construction. That testing is future work, not a
result claimed here.

Proposed real-car procedure for that separately approved build:

1. Remove Carlinkit completely.
2. Start Geely normally.
3. Unlock the iPhone and connect it directly to the driver-side USB port using
   a known-good data cable.
4. Open DiPlay and the future Phase 3D.2D diagnostic.
5. Read the configuration-change warning; press its manual test button once.
6. Do not approve Trust or other authorization prompts. If one appears, record
   it, stop further operations and let the owner finish cleanup safely.
7. Save the diagnostic report, including any unresolved/watchdog status.
8. STOP and send the report for analysis. Do not run Phase 3D.2 USBMUX, change
   alternate settings, start NCM, or attempt any pairing/CarPlay operation.

Reconnecting the cable afterward may change the phone's selection; do not
assume it restores PTP or silently perform an automatic rollback.

## 9. Retained safety state and remaining evidence gaps

Static inspection confirms:

- [LegacyLaunchBuild](../shared/src/main/java/com/shilapi/xcertplay/orchestration/LegacyLaunchBuild.kt):
  `CONNECTIONS_ENABLED=false`, `VENDOR_INTEGRATION_ENABLED=false`,
  `PHASE3B_TRANSPORT_TESTS_ENABLED=false`.
- [mobile build configuration](../mobile/build.gradle.kts): `minSdk=22`.
- No new APK was built or substituted during this documentation-only audit.
- Existing Phase 3D.2 refusal of ambiguous flattened candidates is preserved.

Still unproven: successful E01 selection/readback of 4; applicability of
QDrive's `Valeria` gate/vendor branch; E01 re-enumeration/permission behavior
after selection; USBMUX/Lockdown response; proprietary Apple Ethernet handling;
and any complete CDC-NCM/CarPlay mode transition.

**Stop after Phase 3D.2C. Phase 3D.2D is proposed, not implemented or executed.**
