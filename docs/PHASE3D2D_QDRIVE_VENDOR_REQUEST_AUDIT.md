# Phase 3D.2D - QDrive/QDLink vendor-request branch static audit

## Scope and conclusion

The applicable branch sends exactly one vendor/device **OUT** control request:

`bmRequestType=0x40, bRequest=0x52, wValue=0, wIndex=2, wLength=0`.

It supplies a NULL data pointer and libusb timeout **0**, ignores the transfer
return, and returns **-1** from device addition. It does not immediately select
a configuration, claim an interface, start USBMUX, or validate a changed device.
The numeric request and its separation from subsequent transport operations
are fully identified. The iPhone's response/state transition is **not** proven.

This is a static audit, not a new diagnostic or hardware authorization.
No vendor library/JNI was loaded or executed. No USB operation was performed.
No runtime behavior, selector, build configuration or connection gate changed.
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false`; mobile minSdk remains 22.
Phase 3D.2E below is a design only, not an implemented test.

## 1. Applicable real-E01 state

The user supplied the completed real Phase 3D.2C2 result:

- Direct iPhone `0x05AC:0x12A8`; Carlinkit removed.
- Android permission already granted; opening and read-only inspection succeed.
- Active configuration **1 / PTP**, before QDrive initialization.
- Complete relevant first-alternate scan, including configurations 1 through 4.
- Strings include `PTP`, `Apple USB Multiplexor`, `AppleUSBEthernet`.
- No checked string contains case-sensitive `Valeria`.
- Result: **VALERIA CONDITION DOES NOT MATCH — VENDOR-REQUEST BRANCH SUPPORTED**.

This supersedes the provisional configuration-count/Valeria-TRUE proposal in
the [3D.2C audit](PHASE3D2C_USB_CONFIGURATION_TRANSITION_AUDIT.md).
An advertised USBMUX descriptor is not proof of an active/usable interface:
the current configuration is still PTP. Do not select configuration 4 merely
because four configurations exist or because it advertises Ethernet.

## 2. Evidence identity and method

Artifacts: `vendor-apks/QDrive_Global/QDrive_Global.apk` and the extracted
`vendor-apks/QDrive_Global/lib/arm/` libraries. SHA256:

| Artifact | SHA256 |
|---|---|
| QDrive_Global.apk | `C8907EDA5D29C7DD6F7ECD4C7FD56EC24EE87AA16D7787749449698154773732` |
| libusbserver.so | `87748337AB3B0BB70AFB037E412454C74B1BB419CA01E270B666EB225C8AEA54` |
| libSSPAirPlayUSB.so | `355284ADC3B9E8ECCD1C9478859068AAE976A2F123749EA6BB7DE64A73682ED9` |
| libSSP_Main.so | `A426236D3919D5395A9E9F686CFB3D740FE8B272F452F8D4AB557944390CB67A` |

Tools: local Android NDK LLVM objdump/readelf/nm and Android dexdump.
Addresses below are ELF virtual addresses, not arbitrary string offsets.
Thumb text was decoded as Thumb; PLT/interworking ARM instructions as ARM.
The nearest exported disassembler label in stripped code is not a function
identity. `.text` has equal virtual/file offsets, but writable sections have
file offsets **0x1000 below** their virtual addresses; GOT reads account for
this difference.

Relevant verified relocations:

| PLT | GOT relocation | Target |
|---|---|---|
| `0x78818` | `0x2CE560` | `__aeabi_memclr8` |
| `0x78B00` | `0x2CE658` | libc `strstr` |
| `0x78DE8` | `0x2CE750` | `libusb_get_device2` |
| `0x78DF4` | `0x2CE754` | `libusb_open2` |
| `0x78E24` | `0x2CE764` | `libusb_get_configuration` |
| `0x78E30` | `0x2CE768` | `libusb_control_transfer`, Thumb entry `0xA0CC5` |
| `0x78E48` | `0x2CE770` | `libusb_get_active_config_descriptor` |
| `0x78E6C` | `0x2CE77C` | `libusb_set_configuration` |
| `0x78E78` | `0x2CE780` | `libusb_claim_interface` |
| `0x78D7C` | `0x2CE72C` | `libusb_submit_transfer` |
| `0x78E9C` | `0x2CE78C` | `libusb_bulk_transfer` |

For example ARM PLT `0x78E30` calculates its GOT entry through
`add r12,pc,#0x200000`, `add r12,r12,#0x55000`,
`ldr pc,[r12,#0x930]!`; the relocation resolves the actual callee.

## 3. Exact branch and argument reconstruction

### Entry and predicate

`PhoneMux.registDevice` DEX code item `0x1FD428` opens Android USB, obtains its
FD/path, and calls `JNI_SSP_AddUsbDevice` at `0x1FD4C2`.
`libSSPAirPlayUSB.so:0x58EA` calls PLT `0x3DA0`, relocation `0x16E1C`
(`UsbScreen_AddUsbDevice`). Its result is saved and returned at `0x5912`.

`libusbserver.so:UsbScreen_AddUsbDevice` at `0x84974` stores the supplied FD
and tail-branches at `0x8497A` to device-add `0x95A60`.
That routine calls `libusb_get_device2` at `0x95A98` and `libusb_open2`
at `0x95AA6`; the resulting handle is held at `[sp+0x38]`.
Apple VID checking occurs at `0x95B44`; the native PID range check includes
`0x12A8` (`0x1290` through `0x12AF` inclusive), while Java's discovery filter
is stricter (`0x1291` through `0x12AE`).

At `0x95B68`, helper `0x95574` scans every advertised configuration and
each libusb interface's first alternate descriptor. It reads `iInterface`
(offset 8), clears a 255-byte buffer, calls ASCII string retrieval, ignores
that read's return, and calls **case-sensitive substring** `strstr` at
`0x955D2`, with needle `Valeria` at `0x26DD01`.
Non-NULL search returns TRUE at `0x955F6`; exhaustion returns FALSE at
`0x955F2`. The predicate is not configuration-name equality, and has no
USBMUX/Ethernet class filter. See the
[C1/C2 descriptor audit](PHASE3D2C1_QDRIVE_VALERIA_DISCRIMINATOR.md)
for read-failure/malformed-data qualifications.

`0x95B6C: cbz r0,0x95B9A` directly selects the FALSE branch.

### FALSE branch instruction effects

| Address | Effect |
|---|---|
| `0x95B9A` / `0x95BA2` | Resolve relative GOT slot `0x2CDDEC`. Its `R_ARM_RELATIVE` addend points to flag storage `0x2DCE8C`. |
| `0x95B9E` | `r2=0`. |
| `0x95BA0` | `r3=0`. |
| `0x95BA4` | Load flag pointer. |
| `0x95BA6` | `r0=[sp+0x38]`, the opened libusb handle. |
| `0x95BA8` | Store zero into flag. TRUE sibling instead stores one at `0x95B78`; this is not transfer success status. |
| `0x95BAA` | `r1=2`. |
| `0x95BAC` | `strd r1,r2,[sp]`: stack arguments `wIndex=2`, `data=NULL`. |
| `0x95BB0` | `r1=0x40`. |
| `0x95BB2` | `strd r2,r2,[sp+8]`: `wLength=0`, `timeout=0`. |
| `0x95BB6` | `r2=0x52`. |
| `0x95BB8` | Call resolved `libusb_control_transfer`. |
| `0x95BBC` | Unconditional jump to `0x95AC6`; no comparison or use of transfer return. |
| `0x95AC6` | Overwrite `r0` with `-1`. |
| `0x95ACA` onward | Stack-canary check and ordinary epilogue; no USB cleanup or reopen. |

AAPCS arguments at the call: `r0=handle`, `r1=bmRequestType`,
`r2=bRequest`, `r3=wValue`, followed by the four stack arguments above.
The **entire state-changing FALSE branch has one control-transfer call**.
Earlier predicate descriptor/string reads are separate standard read-only
operations, not additional vendor writes.

## 4. Exact USB request and completion semantics

| Field | Recovered value / meaning |
|---|---|
| Direction | Host to device / OUT |
| Type | Vendor |
| Recipient | Device |
| bmRequestType | `0x40` |
| bRequest | `0x52` |
| wValue | `0x0000` |
| wIndex | `0x0002` |
| wLength | `0x0000` |
| Data | NULL; no data stage or payload |
| Timeout | libusb `0` milliseconds: no transfer deadline, **not** immediate timeout |
| Setup bytes, little endian | `40 52 00 00 02 00 00 00` |
| Successful libusb return | Normally `0` actual data bytes for this zero-length request |
| QDrive acceptance check | None; zero/negative results both lead to device-add `-1` |
| Local retry/delay | None in this branch |
| Error handling | No per-request error branch/log/readback; native return discarded |
| Immediate reopen | None |

The resolved synchronous implementation `0xA0CC4` builds the setup header
at `0xA0D1C` through `0xA0D32`, sets transfer timeout at `0xA0D56`,
submits at `0xA0D76`, and waits at `0xA0D84`. Completed status returns
actual length at `0xA0DE4`; other statuses produce negative errors.
`libusb_submit_transfer:0x9FA62` loads the timeout; `0x9FA68` bypasses
deadline calculation when zero, to `0x9FB3C`. Thus QDrive's zero is
unbounded at the transfer level; event-loop wakeups are not a request deadline.

**Apple USB mode-switch interpretation:** this is a vendor-specific `0x52`
operation addressed to an Apple device, used after the pre-mode descriptor
predicate fails and before transport registration. It is evidence for a
mode-transition attempt, **not standard SET_CONFIGURATION** (`0x09`).
The binary does not encode an authoritative symbolic name for index 2.
Do not label it "configuration 2", "CarPlay mode", "activate config 4", or
prove an Apple protocol contract solely from these numbers. No iPhone-side
firmware/protocol response was executed or supplied in this phase.

## 5. Immediately afterward: connection, discovery, success

### What is certain

- No readback of current configuration/PID/descriptors after the request.
- No interface claim, alternate selection, configuration setter or USBMUX
  packet occurs on this invocation.
- No explicit `libusb_close` on this FALSE exit. Contrast TRUE-path failures
  calling close at `0x95CFA` and `0x95DF2`.
- `libusb_open2:0x9CF9C` onward links the allocated handle into the context
  handle list. The FALSE exit has not removed/freed it. Java's
  `registDevice` also has no explicit connection close; it only logs the
  returned value at `0x1FD4D4` onward.
- This does **not** guarantee the handle/FD remains usable if the phone
  resets/disconnects. Eventual teardown/finalization is not immediate cleanup.
  Do not reproduce this cleanup omission in a future DiPlay experiment.
- JNI returns `-1`; Java logs it, but does not use it to schedule a retry
  or classify success.

### Later Android discovery, not an explicit transition waiter

DEX `PhoneMux.startTimerFindDevice` (`0x1FD4F4`) schedules discovery after
**2000 ms**, every **1000 ms** (`0x1FD55A` through `0x1FD562`).
`PhoneMux$2.run` (`0x1FC640`) calls discovery while `isExit` is false.
`discoverDevice` (`0x1FD310`) gets a fresh `UsbManager.getDeviceList()`.

It skips paths in `mCheckedDevices` (`0x1FD3A2` through `0x1FD3AE`),
inserts a new path **before** permission/open/native registration
(`0x1FD3BC` / `0x1FD3C0`), and registers a permitted matching Apple device
at `0x1FD3F6`. Its permission receiver (`0x1FC544`) can also register after
permission is granted. In the inspected DEX, the checked map is initialized
and cleared by `PhoneMux.stop` (`0x1FD594`), not by the request's return.

Consequences:

- A newly enumerated path can lead to another Android open and native add.
- An unchanged/already-checked path is not retried every second merely
  because native add returned `-1`.
- No request-local retry count, backoff, bounded re-enumeration deadline,
  required detach event, target PID, or post-request target configuration is
  encoded in this path.
- Re-enumeration/new discovery is a plausible intended bridge to another
  invocation, not a measured guarantee that this iPhone disconnects.
- Permission is rechecked for the newly discovered device. It must not be
  assumed to survive an actual re-enumeration.

There is **no explicit detection of vendor-request completion success**.
For the same native add route to reach transport on a later invocation, its
new predicate must return TRUE. If its strings remain non-Valeria, another
eligible invocation takes the vendor branch again, not a config-selection
fallback. A new USB path alone is not success.

## 6. Path toward USBMUX / QDLink and Ethernet

The statically supported sequence is conditional:

```text
existing Android FD -> native open -> descriptor scan FALSE
  -> OUT 40/52/0000/0002/0000 -> return -1 (result ignored)
  -> [iPhone state change/new discovery: not proven]
  -> later eligible Android open/native add -> scan again
  -> only if TRUE: read current configuration
  -> select bNumConfigurations if active value differs
  -> get active descriptor -> find FF/FE/02 with two endpoints
  -> claim -> register USBMUX -> first version packet -> async receive
```

TRUE continuation evidence: GET_CONFIGURATION `0x95B82`; compare active
value to descriptor `bNumConfigurations` byte `[sp+0x35]` at `0x95BE0`;
setter `0x95CC2`; active-descriptor read `0x95CE0`; USBMUX class/subclass/
protocol checks `0x95D90` through `0x95DA0`; two-endpoint check `0x95D3E`;
claim `0x95DD6`. No Ethernet-presence requirement is part of these USBMUX
checks. No alternate-setting selection is made in this add continuation.

After claim/serial retrieval and endpoint registration, it drains the input
endpoint with `libusb_bulk_transfer` at `0x95F56` (4096-byte buffer, 200 ms,
repeating while return is zero), then calls device registration `0x93DA4`
at `0x95F7E`. That routine builds a USBMUX version payload containing
`0x02000000` at `0x93E68`/`0x93E76` (little-endian bytes `00 00 00 02`)
and sends packet type 0 at `0x93E80` through `0x93F34`.
Packet construction reaches sender `0x95348` at `0x94036`; that sender
sets bulk type 2, the registered output endpoint, and submits at `0x95384`.
The add routine then submits asynchronous bulk receive at `0x95FBE`.
These are actual transport calls, **unreachable on the FALSE invocation**.

`libSSPAirPlayUSB` imports StartUsbService/StopUsbService/AddUsbDevice/
StartRecord, with JNI start-server entry `0x4AC0` and add entry `0x5888`.
`libusbserver` contains the previously audited USBMUX/Lockdown implementation;
the branch does not jump directly into Pair/StartSession or an AirPlay call.
`libSSP_Main` depends on `libusbserver`, but its public USB-monitor wrapper
`0x19060` resolves via `0x63390` to `SSP_Main_iStartUSBMonitor:0x14BDC`.
That monitor's other-mode path reaches `HC_start_monitor:0x62238` via
veneer `0x63290`, PLT `0x11E68`, GOT `0x75884`; it is not an implicit
SET_CONFIGURATION following the FALSE return. A dependency, callback or
"USB monitor" name alone is not proof of a completed mode transition.

**Not established:** FALSE request changes strings to Valeria, changes PID,
activates configuration 3/4, changes the advertised count, activates Apple
USB Ethernet, or guarantees QDLink/Lockdown/AirPlay startup. If a later TRUE
device still has four configurations, QDrive requests value 4 when needed;
that conditional fact does not show this request itself selects value 4.
Apple Ethernet activation timing/alternate use remains unproven on this route.
No NCM or CarPlay success follows from the USBMUX registration evidence alone.

## 7. Comparison with DiPlay

Relevant source:

- [IphoneUsbHost](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt)
- [IphoneCarPlayConfiguration](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneCarPlayConfiguration.kt)
- [CarPlayController](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)
- [NcmFunctionDiscovery](../shared/src/main/java/com/shilapi/xcertplay/transport/NcmFunctionDiscovery.kt)

DiPlay's gated normal transition is LIVI-evidenced:
**IN `0xC0/0x52`, value 0, index 4, one-byte response, 1000 ms**.
It is not QDrive's **OUT `0x40/0x52`, index 2, no payload, timeout 0**.
Direction, index, length, completion check and timeout all differ.
The existing function name "CarPlay re-enumeration" cannot establish
interchangeability. Its finally-close is safer than QDrive's observed exit.

The controller currently searches advertised configurations for USBMUX plus
**CDC-NCM `02/0D`**, preferring additional Apple Ethernet `FF/FD/01`;
the supplied configs 3/4 do not establish that CDC requirement. It may
request its different vendor transition up to two times and expects fresh
attach/permission handling. Its configuration opener selects before claim;
it then opens NCM before USBMUX/Lockdown/CarKit/iAP2. That is not the audited
QDrive USBMUX-only add selector.

The smallest **testable missing QDrive operation** is the single recovered
OUT request and a bounded fresh-state readback, not an automatic config4
setter or any transport startup. It is **not yet proven sufficient** to
obtain an active USBMUX interface. If it changes only the advertised layout
or predicate, a later separately authorized configuration-selection phase
may still be necessary. If active USBMUX appears, future discovery must be
active-configuration-scoped, not the ambiguous flattened index selector.
Do not change DiPlay's existing normal transition or CDC-NCM selector here.

## 8. Proposed Phase 3D.2E: one operation, readback, STOP

**Design only. Requires a later explicit implementation/test phase.**
No QDrive services/JNI may run during the experiment.

1. Remove Carlinkit; start Geely normally; unlock and connect iPhone directly
   with a data cable. Open DiPlay. Do not approve Trust or Pair prompts.
2. Manual opt-in diagnostic, no automatic run. Preflight requires one
   unambiguous directly connected `05AC:12A8`, existing Android permission,
   no transport/session, and successful cleanup-capable read-only baseline.
   Record device path, VID/PID, GET_CONFIGURATION **1/PTP**, raw/cached
   configuration/interface descriptors and complete non-Valeria result.
   Otherwise abort without mutation.
3. Register observation of attach/detach **before** opening. Send **once only**
   standard Android `controlTransfer` with request parameters
   `0x40,0x52,0,2,NULL,0`, with a proposed **1000 ms finite timeout**.
   This timeout is an intentional safety deviation from QDrive's unbounded
   libusb timeout0, not a byte-for-byte timing reproduction. No retry or
   alternative IN/index4 request, reset or configuration setter.
4. Record request start/end, return and exceptions. Close the connection in
   finally immediately after the call, even on timeout/disconnect. A negative
   return does not prove the phone made no state change; still observe.
5. Observe fresh `UsbManager.getDeviceList()` and attach/detach for at most
   **10 seconds after request completion**, proposed polling **250 ms**.
   Record paths/PIDs and timestamps without hard-coding an expected PID.
   Do not hold/reuse pre-transition UsbDevice/UsbInterface/connection objects.
   If no detach occurs, read fresh inventory for the same path too; the
   experiment must detect in-place changes, not assume detach is mandatory.
6. Once an unambiguous Apple device is stable, check permission anew.
   No permission request in this experiment. If permission is absent,
   enumeration evidence is retained but active readback is unavailable.
   Reopen only for standard GET_CONFIGURATION/raw descriptors/necessary
   standard string reads, and finally-close immediately.
   Record all configurations, the actual active value, first-alternate
   strings/Valeria decision, and whether the active configuration contains
   USBMUX `FF/FE/02` with bulk OUT04/IN85 and Apple Ethernet `FF/FD/01`.
   Newly advertised descriptors alone do not prove active USBMUX.
7. Save report, including cleanup and observations, then **STOP**.
   No post-readback configuration selection, claim, setInterface, bulk
   request, USBMUX, Lockdown, Trust, CarKit/iAP2, MFi, Ethernet/NCM,
   AirPlay or CarPlay. Keep all runtime gates disabled.
   Do not automatically restore USB configuration: unplug/replug after
   saving if the user needs a fresh session, then independently rebaseline.

### Evidence classification for that future test

- **Request completion:** return 0 is successful zero-length completion;
  negative/error is a request-level failure, not proof of unchanged state.
- **PASS for the immediate usable-USBMUX goal:** trustworthy post-readback
  proves USBMUX endpoints in the **active** configuration; cleanup succeeds.
  This does not claim an interface or prove bulk interoperability/CarPlay.
- **Intermediate transition evidence, not full PASS:** new PID/path,
  changed descriptor set or a now-TRUE Valeria predicate, but no active
  USBMUX. Report what changed; do not take the TRUE branch automatically.
- **FAIL to achieve the goal:** complete permitted readback remains
  config1/PTP/no active USBMUX, including after the bounded wait.
- **INCONCLUSIVE:** disappearance, ambiguity, lost permission, stale/read
  failure, missing baseline, or cleanup failure prevents reliable readback.
  Preserve raw errors and events; never infer target config from return 0.

The native FALSE branch does not contain multiple inseparable mutations.
Its single request can therefore be isolated for this experiment. Subsequent
TRUE-path configuration selection is a separate invocation/decision and
must remain outside this test.

## 9. Unresolved uncertainties and validation

1. The iPhone-side symbolic meaning of `0x52/index2` and its supported modes.
2. Whether this real phone resets/re-enumerates, changes PID, changes strings
   to Valeria, or changes its active/advertised configurations.
3. Whether Android permission survives and a new path is assigned on E01.
4. Ultimate lifetime/teardown of QDrive's unclosed FALSE-path handle.
5. Whether additional product-specific QDLink lifecycle behavior is required;
   no such behavior is inferred from SSP names or linkage.
6. Apple Ethernet activation/alternate timing and DiPlay CDC-NCM compatibility.
7. USBMUX/Lockdown interoperability and authentication/projection; not tested.

Static validation rechecked the ARM/Thumb branch, stack/register arguments,
PLT/GOT relocations, synchronous timeout handling, JNI return propagation,
DEX discovery/cache/reopen logic, and first USBMUX bulk submit.
The real C2 hardware evidence is user-supplied, not generated by this audit.
Documentation-only changes require no APK rebuild or runtime tests; neither
was run in 3D.2D. The existing minSdk22 and disabled connection gate were
rechecked from source. No Phase 3D.2E diagnostic was implemented.

The verdict below concerns recovery of the isolated request and ability to
design a controlled measurement, **not proof of its resulting configuration
or of a complete working QDLink/CarPlay transition**.

VENDOR REQUEST FULLY IDENTIFIED — CONTROLLED HARDWARE TEST CAN BE DESIGNED
