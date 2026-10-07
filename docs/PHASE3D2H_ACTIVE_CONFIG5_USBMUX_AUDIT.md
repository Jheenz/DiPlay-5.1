# Phase 3D.2H - Active configuration 5 USBMUX integration audit

## Scope and conclusion

**Static/code audit only.** No USB connection was opened, interface claimed
or released, transfer performed, vendor/JNI library executed, configuration
selected, transport started or runtime source modified in this phase.
`LegacyLaunchBuild.CONNECTIONS_ENABLED=false` remains unchanged.
Phase 3D.2I below is **design only**, not implemented or authorized to run here.

API22 supports obtaining the real `UsbInterface` from configuration ID5 and
passing it directly to `claimInterface`. Flattened index13 is unnecessary
and must not be used. However, the native claim is by **interface number**,
not by configuration object, class tuple or alternate setting. Therefore
**same-connection GET_CONFIGURATION5 plus configuration-scoped descriptor
selection and stable device identity** is the essential binding boundary.

A single `claimInterface(config5UsbMux, false)` followed by successful release
and finally-close provides meaningful independent evidence of Android access
to active interface1. No USBMUX version packet or bulk traffic is necessary.
It proves neither USBMUX protocol success nor Trust/pairing readiness.

## 1. Proven real-hardware state (user reported)

Subsequent separately authorized implementation:
[Phase3D.2I claim/release-only test](PHASE3D2I_ACTIVE_CONFIG5_USBMUX_CLAIM_TEST.md).
The design-only statements in this H audit describe its original scope.

The user reports the completed real-E01
[Phase3D.2G test](PHASE3D2G_QDRIVE_CONFIGURATION5_TEST.md):

- Direct iPhone `05AC:12A8`.
- Prior Phase3D.2E OUT40/52/value0/index2 transition produced detach/reattach
  and five configurations, rather than the initial four/PTP-active state.
- Config5: PTP + Apple Mobile Device + Valeria, with genuine CDC-NCM.
- Exactly one Android setter returnedtrue.
- Immediate and final **GET_CONFIGURATION5**; same post-transition enumeration
  remained present.
- No interface claim, bulk, USBMUX, Lockdown, pairing/trust, iAP2, MFi,
  NCM networking, AirPlay or CarPlay.
- **QDRIVE CONFIGURATION 5 SELECTION CONFIRMED**.

| Scope | Identity |
|---|---|
| Configuration | `bConfigurationValue=5` (not array position5) |
| USBMUX | Interface number1, alternate0, class255/subclass254/protocol2 |
| USBMUX OUT | Bulk endpoint `0x04`, endpoint number4, OUT |
| USBMUX IN | Bulk endpoint `0x85`, endpoint number5, IN |
| Excluded functions | PTP ID0; Valeria ID2; NCM control ID3; NCM data ID4 |

Config3/4/5 have descriptor-identical flattened USBMUX candidates.
The historical flattened index13 is not a stable identity. Endpoint packet
size/interval must be recorded from fresh descriptors, not invented here.
These hardware results were supplied by the user; this audit did not measure
the car or independently import its full raw diagnostic report.

The user also observed **Trust This Computer?** after the transition.
No instruction to approve **or reject** that pending prompt is part of this
audit or the proposed claim-only experiment.

## 2. API22 configuration-scoped object mapping

Primary evidence: Android **5.1.1_r1**, the API22-era AOSP implementation:

- [UsbDevice.java](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r1/core/java/android/hardware/usb/UsbDevice.java)
- [UsbConfiguration.java](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r1/core/java/android/hardware/usb/UsbConfiguration.java)
- [UsbInterface.java](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r1/core/java/android/hardware/usb/UsbInterface.java)
- [UsbDeviceConnection.java](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r1/core/java/android/hardware/usb/UsbDeviceConnection.java)
- [USB host enumeration JNI](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r1/services/core/jni/com_android_server_UsbHostManager.cpp)
- [UsbHostManager.java](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r1/services/usb/java/com/android/server/usb/UsbHostManager.java)
- [Connection JNI](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r1/core/jni/android_hardware_UsbDeviceConnection.cpp)
- [libusbhost](https://android.googlesource.com/platform/system/core/+/android-5.1.1_r1/libusbhost/usbhost.c)

The corresponding tagged GitHub AOSP mirrors were fetched read-only for this
audit. No local android-22 platform stubs are installed; no SDK installation
or runtime test was performed. Tagged source establishes the platform behavior,
not certification that E01 has no OEM deviations.

### Object construction and traversal

Host enumeration JNI passes `bConfigurationValue` to `addUsbConfiguration`,
and `bInterfaceNumber`, `bAlternateSetting`, class/subclass/protocol to
`addUsbInterface`. Java builds a separate interface collection per configuration
and assigns it to that `UsbConfiguration`; endpoint objects derive from actual
endpoint descriptor address/attributes/max-packet-size/interval.

`UsbDevice.getConfigurationCount/getConfiguration(i)` operate on that
configuration array. `UsbConfiguration.getId()` gives the descriptor value;
`getInterfaceCount/getInterface(j)` return its stored interface objects.
Search arrays by descriptor identity, not index assumptions:

```text
fresh UsbDevice
  -> enumerate all configurations
  -> require exactly one configuration whose id == 5
  -> enumerate that configuration's interfaces
  -> require exactly one id1/alt0/FF.FE.02 USBMUX descriptor
  -> validate exact bulk OUT04 and IN85 endpoints
  -> retain that actual UsbInterface (and configuration ID provenance)
```

This is API22 public functionality, with no hidden constructors, JNI added by
DiPlay, reflection, root, ioctl bypass, guessed index or object fabrication.
The project already uses these APIs in its configuration selector, descriptor
inventory and G adapter.

### Why flattening is not active-configuration discovery

`UsbDevice.getInterfaceList()` concatenates interfaces from **all**
configurations and caches that array. `getInterface(index)` returns from it;
it does not query active configuration or filter the array after selection.
The flattened entries are the same configuration interface references within
that Java object, not a second authoritative active-descriptor view.
Parcelling may recreate objects, so object identity across inventory snapshots
is not a device-stability test.

### What claim and release actually do

API22 `claimInterface(intf, force)` invokes native claim with
`intf.getId()` and `force`. It does **not** pass:

- configuration ID or configuration object;
- alternate setting, class/subclass/protocol;
- the interface's position in either array;
- endpoint identity.

Connection JNI calls `usb_device_claim_interface(device, interfaceID)`.
If it fails with EBUSY and **force=true**, JNI disconnects the kernel driver
and attempts the claim again. With **force=false**, that workaround is skipped.
libusbhost passes the interface number to USBDEVFS_CLAIMINTERFACE on the FD.
The kernel applies it to the device's current configuration.

Thus directly passing the config5 interface works, but even an identical
config3 object with ID1 could numerically claim current interface1.
**The scoped object alone cannot prove the active configuration.**
Read GET_CONFIGURATION on the same handle immediately before the claim;
require5 and stable fresh identity/descriptors. Do not repeat the setter.

Claim does not select an alternate. `setInterface` is a separate API passing
ID and alternate to its own native operation, and must not run in I.
Claim-only evidence is for the interface number; it does not independently
query the currently selected alternate. Config5's USBMUX alt0 descriptor is
the validated object, not proof produced by claiming.

Release passes the same interface ID to native release. On successful release,
AOSP JNI also allows the kernel driver to reconnect using libusbhost's
connect-driver operation. This is normal public-API cleanup, not DiPlay
explicitly detaching a driver. Report release success and finally-close.
Do not replace ordinary release with a custom driver operation.

## 3. Current DiPlay paths and exact required changes

### Gated normal wired startup

Sources:

- [CarPlayController](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt),
  connection gate at line409; permission/descriptor branch at lines1682-1715;
  data paths at line1788; stack at line1829.
- [IphoneUsbHost](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt),
  detection/permission, vendor transition at line140, opener at line232.
- [IphoneCarPlayConfiguration](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneCarPlayConfiguration.kt),
  config selection at line31, interface/endpoint selection at lines52/62.
- [NcmUsbBridge](../shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt),
  claim/alternate activation at line282.

Current sequence:

```text
Apple discovery -> permission path
 -> find advertised USBMUX + CDC-NCM configuration
    -> if missing: normal IN C0/52/index4/length1 vendor transition
       (up to two re-enumeration attempts; not QDrive OUT40/index2)
    -> if present: openIap2UsbSession
       -> open -> descriptor-chosen config -> setConfiguration again
       -> warn-and-continue even if setter returnsfalse
       -> first FF/FE/02 interface in that configuration
       -> preferred bulk OUT04/IN85 (unique directional fallback otherwise)
       -> claimInterface(usbMux, true)
       -> return bulk session
 -> open NCM (control/data claims, alternate selection and setup)
 -> Iap2UsbMuxHost.open -> USBMUX exchange
 -> saved pair record or Lockdown pairing -> session/services -> further stack
```

For the reported config5, the existing configuration-scoped selector already
recognizes USBMUX + CDC-NCM. It need not change to find this layout.
However, it selects advertised descriptors, not proof of active value5.
Its normal opener reselects configuration, force-claims, and the controller
continues into NCM/pairing. **None is suitable for the next claim-only test.**

### Existing manual direct USBMUX diagnostic

[DirectUsbMuxSelection.find](../shared/src/main/java/com/shilapi/xcertplay/transport/DirectUsbMuxAccess.kt)
at line22 scans flattened interfaces and requires one total candidate.
That safely refuses the three identical config3/4/5 candidates.
Do not solve it by changing `singleOrNull` to first/last, choosing flattened13,
or matching endpoint04/85 alone; all three candidates share those descriptors.

[AndroidDirectUsbMuxAccess.open](../shared/src/main/java/com/shilapi/xcertplay/transport/DirectUsbMuxAccess.kt)
at line40 opens and claims with **force=false**, then returns an
`Iap2UsbSession` bulk pipe. Constructing that session does not send a packet,
but the exposed pipe enables subsequent traffic.
[DirectUsbMuxDiagnostic.run](../shared/src/main/java/com/shilapi/xcertplay/transport/DirectUsbMuxDiagnostic.kt)
then immediately performs USBMUX and a Lockdown ProductType query.
It is not a claim-only diagnostic and must not be reused as the I action.

### Required future binding boundary (not implemented)

1. Distinct active-configuration-scoped selection carrying config ID, interface
   ID/alternate, exact object and endpoint provenance; no flattened index.
2. Same-handle read-only active5 verification before one **force=false** claim.
3. Unique scoped match; reject duplicate IDs/alternates/candidates/endpoints,
   unexpected identity/layout and stale/disconnected handles.
4. Retain `IphoneCarPlayConfiguration` class/endpoint helpers where appropriate,
   but enforce the exact E01 ID1/alt0/OUT04/IN85 contract and uniqueness instead
   of its generic first-match/fallback behavior.
5. For I, expose only inventory/read/claim/release/close, not `UsbMuxBulkPipe`,
   `Iap2UsbMuxHost`, configuration setter, vendor transition or NCM/controller.
6. Later separately authorized transport could construct the existing
   `Iap2UsbSession` from the scoped endpoints **after** a successful claim.
   That integration is not implemented or authorized here.

The verified real path is QDrive OUT transition -> active5 -> scoped USBMUX.
Normal DiPlay startup still uses a different transition and is disabled;
do not silently replace it or enable connections during this audit.

## 4. First DiPlay USBMUX operation and expected response

Audited sources:
[Iap2UsbMuxHost.begin](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt)
at line108, sendFrame at line183, constants/open at lines267-302;
[Iap2UsbSession](../shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt)
at line328; [UsbTransferCompatibility](../shared/src/main/java/com/shilapi/xcertplay/transport/UsbTransferCompatibility.kt);
[UsbMuxFrameBuffer](../shared/src/main/java/com/shilapi/xcertplay/transport/UsbMuxFrameBuffer.kt).

After claim, session construction itself is inert. The **first OUT logical
packet** occurs when `Iap2UsbMuxHost.open` invokes `begin()`:

```text
00 00 00 00  00 00 00 14  00 00 00 02  00 00 00 00  00 00 00 00
```

20 bytes, big endian:

| Offset | Value | Interpretation in current code |
|---|---|---|
| 0..3 | 0 | USBMUX version protocol |
| 4..7 | 20 | Total declared length |
| 8..11 | 2 | USBMUX version |
| 12..19 | zero | Remaining version packet bytes |

This initial version packet is special; it does **not** use the later
16-byte header's FEEDFACE word. OUT is the session's bulk endpoint04.
`Iap2UsbSession.write` uses Android bulkTransfer, handling positive partial
writes under one logical deadline; zero/negative result fails. This is not
a loop resending the version packet from its start.

Expected IN is a framed packet with **protocol0, declared length20, word8=2**,
not protocol1. Remaining words are not required to mirror transmitted zeros.
The code does not require incoming FEEDFACE. Reads use UsbRequest on IN85,
incremental framing, and preserve fragmented/coalesced data.
For API22, legacy `queue(buffer,length)` and blocking `requestWait()` are
wrapped in scheduled cancellation; API26 timeout APIs are not assumed.
Cancellation effectiveness remains dependent on the actual Android USB stack.

Timeouts/retries:

- Normal host `open`: version write timeout60000ms, then an IN handshake
  deadline60000ms; these are separate write/read budgets, not a single60s
  end-to-end limit. Default subsequent reader polls1000ms.
- Existing manual direct diagnostic: version write and handshake read budgets
  each5000ms, reader polls500ms, diagnostic overall watchdog25000ms.
- No automatic retransmission of the initial version packet.
- Before version reply, up to32 stale TCP/protocol6 frames may be discarded;
  other unexpected replies or a further stale frame fail. Timed-out read polls
  may repeat within the deadline, not an OUT-packet retry.
- Existing incremental framer has narrowly qualified optional four-byte
  reply-padding handling based on earlier captures; those captures are not
  E01 results from this audit.

After valid version response, `open()` **also sends setup**:

```text
00 00 00 02  00 00 00 11  FE ED FA CE  00 00  SS SS  07
```

Protocol2, length17, sequence0; `SS SS` is acknowledgment derived from the
incoming frame's sequence. Setup write timeout2000ms. It then starts its
framed reader thread. Consequently current `open()` is not a
"send-version-and-stop" API, much less a claim-only API.

This is USBMUX transport initialization, not iAP2 merely because classes
contain `Iap2` in their names. No TCP port62078, plist Pair, session/TLS,
MFi or NCM is invoked by `begin` itself. A future transport-only phase could
stop before `connect`, but is outside I.

## 5. QDrive comparison, including claim flags and version reply

Local binary [libusbserver.so](../vendor-apks/QDrive_Global/lib/arm/libusbserver.so)
SHA256 reverified:
`87748337AB3B0BB70AFB037E412454C74B1BB419CA01E270B666EB225C8AEA54`.
ARM PLT/GOT and Thumb disassembly were re-read, not inferred from strings.

| Native address | Recovered effect |
|---|---|
| 0x95CC2 / 0x95CC6 | Set configuration count-as-value; accept return0. |
| 0x95CDC-0x95CE0 | Fetch active descriptor on success or when already target. |
| 0x95D00-0x95D28 | Allocate/zero transport state; initialize list bookkeeping. |
| 0x95D8A-0x95DA0 | Scan first alternate descriptors in **active** config; require FF/FE/02. |
| 0x95D3E | Require two endpoints; direction extracted from address bit7. |
| 0x96042/0x9605C | Store selected interface number and IN/OUT endpoint addresses. |
| 0x95DCC | Free active descriptor after successful selection. |
| 0x95DD0-0x95DD6 | Load stored number, same opened handle; call `libusb_claim_interface`. |
| 0x95DDA | Only return0 advances; claim failure logs/closes/frees state. |
| 0x95E18 | After claim, retrieve serial string. |
| 0x95F56/0x95F5C | Drain USBMUX IN,4096 bytes,200ms; repeat while return0. |
| 0x95F7E -> 0x93DA4 | Register USBMUX device; builds first version packet. |
| 0x93E68-0x93E80 | Version payload: BE major2/minor0/padding0; packet protocol0. |
| 0x93F62/0x93F8E | Version payload12 bytes, initial protocol state<2 -> header8, total20. |
| 0x93FC4 / 0x94014 | Write BE protocol/length and copy version payload; no FEEDFACE for initial header. |
| 0x94036 -> 0x95348 | Bulk sender; uses registered OUT endpoint at0x95366. |
| 0x95378 / 0x95384 | Async transfer timeout0, submit via libusb_submit_transfer. |
| 0x95FBE | Async IN receive submission,16384-byte buffer,timeout0. |

Applied to the supplied active5 layout QDrive claims **interface1 USBMUX**
and uses OUT04/IN85. It does not claim Valeria2 or NCM3/4 in this add path.
It does not select an alternate setting between config setter and claim.
Active-descriptor retrieval, allocation, descriptor validation, endpoint
selection and descriptor free occur in that interval; serial/bulk occur after
claim. Conditional old-driver detach belongs to the **pre-setter** prelude
documented in [F](PHASE3D2F_QDRIVE_POST_VALERIA_CONFIGURATION_AUDIT.md).
No extra vendor request or Lockdown message is inserted between setter and claim.

### Exact claim arguments and native backend qualification

PLT0x78E78 -> GOT0x2CE780 resolves `libusb_claim_interface:0x9D42C`.
Arguments are **handle, interfaceNumber**, with no Android-style force
boolean. The wrapper validates number<=31, checks already-claimed mask, then
dispatches backend+0x40 at0x9D484. GOT0x2CDDF8 points to backend0x2C6EC0,
whose claim callback is Thumb0xA2189.

The callback0xA2188 checks handle offset0x14:

- Zero -> plain claim0xA3B00, ioctl **0x8004550F** at0xA3B20
  (USBDEVFS_CLAIMINTERFACE).
- Nonzero ->0xA3A3C, disconnect-claim ioctl **0x8108551B**, flags**2**,
  driver string `"usbfs"`; flags2 means EXCEPT_DRIVER per local Linux header.
  A fallback path can detach then plain-claim.

No explicit `libusb_set_auto_detach_kernel_driver` enable call appears in the
audited add routine. **Do not call this "QDrive claimInterface(force=false)"**:
that Java API does not exist in its call, and this collected `libusb_open2`
allocates with malloc at0x9CF32, initializes the claimed mask at0x9CF74 and
backend area at0x9CF78, but no initialization of generic handle offset0x14 was
established in the recovered open2/backend-open code. Its actual auto-detach
flag is not reliably known from this trace. This bounded native uncertainty
is a reason to require explicit Android **force=false** in I, not to emulate
QDrive's disconnect-claim fallback.

### First OUT and expected native response

QDrive's first OUT packet is the same20-byte initial version packet shown
for DiPlay. Its first bulk activity overall is the preceding IN drain,
which DiPlay `begin()` does not perform.

Receive callback0x963B4 calls parser0x9359C at0x963FC.
At0x93734-0x93738 parser checks BE declared length against assembled length;
0x93748-0x93758 recognizes protocol0. Initially header8, it requires at least
12 bytes of version payload at0x9375A-0x93760. At0x939DA-0x939F6 it decodes
the payload and accepts major**1 or2**; other versions go to error handling.
Version2 at0x939FC is stored and results in setup protocol2 send at0x93A0E.
Setup pointer0x26D6EE contains byte07. Thus expected normal v2 response is
protocol0/length20/major2 (minor/padding not a mandatory zero comparison).
QDrive allows version1; current DiPlay requires2.

The claim/version exchange does not itself require or prove a Lockdown Pair.
Later native callbacks/services are not traced here into a full QDLink
authentication/projection sequence. Timeout0 async transport and native drain
loops must not be transplanted into a bounded claim-only test.

## 6. Trust, Lockdown and Pair boundary

There are three distinct boundaries in current code:

1. **Claim-only:** host interface ownership by number. No bulk, TCP, plist,
   pairing record, certificate or Trust-response operation.
2. **USBMUX initialization:** version reply validation, setup07 and framed
   reader. No service-port connection yet.
3. **Lockdown:** `mux.connect(62078)` establishes TCP over USBMUX; a
   [LockdownPlistChannel](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPlistChannel.kt)
   sends four-byte BE length + UTF8 plist. Even an unpaired GetValue query
   crosses this service boundary, though it is not Pair.

The existing manual direct diagnostic proceeds from mux.open into
port62078 and `GetValue(ProductType)`. It sends no Pair, but must stay outside
the claim-only experiment; "not pairing" is not the same as "no Lockdown."

Normal [CarPlayController.runStack](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)
opens USBMUX, then creates
[LockdownPairingClient](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairingClient.kt).
Without a saved record it calls `pairNewRecord` (line2000):

- TCP connect62078.
- SetValue **UntrustedHostBUID**, before Pair.
- GetValue DevicePublicKey/WiFiAddress; generate local pairing record.
- Send plaintext **Pair**, ProtocolVersion2, ExtendedPairingErrors.
- PairingDialogResponsePending -> close channel, wait, reconnect/retry until
  deadline; UserDeniedPairing/PasswordProtected/other errors stop.
- Controller gives pairing a five-minute total deadline; individual steps
  are bounded up to5000ms. Success persists the record.

This is the code path explicitly expecting the user's Trust decision.
The preparatory BUID write is already stateful Lockdown and must also be
excluded, not merely the final Pair request.
Saved-record flow subsequently uses
[LockdownCarKitClient](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt)
StartSession, TLS and service requests; rejected records can fall back to Pair.
No pairing/session client should be constructed by I.

**Prompt causality is not proven:** the user saw Trust after a transition
that the G report says contained no Lockdown. This audit can prove which
DiPlay paths send pairing messages, but cannot establish what caused iOS to
display the already-pending prompt, or guarantee that a host claim will never
coincide with an OS-generated prompt. Do not infer Pair traffic from the UI
alone. Leave the prompt untouched; if a new prompt/state change is observed,
record it and stop/clean up rather than approving or rejecting it.

## 7. Proposed Phase 3D.2I - claim/release only

**Design only.** A separately authorized implementation should add a distinct
manual warning/confirmation action, isolated from all transport/session code.

1. Carlinkit absent; current direct iPhone in the already-verified transitioned
   state. No automatic E vendor request or G configuration setter.
2. Read-only preflight: exactly one Apple05AC:12A8, existing permission, five
   configurations, unique config5 with reported Valeria/USBMUX/NCM layout.
   Record full configuration-scoped interface/endpoint provenance.
3. Open the fresh device without claims. GET_CONFIGURATION exactly5 on that
   handle (`80/08/0/0`, length1,1000ms). If unavailable/other value, STOP.
   Existing config5 standard string/raw inspection may confirm Valeria if
   needed; recheck active5 and stable inventory afterward.
4. Obtain the actual config ID5 object from this fresh inventory. Require one
   interface ID1/alt0/255.254.2; exactly the expected bulk OUT04/IN85, positive
   maxPacketSize, no guessed flattened index. Reject ambiguity/malformed data.
5. Recheck active5 immediately before one
   **`claimInterface(config5Interface1, false)`**. Timestamp, elapsed and boolean
   result/exception. No retry/force=true/driver-detach/alternate/setter fallback.
6. If claim succeeds, optionally perform only standard GET_CONFIGURATION
   on the same handle to confirm5 remained active and inventory remained stable.
   Do not initialize `UsbRequest`, construct a bulk session or mux host, drain
   IN, or send version/setup/TCP/plist. No meaningful bulk evidence is required
   for an independent ownership test.
7. In finally, release exactly the successfully claimed interface **once**,
   recording result; close connection in a nested finally regardless of
   release outcome. If claim failed, do not release an unowned interface.
   If an exception leaves ownership uncertain, record it and close; do not
   claim/release another interface or use it as a workaround.
8. Save report and STOP. Do not restore config1 or continue after PASS.
   Device disappearance/permission loss means no reopen-and-reclaim retry.

The public claim API has no timeout argument. Run away from the UI; measure
elapsed time without pretending a watchdog can forcibly cancel the kernel
operation. Cancellation prevents later work, not cleanup of an acquired claim.
Normal public release may permit kernel rebind as described above.

### Proposed outcomes

| Classification | Evidence |
|---|---|
| **ACTIVE CONFIGURATION 5 USBMUX INTERFACE CLAIM CONFIRMED** | Same-handle active5, unique config5 ID1/alt0/04/85 selection, claimtrue with forcefalse, stable active5/device state, release success and connection cleanup success. |
| Preflight failed - CLAIM NOT ATTEMPTED | Wrong identity/permission/configuration/layout, read/open failure, ambiguity or cancellation before claim. |
| USBMUX INTERFACE CLAIM FAILED | Claimfalse/exception; no fallback/retry; cleanup recorded. |
| Claim result inconclusive/cleanup failed | Device/configuration changed, final verification unavailable, releasefalse/exception or close failure. Retain actual claim result, do not publish clean PASS. |

PASS means Android acquired and released **active interface1** using the
config5-scoped binding contract. It is **not USBMUX handshake success**,
endpoint transfer success, Lockdown reachability, pairing or CarPlay.

### Required future tests

Inject inventory/read/claim/release/close only. Test duplicate flattened
candidates across3/4/5 with a unique config5 candidate; reorder arrays to prove
no index13 dependency; require active5; wrong/duplicate ID/alt/endpoints;
permission loss/stale identity; claimfalse/exception; exactly one forcefalse
claim and only successful-claim release; nested-finally close on release
failure; cancellation; no setter/vendor/alternate/bulk/UsbRequest/transport
references or automatic start. These are specifications, not tests added/run
during this audit.

## 8. Remaining uncertainties and validation

- Real-E01 claimfalse/true and release behavior remain untested.
- Tagged AOSP verifies API22 mapping; E01 OEM native deviations cannot be
  excluded without its framework/native implementation or a controlled test.
- Claim identifies interface number, not configuration/alternate at the ABI;
  same-handle active5 verification reduces but cannot atomically eliminate
  concurrent external configuration changes. Exclude other apps/services and
  active DiPlay USB diagnostics; detect detach/state changes and fail closed.
- Default alt0 for active USBMUX is consistent with the reported layout;
  claiming it does not independently measure active alternate.
- QDrive's recovered open2 auto-detach flag initialization is not reliable;
  do not inherit its conditional backend disconnect behavior.
- Phone Trust prompt cause/timing is unresolved; no response is authorized.
- Version2 traffic compatibility on E01 is not tested, and native QDrive's
  version1 acceptance differs from DiPlay. Claim PASS cannot close this gap.

Documentation-only validation: local native hash/control-flow/relocations
rechecked; tagged AOSP Java/JNI/host code examined; current selectors, I/O,
pairing and orchestration traced directly. No build, unit tests or APK change
was needed/performed. G runtime and disabled gates remain unchanged.

CONFIGURATION-5 USBMUX CLAIM PATH FULLY IDENTIFIED — CONTROLLED CLAIM TEST CAN BE DESIGNED
