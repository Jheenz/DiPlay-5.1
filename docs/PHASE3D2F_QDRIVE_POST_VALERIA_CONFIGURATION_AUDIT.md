# Phase 3D.2F - QDrive post-Valeria configuration-selection static audit

## Scope and verdict

**Static analysis only.** No USB calls, vendor/JNI execution, runtime edits,
configuration changes, interface claims or transport startup occurred in this
phase. Phase 3D.2G is a proposal only.
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false`; mobile minSdk remains 22.

The recovered TRUE branch uses the **device descriptor's
`bNumConfigurations` as a configuration VALUE**, not an array index, matching
configuration ID, constant 4, or constant 5. For the reported post-transition
device, `bNumConfigurations=5`, active value=1, and advertised
`bConfigurationValue=5` exists. Consequently QDrive calls
**`libusb_set_configuration(existingHandle, 5)`**.

This proves QDrive's target. It does not prove that E01/Android will successfully
activate it. Selecting the configuration is separable from subsequent claims
and transport, so a controlled configuration-only test can be designed.

## 1. User-supplied real-E01 evidence

The completed [Phase 3D.2E test](PHASE3D2E_QDRIVE_VENDOR_TRANSITION_TEST.md)
reported:

- Before: `05AC:12A8`, four configurations, 12 flattened interfaces, active
  configuration 1/PTP, complete Valeria predicate FALSE.
- One OUT `40/52/value0/index2/length0` request, Android timeout 1000ms,
  returned 0.
- Original device detached; approximately 0.9 seconds later an Apple device
  reattached with the same VID/PID.
- After: five configurations, 18 flattened interfaces; active configuration
  still **1**, not 5.
- Configuration ID 5: PTP + Apple Mobile Device + Valeria.
  - ID0: PTP.
  - ID1: USBMUX `255/254/2`, bulk OUT04/IN85.
  - ID2: Valeria `255/42/255`, bulk IN86/OUT05.
  - ID3: CDC-NCM control `2/13/0`.
  - ID4 alt0/alt1: CDC data `10/0/1`; alt1 bulk IN87/OUT06.
- Exact checked interface string `"Valeria"` retrieved; predicate TRUE.

These are user-reported hardware results, not measurements made during this
audit. Detach/reattach and changed descriptors prove the preceding vendor
transition; request return 0 alone would not. Advertised configuration 5
does not establish its activation, networking or projection readiness.

## 2. Evidence identity and method

Locally re-read ARM/Thumb disassembly, ARM PLT instructions, relocations and
ELF data using Android NDK LLVM tools. No vendor binary was loaded as code.
Addresses below are ELF virtual addresses. Writable sections have file offsets
0x1000 below their VMAs; raw table reads accounted for this. Disassembler
nearest-symbol labels in stripped routines are not asserted function names.

| Binary | SHA256, reverified |
|---|---|
| [libusbserver.so](../vendor-apks/QDrive_Global/lib/arm/libusbserver.so) | `87748337AB3B0BB70AFB037E412454C74B1BB419CA01E270B666EB225C8AEA54` |
| [libSSPAirPlayUSB.so](../vendor-apks/QDrive_Global/lib/arm/libSSPAirPlayUSB.so) | `355284ADC3B9E8ECCD1C9478859068AAE976A2F123749EA6BB7DE64A73682ED9` |
| [libSSP_Main.so](../vendor-apks/QDrive_Global/lib/arm/libSSP_Main.so) | `A426236D3919D5395A9E9F686CFB3D740FE8B272F452F8D4AB557944390CB67A` |

Resolved ARM PLT/GOT evidence:

| PLT | GOT relocation | Resolved function |
|---|---|---|
| 0x78B00 | 0x2CE658 | libc `strstr` |
| 0x78E18 | 0x2CE760 | `libusb_get_device_descriptor` |
| 0x78E24 | 0x2CE764 | `libusb_get_configuration`, Thumb 0x9D2D5 |
| 0x78E48 | 0x2CE770 | `libusb_get_active_config_descriptor`, Thumb 0x9E0DD |
| 0x78E54 | 0x2CE774 | `libusb_kernel_driver_active`, Thumb 0x9D715 |
| 0x78E60 | 0x2CE778 | `libusb_detach_kernel_driver`, Thumb 0x9D769 |
| 0x78E6C | 0x2CE77C | `libusb_set_configuration`, Thumb 0x9D3F1 |
| 0x78E78 | 0x2CE780 | `libusb_claim_interface`, Thumb 0x9D42D |
| 0x7910C | 0x2CE85C | libc `ioctl` |

For example PLT 0x78E6C computes 0x2CE77C with ARM PC-relative adds and
`ldr pc,[r12,#0x908]!`; this is not a guessed name from surrounding strings.

## 3. Exact predicate and target provenance

At 0x95B26, `r1=sp+0x24`; 0x95B2A calls
`libusb_get_device_descriptor`. Success reaches 0x95B44. The descriptor's
18th byte, offset 0x11 (`bNumConfigurations`), is therefore **`sp+0x35`**.
VID and PID checks precede the predicate; 05AC:12A8 passes the native range.

Helper 0x95574 receives the descriptor pointer in r2:

| Address | Instruction/effect |
|---|---|
| 0x9558E | Read descriptor offset 0x11 as configuration loop count. |
| 0x9559E-0x955A4 | Fetch configuration by zero-based loop index r4. |
| 0x955B2-0x955C0 | Obtain each interface's first alternate descriptor and its `iInterface` byte at offset 8. |
| 0x955BC / 0x955CA | Clear ASCII buffer, retrieve interface string. |
| 0x955D2 | `strstr(buffer, "Valeria")`; literal at 0x26DD01. |
| 0x955D6 / 0x955F6 | Non-NULL search immediately returns **1**, not the configuration index/value. |
| 0x955F2 | Exhaustion returns 0. |
| 0x95B68 / 0x95B6C | Call helper; zero branches FALSE, nonzero falls through TRUE. |

Missing-string and malformed-data qualifications remain those of the
[C1/C2 audit](PHASE3D2C1_QDRIVE_VALERIA_DISCRIMINATOR.md). Here the supplied
valid `"Valeria"` result establishes TRUE.

The TRUE caller does not receive or recover the matching configuration:

```text
TRUE:
  write global branch flag = 1                 // 0x95B78
  rc = libusb_get_configuration(handle, &current)
  if rc != 0: log, close handle, return -1
  target = deviceDescriptor.bNumConfigurations // sp+0x35
  if current != target:
      inspect current active interfaces; conditionally detach kernel drivers
      rc = libusb_set_configuration(handle, target)
      if rc != 0: log, close handle, return -1
  get active configuration descriptor
  find USBMUX -> claim -> bulk/USBMUX continuation
```

Exact target evidence:

| Address | Instruction/effect |
|---|---|
| 0x95B7C-0x95B82 | Same handle `[sp+0x38]`, output integer `sp+0x20`, GET configuration API. |
| 0x95B86 | Only return 0 advances to target comparison; error goes to 0x95CEC. |
| 0x95BE0 | `ldrb.w r0,[sp,#0x35]` = device configuration COUNT. |
| 0x95BE4-0x95BE8 | Load current integer, compare with count; equal skips setter to 0x95CDC. |
| 0x95CA8 | Log target from the same count byte, not a configuration descriptor. |
| 0x95CBC | `ldrb.w r1,[sp,#0x35]` = setter's second argument. |
| 0x95CC0 | `r0=[sp+0x38]` = existing handle. |
| 0x95CC2 | Call resolved `libusb_set_configuration`. |

| Layout | Predicate | Current | QDrive target/behavior |
|---|---|---|---|
| Four configurations | FALSE | 1 | Vendor branch; no configuration selection. |
| Four configurations | TRUE | 1 | Set configuration **value 4**. |
| Five configurations | TRUE | 1 | Set configuration **value 5**. |
| Five configurations | TRUE | 5 | Skip setter; continue with active descriptor. |

The numeric count is used as a **value**, not `count-1` or array index 5.
Configuration ID5 is the target for this layout because count=5 and ID5
exists, not because its name includes Valeria. If count and advertised
configuration values were non-contiguous, QDrive would still request the
count; this code has no matching-ID lookup or highest-ID fallback.

## 4. Configuration operation, prelude and error handling

### Conditional kernel-driver prelude

When current != target, 0x95BEE first obtains the **current active**
configuration descriptor. Failure is logged at 0x95BF4-0x95C06 but still
reaches the setter preparation at 0x95CA2.

On descriptor success, 0x95C0C-0x95C9C loops its interfaces, taking the
first alternate's interface number at offset 2:

- 0x95C2A: query `libusb_kernel_driver_active(handle, interfaceNumber)`.
- Negative result: log at 0x95C7C and continue.
- Result other than 1: continue at 0x95C92.
- Exactly 1: call `libusb_detach_kernel_driver` at 0x95C62.
- Negative detach result: log at 0x95C6E, still continue.
- Free current descriptor at 0x95C9E, then attempt setter once.

This is a conditional OS **kernel-driver unbind**, not a physical USB
detach/re-enumeration, interface claim, alternate-setting change, or Valeria/NCM
activation. For active1/PTP it considers active PTP interfaces, not all
five advertised configurations. Thus QDrive can perform another host-side
state change before selection when a driver is bound; it is not appropriate
to silently transplant this force-detach behavior into the proposed minimal test.

### Setter backend

`libusb_set_configuration:0x9D3F0` preserves handle and value, resolves
backend through GOT 0x2CDDF8, and tail-calls backend slot +0x3C at 0x9D41E.
The `R_ARM_RELATIVE` GOT data points to table **0x2C6EC0**; its +0x3C
relocated callback is **0xA20E9** (Thumb code 0xA20E8).

At 0xA20FE the callback stores the value as an unsigned integer; at
0xA2100/0xA2106 it constructs ioctl code **0x80045505**; at 0xA210A
loads the existing handle's FD and at **0xA210E** calls libc `ioctl` with a
pointer to that integer. The local NDK Linux header identifies this code as
`USBDEVFS_SETCONFIGURATION = _IOR('U',5,unsigned int)`.
It is **not a second Apple vendor request** or an explicit user-space
`libusb_control_transfer` in this branch.

The corresponding standard USB configuration selection has semantic setup:
`bmRequestType=0x00`, `bRequest=0x09` (SET_CONFIGURATION),
`wValue=5`, `wIndex=0`, `wLength=0`, no data stage.
Those are USB request semantics of the setter, not a directly recovered
eight-byte buffer passed by QDrive. The native API/ioctl has **no transfer
timeout argument**; do not attribute the vendor request's timeout0 to it.

- Backend ioctl success: cache chosen value in device-private offset 0x4C
  at 0xA2130, return 0 at 0xA2132.
- errno22/EINVAL -> -5 at 0xA2136; errno19/ENODEV -> -4 at 0xA213C;
  errno16/EBUSY -> -6 at 0xA2128; other errors logged -> -99 at 0xA2158.
- No retry, delay, sleep, reset or reopen in this callback.
- Caller 0x95CC6 accepts **only 0**. Nonzero logs at 0x95CC8-0x95CF4,
  closes at 0x95CFA and returns -1 at 0x95AC6. No fallback config or retry.
- Success immediately reaches active-descriptor read 0x95CE0.
- The routine keeps the same handle for subsequent claim and transfer:
  `[sp+0x38]` at 0x95DD4, stored in transport state at 0x95E58.

No detach/attach wait, changed PID requirement, or discovery/reopen sequence
is encoded between setter success and the next descriptor/claim. This is
QDrive's expectation, not a guarantee about E01 hardware.

**Readback limitation:** it does not explicitly perform another
GET_CONFIGURATION after setter success. `libusb_get_active_config_descriptor`
uses backend +0x2C (0xA1F2D): depending on sysfs-availability flag 0x2DCF28,
it uses cached value at 0xA1F50 or queries via 0xA3798, then looks up the
descriptor by value. The setter updates that cache. This is not equivalent
to mandatory wire-level verification. Phase 3D.2G must require actual standard
GET_CONFIGURATION evidence instead.

## 5. Immediate post-selection ordering and safe stopping boundary

| Order | Evidence and meaning |
|---|---|
| 1 | Active descriptor read 0x95CE0; error logs/closes/returns -1. |
| 2 | Loop active interfaces' first alternates at 0x95D8A. Accept only class FF/subclass FE/protocol02 at 0x95D90-0x95DA0. |
| 3 | Require two endpoints at 0x95D3E; discriminate IN/OUT by endpoint address sign at 0x95D44-0x95D76. Store interface number and endpoints at 0x96042/0x9605C. No fixed endpoint-address requirement here. |
| 4 | Free descriptor; claim selected USBMUX interface at **0x95DD6**. Failure logs/closes/frees state at 0x95DDC-0x95E08. |
| 5 | Read serial string at 0x95E18; failure releases claim/closes via 0x95EA6-0x95EB0. Keep same handle in state, inspect packet size/speed. |
| 6 | First bulk operation: drain USBMUX IN at **0x95F56**, 4096 bytes/200ms, repeating while return=0. Not Valeria or NCM endpoints. |
| 7 | Register USBMUX device at 0x95F7E -> 0x93DA4; build version payload at 0x93E68/0x93E76 and packet type0 at 0x93E80 -> 0x93F34 -> sender 0x95348 (call 0x94036). Bulk submit at 0x95384. |
| 8 | Submit USBMUX asynchronous IN receive at 0x95FBE. |

Applied to config5's supplied valid descriptors, the selected interface is
**ID1 USBMUX**, OUT04/IN85. Valeria ID2 was used to decide the branch through
its string; it is not claimed by the add continuation. NCM control ID3 and
data ID4 are not selected by the FF/FE/02 loop. No
`libusb_set_interface_alt_setting` call appears on this continuation:
**NCM data alt1 is not activated here**.

Cross-library chain reverified:
`libSSPAirPlayUSB JNI_SSP_AddUsbDevice` calls PLT0x3DA0 at 0x58EA,
relocation0x16E1C -> `UsbScreen_AddUsbDevice`; the wrapper at
`libusbserver:0x84974` tail-branches at 0x8497A to 0x95A60.
JNI saves/returns the add result at 0x58EE/0x5912; it does not insert
NCM/Valeria setup between selection and USBMUX. `libSSP_Main` has a
DT_NEEDED dependency on libusbserver; that dependency is not a transport call.

The bounded trace establishes **selection -> USBMUX claim -> bulk drain ->
USBMUX version exchange/receive**, not selection -> NCM alt1 -> Lockdown.
Lockdown transactions depend on later USBMUX protocol progress; no immediate
Pair/StartSession/Lockdown operation is present in the selection/claim block.
AirPlayUSB is already the calling library, not a proven post-selection
projection-start stage. Relative later ordering of SSP callbacks, Lockdown
session operations, Valeria traffic and NCM networking is **not established**
by this trace. None is needed to prove configuration selection; do not invent
a total order or equate successful add with AirPlay/CarPlay.

**Next safe boundary:** select value5, read configuration/state, close, STOP
**before the interface claim at 0x95DD6**. Do not invoke the native add routine,
which would automatically cross that boundary.

## 6. Comparison with unchanged DiPlay selectors

[IphoneCarPlayConfiguration.find](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneCarPlayConfiguration.kt)
searches configurations, first for USBMUX + CDC-NCM control + proprietary
Apple Ethernet, then for USBMUX + CDC-NCM control. USBMUX is FF/FE/02;
CDC-NCM control is 02/0D. The second pass does not require FF/FD/01.

For the supplied layout, config3 lacks CDC-NCM; config4's FF/FD/01
Ethernet is not CDC-NCM; config5 has USBMUX and genuine 02/0D control.
**The current descriptor selector naturally chooses config5**, without a
constant, name match, Valeria match, or selector change. This conclusion is
conditional on the reported configuration set; another earlier configuration
meeting its preferred predicates could change the choice.

[NcmFunctionDiscovery.find](../shared/src/main/java/com/shilapi/xcertplay/transport/NcmFunctionDiscovery.kt)
would recognize config5's control ID3 and data ID4 alt1 with one bulk IN/OUT
pair. It prefers bulk-bearing alt1. Descriptor recognition is not permission,
successful claim, alternate activation, or NCM networking.

[IphoneUsbHost.openIap2UsbSession](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt)
uses that configuration-scoped selector, sets the configuration and then
claims USBMUX. Its existing warn-and-claim-on-setter-failure behavior differs
from QDrive's abort-on-error; it must not be reused as the configuration-only
test. [CarPlayController](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)
then opens NCM before its USBMUX/Lockdown stack. These paths remain gated
off and unchanged.

**Two different ambiguities:**

- Within the verified active configuration5 there is one reported USBMUX
  ID1/alt0 with OUT04/IN85. Active-configuration-scoped discovery would resolve
  the former config3/config4 choice.
- [DirectUsbMuxSelection.find](../shared/src/main/java/com/shilapi/xcertplay/transport/DirectUsbMuxAccess.kt)
  still scans `UsbDevice.interfaceCount` and requires exactly one candidate
  across the flattened list. Advertised configs3/4/5 can all retain candidates.
  Merely selecting config5 need not change Android's cached flattened
  descriptor object. **Its old ambiguity is not automatically fixed**;
  future work must correlate actual GET_CONFIGURATION with scoped descriptors,
  not assume a setter or bulk claim filters Android's cached inventory.

The smallest missing operation for this already-transitioned phone is a
verified activation of value5 with readback, not another vendor52 request,
NCM activation or USBMUX startup.

## 7. Proposed Phase 3D.2G - design only

No implementation, UI button, setter, tests exercising a setter, or APK change
is authorized by this document.

1. Carlinkit removed; Geely started normally; unlock/direct-connect the already
   transitioned iPhone. Open DiPlay; no Trust/Pair approval.
2. Explicit manual read-only preflight: exactly one appropriate permitted
   Apple device; record identity/path, fresh descriptors, count **5**, and a
   unique descriptor with **bConfigurationValue5**. Require the reported
   USBMUX/Valeria/NCM layout and an exact checked Valeria TRUE result.
3. Open without claims, verify wire-level GET_CONFIGURATION=**1**; require
   stable identity/layout. If permission absent, predicate unavailable,
   count/value mismatch, read/open failure or current !=1: **REQUEST NOT SENT**.
   If already5, report already active without repeating the setter.
4. Register passive attach/detach observation before sending. On a separate
   explicit confirmation, repeat preflight on the sending handle, then perform
   **one configuration setter targeting value5**. Proposed Android mapping:
   API22 `UsbDeviceConnection.setConfiguration(freshConfigWithId5)`, preserving
   QDrive's OS-mediated setter semantics; not the transport opener and not
   QDrive JNI. This mapping requires validation during a separately authorized
   implementation phase. Do not issue both setter and raw SET_CONFIGURATION.
5. Immediately attempt standard GET_CONFIGURATION on the same handle:
   `80/08/value0/index0/length1`, bounded read timeout. Record setter return,
   exception, timing and readback. Do not equate setter true with PASS.
6. Close in finally without claims/releases/alternate changes. Passively
   observe for a proposed **10 seconds**; record detach/attach, VID/PID/path,
   count and descriptor metadata. No setter retry.
7. If the original connection was invalidated, use only a fresh unambiguous
   Apple device with existing permission for a bounded GET_CONFIGURATION,
   then close. Do not request permission, resend vendor52 or repeat setter.
8. Save report and STOP. No USBMUX/Lockdown/iAP2/MFi/NCM/AirPlay/CarPlay.

**Kernel-driver prelude:** the minimal experiment intentionally does not
detach drivers or force-claim interfaces. QDrive's detach is conditional and
its failure is nonfatal to attempting the setter, so the single setter is a
separable operation. If Android rejects it (including busy/driver ownership),
report and STOP; a driver-detach workaround would need separate analysis and
authorization. No silent replication of the entire native TRUE branch.

**Timing:** Android's configuration setter has no caller timeout parameter.
Run it off the UI thread, prohibit repeat attempts and keep its connection
owned until completion/final cleanup. A worker cancellation or observer
deadline cannot honestly guarantee cancellation of an in-flight setter.
Review this constraint in the implementation phase; never close/reopen/retry
concurrently as a timeout workaround. Read/observation deadlines do not
establish a native setter timeout.

| Outcome | Required evidence |
|---|---|
| PASS: expected configuration active | After the one operation, successful standard GET_CONFIGURATION returns **5**, correlated with the fresh unique config5 descriptor and the same attributable Apple device. No claim/bulk is needed. |
| FAIL: expected configuration not active | Reliable post-readback remains1 or another value, or setter fails with reliable readback proving value5 was not activated. Record actual values/errors; no retry. |
| INCONCLUSIVE | Detach/no final device, permission loss, read/open failure, ambiguous identity, stale descriptor correlation or cleanup failure prevents reliable final value. Preserve passive evidence. |
| REQUEST NOT SENT | Any preflight/confirmation failure; current already5 is separately reported as already active, not a new-test PASS. |

A negative setter result with reliable later GET_CONFIGURATION5 is discrepant
but establishes the expected active state; retain both facts. Changed PID,
attach/detach or advertised config5 alone is not PASS. No automatic restore to
config1: that would be a second state-changing operation. Report cleanup;
disconnect physically only after saving, if needed to end the session.

## 8. Remaining uncertainties and validation

- Real-E01 activation of config5 and same-handle readback remain untested.
- Kernel-driver ownership and Android setter blocking/error behavior on E01
  remain unknown. The native ioctl has no request-local timeout/retry.
- Static evidence identifies configuration selection fully, not complete
  QDLink transport/network/projection ordering or authentication readiness.
- Android cached flattened metadata may survive configuration selection;
  only GET_CONFIGURATION plus scoped descriptors establishes active USBMUX.
- QDrive's count-as-value assumption is not portable to arbitrary devices.
- Full raw hardware report was not independently imported here; supplied
  hardware facts are explicitly separated from native evidence.

Documentation-only phase: checked local binaries/hashes, target argument
provenance, PLT/GOT/backend linkage and current source selectors. No build or
runtime tests are required for these documentation changes; no new APK was
produced. The Phase3D.2E APK and runtime behavior remain unchanged.

POST-VALERIA CONFIGURATION SELECTION FULLY IDENTIFIED — CONTROLLED TEST CAN BE DESIGNED
